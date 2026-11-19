package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.identity.AuthService;
import com.fireplatform.ingestion.IngestionInputs;
import com.fireplatform.product.*;
import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api,ingest","app.storage=h2-dev","APP_GENERIC_INGEST_ENABLED=true","spring.datasource.url=jdbc:h2:mem:producttests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class ProductIntegrationTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser; @Autowired AuthService auth; @Autowired ProductCommands commands;
    @Autowired com.fireplatform.device.DeviceManagementService registrations;
    String root;
    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM alarm_configuration_outcomes");
        jdbc.update("DELETE FROM alarm_reminder_schedule");
        jdbc.update("DELETE FROM resource_linkage_outcomes");
        jdbc.update("DELETE FROM real_device_battery_observations");
        jdbc.update("DELETE FROM legacy_ingest_events");
        for(String table:List.of("message_previews","raw_messages","device_message_watermark","active_source_bindings"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM source_bindings");
        jdbc.update("DELETE FROM ingestion_sources");
        for(String table:List.of("notification_automation_intents","alarm_trace_entries","device_battery_threshold_changes","device_battery_threshold_overrides", "device_place_history", "device_version_history", "device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A'),('tenant-b','Fixture B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc);
        root=login("admin",INITIAL,"management"); call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent()); root=login("admin",PASSWORD,"management");
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES ('product-place-a','tenant-a','开户位置A'),('product-place-b','tenant-b','开户位置B')");
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception {
        var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key);
        if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder);
    }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    JsonNode product() throws Exception { String name="Synthetic-"+UUID.randomUUID(); return body(call(HttpMethod.POST,"/api/management/products",root,null,UUID.randomUUID().toString(),Map.of("name",name,"manufacturer","Synthetic laboratory","model",name,"description","Synthetic mapping only, not a real vendor integration")).andExpect(status().isOk())); }
    JsonNode version(String product,String base) throws Exception { var input=new HashMap<String,Object>(); input.put("base_version_id",base); return body(call(HttpMethod.POST,"/api/management/products/"+product+"/versions",root,null,UUID.randomUUID().toString(),input).andExpect(status().isOk())); }
    JsonNode capabilities() { return json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED"); }
    JsonNode edit(JsonNode version,JsonNode schema) throws Exception { var input=new HashMap<String,Object>(); input.put("expected_revision",version.get("revision").asLong()); input.put("schema",schema); input.put("capabilities",capabilities()); input.put("battery_low_threshold",version.get("battery_low_threshold").isNull()?null:version.get("battery_low_threshold")); return body(call(HttpMethod.PUT,"/api/management/product-versions/"+version.get("id").asText(),root,null,UUID.randomUUID().toString(),input).andExpect(status().isOk())); }
    JsonNode editThreshold(JsonNode version,String comparator,String amount) throws Exception { var input=new HashMap<String,Object>(); input.put("expected_revision",version.get("revision").asLong()); input.put("schema",version.get("schema")); input.put("capabilities",capabilities()); input.put("battery_low_threshold",Map.of("parameter_key","battery_low_threshold_percent","metric_key","battery_percent","unit","%","comparator",comparator,"threshold",new java.math.BigDecimal(amount))); return body(call(HttpMethod.PUT,"/api/management/product-versions/"+version.get("id").asText(),root,null,UUID.randomUUID().toString(),input).andExpect(status().isOk())); }
    List<ProductInputs.Sample> samples() { return List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",new java.math.BigDecimal("0"),List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new java.math.BigDecimal("50"),List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{\"event\":\"unmapped\"}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of())); }
    List<ProductInputs.Sample> samplesWithoutBattery() { return List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",null,List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",null,List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{\"event\":\"unmapped\"}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of())); }
    ResultActions test(JsonNode version,String key,List<ProductInputs.Sample> samples) throws Exception { return call(HttpMethod.POST,"/api/management/product-versions/"+(version.has("id") ? version.get("id") : version.get("version_id")).asText()+"/test",root,null,key,Map.of("expected_revision",version.get("revision").asLong(),"samples",samples)); }
    ResultActions publish(JsonNode version,String key) throws Exception { return call(HttpMethod.POST,"/api/management/product-versions/"+(version.has("id") ? version.get("id") : version.get("version_id")).asText()+"/publish",root,null,key,Map.of("expected_revision",version.get("revision").asLong())); }
    JsonNode published(String product) throws Exception { var draft=edit(version(product,null),parser.defaultSchema()); var tested=body(test(draft,UUID.randomUUID().toString(),samples()).andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true))); return body(publish(tested,UUID.randomUUID().toString()).andExpect(status().isOk())); }
    JsonNode registeredDevice(String label,String versionId) throws Exception {
        String eui="product-"+UUID.randomUUID();
        return body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name",label,"device_eui",eui,"address","synthetic lab","place_id","product-place-a","product_version_id",versionId)).andExpect(status().isOk()));
    }
    JsonNode device(String id,String tenant) throws Exception { JsonNode rows=body(call(HttpMethod.GET,"/api/management/devices",root,tenant,null,null).andExpect(status().isOk())).get("items"); for(JsonNode row:rows) if(row.get("id").asText().equals(id)) return row; throw new AssertionError("Device missing"); }
    ResultActions migrate(JsonNode device,JsonNode version,String tenant,String key) throws Exception { return call(HttpMethod.POST,"/api/management/devices/"+device.get("id").asText()+"/migrate-version",root,tenant,key,Map.of("product_version_id",version.get("id").asText(),"expected_revision",device.get("revision").asLong(),"reason","Explicit synthetic migration")); }
    @Test void stringThresholdTokenIsRejectedWithoutChangingDraftOrCreatingReceipt() throws Exception {
        var draft=version(product().get("id").asText(),null);
        var input=new HashMap<String,Object>();
        input.put("expected_revision",draft.get("revision").asLong());
        input.put("schema",draft.get("schema")); input.put("capabilities",capabilities());
        input.put("battery_low_threshold",Map.of("parameter_key","battery_low_threshold_percent","metric_key","battery_percent","unit","%","comparator","LT","threshold","20"));
        call(HttpMethod.PUT,"/api/management/product-versions/"+draft.get("id").asText(),root,null,"reject-string-threshold",input).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT revision FROM product_versions WHERE id=?",Long.class,draft.get("id").asText())).isZero();
        assertThat(jdbc.queryForObject("SELECT battery_low_threshold FROM product_versions WHERE id=?",java.math.BigDecimal.class,draft.get("id").asText())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idem_key='reject-string-threshold'",Long.class)).isZero();
    }
    @Test void versionThresholdHasStrictCapabilityDecimalCopyAndEvidenceSemantics() throws Exception {
        var product=product(); var draft=version(product.get("id").asText(),null);
        call(HttpMethod.PUT,"/api/management/product-versions/"+draft.get("id").asText(),root,null,"missing-explicit-threshold",Map.of("expected_revision",draft.get("revision").asLong(),"schema",draft.get("schema"),"capabilities",capabilities())).andExpect(status().isBadRequest());
        draft=editThreshold(draft,"LTE","12.123456789012345678901234567890123456");
        assertThat(draft.at("/battery_low_threshold/threshold").decimalValue()).isEqualByComparingTo("12.123456789012345678901234567890123456");
        var tested=body(test(draft,"threshold-test",samples()).andExpect(status().isOk())); assertThat(tested.get("passed").asBoolean()).isTrue();
        var currentVersions=body(call(HttpMethod.GET,"/api/management/products/"+product.get("id").asText()+"/versions",root,null,null,null).andExpect(status().isOk()));
        var changed=editThreshold(currentVersions.at("/items/0"),"LT","0"); assertThat(changed.get("parser_tested").asBoolean()).isFalse(); assertThat(changed.get("parser_test_summary").isNull()).isTrue();
        var latest=body(test(changed,"threshold-test-again",samples()).andExpect(status().isOk()));
        var published=body(publish(latest,"threshold-publish").andExpect(status().isOk()));
        assertThat(published.at("/battery_low_threshold/comparator").asText()).isEqualTo("LT");
        var clone=version(product.get("id").asText(),published.get("id").asText()); assertThat(clone.at("/battery_low_threshold/threshold").decimalValue()).isEqualByComparingTo("0");
        clone=editThreshold(clone,"LTE","100"); assertThat(clone.at("/battery_low_threshold/threshold").decimalValue()).isEqualByComparingTo("100");
        var unsupported=capabilities(); ((com.fasterxml.jackson.databind.node.ObjectNode)unsupported).put("battery","UNSUPPORTED");
        var invalid=new HashMap<String,Object>(); invalid.put("expected_revision",clone.get("revision").asLong()); invalid.put("schema",clone.get("schema")); invalid.put("capabilities",unsupported); invalid.put("battery_low_threshold",clone.get("battery_low_threshold"));
        call(HttpMethod.PUT,"/api/management/product-versions/"+clone.get("id").asText(),root,null,"unsupported-threshold",invalid).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARAMETER_INCOMPATIBLE"));
        invalid.put("battery_low_threshold",null); call(HttpMethod.PUT,"/api/management/product-versions/"+clone.get("id").asText(),root,null,"clear-threshold",invalid).andExpect(status().isOk());
    }
    @Test void valuedDeviceOverrideBlocksMigrationToUnsupportedBatteryCapabilityUntilClear() throws Exception {
        var product=product(); var supported=published(product.get("id").asText()); var device=registeredDevice("Override compatibility",supported.get("id").asText()); String deviceId=device.get("id").asText();
        var threshold=Map.of("parameter_key","battery_low_threshold_percent","metric_key","battery_percent","unit","%","comparator","LT","threshold",new java.math.BigDecimal("20"));
        call(HttpMethod.PUT,"/api/management/devices/"+deviceId+"/battery-low-threshold",root,"tenant-a","set-before-migrate",Map.of("expected_revision",0,"battery_low_threshold",threshold,"reason","Compatibility guard")).andExpect(status().isOk());
        var draft=version(product.get("id").asText(),null); var caps=capabilities(); ((com.fasterxml.jackson.databind.node.ObjectNode)caps).put("battery","UNSUPPORTED");
        var input=new HashMap<String,Object>(); input.put("expected_revision",draft.get("revision").asLong());input.put("schema",parser.defaultSchema());input.put("capabilities",caps);input.put("battery_low_threshold",null);
        var edited=body(call(HttpMethod.PUT,"/api/management/product-versions/"+draft.get("id").asText(),root,null,"unsupported-version-edit",input).andExpect(status().isOk()));
        var tested=body(test(edited,"unsupported-version-test",samplesWithoutBattery()).andExpect(status().isOk()));var unsupported=body(publish(tested,"unsupported-version-publish").andExpect(status().isOk()));
        var current=device(deviceId,"tenant-a");migrate(current,unsupported,"tenant-a","blocked-migration").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARAMETER_INCOMPATIBLE"));
        assertThat(device(deviceId,"tenant-a").get("revision").asLong()).isEqualTo(current.get("revision").asLong());
        var clear=new HashMap<String,Object>();clear.put("expected_revision",1);clear.put("battery_low_threshold",null);clear.put("reason","Explicit compatibility clear");
        call(HttpMethod.PUT,"/api/management/devices/"+deviceId+"/battery-low-threshold",root,"tenant-a","clear-before-migrate",clear).andExpect(status().isOk());
        assertThat(body(migrate(device(deviceId,"tenant-a"),unsupported,"tenant-a","migrate-after-clear").andExpect(status().isOk())).get("product_version_id").asText()).isEqualTo(unsupported.get("id").asText());
    }
    @Test void deviceOverridePrecedesPublishedThresholdAndClearRestoresVersionSource() throws Exception {
        var product=product(); var draft=editThreshold(version(product.get("id").asText(),null),"LTE","20");
        var tested=body(test(draft,"projection-test",samples()).andExpect(status().isOk())); var live=body(publish(tested,"projection-publish").andExpect(status().isOk()));
        var device=registeredDevice("Projection source",live.get("id").asText()); String deviceId=device.get("id").asText();
        long deviceRevision=device.get("revision").asLong();
        jdbc.update("INSERT INTO real_device_battery_observations(id,tenant_id,device_id,observed_at,battery_percent,source_kind,recorded_at) VALUES(?,?,?,?,?,'GENERIC_INGEST',?)",UUID.randomUUID().toString(),"tenant-a",deviceId,java.time.OffsetDateTime.now(),new java.math.BigDecimal("15"),java.time.OffsetDateTime.now());
        jdbc.update("UPDATE devices SET battery_percent=15,last_message_at=? WHERE id=?",java.time.OffsetDateTime.now(),deviceId);
        var override=Map.of("parameter_key","battery_low_threshold_percent","metric_key","battery_percent","unit","%","comparator","LTE","threshold",new java.math.BigDecimal("10"));
        call(HttpMethod.PUT,"/api/management/devices/"+deviceId+"/battery-low-threshold",root,"tenant-a","projection-set",Map.of("expected_revision",0,"battery_low_threshold",override,"reason","Projection source test")).andExpect(status().isOk());
        var effective=device(deviceId,"tenant-a");assertThat(effective.at("/battery_policy/source_scope").asText()).isEqualTo("DEVICE");assertThat(effective.at("/battery_policy/status").asText()).isEqualTo("NORMAL");
        var clear=new HashMap<String,Object>();clear.put("expected_revision",1);clear.put("battery_low_threshold",null);clear.put("reason","Restore product threshold");
        call(HttpMethod.PUT,"/api/management/devices/"+deviceId+"/battery-low-threshold",root,"tenant-a","projection-clear",clear).andExpect(status().isOk());
        var restored=device(deviceId,"tenant-a");assertThat(restored.at("/battery_policy/source_scope").asText()).isEqualTo("PRODUCT_VERSION");assertThat(restored.at("/battery_policy/source_id").asText()).isEqualTo(live.get("id").asText());assertThat(restored.at("/battery_policy/source_revision").asLong()).isEqualTo(live.get("revision").asLong());assertThat(restored.at("/battery_policy/threshold").decimalValue()).isEqualByComparingTo("20");assertThat(restored.at("/battery_policy/status").asText()).isEqualTo("LOW");
        var view=body(call(HttpMethod.GET,"/api/management/devices/"+deviceId+"/battery-low-threshold",root,"tenant-a",null,null).andExpect(status().isOk()));assertThat(view.at("/override/revision").asLong()).isEqualTo(2);assertThat(view.at("/override/has_value").asBoolean()).isFalse();assertThat(view.at("/effective/source_scope").asText()).isEqualTo("PRODUCT_VERSION");
        assertThat(restored.get("revision").asLong()).isEqualTo(deviceRevision);
    }
    @Test void evidenceBindsRevisionEditsInvalidateAndPublishedVersionIsImmutable() throws Exception {
        var p=product(); var draft=version(p.get("id").asText(),null); assertThat(draft.get("parser_tested").asBoolean()).isFalse(); assertThat(draft.get("parser_test_summary").isNull()).isTrue();
        publish(draft,"no-evidence").andExpect(status().isConflict()); draft=edit(draft,parser.defaultSchema());
        var incomplete=body(test(draft,"incomplete",List.of(samples().getFirst())).andExpect(status().isOk())); assertThat(incomplete.get("passed").asBoolean()).isFalse(); assertThat(incomplete.at("/results/0/passed").asBoolean()).isTrue();
        publish(incomplete,"incomplete-publish").andExpect(status().isConflict());
        var tested=body(test(incomplete,"full-test",samples()).andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true)));
        assertThat(tested.get("revision").asLong()).isEqualTo(3);
        JsonNode current=body(call(HttpMethod.GET,"/api/management/products/"+p.get("id").asText()+"/versions",root,null,null,null).andExpect(status().isOk())).get("items").get(0);
        assertThat(current.at("/parser_test_summary/tested_revision").asLong()).isEqualTo(3); assertThat(current.at("/parser_test_summary/samples").size()).isEqualTo(4);
        var changed=edit(current,parser.defaultSchema()); assertThat(changed.get("parser_tested").asBoolean()).isFalse(); assertThat(changed.get("parser_test_summary").isNull()).isTrue();
        publish(changed,"stale-evidence").andExpect(status().isConflict()); test(incomplete,"full-test",samples()).andExpect(status().isConflict());
        var retested=body(test(changed,"fresh-test",samples()).andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true)));
        var live=body(publish(retested,"publish-once").andExpect(status().isOk())); assertThat(live.get("status").asText()).isEqualTo("PUBLISHED"); assertThat(live.get("revision").asLong()).isEqualTo(retested.get("revision").asLong()+1);
        assertThat(body(publish(retested,"publish-once").andExpect(status().isOk()))).isEqualTo(live);
        test(live,"immutable-test",samples()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_IMMUTABLE"));
        call(HttpMethod.PUT,"/api/management/product-versions/"+live.get("id").asText(),root,null,"immutable-edit",Map.of("expected_revision",live.get("revision").asLong(),"schema",parser.defaultSchema(),"capabilities",capabilities(),"battery_low_threshold",json.nullNode())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_IMMUTABLE"));
        var clone=version(p.get("id").asText(),live.get("id").asText()); assertThat(clone.get("status").asText()).isEqualTo("DRAFT"); assertThat(clone.get("version_number").asLong()).isEqualTo(2); assertThat(clone.get("parser_test_summary").isNull()).isTrue(); assertThat(clone.get("parser_tested").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_events",Long.class)).isZero();
        for(var row:jdbc.queryForList("SELECT response_json FROM idempotency_records")) assertThat(row.toString()).doesNotContain(PASSWORD).doesNotContain(INITIAL);
    }
    @Test void exactDecimalEvidenceSurvivesStorageAndPublicationRevalidation() throws Exception {
        var schema=parser.defaultSchema(); ((com.fasterxml.jackson.databind.node.ObjectNode)schema.get("battery")).put("scale",new java.math.BigDecimal("0.1"));
        var draft=edit(version(product().get("id").asText(),null),schema); var cases=new ArrayList<>(samples());
        cases.set(0,new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":1.0000000000000001}","FIRE",new java.math.BigDecimal("0.10000000000000001"),List.of()));
        cases.set(1,new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new java.math.BigDecimal("5"),List.of()));
        var tested=body(test(draft,"exact-decimal-test",cases).andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true)));
        assertThat(tested.at("/results/0/battery_percent").decimalValue()).isEqualByComparingTo("0.10000000000000001");
        publish(tested,"exact-decimal-publish").andExpect(status().isOk()).andExpect(jsonPath("$.parser_tested").value(true));
    }
    @Test void registrationRejectsMissingPlaceWithoutCreatingDeviceOrReceipt() throws Exception {
        var live=published(product().get("id").asText());
        var input=new HashMap<String,Object>(Map.of("name","No place","device_eui","no-place","address","lab","product_version_id",live.get("id").asText()));
        call(HttpMethod.POST,"/api/management/devices",root,"tenant-a","missing-place",input).andExpect(status().isBadRequest());
        for(String place:Arrays.asList(null,""," \t","p".repeat(65),"missing-place","product-place-b")) {
            input.put("place_id",place);
            call(HttpMethod.POST,"/api/management/devices",root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isBadRequest());
            var actor=auth.authenticate(root);
            var direct=new com.fireplatform.device.DeviceInputs.Register("No place","no-place","lab",place,live.get("id").asText(),null,null);
            assertThatThrownBy(()->registrations.register(actor,"tenant-a",UUID.randomUUID().toString(),direct,"registration-placement-test","management"))
                .isInstanceOfSatisfying(ApiError.class,e->assertThat(e.status).isEqualTo(400));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui='no-place'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation='device-register'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_version_history",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='device-register' AND outcome='SUCCESS'",Long.class)).isZero();
    }

    @Test void publishedOnlyRegistrationStoresUnknownAndMigrationRequiresExplicitRequest() throws Exception {
        var p=product(); var draft=version(p.get("id").asText(),null);
        var input=new HashMap<String,Object>(Map.of("name","Real pending device","device_eui","brand-safe-ascii:001","address","lab","place_id","product-place-a","product_version_id",draft.get("id").asText()));
        call(HttpMethod.POST,"/api/management/devices",root,"tenant-a","unpublished",input).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_NOT_PUBLISHED"));
        var live=published(p.get("id").asText()); input.put("product_version_id",live.get("id").asText());
        var registered=body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a","register",input).andExpect(status().isOk())); String id=registered.get("id").asText();
        assertThat(registered.get("physical_state").asText()).isEqualTo("UNKNOWN"); assertThat(registered.get("connectivity").asText()).isEqualTo("UNACTIVATED"); assertThat(registered.get("integration_state").asText()).isEqualTo("PENDING_INTEGRATION"); assertThat(registered.get("last_message_at").isNull()).isTrue(); assertThat(registered.get("battery_percent").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,id)).isEqualTo("UNKNOWN");
        assertThat(registered.get("place_id").asText()).isEqualTo("product-place-a");
        assertThat(jdbc.queryForObject("SELECT place_id FROM devices WHERE id=?",String.class,id)).isEqualTo("product-place-a");
        var history=body(call(HttpMethod.GET,"/api/management/devices/"+id+"/version-history",root,"tenant-a",null,null).andExpect(status().isOk())).get("items"); assertThat(history.size()).isEqualTo(1); assertThat(history.get(0).get("from_version_id").isNull()).isTrue(); assertThat(history.get(0).get("reason").asText()).isEqualTo("REGISTER");
        assertThat(body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a","register",input).andExpect(status().isOk()))).isEqualTo(registered);
        var otherTenantInput=new HashMap<>(input); otherTenantInput.put("place_id","product-place-b");
        call(HttpMethod.POST,"/api/management/devices",root,"tenant-b","duplicate-eui",otherTenantInput).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_EUI_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT legacy_integration_state FROM devices WHERE id=?",String.class,id)).isNull();
        var newer=published(p.get("id").asText()); assertThat(device(id,"tenant-a").get("product_version_id").asText()).isEqualTo(live.get("id").asText());
        var migrated=body(migrate(registered,newer,"tenant-a","explicit").andExpect(status().isOk())); assertThat(migrated.get("physical_state").asText()).isEqualTo("UNKNOWN");
        migrate(migrated,newer,"tenant-a","same-version").andExpect(status().isConflict());
    }
    @Test void realAppliedFactsRemainUnchangedAcrossExplicitProductMigration() throws Exception {
        var p=product(); var first=published(p.get("id").asText()); var second=published(p.get("id").asText());
        var device=body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a","real-migration-device",Map.of("name","Bound real device","device_eui","real-"+UUID.randomUUID(),"address","lab","place_id","product-place-a","product_version_id",first.get("id").asText())).andExpect(status().isOk()));
        var source=body(call(HttpMethod.POST,"/api/management/sources",root,"tenant-a","real-migration-source",Map.of("name","Real capture source")).andExpect(status().isOk()));
        String vendor="migration-"+UUID.randomUUID();
        call(HttpMethod.POST,"/api/management/sources/"+source.at("/source/id").asText()+"/bindings",root,"tenant-a","real-migration-bind",Map.of("device_id",device.get("id").asText(),"vendor_device_id",vendor,"expected_revision",0,"reason","Real migration fixture")).andExpect(status().isOk());
        String raw=body(call(HttpMethod.POST,"/api/ingest/"+source.at("/source/id").asText()+"/messages",source.get("one_time_token").asText(),null,null,new IngestionInputs.Envelope("migration-"+UUID.randomUUID(),vendor,null,"{\"event\":\"FIRE\",\"battery\":20}",1L,"2026-10-31T10:00:00+08:00")).andExpect(status().isOk())).get("raw_message_id").asText();
        String alarm=body(call(HttpMethod.POST,"/api/management/messages/"+raw+"/apply",root,"tenant-a","real-migration-apply",Map.of("expected_revision",0,"reason","Real migration fixture")).andExpect(status().isOk())).get("alarm_id").asText();
        var before=device(device.get("id").asText(),"tenant-a"); var alarmBefore=jdbc.queryForMap("SELECT * FROM alarms WHERE id=?",alarm);
        assertThat(before.get("integration_state").asText()).isEqualTo("CONNECTED");
        var migrated=body(migrate(before,second,"tenant-a","real-version-migration").andExpect(status().isOk()));
        assertThat(migrated.get("physical_state").asText()).isEqualTo("FIRE");
        for(String key:List.of("last_message_at","battery_percent")) assertThat(migrated.get(key)).isEqualTo(before.get(key));
        assertThat(migrated.get("revision").asLong()).isEqualTo(before.get("revision").asLong()+1);
        assertThat(jdbc.queryForMap("SELECT * FROM alarms WHERE id=?",alarm)).isEqualTo(alarmBefore);
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,device.get("id").asText())).isEqualTo("PENDING_INTEGRATION");
        var other=published(product().get("id").asText()); migrate(migrated,other,"tenant-a","cross-product").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_MISMATCH"));
        migrate(before,first,"tenant-a","stale-device").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        call(HttpMethod.GET,"/api/management/devices/"+device.get("id").asText()+"/version-history",root,"tenant-b",null,null).andExpect(status().isNotFound());
    }

    @Test void concurrentCloneNumbersAreUniqueAndConcurrentPublishCommitsOnce() throws Exception {
        var p=product(); String product=p.get("id").asText(); var draft=edit(version(product,null),parser.defaultSchema()); var tested=body(test(draft,"tested",samples()).andExpect(status().isOk()));
        try(var executor=Executors.newFixedThreadPool(4)) {
            var jobs=executor.invokeAll(List.<Callable<Integer>>of(()->publish(tested,"publisher-one").andReturn().getResponse().getStatus(),()->publish(tested,"publisher-two").andReturn().getResponse().getStatus()),30,TimeUnit.SECONDS);
            var statuses=new ArrayList<Integer>(); for(var job:jobs) { assertThat(job.isCancelled()).isFalse(); statuses.add(job.get()); } assertThat(statuses).containsExactlyInAnyOrder(200,409);
            var clones=executor.invokeAll(List.<Callable<Long>>of(()->version(product,null).get("version_number").asLong(),()->version(product,null).get("version_number").asLong(),()->version(product,null).get("version_number").asLong()),30,TimeUnit.SECONDS);
            var numbers=new HashSet<Long>(); for(var clone:clones) { assertThat(clone.isCancelled()).isFalse(); numbers.add(clone.get()); } assertThat(numbers).containsExactlyInAnyOrder(2L,3L,4L);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE status='PUBLISHED'",Long.class)).isEqualTo(1);
    }
    @Test void secretMalformedAndInvalidSchemasDoNotPersistOrAlterEvidence() throws Exception {
        var draft=edit(version(product().get("id").asText(),null),parser.defaultSchema()); String id=draft.get("id").asText();
        for(String payload:List.of("{\"password\":\"synthetic-sensitive-marker\"}","{token:broken")) test(draft,"rejected-"+UUID.randomUUID(),List.of(new ProductInputs.Sample("unsafe","MALFORMED",payload,"UNKNOWN",null,List.of()))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT revision FROM product_versions WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT test_summary_json FROM product_versions WHERE id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation='version-test'",Long.class)).isZero();
        var invalid=parser.defaultSchema().deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("script","not executed");
        call(HttpMethod.PUT,"/api/management/product-versions/"+id,root,null,"script",Map.of("expected_revision",1,"schema",invalid,"capabilities",capabilities(),"battery_low_threshold",json.nullNode())).andExpect(status().isBadRequest());
        for(var row:jdbc.queryForList("SELECT * FROM audit_log")) assertThat(row.toString()).doesNotContain("synthetic-sensitive-marker").doesNotContain(PASSWORD);
    }
    @Test void authorizationAudienceTenantDisableAndMetadataFieldRestrictionsRemainServerEnforced() throws Exception {
        call(HttpMethod.POST,"/api/management/users",root,"tenant-a","product-customer-fixture",Map.of("username","customer-a","name","Product fixture customer","initial_password",INITIAL,"permissions",List.of("device:read"))).andExpect(status().isOk());
        String customer=login("customer-a",INITIAL,"customer"); call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent()); customer=login("customer-a",PASSWORD,"customer");
        call(HttpMethod.GET,"/api/management/products",customer,null,null,null).andExpect(status().isForbidden());
        call(HttpMethod.POST,"/api/management/devices",customer,null,"forbidden",Map.of()).andExpect(status().isForbidden());
        var p=product(); var live=published(p.get("id").asText());
        call(HttpMethod.POST,"/api/management/devices",root,null,"missing-tenant",Map.of("name","x","device_eui","x","address","x","place_id","product-place-a","product_version_id",live.get("id").asText())).andExpect(status().isBadRequest());
        var metadataTarget=registeredDevice("Metadata-only fixture",live.get("id").asText());
        call(HttpMethod.PUT,"/api/management/devices/"+metadataTarget.get("id").asText(),root,"tenant-a","forged",Map.of("name","x","address","x","physical_state","FIRE","expected_revision",0)).andExpect(status().isBadRequest());
        assertThat(device(metadataTarget.get("id").asText(),"tenant-a").get("physical_state").asText()).isEqualTo("UNKNOWN");
        call(HttpMethod.PUT,"/api/management/units/tenant-a",root,null,"disable",Map.of("name","paused","enabled",false,"expected_revision",0)).andExpect(status().isOk());
        call(HttpMethod.POST,"/api/management/devices",root,"tenant-a","disabled",Map.of("name","x","device_eui","x","address","x","place_id","product-place-a","product_version_id",live.get("id").asText())).andExpect(status().isForbidden());
        var identity=auth.authenticate(root); jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='products:write'");
        assertThatThrownBy(()->commands.create(identity,"stale-authority",new ProductInputs.CreateProduct("x","x","x",""),"request")).isInstanceOfSatisfying(ApiError.class,e->assertThat(e.status).isEqualTo(403));
    }
    @Test void globalDuplicateDetectionAndBodyByteCapIncludeChunkedAndMatrixPaths() throws Exception {
        var draft=version(product().get("id").asText(),null); String id=draft.get("id").asText(); String duplicate="{\"expected_revision\":0,\"schema\":"+parser.defaultSchema()+",\"schema\":"+parser.defaultSchema()+",\"capabilities\":"+capabilities()+",\"battery_low_threshold\":null}";
        mvc.perform(put("/api/management/product-versions/"+id).header("Authorization","Bearer "+root).header("Idempotency-Key","duplicate-root-schema").contentType("application/json").content(duplicate)).andExpect(status().isBadRequest());
        byte[] tooLarge=("{\"padding\":\""+"x".repeat(Limits.MAX_JSON_BODY_BYTES)+"\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api;ignored/management/products").header("Authorization","Bearer "+root).contentType("application/json").content(tooLarge)).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        String item="{\"name\":\"row\",\"case_kind\":\"FIRE\",\"payload\":\"{}\",\"expected_event\":\"UNKNOWN\",\"expected_battery_percent\":null}";
        byte[] chunked=("{\"expected_revision\":0,\"samples\":["+(item+",").repeat(23000)+item+"]}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(context->{ var request=new MockHttpServletRequest(context,"POST","/api/management/product-versions/"+id+"/test") { @Override public long getContentLengthLong() { return -1; } @Override public int getContentLength() { return -1; } }; request.setContentType("application/json"); request.setContent(chunked); request.addHeader("Authorization","Bearer "+root); request.addHeader("Idempotency-Key","chunked"); request.addHeader("Transfer-Encoding","chunked"); return request; }).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        assertThat(jdbc.queryForObject("SELECT test_summary_json FROM product_versions WHERE id=?",String.class,id)).isNull();
    }
}
