package com.fireplatform.integration;

import com.fireplatform.configuration.DatabaseTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the durable delivery outbox for one applied business fact, inside the producer's own transaction. It
 * performs no external I/O, acquires no coordination lock and never raises for an ordinary business refusal:
 * a missing output must not roll back ingestion.
 *
 * <p>Capture is scope-based, not identity-based: every enabled output whose active authorization covers the device
 * (whole unit, or the explicit snapshot) gets one durable row, together with the frozen reference identity. The
 * delivery processor then decides whether the fact's actual source identity satisfies that reference. A missing
 * prerequisite or a mismatch becomes an auditable BLOCKED attempt instead of a silent skip or a fabricated success.
 * That is what keeps approved unversioned legacy/vendor assets usable without inventing a formal product version.
 */
@Service
public class BusinessFactPublisher {
    private static final Logger log = LoggerFactory.getLogger(BusinessFactPublisher.class);
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final Pattern CONTENT_TYPE =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}");

    private record Route(String applicationId, String outputId, long outputRevision, String mode, String origin,
                         String endpointDigest, String endpointMasked, String authScheme, String authHeader,
                         int timeoutMs, String authorizationId, long authorizationRevision, String referenceKind,
                         String referenceKey, String scopeMode, String productVersionId, String sourcePlatform,
                         String legacyProductId, String legacyDecoderSha256, String vendorModelId,
                         String driverKey, String adapterDigest) { }

    private final JdbcTemplate jdbc;
    private final DatabaseTime time;
    /** Savepoint scope for non-critical capture SQL; never a new transaction, so the outbox stays atomic. */
    private final TransactionTemplate savepoint;

    public BusinessFactPublisher(JdbcTemplate jdbc, DatabaseTime time, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.time = time;
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.savepoint = template;
    }

    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public void publish(NormalizedBusinessFact fact) {
        if (fact == null) throw new IllegalArgumentException("fact is required");
        // Replay/backfill facts are intentionally inert: they never create an external delivery.
        if (!fact.externalEffectsAllowed()) return;
        if (!fact.attributed()) return;
        if (!ownedDevice(fact.tenantId(), fact.deviceId())) return;
        Long enabled = jdbc.queryForObject(
            "SELECT COUNT(*) FROM application_outputs WHERE tenant_id=? AND enabled=TRUE", Long.class, fact.tenantId());
        if (enabled == null || enabled == 0L) return;

        byte[] normalized = fact.normalizedJson() == null ? null
            : fact.normalizedJson().getBytes(StandardCharsets.UTF_8);
        byte[] raw = decodeRaw(fact);
        if (normalized == null && raw == null) return;

        for (Route route : choose(routes(fact), fact)) {
            byte[] payload;
            String contentType;
            if ("SINGLE_DEVICE_RAW".equals(route.mode())) {
                // Raw forwarding requires the producer's explicit single-device assertion and its original bytes.
                if (raw == null || !fact.singleDeviceRaw()) continue;
                payload = raw;
                contentType = contentType(fact.originalContentType());
            } else {
                if (normalized == null) continue;
                payload = normalized;
                contentType = "application/json";
            }
            if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
                log.debug("application output skipped oversized payload output={}", route.outputId());
                continue;
            }
            // PostgreSQL aborts the whole transaction on a constraint error even when the exception is caught, so
            // capture SQL runs in a savepoint. A refusal rolls back only to that savepoint; the source transaction
            // and every other route stay healthy, and the outbox still commits atomically with the source.
            try {
                savepoint.executeWithoutResult(status -> enqueue(fact, route, payload, contentType));
            } catch (DataAccessException refused) {
                log.debug("application output capture refused output={} type={}", route.outputId(),
                    refused.getClass().getSimpleName());
            }
        }
    }

    /**
     * One candidate per output:
     * <ul>
     *   <li>A proven identity equal to the reference is captured (exact when the digest also matches).</li>
     *   <li>A proven identity different from the reference is not authorized and is not captured. This applies to
     *       a proven formal version that matches no grant, and to a contradicting legacy/vendor identity.</li>
     *   <li>A missing identity for a scope-covered asset is captured so the processor records the prerequisite.
     *       For a formal route that includes a fact whose formal version is absent.</li>
     * </ul>
     * Formal routes are platform-agnostic (their authorization stores no source platform); source kinds require the
     * platform to match. Among captured routes the fully matching one wins, otherwise the closest kind for the
     * fact's platform is used, deterministically.
     */
    private static List<Route> choose(List<Route> routes, NormalizedBusinessFact fact) {
        boolean provesAnyIdentity = fact.sourceProductKind() != null || fact.productVersionId() != null;
        var selected = new LinkedHashMap<String, Route>();
        var matched = new LinkedHashMap<String, Boolean>();
        for (Route route : routes) {
            boolean formal = "FORMAL_VERSION".equals(route.referenceKind());
            String identity = fact.identityValue(route.referenceKind());
            if (identity == null) {
                // Formal: missing fact version on a covered asset is a visible prerequisite. Source kinds: only when
                // the fact proves no identity at all, otherwise it is simply not that kind of fact.
                if (!formal && provesAnyIdentity) continue;
            } else {
                boolean sameIdentity = identity.equals(routeIdentity(route));
                boolean samePlatform = formal || route.sourcePlatform() == null
                    || fact.sourcePlatform().equalsIgnoreCase(route.sourcePlatform());
                if (!sameIdentity || !samePlatform) continue;
            }
            String output = route.outputId();
            boolean exact = matches(route, fact);
            Route current = selected.get(output);
            if (current == null) {
                selected.put(output, route);
                matched.put(output, exact);
                continue;
            }
            boolean currentExact = matched.get(output);
            if (exact && !currentExact) {
                selected.put(output, route);
                matched.put(output, true);
            } else if (exact == currentExact && priority(route, fact) < priority(current, fact)) {
                selected.put(output, route);
            }
        }
        return List.copyOf(selected.values());
    }

    /** Attribution order for a fact that could not prove an identity: declared kind, same platform, then formal. */
    private static int priority(Route route, NormalizedBusinessFact fact) {
        if (route.referenceKind().equals(fact.sourceProductKind())) return 0;
        if (!"FORMAL_VERSION".equals(route.referenceKind()) && route.sourcePlatform() != null
            && route.sourcePlatform().equalsIgnoreCase(fact.sourcePlatform())) return 1;
        if ("FORMAL_VERSION".equals(route.referenceKind())) return 2;
        return 3;
    }

    private List<Route> routes(NormalizedBusinessFact fact) {
        var rows = jdbc.query("SELECT o.application_id,o.id,o.revision,o.mode,o.endpoint_origin,o.endpoint_digest,"
                + "o.endpoint_masked,o.auth_scheme,o.auth_header_name,o.timeout_ms,"
                + "a.id,a.revision,a.reference_kind,a.reference_key,a.scope_mode,a.product_version_id,"
                + "a.source_platform,a.legacy_product_id,a.legacy_decoder_sha256,"
                + "a.vendor_model_id,a.driver_key,a.adapter_digest"
                + " FROM application_outputs o"
                + " JOIN output_applications app ON app.id=o.application_id AND app.tenant_id=o.tenant_id"
                + " AND app.status='ACTIVE'"
                + " JOIN application_product_authorizations a ON a.application_id=o.application_id"
                + " AND a.tenant_id=o.tenant_id AND a.status='ACTIVE'"
                + " WHERE o.tenant_id=? AND o.enabled=TRUE AND (a.scope_mode='ALL_TENANT' OR EXISTS("
                + "SELECT 1 FROM application_authorization_devices s WHERE s.authorization_id=a.id"
                + " AND s.tenant_id=a.tenant_id AND s.device_id=?))"
                + " ORDER BY o.id,a.reference_kind,a.id",
            (rs, n) -> new Route(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10), rs.getString(11),
                rs.getLong(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16),
                rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getString(21),
                rs.getString(22)),
            fact.tenantId(), fact.deviceId());
        var eligible = new ArrayList<Route>(rows.size());
        for (Route route : rows) {
            // A frozen origin is required: a draft output cannot be enabled, but a truncated configuration must
            // never be captured as if it were sendable.
            if (route.origin() == null || route.endpointDigest() == null) continue;
            if (!"NORMALIZED".equals(route.mode()) && !"SINGLE_DEVICE_RAW".equals(route.mode())) continue;
            eligible.add(route);
        }
        return List.copyOf(eligible);
    }

    /** Exactness for preference only: formal = same version; source kinds = same identity, platform and digest. */
    private static boolean matches(Route route, NormalizedBusinessFact fact) {
        String identity = fact.identityValue(route.referenceKind());
        if (identity == null || !identity.equals(routeIdentity(route))) return false;
        if ("FORMAL_VERSION".equals(route.referenceKind())) return true;
        if (route.sourcePlatform() == null || !fact.sourcePlatform().equalsIgnoreCase(route.sourcePlatform()))
            return false;
        if (NormalizedBusinessFact.LEGACY_PRODUCT.equals(route.referenceKind())) {
            return digestExact(route.legacyDecoderSha256(), fact.sourceProductDigest());
        }
        if (NormalizedBusinessFact.VENDOR_MODEL.equals(route.referenceKind())) {
            if (route.driverKey() != null && fact.sourceDriverKey() != null
                && !route.driverKey().equalsIgnoreCase(fact.sourceDriverKey())) return false;
            return digestExact(route.adapterDigest(), fact.sourceProductDigest());
        }
        return true;
    }

    private void enqueue(NormalizedBusinessFact fact, Route route, byte[] payload, String contentType) {
        // At most one delivery per (fact, output): a later configuration revision must never re-send the same fact.
        Long captured = jdbc.queryForObject("SELECT COUNT(*) FROM application_delivery_outbox WHERE fact_id=?"
            + " AND output_id=?", Long.class, fact.factId(), route.outputId());
        if (captured != null && captured > 0) return;
        jdbc.update("INSERT INTO application_delivery_outbox(id,tenant_id,application_id,output_id,"
                    + "output_revision,authorization_id,authorization_revision,authorization_reference_kind,"
                    + "authorization_reference_key,fact_id,device_id,product_version_id,source_platform,"
                    + "source_product_kind,source_product_id,source_driver_key,source_product_digest,"
                    + "source_message_id,source_event_type,source_event_at,received_at,alarm_id,mode,"
                    + "single_device_proof,content_type,payload_base64,payload_sha256,payload_bytes,"
                    + "frozen_endpoint_origin,frozen_endpoint_digest,frozen_endpoint_masked,frozen_auth_scheme,"
                    + "frozen_auth_header_name,frozen_timeout_ms,status,attempt_count,captured_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?)",
                UUID.randomUUID().toString(), fact.tenantId(), route.applicationId(), route.outputId(),
                route.outputRevision(), route.authorizationId(), route.authorizationRevision(),
                route.referenceKind(), route.referenceKey(), fact.factId(), fact.deviceId(),
                fact.productVersionId(), fact.sourcePlatform(), fact.sourceProductKind(), fact.sourceProductId(),
                fact.sourceDriverKey(), fact.sourceProductDigest(), fact.sourceMessageId(), fact.sourceEventType(),
                fact.sourceEventTime(), fact.receivedAt(), fact.alarmId(), route.mode(), fact.singleDeviceRaw(),
                contentType, Base64.getEncoder().encodeToString(payload), sha256(payload), payload.length,
                route.origin(), route.endpointDigest(), route.endpointMasked(), route.authScheme(),
                route.authHeader(), route.timeoutMs(), time.now());
    }

    /** Attribution is only accepted for a live, real device that still belongs to the fact's unit. */
    private boolean ownedDevice(String tenant, String device) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND id=?"
            + " AND archived_at IS NULL AND is_simulated=FALSE", Long.class, tenant, device);
        return count != null && count == 1L;
    }

    private static String routeIdentity(Route route) {
        return switch (route.referenceKind()) {
            case "FORMAL_VERSION" -> route.productVersionId();
            case "LEGACY_PRODUCT" -> route.legacyProductId();
            case "VENDOR_MODEL" -> route.vendorModelId();
            default -> null;
        };
    }

    /**
     * Route preference only: an authorization that froze a real digest counts as exact only when the fact proves the
     * same digest. A missing fact digest is not compatible here, but the route is still captured so the processor can
     * block it with the truthful prerequisite code instead of dropping the evidence.
     */
    private static boolean digestExact(String authorized, String observed) {
        return authorized != null && observed != null && authorized.equalsIgnoreCase(observed);
    }

    private static byte[] decodeRaw(NormalizedBusinessFact fact) {
        String encoded = fact.originalRawBase64();
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_PAYLOAD_BYTES * 2L) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES ? null : bytes;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String contentType(String declared) {
        if (declared == null) return "application/octet-stream";
        String normalized = declared.trim();
        return CONTENT_TYPE.matcher(normalized).matches() ? normalized : "application/octet-stream";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
