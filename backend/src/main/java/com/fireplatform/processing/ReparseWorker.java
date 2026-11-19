package com.fireplatform.processing;

import com.fireplatform.configuration.RuntimeConfig;
import java.util.UUID;
import org.springframework.boot.availability.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** N07 重解析消费者：串行、可中断、无外部副作用。 */
@Component
public class ReparseWorker implements SmartLifecycle {
    private final RuntimeConfig config; private final ReparseRunner runner; private final ApplicationAvailability availability;
    private final String owner=UUID.randomUUID().toString(); private volatile boolean running; private Thread thread;
    public ReparseWorker(RuntimeConfig config,ReparseRunner runner,ApplicationAvailability availability) { this.config=config; this.runner=runner; this.availability=availability; }
    @Override public synchronized void start() {
        if(running || !config.reparseWorkerEnabled()) return; running=true;
        thread=new Thread(()->{
            while(running && !Thread.currentThread().isInterrupted()) {
                try { if(availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC) runner.runOne(owner); }
                catch(RuntimeException ignored) { /* 存储不可用不写诊断；下一轮再试。 */ }
                try { Thread.sleep(config.taskPollMs()); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        },"reparse-worker"); thread.setDaemon(true); thread.start();
    }
    @Override public synchronized void stop() { running=false; if(thread!=null) thread.interrupt(); }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
}
