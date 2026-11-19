package com.fireplatform.legacyingest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 接缝类型：接入线（ingest）产出，事实线（facts）消费。
 * 形状由 docs/rewrite-24h-spec.md §3 冻结，两条线都不得自行改造。
 */
public record LegacyNormalizedEvent(
    String tenantId,
    String deviceId,
    String vendorDeviceId,
    String platform,
    String kind,
    OffsetDateTime observedAt,
    BigDecimal battery,
    boolean pendingDecoder,
    List<LegacyEvent> events) {

    public record LegacyEvent(String kind, String method, String paramsJson, OffsetDateTime observedAt) { }
}
