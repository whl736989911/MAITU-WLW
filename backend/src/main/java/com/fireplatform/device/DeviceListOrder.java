package com.fireplatform.device;

import com.fireplatform.shared.ApiError;

public final class DeviceListOrder {
    static final DeviceListOrder BY_ID = new DeviceListOrder("q.id", "ASC");

    // These are the projected badges, not the raw physical or connectivity fields.
    // A business status sorts by the first badge shown in DeviceStatusComposer.
    private static final String BUSINESS = "CASE WHEN q.pending_alarm THEN 1 WHEN q.notification_shielded THEN 2 WHEN q.pending_faults IS NOT NULL THEN 3 END";
    private static final String CUSTOMER = "CASE WHEN q.report_status='OFFLINE' THEN 4 ELSE COALESCE(" + BUSINESS
        + ", CASE q.report_status WHEN 'ONLINE' THEN 5 WHEN 'UNACTIVATED' THEN 6 END) END";

    private final String expression;
    private final String direction;

    private DeviceListOrder(String expression, String direction) {
        this.expression = expression;
        this.direction = direction;
    }

    static DeviceListOrder parse(String field, String direction) {
        if (field == null && direction == null) return BY_ID;
        if (field == null) throw ApiError.bad("设备排序列无效");
        String expression = switch (field) {
            case "name" -> text("q.name");
            case "eui" -> text("q.device_eui");
            case "model" -> text("q.display_model");
            case "product" -> text("q.product_name");
            case "version" -> "CASE WHEN q.product_version_id IS NOT NULL THEN q.version_number END";
            case "unit" -> text("COALESCE(q.tenant_name,q.tenant_id)");
            case "address" -> text("q.address");
            case "placement" -> text("COALESCE(q.place_name,q.place_id)");
            case "system" -> text("q.system_type");
            case "category" -> text("q.device_category");
            case "customer" -> CUSTOMER;
            case "online" -> "CASE q.report_status WHEN 'OFFLINE' THEN 1 WHEN 'ONLINE' THEN 2 WHEN 'UNACTIVATED' THEN 3 END";
            case "business" -> BUSINESS;
            case "battery" -> "q.battery_percent";
            case "last" -> "q.last_message_at";
            default -> throw ApiError.bad("设备排序列无效");
        };
        String sqlDirection = direction == null ? ("last".equals(field) ? "DESC" : "ASC") : switch (direction) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw ApiError.bad("设备排序方向无效");
        };
        return new DeviceListOrder(expression, sqlDirection);
    }

    private static String text(String column) { return "LOWER(NULLIF(TRIM(" + column + "),''))"; }

    String sql() {
        return this == BY_ID ? " ORDER BY q.id" : " ORDER BY " + expression + " " + direction + " NULLS LAST, q.id";
    }
}
