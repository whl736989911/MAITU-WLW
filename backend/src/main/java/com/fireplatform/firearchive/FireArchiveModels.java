package com.fireplatform.firearchive;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Unit fire-document metadata projections. No legal/compliance conclusion is represented. */
public final class FireArchiveModels {
    private FireArchiveModels() { }

    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    public record Summary(String id, String tenantId, String title, String category, String archiveNumber,
                          String deviceId, String deviceName, String placeId, String placeName,
                          LocalDate issuedOn, LocalDate expiresOn, String description, String status,
                          long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          String archivedBy, String archivedByName, OffsetDateTime archivedAt, String archivedReason,
                          long attachmentCount) { }

    public record Attachment(String id, String filename, String mimeType, int byteSize, String contentSha256,
                             String uploadedBy, String uploaderName, OffsetDateTime uploadedAt) { }

    public record History(String id, String action, Long revisionBefore, Long revisionAfter, String actorId,
                          String actorName, String reason, OffsetDateTime createdAt) { }

    public record Detail(Summary archive, List<Attachment> attachments) {
        public Detail { attachments = List.copyOf(attachments); }
    }

    public record Page(List<Summary> items, long total, int page, int size) { }

    record Locked(String status, long revision, String deviceId, String placeId) { }

    record StoredAttachment(String id, String archiveId, String tenantId, String filename, String mimeType,
                            int byteSize, String contentSha256, String ciphertext, String nonce, String status) { }

    record Download(String filename, String mimeType, int byteSize, String contentSha256, byte[] content) { }
}
