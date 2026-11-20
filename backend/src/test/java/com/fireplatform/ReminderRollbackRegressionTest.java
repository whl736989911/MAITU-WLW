package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.notification.AlarmReminderScheduler;
import com.fireplatform.notification.ContactPhoneStorage;
import com.fireplatform.notification.NotificationChannelPolicyService;
import com.fireplatform.notification.NotificationProviderAttemptService;
import com.fireplatform.notification.NotificationProviderService;
import com.fireplatform.notification.NotificationResourceService;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.transport.AliyunNotificationClient;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={
    "app.roles=all", "app.generic-ingest-enabled=true", "app.storage=h2-dev",
    "app.notification-contact-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "fire.notification.real-enabled=true",
    "fire.notification.real-initial-delay-ms=3600000", "fire.notification.real-poll-ms=3600000",
    "fire.alarm-reminder-initial-delay-ms=3600000", "fire.alarm-reminder-scan-ms=3600000",
    "spring.datasource.url=jdbc:h2:mem:reminder-rollback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class ReminderRollbackRegressionTest {
    private static final String SPOOL =
        Path.of(System.getProperty("java.io.tmpdir"), "reminder-rollback-" + UUID.randomUUID()).toString();

    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) {
        IngestionIntegrationTest.environment(r);
        r.add("fire.notification.real-spool-dir", () -> SPOOL);
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
    @Autowired AlarmReminderScheduler scheduler;
    @MockitoBean AliyunNotificationClient client;

    @BeforeEach
    void stubAcceptedProvider() {
        when(client.enabled()).thenReturn(true);
        when(client.voiceReady()).thenReturn(true);
        when(client.sendSms(anyString(), anyMap(), anyString()))
            .thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "sms-accepted", "OK", "accepted"));
        when(client.sendVoice(anyString(), anyMap(), anyString()))
            .thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "voice-accepted", "OK", "accepted"));
    }

    private NotificationIntegrationTest reminderFixtures() throws Exception {
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
        return notifications;
    }

    private String dueAlarm() throws Exception {
        var notifications=reminderFixtures();

        OffsetDateTime now = time.now();
        String alarm = notifications.initialNotificationEvent("FIRE", now.minusHours(2));
        // One SMS unit available, zero VOICE units: SMS submits successfully, VOICE reservation throws.
        jdbc.update("UPDATE notification_resource_accounts SET sms_total=1, sms_used=0, voice_total=1, voice_used=1 WHERE tenant_id='tenant-a'");
        jdbc.update("INSERT INTO alarm_base_configurations(tenant_id,config_json,revision,updated_by,updated_at) "
            + "VALUES ('tenant-a','{\"pushRules\":{\"periodHours\":1}}',1,'bootstrap-admin',?)", now);
        jdbc.update("INSERT INTO alarm_reminder_schedule(tenant_id,alarm_id,cycle_number,period_hours,cycle_started_at,"
            + "next_due_at,last_accepted_at,status,revision,updated_at) VALUES ('tenant-a',?,1,1,?,?,NULL,'WAITING',1,?)",
            alarm, now.minusHours(2), now.minusMinutes(1), now);
        return alarm;
    }

    @Test
    void laterVoiceResourceFailureMustPreserveAcceptedSmsDebit() throws Exception {
        String alarm = dueAlarm();

        Throwable failure = catchThrowable(() -> scheduler.processDue());

        long smsUsed = jdbc.queryForObject(
            "SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'", Long.class);
        long consumes = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND channel='SMS' AND kind='CONSUME'", Long.class);
        long sent = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND channel='SMS' AND status='SENT'", Long.class, alarm);
        long voiceSent = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND channel='VOICE' AND status='SENT'", Long.class, alarm);

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(smsUsed).as("accepted SMS keeps its debit; rollback=%s", failure).isEqualTo(1L);
        soft.assertThat(consumes).as("accepted SMS keeps one CONSUME ledger row").isEqualTo(1L);
        soft.assertThat(sent).as("REQUIRES_NEW SENT attempt proves the external submission was accepted").isEqualTo(1L);
        soft.assertThat(voiceSent).as("VOICE reservation failed before any VOICE provider acceptance").isZero();
        soft.assertThat(failure).as("a caught resource-unavailable outcome must not poison the reminder transaction").isNull();
        soft.assertAll();

        // The external send happened exactly once; VOICE was never submitted to the provider.
        verify(client, org.mockito.Mockito.times(1)).sendSms(anyString(), anyMap(), anyString());
        verify(client, never()).sendVoice(anyString(), anyMap(), anyString());

        // The failed reservation gets bounded retries; after those, the SMS acceptance still counts.
        assertThat(jdbc.queryForObject("SELECT cycle_number FROM alarm_reminder_schedule WHERE alarm_id=?",Long.class,alarm)).isEqualTo(1L);
        assertThat(scheduler.processDue()).isZero();
        assertThat(scheduler.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cycle_number FROM alarm_reminder_schedule WHERE alarm_id=?",Long.class,alarm)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT last_accepted_at FROM alarm_reminder_schedule WHERE alarm_id=?",OffsetDateTime.class,alarm))
            .isEqualTo(jdbc.queryForObject("SELECT finished_at FROM notification_provider_attempts WHERE source_id=? AND channel='SMS' AND status='SENT'",OffsetDateTime.class,alarm));
        assertThat(scheduler.processDue()).isZero();
    }

    @Test
    void ambiguousReminderIsNotResubmittedOrRecordedAsAccepted() throws Exception {
        String alarm = dueAlarm();
        when(client.voiceReady()).thenReturn(false);
        when(client.sendSms(anyString(), anyMap(), anyString())).thenReturn(
            new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.UNKNOWN, null, "NO_REPLY", "unknown"));

        assertThat(scheduler.processDue()).isZero();
        assertThat(scheduler.processDue()).isZero();

        assertThat(jdbc.queryForObject("SELECT cycle_number FROM alarm_reminder_schedule WHERE alarm_id=?",Long.class,alarm)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT last_accepted_at FROM alarm_reminder_schedule WHERE alarm_id=?",OffsetDateTime.class,alarm)).isNull();
        assertThat(jdbc.queryForList("SELECT status FROM notification_provider_attempts WHERE source_id=? AND channel='SMS'",String.class,alarm))
            .containsExactly("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1L);
        verify(client, org.mockito.Mockito.times(1)).sendSms(anyString(), anyMap(), anyString());
    }

    @Test
    void reminderScanReachesReadyChannelBeyondFullPendingPageAndResumesSameCycles() throws Exception {
        var notifications=reminderFixtures();
        notifications.addReadyVoiceOnlyDevice();
        jdbc.update("UPDATE notification_resource_accounts SET sms_total=150,voice_total=150 WHERE tenant_id='tenant-a'");
        when(client.enabled()).thenReturn(false);
        jdbc.update("UPDATE notification_provider_config SET sms_enabled=TRUE,sms_template_variables=NULL WHERE id=1");
        try {
            var now=time.now();
            jdbc.update("INSERT INTO alarm_base_configurations(tenant_id,config_json,revision,updated_by,updated_at) "
                + "VALUES ('tenant-a','{\"pushRules\":{\"periodHours\":1}}',1,'bootstrap-admin',?)",now);
            for (int i=0;i<100;i++) {
                String alarm=notifications.initialNotificationEvent("FIRE",now.minusHours(2));
                seedDueReminder(alarm,now.minusMinutes(2),now);
            }
            String ready=notifications.initialNotificationEvent("FIRE",now.minusHours(1));
            jdbc.update("UPDATE alarms SET device_id='device-ready' WHERE id=?",ready);
            seedDueReminder(ready,now.minusMinutes(1),now);
            assertThat(scheduler.processDue()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_reminder_schedule WHERE cycle_number=1 AND last_accepted_at IS NULL",Long.class)).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isZero();

            assertThat(scheduler.processDue()).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT channel FROM notification_provider_attempts WHERE source_id=? AND occurrence_key='REMINDER:1' AND status='SENT'",String.class,ready))
                .containsExactly("VOICE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_reminder_schedule WHERE cycle_number=1 AND last_accepted_at IS NULL",Long.class)).isEqualTo(100);

            jdbc.update("UPDATE notification_provider_config SET sms_template_variables='{}' WHERE id=1");
            when(client.enabled()).thenReturn(true);
            assertThat(scheduler.processDue()).isEqualTo(100);
            assertThat(scheduler.processDue()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_reminder_schedule WHERE cycle_number=2 AND last_accepted_at IS NOT NULL",Long.class)).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE occurrence_key='REMINDER:1' AND status='SENT'",Long.class)).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts",Long.class)).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE kind='CONSUME' AND channel='SMS'",Long.class)).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE kind='CONSUME' AND channel='VOICE'",Long.class)).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE kind='REFUND'",Long.class)).isZero();
        } finally {
            jdbc.update("UPDATE notification_provider_config SET sms_enabled=FALSE,sms_template_variables=NULL WHERE id=1");
        }
    }

    private void seedDueReminder(String alarm,OffsetDateTime due,OffsetDateTime now) {
        jdbc.update("INSERT INTO alarm_reminder_schedule(tenant_id,alarm_id,cycle_number,period_hours,cycle_started_at,"
            + "next_due_at,last_accepted_at,status,revision,updated_at) VALUES ('tenant-a',?,1,1,?,?,NULL,'WAITING',1,?)",
            alarm,due,due,now);
    }
}
