package com.fireplatform.alarm;

import com.fireplatform.configuration.RuntimeConfig;
import org.springframework.boot.availability.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Autonomous all/worker publisher only; no durable state lives in this loop. */
@Component
public class AlarmChangeWorker implements SmartLifecycle {
    private final RuntimeConfig config;private final AlarmChangePublisher publisher;private final ApplicationAvailability availability;
    private volatile boolean running;private Thread thread;
    public AlarmChangeWorker(RuntimeConfig config,AlarmChangePublisher publisher,ApplicationAvailability availability){this.config=config;this.publisher=publisher;this.availability=availability;}
    @Override public synchronized void start(){
        if(running||!config.alarmChangePublisherEnabled())return;running=true;
        thread=new Thread(()->{while(running&&!Thread.currentThread().isInterrupted()){
            try{if(availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC)publisher.publish();}catch(RuntimeException ignored){/* No payload, SQL, or credentials in diagnostics. Retry the durable pending rows. */}
            try{Thread.sleep(config.alarmChangePollMs());}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }},"alarm-change-publisher");thread.setDaemon(true);thread.start();
    }
    @Override public synchronized void stop(){running=false;if(thread!=null)thread.interrupt();}
    @Override public void stop(Runnable callback){stop();callback.run();}
    @Override public boolean isRunning(){return running;}
}
