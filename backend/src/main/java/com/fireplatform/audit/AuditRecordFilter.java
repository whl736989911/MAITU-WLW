package com.fireplatform.audit;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.ScopeFilter;
import java.time.*;
import java.util.*;

/** A single canonical plan for safe projected filters, the total and the page. */
public record AuditRecordFilter(String action,String outcome,String actorRef,String targetRef,OffsetDateTime occurredFrom,OffsetDateTime occurredTo) {
    public record Sql(String where,List<Object> bindings) { }
    private static String optional(String value,Set<String> choices){if(value==null||value.isEmpty())return null;if(!choices.contains(value))throw ApiError.bad("审计分类筛选无效");return value;}
    private static String reference(String value){if(value==null||value.isEmpty())return null;if(!AuditRecordProjection.uuid(value))throw ApiError.bad("引用须为完整小写UUID");return value;}
    private static OffsetDateTime time(String value){
        if(value==null||value.isEmpty())return null;
        try{var parsed=OffsetDateTime.parse(value);var utc=parsed.withOffsetSameInstant(ZoneOffset.UTC);if(parsed.getYear()<1||parsed.getYear()>9999||utc.getYear()<1||utc.getYear()>9999||parsed.getNano()%1000!=0)throw ApiError.bad("时间须为公元0001至9999年且可精确表示到微秒");return utc;}catch(DateTimeException e){throw ApiError.bad("时间须为带明确时区偏移的ISO8601时刻");}
    }
    public static AuditRecordFilter parse(String action,String outcome,String actor,String target,String from,String to){
        var start=time(from);var end=time(to);if(start!=null&&end!=null&&!start.isBefore(end))throw ApiError.bad("开始时间必须早于结束时间");
        return new AuditRecordFilter(optional(action,AuditRecordCatalog.ACTION_CODES),optional(outcome,AuditRecordCatalog.OUTCOME_CODES),reference(actor),reference(target),start,end);
    }
    public Sql sql(ScopeFilter.Clause scope){
        var where=new StringBuilder(" WHERE ").append(scope.sql());var args=new ArrayList<Object>(scope.bindings());
        if(action!=null){where.append(" AND ("+AuditRecordCatalog.ACTION_SQL+")=?");args.add(action);}
        if(outcome!=null){where.append(" AND ("+AuditRecordCatalog.OUTCOME_SQL+")=?");args.add(outcome);}
        if(actorRef!=null){where.append(" AND ("+AuditRecordProjection.ACTOR_SQL+")=?");args.add(actorRef);}
        if(targetRef!=null){where.append(" AND ("+AuditRecordProjection.TARGET_SQL+")=?");args.add(targetRef);}
        if(occurredFrom!=null){where.append(" AND occurred_at>=?");args.add(occurredFrom);}
        if(occurredTo!=null){where.append(" AND occurred_at<?");args.add(occurredTo);}
        return new Sql(where.toString(),List.copyOf(args));
    }
}
