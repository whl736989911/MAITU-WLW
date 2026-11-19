package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionIngestion;
import com.fireplatform.notification.*;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.transport.NotificationSpool;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","app.alarm-change-publish-enabled=true","app.alarm-change-poll-ms=60000","fire.notification.real-enabled=true","fire.notification.real-initial-delay-ms=3600000","fire.notification.real-poll-ms=3600000","fire.hikvision.enabled=false","fire.legacy.ingest-token=vendor-flow-test-token","app.notification-contact-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","spring.datasource.url=jdbc:h2:mem:vendor-alarm-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class VendorDeviceAlarmFlowIntegrationTest {
    private static final String SPOOL=Path.of(System.getProperty("java.io.tmpdir"),"vendor-flow-"+UUID.randomUUID()).toString();
    private static final String MIRROR_SPOOL=SPOOL+"-mirror";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){IngestionIntegrationTest.environment(registry);registry.add("fire.notification.real-spool-dir",()->SPOOL);registry.add("fire.legacy.mirror.spool-dir",()->MIRROR_SPOOL);registry.add("fire.legacy.mirror.token",()->"flow-mirror-token");registry.add("fire.legacy.mirror-scan-ms",()->"60000");}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired HikvisionIngestion hikvision;
    @Autowired ContactPhoneStorage phones;
    @Autowired DatabaseTime time;
    @Autowired NotificationChannelPolicyService policies;
    @Autowired NotificationProviderAttemptService attempts;
    @Autowired com.fireplatform.configuration.AlarmBaseConfigurationQueries configuration;
    @Autowired NotificationProviderService providers;
    @Autowired NotificationResourceService resources;
    @Autowired RealNotificationService worker;
    @Autowired NotificationSpool spool;
    @MockitoBean com.fireplatform.transport.AliyunNotificationClient client;
    IngestionIntegrationTest f;
    NotificationIntegrationTest notifications;
    String profile;

    @BeforeEach void setup() throws Exception {
        NotificationIntegrationTest.clear(jdbc);
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=NULL");
        jdbc.update("DELETE FROM duty_fault_handling_history");
        jdbc.update("DELETE FROM duty_fault_handlings");
        f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();
        String product="flow-product-"+UUID.randomUUID();profile="flow-profile-"+UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,name,manufacturer,model,description,state) VALUES(?,?,?,?,?,'ACTIVE')",product,"Legacy smoke profile","Controlled fixture","Legacy smoke","Isolated integration test");
        jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,revision,schema_json,capabilities_json,parser_tested,schema_format,legacy_product_id,created_at) VALUES(?,?,1,'DRAFT',0,'{}','{}',FALSE,'LEGACY_ATTR_JS_V1',?,CURRENT_TIMESTAMP)",profile,product,"flow-legacy-"+UUID.randomUUID());
        jdbc.update("INSERT INTO notification_channel_policies(id,tenant_id,scope_type,scope_key,sms_enabled,voice_enabled,revision,changed_at) VALUES ('flow-policy','tenant-a','UNIT','tenant-a',TRUE,TRUE,1,CURRENT_TIMESTAMP)");
        notifications=new NotificationIntegrationTest();notifications.f=f;notifications.mvc=mvc;notifications.json=json;notifications.jdbc=jdbc;notifications.phones=phones;notifications.time=time;notifications.channelPolicies=policies;notifications.providerAttempts=attempts;notifications.baseConfiguration=configuration;notifications.providerService=providers;notifications.realResources=resources;
        org.mockito.Mockito.when(client.enabled()).thenReturn(true);
        org.mockito.Mockito.when(client.voiceReady()).thenReturn(true);
        org.mockito.Mockito.when(client.sendSms(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.fireplatform.transport.AliyunNotificationClient.Result(com.fireplatform.transport.AliyunNotificationClient.Result.Outcome.ACCEPTED, "sms-fixture","OK","accepted"));
        org.mockito.Mockito.when(client.sendVoice(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.fireplatform.transport.AliyunNotificationClient.Result(com.fireplatform.transport.AliyunNotificationClient.Result.Outcome.ACCEPTED, "voice-fixture","OK","accepted"));
    }

    @ParameterizedTest @ValueSource(strings={"LEGACY_REALTIME","HIKVISION_FIRECLOUD"})
    void newDeviceReportsNotifiesChargesAndPreservesSourceSpecificRecovery(String source) throws Exception {
        String serial="flow-"+UUID.randomUUID(),upstream="reported-"+UUID.randomUUID(),external="cloud-"+UUID.randomUUID();
        if(source.equals("HIKVISION_FIRECLOUD"))
            hikvision.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of("deviceID",external,"deviceSerial",serial,"deviceName","Discovered smoke","deviceType","600002","deviceModel","800235"))),time.now());
        var input=new LinkedHashMap<String,Object>();input.put("source",source);input.put("name","New controlled smoke");input.put("serial",serial);input.put("place_id",f.placeId);input.put("address","Controlled room");
        if(source.equals("LEGACY_REALTIME")){input.put("legacy_profile_id",profile);input.put("upstream_device_id",upstream);}
        String key=UUID.randomUUID().toString();
        JsonNode created=f.body(f.call(HttpMethod.POST,"/api/management/vendor-devices",f.root,"tenant-a",key,input).andExpect(status().isOk()));
        String device=created.path("id").asText();
        assertThat(device).isNotBlank().isNotEqualTo("device-a");
        assertThat(created.path("physical_state").asText()).isEqualTo("UNKNOWN");
        assertThat(created.path("integration_state").asText()).isEqualTo("PENDING_INTEGRATION");
        assertThat(f.body(f.call(HttpMethod.POST,"/api/management/vendor-devices",f.root,"tenant-a",key,input).andExpect(status().isOk())).path("id").asText()).isEqualTo(device);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE status='PUBLISHED'",Long.class)).isZero();
        assertThat(deviceStatus(device,"management",f.root).path("customer_status").asText()).isEqualTo("UNACTIVATED");

        notifications.initialNotificationFixtures();
        // This test checks FIRE charging; its later FAULT rows exercise status and handling independently.
        jdbc.update("UPDATE notification_rules SET enabled=FALSE WHERE tenant_id='tenant-a' AND event_scope='FAULT'");
        spool.writeCursor(time.now().minusMinutes(1));
        OffsetDateTime observed=time.now();
        if(source.equals("LEGACY_REALTIME")) {
            legacy(upstream,"heartbeat","thing.event.heartbeat","1002045",observed);
            legacy(upstream,"alarm","thing.event.alarm","1001001",observed.plusNanos(1000));
        } else {
            hikvision.accept(HikvisionClient.Stream.FAULT,json.valueToTree(List.of(Map.of("faultID","link-"+external,"resourceID",external,"faultType","300002","faultStartTime",observed.toString(),"faultState","0"))),observed,"800235","HIKVISION_PUSH");
            assertThat(deviceStatus(device,"management",f.root).path("online_status").asText()).isEqualTo("UNACTIVATED");
            String linkFault=jdbc.queryForObject("SELECT id FROM legacy_ingest_events WHERE device_id=? AND kind='FAULT'",String.class,device);
            f.call(HttpMethod.POST,"/api/management/duty/faults/"+linkFault+"/handling",f.root,"tenant-a",UUID.randomUUID().toString(),
                Map.of("expected_revision",0,"handled",true,"note","Checked the source link fault")).andExpect(status().isOk());
            hikvision.accept(HikvisionClient.Stream.ALARM,json.valueToTree(List.of(Map.of("alarmID","fire-"+external,"resourceID",external,"alarmType","200001","alarmStartTime",observed.toString(),"alarmState","0"))),observed,"800235","HIKVISION_PUSH");
            assertThat(deviceStatus(device,"management",f.root).path("online_status").asText()).isEqualTo("ONLINE");
            hikvision.accept(HikvisionClient.Stream.MONITOR,json.valueToTree(List.of(Map.of("resourceID",external,"monitorTime",observed.toString(),"data",List.of(Map.of("monitorType","400006","monitorValue","24.4","monitorUnit","℃","circuitNo","0"))))),observed);
        }
        long alarmDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)==0&&System.nanoTime()<alarmDeadline)Thread.sleep(10);
        String alarm=jdbc.queryForObject("SELECT id FROM alarms WHERE device_id=? AND physical_state='FIRE'",String.class,device);
        JsonNode details=f.body(f.call(HttpMethod.GET,"/api/management/devices/"+device,f.root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(details.path("device").path("id").asText()).isEqualTo(device);
        assertThat(details.path("device").path("physical_state").asText()).isEqualTo("FIRE");
        assertThat(details.path("device").path("integration_state").asText()).isEqualTo("CONNECTED");
        assertThat(details.at("/device/status_display/customer_status").asText()).isEqualTo("FIRE");
        JsonNode telemetry=f.body(f.call(HttpMethod.GET,"/api/management/devices/"+device+"/telemetry",f.root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(telemetry.toString()).contains("24.4");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,alarm)<2&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE source_id=? AND status='SENT'",Long.class,alarm)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_outbox o JOIN alarm_trace_entries t ON t.id=o.id WHERE t.alarm_id=? AND o.publication_sequence IS NOT NULL",Long.class,alarm)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND kind='CONSUME'",Long.class)).isEqualTo(2);
        worker.poll();worker.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND kind='CONSUME'",Long.class)).isEqualTo(2);

        String customer=f.login("customer-a",IngestionIntegrationTest.INITIAL,"customer");
        f.call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",IngestionIntegrationTest.PASSWORD)).andExpect(status().isNoContent());
        customer=f.login("customer-a",IngestionIntegrationTest.PASSWORD,"customer");
        assertThat(deviceStatus(device,"customer",customer)).isEqualTo(details.at("/device/status_display"));
        OffsetDateTime reported=jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,device);
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id=?",observed.minusYears(1),device);
        JsonNode timedOut=deviceStatus(device,"customer",customer);
        String timeoutStatus=source.equals("LEGACY_REALTIME")?"OFFLINE":"UNKNOWN";
        assertThat(timedOut.path("online_status").asText()).isEqualTo(timeoutStatus);
        assertThat(timedOut.path("customer_status").asText()).isEqualTo(source.equals("LEGACY_REALTIME")?"OFFLINE":"FIRE");
        assertThat(timedOut.at("/business_statuses/0/code").asText()).isEqualTo("FIRE");
        JsonNode current=f.body(f.call(HttpMethod.GET,"/api/customer/alarms/"+alarm,customer,null,null,null).andExpect(status().isOk()));
        f.call(HttpMethod.POST,"/api/customer/alarms/"+alarm+"/review",customer,null,UUID.randomUUID().toString(),Map.of("expected_revision",current.path("revision").asLong(),"conclusion","TEST","remark","Controlled source alarm was verified")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT response_state FROM alarms WHERE id=?",String.class,alarm)).isEqualTo("HANDLED");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM alarms WHERE id=?",String.class,alarm)).isEqualTo("FIRE");
        assertThat(deviceStatus(device,"customer",customer).path("customer_status").asText()).isEqualTo(timeoutStatus);
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id=?",reported,device);
        assertThat(deviceStatus(device,"customer",customer).path("customer_status").asText()).isEqualTo("ONLINE");
        if(source.equals("LEGACY_REALTIME"))legacy(upstream,"heartbeat","thing.event.heartbeat","1002045",time.now().plusSeconds(1));
        else hikvision.accept(HikvisionClient.Stream.ALARM,json.valueToTree(List.of(Map.of("alarmID","fire-"+external,"resourceID",external,"alarmType","200001","alarmStartTime",observed.toString(),"alarmStopTime",time.now().toString(),"alarmState","1"))),time.now(),"800235","HIKVISION_PUSH");
        JsonNode restored=f.body(f.call(HttpMethod.GET,"/api/customer/alarms/"+alarm,customer,null,null,null).andExpect(status().isOk()));
        assertThat(restored.path("physical_state").asText()).isEqualTo(source.equals("LEGACY_REALTIME")?"FIRE":"NORMAL");
        assertThat(restored.path("response_state").asText()).isEqualTo("HANDLED");
        assertThat(restored.path("conclusion").asText()).isEqualTo("TEST");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT voice_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1);

        String firstCode=source.equals("LEGACY_REALTIME")?"1002003":"300004";
        String secondCode=source.equals("LEGACY_REALTIME")?"1002027":"300003";
        if(source.equals("LEGACY_REALTIME")) {
            legacy(upstream,"fault","thing.event.faults",firstCode,observed);
            legacy(upstream,"fault","thing.event.faults",secondCode,observed.plusNanos(1000));
        } else {
            hikvision.accept(HikvisionClient.Stream.FAULT,json.valueToTree(List.of(
                Map.of("faultID","first-"+external,"resourceID",external,"faultType",firstCode,"faultStartTime",observed.toString(),"faultState","0"),
                Map.of("faultID","second-"+external,"resourceID",external,"faultType",secondCode,"faultStartTime",observed.plusNanos(1000).toString(),"faultState","0"))),time.now());
        }
        long faultDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        List<String> events;
        do {
            events=jdbc.queryForList("SELECT id FROM legacy_ingest_events WHERE device_id=? AND kind='FAULT' AND business_code IN (?,?) ORDER BY observed_at,id",String.class,device,firstCode,secondCode);
            if(events.size()!=2)Thread.sleep(10);
        } while(events.size()!=2&&System.nanoTime()<faultDeadline);
        assertThat(events).hasSize(2);
        JsonNode faults=deviceStatus(device,"management",f.root);
        assertThat(faults.path("customer_status").asText()).isEqualTo("FAULT");
        assertThat(faults.path("business_statuses").size()).isEqualTo(2);
        assertThat(faults.path("business_statuses").toString()).contains(source.equals("LEGACY_REALTIME")?"防拆报警":"防拆");
        for(int i=0;i<events.size();i++) {
            String event=events.get(i);
            f.call(HttpMethod.POST,"/api/management/duty/faults/"+event+"/handling",f.root,"tenant-a",UUID.randomUUID().toString(),
                Map.of("expected_revision",0,"handled",true,"note","Checked the source fault")).andExpect(status().isOk());
            assertThat(deviceStatus(device,"customer",customer).path("customer_status").asText()).isEqualTo(i==0?"FAULT":"ONLINE");
        }
    }

    private JsonNode deviceStatus(String device,String audience,String session) throws Exception {
        return f.body(f.call(HttpMethod.GET,"/api/"+audience+"/devices/"+device,session,audience.equals("management")?"tenant-a":null,null,null)
            .andExpect(status().isOk())).at("/device/status_display");
    }

    private void legacy(String upstream,String path,String method,String code,OffsetDateTime observed) throws Exception {
        var params=new LinkedHashMap<String,Object>();params.put("time",observed.toString());params.put("temperature",24.4);params.put("battery",88);params.put("deviceState",method.endsWith("alarm")?"火警":"心跳上报");params.put("deviceStateCode",code);
        if(path.equals("alarm")||path.equals("fault")) {
            String raw=json.writeValueAsString(Map.of("deviceId",upstream,"deviceName","Upstream smoke","messageType",path.equals("alarm")?"Alarm":"Fault","deviceStateCode",code,"params",json.writeValueAsString(params),"ts",observed.toInstant().toEpochMilli()));
            String form="msg="+java.net.URLEncoder.encode(raw,java.nio.charset.StandardCharsets.UTF_8);
            mvc.perform(post("/api/ingest/cat1").header("X-Ingest-Token","flow-mirror-token").header("X-Original-Uri","/api/v1/formttl/alarms/telemetry").contentType("application/x-www-form-urlencoded").content(form)).andExpect(status().isAccepted());
            return;
        }
        var body=Map.of("deviceId",upstream,"deviceName","Upstream smoke","platfromType","lorawan_turbo","productCategory",1,"productManufacturer","Turboes","productName","Smoke","deviceStateCode",code,"deviceState",params.get("deviceState"),"parseMsg",List.of(Map.of("method",method,"params",List.of(params))));
        mvc.perform(post("/v1/formttl/"+path+"/telemetry").header("X-Legacy-Token","vendor-flow-test-token").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk());
    }
}
