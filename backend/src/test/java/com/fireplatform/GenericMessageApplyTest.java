package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.duty.DutyQueries;
import com.fireplatform.duty.DutyTelemetryQueries;
import com.fireplatform.duty.FaultHandlingService;
import com.fireplatform.identity.AuthService;
import com.fireplatform.ingestion.IngestionInputs;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.product.ProductInputs;
import com.fireplatform.shared.ScopeFilter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** N06：通用消息应用、顺序水位、事务 outbox，以及"来源故障不制造全设备离线"。 */
@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","app.task-poll-ms=60000","spring.datasource.url=jdbc:h2:mem:genericapplytests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class GenericMessageApplyTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser; @Autowired com.fireplatform.ingestion.GenericMessageApplyWorker worker; @Autowired com.fireplatform.ingestion.CaptureService captureService;
    @Autowired AuthService auth; @Autowired DutyTelemetryQueries telemetry; @Autowired DutyQueries duty; @Autowired FaultHandlingService faultHandling;
    String root, placeId;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        for(String table:List.of("alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_change_outbox","alarm_change_heads","alarm_trace_entries","duty_fault_handling_history","duty_fault_handlings","legacy_ingest_events","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources","real_device_battery_observations","device_battery_threshold_changes","device_battery_threshold_overrides", "device_place_history", "device_version_history", "device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Generic test tenant A'),('tenant-b','Generic test tenant B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        placeId=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Generic fixture "+UUID.randomUUID())).andExpect(status().isOk())).get("id").asText();
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    JsonNode source() throws Exception { return body(call(HttpMethod.POST,"/api/management/sources",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Authenticated generic source")).andExpect(status().isOk())); }
    String sid(JsonNode c) { return c.at("/source/id").asText(); }
    String token(JsonNode c) { return c.get("one_time_token").asText(); }
    JsonNode published() throws Exception {
        var caps=json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED");
        var samples=List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",BigDecimal.ZERO,List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new BigDecimal("50"),List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
        return publishVersion(parser.defaultSchema(),caps,samples);
    }
    JsonNode publishVersion(JsonNode schema,JsonNode caps,List<ProductInputs.Sample> samples) throws Exception {
        String name="Capture-"+UUID.randomUUID();
        var product=body(call(HttpMethod.POST,"/api/management/products",root,null,UUID.randomUUID().toString(),Map.of("name",name,"manufacturer","Synthetic laboratory","model",name,"description","Generic test only")).andExpect(status().isOk()));
        var version=body(call(HttpMethod.POST,"/api/management/products/"+product.get("id").asText()+"/versions",root,null,UUID.randomUUID().toString(),Map.of()).andExpect(status().isOk()));
        var edit=new LinkedHashMap<String,Object>(); edit.put("schema",schema); edit.put("capabilities",caps); edit.put("expected_revision",0); edit.put("battery_low_threshold",null);
        var edited=body(call(HttpMethod.PUT,"/api/management/product-versions/"+version.get("id").asText(),root,null,UUID.randomUUID().toString(),edit).andExpect(status().isOk()));
        var tested=body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/test",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",edited.get("revision").asLong(),"samples",samples)).andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true)));
        return body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/publish",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",tested.get("revision").asLong())).andExpect(status().isOk()));
    }
    JsonNode device(JsonNode version) throws Exception { return body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Generic device","device_eui","generic-"+UUID.randomUUID(),"address","Synthetic address","product_version_id",version.get("id").asText(),"place_id",placeId)).andExpect(status().isOk())); }

    record Fixture(JsonNode source,String vendor,JsonNode device) { }
    Fixture fixture() throws Exception { return fixture(published()); }
    Fixture fixture(JsonNode version) throws Exception {
        var source=source(); var device=device(version); String vendor="vendor-"+UUID.randomUUID();
        call(HttpMethod.POST,"/api/management/sources/"+sid(source)+"/bindings",root,"tenant-a",UUID.randomUUID().toString(),Map.of("device_id",device.get("id").asText(),"vendor_device_id",vendor,"expected_revision",device.get("revision").asLong(),"reason","Explicit test binding")).andExpect(status().isOk());
        return new Fixture(source,vendor,device);
    }
    JsonNode capture(Fixture f,String message,Long sequence,String occurredAt) throws Exception {
        return capture(f,message,sequence,occurredAt,"{\"event\":\"FIRE\",\"battery\":20}");
    }
    JsonNode capture(Fixture f,String message,Long sequence,String occurredAt,String payload) throws Exception {
        return body(call(HttpMethod.POST,"/api/ingest/"+sid(f.source())+"/messages",token(f.source()),null,null,new IngestionInputs.Envelope(message,f.vendor(),null,payload,sequence,occurredAt)).andExpect(status().isOk()));
    }
    ResultActions apply(String id,long revision,String key) throws Exception {
        return call(HttpMethod.POST,"/api/management/messages/"+id+"/apply",root,"tenant-a",key,Map.of("expected_revision",revision,"reason","Generic apply test"));
    }

    @Test void applyWritesDeviceFactsWatermarkAndOutboxInOneTransaction() throws Exception {
        var f=fixture(); var raw=capture(f,"m-1",1L,"2026-10-31T10:00:00+08:00");
        var result=body(apply(raw.get("raw_message_id").asText(),0, UUID.randomUUID().toString()).andExpect(status().isOk())
            .andExpect(jsonPath("$.application_state").value("APPLIED"))
            .andExpect(jsonPath("$.event_type").value("FIRE"))
            .andExpect(jsonPath("$.outbox_event_type").value("MESSAGE_APPLIED")));
        assertThat(result.get("alarm_id").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,f.device().get("id").asText())).isEqualTo("FIRE");
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,f.device().get("id").asText())).isEqualTo("CONNECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM real_device_battery_observations WHERE device_id=?",Long.class,f.device().get("id").asText())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM device_message_watermark WHERE device_id=?",Long.class,f.device().get("id").asText())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE raw_message_id=? AND event_type='MESSAGE_APPLIED'",Long.class,raw.get("raw_message_id").asText())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,raw.get("raw_message_id").asText())).isEqualTo("APPLIED");
        // 旧字段语义冻结：business_status 恒为 NOT_APPLIED。
        assertThat(jdbc.queryForObject("SELECT business_status FROM raw_messages WHERE id=?",String.class,raw.get("raw_message_id").asText())).isEqualTo("NOT_APPLIED");
    }

    @Test void authenticatedCaptureIsAutomaticallyAppliedByDurableWorker() throws Exception {
        var f=fixture();var raw=capture(f,"auto-1",1L,"2026-10-31T10:00:00Z");String id=raw.get("raw_message_id").asText();
        assertThat(jdbc.queryForObject("SELECT capture_provenance FROM raw_messages WHERE id=?",String.class,id)).isEqualTo("AUTHENTICATED_SOURCE");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,id)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,f.device().get("id").asText())).isEqualTo("FIRE");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE target_id=? AND action='MESSAGE_AUTO_APPLY' ORDER BY occurred_at DESC LIMIT 1",String.class,id)).isNull();
    }

    @Test void timestampOnlyMessagesKeepNullableSequenceAndRejectOlderObservation() throws Exception {
        var f=fixture();String device=f.device().get("id").asText();
        var first=capture(f,"time-only-first",null,"2026-10-31T10:00:00Z");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,first.get("raw_message_id").asText())).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM device_message_watermark WHERE device_id=?",Long.class,device)).isNull();
        var second=capture(f,"time-only-second",null,"2026-10-31T10:01:00Z");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,second.get("raw_message_id").asText())).isEqualTo("APPLIED");
        var stale=capture(f,"time-only-stale",null,"2026-10-31T10:00:30Z");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,stale.get("raw_message_id").asText())).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM device_message_watermark WHERE device_id=?",Long.class,device)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_occurred_at FROM device_message_watermark WHERE device_id=?",java.time.OffsetDateTime.class,device)).isEqualTo(java.time.OffsetDateTime.parse("2026-10-31T10:01:00Z"));
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",java.time.OffsetDateTime.class,device)).isEqualTo(java.time.OffsetDateTime.parse("2026-10-31T10:01:00Z"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM real_device_battery_observations WHERE device_id=?",Long.class,device)).isEqualTo(2L);
    }

    @Test void malformedSourceTimeIsRetainedAndExplicitlyRejectedWithoutObservationTime() throws Exception {
        var f=fixture();var raw=capture(f,"bad-time",1L,"not-an-offset-time");String id=raw.get("raw_message_id").asText();
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT occurred_at_raw FROM raw_messages WHERE id=?",String.class,id)).isEqualTo("not-an-offset-time");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM raw_messages WHERE id=?",java.time.OffsetDateTime.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,id)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT apply_error FROM raw_messages WHERE id=?",String.class,id)).isEqualTo("SOURCE_TIME_INVALID");
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",java.time.OffsetDateTime.class,f.device().get("id").asText())).isNull();
    }

    @Test void concurrentDuplicateCaptureAndApplicationUseOneIdentityAndOneAlarm() throws Exception {
        var f=fixture();var envelope=new IngestionInputs.Envelope("concurrent-1",f.vendor(),null,"{\"event\":\"FIRE\",\"battery\":20}",1L,"2026-10-31T10:00:00Z");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(()->{gate.await();return captureService.capture(sid(f.source()),"Bearer "+token(f.source()),envelope,"parallel-capture-a");});
            var second=pool.submit(()->{gate.await();return captureService.capture(sid(f.source()),"Bearer "+token(f.source()),envelope,"parallel-capture-b");});
            var apply=pool.submit(()->{gate.await();worker.poll();return null;});
            gate.countDown();var a=first.get();var b=second.get();apply.get();worker.poll();
            assertThat(a.rawMessageId()).isEqualTo(b.rawMessageId());
            assertThat(jdbc.queryForObject("SELECT duplicate_count FROM raw_messages WHERE id=?",Long.class,a.rawMessageId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,a.rawMessageId())).isEqualTo("APPLIED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,f.device().get("id").asText())).isEqualTo(1L);
        } finally { pool.shutdownNow(); }
    }

    @Test void outOfOrderMessageIsRecordedButNeverChangesFacts() throws Exception {
        var f=fixture(); var first=capture(f,"m-1",5L,"2026-10-31T10:00:00+08:00");
        apply(first.get("raw_message_id").asText(),0,UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.application_state").value("APPLIED"));
        long alarmsAfterFirst=jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class);
        var late=capture(f,"m-2",4L,"2026-10-31T09:00:00+08:00");
        body(apply(late.get("raw_message_id").asText(),0,UUID.randomUUID().toString()).andExpect(status().isOk())
            .andExpect(jsonPath("$.application_state").value("STALE"))
            .andExpect(jsonPath("$.outbox_event_type").value("MESSAGE_STALE")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isEqualTo(alarmsAfterFirst);
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM device_message_watermark WHERE device_id=?",Long.class,f.device().get("id").asText())).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT apply_error FROM raw_messages WHERE id=?",String.class,late.get("raw_message_id").asText())).isEqualTo("OUT_OF_ORDER");
    }

    @Test void duplicateApplyIsIdempotentAndWritesNoSecondOutboxRow() throws Exception {
        var f=fixture(); var raw=capture(f,"m-1",1L,"2026-10-31T10:00:00Z"); String id=raw.get("raw_message_id").asText();
        apply(id,0,UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.application_state").value("APPLIED"));
        apply(id,1,UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.application_state").value("APPLIED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE raw_message_id=?",Long.class,id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isEqualTo(1L);
    }

    @Test void disablingSourceNeverMarksDevicesOffline() throws Exception {
        var f=fixture(); var raw=capture(f,"m-1",1L,java.time.OffsetDateTime.now().minusSeconds(1).toString());
        apply(raw.get("raw_message_id").asText(),0,UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.application_state").value("APPLIED"));
        String deviceId=f.device().get("id").asText();
        var before=jdbc.queryForMap("SELECT last_message_at,physical_state FROM devices WHERE id=?",deviceId);
        long sourceRevision=jdbc.queryForObject("SELECT revision FROM ingestion_sources WHERE id=?",Long.class,sid(f.source()));
        call(HttpMethod.PUT,"/api/management/sources/"+sid(f.source()),root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Synthetic capture source","enabled",false,"expected_revision",sourceRevision)).andExpect(status().isOk());
        var after=jdbc.queryForMap("SELECT last_message_at,physical_state FROM devices WHERE id=?",deviceId);
        assertThat(after.get("last_message_at")).isEqualTo(before.get("last_message_at"));
        assertThat(after.get("physical_state")).isEqualTo(before.get("physical_state"));
    }

    ObjectNode telemetrySchema() {
        ObjectNode schema=json.createObjectNode(); schema.put("format","JSON_POINTER_V1");
        ArrayNode measurements=schema.putArray("measurements");
        ObjectNode temperature=measurements.addObject(); temperature.put("key","temperature"); temperature.put("label","温度"); temperature.put("pointer","/temp"); temperature.put("value_type","NUMBER"); temperature.put("unit","℃"); temperature.put("required",true);
        ObjectNode humidity=measurements.addObject(); humidity.put("key","humidity"); humidity.put("label","湿度"); humidity.put("pointer","/hum"); humidity.put("value_type","NUMBER"); humidity.put("unit","%");
        return schema;
    }
    ObjectNode telemetryCaps() { return json.createObjectNode().put("fire","UNSUPPORTED").put("recovery","UNSUPPORTED").put("battery","UNSUPPORTED").put("telemetry","SUPPORTED"); }
    List<ProductInputs.Sample> telemetrySamples() {
        return List.of(
            new ProductInputs.Sample("valid","PARAM","{\"temp\":23.5,\"hum\":40}","PARAM",null,List.of(
                new ProductInputs.SampleReading("temperature","VALID",json.getNodeFactory().numberNode(new BigDecimal("23.5"))),
                new ProductInputs.SampleReading("humidity","VALID",json.getNodeFactory().numberNode(new BigDecimal("40"))))),
            new ProductInputs.Sample("missing","PARAM","{\"hum\":41}","PARAM",null,List.of(
                new ProductInputs.SampleReading("temperature","MISSING",null),
                new ProductInputs.SampleReading("humidity","VALID",json.getNodeFactory().numberNode(new BigDecimal("41"))))),
            new ProductInputs.Sample("invalid","PARAM","{\"temp\":\"hot\",\"hum\":42}","PARAM",null,List.of(
                new ProductInputs.SampleReading("temperature","INVALID",null),
                new ProductInputs.SampleReading("humidity","VALID",json.getNodeFactory().numberNode(new BigDecimal("42"))))),
            new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),
            new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
    }
    JsonNode faultSchema() {
        JsonNode schema=parser.defaultSchema().deepCopy();
        ((ArrayNode)schema.get("event_mappings")).addObject().put("raw_value","FAULT_OVERHEAT").put("event_type","FAULT");
        return schema;
    }
    ObjectNode faultCaps() { return json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED").put("fault","SUPPORTED"); }
    List<ProductInputs.Sample> faultSamples() {
        return List.of(
            new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",BigDecimal.ZERO,List.of()),
            new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new BigDecimal("50"),List.of()),
            new ProductInputs.Sample("fault","FAULT","{\"event\":\"FAULT_OVERHEAT\",\"battery\":10}","FAULT",new BigDecimal("10"),List.of()),
            new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),
            new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
    }

    @Test void pureTelemetryProductProjectsIntoExistingMonitorAndKeepsBackdatedHistoryWithoutRewindingCurrent() throws Exception {
        var f=fixture(publishVersion(telemetrySchema(),telemetryCaps(),telemetrySamples()));
        var actorId=auth.authenticate(root).user().id();
        var fresh=capture(f,"telemetry-new",1L,"2026-10-31T10:00:00Z","{\"temp\":23.5,\"hum\":40}"); worker.poll();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,fresh.get("raw_message_id").asText())).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT event_type FROM message_outbox WHERE raw_message_id=?",String.class,fresh.get("raw_message_id").asText())).isEqualTo("MESSAGE_APPLIED");
        var metrics=telemetry.metrics(actorId,"tenant-a",ScopeFilter.Selection.all());
        assertThat(metrics).anySatisfy(metric->{assertThat(metric.key()).isEqualTo("temperature");assertThat(metric.label()).isEqualTo("温度");assertThat(metric.valueType()).isEqualTo("NUMBER");});
        assertThat(metrics).anySatisfy(metric->{assertThat(metric.key()).isEqualTo("humidity");assertThat(metric.valueType()).isEqualTo("NUMBER");});
        var current=telemetry.current(actorId,"tenant-a",ScopeFilter.Selection.all(),"temperature",1,20);
        assertThat(current.items()).singleElement().satisfies(sample->{
            assertThat(sample.readings()).anySatisfy(reading->{assertThat(reading.metric()).isEqualTo("temperature");assertThat(reading.value()).isEqualTo("23.5");assertThat(reading.unit()).isEqualTo("℃");assertThat(reading.valueType()).isEqualTo("NUMBER");});
        });
        assertThat(telemetry.history(actorId,"tenant-a",ScopeFilter.Selection.all(),"temperature",null,null,1,20).total()).isEqualTo(1);
        var backdated=capture(f,"telemetry-old",2L,"2026-10-31T09:00:00Z","{\"temp\":99,\"hum\":10}"); worker.poll();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,backdated.get("raw_message_id").asText())).isEqualTo("STALE");
        var after=telemetry.current(actorId,"tenant-a",ScopeFilter.Selection.all(),"temperature",1,20);
        assertThat(after.items()).singleElement().satisfies(sample->assertThat(sample.readings()).anySatisfy(reading->assertThat(reading.value()).isEqualTo("23.5")));
        var history=telemetry.history(actorId,"tenant-a",ScopeFilter.Selection.all(),"temperature",null,null,1,20);
        assertThat(history.total()).isEqualTo(2);
        assertThat(history.items()).anySatisfy(sample->assertThat(sample.readings()).anySatisfy(reading->assertThat(reading.value()).isEqualTo("99")));
    }

    @Test void genericFaultUsesExistingFaultPageAndHandling() throws Exception {
        var f=fixture(publishVersion(faultSchema(),faultCaps(),faultSamples()));
        var raw=capture(f,"fault-1",1L,"2026-10-31T10:00:00Z","{\"event\":\"FAULT_OVERHEAT\",\"battery\":10}"); worker.poll();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,raw.get("raw_message_id").asText())).isEqualTo("APPLIED");
        var actor=auth.authenticate(root); var actorId=actor.user().id();
        var faults=duty.faults(actorId,"tenant-a",ScopeFilter.Selection.all(),null,null,null,null,1,20);
        assertThat(faults.items()).singleElement().satisfies(fault->{
            assertThat(fault.method()).isEqualTo("GENERIC_FAULT");
            assertThat(fault.faultCode()).isEqualTo("FAULT_OVERHEAT");
            assertThat(fault.observedAt()).isNotNull();
        });
        String event=faults.items().getFirst().id();
        var handling=faultHandling.set(actor,"management","tenant-a",event,UUID.randomUUID().toString(),true,0,"已处理","fault-handling-test");
        assertThat(handling.handled()).isTrue();
        assertThat(duty.fault(actorId,"tenant-a",ScopeFilter.Selection.all(),event).handling().handled()).isTrue();
    }

    @Test void unknownTimeFireIsRetainedAsRiskWithoutLivenessBatteryOrWatermark() throws Exception {
        var f=fixture(); var raw=capture(f,"untimed-fire",null,null); worker.poll();
        String id=raw.get("raw_message_id").asText(), deviceId=f.device().get("id").asText();
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,id)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM raw_messages WHERE id=?",java.time.OffsetDateTime.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT source_time_known FROM alarms WHERE device_id=?",Boolean.class,deviceId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT platform_recorded_at IS NOT NULL FROM alarms WHERE device_id=?",Boolean.class,deviceId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",java.time.OffsetDateTime.class,deviceId)).isNull();
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,deviceId)).isEqualTo("PENDING_INTEGRATION");
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id=?",BigDecimal.class,deviceId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_message_watermark WHERE device_id=?",Long.class,deviceId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM real_device_battery_observations WHERE device_id=?",Long.class,deviceId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='ALARM' AND observed_at IS NULL",Long.class,deviceId)).isEqualTo(1L);
    }
}
