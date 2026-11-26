package com.fireplatform.hikvision;

import jakarta.validation.constraints.*;

public final class HikvisionConfigInputs {
    private HikvisionConfigInputs() { }
    public record Write(@NotNull Long expectedRevision, @NotBlank @Size(max=500) String endpoint,
        @Size(max=200) String appKey, @Size(max=500) String appSecret,
        @NotNull Boolean enabled, @NotNull @Min(10) @Max(3600) Integer pollIntervalSeconds,
        @NotBlank @Size(max=100) String modelScope, @Size(max=64) String registrationUnitId) { }
}
