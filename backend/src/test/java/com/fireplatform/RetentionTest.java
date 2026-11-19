package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import java.time.OffsetDateTime;
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

/** N16：保留策略默认未启用、冻结试算、显式执行、CAS/候选冲突回滚与运行留痕。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:retentiontests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class RetentionTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap;
    String root;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM alarm_configuration_outcomes");
        jdbc.update("DELETE FROM alarm_reminder_schedule");
        jdbc.update("DELETE FROM resource_linkage_outcomes");
        jdbc.update("DELETE FROM real_device_battery_observations");
        for(String table:List.of("retention_runs","retention_policies","user_messages","device_import_rows","device_import_jobs","device_export_snapshots","device_profile_snapshots","profile_rules","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_change_outbox","alarm_change_heads","alarm_change_publisher_coord","alarm_trace_entries","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments()); jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A')"); jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a')"); N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    JsonNode configure(String kind,int days,boolean enabled,long revision,String key) throws Exception { return body(call(HttpMethod.POST,"/api/management/retention/policies",root,"tenant-a",key,Map.of("kind",kind,"keep_days",days,"enabled",enabled,"expected_revision",revision,"reason","显式变更保留期")).andExpect(status().isOk())); }
    ResultActions preview(String kind,String key) throws Exception { return call(HttpMethod.POST,"/api/management/retention/preview",root,"tenant-a",key,Map.of("kind",kind)); }
    ResultActions execute(String kind,String previewId,long revision,String key) throws Exception { return call(HttpMethod.POST,"/api/management/retention/execute",root,"tenant-a",key,Map.of("kind",kind,"preview_id",previewId,"expected_revision",revision,"reason","执行不可逆清理")); }
    void audit(String id,int daysAgo) { jdbc.update("INSERT INTO audit_log(id,tenant_id,action,target_id,outcome,request_id,occurred_at,actor_id) VALUES(?,?,?,?,?,?,?,NULL)",id,"tenant-a","TEST",id,"SUCCESS","r",OffsetDateTime.now().minusDays(daysAgo)); }

    @Test void previewRequiresConfiguredAndEnabledPolicy() throws Exception {
        preview("AUDIT_LOG",UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_NOT_CONFIGURED"));
        configure("AUDIT_LOG",30,false,0,UUID.randomUUID().toString());
        preview("AUDIT_LOG",UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_DISABLED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retention_runs",Long.class)).isZero();
    }

    @Test void previewNeverDeletesAndExecutionDeletesFrozenCandidates() throws Exception {
        audit("old-1",40); audit("old-2",31); audit("new-1",1);
        var policy=configure("AUDIT_LOG",30,true,0,UUID.randomUUID().toString());
        var dry=body(preview("AUDIT_LOG",UUID.randomUUID().toString()).andExpect(status().isOk()));
        assertThat(dry.get("dry_run").asBoolean()).isTrue();
        assertThat(dry.get("scanned").asLong()).isEqualTo(2);
        assertThat(dry.get("eligible").asLong()).isEqualTo(2);
        assertThat(dry.get("deleted").asLong()).isZero();
        assertThat(dry.get("legacy_potential_count").asBoolean()).isFalse();
        assertThat(dry.get("candidate_fingerprint").asText()).hasSize(64);
        assertThat(jdbc.queryForList("SELECT id FROM audit_log WHERE id IN ('old-1','old-2','new-1')",String.class))
            .containsExactlyInAnyOrder("old-1","old-2","new-1");
        var real=body(execute("AUDIT_LOG",dry.get("id").asText(),policy.get("revision").asLong(),UUID.randomUUID().toString()).andExpect(status().isOk()));
        assertThat(real.get("dry_run").asBoolean()).isFalse();
        assertThat(real.get("eligible").asLong()).isEqualTo(2);
        assertThat(real.get("deleted").asLong()).isEqualTo(2);
        assertThat(real.get("source_preview_id").asText()).isEqualTo(dry.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id IN ('old-1','old-2')",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id='new-1'",Long.class)).isEqualTo(1);
        var runs=body(call(HttpMethod.GET,"/api/management/retention/runs?page=1&size=10",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(runs.get("total").asLong()).isEqualTo(2);
        assertThat(runs.get("items")).hasSize(2);
    }

    @Test void previewReplayUsesFrozenKeyAndDoesNotCreateDuplicateHistory() throws Exception {
        audit("old-1",40);
        configure("AUDIT_LOG",30,true,0,UUID.randomUUID().toString());
        String key=UUID.randomUUID().toString();
        var first=body(preview("AUDIT_LOG",key).andExpect(status().isOk()));
        var replay=body(preview("AUDIT_LOG",key).andExpect(status().isOk()));
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retention_runs",Long.class)).isEqualTo(1);
    }

    @Test void stalePolicyRevisionRejectsExecutionWithNoDelete() throws Exception {
        audit("old-1",40);
        var policy=configure("AUDIT_LOG",30,true,0,UUID.randomUUID().toString());
        var dry=body(preview("AUDIT_LOG",UUID.randomUUID().toString()).andExpect(status().isOk()));
        var updated=configure("AUDIT_LOG",31,true,policy.get("revision").asLong(),UUID.randomUUID().toString());
        assertThat(updated.get("revision").asLong()).isEqualTo(policy.get("revision").asLong()+1);
        execute("AUDIT_LOG",dry.get("id").asText(),updated.get("revision").asLong(),UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_PREVIEW_STALE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id='old-1'",Long.class)).isEqualTo(1);
    }

    @Test void candidateChangeRejectsExecutionAndRollsBack() throws Exception {
        audit("old-1",40); audit("old-2",31);
        var policy=configure("AUDIT_LOG",30,true,0,UUID.randomUUID().toString());
        var dry=body(preview("AUDIT_LOG",UUID.randomUUID().toString()).andExpect(status().isOk()));
        audit("late-old",35);
        execute("AUDIT_LOG",dry.get("id").asText(),policy.get("revision").asLong(),UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_CANDIDATES_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id IN ('old-1','old-2','late-old')",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retention_runs WHERE dry_run=FALSE",Long.class)).isZero();
    }

    @Test void partialDeviceScopeCannotConfigurePreviewOrExecuteAfterRevocation() throws Exception {
        audit("old-1",40);
        var policy=configure("AUDIT_LOG",30,true,0,UUID.randomUUID().toString());
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        preview("AUDIT_LOG",UUID.randomUUID().toString()).andExpect(status().isForbidden());
        call(HttpMethod.POST,"/api/management/retention/policies",root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("kind","AUDIT_LOG","keep_days",30,"enabled",true,"expected_revision",0,"reason","部分设备范围不应配置全单位策略"))
            .andExpect(status().isForbidden());
        jdbc.update("UPDATE user_device_scopes SET mode='ALL_TENANT',revision=revision+1 WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        var dry=body(preview("AUDIT_LOG",UUID.randomUUID().toString()).andExpect(status().isOk()));
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        execute("AUDIT_LOG",dry.get("id").asText(),policy.get("revision").asLong(),UUID.randomUUID().toString())
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id='old-1'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retention_runs WHERE dry_run=FALSE",Long.class)).isZero();
    }

    @Test void configureUsesCasAndReason() throws Exception {
        var policy=configure("AUDIT_LOG",30,true,0,UUID.randomUUID().toString());
        call(HttpMethod.POST,"/api/management/retention/policies",root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("kind","AUDIT_LOG","keep_days",31,"enabled",true,"expected_revision",5,"reason","显式变更保留期"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        call(HttpMethod.POST,"/api/management/retention/policies",root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("kind","AUDIT_LOG","keep_days",31,"enabled",true,"expected_revision",policy.get("revision").asLong(),"reason",""))
            .andExpect(status().isBadRequest());
    }

    @Test void secondCreateFromSameVirtualRevisionConflictsWithoutLostUpdate() throws Exception {
        var first=configure("AUDIT_LOG",30,true,0,UUID.randomUUID().toString());
        assertThat(first.get("revision").asLong()).isEqualTo(1);
        call(HttpMethod.POST,"/api/management/retention/policies",root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("kind","AUDIT_LOG","keep_days",99,"enabled",false,"expected_revision",0,"reason","并发创建不得覆盖"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT keep_days FROM retention_policies WHERE tenant_id='tenant-a' AND kind='AUDIT_LOG'",Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT revision FROM retention_policies WHERE tenant_id='tenant-a' AND kind='AUDIT_LOG'",Long.class)).isEqualTo(1);
    }

    @Test void invalidKindsAndDaysAreRejected() throws Exception {
        call(HttpMethod.POST,"/api/management/retention/policies",root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("kind","NOT_A_KIND","keep_days",30,"enabled",true,"expected_revision",0,"reason","x")).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/retention/policies",root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("kind","AUDIT_LOG","keep_days",0,"enabled",true,"expected_revision",0,"reason","x")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retention_policies",Long.class)).isZero();
    }
}
