package com.fireplatform.deviceservice;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-supplied capability evidence for vendor-platform operations that the official portal does
 * not publish per model. Without such evidence the built-in operation stays UNVERIFIED and cannot
 * dispatch; this table never turns vendor acceptance into physical-effect proof.
 */
@Service
public class DeviceCapabilityEvidenceService {
    public record EvidenceView(String driverKey, String model, String serviceKey, String evidence,
                               String verifiedBy, OffsetDateTime verifiedAt) { }

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final Clock clock;

    public DeviceCapabilityEvidenceService(JdbcTemplate jdbc, ManagementGuard guard, AuditService audit,
                                           IdempotencyStore idem, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.idem = idem;
        this.clock = clock;
    }

    public Optional<EvidenceView> find(String driverKey, String model, String serviceKey) {
        if (driverKey == null || model == null || serviceKey == null) return Optional.empty();
        var rows = jdbc.query("SELECT driver_key,model,service_key,evidence,verified_by,verified_at FROM device_capability_evidence WHERE driver_key=? AND model=? AND service_key=?",
            (rs, n) -> new EvidenceView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, OffsetDateTime.class)), driverKey, model, serviceKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Transactional
    public EvidenceView upsert(AuthService.Identity actor, String driverKey,
                               DeviceServiceInputs.CapabilityEvidence input, String key, String requestId) {
        guard.begin(actor, "device-services:engineering", null, "management");
        idem.validateKey(key);
        String hash = idem.fingerprint(List.of(driverKey, input.model(), input.serviceKey(), input.evidence()));
        EvidenceView replay = idem.replay(actor.user().id(), "capability-evidence", key, hash, EvidenceView.class);
        if (replay != null) return replay;
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (jdbc.update("UPDATE device_capability_evidence SET evidence=?,verified_by=?,verified_at=? WHERE driver_key=? AND model=? AND service_key=?",
                input.evidence().trim(), actor.user().id(), now, driverKey, input.model(), input.serviceKey()) == 0) {
            jdbc.update("INSERT INTO device_capability_evidence(id,driver_key,model,service_key,evidence,verified_by,verified_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), driverKey, input.model(), input.serviceKey(), input.evidence().trim(),
                actor.user().id(), now);
        }
        EvidenceView result = new EvidenceView(driverKey, input.model(), input.serviceKey(), input.evidence().trim(),
            actor.user().id(), now);
        audit.recordReason(actor.user().id(), null, "DEVICE_CAPABILITY_EVIDENCE", driverKey + "/" + input.model() + "/" + input.serviceKey(),
            "SUCCESS", requestId, input.evidence().trim());
        idem.save(actor.user().id(), "capability-evidence", key, hash, result);
        return result;
    }
}
