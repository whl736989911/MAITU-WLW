package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.roles=api","app.simulation-enabled=false","app.simulated-notifications-enabled=false","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:devicechannel-integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
class DeviceChannelIntegrationTest {
    static final String BASE="/api/management/device-channels";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired Bootstrap bootstrap;@Autowired JsonPointerParser parser;
    IngestionIntegrationTest f;
    @BeforeEach void reset()throws Exception{jdbc.update("DELETE FROM device_channels");f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES ('device-a-unactivated','tenant-a','Explicit channel fixture','fixture-a-unactivated','fixture','A2','UNKNOWN',FALSE,'LEGACY_UNVERIFIED')");}
    Map<String,Object> createBody(String device,String key){return Map.of("device_id",device,"channel_key",key,"name","北侧烟感","channel_type","smoke","enabled",true,"reason","人工登记");}
    Map<String,Object> updateBody(String name,long revision){return Map.of("name",name,"channel_type","smoke","enabled",true,"expected_revision",revision,"reason","更新人工台账");}
    ResultActions call(org.springframework.http.HttpMethod method,String path,String tenant,String key,Object body)throws Exception{var request=request(method,path).header("Authorization","Bearer "+f.root);if(tenant!=null)request.header("X-Tenant-Id",tenant);if(key!=null)request.header("Idempotency-Key",key);if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));return mvc.perform(request);}
    JsonNode body(ResultActions result)throws Exception{return f.body(result);}
    @Test void manualChannelCanShareStableKeyAcrossDevicesAndRetainsManualChannelFacts()throws Exception{var first=body(call(POST,BASE,"tenant-a","same-device-key",createBody("device-a","north")).andExpect(status().isCreated()));var second=body(call(POST,BASE,"tenant-a","other-device-key",createBody("device-a-unactivated","north")).andExpect(status().isCreated()));assertThat(first.get("id").asText()).isNotEqualTo(second.get("id").asText());assertThat(first.get("device_id").asText()).isEqualTo("device-a");assertThat(first.get("channel_key").asText()).isEqualTo("north");assertThat(first.get("channel_type").asText()).isEqualTo("smoke");assertThat(first.get("enabled").asBoolean()).isTrue();var page=body(call(GET,BASE+"?device_id=device-a","tenant-a",null,null).andExpect(status().isOk()));assertThat(page.get("total").asInt()).isEqualTo(1);assertThat(page.at("/items/0/channel_key").asText()).isEqualTo("north");}
    @Test void createReplayReturnsReceiptAndUpdateUsesCas()throws Exception{var first=body(call(POST,BASE,"tenant-a","create-receipt",createBody("device-a","north")).andExpect(status().isCreated()));assertThat(body(call(POST,BASE,"tenant-a","create-receipt",createBody("device-a","north")).andExpect(status().isCreated()))).isEqualTo(first);String id=first.get("id").asText();var updated=body(call(PUT,BASE+"/"+id,"tenant-a","update-receipt",updateBody("北侧烟感新版",0)).andExpect(status().isOk()));assertThat(updated.get("revision").asLong()).isEqualTo(1);assertThat(body(call(PUT,BASE+"/"+id,"tenant-a","update-receipt",updateBody("北侧烟感新版",0)).andExpect(status().isOk()))).isEqualTo(updated);call(PUT,BASE+"/"+id,"tenant-a","stale-update",updateBody("错误覆盖",0)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));}
    @Test void rejectsCrossTenantAndHiddenDevicesWithoutPersistingChannels()throws Exception{call(POST,BASE,"tenant-a","foreign-device",createBody("device-b","north")).andExpect(status().isNotFound());jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES ('bootstrap-admin','tenant-a','device-a')");call(POST,BASE,"tenant-a","hidden-device",createBody("device-a-unactivated","north")).andExpect(status().isNotFound());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_channels",Long.class)).isZero();}
}
