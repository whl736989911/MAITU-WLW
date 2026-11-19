package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.storage=h2-dev","app.generic-ingest-enabled=false","spring.datasource.url=jdbc:h2:mem:customerunitparameters;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerUnitParametersIntegrationTest {
    private static final String INITIAL="Initial-"+UUID.randomUUID();
    private static final String PASSWORD="Changed-"+UUID.randomUUID();
    private static final String BASE="/api/customer/unit-parameters";
    private static final String VALUE=BASE+"/offline_threshold_minutes";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME",()->"admin");
        registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    private String root,customer,userId,tenantId,otherTenant;

    @BeforeAll void initializeRoot() throws Exception {
        root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
    }
    @BeforeEach void createIsolatedUnitAdministrator() throws Exception {
        tenantId=UUID.randomUUID().toString(); otherTenant=UUID.randomUUID().toString(); userId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?),(?,?)",tenantId,"Self-service boundary fixture",otherTenant,"Unrelated unit fixture");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin',?),('bootstrap-admin',?)",tenantId,otherTenant);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES('bootstrap-admin',?,'ALL_TENANT',1),('bootstrap-admin',?,'ALL_TENANT',1)",tenantId,otherTenant);
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password) VALUES(?,?,?,'CUSTOMER',?,?,FALSE)",userId,userId,"Explicit unit administrator",tenantId,encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES(?,'unit-parameters:read'),(?,'unit-parameters:write')",userId,userId);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES(?,?,'ALL_TENANT',1)",userId,tenantId);
        jdbc.update("UPDATE numeric_settings SET setting_value=120,revision=revision+1 WHERE setting_key='offline_threshold_minutes'");
        customer=login(userId,PASSWORD,"customer");
    }
    @Test void requiresExplicitPermissionFullUnitScopeAndCustomerAudience() throws Exception {
        jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission='unit-parameters:read'",userId);
        call(HttpMethod.GET,BASE,customer,null,null,null).andExpect(status().isForbidden());
        set(10,0,"read-revoked").andExpect(status().isForbidden());
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES(?,'unit-parameters:read')",userId);
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id=? AND tenant_id=?",userId,tenantId);
        call(HttpMethod.GET,BASE,customer,null,null,null).andExpect(status().isForbidden());
        set(10,0,"partial-unit-scope").andExpect(status().isForbidden());
        call(HttpMethod.GET,BASE,root,null,null,null).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_overrides WHERE tenant_id=?",Long.class,tenantId)).isZero();
    }
    @Test void rejectsForeignTenantHeaderAndNeverExposesGlobalOnlySettings() throws Exception {
        call(HttpMethod.GET,BASE,customer,otherTenant,null,null).andExpect(status().isForbidden());
        var own=body(call(HttpMethod.GET,BASE+"?tenant_id="+otherTenant,customer,null,null,null).andExpect(status().isOk()));
        assertThat(own.at("/policy/tenant_id").asText()).isEqualTo(tenantId);
        assertThat(own.at("/policy/scope_id").asText()).isEqualTo(tenantId);
        assertThat(own.at("/definition/key").asText()).isEqualTo("offline_threshold_minutes");
        call(HttpMethod.PUT,BASE+"/default_page_size",customer,null,"global-only",input(30,0)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_overrides WHERE tenant_id=?",Long.class,otherTenant)).isZero();
    }
    @Test void enforcesCurrentPlatformBoundaryAndCasButReplaysAnAlreadyCommittedIntent() throws Exception {
        set(121,0,"above-current-boundary").andExpect(status().isBadRequest());
        var committed=body(set(120,0,"committed-once").andExpect(status().isOk()));
        assertThat(committed.at("/policy/value").asLong()).isEqualTo(120);
        set(30,0,"stale-editor").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        jdbc.update("UPDATE numeric_settings SET setting_value=60,revision=revision+1 WHERE setting_key='offline_threshold_minutes'");
        var replay=body(set(120,0,"committed-once").andExpect(status().isOk()));
        assertThat(replay.get("change")).isEqualTo(committed.get("change"));
        assertThat(replay.at("/policy/revision").asLong()).isEqualTo(1);
        set(61,1,"new-intent-above-boundary").andExpect(status().isBadRequest());
        set(60,1,"tightened").andExpect(status().isOk()).andExpect(jsonPath("$.policy.revision").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_changes WHERE tenant_id=?",Long.class,tenantId)).isEqualTo(2);
    }
    @Test void clearingInheritsTheLatestParentAndCustomerHistoryHidesAdministrativeIdentity() throws Exception {
        var first=body(set(20,0,"unit-override").andExpect(status().isOk()));
        jdbc.update("UPDATE numeric_settings SET setting_value=90,revision=revision+1 WHERE setting_key='offline_threshold_minutes'");
        var cleared=body(call(HttpMethod.POST,VALUE+"/clear",customer,null,"inherit-latest",Map.of("expected_revision",1,"reason","Return to the current platform default")).andExpect(status().isOk()));
        assertThat(cleared.at("/policy/has_override").asBoolean()).isFalse();
        assertThat(cleared.at("/policy/effective/value").asLong()).isEqualTo(90);
        assertThat(cleared.at("/policy/effective/source_scope").asText()).isEqualTo("GLOBAL");
        var history=body(call(HttpMethod.GET,VALUE+"/history",customer,null,null,null).andExpect(status().isOk()));
        assertThat(history.at("/total").asLong()).isEqualTo(2);
        assertThat(history.at("/items/0/id").asText()).isEqualTo(cleared.at("/change/id").asText());
        assertThat(history.at("/items/1/id").asText()).isEqualTo(first.at("/change/id").asText());
        for(var row:history.get("items")) {
            assertThat(row.has("actor_id")).isFalse();
            assertThat(row.has("reason")).isFalse();
            assertThat(row.has("tenant_id")).isFalse();
        }
    }
    private Map<String,Object> input(long value,long revision) {
        return Map.of("value",value,"expected_revision",revision,"reason","Explicit customer unit configuration");
    }
    private ResultActions set(long value,long revision,String key) throws Exception {
        return call(HttpMethod.PUT,VALUE,customer,null,key,input(value,revision));
    }
    private String login(String username,String password,String audience) throws Exception {
        return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",username,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText();
    }
    private JsonNode body(ResultActions action) throws Exception {
        return json.readTree(action.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception {
        var builder=request(method,path);
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(tenant!=null)builder.header("X-Tenant-Id",tenant);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(input!=null)builder.contentType("application/json").content(json.writeValueAsString(input));
        return mvc.perform(builder);
    }
}
