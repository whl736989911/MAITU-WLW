package com.fireplatform.integration;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable common contract between source producers (legacy ingest, generic message apply, Hikvision importer)
 * and the open-application output domain. A producer builds exactly one fact per applied business fact and calls
 * {@link BusinessFactPublisher#publish} inside its own database transaction.
 *
 * <p>Approved ingress modes may carry assets without a formal product version: the legacy decoder registry and
 * vendor discovery metadata are authoritative identities too. The fact therefore carries the source identity kind,
 * the actual source product/model id, the adapter/driver key and the decoder/adapter revision when known. Nothing
 * here is inferred from a device name or from "the first model".
 *
 * <p>Fields are intentionally transport-neutral:
 * <ul>
 *   <li>{@code factId} — immutable, unique and stable across a replay. It is the delivery idempotency seed.</li>
 *   <li>{@code productVersionId} — the formal product version, when the asset has one.</li>
 *   <li>{@code sourceProductKind}/{@code sourceProductId} — the legacy product id or vendor model id actually
 *       observed for this device in this fact's source. Must be the authoritative value, never a guess.</li>
 *   <li>{@code sourceProductDigest} — the exact lowercase hex SHA-256 of the decoder/adapter source that produced
 *       this fact (legacy: the decoder revision source hash; vendor: the shipped adapter dictionary digest), or
 *       {@code null} when it cannot be computed. Version numbers are never inferred and never substituted here.</li>
 *   <li>{@code normalizedJson} — the producer's already-serialized normalized document. The output domain never
 *       re-serializes it and never reinterprets it.</li>
 *   <li>{@code originalRawBase64} / {@code originalContentType} — the producer's exact original bytes. Raw output
 *       posts these bytes unchanged; it never re-encodes a parsed model.</li>
 *   <li>{@code singleDeviceRaw} — the producer's explicit assertion that the original bytes describe exactly one
 *       device and only that device's own unit. A mixed vendor callback (for instance a Hikvision callback that
 *       carries several resources or units) must be published with {@code false}.</li>
 *   <li>{@code externalEffectsAllowed} — replay/backfill producers pass {@code false}; such facts are never
 *       enqueued for external delivery.</li>
 * </ul>
 */
public record NormalizedBusinessFact(
    String factId,
    String tenantId,
    String deviceId,
    String productVersionId,
    String legacyProductId,
    String sourcePlatform,
    String sourceProductKind,
    String sourceProductId,
    String sourceDriverKey,
    String sourceProductDigest,
    String sourceMessageId,
    String sourceEventType,
    OffsetDateTime sourceEventTime,
    OffsetDateTime receivedAt,
    String alarmId,
    String normalizedJson,
    String originalRawBase64,
    String originalContentType,
    boolean singleDeviceRaw,
    boolean externalEffectsAllowed) {

    public static final String LEGACY_PRODUCT = "LEGACY_PRODUCT";
    public static final String VENDOR_MODEL = "VENDOR_MODEL";
    private static final Set<String> SOURCE_PRODUCT_KINDS = Set.of(LEGACY_PRODUCT, VENDOR_MODEL);

    public NormalizedBusinessFact {
        if (factId == null || factId.isBlank() || factId.length() > 160)
            throw new IllegalArgumentException("factId must be a non-blank value of at most 160 characters");
        if (sourcePlatform == null || sourcePlatform.isBlank() || sourcePlatform.length() > 64)
            throw new IllegalArgumentException("sourcePlatform must be a non-blank value of at most 64 characters");
        sourcePlatform = sourcePlatform.trim().toLowerCase(Locale.ROOT);
        // A declared kind without an actual id is unknown attribution, not a producer crash.
        String kind = sourceProductKind == null ? null : sourceProductKind.trim().toUpperCase(Locale.ROOT);
        if (kind == null || !SOURCE_PRODUCT_KINDS.contains(kind) || sourceProductId == null
            || sourceProductId.isBlank()) { kind = null; sourceProductId = null; }
        sourceProductKind = kind;
        if (sourceProductId != null && sourceProductId.length() > 200)
            throw new IllegalArgumentException("sourceProductId exceeds 200 characters");
        if (sourceDriverKey != null && sourceDriverKey.length() > 64)
            throw new IllegalArgumentException("sourceDriverKey exceeds 64 characters");
        // Only an exact lowercase SHA-256 counts as a decoder/adapter revision. Anything else is unknown, never
        // coerced into a version or an inferred value.
        if (sourceProductDigest != null) {
            String digest = sourceProductDigest.trim().toLowerCase(Locale.ROOT);
            sourceProductDigest = digest.matches("[0-9a-f]{64}") ? digest : null;
        }
        if (sourceMessageId != null && sourceMessageId.length() > 160)
            throw new IllegalArgumentException("sourceMessageId exceeds 160 characters");
        if (sourceEventType != null && sourceEventType.length() > 64)
            throw new IllegalArgumentException("sourceEventType exceeds 64 characters");
        if (alarmId != null && alarmId.length() > 64) throw new IllegalArgumentException("alarmId exceeds 64 characters");
        if (tenantId != null && tenantId.length() > 64) throw new IllegalArgumentException("tenantId exceeds 64 characters");
        if (deviceId != null && deviceId.length() > 64) throw new IllegalArgumentException("deviceId exceeds 64 characters");
        if (productVersionId != null && productVersionId.length() > 64)
            throw new IllegalArgumentException("productVersionId exceeds 64 characters");
        if (legacyProductId != null && legacyProductId.length() > 64)
            throw new IllegalArgumentException("legacyProductId exceeds 64 characters");
    }

    /** A fact can only be delivered once its attribution is fully known. */
    public boolean attributed() {
        return tenantId != null && !tenantId.isBlank() && deviceId != null && !deviceId.isBlank()
            && receivedAt != null;
    }

    /** Identity key this fact can prove for one authorization kind; null means the prerequisite is unknown. */
    public String identityValue(String referenceKind) {
        if (LEGACY_PRODUCT.equals(referenceKind)) return LEGACY_PRODUCT.equals(sourceProductKind) ? sourceProductId : null;
        if (VENDOR_MODEL.equals(referenceKind)) return VENDOR_MODEL.equals(sourceProductKind) ? sourceProductId : null;
        if ("FORMAL_VERSION".equals(referenceKind)) return productVersionId;
        return null;
    }
}
