package com.fireplatform.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.notification.NotificationInputs.*;

public final class NotificationResourceInputs {
    private NotificationResourceInputs() { }
    public record Recharge(@JsonDeserialize(using=TextOnly.class) String ownerType,
                           @JsonDeserialize(using=TextOnly.class) String ownerId,
                           @JsonDeserialize(using=TextOnly.class) String channel,
                           @JsonDeserialize(using=IntegerOnly.class) Long quantity,
                           @JsonDeserialize(using=TextOnly.class) String reference) { }
    public record Pricing(@JsonDeserialize(using=IntegerOnly.class) Long smsPriceCents,
                          @JsonDeserialize(using=IntegerOnly.class) Long voicePriceCents,
                          @JsonDeserialize(using=IntegerOnly.class) Long expectedRevision) { }
    public record OrderCreate(@JsonDeserialize(using=TextOnly.class) String ownerType,
                              @JsonDeserialize(using=TextOnly.class) String ownerId,
                              @JsonDeserialize(using=TextOnly.class) String channel,
                              @JsonDeserialize(using=IntegerOnly.class) Long quantity) { }
    public record OrderConfirm(@JsonDeserialize(using=TextOnly.class) String reference) { }
}
