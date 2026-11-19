package com.fireplatform.hikvision.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.deviceservice.IntegrationDriver;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionConfigSecrets;
import com.fireplatform.hikvision.HikvisionConfigService;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class HikvisionOperateResultCorrelationRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HikvisionClient client = mock(HikvisionClient.class);
    private final HikvisionClient.Session lookupSession = mock(HikvisionClient.Session.class);

    private HikvisionIntegrationDriver driver() {
        HikvisionClient sessionSource = new HikvisionClient(JSON, Clock.systemUTC(), "fixture-key", "fixture-secret");
        HikvisionConfigService configuration = mock(HikvisionConfigService.class);
        when(configuration.refreshRuntime()).thenReturn(new HikvisionConfigService.Runtime(
            "https://vendor.invalid", "fixture-key", "fixture-secret", true, 30, "800235", false, 1,
            sessionSource.session()));
        when(client.session()).thenReturn(lookupSession);
        HikvisionConfigSecrets secrets = mock(HikvisionConfigSecrets.class);
        when(secrets.fingerprint(anyString())).thenReturn("fixture-account");
        return new HikvisionIntegrationDriver(client, configuration, secrets, JSON);
    }

    @Test
    void deviceLevelStatusCannotBecomeTerminalCommandReceipt() {
        var driver = driver();
        IntegrationDriver.DriverServiceDescriptor operate = driver.services().stream()
            .filter(service -> HikvisionIntegrationDriver.CONTROL_OPERATE.equals(service.key())).findFirst().orElseThrow();
        ObjectNode arguments = JSON.createObjectNode().put("operateType", 2);
        var call = new IntegrationDriver.DriverCall("fixture-command", "fixture-tenant", "fixture-device",
            "fixture-eui", "fixture-external-device", "fixture-serial", "800235", null, null,
            arguments, operate, "fixture-account");
        ObjectNode stale = JSON.createObjectNode().put("deviceID", "fixture-external-device")
            .put("result", 1).put("currentState", "fixture-state").put("updateTime", "2020-01-01 00:00:00");
        when(client.write(anyString(), any()))
            .thenReturn(new HikvisionClient.VendorResult("0", "ok", JSON.createArrayNode().add(stale)))
            .thenReturn(new HikvisionClient.VendorResult("0", "ok", JSON.createArrayNode().add(stale.deepCopy().put("result", 0))));

        assertThat(driver.query(call).outcome()).isNotIn("SUCCEEDED", "FAILED");
        assertThat(driver.query(call).outcome()).isNotIn("SUCCEEDED", "FAILED");
    }

    @Test
    void cloudDeleteReconciliationReportsTheActualBoundRecord() {
        var driver = driver();
        var service = driver.services().stream()
            .filter(s -> HikvisionIntegrationDriver.CLOUD_DELETE.equals(s.key())).findFirst().orElseThrow();
        var call = new IntegrationDriver.DriverCall("fixture-command", "fixture-tenant", "fixture-device",
            "fixture-eui", "bound-device", "bound-serial", "800235", null, null,
            JSON.createObjectNode(), service, "fixture-account");
        ObjectNode first = devicePage(1, 2, "cursor-1");
        first.withArray("list").addObject().put("deviceID", "first-device").put("deviceSerial", "first-serial");
        ObjectNode second = devicePage(2, 2, "");
        second.withArray("list").addObject().put("deviceID", "bound-device").put("deviceSerial", "bound-serial");
        when(lookupSession.write(anyString(), any())).thenReturn(response(first), response(second));

        var result = driver.query(call);

        assertThat(result.outcome()).isEqualTo("ACCEPTED");
        assertThat(result.vendorCode()).isEqualTo("CLOUD_RECORD_PRESENT");
        verify(lookupSession).write("/api/device/v3/getDevice", JSON.createObjectNode().put("deviceID", "bound-device")
            .put("flagId", "cursor-1").put("offset", "1").put("pageNo", "2").put("pageSize", "10"));
    }

    @Test
    void inconsistentCloudReplyCannotConfirmAbsenceAfterDelete() {
        var driver = driver();
        ObjectNode incomplete = devicePage(1, 1, "");
        when(lookupSession.write("/api/device/v2/delete", JSON.createObjectNode().put("deviceID", "bound-device")))
            .thenReturn(response(JSON.createObjectNode()));
        when(lookupSession.write(eq("/api/device/v3/getDevice"), any()))
            .thenReturn(response(incomplete), response(devicePage(1, 0, "")));

        assertThat(driver.delete("bound-device", "bound-serial").vendorCode()).isEqualTo("CLOUD_ABSENCE_UNVERIFIED");
        assertThat(driver.lookup("bound-serial", "bound-device").state()).isEqualTo("NOT_FOUND");
    }

    @Test
    void looksForAnExactSerialAcrossPagesBeforeDeclaringItUnique() {
        var driver = driver();
        ObjectNode first = devicePage(1, 2, "");
        first.withArray("list").addObject().put("deviceID", "id-a").put("deviceSerial", "shared-serial");
        ObjectNode second = devicePage(2, 2, "");
        second.withArray("list").addObject().put("deviceID", "id-b").put("deviceSerial", "shared-serial");
        when(lookupSession.write(anyString(), any())).thenReturn(response(first), response(second));

        assertThat(driver.lookup("shared-serial", null).state()).isEqualTo("AMBIGUOUS");
    }

    @Test
    void boundCloudIdWithADifferentSerialIsNotAbsence() {
        var driver = driver();
        ObjectNode page = devicePage(1, 1, "");
        page.withArray("list").addObject().put("deviceID", "bound-device").put("deviceSerial", "different-serial");
        when(lookupSession.write(eq("/api/device/v3/getDevice"), any())).thenReturn(response(page));

        assertThat(driver.lookup("bound-serial", "bound-device").state()).isEqualTo("UNKNOWN");
    }

    private static ObjectNode devicePage(int page, int total, String flag) {
        ObjectNode data = JSON.createObjectNode().put("pageNo", page).put("total", total).put("flagId", flag);
        data.putArray("list");
        return data;
    }

    private static HikvisionClient.VendorResult response(ObjectNode data) {
        return new HikvisionClient.VendorResult("0", "ok", data);
    }

    @Test
    void missingBoundSerialDoesNotProveCloudDeletion() {
        var driver = driver();
        var service = driver.services().stream()
            .filter(s -> HikvisionIntegrationDriver.CLOUD_DELETE.equals(s.key())).findFirst().orElseThrow();
        var call = new IntegrationDriver.DriverCall("fixture-command", "fixture-tenant", "fixture-device",
            "fixture-eui", "bound-device", null, "800235", null, null,
            JSON.createObjectNode(), service, "fixture-account");

        var result = driver.query(call);

        assertThat(result.outcome()).isNotEqualTo("SUCCEEDED");
        assertThat(result.vendorCode()).isEqualTo("CLOUD_ABSENCE_UNVERIFIED");
        verifyNoInteractions(lookupSession);
    }
}
