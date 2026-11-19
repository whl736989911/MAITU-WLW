package com.fireplatform.ruleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft lifecycle plus the preview/publish/enable orchestration. Drafts never reach a consumer; a publish
 * recompiles first and reports per-node errors without writing anything, then applies the plan atomically.
 */
@Service
public class RuleFlowCommands {
    private final JdbcTemplate jdbc;
    private final DatabaseTime time;
    private final IdempotencyStore idem;
    private final ExecutionAuthorization authorization;
    private final TenantFence tenants;
    private final AuditService audit;
    private final RuleFlowCompiler compiler;
    private final RuleFlowQueries queries;
    private final RuleFlowTransactions transactions;
    private final RuleFlowReports reports;
    private final ObjectMapper json;

    public RuleFlowCommands(JdbcTemplate jdbc, DatabaseTime time, IdempotencyStore idem, ExecutionAuthorization authorization,
            TenantFence tenants, AuditService audit, RuleFlowCompiler compiler, RuleFlowQueries queries,
            RuleFlowTransactions transactions, RuleFlowReports reports, ObjectMapper json) {
        this.jdbc = jdbc; this.time = time; this.idem = idem; this.authorization = authorization; this.tenants = tenants;
        this.audit = audit; this.compiler = compiler; this.queries = queries; this.transactions = transactions; this.reports = reports;
        this.json = json;
    }

    @Transactional
    public RuleFlowModels.FlowDetail create(AuthService.Identity actor, String tenant, String name, String description,
            Map<String, Object> rawDefinition, String reason, String requestId, String key) {
        authorization.lockRequest(actor, tenant, List.of("rules:write"));
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String trimmed = RuleFlowTransactions.requireReason(reason);
        RuleFlowModels.Definition definition = RuleFlowDefinition.parse(rawDefinition);
        String flowName = name(name);
        String text = description(description);
        String hash = idem.fingerprint(Arrays.asList(tenant, flowName, text, RuleFlowDefinition.encode(definition), trimmed));
        RuleFlowModels.FlowDetail replay = idem.replay(actor.user().id(), "rule-flow-create", key, hash, RuleFlowModels.FlowDetail.class);
        if (replay != null) return replay;
        assertNameFree(tenant, flowName, null);
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = time.now();
        jdbc.update("INSERT INTO rule_flows(id,tenant_id,name,description,status,definition_json,definition_sha256,revision,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,'DRAFT',?,?,0,?,?,?,?)",
            id, tenant, flowName, text, encode(RuleFlowDefinition.encode(definition)), RuleFlowDefinition.definitionSha256(definition),
            actor.user().id(), now, actor.user().id(), now);
        event(tenant, id, "CREATE", null, 0, actor.user().id(), trimmed, Map.of("name", flowName));
        audit.recordReason(actor.user().id(), tenant, "RULE_FLOW_CREATED", id, "SUCCESS", requestId, trimmed);
        RuleFlowModels.FlowDetail result = queries.detail(tenant, id);
        idem.save(actor.user().id(), "rule-flow-create", key, hash, result);
        return result;
    }

