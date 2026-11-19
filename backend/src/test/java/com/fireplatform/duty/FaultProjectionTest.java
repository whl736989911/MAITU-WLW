package com.fireplatform.duty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fireplatform.legacyingest.FaultProjection;
import org.junit.jupiter.api.Test;

class FaultProjectionTest {
    @Test void knownCodeUsesDictionaryWithoutTreatingMethodAsType() {
        var result = FaultProjection.describe("thing.event.faults", "{\"deviceStateCode\":\"1002003\"}");
        assertThat(result.code()).isEqualTo("1002003");
        assertThat(result.label()).isEqualTo("防拆报警");
    }

    @Test void unknownCodeIsPreservedAndExplicitlyUnmapped() {
        var result = FaultProjection.describe("thing.event.faults", "{\"deviceStateCode\":\"X-17\"}");
        assertThat(result.code()).isEqualTo("X-17");
        assertThat(result.label()).isEqualTo("未知故障类型");
    }

    @Test void missingOrMalformedCodeDoesNotPromoteMethodToFaultType() {
        assertThat(FaultProjection.describe("thing.event.faults", "{}")).isEqualTo(
            new FaultProjection.Description(null, "故障类型未知"));
        assertThat(FaultProjection.describe("thing.event.faults", "not-json")).isEqualTo(
            new FaultProjection.Description(null, "故障类型未知"));
    }
}
