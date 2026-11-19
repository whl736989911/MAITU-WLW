package com.fireplatform.audit;

import com.fireplatform.shared.ApiError;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** SQL masks unknown action/outcome text and unsafe references before JDBC mapping. */
public final class AuditRecordProjection {
    private AuditRecordProjection() { }
    public static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private static final String HEX_UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern UUID_PATTERN=Pattern.compile(HEX_UUID);
    private static String validReference(String column){return "CHAR_LENGTH("+column+")=36 AND REGEXP_LIKE("+column+",'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')";}
    private static String reference(String column){return "CASE WHEN "+validReference(column)+" THEN LOWER("+column+") ELSE NULL END";}
    private static String state(String column){return "CASE WHEN "+column+" IS NULL THEN 'NOT_RECORDED' WHEN "+validReference(column)+" THEN 'UUID' ELSE 'WITHHELD' END";}
    public static final String ACTOR_SQL=reference("actor_id"),TARGET_SQL=reference("target_id");
    public static final String COLUMNS="a.id,a.tenant_id,t.name AS tenant_name,a.occurred_at,"+AuditRecordCatalog.ACTION_SQL+" AS action,"+AuditRecordCatalog.OUTCOME_SQL+" AS outcome,"+ACTOR_SQL+" AS actor_ref,"+state("actor_id")+" AS actor_reference_state,"+TARGET_SQL+" AS target_ref,"+state("target_id")+" AS target_reference_state";
    public record Row(String id,String tenantId,String tenantName,OffsetDateTime occurredAt,String action,String outcome,String actorRef,String actorReferenceState,String targetRef,String targetReferenceState) { }
    public static ApiError unconfirmed(){return new ApiError(503,"AUDIT_READ_UNCONFIRMED","审计记录未确认，请稍后重试");}
    public static boolean uuid(String value){return value!=null&&value.length()==36&&UUID_PATTERN.matcher(value).matches();}
    public static OffsetDateTime instant(OffsetDateTime value){
        if(value==null)throw unconfirmed();
        try{var utc=value.withOffsetSameInstant(ZoneOffset.UTC);if(value.getYear()<1||value.getYear()>9999||utc.getYear()<1||utc.getYear()>9999||value.getNano()%1000!=0)throw unconfirmed();return utc;}catch(DateTimeException e){throw unconfirmed();}
    }
    public static long total(ResultSet rs)throws SQLException{long value=rs.getLong(1);if(rs.wasNull()||value<0||value>MAX_SAFE_INTEGER)throw unconfirmed();return value;}
    private static void referenceShape(String ref,String state){
        if("UUID".equals(state)){if(!uuid(ref))throw unconfirmed();}
        else if(!Set.of("NOT_RECORDED","WITHHELD").contains(state)||ref!=null)throw unconfirmed();
    }
    public static Row row(ResultSet rs)throws SQLException{
        try{
            String id=rs.getString("id"),tenantId=rs.getString("tenant_id"),tenantName=rs.getString("tenant_name"),action=rs.getString("action"),outcome=rs.getString("outcome"),actor=rs.getString("actor_ref"),actorState=rs.getString("actor_reference_state"),target=rs.getString("target_ref"),targetState=rs.getString("target_reference_state");
            if(!uuid(id)||tenantId==null||tenantName==null||!AuditRecordCatalog.ACTION_CODES.contains(action)||!AuditRecordCatalog.OUTCOME_CODES.contains(outcome))throw unconfirmed();
            referenceShape(actor,actorState);referenceShape(target,targetState);
            return new Row(id,tenantId,tenantName,instant(rs.getObject("occurred_at",OffsetDateTime.class)),action,outcome,actor,actorState,target,targetState);
        }catch(SQLException|RuntimeException e){throw unconfirmed();}
    }
}
