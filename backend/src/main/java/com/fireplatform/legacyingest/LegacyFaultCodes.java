package com.fireplatform.legacyingest;

import java.util.Map;

/** Code labels shared by legacy protocol normalization and read projections. */
public final class LegacyFaultCodes {
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("1002001","烟感故障报警"), Map.entry("1002002","低电压故障"),
        Map.entry("1002003","防拆报警"), Map.entry("1002005","电路故障"),
        Map.entry("1002006","无线底座防拆"), Map.entry("1002007","烟感与底座失联"),
        Map.entry("1002008","温度传感器故障"), Map.entry("1002009","底座低电压"),
        Map.entry("1002010","底座其他故障"), Map.entry("1002016","传感器故障"),
        Map.entry("1002027","烟感低电量"), Map.entry("1002035","主电故障"),
        Map.entry("1002036","备电故障"), Map.entry("1002044","烟感故障报警"),
        Map.entry("1002045","烟感低电量"), Map.entry("1002070","欠压故障"),
        Map.entry("1002072","温度报警"), Map.entry("1002074","通信故障"),
        Map.entry("1002077","转接板故障"), Map.entry("1002078","4G故障"),
        Map.entry("1002079","以太网故障"), Map.entry("1002080","射频故障"),
        Map.entry("1002082","欠压故障"), Map.entry("1001027","欠压故障")
    );

    private LegacyFaultCodes() { }

    public static String label(String code) {
        return code == null ? "故障类型未知" : LABELS.getOrDefault(code, "未知故障类型");
    }
}
