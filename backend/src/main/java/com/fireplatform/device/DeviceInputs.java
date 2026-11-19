package com.fireplatform.device;

import jakarta.validation.constraints.*;

public final class DeviceInputs {
    private DeviceInputs() { }
    public record Register(@NotBlank @Size(max=200) String name,@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String deviceEui,
        @NotBlank @Size(max=500) String address,@NotBlank @Size(max=64) String placeId,@NotBlank @Size(max=64) String productVersionId,
        @Size(max=80) String systemType,@Size(max=80) String deviceCategory) { }
    public record Edit(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=500) String address,@Size(max=64) String placeId,@NotNull @Min(0) Long expectedRevision,
        @Size(max=80) String systemType,@Size(max=80) String deviceCategory) { }
    public record BatchTarget(@NotBlank @Size(max=64) String deviceId,@NotNull @Min(0) Long expectedRevision) { }
    public record BatchMigrate(@NotEmpty @Size(max=100) java.util.List<@jakarta.validation.Valid BatchTarget> devices,@NotBlank @Size(max=64) String productVersionId,@NotBlank @Size(max=1000) String reason) { }
    public record BatchMove(@NotEmpty @Size(max=100) java.util.List<@jakarta.validation.Valid BatchTarget> devices,@NotBlank @Size(max=64) String placeId) { }
    public record Migrate(@NotBlank @Size(max=64) String productVersionId,@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
}
