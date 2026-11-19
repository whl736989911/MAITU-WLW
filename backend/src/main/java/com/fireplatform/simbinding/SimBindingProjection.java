package com.fireplatform.simbinding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.shared.ApiError;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.fireplatform.simbinding.SimBindingModels.*;

public final class SimBindingProjection {
    private SimBindingProjection(){ }
    public static final String STATE_COLUMNS="card_id,tenant_id,device_id,revision,recorded_at";
    public static final String CHANGE_COLUMNS="id AS change_id,card_id,tenant_id,revision,operation,from_device_id,to_device_id,reason,recorded_at";
    private static final Set<String> STATE_KEYS=Set.of("card_id","tenant_id","device_id","device_visibility","revision","recorded_at");
    private static final Set<String> LEGACY_STATE_KEYS=Set.of("card_id","tenant_id","device_id","revision","recorded_at");
    private static final DateTimeFormatter TIME=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    public static ApiError unconfirmed(){return new ApiError(503,"SIM_BINDING_READ_UNCONFIRMED","SIM设备关联数据未确认，请稍后重试");}
    public static ApiError cardMissing(){return new ApiError(404,"SIM_CARD_NOT_FOUND","SIM手工台账不存在");}
    public static ApiError deviceMissing(){return new ApiError(404,"SIM_BINDING_DEVICE_NOT_FOUND","本单位设备不存在");}
    public static ApiError occupied(){return ApiError.conflict("SIM_BINDING_DEVICE_CONFLICT","本单位目标设备已有SIM关联，请先显式解绑");}
    public static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    public static long safe(long value){if(value<0||value>SimBindingInputs.MAX_SAFE)throw unconfirmed();return value;}
    public static long number(Object raw){try{if(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long)return safe(((Number)raw).longValue());if(raw instanceof BigInteger value)return safe(value.longValueExact());if(raw instanceof BigDecimal value)return safe(value.longValueExact());throw unconfirmed();}catch(ArithmeticException e){throw unconfirmed();}}
    public static String instant(OffsetDateTime value){try{if(value==null)throw unconfirmed();OffsetDateTime utc=value.withOffsetSameInstant(ZoneOffset.UTC);if(utc.getYear()<1||utc.getYear()>9999||utc.getNano()%1000!=0)throw unconfirmed();return TIME.format(utc.toInstant());}catch(DateTimeException e){throw unconfirmed();}}
    private static String timestamp(String value){try{if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z"))throw unconfirmed();if(!instant(OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME)).equals(value))throw unconfirmed();return value;}catch(DateTimeException e){throw unconfirmed();}}
    public static State empty(String tenant,String card){return valid(new State(card,tenant,null,DeviceVisibility.UNBOUND,0,null),tenant,card);}
    public static State valid(State value,String tenant,String card){
        try{if(value==null||!uuid(value.cardId())||!value.cardId().equals(card)||value.tenantId()==null||value.tenantId().isEmpty()||value.tenantId().length()>64||!value.tenantId().equals(tenant))throw unconfirmed();SimBindingInputs.unicode(value.tenantId());safe(value.revision());if(value.deviceId()!=null)SimBindingInputs.device(value.deviceId());if(value.deviceVisibility()==null||(value.deviceVisibility()==DeviceVisibility.VISIBLE)!=(value.deviceId()!=null)||(value.deviceVisibility()==DeviceVisibility.CONCEALED&&value.deviceId()!=null))throw unconfirmed();
            if(value.revision()==0){if(value.deviceId()!=null||value.deviceVisibility()!=DeviceVisibility.UNBOUND||value.recordedAt()!=null)throw unconfirmed();}else timestamp(value.recordedAt());return value;
        }catch(RuntimeException e){throw unconfirmed();}
    }
    public static State state(ResultSet rs,String tenant,String card)throws SQLException{
        try{long revision=number(rs.getObject("revision"));if(revision==0)throw unconfirmed();String device=rs.getString("device_id");return valid(new State(rs.getString("card_id"),rs.getString("tenant_id"),device,device==null?DeviceVisibility.UNBOUND:DeviceVisibility.VISIBLE,revision,instant(rs.getObject("recorded_at",OffsetDateTime.class))),tenant,card);}catch(SQLException|RuntimeException e){throw unconfirmed();}
    }
    public static Change change(ResultSet rs,State current)throws SQLException{
        try{String id=rs.getString("change_id"),card=rs.getString("card_id"),tenant=rs.getString("tenant_id"),operation=rs.getString("operation"),from=rs.getString("from_device_id"),to=rs.getString("to_device_id"),reason=SimBindingInputs.reason(rs.getString("reason")),recorded=instant(rs.getObject("recorded_at",OffsetDateTime.class));long revision=number(rs.getObject("revision"));
            if(!uuid(id)||!current.cardId().equals(card)||!current.tenantId().equals(tenant)||revision==0||revision>current.revision())throw unconfirmed();if(from!=null)SimBindingInputs.device(from);if(to!=null)SimBindingInputs.device(to);
            if(!("BIND".equals(operation)&&from==null&&to!=null)&&!("UNBIND".equals(operation)&&from!=null&&to==null))throw unconfirmed();
            if(revision==current.revision()&&(!Objects.equals(to,current.deviceId())||!recorded.equals(current.recordedAt())))throw unconfirmed();
            return new Change(id,card,tenant,revision,operation,from,from==null?EndpointVisibility.ABSENT:EndpointVisibility.VISIBLE,to,to==null?EndpointVisibility.ABSENT:EndpointVisibility.VISIBLE,reason,recorded);
        }catch(SQLException|RuntimeException e){throw unconfirmed();}
    }
    public static Receipt stored(JsonNode node){
        try{SimBindingInputs.keys(node,Set.of("association","changed"));JsonNode changed=node.get("changed");if(!changed.isBoolean())throw unconfirmed();JsonNode state=node.get("association");if(state==null||!state.isObject())throw unconfirmed();Set<String> fields=new HashSet<>();state.fieldNames().forEachRemaining(fields::add);if(!fields.equals(STATE_KEYS)&&!fields.equals(LEGACY_STATE_KEYS))throw unconfirmed();String card=SimBindingInputs.string(state,"card_id"),tenant=SimBindingInputs.string(state,"tenant_id"),device=SimBindingInputs.nullableString(state,"device_id");DeviceVisibility visibility=fields.equals(STATE_KEYS)?DeviceVisibility.valueOf(SimBindingInputs.string(state,"device_visibility")):device==null?DeviceVisibility.UNBOUND:DeviceVisibility.VISIBLE;State value=valid(new State(card,tenant,device,visibility,SimBindingInputs.integer(state.get("revision")),SimBindingInputs.nullableString(state,"recorded_at")),tenant,card);if(changed.booleanValue()&&value.revision()==0)throw unconfirmed();return new Receipt(value,changed.booleanValue());}catch(RuntimeException e){throw unconfirmed();}
    }
}
