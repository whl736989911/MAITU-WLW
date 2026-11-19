package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.hikvision.*;
import com.fireplatform.identity.AuthService;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"app.roles=all","app.alarm-change-read-enabled=true","app.alarm-change-publish-enabled=true","app.alarm-change-poll-ms=60000","app.hikvision-callback-initial-delay-ms=60000","app.hikvision-callback-poll-ms=60000","fire.hikvision.enabled=false","app.hikvision-config-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","fire.hikvision.app-key=realtime-fixture-key","fire.hikvision.app-secret=realtime-fixture-secret","spring.datasource.url=jdbc:h2:mem:hikvision-realtime-commit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class HikvisionRealtimeCommitIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){IngestionIntegrationTest.environment(registry);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired AuthService auth;
    @Autowired HikvisionConfigService configuration;
    @Autowired HikvisionCallbackService callbacks;
    @Autowired HikvisionIngestion ingestion;
    @Autowired TransactionTemplate transactions;
    IngestionIntegrationTest f;

    @BeforeEach void setup() throws Exception {
        f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();
    }
    @Test void rollbackDoesNotDispatchAndCommittedHttpReceiptPublishesWithoutWorkerTick() throws Exception {
        var actor=auth.authenticate(f.root);
        configuration.update(actor,UUID.randomUUID().toString(),new HikvisionConfigInputs.Write(0L,"https://open.hikfirecloud.com","realtime-fixture-key","realtime-fixture-secret",false,30,"800235"),"realtime-fixture");
        String serial="realtime-"+UUID.randomUUID(),external="source-"+UUID.randomUUID();
        ingestion.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of("deviceID",external,"deviceSerial",serial,"deviceName","Realtime smoke","deviceType","600002","deviceModel","800235"))),OffsetDateTime.now());
        String device=f.body(f.call(HttpMethod.POST,"/api/management/vendor-devices",f.root,"tenant-a",UUID.randomUUID().toString(),Map.of("source","HIKVISION_FIRECLOUD","name","Realtime smoke","serial",serial,"place_id",f.placeId)).andExpect(status().isOk())).path("id").asText();
        String credential=UUID.randomUUID().toString().replace("-","").repeat(2);
        callbacks.update(actor,UUID.randomUUID().toString(),new HikvisionCallbackService.Write(0L,true,"https://receiver.example.com",credential,List.of("980002","980016")),"fixture-receiver");
        String sourceAlarm="alarm-"+UUID.randomUUID();
        byte[] payload=json.writeValueAsBytes(Map.of("fps",Map.of("msgList",List.of(Map.of("msgType","980002","body",Map.of("dataType","980002101","data",List.of(Map.of("alarmID",sourceAlarm,"resourceID",external,"alarmType","200001","alarmStartTime",OffsetDateTime.now().toString(),"alarmState","0"))))))));
        transactions.executeWithoutResult(status->{callbacks.receive(credential,payload);status.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isZero();
        long started=System.nanoTime();
        mvc.perform(post("/api/hooks/hikvision/"+credential).contentType("application/json").content(payload)).andExpect(status().isOk());
        long deadline=started+TimeUnit.SECONDS.toNanos(2);
        while(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_outbox o JOIN alarm_trace_entries t ON t.id=o.id JOIN alarms a ON a.id=t.alarm_id WHERE a.device_id=? AND o.publication_sequence IS NOT NULL",Long.class,device)==0&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND physical_state='FIRE'",Long.class,device)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_outbox o JOIN alarm_trace_entries t ON t.id=o.id JOIN alarms a ON a.id=t.alarm_id WHERE a.device_id=? AND o.publication_sequence IS NOT NULL",Long.class,device)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM hikvision_callback_receipts",String.class)).isEqualTo("PROCESSED");
        mvc.perform(post("/api/hooks/hikvision/"+credential).contentType("application/json").content(payload)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT duplicate_count FROM hikvision_callback_receipts",Long.class)).isEqualTo(1);
    }
}
