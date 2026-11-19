package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.ingestion.IngestionInputs;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.product.ProductInputs;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** N11：画像规则版本、窗口取样、四态分离与不可变历史。 */
@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","app.task-poll-ms=60000","spring.datasource.url=jdbc:h2:mem:profiletests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceProfileTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser;
    String root, placeId;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        for(String table:List.of("alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","real_device_battery_observations","alarm_change_outbox","alarm_change_heads"))jdbc.update("DELETE FROM "+table);
        for(String table:List.of("device_profile_snapshots","profile_rules","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_trace_entries","legacy_ingest_events","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Profile test tenant A'),('tenant-b','Profile test tenant B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        placeId=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Profile fixture "+UUID.randomUUID())).andExpect(status().isOk())).get("id").asText();
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    JsonNode published() throws Exception {
        String name="Profile-"+UUID.randomUUID();
        var product=body(call(HttpMethod.POST,"/api/management/products",root,null,UUID.randomUUID().toString(),Map.of("name",name,"manufacturer","Synthetic","model",name,"description","N11")).andExpect(status().isOk()));
        var version=body(call(HttpMethod.POST,"/api/management/products/"+product.get("id").asText()+"/versions",root,null,UUID.randomUUID().toString(),Map.of()).andExpect(status().isOk()));
        var caps=json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED");
        var edited=body(call(HttpMethod.PUT,"/api/management/product-versions/"+version.get("id").asText(),root,null,UUID.randomUUID().toString(),Map.of("schema",version.get("schema"),"capabilities",caps,"expected_revision",0,"battery_low_threshold",json.nullNode())).andExpect(status().isOk()));
        var samples=List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",BigDecimal.ZERO,List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new BigDecimal("50"),List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
        var tested=body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/test",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",edited.get("revision").asLong(),"samples",samples)).andExpect(status().isOk()));
        return body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/publish",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",tested.get("revision").asLong())).andExpect(status().isOk()));
    }
    record Fixture(JsonNode source,String vendor,JsonNode device) { }
    Fixture fixture() throws Exception {
        var version=published();
        var source=body(call(HttpMethod.POST,"/api/management/sources",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","N11 source")).andExpect(status().isOk()));
        var device=body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","N11 device","device_eui","n11-"+UUID.randomUUID(),"address","Synthetic","product_version_id",version.get("id").asText(),"place_id",placeId)).andExpect(status().isOk()));
        String vendor="vendor-"+UUID.randomUUID();
        call(HttpMethod.POST,"/api/management/sources/"+source.at("/source/id").asText()+"/bindings",root,"tenant-a",UUID.randomUUID().toString(),Map.of("device_id",device.get("id").asText(),"vendor_device_id",vendor,"expected_revision",device.get("revision").asLong(),"reason","N11 binding")).andExpect(status().isOk());
        return new Fixture(source,vendor,device);
    }
    void applyWithBattery(Fixture f,String message,int battery) throws Exception {
        long sequence=Long.parseLong(message.substring(message.lastIndexOf('-')+1));
        String occurred=java.time.OffsetDateTime.now().minusMinutes(1).plusSeconds(sequence).toString();
        var receipt=body(call(HttpMethod.POST,"/api/ingest/"+f.source().at("/source/id").asText()+"/messages",f.source().get("one_time_token").asText(),null,null,new IngestionInputs.Envelope(message,f.vendor(),null,"{\"event\":\"FIRE\",\"battery\":"+battery+"}",sequence,occurred)).andExpect(status().isOk()));
        call(HttpMethod.POST,"/api/management/messages/"+receipt.get("raw_message_id").asText()+"/apply",root,"tenant-a",UUID.randomUUID().toString(),Map.of("expected_revision",0,"reason","N11 观测")).andExpect(status().isOk());
    }
    JsonNode rule(int window,int min) throws Exception { return body(call(HttpMethod.POST,"/api/management/profile-rule",root,"tenant-a",null,Map.of("window_days",window,"min_samples",min,"enabled",true,"reason","N11 规则")).andExpect(status().isOk())); }
    JsonNode recompute(String device) throws Exception { return body(call(HttpMethod.POST,"/api/management/devices/"+device+"/profile/recompute",root,"tenant-a",null,Map.of("reason","N11 重算")).andExpect(status().isOk())); }

    @Test void recomputeRequiresAnEnabledRuleAndDistinguishesMissingFromInsufficient() throws Exception {
        var f=fixture();
        call(HttpMethod.POST,"/api/management/devices/"+f.device().get("id").asText()+"/profile/recompute",root,"tenant-a",null,Map.of("reason","no rule")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_RULE_NOT_CONFIGURED"));
        rule(30,3);
        assertThat(recompute(f.device().get("id").asText()).get("status").asText()).isEqualTo("MISSING");
        applyWithBattery(f,"m-1",40); applyWithBattery(f,"m-2",60);
        var insufficient=recompute(f.device().get("id").asText());
        assertThat(insufficient.get("status").asText()).isEqualTo("INSUFFICIENT");
        assertThat(insufficient.get("sample_count").asInt()).isEqualTo(2);
        assertThat(insufficient.get("mean_percent").isNull()).isTrue();
    }

    @Test void okSnapshotCarriesMinMaxMeanLatestAndRuleVersionBump() throws Exception {
        var f=fixture();
        rule(30,3);
        applyWithBattery(f,"m-1",40); applyWithBattery(f,"m-2",60); applyWithBattery(f,"m-3",50);
        var second=rule(30,2);
        assertThat(second.get("version").asInt()).isEqualTo(2);
        var snapshot=recompute(f.device().get("id").asText());
        assertThat(snapshot.get("status").asText()).isEqualTo("OK");
        assertThat(snapshot.get("sample_count").asInt()).isEqualTo(3);
        assertThat(snapshot.get("min_percent").decimalValue()).isEqualByComparingTo("40");
        assertThat(snapshot.get("max_percent").decimalValue()).isEqualByComparingTo("60");
        assertThat(snapshot.get("mean_percent").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(snapshot.get("latest_percent").decimalValue()).isEqualByComparingTo("50");
        assertThat(snapshot.get("rule_version").asInt()).isEqualTo(2);
        // 历史不可变：每次重算追加一行。
        recompute(f.device().get("id").asText());
        var history=body(call(HttpMethod.GET,"/api/management/devices/"+f.device().get("id").asText()+"/profile/history",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(history).hasSize(2);
        // 不改报警、不停设备。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id=? AND physical_state='UNKNOWN'",Long.class,f.device().get("id").asText())).isZero();
    }

    @Test void realCloudBatteryObservationSupportsVersionlessDeviceWithoutLegacySamples() throws Exception {
        var f=fixture();
        rule(30,1);
        var observed=java.time.OffsetDateTime.now();
        jdbc.update("UPDATE devices SET product_version_id=NULL WHERE id=?",f.device().get("id").asText());
        jdbc.update("INSERT INTO real_device_battery_observations(id,tenant_id,device_id,observed_at,battery_percent,source_kind,recorded_at) VALUES(?,?,?,?,?,'HIKVISION_FIRECLOUD',?)",
                UUID.randomUUID().toString(),"tenant-a",f.device().get("id").asText(),observed,new BigDecimal("37.25"),observed);
        var snapshot=recompute(f.device().get("id").asText());
        assertThat(snapshot.get("status").asText()).isEqualTo("OK");
        assertThat(snapshot.get("sample_count").asInt()).isEqualTo(1);
        assertThat(snapshot.get("latest_percent").decimalValue()).isEqualByComparingTo("37.25");
    }


    @Test void unsupportedCapabilityIsReportedAsUnsupportedNotInsufficient() throws Exception {
        var f=fixture();
        rule(30,1);
        applyWithBattery(f,"m-1",40);
        jdbc.update("UPDATE product_versions SET capabilities_json=? WHERE id=(SELECT product_version_id FROM devices WHERE id=?)","{\"fire\":\"SUPPORTED\",\"recovery\":\"SUPPORTED\",\"battery\":\"PENDING\"}",f.device().get("id").asText());
        assertThat(recompute(f.device().get("id").asText()).get("status").asText()).isEqualTo("UNSUPPORTED");
    }

    @Test void ruleValidationRejectsOutOfRangeAndMissingReason() throws Exception {
        call(HttpMethod.POST,"/api/management/profile-rule",root,"tenant-a",null,Map.of("window_days",0,"min_samples",3,"enabled",true,"reason","x")).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/profile-rule",root,"tenant-a",null,Map.of("window_days",30,"min_samples",0,"enabled",true,"reason","x")).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/profile-rule",root,"tenant-a",null,Map.of("window_days",30,"min_samples",3,"enabled",true,"reason","  ")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_rules",Long.class)).isZero();
    }
}
