package com.fireplatform.device;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;

public final class DeviceChannelModels {
    private DeviceChannelModels() { }
    /** Preserve the original timestamp offsets when re-reading saved mutation receipts. */
    public record Item(String id,String tenantId,String deviceId,String channelKey,String name,String channelType,boolean enabled,long revision,
                       @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime createdAt,
                       @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime updatedAt,
                       String deviceName,String tenantName) { }
    public record Page(List<Item> items,long total,int page,int size) { }
    public record Create(String deviceId,String channelKey,String name,String channelType,Boolean enabled,String reason) { }
    public record Update(String name,String channelType,Boolean enabled,Long expectedRevision,String reason) { }
}
