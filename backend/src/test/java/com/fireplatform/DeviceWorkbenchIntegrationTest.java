package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.device.DeviceQueryService;
import com.fireplatform.product.JsonPointerParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:genericapplytests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceWorkbenchIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { GenericMessageApplyTest.environment(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    private GenericMessageApplyTest fixture;

    @BeforeEach void reset() throws Exception {
        fixture=new GenericMessageApplyTest();
        fixture.jdbc=jdbc; fixture.bootstrap=bootstrap; fixture.mvc=mvc; fixture.json=json; fixture.parser=parser;
        fixture.reset();
    }

    @Test void workbenchShowsRealAssetsAndExcludesHistoricalSimulationFromRowsAndAggregates() throws Exception {
        var real=fixture.fixture();
        jdbc.update("UPDATE devices SET last_message_at=CURRENT_TIMESTAMP - INTERVAL '7' DAY,physical_state='FIRE' WHERE id=?",real.device().get("id").asText());
        var version=fixture.published();
        var historical=fixture.device(version);
        jdbc.update("UPDATE devices SET is_simulated=TRUE,integration_state='RETIRED_SIMULATION' WHERE id=?",historical.get("id").asText());
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Long.class);

        var response=mvc.perform(get("/api/management/device-workbench").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode body=json.readTree(response.getContentAsString());
        assertThat(body.get("total").asLong()).isEqualTo(1);
        assertThat(body.at("/items/0/id").asText()).isEqualTo(real.device().get("id").asText());
        assertThat(body.at("/items/0/connectivity").asText()).isEqualTo("UNKNOWN");
        assertThat(body.at("/items/0/physical_state").asText()).isEqualTo("FIRE");
        assertThat(body.at("/items/0/offline_policy/evaluated_at").asText()).isEqualTo(body.get("as_of").asText());
        assertThat(body.at("/metrics/device_total").asLong()).isEqualTo(1);
        assertThat(body.at("/metrics/online").asLong()).isZero();
        assertThat(body.at("/metrics/offline").asLong()).isZero();
        assertThat(body.at("/metrics/unactivated").asLong()).isZero();
        assertThat(body.at("/metrics/unknown_connectivity").asLong()).isEqualTo(1);
        assertThat(body.at("/metrics/fire").asLong()).isEqualTo(1);
        assertThat(body.at("/metrics").has("simulated")).isFalse();
        assertThat(body.at("/metrics").has("non_simulated")).isFalse();
        assertThat(body.at("/items").toString()).doesNotContain(historical.get("id").asText());
        assertThat(body.at("/metric_definitions").has("simulation")).isFalse();
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,historical.get("id").asText())).isEqualTo("RETIRED_SIMULATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Long.class)).isEqualTo(before);

        mvc.perform(get("/api/management/device-workbench").param("is_simulated","false").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isBadRequest());
    }
}
