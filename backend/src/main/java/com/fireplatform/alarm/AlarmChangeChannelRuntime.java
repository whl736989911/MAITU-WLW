package com.fireplatform.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.*;
import com.fireplatform.identity.ChannelScopeAuthorization;
import com.fireplatform.shared.ApiError;
import jakarta.websocket.Session;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.*;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

/** Single lightweight timer; JDBC and any necessary blocking close only use the bounded daemon worker pool. */
@Component
public class AlarmChangeChannelRuntime implements SmartLifecycle {
    public static final CloseStatus AUTH_CHANGED=new CloseStatus(1008,"AUTHORIZATION_CHANGED"),UNAVAILABLE=new CloseStatus(1011,"CHANNEL_UNAVAILABLE"),INVALID=new CloseStatus(1008,"PROTOCOL_INVALID"),TOO_LARGE=new CloseStatus(1009,"MESSAGE_TOO_LARGE");
    static final class Connection {
        final WebSocketSession socket;final Session nativeSession;final AlarmChangeChannelTickets.Lease lease;
        final AtomicBoolean busy=new AtomicBoolean(),closeQueued=new AtomicBoolean();final java.util.concurrent.atomic.AtomicReference<CloseStatus> closeReason=new java.util.concurrent.atomic.AtomicReference<>();
        // Only under this Connection's monitor: one owner's coherent busy/phase/start and irreversible close fence.
        long next,started;boolean sending,closing;Object operation;
        Connection(WebSocketSession socket,Session nativeSession,AlarmChangeChannelTickets.Lease lease){this.socket=socket;this.nativeSession=nativeSession;this.lease=lease;}
    }
    private final AlarmChangeChannelTickets tickets;private final AlarmChangeChannelHead heads;private final ChannelScopeAuthorization authority;private final AlarmChangeChannelPolicy policy;private final RuntimeConfig runtime;private final ObjectMapper json;
    private final ConcurrentMap<WebSocketSession,Connection> connections=new ConcurrentHashMap<>();private volatile boolean running;private ScheduledExecutorService timer;private ThreadPoolExecutor workers;
    public AlarmChangeChannelRuntime(AlarmChangeChannelTickets tickets,AlarmChangeChannelHead heads,ChannelScopeAuthorization authority,AlarmChangeChannelPolicy policy,RuntimeConfig runtime,ObjectMapper json){this.tickets=tickets;this.heads=heads;this.authority=authority;this.policy=policy;this.runtime=runtime;this.json=json;}
    @Override public synchronized void start(){if(running||!policy.enabled()||!runtime.alarmChangeReadEnabled())return;running=true;
        var factory=(ThreadFactory)(task->{Thread thread=new Thread(task,"alarm-change-channel-worker");thread.setDaemon(true);return thread;});
        workers=new ThreadPoolExecutor(policy.workerThreads(),policy.workerThreads(),0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(policy.maxConnections()*2),factory,new ThreadPoolExecutor.AbortPolicy());
        timer=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"alarm-change-channel-timer");thread.setDaemon(true);return thread;});timer.scheduleAtFixedRate(this::sweep,0,Math.min(policy.pollMs(),Math.min(policy.sendTimeoutMs(),policy.connectTimeoutMs())),TimeUnit.MILLISECONDS);
    }
    public void open(WebSocketSession socket){
        Object stored=socket.getAttributes().remove(AlarmChangeChannelHandshake.LEASE);if(!(stored instanceof AlarmChangeChannelTickets.Lease lease)){closeUnregistered(socket);return;}
        Session nativeSession=socket instanceof NativeWebSocketSession adapted?adapted.getNativeSession(Session.class):null;
        var connection=new Connection(socket,nativeSession,lease);connections.put(socket,connection);boolean attached=tickets.attach(lease);
        if(nativeSession instanceof org.apache.tomcat.websocket.WsSession){nativeSession.getAsyncRemote().setSendTimeout(policy.sendTimeoutMs());nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",(long)policy.sendTimeoutMs());nativeSession.getUserProperties().put("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT",(long)policy.sendTimeoutMs());nativeSession.setMaxIdleTimeout(policy.silenceTimeoutMs());socket.setTextMessageSizeLimit(policy.maxMessageBytes());socket.setBinaryMessageSizeLimit(policy.maxMessageBytes());}
        if(!attached){closeUnregistered(socket);connections.remove(socket,connection);return;}
        if(!running||!(nativeSession instanceof org.apache.tomcat.websocket.WsSession)||!tickets.activate(lease)){requestClose(connection,UNAVAILABLE);return;}
        dispatch(connection);
    }
    /** Wake connected clients after a committed head advance; each sample rechecks current authorization. */
    public void signal(){if(!running)return;for(var connection:connections.values())dispatch(connection);}
    private void closeUnregistered(WebSocketSession socket){try{socket.close(UNAVAILABLE);}catch(Exception ignored){/* no request/credential diagnostics */}}
    private void sweep(){try{tickets.expirePending();for(var lease:tickets.overdueClaims())tickets.closing(lease);for(var connection:connections.values()){
        boolean close=false,due=false;
        synchronized(connection){
            if(connection.closing||connection.lease.state()==AlarmChangeChannelTickets.State.CLOSING){connection.closing=true;connection.closeReason.compareAndSet(null,UNAVAILABLE);close=true;}
            else if(active(connection)){
                long now=System.nanoTime();
                if(connection.busy.get()&&now-connection.started>TimeUnit.MILLISECONDS.toNanos(connection.sending?policy.sendTimeoutMs():policy.silenceTimeoutMs())){
                    // Decide and fence the same owner atomically, before completion can start a successor.
                    connection.closing=true;connection.closeReason.compareAndSet(null,UNAVAILABLE);close=true;
                }else due=now-connection.next>=0;
            }
        }
        if(close)requestClose(connection,connection.closeReason.get());else if(due)dispatch(connection);
    }}catch(RuntimeException ignored){/* finite per-connection failures never kill expiry timer */}}
    private void dispatch(Connection connection){final Object operation;
        synchronized(connection){
            if(connection.closing||!active(connection)||!connection.busy.compareAndSet(false,true))return;
            operation=new Object();connection.operation=operation;connection.sending=false;connection.started=System.nanoTime();connection.next=connection.started+TimeUnit.MILLISECONDS.toNanos(policy.pollMs());
        }
        try{workers.execute(()->sample(connection,operation));}catch(RejectedExecutionException e){finish(connection,operation,UNAVAILABLE);}
    }
    private boolean owns(Connection connection,Object operation){synchronized(connection){return connection.operation==operation&&!connection.closing&&active(connection);}}
    private boolean beginSend(Connection connection,Object operation){synchronized(connection){
        if(connection.operation!=operation||connection.closing||!active(connection))return false;
        connection.sending=true;connection.started=System.nanoTime();return true;
    }}
    private void finish(Connection connection,Object operation,CloseStatus failure){
        synchronized(connection){
            if(connection.operation!=operation)return;
            if(failure!=null){connection.closing=true;connection.closeReason.compareAndSet(null,failure);}
            connection.operation=null;connection.sending=false;connection.busy.set(false);
        }
        if(failure!=null)requestClose(connection,failure);
    }
    private void sample(Connection connection,Object operation){boolean handedOff=false;try{
        if(!owns(connection,operation))return;var lease=connection.lease;
        // Snapshot proxy has already returned: neither a DB transaction nor a queued Identity crosses network I/O.
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Channel send must be outside head transaction");
        var head=heads.read(lease.actorId,lease.tenant);
        var current=authority.require(lease.tokenHash,lease.audience,lease.tenant);
        if(!owns(connection,operation))return;
        String cursor=AlarmChangeCursor.encode(new AlarmChangeCursor.Position(lease.tenant,lease.audience,AlarmChangeCursor.session(current.tokenHash()),head.stream(),head.sequence()));
        var frame=json.createObjectNode();frame.put("v",1);frame.put("type","ALARM_CHANGE_SIGNAL");frame.put("head_cursor",cursor);var semantics=frame.putObject("semantics");semantics.put("kind","INVALIDATION_ONLY");semantics.put("publisher_liveness","UNCONFIRMED");semantics.put("delivery","PER_CONNECTION_SAMPLE");String text=json.writeValueAsString(frame);
        if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>policy.maxMessageBytes())throw new IllegalStateException("Channel frame resource limit");
        if(!beginSend(connection,operation))return;handedOff=true;
        connection.nativeSession.getAsyncRemote().sendText(text,result->finish(connection,operation,result.isOK()?null:UNAVAILABLE));
    }catch(ApiError e){finish(connection,operation,e.status==401||e.status==403?AUTH_CHANGED:UNAVAILABLE);}catch(Exception e){finish(connection,operation,UNAVAILABLE);}finally{if(!handedOff)finish(connection,operation,null);}}
    private boolean active(Connection connection){return running&&connections.get(connection.socket)==connection&&connection.lease.state()==AlarmChangeChannelTickets.State.ACTIVE;}
    public void protocol(WebSocketSession socket,int bytes){var connection=connections.get(socket);if(connection!=null)requestClose(connection,bytes>policy.maxMessageBytes()?TOO_LARGE:INVALID);}
    public void failed(WebSocketSession socket){var connection=connections.get(socket);if(connection!=null)requestClose(connection,UNAVAILABLE);}
    private void requestClose(Connection connection,CloseStatus status){if(connections.get(connection.socket)!=connection||connection.lease.state()==AlarmChangeChannelTickets.State.CLOSED)return;
        synchronized(connection){connection.closing=true;connection.closeReason.compareAndSet(null,status);}
        tickets.closing(connection.lease);if(!connection.closeQueued.compareAndSet(false,true))return;Runnable close=()->{try{connection.socket.close(connection.closeReason.get());}catch(Exception ignored){connection.closeQueued.set(false);/* retain permit; bounded timer retries, never recycle a still-closing socket */}};
        var pool=workers;if(pool!=null&&!pool.isShutdown()){try{pool.execute(close);return;}catch(RejectedExecutionException ignored){connection.closeQueued.set(false);return;}}
        if(!Thread.currentThread().getName().equals("alarm-change-channel-timer"))close.run();else connection.closeQueued.set(false);
    }
    public void closed(WebSocketSession socket){var connection=connections.remove(socket);if(connection!=null){synchronized(connection){connection.closing=true;connection.operation=null;connection.sending=false;connection.busy.set(false);}tickets.release(connection.lease);}}
    @Override public synchronized void stop(){running=false;if(timer!=null)timer.shutdownNow();tickets.shutdownPending();for(var connection:connections.values())requestClose(connection,CloseStatus.GOING_AWAY);if(workers!=null)workers.shutdown();}
    @Override public void stop(Runnable callback){stop();callback.run();}
    @Override public boolean isRunning(){return running;}
}
