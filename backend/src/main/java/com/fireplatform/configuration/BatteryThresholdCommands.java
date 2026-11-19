package com.fireplatform.configuration;

import com.fireplatform.shared.ParameterTargets;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.AuditService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.IdempotencyStore;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatteryThresholdCommands {
    private record State(String comparator, java.math.BigDecimal threshold, long revision) { }

    private final JdbcTemplate jdbc;
    private final ExecutionAuthorization authorization;
    private final TenantFence tenants;
    private final ParameterTargets targets;
    private final BatteryThresholdQueries queries;
    private final IdempotencyStore idem;
    private final AuditService audit;

    public BatteryThresholdCommands(JdbcTemplate jdbc, ExecutionAuthorization authorization, TenantFence tenants,
                                    ParameterTargets targets, BatteryThresholdQueries queries,
                                    IdempotencyStore idem, AuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.tenants = tenants;
        this.targets = targets;
        this.queries = queries;
        this.idem = idem;
        this.audit = audit;
    }

    @Transactional
    public BatteryThresholdModels.Mutation put(AuthService.Identity actor, String tenant, String device, String key,
                                               BatteryThresholdInputs.SetOverride input, String request) {
        authorization.lockRequest(actor, tenant, List.of("parameters:write", "parameters:read"));
        tenants.lockEnabled(tenant);
        targets.require(actor.user().id(), tenant, device, true);
        idem.validateKey(key);
        text(input.reason());
        var value = input.batteryLowThreshold();
        if (value != null && (!BatteryThresholdModels.PARAMETER_KEY.equals(value.parameterKey()) ||
            !BatteryThresholdModels.METRIC_KEY.equals(value.metricKey()) || !BatteryThresholdModels.UNIT.equals(value.unit()) ||
            value.comparator() == null || !java.util.Set.of("LT","LTE").contains(value.comparator()) ||
            value.threshold() == null || value.threshold().scale() > 36 || value.threshold().precision() > 64 ||
            value.threshold().compareTo(java.math.BigDecimal.ZERO) < 0 ||
            value.threshold().compareTo(java.math.BigDecimal.valueOf(100)) > 0)) {
            throw ApiError.bad("低电量阈值的参数、指标、单位或数值无效");
        }
        String action = value == null ? "CLEAR" : "SET";
        String comparator = value == null ? null : value.comparator();
        java.math.BigDecimal threshold = value == null ? null : value.threshold();
        String fingerprint = idem.fingerprint(Arrays.asList(tenant, device, action, comparator, threshold,
            input.expectedRevision(), input.reason()));
        String operation = "battery-threshold";
        var replay = idem.replay(actor.user().id(), operation, key, fingerprint, BatteryThresholdModels.ChangeReference.class);
        if (replay != null) {
            return new BatteryThresholdModels.Mutation(queries.view(actor.user().id(), tenant, device),
                queries.change(tenant, device, replay.changeId()));
        }
        if (value != null) {
            var current = queries.view(actor.user().id(), tenant, device);
            if (current.productVersion() != null && !"SUPPORTED".equals(current.productVersion().batteryCapability())) {
                throw ApiError.conflict("PARAMETER_INCOMPATIBLE", "当前产品版本未声明支持电量阈值");
            }
        }
        var rows = jdbc.query("SELECT comparator,threshold,revision FROM device_battery_threshold_overrides WHERE device_id=? FOR UPDATE",
            (rs, n) -> new State(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)), device);
        State old = rows.isEmpty() ? new State(null, null, 0) : rows.getFirst();
        if (old.revision() != input.expectedRevision())
            throw ApiError.conflict("REVISION_CONFLICT", "覆盖已更新，请刷新后重开操作");
        if (action.equals("CLEAR") && old.threshold() == null)
            throw ApiError.conflict("NO_OVERRIDE", "当前没有可清除的覆盖");
        long after = old.revision() + 1;
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO device_battery_threshold_overrides(device_id,comparator,threshold,revision) VALUES (?,?,?,?)",
                device, comparator, threshold, after);
        } else {
            int updated = jdbc.update("UPDATE device_battery_threshold_overrides SET comparator=?,threshold=?,revision=? WHERE device_id=? AND revision=?",
                comparator, threshold, after, device, old.revision());
            if (updated != 1) throw ApiError.conflict("REVISION_CONFLICT", "覆盖已更新，请刷新后重开操作");
        }
        String changeId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_battery_threshold_changes(id,device_id,action,previous_comparator,previous_threshold,new_comparator,new_threshold,revision_before,revision_after,actor_id,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            changeId, device, action, old.comparator(), old.threshold(), comparator, threshold, old.revision(), after,
            actor.user().id(), input.reason());
        audit.record(actor.user().id(), tenant, "BATTERY_THRESHOLD_" + action, device, "SUCCESS", request);
        idem.save(actor.user().id(), operation, key, fingerprint, new BatteryThresholdModels.ChangeReference(changeId));
        return new BatteryThresholdModels.Mutation(queries.view(actor.user().id(), tenant, device),
            queries.change(tenant, device, changeId));
    }

    private void text(String value) {
        if (value == null || value.isBlank() || value.length() > com.fireplatform.shared.Limits.MAX_CHANGE_REASON_LENGTH || value.indexOf(0) >= 0)
            throw ApiError.bad("变更理由无效");
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException e) {
            throw ApiError.bad("文本编码无效");
        }
    }
}
