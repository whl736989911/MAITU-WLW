package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:genericapplytests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class DeviceEventHistoryTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { GenericMessageApplyTest.environment(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    private GenericMessageApplyTest fixture;

    @BeforeEach void reset() throws Exception {
        jdbc.update("DELETE FROM legacy_ingest_events WHERE method IN ('fixture.event','HIKVISION_DEVICE_EVENT')");
        fixture=new GenericMessageApplyTest();
        fixture.jdbc=jdbc; fixture.bootstrap=bootstrap; fixture.mvc=mvc; fixture.json=json; fixture.parser=parser;
        fixture.reset();
    }

    @Test void legacyDeviceEventHistoryRemainsTenantScopedReadOnlyAndOmitsPayload() throws Exception {
        var bound=fixture.fixture();
        String device=bound.device().get("id").asText();
        String eventId=UUID.randomUUID().toString();
        OffsetDateTime now=OffsetDateTime.parse("2026-10-31T10:00:00+08:00");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,? ,NULL,?,'ALARM','fixture.event','{\"secret\":\"must not leak\"}',?,?)",eventId,"tenant-a",device,now,now);
        long revision=jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,device);
        var response=mvc.perform(get("/api/management/devices/"+device+"/events?page=1&size=10").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode result=json.readTree(response.getContentAsString());
        assertThat(result.get("total").asInt()).isEqualTo(1);
        assertThat(result.at("/items/0/id").asText()).isEqualTo(eventId);
        assertThat(result.at("/items/0/device_id").asText()).isEqualTo(device);
        assertThat(result.at("/items/0/tenant_id").asText()).isEqualTo("tenant-a");
        assertThat(result.at("/items/0/method").asText()).isEqualTo("fixture.event");
        assertThat(result.at("/items/0/event_code").isNull()).isTrue();
        assertThat(result.at("/items/0/event_label").isNull()).isTrue();
        assertThat(result.at("/items/0/params_json").isMissingNode()).isTrue();
        mvc.perform(get("/api/management/devices/"+device+"/events").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-b")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,device)).isEqualTo(revision);
    }
    @Test void authenticatedGenericCaptureAppearsAsARealNonAlarmEvent() throws Exception {
        var bound=fixture.fixture();
        var captured=fixture.capture(bound,"history-generic-"+UUID.randomUUID(),1L,"2026-10-31T10:00:00+08:00");
        String rawId=captured.get("raw_message_id").asText(),device=bound.device().get("id").asText();
        var response=mvc.perform(get("/api/management/devices/"+device+"/events?page=1&size=10").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode result=json.readTree(response.getContentAsString());
        assertThat(result.get("total").asInt()).isEqualTo(1);
        assertThat(result.at("/items/0/id").asText()).isEqualTo("GENERIC_MESSAGE:"+rawId);
        assertThat(result.at("/items/0/kind").asText()).isEqualTo("OTHER");
        assertThat(result.at("/items/0/method").asText()).isEqualTo("GENERIC_HTTP_JSON_V1");
        assertThat(result.at("/items/0/status").asText()).isEqualTo("NOT_APPLIED");
        assertThat(result.at("/items/0/observed_at").asText()).isEqualTo("2026-10-31T10:00:00+08:00");
    }
    @Test void hikvisionEventHistoryExposesOnlySnakeCaseCodeAndTrustedLabel() throws Exception {
        var bound=fixture.fixture();
        String device=bound.device().get("id").asText(),eventId=UUID.randomUUID().toString();
        OffsetDateTime now=OffsetDateTime.parse("2026-10-31T10:00:00+08:00");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,? ,NULL,?,'OTHER','HIKVISION_DEVICE_EVENT',?,?,?)",
            eventId,"tenant-a",device,"{\"source\":\"HIKVISION_FIRECLOUD\",\"source_event_id\":\"vendor-1\",\"source_code\":\"100209\",\"source_state\":\"0\"}",now,now);
        var response=mvc.perform(get("/api/management/devices/"+device+"/events?page=1&size=10").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode row=json.readTree(response.getContentAsString()).at("/items/0");
        assertThat(row.get("event_code").asText()).isEqualTo("100209");
        assertThat(row.get("event_label").asText()).isEqualTo("开机");
        assertThat(row.get("eventCode")).isNull();
        assertThat(row.get("params_json")).isNull();
    }
    @Test void nonNativeHikvisionCodesDoNotUseTheDeviceEventDictionary() throws Exception {
        var bound=fixture.fixture();
        String device=bound.device().get("id").asText(),eventId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,created_at) VALUES(?,? ,NULL,?,'FAULT','fixture.event',?,CURRENT_TIMESTAMP)",
            eventId,"tenant-a",device,"{\"source\":\"HIKVISION_FIRECLOUD\",\"source_code\":\"100209\"}");
        var response=mvc.perform(get("/api/management/devices/"+device+"/events?page=1&size=10").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode row=json.readTree(response.getContentAsString()).at("/items/0");
        assertThat(row.get("event_code").isNull()).isTrue();
        assertThat(row.get("event_label").isNull()).isTrue();
    }
    @Test void eventFiltersUseSourceTimeAcrossGenericAndLegacyBeforeCountingAndPaging() throws Exception {
        var bound=fixture.fixture();
        String device=bound.device().get("id").asText();
        var at=OffsetDateTime.parse("2026-10-31T10:00:00+08:00");
        var captured=fixture.capture(bound,"filtered-"+UUID.randomUUID(),1L,at.plusNanos(1000).toString());
        String genericId="GENERIC_MESSAGE:"+captured.get("raw_message_id").asText();
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES('event-peer','tenant-a','Peer','event-peer','test','test','UNKNOWN',FALSE)");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('filter-start','tenant-a',?,'OTHER','fixture.event','{}',?,?),('filter-end','tenant-a',?,'OTHER','fixture.event','{}',?,?),('filter-untimed','tenant-a',?,'OTHER','fixture.event','{}',NULL,?),('filter-kind','tenant-a',?,'FAULT','fixture.event','{}',?,?),('filter-peer','tenant-a','event-peer','OTHER','fixture.event','{}',?,?)",
            device,at,at.plusDays(1),device,at.plusNanos(2000),at,device,at.plusNanos(1000),device,at.plusNanos(1000),at,at,at);
        var ids=new java.util.HashSet<String>();
        for(int page=1;page<=2;page++){
            var response=mvc.perform(get("/api/management/devices/"+device+"/events").param("kind","OTHER").param("from",at.toString()).param("to",at.plusNanos(2000).toString()).param("page",Integer.toString(page)).param("size","1").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse();
            var result=json.readTree(response.getContentAsString());
            assertThat(result.get("total").asInt()).isEqualTo(2);
            assertThat(result.get("page").asInt()).isEqualTo(page);
            assertThat(result.get("items").size()).isEqualTo(1);
            assertThat(result.at("/items/0/kind").asText()).isEqualTo("OTHER");
            assertThat(result.at("/items/0/device_id").asText()).isEqualTo(device);
            ids.add(result.at("/items/0/id").asText());
        }
        assertThat(ids).containsExactlyInAnyOrder("filter-start",genericId);
        var unbounded=mvc.perform(get("/api/management/devices/"+device+"/events").param("kind","OTHER").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(unbounded.getContentAsString()).get("total").asInt()).isEqualTo(4);
    }

    @Test void eventFiltersRejectInvalidTypesTimesDuplicatesAndScopeOverrides() throws Exception {
        var bound=fixture.fixture();String path="/api/management/devices/"+bound.device().get("id").asText()+"/events";
        for(String suffix:java.util.List.of("?kind=UNKNOWN","?from=2026-10-31","?from=2026-10-31T10:00:00Z&to=2026-10-31T10:00:00Z","?kind=FAULT&kind=OTHER","?device_id=event-peer")){
            mvc.perform(get(path+suffix).header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isBadRequest());
        }
    }
}
