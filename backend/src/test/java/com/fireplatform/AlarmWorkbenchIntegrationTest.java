package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.alarm.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.product.JsonPointerParser;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import static com.fireplatform.IngestionIntegrationTest.PASSWORD;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:alarm-workbench;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(AlarmWorkbenchIntegrationTest.Observation.class)
class AlarmWorkbenchIntegrationTest {
    static final OffsetDateTime T=OffsetDateTime.parse("2026-10-22T00:00:00.123456Z");
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { IngestionIntegrationTest.environment(r); }
    @TestConfiguration static class Observation {
        @Bean @Primary Clock deliberatelyDifferentAppClock() { return Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"),ZoneOffset.UTC); }
        @Bean @Primary ObservedDataSource workbenchObservedDataSource(@Qualifier("dataSource") DataSource actual) { return new ObservedDataSource(actual); }
    }
    record SqlCall(String sql,Map<Integer,Object> bindings,int isolation,boolean finalOutput) { }
    static class ObservedDataSource extends AbstractDataSource {
        final DataSource actual; final List<SqlCall> calls=new CopyOnWriteArrayList<>(); volatile Runnable onPage;
        ObservedDataSource(DataSource actual) { this.actual=actual; }
        private Object invoke(Object target,Method method,Object[] args) throws Throwable { try { return method.invoke(target,args); } catch(InvocationTargetException e) { throw e.getCause(); } }
        Connection wrap(Connection connection) { return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql) {
                if(sql.startsWith("SELECT id,device_id,device_name,address,event_type") && onPage!=null) { var hook=onPage; onPage=null; hook.run(); }
                var parameters=new TreeMap<Integer,Object>(); var call=new SqlCall(sql, parameters, connection.getTransactionIsolation(), "authorized-json-output".equals(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionName())); calls.add(call); Object statement=invoke(connection,method,args);
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{ if(m.getName().startsWith("set") && a!=null && a.length>=2 && a[0] instanceof Integer index) parameters.put(index,m.getName().equals("setNull")?null:a[1]); return invoke(statement,m,a); });
            }
            if(method.getName().equals("createStatement")) { Object statement=invoke(connection,method,args); return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(p,m,a)->{ if(m.getName().startsWith("execute") && a!=null && a.length>0 && a[0] instanceof String sql) calls.add(new SqlCall(sql, new TreeMap<>(), connection.getTransactionIsolation(), "authorized-json-output".equals(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionName()))); return invoke(statement,m,a); }); }
            return invoke(connection,method,args);
        }); }
        @Override public Connection getConnection() throws SQLException { return wrap(actual.getConnection()); }
        @Override public Connection getConnection(String user,String password) throws SQLException { return wrap(actual.getConnection(user,password)); }
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser; @Autowired AlarmWorkbenchQueries workbench; @Autowired DatabaseTime time; @Autowired ObservedDataSource observed;
    IngestionIntegrationTest f;
    @BeforeEach void reset() throws Exception {
        f=new IngestionIntegrationTest(); f.mvc=mvc; f.json=json; f.jdbc=jdbc; f.bootstrap=bootstrap; f.parser=parser; f.reset(); jdbc.update("UPDATE numeric_settings SET setting_value=20,revision=0 WHERE setting_key='default_page_size'");
        seed("alarm-a0","tenant-a","device-a",T.minusNanos(1000),"FIRE","UNHANDLED",null,"Snapshot%_! <script>","Historical address");
        seed("alarm-a1","tenant-a","device-a",T,"FIRE","UNHANDLED",null,"Alpha group","North");
        seed("alarm-a2","tenant-a","device-a",T.plusNanos(1000),"FIRE","HANDLED","TRUE_FIRE","Alpha group","North");
        seed("alarm-a3","tenant-a","device-a",T.plusSeconds(1),"NORMAL","HANDLED","FALSE_ALARM","Beta group","South");
        seed("alarm-a4","tenant-a","device-a",T.plusSeconds(2),"NORMAL","UNHANDLED",null,"Gamma group","West");
        seed("alarm-a5","tenant-a","device-a",T.plusSeconds(3),"FIRE","HANDLED","TEST","Delta group","East");
        seed("alarm-a6","tenant-a","device-a",T.plusSeconds(4),"NORMAL","HANDLED","DEVICE_ALARM","Echo group","East");
        seed("alarm-a7","tenant-a","device-a",T.plusSeconds(5),"NORMAL","HANDLED","TRUE_FIRE","Zeta group","South");
        seed("alarm-b0","tenant-b","device-b",T,"FIRE","HANDLED","TRUE_FIRE","Alpha foreign group","North"); seed("alarm-historical-simulation","tenant-a","device-a",T.plusSeconds(6),"FIRE","UNHANDLED",null,"Historical simulation","Old simulated record"); jdbc.update("UPDATE alarms SET is_simulated=TRUE WHERE id='alarm-historical-simulation'"); observed.calls.clear(); observed.onPage=null;
    }
    void seed(String id,String tenant,String device,OffsetDateTime at,String physical,String response,String conclusion,String name,String address) { jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,conclusion,remark,revision,is_simulated) VALUES (?,?,?,?,?,'FIRE',?,?,?,?, 'Private fixture note',0,FALSE)",id,tenant,device,name,address,at,physical,response,conclusion); }
    ResultActions request(String token,String tenant,Map<String,String> query) throws Exception { var builder=get("/api/management/alarm-workbench").header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); query.forEach(builder::param); return mvc.perform(builder); }
    JsonNode bench(Map<String,String> query) throws Exception { return f.body(request(f.root,"tenant-a",query).andExpect(status().isOk())); }
    AlarmWorkbenchFilter filter(String keyword,String physical,String response,String conclusion,String from,String to) { return AlarmWorkbenchFilter.parse(keyword,physical,response,conclusion,from,to); }
    void metrics(JsonNode body,long total,long unhandled,long handled,long unrecovered,long recovered,long trueFire) { assertThat(body.get("total").asLong()).isEqualTo(total); var m=body.get("metrics"); assertThat(m.get("alarm_total").asLong()).isEqualTo(total); assertThat(m.get("unhandled").asLong()).isEqualTo(unhandled); assertThat(m.get("handled").asLong()).isEqualTo(handled); assertThat(m.get("unrecovered").asLong()).isEqualTo(unrecovered); assertThat(m.get("recovered").asLong()).isEqualTo(recovered); assertThat(m.get("true_fire").asLong()).isEqualTo(trueFire); }
    List<String> ids(JsonNode body) { var result=new ArrayList<String>(); body.get("items").forEach(i->result.add(i.get("id").asText())); return result; }
    @Test void scopeTenantFilterNarrowsWorkbenchRows() throws Exception {
        var onlyA=f.body(request(f.root,"tenant-a",Map.of("scope_tenant_ids","tenant-a")).andExpect(status().isOk()));
        assertThat(onlyA.get("total").asLong()).isEqualTo(8);
        var none=f.body(request(f.root,"tenant-a",Map.of("scope_tenant_ids","tenant-b")).andExpect(status().isOk()));
        assertThat(none.get("total").asLong()).isZero();
        request(f.root,"tenant-a",Map.of("scope_tenant_ids","unknown-tenant")).andExpect(status().isForbidden());
    }

    @Test void defaultAllHasSixIndependentMetricsCanonicalMetadataAndActualDatabaseAsOfNotAppClock() throws Exception {
        var before=time.now(); var result=bench(Map.of()); var after=time.now(); metrics(result,8,3,5,4,4,2); assertThat(result.get("size").asInt()).isEqualTo(20); assertThat(result.get("timezone").asText()).isEqualTo("Asia/Shanghai"); var asOf=OffsetDateTime.parse(result.get("as_of").asText()); assertThat(asOf.getOffset()).isEqualTo(ZoneOffset.UTC); assertThat(asOf.toInstant()).isBetween(before.toInstant(),after.toInstant()); assertThat(asOf.getYear()).isGreaterThan(2000);
        assertThat(result.get("filters").size()).isEqualTo(6); assertThat(result.at("/filters/keyword").asText()).isEmpty(); for(String key:List.of("physical_state","response_state","conclusion","occurred_from","occurred_to")) assertThat(result.get("filters").get(key).isNull()).isTrue();
        assertThat(result.at("/metric_definitions/scope").asText()).isEqualTo("FILTERED_ALL_PAGES"); assertThat(result.at("/metric_definitions/time_field").asText()).isEqualTo("ALARM_OCCURRED_AT"); assertThat(result.at("/metric_definitions/time_bounds").asText()).isEqualTo("FROM_INCLUSIVE_TO_EXCLUSIVE"); assertThat(result.at("/metric_definitions/unhandled").asText()).isEqualTo("RESPONSE_UNHANDLED"); assertThat(result.at("/metric_definitions/unrecovered").asText()).isEqualTo("PHYSICAL_FIRE"); assertThat(result.at("/metric_definitions/true_fire").asText()).isEqualTo("CURRENT_CONCLUSION_TRUE_FIRE");
        for(var row:result.get("items")) { assertThat(row.has("remark")).isFalse(); assertThat(row.has("actor_id")).isFalse(); } assertThat(result.toString()).doesNotContain("Private fixture note").doesNotContain("payload").doesNotContain("lease");
    }
    @Test void activeWorkbenchRowsAndMetricsExcludeHistoricalSimulationRecords() throws Exception {
        var result=bench(Map.of()); metrics(result,8,3,5,4,4,2); assertThat(ids(result)).doesNotContain("alarm-historical-simulation"); assertThat(bench(Map.of("keyword","Historical simulation")).get("total").asLong()).isZero();
    }

    @Test void enumConditionsAreAndedWithoutConflatingPhysicalRecoveryManualHandlingOrCurrentTrueFire() throws Exception {
        metrics(bench(Map.of("physical_state","FIRE","response_state","HANDLED")),2,0,2,2,0,1); metrics(bench(Map.of("physical_state","NORMAL","response_state","UNHANDLED")),1,1,0,0,1,0);
        metrics(bench(Map.of("conclusion","UNREVIEWED")),3,3,0,2,1,0); metrics(bench(Map.of("response_state","HANDLED","conclusion","UNREVIEWED")),0,0,0,0,0,0);
        metrics(bench(Map.of("conclusion","TRUE_FIRE")),2,0,2,1,1,2); for(String conclusion:List.of("FALSE_ALARM","TEST","DEVICE_ALARM")) assertThat(bench(Map.of("conclusion",conclusion)).get("total").asLong()).isEqualTo(1);
        assertThat(ids(bench(Map.of("keyword"," Alpha ","physical_state","FIRE","response_state","HANDLED","conclusion","TRUE_FIRE","occurred_from",T.toString(),"occurred_to",T.plusSeconds(1).toString())))).containsExactly("alarm-a2");
    }
    @Test void microsecondFromInclusiveToExclusiveAndEquivalentOffsetsHaveIdenticalResultsAndNoDefaultWindow() throws Exception {
        var from=T.toString(); var to=T.plusNanos(1000).toString(); var exact=bench(Map.of("occurred_from",from,"occurred_to",to)); assertThat(ids(exact)).containsExactly("alarm-a1"); assertThat(bench(Map.of("occurred_from",from)).get("total").asLong()).isEqualTo(7); assertThat(ids(bench(Map.of("occurred_to",from)))).containsExactly("alarm-a0");
        var offsets=bench(Map.of("occurred_from",T.withOffsetSameInstant(ZoneOffset.ofHours(8)).toString(),"occurred_to",T.plusNanos(1000).withOffsetSameInstant(ZoneOffset.ofHours(8)).toString())); assertThat(offsets.get("items")).isEqualTo(exact.get("items")); assertThat(offsets.get("filters")).isEqualTo(exact.get("filters")); assertThat(offsets.get("metrics")).isEqualTo(exact.get("metrics")); assertThat(offsets.at("/filters/occurred_from").asText()).isEqualTo(T.toString());
        seed("edge-min","tenant-a","device-a",OffsetDateTime.parse("0001-01-01T00:00:00Z"),"FIRE","UNHANDLED",null,"ancient","a"); seed("edge-max","tenant-a","device-a",OffsetDateTime.parse("9999-12-31T23:59:59.999999Z"),"NORMAL","HANDLED","TEST","future","a"); assertThat(bench(Map.of()).get("total").asLong()).isEqualTo(10); assertThat(bench(Map.of("occurred_from","0001-01-01T00:00:00Z","occurred_to","9999-12-31T23:59:59.999999Z")).get("total").asLong()).isEqualTo(9);
    }
    @Test void literalKeywordUsesOnlyAlarmSnapshotsAndIdWithDatabaseLowerAndTrimNotWildcardOrCurrentDirectory() throws Exception {
        assertThat(ids(bench(Map.of("keyword","%_!")))).containsExactly("alarm-a0"); assertThat(ids(bench(Map.of("keyword","ALARM-A1")))).containsExactly("alarm-a1"); assertThat(bench(Map.of("keyword","  aLpHa  ")).get("total").asLong()).isEqualTo(2); assertThat(bench(Map.of("keyword","\uFEFF\u3000\u00A0Alpha\u00A0")).at("/filters/keyword").asText()).isEqualTo("Alpha"); assertThat(bench(Map.of("keyword","' OR 1=1 --")).get("total").asLong()).isZero();
        jdbc.update("UPDATE devices SET name='Current live renamed',address='Current live address',device_eui='current-live-eui' WHERE id='device-a'"); for(String term:List.of("Current live","current-live-eui")) assertThat(bench(Map.of("keyword",term)).get("total").asLong()).isZero(); assertThat(bench(Map.of("keyword","Historical address")).get("total").asLong()).isEqualTo(1);
    }
    @Test void paginationUsesConfiguredDefaultAndAllMatchingMetricsEvenWhenCurrentPageIsEmpty() throws Exception {
        f.call(HttpMethod.PUT,"/api/management/settings/default_page_size",f.root,null,null,Map.of("value",5,"expected_revision",0)).andExpect(status().isOk()); var first=bench(Map.of()); assertThat(first.get("size").asInt()).isEqualTo(5); assertThat(first.get("items").size()).isEqualTo(5); metrics(first,8,3,5,4,4,2); var next=bench(Map.of("page","2")); assertThat(next.get("items").size()).isEqualTo(3); metrics(next,8,3,5,4,4,2); var outside=bench(Map.of("page","20")); assertThat(outside.get("items").isEmpty()).isTrue(); metrics(outside,8,3,5,4,4,2);
        seed("alarm-a8","tenant-a","device-a",T,"FIRE","UNHANDLED",null,"Alpha group","North"); var ties=bench(Map.of("keyword","Alpha","conclusion","UNREVIEWED")); assertThat(ids(ties)).containsExactly("alarm-a1","alarm-a8");
    }
    @Test void minimumAlarmReadWorksForBothAudiencesWithNoHistoryDirectoryRightsAndStrictTenantScopes() throws Exception {
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission<>'alarm:read'"); request(f.root,"tenant-a",Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(8)); request(f.root,"tenant-b",Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)); request(f.root,null,Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(9));
        String customer=f.login("customer-a",IngestionIntegrationTest.INITIAL,"customer"); f.call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent()); customer=f.login("customer-a",PASSWORD,"customer"); String id=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class); jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission<>'alarm:read'",id);
        mvc.perform(get("/api/customer/alarm-workbench").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(8)); mvc.perform(get("/api/customer/alarm-workbench").header("Authorization","Bearer "+customer).header("X-Tenant-Id","tenant-a")).andExpect(status().isForbidden()); mvc.perform(get("/api/customer/alarm-workbench").header("Authorization","Bearer "+customer).param("keyword","alarm-b0")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)); mvc.perform(get("/api/customer/alarm-workbench").header("Authorization","Bearer "+customer).param("scope_selected","true")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission='alarm:read'",id); mvc.perform(get("/api/customer/alarm-workbench").header("Authorization","Bearer "+customer)).andExpect(status().isForbidden());
    }
    @Test void invalidFiltersPaginationUnknownOrRepeatedParametersFailWithoutInvalidatingValidSession() throws Exception {
        for(var query:List.of(Map.of("physical_state","ACTIVE"),Map.of("response_state","NEW"),Map.of("conclusion","OTHER"),Map.of("page","0"),Map.of("page","1000001"),Map.of("size","0"),Map.of("size","101"),Map.of("page","1.5"),Map.of("sort","occurred_at desc;drop table alarms"),Map.of("keyword","x".repeat(101)),Map.of("keyword","\u0000invalid"),Map.of("keyword","\uD800"))) request(f.root,"tenant-a",query).andExpect(status().isBadRequest());
        for(String value:List.of("2026-10-22T08:00","2026-02-29T00:00:00Z","2026-10-22T08:00:00.1234567Z","0000-01-01T00:00:00Z","+10000-01-01T00:00:00Z","0001-01-01T00:00:00+08:00","9999-12-31T23:59:59-08:00")) request(f.root,"tenant-a",Map.of("occurred_from",value)).andExpect(status().isBadRequest());
        request(f.root,"tenant-a",Map.of("occurred_from",T.toString(),"occurred_to",T.toString())).andExpect(status().isBadRequest()); mvc.perform(get("/api/management/alarm-workbench").header("Authorization","Bearer "+f.root).header("X-Tenant-Id","tenant-a").param("physical_state","FIRE","NORMAL")).andExpect(status().isBadRequest()); f.call(HttpMethod.GET,"/api/auth/me",f.root,null,null,null).andExpect(status().isOk());
    }
    @Test void newReadMakesNoBusinessWritesAndLegacyListSummaryCurrentAndHistoryContractsAreUnchanged() throws Exception {
        var alarmFacts=jdbc.queryForList("SELECT * FROM alarms ORDER BY id"); var deviceFacts=jdbc.queryForList("SELECT * FROM devices ORDER BY id"); var revisions=jdbc.queryForList("SELECT id,revision FROM app_users ORDER BY id"); var counts=new HashMap<String,Long>(); for(String table:List.of("alarm_trace_entries","device_profile_snapshots","profile_rules","device_battery_observations","device_events","processing_tasks","idempotency_records","audit_log")) counts.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)); for(int i=0;i<3;i++) bench(Map.of("conclusion","TRUE_FIRE","page","2")); assertThat(jdbc.queryForList("SELECT * FROM alarms ORDER BY id")).isEqualTo(alarmFacts); assertThat(jdbc.queryForList("SELECT * FROM devices ORDER BY id")).isEqualTo(deviceFacts); assertThat(jdbc.queryForList("SELECT id,revision FROM app_users ORDER BY id")).isEqualTo(revisions); for(String table:List.of("alarm_trace_entries","device_profile_snapshots","profile_rules","device_battery_observations","device_events","processing_tasks","idempotency_records","audit_log")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isEqualTo(counts.get(table));
        var old=f.body(f.call(HttpMethod.GET,"/api/management/alarms?physical_state=FIRE",f.root,"tenant-a",null,null).andExpect(status().isOk())); assertThat(old.get("items")).allMatch(row -> row.get("physical_state").asText().equals("FIRE")); assertThat(old.has("metrics")).isFalse(); f.call(HttpMethod.GET,"/api/management/alarms/alarm-a1",f.root,"tenant-a",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0)); f.call(HttpMethod.GET,"/api/management/alarms/alarm-a1/history",f.root,"tenant-a",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.coverage.kind").value("PARTIAL")); f.call(HttpMethod.GET,"/api/management/summary",f.root,"tenant-a",null,null).andExpect(status().isOk());
    }
    @Test void realSqlHasFourQueriesOneAggregateAndLimitedPageSharingExactlySameWhereAndBindings() {
        var applied=filter("Alpha","FIRE",null,null,T.toString(),T.plusSeconds(1).toString()); observed.calls.clear(); var small=workbench.query("bootstrap-admin", "tenant-a", 1,1,applied); var first=List.copyOf(observed.calls); observed.calls.clear(); var large=workbench.query("bootstrap-admin", "tenant-a", 1,100,applied); assertThat(small.total()).isEqualTo(2); assertThat(large.items()).hasSize(2); assertThat(observed.calls).hasSize(4); assertThat(first).hasSize(4); assertThat(observed.calls).allMatch(c->c.isolation()==Connection.TRANSACTION_REPEATABLE_READ); var aggregate=observed.calls.stream().filter(c->c.sql().startsWith("SELECT COUNT(*)")).findFirst().orElseThrow(); var page=observed.calls.stream().filter(c->c.sql().startsWith("SELECT id,device_id")).findFirst().orElseThrow(); assertThat(observed.calls.stream().filter(c->c.sql().contains("FROM alarms")).count()).isEqualTo(2); assertThat(aggregate.sql()).contains("SUM(CASE"); String where=aggregate.sql().substring(aggregate.sql().indexOf(" WHERE ")); assertThat(page.sql()).endsWith(where+" ORDER BY occurred_at DESC,id LIMIT ? OFFSET ?"); assertThat(new ArrayList<>(page.bindings().values()).subList(0,aggregate.bindings().size())).containsExactlyElementsOf(aggregate.bindings().values()); assertThat(observed.calls.stream().noneMatch(c->c.sql().matches("(?i).*\\b(?:INSERT|UPDATE|DELETE)\\b.*"))).isTrue();
    }
    @Test void repeatableReadKeepsAggregateAndPageCoherentWhenRealReviewCommitsBetweenThem() throws Exception {
        var reachedPage=new CountDownLatch(1); var committed=new CountDownLatch(1); observed.onPage=()->{ reachedPage.countDown(); try { if(!committed.await(10,TimeUnit.SECONDS)) throw new AssertionError("Writer did not commit during workbench snapshot"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } };
        try(var pool=Executors.newSingleThreadExecutor()) { var reading=pool.submit(()->workbench.query("bootstrap-admin", "tenant-a", 1,100,filter("",null,"UNHANDLED",null,null,null))); assertThat(reachedPage.await(10,TimeUnit.SECONDS)).isTrue(); try { f.call(HttpMethod.POST,"/api/management/alarms/alarm-a1/review",f.root,"tenant-a","workbench-review",Map.of("expected_revision",0,"conclusion","TRUE_FIRE","remark","Explicit new current conclusion")).andExpect(status().isOk()).andExpect(jsonPath("$.physical_state").value("FIRE")); } finally { committed.countDown(); } var old=reading.get(10,TimeUnit.SECONDS); assertThat(old.total()).isEqualTo(3); assertThat(old.items()).hasSize(3); assertThat(old.metrics().unhandled()).isEqualTo(3); assertThat(old.items().stream().filter(a->a.id().equals("alarm-a1")).findFirst().orElseThrow().responseState()).isEqualTo("UNHANDLED"); }
        metrics(bench(Map.of()),8,2,6,4,4,3); assertThat(bench(Map.of("response_state","UNHANDLED")).get("total").asLong()).isEqualTo(2); var annotation=AlarmWorkbenchQueries.class.getMethod("query",String.class,String.class,int.class,Integer.class,AlarmWorkbenchFilter.class).getAnnotation(Transactional.class); assertThat(annotation.readOnly()).isTrue();
    }
}
