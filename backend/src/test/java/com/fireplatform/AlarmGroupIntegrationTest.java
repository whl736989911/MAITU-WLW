package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.alarm.AlarmLifecycle;
import com.fireplatform.alarmgroup.AlarmMergePolicyQueries;
import com.fireplatform.alarmgroup.AlarmSessionNotificationGate;
import com.fireplatform.alarmgroup.AlarmSessionQueries;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.notification.*;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.transport.AliyunNotificationClient;
import com.fireplatform.transport.NotificationSpool;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.fireplatform.IngestionIntegrationTest.INITIAL;
import static com.fireplatform.IngestionIntegrationTest.PASSWORD;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:alarm-group;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class AlarmGroupIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }
    @Autowired MockMvc mvc; @Autowired com.fasterxml.jackson.databind.ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser;
    @Autowired AlarmLifecycle lifecycle; @Autowired AlarmSessionNotificationGate gate; @Autowired AlarmSessionQueries sessionQueries;
    @Autowired AlarmMergePolicyQueries policies; @Autowired NotificationProviderAttemptService attempts; @Autowired NotificationProviderService providerService;
    @Autowired AlarmBaseConfigurationQueries baseConfiguration; @Autowired NotificationChannelPolicyService channelPolicies; @Autowired DatabaseTime time;
    IngestionIntegrationTest f; TransactionTemplate tx; String root;
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("alarm_notification_claims","alarm_session_events","alarm_session_members","alarm_sessions","alarm_merge_policy_revisions","alarm_merge_policies","notification_provider_attempts","notification_rule_receivers","notification_rules","notification_contacts","notification_channel_policies")) jdbc.update("DELETE FROM "+table);
        f=new IngestionIntegrationTest(); f.mvc=mvc; f.json=json; f.jdbc=jdbc; f.bootstrap=bootstrap; f.parser=parser; f.reset(); root=f.root;
        tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    String fire(String tenant,String device,OffsetDateTime at) {
        return tx.execute(status->{jdbc.queryForObject("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE",String.class,tenant,device);return lifecycle.fire(tenant,device,"Fixture device","Fixture address",at);});
    }
    boolean recover(String tenant,String device,String alarm,OffsetDateTime at) {
        return Boolean.TRUE.equals(tx.execute(status->lifecycle.recoverAlarm(tenant,device,alarm,at)));
    }
    JsonNode putPolicy(String tenant,boolean enabled,Integer window,long expected,String key,ResultMatcher expectedStatus) throws Exception {
        var body=new LinkedHashMap<String,Object>();
        body.put("expected_revision",expected);body.put("enabled",enabled);body.put("window_seconds",window);body.put("reason","Explicit alarm grouping window");
        return f.body(f.call(HttpMethod.PUT,"/api/management/alarm-merge-policy",root,tenant,key,body).andExpect(expectedStatus));
    }
    JsonNode putPolicy(String tenant,boolean enabled,Integer window,long expected,String key) throws Exception { return putPolicy(tenant,enabled,window,expected,key,status().isOk()); }
    String customerToken() throws Exception {
        String token=f.login("customer-a",INITIAL,"customer");
        f.call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        return f.login("customer-a",PASSWORD,"customer");
    }
    void notificationFixtures() {
        jdbc.update("INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,enabled,revision,created_at) VALUES ('contact-1','tenant-a','Duty contact','FIRE_RESPONSIBLE','cipher','nonce','138****0000',?,TRUE,0,CURRENT_TIMESTAMP)","a".repeat(64));
        jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,created_at) VALUES ('rule-1','tenant-a','Unit real rule',TRUE,'FIRE',NULL,0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES ('tenant-a','rule-1','contact-1',1,'SMS'),('tenant-a','rule-1','contact-1',1,'VOICE')");
        jdbc.update("INSERT INTO notification_channel_policies(id,tenant_id,scope_type,scope_key,place_id,sms_enabled,voice_enabled,revision,changed_at) VALUES ('policy-a','tenant-a','UNIT','tenant-a',NULL,TRUE,TRUE,1,CURRENT_TIMESTAMP)");
    }
    RealNotificationService worker(Path spool,AliyunNotificationClient client) {
        var phones=mock(ContactPhoneStorage.class);
        when(phones.reveal(anyString(),anyString(),anyString())).thenReturn("13800001234");
        when(phones.normalize(anyString())).thenCallRealMethod();
        var resources=mock(NotificationResourceService.class);
        when(resources.reserve(anyString(),anyString(),anyString())).thenAnswer(inv->new NotificationResourceService.Reservation("test-account",inv.getArgument(2),inv.getArgument(0)));
        var suppressions=mock(SuppressionCommands.class);
        when(suppressions.blocked(anyString(),anyString(),anyBoolean(),anyString())).thenReturn(false);
        var provider=mock(NotificationProviderService.class);
        return new RealNotificationService(jdbc,phones,client,resources,suppressions,attempts,provider,baseConfiguration,channelPolicies,time,new NotificationSpool(spool.toString()),true,gate,mock(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.class),NotificationIntegrationTest.notificationRuntime());
    }
    AliyunNotificationClient successClient() {
        var client=mock(AliyunNotificationClient.class);
        when(client.enabled()).thenReturn(true);when(client.voiceReady()).thenReturn(true);when(client.accountFingerprint()).thenReturn("account-a");
        when(client.sendSms(anyString(),anyMap(),anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "provider-sms","ACCEPTED","accepted"));
        when(client.sendVoice(anyString(),anyMap(),anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "provider-voice","ACCEPTED","accepted"));
        return client;
    }

    @Test void physicalRecoveryAndReviewRemainPerAlarmWhileSessionMembershipStaysIndependent() throws Exception {
        putPolicy("tenant-a",true,60,0,"policy-on");
        OffsetDateTime base=time.now().minusSeconds(30);
        String a=fire("tenant-a","device-a",base),b=fire("tenant-a","device-a",base.plusSeconds(5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_sessions",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members",Long.class)).isEqualTo(2);
        f.call(HttpMethod.POST,"/api/management/alarms/"+a+"/review",root,"tenant-a","independent-review",Map.of("expected_revision",0,"conclusion","TEST","remark","review before recovery")).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT physical_state,response_state FROM alarms WHERE id=?",a))
            .containsEntry("physical_state","FIRE").containsEntry("response_state","HANDLED");
        assertThat(recover("tenant-a","device-a",a,base.plusSeconds(6))).isTrue();
        assertThat(jdbc.queryForMap("SELECT physical_state,response_state,conclusion FROM alarms WHERE id=?",a))
            .containsEntry("physical_state","NORMAL").containsEntry("response_state","HANDLED").containsEntry("conclusion","TEST");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM alarms WHERE id=?",String.class,b)).isEqualTo("FIRE");
        var session=jdbc.queryForMap("SELECT * FROM alarm_sessions");
        assertThat(session.get("open_slot")).isEqualTo("OPEN");
        var detail=sessionQueries.session("bootstrap-admin","tenant-a",String.valueOf(session.get("id")));
        assertThat(detail.session().memberCount()).isEqualTo(2);
        assertThat(detail.session().activeMemberCount()).isEqualTo(1);
        assertThat(detail.session().unhandledMemberCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_events WHERE event_kind='MEMBER_RECOVERED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT response_state FROM alarms WHERE id=?",String.class,b)).isEqualTo("UNHANDLED");
        assertThat(recover("tenant-a","device-a",b,base.plusSeconds(7))).isTrue();
        assertThat(jdbc.queryForMap("SELECT physical_state,response_state FROM alarms WHERE id=?",b)).containsEntry("physical_state","NORMAL").containsEntry("response_state","UNHANDLED");
        String next=fire("tenant-a","device-a",base.plusSeconds(8));
        var original=gate.sessionFor("tenant-a",a);
        assertThat(gate.sessionFor("tenant-a",b).id()).isEqualTo(original.id());
        assertThat(gate.sessionFor("tenant-a",next).id()).isNotEqualTo(original.id());
        assertThat(sessionQueries.session("bootstrap-admin","tenant-a",original.id()).session().activeMemberCount()).isZero();
    }

    @Test void capturedPolicyRevisionsControlFutureGroupingOnly() throws Exception {
        var first=putPolicy("tenant-a",true,60,0,"policy-1");
        OffsetDateTime base=time.now().minusSeconds(30);
        String a=fire("tenant-a","device-a",base);
        var second=putPolicy("tenant-a",true,3600,1,"policy-2");
        String b=fire("tenant-a","device-a",base.plusSeconds(5)),c=fire("tenant-a","device-a",base.plusSeconds(10));
        var sessions=jdbc.queryForList("SELECT * FROM alarm_sessions ORDER BY policy_revision");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).get("policy_revision")).isEqualTo(1L);
        assertThat(sessions.get(0).get("window_seconds")).isEqualTo(60);
        assertThat(sessions.get(0).get("closed_at")).isNotNull();
        assertThat(sessions.get(1).get("policy_revision")).isEqualTo(2L);
        assertThat(sessions.get(1).get("window_seconds")).isEqualTo(3600);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members WHERE session_id=?",Long.class,sessions.get(1).get("id"))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members WHERE alarm_id=?",Long.class,a)).isEqualTo(1);
        putPolicy("tenant-a",false,null,2,"policy-off");
        String d=fire("tenant-a","device-a",base.plusSeconds(15));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_sessions",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members WHERE alarm_id=?",Long.class,d)).isZero();
        var history=putPolicy("tenant-a",true,120,3,"policy-3");
        assertThat(history.get("revision").asLong()).isEqualTo(4);
        assertThat(policies.history("tenant-a")).hasSize(4);
        assertThat(policies.history("tenant-a").get(0).windowSeconds()).isEqualTo(120);
        assertThat(first.get("revision").asLong()).isEqualTo(1);
        assertThat(second.get("revision").asLong()).isEqualTo(2);
    }

    @Test void concurrentGroupingSerializesToOneOpenSessionWithoutCreatingDuplicateMembers() throws Exception {
        putPolicy("tenant-a",true,60,0,"policy-race");
        OffsetDateTime base=time.now().minusSeconds(30);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("start latch timeout");return fire("tenant-a","device-a",base);});
            var second=pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("start latch timeout");return fire("tenant-a","device-a",base.plusSeconds(1));});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isNotBlank();
            assertThat(second.get(15,TimeUnit.SECONDS)).isNotBlank();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_sessions",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_sessions WHERE open_slot='OPEN'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT alarm_id) FROM alarm_session_members",Long.class)).isEqualTo(2);
    }

    @Test void recoveredFirstMemberDoesNotSuppressActiveRemainingMemberAndAcceptedOutcomeNeverResends(@TempDir Path spool) throws Exception {
        notificationFixtures();
        putPolicy("tenant-a",true,60,0,"policy-notify");
        OffsetDateTime base=time.now().minusSeconds(30);
        String a=fire("tenant-a","device-a",base),b=fire("tenant-a","device-a",base.plusSeconds(5));
        assertThat(recover("tenant-a","device-a",a,base.plusSeconds(6))).isTrue();
        var client=successClient();new NotificationSpool(spool.toString()).writeCursor(base.minusSeconds(1));
        var worker=worker(spool,client);worker.poll();
        verify(client,times(1)).sendSms(anyString(),anyMap(),anyString());
        verify(client,times(1)).sendVoice(anyString(),anyMap(),anyString());
        var claims=jdbc.queryForList("SELECT * FROM alarm_notification_claims ORDER BY channel");
        assertThat(claims).hasSize(2);
        for(var claim:claims){assertThat(claim.get("status")).isEqualTo("SENT");assertThat(claim.get("alarm_id")).isEqualTo(b);}
        assertThat(jdbc.queryForList("SELECT source_id FROM notification_provider_attempts",String.class)).containsOnly(b);
        String c=fire("tenant-a","device-a",base.plusSeconds(10));worker.poll();
        verify(client,times(1)).sendSms(anyString(),anyMap(),anyString());
        verify(client,times(1)).sendVoice(anyString(),anyMap(),anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members WHERE alarm_id=?",Long.class,c)).isEqualTo(1);
    }

    @Test void unknownProviderOutcomeBlocksLaterMembersFromBlindRetry(@TempDir Path spool) throws Exception {
        notificationFixtures();
        putPolicy("tenant-a",true,60,0,"policy-unknown");
        OffsetDateTime base=time.now().minusSeconds(30);
        String a=fire("tenant-a","device-a",base),b=fire("tenant-a","device-a",base.plusSeconds(5));
        assertThat(recover("tenant-a","device-a",a,base.plusSeconds(6))).isTrue();
        var client=mock(AliyunNotificationClient.class);
        when(client.enabled()).thenReturn(true);when(client.voiceReady()).thenReturn(true);when(client.accountFingerprint()).thenReturn("account-a");
        when(client.sendSms(anyString(),anyMap(),anyString())).thenThrow(new RuntimeException("uncertain"));
        when(client.sendVoice(anyString(),anyMap(),anyString())).thenThrow(new RuntimeException("uncertain"));
        new NotificationSpool(spool.toString()).writeCursor(base.minusSeconds(1));
        var worker=worker(spool,client);worker.poll();
        verify(client,times(1)).sendSms(anyString(),anyMap(),anyString());
        verify(client,times(1)).sendVoice(anyString(),anyMap(),anyString());
        assertThat(jdbc.queryForList("SELECT status FROM alarm_notification_claims",String.class)).containsOnly("UNKNOWN");
        String originalRule=jdbc.queryForObject("SELECT id FROM notification_rules WHERE tenant_id='tenant-a'",String.class);
        jdbc.update("UPDATE notification_rules SET enabled=FALSE WHERE id=?",originalRule);
        jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,created_at) VALUES ('replacement-rule','tenant-a','Replacement',TRUE,'FIRE',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) "
            +"SELECT tenant_id,'replacement-rule',contact_id,2,channel FROM notification_rule_receivers WHERE rule_id=?",originalRule);
        String c=fire("tenant-a","device-a",base.plusSeconds(10));
        assertThat(recover("tenant-a","device-a",b,base.plusSeconds(11))).isTrue();
        worker.poll();
        verify(client,times(1)).sendSms(anyString(),anyMap(),anyString());
        verify(client,times(1)).sendVoice(anyString(),anyMap(),anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_notification_claims",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT rule_id FROM alarm_notification_claims",String.class)).containsExactly(originalRule);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members WHERE alarm_id=?",Long.class,c)).isEqualTo(1);
    }

    @Test void definiteFailuresUseOneBoundedSessionRetryBudgetAcrossMembers(@TempDir Path spool) throws Exception {
        notificationFixtures();
        putPolicy("tenant-a",true,60,0,"policy-retry");
        OffsetDateTime base=time.now().minusSeconds(30);
        fire("tenant-a","device-a",base);fire("tenant-a","device-a",base.plusSeconds(5));
        var client=mock(AliyunNotificationClient.class);
        when(client.enabled()).thenReturn(true);when(client.voiceReady()).thenReturn(true);when(client.accountFingerprint()).thenReturn("account-a");
        when(client.sendSms(anyString(),anyMap(),anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.REJECTED, null,"BUSINESS_LIMIT_CONTROL","limited"));
        when(client.sendVoice(anyString(),anyMap(),anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.REJECTED, null,"BUSINESS_LIMIT_CONTROL","limited"));
        new NotificationSpool(spool.toString()).writeCursor(base.minusSeconds(1));
        var worker=worker(spool,client);worker.poll();
        verify(client,times(NotificationProviderAttemptService.MAX_ATTEMPTS)).sendSms(anyString(),anyMap(),anyString());
        verify(client,times(NotificationProviderAttemptService.MAX_ATTEMPTS)).sendVoice(anyString(),anyMap(),anyString());
        var claims=jdbc.queryForList("SELECT * FROM alarm_notification_claims ORDER BY channel");
        assertThat(claims).hasSize(2);
        for(var claim:claims){assertThat(claim.get("status")).isEqualTo("FAILED");assertThat(((Number)claim.get("attempt_no")).intValue()).isEqualTo(NotificationProviderAttemptService.MAX_ATTEMPTS);}
        String c=fire("tenant-a","device-a",base.plusSeconds(10));worker.poll();
        verify(client,times(NotificationProviderAttemptService.MAX_ATTEMPTS)).sendSms(anyString(),anyMap(),anyString());
        verify(client,times(NotificationProviderAttemptService.MAX_ATTEMPTS)).sendVoice(anyString(),anyMap(),anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members WHERE alarm_id=?",Long.class,c)).isEqualTo(1);
    }

    @Test void sessionReadsHonorTenantAndCustomerScopeAndPolicyApiValidatesWindowAndRevision() throws Exception {
        putPolicy("tenant-a",true,60,0,"scope-policy-a");
        putPolicy("tenant-b",true,60,0,"scope-policy-b");
        OffsetDateTime base=time.now().minusSeconds(30);
        fire("tenant-a","device-a",base);fire("tenant-b","device-b",base);
        String sessionA=jdbc.queryForObject("SELECT id FROM alarm_sessions WHERE tenant_id='tenant-a'",String.class);
        String sessionB=jdbc.queryForObject("SELECT id FROM alarm_sessions WHERE tenant_id='tenant-b'",String.class);
        f.call(HttpMethod.GET,"/api/management/alarm-sessions",root,"tenant-a",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].tenant_id").value("tenant-a"));
        f.call(HttpMethod.GET,"/api/management/alarm-sessions",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        String customer=customerToken();
        f.call(HttpMethod.GET,"/api/customer/alarm-sessions",customer,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].tenant_id").value("tenant-a"));
        f.call(HttpMethod.GET,"/api/customer/alarm-sessions/"+sessionA,customer,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.session.id").value(sessionA));
        f.call(HttpMethod.GET,"/api/customer/alarm-sessions/"+sessionB,customer,null,null,null).andExpect(status().isNotFound());
        f.call(HttpMethod.GET,"/api/customer/alarm-sessions/"+sessionA+"/members",customer,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        f.call(HttpMethod.GET,"/api/management/alarm-sessions/"+sessionB,root,"tenant-a",null,null).andExpect(status().isNotFound());
        putPolicy("tenant-a",true,null,1,"invalid-window",status().isBadRequest());
        var replay=putPolicy("tenant-a",true,60,1,"scope-policy-a2");
        assertThat(replay.get("revision").asLong()).isEqualTo(2);
        putPolicy("tenant-a",true,30,1,"stale-window",status().isConflict());
        f.call(HttpMethod.GET,"/api/management/alarm-merge-policy/history",root,"tenant-a",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }
}
