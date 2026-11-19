package com.fireplatform.devicegroup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;

public final class DeviceGroupModels {
    private DeviceGroupModels() { }
    @JsonDeserialize(using=StoredGroup.class)
    public record Group(String id,String tenantId,String name,String description,long revision,long memberCount) { }
    /** Local cached-response decoder: no ignored fields, scalar coercion, JSON null or trailing roots. */
    public static final class StoredGroup extends JsonDeserializer<Group> {
        @Override public Group deserialize(JsonParser parser,DeserializationContext context)throws IOException{JsonNode node=parser.readValueAsTree();if(parser.getParsingContext().inRoot()&&parser.nextToken()!=null)throw DeviceGroupProjection.unconfirmed();return DeviceGroupProjection.stored(node);}
        @Override public Group getNullValue(DeserializationContext context){throw DeviceGroupProjection.unconfirmed();}
    }
    public record DeviceReference(String deviceId,String deviceName,String deviceEui,String address) { }
    public record CandidateReference(String deviceId,String deviceName,String deviceEui,String address,boolean isMember) { }
    public record GroupPage(List<Group> items,long total,int page,int size) { }
    public record MemberPage(Group group,List<DeviceReference> items,long total,int page,int size) { }
    public record CandidatePage(Group group,List<CandidateReference> items,long total,int page,int size) { }
}
