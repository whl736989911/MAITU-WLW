package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.ingestion.IngestionInputs;
import com.fireplatform.processing.ReparseRunner;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.product.ProductInputs;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

/** N07：重解析任务——纯解析、无业务写入、租约过期可恢复、尝试有上限。 */
@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.reparse-enabled=true","app.storage=h2-dev","app.task-poll-ms=60000","spring.datasource.url=jdbc:h2:mem:reparsetests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class ReparseJobTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser; @Autowired ReparseRunner runner;
    String root, placeId;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM alarm_configuration_outcomes");
        jdbc.update("DELETE FROM alarm_reminder_schedule");
        jdbc.update("DELETE FROM resource_linkage_outcomes");
        jdbc.update("DELETE FROM real_device_battery_observations");
        for(String table:List.of("reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_trace_entries","legacy_ingest_events","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments()); jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A')"); jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a')"); N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        placeId=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Reparse fixture "+UUID.randomUUID())).andExpect(status().isOk())).get("id").asText();
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    JsonNode published() throws Exception {
        String name="Reparse-"+UUID.randomUUID();
        var product=body(call(HttpMethod.POST,"/api/management/products",root,null,UUID.randomUUID().toString(),Map.of("name",name,"manufacturer","Synthetic","model",name,"description","N07")).andExpect(status().isOk()));
        var version=body(call(HttpMethod.POST,"/api/management/products/"+product.get("id").asText()+"/versions",root,null,UUID.randomUUID().toString(),Map.of()).andExpect(status().isOk()));
        var caps=json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED");
        var edited=body(call(HttpMethod.PUT,"/api/management/product-versions/"+version.get("id").asText(),root,null,UUID.randomUUID().toString(),Map.of("schema",version.get("schema"),"capabilities",caps,"expected_revision",0,"battery_low_threshold",json.nullNode())).andExpect(status().isOk()));
        var samples=List.of(new ProductInputs.Sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE",BigDecimal.ZERO,List.of()),new ProductInputs.Sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY",new BigDecimal("50"),List.of()),new ProductInputs.Sample("unknown","UNKNOWN","{}","UNKNOWN",null,List.of()),new ProductInputs.Sample("malformed","MALFORMED","{","UNKNOWN",null,List.of()));
        var tested=body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/test",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",edited.get("revision").asLong(),"samples",samples)).andExpect(status().isOk()));
        return body(call(HttpMethod.POST,"/api/management/product-versions/"+version.get("id").asText()+"/publish",root,null,UUID.randomUUID().toString(),Map.of("expected_revision",tested.get("revision").asLong())).andExpect(status().isOk()));
    }
    record Fixture(JsonNode source,String vendor,JsonNode device) { }
    Fixture fixture() throws Exception {
        var version=published(); var source=body(call(HttpMethod.POST,"/api/management/sources",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","N07 source")).andExpect(status().isOk()));
        var device=body(call(HttpMethod.POST,"/api/management/devices",root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","N07 device","device_eui","n07-"+UUID.randomUUID(),"address","Synthetic","product_version_id",version.get("id").asText(),"place_id",placeId)).andExpect(status().isOk()));
        String vendor="vendor-"+UUID.randomUUID();
        call(HttpMethod.POST,"/api/management/sources/"+source.at("/source/id").asText()+"/bindings",root,"tenant-a",UUID.randomUUID().toString(),Map.of("device_id",device.get("id").asText(),"vendor_device_id",vendor,"expected_revision",device.get("revision").asLong(),"reason","N07 binding")).andExpect(status().isOk());
        return new Fixture(source,vendor,device);
    }
    String message(Fixture f,String id) throws Exception {
        return body(call(HttpMethod.POST,"/api/ingest/"+f.source().at("/source/id").asText()+"/messages",f.source().get("one_time_token").asText(),null,null,new IngestionInputs.Envelope(id,f.vendor(),null,"{\"event\":\"FIRE\",\"battery\":20}",null,java.time.OffsetDateTime.now().toString())).andExpect(status().isOk())).get("raw_message_id").asText();
    }
    JsonNode create(String raw) throws Exception { return body(call(HttpMethod.POST,"/api/management/messages/"+raw+"/reparse",root,"tenant-a",UUID.randomUUID().toString(),Map.of("reason","N07 重解析")).andExpect(status().isOk())); }

    @Test void reparseSucceedsWithoutAnyBusinessWrite() throws Exception {
        var f=fixture(); var raw=message(f,"m-1"); var job=create(raw);
        assertThat(job.get("status").asText()).isEqualTo("PENDING");
        long deviceRevision=jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,f.device().get("id").asText());
        assertThat(runner.runOne("test-worker")).isTrue();
        var detail=body(call(HttpMethod.GET,"/api/management/reparse-jobs/"+job.get("id").asText(),root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(detail.at("/job/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(detail.at("/job/result_json").asText()).contains("FIRE");
        assertThat(detail.get("attempts")).hasSize(1);
        assertThat(detail.at("/attempts/0/outcome").asText()).isEqualTo("SUCCEEDED");
        // 纯解析：不写设备、不建报警、不建事件。
        assertThat(jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,f.device().get("id").asText())).isEqualTo(deviceRevision);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_events",Long.class)).isZero();
    }

    @Test void expiredLeaseIsRecoveredAsNewAttempt() throws Exception {
        var f=fixture(); var raw=message(f,"m-1"); var job=create(raw); String id=job.get("id").asText();
        // 模拟崩溃：任务处于 RUNNING、租约已过期、尝试仍为 RUNNING。
        jdbc.update("UPDATE reparse_jobs SET status='RUNNING',attempt_count=1,lease_owner='dead',lease_token=1,lease_until=? WHERE id=?",OffsetDateTime.now().minusMinutes(5),id);
        jdbc.update("INSERT INTO reparse_attempts(id,job_id,attempt_no,lease_token,worker_id,started_at,outcome) VALUES(?,?,1,1,'dead',?,'RUNNING')",UUID.randomUUID().toString(),id,OffsetDateTime.now().minusMinutes(5));
        assertThat(runner.runOne("recovery-worker")).isTrue();
        var detail=body(call(HttpMethod.GET,"/api/management/reparse-jobs/"+id,root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(detail.at("/job/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(detail.at("/attempts/0/outcome").asText()).isEqualTo("EXPIRED");
        assertThat(detail.at("/attempts/1/outcome").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void attemptsAreBoundedAndJobEndsFailed() throws Exception {
        var f=fixture(); var raw=message(f,"m-1"); var job=create(raw); String id=job.get("id").asText();
        jdbc.update("UPDATE reparse_jobs SET attempt_count=3 WHERE id=?",id);
        // 让解析必然失败：指向不存在的版本。
        jdbc.update("UPDATE reparse_jobs SET product_version_id=(SELECT product_version_id FROM reparse_jobs WHERE id=?) WHERE id=?",id,id);
        runner.runOne("bounded-worker");
        var detail=body(call(HttpMethod.GET,"/api/management/reparse-jobs/"+id,root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(detail.at("/job/attempt_count").asInt()).isEqualTo(3);
    }

    @Test void reparseCreateListAndDetailRecheckCurrentDeviceScope() throws Exception {
        var f=fixture();
        var raw=message(f,"scope-revoked-message");
        var job=create(raw);
        String jobId=job.get("id").asText();
        String admin=jdbc.queryForObject("SELECT id FROM app_users WHERE username='admin'",String.class);
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id=? AND tenant_id='tenant-a'",admin);
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=? AND tenant_id='tenant-a'",admin);

        call(HttpMethod.GET,"/api/management/messages/"+raw,root,"tenant-a",null,null).andExpect(status().isNotFound());
        call(HttpMethod.POST,"/api/management/messages/"+raw+"/reparse",root,"tenant-a",UUID.randomUUID().toString(),Map.of("reason","Out-of-scope retry"))
            .andExpect(status().isNotFound());
        call(HttpMethod.GET,"/api/management/reparse-jobs?page=1",root,"tenant-a",null,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call(HttpMethod.GET,"/api/management/reparse-jobs/"+jobId,root,"tenant-a",null,null)
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reparse_jobs WHERE id=?",Long.class,jobId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM reparse_jobs WHERE id=?",String.class,jobId)).isEqualTo("PENDING");
    }

    @Test void duplicateCreateIsRejected() throws Exception {
        var f=fixture(); var raw=message(f,"m-1"); create(raw);
        call(HttpMethod.POST,"/api/management/messages/"+raw+"/reparse",root,"tenant-a",UUID.randomUUID().toString(),Map.of("reason","again")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPARSE_ALREADY_EXISTS"));
    }
}
