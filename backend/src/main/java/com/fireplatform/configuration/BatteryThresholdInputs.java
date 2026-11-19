package com.fireplatform.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.shared.DecimalTokenDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public final class BatteryThresholdInputs {
    private BatteryThresholdInputs() { }


    public record Threshold(@NotBlank @Size(max=64) String parameterKey,
                            @NotBlank @Size(max=64) String metricKey,
                            @NotBlank @Size(max=8) String unit,
                            @NotBlank @Pattern(regexp="LT|LTE") String comparator,
                            @NotNull @JsonDeserialize(using=DecimalTokenDeserializer.class)
                            @DecimalMin("0") @DecimalMax("100") @Digits(integer=3, fraction=36) BigDecimal threshold) { }

    public record SetOverride(@NotNull @Min(0) Long expectedRevision,
                              @JsonProperty(value="battery_low_threshold", required=true) @Valid Threshold batteryLowThreshold,
                              @NotBlank @Size(max=com.fireplatform.shared.Limits.MAX_CHANGE_REASON_LENGTH) String reason) { }
}
