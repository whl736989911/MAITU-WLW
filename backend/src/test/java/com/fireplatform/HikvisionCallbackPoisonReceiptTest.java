package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.hikvision.HikvisionCallbackService;
import com.fireplatform.hikvision.HikvisionCallbackWorker;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionConfigInputs;
import com.fireplatform.hikvision.HikvisionConfigSecrets;
import com.fireplatform.hikvision.HikvisionConfigService;
import com.fireplatform.hikvision.HikvisionIngestion;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest(properties={
    "app.roles=api",
    "app.hikvision-config-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "fire.hikvision.enabled=false",
    "fire.hikvision.app-key=callback-env-key",
    "fire.hikvision.app-secret=callback-env-secret",
    "spring.datasource.url=jdbc:h2:mem:hikvisionpoison;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class HikvisionCallbackPoisonReceiptTest {
    @Autowired HikvisionCallbackService callbacks;
    @Autowired HikvisionConfigService configuration;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired HikvisionIngestion ingestion;
    @Autowired HikvisionConfigSecrets secrets;
    @Autowired Clock clock;
    @Autowired TransactionTemplate transactions;
    @Autowired CoordinationFence coordination;
    @Autowired @Qualifier("hikvisionCallbackScheduler") ThreadPoolTaskScheduler callbackScheduler;
    @MockitoBean ManagementGuard guard;
    private AuthService.Identity actor;
    private String credential;

    @BeforeEach void setUp(){
        doNothing().when(guard).begin(any(),any(),any(),any());
        jdbc.update("DELETE FROM hikvision_callback_receipts");
        jdbc.update("DELETE FROM hikvision_callback_config");
        jdbc.update("DELETE FROM hikvision_config");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES(?,?,?,'PLATFORM',?,FALSE,TRUE)",id,id,"Poison fixture owner","unused-poison-fixture");
        actor=new AuthService.Identity(new AuthService.UserView(id,id,"Poison fixture owner","PLATFORM",null,false),List.of("integrations:write","integrations:read"),"management","fixture-token");
        configuration.update(actor,"poison-hikvision-"+id,new HikvisionConfigInputs.Write(0L,"https://open.hikfirecloud.com","callback-env-key","callback-env-secret",false,30,"800235",null),"request-poison-config");
        credential="a".repeat(64);
        callbacks.update(actor,"poison-enable-"+id,
            new HikvisionCallbackService.Write(0L,true,"https://receiver.example.com",credential,List.of("980004","980016")),"request-poison-enable");
    }

    @Test void validDeviceEventReceiptReachesProcessedWithoutAnyPoison(){
        String[] device=linkedDevice("control");
        callbacks.receive(credential,deviceEventBody(device[1],"control-event").getBytes(StandardCharsets.UTF_8));
        String receipt=onlyReceipt("980016");
        var worker=worker();
        worker.tick();
        worker.tick();
        assertThat(jdbc.queryForObject("SELECT state FROM hikvision_callback_receipts WHERE id=?",String.class,receipt))
            .as("a benign DEVICE_EVENT receipt must complete without any poison receipt in front of it")
            .isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND method='HIKVISION_DEVICE_EVENT'",Long.class,device[0]))
            .isEqualTo(1L);
    }

    @Test void deterministicPoisonReceiptMustNotStarveTheLaterLegitimateReceipt(){
        String[] poisonDevice=linkedDevice("poison");
        String[] laterDevice=linkedDevice("later");
        String longFaultId="F".repeat(129);
        String poison=poisonFaultBody(poisonDevice[1],longFaultId);
        String later=deviceEventBody(laterDevice[1],"later-event");
        callbacks.receive(credential,poison.getBytes(StandardCharsets.UTF_8));
        callbacks.receive(credential,later.getBytes(StandardCharsets.UTF_8));
        String poisonId=onlyReceipt("980004");String laterId=onlyReceipt("980016");
        // Fixture-internal ordering control only: the poison genuinely arrived first, independent of clock granularity.
        jdbc.update("UPDATE hikvision_callback_receipts SET received_at=? WHERE id=?",OffsetDateTime.now(clock).minusMinutes(5),poisonId);
        jdbc.update("UPDATE hikvision_callback_receipts SET received_at=? WHERE id=?",OffsetDateTime.now(clock),laterId);

        // Five bounded ticks: enough for any fix that bounds or terminates repeated attempts, while the
        // unfixed worker reselects the same oldest PENDING receipt on every single tick.
        var worker=worker();
        for(int tick=1;tick<=5;tick++)worker.tick();

        String poisonState=jdbc.queryForObject("SELECT state FROM hikvision_callback_receipts WHERE id=?",String.class,poisonId);
        String laterState=jdbc.queryForObject("SELECT state FROM hikvision_callback_receipts WHERE id=?",String.class,laterId);
        String poisonError=jdbc.queryForObject("SELECT error_code FROM hikvision_callback_receipts WHERE id=?",String.class,poisonId);
        String laterError=jdbc.queryForObject("SELECT error_code FROM hikvision_callback_receipts WHERE id=?",String.class,laterId);
        var rawErrors=jdbc.queryForList("SELECT apply_error FROM hikvision_raw_records WHERE external_key=?",String.class,longFaultId);
        String rawError=rawErrors.isEmpty()?null:rawErrors.getFirst();

        assertThat(laterState).as("poison=%s/%s later=%s/%s",poisonState,poisonError,laterState,laterError).isEqualTo("PROCESSED");
        assertThat(poisonState).as("a deterministic payload failure must not leave the receipt retried forever").isNotEqualTo("PENDING");
        assertThat(poisonError!=null||rawError!=null)
            .as("the rejected occurrence must stay explicit on the receipt (%s) or the raw record (%s)",poisonError,rawError)
            .isTrue();
        // The rejected payload must be retained as evidence and nothing may be half-applied from the rolled-back attempt.
        assertThat(jdbc.queryForObject("SELECT payload_json FROM hikvision_callback_receipts WHERE id=?",String.class,poisonId)).isEqualTo(poison);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_fault_state",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE external_key=? AND applied_at IS NOT NULL",Long.class,longFaultId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,poisonDevice[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,poisonDevice[0])).isEqualTo("LEGACY_UNVERIFIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND method='HIKVISION_DEVICE_EVENT'",Long.class,laterDevice[0])).isEqualTo(1L);
    }

    private String[] linkedDevice(String label){
        String tenant=UUID.randomUUID().toString(),device=UUID.randomUUID().toString(),source="source-"+UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)",tenant,"Poison fixture "+label);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES(?,?,?,?,?,?,'UNKNOWN',FALSE)",
            device,tenant,"Poison fixture "+label,device,"800235","");
        ingestion.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of(
            "deviceID",source,"deviceSerial","serial-"+label,"deviceName","Poison fixture "+label,"deviceModel","800235"))),OffsetDateTime.now(clock));
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=? WHERE external_id=?",device,source);
        return new String[]{device,source};
    }

    private String poisonFaultBody(String source,String faultId){
        return "{\"fps\":{\"msgList\":[{\"msgType\":\"980004\",\"body\":{\"dataType\":\"980004101\",\"data\":[{"+
            "\"faultID\":\""+faultId+"\",\"resourceID\":\""+source+"\",\"faultType\":\"300004\",\"faultState\":\"0\","+
            "\"faultTime\":\""+OffsetDateTime.now(clock)+"\"}]}}]}}";
    }

    private String deviceEventBody(String source,String eventId){
        return "{\"fps\":{\"msgList\":[{\"msgType\":\"980016\",\"body\":{\"dataType\":\"980016101\",\"data\":[{"+
            "\"eventID\":\""+eventId+"\",\"deviceID\":\""+source+"\",\"eventType\":\"100208\","+
            "\"happenTime\":\""+OffsetDateTime.now(clock)+"\"}]}}]}}";
    }

    private String onlyReceipt(String messageType){
        return jdbc.queryForObject("SELECT id FROM hikvision_callback_receipts WHERE message_types_json LIKE ?",String.class,"%\""+messageType+"\"%");
    }

    private HikvisionCallbackWorker worker(){
        var environment=new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("app.roles","all");
        environment.setProperty("spring.datasource.url","jdbc:h2:mem:hikvisionpoison");
        return new HikvisionCallbackWorker(jdbc,json,callbacks,configuration,secrets,ingestion,
            new RuntimeConfig(environment),clock,transactions,coordination,callbackScheduler);
    }
}
