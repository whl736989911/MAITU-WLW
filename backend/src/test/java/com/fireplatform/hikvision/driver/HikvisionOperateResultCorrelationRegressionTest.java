package com.fireplatform.hikvision.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private HikvisionIntegrationDriver driver() {
        HikvisionClient sessionSource = new HikvisionClient(JSON, Clock.systemUTC(), "fixture-key", "fixture-secret");
        HikvisionConfigService configuration = mock(HikvisionConfigService.class);
        when(configuration.refreshRuntime()).thenReturn(new HikvisionConfigService.Runtime(
            "https://vendor.invalid", "fixture-key", "fixture-secret", true, 30, "800235", false, 1,
            sessionSource.session()));
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
        var data = JSON.createObjectNode();
        data.putArray("list").addObject().put("deviceID", "bound-device").put("deviceSerial", "bound-serial");
        when(client.write(anyString(), any())).thenReturn(new HikvisionClient.VendorResult("0", "ok", data));

        var result = driver.query(call);

        assertThat(result.outcome()).isEqualTo("ACCEPTED");
        assertThat(result.vendorCode()).isEqualTo("CLOUD_RECORD_PRESENT");
        verify(client).write("/api/device/v3/getDevice",
            JSON.createObjectNode().put("deviceSerial", "bound-serial").put("pageNo", 1).put("pageSize", 10));
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
        verifyNoInteractions(client);
    }
}
