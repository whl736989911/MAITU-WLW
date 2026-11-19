package com.fireplatform.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public final class PaymentConfigInputs {
    private PaymentConfigInputs() { }
    /** {@code orderExpiryMinutes} is the explicit business expiry: null clears it, there is no invented default. */
    public record Config(@JsonDeserialize(using=NotificationInputs.BooleanOnly.class) Boolean enabled,
                         @JsonDeserialize(using=NotificationInputs.TextOnly.class) String mchId,
                         @JsonDeserialize(using=NotificationInputs.TextOnly.class) String appId,
                         @JsonDeserialize(using=NotificationInputs.TextOnly.class) String apiV2Key,
                         @JsonDeserialize(using=NotificationInputs.TextOnly.class) String notifyUrl,
                         @JsonDeserialize(using=NotificationInputs.IntegerOnly.class) Long orderExpiryMinutes,
                         @JsonDeserialize(using=NotificationInputs.BooleanOnly.class) Boolean autoLifecycleEnabled,
                         @JsonDeserialize(using=NotificationInputs.BooleanOnly.class) Boolean autoReconcileEnabled,
                         @JsonDeserialize(using=NotificationInputs.IntegerOnly.class) Long expectedRevision) { }
}
