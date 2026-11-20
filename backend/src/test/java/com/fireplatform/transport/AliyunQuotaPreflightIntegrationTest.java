package com.fireplatform.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.alarmgroup.AlarmSessionNotificationGate;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.notification.ContactPhoneStorage;
import com.fireplatform.notification.NotificationChannelPolicyService;
import com.fireplatform.notification.NotificationProviderAttemptService;
import com.fireplatform.notification.NotificationProviderSecretStorage;
import com.fireplatform.notification.NotificationProviderService;
import com.fireplatform.notification.NotificationResourceService;
import com.fireplatform.notification.RealNotificationService;
import com.fireplatform.notification.SuppressionCommands;
import com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.Digests;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "app.roles=all", "app.storage=h2-dev", "fire.notification.real-enabled=false",
    "app.notification-contact-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.notification-provider-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "spring.datasource.url=jdbc:h2:mem:aliyun-quota-preflight;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@SuppressWarnings({"unchecked", "rawtypes"})
class AliyunQuotaPreflightIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ContactPhoneStorage phones;
    @Autowired NotificationResourceService resources;
    @Autowired SuppressionCommands suppressions;
    @Autowired NotificationProviderAttemptService attempts;
    @Autowired ManagementGuard guard;
    @Autowired AuditService audit;
    @Autowired NotificationProviderSecretStorage secrets;
    @Autowired AlarmBaseConfigurationQueries baseConfiguration;
    @Autowired NotificationChannelPolicyService channelPolicies;
    @Autowired DatabaseTime time;
    @Autowired AlarmSessionNotificationGate merge;
    @Autowired WechatOfficialAccountChannel wechat;
    @Autowired RuntimeConfig runtime;

    @Test
    void legacyBlankFireNameNeverReservesQuotaOrClaimsASend(@TempDir Path spoolDir) throws Exception {
        OffsetDateTime occurred = time.now().minusMinutes(1);
        insertFire("blank-name", "", occurred);
        jdbc.update("""
            INSERT INTO alarm_sessions(id,tenant_id,device_id,device_name,address,event_type,group_by,policy_revision,
                window_seconds,first_source_at,last_source_at,open_slot,created_at,updated_at)
            VALUES ('merge-fixture','preflight','preflight-device','Legacy device','Fixture address','FIRE','DEVICE_EVENT_TYPE',
                1,300,?,?,'OPEN',?,?)
            """, occurred, occurred, occurred, occurred);
        jdbc.update("""
            INSERT INTO alarm_session_members(session_id,tenant_id,alarm_id,device_id,source_at,added_at)
            VALUES ('merge-fixture','preflight','blank-name','preflight-device',?,?)
            """, occurred, occurred);
        HttpClient http = mock(HttpClient.class);
        var client = client(http);
        var provider = configure(client, "{\"name\":\"device_name\"}", "{\"name\":\"device_name\"}", true, true);
        assertThat(client.enabled()).isTrue();
        assertThat(client.voiceReady()).isTrue();
        var worker = worker(client, provider, resources, spoolDir, occurred.minusMinutes(1));

        worker.dispatchInitialAlarm("preflight", "blank-name");

        verifyNoInteractions(http);
        for (String channel : new String[]{"SMS", "VOICE"}) {
            var marker = json.readTree(Files.readString(spoolDir.resolve("dead/blank-name__preflight-contact__" + channel + ".json")));
            assertThat(marker.path("status").asText()).isEqualTo("TEMPLATE_VARIABLE_MISSING");
        }
        assertThat(ledger()).as("a request that never left must not debit and refund").isEmpty();
        assertThat(jdbc.queryForMap("SELECT sms_used,voice_used FROM notification_resource_accounts WHERE id='preflight-credit'"))
            .containsAllEntriesOf(Map.of("sms_used", 0L, "voice_used", 0L));
        assertThat(jdbc.queryForList("SELECT status FROM notification_provider_attempts WHERE tenant_id='preflight'", String.class)).isEmpty();
        assertThat(jdbc.queryForList("SELECT status FROM alarm_notification_claims WHERE tenant_id='preflight'", String.class)).isEmpty();
    }

    @Test
    void rotationAfterDebitKeepsSmsSnapshotAndNextChannelUsesNewConfig(@TempDir Path spoolDir) throws Exception {
        var occurred = time.now().minusMinutes(1);
        insertFire("rotating-account", "Legacy device", occurred);
        HttpClient http = mock(HttpClient.class);
        var client = client(http);
        var provider = configure(client, "{\"name\":\"device_name\"}", "{}", true, true);
        var smsResponse = acceptedResponse("{\"Code\":\"OK\",\"BizId\":\"sms-id\"}");
        var voiceResponse = acceptedResponse("{\"Code\":\"OK\",\"CallId\":\"voice-id\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(smsResponse, voiceResponse);
        var rotatingResources = mock(NotificationResourceService.class);
        doAnswer(call -> {
            var reservation = resources.reserve(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            if ("SMS".equals(reservation.channel())) {
                client.apply(new AliyunNotificationClient.Config("rotated-ak", "rotated-secret", "Rotated", "SMS_ROTATED",
                    "TTS_ROTATED", "", "https://synthetic.invalid/sms", "https://synthetic.invalid/voice",
                    Map.of("where", "address"), Map.of("where", "address")));
            }
            return reservation;
        }).when(rotatingResources).reserve(any(), any(), any());

        var worker = worker(client, provider, rotatingResources, spoolDir, occurred.minusMinutes(1));
        worker.dispatchInitialAlarm("preflight", "rotating-account");
        worker.dispatchInitialAlarm("preflight", "rotating-account");

        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(captured.capture(), any(HttpResponse.BodyHandler.class));
        var sms = captured.getAllValues().get(0);
        var voice = captured.getAllValues().get(1);
        assertThat(sms.uri().getHost()).isEqualTo("dysmsapi.aliyuncs.com");
        assertThat(voice.uri().getPath()).isEqualTo("/voice");
        var smsParams = signedParameters(sms, "fixture-secret");
        var voiceParams = signedParameters(voice, "rotated-secret");
        assertThat(smsParams).containsEntry("AccessKeyId", "fixture-ak").containsEntry("SignName", "Fixture")
            .containsEntry("TemplateCode", "SMS_FIXTURE").containsEntry("TemplateParam", "{\"name\":\"Legacy device\"}");
        assertThat(voiceParams).containsEntry("AccessKeyId", "rotated-ak")
            .containsEntry("TtsCode", "TTS_ROTATED").containsEntry("TtsParam", "{\"where\":\"Fixture address\"}");
        var sent = jdbc.queryForList("""
            SELECT channel,status,provider_id,account_fingerprint,out_id
            FROM notification_provider_attempts WHERE source_id='rotating-account' ORDER BY channel
            """);
        assertThat(sent).containsExactly(
            Map.of("channel", "SMS", "status", "SENT", "provider_id", "sms-id",
                "account_fingerprint", Digests.sha256("fixture-ak"), "out_id", smsParams.get("OutId")),
            Map.of("channel", "VOICE", "status", "SENT", "provider_id", "voice-id",
                "account_fingerprint", Digests.sha256("rotated-ak"), "out_id", voiceParams.get("OutId")));
        assertThat(ledger()).containsExactly(
            Map.of("channel", "SMS", "kind", "CONSUME", "quantity", 1L),
            Map.of("channel", "VOICE", "kind", "CONSUME", "quantity", 1L));
        assertThat(jdbc.queryForMap("SELECT sms_used,voice_used FROM notification_resource_accounts WHERE id='preflight-credit'"))
            .containsAllEntriesOf(Map.of("sms_used", 1L, "voice_used", 1L));
    }

    @Test
    void enabledUnreadyChannelStaysPendingWhileDisabledChannelIsTerminal(@TempDir Path spoolDir) {
        var occurred = time.now().minusMinutes(1);
        insertFire("unready", "Legacy device", occurred);
        HttpClient http = mock(HttpClient.class);
        var client = client(http);
        var provider = configure(client, "{}", "{}", false, false);
        var worker = worker(client, provider, resources, spoolDir, occurred.minusMinutes(1));

        worker.dispatchInitialAlarm("preflight", "unready");

        verifyNoInteractions(http);
        assertThat(ledger()).isEmpty();
        assertThat(jdbc.queryForList("SELECT source_id FROM real_notification_completions WHERE tenant_id='preflight'", String.class)).isEmpty();
        assertThat(spoolDir.resolve("dead/unready__preflight-contact__SMS.json")).doesNotExist();
        assertThat(spoolDir.resolve("dead/unready__preflight-contact__VOICE.json")).exists();
        assertThat(jdbc.queryForList("SELECT channel,status,provider_code FROM notification_provider_attempts WHERE source_id='unready'"))
            .containsExactly(Map.of("channel", "VOICE", "status", "RESOURCE_UNAVAILABLE", "provider_code", "VOICE_PROVIDER_NOT_READY"));
    }

    private static HttpResponse<String> acceptedResponse(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static Map<String, String> signedParameters(HttpRequest request, String secret) throws Exception {
        var params = new TreeMap<String, String>();
        for (String pair : request.uri().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        String actualSignature = params.remove("Signature");
        String signed = "GET&%2F&" + AliyunNotificationClient.percent(AliyunNotificationClient.canonical(params));
        assertThat(actualSignature).isEqualTo(AliyunNotificationClient.hmac(secret + "&", signed));
        return params;
    }

    @BeforeEach
    void seedRecipientsAndCredit() {
        for (String table : List.of("real_notification_completions", "alarm_notification_claims", "alarm_session_members",
                "alarm_sessions", "notification_provider_attempts", "notification_resource_ledger", "notification_resource_accounts",
                "notification_rule_receivers", "notification_rules", "notification_contacts", "notification_channel_policies",
                "alarms", "devices")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id='preflight'");
        }
        jdbc.update("DELETE FROM tenants WHERE id='preflight'");
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('preflight','Quota preflight')");
        jdbc.update("""
            INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,integration_state)
            VALUES ('preflight-device','preflight','Legacy device','preflight-eui','fixture','Fixture address','FIRE','LEGACY_UNVERIFIED')
            """);
        var phone = phones.protect("preflight", "+8613800000000");
        jdbc.update("""
            INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,enabled,created_at)
            VALUES ('preflight-contact','preflight','Fixture recipient','DUTY_OFFICER',?,?,?,?,TRUE,CURRENT_TIMESTAMP)
            """, phone.cipher(), phone.nonce(), phone.masked(), phone.mac());
        jdbc.update("""
            INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,created_at,scope_type)
            VALUES ('preflight-rule','preflight','Fixture rule',TRUE,'FIRE',CURRENT_TIMESTAMP,'UNIT')
            """);
        jdbc.update("""
            INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES
            ('preflight','preflight-rule','preflight-contact',1,'SMS'),('preflight','preflight-rule','preflight-contact',1,'VOICE')
            """);
        jdbc.update("""
            INSERT INTO notification_channel_policies(id,tenant_id,scope_type,scope_key,sms_enabled,voice_enabled,changed_at)
            VALUES ('preflight-policy','preflight','UNIT','preflight',TRUE,TRUE,CURRENT_TIMESTAMP)
            """);
        jdbc.update("""
            INSERT INTO notification_resource_accounts(id,tenant_id,owner_type,owner_id,owner_name,sms_total,voice_total)
            VALUES ('preflight-credit','preflight','ORGANIZATION','preflight','Quota preflight',2,2)
            """);
    }

    private void insertFire(String id, String name, OffsetDateTime occurred) {
        jdbc.update("""
            INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,
                response_state,is_simulated,source_time_known,platform_recorded_at)
            VALUES (?,'preflight','preflight-device',?,'Fixture address','FIRE',?,'FIRE','UNHANDLED',FALSE,TRUE,CURRENT_TIMESTAMP)
            """, id, name, occurred);
    }

    private static AliyunNotificationClient client(HttpClient http) {
        return new AliyunNotificationClient(new AliyunNotificationClient.Config(null,null,null,null,null,null,null,null,null,null),
            http, Duration.ofSeconds(1));
    }

    private NotificationProviderService configure(AliyunNotificationClient client, String smsMapping, String voiceMapping,
            boolean voiceEnabled, boolean ready) {
        var secret = ready ? secrets.protect("fixture-secret") : null;
        jdbc.update("""
            UPDATE notification_provider_config SET sms_enabled=TRUE,voice_enabled=?,sms_sign_name='Fixture',
                sms_template_code='SMS_FIXTURE',tts_code='TTS_FIXTURE',sms_template_variables=?,voice_template_variables=?,
                access_key_id='fixture-ak',access_key_secret_cipher=?,access_key_secret_nonce=? WHERE id=1
            """, voiceEnabled, smsMapping, voiceMapping, secret == null ? null : secret.cipher(), secret == null ? null : secret.nonce());
        var provider = new NotificationProviderService(jdbc, guard, audit, secrets, client, phones, true);
        provider.loadConfiguredProvider();
        return provider;
    }

    private RealNotificationService worker(AliyunNotificationClient client, NotificationProviderService provider,
            NotificationResourceService resourceService, Path spoolDir, OffsetDateTime floor) {
        var spool = new NotificationSpool(spoolDir.toString());
        spool.writeCursor(floor);
        return new RealNotificationService(jdbc, phones, client, resourceService, suppressions, attempts, provider,
            baseConfiguration, channelPolicies, time, spool, true, merge, wechat, runtime);
    }

    private List<Map<String,Object>> ledger() {
        return jdbc.queryForList("SELECT channel,kind,quantity FROM notification_resource_ledger WHERE tenant_id='preflight' ORDER BY channel,kind");
    }
}
