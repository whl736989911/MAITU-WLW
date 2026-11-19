package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionIngestion;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"app.roles=all","fire.hikvision.enabled=false","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:hikvision-vendor-registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class HikvisionVendorDeviceCreationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired HikvisionIngestion ingestion;
    IngestionIntegrationTest fixture;
    String serial, external;

    @BeforeEach void setup() throws Exception {
        fixture=new IngestionIntegrationTest();fixture.mvc=mvc;fixture.json=json;fixture.jdbc=jdbc;fixture.bootstrap=bootstrap;fixture.parser=parser;fixture.reset();
        serial="hik-"+UUID.randomUUID();external="cloud-"+UUID.randomUUID();
        ingestion.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of("deviceID",external,"deviceSerial",serial,"deviceName","Discovered fixture","deviceType","600002","deviceModel","800235"))),OffsetDateTime.now().minusMinutes(1));
        ingestion.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of("deviceID","legacy-old-source","deviceSerial","old-source-serial","deviceName","Old fixture","deviceModel","800235"))),OffsetDateTime.now().minusMinutes(2));
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id='device-a' WHERE external_id='legacy-old-source'");
    }
    @Test void postCreatesAndBindsWithoutProductVersionThenIngestsOnlyForNewDevice() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE status='PUBLISHED'",Long.class)).isZero();
        Map<String,Object> payload=Map.of("source","HIKVISION_FIRECLOUD","name","Local vendor asset","serial",serial,"place_id",fixture.placeId,"address","Building A");
        String key="create-"+UUID.randomUUID();
        JsonNode created=fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",key,payload).andExpect(status().isOk()));
        String id=created.get("id").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT product_version_id FROM devices WHERE id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT local_device_id FROM hikvision_discovered_devices WHERE external_id=?",String.class,external)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_source_bindings WHERE tenant_id=? AND device_id=? AND driver_key='HIKVISION_FIRECLOUD' AND source_external_id=? AND source_serial=? AND state='ACTIVE'",Long.class,"tenant-a",id,external,serial)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=? AND action='REGISTER'",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,id)).isEqualTo("PENDING_INTEGRATION");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,id)).isEqualTo("UNKNOWN");
        JsonNode repeated=fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",key,payload).andExpect(status().isOk()));
        assertThat(repeated.get("id").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isEqualTo(1);

        OffsetDateTime observed=OffsetDateTime.now().plusSeconds(2);
        ingestion.accept(HikvisionClient.Stream.MONITOR,json.valueToTree(List.of(Map.of("resourceID",external,"monitorTime",observed.toString(),"data",List.of(Map.of("monitorType","400006","monitorValue","24.4","monitorUnit","℃","circuitNo","0"))))),observed);
        ingestion.accept(HikvisionClient.Stream.ALARM,json.valueToTree(List.of(Map.of("alarmID","alarm-"+external,"resourceID",external,"alarmType","200001","alarmStartTime",observed.toString(),"alarmState","0"))),observed);
        String monitorParams=jdbc.queryForObject("SELECT params_json FROM legacy_ingest_events WHERE device_id=? AND kind='OTHER' AND method='HIKVISION_CURRENT_MONITOR'",String.class,id);
        assertThat(monitorParams).contains("24.4");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id='device-a'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND physical_state='FIRE'",Long.class,id)).isEqualTo(1L);
        ingestion.accept(HikvisionClient.Stream.ALARM,json.valueToTree(List.of(Map.of("alarmID","alarm-"+external,"resourceID",external,"alarmType","200001","alarmStartTime",observed.toString(),"alarmStopTime",observed.plusSeconds(5).toString(),"alarmState","1"))),observed.plusSeconds(5));
        assertThat(jdbc.queryForObject("SELECT physical_state FROM alarms WHERE device_id=? AND id=(SELECT local_alarm_id FROM hikvision_alarm_state WHERE source_alarm_id=?)",String.class,id,"alarm-"+external)).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries t JOIN alarms a ON a.id=t.alarm_id WHERE a.device_id=? AND t.action='ALARM_RECOVERED'",Long.class,id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE external_id='legacy-old-source' AND local_device_id='device-a'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id='device-a'",Long.class)).isZero();
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a","conflict-"+UUID.randomUUID(),payload).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isEqualTo(1);
    }
}
