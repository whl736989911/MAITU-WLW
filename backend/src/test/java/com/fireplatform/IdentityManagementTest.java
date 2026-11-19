package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.identity.PermissionCatalog;
import com.fireplatform.shared.Digests;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=all","app.simulation-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:identitytests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class IdentityManagementTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), ROOT_PASSWORD="Root-"+UUID.randomUUID(), TARGET="Target-"+UUID.randomUUID(), UPDATED="Updated-"+UUID.randomUUID(), RESET="Reset-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME",()->"admin"); registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired PasswordEncoder encoder;
    @BeforeEach void reset() {
        N04TestScopes.clear(jdbc);
        for(String table:List.of("notification_automation_intents","alarm_trace_entries","alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","real_device_battery_observations","identity_command_records","idempotency_records","device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","sessions","user_permissions","user_tenant_grants","app_users","devices","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE numeric_settings SET setting_value=20,revision=0 WHERE setting_key='default_page_size'");
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Identity test tenant A'),('tenant-b','Identity test tenant B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        for(String[] account:new String[][]{{"customer-a","tenant-a"},{"customer-b","tenant-b"}}) {
            jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled) VALUES(?,?,?,'CUSTOMER',?,?,TRUE,TRUE)",
                account[0],account[0],account[0],account[1],encoder.encode(INITIAL));
            for(String permission:com.fireplatform.shared.Limits.CUSTOMER_PERMISSIONS)
                jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES(?,?)",account[0],permission);
        }
        N04TestScopes.grantExisting(jdbc);
    }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object value) throws Exception {
        var request=request(method,path); if(token!=null) request.header("Authorization","Bearer "+token); if(tenant!=null) request.header("X-Tenant-Id",tenant); if(key!=null) request.header("Idempotency-Key",key);
        if(value!=null) request.contentType("application/json").content(json.writeValueAsString(value)); return mvc.perform(request);
    }
    ResultActions loginResult(String username,String password,String audience) throws Exception { return call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",username,"password",password,"audience",audience)); }
    String login(String username,String password,String audience) throws Exception { return body(loginResult(username,password,audience).andExpect(status().isOk())).get("access_token").asText(); }
    String activate(String username,String initial,String next,String audience) throws Exception {
        String token=login(username,initial,audience); call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",initial,"new_password",next)).andExpect(status().isNoContent()); return login(username,next,audience);
    }
    String root() throws Exception { return activate("admin",INITIAL,ROOT_PASSWORD,"management"); }
    JsonNode customer(String root,String tenant,String username,List<String> permissions) throws Exception {
        return body(call(HttpMethod.POST,"/api/management/users",root,tenant,UUID.randomUUID().toString(),Map.of("username",username,"name",username,"initial_password",TARGET,"permissions",permissions)).andExpect(status().isOk()));
    }
    JsonNode platform(String root,String username,List<String> permissions,List<String> grants) throws Exception {
        return body(call(HttpMethod.POST,"/api/management/platform-users",root,null,UUID.randomUUID().toString(),Map.of("username",username,"name",username,"initial_password",TARGET,"permissions",permissions,"tenant_ids",grants)).andExpect(status().isOk()));
    }
    long revision(String id) { return jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?",Long.class,id); }
    Map<String,Object> platformUpdate(String name,boolean enabled,List<String> permissions,List<String> grants,long revision) { return Map.of("name",name,"enabled",enabled,"permissions",permissions,"tenant_ids",grants,"expected_revision",revision); }
    @Test void freshUnitAndAccountDoNotRequireDemoAndCreatorSessionSurvives() throws Exception {
        String root=root();
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM sessions WHERE user_id<>'bootstrap-admin'"); jdbc.update("DELETE FROM user_permissions WHERE user_id<>'bootstrap-admin'"); jdbc.update("DELETE FROM device_battery_threshold_changes"); jdbc.update("DELETE FROM device_battery_threshold_overrides"); jdbc.update("DELETE FROM app_users WHERE id<>'bootstrap-admin'"); jdbc.update("DELETE FROM devices"); jdbc.update("DELETE FROM user_tenant_grants"); jdbc.update("DELETE FROM tenants");
        long before=revision("bootstrap-admin");
        var payload=Map.of("name","正式新单位");
        JsonNode unit=body(call(HttpMethod.POST,"/api/management/units",root,null,"unit-create",payload).andExpect(status().isOk())); String id=unit.get("id").asText();
        assertThat(revision("bootstrap-admin")).isEqualTo(before);
        call(HttpMethod.GET,"/api/auth/me",root,null,null,null).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/management/tenants",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));
        assertThat(body(call(HttpMethod.POST,"/api/management/units",root,null,"unit-create",payload).andExpect(status().isOk()))).isEqualTo(unit);
        call(HttpMethod.POST,"/api/management/units",root,null,"unit-create",Map.of("name","不同单位")).andExpect(status().isConflict());
        assertThat(body(call(HttpMethod.POST,"/api/management/units",root,null,"unit-create",Map.of("name","正式新单位")).andExpect(status().isOk()))).isEqualTo(unit);
        JsonNode account=customer(root,id,"production-customer",List.of("summary:read")); assertThat(account.get("tenant_id").asText()).isEqualTo(id); assertThat(account.get("is_protected").asBoolean()).isFalse(); assertThat(account.get("must_change_password").asBoolean()).isTrue();
        String token=activate("production-customer",TARGET,UPDATED,"customer");
        call(HttpMethod.GET,"/api/customer/summary",token,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.device_total").value(0));
        call(HttpMethod.GET,"/api/customer/summary",token,id,null,null).andExpect(status().isForbidden());
        bootstrap.run(new DefaultApplicationArguments()); // existing official accounts must not be reset or deleted
        login("production-customer",UPDATED,"customer"); assertThat(revision(account.get("id").asText())).isEqualTo(1);
    }
    @Test void managementUserDirectoryDefaultsAcrossGrantedUnitsAndExplicitClearIsEmpty() throws Exception {
        String root=root();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-b'",Integer.class)==0)jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-b')");
        JsonNode first=customer(root,"tenant-a","scope-customer-a",List.of("summary:read"));
        JsonNode second=customer(root,"tenant-b","scope-customer-b",List.of("summary:read"));
        JsonNode all=body(call(HttpMethod.GET,"/api/management/users",root,null,null,null).andExpect(status().isOk()));
        assertThat(all.at("/items").toString()).contains(first.get("id").asText(),second.get("id").asText());
        assertThat(all.at("/items").toString()).contains(jdbc.queryForObject("SELECT name FROM tenants WHERE id='tenant-a'",String.class),jdbc.queryForObject("SELECT name FROM tenants WHERE id='tenant-b'",String.class));
        JsonNode cleared=body(call(HttpMethod.GET,"/api/management/users?scope_selected=true&scope_tenant_ids=&scope_place_ids=",root,null,null,null).andExpect(status().isOk()));
        assertThat(cleared.get("total").asInt()).isZero();
        assertThat(cleared.get("items").isEmpty()).isTrue();
    }
    @Test void catalogIsAudienceFilteredAndDelegationIsIntersection() throws Exception {
        String root=root(); String basic=activate("customer-a",INITIAL,UPDATED,"customer");
        JsonNode catalog=body(call(HttpMethod.GET,"/api/auth/permission-catalog",basic,null,null,null).andExpect(status().isOk()));
        assertThat(catalog.get("password_policy").get("min_length").asInt()).isEqualTo(12);
        for(JsonNode item:catalog.get("items")) assertThat(PermissionCatalog.CUSTOMER_KEYS).contains(item.get("key").asText());
        assertThat(catalog.get("items").toString()).doesNotContain("settings:write").doesNotContain("platform-users:");
        var permissions=List.of("users:read","users:write","platform-users:read","platform-users:write","platform-users:reset-password","units:read");
        platform(root,"limited-admin",permissions,List.of("tenant-a")); String limited=activate("limited-admin",TARGET,UPDATED,"management");
        call(HttpMethod.POST,"/api/management/platform-users",limited,null,"illegal-permission",Map.of("username","bad-permission","name","x","initial_password",TARGET,"permissions",List.of("settings:write"),"tenant_ids",List.of("tenant-a"))).andExpect(status().isForbidden());
        call(HttpMethod.POST,"/api/management/platform-users",limited,null,"illegal-grant",Map.of("username","bad-grant","name","x","initial_password",TARGET,"permissions",List.of(),"tenant_ids",List.of("tenant-b"))).andExpect(status().isForbidden());
        for(String permission:List.of("settings:read","not-a-permission")) call(HttpMethod.POST,"/api/management/users",root,"tenant-a","bad-catalog-"+permission.replace(':','-'),Map.of("username","bad-customer","name","x","initial_password",TARGET,"permissions",List.of(permission))).andExpect(status().isForbidden());
        call(HttpMethod.GET,"/api/management/users",limited,"tenant-b",null,null).andExpect(status().isForbidden());
        call(HttpMethod.GET,"/api/customer/users",basic,null,null,null).andExpect(status().isForbidden());
        call(HttpMethod.GET,"/api/management/platform-users",basic,null,null,null).andExpect(status().isForbidden());
    }
    @Test void rootSelfAndCrossTenantTargetsAreProtected() throws Exception {
        String root=root(); var perms=List.of("users:read","users:write","users:reset-password");
        JsonNode a=customer(root,"tenant-a","customer-manager",perms); String aToken=activate("customer-manager",TARGET,UPDATED,"customer");
        JsonNode b=customer(root,"tenant-b","other-customer",List.of("summary:read"));
        JsonNode leaf=body(call(HttpMethod.POST,"/api/customer/users",aToken,null,"customer-create",Map.of("username","customer-leaf","name","leaf","initial_password",TARGET,"permissions",List.of("users:read"))).andExpect(status().isOk()));
        assertThat(leaf.get("tenant_id").asText()).isEqualTo("tenant-a");
        call(HttpMethod.PUT,"/api/customer/users/"+leaf.get("id").asText(),aToken,null,"customer-update",Map.of("name","leaf updated","enabled",true,"permissions",List.of("users:read"),"expected_revision",0)).andExpect(status().isOk());
        call(HttpMethod.POST,"/api/customer/users/"+leaf.get("id").asText()+"/reset-password",aToken,null,"customer-reset",Map.of("new_password",RESET,"expected_revision",1)).andExpect(status().isOk()).andExpect(jsonPath("$.must_change_password").value(true));
        login("customer-leaf",RESET,"customer");
        var change=Map.of("name","x","enabled",true,"permissions",perms,"expected_revision",revision(a.get("id").asText()));
        call(HttpMethod.PUT,"/api/customer/users/"+a.get("id").asText(),aToken,null,"self",change).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_MANAGEMENT_FORBIDDEN"));
        call(HttpMethod.PUT,"/api/customer/users/"+b.get("id").asText(),aToken,null,"foreign",change).andExpect(status().isNotFound());
        call(HttpMethod.PUT,"/api/customer/users/bootstrap-admin",aToken,null,"wrong-type",change).andExpect(status().isNotFound());
        platform(root,"operator",List.of("platform-users:write","platform-users:reset-password"),List.of()); String operator=activate("operator",TARGET,UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/platform-users/bootstrap-admin",operator,null,"protected",platformUpdate("root",true,List.of(),List.of(),revision("bootstrap-admin"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROTECTED_ACCOUNT"));
        call(HttpMethod.POST,"/api/management/platform-users/bootstrap-admin/reset-password",operator,null,"protected-reset",Map.of("new_password",RESET,"expected_revision",revision("bootstrap-admin"))).andExpect(status().isConflict());
        call(HttpMethod.PUT,"/api/management/platform-users/bootstrap-admin",root,null,"root-self",platformUpdate("root",true,List.of(),List.of(),revision("bootstrap-admin"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_MANAGEMENT_FORBIDDEN"));
    }
    @Test void customerCannotResetOrDowngradeTargetWithHigherCurrentPermissions() throws Exception {
        String root=root();
        customer(root,"tenant-a","low-customer-manager",List.of("users:read","users:write","users:reset-password"));
        JsonNode high=customer(root,"tenant-a","higher-customer",List.of("alarm:review")); String id=high.get("id").asText();
        String low=activate("low-customer-manager",TARGET,UPDATED,"customer"), highSession=activate("higher-customer",TARGET,UPDATED,"customer");
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,id);
        call(HttpMethod.POST,"/api/customer/users/"+id+"/reset-password",low,null,"customer-boundary-reset",Map.of("new_password",RESET,"expected_revision",1)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TARGET_MANAGEMENT_FORBIDDEN"));
        call(HttpMethod.PUT,"/api/customer/users/"+id,low,null,"customer-boundary-update",Map.of("name","downgraded","enabled",false,"permissions",List.of(),"expected_revision",1)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,id)).isEqualTo(hash); assertThat(revision(id)).isEqualTo(1);
        call(HttpMethod.GET,"/api/auth/me",highSession,null,null,null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_command_records WHERE idem_key LIKE '%boundary%'",Long.class)).isZero();
    }
    @Test void platformCannotResetOrDowngradeTargetWithHigherPermissionsOrBroaderCurrentGrants() throws Exception {
        String root=root(); var lowPermissions=List.of("platform-users:read","platform-users:write","platform-users:reset-password");
        platform(root,"low-platform-manager",lowPermissions,List.of("tenant-a")); String low=activate("low-platform-manager",TARGET,UPDATED,"management");
        JsonNode highPermissions=platform(root,"higher-platform",List.of("settings:read"),List.of("tenant-a"));
        JsonNode broadGrants=platform(root,"broader-platform",List.of("platform-users:read"),List.of("tenant-b"));
        for(JsonNode target:List.of(highPermissions,broadGrants)) {
            String id=target.get("id").asText(), username=target.get("username").asText(); String session=activate(username,TARGET,UPDATED,"management");
            String hash=jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,id);
            call(HttpMethod.POST,"/api/management/platform-users/"+id+"/reset-password",low,null,"platform-boundary-reset-"+username,Map.of("new_password",RESET,"expected_revision",1)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TARGET_MANAGEMENT_FORBIDDEN"));
            call(HttpMethod.PUT,"/api/management/platform-users/"+id,low,null,"platform-boundary-update-"+username,platformUpdate("downgraded",false,List.of(),List.of(),1)).andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,id)).isEqualTo(hash); assertThat(revision(id)).isEqualTo(1);
            call(HttpMethod.GET,"/api/auth/me",session,null,null,null).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_command_records WHERE idem_key LIKE '%boundary%'",Long.class)).isZero();
    }

    @Test void roleTemplateUpdateCannotBypassCurrentTargetAuthorityBound() throws Exception {
        String root=root();
        JsonNode role=body(call(HttpMethod.POST,"/api/management/roles",root,null,"role-template-create",
            Map.of("code","bound-role","name","Bound role","user_type","PLATFORM","enabled",true,
                "permissions",List.of("settings:write"),"reason","Role target boundary regression"))
            .andExpect(status().isOk()));
        JsonNode target=platform(root,"role-bound-target",List.of(),List.of());
        String targetId=target.get("id").asText();
        call(HttpMethod.PUT,"/api/management/platform-users/"+targetId+"/access/roles",root,null,"role-template-assign",
            Map.of("role_ids",List.of(role.get("id").asText()),"expected_revision",revision(targetId),"reason","Assign superior role"))
            .andExpect(status().isOk());
        String targetSession=activate("role-bound-target",TARGET,UPDATED,"management");
        platform(root,"role-bound-operator",List.of("roles:read","roles:write"),List.of());
        String operator=activate("role-bound-operator",TARGET,UPDATED,"management");

        call(HttpMethod.PUT,"/api/management/roles/"+role.get("id").asText(),operator,null,"role-template-strip",
            Map.of("name","Bound role","enabled",false,"permissions",List.of(),"expected_revision",0,"reason","Strip superior target role"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TARGET_MANAGEMENT_FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT revision FROM access_roles WHERE id=?",Long.class,role.get("id").asText())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT enabled FROM access_roles WHERE id=?",Boolean.class,role.get("id").asText())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_role_permissions WHERE role_id=? AND permission='settings:write'",Long.class,role.get("id").asText())).isEqualTo(1L);
        call(HttpMethod.GET,"/api/auth/me",targetSession,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("settings:write")));
    }
    @Test void unitDisableBlocksLoginSessionsAndTenantBusinessButCanBeReenabled() throws Exception {
        String root=root(); String customer=activate("customer-a",INITIAL,UPDATED,"customer");
        var unit=Map.of("name","暂停单位","enabled",false,"expected_revision",0);
        call(HttpMethod.PUT,"/api/management/units/tenant-a",root,null,"disable",unit).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        call(HttpMethod.GET,"/api/auth/me",customer,null,null,null).andExpect(status().isUnauthorized()); loginResult("customer-a",UPDATED,"customer").andExpect(status().isUnauthorized());
        call(HttpMethod.GET,"/api/management/summary",root,"tenant-a",null,null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNIT_DISABLED"));
        call(HttpMethod.GET,"/api/management/units",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        call(HttpMethod.GET,"/api/management/tenants",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE user_id='customer-a' AND revoked=FALSE",Long.class)).isZero();
        call(HttpMethod.PUT,"/api/management/units/tenant-a",root,null,"enable",Map.of("name","恢复单位","enabled",true,"expected_revision",1)).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/auth/me",customer,null,null,null).andExpect(status().isUnauthorized()); login("customer-a",UPDATED,"customer");
        call(HttpMethod.PUT,"/api/management/units/tenant-a",root,null,"stale-unit",unit).andExpect(status().isConflict());
    }
    @Test void targetAccountChangesRevokeSessionsAndResetRequiresNewPassword() throws Exception {
        String root=root(); List<String> perms=List.of("summary:read"); JsonNode target=platform(root,"managed-platform",perms,List.of("tenant-a")); String id=target.get("id").asText(); String old=activate("managed-platform",TARGET,UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/platform-users/"+id,root,null,"disable-account",platformUpdate("disabled",false,perms,List.of("tenant-a"),1)).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/auth/me",old,null,null,null).andExpect(status().isUnauthorized()); loginResult("managed-platform",UPDATED,"management").andExpect(status().isUnauthorized());
        call(HttpMethod.PUT,"/api/management/platform-users/"+id,root,null,"enable-account",platformUpdate("enabled",true,perms,List.of("tenant-a"),2)).andExpect(status().isOk());
        String enabled=login("managed-platform",UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/platform-users/"+id,root,null,"remove-grant",platformUpdate("enabled",true,perms,List.of(),3)).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/auth/me",enabled,null,null,null).andExpect(status().isUnauthorized()); String noGrant=login("managed-platform",UPDATED,"management");
        call(HttpMethod.GET,"/api/management/summary",noGrant,"tenant-a",null,null).andExpect(status().isForbidden());
        var reset=Map.of("new_password",RESET,"expected_revision",4);
        JsonNode result=body(call(HttpMethod.POST,"/api/management/platform-users/"+id+"/reset-password",root,null,"reset-target",reset).andExpect(status().isOk())); assertThat(result.get("must_change_password").asBoolean()).isTrue();
        call(HttpMethod.GET,"/api/auth/me",noGrant,null,null,null).andExpect(status().isUnauthorized()); loginResult("managed-platform",UPDATED,"management").andExpect(status().isUnauthorized());
        String resetSession=login("managed-platform",RESET,"management"); call(HttpMethod.GET,"/api/management/summary",resetSession,"tenant-a",null,null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        assertThat(body(call(HttpMethod.POST,"/api/management/platform-users/"+id+"/reset-password",root,null,"reset-target",reset).andExpect(status().isOk()))).isEqualTo(result);
        call(HttpMethod.POST,"/api/management/platform-users/"+id+"/reset-password",root,null,"reset-target",Map.of("new_password",TARGET,"expected_revision",4)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(body(call(HttpMethod.POST,"/api/management/platform-users/"+id+"/reset-password",root,null,"reset-target",Map.of("new_password",RESET,"expected_revision",4)).andExpect(status().isOk()))).isEqualTo(result);
        var stored=jdbc.queryForMap("SELECT request_hash,secret_hash,response_json FROM identity_command_records WHERE idem_key='reset-target'");
        String all=stored.toString(); for(String secret:List.of(ROOT_PASSWORD,RESET,TARGET)) assertThat(all).doesNotContain(secret).doesNotContain(Digests.sha256(secret));
        assertThat(encoder.matches(RESET,(String)stored.get("secret_hash"))).isTrue(); assertThat((String)stored.get("response_json")).doesNotContain("password_hash").doesNotContain("access_token").doesNotContain("secret_hash");
        String safe=json.writeValueAsString(List.of("PLATFORM","",id,4L)); assertThat(stored.get("request_hash")).isEqualTo(Digests.sha256(safe));
        for(var row:jdbc.queryForList("SELECT * FROM audit_log")) assertThat(row.toString()).doesNotContain(ROOT_PASSWORD).doesNotContain(TARGET).doesNotContain(RESET);
    }
    @Test void initialPasswordIdempotencyUsesSlowHashAndSurvivesSessionChange() throws Exception {
        String root=root(); var create=new HashMap<String,Object>(Map.of("username","secret-user","name","secret-user","initial_password",TARGET,"permissions",List.of("summary:read")));
        JsonNode first=body(call(HttpMethod.POST,"/api/management/users",root,"tenant-a","secret-create",create).andExpect(status().isOk()));
        assertThat(body(call(HttpMethod.POST,"/api/management/users",root,"tenant-a","secret-create",create).andExpect(status().isOk()))).isEqualTo(first);
        create.put("initial_password",RESET); call(HttpMethod.POST,"/api/management/users",root,"tenant-a","secret-create",create).andExpect(status().isConflict());
        create.put("initial_password",TARGET); assertThat(body(call(HttpMethod.POST,"/api/management/users",root,"tenant-a","secret-create",create).andExpect(status().isOk()))).isEqualTo(first);
        var row=jdbc.queryForMap("SELECT * FROM identity_command_records WHERE idem_key='secret-create'"); assertThat(encoder.matches(TARGET,(String)row.get("secret_hash"))).isTrue();
        assertThat(row.toString()).doesNotContain(TARGET).doesNotContain(ROOT_PASSWORD).doesNotContain(Digests.sha256(TARGET));
        String accountHash=jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,first.get("id").asText()); assertThat(row.get("secret_hash")).isNotEqualTo(accountHash);
        String renewed="Renewed-"+UUID.randomUUID();
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",ROOT_PASSWORD,"new_password",renewed)).andExpect(status().isNoContent());
        call(HttpMethod.POST,"/api/management/users",root,"tenant-a","secret-create",create).andExpect(status().isUnauthorized());
        String newSession=login("admin",renewed,"management");
        assertThat(body(call(HttpMethod.POST,"/api/management/users",newSession,"tenant-a","secret-create",create).andExpect(status().isOk()))).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='secret-user'",Long.class)).isEqualTo(1);
    }
    @Test void malformedScopeFieldsPaginationAndMissingSecretsCannotMutate() throws Exception {
        String root=root(); var body=new HashMap<String,Object>(Map.of("username","forged","name","x","initial_password",TARGET,"permissions",List.of()));
        body.put("tenant_id","tenant-b"); call(HttpMethod.POST,"/api/management/users",root,"tenant-a","forged",body).andExpect(status().isBadRequest()); body.remove("tenant_id");
        body.put("user_type","PLATFORM"); call(HttpMethod.POST,"/api/management/users",root,"tenant-a","forged",body).andExpect(status().isBadRequest()); body.remove("user_type");
        body.remove("initial_password"); call(HttpMethod.POST,"/api/management/users",root,"tenant-a","missing-initial-password",body).andExpect(status().isBadRequest());
        jdbc.update("UPDATE numeric_settings SET setting_value=5 WHERE setting_key='default_page_size'");
        call(HttpMethod.GET,"/api/management/units?keyword=%25",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.size").value(5));
        call(HttpMethod.GET,"/api/management/platform-users?size=101",root,null,null,null).andExpect(status().isBadRequest());
        JsonNode list=body(call(HttpMethod.GET,"/api/management/platform-users",root,null,null,null).andExpect(status().isOk())); assertThat(list.toString()).doesNotContain("password_hash").doesNotContain("access_token");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='forged'",Long.class)).isZero();
    }
    @Test void concurrentAdminsCannotDeadlockAndRevokedActorCannotCommit() throws Exception {
        String root=root(); var perms=List.of("platform-users:read","platform-users:write");
        String aId=platform(root,"admin-one",perms,List.of()).get("id").asText(), bId=platform(root,"admin-two",perms,List.of()).get("id").asText();
        String a=activate("admin-one",TARGET,UPDATED,"management"), b=activate("admin-two",TARGET,UPDATED,"management");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var jobs=executor.invokeAll(List.<Callable<Integer>>of(()->call(HttpMethod.PUT,"/api/management/platform-users/"+bId,a,null,"a-to-b",platformUpdate("changed-b",true,perms,List.of(),1)).andReturn().getResponse().getStatus(),()->call(HttpMethod.PUT,"/api/management/platform-users/"+aId,b,null,"b-to-a",platformUpdate("changed-a",true,perms,List.of(),1)).andReturn().getResponse().getStatus()),20,TimeUnit.SECONDS);
            var statuses=new ArrayList<Integer>(); for(var job:jobs) { assertThat(job.isCancelled()).isFalse(); statuses.add(job.get()); }
            assertThat(statuses).contains(200); assertThat(statuses).allMatch(s->Set.of(200,401,403).contains(s)); assertThat(Collections.frequency(statuses,200)).isEqualTo(1);
        }
    }
    @Test void failedAuditRollsBackAccountPermissionsAndSecretLedger() throws Exception {
        String root=root(); jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT reject_customer_create CHECK (action <> 'CUSTOMER-CREATE')");
        try {
            call(HttpMethod.POST,"/api/management/users",root,"tenant-a","failed-audit",Map.of("username","rolled-back","name","x","initial_password",TARGET,"permissions",List.of("summary:read"))).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='rolled-back'",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_command_records",Long.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT reject_customer_create"); }
    }
}
