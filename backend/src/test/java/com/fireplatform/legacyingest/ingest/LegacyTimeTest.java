package com.fireplatform.legacyingest.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LegacyTimeTest {
    @Test void parsesIsoWithOffsetAndZulu() {
        assertThat(NormalizerSupport.time("2026-11-01T20:25:00Z"))
            .isEqualTo(OffsetDateTime.of(2026, 11, 1, 20, 25, 0, 0, ZoneOffset.UTC));
        assertThat(NormalizerSupport.time("2026-11-02T04:25:00+08:00"))
            .isEqualTo(OffsetDateTime.of(2026, 11, 1, 20, 25, 0, 0, ZoneOffset.UTC));
    }

    @Test void parsesEpochSecondsAndMilliseconds() {
        assertThat(NormalizerSupport.time("1793565105"))
            .isEqualTo(OffsetDateTime.of(2026, 11, 1, 20, 31, 45, 0, ZoneOffset.UTC));
        assertThat(NormalizerSupport.time("1793565105000"))
            .isEqualTo(OffsetDateTime.of(2026, 11, 1, 20, 31, 45, 0, ZoneOffset.UTC));
    }

    @Test void parsesUtcSuffixAndLocalBeijingTime() {
        assertThat(NormalizerSupport.time("2026-11-01 20:24:49 UTC"))
            .isEqualTo(OffsetDateTime.of(2026, 11, 1, 20, 24, 49, 0, ZoneOffset.UTC));
        assertThat(NormalizerSupport.time("2026-11-02 04:24:49"))
            .isEqualTo(utc(2026, 11, 1, 20, 24, 49));
    }

    @Test void returnsNullForUnknownFormats() {
        assertThat(NormalizerSupport.time("")).isNull();
        assertThat(NormalizerSupport.time("yesterday")).isNull();
        assertThat(NormalizerSupport.time(null)).isNull();
    }

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute, int second) {
        return OffsetDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC);
    }
}
