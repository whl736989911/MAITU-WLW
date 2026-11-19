package com.fireplatform.ruleflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.ResourceLinkageCommands;
import com.fireplatform.identity.AuthService;
import com.fireplatform.notification.NotificationChannelSupport;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ScopeFilter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side: stored flow revisions, per-node compile reports and a live readback of the consumers a flow owns. */
@Service
@Transactional(readOnly = true)
public class RuleFlowQueries {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PageSizePolicy sizes;
    private final DeviceAccessScope scopes;
    private final ScopeFilter tree;
    private final ResourceLinkageCommands linkage;
    private final NotificationChannelSupport channels;

    public RuleFlowQueries(JdbcTemplate jdbc, ObjectMapper json, PageSizePolicy sizes, DeviceAccessScope scopes,
            ScopeFilter tree, ResourceLinkageCommands linkage, NotificationChannelSupport channels) {
        this.jdbc = jdbc; this.json = json; this.sizes = sizes; this.scopes = scopes; this.tree = tree;
        this.linkage = linkage; this.channels = channels;
    }

    public Page<RuleFlowModels.Flow> page(String actor, String tenant, ScopeFilter.Selection selection, int page,
            Integer size, String keyword, String status) {
        var filter = tree.units(actor, "f", "tenant_id", tenant, selection);
        StringBuilder where = new StringBuilder(" FROM rule_flows f WHERE 1=1").append(filter.and());
        List<Object> args = new ArrayList<>(filter.bindings());
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(f.name) LIKE ? OR LOWER(f.id) LIKE ?)");
            args.add(like); args.add(like);
        }
        if (status != null && !status.isBlank()) {
            if (!Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status)) throw ApiError.bad("规则流状态筛选无效");
            where.append(" AND f.status=?"); args.add(status);
        }
        Pagination pagination = Pagination.of(page, size, sizes.defaultPageSize());
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pagination.size()); pageArgs.add(pagination.offset());
        var items = jdbc.query("SELECT *" + where + " ORDER BY f.updated_at DESC,f.id LIMIT ? OFFSET ?",
            (rs, n) -> flow(rs), pageArgs.toArray());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }

    public RuleFlowModels.Flow flow(String tenant, String id) {
        var rows = jdbc.query("SELECT * FROM rule_flows WHERE tenant_id=? AND id=?", (rs, n) -> flow(rs), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public RuleFlowModels.Definition definition(String tenant, String flowId) {
        var rows = jdbc.queryForList("SELECT definition_json FROM rule_flows WHERE tenant_id=? AND id=?", tenant, flowId);
        if (rows.isEmpty()) throw ApiError.missing();
        return parseDefinition(String.valueOf(rows.getFirst().get("definition_json")));
    }

    /** Published definition plus whether the current draft already moved away from it. */
    public RuleFlowModels.FlowDetail detail(String tenant, String id) {
        RuleFlowModels.Flow flow = flow(tenant, id);
        RuleFlowModels.Definition definition = definition(tenant, id);
        Long drift = null;
        if (flow.publishedRevision() != null) {
            String published = jdbc.queryForObject("SELECT definition_sha256 FROM rule_flow_revisions WHERE flow_id=? AND revision=?",
                String.class, id, flow.publishedRevision());
            if (!published.equals(flow.definitionSha256())) drift = flow.publishedRevision();
        }
        return new RuleFlowModels.FlowDetail(flow, definition, drift);
    }

    public List<RuleFlowModels.Revision> revisions(String tenant, String flowId) {
        return jdbc.query("SELECT revision,definition_sha256,plan_hash,reason,published_by,published_at FROM rule_flow_revisions WHERE tenant_id=? AND flow_id=? ORDER BY revision DESC",
            (rs, n) -> new RuleFlowModels.Revision(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime.class)), tenant, flowId);
    }

    public RuleFlowModels.RevisionDetail revision(String tenant, String flowId, long revision) {
        var rows = jdbc.query("SELECT definition_json,plan_json,reason,published_by,published_at FROM rule_flow_revisions WHERE tenant_id=? AND flow_id=? AND revision=?",
            (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class)},
            tenant, flowId, revision);
        if (rows.isEmpty()) throw ApiError.missing();
        Object[] row = rows.getFirst();
        return new RuleFlowModels.RevisionDetail(revision, parseDefinition(String.valueOf(row[0])),
            decode(String.valueOf(row[1]), new TypeReference<RuleFlowModels.Plan>() { }), String.valueOf(row[2]),
            String.valueOf(row[3]), (OffsetDateTime) row[4]);
    }

    public RuleFlowModels.Definition revisionDefinition(String tenant, String flowId, long revision) {
        var rows = jdbc.queryForList("SELECT definition_json FROM rule_flow_revisions WHERE tenant_id=? AND flow_id=? AND revision=?",
            tenant, flowId, revision);
        if (rows.isEmpty()) throw ApiError.missing();
        return parseDefinition(String.valueOf(rows.getFirst().get("definition_json")));
    }

    public Map<String, RuleFlowModels.Binding> bindings(String tenant, String flowId, long revision) {
        Map<String, RuleFlowModels.Binding> result = new LinkedHashMap<>();
        jdbc.query("SELECT consumer_kind,scope_key,consumer_id,consumer_revision,owned_fields_json,updated_at FROM rule_flow_consumer_bindings WHERE tenant_id=? AND flow_id=? AND flow_revision=? ORDER BY consumer_kind,scope_key",
            (rs, n) -> {
                List<String> owned = decode(rs.getString(5), new TypeReference<List<String>>() { });
                result.put(RuleFlowCompiler.bindingKey(rs.getString(1), rs.getString(2)),
                    new RuleFlowModels.Binding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), owned,
                        rs.getObject(6, OffsetDateTime.class)));
                return null;
            }, tenant, flowId, revision);
        return result;
    }

    /** Bindings of the currently published revision; empty when nothing has been published. */
    public Map<String, RuleFlowModels.Binding> liveBindings(String tenant, RuleFlowModels.Flow flow) {
        if (flow.publishedRevision() == null) return Map.of();
        return bindings(tenant, flow.id(), flow.publishedRevision());
    }

    public List<RuleFlowModels.CompileReport> reports(String tenant, String flowId) {
        return jdbc.query("SELECT id,draft_revision,plan_hash,ok,issues_json,created_by,created_at FROM rule_flow_compile_reports WHERE tenant_id=? AND flow_id=? ORDER BY created_at DESC,id DESC LIMIT 50",
            (rs, n) -> new RuleFlowModels.CompileReport(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4),
                decode(rs.getString(5), new TypeReference<List<RuleFlowModels.Issue>>() { }), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class)), tenant, flowId);
    }

    public List<RuleFlowModels.Event> events(String tenant, String flowId) {
        return jdbc.query("SELECT id,action,revision_before,revision_after,actor_id,reason,detail_json,created_at FROM rule_flow_events WHERE tenant_id=? AND flow_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
            (rs, n) -> new RuleFlowModels.Event(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)), tenant, flowId);
    }

    /**
     * Live readback of every consumer a flow revision owns. A consumer whose revision moved away from the
     * recorded binding is reported as drift instead of being silently re-adopted.
     */
    public RuleFlowModels.CompiledView compiled(String tenant, String flowId) {
        RuleFlowModels.Flow flow = flow(tenant, flowId);
        if (flow.publishedRevision() == null) return new RuleFlowModels.CompiledView(flowId, 0, false, List.of());
        Map<String, RuleFlowModels.Binding> bindings = bindings(tenant, flowId, flow.publishedRevision());
        List<RuleFlowModels.ConsumerReadback> consumers = new ArrayList<>();
        for (RuleFlowModels.Binding binding : bindings.values()) {
            consumers.add(readback(tenant, binding, flow.enabled()));
        }
        return new RuleFlowModels.CompiledView(flowId, flow.publishedRevision(), flow.enabled(), List.copyOf(consumers));
    }

    private RuleFlowModels.ConsumerReadback readback(String tenant, RuleFlowModels.Binding binding, boolean enabled) {
        switch (binding.consumerKind()) {
            case "ALARM_BASE_CONFIGURATION" -> {
                var rows = jdbc.query("SELECT revision,config_json FROM alarm_base_configurations WHERE tenant_id=?",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, tenant);
                long revision = rows.isEmpty() ? 0 : ((Number) rows.getFirst()[0]).longValue();
                Map<String, Object> detail = new LinkedHashMap<>();
                if (!rows.isEmpty()) {
                    Map<String, Object> value = decode(String.valueOf(rows.getFirst()[1]), new TypeReference<Map<String, Object>>() { });
                    for (String field : binding.ownedFields()) detail.put(field, value.get(field));
                }
                return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(), tenant, revision,
                    revision != binding.consumerRevision(), enabled ? "已启用" : "已发布但未启用", detail);
            }
            case "ALARM_MERGE_POLICY" -> {
                var rows = jdbc.query("SELECT revision,enabled,window_seconds FROM alarm_merge_policies WHERE tenant_id=?",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2), rs.getObject(3, Integer.class)}, tenant);
                long revision = rows.isEmpty() ? 0 : ((Number) rows.getFirst()[0]).longValue();
                Map<String, Object> detail = new LinkedHashMap<>();
                if (!rows.isEmpty()) { detail.put("enabled", rows.getFirst()[1]); detail.put("window_seconds", rows.getFirst()[2]); }
                return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(), tenant, revision,
                    revision != binding.consumerRevision(), enabled ? "已启用" : "已发布但未启用", detail);
            }
            case "NOTIFICATION_RULE" -> {
                var rows = jdbc.query("SELECT revision,enabled,event_scope,sms_escalation_interval_seconds,voice_escalation_interval_seconds FROM notification_rules WHERE tenant_id=? AND id=? AND legacy_mode IS NULL",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2), rs.getString(3), rs.getObject(4, Integer.class), rs.getObject(5, Integer.class)},
                    tenant, binding.consumerId());
                if (rows.isEmpty()) return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(),
                    binding.consumerId(), 0, true, "绑定的通知规则已不存在", Map.of());
                long revision = ((Number) rows.getFirst()[0]).longValue();
                List<Map<String, Object>> receivers = jdbc.queryForList("SELECT channel,level,contact_id FROM notification_rule_receivers WHERE tenant_id=? AND rule_id=? ORDER BY channel,level,contact_id",
                    tenant, binding.consumerId());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("event_scope", rows.getFirst()[2]);
                detail.put("enabled", rows.getFirst()[1]);
                detail.put("sms_escalation_seconds", rows.getFirst()[3]);
                detail.put("voice_escalation_seconds", rows.getFirst()[4]);
                detail.put("receivers", receivers);
                return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(), binding.consumerId(), revision,
                    revision != binding.consumerRevision(), Boolean.TRUE.equals(rows.getFirst()[1]) ? "已启用" : "已停用", detail);
            }
            case "RESOURCE_LINKAGE" -> {
                var rows = jdbc.query("SELECT revision,enabled,name FROM resource_linkage_rules WHERE tenant_id=? AND id=? AND deleted=FALSE",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2), rs.getString(3)}, tenant, binding.consumerId());
                if (rows.isEmpty()) return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(),
                    binding.consumerId(), 0, true, "引用的联动规则已不存在", Map.of());
                long revision = ((Number) rows.getFirst()[0]).longValue();
                return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(), binding.consumerId(), revision,
                    revision != binding.consumerRevision(), "只读引用（规则流不启停）",
                    Map.of("name", rows.getFirst()[2], "enabled", rows.getFirst()[1]));
            }
            case "OUTPUT_APPLICATION" -> {
                var rows = jdbc.query("SELECT revision,name,status FROM output_applications WHERE tenant_id=? AND id=?",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}, tenant, binding.consumerId());
                if (rows.isEmpty()) return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(),
                    binding.consumerId(), 0, true, "引用的数据输出应用已不存在", Map.of());
                long revision = ((Number) rows.getFirst()[0]).longValue();
                return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(), binding.consumerId(), revision,
                    revision != binding.consumerRevision(), "只读引用（规则流不启停外发）",
                    Map.of("name", rows.getFirst()[1], "status", rows.getFirst()[2]));
            }
            default -> {
                return new RuleFlowModels.ConsumerReadback(binding.consumerKind(), binding.scopeKey(), binding.consumerId(),
                    binding.consumerRevision(), false, "未知消费者", Map.of());
            }
        }
    }

    /** Real selector data for the editor. Each list is permission-gated and bounded. */
    public RuleFlowModels.Catalog catalog(AuthService.Identity actor, String tenant) {
        Set<String> permissions = Set.copyOf(actor.permissions());
        List<RuleFlowModels.Option> systemTypes = new ArrayList<>();
        if (permissions.contains("device:read")) {
            jdbc.query("SELECT model,COUNT(*) FROM devices WHERE tenant_id=? AND model IS NOT NULL GROUP BY model ORDER BY model LIMIT 200",
                (rs, n) -> { systemTypes.add(new RuleFlowModels.Option(rs.getString(1), rs.getString(1))); return null; }, tenant);
        }
        List<RuleFlowModels.PlaceOption> places = new ArrayList<>();
        if (permissions.contains("units:read")) {
            Map<String, String> parents = new HashMap<>(), names = new HashMap<>();
            jdbc.query("SELECT id,parent_place_id,name FROM places WHERE tenant_id=? ORDER BY name LIMIT 2000",
                (rs, n) -> { String id = rs.getString(1); parents.put(id, rs.getString(2)); names.put(id, rs.getString(3)); return null; }, tenant);
            for (var entry : parents.entrySet()) places.add(new RuleFlowModels.PlaceOption(entry.getKey(), names.get(entry.getKey()), path(parents, names, entry.getKey())));
            places.sort((left, right) -> left.path().compareTo(right.path()));
            if (places.size() > 500) places = places.subList(0, 500);
        }
        List<RuleFlowModels.DeviceOption> devices = new ArrayList<>();
        if (permissions.contains("device:read")) {
            jdbc.query("SELECT d.id,d.name,d.model,p.name,p.id FROM devices d LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id WHERE d.tenant_id=? ORDER BY d.name,d.id LIMIT 500",
                (rs, n) -> {
                    String id = rs.getString(1);
                    devices.add(new RuleFlowModels.DeviceOption(id, rs.getString(2), rs.getString(3), rs.getString(4),
                        scopes.contains(actor.user().id(), tenant, id)));
                    return null;
                }, tenant);
        }
        List<RuleFlowModels.ContactOption> contacts = new ArrayList<>();
        if (permissions.contains("notification-config:read")) {
            jdbc.query("SELECT id,name,phone_masked,enabled FROM notification_contacts WHERE tenant_id=? ORDER BY name,id LIMIT 500",
                (rs, n) -> { contacts.add(new RuleFlowModels.ContactOption(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))); return null; }, tenant);
        }
        List<RuleFlowModels.LinkageOption> linkageRules = new ArrayList<>();
        if (permissions.contains("resource-linkage:read")) {
            for (ResourceLinkageCommands.Rule rule : linkage.list(tenant)) {
                Object targets = rule.config().get("targets");
                boolean controllers = targets instanceof Map<?, ?> map && map.get("controllerIds") instanceof List<?> list && !list.isEmpty();
                linkageRules.add(new RuleFlowModels.LinkageOption(rule.id(), rule.name(), rule.kind(), rule.enabled(), controllers));
            }
        }
        List<RuleFlowModels.ApplicationOption> applications = new ArrayList<>();
        if (permissions.contains("applications:read")) {
            jdbc.query("SELECT a.id,a.name,a.status,"
                    + "(SELECT COUNT(*) FROM application_outputs o WHERE o.application_id=a.id AND o.enabled=TRUE),"
                    + "(SELECT COUNT(*) FROM application_outputs o WHERE o.application_id=a.id AND o.enabled=TRUE"
                    + " AND o.protocol='MQTT_TLS_3_1_1')"
                    + " FROM output_applications a WHERE a.tenant_id=? AND a.status='ACTIVE' ORDER BY a.name LIMIT 200",
                (rs, n) -> { applications.add(new RuleFlowModels.ApplicationOption(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getInt(4), rs.getInt(5))); return null; }, tenant);
        }
        List<RuleFlowModels.ChannelSupport> support = new ArrayList<>();
        for (String channel : List.of("SMS", "VOICE", "WECHAT"))
            support.add(new RuleFlowModels.ChannelSupport(channel, channels.supported().contains(channel), channels.rationale(channel)));
        return new RuleFlowModels.Catalog(RuleFlowCatalog.TYPES, List.copyOf(support), List.copyOf(systemTypes), List.copyOf(places),
            List.copyOf(devices), List.copyOf(contacts), List.copyOf(linkageRules), List.copyOf(applications),
            RuleFlowCatalog.LINKAGE_EVENT_CODES, RuleFlowCatalog.LINKAGE_ACTIONS, true);
    }

    private static String path(Map<String, String> parents, Map<String, String> names, String id) {
        LinkedHashSet<String> segments = new LinkedHashSet<>();
        String current = id;
        int guard = 0;
        while (current != null && guard++ < 32) {
            String name = names.get(current);
            if (name != null) segments.add(name);
            current = parents.get(current);
        }
        return String.join(" / ", new ArrayList<>(segments).reversed());
    }

    public RuleFlowModels.Flow flow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String compile = rs.getString("last_compile_json");
        return new RuleFlowModels.Flow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
            rs.getString("description"), rs.getString("status"), rs.getLong("revision"),
            rs.getObject("published_revision", Long.class), rs.getBoolean("enabled"),
            rs.getObject("last_compile_ok", Boolean.class),
            compile == null ? List.of() : decode(compile, new TypeReference<List<RuleFlowModels.Issue>>() { }),
            rs.getObject("last_compile_at", OffsetDateTime.class), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getString("updated_by"),
            rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("archived_at", OffsetDateTime.class),
            rs.getString("archived_reason"), rs.getString("definition_sha256"));
    }

    public RuleFlowModels.Definition parseDefinition(String source) {
        return RuleFlowDefinition.parse(decode(source, new TypeReference<Map<String, Object>>() { }));
    }

    public <T> T decode(String source, TypeReference<T> type) {
        try { return json.readValue(source, type); }
        catch (Exception failure) { throw new IllegalStateException("存储的规则流数据无法解析", failure); }
    }
}
