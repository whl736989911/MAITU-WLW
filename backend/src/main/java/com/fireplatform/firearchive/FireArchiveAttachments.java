package com.fireplatform.firearchive;

import com.fireplatform.shared.DataTransferPayloadCipher;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Encrypted bounded attachment storage reusing the existing payload cipher; no plaintext fallback or paths. */
@Service
public class FireArchiveAttachments {
    private final JdbcTemplate jdbc;
    private final FireArchiveCommands commands;
    private final FireArchiveQueries queries;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final DataTransferPayloadCipher cipher;
    private final DatabaseTime time;

    public FireArchiveAttachments(JdbcTemplate jdbc, FireArchiveCommands commands, FireArchiveQueries queries,
                                  IdempotencyStore idem, AuditService audit, DataTransferPayloadCipher cipher,
                                  DatabaseTime time) {
        this.jdbc = jdbc; this.commands = commands; this.queries = queries; this.idem = idem; this.audit = audit;
        this.cipher = cipher; this.time = time;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FireArchiveModels.Detail upload(AuthService.Identity actor, String tenant, String archiveId, String key,
                                           FireArchiveInputs.Attachment input, String requestId) {
        commands.begin(actor, tenant, key);
        FireArchiveInputs.archiveId(archiveId);
        commands.requireVisibleArchive(actor.user().id(), tenant, archiveId);
        String sha = sha256(input.content());
        String operation = "fire-archive-attachment-add";
        String hash = idem.fingerprint(List.of(tenant, archiveId, input.expectedRevision(), input.filename(),
            input.mimeType(), sha, input.content().length, input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, FireArchiveModels.Detail.class);
        if (replay != null) return replay;
        var locked = commands.lockAndCheck(actor.user().id(), tenant, archiveId, input.expectedRevision());
        if (!"ACTIVE".equals(locked.status())) throw ApiError.conflict("ARCHIVE_STATE", "已归档档案只读，不能新增附件");
        if (!cipher.ready()) throw new ApiError(503, "ATTACHMENT_ENCRYPTION_UNAVAILABLE", "未配置附件加密，未保存附件内容");
        String attachmentId = UUID.randomUUID().toString();
        var encrypted = cipher.encryptScope(scope(tenant, archiveId, attachmentId), input.content());
        OffsetDateTime now = time.now();
        jdbc.update("INSERT INTO fire_archive_attachments(id,tenant_id,archive_id,filename,mime_type,byte_size,"
                + "content_sha256,ciphertext,nonce,uploaded_by,uploaded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            attachmentId, tenant, archiveId, input.filename(), input.mimeType(), input.content().length, sha,
            encrypted.ciphertext(), encrypted.nonce(), actor.user().id(), now);
        commands.history(tenant, archiveId, "ATTACHMENT_ADD", locked.revision(), locked.revision(),
            actor.user().id(), input.reason(), now);
        return commands.saved(actor, tenant, archiveId, operation, key, hash, requestId,
            "FIRE_ARCHIVE_ATTACHMENT_ADD");
    }

    @Transactional
    public FireArchiveModels.Download download(AuthService.Identity actor, String tenant,
                                               ScopeFilter.Selection selection, String archiveId,
                                               String attachmentId, String requestId) {
        var stored = queries.storedAttachment(actor.user().id(), tenant, selection, archiveId, attachmentId);
        if (!cipher.ready()) throw new ApiError(503, "ATTACHMENT_ENCRYPTION_UNAVAILABLE", "未配置附件加密，无法读取附件");
        byte[] content;
        try {
            content = cipher.decryptScopeBytes(scope(stored.tenantId(), stored.archiveId(), stored.id()),
                stored.ciphertext(), stored.nonce());
        } catch (RuntimeException failure) {
            throw new ApiError(503, "ATTACHMENT_UNREADABLE", "附件无法解密或完整性校验失败");
        }
        if (content.length != stored.byteSize() || !MessageDigest.isEqual(sha256(content).getBytes(StandardCharsets.US_ASCII),
            stored.contentSha256().getBytes(StandardCharsets.US_ASCII)))
            throw new ApiError(503, "ATTACHMENT_UNREADABLE", "附件完整性校验失败");
        audit.record(actor.user().id(), stored.tenantId(), "FIRE_ARCHIVE_ATTACHMENT_DOWNLOAD", stored.id(), "SUCCESS", requestId);
        return new FireArchiveModels.Download(stored.filename(), stored.mimeType(), stored.byteSize(),
            stored.contentSha256(), content);
    }

    private static String scope(String tenant, String archiveId, String attachmentId) {
        return "firearchive-attachment-v1\0" + tenant + "\0" + archiveId + "\0" + attachmentId;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
