package com.fireplatform.notification;

import com.fireplatform.transport.AliyunNotificationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment technical limits and the optional provider-query switch. The operational enablement and scheduling
 * values are managed by pages in {@link NotificationDeliveryReceiptPolicy}; these environment values are only a
 * safety ceiling that can lower the effective values and can force querying off.
 */
@Component
public class NotificationDeliveryReceiptConfig {
    private final boolean allowPeriodicQuery;
    private final int maxBatchSize;
    private final int maxMinAgeSeconds;
    private final int maxRetrySeconds;
    private final int maxQueriesPerAttempt;
    private final int smsPageSize;
    private final long pollMs;
    private final AliyunNotificationClient client;

    public NotificationDeliveryReceiptConfig(
            @Value("${fire.notification.receipt.allow-periodic:true}") boolean allowPeriodicQuery,
            @Value("${fire.notification.receipt.limit-batch-size:100}") int maxBatchSize,
            @Value("${fire.notification.receipt.limit-min-age-seconds:86400}") int maxMinAgeSeconds,
            @Value("${fire.notification.receipt.limit-retry-seconds:604800}") int maxRetrySeconds,
            @Value("${fire.notification.receipt.limit-max-queries-per-attempt:500}") int maxQueriesPerAttempt,
            @Value("${fire.notification.receipt.sms-page-size:50}") int smsPageSize,
            @Value("${fire.notification.receipt.poll-ms:300000}") long pollMs,
            AliyunNotificationClient client) {
        this.allowPeriodicQuery = allowPeriodicQuery;
        this.maxBatchSize = Math.max(1, Math.min(100, maxBatchSize));
        this.maxMinAgeSeconds = Math.max(0, Math.min(86400, maxMinAgeSeconds));
        this.maxRetrySeconds = Math.max(60, Math.min(604800, maxRetrySeconds));
        this.maxQueriesPerAttempt = Math.max(1, Math.min(500, maxQueriesPerAttempt));
        this.smsPageSize = Math.max(1, Math.min(50, smsPageSize));
        this.pollMs = Math.max(30000, pollMs);
        this.client = client;
    }

    public boolean allowPeriodicQuery() { return allowPeriodicQuery; }
    public int maxBatchSize() { return maxBatchSize; }
    public int maxMinAgeSeconds() { return maxMinAgeSeconds; }
    public int maxRetrySeconds() { return maxRetrySeconds; }
    public int maxQueriesPerAttempt() { return maxQueriesPerAttempt; }
    public int smsPageSize() { return smsPageSize; }
    public long pollMs() { return pollMs; }
    public boolean providerQueryReady() { return client.queryReady(); }
    public boolean accountBound() { return client.accountFingerprint() != null; }
}
