package com.fireplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.util.Map;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:registrationauthority;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceRegistrationAuthorityTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    IngestionIntegrationTest f;
    String other;
    @BeforeEach void reset() throws Exception {
        f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) SELECT 'registration-other','registration-other','Other platform operator','PLATFORM',password_hash,FALSE,TRUE FROM app_users WHERE id='bootstrap-admin'");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('registration-other','device:write')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('registration-other','tenant-a')");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES ('registration-other','tenant-a','ALL_TENANT',1)");
        registration("unassigned-registration",null,"unassigned-serial");
        registration("foreign-unit-registration","tenant-b","foreign-unit-serial");
        other=f.login("registration-other",IngestionIntegrationTest.PASSWORD,"management");
    }
    void registration(String id,String tenant,String serial){jdbc.update("INSERT INTO device_registrations(id,driver_key,mode,status,tenant_id,external_serial,cloud_state,local_state,created_by,created_at,updated_at) VALUES (?,'HIKVISION_FIRECLOUD','NEW_VENDOR','DRAFT',?,?,'NOT_ATTEMPTED','NOT_READY','bootstrap-admin',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,tenant,serial);}
    Map<String,String> abandon(){return Map.of("reason","Explicit abandonment");}
    @Test void ordinaryDeviceWriterCannotReadOrAbandonAnotherCreatorsUnassignedRegistration() throws Exception {
        f.call(HttpMethod.GET,"/api/management/device-registrations",other,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        f.call(HttpMethod.GET,"/api/management/device-registrations/unassigned-registration",other,null,null,null).andExpect(status().isNotFound());
        f.call(HttpMethod.POST,"/api/management/device-registrations/unassigned-registration/abandon",other,null,"unassigned-denied",abandon()).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM device_registrations WHERE id='unassigned-registration'",String.class)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_registration_events",Long.class)).isZero();
        f.call(HttpMethod.GET,"/api/management/device-registrations/unassigned-registration",f.root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.registration.external_serial").value("unassigned-serial"));
    }
    @Test void explicitRoleGlobalAuthorityCoversOnlyUnassignedPoolAndRevocationTakesEffectImmediately() throws Exception {
        jdbc.update("INSERT INTO access_roles(id,code,name,user_type) VALUES ('registration-global-role','registration-global-role','Unassigned pool operator','PLATFORM')");
        jdbc.update("INSERT INTO access_role_permissions(role_id,permission) VALUES ('registration-global-role','device-registrations:read-global'),('registration-global-role','device-registrations:write-global')");
        jdbc.update("INSERT INTO access_user_roles(user_id,role_id,user_type) VALUES ('registration-other','registration-global-role','PLATFORM')");
        f.call(HttpMethod.GET,"/api/management/device-registrations",other,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value("unassigned-registration"));
        f.call(HttpMethod.GET,"/api/management/device-registrations/foreign-unit-registration",other,null,null,null).andExpect(status().isNotFound());
        f.call(HttpMethod.POST,"/api/management/device-registrations/foreign-unit-registration/abandon",other,null,"assigned-denied",abandon()).andExpect(status().isNotFound());
        f.call(HttpMethod.POST,"/api/management/device-registrations/unassigned-registration/abandon",other,null,"unassigned-allowed",abandon()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM device_registrations WHERE id='unassigned-registration'",String.class)).isEqualTo("ABANDONED");
        assertThat(jdbc.queryForObject("SELECT status FROM device_registrations WHERE id='foreign-unit-registration'",String.class)).isEqualTo("DRAFT");
        jdbc.update("UPDATE access_roles SET enabled=FALSE WHERE id='registration-global-role'");
        f.call(HttpMethod.GET,"/api/management/device-registrations/unassigned-registration",other,null,null,null).andExpect(status().isNotFound());
        f.call(HttpMethod.GET,"/api/management/device-registrations",other,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
}
