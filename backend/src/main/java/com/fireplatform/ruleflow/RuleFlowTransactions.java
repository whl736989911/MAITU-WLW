package com.fireplatform.ruleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.alarmgroup.AlarmMergePolicyCommands;
import com.fireplatform.alarmgroup.AlarmMergePolicyQueries;
import com.fireplatform.configuration.AlarmBaseConfigurationCommands;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.notification.NotificationConfigCommands;
import com.fireplatform.notification.NotificationInputs;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a compile plan to the existing typed consumers inside one transaction. Every consumer call goes through
 * its own public command service, so validation, audit and revision rules are the domain's, not a parallel copy.
 * Any drift, conflict or consumer failure aborts everything: no partial policy is ever left behind.
 */
@Service
public class RuleFlowTransactions {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DatabaseTime time;
    private final IdempotencyStore idem;
    private final ExecutionAuthorization authorization;
    private final TenantFence tenants;
    private final AuditService audit;
    private final RuleFlowCompiler compiler;
    private final RuleFlowQueries queries;
    private final AlarmBaseConfigurationCommands baseCommands;
    private final AlarmBaseConfigurationQueries baseQueries;
    private final AlarmMergePolicyCommands mergeCommands;
    private final AlarmMergePolicyQueries mergeQueries;
    private final NotificationConfigCommands notifications;

    public RuleFlowTransactions(JdbcTemplate jdbc, ObjectMapper json, DatabaseTime time, IdempotencyStore idem,
            ExecutionAuthorization authorization, TenantFence tenants, AuditService audit, RuleFlowCompiler compiler,
            RuleFlowQueries queries, AlarmBaseConfigurationCommands baseCommands, AlarmBaseConfigurationQueries baseQueries,
            AlarmMergePolicyCommands mergeCommands, AlarmMergePolicyQueries mergeQueries, NotificationConfigCommands notifications) {
        this.jdbc = jdbc; this.json = json; this.time = time; this.idem = idem; this.authorization = authorization;
        this.tenants = tenants; this.audit = audit; this.compiler = compiler; this.queries = queries;
        this.baseCommands = baseCommands; this.baseQueries = baseQueries; this.mergeCommands = mergeCommands;
        this.mergeQueries = mergeQueries; this.notifications = notifications;
    }

    /** Thrown from inside the transaction so nothing is written, then recorded and reported outside it. */
    public static final class CompileRejected extends RuntimeException {
        private final transient List<RuleFlowModels.Issue> issues;
        private final String planHash;
        private final long draftRevision;

        CompileRejected(List<RuleFlowModels.Issue> issues, String planHash, long draftRevision) {
            super("规则流编译未通过");
            this.issues = List.copyOf(issues); this.planHash = planHash; this.draftRevision = draftRevision;
        }

        public List<RuleFlowModels.Issue> issues() { return issues; }
        public String planHash() { return planHash; }
        public long draftRevision() { return draftRevision; }
    }

    public record Result(long revision, RuleFlowModels.Plan plan, List<RuleFlowModels.Binding> bindings) { }

    private record FlowRow(String id, String tenantId, String name, String description, String status,
                           String definitionJson, String definitionSha256, long revision, Long publishedRevision,
                           boolean enabled) { }

