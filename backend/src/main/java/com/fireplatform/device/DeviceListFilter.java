package com.fireplatform.device;

import com.fireplatform.shared.ApiError;
import com.fireplatform.legacyingest.facts.DeviceStatusComposer;
import java.util.*;

/** Exact source-status and nullable manually declared category filters for the device ledger. */
public record DeviceListFilter(String connectivity,String physicalState,String integrationState,String systemType,String deviceCategory,List<String> customerStatuses) {
    public static DeviceListFilter parse(String connectivity,String physicalState,String integrationState,String systemType,String deviceCategory){
        return parse(connectivity,physicalState,integrationState,systemType,deviceCategory,null);
    }
    public static DeviceListFilter parse(String connectivity,String physicalState,String integrationState,String systemType,String deviceCategory,String customerStatuses){
        return new DeviceListFilter(one(connectivity,Set.of("ONLINE","OFFLINE","UNKNOWN","UNACTIVATED")),one(physicalState,Set.of("FIRE","NORMAL","UNKNOWN")),one(integrationState,Set.of("CONNECTED","PENDING_INTEGRATION","LEGACY_UNVERIFIED","RETIRED_SIMULATION")),metadata(systemType),metadata(deviceCategory),DeviceStatusComposer.parseCustomerStatuses(customerStatuses));
    }
    private static String one(String value,Set<String> values){if(value==null||value.isEmpty())return null;if(!values.contains(value))throw ApiError.bad("设备状态筛选无效");return value;}
    private static String metadata(String value){if(value==null||value.isEmpty())return null;var normalized=value.trim();if(normalized.isEmpty()||normalized.length()>80||normalized.chars().anyMatch(Character::isISOControl))throw ApiError.bad("设备系统或类别筛选值无效");return normalized;}
}
