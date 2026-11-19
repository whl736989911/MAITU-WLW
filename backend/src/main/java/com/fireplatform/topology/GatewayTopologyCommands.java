package com.fireplatform.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gateway topology commands. Links are explicit: MANUAL declarations, OBSERVED links backed by a stored
 * legacy MQTT event, and operator VERIFIED annotations. A child can only have one active gateway, which
 * makes silent re-parenting and cycles impossible instead of "resolving" conflicts.
 */
@Service
public class GatewayTopologyCommands {
    private static final int MAX_DEPTH = 64;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ManagementGuard guard;
    private final TenantFence tenants;
    private final DeviceAccessScope scopes;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final DatabaseTime time;

    public GatewayTopologyCommands(JdbcTemplate jdbc, ObjectMapper json, ManagementGuard guard, TenantFence tenants,
                                   DeviceAccessScope scopes, IdempotencyStore idem, AuditService audit,
                                   DatabaseTime time) {
        this.jdbc = jdbc; this.json = json; this.guard = guard; this.tenants = tenants; this.scopes = scopes;
        this.idem = idem; this.audit = audit; this.time = time;
    }

    // ---------------- gateways ----------------

    @Transactional
    public TopologyModels.Gateway createGateway(AuthService.Identity actor, String tenant, String key,
                                                TopologyInputs.CreateGateway input, String requestId) {
        guard.begin(actor, "topology:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, input.address(), input.name(), input.protocol(),
            input.deviceId(), input.reason()));
        var replay = idem.replay(actor.user().id(), "topology-gateway-create", key, hash, TopologyModels.Gateway.class);
        if (replay != null) return replay;
        assertAddressFree(tenant, input.address(), null);
        if (input.deviceId() != null) assertGatewayDevice(tenant, input.deviceId(), null, actor.user().id());
        String id = UUID.randomUUID().toString();
        var now = time.now();
        try {
            jdbc.update("INSERT INTO gateway_nodes(id,tenant_id,address,name,protocol,device_id,state,active_key,revision,created_by,created_at,updated_by,updated_at)"
                    + " VALUES(?,?,?,?,?,?,'ACTIVE',?,0,?,?,?,?)",
                id, tenant, input.address(), input.name(), input.protocol(), input.deviceId(),
                input.deviceId(), actor.user().id(), now, actor.user().id(), now);
        } catch (DuplicateKeyException race) {
            throw gateConflict(race);
        }
        audit.recordReason(actor.user().id(), tenant, "TOPOLOGY_GATEWAY_CREATED", id, "SUCCESS", requestId, input.reason());
        var result = gatewayRow(tenant, id);
        idem.save(actor.user().id(), "topology-gateway-create", key, hash, result);
        return result;
    }

    @Transactional
    public TopologyModels.Gateway updateGateway(AuthService.Identity actor, String tenant, String id, String key,
                                                TopologyInputs.UpdateGateway input, String requestId) {
        guard.begin(actor, "topology:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.address(), input.name(),
            input.protocol(), input.deviceId(), input.reason()));
        var current = lockedGateway(tenant, id);
        if (current.deviceId() != null && !scopes.contains(actor.user().id(), tenant, current.deviceId())) throw ApiError.missing();
        var replay = idem.replay(actor.user().id(), "topology-gateway-update", key, hash, TopologyModels.Gateway.class);
        if (replay != null) return replay;
        revision(input.expectedRevision(), current.revision());
        assertAddressFree(tenant, input.address(), id);
        if (input.deviceId() != null) {
            assertGatewayDevice(tenant, input.deviceId(), id, actor.user().id());
            assertAncestorChain(tenant, input.deviceId(), id, null);
        }
        try {
            int changed = jdbc.update("UPDATE gateway_nodes SET address=?,name=?,protocol=?,device_id=?,active_key=?,"
                    + "revision=revision+1,updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
                input.address(), input.name(), input.protocol(), input.deviceId(), input.deviceId(),
                actor.user().id(), time.now(), id, tenant, input.expectedRevision());
            if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "网关已变化，请刷新");
        } catch (DuplicateKeyException race) {
            throw gateConflict(race);
        }
        audit.recordReason(actor.user().id(), tenant, "TOPOLOGY_GATEWAY_UPDATED", id, "SUCCESS", requestId, input.reason());
        var result = gatewayRow(tenant, id);
        idem.save(actor.user().id(), "topology-gateway-update", key, hash, result);
        return result;
    }

    @Transactional
    public TopologyModels.Gateway retireGateway(AuthService.Identity actor, String tenant, String id, String key,
                                                TopologyInputs.RetireGateway input, String requestId) {
        guard.begin(actor, "topology:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.reason()));
        var current = lockedGateway(tenant, id);
        if (current.deviceId() != null && !scopes.contains(actor.user().id(), tenant, current.deviceId())) throw ApiError.missing();
        var replay = idem.replay(actor.user().id(), "topology-gateway-retire", key, hash, TopologyModels.Gateway.class);
        if (replay != null) return replay;
        revision(input.expectedRevision(), current.revision());
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM gateway_links WHERE tenant_id=? AND gateway_id=? AND state='ACTIVE'",
            Long.class, tenant, id);
        if (active != null && active != 0)
            throw ApiError.conflict("GATEWAY_HAS_ACTIVE_LINKS", "请先撤销该网关下所有有效设备/测点关系");
        if (jdbc.update("UPDATE gateway_nodes SET state='RETIRED',active_key=NULL,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE id=? AND tenant_id=? AND revision=?", actor.user().id(), time.now(), id, tenant,
                input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "网关已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "TOPOLOGY_GATEWAY_RETIRED", id, "SUCCESS", requestId, input.reason());
        var result = gatewayRow(tenant, id);
        idem.save(actor.user().id(), "topology-gateway-retire", key, hash, result);
        return result;
    }

    /**
     * Import-only: reuse an identical declared gateway or reject the row. Existing protocol/name/device
     * conflicts are never overwritten by an import.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TopologyModels.Gateway ensureImportedGateway(AuthService.Identity actor, String tenant, String address,
                                                       String name, String protocol, String deviceId,
                                                       String reason, String requestId) {
        String normalized = TopologyInputs.address(address);
        var rows = jdbc.query("SELECT id,address,name,protocol,device_id,state FROM gateway_nodes WHERE tenant_id=? AND address=?",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6)}, tenant, normalized);
        if (!rows.isEmpty()) {
            String[] existing = rows.getFirst();
            if ("ACTIVE".equals(existing[5]) && existing[2].equals(name.trim()) && existing[3].equals(protocol)
                && java.util.Objects.equals(existing[4], deviceId))
                return gatewayRow(tenant, existing[0]);
            throw ApiError.conflict("GATEWAY_CONFLICT_UNRESOLVED",
                "网关地址 " + normalized + " 已存在且名称/协议/设备不一致；导入不会合并或改写现有网关");
        }
        if (deviceId != null) assertGatewayDevice(tenant, deviceId, null, actor.user().id());
        String id = UUID.randomUUID().toString();
        var now = time.now();
        try {
            jdbc.update("INSERT INTO gateway_nodes(id,tenant_id,address,name,protocol,device_id,state,active_key,revision,created_by,created_at,updated_by,updated_at)"
                    + " VALUES(?,?,?,?,?,?,'ACTIVE',?,0,?,?,?,?)",
                id, tenant, normalized, name.trim(), protocol, deviceId, deviceId, actor.user().id(), now,
                actor.user().id(), now);
        } catch (DuplicateKeyException race) {
            throw gateConflict(race);
        }
        audit.recordReason(actor.user().id(), tenant, "TOPOLOGY_GATEWAY_IMPORTED", id, "SUCCESS", requestId, reason);
        return gatewayRow(tenant, id);
    }

    // ---------------- links ----------------

    @Transactional
    public TopologyModels.Link createLink(AuthService.Identity actor, String tenant, String key,
                                          TopologyInputs.CreateLink input, String requestId) {
        guard.begin(actor, "topology:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, input.gatewayId(), input.childKind(), input.deviceId(),
            input.channelId(), input.provenance(), input.observationSource(), input.observationEventId(),
            String.valueOf(input.observedAt()), input.reason()));
        var replay = idem.replay(actor.user().id(), "topology-link-create", key, hash, TopologyModels.Link.class);
        if (replay != null) return replay;
        return createLinkLocked(actor, tenant, input, hash, key, requestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TopologyModels.Link importLink(AuthService.Identity actor, String tenant, String key,
                                          TopologyInputs.CreateLink input, String requestId) {
        return createLinkLocked(actor, tenant, input, null, key, requestId);
    }

    private TopologyModels.Link createLinkLocked(AuthService.Identity actor, String tenant, TopologyInputs.CreateLink input,
                                                 String hash, String key, String requestId) {
        var gateway = lockedGateway(tenant, input.gatewayId());
        if (!"ACTIVE".equals(gateway.state())) throw ApiError.conflict("GATEWAY_RETIRED", "网关已停用，不能新增关系");
        assertActiveChildDevice(tenant, input.deviceId(), actor.user().id());
        if (input.channelId() != null) assertChannel(tenant, input.deviceId(), input.channelId());
        assertNoActiveLink(tenant, input.deviceId());
        assertAncestorChain(tenant, gateway.deviceId(), gateway.id(), input.deviceId());
        if ("OBSERVED".equals(input.provenance())) verifyObservation(tenant, input);
        String id = UUID.randomUUID().toString();
        var now = time.now();
        String activeKey = childKey(input.childKind(), input.deviceId(), input.channelId());
        try {
            jdbc.update("INSERT INTO gateway_links(id,tenant_id,gateway_id,child_kind,device_id,channel_id,provenance,"
                    + "observation_source,observation_event_id,observed_at,verified,state,active_key,revision,created_by,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,FALSE,'ACTIVE',?,0,?,?,?)",
                id, tenant, input.gatewayId(), input.childKind(), input.deviceId(), input.channelId(),
                input.provenance(), input.observationSource(), input.observationEventId(), input.observedAt(),
                activeKey, actor.user().id(), now, now);
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("CHILD_ALREADY_LINKED", "该设备或测点已存在有效网关关系；不会静默替换父级");
        }
        audit.recordReason(actor.user().id(), tenant, "TOPOLOGY_LINK_CREATED", id, "SUCCESS", requestId, input.reason());
        var result = linkRow(tenant, id);
        if (hash != null && key != null) idem.save(actor.user().id(), "topology-link-create", key, hash, result);
        return result;
    }

    @Transactional
    public TopologyModels.Link verifyLink(AuthService.Identity actor, String tenant, String id, String key,
                                          TopologyInputs.VerifyLink input, String requestId) {
        guard.begin(actor, "topology:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.note(), input.reason()));
        var current = lockedLink(tenant, id);
        if (!scopes.contains(actor.user().id(), tenant, current.deviceId())) throw ApiError.missing();
        var replay = idem.replay(actor.user().id(), "topology-link-verify", key, hash, TopologyModels.Link.class);
        if (replay != null) return replay;
        revision(input.expectedRevision(), current.revision());
        if (!"ACTIVE".equals(current.state())) throw ApiError.conflict("LINK_REVOKED", "已撤销的关系不能核验");
        if (current.verified()) throw ApiError.conflict("LINK_ALREADY_VERIFIED", "该关系已核验；如需更正请撤销后重新登记");
        if (jdbc.update("UPDATE gateway_links SET verified=TRUE,verified_by=?,verified_at=?,verification_note=?,"
                + "revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            actor.user().id(), time.now(), input.note(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "关系已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "TOPOLOGY_LINK_VERIFIED", id, "SUCCESS", requestId, input.reason());
        var result = linkRow(tenant, id);
        idem.save(actor.user().id(), "topology-link-verify", key, hash, result);
        return result;
    }

    @Transactional
    public TopologyModels.Link revokeLink(AuthService.Identity actor, String tenant, String id, String key,
                                          TopologyInputs.RevokeLink input, String requestId) {
        guard.begin(actor, "topology:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.reason()));
        var current = lockedLink(tenant, id);
        if (!scopes.contains(actor.user().id(), tenant, current.deviceId())) throw ApiError.missing();
        var replay = idem.replay(actor.user().id(), "topology-link-revoke", key, hash, TopologyModels.Link.class);
        if (replay != null) return replay;
        revision(input.expectedRevision(), current.revision());
        if (!"ACTIVE".equals(current.state())) throw ApiError.conflict("LINK_ALREADY_REVOKED", "该关系已撤销");
        if (jdbc.update("UPDATE gateway_links SET state='REVOKED',active_key=NULL,revoked_by=?,revoked_at=?,revoke_reason=?,"
                + "revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            actor.user().id(), time.now(), input.revokeReason(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "关系已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "TOPOLOGY_LINK_REVOKED", id, "SUCCESS", requestId, input.reason());
        var result = linkRow(tenant, id);
        idem.save(actor.user().id(), "topology-link-revoke", key, hash, result);
        return result;
    }

    // ---------------- validation ----------------

    private void assertActiveChildDevice(String tenant, String deviceId, String actorId) {
        var rows = jdbc.query("SELECT archived_at FROM devices WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), deviceId, tenant);
        if (rows.isEmpty()) throw ApiError.conflict("DEVICE_NOT_IN_TENANT", "设备不存在或不属于当前单位");
        if (rows.getFirst() != null) throw ApiError.conflict("DEVICE_ARCHIVED", "已归档设备不能新增拓扑关系");
        if (!scopes.contains(actorId, tenant, deviceId))
            throw ApiError.forbidden("设备不在当前操作者的设备范围内");
    }

    private void assertGatewayDevice(String tenant, String deviceId, String selfGatewayId, String actorId) {
        var rows = jdbc.query("SELECT archived_at FROM devices WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), deviceId, tenant);
        if (rows.isEmpty()) throw ApiError.conflict("DEVICE_NOT_IN_TENANT", "网关设备不存在或不属于当前单位");
        if (rows.getFirst() != null) throw ApiError.conflict("DEVICE_ARCHIVED", "网关设备已归档");
        if (!scopes.contains(actorId, tenant, deviceId))
            throw ApiError.forbidden("网关设备不在当前操作者的设备范围内");
        var binding = jdbc.query("SELECT id FROM gateway_nodes WHERE tenant_id=? AND device_id=? AND state='ACTIVE'",
            (rs, n) -> rs.getString(1), tenant, deviceId);
        if (!binding.isEmpty() && !binding.getFirst().equals(selfGatewayId))
            throw ApiError.conflict("GATEWAY_DEVICE_ALREADY_BOUND", "该设备已作为其他网关的设备资产；不会自动改绑");
    }

    private void assertChannel(String tenant, String deviceId, String channelId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_channels WHERE id=? AND tenant_id=? AND device_id=?",
            Long.class, channelId, tenant, deviceId);
        if (count == null || count != 1L) throw ApiError.conflict("CHANNEL_NOT_FOUND", "测点通道不存在或不属于目标设备");
    }

    private void assertNoActiveLink(String tenant, String deviceId) {
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM gateway_links WHERE tenant_id=? AND device_id=? AND state='ACTIVE'",
            Long.class, tenant, deviceId);
        if (active != null && active != 0)
            throw ApiError.conflict("CHILD_ALREADY_LINKED", "该设备/测点已有有效网关关系；请先撤销旧关系");
    }

    /** Check gateway-owner ancestry for child edges, or proposed-owner ancestry for asset reassignment. */
    private void assertAncestorChain(String tenant, String startDeviceId, String gatewayId, String forbiddenDeviceId) {
        if (startDeviceId == null) return;
        String current = startDeviceId;
        var visited = new java.util.HashSet<String>();
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            if (current.equals(forbiddenDeviceId) || !visited.add(current))
                throw ApiError.conflict("TOPOLOGY_CYCLE", "该操作会形成或延续网关环路，已拒绝");
            var parent = jdbc.query("SELECT l.gateway_id,g.device_id FROM gateway_links l"
                    + " JOIN gateway_nodes g ON g.id=l.gateway_id AND g.tenant_id=l.tenant_id"
                    + " WHERE l.tenant_id=? AND l.device_id=? AND l.state='ACTIVE'",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, tenant, current);
            if (parent.isEmpty()) return;
            if (parent.getFirst()[0].equals(gatewayId))
                throw ApiError.conflict("TOPOLOGY_CYCLE", "该操作会形成或延续网关环路，已拒绝");
            current = parent.getFirst()[1];
        }
        if (current != null)
            throw ApiError.conflict("TOPOLOGY_TOO_DEEP", "网关层级超过安全上限");
    }

    private void assertAddressFree(String tenant, String address, String selfId) {
        var rows = jdbc.query("SELECT id FROM gateway_nodes WHERE tenant_id=? AND address=?",
            (rs, n) -> rs.getString(1), tenant, address);
        if (!rows.isEmpty() && !rows.getFirst().equals(selfId))
            throw ApiError.conflict("GATEWAY_ADDRESS_EXISTS", "该网关地址已登记；不会自动合并或改绑");
    }

    /** OBSERVED links must quote a stored legacy MQTT event that names both this gateway and this device. */
    private void verifyObservation(String tenant, TopologyInputs.CreateLink input) {
        var rows = jdbc.query("SELECT device_id,params_json FROM legacy_ingest_events WHERE id=? AND tenant_id=?",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, input.observationEventId(), tenant);
        if (rows.isEmpty()) throw ApiError.conflict("EVIDENCE_NOT_FOUND", "观察事件不存在或不属于当前单位");
        String device = rows.getFirst()[0];
        if (device == null || !device.equals(input.deviceId()))
            throw ApiError.conflict("EVIDENCE_MISMATCH", "观察事件不属于该设备");
        JsonNode params;
        try {
            params = json.readTree(rows.getFirst()[1]);
        } catch (Exception failure) {
            throw ApiError.conflict("EVIDENCE_UNREADABLE", "观察事件参数无法读取");
        }
        JsonNode gateway = params == null ? null : params.get("gateway");
        var address = jdbc.queryForObject("SELECT address FROM gateway_nodes WHERE id=? AND tenant_id=?",
            String.class, input.gatewayId(), tenant);
        if (gateway == null || !gateway.isTextual() || address == null
            || !address.equalsIgnoreCase(gateway.asText().trim()))
            throw ApiError.conflict("EVIDENCE_MISMATCH", "观察事件的网关地址与所选网关不一致");
    }

    // ---------------- read helpers ----------------

    private TopologyModels.Gateway gatewayRow(String tenant, String id) {
        var rows = jdbc.query("SELECT g.id,g.tenant_id,g.address,g.name,g.protocol,g.device_id,d.name AS device_name,"
                + "g.state,g.revision,g.created_at,g.updated_at,"
                + "(SELECT COUNT(*) FROM gateway_links l WHERE l.tenant_id=g.tenant_id AND l.gateway_id=g.id AND l.state='ACTIVE') AS visible_children"
                + " FROM gateway_nodes g LEFT JOIN devices d ON d.id=g.device_id AND d.tenant_id=g.tenant_id"
                + " WHERE g.tenant_id=? AND g.id=?",
            (rs, n) -> new TopologyModels.Gateway(rs.getString("id"), rs.getString("tenant_id"), rs.getString("address"),
                rs.getString("name"), rs.getString("protocol"), rs.getString("device_id"), rs.getString("device_name"),
                rs.getString("state"), rs.getLong("revision"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("visible_children")), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private TopologyModels.Link linkRow(String tenant, String id) {
        var rows = jdbc.query("SELECT l.id,l.tenant_id,l.gateway_id,g.address AS gateway_address,g.name AS gateway_name,"
                + "l.child_kind,l.device_id,d.name AS device_name,d.device_eui,d.place_id,p.name AS place_name,"
                + "l.channel_id,c.name AS channel_name,c.channel_type,c.enabled AS channel_enabled,d.archived_at AS device_archived_at,"
                + "l.provenance,l.observation_source,l.observation_event_id,l.observed_at,l.verified,l.verified_by,"
                + "l.verified_at,l.verification_note,l.state,l.revoked_by,l.revoked_at,l.revoke_reason,l.revision,"
                + "l.created_by,l.created_at,l.updated_at"
                + " FROM gateway_links l"
                + " JOIN gateway_nodes g ON g.id=l.gateway_id AND g.tenant_id=l.tenant_id"
                + " JOIN devices d ON d.id=l.device_id AND d.tenant_id=l.tenant_id"
                + " LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id"
                + " LEFT JOIN device_channels c ON c.id=l.channel_id AND c.tenant_id=l.tenant_id AND c.device_id=l.device_id"
                + " WHERE l.tenant_id=? AND l.id=?",
            (rs, n) -> link(rs), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private static TopologyModels.Link link(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TopologyModels.Link(rs.getString("id"), rs.getString("tenant_id"), rs.getString("gateway_id"),
            rs.getString("gateway_address"), rs.getString("gateway_name"), rs.getString("child_kind"),
            rs.getString("device_id"), rs.getString("device_name"), rs.getString("device_eui"), rs.getString("place_id"),
            rs.getString("place_name"), rs.getString("channel_id"), rs.getString("channel_name"),
            rs.getString("channel_type"), rs.getBoolean("channel_enabled"),
            rs.getObject("device_archived_at", OffsetDateTime.class), rs.getString("provenance"),
            rs.getString("observation_source"), rs.getString("observation_event_id"),
            rs.getObject("observed_at", OffsetDateTime.class), rs.getBoolean("verified"), rs.getString("verified_by"),
            rs.getObject("verified_at", OffsetDateTime.class), rs.getString("verification_note"), rs.getString("state"),
            rs.getString("revoked_by"), rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoke_reason"),
            rs.getLong("revision"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));
    }

    private record LockedGateway(String id, String state, long revision, String deviceId, String address) { }

    private LockedGateway lockedGateway(String tenant, String id) {
        var rows = jdbc.query("SELECT id,state,revision,device_id,address FROM gateway_nodes WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> new LockedGateway(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)),
            id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private record LockedLink(String id, String state, long revision, boolean verified, String deviceId) { }

    private LockedLink lockedLink(String tenant, String id) {
        var rows = jdbc.query("SELECT id,state,revision,verified,device_id FROM gateway_links WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> new LockedLink(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), rs.getString(5)), id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private static void revision(long expected, long actual) {
        if (expected != actual) throw ApiError.conflict("REVISION_CONFLICT", "数据已变化，请刷新后重试");
    }

    private static String childKey(String kind, String deviceId, String channelId) {
        return kind + ":" + deviceId + ":" + (channelId == null ? "-" : channelId);
    }

    private static ApiError gateConflict(DuplicateKeyException race) {
        String message = race.getMostSpecificCause() == null ? "" : race.getMostSpecificCause().getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT).contains("active_device"))
            return ApiError.conflict("GATEWAY_DEVICE_ALREADY_BOUND", "该设备已作为其他网关的设备资产");
        return ApiError.conflict("GATEWAY_ADDRESS_EXISTS", "网关地址已登记；不会自动合并或改绑");
    }
}
