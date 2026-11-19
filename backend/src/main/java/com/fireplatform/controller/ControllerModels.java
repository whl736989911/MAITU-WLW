package com.fireplatform.controller;

import java.time.OffsetDateTime;
import java.util.List;

/** A controller association is one real device plus one live service capability, never a free-form relay id. */
public final class ControllerModels {
    private ControllerModels() { }
    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    public record Resource(String id, String tenantId, String name, String deviceId, String deviceName, String deviceEui,
                           String action, String serviceKey, String driverKey, String driverMode,
                           String serviceDefinitionId, long capabilityRevision, String supportState, String riskLevel,
                           String resultKind, String argumentsJson, boolean enabled, String state, long revision,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record Detail(Resource resource, com.fireplatform.deviceservice.DeviceServiceModels.Capabilities capabilities,
                         com.fireplatform.shared.Page<com.fireplatform.deviceservice.DeviceServiceModels.CommandSummary> commands,
                         String note) { }

    public record Enqueue(String controllerId, String action, String deviceId, String serviceKey, String commandId,
                          String state, String blockedCode, String blockedReason) { }

    public record VideoMatch(String id, String name, String protocol, boolean enabled, String playback,
                             String snapshot, boolean mediaReady, String note) { }

    public record IdResult(String id) { }
}
