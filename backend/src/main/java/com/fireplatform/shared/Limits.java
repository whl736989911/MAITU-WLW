package com.fireplatform.shared;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Central policy: only the explicitly listed business settings are user-editable. */
public final class Limits {
    private Limits() { }
    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 128;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 1_000_000;
    public static final int MAX_KEYWORD_LENGTH = 100;
    public static final int MAX_IDEMPOTENCY_LENGTH = 128;
    public static final int MAX_CHANGE_REASON_LENGTH = 1000;
    public static final int MAX_ALARM_REMARK_LENGTH = 2000;
    public static final int DEVICE_GROUP_NAME_MAX=200, DEVICE_GROUP_DESCRIPTION_MAX=1000;
    public static final int SIM_CARD_ICCID_MAX=64, SIM_CARD_CARRIER_MAX=200, SIM_CARD_SOURCE_NOTE_MAX=1000;
    public static final int NOTIFICATION_NAME_MAX=200, NOTIFICATION_PHONE_MAX=32, NOTIFICATION_BODY_MAX=2000, NOTIFICATION_REASON_MAX=1000;
    public static final int NOTIFICATION_MAX_RECEIVERS=50, NOTIFICATION_MAX_LEVEL=5, NOTIFICATION_MAX_ATTEMPTS=20;
    public static final int NOTIFICATION_RETRY_MAX_SECONDS=86400, NOTIFICATION_ESCALATION_MAX_SECONDS=604800;
    public static final int MAX_PERMISSION_ENTRIES = 128;
    public static final int MAX_ROLE_ASSIGNMENTS = 64;
    public static final int MAX_SCOPE_DEVICES = 1000;
    public static final int MAX_SCOPE_PLACES = 200;
    public static final int MAX_DELEGATED_GRANTS = 1000;
    public static final int JSON_MAX_DEPTH = 32, JSON_MAX_STRING = 131072, JSON_MAX_NUMBER_LENGTH = 64, MAX_JSON_BODY_BYTES = 2 * 1024 * 1024;
    public static final Duration SESSION_TTL = Duration.ofHours(8);
    public static final List<String> CUSTOMER_PERMISSIONS = List.of("summary:read", "device:read", "alarm:read", "alarm:review", "inbox:read", "inbox:handle", "notification-recharge:read", "notification-recharge:write");
    public static final List<String> PLATFORM_PERMISSIONS = List.of(
        "summary:read","device:read","alarm:read","alarm:review","tenants:read","settings:read","settings:write",
        "units:read","units:write","users:read","users:write","users:reset-password","platform-users:read","platform-users:write","platform-users:reset-password",
        "products:read","products:write","products:publish","device:write","device:migrate","faults:handle","sources:read","sources:write","sources:bind","sources:credentials","integrations:read","integrations:write",
        "messages:read","messages:raw","messages:preview","messages:apply","messages:reparse","suppressions:read","suppressions:write","profiles:read","profiles:write",
        "imports:read","imports:write","exports:read","exports:write","inbox:read","inbox:handle","inbox:publish","retention:read","retention:write","tasks:read","parameters:read","parameters:write","alarm:history",
        "notification-config:read","notification-config:write","notification-resources:read","notification-resources:write","notifications:read","notification-recharge:read","notification-recharge:write",
        "unit-parameters:read","unit-parameters:write","unit-notifications:read","unit-notifications:write",
        "audit:read","sim-cards:read","sim-cards:write","sim-cards:bind","sim-embedded:read","sim-embedded:write","sim-inventory:read","sim-inventory:write","sim-expiry:read","sim-expiry:handle","sim-expiry:write",
        "roles:read","roles:write","users:assign-roles","users:assign-scope","platform-users:assign-roles","platform-users:assign-scope","legacy-ingest:read","legacy-ingest:write","alarm-base:read","alarm-base:write","resource-linkage:read","resource-linkage:write",
        "device-services:read","device-services:query","device-services:execute","device-services:engineering",
        "device-registrations:read-global","device-registrations:write-global",
        "device-registrations:write",
        "platform-ops:read",
        "applications:read","applications:write","applications:forward-raw",
        "topology:read","topology:write","video:read","video:write","video:play","controller:read","controller:write",
        "rules:read","rules:write","rules:publish",
        "maintenance:read","maintenance:write","maintenance:assign","maintenance:review","fire-archives:read","fire-archives:write",
        "payments:read","payments:write","notification-delivery:reconcile","ownership-exceptions:read");
    public record SettingDefinition(String key, long initial, String unit, long min, long max, String description) { }
    public static final Map<String, SettingDefinition> SETTINGS = Map.of(
        "offline_threshold_minutes", new SettingDefinition("offline_threshold_minutes", 2880, "分钟", 1, 525600,
            "设备列表/summary连接状态及离线事件扫描的离线截止时间使用此阈值；无已接收心跳证据时不推断离线。默认适配24小时心跳；此值不配置扫描周期、通知间隔或厂商上报周期。"),
        "default_page_size", new SettingDefinition("default_page_size", 20, "条", 5, MAX_PAGE_SIZE,
            "设备及报警列表未指定size时的默认条数。单次请求硬上限由服务端策略控制。")
    );
    public static boolean validPassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH && password.length() <= MAX_PASSWORD_LENGTH
            && !password.isBlank();
    }
}
