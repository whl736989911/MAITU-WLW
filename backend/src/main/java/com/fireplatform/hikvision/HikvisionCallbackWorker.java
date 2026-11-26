package com.fireplatform.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fireplatform.configuration.RuntimeConfig;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;
/** Bounded local decoder handoff; external HTTP acknowledgement never waits on business processing. */
@Component
public class HikvisionCallbackWorker {
    private record Pending(String id,String account,String model,String payload,OffsetDateTime received,long reparseCount) { }
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final HikvisionCallbackService callbacks;private final HikvisionConfigService configuration;
    private final HikvisionConfigSecrets secrets;private final HikvisionIngestion ingestion;private final RuntimeConfig runtime;private final Clock clock;private final TransactionTemplate transactions;private final com.fireplatform.identity.CoordinationFence coordination;private final ThreadPoolTaskScheduler scheduler;
    private final AtomicBoolean wakeRequested=new AtomicBoolean(),wakeScheduled=new AtomicBoolean();
    private final ObjectReader sourceJson;
    public HikvisionCallbackWorker(JdbcTemplate jdbc,ObjectMapper json,HikvisionCallbackService callbacks,HikvisionConfigService configuration,
        HikvisionConfigSecrets secrets,HikvisionIngestion ingestion,RuntimeConfig runtime,Clock clock,TransactionTemplate transactions,com.fireplatform.identity.CoordinationFence coordination,
        @Qualifier("hikvisionCallbackScheduler") ThreadPoolTaskScheduler scheduler){
        this.jdbc=jdbc;this.json=json;this.callbacks=callbacks;this.configuration=configuration;this.secrets=secrets;this.ingestion=ingestion;this.runtime=runtime;this.clock=clock;this.transactions=transactions;this.coordination=coordination;this.scheduler=scheduler;
        this.sourceJson=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    @EventListener public void received(HikvisionCallbackReceivedEvent event){requestWake();}
    private void requestWake(){
        wakeRequested.set(true);
        if(wakeScheduled.compareAndSet(false,true)){
            try{scheduler.execute(this::drainWakeups);}
            catch(RuntimeException rejected){wakeScheduled.set(false);}
        }
    }
    private void drainWakeups(){
        try{while(wakeRequested.getAndSet(false))tick();}
        finally{wakeScheduled.set(false);if(wakeRequested.get())requestWake();}
    }
    @Scheduled(fixedDelayString="${app.hikvision-callback-poll-ms:1000}",initialDelayString="${app.hikvision-callback-initial-delay-ms:5000}",scheduler="hikvisionCallbackScheduler")
    public void tick(){if(!(runtime.roles().contains("all")||runtime.roles().contains("ingest")))return;
        HikvisionConfigService.Runtime cfg;String account;try{cfg=configuration.refreshRuntime();if(!cfg.session().configured()||!secrets.ready())return;account=secrets.fingerprint(cfg.endpoint()+"\n"+cfg.appKey());}catch(RuntimeException unavailable){return;}
        if(!callbacks.processingEnabled(account))return;
        for(int i=0;i<runtime.hikvisionCallbackBatchSize();i++){
            if(!callbacks.processingEnabled(account))break;
            String id=jdbc.query("SELECT id FROM hikvision_callback_receipts WHERE state='PENDING' AND account_fingerprint=? ORDER BY received_at,id LIMIT 1",(r,n)->r.getString(1),account).stream().findFirst().orElse(null);
            if(id==null)break;
            try{transactions.executeWithoutResult(s->process(id,account,cfg.modelScope()));}
            catch(DataIntegrityViolationException rejected){
                // The record transaction rolled back; retain the envelope for explicit inspection/reparse.
                try{transactions.executeWithoutResult(s->finish(id,"REJECTED","PROCESSING_REJECTED",0,0));}
                catch(RuntimeException unavailable){break;}
            }
            catch(RuntimeException failure){
                try{transactions.executeWithoutResult(s->jdbc.update("UPDATE hikvision_callback_receipts SET error_code='PROCESSING_RETRY' WHERE id=? AND state='PENDING'",id));}
                catch(RuntimeException unavailable){break;}
                break;
            }
        }
    }
    private void process(String id,String account,String modelScope){
        coordination.lock();
        var rows=jdbc.query("SELECT id,account_fingerprint,model_scope,payload_json,received_at,reparse_count FROM hikvision_callback_receipts WHERE id=? FOR UPDATE",(r,n)->new Pending(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getObject(5,OffsetDateTime.class),r.getLong(6)),id);
        if(rows.isEmpty()||!"PENDING".equals(jdbc.queryForObject("SELECT state FROM hikvision_callback_receipts WHERE id=?",String.class,id)))return;
        Pending receipt=rows.getFirst();
        if(!receipt.account().equals(account)||!receipt.model().equals(modelScope))return;
        List<String> enabledTypes=callbacks.enabledTypesForWork(account);
        if(enabledTypes.isEmpty())return;
        JsonNode root;try{root=sourceJson.readTree(receipt.payload());}catch(Exception bad){finish(id,"REJECTED","INVALID_STORED_ENVELOPE",0,0);return;}
        JsonNode messages=root.path("fps").path("msgList");
        if(!messages.isArray()||messages.isEmpty()){finish(id,"REJECTED","INVALID_STORED_ENVELOPE",0,0);return;}
        boolean partial=false,rejected=false;String error=null;int submitted=0,unsupported=0;
        for(JsonNode message:messages){
            String type=message.path("msgType").asText();
            HikvisionClient.Stream stream=HikvisionPushTypes.stream(type,message.path("body").path("dataType").asText());
            if(stream==null){partial=true;unsupported++;if(error==null)error="UNSUPPORTED_MESSAGE";continue;}
            if(!enabledTypes.contains(type)){partial=true;unsupported++;if(error==null)error="STREAM_NOT_ENABLED";continue;}
            JsonNode data=message.path("body").path("data");
            if(!data.isArray()){rejected=true;error="INVALID_PUSH_MESSAGE";continue;}
            ArrayNode records=json.createArrayNode();
            String resourceField=stream==HikvisionClient.Stream.DEVICE_EVENT?"deviceID":"resourceID";
            for(JsonNode record:data){
                if(!record.isObject()){rejected=true;error="INVALID_PUSH_RECORD";continue;}
                String resource=HikvisionIngestion.text(record,resourceField);
                if(resource==null){rejected=true;error="RESOURCE_ID_MISSING";continue;}
                if(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE external_id=? AND model=?",Long.class,resource,receipt.model())!=1){
                    partial=true;error="RESOURCE_NOT_DISCOVERED";continue;
                }
                records.add(record);
            }
            if(!records.isEmpty()){
                ingestion.accept(stream,records,receipt.received(),receipt.model(),"HIKVISION_PUSH");
                submitted+=records.size();
            }
        }
        String state=submitted>0?(rejected||partial?"PARTIAL":"PROCESSED"):rejected?"REJECTED":partial?"PARTIAL":"PROCESSED";
        finish(id,state,error,submitted,unsupported);
    }
    private void finish(String id,String state,String error,int submitted,int unsupported){
        jdbc.update("UPDATE hikvision_callback_receipts SET state=?,error_code=?,submitted_records=?,unsupported_messages=?,processed_at=?,revision=revision+1 WHERE id=? AND state='PENDING'",
            state,error,submitted,unsupported,OffsetDateTime.now(clock),id);
    }
}
