package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.roles=api","fire.hikvision.enabled=false","spring.datasource.url=jdbc:h2:mem:device-telemetry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DeviceTelemetryIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){IngestionIntegrationTest.environment(registry);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    IngestionIntegrationTest fixture;
    final OffsetDateTime at=OffsetDateTime.parse("2026-11-14T02:00:00Z");

    @BeforeEach void setup()throws Exception{
        fixture=new IngestionIntegrationTest();fixture.mvc=mvc;fixture.json=json;fixture.jdbc=jdbc;fixture.bootstrap=bootstrap;fixture.parser=parser;fixture.reset();
    }
    void sample(String tenant,String device,String params,OffsetDateTime observed){
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,NULL,?,'PARAM','observed.monitor',?,?,?)",UUID.randomUUID().toString(),tenant,device,params,observed,at.plusHours(1));
    }
    JsonNode current(String device)throws Exception{
        return json.readTree(mvc.perform(get("/api/management/devices/"+device+"/telemetry").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void latestValuesKeepTheirOwnTimesZeroAndDecimalWithoutMixingOtherDevices()throws Exception{
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES('device-peer','tenant-a','Peer','peer-eui','generic','','UNKNOWN',FALSE)");
        N04TestScopes.grantExisting(jdbc);
        sample("tenant-a","device-a","{\"temperature\":0.10000000000000000001}",at);
        sample("tenant-a","device-a","{\"humidity\":0}",at.plusMinutes(1));
        sample("tenant-a","device-peer","{\"temperature\":999}",at.plusMinutes(2));
        sample("tenant-b","device-b","{\"temperature\":888}",at.plusMinutes(3));
        var result=current("device-a");
        assertThat(result.get("total").asLong()).isEqualTo(1);
        assertThat(result.at("/items/0/device_id").asText()).isEqualTo("device-a");
        var readings=result.at("/items/0/readings");assertThat(readings.size()).isEqualTo(2);
        var temperature=readings.get(0).get("metric").asText().equals("temperature")?readings.get(0):readings.get(1);
        var humidity=readings.get(0).get("metric").asText().equals("humidity")?readings.get(0):readings.get(1);
        assertThat(temperature.get("value").asText()).isEqualTo("0.10000000000000000001");
        assertThat(OffsetDateTime.parse(temperature.get("observed_at").asText()).toInstant()).isEqualTo(at.toInstant());
        assertThat(humidity.get("value").asText()).isEqualTo("0");
        assertThat(OffsetDateTime.parse(humidity.get("observed_at").asText()).toInstant()).isEqualTo(at.plusMinutes(1).toInstant());
        assertThat(temperature.get("unit").isNull()).isTrue();assertThat(temperature.get("received_at").isNull()).isTrue();
        assertThat(temperature.get("unit_status").asText()).isEqualTo("UNKNOWN");
    }
    @Test void emptyDeviceHasNoInventedReadingsAndWrongTenantOrQueryCannotChangeScope()throws Exception{
        assertThat(current("device-a").get("items").isEmpty()).isTrue();
        mvc.perform(get("/api/management/devices/device-b/telemetry").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isNotFound());
        mvc.perform(get("/api/management/devices/device-a/telemetry").header("Authorization","Bearer "+fixture.root)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/management/devices/device-a/telemetry").param("device_id","device-b").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isBadRequest());
        var customer=fixture.login("customer-a",IngestionIntegrationTest.INITIAL,"customer");
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/auth/change-password",customer,null,null,java.util.Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",IngestionIntegrationTest.PASSWORD)).andExpect(status().isNoContent());
        customer=fixture.login("customer-a",IngestionIntegrationTest.PASSWORD,"customer");
        mvc.perform(get("/api/customer/devices/device-a/telemetry").header("Authorization","Bearer "+customer)).andExpect(status().isOk());
        mvc.perform(get("/api/customer/devices/device-b/telemetry").header("Authorization","Bearer "+customer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/customer/devices/device-a/telemetry").header("Authorization","Bearer "+customer).header("X-Tenant-Id","tenant-b")).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='device:read'");
        mvc.perform(get("/api/management/devices/device-a/telemetry").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isForbidden());
    }
    JsonNode history(String device,java.util.Map<String,String> query)throws Exception{
        var request=get("/api/management/devices/"+device+"/telemetry/history").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a");
        query.forEach(request::param);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void historyPagesUseSourceTimeAndExcludePeerDevicesAndCurrentSnapshots()throws Exception{
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES('history-peer','tenant-a','Peer','history-peer-eui','generic','','UNKNOWN',FALSE)");
        N04TestScopes.grantExisting(jdbc);
        sample("tenant-a","device-a","{\"temperature\":1}",at);
        sample("tenant-a","device-a","{\"temperature\":2}",at.plusNanos(1000));
        sample("tenant-a","device-a","{\"temperature\":3}",at.plusNanos(2000));
        sample("tenant-a","device-a","{\"temperature\":4}",null);
        sample("tenant-a","history-peer","{\"temperature\":999}",at.plusNanos(1000));
        sample("tenant-b","device-b","{\"temperature\":888}",at.plusNanos(1000));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at,source_platform) VALUES('history-snapshot','tenant-a','device-a','OTHER','HIKVISION_CURRENT_MONITOR','{\"source\":\"HIKVISION_FIRECLOUD\",\"temperature\":555}',?,?,'hikvision_firecloud')",at,at.plusHours(1));
        var first=history("device-a",java.util.Map.of("page","1","size","1","from",at.toString(),"to",at.plusNanos(2000).toString()));
        var second=history("device-a",java.util.Map.of("page","2","size","1","from",at.toString(),"to",at.plusNanos(2000).toString()));
        assertThat(first.get("total").asLong()).isEqualTo(2);
        assertThat(first.at("/items/0/readings/0/value").asText()).isEqualTo("2");
        assertThat(second.get("total").asLong()).isEqualTo(2);
        assertThat(second.at("/items/0/readings/0/value").asText()).isEqualTo("1");
        assertThat(first.at("/items/0/event_id")).isNotEqualTo(second.at("/items/0/event_id"));
        var all=history("device-a",java.util.Map.of());
        assertThat(all.get("total").asLong()).isEqualTo(4);
        assertThat(all.at("/items/3/observed_at").isNull()).isTrue();
        assertThat(all.at("/items/3/received_at").isNull()).isTrue();
        assertThat(all.at("/items/3/readings/0/value").asText()).isEqualTo("4");
    }
    @Test void historyRejectsScopeOverridesInvalidTimesAndMissingDeviceRead()throws Exception{
        String path="/api/management/devices/device-a/telemetry/history";
        mvc.perform(get("/api/management/devices/device-b/telemetry/history").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization","Bearer "+fixture.root)).andExpect(status().isBadRequest());
        for(var query:java.util.List.of(java.util.Map.of("device_id","device-b"),java.util.Map.of("from","not-a-time"),java.util.Map.of("from",at.toString(),"to",at.toString()))){
            var request=get(path).header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a");query.forEach(request::param);mvc.perform(request).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).param("page","1","2").header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isBadRequest());
        var customer=fixture.login("customer-a",IngestionIntegrationTest.INITIAL,"customer");
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/auth/change-password",customer,null,null,java.util.Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",IngestionIntegrationTest.PASSWORD)).andExpect(status().isNoContent());
        customer=fixture.login("customer-a",IngestionIntegrationTest.PASSWORD,"customer");
        mvc.perform(get("/api/customer/devices/device-a/telemetry/history").header("Authorization","Bearer "+customer)).andExpect(status().isOk());
        mvc.perform(get("/api/customer/devices/device-b/telemetry/history").header("Authorization","Bearer "+customer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/customer/devices/device-a/telemetry/history").header("Authorization","Bearer "+customer).header("X-Tenant-Id","tenant-b")).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='device:read'");
        mvc.perform(get(path).header("Authorization","Bearer "+fixture.root).header("X-Tenant-Id","tenant-a")).andExpect(status().isForbidden());
    }
}
