package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import com.fireplatform.hikvision.HikvisionCallbackService;
import com.fireplatform.hikvision.HikvisionConfigInputs;
import com.fireplatform.hikvision.HikvisionConfigService;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import com.fireplatform.hikvision.HikvisionCallbackWorker;
import com.fireplatform.hikvision.HikvisionIngestion;
import com.fireplatform.hikvision.HikvisionConfigSecrets;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties={"app.roles=api","app.hikvision-config-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","fire.hikvision.enabled=false","fire.hikvision.app-key=callback-env-key","fire.hikvision.app-secret=callback-env-secret","spring.datasource.url=jdbc:h2:mem:hikvisioncallback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class HikvisionCallbackServiceTest {
    @Autowired HikvisionCallbackService callbacks;
    @Autowired HikvisionConfigService configuration;
    @Autowired JdbcTemplate jdbc;
    @MockBean ManagementGuard guard;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired HikvisionIngestion ingestion;
    @Autowired HikvisionConfigSecrets secrets;
    @Autowired java.time.Clock clock;
    @Autowired TransactionTemplate transactions;
    @Autowired com.fireplatform.identity.CoordinationFence coordination;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("hikvisionCallbackScheduler") org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler callbackScheduler;
    private AuthService.Identity actor;
    @BeforeEach void setUp(){
        doNothing().when(guard).begin(any(),any(),any(),any());
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES(?,?,?,'PLATFORM',?,FALSE,TRUE)",id,id,"Callback test","unused-callback-test");
        actor=new AuthService.Identity(new AuthService.UserView(id,id,"Callback test","PLATFORM",null,false),List.of("integrations:write","integrations:read"),"management","fixture-token");
    }
    @Test void durableReceiptAuthenticationDedupMaskingAndCas(){
        jdbc.update("DELETE FROM hikvision_callback_receipts");jdbc.update("DELETE FROM hikvision_callback_config");jdbc.update("DELETE FROM hikvision_config");
        configuration.update(actor,"callback-test-hikvision",new HikvisionConfigInputs.Write(0L,"https://open.hikfirecloud.com","","",false,30,"800235"),"request-config");
        String credential="a".repeat(64);var saved=callbacks.update(actor,"callback-test-enable",new HikvisionCallbackService.Write(0L,true,"https://receiver.example.com",credential,null),"request-enable");
        assertThat(saved.enabled()).isTrue();assertThat(saved.credential_configured()).isTrue();assertThat(saved.public_origin()).isEqualTo("https://receiver.example.com");
        assertThat(saved.worker_enabled()).isFalse();assertThat(callbacks.get().worker_enabled()).isFalse();
        assertThat(callbacks.get().toString()).doesNotContain(credential);
        assertThatThrownBy(()->callbacks.update(actor,"callback-test-stale",new HikvisionCallbackService.Write(0L,true,"https://receiver.example.com","b".repeat(64),null),"request-stale"))
            .isInstanceOf(com.fireplatform.shared.ApiError.class).satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(409));
        String body="{\"fps\":{\"msgList\":[{\"msgType\":\"999999\",\"body\":{\"dataType\":\"unknown\",\"data\":[{\"privateField\":\"retained-only\"}]}}]}}";
        byte[] raw=body.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->callbacks.receive("b".repeat(64),raw)).isInstanceOf(com.fireplatform.shared.ApiError.class).satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(404));
        assertThatThrownBy(()->callbacks.receive(credential,"{\"fps\":{\"msgList\":[]}}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(com.fireplatform.shared.ApiError.class).satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(400));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts",Long.class)).isZero();
        callbacks.receive(credential,raw);callbacks.receive(credential,raw);
        var receipt=jdbc.queryForMap("SELECT payload_json,payload_bytes,duplicate_count,state,unsupported_messages,submitted_records FROM hikvision_callback_receipts");
        assertThat(receipt.get("payload_json")).isEqualTo(body);assertThat(receipt.get("payload_bytes")).isEqualTo(raw.length);
        assertThat(receipt.get("duplicate_count")).isEqualTo(1L);assertThat(receipt.get("state")).isEqualTo("PENDING");assertThat(receipt.get("unsupported_messages")).isEqualTo(1);assertThat(receipt.get("submitted_records")).isEqualTo(0);
        var listed=callbacks.receipts(1,20).items().getFirst();assertThat(listed.message_types()).containsExactly("999999");assertThat(listed.error_code()).isNull();
        var workerEnvironment=new MockEnvironment();workerEnvironment.setActiveProfiles("test");workerEnvironment.setProperty("app.roles","all");workerEnvironment.setProperty("spring.datasource.url","jdbc:h2:mem:hikvisioncallback");
        var workerRuntime=new com.fireplatform.configuration.RuntimeConfig(workerEnvironment);
        new HikvisionCallbackWorker(jdbc,json,callbacks,configuration,secrets,ingestion,workerRuntime,clock,transactions,coordination,callbackScheduler).tick();
        assertThat(jdbc.queryForObject("SELECT state FROM hikvision_callback_receipts",String.class)).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE ingress='HIKVISION_PUSH'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_config WHERE credential_cipher LIKE ?",Long.class,"%"+credential+"%")).isZero();
        configuration.update(actor,"callback-test-account-switch",new HikvisionConfigInputs.Write(1L,"https://open.hikfirecloud.com","replacement-account-key","replacement-account-secret",false,30,"800235"),"request-account-switch");
        assertThatThrownBy(()->callbacks.update(actor,"callback-test-rebind-stale",new HikvisionCallbackService.Write(1L,true,"https://receiver.example.com","",null),"request-rebind-stale"))
            .isInstanceOf(com.fireplatform.shared.ApiError.class).satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(409));
        String replacementCredential="c".repeat(64);
        callbacks.update(actor,"callback-test-rebind-new",new HikvisionCallbackService.Write(1L,true,"https://receiver.example.com",replacementCredential,null),"request-rebind-new");
        assertThatThrownBy(()->callbacks.receive(credential,raw)).isInstanceOf(com.fireplatform.shared.ApiError.class).satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(404));
        callbacks.receive(replacementCredential,raw);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT account_fingerprint) FROM hikvision_callback_receipts",Long.class)).isEqualTo(2L);
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void mixedNativeEnvelopeRetainsExactNumericFieldsAndDoesNotTreatOtherStreamsAsApplied() throws Exception {
        jdbc.update("DELETE FROM hikvision_callback_receipts");jdbc.update("DELETE FROM hikvision_callback_config");jdbc.update("DELETE FROM hikvision_config");
        configuration.update(actor,UUID.randomUUID().toString(),new HikvisionConfigInputs.Write(0L,"https://open.hikfirecloud.com","callback-env-key","callback-env-secret",false,30,"800235"),"precision-config");
        String credential="d".repeat(64);
        callbacks.update(actor,UUID.randomUUID().toString(),new HikvisionCallbackService.Write(0L,true,"https://receiver.example.com",credential,null),"precision-enable");
        String tenant=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)",tenant,"Callback precision fixture");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES(?,?,?,?,?,?,'FIRE',FALSE)",device,tenant,"Callback fixture",device,"800235","");
        ingestion.accept(com.fireplatform.hikvision.HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(java.util.Map.of("deviceID","precision-source","deviceSerial","precision-fixture","deviceName","Callback fixture","deviceModel","800235"))),java.time.OffsetDateTime.now(clock));
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=? WHERE external_id='precision-source'",device);
        String raw="{\"fps\":{\"msgList\":[{\"msgType\":\"980016\",\"body\":{\"dataType\":\"980016101\",\"data\":[{\"eventID\":\"precision-event\",\"deviceID\":\"precision-source\",\"eventType\":\"100208\",\"happenTime\":\"2026-11-11T10:00:00+08:00\",\"opaqueValue\":0.12345678901234567890123456789}]}},{\"msgType\":\"980002\",\"body\":{\"dataType\":\"980002101\",\"data\":[{}]}},{\"msgType\":\"980016\",\"body\":{\"dataType\":\"980016101\",\"data\":\"invalid\"}}]}}";
        callbacks.receive(credential,raw.getBytes(StandardCharsets.UTF_8));
        var environment=new MockEnvironment();environment.setActiveProfiles("test");environment.setProperty("app.roles","all");environment.setProperty("spring.datasource.url","jdbc:h2:mem:hikvisioncallback");
        new HikvisionCallbackWorker(jdbc,json,callbacks,configuration,secrets,ingestion,new com.fireplatform.configuration.RuntimeConfig(environment),clock,transactions,coordination,callbackScheduler).tick();
        var receipt=callbacks.receipts(1,20).items().getFirst();
        assertThat(receipt.state()).isEqualTo("PARTIAL");assertThat(receipt.submitted_records()).isEqualTo(1);assertThat(receipt.unsupported_messages()).isEqualTo(1);
        String stored=jdbc.queryForObject("SELECT payload_json FROM hikvision_raw_records WHERE external_device_id='precision-source' AND stream_kind='DEVICE_EVENT'",String.class);
        com.fasterxml.jackson.databind.JsonNode exact=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(stored);
        assertThat(exact.path("opaqueValue").decimalValue()).isEqualByComparingTo("0.12345678901234567890123456789");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,device)).isEqualTo("FIRE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isZero();
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void newStreamsRequireOptInAndReceiptReparseKeepsOriginalBodyAndOriginalIdempotentResult() {
        jdbc.update("DELETE FROM hikvision_callback_receipts");jdbc.update("DELETE FROM hikvision_callback_config");jdbc.update("DELETE FROM hikvision_config");
        configuration.update(actor,UUID.randomUUID().toString(),new HikvisionConfigInputs.Write(0L,"https://open.hikfirecloud.com","callback-env-key","callback-env-secret",false,30,"800235"),"opt-in-config");
        String credential="e".repeat(64);
        var enabled=callbacks.update(actor,UUID.randomUUID().toString(),new HikvisionCallbackService.Write(0L,true,"https://receiver.example.com",credential,null),"native-default");
        assertThat(enabled.enabled_message_types()).containsExactly("980016");
        assertThat(enabled.supported_message_types()).containsExactly("980002","980004","980008","980016");
        String raw="{\"fps\":{\"msgList\":[{\"msgType\":\"980008\",\"body\":{\"dataType\":\"980008101\",\"data\":[{\"resourceID\":\"not-yet-discovered\",\"monitorType\":\"400006\",\"monitorValue\":\"47.25\",\"monitorUnit\":\"℃\",\"happenTime\":\"2026-11-11T10:00:00+08:00\"}]}}]}}";
        callbacks.receive(credential,raw.getBytes(StandardCharsets.UTF_8));
        var environment=new MockEnvironment();environment.setActiveProfiles("test");environment.setProperty("app.roles","all");environment.setProperty("spring.datasource.url","jdbc:h2:mem:hikvisioncallback");
        var worker=new HikvisionCallbackWorker(jdbc,json,callbacks,configuration,secrets,ingestion,new com.fireplatform.configuration.RuntimeConfig(environment),clock,transactions,coordination,callbackScheduler);
        worker.tick();
        var initial=callbacks.receipts(1,20).items().getFirst();
        assertThat(initial.state()).isEqualTo("PARTIAL");assertThat(initial.error_code()).isEqualTo("STREAM_NOT_ENABLED");
        assertThat(initial.submitted_records()).isZero();assertThat(initial.revision()).isEqualTo(1);
        callbacks.update(actor,UUID.randomUUID().toString(),new HikvisionCallbackService.Write(1L,true,"https://receiver.example.com","",List.of("980008","980016")),"monitor-opt-in");
        String key=UUID.randomUUID().toString();
        var input=new HikvisionCallbackService.Reparse(initial.revision());
        var queued=callbacks.reparse(actor,initial.id(),key,input,"queue-original");
        assertThat(queued.state()).isEqualTo("PENDING");assertThat(queued.reparse_count()).isEqualTo(1);assertThat(queued.revision()).isEqualTo(2);
        worker.tick();
        var current=callbacks.receipts(1,20).items().getFirst();
        assertThat(current.state()).isEqualTo("PARTIAL");assertThat(current.error_code()).isEqualTo("RESOURCE_NOT_DISCOVERED");assertThat(current.revision()).isEqualTo(3);
        var replay=callbacks.reparse(actor,initial.id(),key,input,"replay-original");
        assertThat(replay).isEqualTo(queued);
        assertThat(callbacks.receipts(1,20).items().getFirst().reparse_count()).isEqualTo(1);
        assertThatThrownBy(()->callbacks.reparse(actor,initial.id(),UUID.randomUUID().toString(),input,"stale-reparse"))
            .isInstanceOf(com.fireplatform.shared.ApiError.class).satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(409));
        callbacks.receive(credential,raw.getBytes(StandardCharsets.UTF_8));
        assertThat(callbacks.receipts(1,20).items().getFirst().revision()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM hikvision_callback_receipts WHERE id=?",String.class,initial.id())).isEqualTo(raw);
        jdbc.update("UPDATE hikvision_callback_receipts SET account_fingerprint=? WHERE id=?","f".repeat(64),initial.id());
        assertThatThrownBy(()->callbacks.reparse(actor,initial.id(),UUID.randomUUID().toString(),new HikvisionCallbackService.Reparse(current.revision()),"wrong-account"))
            .isInstanceOf(com.fireplatform.shared.ApiError.class).satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(409));
    }
}
