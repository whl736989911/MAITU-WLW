package com.fireplatform.device;

import jakarta.validation.constraints.*;

/** Local asset registration against existing vendor data feeds, not product-model publication. */
public final class VendorDeviceInputs {
    private VendorDeviceInputs() { }

    public record Register(
        @NotBlank @Pattern(regexp="LEGACY_REALTIME|HIKVISION_FIRECLOUD") String source,
        @NotBlank @Size(max=200) String name,
        @NotBlank @Pattern(regexp="[\\x21-\\x7e]{1,64}") String serial,
        @NotBlank @Size(max=64) String placeId,
        @Size(max=500) String address,
        @Size(max=64) String legacyProfileId,
        @Pattern(regexp="[\\x21-\\x7e]{1,128}") String upstreamDeviceId) { }

    public record ConfirmAep(
        @NotBlank @Pattern(regexp="[\\x21-\\x7e]{1,128}") String upstreamDeviceId,
        @NotNull @Min(0) Long expectedRevision) { }
}
