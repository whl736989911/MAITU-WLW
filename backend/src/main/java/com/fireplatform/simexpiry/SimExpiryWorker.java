package com.fireplatform.simexpiry;

import com.fireplatform.configuration.RuntimeConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SimExpiryWorker implements SmartLifecycle {
    private static final Logger log=LoggerFactory.getLogger(SimExpiryWorker.class);
    private final RuntimeConfig config;private final SimExpiryCommands commands;private final JdbcTemplate jdbc;private final ApplicationAvailability availability;private volatile boolean running;private Thread thread;
    public SimExpiryWorker(RuntimeConfig config,SimExpiryCommands commands,JdbcTemplate jdbc,ApplicationAvailability availability){this.config=config;this.commands=commands;this.jdbc=jdbc;this.availability=availability;}
    private boolean enabled(){return config.roles().contains("all")||config.roles().contains("worker");}
    @Override public synchronized void start(){
        if(running||!enabled())return;running=true;
        thread=new Thread(()->{while(running&&!Thread.currentThread().isInterrupted()){
            if(availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC){
                try{List<String> tenants=jdbc.query("SELECT t.id FROM tenants t JOIN sim_expiry_config c ON c.tenant_id=t.id WHERE t.enabled=TRUE AND c.enabled=TRUE ORDER BY t.id",(r,n)->r.getString(1));for(String tenant:tenants)if(running)try{commands.scanTenant(tenant);}catch(RuntimeException failure){log.warn("SIM expiry tenant scan failed");}}
                catch(RuntimeException failure){log.warn("SIM expiry tenant discovery failed");}
            }
            try{Thread.sleep(config.taskPollMs());}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }},"sim-expiry-worker");thread.setDaemon(true);thread.start();
    }
    @Override public synchronized void stop(){running=false;if(thread!=null)thread.interrupt();}
    @Override public void stop(Runnable callback){stop();callback.run();}
    @Override public boolean isRunning(){return running;}
}
