package com.fireplatform.notification.officialaccount;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.notification.NotificationInputs;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Page-managed Official Account configuration. Secrets are write-only and blank keeps the stored value. */
public final class WechatOfficialAccountInputs {
    private WechatOfficialAccountInputs() { }

    public record Config(
        @JsonDeserialize(using = NotificationInputs.BooleanOnly.class) @NotNull Boolean enabled,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 64) String appId,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 128) String appSecret,
        @JsonDeserialize(using = NotificationInputs.BooleanOnly.class) Boolean clearAppSecret,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 128) String callbackToken,
        @JsonDeserialize(using = NotificationInputs.BooleanOnly.class) Boolean clearCallbackToken,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 128) String encodingAesKey,
        @JsonDeserialize(using = NotificationInputs.BooleanOnly.class) Boolean clearEncodingAesKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 500) String oauthRedirectUri,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 500) String callbackUrl,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 128) String fireTemplateId,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 128) String faultTemplateId,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String fireDeviceKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String fireAddressKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String fireTimeKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String fireEventKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String faultDeviceKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String faultAddressKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String faultTimeKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @Size(max = 32) String faultEventKey,
        @JsonDeserialize(using = NotificationInputs.TextOnly.class) @NotBlank @Size(max = 1000) String reason,
        @JsonDeserialize(using = NotificationInputs.IntegerOnly.class) @NotNull Long expectedRevision) { }
}
