package com.fireplatform.hikvision;

import java.util.Map;

/** Official FireCloud device-event dictionary (document 65); unknown and conflicting codes stay unmapped. */
public final class HikvisionEventCatalog {
    // 100208 has manual-reset and sensor-button-reset descriptions; retain only their common meaning.
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("100202","远程消音"),Map.entry("100203","遥控消音"),Map.entry("100204","手动消音"),
        Map.entry("100205","远程复位"),Map.entry("100206","遥控复位"),Map.entry("100207","自动复位"),
        Map.entry("100208","复位"),Map.entry("100209","开机"),Map.entry("100211","自检"),Map.entry("100224","静音"),
        Map.entry("100225","复位"),Map.entry("100226","启用"),Map.entry("100227","禁用"),
        Map.entry("100231","设备重启"),Map.entry("100263","远程自检"),Map.entry("100266","本地重启"),
        Map.entry("100258","本地恢复出厂设置"),Map.entry("100236","4G网络断开"),Map.entry("100237","4G网络断开恢复"));

    private HikvisionEventCatalog() { }

    public static String label(String sourceCode) { return LABELS.get(sourceCode); }
}
