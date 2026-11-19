package com.fireplatform.audit;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.LiteralLike;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Additional log-directory predicates never inspect private reasons, bodies or raw classifications. */
public record AuditLogSearch(String keyword, String username, String module, String platform) {
    public record Access(boolean customerNames, boolean platformNames) { }
    public record Metadata(String actorUsername, String module, String platform) { }
    public static final Set<String> MODULES = Set.of("IDENTITY", "DEVICE", "ALARM", "NOTIFICATION", "INGESTION", "SYSTEM", "UNCLASSIFIED");
    public static final Set<String> PLATFORMS = Set.of("WEB", "APP", "API", "SYSTEM", "UNKNOWN");
    private static final String MODULE_SQL = "CASE WHEN (" + AuditRecordCatalog.ACTION_SQL + ")='UNCLASSIFIED' THEN 'UNCLASSIFIED' "
        + "WHEN action IN ('LOGIN','CHANGE_PASSWORD','LOGOUT','CREATE_UNIT','UPDATE_UNIT','UPDATE_UNIT_DETAILS','CUSTOMER-CREATE','CUSTOMER-UPDATE','CUSTOMER-RESET','PLATFORM-CREATE','PLATFORM-UPDATE','PLATFORM-RESET','IDENTITY-DEPARTMENT-CREATE','IDENTITY-DEPARTMENT-UPDATE','IDENTITY-USER-METADATA','ROLE-CREATE','ROLE-UPDATE','ROLE-DELETE') THEN 'IDENTITY' "
        + "WHEN action IN ('device-register','device-edit','device-migrate','SIMULATE_FIRE','SIMULATE_RECOVERY','SIMULATION_DEDUPLICATED','DEVICE_PORTRAIT_RULE_CONFIGURE','DEVICE_PORTRAIT_EVALUATE') OR action LIKE 'DEVICE_CHANNEL_%' THEN 'DEVICE' "
        + "WHEN action IN ('REVIEW_ALARM','CHANNEL_UPGRADE','DUTY_FAULT_HANDLING','ALARM_BASE_CONFIG_UPDATED') OR action LIKE 'RESOURCE_LINKAGE_%' THEN 'ALARM' "
        + "WHEN action LIKE 'NOTIFICATION_%' OR action LIKE 'SUPPRESSION_%' THEN 'NOTIFICATION' "
        + "WHEN action IN ('CAPTURE','CAPTURE_DUPLICATE','MESSAGE_PREVIEW','MESSAGE_APPLY','MESSAGE_AUTO_APPLY') OR action LIKE 'source-%' OR action LIKE 'hikvision-%' OR action LIKE 'PROCESSING_%' THEN 'INGESTION' ELSE 'SYSTEM' END";
    private static final String PLATFORM_SQL = "CASE WHEN a.client_platform IN ('WEB','APP','API','SYSTEM') THEN a.client_platform ELSE 'UNKNOWN' END";
    public static AuditLogSearch empty() { return new AuditLogSearch(null, null, null, null); }
    private static String text(String value) {
        String result = LiteralLike.normalizedKeyword(value);
        return result.isEmpty() ? null : result;
    }
    private static String choice(String value, Set<String> allowed) {
        if (value == null || value.isEmpty()) return null;
        if (!allowed.contains(value)) throw ApiError.bad("日志分类筛选无效");
        return value;
    }
    public static AuditLogSearch parse(String keyword, String username, String module, String platform, Access access) {
        String user = text(username);
        if (user != null && !access.customerNames() && !access.platformNames()) throw ApiError.forbidden("按用户名查询日志还需要相应用户目录读取权限");
        return new AuditLogSearch(text(keyword), user, choice(module, MODULES), choice(platform, PLATFORMS));
    }
    private static String visibleActor(Access access) {
        return "SELECT log_user.username FROM app_users log_user WHERE log_user.id=a.actor_id AND ("
            + (access.platformNames() ? "log_user.user_type='PLATFORM'" : "1=0") + " OR "
            + (access.customerNames() ? "(log_user.user_type='CUSTOMER' AND log_user.tenant_id=a.tenant_id)" : "1=0") + ")";
    }
    public static String columns(Access access) {
        return ",(" + visibleActor(access) + ") AS log_actor_username," + MODULE_SQL + " AS log_module," + PLATFORM_SQL + " AS log_platform";
    }
    public AuditRecordFilter.Sql sql(AuditRecordFilter.Sql base, Access access) {
        StringBuilder where = new StringBuilder(base.where());
        var args = new ArrayList<>(base.bindings());
        if (keyword != null) {
            where.append(" AND (LOWER(a.id) LIKE LOWER(?) ESCAPE '!' OR LOWER(").append(AuditRecordCatalog.ACTION_SQL)
                .append(") LIKE LOWER(?) ESCAPE '!' OR EXISTS (SELECT 1 FROM tenants log_unit WHERE log_unit.id=a.tenant_id AND LOWER(log_unit.name) LIKE LOWER(?) ESCAPE '!'))");
            String like = LiteralLike.contains(keyword); args.add(like); args.add(like); args.add(like);
        }
        if (username != null) { where.append(" AND LOWER((").append(visibleActor(access)).append(")) LIKE LOWER(?) ESCAPE '!'"); args.add(LiteralLike.contains(username)); }
        if (module != null) { where.append(" AND (").append(MODULE_SQL).append(")=?"); args.add(module); }
        if (platform != null) { where.append(" AND (").append(PLATFORM_SQL).append(")=?"); args.add(platform); }
        return new AuditRecordFilter.Sql(where.toString(), List.copyOf(args));
    }
}
