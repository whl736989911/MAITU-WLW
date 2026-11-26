package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** N18：人工经纬度与无底图分布。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:loctests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceLocationTest {
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
        for(String table:List.of("user_messages","device_import_rows","device_import_jobs","device_export_snapshots","device_profile_snapshots","profile_rules","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_change_outbox","alarm_change_heads","alarm_change_publisher_coord","alarm_trace_entries","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments()); jdbc.update("INSERT INTO tenants(id,name) VALUES ('tenant-a','Fixture A'),('tenant-b','Fixture B')"); jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')"); jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,integration_state) VALUES ('device-a','tenant-a','Fixture A device','fixture-a','fixture','A','UNKNOWN','LEGACY_UNVERIFIED'),('device-b','tenant-b','Fixture B device','fixture-b','fixture','B','UNKNOWN','LEGACY_UNVERIFIED')"); N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        call(HttpMethod.POST,"/api/management/users",root,"tenant-a",UUID.randomUUID().toString(),Map.of("username","customer-a","name","Location fixture customer","initial_password",INITIAL,"permissions",new java.util.ArrayList<>(com.fireplatform.shared.Limits.CUSTOMER_PERMISSIONS))).andExpect(status().isOk());
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    long revision(String device) { return jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,device); }

    @Test void setDistributionAndClearAreTenantScoped() throws Exception {
        call(HttpMethod.GET,"/api/management/devices/device-a",root,"tenant-a",null,null).andExpect(status().isOk());
        long rev=revision("device-a");
        var updated=body(call(HttpMethod.PUT,"/api/management/devices/device-a/location",root,"tenant-a",null,Map.of("latitude",31.234567,"longitude",121.456789,"expected_revision",rev)).andExpect(status().isOk()));
        assertThat(updated.get("revision").asLong()).isEqualTo(rev+1);
        var distribution=body(call(HttpMethod.GET,"/api/management/devices/distribution",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(distribution.get("with_coordinates").asLong()).isEqualTo(1);
        assertThat(distribution.at("/points/0/latitude").asText()).isEqualTo("31.234567");
        assertThat(distribution.get("semantics").asText()).contains("不推断");
        // 他租户看不到该点。
        var other=body(call(HttpMethod.GET,"/api/management/devices/distribution",root,"tenant-b",null,null).andExpect(status().isOk()));
        assertThat(other.get("with_coordinates").asLong()).isZero();
        call(HttpMethod.POST,"/api/management/devices/device-a/location/clear",root,"tenant-a",null,Map.of("expected_revision",updated.get("revision").asLong())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE latitude IS NOT NULL",Long.class)).isZero();
    }

    @Test void outOfRangeAndPartialCoordinatesAreRejected() throws Exception {
        long rev=revision("device-a");
        call(HttpMethod.PUT,"/api/management/devices/device-a/location",root,"tenant-a",null,Map.of("latitude",91.0,"longitude",10.0,"expected_revision",rev)).andExpect(status().isBadRequest());
        call(HttpMethod.PUT,"/api/management/devices/device-a/location",root,"tenant-a",null,Map.of("latitude",10.0,"longitude",181.0,"expected_revision",rev)).andExpect(status().isBadRequest());
        call(HttpMethod.PUT,"/api/management/devices/device-a/location",root,"tenant-a",null,Map.of("latitude",10.0,"expected_revision",rev)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE latitude IS NOT NULL",Long.class)).isZero();
    }

    static java.util.Map<String,Object> placeBody(String name,String parent) { var map=new java.util.HashMap<String,Object>(); map.put("name",name); map.put("parent_place_id",parent); return map; }
    static java.util.Map<String,Object> placeUpdateBody(String name,String parent,long revision) { var map=placeBody(name,parent); map.put("expected_revision",revision); return map; }
    @Test void deviceEditCanMoveWithinCurrentTenantPlace() throws Exception {
        var place=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",null,placeBody("设备移动位置",null)).andExpect(status().isOk()));
        String placeId=place.get("id").asText(); long rev=revision("device-a");
        var updated=body(call(HttpMethod.PUT,"/api/management/devices/device-a",root,"tenant-a",java.util.UUID.randomUUID().toString(),Map.of("name","移动后设备","address","新地址","place_id",placeId,"expected_revision",rev)).andExpect(status().isOk()));
        assertThat(updated.get("place_id").asText()).isEqualTo(placeId);
        assertThat(updated.get("revision").asLong()).isEqualTo(rev+1);
    }

    @Test void placeCreateRenameAndDeleteAreTenantScoped() throws Exception {
        var created=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",null,placeBody("测试新位置",null)).andExpect(status().isOk()));
        String id=created.get("id").asText(); long revision=created.get("revision").asLong();
        var renamed=body(call(HttpMethod.PUT,"/api/management/places/"+id,root,"tenant-a",null,placeUpdateBody("测试改名位置",null,revision)).andExpect(status().isOk()));
        assertThat(renamed.get("name").asText()).isEqualTo("测试改名位置");
        call(HttpMethod.DELETE,"/api/management/places/"+id,root,"tenant-a",null,Map.of("expected_revision",renamed.get("revision").asLong())).andExpect(status().isOk());
        call(HttpMethod.PUT,"/api/management/places/"+id,root,"tenant-a",null,placeUpdateBody("不存在",null,0)).andExpect(status().isNotFound());
    }

    @Test void scopeTreeAndMultiUnitDeviceFilterAreAuthorized() throws Exception {
        var tree=body(call(HttpMethod.GET,"/api/management/scope-tree",root,null,null,null).andExpect(status().isOk()));
        assertThat(tree).isNotEmpty();
        var scoped=body(call(HttpMethod.GET,"/api/management/devices?page=1&size=100&scope_tenant_ids=tenant-a",root,null,null,null).andExpect(status().isOk()));
        assertThat(scoped.at("/items")).anySatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-a"));
        assertThat(scoped.at("/items")).noneSatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-b"));
        jdbc.update("DELETE FROM places WHERE id IN ('scope-place-root','scope-place-child','scope-place-grandchild','scope-place-other')");
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES('scope-place-root','tenant-a','范围根位置',NULL)");
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES('scope-place-child','tenant-a','范围子位置','scope-place-root')");
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES('scope-place-grandchild','tenant-a','范围孙位置','scope-place-child')");
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES('scope-place-other','tenant-a','范围其他分支',NULL)");
        jdbc.update("UPDATE devices SET place_id='scope-place-grandchild' WHERE id='device-a'");
        jdbc.update("UPDATE places SET legacy_parent_place_id='private-parent-538',legacy_parent_company_id='327' WHERE id='scope-place-other'");
        var hierarchy=body(call(HttpMethod.GET,"/api/management/scope-tree",root,null,null,null).andExpect(status().isOk()));
        var places=hierarchy.findValues("places").stream().flatMap(node -> java.util.stream.StreamSupport.stream(node.spliterator(),false)).toList();
        assertThat(places).anySatisfy(place -> { assertThat(place.get("id").asText()).isEqualTo("scope-place-child"); assertThat(place.get("parent_id").asText()).isEqualTo("scope-place-root"); assertThat(place.get("level").asInt()).isEqualTo(2); });
        assertThat(places).anySatisfy(place -> { assertThat(place.get("id").asText()).isEqualTo("scope-place-other"); assertThat(place.at("/parent_context/id").isNull()).isTrue(); assertThat(place.at("/parent_context/name").asText()).isEqualTo("上级位置（暂不可见）"); });
        assertThat(places).anySatisfy(place -> { assertThat(place.get("id").asText()).isEqualTo("scope-place-grandchild"); assertThat(place.get("parent_id").asText()).isEqualTo("scope-place-child"); assertThat(place.get("level").asInt()).isEqualTo(3); });
        var placeScoped=body(call(HttpMethod.GET,"/api/management/devices?page=1&size=100&scope_place_ids=scope-place-root",root,null,null,null).andExpect(status().isOk()));
        assertThat(placeScoped.at("/items")).anySatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-a"));
        assertThat(placeScoped.at("/items")).noneSatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-b"));
        var childScoped=body(call(HttpMethod.GET,"/api/management/devices?page=1&size=100&scope_place_ids=scope-place-child",root,null,null,null).andExpect(status().isOk()));
        assertThat(childScoped.at("/items")).anySatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-a"));
        assertThat(childScoped.at("/items")).noneSatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-b"));
        var isolated=body(call(HttpMethod.GET,"/api/management/devices?page=1&size=100&scope_place_ids=scope-place-other",root,null,null,null).andExpect(status().isOk()));
        assertThat(isolated.at("/items")).noneSatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-a"));
        assertThat(isolated.at("/items")).noneSatisfy(item -> assertThat(item.get("id").asText()).isEqualTo("device-b"));
        call(HttpMethod.GET,"/api/management/devices?page=1&size=100&scope_tenant_ids=tenant-b",root,"tenant-a",null,null).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/management/devices?page=1&size=100&scope_tenant_ids=UNKNOWN",root,null,null,null).andExpect(status().isForbidden());
    }

    @Test void deviceLedgerSortsTheAuthorizedResultsBeforePaging() throws Exception {
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES ('sort-a','tenant-a','Zone Z'),('sort-z','tenant-a','Zone A')");
        jdbc.update("UPDATE devices SET place_id='sort-a',last_message_at=TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00' WHERE id='device-a'");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,integration_state,place_id,last_message_at) VALUES "
            +"('device-latest','tenant-a','Sort latest','sort-latest','fixture','A','UNKNOWN','LEGACY_UNVERIFIED','sort-z',TIMESTAMP WITH TIME ZONE '2026-01-02 02:00:00+02'),"
            +"('device-tie','tenant-a','Sort tie','sort-tie','fixture','A','UNKNOWN','LEGACY_UNVERIFIED','sort-z',TIMESTAMP WITH TIME ZONE '2026-01-02 00:00:00+00'),"
            +"('device-unknown','tenant-a','Sort unknown','sort-unknown','fixture','A','UNKNOWN','LEGACY_UNVERIFIED',NULL,NULL)");
        jdbc.update("UPDATE devices SET last_message_at=TIMESTAMP WITH TIME ZONE '2026-01-03 00:00:00+00' WHERE id='device-b'");

        String ledger="/api/management/devices?scope_tenant_ids=tenant-a";
        assertThat(sortedDeviceIds(ledger,"last","desc",1)).containsExactly("device-latest","device-tie");
        assertThat(sortedDeviceIds(ledger,"last","desc",2)).containsExactly("device-a","device-unknown");
        assertThat(sortedDeviceIds(ledger,"last","asc",1)).containsExactly("device-a","device-latest");
        assertThat(sortedDeviceIds(ledger,"placement","asc",1)).containsExactly("device-latest","device-tie");
        assertThat(sortedDeviceIds(ledger,"placement","desc",1)).containsExactly("device-a","device-latest");
        assertThat(sortedDeviceIds("/api/management/device-workbench?scope_tenant_ids=tenant-a","placement","desc",2))
            .containsExactly("device-tie","device-unknown");
        call(HttpMethod.GET,ledger+"&sort=nonexistent&direction=asc",root,null,null,null).andExpect(status().isBadRequest());
        call(HttpMethod.GET,ledger+"&sort=last&direction=sideways",root,null,null,null).andExpect(status().isBadRequest());
    }

    private List<String> sortedDeviceIds(String path,String sort,String direction,int page) throws Exception {
        return body(call(HttpMethod.GET,path+"&sort="+sort+"&direction="+direction+"&size=2&page="+page,root,null,null,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(4))).path("items").findValuesAsText("id");
    }

    @Test void distributionCountsOnlyDevicesVisibleToActorScope() throws Exception {
        long a=revision("device-a"), b=revision("device-b");
        var updatedA=body(call(HttpMethod.PUT,"/api/management/devices/device-a/location",root,"tenant-a",null,Map.of("latitude",31.0,"longitude",121.0,"expected_revision",a)).andExpect(status().isOk()));
        call(HttpMethod.PUT,"/api/management/devices/device-b/location",root,"tenant-b",null,Map.of("latitude",32.0,"longitude",122.0,"expected_revision",b)).andExpect(status().isOk());
        String admin=jdbc.queryForObject("SELECT id FROM app_users WHERE username='admin'",String.class);
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=?",admin);
        jdbc.update("DELETE FROM user_device_scopes WHERE user_id=?",admin);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES(?,?,'DEVICE_SET',1)",admin,"tenant-a");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES(?,?,?)",admin,"tenant-a","device-a");
        var distribution=body(call(HttpMethod.GET,"/api/management/devices/distribution",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(distribution.get("with_coordinates").asLong()).isEqualTo(1);
        assertThat(distribution.get("without_coordinates").asLong()).isZero();
        assertThat(distribution.at("/points/0/id").asText()).isEqualTo("device-a");
        // restore broad scope for subsequent tests in this class
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=?",admin);
        jdbc.update("UPDATE user_device_scopes SET mode='ALL_TENANT',revision=revision+1 WHERE user_id=?",admin);
    }

    @Test void staleRevisionAndCustomerAudienceAreRejected() throws Exception {
        call(HttpMethod.PUT,"/api/management/devices/device-a/location",root,"tenant-a",null,Map.of("latitude",1.0,"longitude",1.0,"expected_revision",999)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        String customer=login("customer-a",INITIAL,"customer");
        call(HttpMethod.GET,"/api/management/devices/distribution",customer,"tenant-a",null,null).andExpect(status().isForbidden());
    }
}
