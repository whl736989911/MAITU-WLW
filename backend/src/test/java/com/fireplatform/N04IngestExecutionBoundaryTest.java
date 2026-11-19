package com.fireplatform;

import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.HttpMethod;

// This tests manual application through the API; keep the automatic worker out of this database.
@SpringBootTest(properties={"app.roles=api,ingest","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:manual-apply-boundary;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class N04IngestExecutionBoundaryTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { GenericMessageApplyTest.environment(registry); }
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JsonPointerParser parser;

    @Test void authenticatedRealCaptureCannotBeAppliedAcrossTenantAndCanApplyWithinItsTenant() throws Exception {
        var fixture=new GenericMessageApplyTest();
        fixture.jdbc=jdbc; fixture.bootstrap=bootstrap; fixture.mvc=mvc; fixture.json=json; fixture.parser=parser;
        fixture.reset();
        var bound=fixture.fixture();
        var captured=fixture.capture(bound,"boundary-"+UUID.randomUUID(),1L,"2026-10-31T10:00:00+08:00");
        String rawId=captured.get("raw_message_id").asText();

        var crossTenant=fixture.call(HttpMethod.POST,"/api/management/messages/"+rawId+"/apply",fixture.root,"tenant-b",UUID.randomUUID().toString(),java.util.Map.of("expected_revision",0,"reason","Tenant isolation"));
        assertThat(crossTenant.andReturn().getResponse().getStatus()).isIn(403,404);
        assertThat(jdbc.queryForObject("SELECT application_state FROM raw_messages WHERE id=?",String.class,rawId)).isEqualTo("NOT_APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms",Long.class)).isZero();

        fixture.call(HttpMethod.POST,"/api/management/messages/"+rawId+"/apply",fixture.root,"tenant-a",UUID.randomUUID().toString(),java.util.Map.of("expected_revision",0,"reason","Tenant-scoped real application"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.application_state").value("APPLIED"));
        
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id='tenant-a'",Long.class)).isEqualTo(1L);
    }
}