    @Transactional
    public Result publish(AuthService.Identity actor, String tenant, String flowId, long expectedRevision,
            String previewHash, List<String> rights, String reason, String requestId, String key) {
        authorization.lockRequest(actor, tenant, rights);
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String trimmed = requireReason(reason);
        String hash = idem.fingerprint(Arrays.asList(tenant, flowId, expectedRevision, previewHash, trimmed));
        Result replay = idem.replay(actor.user().id(), "rule-flow-publish", key, hash, Result.class);
        if (replay != null) return replay;

        FlowRow flow = lockFlow(tenant, flowId);
        if (flow.revision() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "规则流草稿已变化，请刷新");
        if ("ARCHIVED".equals(flow.status())) throw ApiError.conflict("FLOW_ARCHIVED", "已归档规则流不能发布");
        RuleFlowModels.Definition definition = queries.parseDefinition(flow.definitionJson());
        Map<String, RuleFlowModels.Binding> bindings = flow.publishedRevision() == null
            ? Map.of() : queries.bindings(tenant, flowId, flow.publishedRevision());
        RuleFlowModels.Plan plan = compiler.compile(actor, tenant, flowId, flow.name(), definition, expectedRevision,
            compiler.observe(tenant, bindings));
        if (!plan.ok()) throw new CompileRejected(plan.issues(), plan.planHash(), expectedRevision);
        if (!plan.planHash().equals(previewHash)) throw ApiError.conflict("PREVIEW_CHANGED", "影响预览与当前数据不一致，请重新预览后再发布");
        List<String> observedRights = rights(plan, "rules:publish");
        if (!actor.permissions().containsAll(observedRights)) throw ApiError.forbidden("编译所需的下层领域写入权限已变化，请重新核验");
        assertOwnership(tenant, flowId, plan);

        long revision = flow.publishedRevision() == null ? 1 : flow.publishedRevision() + 1;
        OffsetDateTime now = time.now();
        // The immutable revision row exists first: consumer bindings carry a foreign key to it.
        jdbc.update("INSERT INTO rule_flow_revisions(flow_id,tenant_id,revision,definition_json,definition_sha256,plan_hash,plan_json,reason,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            flowId, tenant, revision, flow.definitionJson(), flow.definitionSha256(), plan.planHash(), encode(plan),
            trimmed, actor.user().id(), now);
        List<RuleFlowModels.Binding> written = apply(actor, tenant, flowId, flow.name(), revision, plan, false, bindings, trimmed, requestId);
        jdbc.update("UPDATE rule_flows SET status='PUBLISHED',published_revision=?,enabled=FALSE,last_compile_ok=TRUE,last_compile_json='[]',last_compile_at=?,updated_by=?,updated_at=? WHERE tenant_id=? AND id=?",
            revision, now, actor.user().id(), now, tenant, flowId);
        event(tenant, flowId, "PUBLISH", flow.publishedRevision(), revision, actor.user().id(), trimmed,
            Map.of("plan_hash", plan.planHash(), "draft_revision", expectedRevision, "consumers", written.size()));
        audit.recordReason(actor.user().id(), tenant, "RULE_FLOW_PUBLISHED", flowId, "SUCCESS", requestId, trimmed);
        Result result = new Result(revision, plan, written);
        idem.save(actor.user().id(), "rule-flow-publish", key, hash, result);
        return result;
    }

    @Transactional
    public Result activate(AuthService.Identity actor, String tenant, String flowId, long expectedRevision,
            boolean enabled, String reason, String requestId, String key) {
        // Read the published plan first so the required underlying rights are known before any row lock is taken.
        FlowRow snapshot = readFlow(tenant, flowId);
        if (snapshot.publishedRevision() == null) throw ApiError.conflict("NOT_PUBLISHED", "规则流尚未发布，不能启用");
        RuleFlowModels.Plan plan = queries.revision(tenant, flowId, snapshot.publishedRevision()).plan();
        authorization.lockRequest(actor, tenant, rights(plan, "rules:publish"));
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String trimmed = requireReason(reason);
        String hash = idem.fingerprint(Arrays.asList(tenant, flowId, expectedRevision, enabled, trimmed));
        Result replay = idem.replay(actor.user().id(), enabled ? "rule-flow-enable" : "rule-flow-disable", key, hash, Result.class);
        if (replay != null) return replay;
        FlowRow flow = lockFlow(tenant, flowId);
        if (flow.revision() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "规则流已变化，请刷新");
        if (flow.publishedRevision() == null || !flow.publishedRevision().equals(snapshot.publishedRevision()))
            throw ApiError.conflict("REVISION_CONFLICT", "规则流发布版本已变化，请刷新");
        if ("ARCHIVED".equals(flow.status())) throw ApiError.conflict("FLOW_ARCHIVED", "已归档规则流不能启用");
        Map<String, RuleFlowModels.Binding> bindings = queries.bindings(tenant, flowId, flow.publishedRevision());
        boolean changesState = flow.enabled() != enabled;
        assertActivationBindings(tenant, bindings, changesState);
        if (!changesState) {
            Result unchanged = new Result(flow.publishedRevision(), plan, List.copyOf(bindings.values()));
            idem.save(actor.user().id(), enabled ? "rule-flow-enable" : "rule-flow-disable", key, hash, unchanged);
            return unchanged;
        }
        List<RuleFlowModels.Binding> written = apply(actor, tenant, flowId, flow.name(), flow.publishedRevision(), plan, enabled,
            bindings, trimmed, requestId);
        OffsetDateTime now = time.now();
        jdbc.update("UPDATE rule_flows SET enabled=?,updated_by=?,updated_at=? WHERE tenant_id=? AND id=?",
            enabled, actor.user().id(), now, tenant, flowId);
        event(tenant, flowId, enabled ? "ENABLE" : "DISABLE", flow.revision(), flow.revision(), actor.user().id(), trimmed,
            Map.of("published_revision", flow.publishedRevision(), "consumers", written.size()));
        audit.recordReason(actor.user().id(), tenant, enabled ? "RULE_FLOW_ENABLED" : "RULE_FLOW_DISABLED", flowId, "SUCCESS", requestId, trimmed);
        Result result = new Result(flow.publishedRevision(), plan, written);
        idem.save(actor.user().id(), enabled ? "rule-flow-enable" : "rule-flow-disable", key, hash, result);
        return result;
    }

