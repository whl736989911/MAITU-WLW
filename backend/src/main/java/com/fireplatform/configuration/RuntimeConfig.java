package com.fireplatform.configuration;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** Deployment choices are NOT mutable business settings. Role split is declarative only in this slice. */
@Configuration
public class RuntimeConfig {
    private final String storage;
    private final boolean genericIngestEnabled;
    private final boolean reparseEnabled;
    private final int taskPollMs;
    private final List<String> roles;
    private final boolean development;
    private final boolean alarmChangeReadEnabled,alarmChangePublishEnabled;
    private final int alarmChangePollMs,alarmChangeBatchSize;
    private final int hikvisionCallbackPollMs,hikvisionCallbackBatchSize;
    private final boolean genericMessageWorkerEnabled;
    private final boolean applicationOutputEnabled;
    private final int applicationOutputPollMs,applicationOutputBatchSize;

    private final boolean videoCaptureWorkerEnabled;
    public RuntimeConfig(Environment env) {
        alarmChangeReadEnabled=env.getProperty("app.alarm-change-read-enabled",Boolean.class,env.getProperty("APP_ALARM_CHANGE_READ_ENABLED",Boolean.class,false));
        alarmChangePublishEnabled=env.getProperty("app.alarm-change-publish-enabled",Boolean.class,env.getProperty("APP_ALARM_CHANGE_PUBLISH_ENABLED",Boolean.class,false));
        alarmChangePollMs=alarmChangeBound(env,"app.alarm-change-poll-ms","APP_ALARM_CHANGE_POLL_MS",500,50,60000);
        alarmChangeBatchSize=alarmChangeBound(env,"app.alarm-change-batch-size","APP_ALARM_CHANGE_BATCH_SIZE",20,1,100);
        hikvisionCallbackPollMs=alarmChangeBound(env,"app.hikvision-callback-poll-ms","APP_HIKVISION_CALLBACK_POLL_MS",1000,100,60000);
        hikvisionCallbackBatchSize=alarmChangeBound(env,"app.hikvision-callback-batch-size","APP_HIKVISION_CALLBACK_BATCH_SIZE",10,1,100);
        storage = env.getProperty("app.storage", "h2-dev");
        genericIngestEnabled = env.getProperty("app.generic-ingest-enabled", Boolean.class, false);
        reparseEnabled = env.getProperty("app.reparse-enabled", Boolean.class, false);
        taskPollMs=bounded(env,"app.task-poll-ms",500,50,60000);
        var profiles = Set.copyOf(Arrays.asList(env.getActiveProfiles()));
        development = !profiles.isEmpty() && profiles.stream().allMatch(p -> p.equals("dev") || p.equals("test"));
        String url = env.getProperty("spring.datasource.url", "");
        if (!Set.of("h2-dev", "postgresql").contains(storage)) throw new IllegalStateException("Unsupported APP_STORAGE");
        if (storage.equals("h2-dev") && (!development || !url.startsWith("jdbc:h2:")))
            throw new IllegalStateException("H2 is restricted to dev/test profiles and cannot be used as production storage");
        if (storage.equals("postgresql") && !url.startsWith("jdbc:postgresql:"))
            throw new IllegalStateException("PostgreSQL storage requires a PostgreSQL JDBC URL");
        roles = Arrays.stream(env.getProperty("app.roles", "all").split(",", -1)).map(String::trim).distinct().toList();
        genericMessageWorkerEnabled=genericIngestEnabled&&(roles.contains("all")||roles.contains("worker"));
        applicationOutputEnabled=env.getProperty("app.application-output-enabled",Boolean.class,env.getProperty("APP_APPLICATION_OUTPUT_ENABLED",Boolean.class,false));
        applicationOutputPollMs=alarmChangeBound(env,"app.application-output-poll-ms","APP_APPLICATION_OUTPUT_POLL_MS",1000,250,60000);
        videoCaptureWorkerEnabled=roles.contains("all")||roles.contains("worker");
        applicationOutputBatchSize=alarmChangeBound(env,"app.application-output-batch-size","APP_APPLICATION_OUTPUT_BATCH_SIZE",5,1,50);
        if (roles.isEmpty() || roles.stream().anyMatch(r -> !Set.of("all", "api", "ingest", "worker").contains(r))
            || (roles.contains("all") && roles.size() != 1)) throw new IllegalStateException("Invalid APP_ROLES");
    }
    private static int bounded(Environment env,String key,int initial,int min,int max) { int value=env.getProperty(key,Integer.class,initial); if(value<min || value>max) throw new IllegalStateException("Invalid deployment resource setting: "+key); return value; }
    private static int alarmChangeBound(Environment env,String key,String variable,int initial,int min,int max){int value=env.getProperty(key,Integer.class,env.getProperty(variable,Integer.class,initial));if(value<min||value>max)throw new IllegalStateException("Invalid deployment resource setting: "+key);return value;}
    public boolean alarmChangeReadEnabled(){return alarmChangeReadEnabled&&apiEnabled();}
    public boolean alarmChangePublisherEnabled(){return alarmChangePublishEnabled&&(roles.contains("all")||roles.contains("worker"));}
    public boolean realNotificationWorkerEnabled(){return roles.contains("all")||roles.contains("worker");}
    public int alarmChangePollMs(){return alarmChangePollMs;}
    public int alarmChangeBatchSize(){return alarmChangeBatchSize;}
    public int hikvisionCallbackPollMs(){return hikvisionCallbackPollMs;}
    public int hikvisionCallbackBatchSize(){return hikvisionCallbackBatchSize;}
    public boolean genericMessageWorkerEnabled(){return genericMessageWorkerEnabled;}
    /** Application output delivery is deployment-gated and OFF unless explicitly enabled for a worker role. */
    public boolean applicationOutputWorkerEnabled(){return applicationOutputEnabled&&(roles.contains("all")||roles.contains("worker"));}
    public int applicationOutputPollMs(){return applicationOutputPollMs;}
    public int applicationOutputBatchSize(){return applicationOutputBatchSize;}
    public boolean reparseWorkerEnabled() { return reparseEnabled && (roles.contains("all") || roles.contains("worker")); }
    public int taskPollMs() { return taskPollMs; }
    public String storage() { return storage; }
    public boolean genericIngestEnabled() { return genericIngestEnabled && apiEnabled(); }
    public List<String> roles() { return roles; }
    public boolean development() { return development; }
    public boolean apiEnabled() { return roles.contains("all") || roles.contains("api"); }
    /** Video capture performs network I/O and is scheduled only by worker/all-role instances. */
    public boolean videoCaptureWorkerEnabled(){return videoCaptureWorkerEnabled;}
    @Bean @org.springframework.boot.context.properties.ConfigurationProperties(prefix="spring.datasource.hikari")
    DataSource dataSource(DataSourceProperties properties) { return properties.initializeDataSourceBuilder().build(); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
}
