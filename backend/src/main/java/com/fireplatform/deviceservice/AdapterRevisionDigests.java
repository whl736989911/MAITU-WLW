package com.fireplatform.deviceservice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Exact adapter revision evidence for vendor-model authorizations. The adapter revision is not a version number:
 * it is a deterministic SHA-256 framed over the actual Hikvision normalization implementation class resources plus
 * the shipped code dictionary. Parser code can therefore change while the catalog stays byte-identical and the
 * revision still moves. The digest is computed once per process and never per fact.
 *
 * <p>Supporting producers call {@link #digestFor(String)} — the same bean and the same deployed build — so the fact's
 * frozen digest and the authorization's frozen digest are comparable by construction. If any artifact is missing or
 * unreadable the digest is absent and a vendor-model grant is refused as a missing prerequisite; no version string
 * is invented and no Git revision is claimed.
 */
@Service
public final class AdapterRevisionDigests {
    private static final List<String> HIKVISION_ARTIFACTS = List.of(
        "com/fireplatform/hikvision/HikvisionIngestion.class",
        "com/fireplatform/hikvision/HikvisionCatalog.class",
        "com/fireplatform/hikvision/HikvisionEventCatalog.class",
        "hikvision-code-catalog.json");

    private final Map<String, String> digests;

    public AdapterRevisionDigests() {
        String hikvision = framedDigest(HIKVISION_ARTIFACTS);
        this.digests = hikvision == null ? Map.of() : Map.of("HIKVISION_FIRECLOUD", hikvision);
    }

    /** Stable API for authorization writes and source producers; null means the evidence is unavailable. */
    public String digestFor(String driverKey) {
        if (driverKey == null) return null;
        return digests.get(driverKey.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Fixed artifact order, each framed as name UTF-8 + NUL + 4-byte big-endian length + bytes, so the digest is
     * reproducible and cannot be confused by concatenation ambiguity. Any missing artifact fails the whole digest.
     */
    private static String framedDigest(List<String> artifacts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String artifact : artifacts) {
                byte[] bytes = resource(artifact);
                if (bytes == null) return null;
                digest.update(artifact.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(new byte[]{(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
                    (byte) (bytes.length >>> 8), (byte) bytes.length});
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            return null;
        }
    }

    private static byte[] resource(String name) {
        String path = name.startsWith("/") ? name : "/" + name;
        try (InputStream input = AdapterRevisionDigests.class.getResourceAsStream(path)) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException unreadable) {
            return null;
        }
    }
}
