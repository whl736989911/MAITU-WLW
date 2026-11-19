package com.fireplatform.device;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
/** Pure threshold semantics shared by persisted portrait evaluation and boundary regression tests. */
final class PortraitThresholds {
    static boolean lowBattery(BigDecimal observed,BigDecimal threshold) {
        return observed!=null&&threshold!=null&&observed.compareTo(threshold)<=0;
    }
    static boolean weakSignal(BigDecimal observed,BigDecimal threshold) {
        return observed!=null&&threshold!=null&&observed.compareTo(threshold)<=0;
    }
    static BigDecimal weakPercent(int weak,int samples) {
        return samples<=0?null:BigDecimal.valueOf(weak).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(samples),36,java.math.RoundingMode.HALF_UP);
    }
    static boolean weakPercentAtLeast(int weak,int samples,BigDecimal threshold) {
        return samples>0&&threshold!=null&&BigDecimal.valueOf(weak).multiply(BigDecimal.valueOf(100)).compareTo(threshold.multiply(BigDecimal.valueOf(samples)))>=0;
    }
    record Count(String status,boolean matched) { }
    private PortraitThresholds() { }
    static Count count(int observed,boolean supported,int threshold) {
        return supported ? new Count("OK",observed>=threshold) : new Count("MISSING",false);
    }
    static boolean longOffline(OffsetDateTime lastMessageAt,OffsetDateTime asOf,int days) {
        return lastMessageAt!=null&&!lastMessageAt.isAfter(asOf.minusDays(days));
    }
}
