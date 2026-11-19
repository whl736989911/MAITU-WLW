package com.fireplatform.devicegroup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.shared.ApiError;
import java.sql.*;
import java.util.*;
import static com.fireplatform.devicegroup.DeviceGroupModels.*;

public final class DeviceGroupProjection {
    private DeviceGroupProjection() { }
    public static final String GROUP_COLUMNS="g.id,g.tenant_id,g.name,g.description,g.revision,(SELECT COUNT(*) FROM device_group_members cm JOIN devices cd ON cd.id=cm.device_id AND cd.tenant_id=cm.tenant_id WHERE cm.group_id=g.id AND cm.tenant_id=g.tenant_id AND cd.is_simulated=FALSE AND cd.archived_at IS NULL) AS member_count";
    public static final String DEVICE_COLUMNS="d.id AS device_id,d.name AS device_name,d.device_eui,d.address";
    public static ApiError unconfirmed(){return new ApiError(503,"DEVICE_GROUP_READ_UNCONFIRMED","设备分组数据未确认，请稍后重试");}
    public static ApiError missing(){return new ApiError(404,"DEVICE_GROUP_NOT_FOUND","设备分组不存在");}
    public static boolean groupId(String id){return id!=null&&id.length()==36&&id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    public static long safe(long value){if(value<0||value>DeviceGroupInputs.MAX_SAFE)throw unconfirmed();return value;}
    public static long number(ResultSet rs,String column)throws SQLException{long value=rs.getLong(column);if(rs.wasNull())throw unconfirmed();return safe(value);}
    public static long total(ResultSet rs)throws SQLException{long value=rs.getLong(1);if(rs.wasNull())throw unconfirmed();return safe(value);}
    public static Group valid(Group value,String tenant){
        try{if(value==null||!groupId(value.id())||value.tenantId()==null||value.tenantId().isEmpty()||value.tenantId().length()>64||tenant!=null&&!value.tenantId().equals(tenant)||!DeviceGroupInputs.name(value.name()).equals(value.name())||!DeviceGroupInputs.description(value.description()).equals(value.description()))throw unconfirmed();safe(value.revision());safe(value.memberCount());return value;}catch(ApiError e){throw unconfirmed();}
    }
    public static Group group(ResultSet rs,String tenant)throws SQLException{
        try{return valid(new Group(rs.getString("id"),rs.getString("tenant_id"),rs.getString("name"),rs.getString("description"),number(rs,"revision"),number(rs,"member_count")),tenant);}catch(SQLException|RuntimeException e){throw unconfirmed();}
    }
    private static String text(ResultSet rs,String column,int max)throws SQLException{String value=rs.getString(column);try{DeviceGroupInputs.unicode(value);if(value.length()>max)throw unconfirmed();return value;}catch(ApiError e){throw unconfirmed();}}
    public static DeviceReference device(ResultSet rs)throws SQLException{
        try{String id=DeviceGroupInputs.deviceId(rs.getString("device_id"));return new DeviceReference(id,text(rs,"device_name",200),text(rs,"device_eui",64),text(rs,"address",500));}catch(SQLException|RuntimeException e){throw unconfirmed();}
    }
    public static CandidateReference candidate(ResultSet rs)throws SQLException{var ref=device(rs);boolean member=rs.getBoolean("is_member");if(rs.wasNull())throw unconfirmed();return new CandidateReference(ref.deviceId(),ref.deviceName(),ref.deviceEui(),ref.address(),member);}
    public static Group stored(JsonNode node){
        try{var keys=new HashSet<String>();if(node==null||!node.isObject()||node.size()!=6)throw unconfirmed();node.fieldNames().forEachRemaining(keys::add);if(!keys.equals(Set.of("id","tenant_id","name","description","revision","member_count")))throw unconfirmed();String tenant=DeviceGroupInputs.string(node,"tenant_id");return valid(new Group(DeviceGroupInputs.string(node,"id"),tenant,DeviceGroupInputs.string(node,"name"),DeviceGroupInputs.string(node,"description"),DeviceGroupInputs.integer(node.get("revision")),DeviceGroupInputs.integer(node.get("member_count"))),tenant);}catch(RuntimeException e){throw unconfirmed();}
    }
}
