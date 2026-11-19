package com.fireplatform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationDeliveryReceiptPolicyTest {
    private static final NotificationDeliveryReceiptPolicy.Desired ON =
            new NotificationDeliveryReceiptPolicy.Desired(true, 300, 600, 48, 20, 3, null);

    @Test void effectiveIsEnabledOnlyWhenDesiredEnvAndProviderAllowIt() {
        assertThat(NotificationDeliveryReceiptPolicy.effective(ON, true, true, 100, 86400, 604800, 500).enabled()).isTrue();
        var desiredOff = new NotificationDeliveryReceiptPolicy.Desired(false, 300, 600, 48, 20, 0, null);
        assertThat(NotificationDeliveryReceiptPolicy.effective(desiredOff, true, true, 100, 86400, 604800, 500).enabled()).isFalse();
        var envOff = NotificationDeliveryReceiptPolicy.effective(ON, false, true, 100, 86400, 604800, 500);
        assertThat(envOff.enabled()).isFalse();
        assertThat(envOff.clampedReasons()).contains("ENV_QUERY_DISABLED");
        var providerOff = NotificationDeliveryReceiptPolicy.effective(ON, true, false, 100, 86400, 604800, 500);
        assertThat(providerOff.enabled()).isFalse();
        assertThat(providerOff.clampedReasons()).contains("PROVIDER_NOT_CONFIGURED");
    }

    @Test void environmentCeilingsClampEffectiveValuesAndReportEveryClamp() {
        var desired = new NotificationDeliveryReceiptPolicy.Desired(true, 100000, 999999, 900, 250, 1, null);
        var effective = NotificationDeliveryReceiptPolicy.effective(desired, true, true, 100, 86400, 604800, 500);
        assertThat(effective.minAgeSeconds()).isEqualTo(86400);
        assertThat(effective.retrySeconds()).isEqualTo(604800);
        assertThat(effective.maxQueriesPerAttempt()).isEqualTo(500);
        assertThat(effective.batchSize()).isEqualTo(100);
        assertThat(effective.clampedReasons()).containsExactlyInAnyOrder("MIN_AGE_CAPPED", "RETRY_CAPPED", "MAX_QUERIES_CAPPED", "BATCH_SIZE_CAPPED");
    }

    @Test void valuesBelowTechnicalMinimumsAreRaisedToTheSafeBoundary() {
        var desired = new NotificationDeliveryReceiptPolicy.Desired(true, -1, 0, 0, 0, 0, null);
        var effective = NotificationDeliveryReceiptPolicy.effective(desired, true, true, 100, 86400, 604800, 500);
        assertThat(effective.minAgeSeconds()).isZero();
        assertThat(effective.retrySeconds()).isEqualTo(60);
        assertThat(effective.maxQueriesPerAttempt()).isEqualTo(1);
        assertThat(effective.batchSize()).isEqualTo(1);
        assertThat(effective.clampedReasons()).containsExactlyInAnyOrder("MIN_AGE_CAPPED", "RETRY_CAPPED", "MAX_QUERIES_CAPPED", "BATCH_SIZE_CAPPED");
    }
}
