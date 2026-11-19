package com.fireplatform.alarm;

import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;

/** Canonical applied conditions and the one fixed SQL plan shared by aggregate and page. */
public record AlarmWorkbenchFilter(String keyword,String physicalState,String responseState,String conclusion,OffsetDateTime occurredFrom,OffsetDateTime occurredTo) {
    record Sql(String where,List<Object> bindings) { }
    public static AlarmWorkbenchFilter parse(String keyword,String physical,String response,String conclusion,String from,String to) {
        var start=time(from); var end=time(to); if(start!=null && end!=null && !start.isBefore(end)) throw ApiError.bad("开始时间必须早于结束时间");
        return new AlarmWorkbenchFilter(LiteralLike.normalizedKeyword(keyword),enumeration(physical,Set.of("FIRE","NORMAL")),enumeration(response,Set.of("UNHANDLED","HANDLED")),enumeration(conclusion,Set.of("TRUE_FIRE","FALSE_ALARM","TEST","DEVICE_ALARM","UNREVIEWED")),start,end);
    }
    private static String enumeration(String value,Set<String> allowed) { if(value==null || value.isEmpty()) return null; if(!allowed.contains(value)) throw ApiError.bad("筛选状态或结论无效"); return value; }
    private static OffsetDateTime time(String value) {
        if(value==null || value.isEmpty()) return null;
        try { var parsed=OffsetDateTime.parse(value); var utc=parsed.withOffsetSameInstant(ZoneOffset.UTC); if(parsed.getYear()<1 || parsed.getYear()>9999 || utc.getYear()<1 || utc.getYear()>9999 || parsed.getNano()%1000!=0) throw ApiError.bad("时间须为公元0001至9999年且不超过微秒精度"); return utc; }
        catch(DateTimeException e) { throw ApiError.bad("时间须为带明确时区偏移的ISO8601时刻"); }
    }
    Sql sql(String tenant,String device,DeviceAccessScope.Filter scope) {
        return sql(tenant,device,scope,null,List.of());
    }
    Sql sql(String tenant,String device,DeviceAccessScope.Filter scope,String extraSql,List<Object> extraArgs) {
        var where=new StringBuilder(tenant==null?" WHERE 1=1":" WHERE tenant_id=?"); var args=new ArrayList<Object>(); if(tenant!=null)args.add(tenant);args.addAll(scope.bindings()); where.append(scope.and());
        where.append(" AND is_simulated=FALSE");
        if(device!=null){where.append(" AND device_id=?");args.add(device);}
        if(!keyword.isEmpty()) { String like=LiteralLike.contains(keyword); where.append(" AND (LOWER(id) LIKE LOWER(?) ESCAPE '!' OR LOWER(device_name) LIKE LOWER(?) ESCAPE '!' OR LOWER(address) LIKE LOWER(?) ESCAPE '!')"); args.add(like); args.add(like); args.add(like); }
        if(physicalState!=null) { where.append(" AND physical_state=?"); args.add(physicalState); }
        if(responseState!=null) { where.append(" AND response_state=?"); args.add(responseState); }
        if(conclusion!=null) { if(conclusion.equals("UNREVIEWED")) where.append(" AND conclusion IS NULL"); else { where.append(" AND conclusion=?"); args.add(conclusion); } }
        if(occurredFrom!=null||occurredTo!=null)where.append(" AND source_time_known IS NOT FALSE");
        if(occurredFrom!=null) { where.append(" AND occurred_at>=?"); args.add(occurredFrom); }
        if(occurredTo!=null) { where.append(" AND occurred_at<?"); args.add(occurredTo); }
        if(extraSql!=null && !extraSql.isBlank()) { where.append(" AND ").append(extraSql); args.addAll(extraArgs); }
        return new Sql(where.toString(),List.copyOf(args));
    }
}
