package com.fireplatform.maintenance;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.duty.DutyQueries;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local maintenance workflow writes. Password verification, current permissions, unit/device scope and CAS revision
 * are rechecked on every call. Source fault, recovery and physical/device state are never updated here.
 */
@Service
public class MaintenanceCommands {
    private static final String CREATE_SQL = "INSERT INTO maintenance_work_orders(id,tenant_id,device_id,fault_event_id,"
        + "active_fault_event_id,title,description,priority,due_at,status,assignee_user_id,revision,created_by,created_at,"
        + "updated_by,updated_at,closed_at,cancelled_at) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,0,?,?,?,?,NULL,NULL)";

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final TenantFence tenants;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final TransactionRequirements transactions;
    private final DeviceAccessScope devices;
    private final DutyQueries duty;
    private final MaintenanceQueries queries;
    private final DatabaseTime time;

    public MaintenanceCommands(JdbcTemplate jdbc, ManagementGuard guard, TenantFence tenants,
                               IdempotencyStore idem, AuditService audit, TransactionRequirements transactions,
                               DeviceAccessScope devices, DutyQueries duty, MaintenanceQueries queries, DatabaseTime time) {
        this.jdbc = jdbc; this.guard = guard; this.tenants = tenants; this.idem = idem;
        this.audit = audit; this.transactions = transactions; this.devices = devices; this.duty = duty;
        this.queries = queries; this.time = time;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail create(AuthService.Identity actor, String tenant, String key,
                                                MaintenanceInputs.Create input, String requestId, boolean draft) {
        begin(actor, tenant, key, "maintenance:write");
        String operation = draft ? "maint-order-draft-import" : "maint-order-create";
        String hash = idem.fingerprint(List.of(tenant, String.valueOf(input.faultEventId()), input.deviceId(),
            input.title(), input.description(), String.valueOf(input.priority()), String.valueOf(input.dueAt()),
            draft ? "DRAFT" : "OPEN", input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        requireActualDevice(tenant, input.deviceId());
        devices.requireDevice(actor.user().id(), tenant, input.deviceId());
        if (input.faultEventId() != null) requireFault(actor.user().id(), tenant, input.deviceId(), input.faultEventId());
        String id = UUID.randomUUID().toString();
        String status = draft ? "DRAFT" : "OPEN";
        String activeFault = draft || input.faultEventId() == null ? null : input.faultEventId();
        OffsetDateTime now = time.now();
        try {
            jdbc.update(CREATE_SQL, id, tenant, input.deviceId(), input.faultEventId(), activeFault, input.title(),
                input.description(), input.priority(), input.dueAt(), status, actor.user().id(), now,
                actor.user().id(), now);
        } catch (DuplicateKeyException conflict) {
            throw ApiError.conflict("FAULT_WORK_ORDER_EXISTS", "该故障已有关联的活动工单");
        }
        transition(tenant, id, "CREATE", null, status, actor.user().id(), input.reason(), null, null, null, 0, now);
        return saved(actor, tenant, id, operation, key, hash, requestId,
            draft ? "MAINTENANCE_WORK_ORDER_DRAFT_CREATE" : "MAINTENANCE_WORK_ORDER_CREATE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail open(AuthService.Identity actor, String tenant, String id, String key,
                                              MaintenanceInputs.Versioned input, String requestId) {
        begin(actor, tenant, key, "maintenance:write");
        requireVisibleOrder(actor.user().id(), tenant, id);
        String operation = "maint-order-open";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.note(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        requireTransition(MaintenanceLifecycle.canOpen(locked.status()), "只有草稿工单可以开始处理");
        String active = locked.faultEventId();
        if (active != null) requireFault(actor.user().id(), tenant, locked.deviceId(), active);
        try {
            update(tenant, id, input.expectedRevision(), "OPEN", locked.assigneeUserId(), active, null, null,
                actor.user().id());
        } catch (DuplicateKeyException conflict) {
            throw ApiError.conflict("FAULT_WORK_ORDER_EXISTS", "该故障已有其他活动工单");
        }
        transition(tenant, id, "OPEN", locked.status(), "OPEN", actor.user().id(), combined(input.reason(), input.note()),
            null, null, locked.revision(), input.expectedRevision() + 1, time.now());
        return saved(actor, tenant, id, operation, key, hash, requestId, "MAINTENANCE_WORK_ORDER_OPEN");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail assign(AuthService.Identity actor, String tenant, String id, String key,
                                                MaintenanceInputs.Assign input, String requestId) {
        begin(actor, tenant, key, "maintenance:assign");
        requireVisibleOrder(actor.user().id(), tenant, id);
        String operation = "maint-order-assign";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.assigneeUserId(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        requireTransition(MaintenanceLifecycle.canAssign(locked.status()), "当前工单状态不允许指派");
        if (!queries.assigneeAllowed(tenant, locked.deviceId(), input.assigneeUserId()))
            throw ApiError.bad("负责人必须已启用、具有维护权限且可访问同一单位设备");
        String status = MaintenanceLifecycle.afterAssign(locked.status());
        OffsetDateTime now = time.now();
        update(tenant, id, input.expectedRevision(), status, input.assigneeUserId(), locked.activeFaultEventId(),
            null, null, actor.user().id());
        transition(tenant, id, "ASSIGN", locked.status(), status, actor.user().id(), input.reason(),
            input.assigneeUserId(), null, locked.revision(), input.expectedRevision() + 1, now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "MAINTENANCE_WORK_ORDER_ASSIGN");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail workLog(AuthService.Identity actor, String tenant, String id, String key,
                                                 MaintenanceInputs.WorkLog input, String requestId) {
        begin(actor, tenant, key, "maintenance:write");
        requireVisibleOrder(actor.user().id(), tenant, id);
        String operation = "maint-order-work-log";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.note(), input.result(),
            String.valueOf(input.replacementDeviceId()), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        requireTransition(MaintenanceLifecycle.canWorkLog(locked.status()), "当前工单状态不允许登记维护记录");
        String replacement = input.replacementDeviceId();
        if (replacement != null) {
            if (replacement.equals(locked.deviceId())) throw ApiError.bad("更换设备必须不同于工单源设备");
            requireActualDevice(tenant, replacement);
            devices.requireDevice(actor.user().id(), tenant, replacement);
        }
        String logId = UUID.randomUUID().toString();
        String status = MaintenanceLifecycle.afterWorkLog();
        OffsetDateTime now = time.now();
        long next = input.expectedRevision() + 1;
        jdbc.update("INSERT INTO maintenance_work_logs(id,tenant_id,work_order_id,actor_id,note,result,revision_after,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)", logId, tenant, id, actor.user().id(), input.note(), input.result(), next, now);
        if (replacement != null) {
            jdbc.update("INSERT INTO maintenance_work_order_replacements(id,tenant_id,work_order_id,work_log_id,"
                    + "source_device_id,replacement_device_id,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenant, id, logId, locked.deviceId(), replacement, actor.user().id(), now);
        }
        update(tenant, id, input.expectedRevision(), status, locked.assigneeUserId(), locked.activeFaultEventId(),
            null, null, actor.user().id());
        transition(tenant, id, "WORK_LOG", locked.status(), status, actor.user().id(), input.reason(), null,
            logId, locked.revision(), next, now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "MAINTENANCE_WORK_ORDER_WORK_LOG");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail submitReview(AuthService.Identity actor, String tenant, String id, String key,
                                                      MaintenanceInputs.Versioned input, String requestId) {
        begin(actor, tenant, key, "maintenance:write");
        requireVisibleOrder(actor.user().id(), tenant, id);
        String operation = "maint-order-submit-review";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.note(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        requireTransition(MaintenanceLifecycle.canSubmitReview(locked.status()), "只有处理中的工单可以提交复核");
        Long logs = jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_work_logs WHERE tenant_id=? AND work_order_id=?",
            Long.class, tenant, id);
        if (logs == null || logs == 0) throw ApiError.conflict("WORK_ORDER_STATE", "提交复核前至少需要一条维护记录");
        update(tenant, id, input.expectedRevision(), "PENDING_REVIEW", locked.assigneeUserId(),
            locked.activeFaultEventId(), null, null, actor.user().id());
        transition(tenant, id, "SUBMIT_REVIEW", locked.status(), "PENDING_REVIEW", actor.user().id(),
            combined(input.reason(), input.note()), null, null, locked.revision(), input.expectedRevision() + 1, time.now());
        return saved(actor, tenant, id, operation, key, hash, requestId, "MAINTENANCE_WORK_ORDER_SUBMIT_REVIEW");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail review(AuthService.Identity actor, String tenant, String id, String key,
                                                MaintenanceInputs.Review input, String requestId) {
        begin(actor, tenant, key, "maintenance:review");
        requireVisibleOrder(actor.user().id(), tenant, id);
        String operation = input.pass() ? "maint-order-review-pass" : "maint-order-review-return";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.pass(), input.note(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        requireTransition(MaintenanceLifecycle.canReview(locked.status()), "只有待复核工单可以复核");
        String status = MaintenanceLifecycle.afterReview(input.pass());
        OffsetDateTime now = time.now();
        update(tenant, id, input.expectedRevision(), status, locked.assigneeUserId(),
            input.pass() ? null : locked.activeFaultEventId(), input.pass() ? now : null, null, actor.user().id());
        transition(tenant, id, input.pass() ? "REVIEW_PASS" : "REVIEW_RETURN", locked.status(), status,
            actor.user().id(), combined(input.reason(), input.note()), null, null, locked.revision(),
            input.expectedRevision() + 1, now);
        return saved(actor, tenant, id, operation, key, hash, requestId,
            input.pass() ? "MAINTENANCE_WORK_ORDER_REVIEW_PASS" : "MAINTENANCE_WORK_ORDER_REVIEW_RETURN");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail reopen(AuthService.Identity actor, String tenant, String id, String key,
                                                MaintenanceInputs.Versioned input, String requestId) {
        begin(actor, tenant, key, "maintenance:write");
        requireVisibleOrder(actor.user().id(), tenant, id);
        String operation = "maint-order-reopen";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.note(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        requireTransition(MaintenanceLifecycle.canReopen(locked.status()), "只有已关闭工单可以重新打开");
        if (locked.faultEventId() != null) requireFault(actor.user().id(), tenant, locked.deviceId(), locked.faultEventId());
        try {
            update(tenant, id, input.expectedRevision(), "OPEN", null, locked.faultEventId(), null, null,
                actor.user().id());
        } catch (DuplicateKeyException conflict) {
            throw ApiError.conflict("FAULT_WORK_ORDER_EXISTS", "该故障已有其他活动工单，不能重新打开");
        }
        transition(tenant, id, "REOPEN", locked.status(), "OPEN", actor.user().id(), combined(input.reason(), input.note()),
            null, null, locked.revision(), input.expectedRevision() + 1, time.now());
        return saved(actor, tenant, id, operation, key, hash, requestId, "MAINTENANCE_WORK_ORDER_REOPEN");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MaintenanceModels.OrderDetail cancel(AuthService.Identity actor, String tenant, String id, String key,
                                                MaintenanceInputs.Versioned input, String requestId) {
        begin(actor, tenant, key, "maintenance:write");
        requireVisibleOrder(actor.user().id(), tenant, id);
        String operation = "maint-order-cancel";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.note(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, MaintenanceModels.OrderDetail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        requireTransition(MaintenanceLifecycle.canCancel(locked.status()), "当前工单状态不允许取消");
        OffsetDateTime now = time.now();
        update(tenant, id, input.expectedRevision(), "CANCELLED", locked.assigneeUserId(), null, null, now,
            actor.user().id());
        transition(tenant, id, "CANCEL", locked.status(), "CANCELLED", actor.user().id(), combined(input.reason(), input.note()),
            null, null, locked.revision(), input.expectedRevision() + 1, now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "MAINTENANCE_WORK_ORDER_CANCEL");
    }

    private void begin(AuthService.Identity actor, String tenant, String key, String permission) {
        transactions.readCommitted();
        guard.begin(actor, permission, tenant, actor.audience());
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
    }

    private void requireVisibleOrder(String actorId, String tenant, String id) {
        var rows = jdbc.query("SELECT device_id FROM maintenance_work_orders WHERE tenant_id=? AND id=?",
            (rs, n) -> rs.getString(1), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        devices.requireDevice(actorId, tenant, rows.getFirst());
    }
    private MaintenanceQueries.Locked lockAndCheck(String actorId, String tenant, String id, long expected) {
        var locked = queries.locked(tenant, id);
        devices.requireDevice(actorId, tenant, locked.deviceId());
        if (locked.revision() != expected) throw ApiError.conflict("REVISION_CONFLICT", "工单已更新，请刷新后重试");
        if (locked.revision() >= MaintenanceModels.MAX_SAFE)
            throw ApiError.conflict("REVISION_EXHAUSTED", "工单版本已达安全上界");
        return locked;
    }

    private void update(String tenant, String id, long expected, String status, String assigneeUserId,
                        String activeFaultEventId, OffsetDateTime closedAt, OffsetDateTime cancelledAt, String actorId) {
        int changed = jdbc.update("UPDATE maintenance_work_orders SET status=?,assignee_user_id=?,active_fault_event_id=?,"
                + "closed_at=?,cancelled_at=?,revision=revision+1,updated_by=?,updated_at=? "
                + "WHERE tenant_id=? AND id=? AND revision=?",
            status, assigneeUserId, activeFaultEventId, closedAt, cancelledAt, actorId, time.now(), tenant, id, expected);
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "工单已更新，请刷新后重试");
    }

    private void transition(String tenant, String id, String action, String fromStatus, String toStatus, String actorId,
                            String reason, String assigneeUserId, String workLogId, Long revisionBefore,
                            long revisionAfter, OffsetDateTime now) {
        jdbc.update("INSERT INTO maintenance_work_order_transitions(id,tenant_id,work_order_id,action,from_status,"
                + "to_status,actor_id,reason,assignee_user_id,work_log_id,revision_before,revision_after,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), tenant, id, action, fromStatus, toStatus, actorId, reason,
            assigneeUserId, workLogId, revisionBefore, revisionAfter, now);
    }

    private MaintenanceModels.OrderDetail saved(AuthService.Identity actor, String tenant, String id, String operation,
                                                String key, String hash, String requestId, String auditAction) {
        var detail = queries.detailCurrent(tenant, id);
        audit.record(actor.user().id(), tenant, auditAction, id, "SUCCESS", requestId);
        idem.save(actor.user().id(), operation, key, hash, detail);
        return detail;
    }

    private void requireActualDevice(String tenant, String deviceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND id=? AND is_simulated=FALSE"
            + " AND archived_at IS NULL", Long.class, tenant, deviceId);
        if (count == null || count != 1L) throw ApiError.missing();
    }

    private void requireFault(String actorId, String tenant, String deviceId, String eventId) {
        var event = duty.fault(actorId, tenant, ScopeFilter.Selection.all(), eventId);
        if (!tenant.equals(event.tenantId()) || !deviceId.equals(event.deviceId()))
            throw ApiError.bad("故障事件必须属于同一单位的同一设备");
    }

    private static void requireTransition(boolean allowed, String message) {
        if (!allowed) throw ApiError.conflict("WORK_ORDER_STATE", message);
    }

    private static String combined(String reason, String note) {
        if (note == null || note.isBlank()) return reason;
        if (reason.length() + note.length() + 3 > 1000) throw ApiError.bad("变更原因与说明合计超过长度限制");
        return reason + " | " + note;
    }
}
