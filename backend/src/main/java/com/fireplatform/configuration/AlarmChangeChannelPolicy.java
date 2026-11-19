package com.fireplatform.configuration;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Technical per-node resource policy; these are not business reminder or delivery SLAs. */
@Component
public class AlarmChangeChannelPolicy {
    private final boolean enabled;private final Set<String> origins;
    private final int poll,connect,silence,maxConnections,maxPerSession,send,threads,messageBytes;
    public AlarmChangeChannelPolicy(Environment env){
        enabled=env.getProperty("app.alarm-change-ws-enabled",Boolean.class,env.getProperty("APP_ALARM_CHANGE_WS_ENABLED",Boolean.class,false));
        poll=bound(env,"POLL_MS",1000,50,60000);connect=bound(env,"CONNECT_TIMEOUT_MS",10000,1000,60000);silence=bound(env,"SILENCE_TIMEOUT_MS",15000,1000,180000);maxConnections=bound(env,"MAX_CONNECTIONS",32,1,512);maxPerSession=bound(env,"MAX_PER_SESSION",2,1,32);send=bound(env,"SEND_TIMEOUT_MS",1000,50,10000);threads=bound(env,"WORKER_THREADS",2,1,16);messageBytes=bound(env,"MAX_MESSAGE_BYTES",4096,1024,65536);
        if(maxPerSession>maxConnections||silence<=poll+send)throw new IllegalStateException("Invalid alarm channel resource relationships");
        String configured=env.getProperty("app.alarm-change-ws-allowed-origins",env.getProperty("APP_ALARM_CHANGE_WS_ALLOWED_ORIGINS",""));var values=new LinkedHashSet<String>();
        if(!configured.isBlank())for(String part:configured.split(",",-1)){String origin=part.trim();if(!canonicalOrigin(origin)||!values.add(origin))throw new IllegalStateException("Invalid alarm channel origin policy");}
        origins=Set.copyOf(values);if(enabled&&origins.isEmpty())throw new IllegalStateException("Enabled alarm channel requires explicit origins");
    }
    private static int bound(Environment env,String suffix,int initial,int min,int max){String key="app.alarm-change-ws-"+suffix.toLowerCase(Locale.ROOT).replace('_','-');int value=env.getProperty(key,Integer.class,env.getProperty("APP_ALARM_CHANGE_WS_"+suffix,Integer.class,initial));if(value<min||value>max)throw new IllegalStateException("Invalid alarm channel resource setting: "+key);return value;}
    public static boolean canonicalOrigin(String value){try{var uri=URI.create(value);String scheme=uri.getScheme(),host=uri.getHost();if(!Set.of("http","https").contains(scheme)||host==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().isEmpty()||uri.getPort()==0||uri.getPort()>65535)return false;String normalizedHost=host.toLowerCase(Locale.ROOT);if(normalizedHost.contains(":")&&!normalizedHost.startsWith("["))normalizedHost="["+normalizedHost+"]";int port=uri.getPort();String normalized=scheme+"://"+normalizedHost+((port==-1||port==(scheme.equals("https")?443:80))?"":":"+port);return normalized.equals(value);}catch(RuntimeException e){return false;}}
    public boolean acceptsOrigin(String value){return value!=null&&canonicalOrigin(value)&&origins.contains(value);}
    public Set<String> origins(){return origins;}
    public boolean enabled(){return enabled;}public int pollMs(){return poll;}public int connectTimeoutMs(){return connect;}public int silenceTimeoutMs(){return silence;}public int maxConnections(){return maxConnections;}public int maxPerSession(){return maxPerSession;}public int sendTimeoutMs(){return send;}public int workerThreads(){return threads;}public int maxMessageBytes(){return messageBytes;}
}
