package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.configuration.RuntimeConfig;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded scheduled compensation for local WeChat Pay orders.
 *
 * <p>It is inert unless an operator explicitly enabled automatic lifecycle handling in the page
 * configuration. Only merchant-side close and query calls are performed; nothing re-issues an order,
 * marks a payment failed or expires a business TTL on the provider's behalf. Every order-level failure
 * is isolated so one uncertain order cannot stop the scan.</p>
 */
@Service
public class PaymentLifecycleWorker {
    private static final Logger log = LoggerFactory.getLogger(PaymentLifecycleWorker.class);
    private static final int MIN_CLOSE_AGE_MINUTES = 5;

    private final JdbcTemplate jdbc;
    private final PaymentConfigService config;
    private final PaymentOrderService orders;
    private final DatabaseTime time;
    private final RuntimeConfig runtime;
    private final int queryIntervalSeconds, closeRetrySeconds, batch, maxQueryAttempts, maxCloseAttempts;

    public PaymentLifecycleWorker(JdbcTemplate jdbc, PaymentConfigService config, PaymentOrderService orders,
            DatabaseTime time, RuntimeConfig runtime,
            @Value("${fire.payment-query-interval-seconds:300}") int queryIntervalSeconds,
            @Value("${fire.payment-close-retry-seconds:600}") int closeRetrySeconds,
            @Value("${fire.payment-lifecycle-batch:20}") int batch,
            @Value("${fire.payment-query-max-attempts:48}") int maxQueryAttempts,
            @Value("${fire.payment-close-max-attempts:24}") int maxCloseAttempts) {
        this.jdbc = jdbc; this.config = config; this.orders = orders; this.time = time; this.runtime = runtime;
        this.queryIntervalSeconds = clamp(queryIntervalSeconds, 60, 3600);
        this.closeRetrySeconds = clamp(closeRetrySeconds, 60, 86400);
        this.batch = clamp(batch, 1, 100);
        this.maxQueryAttempts = clamp(maxQueryAttempts, 1, 1000);
        this.maxCloseAttempts = clamp(maxCloseAttempts, 1, 1000);
    }

    @Scheduled(initialDelayString = "${fire.payment-lifecycle-initial-delay-ms:60000}",
               fixedDelayString = "${fire.payment-lifecycle-scan-ms:60000}")
    public void tick() {
        if (!runtime.roles().contains("all") && !runtime.roles().contains("worker")) return;
        if (!config.autoLifecycleEnabled()) return;
        OffsetDateTime now = time.now();
        closeDue(now);
        queryDue(now);
    }

    private void closeDue(OffsetDateTime now) {
        List<String> due = jdbc.queryForList(
            "SELECT id FROM notification_resource_orders WHERE status='PENDING' AND expires_at IS NOT NULL AND expires_at<=?"
                + " AND payment_state IN ('ACCEPTED','UNKNOWN')"
                + " AND (close_state IS NULL OR close_state IN ('IN_FLIGHT','UNKNOWN','REJECTED','PAID_UNCONFIRMED'))"
                + " AND close_attempts<? AND (last_close_attempt_at IS NULL OR last_close_attempt_at<=?)"
                + " AND created_at<=? ORDER BY expires_at,id LIMIT ?",
            String.class, now, maxCloseAttempts, now.minusSeconds(closeRetrySeconds), now.minusMinutes(MIN_CLOSE_AGE_MINUTES), batch);
        for (String orderId : due) {
            try {
                orders.systemClose(orderId);
            } catch (RuntimeException failure) {
                log.warn("payment close compensation failed order={} type={}", orderId, failure.getClass().getSimpleName());
            }
        }
    }

    private void queryDue(OffsetDateTime now) {
        List<String> pending = jdbc.queryForList(
            "SELECT id FROM notification_resource_orders WHERE status='PENDING' AND payment_state IN ('ACCEPTED','UNKNOWN')"
                + " AND query_attempts<? AND (last_query_at IS NULL OR last_query_at<=?)"
                + " AND created_at<=? ORDER BY created_at,id LIMIT ?",
            String.class, maxQueryAttempts, now.minusSeconds(queryIntervalSeconds), now.minusSeconds(120), batch);
        for (String orderId : pending) {
            try {
                orders.systemQuery(orderId);
            } catch (RuntimeException failure) {
                log.warn("payment query compensation failed order={} type={}", orderId, failure.getClass().getSimpleName());
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
