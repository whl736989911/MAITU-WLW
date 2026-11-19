package com.fireplatform.device;

import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Validated applied conditions; one immutable bound plan feeds both aggregate and page. */
public record DeviceWorkbenchFilter(String keyword,String connectivity,String physicalState,String integrationState,String systemType,String deviceCategory) {
    record Sql(String fromWhere,List<Object> bindings) { }
    public static DeviceWorkbenchFilter parse(String keyword,String connectivity,String physical,String integration,String systemType,String deviceCategory) {
        return new DeviceWorkbenchFilter(LiteralLike.normalizedKeyword(keyword),enumeration(connectivity,Set.of("ONLINE","OFFLINE","UNKNOWN","UNACTIVATED")),enumeration(physical,Set.of("FIRE","NORMAL","UNKNOWN")),enumeration(integration,Set.of("CONNECTED","PENDING_INTEGRATION","LEGACY_UNVERIFIED","RETIRED_SIMULATION")),metadata(systemType),metadata(deviceCategory));
    }
    private static String enumeration(String value,Set<String> allowed){if(value==null||value.isEmpty())return null;if(!allowed.contains(value))throw ApiError.bad("设备筛选状态无效");return value;}
    private static String metadata(String value){if(value==null||value.isEmpty())return null;var normalized=value.trim();if(normalized.isEmpty()||normalized.length()>80||normalized.chars().anyMatch(Character::isISOControl))throw ApiError.bad("设备系统或类别筛选值无效");return normalized;}
    Sql plan(String projection,String tenant,OffsetDateTime asOf,DeviceAccessScope.Filter scope){
        var conditions=new StringJoiner(" AND "," WHERE ","");conditions.add("1=1");var args=new ArrayList<Object>();args.add(asOf);args.add(asOf);if(tenant!=null)args.add(tenant);args.addAll(scope.bindings());
        if(!keyword.isEmpty()){String like=LiteralLike.contains(keyword);conditions.add("(LOWER(q.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(q.device_eui) LIKE LOWER(?) ESCAPE '!' OR LOWER(q.address) LIKE LOWER(?) ESCAPE '!')");args.add(like);args.add(like);args.add(like);}
        if(connectivity!=null){conditions.add("q.computed_connectivity=?");args.add(connectivity);}
        if(physicalState!=null){conditions.add("q.physical_state=?");args.add(physicalState);}
        if(integrationState!=null){conditions.add("q.integration_state=?");args.add(integrationState);}
        if(systemType!=null){conditions.add("q.system_type=?");args.add(systemType);}
        if(deviceCategory!=null){conditions.add("q.device_category=?");args.add(deviceCategory);}
        return new Sql(" FROM ("+projection+(tenant==null?" WHERE d.is_simulated=FALSE AND d.archived_at IS NULL":" WHERE d.tenant_id=? AND d.is_simulated=FALSE AND d.archived_at IS NULL")+scope.and()+") q"+conditions,List.copyOf(args));
    }
}
