package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.notification.SuppressionCommands;
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

/** N08：有期限通知屏蔽——火警默认穿透、显式二次确认才屏蔽、到期自动失效、可取消。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:suppressiontests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceSuppressionTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired SuppressionCommands commands;
    String root;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM alarm_configuration_outcomes");
        jdbc.update("DELETE FROM alarm_reminder_schedule");
        jdbc.update("DELETE FROM resource_linkage_outcomes");
        jdbc.update("DELETE FROM real_device_battery_observations");
        jdbc.update("DELETE FROM suppression_changes");
        for(String table:List.of("device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_trace_entries","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","devices","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments()); jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A'),('tenant-b','Fixture B')"); jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')"); jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,integration_state) VALUES ('device-a','tenant-a','Fixture A device','fixture-a','fixture','A','UNKNOWN','LEGACY_UNVERIFIED'),('device-b','tenant-b','Fixture B device','fixture-b','fixture','B','UNKNOWN','LEGACY_UNVERIFIED')"); N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    Map<String,Object> input(String scope,List<String> devices,boolean fire,OffsetDateTime start,OffsetDateTime end){var out=new java.util.HashMap<String,Object>();out.put("scope",scope);out.put("device_ids",devices);out.put("fire_confirmed",fire);out.put("reason","N08 屏蔽演练");out.put("starts_at",start);out.put("ends_at",end);return out;}
    JsonNode create(Map<String,Object> body) throws Exception { return body(call(HttpMethod.POST,"/api/management/suppressions",root,"tenant-a",UUID.randomUUID().toString(),body).andExpect(status().isOk())); }
    private boolean shielded(String device) throws Exception {
        var statuses=body(call(HttpMethod.GET,"/api/management/devices/"+device,root,"tenant-a",null,null).andExpect(status().isOk()))
            .at("/device/status_display/business_statuses");
        return java.util.stream.StreamSupport.stream(statuses.spliterator(),false).anyMatch(item->"SHIELDED".equals(item.path("code").asText()));
    }

    @Test void firePassesThroughTenantSuppressionUnlessExplicitlyConfirmed() throws Exception {
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,integration_state) VALUES ('device-a-unactivated','tenant-a','Other device in tenant','fixture-extra','fixture','A2','UNKNOWN','PENDING_INTEGRATION')");
        var soft=create(input("TENANT",List.of(),false,null,OffsetDateTime.now().plusHours(2)));
        assertThat(soft.get("fire_confirmed").asBoolean()).isFalse();
        assertThat(commands.blocked("tenant-a", "device-a", true, null)).isFalse();
        assertThat(commands.blocked("tenant-a", "device-a", false, null)).isTrue();
        assertThat(shielded("device-a")).isFalse();
        var hard=create(input("DEVICES",List.of("device-a"),true,null,OffsetDateTime.now().plusHours(2)));
        assertThat(hard.get("scope").asText()).isEqualTo("DEVICES");
        assertThat(commands.blocked("tenant-a", "device-a", true, null)).isTrue();
        assertThat(commands.blocked("tenant-a", "device-a-unactivated", true, null)).isFalse();
        assertThat(shielded("device-a")).isTrue();
        assertThat(shielded("device-a-unactivated")).isFalse();
    }

    @Test void expiryIsDerivedAndCancelStopsCoverage() throws Exception {
        var created=create(input("TENANT",List.of(),true,null,OffsetDateTime.now().plusHours(1)));
        String id=created.get("id").asText();
        assertThat(commands.blocked("tenant-a", "device-a", true, null)).isTrue();
        jdbc.update("UPDATE device_suppressions SET starts_at=?,ends_at=? WHERE id=?",OffsetDateTime.now().minusHours(2),OffsetDateTime.now().minusHours(1),id);
        assertThat(commands.blocked("tenant-a", "device-a", true, null)).isFalse();
        assertThat(shielded("device-a")).isFalse();
        jdbc.update("UPDATE device_suppressions SET starts_at=?,ends_at=? WHERE id=?",OffsetDateTime.now().minusHours(1),OffsetDateTime.now().plusHours(1),id);
        var cancelled=body(call(HttpMethod.POST,"/api/management/suppressions/"+id+"/cancel",root,"tenant-a","cancel-once",Map.of("expected_revision",created.get("revision").asLong(),"reason","演练结束")).andExpect(status().isOk()));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(commands.blocked("tenant-a", "device-a", true, null)).isFalse();
        assertThat(shielded("device-a")).isFalse();
        call(HttpMethod.POST,"/api/management/suppressions/"+id+"/cancel",root,"tenant-a","cancel-again",Map.of("expected_revision",cancelled.get("revision").asLong(),"reason","again")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPRESSION_NOT_ACTIVE"));
    }

    @Test void malformedScopesAndForeignDevicesAreRejected() throws Exception {
        call(HttpMethod.POST,"/api/management/suppressions",root,"tenant-a",UUID.randomUUID().toString(),input("TENANT",List.of("device-a"),true,null,OffsetDateTime.now().plusHours(1))).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/suppressions",root,"tenant-a",UUID.randomUUID().toString(),input("DEVICES",List.of(),true,null,OffsetDateTime.now().plusHours(1))).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/suppressions",root,"tenant-a",UUID.randomUUID().toString(),input("DEVICES",List.of("device-b"),true,null,OffsetDateTime.now().plusHours(1))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_NOT_IN_TENANT"));
        call(HttpMethod.POST,"/api/management/suppressions",root,"tenant-a",UUID.randomUUID().toString(),input("TENANT",List.of(),true,OffsetDateTime.now().plusHours(3),OffsetDateTime.now().plusHours(1))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_suppressions",Long.class)).isZero();
    }

    @Test void listFiltersSupportedResourceScopesWithoutCrossTenantLeakage() throws Exception {
        create(input("TENANT",List.of(),false,null,OffsetDateTime.now().plusHours(1)));
        create(input("DEVICES",List.of("device-a"),false,null,OffsetDateTime.now().plusHours(1)));
        var tenantScope=body(call(HttpMethod.GET,"/api/management/suppressions?page=1&size=5&scope=TENANT",root,"tenant-a",null,null).andExpect(status().isOk()));
        var deviceScope=body(call(HttpMethod.GET,"/api/management/suppressions?page=1&size=5&scope=DEVICES",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(tenantScope.get("total").asInt()).isEqualTo(1);
        assertThat(deviceScope.get("total").asInt()).isEqualTo(1);
        var other=body(call(HttpMethod.GET,"/api/management/suppressions?page=1&size=5&scope=DEVICES",root,"tenant-b",null,null).andExpect(status().isOk()));
        assertThat(other.get("total").asInt()).isZero();
        var sensorScope=body(call(HttpMethod.GET,"/api/management/suppressions?page=1&scope=SENSORS",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(sensorScope.get("total").asInt()).isZero();
        var record=body(call(HttpMethod.GET,"/api/management/suppressions?page=1&size=5&scope=TENANT",root,"tenant-a",null,null).andExpect(status().isOk())).get("items").get(0);
        var history=body(call(HttpMethod.GET,"/api/management/suppressions/"+record.get("id").asText()+"/history",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).get("action").asText()).isEqualTo("SUPPRESSION_CREATED");
    }
}
