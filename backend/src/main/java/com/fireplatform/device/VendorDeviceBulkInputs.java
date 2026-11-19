package com.fireplatform.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Request shape for one atomic import of locally registered vendor devices. */
public final class VendorDeviceBulkInputs {
    private VendorDeviceBulkInputs() { }

    public record Import(
        @NotBlank @Pattern(regexp="LEGACY_REALTIME|HIKVISION_FIRECLOUD") String source,
        @NotEmpty @Size(max=200) List<@NotNull @Valid Row> rows
    ) { }

    public record Row(
        @NotBlank @Size(max=200) String name,
        @NotBlank @Pattern(regexp="[\\x21-\\x7e]{1,64}") String serial,
        @NotBlank @Size(max=64) @JsonProperty("place_id") String placeId,
        @Size(max=500) String address,
        @Size(max=64) @JsonProperty("legacy_profile_id") String legacyProfileId,
        @Pattern(regexp="[\\x21-\\x7e]{1,128}") @JsonProperty("upstream_device_id") String upstreamDeviceId,
        @Min(2) @Max(201) @JsonProperty("source_row") Integer sourceRow
    ) { }

    /** Parse-only upload of one bounded XLSX sheet; device writes still go through {@link Import}. */
    public record Spreadsheet(
        @NotBlank @Pattern(regexp="LEGACY_REALTIME|HIKVISION_FIRECLOUD") String source,
        @NotBlank @Size(max=1_400_000) @JsonProperty("content_base64") String contentBase64
    ) { }

    public record LegacyTerminalSpreadsheet(
        @NotBlank @Size(max=1_400_000) @JsonProperty("content_base64") String contentBase64,
        @Size(max=64) @JsonProperty("legacy_profile_id") String legacyProfileId
    ) { }
}
