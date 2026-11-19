package com.fireplatform.simcard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.shared.ApiError;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.fireplatform.simcard.SimCardModels.*;

/** Explicit safe columns; actor identities are stored internally and never selected. */
public final class SimCardProjection {
    private SimCardProjection(){ }
    private static final String FIELDS="tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at";
    public static final String CARD_COLUMNS="c.id,c."+FIELDS.replace(",",",c.");
    public static final String HISTORY_COLUMNS="h.card_id AS id,h."+FIELDS.replace(",",",h.")+",h.id AS change_id,h.operation";
    private static final Set<String> KEYS=Set.of("id","tenant_id","iccid","carrier_name","installation_form","replaceability","management_party","fee_responsibility","reported_operator_status","expires_on","evidence_at","source_note","information_source","revision","recorded_at");
    public static ApiError unconfirmed(){return new ApiError(503,"SIM_CARD_READ_UNCONFIRMED","SIM手工台账数据未确认，请稍后重试");}
    public static ApiError missing(){return new ApiError(404,"SIM_CARD_NOT_FOUND","SIM手工台账不存在");}
    public static boolean uuid(String id){return id!=null&&id.length()==36&&id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    public static long safe(long value){if(value<0||value>SimCardInputs.MAX_SAFE)throw unconfirmed();return value;}
    public static long number(ResultSet rs,String column)throws SQLException{long value=rs.getLong(column);if(rs.wasNull())throw unconfirmed();return safe(value);}
    public static long total(ResultSet rs)throws SQLException{long value=rs.getLong(1);if(rs.wasNull())throw unconfirmed();return safe(value);}
    public static String instant(OffsetDateTime value){
        if(value==null)throw unconfirmed();
        try{OffsetDateTime utc=value.withOffsetSameInstant(ZoneOffset.UTC);if(utc.getYear()<1||utc.getYear()>9999||utc.getNano()%1000!=0)throw unconfirmed();return SimCardInputs.TIMESTAMP.format(utc.toInstant());}catch(DateTimeException e){throw unconfirmed();}
    }
    private static String date(LocalDate value){if(value==null)return null;try{return SimCardInputs.date(value.toString());}catch(RuntimeException e){throw unconfirmed();}}
    public static Card valid(Card value,String tenant){
        try{
            if(value==null||!uuid(value.id())||value.tenantId()==null||value.tenantId().isEmpty()||value.tenantId().length()>64||!value.tenantId().equals(tenant))throw unconfirmed();
            SimCardInputs.unicode(value.tenantId());
            if(!SimCardInputs.iccid(value.iccid()).equals(value.iccid())||!SimCardInputs.carrier(value.carrierName()).equals(value.carrierName())||!SimCardInputs.note(value.sourceNote()).equals(value.sourceNote())||!"REMOVABLE".equals(value.installationForm())||!"MANUAL".equals(value.informationSource()))throw unconfirmed();
            SimCardInputs.choice(value.replaceability(),SimCardInputs.REPLACEABILITY);SimCardInputs.choice(value.managementParty(),SimCardInputs.PARTY);SimCardInputs.choice(value.feeResponsibility(),SimCardInputs.PARTY);SimCardInputs.choice(value.reportedOperatorStatus(),SimCardInputs.REPORTED_STATUS);
            if(!Objects.equals(SimCardInputs.date(value.expiresOn()),value.expiresOn())||!Objects.equals(SimCardInputs.evidence(value.evidenceAt()),value.evidenceAt())||value.recordedAt()==null||!SimCardInputs.evidence(value.recordedAt()).equals(value.recordedAt()))throw unconfirmed();
            safe(value.revision());return value;
        }catch(RuntimeException e){throw unconfirmed();}
    }
    public static Card card(ResultSet rs,String tenant)throws SQLException{
        try{LocalDate expiry=rs.getObject("expires_on",LocalDate.class);OffsetDateTime evidence=rs.getObject("evidence_at",OffsetDateTime.class);
            return valid(new Card(rs.getString("id"),rs.getString("tenant_id"),rs.getString("iccid"),rs.getString("carrier_name"),rs.getString("installation_form"),rs.getString("replaceability"),rs.getString("management_party"),rs.getString("fee_responsibility"),rs.getString("reported_operator_status"),date(expiry),evidence==null?null:instant(evidence),rs.getString("source_note"),rs.getString("information_source"),number(rs,"revision"),instant(rs.getObject("recorded_at",OffsetDateTime.class))),tenant);
        }catch(SQLException|RuntimeException e){throw unconfirmed();}
    }
    public static History history(ResultSet rs,Card current)throws SQLException{
        try{Card snapshot=card(rs,current.tenantId());String change=rs.getString("change_id"),operation=rs.getString("operation");
            if(!uuid(change)||!snapshot.id().equals(current.id())||!snapshot.iccid().equals(current.iccid())||snapshot.revision()>current.revision()||!(snapshot.revision()==0?"CREATE":"EDIT").equals(operation))throw unconfirmed();
            return History.of(snapshot,change,operation);
        }catch(SQLException|RuntimeException e){throw unconfirmed();}
    }
    public static Card stored(JsonNode node){
        try{SimCardInputs.keys(node,KEYS);String tenant=SimCardInputs.string(node,"tenant_id");
            return valid(new Card(SimCardInputs.string(node,"id"),tenant,SimCardInputs.string(node,"iccid"),SimCardInputs.string(node,"carrier_name"),SimCardInputs.string(node,"installation_form"),SimCardInputs.string(node,"replaceability"),SimCardInputs.string(node,"management_party"),SimCardInputs.string(node,"fee_responsibility"),SimCardInputs.string(node,"reported_operator_status"),SimCardInputs.nullableString(node,"expires_on"),SimCardInputs.nullableString(node,"evidence_at"),SimCardInputs.string(node,"source_note"),SimCardInputs.string(node,"information_source"),SimCardInputs.integer(node.get("revision")),SimCardInputs.string(node,"recorded_at")),tenant);
        }catch(RuntimeException e){throw unconfirmed();}
    }
}
