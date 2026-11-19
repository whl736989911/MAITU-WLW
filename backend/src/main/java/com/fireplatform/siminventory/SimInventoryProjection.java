package com.fireplatform.siminventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.shared.ApiError;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.fireplatform.siminventory.SimInventoryModels.*;
import static com.fireplatform.siminventory.SimInventoryInputs.Operation;

public final class SimInventoryProjection {
    private SimInventoryProjection(){ }
    public static final String STATE_COLUMNS="card_id,tenant_id,status,issued_to,revision,recorded_at";
    public static final String CHANGE_COLUMNS="id AS change_id,card_id,tenant_id,revision,operation,from_status,to_status,issued_to,reason,recorded_at";
    private static final Set<String> KEYS=Set.of("card_id","tenant_id","status","issued_to","revision","recorded_at");
    private static final DateTimeFormatter TIME=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    public static ApiError unconfirmed(){return new ApiError(503,"SIM_INVENTORY_READ_UNCONFIRMED","SIM人工库存未确认，请稍后重试");}
    public static ApiError missing(){return new ApiError(404,"SIM_INVENTORY_NOT_FOUND","本单位SIM库存资源不存在");}
    public static ApiError conflict(){return ApiError.conflict("SIM_INVENTORY_STATE_CONFLICT","库存登记状态不允许此操作，报废后须显式更正");}
    public static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    public static long safe(long value){if(value<0||value>SimInventoryInputs.MAX_SAFE)throw unconfirmed();return value;}
    public static long number(Object raw){try{if(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long)return safe(((Number)raw).longValue());if(raw instanceof BigInteger value)return safe(value.longValueExact());if(raw instanceof BigDecimal value)return safe(value.longValueExact());throw unconfirmed();}catch(ArithmeticException e){throw unconfirmed();}}
    public static String instant(OffsetDateTime value){try{if(value==null)throw unconfirmed();OffsetDateTime utc=value.withOffsetSameInstant(ZoneOffset.UTC);if(utc.getYear()<1||utc.getYear()>9999||utc.getNano()%1000!=0)throw unconfirmed();return TIME.format(utc.toInstant());}catch(DateTimeException e){throw unconfirmed();}}
    private static String timestamp(String value){try{if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z")||!instant(OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME)).equals(value))throw unconfirmed();return value;}catch(DateTimeException e){throw unconfirmed();}}
    public static State empty(String tenant,String card){return valid(new State(card,tenant,"UNREGISTERED","",0,null),tenant,card);}
    private static void recipient(String status,String issued){if(issued==null)throw unconfirmed();if("ISSUED".equals(status)){if(!SimInventoryInputs.issuedTo(issued).equals(issued))throw unconfirmed();}else if(!issued.isEmpty())throw unconfirmed();}
    public static State valid(State value,String tenant,String card){try{
        if(value==null||!uuid(value.cardId())||!value.cardId().equals(card)||value.tenantId()==null||value.tenantId().isEmpty()||value.tenantId().length()>64||!value.tenantId().equals(tenant))throw unconfirmed();SimInventoryInputs.unicode(value.tenantId());safe(value.revision());
        if(value.revision()==0){if(!"UNREGISTERED".equals(value.status())||!"".equals(value.issuedTo())||value.recordedAt()!=null)throw unconfirmed();}else{if(!SimInventoryInputs.REGISTERED.contains(value.status()))throw unconfirmed();recipient(value.status(),value.issuedTo());timestamp(value.recordedAt());}return value;
    }catch(RuntimeException e){throw unconfirmed();}}
    public static State state(ResultSet rs,String tenant,String card)throws SQLException{try{long revision=number(rs.getObject("revision"));if(revision==0)throw unconfirmed();return valid(new State(rs.getString("card_id"),rs.getString("tenant_id"),rs.getString("status"),rs.getString("issued_to"),revision,instant(rs.getObject("recorded_at",OffsetDateTime.class))),tenant,card);}catch(SQLException|RuntimeException e){throw unconfirmed();}}
    public static Change change(ResultSet rs,State current)throws SQLException{try{
        String id=rs.getString("change_id"),card=rs.getString("card_id"),tenant=rs.getString("tenant_id"),from=rs.getString("from_status"),to=rs.getString("to_status"),issued=rs.getString("issued_to"),reason=SimInventoryInputs.reason(rs.getString("reason")),recorded=instant(rs.getObject("recorded_at",OffsetDateTime.class));long revision=number(rs.getObject("revision"));Operation operation=Operation.valueOf(rs.getString("operation"));
        if(!uuid(id)||!current.cardId().equals(card)||!current.tenantId().equals(tenant)||revision==0||revision>current.revision()||((revision==1)!= "UNREGISTERED".equals(from))||!SimInventoryInputs.allows(operation,from)||!operation.target().equals(to))throw unconfirmed();recipient(to,issued);if(operation==Operation.CORRECT&&reason.trim().isEmpty())throw unconfirmed();if(revision==current.revision()&&(!to.equals(current.status())||!issued.equals(current.issuedTo())||!recorded.equals(current.recordedAt())))throw unconfirmed();return new Change(id,card,tenant,revision,operation.name(),from,to,issued,reason,recorded);
    }catch(SQLException|RuntimeException e){throw unconfirmed();}}
    public static State stored(JsonNode node){try{SimInventoryInputs.keys(node,KEYS);String tenant=SimInventoryInputs.string(node,"tenant_id"),card=SimInventoryInputs.string(node,"card_id");return valid(new State(card,tenant,SimInventoryInputs.string(node,"status"),SimInventoryInputs.string(node,"issued_to"),SimInventoryInputs.integer(node.get("revision")),SimInventoryInputs.nullableString(node,"recorded_at")),tenant,card);}catch(RuntimeException e){throw unconfirmed();}}
}
