package com.fireplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.fireplatform.IngestionIntegrationTest.PASSWORD;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.notification-contact-key=","app.generic-ingest-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:notification-missing-key;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class NotificationMissingKeyTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired Bootstrap bootstrap;@Autowired JsonPointerParser parser;IngestionIntegrationTest f;
    @BeforeEach void reset()throws Exception{NotificationIntegrationTest.clear(jdbc);f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();}
    @Test void missingKeyBlocksContactWritesButNotReadinessOrRealRuleConfiguration()throws Exception{f.call(HttpMethod.GET,"/api/health",null,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.contact_storage_ready").value(false));f.call(HttpMethod.GET,"/api/management/notification-capabilities",f.root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.contact_storage_ready").value(false));f.call(HttpMethod.POST,"/api/management/notification-contacts",f.root,"tenant-a","no-key-contact",Map.of("name","Contact","contact_type","OTHER","phone","13800001234","enabled",false,"reason","Missing key fixture")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NOTIFICATION_CONTACT_STORAGE_UNAVAILABLE"));f.call(HttpMethod.GET,"/api/management/notification-rules",f.root,"tenant-a",null,null).andExpect(status().isOk());f.call(HttpMethod.GET,"/api/management/alarm-workbench",f.root,"tenant-a",null,null).andExpect(status().isOk());
        // Persisted contact fixture: rule configuration does not need phone decryption.
        jdbc.update("INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,enabled,created_at) VALUES ('private-contact','tenant-a','Persisted contact','OTHER','opaque-cipher','opaque-nonce','138****1234','opaque-mac',TRUE,CURRENT_TIMESTAMP)");
        f.call(HttpMethod.POST,"/api/management/notification-rules",f.root,"tenant-a","rule-without-key",Map.of("name","Rule without crypto dependency","enabled",true,"sms_escalation_interval_minutes",0,"voice_escalation_interval_minutes",0,"receivers",java.util.List.of(Map.of("channel","SMS","contact_id","private-contact","level",1),Map.of("channel","VOICE","contact_id","private-contact","level",1)),"reason","Explicit rule only")).andExpect(status().isCreated());
    }
}
