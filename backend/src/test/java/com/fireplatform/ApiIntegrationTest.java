package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:genericapplytests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class ApiIntegrationTest {
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

    @Test void authenticatedManagementReadsOnlyRealRegisteredDevice() throws Exception {
        var real=fixture.fixture();
        var response=mvc.perform(get("/api/management/devices").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn().getResponse();
        JsonNode list=json.readTree(response.getContentAsString());
        assertThat(list.at("/items/0/id").asText()).isEqualTo(real.device().get("id").asText());
        mvc.perform(get("/api/management/devices/"+real.device().get("id").asText()).header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.device.id").value(real.device().get("id").asText()));
        mvc.perform(get("/api/management/devices").header("X-Tenant-Id","tenant-a")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT is_simulated FROM devices WHERE id=?",Boolean.class,real.device().get("id").asText())).isFalse();
    }
}
