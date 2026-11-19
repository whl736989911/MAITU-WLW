package com.fireplatform.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.notification.NotificationInputs.*;

public final class NotificationRechargeInputs {
    private NotificationRechargeInputs() { }
    public record Policy(@JsonDeserialize(using=TextOnly.class) String mode,
                         @JsonDeserialize(using=IntegerOnly.class) Long expectedRevision) { }
    public record Grant(@JsonDeserialize(using=TextOnly.class) String userId,
                        @JsonDeserialize(using=TextOnly.class) String ownerType,
                        @JsonDeserialize(using=TextOnly.class) String ownerId) { }
    public record CustomerOrder(@JsonDeserialize(using=TextOnly.class) String ownerType,
                                @JsonDeserialize(using=TextOnly.class) String ownerId,
                                @JsonDeserialize(using=TextOnly.class) String channel,
                                @JsonDeserialize(using=IntegerOnly.class) Long quantity) { }
}
