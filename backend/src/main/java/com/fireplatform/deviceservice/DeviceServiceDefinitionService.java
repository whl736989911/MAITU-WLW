package com.fireplatform.deviceservice;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Draft-editable product-version service definitions. Published definitions are frozen with their version. */
@Service
public class DeviceServiceDefinitionService {
    public record Row(String id, String productVersionId, String operationKey, String displayName, String category,
                      String riskLevel, String resultKind, String inputSchemaJson, String applicabilityJson,
                      String evidenceJson, String supportState, String status, long revision,
                      OffsetDateTime publishedAt) { }

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final ServiceSchema schema;
    private final Clock clock;

    public DeviceServiceDefinitionService(JdbcTemplate jdbc, ManagementGuard guard, AuditService audit,
                                          IdempotencyStore idem, ServiceSchema schema, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.idem = idem;
        this.schema = schema;
        this.clock = clock;
    }

    public List<Row> forVersion(String versionId) {
        return jdbc.query("SELECT * FROM device_service_definitions WHERE product_version_id=? ORDER BY operation_key",
            (rs, n) -> row(rs), versionId);
    }

    public List<Row> publishedForVersion(String versionId) {
        return jdbc.query("SELECT * FROM device_service_definitions WHERE product_version_id=? AND status='PUBLISHED' ORDER BY operation_key",
            (rs, n) -> row(rs), versionId);
    }

