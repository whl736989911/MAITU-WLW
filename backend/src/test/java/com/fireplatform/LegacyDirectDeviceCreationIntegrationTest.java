package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","fire.hikvision.enabled=false",
    "fire.legacy.ingest-token=direct-device-fixture-token",
    "spring.datasource.url=jdbc:h2:mem:legacy-direct-create;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class LegacyDirectDeviceCreationIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    IngestionIntegrationTest fixture;
    String profile;

    @BeforeEach void setup() throws Exception {
        fixture=new IngestionIntegrationTest();
        fixture.mvc=mvc;fixture.json=json;fixture.jdbc=jdbc;fixture.bootstrap=bootstrap;fixture.parser=parser;
        fixture.reset();
        String product="direct-product-"+UUID.randomUUID();profile="direct-profile-"+UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,name,manufacturer,model,description,state) VALUES(?,?,?,?,?,'ACTIVE')",
            product,"Direct smoke protocol","Controlled fixture","Direct CAT1 smoke","Native manufacturer report fixture");
        jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,revision,schema_json,capabilities_json,parser_tested,schema_format,legacy_product_id,created_at) VALUES(?,?,1,'DRAFT',0,'{}','{}',FALSE,'LEGACY_ATTR_JS_V1',?,CURRENT_TIMESTAMP)",
            profile,product,"direct-protocol-"+UUID.randomUUID());
    }

    @Test void locallyCreatedDeviceAcceptsOriginalManufacturerAlarmWithoutUpstreamDeviceImport() throws Exception {
        String serial="direct-"+UUID.randomUUID(), key="direct-create-"+UUID.randomUUID();
        Map<String,Object> input=registration(serial,fixture.placeId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE status='PUBLISHED'",Long.class)).isZero();
        JsonNode created=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",key,input).andExpect(status().isOk()));
        String id=created.path("id").asText();
        assertThat(created.path("physical_state").asText()).isEqualTo("UNKNOWN");
        assertThat(created.path("integration_state").asText()).isEqualTo("PENDING_INTEGRATION");
        assertThat(fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",key,input).andExpect(status().isOk())).path("id").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isEqualTo(1);

        OffsetDateTime observed=OffsetDateTime.now().minusSeconds(1);
        String raw=manufacturerAlarm(serial,observed);
        mvc.perform(post("/api/notify/v1/wrong-direct-token/formttl/telemetry").contentType("application/json").content(raw)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,id)).isZero();
        JsonNode receipt=json.readTree(mvc.perform(post("/api/notify/v1/direct-device-fixture-token/formttl/telemetry").contentType("application/json").content(raw))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(receipt.path("state").asText()).isEqualTo("NORMALIZED");
        assertThat(jdbc.queryForObject("SELECT payload_raw FROM legacy_ingest_messages WHERE id=?",String.class,receipt.path("id").asText())).isEqualTo(raw);
        assertThat(jdbc.queryForObject("SELECT device_id FROM legacy_ingest_messages WHERE id=?",String.class,receipt.path("id").asText())).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND tenant_id='tenant-a' AND physical_state='FIRE'",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id='device-a'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT place_id FROM devices WHERE id=?",String.class,id)).isEqualTo(fixture.placeId);
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,id)).isEqualTo("CONNECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE serial=?",Long.class,serial)).isZero();
    }

    @Test void foreignPlaceAndUnregisteredReportsCannotCreateOrClaimAssets() throws Exception {
        String serial="unregistered-"+UUID.randomUUID();
        String foreignPlace=fixture.body(fixture.call(HttpMethod.POST,"/api/management/places",fixture.root,"tenant-b",UUID.randomUUID().toString(),
            Map.of("name","Foreign fixture place")).andExpect(status().isOk())).path("id").asText();
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),registration(serial,foreignPlace)).andExpect(status().isBadRequest());
        JsonNode receipt=json.readTree(mvc.perform(post("/api/notify/v1/direct-device-fixture-token/formttl/telemetry").contentType("application/json").content(manufacturerAlarm(serial,OffsetDateTime.now())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(receipt.path("failure_code").asText()).isEqualTo("UNKNOWN_DEVICE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isZero();
        assertThat(jdbc.queryForObject("SELECT device_id FROM legacy_ingest_messages WHERE id=?",String.class,receipt.path("id").asText())).isNull();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM legacy_ingest_messages WHERE id=?",String.class,receipt.path("id").asText())).isNull();
    }

    @Test void aepAssociationChecksRecentImeiEvidenceAndOnlyRoutesFreshAepReports() throws Exception {
        jdbc.update("UPDATE product_versions SET legacy_product_id='500' WHERE id=?",profile);
        String imei="860000000000019", upstream=UUID.randomUUID().toString().replace("-","");
        String id=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),registration(imei,fixture.placeId))
            .andExpect(status().isOk())).path("id").asText();
        String path="/api/management/vendor-devices/"+id+"/aep-onboarding", key=UUID.randomUUID().toString();
        var input=Map.of("upstream_device_id",upstream,"expected_revision",0);
        OffsetDateTime evidenceAt=OffsetDateTime.now().minusSeconds(3);
        assertThat(aepReport(upstream,"860000000000027",evidenceAt).path("failure_code").asText()).isEqualTo("UNKNOWN_DEVICE");
        fixture.call(HttpMethod.POST,path,fixture.root,"tenant-a",key,input).andExpect(status().isBadRequest());

        var evidence=aepReport(upstream,imei,evidenceAt.plusSeconds(1));
        assertThat(evidence.path("failure_code").asText()).isEqualTo("UNKNOWN_DEVICE");
        fixture.call(HttpMethod.POST,path,fixture.root,"tenant-b",key,input).andExpect(status().isNotFound());
        fixture.call(HttpMethod.POST,path,fixture.root,"tenant-a",key,Map.of("upstream_device_id",upstream,"expected_revision",1))
            .andExpect(status().isConflict());
        var bound=fixture.body(fixture.call(HttpMethod.POST,path,fixture.root,"tenant-a",key,input).andExpect(status().isOk()));
        assertThat(bound.path("revision").asInt()).isEqualTo(1);
        assertThat(bound.path("upstream_ids").get(0).asText()).isEqualTo(upstream);
        assertThat(fixture.body(fixture.call(HttpMethod.POST,path,fixture.root,"tenant-a",key,input)
            .andExpect(status().isOk()))).isEqualTo(bound);
        assertThat(fixture.body(fixture.call(HttpMethod.GET,path,fixture.root,"tenant-a",null,null)
            .andExpect(status().isOk()))).isEqualTo(bound);
        assertThat(jdbc.queryForObject("SELECT device_id FROM legacy_ingest_messages WHERE id=?",String.class,evidence.path("id").asText())).isNull();
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,id)).isNull();

        assertThat(nativeReport(manufacturerAlarm(upstream,OffsetDateTime.now())).path("failure_code").asText()).isEqualTo("UNKNOWN_DEVICE");
        assertThat(aepReport(upstream,imei,evidenceAt.minusSeconds(1)).path("failure_code").asText()).isEqualTo("PRE_AEP_BINDING");
        assertThat(aepReport(upstream,imei,null).path("failure_code").asText()).isEqualTo("AEP_BINDING_TIME_UNKNOWN");
        var after=OffsetDateTime.now().plusSeconds(1);
        assertThat(aepReport(upstream,"860000000000027",after).path("failure_code").asText()).isEqualTo("AEP_IMEI_MISMATCH");
        var matched=aepReport(upstream,imei,after);
        assertThat(matched.path("state").asText()).isEqualTo("PENDING_DECODER");
        assertThat(jdbc.queryForObject("SELECT device_id FROM legacy_ingest_messages WHERE id=?",String.class,matched.path("id").asText())).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,id).toInstant().toEpochMilli()).isEqualTo(after.toInstant().toEpochMilli());
        var detail=fixture.body(fixture.call(HttpMethod.GET,"/api/management/devices/"+id,fixture.root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(detail.at("/device/connectivity").asText()).isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,id)).isEqualTo("UNKNOWN");
    }

    private JsonNode aepReport(String upstream,String imei,OffsetDateTime at) throws Exception {
        var body=json.createObjectNode().put("deviceId",upstream).put("messageType","dataReport");
        if(at!=null)body.put("timestamp",at.toInstant().toEpochMilli());
        body.putObject("payload").put("battery",100).put("IMEI",imei);
        return json.readTree(mvc.perform(post("/api/notify/v1/direct-device-fixture-token/aep_turbo/telemetry")
            .contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void repairedDeviceCanBeRegisteredAgainWithoutRewritingHistoryOrAcceptingLateReports() throws Exception {
        String serial="repaired-"+UUID.randomUUID(), upstream="radio-"+UUID.randomUUID();
        var input=new LinkedHashMap<String,Object>(registration(serial,fixture.placeId));input.put("upstream_device_id",upstream);
        String original=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isOk())).path("id").asText();
        nativeReport(manufacturerAlarm(upstream,OffsetDateTime.now().minusMinutes(1)));
        String originalAlarm=jdbc.queryForObject("SELECT id FROM alarms WHERE device_id=?",String.class,original);
        archive(original);

        String key=UUID.randomUUID().toString();
        String replacement=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",key,input).andExpect(status().isOk())).path("id").asText();
        assertThat(replacement).isNotEqualTo(original);
        assertThat(fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",key,input).andExpect(status().isOk())).path("id").asText()).isEqualTo(replacement);
        assertThat(jdbc.queryForObject("SELECT archived_at FROM devices WHERE id=?",OffsetDateTime.class,original)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT device_id FROM alarms WHERE id=?",String.class,originalAlarm)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE device_id=?",Long.class,replacement)).isEqualTo(2);
        OffsetDateTime boundary=jdbc.queryForObject("SELECT ownership_from FROM devices WHERE id=?",OffsetDateTime.class,replacement);
        assertThat(boundary).isNotNull();

        assertThat(nativeReport(manufacturerAlarm(upstream,boundary.minusSeconds(1))).path("failure_code").asText()).isEqualTo("PRE_OWNERSHIP_HISTORY");
        var untimed=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(manufacturerAlarm(upstream,boundary));
        untimed.remove("ts");
        assertThat(nativeReport(json.writeValueAsString(untimed)).path("failure_code").asText()).isEqualTo("OWNERSHIP_TIME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,replacement)).isZero();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,replacement)).isEqualTo("UNKNOWN");
        assertThat(nativeReport(manufacturerAlarm(upstream,boundary.plusSeconds(1))).path("state").asText()).isEqualTo("NORMALIZED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,replacement)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT device_id FROM alarms WHERE id=?",String.class,originalAlarm)).isEqualTo(original);

        archive(replacement);
        String third=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isOk())).path("id").asText();
        assertThat(third).isNotEqualTo(original).isNotEqualTo(replacement);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND archived_at IS NULL",Long.class,serial)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id IN (?,?)",Long.class,original,replacement)).isEqualTo(2);
    }

    @Test void reRegisteredDeviceReceivesItsHistoricalVendorAliasWithoutReenteringOpaqueSourceId() throws Exception {
        String serial="repair-imei-"+UUID.randomUUID(),upstream="opaque-source-"+UUID.randomUUID();
        var originalInput=new LinkedHashMap<String,Object>(registration(serial,fixture.placeId));
        originalInput.put("upstream_device_id",upstream);
        String original=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),originalInput).andExpect(status().isOk())).path("id").asText();
        nativeReport(manufacturerAlarm(upstream,OffsetDateTime.now().minusMinutes(1)));
        String originalAlarm=jdbc.queryForObject("SELECT id FROM alarms WHERE device_id=?",String.class,original);
        archive(original);
        String otherPlace=fixture.body(fixture.call(HttpMethod.POST,"/api/management/places",fixture.root,"tenant-b",UUID.randomUUID().toString(),Map.of("name","Reinstalled location")).andExpect(status().isOk())).path("id").asText();
        String replacement=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-b",UUID.randomUUID().toString(),registration(serial,otherPlace)).andExpect(status().isOk())).path("id").asText();
        OffsetDateTime boundary=jdbc.queryForObject("SELECT ownership_from FROM devices WHERE id=?",OffsetDateTime.class,replacement);
        assertThat(nativeReport(manufacturerAlarm(upstream,boundary.minusSeconds(1))).path("failure_code").asText()).isEqualTo("PRE_OWNERSHIP_HISTORY");
        var untimed=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(manufacturerAlarm(upstream,boundary));
        untimed.remove("ts");
        assertThat(nativeReport(json.writeValueAsString(untimed)).path("failure_code").asText()).isEqualTo("OWNERSHIP_TIME_UNKNOWN");
        JsonNode receipt=nativeReport(manufacturerAlarm(upstream,boundary.plusSeconds(1)));
        assertThat(receipt.path("state").asText()).isEqualTo("NORMALIZED");
        assertThat(jdbc.queryForObject("SELECT device_id FROM legacy_ingest_messages WHERE id=?",String.class,receipt.path("id").asText())).isEqualTo(replacement);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM alarms WHERE device_id=?",String.class,replacement)).isEqualTo("tenant-b");
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,replacement).toInstant()).isEqualTo(boundary.plusSeconds(1).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(jdbc.queryForObject("SELECT integration_state FROM devices WHERE id=?",String.class,replacement)).isEqualTo("CONNECTED");
        assertThat(jdbc.queryForObject("SELECT device_id FROM alarms WHERE id=?",String.class,originalAlarm)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT device_id FROM device_aliases WHERE alias_value=?",String.class,upstream)).isEqualTo(original);
        var conflicting=new LinkedHashMap<String,Object>(registration("another-radio-"+UUID.randomUUID(),fixture.placeId));
        conflicting.put("upstream_device_id",upstream);
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),conflicting).andExpect(status().isConflict());
    }

    @Test void ambiguousHistoricalVendorAliasesCannotChooseAnOwnerOrCreateAnAlarm() throws Exception {
        String upstream="shared-history-"+UUID.randomUUID(),serialA="history-a-"+UUID.randomUUID(),serialB="history-b-"+UUID.randomUUID();
        String otherPlace=fixture.body(fixture.call(HttpMethod.POST,"/api/management/places",fixture.root,"tenant-b",UUID.randomUUID().toString(),Map.of("name","Historical location")).andExpect(status().isOk())).path("id").asText();
        for(String tenant:java.util.List.of("tenant-a","tenant-b")) {
            var input=new LinkedHashMap<String,Object>(registration(tenant.equals("tenant-a")?serialA:serialB,tenant.equals("tenant-a")?fixture.placeId:otherPlace));
            input.put("upstream_device_id",upstream);
            String original=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,tenant,UUID.randomUUID().toString(),input).andExpect(status().isOk())).path("id").asText();
            fixture.call(HttpMethod.POST,"/api/management/devices/"+original+"/archive",fixture.root,tenant,UUID.randomUUID().toString(),Map.of("expected_revision",0,"reason","Removed for repair")).andExpect(status().isOk());
        }
        String replacementA=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),registration(serialA,fixture.placeId)).andExpect(status().isOk())).path("id").asText();
        String replacementB=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),registration(serialB,fixture.placeId)).andExpect(status().isOk())).path("id").asText();
        OffsetDateTime boundary=jdbc.queryForObject("SELECT ownership_from FROM devices WHERE id=?",OffsetDateTime.class,replacementB);
        JsonNode receipt=nativeReport(manufacturerAlarm(upstream,boundary.plusSeconds(1)));
        assertThat(receipt.path("failure_code").asText()).isEqualTo("UNKNOWN_DEVICE");
        assertThat(jdbc.queryForObject("SELECT device_id FROM legacy_ingest_messages WHERE id=?",String.class,receipt.path("id").asText())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id IN (?,?)",Long.class,replacementA,replacementB)).isZero();
    }

    @Test void activePhysicalIdentifierAndReportAliasCannotBeClaimedByAnotherRegistration() throws Exception {
        String serial="active-"+UUID.randomUUID(), upstream="active-radio-"+UUID.randomUUID();
        var input=new LinkedHashMap<String,Object>(registration(serial,fixture.placeId));input.put("upstream_device_id",upstream);
        String active=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isOk())).path("id").asText();
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isConflict());
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),registration(upstream,fixture.placeId)).andExpect(status().isConflict());
        input.put("serial","another-"+UUID.randomUUID());
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT device_id FROM device_aliases WHERE tenant_id='tenant-a' AND kind='VENDOR_ID' AND alias_value=?",String.class,upstream)).isEqualTo(active);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,input.get("serial"))).isZero();
    }

    @Test void batchReRegistrationRollsBackHistoricalAliasReassignmentIfAnotherRowConflicts() throws Exception {
        String serial="batch-repair-"+UUID.randomUUID(), activeSerial="batch-active-"+UUID.randomUUID();
        var input=registration(serial,fixture.placeId);
        String old=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),input).andExpect(status().isOk())).path("id").asText();
        archive(old);
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices",fixture.root,"tenant-a",UUID.randomUUID().toString(),registration(activeSerial,fixture.placeId)).andExpect(status().isOk());
        var repairedRow=Map.of("name","Repaired smoke","serial",serial,"place_id",fixture.placeId,"legacy_profile_id",profile);
        var blockedRow=Map.of("name","Duplicate active smoke","serial",activeSerial,"place_id",fixture.placeId,"legacy_profile_id",profile);
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices/import",fixture.root,"tenant-a",UUID.randomUUID().toString(),Map.of("source","LEGACY_REALTIME","rows",java.util.List.of(repairedRow,blockedRow))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT device_id FROM device_aliases WHERE tenant_id='tenant-a' AND kind='EUI' AND alias_value=?",String.class,serial)).isEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND archived_at IS NULL",Long.class,serial)).isZero();
        var result=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices/import",fixture.root,"tenant-a",UUID.randomUUID().toString(),Map.of("source","LEGACY_REALTIME","rows",java.util.List.of(repairedRow))).andExpect(status().isOk()));
        String replacement=result.path("items").get(0).path("id").asText();
        assertThat(replacement).isNotEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT device_id FROM device_aliases WHERE tenant_id='tenant-a' AND kind='EUI' AND alias_value=?",String.class,serial)).isEqualTo(replacement);
    }

    private void archive(String id) throws Exception {
        long revision=jdbc.queryForObject("SELECT revision FROM devices WHERE id=?",Long.class,id);
        fixture.call(HttpMethod.POST,"/api/management/devices/"+id+"/archive",fixture.root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("expected_revision",revision,"reason","Removed for repair")).andExpect(status().isOk());
    }

    private JsonNode nativeReport(String body) throws Exception {
        return json.readTree(mvc.perform(post("/api/notify/v1/direct-device-fixture-token/formttl/telemetry").contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<String,Object> registration(String serial,String place) {
        return Map.of("source","LEGACY_REALTIME","name","Direct local smoke","serial",serial,"place_id",place,
            "legacy_profile_id",profile);
    }
    private String manufacturerAlarm(String serial,OffsetDateTime at) throws Exception {
        var params=new LinkedHashMap<String,Object>();
        params.put("deviceStateCode","1001001");params.put("battery",88);params.put("smoke_value",12);
        return json.writeValueAsString(Map.of("deviceId",serial,"messageType","Alarm","deviceStateCode","1001001",
            "ts",at.toInstant().toEpochMilli(),"tenant_id","tenant-b","place_id","untrusted-vendor-place","params",json.writeValueAsString(params)));
    }
}
