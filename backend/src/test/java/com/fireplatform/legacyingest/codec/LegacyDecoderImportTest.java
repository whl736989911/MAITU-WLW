package com.fireplatform.legacyingest.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.N04TestScopes;
import com.fireplatform.bootstrap.Bootstrap;
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

/** 旧平台 pa_code 导入：不可变修订、格式校验、映射语义。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:legacydecodertests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class LegacyDecoderImportTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    static final String SCRIPT="function rawDataToProtocol(payload){ return [{method:'thing.event.alarm', params:[{deviceStateCode:'1001001', battery:80}]}]; }";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap;
    String root;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM alarm_configuration_outcomes");
        jdbc.update("DELETE FROM alarm_reminder_schedule");
        jdbc.update("DELETE FROM resource_linkage_outcomes");
        jdbc.update("DELETE FROM real_device_battery_observations");
        for(String table:List.of("legacy_product_decoders","retention_runs","retention_policies","user_messages","device_import_rows","device_import_jobs","device_export_snapshots","device_profile_snapshots","profile_rules","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_change_outbox","alarm_change_heads","alarm_change_publisher_coord","alarm_trace_entries","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments()); jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A')"); jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a')"); N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    ResultActions importDecoder(String product,String source) throws Exception {
        return call(HttpMethod.POST,"/api/management/legacy-ingest/product-decoders",root,null,UUID.randomUUID().toString(),
            Map.of("legacy_product_id",product,"source",source,"reason","N02 解码器导入"));
    }

    @Test void importsImmutableRevisionsAndLatestWins() throws Exception {
        var first=body(importDecoder("323","// rev1\n"+SCRIPT).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.source_sha256").exists()));
        assertThat(first.get("bytes").asInt()).isGreaterThan(0);
        var second=body(importDecoder("323","// rev2\n"+SCRIPT).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)));
        assertThat(second.get("source_sha256").asText()).isNotEqualTo(first.get("source_sha256").asText());
        var revisions=body(call(HttpMethod.GET,"/api/management/legacy-ingest/product-decoders/323",root,null,null,null).andExpect(status().isOk()));
        assertThat(revisions).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT source FROM legacy_product_decoders WHERE legacy_product_id='323' ORDER BY revision DESC LIMIT 1",String.class)).startsWith("// rev2");
        // 旧修订保留，不覆盖。
        assertThat(jdbc.queryForObject("SELECT source FROM legacy_product_decoders WHERE legacy_product_id='323' AND revision=1",String.class)).startsWith("// rev1");
    }

    @Test void rejectsMissingEntryPointAndMalformedIdentifiers() throws Exception {
        importDecoder("323","var x = 1;").andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/legacy-ingest/product-decoders",root,null,null,Map.of("legacy_product_id","bad id!","source",SCRIPT,"reason","x")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_product_decoders",Long.class)).isZero();
    }

    @Test void importsWithoutActorPasswordAndReplaysTheSameIdempotencyKey() throws Exception {
        String key=UUID.randomUUID().toString();
        var body=Map.of("legacy_product_id","323","source",SCRIPT,"reason","retry safe");
        call(HttpMethod.POST,"/api/management/legacy-ingest/product-decoders",root,null,key,body)
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        call(HttpMethod.POST,"/api/management/legacy-ingest/product-decoders",root,null,key,body)
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_product_decoders",Long.class)).isEqualTo(1);
    }

    @Test void previewRejectsMalformedHexBeforeRunningAnyScript() throws Exception {
        call(HttpMethod.POST,"/api/management/legacy-ingest/product-decoders/preview",root,null,null,Map.of("source",SCRIPT,"payload_hex","xyz")).andExpect(status().isBadRequest());
    }

    @Test void mapsLegacyMethodsAndFireCodeWithoutGuessingUnknowns() {
        assertThat(LegacyDecoderCommands.kind("thing.event.alarm")).isEqualTo("ALARM");
        assertThat(LegacyDecoderCommands.kind("thing.event.faults")).isEqualTo("FAULT");
        assertThat(LegacyDecoderCommands.kind("thing.event.heartbeat")).isEqualTo("HEARTBEAT");
        assertThat(LegacyDecoderCommands.kind("thing.event.params")).isEqualTo("PARAM");
        assertThat(LegacyDecoderCommands.kind("thing.service.replyTerminalACK")).isEqualTo("OTHER");
        assertThat(LegacyDecoderCommands.fireSemantics("1001001")).isEqualTo("FIRE");
        assertThat(LegacyDecoderCommands.fireSemantics("1002006")).isEqualTo("UNKNOWN");
        assertThat(LegacyDecoderCommands.fireSemantics(null)).isEqualTo("UNKNOWN");
    }
}
