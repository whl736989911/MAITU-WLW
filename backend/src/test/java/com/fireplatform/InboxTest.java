package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** N13：站内消息按用户独立读/处理，个人中心会话自助。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:inboxtests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class InboxTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID(), CUSTOMER_NEXT="Customer-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap;
    String root;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        for(String table:List.of("user_messages","device_import_rows","device_import_jobs","device_export_snapshots","alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","real_device_battery_observations","device_profile_snapshots","profile_rules","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_trace_entries","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Inbox test tenant A'),('tenant-b','Inbox test tenant B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc);
        root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        for(String[] account:new String[][]{{"customer-a","tenant-a"},{"customer-b","tenant-b"}})
            call(HttpMethod.POST,"/api/management/users",root,account[1],UUID.randomUUID().toString(),Map.of("username",account[0],"name",account[0],"initial_password",INITIAL,"permissions",com.fireplatform.shared.Limits.CUSTOMER_PERMISSIONS)).andExpect(status().isOk());
        N04TestScopes.grantExisting(jdbc);
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    String activate(String name,String audience) throws Exception { String token=login(name,INITIAL,audience); call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",INITIAL,"new_password",CUSTOMER_NEXT)).andExpect(status().isNoContent()); return login(name,CUSTOMER_NEXT,audience); }

    @Test void publishFansOutAndEachUserReadsAndHandlesIndependently() throws Exception {
        var published=body(call(HttpMethod.POST,"/api/management/user-messages/publish",root,"tenant-a",null,Map.of("kind","SYSTEM","title","演练通知","body","这是站内消息演练","reason","N13 投递")).andExpect(status().isOk()));
        assertThat(published.get("delivered").asInt()).isEqualTo(1);
        String customer=activate("customer-a","customer");
        var list=body(call(HttpMethod.GET,"/api/me/messages?page=1&size=10",customer,null,null,null).andExpect(status().isOk()));
        assertThat(list.get("total").asInt()).isEqualTo(1);
        assertThat(list.at("/items/0/status").asText()).isEqualTo("UNREAD");
        String id=list.at("/items/0/id").asText();
        var read=body(call(HttpMethod.POST,"/api/me/messages/"+id+"/read",customer,null,null,Map.of("expected_revision",0)).andExpect(status().isOk()));
        assertThat(read.get("status").asText()).isEqualTo("READ");
        var handled=body(call(HttpMethod.POST,"/api/me/messages/"+id+"/handle",customer,null,null,Map.of("expected_revision",read.get("revision").asLong())).andExpect(status().isOk()));
        assertThat(handled.get("status").asText()).isEqualTo("HANDLED");
        // 重复处理幂等返回 HANDLED，不再改版本。
        var again=body(call(HttpMethod.POST,"/api/me/messages/"+id+"/handle",customer,null,null,Map.of("expected_revision",handled.get("revision").asLong())).andExpect(status().isOk()));
        assertThat(again.get("status").asText()).isEqualTo("HANDLED");
        assertThat(again.get("revision").asLong()).isEqualTo(handled.get("revision").asLong());
    }

    @Test void otherTenantCustomersAndManagementCannotSeeTheMessage() throws Exception {
        call(HttpMethod.POST,"/api/management/user-messages/publish",root,"tenant-a",null,Map.of("kind","SYSTEM","title","仅A可见","body","body","reason","N13")).andExpect(status().isOk());
        String other=activate("customer-b","customer");
        var otherList=body(call(HttpMethod.GET,"/api/me/messages?page=1&size=10",other,null,null,null).andExpect(status().isOk()));
        assertThat(otherList.get("total").asInt()).isZero();
        var adminList=body(call(HttpMethod.GET,"/api/me/messages?page=1&size=10",root,null,null,null).andExpect(status().isOk()));
        assertThat(adminList.get("total").asInt()).isZero();
    }

    @Test void invalidPaginationAndStatusAreRejectedWithoutQuerying() throws Exception {
        String customer=activate("customer-a","customer");
        call(HttpMethod.GET,"/api/me/messages?page=2147483647&size=100",customer,null,null,null).andExpect(status().isBadRequest());
        call(HttpMethod.GET,"/api/me/messages?page=1&size=100&status=TYPO",customer,null,null,null).andExpect(status().isBadRequest());
    }

    @Test void staleRevisionIsRejectedAfterRead() throws Exception {
        call(HttpMethod.POST,"/api/management/user-messages/publish",root,"tenant-a",null,Map.of("kind","ALARM","title","报警消息","body","body","reason","N13")).andExpect(status().isOk());
        String customer=activate("customer-a","customer");
        var list=body(call(HttpMethod.GET,"/api/me/messages?page=1&size=10",customer,null,null,null).andExpect(status().isOk()));
        String id=list.at("/items/0/id").asText();
        call(HttpMethod.POST,"/api/me/messages/"+id+"/read",customer,null,null,Map.of("expected_revision",0)).andExpect(status().isOk());
        call(HttpMethod.POST,"/api/me/messages/"+id+"/handle",customer,null,null,Map.of("expected_revision",0)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
    }

    @Test void personalCenterListsSessionsAndRevokesOthers() throws Exception {
        String first=activate("customer-a","customer");
        String second=login("customer-a",CUSTOMER_NEXT,"customer");
        var profile=body(call(HttpMethod.GET,"/api/me",second,null,null,null).andExpect(status().isOk()));
        assertThat(profile.get("username").asText()).isEqualTo("customer-a");
        var renamed=body(call(HttpMethod.PUT,"/api/me/profile",second,null,null,Map.of("name","客户新名")).andExpect(status().isOk()));
        assertThat(renamed.get("name").asText()).isEqualTo("客户新名");
        var sessions=body(call(HttpMethod.GET,"/api/me/sessions",second,null,null,null).andExpect(status().isOk()));
        assertThat(sessions.size()).isGreaterThanOrEqualTo(2);
        long current=0; for(JsonNode session:sessions) if(session.get("current").asBoolean()) current++;
        assertThat(current).isEqualTo(1);
        var revoked=body(call(HttpMethod.POST,"/api/me/sessions/revoke-others",second,null,null,null).andExpect(status().isOk()));
        assertThat(revoked.get("revoked").asInt()).isGreaterThanOrEqualTo(1);
        // 当前会话仍有效，被撤销的旧会话失效。
        call(HttpMethod.GET,"/api/me",second,null,null,null).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/me",first,null,null,null).andExpect(status().isUnauthorized());
    }
}
