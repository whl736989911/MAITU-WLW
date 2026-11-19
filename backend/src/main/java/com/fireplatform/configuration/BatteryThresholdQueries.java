package com.fireplatform.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatteryThresholdQueries {
    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final PageSizePolicy sizes;
    private final ObjectMapper mapper;

    public BatteryThresholdQueries(JdbcTemplate jdbc, DeviceAccessScope scopes, PageSizePolicy sizes, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.sizes = sizes;
        this.mapper = mapper;
    }

    public List<BatteryThresholdModels.Definition> definitions() {
        return List.of(new BatteryThresholdModels.Definition(BatteryThresholdModels.PARAMETER_KEY,
            BatteryThresholdModels.METRIC_KEY, "电量低阈值", BatteryThresholdModels.UNIT, 0, 100, 36,
            List.of("LT", "LTE"), List.of("PRODUCT_VERSION", "DEVICE"),
            BatteryThresholdModels.CONSUMER, "QUERY_ONLY"));
    }

    private record ViewRow(String deviceId, String tenantId, String overrideComparator, BigDecimal overrideThreshold,
                           long overrideRevision, String versionId, Long versionNumber, String capabilityJson,
                           String versionComparator, BigDecimal versionThreshold, long versionRevision,
                           OffsetDateTime evaluatedAt) { }

    private ViewRow row(ResultSet rs) throws SQLException {
        return new ViewRow(rs.getString("device_id"), rs.getString("tenant_id"),
            rs.getString("override_comparator"), rs.getBigDecimal("override_threshold"),
            rs.getLong("override_revision"), rs.getString("product_version_id"),
            rs.getObject("version_number", Long.class), rs.getString("capabilities_json"),
            rs.getString("version_battery_comparator"), rs.getBigDecimal("version_battery_threshold"),
            rs.getLong("version_battery_revision"), rs.getObject("evaluated_at", OffsetDateTime.class));
    }

    private String deviceSql() {
        return "SELECT d.id AS device_id,d.tenant_id,d.product_version_id, " +
            "o.comparator AS override_comparator,o.threshold AS override_threshold,o.revision AS override_revision, " +
            "v.version_number,v.capabilities_json,v.battery_low_comparator AS version_battery_comparator, " +
            "v.battery_low_threshold AS version_battery_threshold,v.revision AS version_battery_revision, " +
            "CURRENT_TIMESTAMP AS evaluated_at FROM devices d " +
            "LEFT JOIN device_battery_threshold_overrides o ON o.device_id=d.id " +
            "LEFT JOIN product_versions v ON v.id=d.product_version_id ";
    }

    private ViewRow visible(String actor, String tenant, String device) {
        var access = scopes.predicate(actor, "d.tenant_id", "d.id");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(access.bindings());
        args.add(device);
        var rows = jdbc.query(deviceSql() + "WHERE d.tenant_id=?" + access.and() + " AND d.id=?", (rs, n) -> row(rs), args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public BatteryThresholdModels.View view(String actor, String tenant, String device) {
        ViewRow row = visible(actor, tenant, device);
        var override = new BatteryThresholdModels.Override(row.overrideComparator() != null,
            row.overrideComparator(), row.overrideThreshold(), row.overrideRevision());
        BatteryThresholdModels.ProductVersion version = null;
        if (row.versionId() != null) {
            version = new BatteryThresholdModels.ProductVersion(row.versionId(), row.versionNumber(), capability(row.capabilityJson()));
        }
        return new BatteryThresholdModels.View(row.deviceId(), row.tenantId(), BatteryThresholdModels.PARAMETER_KEY,
            BatteryThresholdModels.METRIC_KEY, BatteryThresholdModels.UNIT, override,
            effective(row), version, BatteryThresholdModels.CONSUMER);
    }

    private String capability(String json) {
        if (json == null) return "PENDING";
        try {
            JsonNode node = mapper.readTree(json).path("battery");
            if (!node.isTextual()) return "PENDING";
            String value = node.textValue();
            return List.of("SUPPORTED", "UNSUPPORTED", "PENDING").contains(value) ? value : "PENDING";
        } catch (Exception e) {
            return "PENDING";
        }
    }

    private static BatteryThresholdModels.Effective effective(ViewRow row) {
        String comparator = row.overrideComparator();
        BigDecimal threshold = row.overrideThreshold();
        String source = "DEVICE";
        String sourceId = row.deviceId();
        long revision = row.overrideRevision();
        if (comparator == null || threshold == null) {
            comparator = row.versionComparator();
            threshold = row.versionThreshold();
            source = "PRODUCT_VERSION";
            sourceId = row.versionId();
            revision = row.versionRevision();
        }
        boolean configured = threshold != null;
        if (!configured) {
            comparator = null;
            source = "NONE";
            sourceId = null;
            revision = 0;
        }
        return new BatteryThresholdModels.Effective(BatteryThresholdModels.PARAMETER_KEY,
            BatteryThresholdModels.METRIC_KEY, BatteryThresholdModels.UNIT, configured, comparator, threshold,
            source, sourceId, revision, row.evaluatedAt());
    }

    public static BatteryThresholdModels.Effective effective(ResultSet rs, OffsetDateTime evaluatedAt) throws SQLException {
        String comparator = rs.getString("battery_override_comparator");
        BigDecimal threshold = rs.getBigDecimal("battery_override_threshold");
        String source = "DEVICE";
        String sourceId = rs.getString("id");
        long revision = rs.getLong("battery_override_revision");
        if (comparator == null || threshold == null) {
            comparator = rs.getString("version_battery_comparator");
            threshold = rs.getBigDecimal("version_battery_threshold");
            source = "PRODUCT_VERSION";
            sourceId = rs.getString("product_version_id");
            revision = rs.getLong("version_battery_revision");
        }
        boolean configured = threshold != null;
        if (!configured) {
            comparator = null;
            source = "NONE";
            sourceId = null;
            revision = 0;
        }
        return new BatteryThresholdModels.Effective(BatteryThresholdModels.PARAMETER_KEY,
            BatteryThresholdModels.METRIC_KEY, BatteryThresholdModels.UNIT, configured, comparator, threshold,
            source, sourceId, revision, evaluatedAt);
    }

    public static BatteryThresholdModels.Observation observation(ResultSet rs, OffsetDateTime evaluatedAt) throws SQLException {
        var configuration = effective(rs, evaluatedAt);
        String status = "NOT_CONFIGURED";
        if (configuration.configured()) {
            BigDecimal battery = rs.getBigDecimal("battery_percent");
            status = "NO_DATA";
            if (battery != null) {
                int comparison = battery.compareTo(configuration.threshold());
                boolean low = "LT".equals(configuration.comparator()) ? comparison < 0 : comparison <= 0;
                status = low ? "LOW" : "NORMAL";
            }
        }
        return new BatteryThresholdModels.Observation(configuration.parameterKey(), configuration.metricKey(),
            configuration.unit(), status, configuration.comparator(), configuration.threshold(),
            configuration.sourceScope(), configuration.sourceId(), configuration.sourceRevision(), evaluatedAt);
    }

    private BatteryThresholdModels.Change change(ResultSet rs) throws SQLException {
        String previousComparator=rs.getString("previous_comparator"); BigDecimal previousThreshold=rs.getBigDecimal("previous_threshold");
        String newComparator=rs.getString("new_comparator"); BigDecimal newThreshold=rs.getBigDecimal("new_threshold");
        var previous=previousThreshold==null ? null : new BatteryThresholdModels.ThresholdValue(previousComparator,previousThreshold);
        var value=newThreshold==null ? null : new BatteryThresholdModels.ThresholdValue(newComparator,newThreshold);
        return new BatteryThresholdModels.Change(rs.getString("id"),rs.getString("action"),previous,value,
            rs.getLong("revision_before"),rs.getLong("revision_after"),rs.getString("actor_id"),
            rs.getString("reason"),rs.getObject("created_at",OffsetDateTime.class));
    }

    public BatteryThresholdModels.Change change(String tenant, String device, String changeId) {
        var rows = jdbc.query("SELECT h.* FROM device_battery_threshold_changes h JOIN devices d ON d.id=h.device_id " +
            "WHERE d.tenant_id=? AND h.device_id=? AND h.id=?", (rs, n) -> change(rs), tenant, device, changeId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<BatteryThresholdModels.Change> history(String actor, String tenant, String device, int page, Integer size) {
        visible(actor, tenant, device);
        var p = Pagination.of(page, size, sizes.defaultPageSize());
        String from = " FROM device_battery_threshold_changes h JOIN devices d ON d.id=h.device_id " +
            "WHERE d.tenant_id=? AND h.device_id=?";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, tenant, device);
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.add(device);
        args.add(p.size());
        args.add(p.offset());
        var rows = jdbc.query("SELECT h.*" + from + " ORDER BY h.revision_after DESC,h.id LIMIT ? OFFSET ?",
            (rs, n) -> change(rs), args.toArray());
        return new Page<>(rows, total, p.page(), p.size());
    }
}
