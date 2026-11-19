package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.ingestion.*;
import com.fireplatform.product.*;
import com.fireplatform.shared.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","app.task-poll-ms=60000","spring.datasource.url=jdbc:h2:mem:ingestiontests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class IngestionIntegrationTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser;
    String root, placeId;
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("hikvision_subscription_observations","hikvision_subscription_attempts","hikvision_subscription_operations","fire_archive_attachments","fire_archive_history","fire_archives","maintenance_work_order_replacements","maintenance_work_order_transitions","maintenance_work_logs","maintenance_work_orders","alarm_notification_claims","alarm_session_events","alarm_session_members","alarm_sessions","alarm_merge_policy_revisions","alarm_merge_policies","device_registration_events","device_registrations","device_service_command_attempts","device_service_commands","device_parameter_snapshots","device_source_bindings","device_capability_evidence","device_service_definitions","payment_paid_transactions","payment_reconciliation_differences","payment_reconciliation_runs"))jdbc.update("DELETE FROM "+table);
        N04TestScopes.clear(jdbc);
        for(String table:List.of("legacy_ingest_quarantines","place_transfer_nodes","place_transfers","device_transfer_alias_history","device_transfers","notification_automation_intents","alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","resource_linkage_rules","alarm_base_configurations","alarm_trace_entries","legacy_ingest_events","legacy_ingest_messages","device_aliases","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources","device_battery_threshold_changes","device_battery_threshold_overrides", "device_place_history", "device_version_history", "device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Ingestion test tenant A'),('tenant-b','Ingestion test tenant B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management"); call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent()); root=login("admin",PASSWORD,"management"); placeId=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Ingestion fixture "+UUID.randomUUID())).andExpect(status().isOk())).get("id").asText();
        // Controlled integration fixtures are not production bootstrap data or hardware evidence.
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES ('device-a','tenant-a','园区A烟感01','fixture-a','legacy-model','A区一层','NORMAL',FALSE,'LEGACY_UNVERIFIED'),('device-b','tenant-b','园区B烟感01','fixture-b','legacy-model','B区一层','NORMAL',FALSE,'LEGACY_UNVERIFIED')");
        for(String[] account:new String[][]{{"customer-a","tenant-a"},{"customer-b","tenant-b"}}) call(HttpMethod.POST,"/api/management/users",root,account[1],UUID.randomUUID().toString(),Map.of("username",account[0],"name",account[0],"initial_password",INITIAL,"permissions",new ArrayList<>(com.fireplatform.shared.Limits.CUSTOMER_PERMISSIONS))).andExpect(status().isOk());
        N04TestScopes.grantExisting(jdbc);
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    JsonNode source(String tenant,boolean retiredFlagFixture) throws Exception { return body(call(HttpMethod.POST,"/api/management/sources",root,tenant,UUID.randomUUID().toString(),Map.of("name","Synthetic capture source")).andExpect(status().isOk())); }
    String sid(JsonNode credential) { return credential.at("/source/id").asText(); }
    String token(JsonNode credential) { return credential.get("one_time_token").asText(); }
    ResultActions capture(JsonNode source,String message,String vendor,String payload,String reported) throws Exception { return call(HttpMethod.POST,"/api/ingest/"+sid(source)+"/messages",token(source),null,null,new IngestionInputs.Envelope(message,vendor,reported,payload,null,null)); }
    JsonNode product() throws Exception { String name="Capture-"+UUID.randomUUID(); return body(call(HttpMethod.POST,"/api/management/products",root,null,UUID.randomUUID().toString(),Map.of("name",name,"manufacturer","Synthetic laboratory","model",name,"description","Generic test only")).andExpect(status().isOk())); }
    JsonNode published(String product,String scale) throws Exception {
        var version=body(call(HttpMethod.POST,"/api/management/products/"+product+"/versions",root,null,UUID.randomUUID().toString(),Map.of()).andExpect(status().isOk())); var schema=parser.defaultSchema(); ((com.fasterxml.jackson.databind.node.ObjectNode)schema.get("battery")).put("scale",new BigDecimal(scale));
        var caps=json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED");
        var edited=body(call(HttpMethod.PUT,"/api/management/product-versions/"+version.get("id").asText(),root,null,UUID.randomUUID().toString(),Map.of("schema",schema,"capabilities",caps,"expected_revision",0,"battery_low_threshold",json.nullNode())).andExpect(status().isOk()));
        var samples=List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",BigDecimal.ZERO,List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new BigDecimal(scale).multiply(new BigDecimal("50")),List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
        var tested=body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/test",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",edited.get("revision").asLong(),"samples",samples)).andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true)));
        return body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/publish",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",tested.get("revision").asLong())).andExpect(status().isOk()));
    }
    JsonNode register(JsonNode version,boolean retiredFlagFixture) throws Exception { return body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Formal pending device","device_eui","generic-"+UUID.randomUUID(),"address","Synthetic address","product_version_id",version.get("id").asText(),"place_id",placeId)).andExpect(status().isOk())); }
    ResultActions bind(JsonNode source,JsonNode device,String vendor,long revision,String key) throws Exception { return call(HttpMethod.POST,"/api/management/sources/"+sid(source)+"/bindings",root,"tenant-a",key,Map.of("device_id",device.get("id").asText(),"vendor_device_id",vendor,"expected_revision",revision,"reason","Explicit test binding")); }
    ResultActions preview(String id,long revision,String key) throws Exception { return call(HttpMethod.POST,"/api/management/messages/"+id+"/preview",root,"tenant-a",key,Map.of("expected_revision",revision,"reason","Historical preview only")); }
    JsonNode message(String id) throws Exception { return body(call(HttpMethod.GET,"/api/management/messages/"+id,root,"tenant-a",null,null).andExpect(status().isOk())); }
    @Test void credentialsAreRandomOneTimeRecoveryIsExplicitAndBearersNeverCrossAuthorities() throws Exception {
        var input=Map.of("name","One-time source"); var first=body(call(HttpMethod.POST,"/api/management/sources",root,"tenant-a","create-once",input).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")));
        String secret=token(first); assertThat(secret).matches("gih_[A-Za-z0-9_-]{43}"); assertThat(first.get("credential_recovery_required").asBoolean()).isFalse();
        var replay=body(call(HttpMethod.POST,"/api/management/sources",root,"tenant-a","create-once",input).andExpect(status().isOk())); assertThat(replay.get("one_time_token").isNull()).isTrue(); assertThat(replay.get("credential_recovery_required").asBoolean()).isTrue(); assertThat(replay.get("source")).isEqualTo(first.get("source"));
        call(HttpMethod.POST,"/api/management/sources",root,"tenant-a","create-once",Map.of("name","changed")).andExpect(status().isConflict());
        jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        try {
            call(HttpMethod.POST,"/api/management/sources",root,"tenant-a","create-once",input).andExpect(status().isForbidden());
        } finally {
            jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a')");
        }
        call(HttpMethod.GET,"/api/auth/me",root,null,null,null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT token_hash FROM ingestion_sources WHERE id=?",String.class,sid(first))).isEqualTo(Digests.sha256(secret));
        capture(first,"before","unbound","{}",null).andExpect(status().isOk());
        call(HttpMethod.POST,"/api/ingest/"+sid(first)+"/messages",root,null,null,new IngestionInputs.Envelope("user-token","unbound",null,"{}",null,null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SOURCE_UNAUTHORIZED"));
        call(HttpMethod.GET,"/api/management/sources",secret,"tenant-a",null,null).andExpect(status().isUnauthorized());
        var rotate=Map.of("expected_revision",0); var changed=body(call(HttpMethod.POST,"/api/management/sources/"+sid(first)+"/rotate-token",root,"tenant-a","rotate-once",rotate).andExpect(status().isOk())); assertThat(token(changed)).isNotEqualTo(secret);
        var rotatedReplay=body(call(HttpMethod.POST,"/api/management/sources/"+sid(first)+"/rotate-token",root,"tenant-a","rotate-once",rotate).andExpect(status().isOk())); assertThat(rotatedReplay.get("one_time_token").isNull()).isTrue(); assertThat(jdbc.queryForObject("SELECT token_generation FROM ingestion_sources",Long.class)).isEqualTo(2);
        capture(first,"before","unbound","{}",null).andExpect(status().isUnauthorized()); capture(changed,"after","unbound","{}",null).andExpect(status().isOk());
        call(HttpMethod.PUT,"/api/management/sources/"+sid(first),root,"tenant-a","disable",Map.of("name","disabled","enabled",false,"expected_revision",1)).andExpect(status().isOk()); capture(changed,"after","unbound","{}",null).andExpect(status().isUnauthorized());
        for(String table:List.of("ingestion_sources","idempotency_records","audit_log")) for(var row:jdbc.queryForList("SELECT * FROM "+table)) assertThat(row.toString()).doesNotContain(secret).doesNotContain(token(changed)).doesNotContain(PASSWORD);
        call(HttpMethod.GET,"/api/health",null,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.generic_ingest_enabled").value(true));
    }
    @Test void rawUtf8WhitespaceNulAndOriginalReportedAreLosslessAcrossDedupAndConflicts() throws Exception {
        var source=source("tenant-a",false); String payload=" { \"event\" : \"FIRE\", \"battery\":0, \"中文\":\"🚒\" }\n"; String reported="not-a-date";
        var first=body(capture(source,"stable","opaque/device-一",payload,reported).andExpect(status().isOk())); var duplicate=body(capture(source,"stable","opaque/device-一",payload,reported).andExpect(status().isOk()));
        assertThat(duplicate.get("raw_message_id")).isEqualTo(first.get("raw_message_id")); assertThat(duplicate.get("received_at")).isEqualTo(first.get("received_at")); assertThat(duplicate.get("deduplicated").asBoolean()).isTrue();
        String id=first.get("raw_message_id").asText(); var raw=body(call(HttpMethod.GET,"/api/management/messages/"+id+"/raw",root,"tenant-a",null,null).andExpect(status().isOk())); assertThat(raw.get("payload").asText()).isEqualTo(payload); assertThat(raw.get("payload_bytes").asInt()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8).length); assertThat(raw.get("payload_sha256").asText()).isEqualTo(IngestionLimits.sha256(payload.getBytes(StandardCharsets.UTF_8)));
        assertThat(message(id).at("/message/reported_at").asText()).isEqualTo(reported); assertThat(message(id).at("/message/revision").asLong()).isZero(); assertThat(message(id).at("/message/duplicate_count").asLong()).isEqualTo(1);
        for(String changed:List.of(payload+" ","{}")) capture(source,"stable","opaque/device-一",changed,reported).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MESSAGE_ID_CONFLICT"));
        capture(source,"stable","other",payload,reported).andExpect(status().isConflict()); capture(source,"stable","opaque/device-一",payload,null).andExpect(status().isConflict());
        capture(source,"nul","no-binding","broken\u0000frame","bad\u0000date").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_messages",Long.class)).isEqualTo(2); assertThat(jdbc.queryForObject("SELECT revision FROM ingestion_sources",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT duplicate_count FROM ingestion_sources",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_events",Long.class)).isZero();
    }
    @Test void sourceMessageAndTaskListsUseAllAuthorizedUnitsAndExplicitEmptyTreeIsEmpty() throws Exception {
        var source=source("tenant-a",false);
        String raw=body(capture(source,"scope-message","vendor","{}",null).andExpect(status().isOk())).get("raw_message_id").asText();
        call(HttpMethod.GET,"/api/management/sources",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        call(HttpMethod.GET,"/api/management/messages",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].tenant_id").value("tenant-a"));
        call(HttpMethod.GET,"/api/management/processing-tasks",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        for(String path:List.of("/api/management/sources","/api/management/messages","/api/management/processing-tasks")) call(HttpMethod.GET,path+"?scope_selected=true&scope_tenant_ids=&scope_place_ids=",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call(HttpMethod.GET,"/api/management/messages/"+raw,root,"tenant-a",null,null).andExpect(status().isOk());
    }
    @Test void realPrimarySourcesRaceThroughDatabaseUniquenessAndRequireExplicitDetach() throws Exception {
        var version=published(product().get("id").asText(),"1"); var device=register(version,false); var first=source("tenant-a",false); var second=source("tenant-a",false);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var jobs=executor.invokeAll(List.<Callable<Integer>>of(()->bind(first,device,"vendor-1",0,"racer-one").andReturn().getResponse().getStatus(),()->bind(second,device,"vendor-2",0,"racer-two").andReturn().getResponse().getStatus()),30,TimeUnit.SECONDS);
            var codes=new ArrayList<Integer>(); for(var job:jobs) { assertThat(job.isCancelled()).isFalse(); codes.add(job.get()); } assertThat(codes).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_source_bindings",Long.class)).isEqualTo(1);
        var active=jdbc.queryForMap("SELECT * FROM active_source_bindings"); String owner=(String)active.get("source_id"), binding=(String)active.get("binding_id");
        var detach=Map.of("expected_revision",1,"reason","Explicit release"); var ended=body(call(HttpMethod.POST,"/api/management/sources/"+owner+"/bindings/"+binding+"/detach",root,"tenant-a","detach-once",detach).andExpect(status().isOk())); assertThat(ended.at("/binding/is_active").asBoolean()).isFalse();
        call(HttpMethod.POST,"/api/management/sources/"+owner+"/bindings/"+binding+"/detach",root,"tenant-a","detach-once",detach).andExpect(status().isOk());
        call(HttpMethod.POST,"/api/management/sources/"+owner+"/bindings/"+binding+"/detach",root,"tenant-a","detach-new",Map.of("expected_revision",2,"reason","Again")).andExpect(status().isConflict());
        var other=owner.equals(sid(first)) ? second : first; bind(other,device,"new-vendor",0,"explicit-new-owner").andExpect(status().isOk()); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_bindings",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT product_version_id FROM devices WHERE id=?",String.class,device.get("id").asText())).isEqualTo(version.get("id").asText());
    }
    @Test void capturedVersionDoesNotDriftAfterMigrationAndPreviewNeverMutatesDeviceOrAlarmState() throws Exception {
        var p=product(); var old=published(p.get("id").asText(),"1"); var next=published(p.get("id").asText(),"0.5"); var device=register(old,false); var source=source("tenant-a",false); bind(source,device,"vendor",0,"bind").andExpect(status().isOk());
        var facts=jdbc.queryForMap("SELECT * FROM devices WHERE id=?",device.get("id").asText());
        var receipt=body(capture(source,"captured-old","vendor","{\"event\":\"FIRE\",\"battery\":80}","2026-10-23T03:00:00+08:00").andExpect(status().isOk())); assertThat(receipt.get("binding_state").asText()).isEqualTo("BOUND");
        assertThat(jdbc.queryForMap("SELECT * FROM devices WHERE id=?",device.get("id").asText())).isEqualTo(facts);
        call(HttpMethod.POST,"/api/management/devices/"+device.get("id").asText()+"/migrate-version",root,"tenant-a","migrate",Map.of("product_version_id",next.get("id").asText(),"expected_revision",0,"reason","Explicit new product version")).andExpect(status().isOk());
        var migrated=jdbc.queryForMap("SELECT * FROM devices WHERE id=?",device.get("id").asText()); String id=receipt.get("raw_message_id").asText(); var result=body(preview(id,0,"old-preview").andExpect(status().isOk()));
        assertThat(result.at("/preview/product_version_id").asText()).isEqualTo(old.get("id").asText()); assertThat(result.at("/preview/battery_percent").decimalValue()).isEqualByComparingTo("80"); assertThat(result.at("/preview/event_type").asText()).isEqualTo("FIRE"); assertThat(result.at("/preview/time_quality").asText()).isEqualTo("REPORTED"); assertThat(result.at("/preview/business_status").asText()).isEqualTo("NOT_APPLIED");
        assertThat(jdbc.queryForMap("SELECT * FROM devices WHERE id=?",device.get("id").asText())).isEqualTo(migrated); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_events",Long.class)).isZero();
        assertThat(message(id).get("previews").get(0).has("raw_event")).isFalse(); assertThat(message(id).get("message").has("payload")).isFalse();
        var newReceipt=body(capture(source,"captured-new","vendor","{\"event\":\"FIRE\",\"battery\":80}","9999-12-31T23:59:59.999999999Z").andExpect(status().isOk())); preview(newReceipt.get("raw_message_id").asText(),0,"new-preview").andExpect(status().isOk()).andExpect(jsonPath("$.preview.battery_percent").value(40)).andExpect(jsonPath("$.preview.reported_at").value("9999-12-31T23:59:59.999999999Z"));
        var repeated=body(capture(source,"captured-old","vendor","{\"event\":\"FIRE\",\"battery\":80}","2026-10-23T03:00:00+08:00").andExpect(status().isOk())); assertThat(repeated.get("received_at")).isEqualTo(receipt.get("received_at")); assertThat(repeated.get("parse_status").asText()).isEqualTo("NOT_PREVIEWED"); assertThat(message(id).at("/message/revision").asLong()).isEqualTo(1);
    }
    @Test void currentRawPermissionRedactsOldIdempotentPreviewAndDetailNeverIncludesRawEvent() throws Exception {
        var device=register(published(product().get("id").asText(),"1"),false); var source=source("tenant-a",false); bind(source,device,"vendor",0,"bind").andExpect(status().isOk());
        String id=body(capture(source,"message","vendor","{\"event\":\"FIRE\",\"battery\":0}",null).andExpect(status().isOk())).get("raw_message_id").asText(); var first=body(preview(id,0,"preview-once").andExpect(status().isOk())); assertThat(first.get("preview").has("raw_event")).isTrue(); String attempt=first.at("/preview/id").asText();
        assertThat(message(id).get("previews").get(0).has("raw_event")).isFalse();
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='messages:raw'"); root=login("admin",PASSWORD,"management");
        var replay=body(preview(id,0,"preview-once").andExpect(status().isOk())); assertThat(replay.at("/preview/id").asText()).isEqualTo(attempt); assertThat(replay.get("preview").has("raw_event")).isFalse();
        call(HttpMethod.GET,"/api/management/messages/"+id+"/previews/"+attempt,root,"tenant-a",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.raw_event").doesNotExist());
        call(HttpMethod.GET,"/api/management/messages/"+id+"/raw",root,"tenant-a",null,null).andExpect(status().isForbidden()); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_previews",Long.class)).isEqualTo(1);
        for(var row:jdbc.queryForList("SELECT response_json FROM idempotency_records WHERE operation='message-preview'")) assertThat(row.toString()).doesNotContain("raw_event").doesNotContain("payload\":");
        call(HttpMethod.POST,"/api/management/messages/"+id+"/preview",root,"tenant-a","preview-once",Map.of("expected_revision",0,"reason","changed")).andExpect(status().isConflict());
    }
    @Test void unboundHistoryNeverBackfillsAndMalformedOrDisabledSourceHistoryRemainsPreviewOnly() throws Exception {
        var source=source("tenant-a",false); String unbound=body(capture(source,"unbound","vendor","{}",null).andExpect(status().isOk())).get("raw_message_id").asText(); preview(unbound,0,"unbound-preview").andExpect(status().isConflict());
        var device=register(published(product().get("id").asText(),"1"),false); var binding=body(bind(source,device,"vendor",0,"bind").andExpect(status().isOk())); preview(unbound,0,"still-unbound").andExpect(status().isConflict()); assertThat(message(unbound).at("/message/product_version_id").isNull()).isTrue();
        String malformed=body(capture(source,"malformed","vendor","{", "not-iso").andExpect(status().isOk())).get("raw_message_id").asText();
        call(HttpMethod.POST,"/api/management/sources/"+sid(source)+"/bindings/"+binding.at("/binding/id").asText()+"/detach",root,"tenant-a","detach",Map.of("expected_revision",1,"reason","Explicit detach")).andExpect(status().isOk());
        call(HttpMethod.PUT,"/api/management/sources/"+sid(source),root,"tenant-a","disable",Map.of("name","Retired source","enabled",false,"expected_revision",2)).andExpect(status().isOk());
        preview(malformed,0,"historical").andExpect(status().isOk()).andExpect(jsonPath("$.preview.event_type").value("UNKNOWN")).andExpect(jsonPath("$.preview.battery_percent").isEmpty()).andExpect(jsonPath("$.preview.time_quality").value("INVALID")).andExpect(jsonPath("$.preview.diagnostics[0]").value("PARSE_ERROR"));
        assertThat(message(malformed).at("/message/source_name").asText()).isEqualTo("Synthetic capture source"); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_previews",Long.class)).isEqualTo(1);
    }
    @Test void sourceAuthTenantFencesEnvelopeTypesAndSensitiveInputsFailWithoutRawPersistence() throws Exception {
        var source=source("tenant-a",false); var foreign=source("tenant-b",false);
        call(HttpMethod.POST,"/api/management/sources",root,"tenant-a","removed-source-flag",Map.of("name","invalid","is_simulated",1)).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/ingest/"+sid(source)+"/messages",token(source),null,null,Map.of("source_message_id","reported-number","vendor_device_id","v","payload","{}","reported_at",123)).andExpect(status().isBadRequest());
        for(String payload:List.of("{\"token\":\"synthetic-rejected\"}","{password:broken")) capture(source,"bad-"+UUID.randomUUID(),"vendor",payload,null).andExpect(status().isBadRequest());
        for(Object payload:List.of(1,true,Map.of("event","FIRE"))) call(HttpMethod.POST,"/api/ingest/"+sid(source)+"/messages",token(source),null,null,Map.of("source_message_id","bad-type","vendor_device_id","v","payload",payload)).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/ingest/"+sid(source)+"/messages",token(source),null,null,Map.of("source_message_id","forged","vendor_device_id","v","payload","{}","tenant_id","tenant-b")).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/ingest/"+sid(source)+"/messages",token(source),null,null,Map.of("vendor_device_id","v","payload","{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ingest/"+sid(source)+"/messages").header("Authorization","Bearer "+token(source)).contentType("application/json").content("{\"source_message_id\":\"a\",\"source_message_id\":\"b\",\"vendor_device_id\":\"v\",\"payload\":\"{}\"}")).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/ingest/"+sid(foreign)+"/messages",token(source),null,null,new IngestionInputs.Envelope("wrong-source","v",null,"{}",null,null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SOURCE_UNAUTHORIZED"));
        call(HttpMethod.POST,"/api/ingest/unknown/messages",token(source),null,null,new IngestionInputs.Envelope("unknown","v",null,"{}",null,null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SOURCE_UNAUTHORIZED"));
        call(HttpMethod.GET,"/api/management/sources/"+sid(source)+"/bindings",root,"tenant-b",null,null).andExpect(status().isNotFound());
        call(HttpMethod.PUT,"/api/management/units/tenant-a",root,null,"disable-unit",Map.of("name","Disabled unit","enabled",false,"expected_revision",0)).andExpect(status().isOk());
        capture(source,"disabled-unit","v","{}",null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SOURCE_UNAUTHORIZED")); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_messages",Long.class)).isZero();
        for(var row:jdbc.queryForList("SELECT * FROM audit_log")) assertThat(row.toString()).doesNotContain("synthetic-rejected").doesNotContain(token(source));
    }
    @Test void currentManagementPermissionsAdditionalDeviceWriteAndTenantScopeCannotBeBypassed() throws Exception {
        var source=source("tenant-a",false); var device=register(published(product().get("id").asText(),"1"),false);
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='device:write'");
        bind(source,device,"vendor",0,"missing-device-write").andExpect(status().isForbidden()); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_bindings",Long.class)).isZero();
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='sources:credentials'");
        call(HttpMethod.POST,"/api/management/sources/"+sid(source)+"/rotate-token",root,"tenant-a","missing-credentials",Map.of("expected_revision",0)).andExpect(status().isForbidden()); assertThat(jdbc.queryForObject("SELECT token_generation FROM ingestion_sources",Long.class)).isEqualTo(1);
        String raw=body(capture(source,"scope","v","{}",null).andExpect(status().isOk())).get("raw_message_id").asText();
        call(HttpMethod.GET,"/api/management/messages/"+raw,root,"tenant-b",null,null).andExpect(status().isNotFound()); call(HttpMethod.GET,"/api/management/messages/"+raw+"/raw",root,"tenant-b",null,null).andExpect(status().isNotFound());
        call(HttpMethod.GET,"/api/management/messages?source_id="+sid(source),root,"tenant-b",null,null).andExpect(status().isNotFound());
        call(HttpMethod.POST,"/api/ingest/"+sid(source)+"/messages",token(source),"tenant-b",null,new IngestionInputs.Envelope("forged-header","v",null,"{}",null,null)).andExpect(status().isBadRequest());
        String customer=login("customer-a",INITIAL,"customer"); call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent()); customer=login("customer-a",PASSWORD,"customer");
        call(HttpMethod.GET,"/api/management/sources",customer,null,null,null).andExpect(status().isForbidden()); call(HttpMethod.GET,"/api/management/messages",customer,null,null,null).andExpect(status().isForbidden());
        call(HttpMethod.POST,"/api/ingest/"+sid(source)+"/messages",customer,null,null,new IngestionInputs.Envelope("customer-bearer","v",null,"{}",null,null)).andExpect(status().isUnauthorized());
        jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'"); call(HttpMethod.GET,"/api/management/sources",root,"tenant-a",null,null).andExpect(status().isForbidden());
    }
    @Test void captureVersusUnitDisableAndParallelDuplicatesSettleWithoutDeadlockOrBusinessEffects() throws Exception {
        var source=source("tenant-a",false);
        try(var executor=Executors.newFixedThreadPool(3)) {
            var duplicates=executor.invokeAll(List.<Callable<JsonNode>>of(()->body(capture(source,"parallel","v","{}",null).andExpect(status().isOk())),()->body(capture(source,"parallel","v","{}",null).andExpect(status().isOk()))),30,TimeUnit.SECONDS);
            assertThat(duplicates.get(0).isCancelled()).isFalse(); assertThat(duplicates.get(1).isCancelled()).isFalse(); var one=duplicates.get(0).get(); var two=duplicates.get(1).get(); assertThat(one.get("raw_message_id")).isEqualTo(two.get("raw_message_id")); assertThat(one.get("received_at")).isEqualTo(two.get("received_at")); assertThat(List.of(one.get("deduplicated").asBoolean(),two.get("deduplicated").asBoolean())).containsExactlyInAnyOrder(true,false);
            var race=executor.invokeAll(List.<Callable<Integer>>of(()->capture(source,"race","v","{}",null).andReturn().getResponse().getStatus(),()->call(HttpMethod.PUT,"/api/management/units/tenant-a",root,null,"disable-race",Map.of("name","Unit paused","enabled",false,"expected_revision",0)).andReturn().getResponse().getStatus()),30,TimeUnit.SECONDS);
            for(var job:race) assertThat(job.isCancelled()).isFalse(); assertThat(race.get(0).get()).isIn(200,401); assertThat(race.get(1).get()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_events",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT SUM(revision) FROM devices",Long.class)).isZero();
    }
}
