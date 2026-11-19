package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.alarm.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.*;
import com.fireplatform.identity.AuthService;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.availability.*;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.alarm-change-read-enabled=true","app.alarm-change-ws-enabled=true","app.alarm-change-ws-allowed-origins=http://channel.test","app.alarm-change-reconnect-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:alarm-reconnect;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test") @Import(AlarmChangeReconnectIntegrationTest.Observation.class)
class AlarmChangeReconnectIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @TestConfiguration static class Observation {
        @Bean @Primary AlarmWorkbenchIntegrationTest.ObservedDataSource observed(@Qualifier("dataSource")javax.sql.DataSource actual){return new AlarmWorkbenchIntegrationTest.ObservedDataSource(actual);}
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired Bootstrap bootstrap;@Autowired JsonPointerParser parser;@Autowired AlarmWorkbenchIntegrationTest.ObservedDataSource observed;@Autowired AlarmChangeChannelTickets tickets;
    @MockitoSpyBean AlarmChangeReconnectPolicy policy;@MockitoSpyBean AlarmChangeChannelPolicy channel;@MockitoSpyBean RuntimeConfig runtime;@MockitoSpyBean ApplicationAvailability availability;
    IngestionIntegrationTest f;
    @BeforeEach void reset()throws Exception{jdbc.update("DELETE FROM alarm_change_outbox");jdbc.update("DELETE FROM alarm_change_heads");f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();observed.calls.clear();}
    MockHttpServletRequestBuilder readRequest(String token,String audience,String tenant){var request=get("/api/"+audience+"/alarm-change-reconnect-policy");if(token!=null)request.header("Authorization","Bearer "+token);if(tenant!=null)request.header("X-Tenant-Id",tenant);return request;}
    ResultActions readPolicy(String token,String audience,String tenant)throws Exception{return mvc.perform(readRequest(token,audience,tenant));}
    JsonNode descriptor(String token,String audience,String tenant)throws Exception{return f.body(readPolicy(token,audience,tenant).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")));}
    String customer(String suffix)throws Exception{String name="customer-"+suffix;String token=f.login(name,IngestionIntegrationTest.INITIAL,"customer");f.call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",IngestionIntegrationTest.PASSWORD)).andExpect(status().isNoContent());return f.login(name,IngestionIntegrationTest.PASSWORD,"customer");}
    Map<String,List<Map<String,Object>>> facts(){var result=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name",String.class))result.put(table,jdbc.queryForList("SELECT * FROM "+table));return result;}
    @Test void closedThreeByThreeWireIsReadOnlyAndReservesNoTicketOrPermit()throws Exception{
        var before=facts();int permits=tickets.used();observed.calls.clear();var body=descriptor(f.root,"management","tenant-a");
        assertThat(body.size()).isEqualTo(3);assertThat(body.has("v")&&body.has("scope")&&body.has("policy")).isTrue();assertThat(body.get("v").isIntegralNumber()).isTrue();assertThat(body.get("v").intValue()).isEqualTo(1);assertThat(body.get("scope").size()).isEqualTo(3);assertThat(body.at("/scope/tenant_id").asText()).isEqualTo("tenant-a");assertThat(body.at("/scope/audience").asText()).isEqualTo("management");assertThat(body.at("/scope/session").asText()).isEqualTo(AlarmChangeCursor.session(AuthService.hash(f.root)));
        assertThat(body.get("policy").size()).isEqualTo(3);for(String key:List.of("max_retries","delay_ms","window_ms"))assertThat(body.get("policy").get(key).isIntegralNumber()).isTrue();assertThat(body.at("/policy/max_retries").intValue()).isEqualTo(3);assertThat(body.at("/policy/delay_ms").intValue()).isEqualTo(1000);assertThat(body.at("/policy/window_ms").intValue()).isEqualTo(60000);assertThat(body.toString().contains(f.root)||body.toString().contains(AuthService.hash(f.root))).isFalse();assertThat(body.toString()).doesNotContain("ticket","head_cursor","connect_timeout_ms","silence_timeout_ms","actor","payload");assertThat(tickets.used()).isEqualTo(permits);assertThat(facts()).isEqualTo(before);
    }
    @Test void minimumAlarmReadScopesNeedOnlyTheCurrentReadPermission()throws Exception{
        String a=customer("a"),b=customer("b");jdbc.update("DELETE FROM user_permissions WHERE permission<>'alarm:read'");
        for(String tenant:List.of("tenant-a","tenant-b")){observed.calls.clear();assertThat(descriptor(f.root,"management",tenant).at("/scope/tenant_id").asText()).isEqualTo(tenant);}
        for(var entry:Map.of("tenant-a",a,"tenant-b",b).entrySet()){observed.calls.clear();var body=descriptor(entry.getValue(),"customer",null);assertThat(body.at("/scope/tenant_id").asText()).isEqualTo(entry.getKey());assertThat(body.at("/scope/audience").asText()).isEqualTo("customer");}
    }
    @Test void missingAndExplicitCanonicalOriginWorkButInvalidAndDuplicateOriginsFail()throws Exception{
        descriptor(f.root,"management","tenant-a");mvc.perform(readRequest(f.root,"management","tenant-a").header("Origin","http://channel.test")).andExpect(status().isOk());
        for(String origin:List.of("","null","http://evil.test","http://channel.test/","HTTP://channel.test","http://channel.test:80","http://channel.test, http://channel.test"))mvc.perform(readRequest(f.root,"management","tenant-a").header("Origin",origin)).andExpect(status().isForbidden());
        mvc.perform(readRequest(f.root,"management","tenant-a").header("Origin","http://channel.test","http://channel.test")).andExpect(status().isForbidden());mvc.perform(readRequest(f.root,"management","tenant-a").header("Host","evil.test").header("Forwarded","host=evil.test;proto=https")).andExpect(status().isOk());
    }
    @Test void queryAndHttpOneBodyFramingDenyOnlyThroughOriginalAudit()throws Exception{
        var before=facts();int rejects=0;
        for(String query:List.of("","x=1","x=1&x=2","ticket=not-a-ticket")){mvc.perform(readRequest(f.root,"management","tenant-a").with(r->{r.setQueryString(query);return r;})).andExpect(status().isBadRequest());rejects++;}
        for(String body:List.of("{}","null","[1]")){mvc.perform(readRequest(f.root,"management","tenant-a").contentType("application/json").content(body)).andExpect(status().isBadRequest());rejects++;}
        for(String encoding:List.of("chunked","identity","")){mvc.perform(readRequest(f.root,"management","tenant-a").header("Transfer-Encoding",encoding)).andExpect(status().isBadRequest());rejects++;}
        mvc.perform(readRequest(f.root,"management","tenant-a").content(new byte[0])).andExpect(status().isOk());var after=facts();assertThat(after.get("audit_log")).hasSize(before.get("audit_log").size()+rejects);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE outcome='DENIED'",Integer.class)).isEqualTo(rejects);before.remove("audit_log");after.remove("audit_log");assertThat(after).isEqualTo(before);
    }
    @Test void rawBearerIsStillRequiredAndUpgradeHeadersCannotBypassTheNewGet()throws Exception{
        readPolicy(null,"management","tenant-a").andExpect(status().isUnauthorized());readPolicy(AuthService.hash(f.root),"management","tenant-a").andExpect(status().isUnauthorized());readPolicy("gih_"+"x".repeat(43),"management","tenant-a").andExpect(status().isUnauthorized());mvc.perform(readRequest(null,"management","tenant-a").header("Upgrade","websocket").header("Connection","Upgrade")).andExpect(status().isUnauthorized());
        var issued=tickets.issue(AuthService.hash(f.root),"tenant-a","management","http://channel.test","bootstrap-admin");try{readPolicy(issued.ticket(),"management","tenant-a").andExpect(status().isUnauthorized());}finally{tickets.release(tickets.claim(issued.ticket(),"http://channel.test","management"));}
    }
    @Test void audienceAndCurrentUnitGrantStayBoundToTheAuthenticatedSession()throws Exception{
        String c=customer("a");readPolicy(f.root,"customer",null).andExpect(status().isForbidden());readPolicy(c,"management","tenant-a").andExpect(status().isForbidden());readPolicy(c,"customer","tenant-a").andExpect(status().isForbidden());readPolicy(f.root,"management",null).andExpect(status().isBadRequest());readPolicy(f.root,"management","tenant-other").andExpect(status().isForbidden());jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");readPolicy(f.root,"management","tenant-a").andExpect(status().isForbidden());
    }
    @Test void passwordChangeAndPermissionRevocationAreFreshEvenAfterPriorSuccess()throws Exception{
        String initial=f.login("customer-a",IngestionIntegrationTest.INITIAL,"customer");readPolicy(initial,"customer",null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));descriptor(f.root,"management","tenant-a");jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='alarm:read'");readPolicy(f.root,"management","tenant-a").andExpect(status().isForbidden());jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('bootstrap-admin','alarm:read')");jdbc.update("UPDATE app_users SET must_change_password=TRUE WHERE id='bootstrap-admin'");readPolicy(f.root,"management","tenant-a").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }
    @Test void expiredRevokedAndDisabledAccountsReturnOriginal401()throws Exception{
        for(int variant=0;variant<3;variant++){if(variant>0)reset();if(variant==0)jdbc.update("UPDATE sessions SET expires_at=?",OffsetDateTime.parse("2000-01-01T00:00:00Z"));if(variant==1)jdbc.update("UPDATE sessions SET revoked=TRUE");if(variant==2)jdbc.update("UPDATE app_users SET enabled=FALSE WHERE id='bootstrap-admin'");readPolicy(f.root,"management","tenant-a").andExpect(status().isUnauthorized());}
    }
    @Test void customerDisabledUnitKeeps401WhileManagementGets403()throws Exception{String c=customer("a");jdbc.update("UPDATE tenants SET enabled=FALSE WHERE id='tenant-a'");readPolicy(c,"customer",null).andExpect(status().isUnauthorized());readPolicy(f.root,"management","tenant-a").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNIT_DISABLED"));}
    @Test void everyAvailabilityGateIs503WithoutPretendingItWritesDenied()throws Exception{
        var before=facts();doReturn(false).when(policy).enabled();readPolicy(f.root,"management","tenant-a").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECONNECT_UNAVAILABLE"));doCallRealMethod().when(policy).enabled();doReturn(false).when(channel).enabled();readPolicy(f.root,"management","tenant-a").andExpect(status().isServiceUnavailable());doCallRealMethod().when(channel).enabled();doReturn(false).when(runtime).alarmChangeReadEnabled();readPolicy(f.root,"management","tenant-a").andExpect(status().isServiceUnavailable());doCallRealMethod().when(runtime).alarmChangeReadEnabled();doReturn(false).when(runtime).apiEnabled();readPolicy(f.root,"management","tenant-a").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ROLE_NOT_IMPLEMENTED"));doCallRealMethod().when(runtime).apiEnabled();doReturn(ReadinessState.REFUSING_TRAFFIC).when(availability).getReadinessState();readPolicy(f.root,"management","tenant-a").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NOT_READY"));doCallRealMethod().when(availability).getReadinessState();assertThat(facts()).isEqualTo(before);
    }
    @Test void sessionFingerprintChangesAcrossLoginButDoesNotExposeTheHash()throws Exception{var first=descriptor(f.root,"management","tenant-a");String other=f.login("admin",IngestionIntegrationTest.PASSWORD,"management");var second=descriptor(other,"management","tenant-a");assertThat(first.at("/scope/session").asText()).isNotEqualTo(second.at("/scope/session").asText());assertThat(second.toString().contains(AuthService.hash(other))).isFalse();}
}
