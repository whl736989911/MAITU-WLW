package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.configuration.RuntimeConfig;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily automatic bill reconciliation for local platform orders.
 *
 * <p>Inert until an operator explicitly enabled it. It downloads the previous day's successful
 * transaction bill once, after the official generation window, and never re-runs a completed date.
 * Reconciliation only records differences; it never credits or refunds.</p>
 */
@Service
public class PaymentReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationWorker.class);
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BILL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;
    private final PaymentConfigService config;
    private final PaymentReconciliationService reconciliation;
    private final DatabaseTime time;
    private final RuntimeConfig runtime;

    public PaymentReconciliationWorker(JdbcTemplate jdbc, PaymentConfigService config,
            PaymentReconciliationService reconciliation, DatabaseTime time, RuntimeConfig runtime) {
        this.jdbc = jdbc; this.config = config; this.reconciliation = reconciliation; this.time = time; this.runtime = runtime;
    }

    @Scheduled(initialDelayString = "${fire.payment-reconciliation-initial-delay-ms:300000}",
               fixedDelayString = "${fire.payment-reconciliation-scan-ms:3600000}")
    public void tick() {
        if (!runtime.roles().contains("all") && !runtime.roles().contains("worker")) return;
        if (!config.autoReconcileEnabled()) return;
        ZonedDateTime now = time.now().atZoneSameInstant(BEIJING);
        if (now.getHour() < 10) return;
        String billDate = now.toLocalDate().minusDays(1).format(BILL_DATE);
        Long completed = jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_runs WHERE bill_date=? AND status='COMPLETED'", Long.class, billDate);
        if (completed != null && completed > 0) return;
        Long running = jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_runs WHERE bill_date=? AND status='RUNNING' AND created_at>?",
            Long.class, billDate, now.minusMinutes(30).toOffsetDateTime());
        if (running != null && running > 0) return;
        try {
            reconciliation.systemRun(billDate);
        } catch (RuntimeException failure) {
            log.warn("automatic payment reconciliation failed billDate={} type={}", billDate, failure.getClass().getSimpleName());
        }
    }
}