    @Transactional
    public RuleFlowModels.FlowDetail save(AuthService.Identity actor, String tenant, String id, long expectedRevision,
            String name, String description, Map<String, Object> rawDefinition, String reason, String requestId, String key) {
        authorization.lockRequest(actor, tenant, List.of("rules:write"));
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String trimmed = RuleFlowTransactions.requireReason(reason);
        RuleFlowModels.Definition definition = RuleFlowDefinition.parse(rawDefinition);
        String flowName = name(name);
        String text = description(description);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, expectedRevision, flowName, text,
            RuleFlowDefinition.encode(definition), trimmed));
        RuleFlowModels.FlowDetail replay = idem.replay(actor.user().id(), "rule-flow-save", key, hash, RuleFlowModels.FlowDetail.class);
        if (replay != null) return replay;
        var rows = jdbc.query("SELECT revision,status FROM rule_flows WHERE tenant_id=? AND id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        if (((Number) rows.getFirst()[0]).longValue() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "规则流草稿已变化，请刷新");
        if ("ARCHIVED".equals(rows.getFirst()[1])) throw ApiError.conflict("FLOW_ARCHIVED", "已归档规则流不能编辑");
        assertNameFree(tenant, flowName, id);
        jdbc.update("UPDATE rule_flows SET name=?,description=?,definition_json=?,definition_sha256=?,revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND id=?",
            flowName, text, encode(RuleFlowDefinition.encode(definition)), RuleFlowDefinition.definitionSha256(definition),
            actor.user().id(), time.now(), tenant, id);
        event(tenant, id, "DRAFT_SAVED", expectedRevision, expectedRevision + 1, actor.user().id(), trimmed, Map.of("name", flowName));
        audit.recordReason(actor.user().id(), tenant, "RULE_FLOW_DRAFT_SAVED", id, "SUCCESS", requestId, trimmed);
        RuleFlowModels.FlowDetail result = queries.detail(tenant, id);
        idem.save(actor.user().id(), "rule-flow-save", key, hash, result);
        return result;
    }

    @Transactional
    public RuleFlowModels.FlowDetail archive(AuthService.Identity actor, String tenant, String id, long expectedRevision,
            String reason, String requestId, String key) {
        authorization.lockRequest(actor, tenant, List.of("rules:write"));
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String trimmed = RuleFlowTransactions.requireReason(reason);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, expectedRevision, trimmed));
        RuleFlowModels.FlowDetail replay = idem.replay(actor.user().id(), "rule-flow-archive", key, hash, RuleFlowModels.FlowDetail.class);
        if (replay != null) return replay;
        var rows = jdbc.query("SELECT revision,enabled,status FROM rule_flows WHERE tenant_id=? AND id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2), rs.getString(3)}, tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        if (((Number) rows.getFirst()[0]).longValue() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "规则流已变化，请刷新");
        if ("ARCHIVED".equals(rows.getFirst()[2])) throw ApiError.conflict("FLOW_ARCHIVED", "规则流已归档");
        if (Boolean.TRUE.equals(rows.getFirst()[1])) throw ApiError.conflict("FLOW_ENABLED", "请先停用规则流再归档，避免留下已启用的消费方策略");
        OffsetDateTime now = time.now();
        jdbc.update("UPDATE rule_flows SET status='ARCHIVED',enabled=FALSE,archived_by=?,archived_at=?,archived_reason=?,revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND id=?",
            actor.user().id(), now, trimmed, actor.user().id(), now, tenant, id);
        event(tenant, id, "ARCHIVE", expectedRevision, expectedRevision + 1, actor.user().id(), trimmed, Map.of());
        audit.recordReason(actor.user().id(), tenant, "RULE_FLOW_ARCHIVED", id, "SUCCESS", requestId, trimmed);
        RuleFlowModels.FlowDetail result = queries.detail(tenant, id);
        idem.save(actor.user().id(), "rule-flow-archive", key, hash, result);
        return result;
    }

    /** Read-only: computes the exact plan and diffs for the current draft without changing anything. */
    public RuleFlowInputs.PreviewResponse preview(AuthService.Identity actor, String tenant, String id, long expectedRevision) {
        RuleFlowModels.Flow flow = queries.flow(tenant, id);
        if (flow.revision() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "规则流草稿已变化，请刷新后再预览");
        RuleFlowModels.Definition definition = queries.parseDefinition(definitionJson(tenant, id));
        Map<String, RuleFlowModels.Binding> bindings = flow.publishedRevision() == null
            ? Map.of() : queries.bindings(tenant, id, flow.publishedRevision());
        RuleFlowModels.Plan plan = compiler.compile(actor, tenant, id, flow.name(), definition, expectedRevision,
            compiler.observe(tenant, bindings));
        return new RuleFlowInputs.PreviewResponse(plan, List.copyOf(bindings.values()));
    }

    /** Compiles first (reporting per-node errors without any write), then applies the plan atomically. */
    public RuleFlowInputs.PublishResponse publish(AuthService.Identity actor, String tenant, String id, long expectedRevision,
            String previewHash, String reason, String requestId, String key) {
        RuleFlowModels.Flow flow = queries.flow(tenant, id);
        if (flow.revision() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "规则流草稿已变化，请刷新");
        if ("ARCHIVED".equals(flow.status())) throw ApiError.conflict("FLOW_ARCHIVED", "已归档规则流不能发布");
        RuleFlowModels.Definition definition = queries.parseDefinition(definitionJson(tenant, id));
        Map<String, RuleFlowModels.Binding> bindings = flow.publishedRevision() == null
            ? Map.of() : queries.bindings(tenant, id, flow.publishedRevision());
        RuleFlowModels.Plan preview = compiler.compile(actor, tenant, id, flow.name(), definition, expectedRevision,
            compiler.observe(tenant, bindings));
        if (!preview.ok()) {
            reports.record(tenant, id, expectedRevision, preview.planHash(), false, preview.issues(), actor.user().id());
            return new RuleFlowInputs.PublishResponse(false, flow, null, preview, List.of(), preview.issues());
        }
        List<String> rights = RuleFlowTransactions.rights(preview, "rules:publish");
        try {
            RuleFlowTransactions.Result result = transactions.publish(actor, tenant, id, expectedRevision, previewHash,
                rights, reason, requestId, key);
            return new RuleFlowInputs.PublishResponse(true, queries.flow(tenant, id), result.revision(), result.plan(),
                result.bindings(), List.of());
        } catch (RuleFlowTransactions.CompileRejected rejected) {
            reports.record(tenant, id, rejected.draftRevision(), rejected.planHash(), false, rejected.issues(), actor.user().id());
            return new RuleFlowInputs.PublishResponse(false, flow, null, preview, List.of(), rejected.issues());
        }
    }

    public RuleFlowInputs.PublishResponse setEnabled(AuthService.Identity actor, String tenant, String id, long expectedRevision,
            boolean enabled, String reason, String requestId, String key) {
        RuleFlowTransactions.Result result = transactions.activate(actor, tenant, id, expectedRevision, enabled, reason, requestId, key);
        return new RuleFlowInputs.PublishResponse(true, queries.flow(tenant, id), result.revision(), result.plan(),
            result.bindings(), List.of());
    }

    private String definitionJson(String tenant, String id) {
        var rows = jdbc.queryForList("SELECT definition_json FROM rule_flows WHERE tenant_id=? AND id=?", tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return String.valueOf(rows.getFirst().get("definition_json"));
    }

    private void assertNameFree(String tenant, String name, String self) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rule_flows WHERE tenant_id=? AND name=? AND id<>?",
            Long.class, tenant, name, self == null ? "" : self);
        if (count != null && count > 0) throw ApiError.conflict("FLOW_NAME_EXISTS", "本单位已存在同名规则流");
    }

    private void event(String tenant, String flowId, String action, Long before, long after, String actor, String reason, Map<String, Object> detail) {
        String payload;
        try { payload = json.writeValueAsString(detail); }
        catch (Exception failure) { throw new IllegalStateException("规则流事件无法序列化", failure); }
        jdbc.update("INSERT INTO rule_flow_events(id,tenant_id,flow_id,action,revision_before,revision_after,actor_id,reason,detail_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), tenant, flowId, action, before, after, actor, reason, payload, time.now());
    }

    private String encode(Object value) {
        return RuleFlowDefinition.canonical(value);
    }

    private static String name(String name) {
        if (name == null || name.isBlank() || name.length() > RuleFlowDefinition.MAX_NAME) throw ApiError.bad("规则流名称必须为1至120个字符");
        return name.trim();
    }

    private static String description(String description) {
        if (description == null) return "";
        if (description.length() > RuleFlowDefinition.MAX_DESCRIPTION) throw ApiError.bad("规则流说明超过1000个字符");
        return description.trim();
    }

}
