package com.fireplatform.hikvision;

import java.util.List;
import java.util.Map;

/** Exact documented v2 envelope pairs; software support is separate from operator opt-in. */
public final class HikvisionPushTypes {
    private record Type(String dataType,HikvisionClient.Stream stream) { }
    private static final Map<String,Type> TYPES=Map.of(
        "980002",new Type("980002101",HikvisionClient.Stream.ALARM),
        "980004",new Type("980004101",HikvisionClient.Stream.FAULT),
        "980008",new Type("980008101",HikvisionClient.Stream.MONITOR),
        "980016",new Type("980016101",HikvisionClient.Stream.DEVICE_EVENT));
    public static final List<String> SUPPORTED=List.of("980002","980004","980008","980016");
    public static final List<String> INITIAL=List.of("980016");
    private HikvisionPushTypes() { }
    public static HikvisionClient.Stream stream(String messageType,String dataType) {
        Type type=TYPES.get(messageType);
        return type!=null&&type.dataType().equals(dataType)?type.stream():null;
    }
}
