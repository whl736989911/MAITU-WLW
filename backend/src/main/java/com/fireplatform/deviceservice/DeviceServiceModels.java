package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/** Public read models for the real device service/lifecycle surface. No secrets, no vendor credentials. */
public final class DeviceServiceModels {
    private DeviceServiceModels() { }

    public record ServiceDefinition(
        String id,
        String source,
        String productVersionId,
        String driverKey,
        String serviceKey,
        String displayName,
        String category,
        String riskLevel,
        String resultKind,
        String supportState,
        boolean dispatchReady,
        String dispatchBlockedCode,
        String dispatchBlockedReason,
        JsonNode inputSchema,
        JsonNode applicability,
        JsonNode evidence,
        String status,
        long revision,
        OffsetDateTime publishedAt
    ) { }

    public record SourceBinding(
        String id,
        String driverKey,
        String externalId,
        String serial,
        String accountFingerprint,
        long generation,
        String state,
        OffsetDateTime boundAt,
        String boundBy,
        OffsetDateTime releasedAt,
        String releasedBy,
        String releaseReason,
        JsonNode pendingRisk,
        long pendingRawRecords
    ) { }

    public record Capabilities(
        String deviceId,
        String tenantId,
        long deviceRevision,
        String mode,
        String driverKey,
        boolean driverConfigured,
        String driverConfigurationCode,
        boolean sourceBound,
        SourceBinding binding,
        boolean parameterSnapshotPresent,
        OffsetDateTime parameterObservedAt,
        List<ServiceDefinition> services,
        List<String> notes
    ) { }

    public record ParameterSnapshot(
        String id,
        String deviceId,
        String driverKey,
        Long sourceGeneration,
        JsonNode schema,
        JsonNode values,
        OffsetDateTime observedAt,
        String commandId
    ) { }

    public record CommandSummary(
        String id,
        String tenantId,
        String deviceId,
        String driverKey,
        String driverMode,
        String serviceKey,
        String riskLevel,
        String resultKind,
        String state,
        long statusVersion,
        String acceptedReference,
        String vendorCode,
        String nextAction,
        OffsetDateTime acceptedAt,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt
    ) { }

    public record CommandDetail(
        CommandSummary command,
        JsonNode arguments,
        JsonNode snapshot,
        JsonNode result,
        String actorId,
        String reason,
        String payloadSha256,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt,
        List<Attempt> attempts
    ) { }

    public record Attempt(
        String id,
        String commandId,
        int attemptNo,
        String phase,
        String outcome,
        String vendorCode,
        JsonNode detail,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
    ) { }

    public record RegistrationSummary(
        String id,
        String driverKey,
        String mode,
        String status,
        long revision,
        String tenantId,
        String placeId,
        String productVersionId,
        String deviceId,
        String externalDeviceId,
        String externalSerial,
        String deviceName,
        String deviceModel,
        String cloudState,
        String localState,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime commissionedAt
    ) { }

    public record RegistrationDetail(
        RegistrationSummary registration,
        Integer deviceType,
        Integer communicationType,
        Integer accessMode,
        Integer producterId,
        Integer productModelId,
        String vendorUnitId,
        boolean verificationCodeStored,
        boolean vendorPayloadStored,
        List<String> vendorPayloadFields,
        String location,
        JsonNode coordinates,
        Boolean autoAddChannel,
        String accountFingerprint,
        JsonNode cloudResult,
        String acceptanceNote,
        String createdBy,
        List<RegistrationEvent> events
    ) { }

    public record RegistrationEvent(
        String id,
        String step,
        String outcome,
        JsonNode detail,
        String actorId,
        OffsetDateTime occurredAt
    ) { }

    public record Driver(
        String key,
        String mode,
        String displayName,
        boolean configured,
        boolean enabled,
        String configurationCode,
        String endpoint,
        String tokenMasked,
        String accountFingerprint,
        long revision,
        OffsetDateTime updatedAt,
        boolean configurableOriginAccount,
        List<ServiceDefinition> services
    ) { }

    public record IdResult(String id) { }

    /** Customer-safe projection: no cloud result, account fingerprint, creator, raw vendor metadata or URLs. */
    public record CustomerRegistrationSummary(String id, String driverKey, String mode, String status, long revision,
                                              String deviceId, String externalSerial, String deviceName, String cloudState,
                                              String localState, String placeId, String productVersionId,
                                              OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                              OffsetDateTime commissionedAt) { }
    public record CustomerRegistrationEvent(String step, String outcome, OffsetDateTime occurredAt) { }
    public record CustomerRegistrationDetail(CustomerRegistrationSummary registration, String deviceModel,
                                             String location, String acceptanceNote,
                                             boolean vendorPayloadStored,
                                             List<CustomerRegistrationEvent> events) { }
}
