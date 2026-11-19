package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Immutable history of vendor source bindings. Releasing a binding never deletes raw source history;
 * it records a generation, the actor and the unresolved-risk snapshot at release time.
 */
@Service
public class DeviceSourceBindingService {
    public record Binding(String id, String tenantId, String deviceId, String driverKey, String externalId,
                          String serial, String accountFingerprint, long generation, String state, JsonNode pendingRisk,
                          OffsetDateTime boundAt, String boundBy, OffsetDateTime releasedAt, String releasedBy,
                          String releaseReason) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public DeviceSourceBindingService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public Optional<Binding> activeForDevice(String tenantId, String deviceId) {
        var rows = jdbc.query("SELECT * FROM device_source_bindings WHERE tenant_id=? AND device_id=? AND state='ACTIVE' ORDER BY generation DESC",
            (rs, n) -> row(rs), tenantId, deviceId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Binding> historyForDevice(String tenantId, String deviceId) {
        return jdbc.query("SELECT * FROM device_source_bindings WHERE tenant_id=? AND device_id=? ORDER BY generation DESC, bound_at DESC",
            (rs, n) -> row(rs), tenantId, deviceId);
    }

    public Optional<Binding> activeBySource(String driverKey, String externalId) {
        var rows = jdbc.query("SELECT * FROM device_source_bindings WHERE driver_key=? AND source_external_id=? AND state='ACTIVE' ORDER BY generation DESC",
            (rs, n) -> row(rs), driverKey, externalId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public long nextGeneration(String driverKey, String externalId) {
        Long current = jdbc.queryForObject("SELECT COALESCE(MAX(generation),0) FROM device_source_bindings WHERE driver_key=? AND source_external_id=?",
            Long.class, driverKey, externalId);
        return (current == null ? 0 : current) + 1;
    }

    public long pendingRawRecords(String externalId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE external_device_id=? AND applied_at IS NULL AND (apply_error IS NULL OR apply_error NOT IN ('PRE_OWNERSHIP_HISTORY','OWNERSHIP_TIME_UNKNOWN'))",
            Long.class, externalId);
        return count == null ? 0 : count;
    }

    /** Must run inside the caller's write transaction; verifies both active slots before inserting. */
    public Binding bind(String tenantId, String deviceId, String driverKey, String externalId, String serial,
                        String accountFingerprint, String actorId) {
        if (activeForDevice(tenantId, deviceId).isPresent())
            throw ApiError.conflict("DEVICE_SOURCE_BOUND", "设备已有生效来源关联，请先显式解绑");
        if (activeBySource(driverKey, externalId).isPresent())
            throw ApiError.conflict("SOURCE_ALREADY_BOUND", "该来源资源已关联其他本地设备");
        String id = UUID.randomUUID().toString();
        long generation = nextGeneration(driverKey, externalId);
        jdbc.update("INSERT INTO device_source_bindings(id,tenant_id,device_id,driver_key,source_external_id,source_serial,account_fingerprint,generation,state,bound_at,bound_by) VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
            id, tenantId, deviceId, driverKey, externalId, serial, accountFingerprint, generation, OffsetDateTime.now(clock), actorId);
        return activeForDevice(tenantId, deviceId).orElseThrow();
    }

    /** Must run inside the caller's write transaction. Only an ACTIVE row can be released. */
    public Binding release(String bindingId, String actorId, String reason, JsonNode pendingRisk) {
        var stored = jdbc.query("SELECT pending_risk_json FROM device_source_bindings WHERE id=? FOR UPDATE",
            (rs, n) -> rs.getString(1), bindingId);
        if (stored.isEmpty()) throw ApiError.missing();
        if (stored.getFirst() != null && pendingRisk == null)
            throw ApiError.conflict("SOURCE_BINDING_RISK_PRESENT", "来源关联已有未决风险证据，释放时必须显式提供新风险快照，不能覆盖为未知");
        int changed = jdbc.update("UPDATE device_source_bindings SET state='RELEASED',pending_risk_json=?,released_at=?,released_by=?,release_reason=? WHERE id=? AND state='ACTIVE'",
            pendingRisk == null ? null : pendingRisk.toString(), OffsetDateTime.now(clock), actorId, reason, bindingId);
        if (changed != 1) throw ApiError.conflict("SOURCE_BINDING_NOT_ACTIVE", "来源关联已释放或不存在，请刷新");
        return jdbc.query("SELECT * FROM device_source_bindings WHERE id=?", (rs, n) -> row(rs), bindingId).getFirst();
    }

    public Binding require(String bindingId) {
        var rows = jdbc.query("SELECT * FROM device_source_bindings WHERE id=?", (rs, n) -> row(rs), bindingId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private Binding row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String risk = rs.getString("pending_risk_json");
        JsonNode riskNode = null;
        if (risk != null) {
            try { riskNode = json.readTree(risk); }
            catch (Exception invalid) { riskNode = null; }
            if (riskNode == null || !riskNode.isObject())
                throw ApiError.conflict("SOURCE_BINDING_RISK_INVALID", "来源关联风险证据无法解析，拒绝覆盖或忽略既有风险");
        }
        return new Binding(rs.getString("id"), rs.getString("tenant_id"), rs.getString("device_id"),
            rs.getString("driver_key"), rs.getString("source_external_id"), rs.getString("source_serial"),
            rs.getString("account_fingerprint"), rs.getLong("generation"), rs.getString("state"), riskNode,
            rs.getObject("bound_at", OffsetDateTime.class), rs.getString("bound_by"),
            rs.getObject("released_at", OffsetDateTime.class), rs.getString("released_by"), rs.getString("release_reason"));
    }
}
