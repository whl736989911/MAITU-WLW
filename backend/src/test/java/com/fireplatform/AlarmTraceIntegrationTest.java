package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.alarm.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.identity.*;
import com.fireplatform.product.JsonPointerParser;
import java.lang.reflect.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.fireplatform.IngestionIntegrationTest.PASSWORD;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:alarm-trace;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(AlarmTraceIntegrationTest.Observation.class)
class AlarmTraceIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { IngestionIntegrationTest.environment(r); }
    @TestConfiguration static class Observation { @Bean @Primary TraceDataSource traceObservedDataSource(@Qualifier("dataSource") DataSource actual) { return new TraceDataSource(actual); } }
    static class TraceDataSource extends ParameterIntegrationTest.CountingDataSource {
        final List<Integer> historyIsolation=new CopyOnWriteArrayList<>(); volatile Runnable onAggregate;
        TraceDataSource(DataSource actual) { super(actual); }
        @Override Connection wrap(Connection connection) { return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql) { statements.add(sql); if(sql.contains("FROM alarm_trace_entries")) historyIsolation.add(connection.getTransactionIsolation()); if(sql.contains("MIN(revision_after)") && onAggregate!=null) { var hook=onAggregate; onAggregate=null; hook.run(); } }
            try { return method.invoke(connection,args); } catch(InvocationTargetException e) { throw e.getCause(); }
        }); }
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser;
    @Autowired AlarmLifecycle lifecycle; @Autowired AlarmHistoryQueries histories; @Autowired AlarmReviewService reviews; @Autowired AuthService auth; @Autowired PermissionCatalog catalog; @Autowired TraceDataSource observed;
    @Autowired com.fireplatform.legacyingest.facts.LegacyFactApplier legacy;
    IngestionIntegrationTest f; TransactionTemplate tx;
    @BeforeEach void reset() throws Exception { f=new IngestionIntegrationTest(); f.mvc=mvc; f.json=json; f.jdbc=jdbc; f.bootstrap=bootstrap; f.parser=parser; f.reset(); jdbc.update("UPDATE numeric_settings SET setting_value=20,revision=0 WHERE setting_key='default_page_size'"); observed.statements.clear(); observed.historyIsolation.clear(); observed.onAggregate=null; tx=new TransactionTemplate(new DataSourceTransactionManager(observed)); }
    String path(String id) { return "/api/management/alarms/"+id; }
    String fire() { return tx.execute(status->{jdbc.queryForObject("SELECT id FROM devices WHERE id='device-a' FOR UPDATE",String.class);String id=lifecycle.fire("tenant-a","device-a","Test device","Test address",OffsetDateTime.now());jdbc.update("UPDATE devices SET physical_state='FIRE' WHERE id='device-a'");return id;}); }
    ResultActions review(String id,long revision,String remark,String key) throws Exception { var body=new HashMap<String,Object>(); body.put("expected_revision",revision); body.put("conclusion","TEST"); body.put("remark",remark); return f.call(HttpMethod.POST,path(id)+"/review",f.root,"tenant-a",key,body); }
    JsonNode current(String id) throws Exception { return f.body(f.call(HttpMethod.GET,path(id),f.root,"tenant-a",null,null).andExpect(status().isOk())); }
    JsonNode history(String id) throws Exception { return f.body(f.call(HttpMethod.GET,path(id)+"/history",f.root,"tenant-a",null,null).andExpect(status().isOk())); }
    boolean recover(String id) { return Boolean.TRUE.equals(tx.execute(status->{ jdbc.queryForObject("SELECT id FROM devices WHERE id='device-a' FOR UPDATE",String.class);boolean recovered=lifecycle.recoverAlarm("tenant-a","device-a",id,OffsetDateTime.now().plusMinutes(1));if(recovered)jdbc.update("UPDATE devices SET physical_state=CASE WHEN EXISTS(SELECT 1 FROM alarms WHERE tenant_id='tenant-a' AND device_id='device-a' AND physical_state='FIRE') THEN 'FIRE' ELSE 'NORMAL' END WHERE id='device-a'");return recovered; })); }
    void recoverDevice() { tx.executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM devices WHERE id='device-a' FOR UPDATE",String.class);lifecycle.recoverDevice("tenant-a","device-a");jdbc.update("UPDATE devices SET physical_state='NORMAL' WHERE id='device-a'");}); }
    long count(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries WHERE alarm_id=?",Long.class,id); }
    String customer() throws Exception { String token=f.login("customer-a",IngestionIntegrationTest.INITIAL,"customer"); f.call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent()); return f.login("customer-a",PASSWORD,"customer"); }
    @Test void legacyTimedRecoveryCanCloseAnUntimedFireWithoutClosingALaterKnownFire() throws Exception {
        var recoveryAt=OffsetDateTime.now().minusHours(1);
        String untimed=tx.execute(status->lifecycle.fire("tenant-a","device-a","Untimed device","Test address",null));
        String later=tx.execute(status->lifecycle.fire("tenant-a","device-a","Later fire","Test address",recoveryAt.plusMinutes(1)));
        review(untimed,0,"Recorded independently of source recovery","untimed-review").andExpect(status().isOk());
        String message=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,duplicate_count) VALUES (?,'tenant-a','HTTP_NOTIFY','aep_turbo',?,'vendor','device-a','{}',?,2,CURRENT_TIMESTAMP,?,'NORMALIZED',0)",message,message,"0".repeat(64),recoveryAt);
        var event=new com.fireplatform.legacyingest.LegacyNormalizedEvent("tenant-a","device-a","vendor","aep_turbo","RECOVERY",recoveryAt,null,false,List.of(new com.fireplatform.legacyingest.LegacyNormalizedEvent.LegacyEvent("RECOVERY","RECOVERY","{}",recoveryAt)));
        legacy.apply(message,event);
        legacy.apply(message,event);
        assertThat(current(untimed).path("physical_state").asText()).isEqualTo("NORMAL");
        assertThat(current(untimed).path("response_state").asText()).isEqualTo("HANDLED");
        assertThat(current(later).path("physical_state").asText()).isEqualTo("FIRE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries WHERE alarm_id=? AND action='ALARM_RECOVERED'",Long.class,untimed)).isEqualTo(1L);
    }
    @Test void freshFireReviewRecoveryAndReReviewKeepFourExactTransitionsAndOldReplaySnapshot() throws Exception {
        String id=fire(); String snapshotName=current(id).get("device_name").asText(); jdbc.update("UPDATE devices SET name='changed device',address='changed address' WHERE id='device-a'"); jdbc.update("UPDATE app_users SET display_name='Actor at review' WHERE id='bootstrap-admin'");
        var reviewed=f.body(review(id,0,"First review <&>\nplain text","first-review").andExpect(status().isOk())); assertThat(reviewed.get("physical_state").asText()).isEqualTo("FIRE"); assertThat(reviewed.get("response_state").asText()).isEqualTo("HANDLED");
        assertThat(recover(id)).isTrue(); jdbc.update("UPDATE app_users SET display_name='Later renamed actor' WHERE id='bootstrap-admin'"); var replay=f.body(review(id,0,"First review <&>\nplain text","first-review").andExpect(status().isOk()));
        assertThat(replay.path("id").asText()).isEqualTo(id);
        assertThat(replay.path("physical_state").asText()).isEqualTo("FIRE");
        assertThat(replay.path("response_state").asText()).isEqualTo("HANDLED");
        assertThat(replay.path("revision").asLong()).isEqualTo(1);
        assertThat(OffsetDateTime.parse(replay.path("occurred_at").asText()).toInstant()).isEqualTo(OffsetDateTime.parse(reviewed.path("occurred_at").asText()).toInstant());
        assertThat(current(id).get("physical_state").asText()).isEqualTo("NORMAL"); assertThat(count(id)).isEqualTo(3);
        review(id,2,"","re-review").andExpect(status().isOk()).andExpect(jsonPath("$.physical_state").value("NORMAL")).andExpect(jsonPath("$.revision").value(3)); var h=history(id); assertThat(h.at("/coverage/kind").asText()).isEqualTo("FROM_CREATION"); assertThat(h.at("/coverage/first_revision").asLong()).isZero(); assertThat(h.at("/coverage/journal_through_revision").asLong()).isEqualTo(3); assertThat(h.at("/coverage/current_revision").asLong()).isEqualTo(3); assertThat(h.get("total").asLong()).isEqualTo(4);
        assertThat(h.at("/items/0/action").asText()).isEqualTo("REVIEWED"); assertThat(h.at("/items/0/after/physical_state").asText()).isEqualTo("NORMAL"); assertThat(h.at("/items/1/action").asText()).isEqualTo("ALARM_RECOVERED"); assertThat(h.at("/items/1/before/response_state").asText()).isEqualTo("HANDLED"); assertThat(h.at("/items/1/after/response_state").asText()).isEqualTo("HANDLED"); assertThat(h.at("/items/1/actor_kind").asText()).isEqualTo("SYSTEM"); assertThat(h.at("/items/1/actor_id").isNull()).isTrue(); assertThat(h.at("/items/1/occurred_at").isTextual()).isTrue(); assertThat(h.at("/items/2/actor_name").asText()).isEqualTo("Actor at review"); assertThat(h.at("/items/2/remark").asText()).isEqualTo("First review <&>\nplain text"); assertThat(h.at("/items/3/action").asText()).isEqualTo("FIRE_CREATED"); assertThat(h.at("/items/3/revision_before").isNull()).isTrue(); assertThat(h.at("/items/3/before").isNull()).isTrue();
        assertThat(current(id).get("device_name").asText()).isEqualTo(snapshotName); assertThat(current(id).has("remark")).isFalse(); assertThat(current(id).has("actor_id")).isFalse();
    }
    @Test void legacyDeviceRecoveryLocksInIdOrderRecordsEachActualFireAndNeverRehandlesOrDuplicatesNormal() throws Exception {
        String a=fire(),b=fire(),c=fire(); review(a,0,"handled","review-a").andExpect(status().isOk()); assertThat(recover(c)).isTrue(); observed.statements.clear(); recoverDevice();
        for(String id:List.of(a,b)) { var h=history(id); assertThat(h.at("/items/0/action").asText()).isEqualTo("DEVICE_RECOVERED"); assertThat(h.at("/items/0/occurred_at").isNull()).isTrue(); assertThat(h.at("/items/0/actor_kind").asText()).isEqualTo("SYSTEM"); assertThat(h.at("/items/0/remark").isNull()).isTrue(); assertThat(h.at("/coverage/kind").asText()).isEqualTo("FROM_CREATION"); }
        assertThat(current(a).get("response_state").asText()).isEqualTo("HANDLED"); assertThat(current(b).get("response_state").asText()).isEqualTo("UNHANDLED"); assertThat(count(c)).isEqualTo(2); long total=jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries",Long.class); recoverDevice(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries",Long.class)).isEqualTo(total);
        assertThat(observed.statements.stream().anyMatch(s->s.contains("FROM alarms") && s.contains("ORDER BY id FOR UPDATE"))).isTrue();
    }
    @Test void historyIsAnIndependentPermissionWhileCurrentAndLegacyReviewNeedNoHistoryOrDirectoryRights() throws Exception {
        String id=fire(); jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission IN ('alarm:history','device:read','products:read','messages:raw')"); f.call(HttpMethod.GET,path(id)+"/history",f.root,"tenant-a",null,null).andExpect(status().isForbidden()); f.call(HttpMethod.GET,path(id),f.root,"tenant-a",null,null).andExpect(status().isOk()); review(id,0,null,"no-history-review").andExpect(status().isOk());
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('bootstrap-admin','alarm:history')"); jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='alarm:read'"); f.call(HttpMethod.GET,path(id)+"/history",f.root,"tenant-a",null,null).andExpect(status().isForbidden()); f.call(HttpMethod.GET,path(id),f.root,"tenant-a",null,null).andExpect(status().isForbidden()); review(id,1,"still legacy review","review-only").andExpect(status().isOk());
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('bootstrap-admin','alarm:read')"); f.call(HttpMethod.GET,path(id),f.root,"tenant-b",null,null).andExpect(status().isNotFound()); f.call(HttpMethod.GET,path(id)+"/history",f.root,"tenant-b",null,null).andExpect(status().isNotFound()); f.call(HttpMethod.GET,path("missing"),f.root,"tenant-a",null,null).andExpect(status().isNotFound());
        var actor=auth.authenticate(f.root); assertThat(catalog.validate("CUSTOMER",List.of("alarm:history"),actor.permissions())).containsExactly("alarm:history"); assertThat(catalog.forIdentity(actor).items().stream().filter(i->i.key().equals("alarm:history")).findFirst().orElseThrow().userTypes()).containsExactly("PLATFORM","CUSTOMER");
    }
    @Test void customerProjectionCompletelyOmitsIdentityKeysAndUsesOnlyConservativeLabels() throws Exception {
        String id=fire(); jdbc.update("UPDATE app_users SET display_name='Private name email@example.test +86123456' WHERE id='bootstrap-admin'"); review(id,0,"Platform business note","platform-review").andExpect(status().isOk()); String customer=customer(); String customerId=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        f.call(HttpMethod.GET,"/api/customer/alarms/"+id,customer,null,null,null).andExpect(status().isOk()); f.call(HttpMethod.GET,"/api/customer/alarms/"+id+"/history",customer,null,null,null).andExpect(status().isForbidden()); jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,'alarm:history')",customerId); jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission='device:read'",customerId);
        f.call(HttpMethod.POST,"/api/customer/alarms/"+id+"/review",customer,null,"customer-review",Map.of("expected_revision",1,"conclusion","FALSE_ALARM","remark","Customer note")).andExpect(status().isOk()); var h=f.body(f.call(HttpMethod.GET,"/api/customer/alarms/"+id+"/history",customer,null,null,null).andExpect(status().isOk())); assertThat(h.at("/items/0/actor_label").asText()).isEqualTo("本人"); assertThat(h.at("/items/0/is_self").asBoolean()).isTrue(); assertThat(h.at("/items/1/actor_label").asText()).isEqualTo("平台人员"); assertThat(h.at("/items/2/actor_label").asText()).isEqualTo("系统处理");
        for(var entry:h.get("items")) { assertThat(entry.has("actor_id")).isFalse(); assertThat(entry.has("actor_name")).isFalse(); } assertThat(h.toString()).doesNotContain("email@example").doesNotContain("Private name").doesNotContain("+86123456").doesNotContain("bootstrap-admin").doesNotContain("raw_event").doesNotContain("payload").doesNotContain("request_id").doesNotContain("lease");
        f.call(HttpMethod.GET,"/api/customer/alarms/"+id+"/history",customer,"tenant-a",null,null).andExpect(status().isForbidden()); f.call(HttpMethod.GET,"/api/customer/alarms/"+id,customer,"tenant-a",null,null).andExpect(status().isForbidden()); jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission='alarm:history'",customerId); f.call(HttpMethod.GET,"/api/customer/alarms/"+id+"/history",customer,null,null,null).andExpect(status().isForbidden()); f.call(HttpMethod.GET,"/api/customer/alarms/"+id,customer,null,null,null).andExpect(status().isOk());
    }
    @Test void reviewUsesCurrentLockedActorNotStaleRequestIdentityAndHistoryNeverChangesAfterRename() throws Exception {
        String id=fire(); var stale=auth.authenticate(f.root); jdbc.update("UPDATE app_users SET display_name='Current locked snapshot' WHERE id='bootstrap-admin'"); reviews.review(stale,"tenant-a",id,"stale-identity",new AlarmReviewService.ReviewRequest(0L,"TEST","captured current actor"),"synthetic-request"); jdbc.update("UPDATE app_users SET display_name='Renamed after commit' WHERE id='bootstrap-admin'"); var h=history(id); assertThat(h.at("/items/0/actor_name").asText()).isEqualTo("Current locked snapshot"); assertThat(h.at("/items/0/actor_label").asText()).isEqualTo("Current locked snapshot"); assertThat(h.at("/items/0/is_self").asBoolean()).isTrue();
    }
    @Test void coverageUsesWholeJournalAndReportsGapsUncoveredTailEmptyAndRevisionZeroBaselineHonestly() throws Exception {
        String id=fire(); review(id,0,null,"one").andExpect(status().isOk()); review(id,1,null,"two").andExpect(status().isOk()); jdbc.update("DELETE FROM alarm_trace_entries WHERE alarm_id=? AND revision_after=1",id); var gap=f.body(f.call(HttpMethod.GET,path(id)+"/history?page=1&size=1",f.root,"tenant-a",null,null).andExpect(status().isOk())); assertThat(gap.get("items").size()).isEqualTo(1); assertThat(gap.at("/coverage/entry_total").asLong()).isEqualTo(2); assertThat(gap.at("/coverage/kind").asText()).isEqualTo("PARTIAL"); jdbc.update("DELETE FROM alarm_trace_entries WHERE alarm_id=? AND revision_after=2",id); assertThat(history(id).at("/coverage/kind").asText()).isEqualTo("PARTIAL"); jdbc.update("DELETE FROM alarm_trace_entries WHERE alarm_id=?",id); var empty=history(id); assertThat(empty.at("/coverage/kind").asText()).isEqualTo("PARTIAL"); assertThat(empty.at("/coverage/first_revision").isNull()).isTrue(); assertThat(empty.at("/coverage/journal_through_revision").isNull()).isTrue();
        String legacy=fire(); jdbc.update("DELETE FROM alarm_trace_entries WHERE alarm_id=?",legacy); jdbc.update("INSERT INTO alarm_trace_entries(id,tenant_id,alarm_id,action,revision_after,after_physical_state,after_response_state,after_conclusion,recorded_at,actor_kind,remark,origin) SELECT 'synthetic-baseline-' || id,tenant_id,id,'MIGRATION_BASELINE',revision,physical_state,response_state,conclusion,CURRENT_TIMESTAMP,'LEGACY_UNKNOWN','Only migration last note','MIGRATION_SNAPSHOT' FROM alarms WHERE id=?",legacy); var baseline=history(legacy); assertThat(baseline.at("/coverage/first_revision").asLong()).isZero(); assertThat(baseline.at("/coverage/kind").asText()).isEqualTo("SINCE_MIGRATION"); assertThat(baseline.at("/coverage/baseline_recorded_at").isTextual()).isTrue(); assertThat(baseline.at("/items/0/actor_label").asText()).isEqualTo("历史未知"); assertThat(baseline.at("/items/0/occurred_at").isNull()).isTrue(); review(legacy,0,"real later note","after-baseline").andExpect(status().isOk()); assertThat(history(legacy).at("/coverage/kind").asText()).isEqualTo("SINCE_MIGRATION");
    }
    @Test void traceAndAuditFaultsRollbackAlarmAndHistoryWhileReviewCanRetry() throws Exception {
        var device=jdbc.queryForMap("SELECT * FROM devices WHERE id='device-a'"); jdbc.execute("ALTER TABLE alarm_trace_entries ADD CONSTRAINT deny_fire_trace CHECK(action <> 'FIRE_CREATED')"); try { assertThatThrownBy(this::fire).isInstanceOf(RuntimeException.class); assertThat(jdbc.queryForMap("SELECT * FROM devices WHERE id='device-a'")).isEqualTo(device); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries",Long.class)).isZero(); } finally { jdbc.execute("ALTER TABLE alarm_trace_entries DROP CONSTRAINT deny_fire_trace"); }
        String id=fire(); jdbc.execute("ALTER TABLE alarm_trace_entries ADD CONSTRAINT deny_review_trace CHECK(action <> 'REVIEWED')"); try { review(id,0,"retry after trace fault","fault-review").andExpect(status().isServiceUnavailable()); assertThat(current(id).get("revision").asLong()).isZero(); assertThat(current(id).get("response_state").asText()).isEqualTo("UNHANDLED"); assertThat(count(id)).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation='review'",Long.class)).isZero(); } finally { jdbc.execute("ALTER TABLE alarm_trace_entries DROP CONSTRAINT deny_review_trace"); }
        review(id,0,"retry after trace fault","fault-review").andExpect(status().isOk()); String second=fire(); jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT deny_trace_audit CHECK(action <> 'REVIEW_ALARM' OR target_id <> '"+second+"')"); try { review(second,0,"retry after audit fault","fault-audit").andExpect(status().isServiceUnavailable()); assertThat(current(second).get("revision").asLong()).isZero(); assertThat(count(second)).isEqualTo(1); } finally { jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT deny_trace_audit"); } review(second,0,"retry after audit fault","fault-audit").andExpect(status().isOk()); assertThat(count(second)).isEqualTo(2);
    }
    @Test void nullEmptyAndBoundedRemarksRemainCompatibleWhileInvalidUnicodeAndNulNeverPersist() throws Exception {
        String id=fire(); review(id,0,null,"null-note").andExpect(status().isOk()); review(id,1,"","empty-note").andExpect(status().isOk()); review(id,2,"x".repeat(2000),"max-note").andExpect(status().isOk()); review(id,3,"x".repeat(2001),"long-note").andExpect(status().isBadRequest()); review(id,3,"bad\u0000note","nul-note").andExpect(status().isBadRequest());
        mvc.perform(post(path(id)+"/review").header("Authorization","Bearer "+f.root).header("X-Tenant-Id","tenant-a").header("Idempotency-Key","surrogate-note").contentType("application/json").content("{\"expected_revision\":3,\"conclusion\":\"TEST\",\"remark\":\"\\uD800\"}")).andExpect(status().isBadRequest()); assertThat(count(id)).isEqualTo(4); assertThat(current(id).get("revision").asLong()).isEqualTo(3);
    }
    @Test void concurrentReviewAndRecoveryProduceOnlyCommittedRevisionTransitions() throws Exception {
        String id=fire(); try(var pool=Executors.newFixedThreadPool(2)) { var tasks=pool.invokeAll(List.<Callable<Object>>of(()->review(id,0,"racing review","race-review").andReturn().getResponse().getStatus(),()->recover(id)),10,TimeUnit.SECONDS); for(var task:tasks) { assertThat(task.isCancelled()).isFalse(); task.get(); } }
        var alarm=current(id); assertThat(alarm.get("physical_state").asText()).isEqualTo("NORMAL"); var h=history(id); long rev=alarm.get("revision").asLong(); assertThat(h.get("total").asLong()).isEqualTo(rev+1); assertThat(h.at("/coverage/kind").asText()).isEqualTo("FROM_CREATION"); assertThat(h.at("/items/0/after/physical_state").asText()).isEqualTo("NORMAL"); long before=count(id); assertThat(recover(id)).isFalse(); assertThat(count(id)).isEqualTo(before);
        review(id,rev,"after recovery","after-race").andExpect(status().isOk());
        var after=current(id); assertThat(after.get("physical_state").asText()).isEqualTo("NORMAL"); assertThat(after.get("response_state").asText()).isEqualTo("HANDLED"); assertThat(after.get("revision").asLong()).isEqualTo(rev+1); assertThat(history(id).get("total").asLong()).isEqualTo(rev+2);
    }
    @Test void historyReadsUseRepeatableSnapshotAggregateAndBoundedPageWithoutEffectsOrFullJournalScan() throws Exception {
        String id=fire(); for(int i=0;i<35;i++) review(id,i,"page note "+i,"page-"+i).andExpect(status().isOk()); var before=jdbc.queryForMap("SELECT * FROM alarms WHERE id=?",id); var devices=jdbc.queryForList("SELECT * FROM devices ORDER BY id"); observed.statements.clear(); observed.historyIsolation.clear(); var small=histories.history("bootstrap-admin", "tenant-a", id,1,1,"bootstrap-admin",true); int queries=observed.statements.size(); observed.statements.clear(); var large=histories.history("bootstrap-admin", "tenant-a", id,1,100,"bootstrap-admin",true); assertThat(large.items()).hasSize(36); assertThat(queries).isEqualTo(4); assertThat(observed.statements).hasSize(queries); assertThat(observed.statements.stream().filter(s->s.contains("MIN(revision_after)")).count()).isEqualTo(1); assertThat(observed.statements.stream().filter(s->s.startsWith("SELECT * FROM alarm_trace_entries")).allMatch(s->s.contains("LIMIT ? OFFSET ?"))).isTrue(); assertThat(observed.historyIsolation).allMatch(level->level==Connection.TRANSACTION_REPEATABLE_READ); assertThat(small.coverage()).isEqualTo(large.coverage());
        var readStarted=new CountDownLatch(1); var writerCommitted=new CountDownLatch(1); observed.onAggregate=()->{ readStarted.countDown(); try { if(!writerCommitted.await(10,TimeUnit.SECONDS)) throw new AssertionError("Writer did not commit during held history read"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } };
        try(var pool=Executors.newSingleThreadExecutor()) { var reading=pool.submit(()->histories.history("bootstrap-admin", "tenant-a", id,1,1,"bootstrap-admin",true)); assertThat(readStarted.await(10,TimeUnit.SECONDS)).isTrue(); try { review(id,35,"concurrent commit","snapshot-writer").andExpect(status().isOk()); } finally { writerCommitted.countDown(); } var snapshot=reading.get(10,TimeUnit.SECONDS); assertThat(snapshot.coverage().currentRevision()).isEqualTo(35); assertThat(snapshot.total()).isEqualTo(36); assertThat(snapshot.items().getFirst().get("revision_after")).isEqualTo(35L); }
        assertThat(history(id).at("/coverage/current_revision").asLong()).isEqualTo(36); assertThat(jdbc.queryForList("SELECT * FROM devices ORDER BY id")).isEqualTo(devices); assertThat(before.get("physical_state")).isEqualTo(current(id).get("physical_state").asText());
    }
}
