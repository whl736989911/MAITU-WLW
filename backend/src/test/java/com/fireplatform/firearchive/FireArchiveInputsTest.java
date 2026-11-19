package com.fireplatform.firearchive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FireArchiveInputsTest {
    private static void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiError.class)
            .satisfies(error -> assertThat(((ApiError) error).status).isEqualTo(400));
    }

    private static FireArchiveInputs.CreateBody body(String title, String category) {
        return new FireArchiveInputs.CreateBody(title, category, null, null, null, null, null, null,
            "Synthetic reason");
    }

    @Test
    void metadataIsTrimmedAndUnknownValuesStayNull() {
        var create = FireArchiveInputs.create(body("  档案标题  ", "  单位自填分类 "));
        assertThat(create.metadata().title()).isEqualTo("档案标题");
        assertThat(create.metadata().category()).isEqualTo("单位自填分类");
        assertThat(create.metadata().archiveNumber()).isNull();
        assertThat(create.metadata().deviceId()).isNull();
        assertThat(create.metadata().placeId()).isNull();
        assertThat(create.metadata().issuedOn()).isNull();
        assertThat(create.metadata().expiresOn()).isNull();
        assertThat(create.metadata().description()).isNull();
    }

    @Test
    void metadataRejectsBlankControlsMalformedSurrogatesAndOverLength() {
        bad(() -> FireArchiveInputs.create(body(" ", "分类")));
        bad(() -> FireArchiveInputs.create(body("标题\u0000", "分类")));
        bad(() -> FireArchiveInputs.create(body("\uD800", "分类")));
        bad(() -> FireArchiveInputs.create(new FireArchiveInputs.CreateBody("😀".repeat(101), "分类", null, null,
            null, null, null, null, "reason")));
        bad(() -> FireArchiveInputs.create(body("标题", "")));
    }

    @Test
    void datesMustBeIsoLocalDatesAndNeverInferred() {
        var create = FireArchiveInputs.create(new FireArchiveInputs.CreateBody(" t ", " c ", null, null, null,
            "2026-01-02", "2027-12-31", "", "reason"));
        assertThat(create.metadata().issuedOn()).hasToString("2026-01-02");
        assertThat(create.metadata().expiresOn()).hasToString("2027-12-31");
        assertThat(create.metadata().description()).isNull();
        bad(() -> FireArchiveInputs.create(new FireArchiveInputs.CreateBody("t", "c", null, null, null, "2026-02-30",
            null, null, "reason")));
        bad(() -> FireArchiveInputs.create(new FireArchiveInputs.CreateBody("t", "c", null, null, null, "2026-01-02T00:00:00Z",
            null, null, "reason")));
    }

    @Test
    void filenamesCannotCarryPathsOrControls() {
        String content = Base64.getEncoder().encodeToString("ok".getBytes(StandardCharsets.UTF_8));
        assertThat(FireArchiveInputs.attachment(attachment("报告.pdf", "application/pdf", content)).filename())
            .isEqualTo("报告.pdf");
        for (String name : Arrays.asList(".", "..", "a/b.txt", "a\\b.txt", "a\u0000b", " ", "x".repeat(201)))
            bad(() -> FireArchiveInputs.attachment(attachment(name, "application/pdf", content)));
    }

    @Test
    void mimeTypeMustBeBareBoundedToken() {
        String content = Base64.getEncoder().encodeToString("ok".getBytes(StandardCharsets.UTF_8));
        bad(() -> FireArchiveInputs.attachment(attachment("a.pdf", "text/plain; charset=utf-8", content)));
        bad(() -> FireArchiveInputs.attachment(attachment("a.pdf", "nonsense", content)));
        bad(() -> FireArchiveInputs.attachment(attachment("a.pdf", "a/" + "b".repeat(120), content)));
        assertThat(FireArchiveInputs.attachment(attachment("a.pdf", "application/octet-stream", content)).mimeType())
            .isEqualTo("application/octet-stream");
    }

    @Test
    void attachmentPayloadIsBoundedAndBase64Validated() {
        bad(() -> FireArchiveInputs.attachment(attachment("a.bin", "application/octet-stream", "")));
        bad(() -> FireArchiveInputs.attachment(attachment("a.bin", "application/octet-stream", "%%%")));
        byte[] tooLarge = new byte[FireArchiveInputs.MAX_ATTACHMENT_BYTES + 1];
        bad(() -> FireArchiveInputs.attachment(attachment("a.bin", "application/octet-stream",
            Base64.getEncoder().encodeToString(tooLarge))));
        byte[] largest = new byte[FireArchiveInputs.MAX_ATTACHMENT_BYTES];
        var accepted = FireArchiveInputs.attachment(attachment("a.bin", "application/octet-stream",
            Base64.getEncoder().encodeToString(largest)));
        assertThat(accepted.content()).hasSize(FireArchiveInputs.MAX_ATTACHMENT_BYTES);
        assertThat(accepted.expectedRevision()).isZero();
    }

    @Test
    void revisionMustStayJsSafeAndNonNegative() {
        String content = Base64.getEncoder().encodeToString("ok".getBytes(StandardCharsets.UTF_8));
        bad(() -> FireArchiveInputs.attachment(new FireArchiveInputs.AttachmentBody("a.pdf", "application/pdf",
            content, -1L, "reason")));
        bad(() -> FireArchiveInputs.attachment(new FireArchiveInputs.AttachmentBody("a.pdf", "application/pdf",
            content, null, "reason")));
        assertThat(FireArchiveInputs.attachment(new FireArchiveInputs.AttachmentBody("a.pdf", "application/pdf",
            content, FireArchiveModels.MAX_SAFE, "reason")).expectedRevision())
            .isEqualTo(FireArchiveModels.MAX_SAFE);
    }

    private static FireArchiveInputs.AttachmentBody attachment(String filename, String mime, String content) {
        return new FireArchiveInputs.AttachmentBody(filename, mime, content, 0L, "reason");
    }
}
