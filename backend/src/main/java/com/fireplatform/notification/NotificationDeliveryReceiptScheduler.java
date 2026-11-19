package com.fireplatform.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Optional periodic provider receipt query; stays disabled unless deployment configuration explicitly enables it. */
@Component
public class NotificationDeliveryReceiptScheduler {
    private final NotificationDeliveryReceiptService service;
    public NotificationDeliveryReceiptScheduler(NotificationDeliveryReceiptService service) { this.service=service; }

    @Scheduled(initialDelayString="${fire.notification.receipt.initial-delay-ms:60000}",
            fixedDelayString="${fire.notification.receipt.poll-ms:300000}")
    public void poll() { service.poll(); }
}
