package com.fireplatform.alarm;

import com.fireplatform.configuration.AlarmChangeChannelPolicy;
import com.fireplatform.shared.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded node-local capabilities. A permit belongs to one lease through pending/claim/active/closing. */
@Component
public class AlarmChangeChannelTickets {
    public enum State { PENDING,CLAIMED,ACTIVE,CLOSING,CLOSED }
    public static final class Lease {
        final String digest,tokenHash,tenant,audience,origin,actorId;final long expires;
        volatile State state=State.PENDING;boolean transportAttached;
        Lease(String digest,String tokenHash,String tenant,String audience,String origin,String actorId,long expires){this.digest=digest;this.tokenHash=tokenHash;this.tenant=tenant;this.audience=audience;this.origin=origin;this.actorId=actorId;this.expires=expires;}
        public State state(){return state;}
    }
    public record Issued(String ticket,Lease lease) { @Override public String toString(){return "Issued[credential withheld]";} }
    private final AlarmChangeChannelPolicy policy;private final SecureRandom random=new SecureRandom();
    private final Map<String,Lease> pending=new HashMap<>();private final Set<Lease> permits=Collections.newSetFromMap(new IdentityHashMap<>());private final Map<String,Integer> perSession=new HashMap<>();
    private boolean stopped;
    public AlarmChangeChannelTickets(AlarmChangeChannelPolicy policy){this.policy=policy;}
    public synchronized Issued issue(String tokenHash,String tenant,String audience,String origin,String actorId){
        expirePending();if(stopped)throw unavailable();if(permits.size()>=policy.maxConnections()||perSession.getOrDefault(tokenHash,0)>=policy.maxPerSession())throw new ApiError(429,"CHANNEL_CAPACITY","通道资源暂不可用");
        byte[] bytes=new byte[32];String raw,digest;do{random.nextBytes(bytes);raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);digest=Digests.sha256(raw);}while(pending.containsKey(digest));
        var lease=new Lease(digest,tokenHash,tenant,audience,origin,actorId,System.nanoTime()+policy.connectTimeoutMs()*1_000_000L);pending.put(digest,lease);permits.add(lease);perSession.merge(tokenHash,1,Integer::sum);return new Issued(raw,lease);
    }
    public synchronized Lease claim(String raw,String origin,String audience){
        expirePending();if(stopped||raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))throw invalid();var value=pending.get(Digests.sha256(raw));
        if(value==null||!value.origin.equals(origin)||!value.audience.equals(audience))throw invalid();
        pending.remove(value.digest);value.state=State.CLAIMED;return value;
    }
    public synchronized boolean attach(Lease value){if(stopped||!permits.contains(value)||value.state==State.CLOSED)return false;value.transportAttached=true;return true;}
    public synchronized boolean claimUsable(Lease value){return !stopped&&permits.contains(value)&&value.state==State.CLAIMED&&!expired(value);}
    public synchronized boolean activate(Lease value){if(stopped||!permits.contains(value)||value.state!=State.CLAIMED||expired(value)){return false;}value.state=State.ACTIVE;return true;}
    public synchronized void closing(Lease value){if(permits.contains(value)&&value.state!=State.CLOSED)value.state=State.CLOSING;}
    public synchronized void release(Lease value){if(value==null||!permits.remove(value))return;pending.remove(value.digest,value);value.state=State.CLOSED;perSession.computeIfPresent(value.tokenHash,(k,n)->n==1?null:n-1);}
    public synchronized void expirePending(){for(var value:new ArrayList<>(pending.values()))if(expired(value))release(value);}
    public synchronized List<Lease> overdueClaims(){return permits.stream().filter(v->v.state==State.CLAIMED&&expired(v)).toList();}
    public synchronized int used(){return permits.size();}
    public synchronized void shutdownPending(){stopped=true;for(var value:new ArrayList<>(permits))if(!value.transportAttached)release(value);}
    private static boolean expired(Lease value){return System.nanoTime()-value.expires>=0;}
    private static ApiError invalid(){return new ApiError(401,"CHANNEL_TICKET_INVALID","通道连接未确认");}
    private static ApiError unavailable(){return new ApiError(503,"CHANNEL_UNAVAILABLE","通道暂不可用");}
}
