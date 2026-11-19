package com.fireplatform.firearchive;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Metadata-only archive writes. No device position, fire state, vendor record or attachment content is changed. */
@Service
public class FireArchiveCommands {
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final TenantFence tenants;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final TransactionRequirements transactions;
    private final DeviceAccessScope devices;
    private final FireArchiveQueries queries;
    private final DatabaseTime time;

    public FireArchiveCommands(JdbcTemplate jdbc, ManagementGuard guard, TenantFence tenants,
                               IdempotencyStore idem, AuditService audit,
                               TransactionRequirements transactions, DeviceAccessScope devices,
                               FireArchiveQueries queries, DatabaseTime time) {
        this.jdbc = jdbc; this.guard = guard; this.tenants = tenants; this.idem = idem;
        this.audit = audit; this.transactions = transactions; this.devices = devices; this.queries = queries;
        this.time = time;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FireArchiveModels.Detail create(AuthService.Identity actor, String tenant, String key,
                                           FireArchiveInputs.Create input, String requestId) {
        begin(actor, tenant, key);
        String operation = "fire-archive-create";
        String hash = fingerprint(tenant, input.metadata(), null, input.reason());
        var replay = idem.replay(actor.user().id(), operation, key, hash, FireArchiveModels.Detail.class);
        if (replay != null) return replay;
        requireMetadataTargets(actor.user().id(), tenant, input.metadata());
        String id = UUID.randomUUID().toString();
        var meta = input.metadata();
        OffsetDateTime now = time.now();
        jdbc.update("INSERT INTO fire_archives(id,tenant_id,title,category,archive_number,device_id,place_id,issued_on,"
                + "expires_on,description,status,revision,created_by,created_at,updated_by,updated_at,"
                + "archived_by,archived_at,archived_reason) VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?,?,?,NULL,NULL,NULL)",
            id, tenant, meta.title(), meta.category(), meta.archiveNumber(), meta.deviceId(), meta.placeId(),
            meta.issuedOn(), meta.expiresOn(), meta.description(), actor.user().id(), now, actor.user().id(), now);
        history(tenant, id, "CREATE", null, 0, actor.user().id(), input.reason(), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "FIRE_ARCHIVE_CREATE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FireArchiveModels.Detail update(AuthService.Identity actor, String tenant, String id, String key,
                                           FireArchiveInputs.Update input, String requestId) {
        begin(actor, tenant, key);
        FireArchiveInputs.archiveId(id);
        requireVisibleArchive(actor.user().id(), tenant, id);
        String operation = "fire-archive-update";
        String hash = fingerprint(tenant, input.metadata(), input.expectedRevision(), input.reason());
        var replay = idem.replay(actor.user().id(), operation, key, hash, FireArchiveModels.Detail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        if (!"ACTIVE".equals(locked.status())) throw ApiError.conflict("ARCHIVE_STATE", "已归档档案只读，不能修改");
        requireChangedTargets(actor.user().id(), tenant, input.metadata(), locked);
        var meta = input.metadata();
        OffsetDateTime now = time.now();
        int changed = jdbc.update("UPDATE fire_archives SET title=?,category=?,archive_number=?,device_id=?,place_id=?,"
                + "issued_on=?,expires_on=?,description=?,revision=revision+1,updated_by=?,updated_at=? "
                + "WHERE tenant_id=? AND id=? AND revision=? AND status='ACTIVE'",
            meta.title(), meta.category(), meta.archiveNumber(), meta.deviceId(), meta.placeId(), meta.issuedOn(),
            meta.expiresOn(), meta.description(), actor.user().id(), now, tenant, id, input.expectedRevision());
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "档案已更新，请刷新后重试");
        history(tenant, id, "UPDATE", locked.revision(), locked.revision() + 1, actor.user().id(), input.reason(), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "FIRE_ARCHIVE_UPDATE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FireArchiveModels.Detail archive(AuthService.Identity actor, String tenant, String id, String key,
                                            FireArchiveInputs.Archive input, String requestId) {
        begin(actor, tenant, key);
        FireArchiveInputs.archiveId(id);
        requireVisibleArchive(actor.user().id(), tenant, id);
        String operation = "fire-archive-archive";
        String hash = idem.fingerprint(List.of(tenant, id, input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, FireArchiveModels.Detail.class);
        if (replay != null) return replay;
        var locked = lockAndCheck(actor.user().id(), tenant, id, input.expectedRevision());
        if (!"ACTIVE".equals(locked.status())) throw ApiError.conflict("ARCHIVE_STATE", "档案已归档");
        OffsetDateTime now = time.now();
        int changed = jdbc.update("UPDATE fire_archives SET status='ARCHIVED',revision=revision+1,updated_by=?,updated_at=?,"
                + "archived_by=?,archived_at=?,archived_reason=? WHERE tenant_id=? AND id=? AND revision=? AND status='ACTIVE'",
            actor.user().id(), now, actor.user().id(), now, input.reason(), tenant, id, input.expectedRevision());
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "档案已更新，请刷新后重试");
        history(tenant, id, "ARCHIVE", locked.revision(), locked.revision() + 1, actor.user().id(), input.reason(), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "FIRE_ARCHIVE_ARCHIVE");
    }

    void begin(AuthService.Identity actor, String tenant, String key) {
        transactions.readCommitted();
        guard.begin(actor, "fire-archives:write", tenant, actor.audience());
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
    }

    void requireVisibleArchive(String actorId, String tenant, String id) {
        var rows = jdbc.query("SELECT device_id FROM fire_archives WHERE tenant_id=? AND id=?",
            (rs, n) -> rs.getString(1), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        if (rows.getFirst() != null && !devices.contains(actorId, tenant, rows.getFirst())) throw ApiError.missing();
    }

    FireArchiveModels.Locked lockAndCheck(String actorId, String tenant, String id, long expected) {
        var locked = queries.locked(tenant, id);
        if (locked.deviceId() != null && !devices.contains(actorId, tenant, locked.deviceId())) throw ApiError.missing();
        if (locked.revision() != expected) throw ApiError.conflict("REVISION_CONFLICT", "档案已更新，请刷新后重试");
        if (locked.revision() >= FireArchiveModels.MAX_SAFE)
            throw ApiError.conflict("REVISION_EXHAUSTED", "档案版本已达安全上界");
        return locked;
    }

    FireArchiveModels.Detail saved(AuthService.Identity actor, String tenant, String id, String operation,
                                   String key, String hash, String requestId, String auditAction) {
        var detail = queries.detailCurrent(tenant, id);
        audit.record(actor.user().id(), tenant, auditAction, id, "SUCCESS", requestId);
        idem.save(actor.user().id(), operation, key, hash, detail);
        return detail;
    }

    void history(String tenant, String archiveId, String action, Long before, long after, String actorId,
                 String reason, OffsetDateTime now) {
        jdbc.update("INSERT INTO fire_archive_history(id,tenant_id,archive_id,action,revision_before,revision_after,"
                + "actor_id,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), tenant, archiveId, action, before, after, actorId, reason, now);
    }

    private void requireMetadataTargets(String actorId, String tenant, FireArchiveInputs.Metadata meta) {
        if (meta.deviceId() != null) {
            if (!queries.actualDevice(tenant, meta.deviceId())) throw ApiError.missing();
            devices.requireDevice(actorId, tenant, meta.deviceId());
        }
        if (meta.placeId() != null && !queries.placeInUnit(tenant, meta.placeId())) throw ApiError.missing();
    }

    /** Historical references may stay bound after a transfer/archive; only a new binding must be an active asset. */
    private void requireChangedTargets(String actorId, String tenant, FireArchiveInputs.Metadata meta,
                                       FireArchiveModels.Locked locked) {
        if (meta.deviceId() != null && !meta.deviceId().equals(locked.deviceId())) {
            if (!queries.actualDevice(tenant, meta.deviceId())) throw ApiError.missing();
            devices.requireDevice(actorId, tenant, meta.deviceId());
        }
        if (meta.placeId() != null && !meta.placeId().equals(locked.placeId())
            && !queries.placeInUnit(tenant, meta.placeId())) throw ApiError.missing();
    }

    private String fingerprint(String tenant, FireArchiveInputs.Metadata meta, Long expectedRevision, String reason) {
        return idem.fingerprint(List.of(tenant, meta.title(), meta.category(), String.valueOf(meta.archiveNumber()),
            String.valueOf(meta.deviceId()), String.valueOf(meta.placeId()), String.valueOf(meta.issuedOn()),
            String.valueOf(meta.expiresOn()), String.valueOf(meta.description()),
            expectedRevision == null ? "CREATE" : expectedRevision.toString(), reason));
    }
}