    public Row require(String id) {
        var rows = jdbc.query("SELECT * FROM device_service_definitions WHERE id=?", (rs, n) -> row(rs), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    @Transactional
    public Row create(AuthService.Identity actor, String versionId, String key,
                      DeviceServiceInputs.CreateServiceDefinition input, String requestId) {
        guard.begin(actor, "products:write", null, "management");
        idem.validateKey(key);
        var version = version(versionId, true);
        if (!"DRAFT".equals(version.status())) throw ApiError.conflict("VERSION_IMMUTABLE", "已发布版本的服务定义不可修改");
        if (!input.operationKey().matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) throw ApiError.bad("操作key格式无效");
        var inputSchema = schema.canonicalSchema(input.inputSchema());
        validated(input);
        String hash = idem.fingerprint(List.of(versionId, input.expectedVersionRevision(), input.operationKey(),
            input.displayName(), input.category(), input.riskLevel(), input.resultKind(), input.inputSchema().toString(),
            input.applicability().toString(), input.evidence().toString(), input.supportState()));
        Row replay = idem.replay(actor.user().id(), "service-definition-create", key, hash, Row.class);
        if (replay != null) return replay;
        if (version.revision() != input.expectedVersionRevision()) throw ApiError.conflict("REVISION_CONFLICT", "产品版本已更新，请刷新");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM device_service_definitions WHERE product_version_id=? AND operation_key=?", Long.class, versionId, input.operationKey()) != 0)
            throw ApiError.conflict("SERVICE_CONFLICT", "该版本已存在相同操作key");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM device_service_definitions WHERE product_version_id=?", Long.class, versionId) >= 32)
            throw ApiError.conflict("SERVICE_LIMIT", "单个产品版本最多登记32个服务操作");
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update("INSERT INTO device_service_definitions(id,product_version_id,operation_key,display_name,category,risk_level,result_kind,input_schema_json,applicability_json,evidence_json,support_state,status,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)",
            id, versionId, input.operationKey(), input.displayName().trim(), input.category(), input.riskLevel(), input.resultKind(),
            inputSchema.toString(), input.applicability().toString(), input.evidence().toString(), input.supportState(), now, now);
        bumpVersion(versionId, input.expectedVersionRevision());
        Row result = require(id);
        audit.record(actor.user().id(), null, "SERVICE_DEFINITION_CREATE", id, "SUCCESS", requestId);
        idem.save(actor.user().id(), "service-definition-create", key, hash, result);
        return result;
    }

    @Transactional
    public Row update(AuthService.Identity actor, String id, String key,
                      DeviceServiceInputs.UpdateServiceDefinition input, String requestId) {
        guard.begin(actor, "products:write", null, "management");
        idem.validateKey(key);
        Row current = requireForUpdate(id);
        var version = version(current.productVersionId(), true);
        if (!"DRAFT".equals(current.status()) || !"DRAFT".equals(version.status()))
            throw ApiError.conflict("VERSION_IMMUTABLE", "已发布版本的服务定义不可修改");
        validated(input);
        var inputSchema = schema.canonicalSchema(input.inputSchema());
        String hash = idem.fingerprint(List.of(id, input.expectedRevision(), input.displayName(), input.category(),
            input.riskLevel(), input.resultKind(), input.inputSchema().toString(), input.applicability().toString(),
            input.evidence().toString(), input.supportState()));
        Row replay = idem.replay(actor.user().id(), "service-definition-update", key, hash, Row.class);
        if (replay != null) return replay;
        if (current.revision() != input.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "服务定义已更新，请刷新");
        jdbc.update("UPDATE device_service_definitions SET display_name=?,category=?,risk_level=?,result_kind=?,input_schema_json=?,applicability_json=?,evidence_json=?,support_state=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
            input.displayName().trim(), input.category(), input.riskLevel(), input.resultKind(), inputSchema.toString(),
            input.applicability().toString(), input.evidence().toString(), input.supportState(),
            OffsetDateTime.now(clock), id, input.expectedRevision());
        bumpVersion(current.productVersionId(), version.revision());
        Row result = require(id);
        audit.record(actor.user().id(), null, "SERVICE_DEFINITION_UPDATE", id, "SUCCESS", requestId);
        idem.save(actor.user().id(), "service-definition-update", key, hash, result);
        return result;
    }

    @Transactional
    public void delete(AuthService.Identity actor, String id, String key,
                       DeviceServiceInputs.DeleteServiceDefinition input, String requestId) {
        guard.begin(actor, "products:write", null, "management");
        idem.validateKey(key);
        Row current = requireForUpdate(id);
        var version = version(current.productVersionId(), true);
        if (!"DRAFT".equals(current.status()) || !"DRAFT".equals(version.status()))
            throw ApiError.conflict("VERSION_IMMUTABLE", "已发布版本的服务定义不可修改");
        String hash = idem.fingerprint(List.of(id, input.expectedRevision()));
        if (idem.replay(actor.user().id(), "service-definition-delete", key, hash, DeviceServiceModels.IdResult.class) != null) return;
        if (current.revision() != input.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "服务定义已更新，请刷新");
        jdbc.update("DELETE FROM device_service_definitions WHERE id=?", id);
        bumpVersion(current.productVersionId(), version.revision());
        audit.record(actor.user().id(), null, "SERVICE_DEFINITION_DELETE", id, "SUCCESS", requestId);
        idem.save(actor.user().id(), "service-definition-delete", key, hash, new DeviceServiceModels.IdResult(id));
    }

    private void validated(DeviceServiceInputs.CreateServiceDefinition input) {
        if (input.displayName().isBlank()) throw ApiError.bad("服务名称不能为空");
        if (!input.applicability().isObject()) throw ApiError.bad("适用性必须是JSON对象");
        if (!input.evidence().isObject()) throw ApiError.bad("支持证据必须是JSON对象");
        if ("VERIFIED".equals(input.supportState()) && input.evidence().path("basis").asText("").isBlank())
            throw ApiError.bad("声明VERIFIED必须提供证据依据");
        floor(input.category(), input.riskLevel(), input.resultKind());
    }

    private void validated(DeviceServiceInputs.UpdateServiceDefinition input) {
        if (input.displayName().isBlank()) throw ApiError.bad("服务名称不能为空");
        if (!input.applicability().isObject()) throw ApiError.bad("适用性必须是JSON对象");
        if (!input.evidence().isObject()) throw ApiError.bad("支持证据必须是JSON对象");
        if ("VERIFIED".equals(input.supportState()) && input.evidence().path("basis").asText("").isBlank())
            throw ApiError.bad("声明VERIFIED必须提供证据依据");
        floor(input.category(), input.riskLevel(), input.resultKind());
    }

    /** Non-query intents must be COMMAND and carry the engineering floor; a schema cannot downgrade them. */
    private static void floor(String category, String riskLevel, String resultKind) {
        if (!"QUERY".equals(category) && !"COMMAND".equals(resultKind))
            throw ApiError.bad("非只读服务操作必须登记为COMMAND结果类型");
        if (!"QUERY".equals(category) && !"ENGINEERING".equals(riskLevel))
            throw ApiError.bad("参数下发与控制操作必须登记为ENGINEERING风险");
    }

    private Row requireForUpdate(String id) {
        var rows = jdbc.query("SELECT * FROM device_service_definitions WHERE id=? FOR UPDATE", (rs, n) -> row(rs), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private record VersionState(String status, long revision) { }

    private VersionState version(String id, boolean lock) {
        var rows = jdbc.query("SELECT status,revision FROM product_versions WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new VersionState(rs.getString(1), rs.getLong(2)), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private void bumpVersion(String versionId, long expectedRevision) {
        if (jdbc.update("UPDATE product_versions SET revision=revision+1 WHERE id=? AND revision=? AND status='DRAFT'", versionId, expectedRevision) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "产品版本已更新，请刷新");
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString("id"), rs.getString("product_version_id"), rs.getString("operation_key"),
            rs.getString("display_name"), rs.getString("category"), rs.getString("risk_level"),
            rs.getString("result_kind"), rs.getString("input_schema_json"), rs.getString("applicability_json"),
            rs.getString("evidence_json"), rs.getString("support_state"), rs.getString("status"),
            rs.getLong("revision"), rs.getObject("published_at", OffsetDateTime.class));
    }
}
