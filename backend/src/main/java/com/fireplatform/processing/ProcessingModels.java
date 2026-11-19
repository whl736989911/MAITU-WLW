package com.fireplatform.processing;

import java.time.OffsetDateTime;
import java.util.List;

public final class ProcessingModels {
    private ProcessingModels() { }
    public record Task(String id,String kind,String tenantId,String rawMessageId,String sourceId,String sourceMessageId,String deviceId,String deviceName,String productVersionId,String actorId,long expectedDeviceRevision,String recoveryAlarmId,String reason,String status,long revision,int attemptCount,int maxAttempts,OffsetDateTime availableAt,String leaseOwner,OffsetDateTime leaseUntil,long leaseToken,OffsetDateTime createdAt,OffsetDateTime finishedAt,String lastErrorCode,String result) { }
    public record Attempt(String id,int attemptNo,long leaseToken,String workerId,OffsetDateTime startedAt,OffsetDateTime finishedAt,String outcome,String errorCode) { }
    public record Detail(Task task,List<Attempt> attempts) { }
}
