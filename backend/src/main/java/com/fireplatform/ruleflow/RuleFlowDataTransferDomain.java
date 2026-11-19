package com.fireplatform.ruleflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bulkio.DataTransferDomain;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bulk import/export for rule flows. Import can only create or update a DRAFT: status and activation are not
 * importable columns, so an imported flow never publishes, enables or touches a consumer.
 */
@Component
public class RuleFlowDataTransferDomain implements DataTransferDomain {
    private static final Set<String> MANAGEMENT = Set.of("management");
    private static final List<String> COLUMNS = List.of("id", "name", "description", "definition_json", "expected_revision");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RuleFlowCommands commands;

    public RuleFlowDataTransferDomain(JdbcTemplate jdbc, ObjectMapper json, RuleFlowCommands commands) {
        this.jdbc = jdbc; this.json = json; this.commands = commands;
    }

    @Override
    public List<Kind> kinds() {
        return List.of(new Kind("RULE_FLOWS", COLUMNS, "rules:read", "rules:write", true, true, true, MANAGEMENT, "DISABLED_DRAFT"));
    }

    @Override
    public List<String> validate(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant) {
        try {
            if (tenant == null || tenant.isBlank()) throw ApiError.bad("规则流导入必须指定单位");
            String name = required(cells, "name", 120);
            String description = nullable(cells.get("description"), 1000);
            Map<String, Object> definition = definition(cells.get("definition_json"));
            RuleFlowDefinition.parse(definition);
            String id = empty(cells.get("id")) ? null : required(cells, "id", 64);
            if (id == null) {
                if (!empty(cells.get("expected_revision"))) throw ApiError.bad("新建规则流不能指定expected_revision");
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rule_flows WHERE tenant_id=? AND name=?", Long.class, tenant, name);
                if (count != null && count > 0) throw ApiError.bad("本单位已存在同名规则流");
            } else {
                var rows = jdbc.query("SELECT revision,status FROM rule_flows WHERE tenant_id=? AND id=?",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, tenant, id);
                if (rows.isEmpty()) throw ApiError.bad("规则流不存在");
                if ("ARCHIVED".equals(rows.getFirst()[1])) throw ApiError.bad("已归档规则流不能通过导入修改");
                if (((Number) rows.getFirst()[0]).longValue() != revision(cells))
                    throw ApiError.bad("规则流修订号已变化，请重新导出后再导入");
            }
            return List.of();
        } catch (RuntimeException failure) {
            return List.of(message(failure));
        }
    }

    @Override
    public String operation(String kind, Map<String, String> cells) {
        return empty(cells.get("id")) ? "CREATE_DISABLED_DRAFT" : "UPDATE_DISABLED_DRAFT";
    }

    @Override
    public String apply(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant,
            String reason, String jobId, int rowNumber) {
        String key = "data-transfer:" + jobId + ":" + rowNumber;
        String id = empty(cells.get("id")) ? null : required(cells, "id", 64);
        Map<String, Object> definition = definition(cells.get("definition_json"));
        String name = required(cells, "name", 120);
        String description = nullable(cells.get("description"), 1000);
        if (id == null)
            return commands.create(actor, tenant, name, description, definition, reason, "data-transfer", key).flow().id();
        return commands.save(actor, tenant, id, revision(cells), name, description, definition, reason,
            "data-transfer", key).flow().id();
    }

    @Override
    public ExportData export(String kind, AuthService.Identity actor, String tenant, Map<String, String> filters, List<String> selectedIds) {
        if (tenant == null || tenant.isBlank()) throw ApiError.bad("规则流导出必须指定单位");
        StringBuilder where = new StringBuilder(" FROM rule_flows f WHERE f.tenant_id=?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (selectedIds != null && !selectedIds.isEmpty()) {
            where.append(" AND f.id IN (").append(String.join(",", java.util.Collections.nCopies(selectedIds.size(), "?"))).append(")");
            args.addAll(selectedIds);
        }
        if (filters != null && filters.get("status") != null && !filters.get("status").isBlank()) {
            where.append(" AND f.status=?"); args.add(filters.get("status"));
        }
        var rows = jdbc.query("SELECT f.id,f.name,f.description,f.definition_json,f.revision" + where + " ORDER BY f.name,f.id",
            (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Long.toString(rs.getLong(5))),
            args.toArray());
        return new ExportData(COLUMNS, rows);
    }

    private Map<String, Object> definition(String source) {
        if (source == null || source.isBlank()) throw ApiError.bad("definition_json不能为空");
        try { return json.readValue(source, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception failure) { throw ApiError.bad("definition_json不是有效JSON对象"); }
    }

    private static long revision(Map<String, String> cells) {
        String value = cells.get("expected_revision");
        if (value == null || value.isBlank()) throw ApiError.bad("更新规则流必须填写expected_revision");
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException failure) { throw ApiError.bad("expected_revision必须是整数"); }
    }

    private static String required(Map<String, String> cells, String column, int max) {
        String value = cells.get(column);
        if (value == null || value.isBlank() || value.length() > max) throw ApiError.bad(column + "必须为1至" + max + "个字符");
        return value.trim();
    }

    private static String nullable(String value, int max) {
        if (value == null) return "";
        if (value.length() > max) throw ApiError.bad("description超过" + max + "个字符");
        return value.trim();
    }

    private static boolean empty(String value) { return value == null || value.isBlank(); }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank() ? "规则流导入校验失败" : failure.getMessage();
    }
}