    /** Consumer CAS revisions distinguish publication and successive toggles within one published flow revision. */
    private List<RuleFlowModels.Binding> apply(AuthService.Identity actor, String tenant, String flowId, String flowName,
            long flowRevision, RuleFlowModels.Plan plan, boolean active, Map<String, RuleFlowModels.Binding> existing,
            String reason, String requestId) {
        List<RuleFlowModels.Binding> written = new ArrayList<>();
        if (!plan.ownedFields().isEmpty()) {
            long current = currentBaseRevision(tenant);
            RuleFlowModels.Binding binding = existing.get(RuleFlowCompiler.bindingKey("ALARM_BASE_CONFIGURATION", "unit"));
            if (binding != null && binding.consumerRevision() != current)
                throw ApiError.conflict("DEPENDENCY_DRIFT", "报警基础配置已在规则流之外被修改，规则流不会静默覆盖");
            String key = "ruleflow:" + flowId + ":" + flowRevision + ":" + current + ":base";
            Map<String, Object> value = baseValue(plan, active);
            baseCommands.save(actor, tenant, key, current, reason, value, requestId);
            written.add(new RuleFlowModels.Binding("ALARM_BASE_CONFIGURATION", "unit", tenant, currentBaseRevision(tenant),
                List.copyOf(plan.ownedFields()), time.now()));
        }
        if (plan.merge() != null) {
            long current = mergeQueries.policy(tenant).revision();
            RuleFlowModels.Binding binding = existing.get(RuleFlowCompiler.bindingKey("ALARM_MERGE_POLICY", "unit"));
            if (binding != null && binding.consumerRevision() != current)
                throw ApiError.conflict("DEPENDENCY_DRIFT", "报警归并策略已在规则流之外被修改，规则流不会静默覆盖");
            String key = "ruleflow:" + flowId + ":" + flowRevision + ":" + current + ":merge";
            mergeCommands.save(actor, tenant, key, current, active && plan.merge().enabled(),
                plan.merge().windowSeconds(), reason, requestId);
            written.add(new RuleFlowModels.Binding("ALARM_MERGE_POLICY", "unit", tenant, mergeQueries.policy(tenant).revision(),
                List.of("enabled", "window_seconds"), time.now()));
        }
        for (RuleFlowModels.RuleTarget target : plan.notificationRules()) {
            String scope = target.eventScope();
            RuleFlowModels.Binding binding = existing.get(RuleFlowCompiler.bindingKey("NOTIFICATION_RULE", scope));
            Long expectedRevision = null;
            if (binding != null) {
                var rows = jdbc.query("SELECT revision FROM notification_rules WHERE id=? AND tenant_id=? AND legacy_mode IS NULL AND scope_type='UNIT'",
                    (rs, n) -> rs.getLong(1), binding.consumerId(), tenant);
                if (rows.isEmpty()) throw ApiError.conflict("DEPENDENCY_MISSING", "绑定的通知规则已不存在，请重新发布");
                if (rows.getFirst() != binding.consumerRevision())
                    throw ApiError.conflict("DEPENDENCY_DRIFT", scope + " 通知规则已在规则流之外被修改，规则流不会静默覆盖");
                expectedRevision = rows.getFirst();
            }
            List<NotificationInputs.Receiver> receivers = new ArrayList<>();
            Long smsMinutes = null, voiceMinutes = null;
            for (RuleFlowModels.ChannelTarget channel : target.channels()) {
                for (Map<String, Object> receiver : channel.receivers())
                    receivers.add(new NotificationInputs.Receiver(channel.channel(), String.valueOf(receiver.get("contactId")),
                        ((Number) receiver.get("level")).longValue()));
                if ("SMS".equals(channel.channel())) smsMinutes = channel.escalationMinutes();
                if ("VOICE".equals(channel.channel())) voiceMinutes = channel.escalationMinutes();
            }
            String ruleName = ruleName(flowName, scope);
            NotificationInputs.Rule input = new NotificationInputs.Rule(ruleName, active, scope, receivers,
                smsMinutes == null ? 0L : smsMinutes, voiceMinutes == null ? 0L : voiceMinutes, null,
                expectedRevision, reason);
            String key = "ruleflow:" + flowId + ":" + flowRevision + ":" + expectedRevision + ":rule:" + scope.toLowerCase();
            Map<String, Object> saved = notifications.rule(actor, tenant, binding == null ? null : binding.consumerId(),
                key, input, requestId);
            String consumerId = String.valueOf(saved.get("id"));
            long revision = jdbc.queryForObject("SELECT revision FROM notification_rules WHERE id=? AND tenant_id=? AND legacy_mode IS NULL AND scope_type='UNIT'",
                Long.class, consumerId, tenant);
            written.add(new RuleFlowModels.Binding("NOTIFICATION_RULE", scope, consumerId, revision,
                List.of("enabled", "event_scope", "receivers", "sms_escalation_interval_seconds", "voice_escalation_interval_seconds"),
                time.now()));
        }
        for (RuleFlowModels.Reference reference : plan.references()) {
            RuleFlowModels.Binding binding = new RuleFlowModels.Binding(reference.consumerKind(),
                reference.consumerKind() + ":" + reference.consumerId(), reference.consumerId(),
                reference.consumerRevision(), List.of("reference"), time.now());
            written.add(binding);
        }
        deactivateOrphans(actor, tenant, flowId, flowRevision, plan, existing, written, reason, requestId);
        for (RuleFlowModels.Binding binding : written) persistBinding(tenant, flowId, flowRevision, binding);
        return List.copyOf(written);
    }

