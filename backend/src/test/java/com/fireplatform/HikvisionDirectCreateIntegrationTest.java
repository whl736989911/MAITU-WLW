package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionConfigService;
import com.fireplatform.hikvision.HikvisionIngestion;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"app.roles=api","fire.hikvision.enabled=false","app.data-transfer-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:hikvision-direct-create;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class HikvisionDirectCreateIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired HikvisionClient client;
    @Autowired HikvisionIngestion ingestion;
    @MockitoSpyBean HikvisionConfigService configuration;
    IngestionIntegrationTest fixture;
    HttpServer server;
    AtomicInteger adds = new AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<String> addRequest;
    java.util.concurrent.atomic.AtomicReference<String> cloudReply;
    java.util.concurrent.atomic.AtomicInteger cloudStatus;
    String serial, external;

    @BeforeEach void setup() throws Exception {
        for(String table:List.of("hikvision_alarm_state","hikvision_fault_state","hikvision_raw_records","hikvision_discovered_devices"))
            jdbc.update("DELETE FROM "+table);
        fixture=new IngestionIntegrationTest();fixture.mvc=mvc;fixture.json=json;fixture.jdbc=jdbc;fixture.bootstrap=bootstrap;fixture.parser=parser;fixture.reset();
        serial="direct-"+UUID.randomUUID(); external="cloud-"+UUID.randomUUID();
        addRequest=new java.util.concurrent.atomic.AtomicReference<>();
        cloudReply=new java.util.concurrent.atomic.AtomicReference<>();
        cloudStatus=new java.util.concurrent.atomic.AtomicInteger(200);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/artemis/api/device/v2/add",exchange->{
            adds.incrementAndGet();
            addRequest.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] response=cloudReply.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(cloudStatus.get(),response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        cloudReply.set(json.writeValueAsString(Map.of("code","0","msg","ok","data",Map.of("deviceID",external))));
        server.start();
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        client.configure(endpoint,"fixture-key","fixture-secret");
        doReturn(new HikvisionConfigService.Runtime(endpoint,"fixture-key","fixture-secret",true,86400,"800235",true,1,client.session())).when(configuration).refreshRuntime();
    }
    @AfterEach void stopServer() { if(server!=null)server.stop(0); }

    @Test void createsWithoutDiscoveryOrPublishedVersionAndAppliesOnlyReportsAfterLocalOwnership() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE status='PUBLISHED'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE serial=?",Long.class,serial)).isZero();
        OffsetDateTime earlyObserved=OffsetDateTime.now().minusSeconds(1);
        ingestion.accept(HikvisionClient.Stream.DEVICE_EVENT,json.valueToTree(List.of(Map.of("deviceID",external,
            "eventID","early-"+external,"eventType","100209","happenTime",earlyObserved.toString()))),OffsetDateTime.now());
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_device_id=? AND stream_kind='DEVICE_EVENT'",
            String.class,external)).isEqualTo("PENDING_ASSIGNMENT");
        Map<String,Object> input=payload(serial);
        input.put("verification_code","verify-123");
        input.put("location","Building A");
        String key="direct-"+UUID.randomUUID();
        JsonNode created=fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,
            "/api/management/vendor-devices/hikvision-create",fixture.root,"tenant-a",key,input).andExpect(status().isOk()));
        assertThat(created.path("registration").path("status").asText()).isEqualTo("COMMISSIONED");
        String device=created.path("registration").path("device_id").asText();
        assertThat(device).isNotBlank();
        JsonNode submitted=json.readTree(addRequest.get());
        assertThat(submitted.path("deviceName").asText()).isEqualTo("Direct smoke");
        assertThat(submitted.path("deviceSerial").asText()).isEqualTo(serial);
        assertThat(submitted.path("deviceModel").asText()).isEqualTo("800235");
        assertThat(submitted.path("deviceType").asInt()).isEqualTo(600002);
        assertThat(submitted.path("communicationType").asInt()).isEqualTo(1);
        assertThat(submitted.path("verificationCode").asText()).isEqualTo("verify-123");
        assertThat(submitted.path("unitID").asText()).isEqualTo("unit-a");
        assertThat(submitted.path("location").asText()).isEqualTo("Building A");
        assertThat(jdbc.queryForObject("SELECT applied_device_id FROM hikvision_raw_records WHERE external_device_id=? AND stream_kind='DEVICE_EVENT'",
            String.class,external)).isNull();
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_device_id=? AND stream_kind='DEVICE_EVENT'",
            String.class,external)).isEqualTo("PRE_OWNERSHIP_HISTORY");
        assertThat(jdbc.queryForObject("SELECT model FROM devices WHERE id=?",String.class,device)).isEqualTo("800235");
        assertThat(jdbc.queryForObject("SELECT product_version_id FROM devices WHERE id=?",String.class,device)).isNull();
        assertThat(jdbc.queryForObject("SELECT device_id FROM device_source_bindings WHERE tenant_id=? AND source_external_id=? AND state='ACTIVE'",String.class,"tenant-a",external)).isEqualTo(device);
        JsonNode replay=fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,
            "/api/management/vendor-devices/hikvision-create",fixture.root,"tenant-a",key,input).andExpect(status().isOk()));
        assertThat(replay.path("registration").path("device_id").asText()).isEqualTo(device);
        assertThat(adds.get()).isEqualTo(1);
        OffsetDateTime observed=OffsetDateTime.now();
        ingestion.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of("deviceID",external,"deviceSerial",serial,
            "deviceName","Direct smoke","deviceModel","800235"))),observed);
        assertThat(jdbc.queryForObject("SELECT local_device_id FROM hikvision_discovered_devices WHERE external_id=?",String.class,external)).isEqualTo(device);
        ingestion.accept(HikvisionClient.Stream.ALARM,json.valueToTree(List.of(Map.of("alarmID","live-"+external,
            "resourceID",external,"alarmType","200001","alarmStartTime",observed.toString(),"alarmState","0"))),observed);
        assertThat(jdbc.queryForObject("SELECT device_id FROM alarms WHERE id=(SELECT local_alarm_id FROM hikvision_alarm_state WHERE source_alarm_id=?)",
            String.class,"live-"+external)).isEqualTo(device);
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,device)).isEqualTo("FIRE");
        Map<String,Object> changed=new java.util.HashMap<>(input);changed.put("verification_code","different-code");
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices/hikvision-create",fixture.root,"tenant-a",key,changed).andExpect(status().isConflict());
        assertThat(adds.get()).isEqualTo(1);
    }

    @Test void syncReadsUnitsWithoutACursorAndDoesNotCheckpointARepeatedAlarmPage() throws Exception {
        JsonNode created = fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,
            "/api/management/vendor-devices/hikvision-create", fixture.root, "tenant-a",
            "direct-" + UUID.randomUUID(), payload(serial)).andExpect(status().isOk()));
        String device = created.path("registration").path("device_id").asText();
        OffsetDateTime observed = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Map<String, Object> alarm = Map.of("alarmID", "polled-" + external, "resourceID", external,
            "alarmType", "200001", "alarmStartTime", observed.toString(), "alarmState", "0");
        var repeatedPage = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/artemis/api/", exchange -> {
            try (exchange) {
                JsonNode request = json.readTree(exchange.getRequestBody());
                int page = request.path("pageNo").asInt();
                String path = exchange.getRequestURI().getPath();
                List<?> rows = List.of();
                int total = 0;
                if (path.equals("/artemis/api/resource/v2/getUnit") && !request.has("flagId") && !request.has("offset")) {
                    rows = List.of(Map.of("unitID", "fixture-unit-" + page));
                    total = 2;
                } else if (path.equals("/artemis/api/businessData/v2/getAlarm") && request.path("timeType").asInt() == 1) {
                    rows = page == 1 || repeatedPage.get() ? List.of(Map.of("resourceID", external, "alarmID", "older-" + external,
                        "alarmType", "200001", "alarmStartTime", observed.minusHours(1).toString(), "alarmState", "0")) : List.of(alarm);
                    total = 2;
                } else if (!List.of("/artemis/api/device/v2/getDevice", "/artemis/api/channel/v2/getSensor",
                    "/artemis/api/device/v2/getFireDeviceStatus", "/artemis/api/businessData/v2/getFault",
                    "/artemis/api/businessData/v2/getAlarm", "/artemis/api/businessData/v2/getHistoryMonitor",
                    "/artemis/api/businessData/v2/getMonitor", "/artemis/api/businessData/v2/getTraditionMsg").contains(path)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = json.writeValueAsBytes(Map.of("code", "0", "data", Map.of("list", rows, "total", total)));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });

        for (int i = 0; i < 2; i++) {
            fixture.call(org.springframework.http.HttpMethod.POST, "/api/management/hikvision/sync",
                fixture.root, null, null, null).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForList("SELECT external_key FROM hikvision_raw_records WHERE stream_kind='UNIT'", String.class))
            .contains("fixture-unit-1", "fixture-unit-2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?", Long.class, device)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT source_start_time FROM hikvision_alarm_state WHERE source_alarm_id=?",
            OffsetDateTime.class, "polled-" + external).toInstant()).isEqualTo(observed.toInstant());

        String checkpointQuery = "SELECT last_success_at FROM hikvision_stream_state WHERE stream_kind='ALARM'";
        OffsetDateTime checkpoint = jdbc.queryForObject(checkpointQuery, OffsetDateTime.class);
        repeatedPage.set(true);
        fixture.call(org.springframework.http.HttpMethod.POST, "/api/management/hikvision/sync",
            fixture.root, null, null, null).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject(checkpointQuery, OffsetDateTime.class)).isEqualTo(checkpoint);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?", Long.class, device)).isEqualTo(1L);
    }

    @Test void rejectedUnknownAndWrongTenantRemainNonAssetsAndNeverRepeatAdd() throws Exception {
        Map<String,Object> input=payload(serial);
        cloudReply.set("{\"code\":\"1001\",\"msg\":\"rejected\",\"data\":{}}");
        String rejectedKey="rejected-"+UUID.randomUUID();
        JsonNode rejected=fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,
            "/api/management/vendor-devices/hikvision-create",fixture.root,"tenant-a",rejectedKey,input).andExpect(status().isOk()));
        assertThat(rejected.path("registration").path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isZero();
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices/hikvision-create",fixture.root,
            "tenant-a",rejectedKey,input).andExpect(status().isOk());
        assertThat(adds.get()).isEqualTo(1);

        String unknownSerial="unknown-"+UUID.randomUUID();
        cloudReply.set("{\"code\":\"0\",\"msg\":\"ok\",\"data\":{}}");
        cloudStatus.set(503);
        String unknownKey="unknown-"+UUID.randomUUID();
        JsonNode unknown=fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,
            "/api/management/vendor-devices/hikvision-create",fixture.root,"tenant-a",unknownKey,payload(unknownSerial)).andExpect(status().isOk()));
        assertThat(unknown.path("registration").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,unknownSerial)).isZero();
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices/hikvision-create",fixture.root,
            "tenant-a",unknownKey,payload(unknownSerial)).andExpect(status().isOk());
        assertThat(adds.get()).isEqualTo(2);

        String wrongPlaceSerial="wrong-place-"+UUID.randomUUID();
        Map<String,Object> wrongPlace=new java.util.HashMap<>(payload(wrongPlaceSerial));
        wrongPlace.put("place_id","no-such-place");
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices/hikvision-create",fixture.root,
            "tenant-a","wrong-place-"+UUID.randomUUID(),wrongPlace).andExpect(status().isBadRequest());
        assertThat(adds.get()).isEqualTo(2);
        String wrongTenantSerial="wrong-tenant-"+UUID.randomUUID();
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices/hikvision-create",fixture.root,
            "tenant-a","wrong-"+UUID.randomUUID(),payload(wrongTenantSerial,"tenant-b")).andExpect(status().isBadRequest());
        assertThat(adds.get()).isEqualTo(2);
        String occupiedSerial="occupied-"+UUID.randomUUID();
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,identity_slot) VALUES(?,?,'Occupied',?,'800235','','UNKNOWN','ACTIVE')",
            UUID.randomUUID().toString(),"tenant-a",occupiedSerial);
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices/hikvision-create",fixture.root,
            "tenant-a","occupied-"+UUID.randomUUID(),payload(occupiedSerial)).andExpect(status().isConflict());
        assertThat(adds.get()).isEqualTo(2);
    }

    private Map<String,Object> payload(String deviceSerial) { return payload(deviceSerial,"tenant-a"); }
    private Map<String,Object> payload(String deviceSerial,String tenant) {
        var input=new java.util.LinkedHashMap<String,Object>();
        input.put("mode","NEW_VENDOR");input.put("driver_key","HIKVISION_FIRECLOUD");input.put("external_serial",deviceSerial);
        input.put("device_name","Direct smoke");input.put("device_model","800235");input.put("device_type",600002);
        input.put("communication_type",1);input.put("vendor_unit_id","unit-a");input.put("tenant_id",tenant);
        input.put("place_id",fixture.placeId);
        return input;
    }

    @Test void acceptedCloudAddWithLocalBindFailureStaysRecoverableWithoutSecondAdd() throws Exception {
        ingestion.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of("deviceID",external,
            "deviceSerial",serial,"deviceName","Cloud fixture","deviceModel","800235"))),OffsetDateTime.now());
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id='device-a' WHERE external_id=?",external);
        Map<String,Object> input=payload(serial);
        String key="recover-"+UUID.randomUUID();
        fixture.call(org.springframework.http.HttpMethod.POST,"/api/management/vendor-devices/hikvision-create",
            fixture.root,"tenant-a",key,input).andExpect(status().isConflict());
        String registration=jdbc.queryForObject("SELECT id FROM device_registrations WHERE external_serial=?",String.class,serial);
        assertThat(jdbc.queryForObject("SELECT status FROM device_registrations WHERE id=?",String.class,registration)).isEqualTo("CLOUD_REGISTERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isZero();
        assertThat(adds.get()).isEqualTo(1);

        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=NULL WHERE external_id=?",external);
        JsonNode recovered=fixture.body(fixture.call(org.springframework.http.HttpMethod.POST,
            "/api/management/vendor-devices/hikvision-create",fixture.root,"tenant-a",key,input).andExpect(status().isOk()));
        assertThat(recovered.path("registration").path("status").asText()).isEqualTo("COMMISSIONED");
        assertThat(recovered.path("registration").path("device_id").asText()).isNotBlank();
        assertThat(adds.get()).isEqualTo(1);
    }
}
