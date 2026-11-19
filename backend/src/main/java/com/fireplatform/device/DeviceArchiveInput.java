package com.fireplatform.device;

import jakarta.validation.constraints.*;

public record DeviceArchiveInput(@NotNull @Min(0) Long expectedRevision, @NotBlank @Size(max=1000) String reason) { }