    /**
     * A consumer this flow owned before but no longer writes must not stay active: the flow is the single truth for
     * the field groups it owns, so a removed node really turns that part off instead of leaving a live policy.
     */
    private void deactivateOrphans(AuthService.Identity actor, String tenant, String flowId, long flowRevision,
            RuleFlowModels.Plan plan, Map<String, RuleFlowModels.Binding> existing, List<RuleFlowModels.Binding> written,
            String reason, String requestId) {
        java.util.Set<String> touched = new LinkedHashSet<>();
        if (!plan.ownedFields().isEmpty()) touched.add("ALARM_BASE_CONFIGURATION:unit");
        if (plan.merge() != null) touched.add("ALARM_MERGE_POLICY:unit");
        for (RuleFlowModels.RuleTarget target : plan.notificationRules()) touched.add("NOTIFICATION_RULE:" + target.eventScope());
        for (RuleFlowModels.Binding binding : existing.values()) {
            String key = binding.consumerKind() + ":" + binding.scopeKey();
            if (touched.contains(key)) continue;
            switch (binding.consumerKind()) {
                case "ALARM_BASE_CONFIGURATION" -> {
                    long current = currentBaseRevision(tenant);
                    Map<String, Object> value = new LinkedHashMap<>();
                    Map<String, Object> stored = new LinkedHashMap<>(baseQueries.configuration(tenant).value());
                    value.putAll(stored);
                    for (String field : List.of("subscriptions", "pushRules", "voiceBroadcast", "silenceIntervals", "unitService")) {
                        Object existingField = stored.get(field);
                        if (existingField == null) existingField = AlarmBaseConfigurationQueries.defaults().get(field);
                        value.put(field, existingField);
                    }
                    if (binding.ownedFields().contains("voiceBroadcast")) {
                        Map<String, Object> voice = new LinkedHashMap<>(asMap(value.get("voiceBroadcast")));
                        voice.put("alarmEnabled", false);
                        voice.put("faultEnabled", false);
                        voice.put("alarmTypes", List.of());
                        voice.put("faultTypes", List.of());
                        value.put("voiceBroadcast", voice);
                    }
                    if (binding.ownedFields().contains("unitService"))
                        value.put("unitService", Map.of("receiveAlarm", false, "pushMessage", false));
                    baseCommands.save(actor, tenant, "ruleflow:" + flowId + ":" + flowRevision + ":" + current + ":base:off",
                        current, reason, value, requestId);
                    written.add(new RuleFlowModels.Binding("ALARM_BASE_CONFIGURATION", "unit", tenant, currentBaseRevision(tenant),
                        List.of("unitService"), time.now()));
                }
                case "ALARM_MERGE_POLICY" -> {
                    var policy = mergeQueries.policy(tenant);
                    mergeCommands.save(actor, tenant, "ruleflow:" + flowId + ":" + flowRevision + ":" + policy.revision() + ":merge:off",
                        policy.revision(), false, policy.windowSeconds(), reason, requestId);
                    written.add(new RuleFlowModels.Binding("ALARM_MERGE_POLICY", "unit", tenant, mergeQueries.policy(tenant).revision(),
                        List.of("enabled"), time.now()));
                }
                case "NOTIFICATION_RULE" -> {
                    var rows = jdbc.query("SELECT revision,name,event_scope,sms_escalation_interval_seconds,voice_escalation_interval_seconds FROM notification_rules WHERE id=? AND tenant_id=? AND legacy_mode IS NULL AND scope_type='UNIT'",
                        (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, Integer.class), rs.getObject(5, Integer.class)},
                        binding.consumerId(), tenant);
                    if (rows.isEmpty()) continue;
                    Object[] row = rows.getFirst();
                    if (((Number) row[0]).longValue() != binding.consumerRevision())
                        throw ApiError.conflict("DEPENDENCY_DRIFT", "通知规则已在规则流之外被修改，无法安全停用；请先在通知页面核对");
                    List<NotificationInputs.Receiver> receivers = new ArrayList<>();
                    jdbc.query("""
                        SELECT rr.channel,rr.contact_id,rr.level FROM notification_rule_receivers rr
                        JOIN notification_rules r ON r.tenant_id=rr.tenant_id AND r.id=rr.rule_id
                        WHERE rr.tenant_id=? AND rr.rule_id=? AND r.legacy_mode IS NULL AND r.scope_type='UNIT'
                        ORDER BY rr.channel,rr.level,rr.contact_id
                        """, (rs, n) -> { receivers.add(new NotificationInputs.Receiver(rs.getString(1), rs.getString(2), rs.getLong(3))); return null; },
                        tenant, binding.consumerId());
                    if (receivers.isEmpty()) continue;
                    Integer smsSeconds = (Integer) row[3], voiceSeconds = (Integer) row[4];
                    NotificationInputs.Rule input = new NotificationInputs.Rule(String.valueOf(row[1]), false, String.valueOf(row[2]),
                        receivers, smsSeconds == null ? 0L : smsSeconds / 60L, voiceSeconds == null ? 0L : voiceSeconds / 60L, null,
                        ((Number) row[0]).longValue(), reason);
                    notifications.rule(actor, tenant, binding.consumerId(),
                        "ruleflow:" + flowId + ":" + flowRevision + ":" + row[0] + ":rule:off:" + binding.scopeKey().toLowerCase(), input, requestId);
                    long revision = jdbc.queryForObject("SELECT revision FROM notification_rules WHERE id=? AND tenant_id=? AND legacy_mode IS NULL AND scope_type='UNIT'",
                        Long.class, binding.consumerId(), tenant);
                    written.add(new RuleFlowModels.Binding("NOTIFICATION_RULE", binding.scopeKey(), binding.consumerId(), revision,
                        List.of("enabled"), time.now()));
                }
                default -> { }
            }
        }
    }

    private void persistBinding(String tenant, String flowId, long flowRevision, RuleFlowModels.Binding binding) {
        int updated = jdbc.update("UPDATE rule_flow_consumer_bindings SET consumer_id=?,consumer_revision=?,owned_fields_json=?,updated_at=? WHERE flow_id=? AND flow_revision=? AND consumer_kind=? AND scope_key=?",
            binding.consumerId(), binding.consumerRevision(), encode(binding.ownedFields()), time.now(),
            flowId, flowRevision, binding.consumerKind(), binding.scopeKey());
        if (updated == 0)
            jdbc.update("INSERT INTO rule_flow_consumer_bindings(flow_id,tenant_id,flow_revision,consumer_kind,scope_key,consumer_id,consumer_revision,owned_fields_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                flowId, tenant, flowRevision, binding.consumerKind(), binding.scopeKey(), binding.consumerId(),
                binding.consumerRevision(), encode(binding.ownedFields()), time.now(), time.now());
    }

    /** Full alarm base configuration with unowned fields preserved; inactive disables only what the flow owns. */
    private Map<String, Object> baseValue(RuleFlowModels.Plan plan, boolean active) {
        Map<String, Object> defaults = AlarmBaseConfigurationQueries.defaults();
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String field : List.of("subscriptions", "pushRules", "voiceBroadcast", "unitService", "silenceIntervals")) {
            Object value = plan.baseConfiguration().get(field);
            merged.put(field, value == null ? defaults.get(field) : value);
        }
        if (!active) {
            if (plan.ownedFields().contains("voiceBroadcast")) {
                Map<String, Object> voice = new LinkedHashMap<>(asMap(merged.get("voiceBroadcast")));
                voice.put("alarmEnabled", false);
                voice.put("faultEnabled", false);
                voice.put("alarmTypes", List.of());
                voice.put("faultTypes", List.of());
                merged.put("voiceBroadcast", voice);
            }
            if (plan.ownedFields().contains("unitService")) {
                merged.put("unitService", Map.of("receiveAlarm", false, "pushMessage", false));
            }
        }
        return merged;
    }

    private void assertOwnership(String tenant, String flowId, RuleFlowModels.Plan plan) {
        Set<String> mine = new LinkedHashSet<>();
        if (!plan.ownedFields().isEmpty()) mine.add("ALARM_BASE_CONFIGURATION:unit");
        if (plan.merge() != null) mine.add("ALARM_MERGE_POLICY:unit");
        for (RuleFlowModels.RuleTarget target : plan.notificationRules()) mine.add("NOTIFICATION_RULE:" + target.eventScope());
        if (mine.isEmpty()) return;
        var rows = jdbc.query("SELECT b.consumer_kind,b.scope_key,f.name FROM rule_flow_consumer_bindings b JOIN rule_flows f ON f.id=b.flow_id AND f.tenant_id=b.tenant_id WHERE b.tenant_id=? AND b.flow_revision=f.published_revision AND f.id<>? AND f.status<>'ARCHIVED'",
            (rs, n) -> new String[]{rs.getString(1) + ":" + rs.getString(2), rs.getString(3)}, tenant, flowId);
        for (String[] row : rows) {
            if (!mine.contains(row[0])) continue;
            throw ApiError.conflict("CONSUMER_CONFLICT", "消费者 " + row[0] + " 已由规则流「" + row[1] + "」持有；一个消费方策略只能有一个规则流作为真相来源");
        }
    }

    /** A state change requires exact consumer revisions; even an unchanged result must not return a non-UNIT rule as live. */
    private void assertActivationBindings(String tenant, Map<String, RuleFlowModels.Binding> bindings, boolean changesState) {
        for (RuleFlowModels.Binding binding : bindings.values()) {
            if (!changesState && !"NOTIFICATION_RULE".equals(binding.consumerKind())) continue;
            long current = switch (binding.consumerKind()) {
                case "ALARM_BASE_CONFIGURATION" -> currentBaseRevision(tenant);
                case "ALARM_MERGE_POLICY" -> mergeQueries.policy(tenant).revision();
                case "NOTIFICATION_RULE" -> {
                    var rows = jdbc.query("SELECT revision FROM notification_rules WHERE id=? AND tenant_id=? AND legacy_mode IS NULL AND scope_type='UNIT'",
                        (rs, n) -> rs.getLong(1), binding.consumerId(), tenant);
                    yield rows.isEmpty() ? -1 : rows.getFirst();
                }
                case "RESOURCE_LINKAGE" -> {
                    var rows = jdbc.query("SELECT revision FROM resource_linkage_rules WHERE id=? AND tenant_id=? AND deleted=FALSE",
                        (rs, n) -> rs.getLong(1), binding.consumerId(), tenant);
                    yield rows.isEmpty() ? -1 : rows.getFirst();
                }
                case "OUTPUT_APPLICATION" -> {
                    var rows = jdbc.query("SELECT revision FROM output_applications WHERE id=? AND tenant_id=?",
                        (rs, n) -> rs.getLong(1), binding.consumerId(), tenant);
                    yield rows.isEmpty() ? -1 : rows.getFirst();
                }
                default -> binding.consumerRevision();
            };
            if (current < 0 || (changesState && current != binding.consumerRevision()))
                throw ApiError.conflict("DEPENDENCY_DRIFT", binding.consumerKind() + "(" + binding.scopeKey()
                    + ") 已在规则流之外被修改（绑定修订 " + binding.consumerRevision() + "，当前 " + current + "）；请重新发布前先核对");
        }
    }

    private long currentBaseRevision(String tenant) {
        var rows = jdbc.query("SELECT revision FROM alarm_base_configurations WHERE tenant_id=?", (rs, n) -> rs.getLong(1), tenant);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private FlowRow readFlow(String tenant, String flowId) {
        var rows = jdbc.query("SELECT id,tenant_id,name,description,status,definition_json,definition_sha256,revision,published_revision,enabled FROM rule_flows WHERE tenant_id=? AND id=?",
            (rs, n) -> new FlowRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getLong(8), rs.getObject(9, Long.class), rs.getBoolean(10)), tenant, flowId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private FlowRow lockFlow(String tenant, String flowId) {
        var rows = jdbc.query("SELECT id,tenant_id,name,description,status,definition_json,definition_sha256,revision,published_revision,enabled FROM rule_flows WHERE tenant_id=? AND id=? FOR UPDATE",
            (rs, n) -> new FlowRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getLong(8), rs.getObject(9, Long.class), rs.getBoolean(10)), tenant, flowId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    static List<String> rights(RuleFlowModels.Plan plan, String primary) {
        List<String> rights = new ArrayList<>();
        rights.add(primary);
        if (!plan.ownedFields().isEmpty() || plan.merge() != null) rights.add("alarm-base:write");
        if (!plan.notificationRules().isEmpty()) { rights.add("notification-config:write"); rights.add("notification-config:read"); }
        return rights;
    }

    static String ruleName(String flowName, String scope) {
        String suffix = "FIRE".equals(scope) ? "火灾报警" : "设备故障";
        String name = flowName + " · " + suffix;
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw ApiError.bad("必须填写操作原因");
        return reason.trim();
    }

    private void event(String tenant, String flowId, String action, Long before, long after, String actor, String reason, Map<String, Object> detail) {
        jdbc.update("INSERT INTO rule_flow_events(id,tenant_id,flow_id,action,revision_before,revision_after,actor_id,reason,detail_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), tenant, flowId, action, before, after, actor, reason, encode(detail), time.now());
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("规则流数据无法序列化", failure); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) for (var entry : source.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
}
