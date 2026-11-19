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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** N12：设备批量导入（逐行错误、整批成败、幂等重放）与不可变导出快照。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:bulktests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceBulkTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser;
    String root; String version;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM alarm_configuration_outcomes");
        jdbc.update("DELETE FROM alarm_reminder_schedule");
        jdbc.update("DELETE FROM resource_linkage_outcomes");
        jdbc.update("DELETE FROM real_device_battery_observations");
        for(String table:List.of("device_import_rows","device_import_jobs","device_export_snapshots","device_profile_snapshots","profile_rules","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_trace_entries","legacy_ingest_events","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments()); jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A'),('tenant-b','Fixture B')"); jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')"); N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        version=published();
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES ('bulk-place-a','tenant-a','导入位置A'),('bulk-place-b','tenant-b','导入位置B')");
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    String published() throws Exception {
        String name="Bulk-"+UUID.randomUUID();
        var product=body(call(HttpMethod.POST,"/api/management/products",root,null,UUID.randomUUID().toString(),Map.of("name",name,"manufacturer","Synthetic","model",name,"description","N12")).andExpect(status().isOk()));
        var v=body(call(HttpMethod.POST,"/api/management/products/"+product.get("id").asText()+"/versions",root,null,UUID.randomUUID().toString(),Map.of()).andExpect(status().isOk()));
        var caps=json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED");
        var edited=body(call(HttpMethod.PUT,"/api/management/product-versions/"+v.get("id").asText(),root,null,UUID.randomUUID().toString(),Map.of("schema",v.get("schema"),"capabilities",caps,"expected_revision",0,"battery_low_threshold",json.nullNode())).andExpect(status().isOk()));
        var samples=List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",BigDecimal.ZERO,List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new BigDecimal("50"),List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
        var tested=body(call(HttpMethod.POST,"/api/management/product-versions/"+v.get("id").asText()+"/test",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",edited.get("revision").asLong(),"samples",samples)).andExpect(status().isOk()));
        return body(call(HttpMethod.POST,"/api/management/product-versions/"+v.get("id").asText()+"/publish",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",tested.get("revision").asLong())).andExpect(status().isOk())).get("id").asText();
    }
    String csv(String... rows) { return "name,device_eui,address,product_version_id,place_id\n"+String.join("\n",rows)+"\n"; }
    JsonNode preview(String body) throws Exception { return body(call(HttpMethod.POST,"/api/management/devices/import/preview",root,"tenant-a",null,Map.of("csv",body)).andExpect(status().isOk())); }
    ResultActions confirm(String body,String sha) throws Exception { return call(HttpMethod.POST,"/api/management/devices/import/confirm",root,"tenant-a",UUID.randomUUID().toString(),Map.of("csv",body,"expected_sha256",sha,"reason","N12 导入")); }

    @Test void obsoleteTemplateCannotCreateUnplacedDevices() throws Exception {
        String oldCsv="name,device_eui,address,product_version_id\nUnplaced,unplaced-"+UUID.randomUUID()+",Addr,"+version+"\n";
        call(HttpMethod.POST,"/api/management/devices/import/preview",root,"tenant-a",null,Map.of("csv",oldCsv))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE name='Unplaced'",Long.class)).isZero();
    }

    @Test void previewReportsPerRowErrorsWithoutWriting() throws Exception {
        String eui="EU-"+UUID.randomUUID().toString().substring(0,8);
        String body=csv("Good device,"+eui+",Addr,"+version+",bulk-place-a","Dup,"+eui+",Addr,"+version+",bulk-place-a","Bad version,EU-X1,Addr,missing-version,bulk-place-a");
        var preview=preview(body);
        assertThat(preview.get("ok_count").asInt()).isEqualTo(1);
        assertThat(preview.get("error_count").asInt()).isEqualTo(2);
        assertThat(preview.at("/rows/1/error").asText()).isEqualTo("DUPLICATE_IN_BATCH");
        assertThat(preview.at("/rows/2/error").asText()).isEqualTo("VERSION_NOT_PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND device_eui=?",Long.class,eui)).isZero();
    }

    @Test void confirmIsAllOrNothingAndRejectsErroredBatch() throws Exception {
        String body=csv("Ok,EU-"+UUID.randomUUID().toString().substring(0,8)+",Addr,"+version+",bulk-place-a","Bad,NOT VALID EUI,Addr,"+version+",bulk-place-a");
        var preview=preview(body);
        confirm(body,preview.get("source_sha256").asText()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_import_jobs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM device_import_jobs",String.class)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND name='Ok'",Long.class)).isZero();
    }

    @Test void validBatchImportsAndReplaysBySourceHash() throws Exception {
        String eui1="EU-"+UUID.randomUUID().toString().substring(0,8), eui2="EU-"+UUID.randomUUID().toString().substring(0,8);
        String body=csv("A1,"+eui1+",Addr,"+version+",bulk-place-a","A2,"+eui2+",Addr,"+version+",bulk-place-a");
        var preview=preview(body);
        var job=body(confirm(body,preview.get("source_sha256").asText()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND device_eui IN (?,?)",Long.class,eui1,eui2)).isEqualTo(2);
        var rows=body(call(HttpMethod.GET,"/api/management/devices/imports/"+job.get("id").asText()+"/rows",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(rows).hasSize(2);
        assertThat(rows.at("/0/status").asText()).isEqualTo("OK");
        assertThat(rows.at("/0/place_id").asText()).isEqualTo("bulk-place-a");
        assertThat(jdbc.queryForList("SELECT place_id FROM devices WHERE device_eui IN (?,?)",String.class,eui1,eui2)).containsExactly("bulk-place-a","bulk-place-a");
        // 幂等重放：同一源哈希返回同一任务，不重复建行。
        var replay=body(confirm(body,preview.get("source_sha256").asText()).andExpect(status().isOk()));
        assertThat(replay.get("id").asText()).isEqualTo(job.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND device_eui IN (?,?)",Long.class,eui1,eui2)).isEqualTo(2);
        jdbc.update("UPDATE devices SET place_id=NULL WHERE device_eui IN (?,?)",eui1,eui2);
        var historical=body(call(HttpMethod.GET,"/api/management/devices/imports/"+job.get("id").asText()+"/rows",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(historical.at("/0/place_id").asText()).isEqualTo("bulk-place-a");
    }
    @Test void classifiedImportsPersistManualFieldsAndRejectControlCharactersAsWholeBatch() throws Exception {
        String eui="Classified-"+UUID.randomUUID();
        String body="name,device_eui,address,product_version_id,place_id,system_type,device_category\n"
            +"Classified,"+eui+",Addr,"+version+",bulk-place-a,Fire alarm,Smoke detector\n";
        var preview=preview(body);
        assertThat(preview.at("/rows/0/system_type").asText()).isEqualTo("Fire alarm");
        assertThat(preview.at("/rows/0/device_category").asText()).isEqualTo("Smoke detector");
        var job=body(confirm(body,preview.get("source_sha256").asText()).andExpect(status().isOk()));
        assertThat(jdbc.queryForMap("SELECT system_type,device_category FROM devices WHERE device_eui=?",eui)).containsEntry("system_type","Fire alarm").containsEntry("device_category","Smoke detector");
        var historical=body(call(HttpMethod.GET,"/api/management/devices/imports/"+job.get("id").asText()+"/rows",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(historical.at("/0/system_type").asText()).isEqualTo("Fire alarm");
        assertThat(historical.at("/0/device_category").asText()).isEqualTo("Smoke detector");
        String invalid="name,device_eui,address,product_version_id,place_id,system_type,device_category\n"
            +"Bad,"+eui+"-bad,Addr,"+version+",bulk-place-a,Fire\talarm,Smoke detector\n";
        var rejected=preview(invalid);
        assertThat(rejected.get("error_count").asInt()).isEqualTo(1);
        assertThat(rejected.at("/rows/0/error").asText()).isEqualTo("CLASSIFICATION_INVALID");
    }

    @Test void invalidPlacesRejectWholeBatchAndPreserveRowEvidence() throws Exception {
        String prefix="placement-"+UUID.randomUUID();
        String overlong="p".repeat(65);
        String csv=csv(
            "Good,"+prefix+"-ok,Addr,"+version+",bulk-place-a",
            "Blank,"+prefix+"-blank,Addr,"+version+",  ",
            "Missing,"+prefix+"-missing,Addr,"+version+",missing-place",
            "Foreign,"+prefix+"-foreign,Addr,"+version+",bulk-place-b",
            "Long,"+prefix+"-long,Addr,"+version+","+overlong);
        var preview=preview(csv);
        assertThat(preview.get("ok_count").asInt()).isEqualTo(1);
        assertThat(preview.get("error_count").asInt()).isEqualTo(4);
        assertThat(preview.at("/rows/1/error").asText()).isEqualTo("PLACE_REQUIRED");
        for(int i=2;i<5;i++) assertThat(preview.at("/rows/"+i+"/error").asText()).isEqualTo("PLACE_NOT_FOUND");
        confirm(csv,preview.get("source_sha256").asText()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui LIKE ?",Long.class,prefix+"%")).isZero();
        assertThat(jdbc.queryForObject("SELECT place_id FROM device_import_rows WHERE row_no=5",String.class)).isEqualTo(overlong);
    }

    @Test void placeDeletedAfterPreviewRejectsBatchThenRepairedPlaceAllowsSameFile() throws Exception {
        String csv=csv("Good,recheck-good,Addr,"+version+",bulk-place-a","Gone,recheck-gone,Addr,"+version+",temp-place");
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES ('temp-place','tenant-a','待删除')");
        var preview=preview(csv);
        assertThat(preview.get("error_count").asInt()).isZero();
        jdbc.update("DELETE FROM places WHERE id='temp-place'");
        confirm(csv,preview.get("source_sha256").asText()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui IN ('recheck-good','recheck-gone')",Long.class)).isZero();
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES ('temp-place','tenant-a','恢复可用')");
        var repaired=preview(csv);
        assertThat(repaired.path("error_count").asInt()).isZero();
        assertThat(repaired.path("source_sha256").asText()).isEqualTo(preview.path("source_sha256").asText());
        confirm(csv,repaired.path("source_sha256").asText()).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui IN ('recheck-good','recheck-gone')",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_import_jobs WHERE tenant_id='tenant-a' AND status='REJECTED'",Long.class)).isEqualTo(1);
    }

}
