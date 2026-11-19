package com.fireplatform.legacyingest.importer;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirmation layer for the legacy importer: current session authorization, source digest CAS and replay-safe
 * idempotency. It only orchestrates; row semantics stay in {@link LegacyDictionaryImporter}.
 */
@Service
public class LegacyAdminImportCommands {
    private static final int MAX_COMPANIES = 500;
    private final LegacyDictionaryImporter importer;
    private final ManagementGuard guard;
    private final IdempotencyStore idempotency;
    private final AuditService audit;

    public LegacyAdminImportCommands(LegacyDictionaryImporter importer, ManagementGuard guard,
                                     IdempotencyStore idempotency, AuditService audit) {
        this.importer = importer;
        this.guard = guard;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional
    public LegacyDictionaryImporter.ImportStats confirmDictionaries(AuthService.Identity actor, String expectedSha, String reason, String idemKey, String requestId) {
        validate(reason);
        idempotency.validateKey(idemKey);
        requireDigest(expectedSha);
        guard.begin(actor, "legacy-ingest:write", null, "management");
        String hash = idempotency.fingerprint(List.of("DICTIONARIES", expectedSha, reason.trim()));
        var replay = idempotency.replay(actor.user().id(), "LEGACY_DICT_IMPORT", idemKey, hash,
            LegacyDictionaryImporter.ImportStats.class);
        if (replay != null) return replay;
        requireCurrentDigest(importer.sourceDigest(), expectedSha);
        var stats = importer.importDictionaries(actor.user().id());
        audit.recordReason(actor.user().id(), null, "LEGACY_DICTIONARIES_IMPORTED", expectedSha, "SUCCESS", requestId, reason);
        idempotency.save(actor.user().id(), "LEGACY_DICT_IMPORT", idemKey, hash, stats);
        return stats;
    }

    @Transactional
    public LegacyDictionaryImporter.PilotDeviceImportStats confirmDevices(AuthService.Identity actor, String expectedSha, List<String> companyIds, String reason, String idemKey, String requestId) {
        validate(reason);
        idempotency.validateKey(idemKey);
        requireDigest(expectedSha);
        var companies = normalize(companyIds);
        if (companies.isEmpty()) throw ApiError.bad("必须明确选择至少一个来源单位");
        var known = importer.sourceCompanyIds();
        if (!known.containsAll(companies)) throw ApiError.bad("来源单位不在当前旧平台台账中，请先刷新预览");
        guard.begin(actor, "legacy-ingest:write", null, "management");
        String hash = idempotency.fingerprint(List.of("DEVICES", expectedSha, String.join(",", companies), reason.trim()));
        var replay = idempotency.replay(actor.user().id(), "LEGACY_DEVICE_IMPORT", idemKey, hash,
            LegacyDictionaryImporter.PilotDeviceImportStats.class);
        if (replay != null) return replay;
        requireCurrentDigest(importer.deviceSourceDigest(), expectedSha);
        var stats = importer.importDevices(UUID.randomUUID().toString(), companies, actor.user().id());
        audit.recordReason(actor.user().id(), null, "LEGACY_DEVICES_IMPORTED", expectedSha + ":" + companies.size(), "SUCCESS", requestId, reason);
        idempotency.save(actor.user().id(), "LEGACY_DEVICE_IMPORT", idemKey, hash, stats);
        return stats;
    }

    private static void validate(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw ApiError.bad("必须填写导入原因");
    }

    private static void requireDigest(String expectedSha) {
        if (expectedSha == null || !expectedSha.matches("[0-9a-fA-F]{64}")) throw ApiError.bad("预览摘要无效，请重新预览");
    }

    private static void requireCurrentDigest(String current, String expected) {
        if (!MessageDigest.isEqual(current.getBytes(StandardCharsets.US_ASCII), expected.toLowerCase().getBytes(StandardCharsets.US_ASCII)))
            throw ApiError.conflict("PREVIEW_CHANGED", "来源归档在预览后已变化，请重新预览");
    }

    private static List<String> normalize(List<String> companyIds) {
        if (companyIds == null) return List.of();
        if (companyIds.size() > MAX_COMPANIES) throw ApiError.bad("来源单位数量超过上限");
        var result = new LinkedHashSet<String>();
        for (String id : companyIds) {
            if (id == null || id.isBlank() || id.length() > 64 || !id.equals(id.trim())) throw ApiError.bad("来源单位ID无效");
            result.add(id);
        }
        return result.stream().sorted().toList();
    }
}
