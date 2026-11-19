package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.notification.SuppressionCommands;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;

/** End-to-end coverage for manually declared sensor provenance and notification-only suppression. */
@SpringBootTest(properties={"app.roles=all","app.simulation-enabled=true","app.simulated-notifications-enabled=true","app.storage=h2-dev","app.notification-contact-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","spring.datasource.url=jdbc:h2:mem:sensorsuppressionintegration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class SensorSuppressionIntegrationTest {
    static final String CHANNELS="/api/management/device-channels", SUPPRESSIONS="/api/management/suppressions";
    static final OffsetDateTime FUTURE=OffsetDateTime.now().plusDays(10);
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired SuppressionCommands commands;
    IngestionIntegrationTest fixture;

    @BeforeEach void reset() throws Exception {
        jdbc.update("DELETE FROM suppression_changes");
        jdbc.update("DELETE FROM suppression_channels");
        jdbc.update("DELETE FROM alarm_device_channels");
        jdbc.update("DELETE FROM device_suppression_members");
        jdbc.update("DELETE FROM device_suppressions");
        jdbc.update("DELETE FROM device_channels");
        fixture=new IngestionIntegrationTest(); fixture.mvc=mvc; fixture.json=json; fixture.jdbc=jdbc; fixture.bootstrap=bootstrap;
        fixture.reset();
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES ('device-a-unactivated','tenant-a','Explicit suppression fixture','fixture-a-unactivated','fixture','A2','UNKNOWN',FALSE,'LEGACY_UNVERIFIED')");
    }
    ResultActions call(org.springframework.http.HttpMethod method,String path,String tenant,String key,Object input)throws Exception{
        var builder=request(method,path).header("Authorization","Bearer "+fixture.root);
        if(tenant!=null)builder.header("X-Tenant-Id",tenant); if(key!=null)builder.header("Idempotency-Key",key);
        if(input!=null)builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder);
    }
    JsonNode body(ResultActions r)throws Exception{return fixture.body(r);}
    Map<String,Object> channel(String device,String key,String name){return Map.of("device_id",device,"channel_key",key,"name",name,"channel_type","smoke","enabled",true,"reason","人工登记");}
    JsonNode register(String key,String name)throws Exception{return body(call(POST,CHANNELS,"tenant-a","channel-"+key,channel("device-a",key,name)).andExpect(status().isCreated()));}
    Map<String,Object> sensor(boolean confirmed,List<String> ids,OffsetDateTime end){var b=new HashMap<String,Object>();b.put("scope","SENSORS");b.put("device_ids",List.of());b.put("channel_ids",ids);b.put("fire_confirmed",confirmed);b.put("reason","传感器维护窗口");b.put("starts_at",OffsetDateTime.now().minusDays(1));b.put("ends_at",end);return b;}
    JsonNode createSensor(boolean confirmed,List<String> ids,String key)throws Exception{return createSensor(sensor(confirmed,ids,FUTURE),key);}
    JsonNode createSensor(Map<String,Object> requestBody,String key)throws Exception{return body(call(POST,SUPPRESSIONS,"tenant-a",key,requestBody).andExpect(status().isOk()));}
    Map<String,Object> mutation(long revision,String reason){return Map.of("expected_revision",revision,"reason",reason);}
    Map<String,Object> edit(long revision,OffsetDateTime start,OffsetDateTime end,String reason){return Map.of("expected_revision",revision,"starts_at",start,"ends_at",end,"reason",reason);}


    @Test void lifecycleCasReplaySnapshotsAndDeletionPreserveHistory()throws Exception{
        JsonNode channel=register("lifecycle","Original sensor name"); String channelId=channel.get("id").asText();
        Map<String,Object> createBody=sensor(true,List.of(channelId),FUTURE);
        JsonNode created=createSensor(createBody,"create-lifecycle"); String id=created.get("id").asText();
        assertThat(createSensor(createBody,"create-lifecycle")).isEqualTo(created);
        assertStoredInstant(created,"created_at",jdbc.queryForObject(
            "SELECT created_at FROM device_suppressions WHERE id=?",OffsetDateTime.class,id));
        JsonNode target=body(call(GET,SUPPRESSIONS+"/"+id+"/targets","tenant-a",null,null).andExpect(status().isOk())).get(0);
        assertThat(target.get("channel_name").asText()).isEqualTo("Original sensor name");
        jdbc.update("UPDATE device_channels SET name='Renamed sensor' WHERE id=?",channelId);
        assertThat(body(call(GET,SUPPRESSIONS+"/"+id+"/targets","tenant-a",null,null).andExpect(status().isOk())).get(0).get("channel_name").asText()).isEqualTo("Original sensor name");

        var editedStarts=OffsetDateTime.now().minusDays(2);
        JsonNode edited=body(call(PUT,SUPPRESSIONS+"/"+id,"tenant-a","edit-once",edit(created.get("revision").asLong(),editedStarts,FUTURE,"调整起始时间")).andExpect(status().isOk()));
        assertThat(edited.get("revision").asLong()).isEqualTo(1);
        assertThat(body(call(PUT,SUPPRESSIONS+"/"+id,"tenant-a","edit-once",edit(0,editedStarts,FUTURE,"调整起始时间")).andExpect(status().isOk()))).isEqualTo(edited);
        assertStoredInstant(edited,"starts_at",jdbc.queryForObject(
            "SELECT starts_at FROM device_suppressions WHERE id=?",OffsetDateTime.class,id));
        assertStoredInstant(edited,"ends_at",jdbc.queryForObject(
            "SELECT ends_at FROM device_suppressions WHERE id=?",OffsetDateTime.class,id));
        call(PUT,SUPPRESSIONS+"/"+id,"tenant-a","stale-edit",edit(0,OffsetDateTime.now().minusDays(2),FUTURE,"过期修改")).andExpect(status().isConflict());
        var disableInput=Map.<String,Object>of("expected_revision",1,"enabled",false,"reason","暂时停用");
        JsonNode disabled=body(call(POST,SUPPRESSIONS+"/"+id+"/enabled","tenant-a","disable-once",disableInput).andExpect(status().isOk()));
        assertThat(body(call(POST,SUPPRESSIONS+"/"+id+"/enabled","tenant-a","disable-once",disableInput).andExpect(status().isOk()))).isEqualTo(disabled);
        assertThat(disabled.get("enabled").asBoolean()).isFalse(); assertThat(disabled.get("revision").asLong()).isEqualTo(2);
        var enableInput=Map.<String,Object>of("expected_revision",2,"enabled",true,"reason","重新启用");
        JsonNode enabled=body(call(POST,SUPPRESSIONS+"/"+id+"/enabled","tenant-a","enable-once",enableInput).andExpect(status().isOk()));
        assertThat(body(call(POST,SUPPRESSIONS+"/"+id+"/enabled","tenant-a","enable-once",enableInput).andExpect(status().isOk()))).isEqualTo(enabled);
        assertThat(enabled.get("enabled").asBoolean()).isTrue(); assertThat(enabled.get("revision").asLong()).isEqualTo(3);
        var cancelInput=mutation(3,"维护完成");
        JsonNode cancelled=body(call(POST,SUPPRESSIONS+"/"+id+"/cancel","tenant-a","cancel-once",cancelInput).andExpect(status().isOk()));
        assertThat(body(call(POST,SUPPRESSIONS+"/"+id+"/cancel","tenant-a","cancel-once",cancelInput).andExpect(status().isOk()))).isEqualTo(cancelled);
        assertStoredInstant(cancelled,"cancelled_at",jdbc.queryForObject(
            "SELECT cancelled_at FROM device_suppressions WHERE id=?",OffsetDateTime.class,id));
        assertThat(createSensor(createBody,"create-lifecycle")).isEqualTo(created);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED"); assertThat(cancelled.get("effective_state").asText()).isEqualTo("CANCELLED");
        call(POST,SUPPRESSIONS+"/"+id+"/cancel","tenant-a","cancel-stale",mutation(3,"再次取消")).andExpect(status().isConflict());
        List<JsonNode> events=body(call(GET,SUPPRESSIONS+"/"+id+"/history","tenant-a",null,null).andExpect(status().isOk())).findParents("action");
        assertThat(events).extracting(e->e.get("action").asText())
            .containsExactlyInAnyOrder("SUPPRESSION_CREATED","SUPPRESSION_UPDATED","SUPPRESSION_DISABLED","SUPPRESSION_ENABLED","SUPPRESSION_CANCELLED");
        assertThat(jdbc.queryForObject("SELECT channel_name FROM suppression_channels WHERE suppression_id=? AND channel_id=?",String.class,id,channelId)).isEqualTo("Original sensor name");

        JsonNode expired=createSensor(true,List.of(channelId),"expired-suppression"); String expiredId=expired.get("id").asText();
        jdbc.update("UPDATE device_suppressions SET starts_at=?,ends_at=? WHERE id=?",OffsetDateTime.parse("2020-01-01T00:00:00Z"),OffsetDateTime.parse("2020-01-02T00:00:00Z"),expiredId);
        var deleteInput=mutation(expired.get("revision").asLong(),"到期清理");
        JsonNode deleted=body(call(DELETE,SUPPRESSIONS+"/"+expiredId,"tenant-a","delete-expired",deleteInput).andExpect(status().isOk()));
        assertThat(body(call(DELETE,SUPPRESSIONS+"/"+expiredId,"tenant-a","delete-expired",deleteInput).andExpect(status().isOk()))).isEqualTo(deleted);
        assertThat(deleted.get("deleted").asBoolean()).isTrue(); assertThat(deleted.get("effective_state").asText()).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suppression_changes WHERE suppression_id=? AND action='SUPPRESSION_DELETED'",Long.class,expiredId)).isEqualTo(1L);
    }

    private static void assertStoredInstant(JsonNode receipt,String field,OffsetDateTime stored) {
        assertThat(OffsetDateTime.parse(receipt.required(field).textValue()).toInstant()).isEqualTo(stored.toInstant());
    }


    @Test void foreignTenantAndRestrictedDeviceCannotReadOrMutateSensorTargetsAndHistory()throws Exception{
        JsonNode channel=register("private","Private sensor"); JsonNode suppression=createSensor(true,List.of(channel.get("id").asText()),"private-suppression"); String id=suppression.get("id").asText();
        call(GET,SUPPRESSIONS+"/missing-suppression","tenant-a",null,null).andExpect(status().isNotFound());
        call(GET,SUPPRESSIONS+"/"+id,"tenant-b",null,null).andExpect(status().isNotFound());
        call(GET,SUPPRESSIONS+"/"+id+"/targets","tenant-b",null,null).andExpect(status().isNotFound());
        call(GET,SUPPRESSIONS+"/"+id+"/history","tenant-b",null,null).andExpect(status().isNotFound());
        call(PUT,SUPPRESSIONS+"/"+id,"tenant-b","foreign-edit",edit(suppression.get("revision").asLong(),OffsetDateTime.now().minusDays(1),FUTURE,"跨单位修改")).andExpect(status().isNotFound());
        call(POST,SUPPRESSIONS+"/"+id+"/cancel","tenant-b","foreign-cancel",mutation(suppression.get("revision").asLong(),"跨单位取消")).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES ('bootstrap-admin','tenant-a','device-a-unactivated')");
        call(GET,SUPPRESSIONS+"/"+id,"tenant-a",null,null).andExpect(status().isNotFound());
        call(GET,SUPPRESSIONS+"/"+id+"/targets","tenant-a",null,null).andExpect(status().isNotFound());
        call(GET,SUPPRESSIONS+"/"+id+"/history","tenant-a",null,null).andExpect(status().isNotFound());
        call(PUT,SUPPRESSIONS+"/"+id,"tenant-a","restricted-edit",edit(suppression.get("revision").asLong(),OffsetDateTime.now().minusDays(1),FUTURE,"越权")).andExpect(status().isNotFound());
        call(POST,SUPPRESSIONS+"/"+id+"/enabled","tenant-a","restricted-disable",Map.of("expected_revision",suppression.get("revision").asLong(),"enabled",false,"reason","越权")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT revision FROM device_suppressions WHERE id=?",Long.class,id)).isEqualTo(suppression.get("revision").asLong());
    }
}
