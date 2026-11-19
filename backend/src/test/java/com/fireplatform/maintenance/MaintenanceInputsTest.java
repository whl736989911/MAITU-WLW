package com.fireplatform.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fireplatform.shared.ApiError;
import org.junit.jupiter.api.Test;

class MaintenanceInputsTest {
    private static void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiError.class)
            .satisfies(error -> assertThat(((ApiError) error).status).isEqualTo(400));
    }

    @Test
    void missingRevisionIsNotTreatedAsZero() {
        bad(() -> MaintenanceInputs.assign(new MaintenanceInputs.AssignBody(null, "assignee", "reason")));
        bad(() -> MaintenanceInputs.versioned(new MaintenanceInputs.VersionedBody(null, "", "reason")));
        bad(() -> MaintenanceInputs.workLog(new MaintenanceInputs.WorkLogBody(null, "note", "OTHER", null,
            "reason")));
    }

    @Test
    void reviewOutcomeMustBeExplicit() {
        bad(() -> MaintenanceInputs.review(new MaintenanceInputs.ReviewBody(0L, null, "", "reason")));
        assertThat(MaintenanceInputs.review(new MaintenanceInputs.ReviewBody(0L, true, null, "reason")).pass())
            .isTrue();
        assertThat(MaintenanceInputs.review(new MaintenanceInputs.ReviewBody(0L, false, "", "reason")).pass())
            .isFalse();
    }

    @Test
    void replacementIsOnlyAcceptedForReplacedResult() {
        bad(() -> MaintenanceInputs.workLog(new MaintenanceInputs.WorkLogBody(1L, "note", "REPAIRED", "device-2",
            "reason")));
        bad(() -> MaintenanceInputs.workLog(new MaintenanceInputs.WorkLogBody(1L, "note", "REPLACED", null,
            "reason")));
        var replaced = MaintenanceInputs.workLog(new MaintenanceInputs.WorkLogBody(1L, "note", "replaced", "device-2",
            "reason"));
        assertThat(replaced.result()).isEqualTo("REPLACED");
        assertThat(replaced.replacementDeviceId()).isEqualTo("device-2");
    }

    @Test
    void priorityAndTextBoundsAreStrict() {
        var create = MaintenanceInputs.create(new MaintenanceInputs.CreateBody(null, "device-1", " title ",
            "line1\nline2", "high", null, "reason"));
        assertThat(create.title()).isEqualTo("title");
        assertThat(create.priority()).isEqualTo("HIGH");
        assertThat(create.description()).isEqualTo("line1\nline2");
        bad(() -> MaintenanceInputs.create(new MaintenanceInputs.CreateBody(null, "device-1", "title", "description",
            "CRITICAL", null, "reason")));
        bad(() -> MaintenanceInputs.create(new MaintenanceInputs.CreateBody(null, "device-1", "title\u0000",
            "description", null, null, "reason")));
        bad(() -> MaintenanceInputs.create(new MaintenanceInputs.CreateBody(null, "device-1", "title",
            "x".repeat(2001), null, null, "reason")));
    }
}
