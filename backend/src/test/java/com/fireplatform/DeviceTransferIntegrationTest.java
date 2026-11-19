package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.simulation-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:devicetransfer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceTransferIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired com.fireplatform.hikvision.HikvisionIngestion hikvision;
    IngestionIntegrationTest fixture;

    @BeforeEach void reset() throws Exception {
        fixture=new IngestionIntegrationTest(); fixture.mvc=mvc; fixture.json=json; fixture.jdbc=jdbc; fixture.bootstrap=bootstrap; fixture.parser=parser; fixture.reset();
    }
    JsonNode createPlace(String tenant) throws Exception {
        return fixture.body(fixture.call(HttpMethod.POST,"/api/management/places",fixture.root,tenant,UUID.randomUUID().toString(),Map.of("name","Transfer destination "+UUID.randomUUID())).andExpect(status().isOk()));
    }
    JsonNode createDevice() throws Exception {
        var product=fixture.product(); var version=fixture.published(product.get("id").asText(),"1");
        var input=new HashMap<String,Object>(); input.put("name","Transfer source"); input.put("device_eui","original-"+UUID.randomUUID()); input.put("address","Synthetic lab"); input.put("place_id",fixture.placeId); input.put("product_version_id",version.get("id").asText());
        return fixture.body(fixture.call(HttpMethod.POST,"/api/management/devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isOk()));
    }
    Map<String,Object> transferInput(JsonNode source,JsonNode place) {
        return new HashMap<>(Map.of("target_tenant_id","tenant-b","target_place_id",place.get("id").asText(),"reason","Approved controlled unit transfer","expected_revision",source.get("revision").asLong()));
    }
    @Test void transferPreservesSourceHistoryAndKeepsSourceAndTargetTenantViewsIsolated() throws Exception {
        JsonNode source=createDevice(); JsonNode targetPlace=createPlace("tenant-b"); String oldEui=source.get("device_eui").asText();
        JsonNode result=fixture.body(fixture.call(HttpMethod.POST,"/api/management/devices/"+source.get("id").asText()+"/transfer",fixture.root,"tenant-a","transfer-preserve",transferInput(source,targetPlace)).andExpect(status().isOk()));
        String newId=result.at("/target/id").asText(); assertThat(newId).isNotBlank().isNotEqualTo(source.get("id").asText()); assertThat(result.at("/target/device_eui").asText()).isEqualTo(oldEui);
        assertThat(result.at("/target/physical_state").asText()).isEqualTo("UNKNOWN"); assertThat(result.at("/target/integration_state").asText()).isEqualTo("PENDING_INTEGRATION");
        assertThat(jdbc.queryForObject("SELECT device_eui FROM devices WHERE id=?",String.class,source.get("id").asText())).isEqualTo(oldEui);
        assertThat(jdbc.queryForObject("SELECT archived_at FROM devices WHERE id=?",java.time.OffsetDateTime.class,source.get("id").asText())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=? AND tenant_id='tenant-a'",Long.class,source.get("id").asText())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=? AND tenant_id='tenant-b'",Long.class,source.get("id").asText())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=? AND tenant_id='tenant-b'",Long.class,newId)).isEqualTo(1);
        JsonNode sourceList=fixture.body(fixture.call(HttpMethod.GET,"/api/management/devices?page=1&size=100",fixture.root,"tenant-a",null,null).andExpect(status().isOk())); assertThat(sourceList.at("/items").findValuesAsText("id")).doesNotContain(source.get("id").asText());
        fixture.call(HttpMethod.GET,"/api/management/devices/"+source.get("id").asText()+"/place-history",fixture.root,"tenant-b",null,null).andExpect(status().isNotFound());
        fixture.call(HttpMethod.GET,"/api/management/devices/"+newId+"/place-history",fixture.root,"tenant-b",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_transfers WHERE source_device_id=? AND target_device_id=?",Long.class,source.get("id").asText(),newId)).isEqualTo(1);
    }
    @Test void transferredHikvisionResourceDoesNotAttributeDelayedHistoryToTheNewUnit() throws Exception {
        JsonNode source=createDevice(),targetPlace=createPlace("tenant-b");
        String oldId=source.get("id").asText(),external="transfer-history-"+UUID.randomUUID();
        jdbc.update("UPDATE devices SET place_id=NULL WHERE id=?",oldId);
        var oldTime=java.time.OffsetDateTime.parse("2020-01-01T00:00:00Z");
        try {
            hikvision.accept(com.fireplatform.hikvision.HikvisionClient.Stream.DEVICE,json.valueToTree(java.util.List.of(Map.of("deviceID",external,"deviceSerial",external,"deviceName","Transfer event fixture","deviceModel","800235"))),oldTime,"800235");
            jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=? WHERE external_id=?",oldId,external);
            JsonNode result=fixture.body(fixture.call(HttpMethod.POST,"/api/management/devices/"+oldId+"/transfer",fixture.root,"tenant-a","transfer-event-history",transferInput(source,targetPlace)).andExpect(status().isOk()));
            String target=result.at("/target/id").asText();
            hikvision.accept(com.fireplatform.hikvision.HikvisionClient.Stream.DEVICE_EVENT,json.valueToTree(java.util.List.of(Map.of("eventID","delayed-before-transfer","deviceID",external,"eventType","100209","happenTime",oldTime.toString()))),java.time.OffsetDateTime.now(),"800235");
            fixture.call(HttpMethod.GET,"/api/management/devices/"+target+"/events?page=1",fixture.root,"tenant-b",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
            assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_device_id=? AND stream_kind='DEVICE_EVENT'",String.class,external)).isEqualTo("PRE_OWNERSHIP_HISTORY");
            var transferTime=jdbc.queryForObject("SELECT occurred_at FROM device_transfers WHERE target_device_id=?",java.time.OffsetDateTime.class,target);
            hikvision.accept(com.fireplatform.hikvision.HikvisionClient.Stream.DEVICE_EVENT,json.valueToTree(java.util.List.of(Map.of("eventID","after-transfer","deviceID",external,"eventType","100209","happenTime",transferTime.plusSeconds(1).toString()))),transferTime.plusSeconds(2),"800235");
            fixture.call(HttpMethod.GET,"/api/management/devices/"+target+"/events?page=1",fixture.root,"tenant-b",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].event_label").value("开机"));
            JsonNode returned=fixture.body(fixture.call(HttpMethod.POST,"/api/management/devices/"+target+"/transfer",fixture.root,"tenant-b","transfer-again-after-quarantine",
                Map.of("target_tenant_id","tenant-a","target_place_id",fixture.placeId,"reason","Transfer retains quarantined earlier-owner history","expected_revision",result.at("/target/revision").asLong())).andExpect(status().isOk()));
            fixture.call(HttpMethod.GET,"/api/management/devices/"+returned.at("/target/id").asText()+"/events?page=1",fixture.root,"tenant-a",null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        } finally {
            jdbc.update("DELETE FROM hikvision_raw_records WHERE external_device_id=?",external);
            jdbc.update("DELETE FROM hikvision_discovered_devices WHERE external_id=?",external);
        }
    }

    @Test void transferRejectsActiveSourceBindingWithoutChangingEitherAsset() throws Exception {
        JsonNode source=createDevice(); JsonNode targetPlace=createPlace("tenant-b"); JsonNode bindingSource=fixture.source("tenant-a",false);
        long revision=jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,source.get("id").asText());
        fixture.bind(bindingSource,source,"source-bound-device",revision,UUID.randomUUID().toString()).andExpect(status().isOk());
        long boundRevision=jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,source.get("id").asText());
        var body=transferInput(source,targetPlace); body.put("expected_revision",boundRevision);
        fixture.call(HttpMethod.POST,"/api/management/devices/"+source.get("id").asText()+"/transfer",fixture.root,"tenant-a",UUID.randomUUID().toString(),body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRANSFER_SOURCE_BOUND"));
        assertThat(jdbc.queryForObject("SELECT device_eui FROM devices WHERE id=?",String.class,source.get("id").asText())).isEqualTo(source.get("device_eui").asText());
        assertThat(jdbc.queryForObject("SELECT archived_at FROM devices WHERE id=?",java.time.OffsetDateTime.class,source.get("id").asText())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'",Long.class,source.get("device_eui").asText())).isEqualTo(1);
    }
    @Test void transferReplayReturnsSameResultAndDoesNotDuplicateAssetsOrHistory() throws Exception {
        JsonNode source=createDevice(); JsonNode targetPlace=createPlace("tenant-b"); String key="transfer-replay"; var body=transferInput(source,targetPlace); String path="/api/management/devices/"+source.get("id").asText()+"/transfer";
        JsonNode first=fixture.body(fixture.call(HttpMethod.POST,path,fixture.root,"tenant-a",key,body).andExpect(status().isOk()));
        JsonNode replay=fixture.body(fixture.call(HttpMethod.POST,path,fixture.root,"tenant-a",key,body).andExpect(status().isOk()));
        assertThat(replay.get("transfer_id").asText()).isEqualTo(first.get("transfer_id").asText()); assertThat(replay.at("/target/id").asText()).isEqualTo(first.at("/target/id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_transfers",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=?",Long.class,first.at("/target/id").asText())).isEqualTo(1);
    }
    @Test void transferRequiresExplicitTargetUnitGrant() throws Exception {
        JsonNode source=createDevice(); JsonNode targetPlace=createPlace("tenant-b"); jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-b'");
        fixture.call(HttpMethod.POST,"/api/management/devices/"+source.get("id").asText()+"/transfer",fixture.root,"tenant-a",UUID.randomUUID().toString(),transferInput(source,targetPlace)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT archived_at FROM devices WHERE id=?",java.time.OffsetDateTime.class,source.get("id").asText())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_transfers",Long.class)).isZero();
    }
    @Test void sameHardwareEuiCanTransferBackWithoutOverwritingEitherArchivedIdentity() throws Exception {
        JsonNode source=createDevice(); String eui=source.get("device_eui").asText();
        JsonNode toB=fixture.body(fixture.call(HttpMethod.POST,"/api/management/devices/"+source.get("id").asText()+"/transfer",fixture.root,"tenant-a","transfer-out",transferInput(source,createPlace("tenant-b"))).andExpect(status().isOk()));
        JsonNode target=toB.get("target"); var back=new HashMap<String,Object>(Map.of("target_tenant_id","tenant-a","target_place_id",createPlace("tenant-a").get("id").asText(),"reason","Approved return transfer","expected_revision",target.get("revision").asLong()));
        JsonNode returned=fixture.body(fixture.call(HttpMethod.POST,"/api/management/devices/"+target.get("id").asText()+"/transfer",fixture.root,"tenant-b","transfer-back",back).andExpect(status().isOk()));
        assertThat(returned.at("/target/device_eui").asText()).isEqualTo(eui);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'",Long.class,eui)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT identity_slot FROM devices WHERE id=?",String.class,source.get("id").asText())).isEqualTo("HISTORY:"+source.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT identity_slot FROM devices WHERE id=?",String.class,target.get("id").asText())).isEqualTo("HISTORY:"+target.get("id").asText());
    }
}
