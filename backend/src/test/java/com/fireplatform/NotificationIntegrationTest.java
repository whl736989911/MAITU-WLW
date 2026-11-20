package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.alarmgroup.AlarmSessionNotificationGate;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.notification.*;
import com.fireplatform.transport.AliyunNotificationClient;
import com.fireplatform.transport.NotificationSpool;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.shared.ApiError;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.fireplatform.IngestionIntegrationTest.PASSWORD;
import static com.fireplatform.IngestionIntegrationTest.INITIAL;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","app.notification-contact-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","spring.datasource.url=jdbc:h2:mem:notification;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class NotificationIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser; @Autowired ContactPhoneStorage phones; @Autowired DatabaseTime time; @Autowired NotificationChannelPolicyService channelPolicies; @Autowired NotificationProviderAttemptService providerAttempts; @Autowired com.fireplatform.configuration.AlarmBaseConfigurationQueries baseConfiguration; @Autowired NotificationProviderService providerService;
    @Autowired NotificationResourceService realResources;
    IngestionIntegrationTest f;
    @BeforeEach void reset()throws Exception{
        jdbc.update("DELETE FROM alarm_scoped_policies");
        clear(jdbc);f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();
        jdbc.update("INSERT INTO notification_channel_policies(id,tenant_id,scope_type,scope_key,sms_enabled,voice_enabled,revision,changed_at) VALUES ('test-policy-a','tenant-a','UNIT','tenant-a',TRUE,TRUE,1,CURRENT_TIMESTAMP)");
    }
    static void clear(JdbcTemplate jdbc){jdbc.update("DELETE FROM notification_provider_attempts");jdbc.update("DELETE FROM notification_resource_ledger");jdbc.update("DELETE FROM notification_resource_accounts");jdbc.update("DELETE FROM real_notification_completions");jdbc.update("DELETE FROM notification_channel_policies");for(String table:List.of("notification_rule_receivers","notification_rules","notification_templates","notification_contacts"))jdbc.update("DELETE FROM "+table);jdbc.update("UPDATE devices SET place_id=NULL WHERE place_id IN ('policy-parent','policy-child')");jdbc.update("DELETE FROM places WHERE id IN ('policy-child','policy-parent')");jdbc.update("UPDATE notification_contact_key_guard SET fingerprint=NULL WHERE id=1");}
    Map<String,Object> contactInput(String name,String phone,boolean enabled){var out=new HashMap<String,Object>();out.put("name",name);out.put("contact_type","FIRE_RESPONSIBLE");out.put("phone",phone);out.put("enabled",enabled);out.put("reason","Explicit contact fixture");return out;}
    ResultActions call(HttpMethod method,String path,String tenant,String key,Object body)throws Exception{return f.call(method,"/api/management"+path,f.root,tenant,key,body);}
    JsonNode contact(String name,String phone)throws Exception{return f.body(call(HttpMethod.POST,"/notification-contacts","tenant-a",UUID.randomUUID().toString(),contactInput(name,phone,true)).andExpect(status().isCreated()));}
    Map<String,Object> ruleInput(String name,List<Map<String,Object>> receivers,Integer smsInterval,Integer voiceInterval){var b=new HashMap<String,Object>();b.put("name",name);b.put("enabled",true);b.put("receivers",receivers);b.put("sms_escalation_interval_minutes",smsInterval);b.put("voice_escalation_interval_minutes",voiceInterval);b.put("reason","Integration test rule");return b;}
    JsonNode rule(List<JsonNode> contacts,int interval)throws Exception{var receivers=new ArrayList<Map<String,Object>>();for(int i=0;i<contacts.size();i++){receivers.add(Map.of("channel","SMS","contact_id",contacts.get(i).get("id").asText(),"level",i+1));receivers.add(Map.of("channel","VOICE","contact_id",contacts.get(i).get("id").asText(),"level",i+1));}return f.body(call(HttpMethod.POST,"/notification-rules","tenant-a",UUID.randomUUID().toString(),ruleInput("Unit real notification rule",receivers,interval,interval)).andExpect(status().isCreated()));}
    @Test void configRoundTripProtectsPhonesCanonicalDedupAndSafeHmacIdempotency()throws Exception{
        var input=contactInput("Private contact","13800001234",true);var c=f.body(call(HttpMethod.POST,"/notification-contacts","tenant-a","contact-once",input).andExpect(status().isCreated()));assertThat(c.get("phone_masked").asText()).isEqualTo("138****1234");assertThat(c.get("tenant_id").asText()).isEqualTo("tenant-a");assertThat(c.get("tenant_name").asText()).isEqualTo("Ingestion test tenant A");
        var detail=f.body(call(HttpMethod.GET,"/notification-contacts/"+c.get("id").asText(),"tenant-a",null,null).andExpect(status().isOk()));assertThat(detail.get("tenant_id").asText()).isEqualTo("tenant-a");assertThat(detail.get("tenant_name").asText()).isEqualTo("Ingestion test tenant A");
        input.put("phone","+8613800001234");assertThat(f.body(call(HttpMethod.POST,"/notification-contacts","tenant-a","contact-once",input).andExpect(status().isCreated()))).isEqualTo(c);
        call(HttpMethod.POST,"/notification-contacts","tenant-a","other-phone-key",input).andExpect(status().isConflict());var db=jdbc.queryForMap("SELECT * FROM notification_contacts WHERE id=?",c.get("id").asText());assertThat(db.get("phone_cipher").toString()).doesNotContain("13800001234");assertThat(db.get("phone_nonce").toString()).isNotBlank();assertThat(db.get("phone_mac").toString()).hasSize(64);assertThat(jdbc.queryForList("SELECT request_hash,response_json FROM idempotency_records").toString()).doesNotContain("13800001234").doesNotContain(PASSWORD);assertThat(jdbc.queryForList("SELECT * FROM audit_log").toString()).doesNotContain("13800001234").doesNotContain(PASSWORD);
        var edit=contactInput("Renamed private contact",null,true);edit.put("expected_revision",0);call(HttpMethod.PUT,"/notification-contacts/"+c.get("id").asText(),"tenant-a","contact-edit",edit).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));assertThat(jdbc.queryForObject("SELECT phone_cipher FROM notification_contacts WHERE id=?",String.class,c.get("id").asText())).isEqualTo(db.get("phone_cipher"));edit.put("phone","010-12345678");edit.put("expected_revision",1);call(HttpMethod.PUT,"/notification-contacts/"+c.get("id").asText(),"tenant-a","contact-landline",edit).andExpect(status().isOk()).andExpect(jsonPath("$.phone_masked").value("010-****5678"));
    }
    @Test void missingAndWrongKeyCannotOverwriteGuardOrLeakValuesButReadsRemainSafe()throws Exception{
        contact("Private","13800001234");String guard=jdbc.queryForObject("SELECT fingerprint FROM notification_contact_key_guard WHERE id=1",String.class);var missing=new ContactPhoneStorage(jdbc,new MockEnvironment());var wrong=new ContactPhoneStorage(jdbc,new MockEnvironment().withProperty("app.notification-contact-key","//////////////////////////////////////////8="));assertThat(missing.contactStorageReady()).isFalse();assertThat(wrong.contactStorageReady()).isFalse();var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));for(var storage:List.of(missing,wrong))assertThatThrownBy(()->tx.execute(s->{storage.requireReady();return null;})).isInstanceOf(ApiError.class).hasMessageNotContaining("13800001234");assertThat(jdbc.queryForObject("SELECT fingerprint FROM notification_contact_key_guard WHERE id=1",String.class)).isEqualTo(guard);call(HttpMethod.GET,"/notification-contacts","tenant-a",null,null).andExpect(status().isOk());
    }
    @Test void realRuleContractHidesLegacyExecutionFieldsAndValidatesIndependentChannelLevels()throws Exception{
        var first=contact("Receiver one","13800001234");var second=contact("Receiver two","13800001235");var receivers=List.<Map<String,Object>>of(Map.of("channel","SMS","contact_id",first.get("id").asText(),"level",1),Map.of("channel","VOICE","contact_id",first.get("id").asText(),"level",1));var input=ruleInput("Real provider rule",receivers,0,0);var created=f.body(call(HttpMethod.POST,"/notification-rules","tenant-a","real-rule-once",input).andExpect(status().isCreated()));assertThat(created.get("name").asText()).isEqualTo("Real provider rule");assertThat(created.at("/receivers/0/phone_masked").asText()).isEqualTo("138****1234");for(String legacy:List.of("legacy_mode","legacy_sms_template_id","legacy_voice_template_id","legacy_max_attempts","legacy_retry_delay_seconds","mode","sms_template_id","voice_template_id","max_attempts","retry_delay_seconds"))assertThat(created.has(legacy)).isFalse();assertThat(jdbc.queryForMap("SELECT legacy_mode,legacy_sms_template_id,legacy_voice_template_id,legacy_max_attempts,legacy_retry_delay_seconds FROM notification_rules WHERE id=?",created.get("id").asText())).containsEntry("legacy_mode",null).containsEntry("legacy_sms_template_id",null).containsEntry("legacy_voice_template_id",null).containsEntry("legacy_max_attempts",null).containsEntry("legacy_retry_delay_seconds",null);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_templates",Long.class)).isZero();
        assertThat(f.body(call(HttpMethod.POST,"/notification-rules","tenant-a","real-rule-once",input).andExpect(status().isCreated())).get("id").asText()).isEqualTo(created.get("id").asText());var edit=ruleInput("Updated provider rule",receivers,0,0);call(HttpMethod.PUT,"/notification-rules/"+created.get("id").asText(),"tenant-a","missing-rule-revision",edit).andExpect(status().isBadRequest());edit.put("expected_revision",0);call(HttpMethod.PUT,"/notification-rules/"+created.get("id").asText(),"tenant-a","real-rule-edit",edit).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));edit.put("expected_revision",0);call(HttpMethod.PUT,"/notification-rules/"+created.get("id").asText(),"tenant-a","stale-real-rule-edit",edit).andExpect(status().isConflict());
        var invalid=ruleInput("Invalid rule",List.of(Map.of("channel","SMS","contact_id",first.get("id").asText(),"level",2),Map.of("channel","VOICE","contact_id",first.get("id").asText(),"level",1)),0,0);call(HttpMethod.POST,"/notification-rules","tenant-a","level-gap",invalid).andExpect(status().isBadRequest());
        invalid=ruleInput("Invalid interval",List.of(Map.of("channel","SMS","contact_id",first.get("id").asText(),"level",1),Map.of("channel","SMS","contact_id",second.get("id").asText(),"level",2),Map.of("channel","VOICE","contact_id",first.get("id").asText(),"level",1),Map.of("channel","VOICE","contact_id",second.get("id").asText(),"level",2)),61,0);call(HttpMethod.POST,"/notification-rules","tenant-a","interval-too-large",invalid).andExpect(status().isBadRequest());
        invalid=ruleInput("Missing channel",List.of(Map.of("channel","SMS","contact_id",first.get("id").asText(),"level",1)),0,0);call(HttpMethod.POST,"/notification-rules","tenant-a","missing-voice",invalid).andExpect(status().isBadRequest());
        for(Object value:List.of("1",1.0,true)){var malformed=new HashMap<String,Object>();malformed.put("channel","SMS");malformed.put("contact_id",first.get("id").asText());malformed.put("level",value);invalid=ruleInput("Invalid level",List.of(malformed,Map.of("channel","VOICE","contact_id",first.get("id").asText(),"level",1)),0,0);call(HttpMethod.POST,"/notification-rules","tenant-a",UUID.randomUUID().toString(),invalid).andExpect(status().isBadRequest());}
    }
    @Test void reminderCycleProgressionUsesItsStableAnchorAndIndependentChannelIntervals(){
        var anchor=OffsetDateTime.parse("2026-10-23T07:00:00Z");
        var now=anchor.plusSeconds(119);
        assertThat(RealNotificationService.escalationDueAt(anchor,1,120)).isEqualTo(anchor);
        assertThat(RealNotificationService.escalationDueAt(anchor,2,120)).isAfter(now);
        assertThat(RealNotificationService.escalationDueAt(anchor,2,120)).isEqualTo(anchor.plusSeconds(120));
        assertThat(RealNotificationService.escalationDueAt(anchor,5,0)).isEqualTo(anchor);
        assertThat(RealNotificationService.escalationDueAt(anchor,2,120)).isNotEqualTo(RealNotificationService.escalationDueAt(anchor,2,0));
    }
    @Test void realReminderConsumerIsOccurrenceIdempotentAndSeparatesAcceptedCycles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var recipients=new ArrayList<JsonNode>();for(int i=0;i<5;i++)recipients.add(contact("Cycle receiver "+i,"1380000123"+i));
        var realRule=rule(recipients,0);
        var legacyOnly=contact("Historical rule recipient","13800001999");jdbc.update("INSERT INTO notification_templates(id,name,channel,mode,body,enabled,created_at) VALUES ('legacy-sms','Historical SMS','SMS','SIMULATION','legacy',TRUE,CURRENT_TIMESTAMP),('legacy-voice','Historical voice','VOICE','SIMULATION','legacy',TRUE,CURRENT_TIMESTAMP)");jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,legacy_mode,legacy_sms_template_id,legacy_voice_template_id,legacy_max_attempts,legacy_retry_delay_seconds,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,created_at) VALUES ('legacy-rule','tenant-a','Historical simulated rule',TRUE,'FIRE','SIMULATION','legacy-sms','legacy-voice',1,0,NULL,0,0,CURRENT_TIMESTAMP)");jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES ('tenant-a','legacy-rule',?,1,'SMS'),('tenant-a','legacy-rule',?,1,'VOICE')",legacyOnly.get("id").asText(),legacyOnly.get("id").asText());var listed=f.body(call(HttpMethod.GET,"/notification-rules","tenant-a",null,null));assertThat(listed.get("total").asLong()).isEqualTo(1);assertThat(listed.at("/items/0/id").asText()).isEqualTo(realRule.get("id").asText());assertThat(listed.toString()).doesNotContain("legacy_mode","legacy_sms_template_id","Historical simulated rule");
        String alarm=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,event_type,occurred_at,physical_state,response_state,conclusion,remark,revision,is_simulated,device_name,address) VALUES (?,'tenant-a','device-a','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',NULL,NULL,0,FALSE,'Reminder device','Reminder address')",alarm);
        var client=org.mockito.Mockito.mock(AliyunNotificationClient.class);
        org.mockito.Mockito.when(client.enabled()).thenReturn(true);
        org.mockito.Mockito.when(client.voiceReady()).thenReturn(true);
        org.mockito.Mockito.when(client.sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "provider-sms","ACCEPTED","accepted"));
        org.mockito.Mockito.when(client.sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "provider-voice","ACCEPTED","accepted"));
        var resources=org.mockito.Mockito.mock(NotificationResourceService.class);
        var suppression=org.mockito.Mockito.mock(SuppressionCommands.class);
        org.mockito.Mockito.when(suppression.blocked("tenant-a", "device-a", true, alarm)).thenReturn(false);
        var real=new RealNotificationService(jdbc,phones,client,resources,suppression,providerAttempts,providerService,baseConfiguration,channelPolicies,time,new NotificationSpool(spool.toString()),true,org.mockito.Mockito.mock(AlarmSessionNotificationGate.class),org.mockito.Mockito.mock(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.class),notificationRuntime());
        var anchor=time.now().minusSeconds(1);
        assertThat(real.runDueReminder("tenant-a",alarm,1,anchor).lastAcceptedAt()).isNotNull();
        assertThat(real.runDueReminder("tenant-a",alarm,1,anchor).lastAcceptedAt()).isNotNull();
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(5)).sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(5)).sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        assertThat(real.runDueReminder("tenant-a",alarm,2,time.now().minusSeconds(1)).lastAcceptedAt()).isNotNull();
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(10)).sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(10)).sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        assertThat(jdbc.queryForList("SELECT DISTINCT occurrence_key FROM notification_provider_attempts WHERE alarm_id=? ORDER BY occurrence_key",String.class,alarm)).containsExactly("REMINDER:1","REMINDER:2");
    }
    @Test void authorizedContactOverviewIncludesRealRowsFromEveryGrantedUnitAndTreeSelectionNarrows()throws Exception{
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-c','Unauthorized tenant')");
        for(var entry:List.of(Map.entry("tenant-a","contact-a"),Map.entry("tenant-b","contact-b"),Map.entry("tenant-c","contact-c"))){
            String tenant=entry.getKey();var phone=phones.protect(tenant,phones.normalize(tenant.equals("tenant-a")?"13800000001":tenant.equals("tenant-b")?"13800000002":"13800000003"));
            jdbc.update("INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,enabled,created_at) VALUES (?,?,?,'DUTY_OFFICER',?,?,?,?,TRUE,CURRENT_TIMESTAMP)",entry.getValue(),tenant,"synthetic-"+tenant,phone.cipher(),phone.nonce(),phone.masked(),phone.mac());
        }
        var all=f.body(f.call(HttpMethod.GET,"/api/management/notification-contacts?page=1",f.root,null,null,null).andExpect(status().isOk()));
        assertThat(all.get("items").findValuesAsText("id")).containsExactlyInAnyOrder("contact-a","contact-b");
        assertThat(all.toString()).doesNotContain("contact-c","13800000003");
        var onlyB=f.body(f.call(HttpMethod.GET,"/api/management/notification-contacts?page=1&scope_selected=true&scope_tenant_ids=tenant-b&scope_place_ids=",f.root,null,null,null).andExpect(status().isOk()));
        assertThat(onlyB.get("items").findValuesAsText("id")).containsExactly("contact-b");
        var cleared=f.body(f.call(HttpMethod.GET,"/api/management/notification-contacts?page=1&scope_selected=true&scope_tenant_ids=&scope_place_ids=",f.root,null,null,null).andExpect(status().isOk()));
        assertThat(cleared.get("items")).isEmpty();
    }
    @Test void customerUnitNotificationSelfServiceRequiresExplicitPermissionFullDeviceScopeAndCas()throws Exception{
        String customer=f.login("customer-a",INITIAL,"customer");f.call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());customer=f.login("customer-a",PASSWORD,"customer");
        f.call(HttpMethod.GET,"/api/customer/notification-settings",customer,null,null,null).andExpect(status().isForbidden());
        String customerId=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,'unit-notifications:read'),(?,'unit-notifications:write')",customerId,customerId);
        f.call(HttpMethod.GET,"/api/customer/notification-settings",customer,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.tenant_id").value("tenant-a"));
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id=? AND tenant_id='tenant-a'",customerId);
        f.call(HttpMethod.GET,"/api/customer/notification-settings/contacts?page=1",customer,null,null,null).andExpect(status().isForbidden());
        jdbc.update("UPDATE user_device_scopes SET mode='ALL_TENANT',revision=revision+1 WHERE user_id=? AND tenant_id='tenant-a'",customerId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_contacts WHERE tenant_id='tenant-a'",Long.class)).isZero();
        var created=f.body(f.call(HttpMethod.POST,"/api/customer/notification-settings/contacts",customer,null,"customer-contact",contactInput("Customer contact","13800001234",true)).andExpect(status().isCreated()));
        String contactId=created.get("id").asText();assertThat(created.get("phone_masked").asText()).isEqualTo("138****1234");assertThat(created.get("tenant_id").asText()).isEqualTo("tenant-a");assertThat(created.get("tenant_name").asText()).isEqualTo("Ingestion test tenant A");
        var listed=f.body(f.call(HttpMethod.GET,"/api/customer/notification-settings/contacts?page=1",customer,null,null,null).andExpect(status().isOk()));assertThat(listed.at("/items/0/tenant_id").asText()).isEqualTo("tenant-a");assertThat(listed.at("/items/0/tenant_name").asText()).isEqualTo("Ingestion test tenant A");
        var stored=jdbc.queryForMap("SELECT phone_cipher,phone_mac FROM notification_contacts WHERE tenant_id='tenant-a' AND id=?",contactId);assertThat(stored.get("phone_cipher").toString()).doesNotContain("13800001234");
        var foreignPhone=phones.protect("tenant-b",phones.normalize("13800001235"));jdbc.update("INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,enabled,created_at) VALUES ('foreign-contact','tenant-b','Foreign','DUTY_OFFICER',?,?,?,?,TRUE,CURRENT_TIMESTAMP)",foreignPhone.cipher(),foreignPhone.nonce(),foreignPhone.masked(),foreignPhone.mac());
        var body=ruleInput("Customer fault rule",List.of(Map.of("channel","SMS","contact_id",contactId,"level",1),Map.of("channel","VOICE","contact_id",contactId,"level",1)),0,0);body.put("event_scope","FAULT");
        var rule=f.body(f.call(HttpMethod.POST,"/api/customer/notification-settings/rules",customer,null,"customer-fault-rule",body).andExpect(status().isCreated()));assertThat(rule.get("event_scope").asText()).isEqualTo("FAULT");assertThat(rule.has("alarm_id")).isFalse();
        var foreign=ruleInput("Cross-unit",List.of(Map.of("channel","SMS","contact_id","foreign-contact","level",1),Map.of("channel","VOICE","contact_id","foreign-contact","level",1)),0,0);foreign.put("event_scope","FAULT");
        f.call(HttpMethod.POST,"/api/customer/notification-settings/rules",customer,null,"customer-cross-unit-rule",foreign).andExpect(status().isNotFound());
        body.put("expected_revision",99);f.call(HttpMethod.PUT,"/api/customer/notification-settings/rules/"+rule.get("id").asText(),customer,null,"customer-stale-rule",body).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_rules WHERE tenant_id='tenant-a' AND event_scope='FAULT'",Long.class)).isEqualTo(1);
    }
    void initialNotificationFixtures() throws Exception {
        var receiver=contact("Controlled notification receiver","13800001238");
        var receivers=List.<Map<String,Object>>of(Map.of("channel","SMS","contact_id",receiver.path("id").asText(),"level",1),Map.of("channel","VOICE","contact_id",receiver.path("id").asText(),"level",1));
        for(String kind:List.of("FIRE","FAULT")){
            var input=ruleInput(kind+" initial notification",receivers,0,0);input.put("event_scope",kind);
            call(HttpMethod.POST,"/notification-rules","tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isCreated());
        }
        for(String channel:List.of("SMS","VOICE"))call(HttpMethod.POST,"/notification-resources/recharge","tenant-a",UUID.randomUUID().toString(),Map.of("owner_type","ORGANIZATION","owner_id","tenant-a","channel",channel,"quantity",100,"reference","Isolated notification regression")).andExpect(status().isOk());
    }
    AliyunNotificationClient initialNotificationClient() throws Exception {
        initialNotificationFixtures();
        var client=org.mockito.Mockito.mock(AliyunNotificationClient.class);
        org.mockito.Mockito.when(client.enabled()).thenReturn(true);
        org.mockito.Mockito.when(client.voiceReady()).thenReturn(true);
        org.mockito.Mockito.when(client.sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "sms-id","OK","accepted"));
        org.mockito.Mockito.when(client.sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "voice-id","OK","accepted"));
        return client;
    }
    RealNotificationService initialNotificationWorker(AliyunNotificationClient client,java.nio.file.Path spool){
        return initialNotificationWorker(client,spool,time);
    }
    RealNotificationService initialNotificationWorker(AliyunNotificationClient client,java.nio.file.Path spool,DatabaseTime workerTime){
        return new RealNotificationService(jdbc,phones,client,realResources,org.mockito.Mockito.mock(SuppressionCommands.class),providerAttempts,providerService,baseConfiguration,channelPolicies,workerTime,new NotificationSpool(spool.toString()),true,org.mockito.Mockito.mock(AlarmSessionNotificationGate.class),org.mockito.Mockito.mock(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.class),notificationRuntime());
    }
    static com.fireplatform.configuration.RuntimeConfig notificationRuntime() {
        var environment=new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("app.roles","all");
        environment.setProperty("spring.datasource.url","jdbc:h2:mem:notification-runtime");
        return new com.fireplatform.configuration.RuntimeConfig(environment);
    }
    String initialNotificationEvent(String kind,OffsetDateTime occurred){
        String id=UUID.randomUUID().toString();
        if("FIRE".equals(kind))jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,is_simulated,source_time_known,platform_recorded_at) VALUES (?,'tenant-a','device-a','Late device','Address','FIRE',?,'FIRE','UNHANDLED',FALSE,TRUE,CURRENT_TIMESTAMP)",id,occurred);
        else{
            jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at,business_code) VALUES (?,'tenant-a','device-a','FAULT','FAULT','{}',?,CURRENT_TIMESTAMP,'LOW_BATTERY')",id,occurred);
            jdbc.update("INSERT INTO alarm_configuration_outcomes(id,tenant_id,source_kind,source_id,event_type,configuration_revision,outcome_kind,outcome,detail_json,occurred_at) VALUES (?,'tenant-a','LEGACY_FAULT',?,'LOW_BATTERY',1,'SUBSCRIPTION','SUBSCRIBED','{}',?)",UUID.randomUUID().toString(),id,occurred);
        }
        return id;
    }
    @Test void targetedInitialAlarmDispatchDoesNotWaitForPollAndFallbackDoesNotChargeTwice(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();var start=time.now().minusMinutes(10);var markers=new NotificationSpool(spool.toString());markers.writeCursor(start);
        String backlog=initialNotificationEvent("FIRE",start.plusMinutes(1));String target=initialNotificationEvent("FIRE",start.plusMinutes(2));
        var worker=initialNotificationWorker(client,spool);worker.dispatchInitialAlarm("tenant-a",target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,target)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=?",Long.class,backlog)).isZero();
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,target)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,backlog)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
    }
    @Test void freshWorkerBoundaryNotifiesStartupFiresImmediatelyAndFaultsOnTheFirstScan(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();
        // A real process captures the lower bound when the worker starts, before any source report or scan.
        var boundary=time.now().minusSeconds(1);
        var startupTime=org.mockito.Mockito.spy(time);
        org.mockito.Mockito.doReturn(boundary).when(startupTime).now();
        var worker=initialNotificationWorker(client,spool,startupTime);
        org.mockito.Mockito.doAnswer(call->time.now()).when(startupTime).now();
        String fire=initialNotificationEvent("FIRE",time.now());
        worker.dispatchInitialAlarm("tenant-a",fire);
        String fault=initialNotificationEvent("FAULT",time.now());
        worker.poll();
        for(String id:List.of(fire,fault))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND kind='CONSUME'",Long.class)).isEqualTo(4L);
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id IN (?,?)",Long.class,fire,fault)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
    }

    @Test void enabledUnreadySmsKeepsEligibleFireAndFaultPendingWithoutReplayingHistory(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path spool) throws Exception {
        var client=initialNotificationClient();
        org.mockito.Mockito.when(client.enabled()).thenReturn(false);
        jdbc.update("UPDATE notification_provider_config SET sms_enabled=TRUE,sms_template_variables=NULL WHERE id=1");
        try {
            var boundary=time.now().minusMinutes(10);
            new NotificationSpool(spool.toString()).writeCursor(boundary);
            String oldFire=initialNotificationEvent("FIRE",boundary.minusSeconds(1));
            String oldFault=initialNotificationEvent("FAULT",boundary.minusSeconds(1));
            String fire=initialNotificationEvent("FIRE",boundary.plusSeconds(1));
            String fault=initialNotificationEvent("FAULT",boundary.plusSeconds(2));
            String recovered=initialNotificationEvent("FIRE",boundary.plusSeconds(3));
            String completed=initialNotificationEvent("FIRE",boundary.plusSeconds(4));
            jdbc.update("INSERT INTO real_notification_completions(tenant_id,source_kind,source_id,completed_at) VALUES ('tenant-a','ALARM',?,CURRENT_TIMESTAMP)",completed);
            var worker=initialNotificationWorker(client,spool);
            worker.dispatchInitialAlarm("tenant-a",fire);
            worker.poll();
            assertThat(jdbc.queryForList("SELECT channel FROM notification_provider_attempts WHERE source_id IN (?,?,?)",String.class,fire,fault,recovered))
                .containsExactly("VOICE","VOICE","VOICE");
            assertThat(jdbc.queryForList("SELECT source_id FROM real_notification_completions",String.class)).containsExactly(completed);
            assertThat(jdbc.queryForList("SELECT channel FROM notification_resource_ledger WHERE kind IN ('CONSUME','REFUND')",String.class))
                .containsExactly("VOICE","VOICE","VOICE");

            jdbc.update("UPDATE alarms SET physical_state='NORMAL' WHERE id=?",recovered);
            jdbc.update("UPDATE notification_provider_config SET sms_template_variables='{}' WHERE id=1");
            org.mockito.Mockito.when(client.enabled()).thenReturn(true);
            var restarted=initialNotificationWorker(client,spool);
            restarted.poll();
            restarted.dispatchInitialAlarm("tenant-a",fire);
            restarted.poll();
            for (String source:List.of(fire,fault)) {
                assertThat(jdbc.queryForList("SELECT channel FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",String.class,source))
                    .containsExactlyInAnyOrder("SMS","VOICE");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id IN (?,?,?)",Long.class,oldFire,oldFault,completed)).isZero();
            assertThat(jdbc.queryForList("SELECT channel FROM notification_provider_attempts WHERE source_id=?",String.class,recovered))
                .containsExactly("VOICE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM real_notification_completions",Long.class)).isEqualTo(4);
            assertThat(jdbc.queryForList("SELECT channel FROM notification_resource_ledger WHERE kind='CONSUME'",String.class))
                .containsExactlyInAnyOrder("SMS","SMS","VOICE","VOICE","VOICE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE kind='REFUND'",Long.class)).isZero();
        } finally {
            jdbc.update("UPDATE notification_provider_config SET sms_enabled=FALSE,sms_template_variables=NULL WHERE id=1");
        }
    }

    @Test void unlocatedFireAndFaultUseUnknownLocationInBothVendorTemplatesWithoutChangingFacts(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path spool) throws Exception {
        initialNotificationFixtures();
        jdbc.update("UPDATE devices SET address='',place_id=NULL WHERE id='device-a'");
        var boundary=time.now().minusMinutes(10);
        new NotificationSpool(spool.toString()).writeCursor(boundary);
        String fire=initialNotificationEvent("FIRE",boundary.plusSeconds(1));
        jdbc.update("UPDATE alarms SET address='' WHERE id=?",fire);
        String fault=initialNotificationEvent("FAULT",boundary.plusSeconds(2));
        var requests=new CopyOnWriteArrayList<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String query=java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),java.nio.charset.StandardCharsets.UTF_8);
            requests.add(query);
            try(exchange) {
                byte[] reply=(query.contains("Action=SendSms")?"{\"Code\":\"OK\",\"BizId\":\"sms\"}":"{\"Code\":\"OK\",\"CallId\":\"voice\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        server.start();
        try {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/";
            var client=new AliyunNotificationClient("fixture","synthetic","fixture","SMS_TEST","TTS_TEST","",endpoint,endpoint,5000,8000);
            client.apply(new AliyunNotificationClient.Config("fixture","synthetic","fixture","SMS_TEST","TTS_TEST","",endpoint,endpoint,
                Map.of("smsLocation","address","kind","event"),Map.of("ttsLocation","address","kind","event")));
            var worker=initialNotificationWorker(client,spool);
            worker.dispatchInitialAlarm("tenant-a",fire);
            worker.poll();
            worker.poll();
            for (String kind:List.of("火灾报警","设备故障")) {
                assertThat(requests).anyMatch(query->query.contains("TemplateParam={\"kind\":\""+kind+"\",\"smsLocation\":\"位置未知\"}"));
                assertThat(requests).anyMatch(query->query.contains("TtsParam={\"kind\":\""+kind+"\",\"ttsLocation\":\"位置未知\"}"));
            }
            assertThat(requests).hasSize(4);
            assertThat(jdbc.queryForList("SELECT status FROM notification_provider_attempts WHERE source_id IN (?,?)",String.class,fire,fault))
                .containsExactly("SENT","SENT","SENT","SENT");
            assertThat(jdbc.queryForList("SELECT channel FROM notification_resource_ledger WHERE kind='CONSUME'",String.class))
                .containsExactlyInAnyOrder("SMS","SMS","VOICE","VOICE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE kind='REFUND'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT address FROM alarms WHERE id=?",String.class,fire)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT address FROM devices WHERE id='device-a'",String.class)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test void scopedRecipientRulesShareTheEffectivePreviewAndRealFireDispatch(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path spool) throws Exception {
        var client = initialNotificationClient();
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES "
            + "('notify-root','tenant-a','Root',NULL),('notify-leaf','tenant-a','Leaf','notify-root'),"
            + "('notify-sibling','tenant-a','Sibling','notify-root')");
        jdbc.update("UPDATE devices SET place_id='notify-leaf' WHERE tenant_id='tenant-a' AND id='device-a'");
        String unitContact = jdbc.queryForObject("SELECT id FROM notification_contacts WHERE tenant_id='tenant-a'", String.class);
        String parent = contact("Parent", "13800005381").path("id").asText();
        String leaf = contact("Leaf", "13800005382").path("id").asText();
        String unrelated = contact("Unrelated", "13800005383").path("id").asText();
        scopedSmsRule("PLACE", "notify-root", 0, Map.of(parent, 1));
        scopedSmsRule("PLACE", "notify-leaf", 0, Map.of(leaf, 1));
        scopedSmsRule("PLACE", "notify-sibling", 0, Map.of(unrelated, 1));
        String deviceRule = scopedSmsRule("DEVICE", "device-a", 10, Map.of(parent, 1, unitContact, 2));

        Map<String,JsonNode> sms = new HashMap<>();
        var preview = f.body(call(HttpMethod.GET, "/notification-rules/effective?deviceId=device-a&eventScope=FIRE",
            "tenant-a", null, null).andExpect(status().isOk()));
        preview.path("receivers").forEach(r -> { if ("SMS".equals(r.path("channel").asText())) sms.put(r.path("contact_id").asText(), r); });
        assertThat(sms.keySet()).containsExactlyInAnyOrder(unitContact, parent, leaf);
        assertThat(sms.get(unitContact).path("source_scope_type").asText()).isEqualTo("UNIT");
        assertThat(sms.get(unitContact).path("delay_seconds").asLong()).isZero();
        assertThat(sms.get(parent).path("source_rule_id").asText()).isEqualTo(deviceRule);
        assertThat(sms.get(leaf).path("source_scope_id").asText()).isEqualTo("notify-leaf");

        new NotificationSpool(spool.toString()).writeCursor(time.now().minusMinutes(10));
        String alarm = initialNotificationEvent("FIRE", time.now().minusMinutes(1));
        var worker = initialNotificationWorker(client, spool);
        worker.dispatchInitialAlarm("tenant-a", alarm);
        worker.poll();
        assertThat(jdbc.queryForList("SELECT contact_id FROM notification_provider_attempts WHERE source_id=? AND channel='SMS' AND status='SENT'", String.class, alarm))
            .containsExactlyInAnyOrder(unitContact, parent, leaf);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'", Long.class)).isEqualTo(1L);
    }

    @Test void faultDispatchUsesEvidenceCodesFromBothSourcesAndNeverRequeuesHistoricalExclusions(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path spool) throws Exception {
        var client=initialNotificationClient();
        String source=f.sid(f.source("tenant-a",false));
        var occurred=time.now().minusMinutes(1);
        String legacy=initialNotificationEvent("FAULT",occurred);
        String raw=rawFault(source,"raw-subscribed","SUBSCRIBED","{\"event_code\":\"SENSOR_FAULT\"}",occurred);
        String excluded=rawFault(source,"raw-excluded","EXCLUDED_BY_SUBSCRIPTION","{\"event_code\":\"SENSOR_FAULT\"}",occurred);
        String missing=rawFault(source,"raw-without-code","SUBSCRIBED","{}",occurred);
        new NotificationSpool(spool.toString()).writeCursor(occurred.minusSeconds(1));
        var worker=initialNotificationWorker(client,spool);
        worker.poll();worker.poll();

        for(String code:List.of("LOW_BATTERY","SENSOR_FAULT")){
            org.mockito.Mockito.verify(client).sendSms(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.argThat(p->code.equals(p.get("event_code"))),org.mockito.ArgumentMatchers.anyString());
        }
        assertThat(jdbc.queryForList("SELECT source_id FROM notification_provider_attempts WHERE tenant_id='tenant-a' AND channel='SMS'",String.class))
            .containsExactlyInAnyOrder(raw,legacy).doesNotContain(excluded,missing);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
    }

    @Test void scopedFaultSubscriptionBlocksOnlyMatchingEvidenceBeforeAttemptAndDebit(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path spool) throws Exception {
        var client=initialNotificationClient();
        String source=f.sid(f.source("tenant-a",false));
        var occurred=time.now().minusMinutes(1);
        String legacy=initialNotificationEvent("FAULT",occurred);
        String raw=rawFault(source,"raw-excluded-at-send","SUBSCRIBED","{\"event_code\":\"SENSOR_FAULT\"}",occurred);
        String fire=initialNotificationEvent("FIRE",occurred);
        var value=Map.of("subscriptions",Map.of("legacy-model",List.of("SENSOR_FAULT")),"receive_alarm",true,
            "push_message",true,"push_period_hours",0,"voice_enabled",false,"voice_types",List.of(),
            "voice_fields",List.of(),"voice_continuous",false,"silence_minutes",0);
        call(HttpMethod.PUT,"/alarm-base-configuration/FAULT/scopes/DEVICE/device-a?tenant_id=tenant-a",null,UUID.randomUUID().toString(),
            Map.of("expected_revision",0,"reason","Suppress only the scoped fault code","value",value)).andExpect(status().isOk());
        new NotificationSpool(spool.toString()).writeCursor(occurred.minusSeconds(1));
        var worker=initialNotificationWorker(client,spool);
        worker.poll();worker.poll();

        assertThat(jdbc.queryForList("SELECT source_id FROM notification_provider_attempts WHERE tenant_id='tenant-a' AND channel='SMS'",String.class))
            .containsExactlyInAnyOrder(fire,legacy).doesNotContain(raw);
        assertThat(jdbc.queryForList("SELECT source_id FROM notification_provider_attempts WHERE tenant_id='tenant-a' AND channel='VOICE'",String.class))
            .containsExactlyInAnyOrder(fire,legacy).doesNotContain(raw);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
    }

    private String rawFault(String source,String raw,String outcome,String detail,OffsetDateTime at) {
        byte[] payload={123,125};
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,device_id,device_name,device_address,received_at,payload_utf8,payload_bytes,payload_sha256,binding_state,capture_provenance) "
            +"VALUES (?,'tenant-a',?,'Fixture',?,'vendor-device','device-a','Device','Address',?,?,2,?,'BOUND','AUTHENTICATED_SOURCE')",
            raw,source,raw,at,payload,com.fireplatform.ingestion.IngestionLimits.sha256(payload));
        jdbc.update("INSERT INTO alarm_configuration_outcomes(id,tenant_id,raw_message_id,source_kind,source_id,event_type,configuration_revision,outcome_kind,outcome,detail_json,occurred_at) "
            +"VALUES (?,'tenant-a',?,'FAULT_MESSAGE',?,'FAULT',1,'SUBSCRIPTION',?,?,?)",
            UUID.randomUUID().toString(),raw,raw,outcome,detail,at);
        return raw;
    }

    @Test void managementRuleTargetsReflectUsableScopeNotSidebarOrNotificationPermission() throws Exception {
        String path = "/api/management/notification-rules/targets";
        String username = "scoped-notification-operator";
        String actor = f.body(f.call(HttpMethod.POST,"/api/management/platform-users",f.root,null,"scoped-notify-user",
            Map.of("username",username,"name",username,"initial_password",INITIAL,
                "permissions",List.of("notification-config:read","notification-config:write"),"tenant_ids",List.of("tenant-a")))
            .andExpect(status().isOk())).path("id").asText();
        String initial = f.login(username,INITIAL,"management");
        f.call(HttpMethod.POST,"/api/auth/change-password",initial,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD))
            .andExpect(status().isNoContent());
        String token = f.login(username,PASSWORD,"management");
        f.call(HttpMethod.GET,path,token,"tenant-b",null,null).andExpect(status().isForbidden());
        f.call(HttpMethod.GET,path,token,"tenant-a",null,null).andExpect(status().isOk())
            .andExpect(jsonPath("$.access").value("NONE")).andExpect(jsonPath("$.items").isEmpty());

        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,integration_state) VALUES "
            + "('hidden-notify-device','tenant-a','Hidden device','hidden-notify-eui','model','hidden address','NORMAL','LEGACY_UNVERIFIED'),"
            + "('archived-notify-device','tenant-a','Archived device','archived-notify-eui','model','archived address','NORMAL','LEGACY_UNVERIFIED')");
        jdbc.update("UPDATE devices SET archived_at=CURRENT_TIMESTAMP,archived_reason='Fixture archive',archived_by='bootstrap-admin',"
            + "identity_slot='HISTORY:archived-notify-device' WHERE id='archived-notify-device'");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES (?,'tenant-a','DEVICE_SET',1)",actor);
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES "
            + "(?,'tenant-a','device-a'),(?,'tenant-a','archived-notify-device')",actor,actor);
        var limited = f.body(f.call(HttpMethod.GET,path+"?scope_selected=true&scope_tenant_ids=",token,"tenant-a",null,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.tenant_id").value("tenant-a"))
            .andExpect(jsonPath("$.access").value("DEVICE_ONLY")).andExpect(jsonPath("$.total").value(1)));
        assertThat(limited.path("items").findValuesAsText("id")).containsExactly("device-a");
        assertThat(limited.path("items").get(0)).isEqualTo(json.createObjectNode().put("id","device-a").put("name","园区A烟感01"));
        f.call(HttpMethod.GET,path+"?targetId=hidden-notify-device",token,"tenant-a",null,null).andExpect(status().isOk())
            .andExpect(jsonPath("$.access").value("DEVICE_ONLY")).andExpect(jsonPath("$.items").isEmpty());
        f.call(HttpMethod.GET,path+"?scopeType=PLACE",token,"tenant-a",null,null).andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
        f.call(HttpMethod.GET,"/api/management/notification-rules/effective?deviceId=device-a&eventScope=FAULT",token,"tenant-a",null,null)
            .andExpect(status().isOk());
        f.call(HttpMethod.GET,"/api/management/notification-rules/effective?deviceId=hidden-notify-device&eventScope=FAULT",token,"tenant-a",null,null)
            .andExpect(status().isNotFound());

        String receiver = contact("Scoped recipient","13800005375").path("id").asText();
        var input = ruleInput("Scoped draft",List.of(Map.of("channel","SMS","contact_id",receiver,"level",1),
            Map.of("channel","VOICE","contact_id",receiver,"level",1)),0,0);
        input.put("enabled",false);
        input.put("scope_type","UNIT"); input.put("scope_id","tenant-a");
        f.call(HttpMethod.POST,"/api/management/notification-rules",token,"tenant-a","deny-unit-notify",input).andExpect(status().isForbidden());
        input.put("scope_type","PLACE"); input.put("scope_id",f.placeId);
        f.call(HttpMethod.POST,"/api/management/notification-rules",token,"tenant-a","deny-place-notify",input).andExpect(status().isForbidden());
        input.put("scope_type","DEVICE"); input.put("scope_id","device-a");
        f.call(HttpMethod.POST,"/api/management/notification-rules",token,"tenant-a","allow-device-notify",input).andExpect(status().isCreated());

        jdbc.update("UPDATE devices SET place_id=? WHERE id='device-a'",f.placeId);
        jdbc.update("UPDATE user_device_scopes SET mode='PLACE_SUBTREE',revision=2 WHERE user_id=? AND tenant_id='tenant-a'",actor);
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=?",actor);
        jdbc.update("INSERT INTO user_device_scope_places(user_id,tenant_id,place_id) VALUES (?,'tenant-a',?)",actor,f.placeId);
        f.call(HttpMethod.GET,path,token,"tenant-a",null,null).andExpect(status().isOk())
            .andExpect(jsonPath("$.access").value("DEVICE_ONLY")).andExpect(jsonPath("$.items[0].id").value("device-a"));
        jdbc.update("UPDATE devices SET place_id=NULL WHERE id='device-a'");
        f.call(HttpMethod.GET,path,token,"tenant-a",null,null).andExpect(status().isOk())
            .andExpect(jsonPath("$.access").value("NONE")).andExpect(jsonPath("$.items").isEmpty());

        f.call(HttpMethod.GET,path+"?scopeType=PLACE&targetId="+f.placeId,f.root,"tenant-a",null,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.access").value("ALL"))
            .andExpect(jsonPath("$.items[0].id").value(f.placeId));
    }

    @Test void scopedRuleRejectsForeignDeviceAndCannotRetargetAnExistingRule() throws Exception {
        String recipient = contact("Scoped receiver", "13800005385").path("id").asText();
        String id = scopedSmsRule("DEVICE", "device-a", 0, Map.of(recipient, 1));
        var input = ruleInput("Wrong tenant", List.of(Map.of("channel","SMS","contact_id",recipient,"level",1)), 0, 0);
        input.put("scope_type", "DEVICE"); input.put("scope_id", "device-b");
        call(HttpMethod.POST, "/notification-rules", "tenant-a", UUID.randomUUID().toString(), input).andExpect(status().isNotFound());
        input.put("scope_type", "UNIT"); input.put("scope_id", "tenant-a"); input.put("expected_revision", 0);
        call(HttpMethod.PUT, "/notification-rules/"+id, "tenant-a", UUID.randomUUID().toString(), input).andExpect(status().isBadRequest());
        call(HttpMethod.GET, "/notification-rules/"+id, "tenant-a", null, null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.scope_id").value("device-a")).andExpect(jsonPath("$.revision").value(0));
    }

    private String scopedSmsRule(String scopeType, String scopeId, int interval, Map<String,Integer> levels) throws Exception {
        var receivers = levels.entrySet().stream()
            .map(r -> Map.<String,Object>of("channel","SMS","contact_id",r.getKey(),"level",r.getValue())).toList();
        var input = ruleInput(scopeId + " Fire recipients", receivers, interval, 0);
        input.put("scope_type", scopeType); input.put("scope_id", scopeId);
        return f.body(call(HttpMethod.POST, "/notification-rules", "tenant-a", UUID.randomUUID().toString(), input)
            .andExpect(status().isCreated())).path("id").asText();
    }
    @Test void missingChannelResourcesProduceNoProviderCallNoLedgerEntryAndNoRepeatScan(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();
        jdbc.update("DELETE FROM notification_resource_ledger");
        jdbc.update("DELETE FROM notification_resource_accounts");
        new NotificationSpool(spool.toString()).writeCursor(time.now().minusMinutes(10));
        String id=initialNotificationEvent("FIRE",time.now().minusMinutes(1));
        var worker=initialNotificationWorker(client,spool);
        worker.dispatchInitialAlarm("tenant-a",id);
        org.mockito.Mockito.verify(client,org.mockito.Mockito.never()).sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.never()).sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        assertThat(jdbc.queryForList("SELECT channel,status,provider_code FROM notification_provider_attempts WHERE source_id=? ORDER BY channel",id)).extracting(row->row.get("channel")+":"+row.get("status")+":"+row.get("provider_code")).containsExactly("SMS:RESOURCE_UNAVAILABLE:NOTIFICATION_RESOURCE_UNAVAILABLE","VOICE:RESOURCE_UNAVAILABLE:NOTIFICATION_RESOURCE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a'",Long.class)).isZero();
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=?",Long.class,id)).isEqualTo(2L);
    }
    @Test void definiteProviderRejectionRefundsTheChannelAndDoesNotBlockTheOther(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();
        org.mockito.Mockito.when(client.sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.REJECTED, null,"isv.BUSINESS_LIMIT_CONTROL","rejected"));
        new NotificationSpool(spool.toString()).writeCursor(time.now().minusMinutes(10));
        String id=initialNotificationEvent("FIRE",time.now().minusMinutes(1));
        var worker=initialNotificationWorker(client,spool);
        worker.dispatchInitialAlarm("tenant-a",id);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND channel='SMS' AND kind='CONSUME'",Long.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND channel='SMS' AND kind='CONSUME'",Long.class)).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND channel='SMS' AND kind='REFUND'",Long.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND channel='SMS' AND status='FAILED'",Long.class,id)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND channel='VOICE' AND status='SENT'",Long.class,id)).isEqualTo(1L);
        long attemptsBeforePoll=jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=?",Long.class,id);
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=?",Long.class,id)).isEqualTo(attemptsBeforePoll);
    }
    @Test void lateFireAfterANewerDispatchStillNotifiesOnceAndChargesChannelsSeparately(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();var start=time.now().minusMinutes(10);
        new NotificationSpool(spool.toString()).writeCursor(start);
        String newer=initialNotificationEvent("FIRE",start.plusMinutes(2));
        initialNotificationWorker(client,spool).poll();
        String older=initialNotificationEvent("FIRE",start.plusMinutes(1));
        initialNotificationWorker(client,spool).poll();
        initialNotificationWorker(client,spool).poll();
        for(String id:List.of(newer,older))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(2L);
    }
    @Test void disabledSmsDoesNotReserveCreditWhileVoiceCanNotifyIndependently(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();
        org.mockito.Mockito.when(client.enabled()).thenReturn(false);
        var start=time.now().minusMinutes(10);
        new NotificationSpool(spool.toString()).writeCursor(start);
        String id=initialNotificationEvent("FIRE",start.plusMinutes(1));
        var worker=initialNotificationWorker(client,spool);worker.poll();worker.poll();
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND channel='SMS' AND kind IN ('CONSUME','REFUND')",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND channel='SMS' AND status='RESOURCE_UNAVAILABLE' AND provider_code='SMS_PROVIDER_NOT_READY'",Long.class,id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND channel='VOICE' AND status='SENT'",Long.class,id)).isEqualTo(1L);
        org.mockito.Mockito.verify(client,org.mockito.Mockito.never()).sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
    }
    @Test void testRecipientOverrideKeepsRealFireAndFaultRuleEligibility(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();
        var recipient=org.mockito.Mockito.mock(NotificationProviderService.class);
        org.mockito.Mockito.when(recipient.dispatchSettings()).thenReturn(new NotificationProviderService.DispatchSettings(true,true,"+8613900001234"));
        var markers=new NotificationSpool(spool.toString());markers.writeCursor(time.now().minusMinutes(2));
        for(String kind:List.of("FIRE","FAULT")) {
            var second=contact("Second "+kind+" level",kind.equals("FIRE")?"13800001239":"13800001240");
            String ruleId=jdbc.queryForObject("SELECT id FROM notification_rules WHERE tenant_id='tenant-a' AND name=? AND event_scope=?",String.class,kind+" initial notification",kind);
            for(String channel:List.of("SMS","VOICE","WECHAT"))jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES ('tenant-a',?,?,2,?)",ruleId,second.path("id").asText(),channel);
        }
        var worker=new RealNotificationService(jdbc,phones,client,realResources,org.mockito.Mockito.mock(SuppressionCommands.class),providerAttempts,recipient,baseConfiguration,channelPolicies,time,new NotificationSpool(spool.toString()),true,org.mockito.Mockito.mock(AlarmSessionNotificationGate.class),org.mockito.Mockito.mock(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.class),notificationRuntime());
        for(String kind:List.of("FIRE","FAULT")) {
            String id=initialNotificationEvent(kind,time.now().minusMinutes(1));
            // Production routes an immediate alarm insert through dispatchInitialAlarm, while FAULT sources are only consumed by poll().
            if("FIRE".equals(kind))worker.dispatchInitialAlarm("tenant-a",id);else worker.poll();
            var actual=providerAttempts.list("tenant-a",1,20).items().stream().filter(attempt->id.equals(attempt.sourceId())).toList();
            assertThat(actual).hasSize(4).extracting(NotificationProviderAttemptService.Attempt::channel).containsExactlyInAnyOrder("SMS","SMS","VOICE","VOICE");
            assertThat(actual).allSatisfy(attempt->{assertThat(attempt.status()).isEqualTo("SENT");assertThat(attempt.eventKind()).isEqualTo(kind);assertThat(attempt.contactId()).isNotEqualTo("TEST_OVERRIDE");});
            assertThat(actual.stream().map(NotificationProviderAttemptService.Attempt::contactId).distinct().toList()).containsExactlyInAnyOrderElementsOf(jdbc.queryForList("SELECT DISTINCT rr.contact_id FROM notification_rules r JOIN notification_rule_receivers rr ON rr.rule_id=r.id WHERE r.tenant_id='tenant-a' AND r.event_scope=? AND rr.channel IN ('SMS','VOICE')",String.class,kind));
        }
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(4L);
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(4)).sendSms(org.mockito.ArgumentMatchers.eq("+8613900001234"),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(4)).sendVoice(org.mockito.ArgumentMatchers.eq("+8613900001234"),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
    }
    @Test void testRecipientOverrideDoesNotCreateEligibilityWithoutARealContact(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();
        var recipient=org.mockito.Mockito.mock(NotificationProviderService.class);
        org.mockito.Mockito.when(recipient.dispatchSettings()).thenReturn(new NotificationProviderService.DispatchSettings(true,true,"+8613900001234"));
        jdbc.update("UPDATE notification_rules SET enabled=FALSE");
        String id=initialNotificationEvent("FAULT",time.now().minusMinutes(1));
        var markers=new NotificationSpool(spool.toString());markers.writeCursor(time.now().minusMinutes(2));
        var worker=new RealNotificationService(jdbc,phones,client,realResources,org.mockito.Mockito.mock(SuppressionCommands.class),providerAttempts,recipient,baseConfiguration,channelPolicies,time,new NotificationSpool(spool.toString()),true,org.mockito.Mockito.mock(AlarmSessionNotificationGate.class),org.mockito.Mockito.mock(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.class),notificationRuntime());
        // FAULT has no alarm row, so its only real consumer is the poller; dispatchInitialAlarm would make this assertion vacuous.
        worker.poll();
        assertThat(providerAttempts.list("tenant-a",1,20).items()).noneMatch(attempt->id.equals(attempt.sourceId()));
        org.mockito.Mockito.verify(client,org.mockito.Mockito.never()).sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.never()).sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test void sameTimeFireAndFaultBatchesBeyondOnePageAreNotLostOrChargedAgain(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var client=initialNotificationClient();var start=time.now().minusMinutes(10);
        new NotificationSpool(spool.toString()).writeCursor(start);
        var ids=new ArrayList<String>();
        for(int i=0;i<21;i++)for(String kind:List.of("FIRE","FAULT"))ids.add(initialNotificationEvent(kind,start.plusMinutes(1)));
        var worker=initialNotificationWorker(client,spool);worker.poll();worker.poll();
        initialNotificationWorker(client,spool).poll();
        for(String id:ids)assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(42L);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(42L);
    }
    @Test void faultConsumerRoutesRealSourceThroughProviderAndAttemptsWithoutAlarmIdentityAndNeverRetriesUnknown(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool)throws Exception{
        var one=contact("Fault receiver","13800001236");var receivers=List.<Map<String,Object>>of(Map.of("channel","SMS","contact_id",one.get("id").asText(),"level",1),Map.of("channel","VOICE","contact_id",one.get("id").asText(),"level",1));var rule=ruleInput("Explicit fault rule",receivers,0,0);rule.put("event_scope","FAULT");f.call(HttpMethod.POST,"/api/management/notification-rules",f.root,"tenant-a",UUID.randomUUID().toString(),rule).andExpect(status().isCreated());
        String fault=UUID.randomUUID().toString();var occurred=time.now().minusSeconds(10);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at,business_code) VALUES (?,'tenant-a','device-a','FAULT','FAULT','{}',?,?,'LOW_BATTERY')",fault,occurred,occurred);
        jdbc.update("INSERT INTO alarm_configuration_outcomes(id,tenant_id,source_kind,source_id,event_type,configuration_revision,outcome_kind,outcome,detail_json,occurred_at) VALUES (?,'tenant-a','LEGACY_FAULT',?,'LOW_BATTERY',1,'SUBSCRIPTION','SUBSCRIBED','{}',?)",UUID.randomUUID().toString(),fault,occurred);
        var client=org.mockito.Mockito.mock(AliyunNotificationClient.class);org.mockito.Mockito.when(client.enabled()).thenReturn(true);org.mockito.Mockito.when(client.voiceReady()).thenReturn(true);org.mockito.Mockito.when(client.sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("provider result unknown"));org.mockito.Mockito.when(client.sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new AliyunNotificationClient.Result(AliyunNotificationClient.Result.Outcome.ACCEPTED, "voice-id","ACCEPTED","provider-accepted"));
        var resources=org.mockito.Mockito.mock(NotificationResourceService.class);org.mockito.Mockito.when(resources.reserve("tenant-a","device-a","SMS")).thenReturn(new NotificationResourceService.Reservation("account-a","SMS","tenant-a"));org.mockito.Mockito.when(resources.reserve("tenant-a","device-a","VOICE")).thenReturn(new NotificationResourceService.Reservation("account-a","VOICE","tenant-a"));
        var suppression=org.mockito.Mockito.mock(SuppressionCommands.class);org.mockito.Mockito.when(suppression.blocked("tenant-a","device-a",false,fault)).thenReturn(false);
        var worker=new RealNotificationService(jdbc,phones,client,resources,suppression,providerAttempts,providerService,baseConfiguration,channelPolicies,time,new NotificationSpool(spool.toString()),true,org.mockito.Mockito.mock(AlarmSessionNotificationGate.class),org.mockito.Mockito.mock(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.class),notificationRuntime());var markers=new NotificationSpool(spool.toString());markers.writeCursor(occurred.minusSeconds(1));worker.poll();worker.poll();
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.argThat(params->"LOW_BATTERY".equals(params.get("event_code"))&&"FAULT".equals(params.get("event_kind"))&&!params.containsKey("alarm_id")),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.argThat(params->"LOW_BATTERY".equals(params.get("event_code"))&&"FAULT".equals(params.get("event_kind"))&&!params.containsKey("alarm_id")),org.mockito.ArgumentMatchers.anyString());
        var attempts=providerAttempts.list("tenant-a",1,20).items();assertThat(attempts).hasSize(2);assertThat(attempts).allSatisfy(attempt->{assertThat(attempt.eventKind()).isEqualTo("FAULT");assertThat(attempt.sourceKind()).isEqualTo("LEGACY_FAULT");assertThat(attempt.sourceId()).isEqualTo(fault);assertThat(attempt.alarmId()).isNull();});assertThat(attempts).extracting(NotificationProviderAttemptService.Attempt::status).containsExactlyInAnyOrder("UNKNOWN","SENT");
        assertThat(jdbc.queryForObject("SELECT kind FROM legacy_ingest_events WHERE id=?",String.class,fault)).isEqualTo("FAULT");
    }
}
