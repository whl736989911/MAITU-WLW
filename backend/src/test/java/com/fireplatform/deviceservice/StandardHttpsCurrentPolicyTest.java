package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.deviceservice.driver.StandardHttpsDriver;
import java.time.Clock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class StandardHttpsCurrentPolicyTest {
    @ParameterizedTest
    @CsvSource({
        "https://previously-approved.example/commands, fixture-account, ENDPOINT_UNSAFE",
        "https://127.0.0.1/commands, fixture-account, ENDPOINT_UNSAFE",
        "https://approved.example/commands?token=secret, fixture-account, ENDPOINT_UNSAFE",
        "http://approved.example/commands, fixture-account, ENDPOINT_UNSAFE",
        "https://approved.example/commands, replaced-account, ACCOUNT_CHANGED"
    })
    void persistedEndpointAndAccountMustStillBeAuthorized(String endpoint, String account, String expectedCode) {
        var json = new ObjectMapper();
        var config = spy(new IntegrationDriverConfigService(null, null, null, null, null,
            Clock.systemUTC(), "approved.example"));
        doReturn(new IntegrationDriverConfigService.Config(true, true, "READY", endpoint,
            "isolated-test-token", "masked", "fixture-account", 1)).when(config).load(StandardHttpsDriver.KEY);
        var service = new IntegrationDriver.DriverServiceDescriptor("reset", "reset", "CONTROL", "HIGH",
            "COMMAND", json.createObjectNode(), json.createObjectNode(), json.createObjectNode(), "VERIFIED", true);
        var call = new IntegrationDriver.DriverCall("fixture-command", "fixture-unit", "fixture-device",
            "fixture-eui", null, null, "fixture-model", "fixture-version", null,
            json.createObjectNode(), service, account);
        var result = new StandardHttpsDriver(config, json).submit(call);
        assertThat(result.outcome()).isEqualTo("BLOCKED");
        assertThat(result.vendorCode()).isEqualTo(expectedCode);
    }
}
