package com.fireplatform.notification;

import java.time.OffsetDateTime;

public record NotificationResourcePricing(Long smsPriceCents,Long voicePriceCents,long revision,OffsetDateTime updatedAt) { }
