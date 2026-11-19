package com.fireplatform.identity;

import java.util.List;

/** Frozen v1 wire DTOs. Field order is stable; Jackson renders them snake_case. */
public final class AccessModels {
    private AccessModels() { }

    /** Global template. {@code permissions} are the template's own entries, independent of any user. */
    public record Role(String id, String code, String name, String userType, boolean enabled, boolean deleted,
                       long revision, List<String> permissions) { }

    /** Direct permissions stay explicit; effective permissions add enabled, non-deleted assigned roles. */
    public record UserAccess(String userId, String userType, long revision, List<String> directPermissions,
                             List<String> effectivePermissions, List<Role> roles) { }

    /**
     * No row means configured=false. DEVICE_SET may hold zero devices; both grant no device rights.
     * ALL_TENANT holds neither collection; DEVICE_SET holds only devices; PLACE_SUBTREE holds only
     * non-empty same-unit place roots and includes their descendants and future devices.
     */
    public record DeviceScope(String userId, String tenantId, boolean configured, String mode, long revision,
                              List<String> deviceIds, List<String> placeIds) { }
}
