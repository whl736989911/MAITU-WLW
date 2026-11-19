package com.fireplatform.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.shared.DecimalTokenDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class ProductInputs {
    private ProductInputs() { }
    public record CreateProduct(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=100) String manufacturer,@NotBlank @Size(max=100) String model,@NotNull @Size(max=2000) String description) { }
    public record UpdateProduct(@NotBlank @Size(max=200) String name,@NotNull @Size(max=2000) String description,@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
    public record ProductLifecycle(@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
    public record CreateVersion(@Size(max=64) String baseVersionId) { }
    public record BatteryThresholdInput(@NotBlank @Size(max=64) String parameterKey,@NotBlank @Size(max=64) String metricKey,@NotBlank @Size(max=8) String unit,@NotBlank @Pattern(regexp="LT|LTE") String comparator,@NotNull @JsonDeserialize(using=DecimalTokenDeserializer.class) @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=36) BigDecimal threshold) { }
    public record EditVersion(@NotNull @Min(0) Long expectedRevision,@NotNull JsonNode schema,@NotNull JsonNode capabilities,@JsonProperty(value="battery_low_threshold",required=true) @Valid BatteryThresholdInput batteryLowThreshold) { }
    public record SampleReading(@NotBlank @Pattern(regexp="[a-z][a-z0-9_]{0,63}") String key,@NotBlank @Pattern(regexp="VALID|MISSING|INVALID") String state,
        @JsonDeserialize(using=ExactJsonTokenDeserializer.class) JsonNode value) { }
    public record Sample(@NotBlank @Size(max=100) String name,@NotBlank @Pattern(regexp="FIRE|FAULT|RECOVERY|HEARTBEAT|PARAM|OTHER|UNKNOWN|MALFORMED") String caseKind,@NotNull @Size(max=ProductLimits.MAX_SAMPLE_BYTES) String payload,
        @NotBlank @Pattern(regexp="FIRE|FAULT|RECOVERY|HEARTBEAT|PARAM|OTHER|UNKNOWN") String expectedEvent,BigDecimal expectedBatteryPercent,
        @Size(max=ProductLimits.MAX_SAMPLE_READINGS) List<@NotNull @Valid SampleReading> expectedMeasurements) { }
    public record TestVersion(@NotNull @Min(0) Long expectedRevision,@NotNull @Size(min=1,max=ProductLimits.MAX_SAMPLES) List<@NotNull @Valid Sample> samples) { }
    public record PublishVersion(@NotNull @Min(0) Long expectedRevision) { }
}
