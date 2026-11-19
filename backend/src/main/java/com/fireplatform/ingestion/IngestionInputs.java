package com.fireplatform.ingestion;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public final class IngestionInputs {
    private IngestionInputs() { }
    public record CreateSource(@NotBlank @Size(max=200) String name) { }
    public record UpdateSource(@NotBlank @Size(max=200) String name,@JsonDeserialize(using=StrictBooleanDeserializer.class) @NotNull Boolean enabled,@NotNull @Min(0) Long expectedRevision) { }
    public record RotateToken(@NotNull @Min(0) Long expectedRevision) { }
    public record Bind(@NotBlank @Size(max=64) String deviceId,@NotBlank @Size(max=IngestionLimits.VENDOR_DEVICE_ID) String vendorDeviceId,@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
    public record Detach(@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
    public record Envelope(@JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(max=IngestionLimits.SOURCE_MESSAGE_ID) String sourceMessageId,@JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(max=IngestionLimits.VENDOR_DEVICE_ID) String vendorDeviceId,@JsonDeserialize(using=StrictStringDeserializer.class) @Size(max=IngestionLimits.REPORTED_AT_LENGTH) String reportedAt,@JsonDeserialize(using=StrictStringDeserializer.class) @NotNull @Size(max=IngestionLimits.PAYLOAD_BYTES) String payload,@Min(0) Long sequence,@JsonDeserialize(using=StrictStringDeserializer.class) @Size(max=IngestionLimits.OCCURRED_AT_LENGTH) String occurredAt) { }
    public record Apply(@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
    public record Preview(@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
}
