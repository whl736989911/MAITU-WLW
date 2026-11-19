package com.fireplatform.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MaintenanceLifecycleTest {
    @Test
    void draftMustBeOpenedBeforeWorkOrAssignment() {
        assertThat(MaintenanceLifecycle.canOpen("DRAFT")).isTrue();
        assertThat(MaintenanceLifecycle.canAssign("DRAFT")).isFalse();
        assertThat(MaintenanceLifecycle.canWorkLog("DRAFT")).isFalse();
        assertThat(MaintenanceLifecycle.canSubmitReview("DRAFT")).isFalse();
        assertThat(MaintenanceLifecycle.canCancel("DRAFT")).isTrue();
    }

    @Test
    void assignmentAndWorkStayInsideActiveStates() {
        assertThat(MaintenanceLifecycle.canAssign("OPEN")).isTrue();
        assertThat(MaintenanceLifecycle.canAssign("ASSIGNED")).isTrue();
        assertThat(MaintenanceLifecycle.canAssign("IN_PROGRESS")).isTrue();
        assertThat(MaintenanceLifecycle.canAssign("PENDING_REVIEW")).isFalse();
        assertThat(MaintenanceLifecycle.afterAssign("OPEN")).isEqualTo("ASSIGNED");
        assertThat(MaintenanceLifecycle.afterAssign("IN_PROGRESS")).isEqualTo("IN_PROGRESS");
        for (String status : List.of("OPEN", "ASSIGNED", "IN_PROGRESS")) assertThat(MaintenanceLifecycle.canWorkLog(status)).isTrue();
        for (String status : List.of("PENDING_REVIEW", "CLOSED", "CANCELLED")) assertThat(MaintenanceLifecycle.canWorkLog(status)).isFalse();
    }

    @Test
    void reviewIsOnlyLocalReviewAndReturnPreservesWork() {
        assertThat(MaintenanceLifecycle.canSubmitReview("IN_PROGRESS")).isTrue();
        assertThat(MaintenanceLifecycle.canSubmitReview("OPEN")).isFalse();
        assertThat(MaintenanceLifecycle.canReview("PENDING_REVIEW")).isTrue();
        assertThat(MaintenanceLifecycle.afterReview(true)).isEqualTo("CLOSED");
        assertThat(MaintenanceLifecycle.afterReview(false)).isEqualTo("IN_PROGRESS");
        assertThat(MaintenanceLifecycle.canReopen("CLOSED")).isTrue();
        assertThat(MaintenanceLifecycle.canCancel("CLOSED")).isFalse();
    }

    @Test
    void terminalStatesAreExplicit() {
        assertThat(MaintenanceLifecycle.STATUSES).isEqualTo(Set.of("DRAFT", "OPEN", "ASSIGNED", "IN_PROGRESS",
            "PENDING_REVIEW", "CLOSED", "CANCELLED"));
        assertThat(MaintenanceLifecycle.terminal("CLOSED")).isTrue();
        assertThat(MaintenanceLifecycle.terminal("CANCELLED")).isTrue();
        assertThat(MaintenanceLifecycle.active("PENDING_REVIEW")).isTrue();
        assertThat(MaintenanceLifecycle.canReopen("CANCELLED")).isFalse();
    }
}
