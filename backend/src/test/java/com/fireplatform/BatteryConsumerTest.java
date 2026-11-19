package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
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

/** N05 消费者：列表筛选与概览计数使用同一份有效电量策略。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:batteryconsumertests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class BatteryConsumerTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap;
    String root, version, placeId;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM alarm_configuration_outcomes");
        jdbc.update("DELETE FROM alarm_reminder_schedule");
        jdbc.update("DELETE FROM resource_linkage_outcomes");
        jdbc.update("DELETE FROM real_device_battery_observations");
        for(String table:List.of("retention_runs","retention_policies","user_messages","device_import_rows","device_import_jobs","device_export_snapshots","device_profile_snapshots","profile_rules","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_change_outbox","alarm_change_heads","alarm_change_publisher_coord","alarm_trace_entries","legacy_ingest_events","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments()); jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A')"); jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a')"); N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        placeId=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Battery fixture "+UUID.randomUUID())).andExpect(status().isOk())).get("id").asText();
        version=publishedWithThreshold("20","LT");
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    String publishedWithThreshold(String threshold,String comparator) throws Exception {
        String name="Batt-"+UUID.randomUUID();
        var product=body(call(HttpMethod.POST,"/api/management/products",root,null,UUID.randomUUID().toString(),Map.of("name",name,"manufacturer","Synthetic","model",name,"description","N05")).andExpect(status().isOk()));
        var v=body(call(HttpMethod.POST,"/api/management/products/"+product.get("id").asText()+"/versions",root,null,UUID.randomUUID().toString(),Map.of()).andExpect(status().isOk()));
        var caps=json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED");
        var thresholdNode=json.createObjectNode().put("parameter_key","battery_low_threshold_percent").put("metric_key","battery_percent").put("unit","%").put("comparator",comparator).put("threshold",new BigDecimal(threshold));
        var edited=body(call(HttpMethod.PUT,"/api/management/product-versions/"+v.get("id").asText(),root,null,UUID.randomUUID().toString(),Map.of("schema",v.get("schema"),"capabilities",caps,"expected_revision",0,"battery_low_threshold",thresholdNode)).andExpect(status().isOk()));
        var samples=List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",BigDecimal.ZERO,List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new BigDecimal("50"),List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
        var tested=body(call(HttpMethod.POST,"/api/management/product-versions/"+v.get("id").asText()+"/test",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",edited.get("revision").asLong(),"samples",samples)).andExpect(status().isOk()));
        return body(call(HttpMethod.POST,"/api/management/product-versions/"+v.get("id").asText()+"/publish",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",tested.get("revision").asLong())).andExpect(status().isOk())).get("id").asText();
    }
    String device(String battery) throws Exception {
        var device=body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Batt device","device_eui","batt-"+UUID.randomUUID(),"address","Synthetic","product_version_id",version,"place_id",placeId)).andExpect(status().isOk())).get("id").asText();
        if(battery!=null) { var observedAt=java.time.OffsetDateTime.now(); var percent=new BigDecimal(battery); jdbc.update("INSERT INTO real_device_battery_observations(id,tenant_id,device_id,observed_at,battery_percent,source_kind,recorded_at) VALUES(?,?,?,?,?,'GENERIC_INGEST',?)",UUID.randomUUID().toString(),"tenant-a",device,observedAt,percent,observedAt); jdbc.update("UPDATE devices SET battery_percent=? WHERE tenant_id=? AND id=?",percent,"tenant-a",device); }
        return device;
    }

    @Test void listFilterAndSummaryShareTheSameEffectivePolicy() throws Exception {
        String low=device("10"), ok=device("50"), missing=device(null);
        var filtered=body(call(HttpMethod.GET,"/api/management/devices?page=1&size=50&battery_status=LOW",root,"tenant-a",null,null).andExpect(status().isOk()));
        var filteredIds=new java.util.HashSet<String>();for(var row:filtered.get("items"))filteredIds.add(row.get("id").asText());
        assertThat(filteredIds).contains(low).doesNotContain(ok,missing);
        var noData=body(call(HttpMethod.GET,"/api/management/devices?page=1&size=50&battery_status=NO_DATA",root,"tenant-a",null,null).andExpect(status().isOk()));
        var noDataIds=new java.util.HashSet<String>();for(var row:noData.get("items"))noDataIds.add(row.get("id").asText());
        assertThat(noDataIds).contains(missing).doesNotContain(low,ok);
        var summary=body(call(HttpMethod.GET,"/api/management/summary",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(summary.get("low_battery").asLong()).isEqualTo(filtered.get("total").asLong());
    }

    @Test void invalidBatteryFilterIsRejected() throws Exception {
        call(HttpMethod.GET,"/api/management/devices?page=1&battery_status=WHATEVER",root,"tenant-a",null,null).andExpect(status().isBadRequest());
    }
}
