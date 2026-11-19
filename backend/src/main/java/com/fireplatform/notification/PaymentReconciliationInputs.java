package com.fireplatform.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public final class PaymentReconciliationInputs {
    private PaymentReconciliationInputs() { }
    /** Explicit bill date (yyyyMMdd) for a manual reconciliation run. */
    public record Run(@JsonDeserialize(using=NotificationInputs.TextOnly.class) String billDate) { }
}
