package com.fireplatform;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.alarmgroup.AlarmSessionNotificationGate;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.notification.ContactPhoneStorage;
import com.fireplatform.notification.NotificationChannelPolicyService;
import com.fireplatform.notification.NotificationProviderAttemptService;
import com.fireplatform.notification.NotificationProviderService;
import com.fireplatform.notification.NotificationResourceService;
import com.fireplatform.notification.RealNotificationService;
import com.fireplatform.notification.SuppressionCommands;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.transport.AliyunNotificationClient;
import com.fireplatform.transport.NotificationSpool;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={
    "app.roles=all", "app.generic-ingest-enabled=true", "app.storage=h2-dev",
    "app.notification-contact-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "spring.datasource.url=jdbc:h2:mem:aliyun-ambiguous-refund;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class AliyunAmbiguousRefundRetryRegressionTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) {
        IngestionIntegrationTest.environment(r);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired ContactPhoneStorage phones;
    @Autowired DatabaseTime time;
    @Autowired NotificationChannelPolicyService channelPolicies;
    @Autowired NotificationProviderAttemptService attempts;
    @Autowired AlarmBaseConfigurationQueries baseConfiguration;
    @Autowired NotificationProviderService provider;
    @Autowired NotificationResourceService resources;

    @Test
    void ambiguousSubmissionRetainsOneReservationWithoutRetry(@TempDir Path temp) throws Exception {
        NotificationIntegrationTest notifications = new NotificationIntegrationTest();
        notifications.mvc = mvc;
        notifications.json = json;
        notifications.jdbc = jdbc;
        notifications.bootstrap = bootstrap;
        notifications.parser = parser;
        notifications.phones = phones;
        notifications.time = time;
        notifications.channelPolicies = channelPolicies;
        notifications.providerAttempts = attempts;
        notifications.baseConfiguration = baseConfiguration;
        notifications.providerService = provider;
        notifications.realResources = resources;
        notifications.reset();
        notifications.initialNotificationFixtures();
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES "
            + "('notify-root','tenant-a','Root',NULL),('notify-leaf','tenant-a','Leaf','notify-root')");
        jdbc.update("UPDATE devices SET place_id='notify-leaf' WHERE id='device-a' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO notification_resource_accounts(id,tenant_id,owner_type,owner_id,owner_name,sms_total) "
            + "VALUES ('notify-root-account','tenant-a','PLACE','notify-root','Root',1)");
        String alarm = notifications.initialNotificationEvent("FIRE", time.now().minusMinutes(1));

        AtomicInteger smsSubmissions = new AtomicInteger();
        AtomicInteger voiceSubmissions = new AtomicInteger();
        AliyunNotificationClient client = mock(AliyunNotificationClient.class);
        var sms = mock(AliyunNotificationClient.PreparedSend.class);
        var voice = mock(AliyunNotificationClient.PreparedSend.class);
        when(client.prepareSms(anyMap())).thenReturn(sms);
        when(client.prepareVoice(anyMap())).thenReturn(voice);
        when(sms.send(anyString(), anyString())).thenAnswer(invocation -> {
            smsSubmissions.incrementAndGet();
            // The concrete adapter outcome is covered by AliyunSubmissionOutcomeTest.
            return new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.UNKNOWN, null, "PROVIDER_RESULT_UNKNOWN", "提交后响应丢失");
        });
        when(voice.send(anyString(), anyString())).thenAnswer(invocation -> {
            voiceSubmissions.incrementAndGet();
            return new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.UNKNOWN, null, "PROVIDER_RESULT_UNKNOWN", "提交后响应丢失");
        });

        String spool = temp.resolve("spool").toString();
        new NotificationSpool(spool).writeCursor(time.now().minusMinutes(10));
        RealNotificationService worker = new RealNotificationService(
            jdbc, phones, client, resources, mock(SuppressionCommands.class), attempts, provider,
            baseConfiguration, channelPolicies, time, new NotificationSpool(spool), true,
            mock(AlarmSessionNotificationGate.class),
            mock(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.class),
            NotificationIntegrationTest.notificationRuntime());

        worker.dispatchInitialAlarm("tenant-a", alarm);
        worker.poll();

        var smsAccount = jdbc.queryForMap(
            "SELECT sms_used,voice_used FROM notification_resource_accounts WHERE id='notify-root-account'");
        var unitAccount = jdbc.queryForMap(
            "SELECT sms_used,voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a' AND owner_type='ORGANIZATION'");
        long consumes = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND kind='CONSUME'", Long.class);
        long refunds = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND kind='REFUND'", Long.class);

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(smsSubmissions.get()).as("SMS submissions for one ambiguous event").isEqualTo(1);
        soft.assertThat(voiceSubmissions.get()).as("VOICE submissions for one ambiguous event").isEqualTo(1);
        soft.assertThat(smsAccount).as("only SMS was debited from the funded ancestor")
            .containsEntry("sms_used", 1L).containsEntry("voice_used", 0L);
        soft.assertThat(unitAccount).as("only voice fell back to the unit")
            .containsEntry("sms_used", 0L).containsEntry("voice_used", 1L);
        soft.assertThat(jdbc.queryForList(
            "SELECT status FROM notification_provider_attempts WHERE tenant_id='tenant-a' AND source_id=?", String.class, alarm))
            .containsExactly("UNKNOWN", "UNKNOWN");
        soft.assertThat(consumes).as("one retained CONSUME per channel").isEqualTo(2L);
        soft.assertThat(refunds).as("ambiguous submissions must never be refunded").isZero();
        soft.assertAll();
    }
}
