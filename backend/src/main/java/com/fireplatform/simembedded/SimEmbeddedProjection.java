package com.fireplatform.simembedded;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.shared.ApiError;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.fireplatform.simembedded.SimEmbeddedModels.*;

public final class SimEmbeddedProjection {
    private SimEmbeddedProjection(){ }
    public static final String PROFILE_COLUMNS="device_id,tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at";
    public static final String CHANGE_COLUMNS="id AS change_id,"+PROFILE_COLUMNS+",operation,reason";
    private static final Set<String> KEYS=Set.of("device_id","tenant_id","iccid","carrier_name","installation_form","replaceability","management_party","fee_responsibility","reported_operator_status","expires_on","evidence_at","source_note","information_source","revision","recorded_at");
    public static ApiError unconfirmed(){return new ApiError(503,"SIM_EMBEDDED_READ_UNCONFIRMED","内置人工资料未确认，请稍后重试");}
    public static ApiError missing(){return new ApiError(404,"SIM_EMBEDDED_NOT_FOUND","本单位设备资料资源不存在");}
    public static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    public static long safe(long value){if(value<0||value>SimEmbeddedInputs.MAX_SAFE)throw unconfirmed();return value;}
    public static long number(Object raw){try{if(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long)return safe(((Number)raw).longValue());if(raw instanceof BigInteger number)return safe(number.longValueExact());if(raw instanceof BigDecimal number)return safe(number.longValueExact());throw unconfirmed();}catch(ArithmeticException e){throw unconfirmed();}}
    public static String instant(OffsetDateTime value){try{if(value==null)throw unconfirmed();OffsetDateTime utc=value.withOffsetSameInstant(ZoneOffset.UTC);if(utc.getYear()<1||utc.getYear()>9999||utc.getNano()%1000!=0)throw unconfirmed();return SimEmbeddedInputs.TIME.format(utc.toInstant());}catch(DateTimeException e){throw unconfirmed();}}
    private static String timestamp(String value){if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z")||!value.equals(SimEmbeddedInputs.evidence(value)))throw unconfirmed();return value;}
    public static Profile empty(String tenant,String device){return valid(new Profile(device,tenant,null,"","EMBEDDED","UNKNOWN","UNKNOWN","UNKNOWN","UNKNOWN",null,null,"","MANUAL",0,null),tenant,device);}
    public static Profile valid(Profile value,String tenant,String device){try{
        if(value==null||!SimEmbeddedInputs.device(value.deviceId()).equals(device)||value.tenantId()==null||value.tenantId().isEmpty()||value.tenantId().length()>64||!value.tenantId().equals(tenant))throw unconfirmed();SimEmbeddedInputs.unicode(value.tenantId());safe(value.revision());
        if(!"EMBEDDED".equals(value.installationForm())||!"MANUAL".equals(value.informationSource())||!Objects.equals(SimEmbeddedInputs.iccid(value.iccid()),value.iccid())||!SimEmbeddedInputs.carrier(value.carrierName()).equals(value.carrierName())||!SimEmbeddedInputs.note(value.sourceNote()).equals(value.sourceNote()))throw unconfirmed();
        SimEmbeddedInputs.choice(value.replaceability(),SimEmbeddedInputs.REPLACEABILITY);SimEmbeddedInputs.choice(value.managementParty(),SimEmbeddedInputs.PARTY);SimEmbeddedInputs.choice(value.feeResponsibility(),SimEmbeddedInputs.PARTY);SimEmbeddedInputs.choice(value.reportedOperatorStatus(),SimEmbeddedInputs.REPORTED_STATUS);
        if(!Objects.equals(SimEmbeddedInputs.date(value.expiresOn()),value.expiresOn())||!Objects.equals(SimEmbeddedInputs.evidence(value.evidenceAt()),value.evidenceAt()))throw unconfirmed();
        if(value.revision()==0){if(value.iccid()!=null||!value.carrierName().isEmpty()||!value.sourceNote().isEmpty()||value.expiresOn()!=null||value.evidenceAt()!=null||value.recordedAt()!=null||!"UNKNOWN".equals(value.replaceability())||!"UNKNOWN".equals(value.managementParty())||!"UNKNOWN".equals(value.feeResponsibility())||!"UNKNOWN".equals(value.reportedOperatorStatus()))throw unconfirmed();}else timestamp(value.recordedAt());return value;
    }catch(RuntimeException e){throw unconfirmed();}}
    public static Profile profile(ResultSet rs,String tenant,String device)throws SQLException{try{
        long revision=number(rs.getObject("revision"));if(revision==0)throw unconfirmed();LocalDate expires=rs.getObject("expires_on",LocalDate.class);OffsetDateTime evidence=rs.getObject("evidence_at",OffsetDateTime.class);
        return valid(new Profile(rs.getString("device_id"),rs.getString("tenant_id"),rs.getString("iccid"),rs.getString("carrier_name"),rs.getString("installation_form"),rs.getString("replaceability"),rs.getString("management_party"),rs.getString("fee_responsibility"),rs.getString("reported_operator_status"),expires==null?null:expires.toString(),evidence==null?null:instant(evidence),rs.getString("source_note"),rs.getString("information_source"),revision,instant(rs.getObject("recorded_at",OffsetDateTime.class))),tenant,device);
    }catch(SQLException|RuntimeException e){throw unconfirmed();}}
    public static Change change(ResultSet rs,Profile current)throws SQLException{try{Profile profile=profile(rs,current.tenantId(),current.deviceId());String id=rs.getString("change_id"),operation=rs.getString("operation"),reason=SimEmbeddedInputs.reason(rs.getString("reason"));if(!uuid(id)||!"RECORD".equals(operation)||profile.revision()>current.revision()||(profile.revision()==current.revision()&&!profile.equals(current)))throw unconfirmed();return new Change(id,profile.deviceId(),profile.tenantId(),profile.revision(),operation,profile,reason,profile.recordedAt());}catch(SQLException|RuntimeException e){throw unconfirmed();}}
    public static Profile stored(JsonNode node){try{SimEmbeddedInputs.keys(node,KEYS);String tenant=SimEmbeddedInputs.string(node,"tenant_id"),device=SimEmbeddedInputs.string(node,"device_id");return valid(new Profile(device,tenant,SimEmbeddedInputs.nullableString(node,"iccid"),SimEmbeddedInputs.string(node,"carrier_name"),SimEmbeddedInputs.string(node,"installation_form"),SimEmbeddedInputs.string(node,"replaceability"),SimEmbeddedInputs.string(node,"management_party"),SimEmbeddedInputs.string(node,"fee_responsibility"),SimEmbeddedInputs.string(node,"reported_operator_status"),SimEmbeddedInputs.nullableString(node,"expires_on"),SimEmbeddedInputs.nullableString(node,"evidence_at"),SimEmbeddedInputs.string(node,"source_note"),SimEmbeddedInputs.string(node,"information_source"),SimEmbeddedInputs.integer(node.get("revision")),SimEmbeddedInputs.nullableString(node,"recorded_at")),tenant,device);}catch(RuntimeException e){throw unconfirmed();}}
}
