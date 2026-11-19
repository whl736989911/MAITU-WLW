package com.fireplatform.configuration;

import java.time.OffsetDateTime;
import java.util.List;

public final class ParameterModels {
    private ParameterModels() { }
    public record Definition(String key,String name,String unit,long min,long max,String valueType,List<String> allowedScopes,String consumer,String effect,long globalValue,long globalRevision) { }
    public record Effective(String key,long value,String unit,String sourceScope,String sourceId,long sourceRevision,OffsetDateTime evaluatedAt) { }
    public record ChainEntry(String scope,String scopeId,boolean hasOverride,Long value,long revision,boolean isEffective) { }
    public record PolicyView(String key,String scope,String scopeId,String tenantId,boolean hasOverride,Long value,long revision,Effective effective,List<ChainEntry> chain,String consumer) { }
    public record Change(String id,String key,String scope,String scopeId,String tenantId,String action,Long previousValue,Long value,long revisionBefore,long revisionAfter,String actorId,String reason,OffsetDateTime createdAt) { }
    public record Mutation(PolicyView policy,Change change) { }
    public record ChangeReference(String changeId) { }
    public record DeviceEffective(String deviceId,List<Effective> items,String consumer) { }
}
