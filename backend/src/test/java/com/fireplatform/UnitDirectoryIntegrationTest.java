package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=all","app.simulation-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:unitdirectorytests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class UnitDirectoryIntegrationTest {
    static final String INITIAL="Initial-Unit-Test-41", ROOT_PASSWORD="Root-Unit-Test-41", ADMIN_PASSWORD="Admin-Unit-Test-41";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){registry.add("APP_BOOTSTRAP_USERNAME",()->"admin");registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap;
    @BeforeEach void reset(){for(String table:List.of("notification_automation_intents","alarm_trace_entries","alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","real_device_battery_observations","identity_command_records","idempotency_records","device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","sessions","user_permissions","user_tenant_grants","user_device_scope_devices","user_device_scopes","app_users","devices","places","tenants","audit_log"))jdbc.update("DELETE FROM "+table);bootstrap.run(new DefaultApplicationArguments());}
    JsonNode body(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
    ResultActions call(HttpMethod method,String path,String token,String key,Object input)throws Exception{var builder=request(method,path);if(token!=null)builder.header("Authorization","Bearer "+token);if(key!=null)builder.header("Idempotency-Key",key);if(input!=null)builder.contentType("application/json").content(json.writeValueAsString(input));return mvc.perform(builder);}
    String login(String username,String password,String audience)throws Exception{return body(call(HttpMethod.POST,"/api/auth/login",null,null,Map.of("username",username,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText();}
    String root()throws Exception{String token=login("admin",INITIAL,"management");call(HttpMethod.POST,"/api/auth/change-password",token,null,Map.of("current_password",INITIAL,"new_password",ROOT_PASSWORD)).andExpect(status().isNoContent());return login("admin",ROOT_PASSWORD,"management");}
    @Test void registrationMetadataOwnerReadsOptionalAdminAndRevisionConflictAreAtomic()throws Exception{
        String root=root();var payload=new LinkedHashMap<String,Object>();payload.put("name","现场登记单位");payload.put("credit_code","91330000123456789X");payload.put("region_id","330106");payload.put("unit_type","园区");payload.put("industry","制造");payload.put("map_address","杭州西湖区");payload.put("detail_address","文三路 1 号");payload.put("contact_name","李明");payload.put("contact_phone","13800000000");payload.put("latitude",30.27);payload.put("longitude",120.15);payload.put("service_start","2026-01-01");payload.put("service_end","2026-12-31");payload.put("engineering_service_start","2026-02-01");payload.put("engineering_service_end","2026-11-30");payload.put("admin_username","unit-admin");payload.put("admin_name","单位管理员");payload.put("admin_initial_password",ADMIN_PASSWORD);payload.put("admin_permissions",List.of());
        JsonNode created=body(call(HttpMethod.POST,"/api/management/units",root,"unit-directory-create",payload).andExpect(status().isOk()));String id=created.get("id").asText();assertThat(created.get("credit_code").asText()).isEqualTo("91330000123456789X");assertThat(created.get("service_end").asText()).isEqualTo("2026-12-31");
        assertThat(body(call(HttpMethod.POST,"/api/management/units",root,"unit-directory-create",payload).andExpect(status().isOk()))).isEqualTo(created);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='unit-admin'",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM app_users WHERE username='unit-admin'",Boolean.class)).isTrue();assertThat(jdbc.queryForObject("SELECT mode FROM user_device_scopes WHERE user_id=(SELECT id FROM app_users WHERE username='unit-admin') AND tenant_id=?",String.class,id)).isEqualTo("DEVICE_SET");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scope_devices WHERE user_id=(SELECT id FROM app_users WHERE username='unit-admin')",Long.class)).isZero();
        mvc.perform(get("/api/management/units").param("status","enabled").param("keyword","现场登记单位").header("Authorization","Bearer "+root)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].contact_name").value("李明"));
        var update=Map.ofEntries(Map.entry("name","现场登记单位更新"),Map.entry("credit_code","91330000123456789X"),Map.entry("region_id","330106"),Map.entry("unit_type","园区"),Map.entry("industry","制造"),Map.entry("map_address","杭州西湖区"),Map.entry("detail_address","文三路 1 号"),Map.entry("contact_name","李明"),Map.entry("contact_phone","13800000000"),Map.entry("remark","变更记录"),Map.entry("latitude",30.27),Map.entry("longitude",120.15),Map.entry("service_start","2026-01-01"),Map.entry("service_end","2026-12-31"),Map.entry("engineering_service_start","2026-02-01"),Map.entry("engineering_service_end","2026-11-30"),Map.entry("expected_revision",0));
        call(HttpMethod.PUT,"/api/management/units/"+id+"/details",root,"unit-directory-edit",update).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));call(HttpMethod.GET,"/api/management/units/"+id,root,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.remark").value("变更记录"));call(HttpMethod.PUT,"/api/management/units/"+id+"/details",root,"unit-directory-stale",update).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
    }
}
