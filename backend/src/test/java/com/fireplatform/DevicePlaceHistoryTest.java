package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties={"app.roles=all","app.generic-ingest-enabled=true","app.simulation-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:placehistory;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class DevicePlaceHistoryTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    IngestionIntegrationTest f;

    @BeforeEach void reset() throws Exception {
        f=new IngestionIntegrationTest(); f.mvc=mvc; f.json=json; f.jdbc=jdbc; f.bootstrap=bootstrap; f.parser=parser; f.reset();
    }
    JsonNode place(String tenant,String name) throws Exception {
        return f.body(f.call(HttpMethod.POST,"/api/management/places",f.root,tenant,null,Map.of("name",name)).andExpect(status().isOk()));
    }
    Map<String,Object> registration(JsonNode version) {
        return new HashMap<>(Map.of("name","Placement history device","device_eui","history-"+UUID.randomUUID(),"address","Synthetic lab","place_id",f.placeId,"product_version_id",version.get("id").asText()));
    }
    JsonNode device() throws Exception { return f.register(f.published(f.product().get("id").asText(),"1"),false); }
    Map<String,Object> edit(String place,long revision) {
        var body=new HashMap<String,Object>(Map.of("name","Moved device","address","Synthetic lab","expected_revision",revision));
        if(place!=null) body.put("place_id",place);
        return body;
    }
    ResultActions edit(String id,String key,Map<String,Object> body) throws Exception { return f.call(HttpMethod.PUT,"/api/management/devices/"+id,f.root,"tenant-a",key,body); }
    ResultActions history(String token,String tenant,String id,String query) throws Exception { return f.call(HttpMethod.GET,"/api/management/devices/"+id+"/place-history"+query,token,tenant,null,null); }
    JsonNode history(String id,String query) throws Exception { return f.body(history(f.root,"tenant-a",id,query).andExpect(status().isOk())); }

    @Test void registrationAndReplayHaveExactlyOneInitialPlacement() throws Exception {
        var version=f.published(f.product().get("id").asText(),"1"); var body=registration(version);
        var created=f.body(f.call(HttpMethod.POST,"/api/management/devices",f.root,"tenant-a","history-register",body).andExpect(status().isOk()));
        var replay=f.body(f.call(HttpMethod.POST,"/api/management/devices",f.root,"tenant-a","history-register",body).andExpect(status().isOk()));
        assertThat(replay).isEqualTo(created);
        var result=history(created.get("id").asText(),"");
        assertThat(result.get("total").asLong()).isEqualTo(1);
        var entry=result.at("/items/0");
        assertThat(entry.get("action").asText()).isEqualTo("REGISTER");
        assertThat(entry.get("from_place_id").isNull()).isTrue();
        assertThat(entry.get("revision_before").isNull()).isTrue();
        assertThat(entry.get("revision_after").asLong()).isZero();
        assertThat(entry.get("to_place_id").asText()).isEqualTo(f.placeId);
        assertThat(entry.get("to_place_name").asText()).isEqualTo(jdbc.queryForObject("SELECT name FROM places WHERE id=?",String.class,f.placeId));
        assertThat(entry.get("actor_id").asText()).isEqualTo("bootstrap-admin");
        assertThat(created.get("physical_state").asText()).isEqualTo("UNKNOWN");
        assertThat(created.get("last_message_at").isNull()).isTrue();
    }
    @Test void actualMovesAreImmutableSnapshotsButMetadataAndReplayDoNotAddMoves() throws Exception {
        var created=device(); String id=created.get("id").asText(); var target=place("tenant-a","Original destination");
        var input=edit(target.get("id").asText(),0);
        var moved=f.body(edit(id,"history-move",input).andExpect(status().isOk()));
        assertThat(f.body(edit(id,"history-move",input).andExpect(status().isOk()))).isEqualTo(moved);
        edit(id,"metadata-only",edit(null,1)).andExpect(status().isOk());
        edit(id,"same-place",edit(target.get("id").asText(),2)).andExpect(status().isOk());
        var before=history(id,""); assertThat(before.get("total").asLong()).isEqualTo(2);
        assertThat(before.at("/items/0/action").asText()).isEqualTo("MOVE");
        assertThat(before.at("/items/0/from_place_id").asText()).isEqualTo(f.placeId);
        assertThat(before.at("/items/0/revision_before").asLong()).isZero();
        assertThat(before.at("/items/0/revision_after").asLong()).isEqualTo(1);
        f.call(HttpMethod.PUT,"/api/management/places/"+target.get("id").asText(),f.root,"tenant-a",null,Map.of("name","Renamed destination","expected_revision",0)).andExpect(status().isOk());
        f.call(HttpMethod.DELETE,"/api/management/places/"+f.placeId,f.root,"tenant-a",null,Map.of("expected_revision",0)).andExpect(status().isOk());
        assertThat(history(id,"")).isEqualTo(before);
        var foreign=place("tenant-b","Foreign destination");
        edit(id,"foreign-place",edit(foreign.get("id").asText(),3)).andExpect(status().isBadRequest());
        var another=place("tenant-a","Another destination");
        edit(id,"stale-move",edit(another.get("id").asText(),0)).andExpect(status().isConflict());
        assertThat(history(id,"")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT place_id FROM devices WHERE id=?",String.class,id)).isEqualTo(target.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,id)).isEqualTo(3);
    }
    @Test void historicalOrphanRequiresAnExplicitRealPlaceAndKeepsNullOriginSnapshot() throws Exception {
        var created=device(); String id=created.get("id").asText();
        jdbc.update("DELETE FROM device_place_history WHERE device_id=?",id);
        jdbc.update("UPDATE devices SET place_id=NULL WHERE id=?",id);
        edit(id,"orphan-metadata-only",edit(null,0)).andExpect(status().isBadRequest());
        var next=f.published(f.product().get("id").asText(),"2");
        f.call(HttpMethod.POST,"/api/management/devices/"+id+"/migrate-version",f.root,"tenant-a","orphan-migrate",Map.of("product_version_id",next.get("id").asText(),"expected_revision",0,"reason","Must assign a real place first")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_PLACE_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT place_id FROM devices WHERE id=?",String.class,id)).isNull();
        var destination=place("tenant-a","Explicit orphan repair destination");
        var repaired=f.body(edit(id,"orphan-explicit-repair",edit(destination.get("id").asText(),0)).andExpect(status().isOk()));
        assertThat(repaired.get("place_id").asText()).isEqualTo(destination.get("id").asText());
        var history=history(id,"");
        assertThat(history.get("total").asLong()).isEqualTo(1);
        assertThat(history.at("/items/0/action").asText()).isEqualTo("MOVE");
        assertThat(history.at("/items/0/from_place_id").isNull()).isTrue();
        assertThat(history.at("/items/0/from_place_name").isNull()).isTrue();
        assertThat(history.at("/items/0/to_place_id").asText()).isEqualTo(destination.get("id").asText());
    }
    @Test void systemAndCategoryMetadataAreExplicitOptionalCASFields() throws Exception {
        var version=f.published(f.product().get("id").asText(),"1");
        var body=registration(version); body.put("system_type","Fire alarm"); body.put("device_category","Smoke detector");
        var created=f.body(f.call(HttpMethod.POST,"/api/management/devices",f.root,"tenant-a","classification-register",body).andExpect(status().isOk()));
        String id=created.get("id").asText();
        assertThat(jdbc.queryForMap("SELECT system_type,device_category FROM devices WHERE id=?",id)).containsEntry("system_type","Fire alarm").containsEntry("device_category","Smoke detector");
        var editBody=edit(f.placeId,0); editBody.put("system_type",""); editBody.put("device_category","Heat detector");
        f.body(edit(id,"classification-edit",editBody).andExpect(status().isOk()));
        assertThat(jdbc.queryForMap("SELECT system_type,device_category FROM devices WHERE id=?",id)).containsEntry("system_type",null).containsEntry("device_category","Heat detector");
        var invalid=edit(f.placeId,1); invalid.put("system_type","Fire"+Character.toString((char)1)+" alarm"); invalid.put("device_category","Heat");
        edit(id,"classification-invalid",invalid).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForMap("SELECT system_type,device_category FROM devices WHERE id=?",id)).containsEntry("system_type",null).containsEntry("device_category","Heat detector");
    }
    @Test void failedMoveHistoryInsertRollsBackDeviceReceiptAndAudit() throws Exception {
        var created=device(); String id=created.get("id").asText(); var target=place("tenant-a","Rollback destination");
        long audits=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Long.class), receipts=jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records",Long.class);
        jdbc.execute("ALTER TABLE device_place_history ADD CONSTRAINT deny_place_move CHECK(action<>'MOVE')");
        try {
            edit(id,"rejected-history",edit(target.get("id").asText(),0)).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT place_id FROM devices WHERE id=?",String.class,id)).isEqualTo(f.placeId);
            assertThat(jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,id)).isZero();
            assertThat(history(id,"").get("total").asLong()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Long.class)).isEqualTo(audits);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records",Long.class)).isEqualTo(receipts);
        } finally { jdbc.execute("ALTER TABLE device_place_history DROP CONSTRAINT deny_place_move"); }
    }
    @Test void failedInitialHistoryInsertRollsBackRegistrationCompletely() throws Exception {
        var version=f.published(f.product().get("id").asText(),"1"); var body=registration(version);
        long audits=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Long.class), receipts=jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records",Long.class);
        jdbc.execute("ALTER TABLE device_place_history ADD CONSTRAINT deny_place_register CHECK(action<>'REGISTER')");
        try {
            f.call(HttpMethod.POST,"/api/management/devices",f.root,"tenant-a","history-register-failure",body).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,body.get("device_eui"))).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_version_history",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Long.class)).isEqualTo(audits);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records",Long.class)).isEqualTo(receipts);
        } finally { jdbc.execute("ALTER TABLE device_place_history DROP CONSTRAINT deny_place_register"); }
    }
    @Test void historyReadRequiresCurrentDeviceScopeAndNeverReturnsAnEmptySuccessForHiddenDevice() throws Exception {
        var created=device(); String id=created.get("id").asText();
        history(null,"tenant-a",id,"").andExpect(status().isUnauthorized());
        assertThat(f.body(history(f.root,null,id,"").andExpect(status().isOk()))).isEqualTo(history(id,""));
        history(f.root,"unknown-unit",id,"").andExpect(status().isForbidden());
        history(f.root,"tenant-b",id,"").andExpect(status().isNotFound());
        history(f.root,"tenant-a","missing","").andExpect(status().isNotFound());
        history(id,"");
        String customer=f.login("customer-a",IngestionIntegrationTest.INITIAL,"customer");
        history(customer,"tenant-a",id,"").andExpect(status().isForbidden());
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES ('bootstrap-admin','tenant-a','device-a')");
        history(f.root,"tenant-a",id,"").andExpect(status().isNotFound());
        history(f.root,null,id,"").andExpect(status().isNotFound());
        assertThat(history("device-a","").get("total").asLong()).isZero();
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='device:read'");
        history(f.root,"tenant-a","device-a","").andExpect(status().isForbidden());
    }
    @Test void paginationUsesRevisionOrderAndRejectsInvalidOrForgedParameters() throws Exception {
        var created=device(); String id=created.get("id").asText(); var target=place("tenant-a","Paged destination");
        edit(id,"page-move-1",edit(target.get("id").asText(),0)).andExpect(status().isOk());
        edit(id,"page-move-2",edit(f.placeId,1)).andExpect(status().isOk());
        jdbc.update("UPDATE device_place_history SET occurred_at=TIMESTAMP WITH TIME ZONE '2026-11-05 00:00:00+00' WHERE device_id=?",id);
        var first=history(id,"?page=1&size=2");
        var second=history(id,"?page=2&size=2");
        assertThat(first.get("total").asLong()).isEqualTo(3);
        assertThat(first.at("/items/0/revision_after").asLong()).isEqualTo(2);
        assertThat(first.at("/items/1/revision_after").asLong()).isEqualTo(1);
        assertThat(second.at("/items/0/action").asText()).isEqualTo("REGISTER");
        assertThat(history(id,"?page=3&size=2").get("items").isEmpty()).isTrue();
        for(String query:new String[]{"?page=0","?size=0","?size=101","?page=2147483647","?tenant_id=tenant-b","?page=1&page=2"}) history(f.root,"tenant-a",id,query).andExpect(status().isBadRequest());
    }
    @Test void batchMoveIsAtomicVersionedAndReplaysHistoryWithoutFabricatingActions() throws Exception {
        var first=device(); var second=device(); String firstId=first.get("id").asText(),secondId=second.get("id").asText();
        var target=place("tenant-a","Batch move destination");
        var body=Map.of("devices",List.of(Map.of("device_id",firstId,"expected_revision",0),Map.of("device_id",secondId,"expected_revision",0)),"place_id",target.get("id").asText());
        var moved=f.body(f.call(HttpMethod.POST,"/api/management/devices/batch-move",f.root,"tenant-a","batch-place-move",body).andExpect(status().isOk()));
        assertThat(moved.get("items")).hasSize(2);
        assertThat(jdbc.queryForList("SELECT place_id FROM devices WHERE id IN (?,?)",String.class,firstId,secondId)).containsOnly(target.get("id").asText());
        assertThat(history(firstId,"").get("total").asLong()).isEqualTo(2);
        assertThat(history(secondId,"").get("total").asLong()).isEqualTo(2);
        assertThat(f.body(f.call(HttpMethod.POST,"/api/management/devices/batch-move",f.root,"tenant-a","batch-place-move",body).andExpect(status().isOk()))).isEqualTo(moved);
        var foreign=place("tenant-b","Foreign batch move");
        var rejected=Map.of("devices",List.of(Map.of("device_id",firstId,"expected_revision",1),Map.of("device_id",secondId,"expected_revision",1)),"place_id",foreign.get("id").asText());
        f.call(HttpMethod.POST,"/api/management/devices/batch-move",f.root,"tenant-a","batch-place-foreign",rejected).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT place_id FROM devices WHERE id IN (?,?)",String.class,firstId,secondId)).containsOnly(target.get("id").asText());
    }
    @Test void batchVersionMigrationRevalidatesEveryRevisionAndPreservesDeviceFacts() throws Exception {
        var product=f.product(); var old=f.published(product.get("id").asText(),"1"); var next=f.published(product.get("id").asText(),"2");
        var first=f.register(old,false); var second=f.register(old,false); String firstId=first.get("id").asText(),secondId=second.get("id").asText();
        var body=Map.of("devices",List.of(Map.of("device_id",firstId,"expected_revision",0),Map.of("device_id",secondId,"expected_revision",0)),"product_version_id",next.get("id").asText(),"reason","Batch migration fixture");
        var migrated=f.body(f.call(HttpMethod.POST,"/api/management/devices/batch-migrate-version",f.root,"tenant-a","batch-version",body).andExpect(status().isOk()));
        assertThat(migrated.get("items")).hasSize(2);
        assertThat(jdbc.queryForList("SELECT product_version_id FROM devices WHERE id IN (?,?)",String.class,firstId,secondId)).containsOnly(next.get("id").asText());
        assertThat(jdbc.queryForList("SELECT physical_state FROM devices WHERE id IN (?,?)",String.class,firstId,secondId)).containsOnly("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_version_history WHERE device_id IN (?,?) AND from_version_id=? AND to_version_id=?",Long.class,firstId,secondId,old.get("id").asText(),next.get("id").asText())).isEqualTo(2L);
        var stale=Map.of("devices",List.of(Map.of("device_id",firstId,"expected_revision",0),Map.of("device_id",secondId,"expected_revision",1)),"product_version_id",old.get("id").asText(),"reason","Stale batch fixture");
        f.call(HttpMethod.POST,"/api/management/devices/batch-migrate-version",f.root,"tenant-a","batch-version-stale",stale).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        assertThat(jdbc.queryForList("SELECT product_version_id FROM devices WHERE id IN (?,?)",String.class,firstId,secondId)).containsOnly(next.get("id").asText());
    }
    @Test void upgradeFromV41DoesNotInventPlacementHistory() {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:history-upgrade-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(dataSource).target("41").load().migrate();
        var old=new JdbcTemplate(dataSource);
        old.update("INSERT INTO tenants(id,name) VALUES ('old-unit','Old unit')");
        old.update("INSERT INTO places(id,tenant_id,name) VALUES ('old-place','old-unit','Old place')");
        old.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id) VALUES ('old-device','old-unit','Old device','old-eui','old-model','old-address','UNKNOWN',FALSE,'old-place')");
        var before=old.queryForMap("SELECT * FROM devices WHERE id='old-device'");
        Flyway.configure().dataSource(dataSource).target("42").load().migrate();
        assertThat(old.queryForObject("SELECT COUNT(*) FROM device_place_history",Long.class)).isZero();
        assertThat(old.queryForMap("SELECT * FROM devices WHERE id='old-device'")).isEqualTo(before);
    }
}
