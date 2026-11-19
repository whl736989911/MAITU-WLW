package com.fireplatform.device;

import jakarta.validation.constraints.*;

public record DeviceTransferInput(
    @NotBlank @Size(max=64) String targetTenantId,
    @NotBlank @Size(max=64) String targetPlaceId,
    @NotBlank @Size(max=1000) String reason,
    @NotNull @Min(0) Long expectedRevision
) { }
