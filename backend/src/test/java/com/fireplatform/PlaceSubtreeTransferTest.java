package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.legacyingest.ingest.LegacyIngestService;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
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

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:placesubtreetransfer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class PlaceSubtreeTransferTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){IngestionIntegrationTest.environment(r);}
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired LegacyIngestService ingest;
    IngestionIntegrationTest f;
    String child;
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("legacy_ingest_quarantines","place_transfer_nodes","place_transfers","device_transfer_alias_history","device_transfers"))jdbc.update("DELETE FROM "+table);
        f=new IngestionIntegrationTest();f.mvc=mvc;f.json=json;f.jdbc=jdbc;f.bootstrap=bootstrap;f.parser=parser;f.reset();
        child=f.body(f.call(HttpMethod.POST,"/api/management/places",f.root,"tenant-a",UUID.randomUUID().toString(),Map.of("name","Nested floor","parent_place_id",f.placeId)).andExpect(status().isOk())).get("id").asText();
        for(int i=0;i<25;i++)jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id,integration_state) VALUES (?,'tenant-a',?,?,'legacy','Fixture address','NORMAL',FALSE,?,'LEGACY_UNVERIFIED')",id(i),"Asset "+i,"subtree-eui-"+i,child);
        jdbc.update("INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at) VALUES ('subtree-alias','tenant-a',?,'VENDOR_ID','subtree-vendor','MANUAL',CURRENT_TIMESTAMP)",id(0));
    }
    String id(int i){return "subtree-"+String.format(Locale.ROOT,"%02d",i);}
    JsonNode preview() throws Exception {
        return f.body(f.call(HttpMethod.GET,"/api/management/places/"+f.placeId+"/transfer-preview?target_tenant_id=tenant-b",f.root,"tenant-a",null,null).andExpect(status().isOk()));
    }
    Map<String,Object> input(JsonNode p){var b=new LinkedHashMap<String,Object>();b.put("target_tenant_id","tenant-b");b.put("target_parent_id",null);b.put("expected_revision",p.get("revision").asLong());b.put("snapshot",p.get("snapshot").asText());b.put("reason","Approved full ownership cutover");b.put("confirm_active_risks",false);return b;}
    JsonNode transfer(JsonNode p,String key) throws Exception {return f.body(f.call(HttpMethod.POST,"/api/management/places/"+f.placeId+"/transfer",f.root,"tenant-a",key,input(p)).andExpect(status().isOk()));}
    @Test void transfersBeyondOnePagePreservesHistoryAndAliasAndReplaysExactly() throws Exception {
        var p=preview();assertThat(p.get("places_count").asLong()).isEqualTo(2);assertThat(p.get("devices_count").asLong()).isEqualTo(25);
        var first=transfer(p,"subtree-full-once");var replay=transfer(p,"subtree-full-once");assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND device_eui LIKE 'subtree-eui-%' AND archived_at IS NOT NULL",Long.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-b' AND device_eui LIKE 'subtree-eui-%' AND archived_at IS NULL",Long.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_transfer_nodes",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_transfers",Long.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM device_aliases WHERE id='subtree-alias'",String.class)).isEqualTo("tenant-b");
        assertThat(jdbc.queryForObject("SELECT source_device_id FROM device_transfer_alias_history WHERE alias_id='subtree-alias'",String.class)).isEqualTo(id(0));
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id=?",String.class,child)).isEqualTo(f.placeId);
        var current=f.body(f.call(HttpMethod.GET,"/api/management/places",f.root,"tenant-a",null,null).andExpect(status().isOk()));assertThat(current.isEmpty()).isTrue();
        String targetChild=jdbc.queryForObject("SELECT target_place_id FROM place_transfer_nodes WHERE source_place_id=?",String.class,child);
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id=?",String.class,targetChild)).isEqualTo(first.get("target_place_id").asText());
    }
    @Test void lateAliasConflictRollsBackEveryEarlierAssetAndPlace() throws Exception {
        jdbc.update("INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at) VALUES ('last-alias','tenant-a',?,'VENDOR_ID','reserved','MANUAL',CURRENT_TIMESTAMP),('reserved-alias','tenant-b','device-b','VENDOR_ID','reserved','MANUAL',CURRENT_TIMESTAMP)",id(24));
        var p=preview();
        f.call(HttpMethod.POST,"/api/management/places/"+f.placeId+"/transfer",f.root,"tenant-a","late-alias-conflict",input(p)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRANSFER_ALIAS_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND device_eui LIKE 'subtree-eui-%' AND archived_at IS NULL",Long.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-b' AND device_eui LIKE 'subtree-eui-%'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE tenant_id='tenant-b'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_transfers",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM device_aliases WHERE id='subtree-alias'",String.class)).isEqualTo("tenant-a");
    }
    @Test void staleSubtreeAndRevokedTargetGrantCannotPartiallyTransfer() throws Exception {
        var stale=preview();jdbc.update("UPDATE devices SET revision=revision+1 WHERE id=?",id(24));
        f.call(HttpMethod.POST,"/api/management/places/"+f.placeId+"/transfer",f.root,"tenant-a","stale-subtree",input(stale)).andExpect(status().isConflict());
        var fresh=preview();jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='tenant-b'");
        f.call(HttpMethod.POST,"/api/management/places/"+f.placeId+"/transfer",f.root,"tenant-a","revoked-target",input(fresh)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_transfers",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT archived_at FROM places WHERE id=?",OffsetDateTime.class,f.placeId)).isNull();
    }
    @Test void unresolvedHardwareCommandOnLastAssetPreventsEveryOwnershipChange() throws Exception {
        jdbc.update("INSERT INTO device_service_commands(id,tenant_id,device_id,driver_key,driver_mode,service_key,risk_level,result_kind,state,arguments_json,payload_sha256,snapshot_json,actor_id,reason,idem_key,request_hash,created_at,updated_at) VALUES ('pending-transfer-command','tenant-a',?,'HIKVISION_FIRECLOUD','VENDOR_PLATFORM','reset','ENGINEERING','COMMAND','UNKNOWN','{}',?,'{}','bootstrap-admin','Unresolved real-world effect','pending-transfer-command',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(24),"0".repeat(64),"0".repeat(64));
        var p=preview();
        f.call(HttpMethod.POST,"/api/management/places/"+f.placeId+"/transfer",f.root,"tenant-a","command-blocks-subtree",input(p)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_COMMAND_PENDING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND device_eui LIKE 'subtree-eui-%' AND archived_at IS NULL",Long.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-b' AND device_eui LIKE 'subtree-eui-%'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_transfers",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_transfers",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE tenant_id='tenant-b'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM device_aliases WHERE id='subtree-alias'",String.class)).isEqualTo("tenant-a");
        assertThat(jdbc.queryForObject("SELECT state FROM device_service_commands WHERE id='pending-transfer-command'",String.class)).isEqualTo("UNKNOWN");
    }
    @Test void transferredAliasQuarantinesOldAndUndatedFactsWithoutInventingAnOwnerOrOnlineTime() throws Exception {
        transfer(preview(),"subtree-before-ingest");
        String target=jdbc.queryForObject("SELECT device_id FROM device_aliases WHERE id='subtree-alias'",String.class);
        OffsetDateTime cutover=jdbc.queryForObject("SELECT occurred_at FROM device_transfers WHERE target_device_id=?",OffsetDateTime.class,target);
        var old=ingest.ingest("HTTP_FORM_TTL","formttl",null,packet(cutover.minusDays(1),10));
        var undated=ingest.ingest("HTTP_FORM_TTL","formttl",null,packet(null,20));
        assertThat(old.failureCode()).isEqualTo("PRE_OWNERSHIP_HISTORY");assertThat(undated.failureCode()).isEqualTo("OWNERSHIP_TIME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_messages WHERE id IN (?,?) AND tenant_id IS NULL AND device_id IS NULL",Long.class,old.id(),undated.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=?",Long.class,target)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,target)).isNull();
        OffsetDateTime observed=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var live=ingest.ingest("HTTP_FORM_TTL","formttl",null,packet(observed,30));assertThat(live.state()).isEqualTo("NORMALIZED");
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,target).toInstant()).isEqualTo(observed.toInstant());
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id=?",java.math.BigDecimal.class,target)).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,id(0))).isNull();
        f.call(HttpMethod.GET,"/api/management/legacy-ingest/quarantines",f.root,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].payload_raw").doesNotExist());
    }
    String packet(OffsetDateTime at,int battery) throws Exception {
        var params=new LinkedHashMap<String,Object>();params.put("battery",battery);if(at!=null)params.put("time",at.toString());
        return json.writeValueAsString(Map.of("deviceId","subtree-vendor","parseMsg",List.of(Map.of("method","thing.event.heartbeat","params",List.of(params)))));
    }
}
