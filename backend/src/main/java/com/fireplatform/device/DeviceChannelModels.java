package com.fireplatform.device;

import java.time.OffsetDateTime;
import java.util.List;

public final class DeviceChannelModels {
    private DeviceChannelModels() { }
    public record Item(String id,String tenantId,String deviceId,String channelKey,String name,String channelType,boolean enabled,long revision,java.time.OffsetDateTime createdAt,java.time.OffsetDateTime updatedAt,String deviceName,String tenantName) { }
    public record Page(List<Item> items,long total,int page,int size) { }
    public record Create(String deviceId,String channelKey,String name,String channelType,Boolean enabled,String reason) { }
    public record Update(String name,String channelType,Boolean enabled,Long expectedRevision,String reason) { }
}
