package com.fireplatform.bulkio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaintenanceTransferDomainTest {
    @Test
    void workOrderImportCannotCarryCompletionStatusOrAssignee() {
        var domain = new MaintenanceDataTransferDomain(null, null, null, null, null);
        assertThat(domain.kinds()).hasSize(1);
        var kind = domain.kinds().getFirst();
        assertThat(kind.kind()).isEqualTo("WORK_ORDERS");
        assertThat(kind.importable()).isTrue();
        assertThat(kind.exportable()).isTrue();
        assertThat(kind.effect()).isEqualTo("DRAFT_ONLY");
        assertThat(kind.columns()).containsExactly("device_id", "title", "description", "priority", "due_at", "fault_event_id");
        assertThat(kind.columns()).doesNotContain("status", "assignee_user_id", "revision", "closed_at");
    }

    @Test
    void archiveImportIsMetadataOnlyAndNeverCarriesAttachments() {
        var domain = new FireArchiveDataTransferDomain(null, null, null, null, null);
        assertThat(domain.kinds()).hasSize(1);
        var kind = domain.kinds().getFirst();
        assertThat(kind.kind()).isEqualTo("FIRE_ARCHIVES");
        assertThat(kind.importable()).isTrue();
        assertThat(kind.exportable()).isTrue();
        assertThat(kind.effect()).isEqualTo("MASTER_DATA");
        assertThat(kind.columns()).containsExactly("title", "category", "archive_number", "device_id", "place_id",
            "issued_on", "expires_on", "description");
        assertThat(kind.columns()).doesNotContain("id", "status", "revision", "content_base64", "ciphertext", "nonce");
    }
}
