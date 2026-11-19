package com.fireplatform.notification;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

record NotificationRuleTarget(String type, String id) {
    static NotificationRuleTarget requested(String tenant, String type, String id) {
        if (type == null && id == null) return new NotificationRuleTarget("UNIT", tenant);
        String kind = NotificationPolicy.choice(type, List.of("UNIT", "PLACE", "DEVICE"));
        String target = NotificationPolicy.id(id);
        if ("UNIT".equals(kind) && !tenant.equals(target)) throw ApiError.bad("单位规则必须指向当前单位");
        return new NotificationRuleTarget(kind, target);
    }

    static NotificationRuleTarget fromRule(String tenant, Map<String,Object> row) {
        String type = (String) row.get("scope_type");
        return new NotificationRuleTarget(type, switch (type) {
            case "PLACE" -> (String) row.get("scope_place_id");
            case "DEVICE" -> (String) row.get("scope_device_id");
            default -> tenant;
        });
    }

    boolean unit() { return "UNIT".equals(type); }
    String placeId() { return "PLACE".equals(type) ? id : null; }
    String deviceId() { return "DEVICE".equals(type) ? id : null; }

    void lockAuthorized(JdbcTemplate jdbc, DeviceAccessScope access, String actor, String tenant) {
        if (deviceId() != null) access.requireDevice(actor, tenant, id);
        else access.requireAll(actor, tenant);
        if (unit()) return;
        String table = deviceId() != null ? "devices" : "places";
        if (jdbc.queryForList("SELECT id FROM " + table + " WHERE tenant_id=? AND id=? AND archived_at IS NULL FOR UPDATE",
                String.class, tenant, id).isEmpty()) throw ApiError.missing();
    }

    void requireUnchanged(String tenant, Map<String,Object> current) {
        if (!equals(fromRule(tenant, current))) throw ApiError.bad("不能更改通知规则的目标作用域");
    }

    // Compatibility with saved unit-rule idempotency fingerprints; explicit and implicit UNIT are identical.
    void addToFingerprint(List<Object> fields) {
        if (!unit()) { fields.add(type); fields.add(id); }
    }
}
