package com.fireplatform.bulkio;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.DeviceScopes;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.topology.GatewayTopologyCommands;
import com.fireplatform.topology.GatewayTopologyQueries;
import com.fireplatform.topology.TopologyInputs;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Topology import/export. Imports only create MANUAL links; OBSERVED links must quote a stored legacy event
 * and are never produced from a spreadsheet. Existing gateway/child conflicts reject the row instead of
 * merging or re-parenting.
 */
@Component
public class TopologyDataTransferDomain implements DataTransferDomain {
    private static final List<String> IMPORT_COLUMNS = List.of("gateway_address", "gateway_name", "gateway_protocol",
        "gateway_device_id", "child_kind", "device_id", "channel_id");
    private static final List<String> EXPORT_COLUMNS = List.of("id", "tenant_id", "gateway_address", "gateway_name",
        "gateway_protocol", "gateway_device_id", "child_kind", "device_id", "device_eui", "channel_id",
        "channel_name", "provenance", "verified", "state", "revision", "created_at", "updated_at");

    private final JdbcTemplate jdbc;
    private final DeviceScopes devices;
    private final GatewayTopologyQueries queries;
    private final GatewayTopologyCommands commands;

    public TopologyDataTransferDomain(JdbcTemplate jdbc, DeviceScopes devices, GatewayTopologyQueries queries,
                                      GatewayTopologyCommands commands) {
        this.jdbc = jdbc; this.devices = devices; this.queries = queries; this.commands = commands;
    }

    @Override
    public List<Kind> kinds() {
        return List.of(new Kind("TOPOLOGY_LINKS", IMPORT_COLUMNS, "topology:read", "topology:write",
            true, true, true, Set.of("management"), "MASTER_DATA"));
    }

