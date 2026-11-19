package com.fireplatform.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.notification.NotificationInputs.*;
import jakarta.validation.constraints.*;

public final class NotificationDeliveryReceiptInputs {
    private NotificationDeliveryReceiptInputs() { }
    public record Reconcile(@JsonDeserialize(using=IntegerOnly.class) @NotNull @Min(0) Long expectedRevision,
                            @JsonDeserialize(using=TextOnly.class) @NotBlank @Size(max=com.fireplatform.shared.Limits.MAX_CHANGE_REASON_LENGTH) String reason) { }
    public record ConfigUpdate(@JsonDeserialize(using=IntegerOnly.class) @NotNull @Min(0) Long expectedRevision,
                               @JsonDeserialize(using=BooleanOnly.class) @NotNull Boolean periodicQueryEnabled,
                               @JsonDeserialize(using=IntegerOnly.class) @NotNull @Min(0) @Max(86400) Long minAgeSeconds,
                               @JsonDeserialize(using=IntegerOnly.class) @NotNull @Min(60) @Max(604800) Long retrySeconds,
                               @JsonDeserialize(using=IntegerOnly.class) @NotNull @Min(1) @Max(500) Long maxQueriesPerAttempt,
                               @JsonDeserialize(using=IntegerOnly.class) @NotNull @Min(1) @Max(100) Long batchSize,
                               @JsonDeserialize(using=TextOnly.class) @NotBlank @Size(max=com.fireplatform.shared.Limits.MAX_CHANGE_REASON_LENGTH) String reason) { }
}
