package com.fireplatform.application;

import com.fireplatform.configuration.DatabaseTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims one captured delivery and records its outcome. The claim transaction revalidates authority, ownership,
 * the frozen configuration and the frozen reference identity, then commits IN_FLIGHT before any network I/O: a
 * crash therefore leaves a durable IN_FLIGHT marker instead of an unrecorded external effect. Re-validation
 * failures terminate as BLOCKED with an immutable attempt row, and nothing is ever automatically re-sent.
 *
 * <p>Identity is resolved per reference kind. Assets approved without a formal product version (legacy decoder
 * registry or vendor discovery metadata) are deliverable, but only when the fact's actual source identity satisfies
 * the referenced identity. A missing prerequisite or a mismatch is recorded, never silently treated as configured
 * or successful.
 */
@Service
public class ApplicationDeliveryProcessor {
    /** A claim is considered abandoned only after every bounded request timeout has certainly elapsed. */
    private static final long STALE_AFTER_MILLIS = 120_000L;

    public record Claim(String outboxId, String tenantId, String applicationId, String outputId, long outputRevision,
                        String endpointOrigin, String endpointDigest, String endpointMasked, String authScheme,
                        String authHeader, int timeoutMs, String mode, String contentType, String urlCipher,
                        String urlNonce, String credentialCipher, String credentialNonce, byte[] payload,
                        long payloadBytes, String payloadSha256, String referenceKind, String frozenDigest,
                        String factDigest, OffsetDateTime claimedAt, int attemptNo, String protocol,
                        String mqttTopicCipher, String mqttTopicNonce, String mqttUsernameCipher,
                        String mqttUsernameNonce, String mqttPasswordCipher, String mqttPasswordNonce) { }

    private record Row(String id, String tenantId, String applicationId, String outputId, long outputRevision,
                       String authorizationId, long authorizationRevision, String authorizationReferenceKind,
                       String authorizationReferenceKey, String deviceId, String productVersionId,
                       String sourcePlatform, String sourceProductKind, String sourceProductId,
                       String sourceDriverKey, String sourceProductDigest, String mode, boolean singleDeviceProof,
                       String contentType, String payloadBase64, String payloadSha256, long payloadBytes,
                       String frozenOrigin, String frozenDigest, String frozenMasked, String frozenAuthScheme,
                       String frozenAuthHeader, int frozenTimeoutMs, int attemptCount, OffsetDateTime claimedAt,
                       String appStatus, Boolean outputEnabled, Long currentOutputRevision, String currentOutputMode,
                       String currentEndpointDigest, String currentAuthScheme, String urlCipher, String urlNonce,
                       String credentialCipher, String credentialNonce, String authStatus, Long currentAuthRevision,
                       String scopeMode, String currentReferenceKind, String currentReferenceKey,
                       String authProductVersionId, String authSourcePlatform, String authLegacyProductId,
                       String authLegacyDecoderSha256, String authVendorModelId, String authDriverKey,
                       String authAdapterDigest, String deviceVersion, String deviceLegacyProductId,
                       OffsetDateTime deviceArchivedAt, Boolean deviceSimulated, long snapshotMember,
                       String discoveryModel, long deviceExists, String currentProtocol,
                       String mqttTopicCipher, String mqttTopicNonce, String mqttUsernameCipher,
                       String mqttUsernameNonce, String mqttPasswordCipher, String mqttPasswordNonce) { }

