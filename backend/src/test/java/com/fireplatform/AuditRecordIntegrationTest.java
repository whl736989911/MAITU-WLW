package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.audit.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.*;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.shared.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.availability.*;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.simulation-enabled=false","app.simulated-notifications-enabled=false","app.notification-contact-key=","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:audit-records;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test") @Import(AuditRecordIntegrationTest.Observation.class)
class AuditRecordIntegrationTest {
    static final OffsetDateTime T=OffsetDateTime.parse("2026-10-22T00:00:00.123456Z");
    static final String ACTOR="a1234567-e89b-12d3-a456-426614174001",TARGET="b1234567-e89b-12d3-a456-426614174002";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @TestConfiguration static class Observation {
        @Bean @Primary Clock differentAppClock(){return Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"),ZoneOffset.UTC);}
        @Bean @Primary Observed observed(@Qualifier("dataSource")DataSource actual){return new Observed(actual);}
    }
    record Call(String sql,boolean readOnly,int isolation,boolean finalOutput) { }
    static class Observed extends AbstractDataSource {
        final DataSource actual;final List<Call> calls=new CopyOnWriteArrayList<>();final AtomicInteger metadata=new AtomicInteger();volatile boolean capture;volatile Runnable onRows;
        Observed(DataSource actual){this.actual=actual;}
        Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
        void observe(Connection connection,String sql)throws SQLException{calls.add(new Call(sql, TransactionSynchronizationManager.isCurrentTransactionReadOnly(), connection.getTransactionIsolation(), "authorized-json-output".equals(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionName())));}
        Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(capture&&m.getName().equals("getTransactionIsolation"))metadata.incrementAndGet();
            if(capture&&m.getName().equals("prepareStatement")&&a!=null&&a[0] instanceof String sql){
                if(sql.startsWith("SELECT "+AuditRecordProjection.COLUMNS)&&onRows!=null){var hook=onRows;onRows=null;hook.run();}observe(connection,sql);
            }
            if(m.getName().equals("createStatement")){Object statement=invoke(connection,m,a);return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(s,method,args)->{if(capture&&method.getName().startsWith("execute")&&args!=null&&args.length>0&&args[0] instanceof String sql)observe(connection,sql);return invoke(statement,method,args);});}
            return invoke(connection,m,a);
        });}
        @Override public Connection getConnection()throws SQLException{return wrap(actual.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(actual.getConnection(user,password));}
        void start(){calls.clear();metadata.set(0);capture=true;}
        void stop(){capture=false;onRows=null;}
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired Bootstrap bootstrap;@Autowired JsonPointerParser parser;@Autowired Observed observed;@Autowired AuditRecordQueries queries;
    @MockitoSpyBean RuntimeConfig runtime;@MockitoSpyBean ApplicationAvailability availability;
    IngestionIntegrationTest f;
    @BeforeEach void reset()throws Exception{observed.stop();NotificationIntegrationTest.clear(jdbc);jdbc.update("DELETE FROM alarm_change_outbox");jdbc.update("DELETE FROM alarm_change_heads");f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();jdbc.update("DELETE FROM audit_log");jdbc.update("UPDATE numeric_settings SET setting_value=20,revision=0 WHERE setting_key='default_page_size'");}
    @AfterEach void stop(){observed.stop();}
    static String id(int n){return String.format(Locale.ROOT,"%08x-0000-0000-0000-%012x",n,n);}
    void seed(String id,String tenant,String action,String outcome,String actor,String target,OffsetDateTime at){jdbc.update("INSERT INTO audit_log(id,actor_id,tenant_id,action,target_id,outcome,request_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)",id,actor,tenant,action,target,outcome,"SECRET_REQUEST",at);}
    void seed(int n,String action,String outcome){seed(id(n),"tenant-a",action,outcome,ACTOR,TARGET,T);}
    MockHttpServletRequestBuilder request(String token,String tenant){var r=get("/api/management/audit-records");if(token!=null)r.header("Authorization","Bearer "+token);if(tenant!=null)r.header("X-Tenant-Id",tenant);return r;}
    ResultActions getRead(String token,String tenant,Map<String,String> params)throws Exception{var r=request(token,tenant);params.forEach(r::queryParam);return mvc.perform(r);}
    JsonNode work(Map<String,String> params)throws Exception{return f.body(getRead(f.root,null,params).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")));}
    Set<String> keys(JsonNode node){var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    String facts()throws Exception{observed.stop();var facts=new TreeMap<String,Object>();for(String table:jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name",String.class))facts.put(table,jdbc.queryForList("SELECT * FROM "+table));return Digests.sha256(json.writeValueAsString(facts));}
    String activate(String name,String initial,String audience)throws Exception{String token=f.login(name,initial,audience);f.call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",initial,"new_password",IdentityManagementTest.UPDATED)).andExpect(status().isNoContent());return f.login(name,IdentityManagementTest.UPDATED,audience);}
    @Test void closedEnvelopeAndRegistryIncludeEveryActualCodeWithoutRawMetadata()throws Exception{
        for(int i=0;i<AuditRecordCatalog.ACTIONS.size()-1;i++)seed(i+1,AuditRecordCatalog.ACTIONS.get(i).code(),AuditRecordCatalog.OUTCOMES.get(i%(AuditRecordCatalog.OUTCOMES.size()-1)).code());seed(100,"SECRET_FUTURE_ACTION","SECRET_FUTURE_OUTCOME");
        var body=work(Map.of("size","2"));assertThat(body.get("items").size()).isEqualTo(2);assertThat(body.at("/scope/kind").asText()).isEqualTo("AUTHORIZED_UNITS");assertThat(body.at("/scope/tenant_ids/0").asText()).isEqualTo("tenant-a");assertThat(body.at("/scope/audience").asText()).isEqualTo("management");assertThat(OffsetDateTime.parse(body.get("as_of").asText()).getYear()).isNotEqualTo(2000);
        assertThat(keys(body.at("/items/0"))).containsExactlyInAnyOrder("id","tenant_id","tenant_name","occurred_at","action","outcome","actor_ref","actor_reference_state","target_ref","target_reference_state");assertThat(body.at("/items/0/id").asText()).isEqualTo(id(1));assertThat(body.toString()).doesNotContain("SECRET_","\"request_id\"","\"actor_id\"","\"target_id\"");
    }
    @Test void unknownClassificationIsIndependentIncludedByDefaultAndFilteredBySameCase()throws Exception{
        seed(1,"SECRET_ACTION","SUCCESS");seed(2,"device-edit","SECRET_OUTCOME");seed(3,"UNCLASSIFIED","UNCLASSIFIED");seed(4,"device-edit","SUCCESS");
        var params=Map.<String,String>of("actor_ref",ACTOR,"target_ref",TARGET);assertThat(work(params).get("total").intValue()).isEqualTo(4);var a=work(Map.of("action","UNCLASSIFIED","actor_ref",ACTOR,"target_ref",TARGET));assertThat(a.get("total").intValue()).isEqualTo(2);assertThat(a.at("/items/0/outcome").asText()).isEqualTo("SUCCESS");assertThat(work(Map.of("outcome","UNCLASSIFIED","actor_ref",ACTOR,"target_ref",TARGET)).get("total").intValue()).isEqualTo(2);var both=work(Map.of("action","UNCLASSIFIED","outcome","UNCLASSIFIED","actor_ref",ACTOR,"target_ref",TARGET));assertThat(both.get("total").intValue()).isEqualTo(1);assertThat(both.at("/items/0/id").asText()).isEqualTo(id(3));assertThat(a.toString()+both).doesNotContain("SECRET_");
    }
    @Test void fullUuidReferencesCasefoldButNullBootstrapAndUnsafeValuesRemainDistinct()throws Exception{
        seed(id(1),"tenant-a","CAPTURE","SUCCESS",ACTOR.toUpperCase(Locale.ROOT),TARGET.toUpperCase(Locale.ROOT),T);
        seed(id(2),"tenant-a","CAPTURE","SUCCESS",null,"bootstrap-admin",T);seed(id(3),"tenant-a","CAPTURE","SUCCESS","bootstrap-admin","SECRET/path",T);seed(id(4),"tenant-a","CAPTURE","SUCCESS","1-1-1-1-1",TARGET+"\n",T);seed(id(5),"tenant-a","CAPTURE","SUCCESS","","",T);
        var rows=work(Map.of()).get("items");assertThat(rows.get(0).get("actor_ref").asText()).isEqualTo(ACTOR);assertThat(rows.get(0).get("target_ref").asText()).isEqualTo(TARGET);assertThat(rows.get(0).get("actor_reference_state").asText()).isEqualTo("UUID");assertThat(rows.get(1).get("actor_reference_state").asText()).isEqualTo("NOT_RECORDED");for(int i=1;i<5;i++){assertThat(rows.get(i).get("target_ref").isNull()).isTrue();assertThat(rows.get(i).get("target_reference_state").asText()).isEqualTo("WITHHELD");}assertThat(rows.get(2).get("actor_reference_state").asText()).isEqualTo("WITHHELD");assertThat(rows.toString()).doesNotContain("bootstrap-admin","SECRET_","1-1-1-1-1");
        assertThat(work(Map.of("actor_ref",ACTOR,"target_ref",TARGET)).get("total").intValue()).isEqualTo(1);
    }
    @Test void noGlobalNullTenantOrForeignUnitRecordsCanBeReachedByFilters()throws Exception{
        jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-b'");
        seed(1,"device-edit","SUCCESS");seed(id(2),"tenant-b","device-edit","SUCCESS",ACTOR,TARGET,T);seed(id(3),null,"device-edit","SUCCESS",ACTOR,TARGET,T);
        var all=work(Map.of("actor_ref",ACTOR,"target_ref",TARGET));assertThat(all.get("total").intValue()).isEqualTo(1);assertThat(all.at("/items/0/tenant_id").asText()).isEqualTo("tenant-a");assertThat(all.toString()).doesNotContain(id(2),id(3));
    }
    @Test void defaultsToAllAuthorizedUnitsAndExplicitClearYieldsNoRows()throws Exception{
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-b'",Integer.class)==0)jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-b')");
        seed(id(1),"tenant-a","device-edit","SUCCESS",ACTOR,TARGET,T);seed(id(2),"tenant-b","device-edit","SUCCESS",ACTOR,TARGET,T.plusNanos(1000));seed(id(3),null,"device-edit","SUCCESS",ACTOR,TARGET,T.plusNanos(2000));
        var all=f.body(getRead(f.root,null,Map.of("actor_ref",ACTOR,"target_ref",TARGET)).andExpect(status().isOk()));
        assertThat(all.get("total").intValue()).isEqualTo(2);
        assertThat(all.at("/scope/tenant_ids").toString()).contains("tenant-a","tenant-b");
        assertThat(all.get("items").findValuesAsText("id")).containsExactly(id(2),id(1));
        assertThat(all.get("items").findValuesAsText("tenant_id")).containsExactly("tenant-b","tenant-a");
        var selected=f.body(getRead(f.root,null,Map.of("scope_selected","true","scope_tenant_ids","tenant-a","scope_place_ids","","actor_ref",ACTOR,"target_ref",TARGET)).andExpect(status().isOk()));
        assertThat(selected.get("total").intValue()).isEqualTo(1);assertThat(selected.at("/items/0/tenant_id").asText()).isEqualTo("tenant-a");
        var cleared=f.body(getRead(f.root,null,Map.of("scope_selected","true","scope_tenant_ids","","scope_place_ids","")).andExpect(status().isOk()));
        assertThat(cleared.get("total").intValue()).isZero();assertThat(cleared.at("/scope/tenant_ids").isEmpty()).isTrue();
    }
    @Test void everyPublicTableIsUnchangedBySuccessfulGetAndSensitiveColumnsAreNeverRead()throws Exception{
        seed(id(1),"tenant-a","SECRET_ACTION","SECRET_OUTCOME","SECRET_ACTOR","SECRET_TARGET",T);String before=facts();observed.start();String text=work(Map.of()).toString();var calls=List.copyOf(observed.calls);observed.stop();assertThat(facts()).isEqualTo(before);assertThat(text).doesNotContain("SECRET_");assertThat(calls.stream().filter(Call::readOnly)).noneMatch(c->c.sql().contains("password_hash")||c.sql().contains("request_id")||c.sql().startsWith("SELECT *"));
    }
    @Test void p2AuditOnlyPlatformNeedsNoOtherReadPermissionAndCustomersCannotReceiveIt()throws Exception{
        f.call(HttpMethod.POST,"/api/management/platform-users",f.root,null,"audit-only",Map.of("username","auditor","name","Audit reader","initial_password",IdentityManagementTest.TARGET,"permissions",List.of("audit:read"),"tenant_ids",List.of("tenant-a"))).andExpect(status().isOk());String token=activate("auditor",IdentityManagementTest.TARGET,"management");seed(1,"CAPTURE","SUCCESS");observed.start();assertThat(f.body(getRead(token,null,Map.of("actor_ref",ACTOR,"target_ref",TARGET)).andExpect(status().isOk())).get("total").intValue()).isEqualTo(1);observed.stop();getRead(token,null,Map.of("scope_selected","true","scope_tenant_ids","tenant-b","scope_place_ids","")).andExpect(status().isForbidden());f.call(HttpMethod.GET,"/api/management/tenants",token,null,null,null).andExpect(status().isForbidden());f.call(HttpMethod.GET,"/api/management/users",token,"tenant-a",null,null).andExpect(status().isForbidden());
        f.call(HttpMethod.POST,"/api/management/users",f.root,"tenant-a","illegal-customer-audit",Map.of("username","illegal-auditor","name","x","initial_password",IdentityManagementTest.TARGET,"permissions",List.of("audit:read"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DELEGATION_FORBIDDEN"));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id IN ('customer-a','customer-b') AND permission='audit:read'",Integer.class)).isZero();
    }
    @Test void customerHasNoEndpointEvenWithForgedDatabaseRightAndManagementAudienceIsRejected()throws Exception{
        String customer=activate("customer-a",IngestionIntegrationTest.INITIAL,"customer");
        String customerId=jdbc.queryForObject("SELECT id FROM app_users WHERE username=?",String.class,"customer-a");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,'audit:read')",customerId);
        getRead(customer,null,Map.of()).andExpect(status().isForbidden());mvc.perform(get("/api/customer/audit-records").header("Authorization","Bearer "+customer)).andExpect(status().isNotFound());getRead(customer,"tenant-a",Map.of()).andExpect(status().isForbidden());
    }
    @Test void freshPermissionGrantEnabledUserUnitAndForcedPasswordAreAllRechecked()throws Exception{
        getRead(null,"tenant-a",Map.of()).andExpect(status().isUnauthorized());jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='audit:read'");getRead(f.root,"tenant-a",Map.of()).andExpect(status().isForbidden());jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('bootstrap-admin','audit:read')");jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");getRead(f.root,null,Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','tenant-a')");jdbc.update("UPDATE tenants SET enabled=FALSE WHERE id='tenant-a'");getRead(f.root,null,Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));jdbc.update("UPDATE tenants SET enabled=TRUE WHERE id='tenant-a'");jdbc.update("UPDATE app_users SET must_change_password=TRUE WHERE id='bootstrap-admin'");getRead(f.root,"tenant-a",Map.of()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));jdbc.update("UPDATE app_users SET must_change_password=FALSE,enabled=FALSE WHERE id='bootstrap-admin'");getRead(f.root,"tenant-a",Map.of()).andExpect(status().isUnauthorized());
    }
    @Test void sixConditionsAreAndedAndStoredTimeBoundariesAreHalfOpenAndMicrosecondExact()throws Exception{
        seed(id(1),"tenant-a","device-edit","SUCCESS",ACTOR,TARGET,T);seed(id(2),"tenant-a","device-edit","SUCCESS",ACTOR,TARGET,T.plusNanos(1000));seed(id(3),"tenant-a","device-edit","DENIED",ACTOR,TARGET,T.plusNanos(2000));
        var params=Map.of("action","device-edit","outcome","SUCCESS","actor_ref",ACTOR,"target_ref",TARGET,"occurred_from","2026-10-22T08:00:00.123456+08:00","occurred_to",T.plusNanos(2000).toString());var body=work(params);assertThat(body.get("total").intValue()).isEqualTo(2);assertThat(body.at("/items/0/id").asText()).isEqualTo(id(2));assertThat(body.at("/items/1/occurred_at").asText()).isEqualTo(T.toString());assertThat(body.at("/filters/occurred_from").asText()).isEqualTo(T.toString());var changed=new HashMap<>(params);changed.put("target_ref",id(999));assertThat(work(changed).get("total").intValue()).isZero();
    }
    @Test void centralizedDefaultAndBoundedHighEmptyPageKeepActualMatchingTotals()throws Exception{
        for(int i=1;i<=7;i++)seed(i,"CAPTURE","SUCCESS");jdbc.update("UPDATE numeric_settings SET setting_value=5 WHERE setting_key='default_page_size'");var body=work(Map.of("actor_ref",ACTOR,"target_ref",TARGET));assertThat(body.get("size").intValue()).isEqualTo(5);assertThat(body.get("items").size()).isEqualTo(5);var high=work(Map.of("page","1000000","size","100","actor_ref",ACTOR,"target_ref",TARGET));assertThat(high.get("total").intValue()).isEqualTo(7);assertThat(high.get("items").isEmpty()).isTrue();
    }
    @Test void badQueryFramingTokensAndDatesAreDeniedWithoutSilentDroppingOrValueEcho()throws Exception{
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE outcome='DENIED'",Integer.class),count=0;
        for(var p:List.of(Map.of("page","0"),Map.of("size","101"),Map.of("page","1000001"),Map.of("page","1.5"),Map.of("page","+1"),Map.of("size","01"),Map.of("action","SECRET_FILTER"),Map.of("outcome","CANCELLED"),Map.of("actor_id",ACTOR),Map.of("request_id","SECRET_REQUEST"),Map.of("keyword","SECRET\0"),Map.of("actor_ref","1-1-1-1-1"),Map.of("target_ref",TARGET.toUpperCase(Locale.ROOT)),Map.of("occurred_from","2026-01-01T00:00:00"),Map.of("occurred_from",T.toString(),"occurred_to",T.toString()))){String text=getRead(f.root,"tenant-a",p).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();assertThat(text).doesNotContain("SECRET_");count++;}
        mvc.perform(request(f.root,"tenant-a").queryParam("action","CAPTURE","CAPTURE")).andExpect(status().isBadRequest());count++;mvc.perform(request(f.root,"tenant-a").content("SECRET_BODY")).andExpect(status().isBadRequest());count++;mvc.perform(request(f.root,"tenant-a").header("Transfer-Encoding","chunked")).andExpect(status().isBadRequest());count++;assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE outcome='DENIED'",Integer.class)).isEqualTo(before+count);
    }
    @Test void corruptCentralPageSizeIsSafe503EvenWhenSizeExplicitAndNeverWritesDenied()throws Exception{
        jdbc.update("UPDATE numeric_settings SET setting_value=? WHERE setting_key='default_page_size'",Long.MAX_VALUE);String before=facts();
        for(var params:List.of(Map.<String,String>of(),Map.of("size","1"))){observed.start();getRead(f.root,"tenant-a",params).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUDIT_READ_UNCONFIRMED"));observed.stop();}
        assertThat(facts()).isEqualTo(before);
    }
    @Test void badRowMetadataIsSafe503WithoutDeniedWritesOrLeakingPrivateId()throws Exception{
        seed("SECRET_BAD_ID","tenant-a","CAPTURE","SUCCESS",ACTOR,TARGET,T);int before=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class);String text=getRead(f.root,"tenant-a",Map.of()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUDIT_READ_UNCONFIRMED")).andReturn().getResponse().getContentAsString();assertThat(text).doesNotContain("SECRET_");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)).isEqualTo(before);
    }
    @Test void disabledSimulationMissingKeysNeverGateHistoryButRoleAndReadinessDo()throws Exception{
        seed(1,"CAPTURE","SUCCESS");work(Map.of());String before=facts();doReturn(false).when(runtime).apiEnabled();getRead(f.root,"tenant-a",Map.of()).andExpect(status().isServiceUnavailable());doCallRealMethod().when(runtime).apiEnabled();doReturn(ReadinessState.REFUSING_TRAFFIC).when(availability).getReadinessState();getRead(f.root,"tenant-a",Map.of()).andExpect(status().isServiceUnavailable());doCallRealMethod().when(availability).getReadinessState();assertThat(facts()).isEqualTo(before);
    }
    @Test void concurrentCommittedInsertAndChangeCannotMixCountAndPageSnapshots()throws Exception{
        seed(1,"SIMULATE_FIRE","SUCCESS");observed.start();observed.onRows=()->{try(var pool=Executors.newSingleThreadExecutor()){pool.submit(()->{jdbc.update("UPDATE audit_log SET action='PROCESSING_RETRY',outcome='RETRY' WHERE id=?",id(1));seed(2,"CAPTURE","SUCCESS");}).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError("bounded concurrent writer failed",e);}};var old=work(Map.of());observed.stop();assertThat(old.get("total").intValue()).isEqualTo(1);assertThat(old.get("items").size()).isEqualTo(1);assertThat(old.at("/items/0/action").asText()).isEqualTo("SIMULATE_FIRE");var fresh=work(Map.of());assertThat(fresh.get("total").intValue()).isEqualTo(2);assertThat(fresh.at("/items/0/action").asText()).isEqualTo("PROCESSING_RETRY");
    }
    @Test void weakerAmbientTransactionIsRejectedRatherThanPretendingAnnotationUpgradesIt(){var tx=new TransactionTemplate(new DataSourceTransactionManager(observed));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThatThrownBy(()->tx.execute(s->queries.query("bootstrap-admin",1,null,AuditRecordFilter.parse(null,null,null,null,null,null),ScopeFilter.Selection.all(),AuditLogSearch.empty(),new AuditLogSearch.Access(false,false)))).isInstanceOfSatisfying(ApiError.class,e->assertThat(e.code).isEqualTo("TRANSACTION_ISOLATION_REQUIRED"));}
    @Test void literalSearchModuleAndPlatformShareExportScopeWithoutPrivateReasons()throws Exception{
        jdbc.update("UPDATE tenants SET name='现场%_literal' WHERE id='tenant-a'");
        seed(id(1),"tenant-a","device-edit","SUCCESS","bootstrap-admin",TARGET,T);
        seed(id(2),"tenant-b","device-edit","SUCCESS","bootstrap-admin",TARGET,T);
        jdbc.update("UPDATE audit_log SET client_platform='WEB' WHERE id IN (?,?)",id(1),id(2));
        var filters=Map.of("keyword","%_","module","DEVICE","platform","WEB","scope_selected","true","scope_tenant_ids","tenant-a","scope_place_ids","");
        var rows=work(filters);assertThat(rows.get("items").findValuesAsText("id")).containsExactly(id(1));
        assertThat(rows.at("/details/"+id(1)+"/module").asText()).isEqualTo("DEVICE");
        var request=get("/api/management/audit-records/export").header("Authorization","Bearer "+f.root);filters.forEach(request::queryParam);
        String csv=mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains(id(1),"现场%_literal").doesNotContain(id(2),"SECRET_REQUEST");
        assertThat(work(Map.of("module","ALARM","keyword","%_")).get("total").intValue()).isZero();
    }
    @Test void newBusinessActionsRemainFilterableWithoutExposingUnknownRawCodes()throws Exception{
        seed(1,"ALARM_BASE_CONFIG_UPDATED","SUCCESS");
        seed(2,"DUTY_FAULT_HANDLING","HANDLED");
        seed(3,"DEVICE_PORTRAIT_EVALUATE","SUCCESS");
        seed(4,"IDENTITY-USER-METADATA","SUCCESS");
        seed(5,"SUPPRESSION_CREATED","SUCCESS");
        seed(6,"SUPPRESSION_SECRET_FUTURE_ACTION","SECRET_FUTURE_OUTCOME");
        seed(7,"SUPPRESSION_UPDATED","SUCCESS");
        seed(8,"SUPPRESSION_ENABLED","SUCCESS");
        seed(9,"SUPPRESSION_DISABLED","SUCCESS");
        seed(10,"SUPPRESSION_DELETED","SUCCESS");
        seed(11,"DEVICE_CHANNEL_CREATED","SUCCESS");
        seed(12,"DEVICE_CHANNEL_UPDATED","SUCCESS");
        var alarms=work(Map.of("module","ALARM"));
        assertThat(alarms.get("items").findValuesAsText("id")).containsExactly(id(1),id(2));
        assertThat(alarms.at("/items/1/outcome").asText()).isEqualTo("HANDLED");
        assertThat(work(Map.of("module","DEVICE")).get("items").findValuesAsText("id")).containsExactly(id(3),id(11),id(12));
        assertThat(work(Map.of("module","IDENTITY")).get("items").findValuesAsText("id")).containsExactly(id(4));
        assertThat(work(Map.of("module","NOTIFICATION")).get("items").findValuesAsText("id")).containsExactly(id(5),id(7),id(8),id(9),id(10));
        var export=get("/api/management/audit-records/export").header("Authorization","Bearer "+f.root).queryParam("module","ALARM");
        String csv=mvc.perform(export).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains(id(1),id(2)).doesNotContain(id(3),id(6),"SECRET_");
        assertThat(work(Map.of("module","UNCLASSIFIED")).get("items").findValuesAsText("id")).containsExactly(id(6));
    }
    @Test void auditExportEscapesSpreadsheetFormulasAndHidesNamesWithoutDirectoryRights()throws Exception{
        jdbc.update("UPDATE app_users SET username='=2+2' WHERE id='bootstrap-admin'");
        seed(id(1),"tenant-a","device-edit","SUCCESS","bootstrap-admin",TARGET,T);
        var export=get("/api/management/audit-records/export").header("Authorization","Bearer "+f.root);
        String csv=mvc.perform(export).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=2+2\"").doesNotContain("SECRET_REQUEST");
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission IN ('users:read','platform-users:read')");
        assertThat(work(Map.of()).at("/details/"+id(1)+"/actor_username").isNull()).isTrue();
        getRead(f.root,null,Map.of("username","=2+2")).andExpect(status().isForbidden());
        String redacted=mvc.perform(get("/api/management/audit-records/export").header("Authorization","Bearer "+f.root)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(redacted).contains(id(1)).doesNotContain("=2+2");
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='exports:read'");
        mvc.perform(get("/api/management/audit-records/export").header("Authorization","Bearer "+f.root)).andExpect(status().isForbidden());
    }
}
