package com.fireplatform.notification;

import com.fireplatform.transport.AliyunNotificationClient;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted page-managed receipt query policy; environment values remain technical ceilings, never hidden. */
@Service
public class NotificationDeliveryReceiptPolicy {
    public record Desired(boolean enabled,int minAgeSeconds,int retrySeconds,int maxQueriesPerAttempt,int batchSize,long revision,OffsetDateTime updatedAt) { }
    public record Effective(boolean enabled,boolean desiredEnabled,boolean envAllowed,boolean providerReady,
                            int minAgeSeconds,int retrySeconds,int maxQueriesPerAttempt,int batchSize,List<String> clampedReasons) { }
    public record Technical(int smsLookbackDays,int voiceLookbackMonths,int smsPageSize,String voiceProdId,long pollMs,
                            int maxBatchSize,int maxMinAgeSeconds,int maxRetrySeconds,int maxQueriesPerAttempt) { }
    public record View(Desired desired,Effective effective,Technical technical) { }

    static final Desired DEFAULTS = new Desired(false,300,600,48,20,0,null);
    private final JdbcTemplate jdbc;
    private final NotificationDeliveryReceiptConfig config;

    public NotificationDeliveryReceiptPolicy(JdbcTemplate jdbc,NotificationDeliveryReceiptConfig config) {
        this.jdbc=jdbc;this.config=config;
    }

    @Transactional(readOnly=true)
    public Desired desired() {
        var rows=jdbc.query("SELECT periodic_query_enabled,min_age_seconds,retry_seconds,max_queries_per_attempt,batch_size,revision,updated_at"
                + " FROM notification_delivery_receipt_policy WHERE id=1",(rs,n)->new Desired(rs.getBoolean(1),rs.getInt(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getLong(6),rs.getObject(7,OffsetDateTime.class)));
        return rows.isEmpty()?DEFAULTS:rows.getFirst();
    }

    /** The scheduler and detail view use the effective values; every clamp is reported instead of hidden. */
    @Transactional(readOnly=true)
    public Effective effective() {
        return effective(desired(),config.allowPeriodicQuery(),config.providerQueryReady(),config.maxBatchSize(),
                config.maxMinAgeSeconds(),config.maxRetrySeconds(),config.maxQueriesPerAttempt());
    }

    static Effective effective(Desired desired,boolean envAllowed,boolean providerReady,int maxBatchSize,int maxMinAgeSeconds,
                               int maxRetrySeconds,int maxQueriesPerAttempt) {
        int batch=Math.max(1,Math.min(desired.batchSize(),maxBatchSize));
        int minAge=Math.max(0,Math.min(desired.minAgeSeconds(),maxMinAgeSeconds));
        int retry=Math.max(60,Math.min(desired.retrySeconds(),maxRetrySeconds));
        int maxQueries=Math.max(1,Math.min(desired.maxQueriesPerAttempt(),maxQueriesPerAttempt));
        var reasons=new ArrayList<String>();
        if(desired.batchSize()!=batch) reasons.add("BATCH_SIZE_CAPPED");
        if(desired.minAgeSeconds()!=minAge) reasons.add("MIN_AGE_CAPPED");
        if(desired.retrySeconds()!=retry) reasons.add("RETRY_CAPPED");
        if(desired.maxQueriesPerAttempt()!=maxQueries) reasons.add("MAX_QUERIES_CAPPED");
        if(!envAllowed) reasons.add("ENV_QUERY_DISABLED");
        if(!providerReady) reasons.add("PROVIDER_NOT_CONFIGURED");
        boolean enabled=desired.enabled()&&envAllowed&&providerReady;
        return new Effective(enabled,desired.enabled(),envAllowed,providerReady,minAge,retry,maxQueries,batch,List.copyOf(reasons));
    }

    @Transactional(readOnly=true)
    public View view() {
        Desired desired=desired();
        return new View(desired,effective(desired,config.allowPeriodicQuery(),config.providerQueryReady(),config.maxBatchSize(),
                config.maxMinAgeSeconds(),config.maxRetrySeconds(),config.maxQueriesPerAttempt()),technical());
    }

    private Technical technical() {
        return new Technical(NotificationDeliveryReceiptEvidence.SMS_LOOKBACK_DAYS,NotificationDeliveryReceiptEvidence.VOICE_LOOKBACK_MONTHS,
                config.smsPageSize(),AliyunNotificationClient.VOICE_NOTIFICATION_PROD_ID,config.pollMs(),
                config.maxBatchSize(),config.maxMinAgeSeconds(),config.maxRetrySeconds(),config.maxQueriesPerAttempt());
    }
}
