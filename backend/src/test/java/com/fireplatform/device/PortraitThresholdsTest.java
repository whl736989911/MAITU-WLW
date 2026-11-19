package com.fireplatform.device;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PortraitThresholdsTest {
    @Test void countThresholdIsInclusiveAndUnknownEvidenceNeverLooksLikeZero() {
        assertThat(PortraitThresholds.count(2,true,3)).isEqualTo(new PortraitThresholds.Count("OK",false));
        assertThat(PortraitThresholds.count(3,true,3)).isEqualTo(new PortraitThresholds.Count("OK",true));
        assertThat(PortraitThresholds.count(20,false,3)).isEqualTo(new PortraitThresholds.Count("MISSING",false));
        assertThat(PortraitThresholds.count(0,false,3).status()).isEqualTo("MISSING");
    }

    @Test void batteryBoundaryUsesExactDecimalComparison() {
        assertThat(PortraitThresholds.lowBattery(new BigDecimal("20.000000000000000000000000000001"),new BigDecimal("20"))).isFalse();
        assertThat(PortraitThresholds.lowBattery(new BigDecimal("20.000000000000000000000000000000"),new BigDecimal("20"))).isTrue();
        assertThat(PortraitThresholds.lowBattery(null,new BigDecimal("20"))).isFalse();
    }
    @Test void weakSignalUsesExplicitMetricBoundaryAndObservedSampleDenominator() {
        assertThat(PortraitThresholds.weakSignal(new BigDecimal("-110.00"),new BigDecimal("-110"))).isTrue();
        assertThat(PortraitThresholds.weakSignal(new BigDecimal("-109.999"),new BigDecimal("-110"))).isFalse();
        assertThat(PortraitThresholds.weakPercent(1,4)).isEqualByComparingTo("25.0000");
        assertThat(PortraitThresholds.weakPercentAtLeast(1,6,new BigDecimal("16.666666666666666667"))).isFalse();
        assertThat(PortraitThresholds.weakPercentAtLeast(1,6,new BigDecimal("16.666666666666666666"))).isTrue();
        assertThat(PortraitThresholds.weakPercent(0,0)).isNull();
    }

    @Test void longOfflineIncludesExactBoundaryButNotOneInstantNewer() {
        var asOf=OffsetDateTime.parse("2026-11-06T12:00:00Z");
        assertThat(PortraitThresholds.longOffline(asOf.minusDays(7),asOf,7)).isTrue();
        assertThat(PortraitThresholds.longOffline(asOf.minusDays(7).plusNanos(1),asOf,7)).isFalse();
        assertThat(PortraitThresholds.longOffline(null,asOf,7)).isFalse();
    }
}
