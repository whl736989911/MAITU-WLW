package com.fireplatform.identity;

import jakarta.validation.constraints.*;
import java.util.List;
import com.fireplatform.shared.Limits;

public final class ManagementInputs {
    private ManagementInputs() { }
    public record CreateUnit(@NotBlank @Size(max=200) String name) { }
    public record UpdateUnit(@NotBlank @Size(max=200) String name,@NotNull Boolean enabled,@NotNull @Min(0) Long expectedRevision) { }
    public record CreateCustomer(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.@-]{2,99}") String username,@NotBlank @Size(max=200) String name,
        @NotBlank @Size(min=Limits.MIN_PASSWORD_LENGTH,max=Limits.MAX_PASSWORD_LENGTH) String initialPassword,@NotNull @Size(max=Limits.MAX_PERMISSION_ENTRIES) List<@NotBlank String> permissions) { }
    public record UpdateCustomer(@NotBlank @Size(max=200) String name,@NotNull Boolean enabled,@NotNull @Size(max=Limits.MAX_PERMISSION_ENTRIES) List<@NotBlank String> permissions,
        @NotNull @Min(0) Long expectedRevision) { }
    public record CreatePlatform(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.@-]{2,99}") String username,@NotBlank @Size(max=200) String name,
        @NotBlank @Size(min=Limits.MIN_PASSWORD_LENGTH,max=Limits.MAX_PASSWORD_LENGTH) String initialPassword,@NotNull @Size(max=Limits.MAX_PERMISSION_ENTRIES) List<@NotBlank String> permissions,
        @NotNull @Size(max=Limits.MAX_DELEGATED_GRANTS) List<@NotBlank @Size(max=64) String> tenantIds) { }
    public record CreateClientPlatformOperator(
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.@-]{2,99}") String username,
        @NotBlank @Size(max=200) String name,
        @NotBlank @Size(min=12,max=128) String initialPassword) { }
    public record UpdatePlatform(@NotBlank @Size(max=200) String name,@NotNull Boolean enabled,@NotNull @Size(max=Limits.MAX_PERMISSION_ENTRIES) List<@NotBlank String> permissions,
        @NotNull @Size(max=Limits.MAX_DELEGATED_GRANTS) List<@NotBlank @Size(max=64) String> tenantIds,@NotNull @Min(0) Long expectedRevision) { }
    public record ResetPassword(@NotBlank @Size(min=Limits.MIN_PASSWORD_LENGTH,max=Limits.MAX_PASSWORD_LENGTH) String newPassword,@NotNull @Min(0) Long expectedRevision) { }
}
