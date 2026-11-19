package com.fireplatform.identity;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class IdentityDirectoryInputs {
    private IdentityDirectoryInputs() { }
    public record Department(@NotBlank @Size(max=120) String name, @Size(max=64) String parentId, @NotNull Boolean enabled,
                             @Min(0) long expectedRevision) { }
    /** Existing duty_personnel storage stays as immutable history but is no longer part of this contract. */
    public record UserMetadata(@Size(max=64) String departmentId, @Size(max=18) String phone,
                               @Email @Size(max=254) String contactEmail, Boolean longTerm,
                               Instant validUntil, @Min(0) long expectedRevision) { }
}
