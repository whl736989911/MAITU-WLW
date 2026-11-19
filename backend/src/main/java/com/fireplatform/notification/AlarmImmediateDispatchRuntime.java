package com.fireplatform.notification;

import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.alarm.AlarmApplicationChangeCommitted;
import com.fireplatform.alarm.AlarmChangeChannelRuntime;
import com.fireplatform.alarm.AlarmChangePublisher;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.context.SmartLifecycle;

/** Bounded, coalescing post-commit wakeups; the database outboxes remain the recovery source. */
@Component
public final class AlarmImmediateDispatchRuntime implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AlarmImmediateDispatchRuntime.class);
    private final RuntimeConfig config;
    private final AlarmChangePublisher publisher;
    private final AlarmChangeChannelRuntime channel;
    private final RealNotificationService notifications;
    private final BlockingQueue<AlarmApplicationChangeCommitted> queue = new ArrayBlockingQueue<>(1024);
    private final Set<String> queuedAlarms = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private Thread worker;

    public AlarmImmediateDispatchRuntime(RuntimeConfig config, AlarmChangePublisher publisher,
            AlarmChangeChannelRuntime channel, RealNotificationService notifications) {
        this.config = config; this.publisher = publisher; this.channel = channel; this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(AlarmApplicationChangeCommitted event) {
        if (!config.alarmChangePublisherEnabled() && !config.realNotificationWorkerEnabled()) return;
        String key = event.traceId();
        if (!queuedAlarms.add(key)) return;
        if (!queue.offer(event)) queuedAlarms.remove(key); // Durable publisher/notification scans recover saturation.
    }

    @Override public synchronized void start() {
        if (running || (!config.alarmChangePublisherEnabled() && !config.realNotificationWorkerEnabled())) return;
        running = true;
        worker = new Thread(this::run, "alarm-immediate-dispatch");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            AlarmApplicationChangeCommitted event;
            try { event = queue.take(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            String key = event.traceId();
            try {
                if (config.alarmChangePublisherEnabled() && publisher.publish() > 0) channel.signal();
            } catch (RuntimeException error) {
                log.warn("immediate alarm publication failed: {}", error.getClass().getSimpleName());
            }
            try {
                if (config.realNotificationWorkerEnabled()) notifications.dispatchInitialAlarm(event.tenantId(), event.alarmId());
            } catch (RuntimeException error) {
                log.warn("immediate alarm notification failed: {}", error.getClass().getSimpleName());
            } finally { queuedAlarms.remove(key); }
        }
    }

    @Override public synchronized void stop() { running = false; if (worker != null) worker.interrupt(); }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
}
