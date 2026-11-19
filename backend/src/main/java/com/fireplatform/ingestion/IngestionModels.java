package com.fireplatform.ingestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class IngestionModels {
    private IngestionModels() { }
    public record Source(String id,String tenantId,String name,String protocol,boolean isSimulated,boolean enabled,long revision,OffsetDateTime createdAt,OffsetDateTime lastReceivedAt,long messageCount,long duplicateCount) { }
    public record CredentialResult(Source source,String oneTimeToken,boolean credentialRecoveryRequired) { }
    public record Binding(String id,String sourceId,String deviceId,String vendorDeviceId,boolean isActive,OffsetDateTime createdAt,OffsetDateTime endedAt) { }
    public record BindingResult(Source source,Binding binding) { }
    public record Receipt(String rawMessageId,String sourceId,String sourceMessageId,String bindingState,String parseStatus,String businessStatus,boolean deduplicated,OffsetDateTime receivedAt) { }
    public record RawSummary(String id,String tenantId,String sourceId,String sourceName,String sourceMessageId,String vendorDeviceId,String deviceId,String deviceName,String bindingId,String productVersionId,OffsetDateTime receivedAt,String reportedAt,long payloadBytes,String payloadSha256,String bindingState,long duplicateCount,long revision,String latestPreviewId,String parseStatus,String businessStatus,String applicationState,OffsetDateTime appliedAt,String applyError,@com.fasterxml.jackson.annotation.JsonProperty("capture_origin") String captureOrigin,OffsetDateTime occurredAt,String occurredAtRaw) { }
    public record RawContent(String id,String payload,long payloadBytes,String payloadSha256) { }
    public record Preview(String id,String rawMessageId,String productVersionId,String eventType,BigDecimal batteryPercent,List<com.fireplatform.product.ProductModels.MeasurementReading> readings,List<String> diagnostics,OffsetDateTime reportedAt,String timeQuality,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode rawEvent,OffsetDateTime createdAt,String actorId,String reason,String businessStatus) { public Preview { if(readings==null) readings=List.of(); } }
    public record MessageDetail(RawSummary message,List<Preview> previews) { }
    public record PreviewResult(RawSummary message,Preview preview) { }
    public record ApplyResult(String rawMessageId,String applicationState,String eventType,String alarmId,String physicalState,java.math.BigDecimal batteryPercent,List<com.fireplatform.product.ProductModels.MeasurementReading> readings,Long sequence,OffsetDateTime occurredAt,String outboxEventType) { public ApplyResult { if(readings==null) readings=List.of(); } }
    public record PreviewReference(RawSummary message,String previewId) { }
}
