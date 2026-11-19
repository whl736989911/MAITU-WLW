package com.fireplatform.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class BatteryThresholdModels {
    private BatteryThresholdModels() { }

    public static final String PARAMETER_KEY = "battery_low_threshold_percent";
    public static final String METRIC_KEY = "battery_percent";
    public static final String UNIT = "%";
    public static final String CONSUMER = "BATTERY_OBSERVATION_ONLY";

    public record Definition(String key, String metricKey, String name, String unit, int min, int max,
                             int scale, List<String> comparators, List<String> allowedScopes,
                             String consumer, String effect) { }

    /** Configuration/provenance only; safe for actors without device:read. */
    public record Effective(String parameterKey, String metricKey, String unit, boolean configured,
                            String comparator, BigDecimal threshold, String sourceScope,
                            String sourceId, long sourceRevision, OffsetDateTime evaluatedAt) { }

    /** Device-read-only observation status, deliberately separate from the parameter view. */
    public record Observation(String parameterKey, String metricKey, String unit, String status,
                              String comparator, BigDecimal threshold, String sourceScope,
                              String sourceId, long sourceRevision, OffsetDateTime evaluatedAt) { }

    public record Override(boolean hasValue, String comparator, BigDecimal threshold, long revision) { }

    public record ProductVersion(String productVersionId, Long versionNumber, String batteryCapability) { }

    public record View(String deviceId, String tenantId, String parameterKey, String metricKey, String unit,
                       Override override, Effective effective, ProductVersion productVersion, String consumer) { }

    public record ThresholdValue(String comparator, BigDecimal threshold) { }
    public record Change(String id, String action, ThresholdValue previous, @JsonProperty("new") ThresholdValue newValue,
                         long revisionBefore, long revisionAfter, String actorId, String reason, OffsetDateTime createdAt) { }

    public record Mutation(View view, Change change) { }
    public record ChangeReference(String changeId) { }
}