    private static final String ROW_SQL = "SELECT d.id,d.tenant_id,d.application_id,d.output_id,d.output_revision,"
        + "d.authorization_id,d.authorization_revision,d.authorization_reference_kind,d.authorization_reference_key,"
        + "d.device_id,d.product_version_id,d.source_platform,d.source_product_kind,d.source_product_id,"
        + "d.source_driver_key,d.source_product_digest,d.mode,d.single_device_proof,d.content_type,"
        + "d.payload_base64,d.payload_sha256,d.payload_bytes,d.frozen_endpoint_origin,d.frozen_endpoint_digest,"
        + "d.frozen_endpoint_masked,d.frozen_auth_scheme,d.frozen_auth_header_name,d.frozen_timeout_ms,"
        + "d.attempt_count,d.claimed_at,"
        + "app.status AS app_status,o.enabled AS output_enabled,o.revision AS current_output_revision,"
        + "o.mode AS current_output_mode,o.protocol AS current_protocol,o.endpoint_digest AS current_endpoint_digest,"
        + "o.auth_scheme AS current_auth_scheme,o.endpoint_url_cipher,o.endpoint_url_nonce,o.credential_cipher,"
        + "o.credential_nonce,o.mqtt_topic_cipher,o.mqtt_topic_nonce,o.mqtt_username_cipher,o.mqtt_username_nonce,"
        + "o.mqtt_password_cipher,o.mqtt_password_nonce,"
        + "a.status AS auth_status,a.revision AS current_auth_revision,a.scope_mode,"
        + "a.reference_kind AS current_reference_kind,a.reference_key AS current_reference_key,"
        + "a.product_version_id AS auth_product_version_id,a.source_platform AS auth_source_platform,"
        + "a.legacy_product_id AS auth_legacy_product_id,"
        + "a.legacy_decoder_sha256 AS auth_legacy_decoder_sha256,a.vendor_model_id AS auth_vendor_model_id,"
        + "a.driver_key AS auth_driver_key,a.adapter_digest AS auth_adapter_digest,"
        + "dev.product_version_id AS device_version,dpv.legacy_product_id AS device_legacy_product_id,"
        + "dev.archived_at,dev.is_simulated,"
        + "(SELECT COUNT(*) FROM application_authorization_devices s WHERE s.authorization_id=d.authorization_id"
        + " AND s.tenant_id=d.tenant_id AND s.device_id=d.device_id) AS snapshot_member,"
        + "(SELECT h.model FROM hikvision_discovered_devices h WHERE h.local_device_id=d.device_id"
        + " ORDER BY h.last_seen_at DESC,h.external_id LIMIT 1) AS discovery_model,"
        + "(SELECT COUNT(*) FROM devices x WHERE x.id=d.device_id AND x.tenant_id=d.tenant_id) AS device_exists"
        + " FROM application_delivery_outbox d"
        + " JOIN output_applications app ON app.id=d.application_id AND app.tenant_id=d.tenant_id"
        + " LEFT JOIN application_outputs o ON o.id=d.output_id AND o.tenant_id=d.tenant_id"
        + " LEFT JOIN application_product_authorizations a ON a.id=d.authorization_id AND a.tenant_id=d.tenant_id"
        + " LEFT JOIN devices dev ON dev.id=d.device_id AND dev.tenant_id=d.tenant_id"
        + " LEFT JOIN product_versions dpv ON dpv.id=dev.product_version_id"
        + " WHERE d.id=?";

    private final JdbcTemplate jdbc;
    private final DatabaseTime time;

