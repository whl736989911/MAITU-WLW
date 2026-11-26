package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.*;
import com.fireplatform.device.DeviceQueryService;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.shared.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.fireplatform.IngestionIntegrationTest.PASSWORD;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.simulation-enabled=true","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:parameters;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(ParameterIntegrationTest.Observations.class)
class ParameterIntegrationTest {
    static final OffsetDateTime NOW=OffsetDateTime.parse("2026-10-20T05:06:07.123456Z");
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { IngestionIntegrationTest.environment(r); }
    static class CountingDataSource extends AbstractDataSource {
        final DataSource actual; final List<String> statements=new CopyOnWriteArrayList<>();
        CountingDataSource(DataSource actual) { this.actual=actual; }
        Connection wrap(Connection connection) { return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{ if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql) statements.add(sql); try { return method.invoke(connection,args); } catch(InvocationTargetException e) { throw e.getCause(); } }); }
        @Override public Connection getConnection() throws SQLException { return wrap(actual.getConnection()); }
        @Override public Connection getConnection(String user,String password) throws SQLException { return wrap(actual.getConnection(user,password)); }
    }
    @TestConfiguration static class Observations {
        @Bean @Primary Clock policyTestClock() { return Clock.fixed(NOW.toInstant(),ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser; @Autowired DeviceQueryService devices; @Autowired @Qualifier("dataSource") DataSource dataSource; @Autowired com.fireplatform.configuration.DatabaseTime databaseTime;
    IngestionIntegrationTest f;
    @org.junit.jupiter.api.AfterEach void clearGroupPolicyFixtures() {
        jdbc.update("DELETE FROM parameter_changes WHERE scope='GROUP'");
        jdbc.update("DELETE FROM parameter_overrides WHERE scope='GROUP'");
        jdbc.update("DELETE FROM device_group_members WHERE group_id IN ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222')");
        jdbc.update("DELETE FROM device_groups WHERE id IN ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222')");
    }
    @BeforeEach void reset() throws Exception { jdbc.update("DELETE FROM parameter_changes"); jdbc.update("DELETE FROM parameter_overrides"); f=new IngestionIntegrationTest(); f.mvc=mvc; f.json=json; f.jdbc=jdbc; f.bootstrap=bootstrap; f.parser=parser; f.reset(); jdbc.update("UPDATE numeric_settings SET setting_value=2880,revision=0 WHERE setting_key='offline_threshold_minutes'"); jdbc.update("UPDATE numeric_settings SET setting_value=20,revision=0 WHERE setting_key='default_page_size'"); jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES ('device-a-unactivated','tenant-a','Explicit unactivated fixture','fixture-a-unactivated','fixture','A2','UNKNOWN',FALSE,'LEGACY_UNVERIFIED')"); }
    String path(String scope,String id) { return "/api/management/parameter-values/"+scope+"/"+id+"/offline_threshold_minutes"; }
    JsonNode view(String scope,String id) throws Exception { var result=f.body(f.call(HttpMethod.GET,path(scope,id),f.root,"tenant-a",null,null).andExpect(status().isOk())); assertThat(result.get("consumer").asText()).isEqualTo("CONNECTIVITY_AND_OFFLINE_EVENTS"); return result; }
    ResultActions set(String scope,String id,long value,long revision,String key) throws Exception { return f.call(HttpMethod.PUT,path(scope,id),f.root,"tenant-a",key,Map.of("value",value,"expected_revision",revision,"reason","Explicit offline threshold policy")); }
    ResultActions clear(String scope,String id,long revision,String key) throws Exception { return f.call(HttpMethod.POST,path(scope,id)+"/clear",f.root,"tenant-a",key,Map.of("expected_revision",revision,"reason","Return to current inherited value")); }
    JsonNode item(String id) throws Exception { var items=f.body(f.call(HttpMethod.GET,"/api/management/devices",f.root,"tenant-a",null,null).andExpect(status().isOk())).get("items"); for(var row:items) if(row.get("id").asText().equals(id)) return row; throw new AssertionError("Missing synthetic device"); }
    @Test void definitionsMinimalTargetsAndInitialInheritanceUseOnlyCurrentGlobalTruth() throws Exception {
        var definitions=f.body(f.call(HttpMethod.GET,"/api/management/parameter-definitions",f.root,null,null,null).andExpect(status().isOk())).get("items"); assertThat(definitions).isNotEmpty(); for(var row:definitions) { assertThat(row.get("value_type").asText()).isEqualTo("INTEGER"); if(row.get("key").asText().equals("offline_threshold_minutes")) { assertThat(row.get("consumer").asText()).isEqualTo("CONNECTIVITY_AND_OFFLINE_EVENTS"); assertThat(row.get("effect").asText()).isEqualTo("LOCAL_DERIVATION"); } else { assertThat(row.get("effect").asText()).isEqualTo("QUERY_ONLY"); if(row.get("key").asText().equals("default_page_size")) assertThat(row.get("allowed_scopes").size()).isEqualTo(1); } }
        var targets=f.body(f.call(HttpMethod.GET,"/api/management/parameter-targets",f.root,"tenant-a",null,null).andExpect(status().isOk())); assertThat(targets.get("total").asLong()).isPositive(); for(var row:targets.get("items")) { assertThat(row.size()).isEqualTo(3); assertThat(row.get("tenant_id").asText()).isEqualTo("tenant-a"); }
        var allTargets=f.body(mvc.perform(get("/api/management/parameter-targets").header("Authorization","Bearer "+f.root)).andExpect(status().isOk())); assertThat(allTargets.get("total").asInt()).isGreaterThanOrEqualTo(targets.get("total").asInt()); assertThat(allTargets.at("/items/0/tenant_id").asText()).isNotBlank();
        mvc.perform(get("/api/management/parameter-targets").header("Authorization","Bearer "+f.root).param("scope_selected","true").param("scope_tenant_ids","").param("scope_place_ids","")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        var own=view("DEVICE","device-a"); assertThat(own.get("has_override").asBoolean()).isFalse(); assertThat(own.get("value").isNull()).isTrue(); assertThat(own.get("revision").asLong()).isZero(); assertThat(own.get("chain").size()).isEqualTo(3); assertThat(own.at("/effective/value").asLong()).isEqualTo(2880); assertThat(own.at("/effective/source_scope").asText()).isEqualTo("GLOBAL"); assertThat(own.at("/effective/source_id").isNull()).isTrue();
        var history=f.body(f.call(HttpMethod.GET,path("DEVICE","device-a")+"/history",f.root,"tenant-a",null,null).andExpect(status().isOk())); assertThat(history.get("total").asLong()).isZero();
    }
    @Test void precedenceTombstonesAndReplayReturnImmutableChangeButCurrentPolicy() throws Exception {
        var first=f.body(set("TENANT","tenant-a",60,0,"tenant-first").andExpect(status().isOk())); String firstChange=first.at("/change/id").asText();
        assertThat(view("DEVICE","device-a").at("/effective/source_scope").asText()).isEqualTo("TENANT"); var child=f.body(set("DEVICE","device-a",10,0,"device-first").andExpect(status().isOk())); assertThat(child.at("/policy/effective/source_scope").asText()).isEqualTo("DEVICE");
        set("TENANT","tenant-a",120,1,"tenant-second").andExpect(status().isOk()); assertThat(view("DEVICE","device-a").get("revision").asLong()).isEqualTo(1); assertThat(view("DEVICE","device-a").at("/effective/value").asLong()).isEqualTo(10);
        var cleared=f.body(clear("DEVICE","device-a",1,"clear-device").andExpect(status().isOk())); assertThat(cleared.at("/policy/revision").asLong()).isEqualTo(2); assertThat(cleared.at("/policy/has_override").asBoolean()).isFalse(); assertThat(cleared.at("/policy/effective/value").asLong()).isEqualTo(120); assertThat(cleared.at("/policy/effective/source_revision").asLong()).isEqualTo(2);
        set("DEVICE","device-a",20,0,"aba").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT")); clear("DEVICE","device-a",2,"clear-again").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_OVERRIDE"));
        set("DEVICE","device-a",20,2,"device-second").andExpect(status().isOk()); var replay=f.body(set("DEVICE","device-a",10,0,"device-first").andExpect(status().isOk())); assertThat(replay.get("change")).isEqualTo(child.get("change")); assertThat(replay.at("/policy/value").asLong()).isEqualTo(20); assertThat(replay.at("/policy/revision").asLong()).isEqualTo(3);
        var oldTenant=f.body(set("TENANT","tenant-a",60,0,"tenant-first").andExpect(status().isOk())); assertThat(oldTenant.at("/change/id").asText()).isEqualTo(firstChange); assertThat(oldTenant.at("/policy/value").asLong()).isEqualTo(120);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_changes",Long.class)).isEqualTo(5); set("DEVICE","device-a",11,0,"device-first").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
    @Test void oldGlobalSettingsRemainCompatibleAndClearUsesLatestParentWithoutTouchingFacts() throws Exception {
        var before=jdbc.queryForList("SELECT * FROM devices ORDER BY id"); var tenants=jdbc.queryForList("SELECT * FROM tenants ORDER BY id"); var actors=jdbc.queryForList("SELECT id,revision FROM app_users ORDER BY id");
        set("TENANT","tenant-a",5,0,"parent").andExpect(status().isOk()); set("DEVICE","device-a",2,0,"child").andExpect(status().isOk());
        f.call(HttpMethod.PUT,"/api/management/settings/offline_threshold_minutes",f.root,null,null,Map.of("value",300,"expected_revision",0)).andExpect(status().isOk()); clear("DEVICE","device-a",1,"clear-child").andExpect(status().isOk()); assertThat(view("DEVICE","device-a").at("/effective/value").asLong()).isEqualTo(5); clear("TENANT","tenant-a",1,"clear-parent").andExpect(status().isOk()); assertThat(view("DEVICE","device-a").at("/effective/value").asLong()).isEqualTo(300);
        assertThat(jdbc.queryForList("SELECT * FROM devices ORDER BY id")).isEqualTo(before); assertThat(jdbc.queryForList("SELECT * FROM tenants ORDER BY id")).isEqualTo(tenants); assertThat(jdbc.queryForList("SELECT id,revision FROM app_users ORDER BY id")).isEqualTo(actors);
        for(String table:List.of("alarms","device_profile_snapshots","profile_rules","device_battery_observations","device_events","processing_tasks","processing_attempts","message_previews","raw_messages")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
    }
    @Test void heartbeatBackedConnectivityUsesDeviceTenantGlobalOverridesAndKeepsFireIndependent() throws Exception {
        var reportAt=databaseTime.now().minusMinutes(9);
        jdbc.update("UPDATE devices SET last_message_at=?,battery_percent=0,physical_state='FIRE' WHERE id='device-a'",reportAt); jdbc.update("UPDATE devices SET last_message_at=NULL WHERE id='device-a-unactivated'");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,last_message_at) VALUES ('reported-unknown','tenant-a','Reported without heartbeat','fixture-reported-unknown','fixture','A3','UNKNOWN',FALSE,?)",reportAt);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('offline-policy-heartbeat','tenant-a','device-a','HEARTBEAT','source.heartbeat','{}',?,?)",reportAt,reportAt);
        set("TENANT","tenant-a",20,0,"parent").andExpect(status().isOk()); set("DEVICE","device-a",10,0,"child").andExpect(status().isOk());
        var beforeQuery=databaseTime.now(); var row=item("device-a"); var afterQuery=databaseTime.now();
        assertThat(row.get("connectivity").asText()).isEqualTo("ONLINE"); assertThat(row.at("/offline_policy/value").asLong()).isEqualTo(10); var evaluated=OffsetDateTime.parse(row.at("/offline_policy/evaluated_at").asText()); assertThat(evaluated.getOffset()).isEqualTo(ZoneOffset.UTC); assertThat(evaluated.getNano()%1000).isZero(); assertThat(evaluated.toInstant()).isBetween(beforeQuery.toInstant(),afterQuery.toInstant()); assertThat(row.get("physical_state").asText()).isEqualTo("FIRE");
        assertThat(item("device-a-unactivated").get("connectivity").asText()).isEqualTo("UNACTIVATED");
        assertThat(item("reported-unknown").get("connectivity").asText()).isEqualTo("ONLINE");
        var onlineSummary=devices.summary("bootstrap-admin", "tenant-a");
        assertThat(onlineSummary.online()).isEqualTo(2); assertThat(onlineSummary.offline()).isZero(); assertThat(onlineSummary.unactivated()).isEqualTo(1); assertThat(onlineSummary.unknownConnectivity()).isZero();
        assertThat(onlineSummary.deviceTotal()).isEqualTo(onlineSummary.online()+onlineSummary.offline()+onlineSummary.unactivated()+onlineSummary.unknownConnectivity());
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='device-a'",reportAt.minusMinutes(2));
        assertThat(item("device-a").get("connectivity").asText()).isEqualTo("OFFLINE");
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='reported-unknown'",reportAt.minusMinutes(12));
        assertThat(item("reported-unknown").get("connectivity").asText()).isEqualTo("UNKNOWN");
        var offlineSummary=devices.summary("bootstrap-admin", "tenant-a");
        assertThat(offlineSummary.offline()).isEqualTo(1);
        assertThat(offlineSummary.deviceTotal()).isEqualTo(offlineSummary.online()+offlineSummary.offline()+offlineSummary.unactivated()+offlineSummary.unknownConnectivity());
        assertThat(devices.device("bootstrap-admin", "tenant-a", "device-a",false).connectivity()).isEqualTo("OFFLINE"); assertThat(devices.device("bootstrap-admin", "tenant-a", "device-a",false).physicalState()).isEqualTo("FIRE");
        String customer=f.login("customer-a",IngestionIntegrationTest.INITIAL,"customer"); f.call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent()); customer=f.login("customer-a",PASSWORD,"customer");
        var effective=f.body(f.call(HttpMethod.GET,"/api/customer/devices/device-a/effective-parameters",customer,null,null,null).andExpect(status().isOk())); assertThat(effective.at("/items/0/value").asLong()).isEqualTo(10); assertThat(effective.toString()).doesNotContain("actor_id").doesNotContain("reason").doesNotContain("chain");
        f.call(HttpMethod.GET,"/api/customer/devices/device-b/effective-parameters",customer,null,null,null).andExpect(status().isNotFound()); f.call(HttpMethod.GET,"/api/customer/devices/device-a/effective-parameters",customer,"tenant-a",null,null).andExpect(status().isForbidden());
    }

    @Test void nearestPlacePolicyAppliesToTheCurrentSubtreeAndDeviceOverrideWins() throws Exception {
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES ('test-floor','tenant-a','Floor',?),('test-room','tenant-a','Room','test-floor'),('other-place','tenant-b','Other unit',NULL)",f.placeId);
        var observed=databaseTime.now().minusMinutes(12);
        jdbc.update("UPDATE devices SET last_message_at=?,place_id='test-room',physical_state='FIRE' WHERE id='device-a'",observed);
        set("TENANT","tenant-a",60,0,"unit-policy").andExpect(status().isOk());
        set("PLACE",f.placeId,30,0,"building-policy").andExpect(status().isOk());
        assertThat(view("PLACE","test-room").at("/effective/source_id").asText()).isEqualTo(f.placeId);
        assertThat(item("device-a").at("/offline_policy/source_id").asText()).isEqualTo(f.placeId);
        assertThat(item("device-a").get("connectivity").asText()).isEqualTo("ONLINE");
        set("PLACE","test-floor",5,0,"floor-policy").andExpect(status().isOk());
        assertThat(item("device-a").at("/offline_policy/source_id").asText()).isEqualTo("test-floor");
        assertThat(item("device-a").get("connectivity").asText()).isEqualTo("UNKNOWN");
        assertThat(item("device-a").get("physical_state").asText()).isEqualTo("FIRE");
        set("DEVICE","device-a",45,0,"device-policy").andExpect(status().isOk());
        assertThat(item("device-a").at("/offline_policy/source_scope").asText()).isEqualTo("DEVICE");
        assertThat(item("device-a").get("connectivity").asText()).isEqualTo("ONLINE");
        clear("DEVICE","device-a",1,"clear-device-place").andExpect(status().isOk());
        assertThat(item("device-a").at("/offline_policy/source_id").asText()).isEqualTo("test-floor");
        clear("PLACE","test-floor",1,"clear-floor").andExpect(status().isOk());
        assertThat(view("PLACE","test-floor").get("revision").asInt()).isEqualTo(2);
        assertThat(item("device-a").at("/offline_policy/source_id").asText()).isEqualTo(f.placeId);
        jdbc.update("UPDATE devices SET place_id=NULL WHERE id='device-a'");
        assertThat(item("device-a").at("/offline_policy/source_scope").asText()).isEqualTo("TENANT");
        set("PLACE","other-place",1,0,"foreign-policy").andExpect(status().isNotFound());
    }

    @Test void deviceGroupsApplyOnlyToCurrentMembersAndDoNotOverrideAnExplicitDevice() throws Exception {
        String first = "11111111-1111-4111-8111-111111111111";
        String second = "22222222-2222-4222-8222-222222222222";
        jdbc.update("INSERT INTO device_groups(id,tenant_id,name,description,revision) VALUES (?,'tenant-a','Policy group A','',0),(?,'tenant-a','Policy group B','',0)",first,second);
        set("TENANT","tenant-a",30,0,"unit-for-groups").andExpect(status().isOk());
        set("GROUP",first,10,0,"policy-group-a").andExpect(status().isOk());
        set("GROUP",second,20,0,"policy-group-b").andExpect(status().isOk());
        assertThat(item("device-a").at("/offline_policy/value").asLong()).isEqualTo(30);
        jdbc.update("INSERT INTO device_group_members(tenant_id,group_id,device_id) VALUES ('tenant-a',?,'device-a'),('tenant-a',?,'device-a')",first,second);
        assertThat(item("device-a").at("/offline_policy/source_id").asText()).isEqualTo(second);
        assertThat(view("DEVICE","device-a").at("/effective/source_scope").asText()).isEqualTo("GROUP");
        set("DEVICE","device-a",4,0,"group-precedence").andExpect(status().isOk());
        assertThat(item("device-a").at("/offline_policy/value").asLong()).isEqualTo(4);
        clear("DEVICE","device-a",1,"group-clear-device").andExpect(status().isOk());
        jdbc.update("DELETE FROM device_group_members WHERE tenant_id='tenant-a' AND group_id=?",second);
        assertThat(item("device-a").at("/offline_policy/source_id").asText()).isEqualTo(first);
        assertThat(item("device-a-unactivated").at("/offline_policy/value").asLong()).isEqualTo(30);
    }
    @Test void integerTokensUnknownFieldsReasonsScopesAndCrossTenantTargetsFailClosed() throws Exception {
        for(String token:List.of("\"10\"","true","null","10.0","1e1","0","525601")) mvc.perform(put(path("TENANT","tenant-a")).header("Authorization","Bearer "+f.root).header("X-Tenant-Id","tenant-a").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"value\":"+token+",\"expected_revision\":0,\"reason\":\"test\"}")).andExpect(status().isBadRequest());
        for(String token:List.of("\"0\"","false","null","0.0","0e0","-1")) mvc.perform(put(path("TENANT","tenant-a")).header("Authorization","Bearer "+f.root).header("X-Tenant-Id","tenant-a").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"value\":10,\"expected_revision\":"+token+",\"reason\":\"test\"}")).andExpect(status().isBadRequest());
        for(String reason:List.of(" ","bad\u0000text")) f.call(HttpMethod.PUT,path("TENANT","tenant-a"),f.root,"tenant-a",UUID.randomUUID().toString(),Map.of("value",10,"expected_revision",0,"reason",reason)).andExpect(status().isBadRequest());
        // Send the actual escaped invalid Unicode token, not a Java surrogate replaced with '?' by MockMvc UTF-8 encoding.
        mvc.perform(put(path("TENANT","tenant-a")).header("Authorization","Bearer "+f.root).header("X-Tenant-Id","tenant-a").header("Idempotency-Key","invalid-unicode").contentType("application/json").content("{\"value\":10,\"expected_revision\":0,\"reason\":\"\\uD800\"}")).andExpect(status().isBadRequest());
        var extra=new HashMap<String,Object>(); extra.put("value",10); extra.put("expected_revision",0); extra.put("reason","test"); extra.put("tenant_id","tenant-b"); f.call(HttpMethod.PUT,path("TENANT","tenant-a"),f.root,"tenant-a","forged",extra).andExpect(status().isBadRequest());
        mvc.perform(put(path("TENANT","tenant-a")).header("Authorization","Bearer "+f.root).header("X-Tenant-Id","tenant-a").header("Idempotency-Key","duplicate").contentType("application/json").content("{\"value\":10,\"value\":11,\"expected_revision\":0,\"reason\":\"test\"}")).andExpect(status().isBadRequest());
        f.call(HttpMethod.GET,path("GLOBAL","tenant-a"),f.root,"tenant-a",null,null).andExpect(status().isBadRequest()); f.call(HttpMethod.GET,path("TENANT","tenant-b"),f.root,"tenant-a",null,null).andExpect(status().isNotFound()); f.call(HttpMethod.GET,path("DEVICE","device-b"),f.root,"tenant-a",null,null).andExpect(status().isNotFound()); f.call(HttpMethod.GET,"/api/management/parameter-values/DEVICE/device-a/default_page_size",f.root,"tenant-a",null,null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARAMETER_SCOPE_UNSUPPORTED")); f.call(HttpMethod.GET,"/api/management/parameter-values/TENANT/tenant-a/unknown",f.root,"tenant-a",null,null).andExpect(status().isNotFound()); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_overrides",Long.class)).isZero();
    }
    @Test void dedicatedRightsDoNotRequireDeviceDirectoryAndRetriesRecheckCurrentRights() throws Exception {
        var input=Map.of("value",10,"expected_revision",0,"reason","Explicit offline threshold policy"); set("DEVICE","device-a",10,0,"retry").andExpect(status().isOk());
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission IN ('device:read','device:write','sources:read','products:read')"); f.call(HttpMethod.GET,"/api/management/parameter-targets",f.root,"tenant-a",null,null).andExpect(status().isOk()); f.call(HttpMethod.GET,path("DEVICE","device-a"),f.root,"tenant-a",null,null).andExpect(status().isOk()); f.call(HttpMethod.PUT,path("DEVICE","device-a"),f.root,"tenant-a","retry",input).andExpect(status().isOk());
        f.call(HttpMethod.PUT,path("DEVICE","device-a"),f.root,"tenant-a","retry",Map.of("value",10,"expected_revision",0,"reason","test")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='parameters:read'"); f.call(HttpMethod.PUT,path("DEVICE","device-a"),f.root,"tenant-a","retry",input).andExpect(status().isForbidden());
        for(var row:jdbc.queryForList("SELECT response_json FROM idempotency_records WHERE operation LIKE 'parameter-%'")) assertThat(row.toString()).contains("change_id").doesNotContain(PASSWORD);
    }
    @Test void auditFailureRollsBackOverrideHistoryAndLedgerTogether() throws Exception {
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT deny_parameter_set CHECK(action <> 'PARAMETER_SET')"); try { set("DEVICE","device-a",10,0,"fault").andExpect(status().isServiceUnavailable()); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_overrides",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_changes",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation='parameter-set'",Long.class)).isZero(); } finally { jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT deny_parameter_set"); }
        set("DEVICE","device-a",10,0,"fault").andExpect(status().isOk());
    }
    @Test void concurrentCreationHasOneCasWinnerAndHistoryStaysAppendOnly() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) { var jobs=pool.invokeAll(List.<Callable<Integer>>of(()->set("DEVICE","device-a",10,0,"a").andReturn().getResponse().getStatus(),()->set("DEVICE","device-a",20,0,"b").andReturn().getResponse().getStatus()),10,TimeUnit.SECONDS); var statuses=new ArrayList<Integer>(); for(var job:jobs) { assertThat(job.isCancelled()).isFalse(); statuses.add(job.get()); } assertThat(statuses).containsExactlyInAnyOrder(200,409); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_overrides",Long.class)).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_changes",Long.class)).isEqualTo(1);
        var history=f.body(f.call(HttpMethod.GET,path("DEVICE","device-a")+"/history",f.root,"tenant-a",null,null).andExpect(status().isOk())); var first=history.at("/items/0").deepCopy(); set("DEVICE","device-a",30,1,"second").andExpect(status().isOk()); assertThat(f.body(f.call(HttpMethod.GET,path("DEVICE","device-a")+"/history",f.root,"tenant-a",null,null).andExpect(status().isOk())).at("/items/1")).isEqualTo(first);
    }
    @Test void pageAndSummaryStayConsistentAsResultSetGrows() {
        for(int i=0;i<50;i++) jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES (?,'tenant-a',?,?,'test','address','UNKNOWN')","bulk-"+i,"Policy%_"+i,"bulk-eui-"+i);
        var small=devices.devices("bootstrap-admin", "tenant-a", 1,1,""); var large=devices.devices("bootstrap-admin", "tenant-a", 1,100,""); var largeIds=new HashSet<String>();for(var row:large.items())largeIds.add(row.id());assertThat(largeIds).contains(small.items().getFirst().id());assertThat(large.items()).hasSizeGreaterThan(small.items().size());assertThat(large.items().stream().map(d->d.offlinePolicy().evaluatedAt()).distinct().count()).isEqualTo(1);
        var summary=devices.summary("bootstrap-admin", "tenant-a"); assertThat(summary.deviceTotal()).isEqualTo(large.total());
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status->devices.device("bootstrap-admin", "tenant-a", "device-a",true));
        assertThat(small.items().getFirst().offlinePolicy().unit()).isEqualTo("分钟");
    }
}
