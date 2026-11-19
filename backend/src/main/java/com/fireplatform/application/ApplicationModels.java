package com.fireplatform.application;

import java.time.OffsetDateTime;
import java.util.List;

/** Read projections for open applications and their delivery results. No endpoint secret ever appears here. */
public final class ApplicationModels {
    private ApplicationModels() { }

    /** Json-safe revision bound; every CAS value round-trips exactly through a browser number. */
    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    public record ApplicationSummary(String id, String tenantId, String tenantName, String name, String description,
                                     String status, long revision, int authorizationCount, int outputCount,
                                     int enabledOutputCount, long pendingDeliveries, long unknownDeliveries,
                                     long blockedDeliveries, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                     OffsetDateTime archivedAt, String archivedByName, String archivedReason) { }

    public record ApplicationPage(List<ApplicationSummary> items, long total, int page, int size) { }

    /**
     * One exact reference identity. A kind-specific projection keeps the absent fields null; the write path proves
     * each value against the unit's authoritative registry/source/discovery metadata before it is stored.
     */
    public record Authorization(String id, String referenceKind, String referenceKey, String productId,
                                String productName, String manufacturer, String model, String productVersionId,
                                Long versionNumber, String sourcePlatform, String legacyProductId,
                                Long legacyDecoderRevision, String legacyDecoderSha256, String vendorModelId,
                                String driverKey, String adapterDigest, String scopeMode, String status,
                                long revision, int deviceCount, String createdBy, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt, OffsetDateTime revokedAt, String revokedReason) { }

    public record Output(String id, String name, String mode, String protocol, boolean enabled, String endpointOrigin,
                         String endpointMasked, String endpointDigest, String authScheme, String authHeaderName,
                         String credentialMasked, String credentialDigest, String mqttAuthScheme,
                         String mqttTopicMasked, String mqttTopicDigest, String mqttUsernameMasked,
                         String mqttUsernameDigest, String mqttPasswordMasked, String mqttPasswordDigest,
                         int timeoutMs, long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record DeliverySummary(long pending, long inFlight, long accepted, long rejected, long unknown,
                                  long blocked, OffsetDateTime lastCapturedAt) { }

    public record ApplicationDetail(ApplicationSummary application, List<Authorization> authorizations,
                                    List<Output> outputs, DeliverySummary deliveries) { }

    public record Delivery(String id, String applicationId, String outputId, String outputName, String factId,
                           String deviceId, String deviceName, String productVersionId, String sourcePlatform,
                           String sourceProductKind, String sourceProductId, String sourceDriverKey,
                           String sourceProductDigest, String authorizationReferenceKind,
                           String authorizationReferenceKey, String sourceMessageId, String sourceEventType,
                           OffsetDateTime sourceEventAt, OffsetDateTime receivedAt, String alarmId, String mode,
                           String protocol, boolean singleDeviceProof, String contentType, String payloadSha256,
                           long payloadBytes, String status, String failureCode, Integer httpStatus,
                           String failureMeaning, int attemptCount, OffsetDateTime capturedAt,
                           OffsetDateTime claimedAt, OffsetDateTime finishedAt) { }

    public record DeliveryPage(List<Delivery> items, long total, int page, int size) { }

    public record Attempt(String id, int attemptNo, long outputRevision, String endpointOrigin,
                          String endpointMasked, String endpointDigest, String mode, String protocol, String status,
                          Integer httpStatus, String failureCode, String failureMeaning,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt, long durationMs) { }

    public record DeliveryDetail(Delivery delivery, List<Attempt> attempts, String payloadRetention,
                                 String successSemantics) { }

    public record Event(String id, String subjectType, String subjectId, String action, Long revisionBefore,
                        long revisionAfter, String actorId, String actorName, String reason, OffsetDateTime createdAt) { }

    public record EventPage(List<Event> items, long total, int page, int size) { }

    public record Driver(String key, String name) { }

    /** One selectable authoritative identity; never a name-inferred or "first model" guess. */
    public record ReferenceOption(String id, String label, String detail, Long revision, String sha256,
                                  Integer deviceCount) { }

    public record ReferenceOptions(String kind, List<String> platforms, List<Driver> drivers,
                                   List<ReferenceOption> items, long total, int page, int size) { }

    /** Truthful labels: acceptance is transport-level, never a remote business result. */
    public static String meaning(String status, String failureCode) {
        if ("ACCEPTED".equals(status)) return "远端已接受传输层请求（HTTP 2xx 或 MQTT PUBACK，不代表远端已业务处理）";
        if ("IN_FLIGHT".equals(status)) return "已提交执行中，结果尚未确认";
        if ("PENDING".equals(status)) return "已持久捕获，等待执行";
        if ("UNKNOWN".equals(status)) return "结果未确认；禁止自动重发";
        if ("BLOCKED".equals(status)) {
            if ("IDENTITY_PREREQUISITE_MISSING".equals(failureCode))
                return "执行前阻断：缺少授权身份或解码器/适配器摘要证据（前置条件未满足）";
            if ("IDENTITY_MISMATCH".equals(failureCode))
                return "执行前阻断：事实来源身份或解码器/适配器摘要与授权不一致";
            return "执行前被阻断，未发起外呼";
        }
        if ("REJECTED".equals(status)) return "远端明确拒绝（HTTP 4xx 或 MQTT CONNACK 拒绝）";
        return failureCode == null ? "状态未确认" : failureCode;
    }
}
