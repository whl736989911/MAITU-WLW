package com.fireplatform.legacyingest.facts;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.legacyingest.facts.DeviceStatusComposer.Code;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.Test;

class DeviceStatusComposerTest {
    private final DeviceStatusComposer composer = new DeviceStatusComposer(new ObjectMapper(),new RuntimeConfig(
        new MockEnvironment().withProperty("app.storage","postgresql").withProperty("spring.datasource.url","jdbc:postgresql:projection")));

    @Test void offlineHasDisplayPriorityWithoutErasingPendingBusinessStates() {
        var status=composer.compose(Code.OFFLINE,true,true,"[\"{\\\"deviceStateCode\\\":\\\"1002027\\\"}\"]");
        assertThat(status.customerStatus()).isEqualTo(Code.OFFLINE);
        assertThat(status.businessStatuses()).extracting(DeviceStatusComposer.BusinessStatus::code)
            .containsExactly(Code.FIRE,Code.SHIELDED,Code.FAULT);
    }

    @Test void repeatedFaultsAreDeduplicatedInObservationOrderWithoutComparingVendorLevels() {
        var status=composer.compose(Code.ONLINE,false,false,
            "[\"{\\\"deviceStateCode\\\":\\\"1002027\\\",\\\"level\\\":2}\",\"{\\\"deviceStateCode\\\":\\\"1002001\\\",\\\"level\\\":1}\",\"{\\\"deviceStateCode\\\":\\\"1002027\\\",\\\"level\\\":2}\"]");
        assertThat(status.customerStatus()).isEqualTo(Code.FAULT);
        assertThat(status.businessStatuses()).extracting(DeviceStatusComposer.BusinessStatus::label)
            .containsExactly("烟感低电量","烟感故障报警");
    }
}
