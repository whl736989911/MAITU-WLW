package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

/** Request contracts. The global SNAKE_CASE strategy plus fail-on-unknown keeps request bodies exact. */
public final class DeviceServiceInputs {
    private DeviceServiceInputs() { }

    public record CreateCommand(
        @NotBlank @Size(max=64) String serviceKey,
        @NotNull JsonNode arguments,
        @NotNull @Min(0) Long expectedDeviceRevision,
        @Min(0) Long capabilityRevision,
        @Size(max=64) String sourceBindingId,
        @NotBlank @Size(max=1000) String reason,
        boolean riskConfirmed) { }

    public record CancelCommand(@NotBlank @Size(max=1000) String reason) { }

    public record ReconcileCommand(@NotBlank @Size(max=1000) String reason) { }

    public record CreateRegistration(
        @NotBlank @Pattern(regexp="NEW_VENDOR|MATCH_EXISTING|PRODUCT_MODEL") String mode,
        @NotBlank @Size(max=64) String driverKey,
        @NotBlank @Size(max=200) String externalSerial,
        @Size(max=200) String deviceName,
        @Size(max=100) String deviceModel,
        @Min(1) Integer deviceType,
        @Min(1) Integer communicationType,
        @Min(1) Integer accessMode,
        @Min(1) Integer producterId,
        @Min(1) Integer productModelId,
        @Size(max=200) String verificationCode,
        @Size(max=500) String location,
        @DecimalMin("-90") @DecimalMax("90") java.math.BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") java.math.BigDecimal longitude,
        Boolean autoAddChannel,
        @Size(max=128) String externalDeviceId,
        com.fasterxml.jackson.databind.JsonNode vendorPayload,
        @Size(max=64) String tenantId,
        @Size(max=64) String placeId,
        @Size(max=64) String productVersionId) { }

    public record CommissionRegistration(
        @NotBlank @Size(max=64) String tenantId,
        @NotBlank @Size(max=64) String placeId,
        @Size(max=64) String productVersionId,
        @Size(max=64) String existingDeviceId,
        @Min(0) Long existingDeviceRevision,
        @NotBlank @Size(max=1000) String acceptanceNote,
        @Size(max=200) String deviceName,
        @Size(max=500) String address,
        @Size(max=80) String systemType,
        @Size(max=80) String deviceCategory) { }

    public record AbandonRegistration(@NotBlank @Size(max=1000) String reason) { }

    public record UnbindSource(
        @NotNull @Min(0) Long expectedDeviceRevision,
        @Size(max=64) String bindingId,
        @NotBlank @Size(max=1000) String reason) { }

    public record CloudDelete(
        @NotNull @Min(0) Long expectedDeviceRevision,
        @Size(max=128) String externalDeviceId,
        @Size(max=200) String externalSerial,
        @NotBlank @Size(max=1000) String reason,
        boolean riskConfirmed) { }

    public record CreateServiceDefinition(
        @NotBlank @Size(max=64) String operationKey,
        @NotBlank @Size(max=200) String displayName,
        @NotBlank @Pattern(regexp="QUERY|CONFIGURATION|CONTROL") String category,
        @NotBlank @Pattern(regexp="NORMAL|ENGINEERING") String riskLevel,
        @NotBlank @Pattern(regexp="PARAMETERS|COMMAND|STATUS") String resultKind,
        @NotNull JsonNode inputSchema,
        @NotNull JsonNode applicability,
        @NotNull JsonNode evidence,
        @NotBlank @Pattern(regexp="VERIFIED|UNVERIFIED") String supportState,
        @NotNull @Min(0) Long expectedVersionRevision) { }

    public record UpdateServiceDefinition(
        @NotBlank @Size(max=200) String displayName,
        @NotBlank @Pattern(regexp="QUERY|CONFIGURATION|CONTROL") String category,
        @NotBlank @Pattern(regexp="NORMAL|ENGINEERING") String riskLevel,
        @NotBlank @Pattern(regexp="PARAMETERS|COMMAND|STATUS") String resultKind,
        @NotNull JsonNode inputSchema,
        @NotNull JsonNode applicability,
        @NotNull JsonNode evidence,
        @NotBlank @Pattern(regexp="VERIFIED|UNVERIFIED") String supportState,
        @NotNull @Min(0) Long expectedRevision) { }

    public record DeleteServiceDefinition(@NotNull @Min(0) Long expectedRevision) { }

    public record UpdateDriverConfig(
        @NotBlank @Size(max=500) String endpoint,
        @Size(max=500) String token,
        boolean enabled,
        @NotNull @Min(0) Long expectedRevision) { }

    public record UpdateLegacySource(
        @NotBlank @Size(max=500) String origin,
        @NotBlank @Size(max=200) String username,
        @Size(max=500) String password,
        boolean allowInsecureHttp,
        boolean enabled,
        @NotNull @Min(0) Long expectedRevision) { }

    public record CapabilityEvidence(
        @NotBlank @Size(max=100) String model,
        @NotBlank @Size(max=64) String serviceKey,
        @NotBlank @Size(max=2000) String evidence) { }
}
