package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.deviceservice.DeviceServiceCommandService;
import com.fireplatform.deviceservice.DeviceSourceBindingService;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionConfigService;
import com.fireplatform.hikvision.driver.HikvisionIntegrationDriver;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.roles=api", "app.storage=h2-dev", "APP_BOOTSTRAP_USERNAME=field-admin",
    "APP_BOOTSTRAP_PASSWORD=Field-maintenance-test!", "app.hikvision-config-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "spring.datasource.url=jdbc:h2:mem:field-maintenance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FieldMaintenanceIntegrationTest {
    private static final String BASE = "/api/management/maintenance/field-records";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired DeviceServiceCommandService commands;
    @Autowired DeviceSourceBindingService bindings;
    @Autowired HikvisionClient client;
    @Autowired HikvisionIntegrationDriver driver;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean HikvisionConfigService configuration;
    String token;
    String unit;
    String oldDevice;
    String newDevice;

    @BeforeEach void seed() throws Exception {
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE WHERE id='bootstrap-admin'", encoder.encode("Field-maintenance-test!"));
        token = body(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of(
            "username", "field-admin", "password", "Field-maintenance-test!", "audience", "management")))).andExpect(status().isOk())).get("access_token").asText();
        String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
        unit = "unit-" + unique;
        oldDevice = "old-" + unique;
        newDevice = "new-" + unique;
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?, 'Field unit')", unit);
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin',?)", unit);
        N04TestScopes.grantExisting(jdbc);
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES (?,?,'First floor')", unit, unit);
        for (String device : new String[]{oldDevice,newDevice}) jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id,integration_state) VALUES (?,?,'Smoke detector',?,'model','Room 101','FIRE',FALSE,?,'CONNECTED')", device,unit,device,unit);
    }

    @Test void handlingCreatesOneImmutableAttributedRecordAndDoesNotChangeAlarmState() throws Exception {
        var input = Map.of("device_id", oldDevice, "expected_device_revision", 0, "action", "HANDLE", "reason_code", "LOW_BATTERY", "note", "Inspected");
        var saved = body(send(BASE, "handle-" + unit, input).andExpect(status().isOk()));
        assertThat(saved.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(saved.get("place_name").asText()).isEqualTo("First floor");
        assertThat(saved.get("actor_name").asText()).isNotBlank();
        assertThat(body(send(BASE, "handle-" + unit, input).andExpect(status().isOk())).get("id")).isEqualTo(saved.get("id"));
        assertThat(list().get("total").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?", String.class, oldDevice)).isEqualTo("FIRE");
    }

    @Test void replacementRejectsStaleOrDifferentPlacementThenArchivesOnlyOldDevice() throws Exception {
        var input = Map.of("device_id", oldDevice, "expected_device_revision", 0, "action", "REPLACE", "reason_code", "REPLACED", "replacement_serial", newDevice, "risk_confirmed", true);
        jdbc.update("UPDATE devices SET address='Room 202' WHERE id=?", newDevice);
        send(BASE, "bad-" + unit, input).andExpect(status().isConflict());
        assertThat(list().get("total").asInt()).isZero();
        jdbc.update("UPDATE devices SET address='Room 101',integration_state='PENDING_INTEGRATION' WHERE id=?", newDevice);
        var saved = body(send(BASE, "replace-" + unit, input).andExpect(status().isOk()));
        assertThat(saved.get("status").asText()).isEqualTo("NEW_DEVICE_PENDING");
        String finish=BASE+"/"+saved.get("id").asText()+"/finish";
        send(finish,"finish-"+unit,null).andExpect(status().isConflict());
        jdbc.update("UPDATE devices SET integration_state='CONNECTED' WHERE id=?", newDevice);
        assertThat(list().at("/items/0/status").asText()).isEqualTo("READY_TO_RETIRE");
        assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM devices WHERE id=?", Boolean.class, oldDevice)).isTrue();
        var completed = body(send(finish, "finish-" + unit, null).andExpect(status().isOk()));
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM devices WHERE id=?", Boolean.class, oldDevice)).isFalse();
        assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM devices WHERE id=?", Boolean.class, newDevice)).isTrue();
        assertThat(list().get("total").asInt()).isEqualTo(1);
    }

    @Test void cloudDeletionIsSentOnceAndUnverifiedReceiptKeepsOldDeviceActive() throws Exception {
        var deletes=new AtomicInteger();
        var confirmed=new AtomicBoolean();
        var cloud=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        cloud.createContext("/artemis/api/device/v2/delete", exchange -> {
            deletes.incrementAndGet();
            fixtureResponse(exchange,"{\"code\":\"0\",\"data\":{}}");
        });
        cloud.createContext("/artemis/api/device/v3/getDevice",exchange -> fixtureResponse(exchange,
            confirmed.get()?"{\"code\":\"0\",\"data\":{\"list\":[],\"total\":0,\"pageNo\":1}}":"{\"code\":\"0\",\"data\":{}}"));
        cloud.start();
        try {
            String endpoint="http://127.0.0.1:"+cloud.getAddress().getPort();
            client.configure(endpoint,"field-fixture-key","field-fixture-secret");
            doReturn(new HikvisionConfigService.Runtime(endpoint,"field-fixture-key","field-fixture-secret",true,86400,
                "800235",true,1,client.session(),unit)).when(configuration).refreshRuntime();
            String account=driver.accountFingerprint();
            assertThat(account).isNotBlank();
            transactions.executeWithoutResult(tx -> {
                for(String device:new String[]{oldDevice,newDevice})
                    bindings.bind(unit,device,"HIKVISION_FIRECLOUD","cloud-"+device,device,account,"bootstrap-admin");
            });
            var input=Map.of("device_id",oldDevice,"expected_device_revision",0,"action","REPLACE",
                "reason_code","REPLACED","replacement_serial",newDevice,"risk_confirmed",true);
            var saved=body(send(BASE,"cloud-"+unit,input).andExpect(status().isOk()));
            assertThat(saved.get("status").asText()).isEqualTo("CLOUD_PENDING");
            String finish=BASE+"/"+saved.get("id").asText()+"/finish";
            send(finish,"finish-"+unit,null).andExpect(status().isConflict());
            assertThat(commands.dispatchOne()).isTrue();
            assertThat(deletes.get()).isEqualTo(1);
            assertThat(list().at("/items/0/status").asText()).isEqualTo("CLOUD_UNKNOWN");
            send(finish,"finish-"+unit,null).andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM devices WHERE id=?",Boolean.class,oldDevice)).isTrue();
            confirmed.set(true);
            send("/api/management/device-service-commands/"+saved.get("cloud_command_id").asText()+"/reconcile",
                "reconcile-"+unit,Map.of("reason","Confirm exact cloud deletion")).andExpect(status().isOk());
            assertThat(list().at("/items/0/status").asText()).isEqualTo("READY_TO_RETIRE");
            send(finish,"finish-"+unit,null).andExpect(status().isOk());
            assertThat(deletes.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM devices WHERE id=?",Boolean.class,oldDevice)).isFalse();
            assertThat(bindings.activeForDevice(unit,newDevice)).isPresent();
        } finally {
            cloud.stop(0);
        }
    }

    private static void fixtureResponse(com.sun.net.httpserver.HttpExchange exchange,String response) throws java.io.IOException {
        byte[] body=response.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(200,body.length);
        try(var output=exchange.getResponseBody()) { output.write(body); }
    }

    ResultActions send(String path,String key,Object input) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token).header("X-Tenant-Id", unit).header("Idempotency-Key", key);
        if (input != null) request.contentType("application/json").content(json.writeValueAsString(input));
        return mvc.perform(request);
    }
    JsonNode list() throws Exception {
        return body(mvc.perform(get(BASE).header("Authorization", "Bearer " + token).header("X-Tenant-Id", unit).param("device_id", oldDevice)).andExpect(status().isOk()));
    }
    JsonNode body(ResultActions value) throws Exception { return json.readTree(value.andReturn().getResponse().getContentAsByteArray()); }
}
