package com.fireplatform.configuration;

import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Per-node advisory retry bounds. No scheduler, credentials, database state or B2 configuration coupling. */
@Component
public class AlarmChangeReconnectPolicy {
    private final boolean enabled;
    private final int maxRetries,delayMs,windowMs;
    public AlarmChangeReconnectPolicy(Environment env){
        enabled=env.getProperty("app.alarm-change-reconnect-enabled",Boolean.class,env.getProperty("APP_ALARM_CHANGE_RECONNECT_ENABLED",Boolean.class,false));
        maxRetries=bound(env,"MAX_RETRIES",3,1,10);
        delayMs=bound(env,"DELAY_MS",1000,100,60000);
        windowMs=bound(env,"WINDOW_MS",60000,1000,600000);
        if(windowMs<=delayMs)throw new IllegalStateException("Reconnect window must exceed delay");
    }
    private static int bound(Environment env,String suffix,int initial,int min,int max){
        String key="app.alarm-change-reconnect-"+suffix.toLowerCase(Locale.ROOT).replace('_','-');
        int value=env.getProperty(key,Integer.class,env.getProperty("APP_ALARM_CHANGE_RECONNECT_"+suffix,Integer.class,initial));
        if(value<min||value>max)throw new IllegalStateException("Invalid alarm reconnect resource setting: "+key);
        return value;
    }
    public boolean enabled(){return enabled;}
    public int maxRetries(){return maxRetries;}
    public int delayMs(){return delayMs;}
    public int windowMs(){return windowMs;}
}