    @Override
    public List<String> validate(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant) {
        requireKind(kind);
        var errors = new ArrayList<String>();
        TopologyInputs.CreateLink input;
        String gatewayAddress, gatewayName, gatewayProtocol, gatewayDevice;
        try {
            gatewayAddress = normalizeAddress(cells.get("gateway_address"));
            gatewayName = text(cells.get("gateway_name"), 200, "网关名称");
            gatewayProtocol = protocol(cells.get("gateway_protocol"));
            gatewayDevice = blank(cells.get("gateway_device_id")) ? null : id(cells.get("gateway_device_id"), "网关设备ID");
            input = TopologyInputs.createLink(new TopologyInputs.CreateLinkBody(
                existingGatewayId(tenant, gatewayAddress), cells.get("child_kind"), cells.get("device_id"),
                cells.get("channel_id"), "MANUAL", null, null, null, "数据导入草稿"));
        } catch (ApiError failure) {
            errors.add(failure.getMessage());
            return List.copyOf(errors);
        }
        if (!devices.contains(actor.user().id(), tenant, input.deviceId()))
            errors.add("设备不存在或不在当前授权范围");
        if (gatewayDevice != null && !devices.contains(actor.user().id(), tenant, gatewayDevice))
            errors.add("网关设备不存在或不在当前授权范围");
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM gateway_links WHERE tenant_id=? AND device_id=? AND state='ACTIVE'",
            Long.class, tenant, input.deviceId());
        if (active != null && active != 0) errors.add("该设备/测点已有有效网关关系；导入不会替换父级");
        var existing = existingGateway(tenant, gatewayAddress);
        if (existing != null) {
            if (!existing.name().equals(gatewayName) || !existing.protocol().equals(gatewayProtocol)
                || !java.util.Objects.equals(existing.deviceId(), gatewayDevice))
                errors.add("网关地址已存在且名称/协议/设备不一致；导入不会合并或改写现有网关");
        }
        return List.copyOf(errors);
    }

    @Override
    public String operation(String kind, Map<String, String> cells) {
        requireKind(kind);
        return "CREATE_LINK";
    }

    @Override
    public String apply(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant,
                        String reason, String jobId, int rowNumber) {
        requireKind(kind);
        String gatewayAddress = normalizeAddress(cells.get("gateway_address"));
        var gateway = commands.ensureImportedGateway(actor, tenant, gatewayAddress,
            text(cells.get("gateway_name"), 200, "网关名称"), protocol(cells.get("gateway_protocol")),
            blank(cells.get("gateway_device_id")) ? null : id(cells.get("gateway_device_id"), "网关设备ID"),
            reason, "data-transfer:" + jobId);
        var input = TopologyInputs.createLink(new TopologyInputs.CreateLinkBody(gateway.id(), cells.get("child_kind"),
            cells.get("device_id"), cells.get("channel_id"), "MANUAL", null, null, null, reason));
        return commands.importLink(actor, tenant, "import." + jobId + "." + rowNumber, input,
            "data-transfer:" + jobId).id();
    }

    @Override
    public ExportData export(String kind, AuthService.Identity actor, String tenant, Map<String, String> filters,
                             List<String> selectedIds) {
        requireKind(kind);
        if (filters == null) filters = Map.of();
        var selection = DataTransferFilters.selection(filters, Set.of("keyword", "state", "gateway_address"));
        if (selection.empty()) return new ExportData(EXPORT_COLUMNS, List.of());
        var visible = queries.visibleDevice(actor.user().id(), tenant, selection, "d");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(visible.bindings());
        var where = new StringBuilder(" WHERE l.tenant_id=? AND ").append(visible.sql());
        String state = text(filters.get("state"));
        if (state != null) {
            String normalized = state.toUpperCase(java.util.Locale.ROOT);
            if (!List.of("ACTIVE", "REVOKED", "ALL").contains(normalized)) throw ApiError.bad("关系状态筛选无效");
            if (!"ALL".equals(normalized)) { where.append(" AND l.state=?"); args.add(normalized); }
        }
        String address = blank(filters.get("gateway_address")) ? null : normalizeAddress(filters.get("gateway_address"));
        if (address != null) { where.append(" AND g.address=?"); args.add(address); }
        String keyword = text(filters.get("keyword"));
        if (keyword != null) {
            String like = LiteralLike.contains(keyword);
            where.append(" AND (LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(d.device_eui,'')) LIKE LOWER(?) ESCAPE '!' OR LOWER(g.address) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like);
        }
        var rows = jdbc.query("SELECT l.id,l.tenant_id,g.address,g.name,g.protocol,g.device_id,l.child_kind,l.device_id,"
                + "d.device_eui,l.channel_id,c.name,l.provenance,l.verified,l.state,l.revision,l.created_at,l.updated_at"
                + " FROM gateway_links l"
                + " JOIN gateway_nodes g ON g.id=l.gateway_id AND g.tenant_id=l.tenant_id"
                + " JOIN devices d ON d.id=l.device_id AND d.tenant_id=l.tenant_id"
                + " LEFT JOIN device_channels c ON c.id=l.channel_id AND c.tenant_id=l.tenant_id AND c.device_id=l.device_id"
                + where + " ORDER BY g.address,d.name,l.id LIMIT 10001",
            (rs, n) -> Arrays.asList(nullable(rs.getString(1)), nullable(rs.getString(2)), nullable(rs.getString(3)),
                nullable(rs.getString(4)), nullable(rs.getString(5)), nullable(rs.getString(6)), nullable(rs.getString(7)),
                nullable(rs.getString(8)), nullable(rs.getString(9)), nullable(rs.getString(10)), nullable(rs.getString(11)),
                nullable(rs.getString(12)), Boolean.toString(rs.getBoolean(13)), nullable(rs.getString(14)),
                Long.toString(rs.getLong(15)), nullable(rs.getObject(16, OffsetDateTime.class)),
                nullable(rs.getObject(17, OffsetDateTime.class))),
            args.toArray());
        return DataTransferFilters.selected(new ExportData(EXPORT_COLUMNS, rows), selectedIds);
    }

    private record Existing(String id, String name, String protocol, String deviceId) { }

    private Existing existingGateway(String tenant, String address) {
        var rows = jdbc.query("SELECT id,name,protocol,device_id FROM gateway_nodes WHERE tenant_id=? AND address=?"
                + " AND state='ACTIVE'",
            (rs, n) -> new Existing(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), tenant, address);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String existingGatewayId(String tenant, String address) {
        var existing = existingGateway(tenant, address);
        return existing == null ? "import-gateway-placeholder" : existing.id();
    }

    private static void requireKind(String kind) {
        if (!"TOPOLOGY_LINKS".equals(kind)) throw ApiError.bad("该类型不支持网关拓扑导入导出");
    }

    private static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) throw ApiError.bad("网关地址不能为空");
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() > 64 || !normalized.matches("[A-Z0-9._:-]+")) throw ApiError.bad("网关地址无效");
        return normalized;
    }

    private static String protocol(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TopologyInputs.PROTOCOLS.contains(normalized)) throw ApiError.bad("网关协议无效");
        return normalized;
    }

    private static String id(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl))
            throw ApiError.bad(field + "无效");
        return value;
    }

    private static String text(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String text(String value, int max, String field) {
        String normalized = text(value);
        if (normalized == null || normalized.length() > max) throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    private static String nullable(Object value) { return value == null ? "" : value.toString(); }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
