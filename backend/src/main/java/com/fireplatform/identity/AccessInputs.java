package com.fireplatform.identity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Limits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Strict request bodies. Global Jackson already rejects unknown/duplicate fields, floats-as-int and trailing JSON. */
public final class AccessInputs {
    private AccessInputs() { }

    public static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final String CODE = "[A-Za-z][A-Za-z0-9_-]{0,63}";
    private static final String MODE = "ALL_TENANT|DEVICE_SET|PLACE_SUBTREE";

    public record CreateRole(
        @NotBlank @Pattern(regexp = CODE) @JsonDeserialize(using = AccessStringDeserializer.class) String code,
        @NotBlank @Size(max = 200) @JsonDeserialize(using = AccessStringDeserializer.class) String name,
        @NotBlank @Pattern(regexp = "PLATFORM|CUSTOMER") @JsonDeserialize(using = AccessStringDeserializer.class) String userType,
        @NotNull @JsonDeserialize(using = AccessBooleanDeserializer.class) Boolean enabled,
        @NotNull @Size(max = Limits.MAX_PERMISSION_ENTRIES) @JsonDeserialize(contentUsing = AccessStringDeserializer.class) List<@NotBlank String> permissions,
        @NotBlank @Size(max = Limits.MAX_CHANGE_REASON_LENGTH) @JsonDeserialize(using = AccessStringDeserializer.class) String reason) { }

    public record UpdateRole(
        @NotBlank @Size(max = 200) @JsonDeserialize(using = AccessStringDeserializer.class) String name,
        @NotNull @JsonDeserialize(using = AccessBooleanDeserializer.class) Boolean enabled,
        @NotNull @Size(max = Limits.MAX_PERMISSION_ENTRIES) @JsonDeserialize(contentUsing = AccessStringDeserializer.class) List<@NotBlank String> permissions,
        @NotNull @Min(0) @JsonDeserialize(using = AccessRevisionDeserializer.class) Long expectedRevision,
        @NotBlank @Size(max = Limits.MAX_CHANGE_REASON_LENGTH) @JsonDeserialize(using = AccessStringDeserializer.class) String reason) { }

    public record DeleteRole(
        @NotNull @Min(0) @JsonDeserialize(using = AccessRevisionDeserializer.class) Long expectedRevision,
        @NotBlank @Size(max = Limits.MAX_CHANGE_REASON_LENGTH) @JsonDeserialize(using = AccessStringDeserializer.class) String reason) { }

    public record AssignRoles(
        @NotNull @Size(max = Limits.MAX_ROLE_ASSIGNMENTS) @JsonDeserialize(contentUsing = AccessStringDeserializer.class) List<@NotBlank @Size(max = 64) String> roleIds,
        @NotNull @Min(0) @JsonDeserialize(using = AccessRevisionDeserializer.class) Long expectedRevision,
        @NotBlank @Size(max = Limits.MAX_CHANGE_REASON_LENGTH) @JsonDeserialize(using = AccessStringDeserializer.class) String reason) { }

    public record AssignScope(
        @NotBlank @Pattern(regexp = MODE) @JsonDeserialize(using = AccessStringDeserializer.class) String mode,
        @NotNull @Size(max = Limits.MAX_SCOPE_DEVICES) @JsonDeserialize(contentUsing = AccessStringDeserializer.class) List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotNull @Size(max = Limits.MAX_SCOPE_PLACES) @JsonDeserialize(contentUsing = AccessStringDeserializer.class) List<@NotBlank @Size(max = 64) String> placeIds,
        @NotNull @Min(0) @JsonDeserialize(using = AccessRevisionDeserializer.class) Long expectedRevision,
        @NotBlank @Size(max = Limits.MAX_CHANGE_REASON_LENGTH) @JsonDeserialize(using = AccessStringDeserializer.class) String reason) { }

    /** Required business reason, preserved verbatim (no trim/normalisation). Never a credential. */
    public static String reason(String value) {
        if (value == null || value.isBlank() || value.length() > Limits.MAX_CHANGE_REASON_LENGTH) throw ApiError.bad("变更理由无效");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || (Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t')) throw ApiError.bad("变更理由格式无效");
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw ApiError.bad("变更理由格式无效");
                i++;
            }
        }
        return value;
    }
}