    public ApplicationDeliveryProcessor(JdbcTemplate jdbc, DatabaseTime time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    /** Returns a claim ready for transport, or null when nothing is deliverable in this pass. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim() {
        // Blocked rows are terminal and are drained in the same call instead of throttling the queue.
        for (int pass = 0; pass < 10; pass++) {
            var ids = jdbc.queryForList("SELECT id FROM application_delivery_outbox WHERE status='PENDING'"
                + " ORDER BY captured_at,id LIMIT 1", String.class);
            if (ids.isEmpty()) return null;
            String id = ids.getFirst();
            var rows = jdbc.query(ROW_SQL, (rs, n) -> new Row(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("application_id"), rs.getString("output_id"), rs.getLong("output_revision"),
                rs.getString("authorization_id"), rs.getLong("authorization_revision"),
                rs.getString("authorization_reference_kind"), rs.getString("authorization_reference_key"),
                rs.getString("device_id"), rs.getString("product_version_id"), rs.getString("source_platform"),
                rs.getString("source_product_kind"), rs.getString("source_product_id"), rs.getString("source_driver_key"),
                rs.getString("source_product_digest"), rs.getString("mode"), rs.getBoolean("single_device_proof"),
                rs.getString("content_type"), rs.getString("payload_base64"), rs.getString("payload_sha256"),
                rs.getLong("payload_bytes"), rs.getString("frozen_endpoint_origin"),
                rs.getString("frozen_endpoint_digest"), rs.getString("frozen_endpoint_masked"),
                rs.getString("frozen_auth_scheme"), rs.getString("frozen_auth_header_name"),
                rs.getInt("frozen_timeout_ms"), rs.getInt("attempt_count"),
                rs.getObject("claimed_at", OffsetDateTime.class), rs.getString("app_status"),
                rs.getObject("output_enabled", Boolean.class), rs.getObject("current_output_revision", Long.class),
                rs.getString("current_output_mode"), rs.getString("current_endpoint_digest"),
                rs.getString("current_auth_scheme"), rs.getString("endpoint_url_cipher"),
                rs.getString("endpoint_url_nonce"), rs.getString("credential_cipher"), rs.getString("credential_nonce"),
                rs.getString("auth_status"), rs.getObject("current_auth_revision", Long.class),
                rs.getString("scope_mode"), rs.getString("current_reference_kind"),
                rs.getString("current_reference_key"), rs.getString("auth_product_version_id"),
                rs.getString("auth_source_platform"), rs.getString("auth_legacy_product_id"),
                rs.getString("auth_legacy_decoder_sha256"), rs.getString("auth_vendor_model_id"),
                rs.getString("auth_driver_key"), rs.getString("auth_adapter_digest"),
                rs.getString("device_version"), rs.getString("device_legacy_product_id"),
                rs.getObject("archived_at", OffsetDateTime.class), rs.getObject("is_simulated", Boolean.class),
                rs.getLong("snapshot_member"), rs.getString("discovery_model"), rs.getLong("device_exists"),
                rs.getString("current_protocol"), rs.getString("mqtt_topic_cipher"),
                rs.getString("mqtt_topic_nonce"), rs.getString("mqtt_username_cipher"),
                rs.getString("mqtt_username_nonce"), rs.getString("mqtt_password_cipher"),
                rs.getString("mqtt_password_nonce")), id);
            if (rows.isEmpty()) return null;
            Row row = rows.getFirst();
            OffsetDateTime now = time.now();
            String blocked = blockReason(row);
            byte[] payload = blocked == null ? payload(row) : null;
            if (blocked == null && payload == null) blocked = "PAYLOAD_INTEGRITY_UNCONFIRMED";
            if (blocked != null) {
                int changed = jdbc.update("UPDATE application_delivery_outbox SET status='BLOCKED',failure_code=?,"
                        + "attempt_count=?,claimed_at=?,finished_at=? WHERE id=? AND status='PENDING'",
                    blocked, row.attemptCount() + 1, now, now, id);
                if (changed == 1) attempt(row, row.attemptCount() + 1, "BLOCKED", null, blocked,
                    row.frozenOrigin(), row.frozenDigest(), row.frozenMasked(), now, now);
                continue;
            }
            int changed = jdbc.update("UPDATE application_delivery_outbox SET status='IN_FLIGHT',attempt_count=?,"
                + "claimed_at=? WHERE id=? AND status='PENDING'", row.attemptCount() + 1, now, id);
            if (changed != 1) continue;
            return new Claim(row.id(), row.tenantId(), row.applicationId(), row.outputId(), row.outputRevision(),
                row.frozenOrigin(), row.frozenDigest(), row.frozenMasked(), row.frozenAuthScheme(),
                row.frozenAuthHeader(), row.frozenTimeoutMs(), row.mode(), row.contentType(), row.urlCipher(),
                row.urlNonce(), row.credentialCipher(), row.credentialNonce(), payload, row.payloadBytes(),
                row.payloadSha256(), row.authorizationReferenceKind(), requiredDigest(row), row.sourceProductDigest(),
                now, row.attemptCount() + 1, protocolOf(row.frozenOrigin()), row.mqttTopicCipher(),
                row.mqttTopicNonce(), row.mqttUsernameCipher(), row.mqttUsernameNonce(),
                row.mqttPasswordCipher(), row.mqttPasswordNonce());
        }
        return null;
    }

    /** Records the terminal result of a claimed delivery; a delivery already closed elsewhere is left untouched. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finish(Claim claim, ApplicationDeliveryTransport.Outcome outcome) {
        OffsetDateTime now = time.now();
        int changed = jdbc.update("UPDATE application_delivery_outbox SET status=?,failure_code=?,http_status=?,"
                + "finished_at=? WHERE id=? AND status='IN_FLIGHT'",
            outcome.status(), outcome.failureCode(), outcome.httpStatus(), now, claim.outboxId());
        if (changed != 1) return;
        long duration = Math.max(0, java.time.Duration.between(claim.claimedAt(), now).toMillis());
        jdbc.update("INSERT INTO application_delivery_attempts(id,tenant_id,outbox_id,attempt_no,output_revision,"
                + "endpoint_origin,endpoint_digest,endpoint_masked,mode,status,http_status,failure_code,"
                + "started_at,finished_at,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), claim.tenantId(), claim.outboxId(), claim.attemptNo(),
            claim.outputRevision(), claim.endpointOrigin(), claim.endpointDigest(), claim.endpointMasked(),
            claim.mode(), outcome.status(), outcome.httpStatus(), outcome.failureCode(), claim.claimedAt(), now,
            duration);
    }

    /**
     * Startup reconciliation for this node: a claimed row whose bounded request window certainly elapsed was
     * interrupted before its result was recorded, so it becomes UNKNOWN. It is never re-sent.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recoverInterrupted() {
        OffsetDateTime now = time.now();
        OffsetDateTime cutoff = now.minus(java.time.Duration.ofMillis(STALE_AFTER_MILLIS));
        var stale = jdbc.query("SELECT id,tenant_id,output_id,output_revision,frozen_endpoint_origin,frozen_endpoint_digest,"
                + "frozen_endpoint_masked,mode,attempt_count,claimed_at FROM application_delivery_outbox"
                + " WHERE status='IN_FLIGHT' AND claimed_at IS NOT NULL AND claimed_at<?",
            (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                rs.getObject(10, OffsetDateTime.class)}, cutoff);
        int recovered = 0;
        for (Object[] row : stale) {
            String id = (String) row[0];
            int changed = jdbc.update("UPDATE application_delivery_outbox SET status='UNKNOWN',"
                    + "failure_code='RECOVERY_INTERRUPTED',finished_at=? WHERE id=? AND status='IN_FLIGHT'", now, id);
            if (changed != 1) continue;
            jdbc.update("INSERT INTO application_delivery_attempts(id,tenant_id,outbox_id,attempt_no,output_revision,"
                    + "endpoint_origin,endpoint_digest,endpoint_masked,mode,status,failure_code,started_at,finished_at,"
                    + "duration_ms) VALUES(?,?,?,?,?,?,?,?,?,'UNKNOWN','RECOVERY_INTERRUPTED',?,?,?)",
                UUID.randomUUID().toString(), row[1], id, (Integer) row[8], (Long) row[3], row[4], row[5], row[6],
                row[7], row[9], now, Math.max(0, java.time.Duration.between((OffsetDateTime) row[9], now).toMillis()));
            recovered++;
        }
        return recovered;
    }

    /** Protocol is frozen by the captured origin: MQTT targets are mqtts:// and every legacy target is HTTPS. */
    private static String protocolOf(String endpointOrigin) {
        return endpointOrigin != null && endpointOrigin.startsWith("mqtts://") ? "MQTT_TLS_3_1_1" : "HTTPS";
    }

    private String blockReason(Row row) {
        if (!"ACTIVE".equals(row.appStatus())) return "APPLICATION_ARCHIVED";
        if (row.currentOutputRevision() == null) return "OUTPUT_MISSING";
        if (!row.mode().equals(row.currentOutputMode())) return "OUTPUT_CONFIGURATION_CHANGED";
        if (row.currentOutputRevision() != row.outputRevision()) return "OUTPUT_CONFIGURATION_CHANGED";
        if (!Boolean.TRUE.equals(row.outputEnabled())) return "OUTPUT_DISABLED";
        if (row.frozenOrigin() == null || row.frozenDigest() == null) return "ENDPOINT_NOT_FROZEN";
        if (!row.frozenDigest().equals(row.currentEndpointDigest())) return "OUTPUT_CONFIGURATION_CHANGED";
        if (!protocolOf(row.frozenOrigin()).equals(row.currentProtocol())) return "OUTPUT_CONFIGURATION_CHANGED";
        if (!row.frozenAuthScheme().equals(row.currentAuthScheme())) return "OUTPUT_CONFIGURATION_CHANGED";
        if (row.urlCipher() == null || row.urlNonce() == null) return "OUTPUT_CONFIGURATION_INCOMPLETE";
        if ("MQTT_TLS_3_1_1".equals(row.currentProtocol())) {
            if (row.mqttTopicCipher() == null || row.mqttTopicNonce() == null
                || row.mqttUsernameCipher() == null || row.mqttUsernameNonce() == null
                || row.mqttPasswordCipher() == null || row.mqttPasswordNonce() == null)
                return "OUTPUT_CONFIGURATION_INCOMPLETE";
        } else if (!"NONE".equals(row.frozenAuthScheme())
            && (row.credentialCipher() == null || row.credentialNonce() == null)) {
            return "OUTPUT_CONFIGURATION_INCOMPLETE";
        }
        if (!"ACTIVE".equals(row.authStatus())) return "AUTHORIZATION_REVOKED";
        if (row.currentAuthRevision() == null || row.currentAuthRevision() != row.authorizationRevision())
            return "AUTHORIZATION_CHANGED";
        if (!row.authorizationReferenceKind().equals(row.currentReferenceKind())
            || !row.authorizationReferenceKey().equals(row.currentReferenceKey())) return "AUTHORIZATION_CHANGED";
        if (row.deviceExists() != 1L) return "DEVICE_NOT_AUTHORIZED";
        if (row.deviceArchivedAt() != null || Boolean.TRUE.equals(row.deviceSimulated()))
            return "DEVICE_NOT_AUTHORIZED";
        if (!"ALL_TENANT".equals(row.scopeMode()) && row.snapshotMember() != 1L) return "DEVICE_NOT_AUTHORIZED";
        if ("SINGLE_DEVICE_RAW".equals(row.mode()) && !row.singleDeviceProof()) return "RAW_ATTRIBUTION_UNPROVEN";
        return identityReason(row);
    }

    /** Reference-kind identity revalidation. Unknown prerequisites and mismatches are both terminal. */
    private static String identityReason(Row row) {
        return switch (row.authorizationReferenceKind()) {
            case "FORMAL_VERSION" -> {
                // reference_kind/reference_key equality above already proves the authorization did not change; a
                // differing fact version is an identity mismatch recorded for the covered asset, never a silent skip.
                if (row.productVersionId() == null || row.authProductVersionId() == null)
                    yield "IDENTITY_PREREQUISITE_MISSING";
                if (!row.productVersionId().equals(row.authProductVersionId())) yield "IDENTITY_MISMATCH";
                if (row.deviceVersion() == null || !row.deviceVersion().equals(row.authProductVersionId()))
                    yield "IDENTITY_MISMATCH";
                yield null;
            }
            case "LEGACY_PRODUCT" -> {
                if (!"LEGACY_PRODUCT".equals(row.sourceProductKind()) || row.sourceProductId() == null)
                    yield "IDENTITY_PREREQUISITE_MISSING";
                if (!row.sourceProductId().equals(row.authLegacyProductId())
                    || row.sourcePlatform() == null || !row.sourcePlatform().equals(row.authSourcePlatform()))
                    yield "IDENTITY_MISMATCH";
                // The decoder source SHA is a real gate once granted: an absent fact digest is unknown evidence,
                // never a pass, and a different digest is a mismatch.
                String required = requiredDigest(row);
                if (required == null || row.sourceProductDigest() == null) yield "IDENTITY_PREREQUISITE_MISSING";
                if (!required.equalsIgnoreCase(row.sourceProductDigest())) yield "IDENTITY_MISMATCH";
                if (row.deviceLegacyProductId() != null
                    && !row.deviceLegacyProductId().equals(row.authLegacyProductId()))
                    yield "IDENTITY_MISMATCH";
                yield null;
            }
            case "VENDOR_MODEL" -> {
                if (!"VENDOR_MODEL".equals(row.sourceProductKind()) || row.sourceProductId() == null)
                    yield "IDENTITY_PREREQUISITE_MISSING";
                if (!row.sourceProductId().equals(row.authVendorModelId())
                    || row.sourcePlatform() == null || !row.sourcePlatform().equals(row.authSourcePlatform()))
                    yield "IDENTITY_MISMATCH";
                if (row.authDriverKey() != null && row.sourceDriverKey() != null
                    && !row.authDriverKey().equalsIgnoreCase(row.sourceDriverKey()))
                    yield "IDENTITY_MISMATCH";
                // Same fail-closed rule for the adapter artifact digest.
                String required = requiredDigest(row);
                if (required == null || row.sourceProductDigest() == null) yield "IDENTITY_PREREQUISITE_MISSING";
                if (!required.equalsIgnoreCase(row.sourceProductDigest())) yield "IDENTITY_MISMATCH";
                if (row.discoveryModel() != null && !row.discoveryModel().equals(row.authVendorModelId()))
                    yield "IDENTITY_MISMATCH";
                yield null;
            }
            default -> "AUTHORIZATION_CHANGED";
        };
    }

    /** The digest the authorization froze for its kind; null means this kind has no digest gate (formal version). */
    private static String requiredDigest(Row row) {
        return switch (row.authorizationReferenceKind()) {
            case "LEGACY_PRODUCT" -> row.authLegacyDecoderSha256();
            case "VENDOR_MODEL" -> row.authAdapterDigest();
            default -> null;
        };
    }

    private static byte[] payload(Row row) {
        try {
            byte[] bytes = Base64.getDecoder().decode(row.payloadBase64());
            if (bytes.length == 0 || bytes.length != row.payloadBytes()) return null;
            return sha256(bytes).equals(row.payloadSha256()) ? bytes : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void attempt(Row row, int attemptNo, String status, Integer httpStatus, String failureCode,
                         String origin, String digest, String masked,
                         OffsetDateTime started, OffsetDateTime finished) {
        jdbc.update("INSERT INTO application_delivery_attempts(id,tenant_id,outbox_id,attempt_no,output_revision,"
                + "endpoint_origin,endpoint_digest,endpoint_masked,mode,status,http_status,failure_code,"
                + "started_at,finished_at,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), row.tenantId(), row.id(), attemptNo, row.outputRevision(), origin, digest,
            masked, row.mode(), status, httpStatus, failureCode, started, finished,
            Math.max(0, java.time.Duration.between(started, finished).toMillis()));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
