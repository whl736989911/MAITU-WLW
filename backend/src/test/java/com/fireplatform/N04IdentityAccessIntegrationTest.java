package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.identity.DeviceScopes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:n04access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class N04IdentityAccessIntegrationTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), ROOT_PASSWORD="Root-"+UUID.randomUUID(), TARGET="Target-"+UUID.randomUUID(), UPDATED="Updated-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME",()->"admin"); registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired DeviceScopes deviceScopes;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.fireplatform.device.DeviceQueryService deviceReads;

    @BeforeEach void reset() {
        // Child tables first; scope rows reference both identity users and units.
        jdbc.update("DELETE FROM identity_user_metadata");
        for(String table:List.of("duty_fault_handling_history","duty_fault_handlings","legacy_ingest_events","user_device_scope_places","user_device_scope_devices","user_device_scopes","access_user_roles","access_role_permissions","access_roles",
            "identity_command_records","idempotency_records","alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","real_device_battery_observations","device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","sessions","user_permissions","user_tenant_grants","app_users","devices","places","tenants","audit_log"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE numeric_settings SET setting_value=20,revision=0 WHERE setting_key='default_page_size'");
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','N04 test tenant A'),('tenant-b','N04 test tenant B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES ('device-a','tenant-a','N04 fixture A','n04-a','fixture','A','NORMAL',FALSE,'LEGACY_UNVERIFIED'),('device-a-unactivated','tenant-a','N04 fixture A2','n04-a2','fixture','A2','NORMAL',FALSE,'LEGACY_UNVERIFIED'),('device-b','tenant-b','N04 fixture B','n04-b','fixture','B','NORMAL',FALSE,'LEGACY_UNVERIFIED')");
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id,revision) VALUES ('n04-root','tenant-a','N04 root',NULL,0),('n04-child','tenant-a','N04 child','n04-root',0),('n04-other','tenant-a','N04 other',NULL,0),('n04-tenant-b','tenant-b','N04 foreign root',NULL,0)");
        jdbc.update("UPDATE devices SET place_id='n04-child' WHERE id='device-a'");
        jdbc.update("UPDATE devices SET place_id='n04-other' WHERE id='device-a-unactivated'");
        N04TestScopes.grantExisting(jdbc);
    }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object value) throws Exception {
        var request=request(method,path); if(token!=null) request.header("Authorization","Bearer "+token);
        if(tenant!=null) request.header("X-Tenant-Id",tenant); if(key!=null) request.header("Idempotency-Key",key);
        if(value!=null) request.contentType("application/json").content(json.writeValueAsString(value)); return mvc.perform(request);
    }
    ResultActions loginResult(String username,String password,String audience) throws Exception { return call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",username,"password",password,"audience",audience)); }
    String login(String username,String password,String audience) throws Exception { return body(loginResult(username,password,audience).andExpect(status().isOk())).get("access_token").asText(); }
    String activate(String username,String initial,String next,String audience) throws Exception {
        String token=login(username,initial,audience);
        call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",initial,"new_password",next)).andExpect(status().isNoContent());
        return login(username,next,audience);
    }
    String root() throws Exception { return activate("admin",INITIAL,ROOT_PASSWORD,"management"); }
    JsonNode customer(String root,String tenant,String username,List<String> permissions) throws Exception {
        return body(call(HttpMethod.POST,"/api/management/users",root,tenant,UUID.randomUUID().toString(),
            Map.of("username",username,"name",username,"initial_password",TARGET,"permissions",permissions)).andExpect(status().isOk()));
    }
    JsonNode platform(String root,String username,List<String> permissions,List<String> grants) throws Exception {
        return body(call(HttpMethod.POST,"/api/management/platform-users",root,null,UUID.randomUUID().toString(),
            Map.of("username",username,"name",username,"initial_password",TARGET,"permissions",permissions,"tenant_ids",grants)).andExpect(status().isOk()));
    }
    JsonNode createRole(String root,String code,String userType,List<String> permissions) throws Exception {
        return body(call(HttpMethod.POST,"/api/management/roles",root,null,UUID.randomUUID().toString(),
            Map.of("code",code,"name",code,"user_type",userType,"enabled",true,"permissions",permissions,"reason","创建模板")).andExpect(status().isOk()));
    }
    Map<String,Object> roleUpdate(String name,boolean enabled,List<String> permissions,long revision) {
        return Map.of("name",name,"enabled",enabled,"permissions",permissions,"expected_revision",revision,"reason","调整模板");
    }
    Map<String,Object> assignRoles(List<String> roleIds,long revision) {
        return Map.of("role_ids",roleIds,"expected_revision",revision,"reason","分配角色");
    }
    Map<String,Object> assignScope(String mode,List<String> devices,long revision) {
        return Map.of("mode",mode,"device_ids",devices,"place_ids",List.of(),"expected_revision",revision,"reason","分配设备范围");
    }
    Map<String,Object> assignPlaceScope(List<String> places,long revision) {
        return Map.of("mode","PLACE_SUBTREE","device_ids",List.of(),"place_ids",places,"expected_revision",revision,"reason","分配动态位置范围");
    }
    long revision(String id) { return jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?",Long.class,id); }
    static List<String> texts(JsonNode node) { var values=new ArrayList<String>(); if(node!=null) node.forEach(value->values.add(value.asText())); return values; }
    List<String> visibleDevices(String actor,String tenant) {
        var filter=deviceScopes.predicate(actor,"devices.tenant_id","devices.id");
        var args=new ArrayList<Object>(); args.add(tenant); args.addAll(filter.bindings());
        return jdbc.queryForList("SELECT id FROM devices WHERE tenant_id=?"+filter.and()+" ORDER BY id",String.class,args.toArray());
    }

    @Test void roleTemplatesAreStrictIdempotentAndCodesSurviveSoftDelete() throws Exception {
        String root=root();
        Map<String,Object> create=Map.of("code","n04-desk","name","前台","user_type","CUSTOMER","enabled",true,
            "permissions",List.of("alarm:history","users:read"),"reason","初始模板");
        JsonNode role=body(call(HttpMethod.POST,"/api/management/roles",root,null,"role-create",create).andExpect(status().isOk()));
        String id=role.get("id").asText();
        assertThat(role.get("code").asText()).isEqualTo("n04-desk"); assertThat(role.get("user_type").asText()).isEqualTo("CUSTOMER");
        assertThat(role.get("revision").asLong()).isZero(); assertThat(role.get("deleted").asBoolean()).isFalse();
        assertThat(texts(role.get("permissions"))).containsExactly("alarm:history","users:read");
        assertThat(body(call(HttpMethod.POST,"/api/management/roles",root,null,"role-create",create).andExpect(status().isOk()))).isEqualTo(role);
        Map<String,Object> changed=new HashMap<>(create); changed.put("name","改过");
        call(HttpMethod.POST,"/api/management/roles",root,null,"role-create",changed).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        call(HttpMethod.POST,"/api/management/roles",root,null,null,create).andExpect(status().isBadRequest());
        Map<String,Object> unknown=new HashMap<>(create); unknown.put("role_id","x");
        call(HttpMethod.POST,"/api/management/roles",root,null,"role-unknown",unknown).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/roles",root,null,"role-dup",create).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_CODE_CONFLICT"));
        call(HttpMethod.POST,"/api/management/roles",root,null,"role-bad-perm",Map.of("code","n04-bad","name","x","user_type","CUSTOMER","enabled",true,
            "permissions",List.of("not-a-permission"),"reason","x")).andExpect(status().isForbidden());

        call(HttpMethod.GET,"/api/management/roles",root,null,null,null).andExpect(status().isBadRequest());
        call(HttpMethod.GET,"/api/management/roles?user_type=CUSTOMER&page=1&size=20",root,null,null,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].code").value("n04-desk"));
        call(HttpMethod.GET,"/api/management/roles?user_type=CUSTOMER&unexpected=1",root,null,null,null).andExpect(status().isBadRequest());

        customer(root,"tenant-a","n04-role-reader",List.of("roles:read"));
        String reader=activate("n04-role-reader",TARGET,UPDATED,"customer");
        call(HttpMethod.GET,"/api/customer/roles?page=1&size=20",reader,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        call(HttpMethod.GET,"/api/customer/roles?page=1&size=20&user_type=PLATFORM",reader,null,null,null).andExpect(status().isBadRequest());
        call(HttpMethod.POST,"/api/management/roles",reader,null,"customer-role-write",create).andExpect(status().isForbidden());

        JsonNode updated=body(call(HttpMethod.PUT,"/api/management/roles/"+id,root,null,"role-update",roleUpdate("前台2",false,List.of("users:read"),0)).andExpect(status().isOk()));
        assertThat(updated.get("revision").asLong()).isEqualTo(1L); assertThat(updated.get("enabled").asBoolean()).isFalse();
        call(HttpMethod.PUT,"/api/management/roles/"+id,root,null,"role-update-stale",roleUpdate("前台3",true,List.of("users:read"),0))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        JsonNode deleted=body(call(HttpMethod.DELETE,"/api/management/roles/"+id,root,null,"role-delete",
            Map.of("expected_revision",1,"reason","删除模板")).andExpect(status().isOk()));
        assertThat(deleted.get("deleted").asBoolean()).isTrue(); assertThat(deleted.get("revision").asLong()).isEqualTo(2L);
        call(HttpMethod.DELETE,"/api/management/roles/"+id,root,null,"role-delete-again",
            Map.of("expected_revision",2,"reason","重复删除")).andExpect(status().isConflict());
        Map<String,Object> reuse=new HashMap<>(create); reuse.put("name","复用");
        call(HttpMethod.POST,"/api/management/roles",root,null,"role-reuse",reuse).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_CODE_CONFLICT"));
        call(HttpMethod.GET,"/api/management/roles?user_type=CUSTOMER&page=1&size=20",root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test void assignmentUnionsEffectivePermissionsWhileManagedUserStaysDirectAndDisableRevokesSessions() throws Exception {
        String root=root();
        JsonNode role=createRole(root,"n04-alarm-hist","CUSTOMER",List.of("alarm:history"));
        String roleId=role.get("id").asText();
        JsonNode target=customer(root,"tenant-a","n04-union",List.of("summary:read")); String id=target.get("id").asText();
        assertThat(texts(target.get("permissions"))).containsExactly("summary:read");
        String session=activate("n04-union",TARGET,UPDATED,"customer");
        assertThat(revision(id)).isEqualTo(1L);
        assertThat(texts(body(call(HttpMethod.GET,"/api/auth/me",session,null,null,null).andExpect(status().isOk())).get("permissions"))).containsExactly("summary:read");

        Map<String,Object> assign=assignRoles(List.of(roleId),1);
        JsonNode access=body(call(HttpMethod.PUT,"/api/management/users/"+id+"/access/roles",root,"tenant-a","assign-union",assign).andExpect(status().isOk()));
        assertThat(texts(access.get("direct_permissions"))).containsExactly("summary:read");
        assertThat(texts(access.get("effective_permissions"))).containsExactly("alarm:history","summary:read");
        assertThat(access.get("roles")).hasSize(1); assertThat(access.get("revision").asLong()).isEqualTo(2L);
        call(HttpMethod.GET,"/api/auth/me",session,null,null,null).andExpect(status().isUnauthorized());
        assertThat(body(call(HttpMethod.PUT,"/api/management/users/"+id+"/access/roles",root,"tenant-a","assign-union",assign).andExpect(status().isOk()))).isEqualTo(access);

        JsonNode list=body(call(HttpMethod.GET,"/api/management/users?keyword=n04-union&page=1&size=20",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(texts(list.get("items").get(0).get("permissions"))).containsExactly("summary:read");
        JsonNode relogin=body(loginResult("n04-union",UPDATED,"customer").andExpect(status().isOk()));
        assertThat(texts(relogin.get("permissions"))).containsExactly("alarm:history","summary:read");
        String active=relogin.get("access_token").asText();

        body(call(HttpMethod.PUT,"/api/management/roles/"+roleId,root,null,"role-disable",roleUpdate("n04-alarm-hist",false,List.of("alarm:history"),0)).andExpect(status().isOk()));
        assertThat(revision(id)).isEqualTo(3L);
        call(HttpMethod.GET,"/api/auth/me",active,null,null,null).andExpect(status().isUnauthorized());
        JsonNode afterDisable=body(loginResult("n04-union",UPDATED,"customer").andExpect(status().isOk()));
        assertThat(texts(afterDisable.get("permissions"))).containsExactly("summary:read");
        JsonNode retained=body(call(HttpMethod.GET,"/api/management/users/"+id+"/access",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(retained.get("roles")).hasSize(1); assertThat(retained.get("roles").get(0).get("enabled").asBoolean()).isFalse();
        assertThat(texts(retained.get("effective_permissions"))).containsExactly("summary:read");

        call(HttpMethod.DELETE,"/api/management/roles/"+roleId,root,null,"role-delete-assigned",
            Map.of("expected_revision",1,"reason","停用后删除"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_ASSIGNED"));
    }

    @Test void deviceScopesAreExplicitBoundedAndNeverImplicit() throws Exception {
        String root=root();
        JsonNode target=customer(root,"tenant-a","n04-scope",List.of("summary:read")); String id=target.get("id").asText();
        String session=activate("n04-scope",TARGET,UPDATED,"customer");
        JsonNode unset=body(call(HttpMethod.GET,"/api/management/users/"+id+"/scope",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(unset.get("configured").asBoolean()).isFalse(); assertThat(unset.get("mode").isNull()).isTrue();
        assertThat(unset.get("revision").asLong()).isZero(); assertThat(unset.get("device_ids")).isEmpty();
        call(HttpMethod.PUT,"/api/management/users/"+id+"/scope",root,"tenant-a","scope-bad-all",
            assignScope("ALL_TENANT",List.of("device-a"),0)).andExpect(status().isBadRequest());
        call(HttpMethod.PUT,"/api/management/users/"+id+"/scope",root,"tenant-a","scope-foreign-device",
            assignScope("DEVICE_SET",List.of("device-b"),0)).andExpect(status().isNotFound());
        JsonNode scoped=body(call(HttpMethod.PUT,"/api/management/users/"+id+"/scope",root,"tenant-a","scope-set",
            assignScope("DEVICE_SET",List.of("device-a-unactivated","device-a"),0)).andExpect(status().isOk()));
        assertThat(scoped.get("mode").asText()).isEqualTo("DEVICE_SET"); assertThat(scoped.get("revision").asLong()).isEqualTo(1L);
        assertThat(texts(scoped.get("device_ids"))).containsExactly("device-a","device-a-unactivated");
        call(HttpMethod.GET,"/api/auth/me",session,null,null,null).andExpect(status().isUnauthorized());
        String again=login("n04-scope",UPDATED,"customer");
        JsonNode own=body(call(HttpMethod.GET,"/api/customer/device-scope",again,null,null,null).andExpect(status().isOk()));
        assertThat(own.get("configured").asBoolean()).isTrue(); assertThat(own.get("tenant_id").asText()).isEqualTo("tenant-a");
        assertThat(texts(own.get("device_ids"))).containsExactly("device-a","device-a-unactivated");
        assertThat(visibleDevices(id,"tenant-a")).containsExactly("device-a","device-a-unactivated");
        JsonNode unscoped=customer(root,"tenant-a","n04-unscoped",List.of("summary:read"));
        assertThat(visibleDevices(unscoped.get("id").asText(),"tenant-a")).isEmpty();
        assertThat(visibleDevices("bootstrap-admin","tenant-a")).containsExactly("device-a","device-a-unactivated");

        // Narrowed operator: cannot read, grant or take over scope above its own explicit set.
        JsonNode ops=platform(root,"n04-ops",List.of("summary:read","users:read","users:assign-scope","platform-users:assign-scope","platform-users:read"),List.of("tenant-a"));
        String opsId=ops.get("id").asText();
        String opsToken=activate("n04-ops",TARGET,UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/platform-users/"+opsId+"/scope",root,"tenant-a","ops-scope",
            Map.of("mode","DEVICE_SET","device_ids",List.of("device-a"),"place_ids",List.of(),"expected_revision",0,"reason","缩小范围")).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/auth/me",opsToken,null,null,null).andExpect(status().isUnauthorized());
        opsToken=login("n04-ops",UPDATED,"management");
        JsonNode opsDeviceScope=body(call(HttpMethod.GET,"/api/management/device-scope",opsToken,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(texts(opsDeviceScope.get("device_ids"))).containsExactly("device-a");
        call(HttpMethod.PUT,"/api/management/platform-users/"+opsId+"/scope",opsToken,"tenant-a","ops-self",
            Map.of("mode","ALL_TENANT","device_ids",List.of(),"place_ids",List.of(),"expected_revision",1,"reason","自我扩权"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_MANAGEMENT_FORBIDDEN"));
        call(HttpMethod.PUT,"/api/management/users/"+id+"/scope",opsToken,"tenant-a","ops-takeover",
            Map.of("mode","DEVICE_SET","device_ids",List.of("device-a"),"place_ids",List.of(),"expected_revision",1,"reason","越权扩围"))
            .andExpect(status().isForbidden());
        call(HttpMethod.GET,"/api/management/users/"+id+"/scope",root,"tenant-a",null,null).andExpect(status().isOk());
        body(call(HttpMethod.PUT,"/api/management/users/"+id+"/scope",root,"tenant-a","scope-all",
            assignScope("ALL_TENANT",List.of(),1)).andExpect(status().isOk()));
        call(HttpMethod.GET,"/api/management/users/"+id+"/scope",opsToken,"tenant-a",null,null)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TARGET_MANAGEMENT_FORBIDDEN"));

        // A narrowed actor can never mint ALL_TENANT; batch member rejection is atomic; an empty set grants nothing.
        JsonNode fresh=customer(root,"tenant-a","n04-ops-fresh",List.of("summary:read")); String freshId=fresh.get("id").asText();
        call(HttpMethod.PUT,"/api/management/users/"+freshId+"/scope",opsToken,"tenant-a","ops-all-denied",
            Map.of("mode","ALL_TENANT","device_ids",List.of(),"place_ids",List.of(),"expected_revision",0,"reason","越权全量"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(HttpMethod.PUT,"/api/management/users/"+freshId+"/scope",opsToken,"tenant-a","ops-batch",
            Map.of("mode","DEVICE_SET","device_ids",List.of("device-a","device-a-unactivated"),"place_ids",List.of(),"expected_revision",0,"reason","整批"))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id=?",Long.class,freshId)).isZero();
        call(HttpMethod.PUT,"/api/management/users/"+freshId+"/scope",opsToken,"tenant-a","ops-set",
            Map.of("mode","DEVICE_SET","device_ids",List.of("device-a"),"place_ids",List.of(),"expected_revision",0,"reason","集合"))
            .andExpect(status().isOk());
        call(HttpMethod.PUT,"/api/management/users/"+freshId+"/scope",opsToken,"tenant-a","ops-outside",
            Map.of("mode","DEVICE_SET","device_ids",List.of("device-a-unactivated"),"place_ids",List.of(),"expected_revision",1,"reason","越界"))
            .andExpect(status().isNotFound());
        JsonNode emptyUser=customer(root,"tenant-a","n04-empty-set",List.of("summary:read")); String emptyId=emptyUser.get("id").asText();
        JsonNode emptySet=body(call(HttpMethod.PUT,"/api/management/users/"+emptyId+"/scope",root,"tenant-a","scope-empty",
            assignScope("DEVICE_SET",List.of(),0)).andExpect(status().isOk()));
        assertThat(emptySet.get("configured").asBoolean()).isTrue(); assertThat(emptySet.get("mode").asText()).isEqualTo("DEVICE_SET");
        assertThat(emptySet.get("device_ids")).isEmpty();
        assertThat(visibleDevices(emptyId,"tenant-a")).isEmpty();
    }

    @Test void placeSubtreeScopeIncludesFutureDevicesDropsMovedDevicesAndCannotBeGrantedFromDeviceSet() throws Exception {
        String root=root();
        JsonNode target=customer(root,"tenant-a","n04-place-target",List.of("device:read"));
        String targetId=target.get("id").asText();
        JsonNode scoped=body(call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",root,"tenant-a","place-root",
            assignPlaceScope(List.of("n04-root"),0)).andExpect(status().isOk()));
        assertThat(scoped.get("mode").asText()).isEqualTo("PLACE_SUBTREE");
        assertThat(scoped.get("device_ids")).isEmpty();
        assertThat(texts(scoped.get("place_ids"))).containsExactly("n04-root");
        assertThat(visibleDevices(targetId,"tenant-a")).containsExactly("device-a");
        String fingerprint=deviceScopes.fingerprint(targetId,"tenant-a");

        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state,place_id) VALUES('device-future','tenant-a','Future child','n04-future','fixture','F','NORMAL',FALSE,'LEGACY_UNVERIFIED','n04-child')");
        assertThat(visibleDevices(targetId,"tenant-a")).containsExactly("device-a","device-future");
        assertThat(deviceScopes.fingerprint(targetId,"tenant-a")).isEqualTo(fingerprint);
        jdbc.update("UPDATE devices SET place_id='n04-other' WHERE id='device-a'");
        assertThat(visibleDevices(targetId,"tenant-a")).containsExactly("device-future");
        assertThat(deviceScopes.fingerprint(targetId,"tenant-a")).isEqualTo(fingerprint);

        String targetSession=activate("n04-place-target",TARGET,UPDATED,"customer");
        JsonNode own=body(call(HttpMethod.GET,"/api/customer/device-scope",targetSession,null,null,null).andExpect(status().isOk()));
        assertThat(own.get("mode").asText()).isEqualTo("PLACE_SUBTREE");
        assertThat(texts(own.get("place_ids"))).containsExactly("n04-root");

        // A manager must itself hold the permissions of the accounts it manages (ManagementBounds.check),
        // so the operator carries the target reader's device:read and only the device scope can refuse it.
        JsonNode ops=platform(root,"n04-place-ops",List.of("device:read","users:read","users:assign-scope","platform-users:read","platform-users:assign-scope","units:read","units:write"),List.of("tenant-a"));
        String opsId=ops.get("id").asText();
        String opsToken=activate("n04-place-ops",TARGET,UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/platform-users/"+opsId+"/scope",root,"tenant-a","ops-device-set",
            assignScope("DEVICE_SET",List.of("device-future"),0)).andExpect(status().isOk());
        assertThat(deviceScopes.covers(opsId,targetId)).isFalse();
        // Assigning a scope revokes that account's sessions, so the operator must re-authenticate before managing anything.
        opsToken=login("n04-place-ops",UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",opsToken,"tenant-a","ops-place-denied",
            Map.of("mode","PLACE_SUBTREE","device_ids",List.of(),"place_ids",List.of("n04-root"),"expected_revision",1,"reason","设备集合不能授未来位置权"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TARGET_MANAGEMENT_FORBIDDEN"));
        call(HttpMethod.PUT,"/api/management/platform-users/"+opsId+"/scope",root,"tenant-a","ops-place",
            assignPlaceScope(List.of("n04-root"),1)).andExpect(status().isOk());
        assertThat(deviceScopes.covers(opsId,targetId)).isTrue();
        opsToken=login("n04-place-ops",UPDATED,"management");
        JsonNode createdPlace=body(call(HttpMethod.POST,"/api/management/places",opsToken,"tenant-a","ops-create-child",
            Map.of("name","Ops child under scope","parent_place_id","n04-child")).andExpect(status().isOk()));
        call(HttpMethod.POST,"/api/management/places",opsToken,"tenant-a","ops-create-outside",
            Map.of("name","Outside root","parent_place_id","n04-other")).andExpect(status().isNotFound());
        call(HttpMethod.POST,"/api/management/places",opsToken,"tenant-a","ops-create-root",
            Map.of("name","Root denied")).andExpect(status().isForbidden());
        JsonNode scopeTree=body(call(HttpMethod.GET,"/api/management/scope-tree",opsToken,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(scopeTree.toString()).contains(createdPlace.get("id").asText(),"n04-child");
        JsonNode child=body(call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",opsToken,"tenant-a","ops-place-child",
            Map.of("mode","PLACE_SUBTREE","device_ids",List.of(),"place_ids",List.of("n04-child"),"expected_revision",1,"reason","覆盖子位置"))
            .andExpect(status().isOk()));
        assertThat(texts(child.get("place_ids"))).containsExactly("n04-child");
        call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",opsToken,"tenant-a","ops-place-outside",
            Map.of("mode","PLACE_SUBTREE","device_ids",List.of(),"place_ids",List.of("n04-other"),"expected_revision",2,"reason","超出操作者结构范围"))
            .andExpect(status().isForbidden());

        call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",root,"tenant-a","place-empty",
            Map.of("mode","PLACE_SUBTREE","device_ids",List.of(),"place_ids",List.of(),"expected_revision",2,"reason","空位置根"))
            .andExpect(status().isBadRequest());
        call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",root,"tenant-a","place-with-devices",
            Map.of("mode","PLACE_SUBTREE","device_ids",List.of("device-future"),"place_ids",List.of("n04-root"),"expected_revision",2,"reason","位置与设备混用"))
            .andExpect(status().isBadRequest());
        call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",root,"tenant-a","all-with-places",
            Map.of("mode","ALL_TENANT","device_ids",List.of(),"place_ids",List.of("n04-root"),"expected_revision",2,"reason","全单位不能带位置"))
            .andExpect(status().isBadRequest());
        call(HttpMethod.PUT,"/api/management/users/"+targetId+"/scope",root,"tenant-a","place-foreign",
            Map.of("mode","PLACE_SUBTREE","device_ids",List.of(),"place_ids",List.of("n04-tenant-b"),"expected_revision",2,"reason","跨单位位置"))
            .andExpect(status().isNotFound());
    }

    @Test void structuralCoverageNeverBorrowsPlaceAuthorityFromAnotherUnit() throws Exception {
        String root = root();
        String actor = platform(root, "n04-place-multiunit", List.of("device:read"), List.of("tenant-a", "tenant-b"))
            .path("id").asText();
        String assignment = "/api/management/platform-users/" + actor + "/scope";
        call(HttpMethod.PUT, assignment, root, "tenant-a", "multiunit-scope-a", assignPlaceScope(List.of("n04-root"), 0))
            .andExpect(status().isOk());
        call(HttpMethod.PUT, assignment, root, "tenant-b", "multiunit-scope-b", assignPlaceScope(List.of("n04-tenant-b"), 0))
            .andExpect(status().isOk());

        assertThat(deviceScopes.coversPlaces(actor, "tenant-a", List.of("n04-child"))).isTrue();
        assertThat(deviceScopes.coversPlaces(actor, "tenant-b", List.of("n04-tenant-b"))).isTrue();
        assertThat(deviceScopes.coversPlaces(actor, "tenant-a", List.of("n04-child", "n04-tenant-b"))).isFalse();
        assertThat(deviceScopes.coversPlaces("bootstrap-admin", "tenant-a", List.of("n04-tenant-b"))).isFalse();
    }

    @Test void selfProtectedTypeAndDelegationBoundsAreEnforced() throws Exception {
        String root=root();
        long rootRevision=revision("bootstrap-admin");
        call(HttpMethod.PUT,"/api/management/platform-users/bootstrap-admin/access/roles",root,null,"root-self-roles",assignRoles(List.of(),rootRevision))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_MANAGEMENT_FORBIDDEN"));
        JsonNode operator=platform(root,"n04-operator",List.of("platform-users:assign-roles","platform-users:read","roles:read"),List.of());
        String operatorToken=activate("n04-operator",TARGET,UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/platform-users/bootstrap-admin/access/roles",operatorToken,null,"root-protected",
            Map.of("role_ids",List.of(),"expected_revision",rootRevision,"reason","Check protected account"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROTECTED_ACCOUNT"));

        JsonNode customerRole=createRole(root,"n04-customer-role","CUSTOMER",List.of("alarm:review"));
        JsonNode platformTarget=platform(root,"n04-type-target",List.of("summary:read"),List.of());
        call(HttpMethod.PUT,"/api/management/platform-users/"+platformTarget.get("id").asText()+"/access/roles",root,null,"type-mismatch",
            assignRoles(List.of(customerRole.get("id").asText()),0)).andExpect(status().isBadRequest());

        JsonNode leaf=customer(root,"tenant-a","n04-leaf",List.of("summary:read"));
        customer(root,"tenant-a","n04-manager",List.of("summary:read","users:read","users:write","users:assign-roles"));
        String manager=activate("n04-manager",TARGET,UPDATED,"customer");
        call(HttpMethod.PUT,"/api/customer/users/"+leaf.get("id").asText()+"/access/roles",manager,null,"delegation",
            Map.of("role_ids",List.of(customerRole.get("id").asText()),"expected_revision",0,"reason","越权委派"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DELEGATION_FORBIDDEN"));

        JsonNode selfRole=createRole(root,"n04-self-role","PLATFORM",List.of("settings:read"));
        JsonNode selfAdmin=platform(root,"n04-self-admin",List.of("settings:read","roles:read","roles:write"),List.of());
        String selfId=selfAdmin.get("id").asText(); String selfToken=activate("n04-self-admin",TARGET,UPDATED,"management");
        body(call(HttpMethod.PUT,"/api/management/platform-users/"+selfId+"/access/roles",root,null,"self-role-assign",
            assignRoles(List.of(selfRole.get("id").asText()),1)).andExpect(status().isOk()));
        call(HttpMethod.GET,"/api/auth/me",selfToken,null,null,null).andExpect(status().isUnauthorized());
        selfToken=login("n04-self-admin",UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/roles/"+selfRole.get("id").asText(),selfToken,null,"edit-own-role",
            Map.of("name","改名","enabled",true,"permissions",List.of("settings:read"),"expected_revision",0,"reason","Self-assigned template"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_MANAGEMENT_FORBIDDEN"));
    }

    @Test void editingAssignedTemplateMustStayInsideEveryAffectedUserBounds() throws Exception {
        String root=root();
        JsonNode role=createRole(root,"n04-bounded","PLATFORM",List.of("settings:read"));
        String roleId=role.get("id").asText();
        JsonNode high=platform(root,"n04-high",List.of("parameters:read","settings:read"),List.of());
        activate("n04-high",TARGET,UPDATED,"management");
        body(call(HttpMethod.PUT,"/api/management/platform-users/"+high.get("id").asText()+"/access/roles",root,null,"high-role",
            assignRoles(List.of(roleId),1)).andExpect(status().isOk()));
        platform(root,"n04-low",List.of("settings:read","roles:read","roles:write"),List.of());
        String low=activate("n04-low",TARGET,UPDATED,"management");
        call(HttpMethod.PUT,"/api/management/roles/"+roleId,low,null,"bounded-edit",
            Map.of("name","受限改名","enabled",true,"permissions",List.of("settings:read"),"expected_revision",0,"reason","Verify affected-user bounds"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TARGET_MANAGEMENT_FORBIDDEN"));
        JsonNode edited=body(call(HttpMethod.PUT,"/api/management/roles/"+roleId,root,null,"bounded-edit-root",roleUpdate("全局改名",true,List.of("settings:read"),0)).andExpect(status().isOk()));
        assertThat(edited.get("revision").asLong()).isEqualTo(1L);
        assertThat(revision(high.get("id").asText())).isEqualTo(3L);
    }

    @Test void removingPlatformGrantDropsScopeAndReaddingItDoesNotRestoreIt() throws Exception {
        String root=root();
        JsonNode granted=platform(root,"n04-granted",List.of("summary:read"),List.of("tenant-a"));
        String id=granted.get("id").asText();
        JsonNode empty=body(call(HttpMethod.GET,"/api/management/platform-users/"+id+"/scope",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(empty.get("configured").asBoolean()).isFalse();
        body(call(HttpMethod.PUT,"/api/management/platform-users/"+id+"/scope",root,"tenant-a","grant-scope",
            Map.of("mode","ALL_TENANT","device_ids",List.of(),"place_ids",List.of(),"expected_revision",0,"reason","全单位")).andExpect(status().isOk()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id=?",Long.class,id)).isEqualTo(1L);
        body(call(HttpMethod.PUT,"/api/management/platform-users/"+id,root,null,"drop-grant",
            Map.of("name","n04-granted","enabled",true,"permissions",List.of("summary:read"),"tenant_ids",List.of(),"expected_revision",1))
            .andExpect(status().isOk()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id=?",Long.class,id)).isZero();
        body(call(HttpMethod.PUT,"/api/management/platform-users/"+id,root,null,"readd-grant",
            Map.of("name","n04-granted","enabled",true,"permissions",List.of("summary:read"),"tenant_ids",List.of("tenant-a"),"expected_revision",2))
            .andExpect(status().isOk()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id=?",Long.class,id)).isZero();
        JsonNode after=body(call(HttpMethod.GET,"/api/management/platform-users/"+id+"/scope",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(after.get("configured").asBoolean()).isFalse(); assertThat(after.get("device_ids")).isEmpty();
    }

    @Test void roleGrantedWritePermissionIsCheckedLiveAndNotOnlyAtLogin() throws Exception {
        String root=root();
        JsonNode role=createRole(root,"n04-writer","CUSTOMER",List.of("users:read","users:write"));
        JsonNode actor=customer(root,"tenant-a","n04-writer-user",List.of("summary:read"));
        String id=actor.get("id").asText();
        String initial=activate("n04-writer-user",TARGET,UPDATED,"customer");
        body(call(HttpMethod.PUT,"/api/management/users/"+id+"/access/roles",root,"tenant-a","writer-role",assignRoles(List.of(role.get("id").asText()),1)).andExpect(status().isOk()));
        String withRole=login("n04-writer-user",UPDATED,"customer");
        body(call(HttpMethod.POST,"/api/customer/users",withRole,null,"writer-create",Map.of("username","n04-written","name","n04-written",
            "initial_password",TARGET,"permissions",List.of("summary:read"))).andExpect(status().isOk()));
        body(call(HttpMethod.PUT,"/api/management/roles/"+role.get("id").asText(),root,null,"writer-disable",
            roleUpdate("n04-writer",false,List.of("users:read","users:write"),0)).andExpect(status().isOk()));
        call(HttpMethod.GET,"/api/auth/me",withRole,null,null,null).andExpect(status().isUnauthorized());
        String withoutRole=login("n04-writer-user",UPDATED,"customer");
        call(HttpMethod.POST,"/api/customer/users",withoutRole,null,"writer-create-denied",Map.of("username","n04-written-2","name","n04-written-2",
            "initial_password",TARGET,"permissions",List.of("summary:read")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='n04-written-2'",Long.class)).isZero();
        assertThat(initial).isNotBlank();
    }

    @Test void concurrentTemplateEditsHonorRevisionCas() throws Exception {
        String root=root();
        String roleId=createRole(root,"n04-concurrent","CUSTOMER",List.of("users:read")).get("id").asText();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var jobs=executor.invokeAll(List.<Callable<Integer>>of(
                ()->call(HttpMethod.PUT,"/api/management/roles/"+roleId,root,null,"concurrent-a",roleUpdate("甲",true,List.of("users:read"),0)).andReturn().getResponse().getStatus(),
                ()->call(HttpMethod.PUT,"/api/management/roles/"+roleId,root,null,"concurrent-b",roleUpdate("乙",true,List.of("users:read"),0)).andReturn().getResponse().getStatus()),20,TimeUnit.SECONDS);
            var statuses=new ArrayList<Integer>();
            for(var job:jobs) { assertThat(job.isCancelled()).isFalse(); statuses.add(job.get()); }
            assertThat(statuses).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM access_roles WHERE id=?",Long.class,roleId)).isEqualTo(1L);
    }

    @Test void scopeRevocationCommittedAfterARealDeviceQueryPreventsItsOldResultFromBeingSerialized() throws Exception {
        String root=root(), name="n04-output-race";
        String id=platform(root,name,List.of("device:read"),List.of("tenant-a")).get("id").asText();
        activate(name,TARGET,UPDATED,"management");
        String scopePath="/api/management/platform-users/"+id+"/scope";
        call(HttpMethod.PUT,scopePath,root,"tenant-a","output-initial-scope",assignScope("DEVICE_SET",List.of("device-a"),0)).andExpect(status().isOk());
        String token=login(name,UPDATED,"management");
        var queried=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            queried.countDown();
            if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("Read response was not released");
            return result;
        }).when(deviceReads).devices(org.mockito.ArgumentMatchers.eq(id),org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.nullable(Integer.class),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.any(com.fireplatform.shared.ScopeFilter.Selection.class),org.mockito.ArgumentMatchers.any(com.fireplatform.device.DeviceListFilter.class));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var read=executor.submit(()->call(HttpMethod.GET,"/api/management/devices",token,"tenant-a",null,null).andReturn().getResponse());
            try {
                assertThat(queried.await(15,TimeUnit.SECONDS)).isTrue();
                call(HttpMethod.PUT,scopePath,root,"tenant-a","output-revoked-scope",assignScope("DEVICE_SET",List.of(),1)).andExpect(status().isOk());
            } finally {
                release.countDown();
            }
            var response=read.get(15,TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(401);
            var error=json.readTree(response.getContentAsString());
            assertThat(error.get("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(error.has("items")).isFalse();
            assertThat(response.getContentAsString()).doesNotContain("device-a");
        } finally {
            release.countDown();
        }
    }

    @Test void accessBodiesRejectScalarCoercionMalformedUnicodeAndNoncanonicalRevisions() throws Exception {
        String root=root();
        String id=createRole(root,"n04-strict","CUSTOMER",List.of()).get("id").asText();
        for(String token:List.of("-0","-1","1.0","1e0","\"0\"","true","null","9007199254740992","9223372036854775808")) {
            String raw="{\"name\":\"原样\",\"enabled\":true,\"permissions\":[],\"expected_revision\":"+token
                +",\"reason\":\"依据\"}";
            mvc.perform(put("/api/management/roles/"+id).header("Authorization","Bearer "+root)
                .header("Idempotency-Key","strict-"+UUID.randomUUID()).contentType("application/json").content(raw))
                .andExpect(status().isBadRequest());
        }
        for(var replacement:List.of(Map.entry("name",(Object)7),Map.entry("enabled",(Object)"true"),Map.entry("permissions",(Object)List.of(7)))) {
            Map<String,Object> value=new HashMap<>(roleUpdate("合法",true,List.of(),0));
            value.put(replacement.getKey(),replacement.getValue());
            call(HttpMethod.PUT,"/api/management/roles/"+id,root,null,UUID.randomUUID().toString(),value).andExpect(status().isBadRequest());
        }
        String malformed=json.writeValueAsString(roleUpdate("合法",true,List.of(),0)).replace("\"合法\"","\"\\uD800\"");
        mvc.perform(put("/api/management/roles/"+id).header("Authorization","Bearer "+root)
            .header("Idempotency-Key","unicode-"+UUID.randomUUID()).contentType("application/json").content(malformed))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT revision FROM access_roles WHERE id=?",Long.class,id)).isZero();
    }

    @Test void authorizationChangeReasonsArePreservedPrivatelyAndReplaysDoNotAppendAudit() throws Exception {
        String root=root(), reason=" \r\n依据 <literal> \t ";
        Map<String,Object> value=Map.of("code","n04-basis","name","依据","user_type","CUSTOMER","enabled",true,
            "permissions",List.of("device:read"),"reason",reason);
        JsonNode role=body(call(HttpMethod.POST,"/api/management/roles",root,null,"reason-create",value).andExpect(status().isOk()));
        String roleId=role.get("id").asText();
        assertThat(body(call(HttpMethod.POST,"/api/management/roles",root,null,"reason-create",value).andExpect(status().isOk()))).isEqualTo(role);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ROLE-CREATE' AND target_id=?",Long.class,roleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action='ROLE-CREATE' AND target_id=?",String.class,roleId)).isEqualTo(reason);
        String userId=customer(root,"tenant-a","n04-basis-user",List.of()).get("id").asText();
        Map<String,Object> scope=new HashMap<>(assignScope("ALL_TENANT",List.of(),0));scope.put("reason",reason);
        call(HttpMethod.PUT,"/api/management/users/"+userId+"/scope",root,"tenant-a","basis-scope",scope).andExpect(status().isOk());
        Map<String,Object> roles=new HashMap<>(assignRoles(List.of(roleId),1));roles.put("reason",reason);
        call(HttpMethod.PUT,"/api/management/users/"+userId+"/access/roles",root,"tenant-a","basis-roles",roles).andExpect(status().isOk());
        for(String action:List.of("USER-SCOPE-ASSIGN","USER-ROLES-ASSIGN"))
            assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action=? AND target_id=?",String.class,action,userId)).isEqualTo(reason);
        assertThat(role.has("reason")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_command_records WHERE response_json LIKE ?",Long.class,"%"+ROOT_PASSWORD+"%")).isZero();
    }

    @Test void safeRevisionExhaustionRejectsWithoutPartiallyChangingAssignmentsOrAudit() throws Exception {
        String root=root();
        String role=createRole(root,"n04-exhaust","CUSTOMER",List.of()).get("id").asText();
        long max=com.fireplatform.identity.AccessInputs.MAX_REVISION;
        jdbc.update("UPDATE access_roles SET revision=? WHERE id=?",max,role);
        long audits=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE outcome='SUCCESS'",Long.class);
        call(HttpMethod.PUT,"/api/management/roles/"+role,root,null,"max-role",roleUpdate("不应写入",false,List.of(),max))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCESS_REVISION_EXHAUSTED"));
        assertThat(jdbc.queryForObject("SELECT revision FROM access_roles WHERE id=?",Long.class,role)).isEqualTo(max);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE outcome='SUCCESS'",Long.class)).isEqualTo(audits);
    }

    @Test void scopeAndRoleChangesPreserveAccountValidityAndDutyPersonnelIsGoneFromTheWire() throws Exception {
        String root=root();
        JsonNode user=customer(root,"tenant-a","n04-validity",List.of("device:read"));
        String id=user.get("id").asText();
        String until=java.time.LocalDate.now().plusDays(45)+"T23:59:59Z";
        // The client contract clears a department with JSON null; "" is an unresolvable department id and is rejected.
        Map<String,Object> metadata=new HashMap<>();
        metadata.put("department_id",null);metadata.put("phone","13800009999");metadata.put("contact_email","validity@example.test");
        metadata.put("long_term",false);metadata.put("valid_until",until);metadata.put("expected_revision",0);
        JsonNode saved=body(call(HttpMethod.PUT,"/api/management/identity/users/"+id,root,"tenant-a","validity-metadata",metadata)
            .andExpect(status().isOk()));
        assertThat(saved.has("duty_personnel")).isFalse();
        assertThat(saved.get("valid_until").asText()).isNotBlank();
        var stored=jdbc.queryForObject("SELECT valid_until FROM identity_user_metadata WHERE user_id=?",java.time.OffsetDateTime.class,id);
        JsonNode role=createRole(root,"n04-validity-role","CUSTOMER",List.of("device:read"));
        call(HttpMethod.PUT,"/api/management/users/"+id+"/access/roles",root,"tenant-a","validity-roles",
            assignRoles(List.of(role.get("id").asText()),0)).andExpect(status().isOk());
        call(HttpMethod.PUT,"/api/management/users/"+id+"/scope",root,"tenant-a","validity-scope",
            assignScope("DEVICE_SET",List.of("device-a"),0)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT valid_until FROM identity_user_metadata WHERE user_id=?",java.time.OffsetDateTime.class,id)).isEqualTo(stored);
        JsonNode listed=body(call(HttpMethod.GET,"/api/management/identity/users?page=1&keyword=n04-validity",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(listed.at("/items/0/valid_until").asText()).isNotBlank();
        assertThat(listed.at("/items/0").has("duty_personnel")).isFalse();
    }

    @Test void customerLocalFaultDispositionRequiresItsOwnPermissionAndCurrentDeviceScope() throws Exception {
        String root=root();
        String eventId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,'tenant-a','device-a','FAULT','thing.event.fault','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",eventId);

        JsonNode handler=customer(root,"tenant-a","n04-fault-handler",List.of("device:read","faults:handle"));
        String handlerId=handler.get("id").asText();
        call(HttpMethod.PUT,"/api/management/users/"+handlerId+"/scope",root,"tenant-a","fault-handler-scope",
            assignScope("ALL_TENANT",List.of(),0)).andExpect(status().isOk());
        String handlerSession=activate("n04-fault-handler",TARGET,UPDATED,"customer");
        JsonNode handled=body(call(HttpMethod.POST,"/api/customer/duty/faults/"+eventId+"/handling",handlerSession,null,"fault-handle-once",
            Map.of("expected_revision",0,"handled",true,"note","客户现场确认")).andExpect(status().isOk()));
        assertThat(handled.get("handled").asBoolean()).isTrue();
        assertThat(handled.get("revision").asLong()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='device-a'",String.class)).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM duty_fault_handling_history WHERE event_id=?",Long.class,eventId)).isEqualTo(1L);
        JsonNode replay=body(call(HttpMethod.POST,"/api/customer/duty/faults/"+eventId+"/handling",handlerSession,null,"fault-handle-once",
            Map.of("expected_revision",0,"handled",true,"note","客户现场确认")).andExpect(status().isOk()));
        assertThat(replay.get("revision").asLong()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM duty_fault_handling_history WHERE event_id=?",Long.class,eventId)).isEqualTo(1L);

        JsonNode reader=customer(root,"tenant-a","n04-fault-reader",List.of("device:read"));
        String readerId=reader.get("id").asText();
        call(HttpMethod.PUT,"/api/management/users/"+readerId+"/scope",root,"tenant-a","fault-reader-scope",
            assignScope("ALL_TENANT",List.of(),0)).andExpect(status().isOk());
        String readerSession=activate("n04-fault-reader",TARGET,UPDATED,"customer");
        call(HttpMethod.POST,"/api/customer/duty/faults/"+eventId+"/handling",readerSession,null,"fault-reader-denied",
            Map.of("expected_revision",1,"handled",false,"note",""))
            .andExpect(status().isForbidden());

        JsonNode outside=customer(root,"tenant-a","n04-fault-outside",List.of("device:read","faults:handle"));
        String outsideId=outside.get("id").asText();
        call(HttpMethod.PUT,"/api/management/users/"+outsideId+"/scope",root,"tenant-a","fault-outside-scope",
            assignPlaceScope(List.of("n04-other"),0)).andExpect(status().isOk());
        String outsideSession=activate("n04-fault-outside",TARGET,UPDATED,"customer");
        call(HttpMethod.POST,"/api/customer/duty/faults/"+eventId+"/handling",outsideSession,null,"fault-outside-denied",
            Map.of("expected_revision",1,"handled",false,"note",""))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM duty_fault_handling_history WHERE event_id=?",Long.class,eventId)).isEqualTo(1L);
    }
}
