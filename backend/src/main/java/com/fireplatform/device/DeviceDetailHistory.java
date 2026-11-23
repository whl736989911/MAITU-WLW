package com.fireplatform.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceDetailHistory {
    public record Connection(String id, String tenantId, String deviceId, String source, OffsetDateTime occurredAt,
                             String state, OffsetDateTime receivedAt, Long durationSeconds) { }
    public record Lifecycle(String id, String tenantId, String deviceId, String action, OffsetDateTime occurredAt,
                            String actor, String detail) { }
    private record Status(String id, String binding, String code, OffsetDateTime at, OffsetDateTime received) { }
    private static final Set<String> STATES = Set.of("ONLINE", "OFFLINE", "UNREGISTERED", "UNKNOWN");
    private final JdbcTemplate jdbc;
    private final DeviceQueryService devices;
    private final PageSizePolicy sizes;
    private final ObjectMapper json;

    public DeviceDetailHistory(JdbcTemplate jdbc, DeviceQueryService devices, PageSizePolicy sizes, ObjectMapper json) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.sizes = sizes;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Connection> connectivity(String actor, String tenant, String id, int page, Integer size,
                                         String state, OffsetDateTime from, OffsetDateTime to) {
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        var events = connections(actor, tenant, id, state, from, to);
        int start = (int) Math.min(events.size(), pagination.offset());
        return new Page<>(events.subList(start, Math.min(events.size(), start + pagination.size())), events.size(), pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String exportConnections(String actor, String tenant, String id, String state, OffsetDateTime from, OffsetDateTime to) {
        var events = connections(actor, tenant, id, state, from, to);
        if (events.size() > 10000) throw ApiError.bad("记录超过导出上限，请缩小日期范围");
        var csv = new StringBuilder("\uFEFF上报或判定时间,状态,持续时间（秒）,来源,平台接收时间\r\n");
        for (var row : events) {
            String label = switch (row.state()) {
                case "ONLINE" -> "在线";
                case "OFFLINE" -> "离线";
                case "UNREGISTERED" -> "未注册";
                default -> "未知";
            };
            csv.append(row.occurredAt()).append(',').append(label).append(',')
                .append(row.durationSeconds() == null ? "" : row.durationSeconds()).append(',').append(row.source()).append(',')
                .append(row.receivedAt() == null ? "" : row.receivedAt()).append("\r\n");
        }
        return csv.toString();
    }

    private ArrayList<Connection> connections(String actor, String tenant, String id, String state, OffsetDateTime from, OffsetDateTime to) {
        if (state != null && !STATES.contains(state)) throw ApiError.bad("在离线状态筛选无效");
        if (from != null && to != null && !from.isBefore(to)) throw ApiError.bad("结束时间必须晚于开始时间");
        devices.device(actor, tenant, id, false);
        var vendor = jdbc.query("""
            SELECT r.id,b.id AS binding,r.payload_json,r.observed_at,r.received_at
            FROM device_source_bindings b
            JOIN hikvision_raw_records r ON r.external_device_id=b.source_external_id AND r.stream_kind='STATUS'
            JOIN devices d ON d.id=b.device_id AND d.tenant_id=b.tenant_id
            WHERE b.tenant_id=? AND b.device_id=? AND b.driver_key='HIKVISION_FIRECLOUD'
                AND r.observed_at>=b.bound_at AND (d.ownership_from IS NULL OR r.observed_at>=d.ownership_from)
                AND (b.released_at IS NULL OR (r.observed_at<b.released_at AND r.received_at<b.released_at))
                AND r.received_at>=b.bound_at
                AND (r.applied_device_id IS NULL OR r.applied_device_id=b.device_id)
            ORDER BY b.id,r.observed_at,r.received_at,r.id
            """, (r, n) -> new Status(r.getString("id"), r.getString("binding"), stateCode(r.getString("payload_json")),
            r.getObject("observed_at", OffsetDateTime.class), r.getObject("received_at", OffsetDateTime.class)), tenant, id);
        var events = new ArrayList<Connection>();
        Status previous = null;
        for (int i = 0; i < vendor.size(); i++) {
            Status next = vendor.get(i);
            // Conflicting assertions for the same source time must not become two apparent transitions.
            for (int j = i + 1; j < vendor.size() && next.binding().equals(vendor.get(j).binding()) && next.at().isEqual(vendor.get(j).at()); j++) {
                if (!next.code().equals(vendor.get(j).code())) next = new Status(next.id(), next.binding(), "UNKNOWN", next.at(), next.received());
                i = j;
            }
            if (previous != null && previous.binding().equals(next.binding())) {
                if (previous.code().equals(next.code())) continue;
                add(events, previous, next, tenant, id);
            } else if (previous != null) add(events, previous, null, tenant, id);
            previous = next;
        }
        if (previous != null) add(events, previous, null, tenant, id);
        events.addAll(jdbc.query("""
            SELECT e.id,e.method,e.observed_at,m.received_at
            FROM legacy_ingest_events e LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id
            WHERE e.tenant_id=? AND e.device_id=? AND e.kind='OFFLINE' AND e.observed_at IS NOT NULL
            """, (r, n) -> new Connection(r.getString("id"), tenant, id,
                "offline.scan".equals(r.getString("method")) ? "PLATFORM_SCAN" : "SOURCE_REPORT",
                r.getObject("observed_at", OffsetDateTime.class), "OFFLINE", r.getObject("received_at", OffsetDateTime.class), null), tenant, id));
        events.removeIf(event -> (state != null && !state.equals(event.state())) || (from != null && event.occurredAt().isBefore(from))
            || (to != null && !event.occurredAt().isBefore(to)));
        events.sort(Comparator.comparing((Connection event) -> event.occurredAt().toInstant()).reversed().thenComparing(Connection::id));
        return events;
    }

    private String stateCode(String payload) {
        try {
            return switch (json.readTree(payload).path("deviceStatus").asText("")) {
                case "0" -> "OFFLINE";
                case "1" -> "ONLINE";
                case "2" -> "UNREGISTERED";
                default -> "UNKNOWN";
            };
        } catch (JsonProcessingException error) { return "UNKNOWN"; }
    }

    private static void add(ArrayList<Connection> events, Status row, Status next, String tenant, String id) {
        Long seconds = next != null && !"UNKNOWN".equals(row.code()) && !"UNKNOWN".equals(next.code())
            ? Duration.between(row.at(), next.at()).getSeconds() : null;
        events.add(new Connection(row.id(), tenant, id, "HIKVISION", row.at(), row.code(), row.received(), seconds));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Lifecycle> lifecycle(String actor, String tenant, String id, int page, Integer size, boolean management) {
        devices.device(actor, tenant, id, false);
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        String rows = """
            SELECT h.id,h.action,h.occurred_at,h.actor_id,h.to_place_name AS detail FROM device_place_history h
                WHERE h.tenant_id=? AND h.device_id=?
            UNION ALL SELECT b.id,'BIND',b.bound_at,b.bound_by,b.driver_key FROM device_source_bindings b
                WHERE b.tenant_id=? AND b.device_id=?
            UNION ALL SELECT b.id,'UNBIND',b.released_at,b.released_by,b.driver_key FROM device_source_bindings b
                WHERE b.tenant_id=? AND b.device_id=? AND b.released_at IS NOT NULL
            UNION ALL SELECT v.id,'VERSION',v.occurred_at,v.actor_id,v.to_version_id FROM device_version_history v
                WHERE v.tenant_id=? AND v.device_id=? AND v.from_version_id IS NOT NULL
            UNION ALL SELECT t.id,'TRANSFER_IN',t.occurred_at,t.actor_id,NULL FROM device_transfers t
                WHERE t.target_tenant_id=? AND t.target_device_id=?
            UNION ALL SELECT t.id,'TRANSFER_OUT',t.occurred_at,t.actor_id,NULL FROM device_transfers t
                WHERE t.source_tenant_id=? AND t.source_device_id=?
            UNION ALL SELECT d.id,'ARCHIVE',d.archived_at,d.archived_by,NULL FROM devices d
                WHERE d.tenant_id=? AND d.id=? AND d.archived_at IS NOT NULL
            """;
        var args = new ArrayList<Object>();
        for (int i = 0; i < 7; i++) { args.add(tenant); args.add(id); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + rows + ") l", Long.class, args.toArray());
        args.add(pagination.size());
        args.add(pagination.offset());
        String actorLabel = management ? "u.display_name" : "CASE u.user_type WHEN 'PLATFORM' THEN '平台人员' WHEN 'CUSTOMER' THEN '单位人员' END";
        var items = jdbc.query("SELECT l.*," + actorLabel + " AS actor FROM (" + rows + ") l LEFT JOIN app_users u ON u.id=l.actor_id"
                + " ORDER BY l.occurred_at DESC,l.id,l.action LIMIT ? OFFSET ?",
            (r, n) -> new Lifecycle(r.getString("action") + ':' + r.getString("id"), tenant, id, r.getString("action"),
                r.getObject("occurred_at", OffsetDateTime.class), r.getString("actor"), r.getString("detail")), args.toArray());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }
}
