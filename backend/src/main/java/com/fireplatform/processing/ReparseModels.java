package com.fireplatform.processing;

import com.fireplatform.product.PublishedMessageNormalizer;
import java.time.OffsetDateTime;

/** N07 重解析：纯解析任务模型，不写设备/报警/通知。 */
public final class ReparseModels {
    private ReparseModels() { }
    public record Job(String id,String tenantId,String rawMessageId,String productVersionId,String actorId,String reason,String status,int attemptCount,int maxAttempts,long revision,OffsetDateTime createdAt,OffsetDateTime startedAt,OffsetDateTime finishedAt,String lastErrorCode,String resultJson) { }
    public record Attempt(String id,String jobId,int attemptNo,long leaseToken,String workerId,OffsetDateTime startedAt,OffsetDateTime finishedAt,String outcome,String errorCode,String resultJson) { }
    public record Detail(Job job,java.util.List<Attempt> attempts) { }
    public record Lease(String jobId,String owner,long token,String attemptId) { }
    public record Parsed(String eventType,String batteryPercent,boolean diagnosticsEmpty) { }
    public record Applied(String jobId,String eventType,String batteryPercent,String status,int attemptCount) { }
}
