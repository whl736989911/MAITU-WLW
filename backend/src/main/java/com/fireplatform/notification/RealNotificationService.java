package com.fireplatform.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.alarmgroup.AlarmSessionNotificationGate;
import com.fireplatform.transport.AliyunNotificationClient;
import com.fireplatform.transport.NotificationSpool;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 真实报警通知：扫描非模拟 FIRE/FAULT，按现有规则/联系人发送短信、语音与公众号模板。 */
@Service
public class RealNotificationService {
    public record Stats(boolean enabled, long sent, long failed, OffsetDateTime cursor) { }
    private static final Logger log = LoggerFactory.getLogger(RealNotificationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SCAN_PAGE_SIZE = 20;
    private static final ColumnMapRowMapper SCAN_ROW = new ColumnMapRowMapper() {
        @Override
        protected Object getColumnValue(ResultSet rs,int index) throws SQLException {
            // Both scan projections start with the time key; JDBC's untyped value differs by database.
            return index==1 ? rs.getObject(index,OffsetDateTime.class) : super.getColumnValue(rs,index);
        }
    };
    private static final String FIRE_ROWS = """
        SELECT a.occurred_at,a.id,a.tenant_id,a.device_id,a.device_name,a.address,a.source_time_known,
               a.physical_state,a.response_state,d.model AS device_model,'FIRE' AS event_kind,
               'ALARM' AS source_kind,a.event_type AS event_code
        """;
    private static final String FIRE_SCAN = """
        FROM alarms a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id
        WHERE a.event_type='FIRE' AND a.is_simulated=FALSE AND a.occurred_at>?
          AND NOT EXISTS(SELECT 1 FROM real_notification_completions c
                         WHERE c.tenant_id=a.tenant_id AND c.source_kind='ALARM' AND c.source_id=a.id)
        """;
    private static final String FAULT_ROWS = """
        SELECT f.occurred_at,f.source_id AS id,f.tenant_id,f.device_id,d.name AS device_name,d.address,d.model AS device_model,
               f.event_code,f.subscription_detail,f.source_kind,'FAULT' AS event_kind,
               NULL AS physical_state,NULL AS response_state
        """;
    private static final String FAULT_SCAN = """
        FROM (
            SELECT e.id AS source_id,e.tenant_id,e.device_id,e.business_code AS event_code,
                   CAST(NULL AS VARCHAR) AS subscription_detail,e.observed_at AS occurred_at,'LEGACY_FAULT' AS source_kind
            FROM legacy_ingest_events e JOIN alarm_configuration_outcomes o
              ON o.tenant_id=e.tenant_id AND o.source_id=e.id AND o.source_kind='LEGACY_FAULT'
             AND o.outcome_kind='SUBSCRIPTION' AND o.outcome='SUBSCRIBED'
            WHERE e.kind='FAULT' AND e.observed_at>?
            UNION ALL
            SELECT r.id,r.tenant_id,r.device_id,NULL,o.detail_json,o.occurred_at,'RAW_FAULT'
            FROM raw_messages r JOIN alarm_configuration_outcomes o
              ON o.tenant_id=r.tenant_id AND o.source_id=r.id AND o.source_kind='FAULT_MESSAGE'
             AND o.outcome_kind='SUBSCRIPTION' AND o.outcome='SUBSCRIBED'
            WHERE r.device_id IS NOT NULL AND o.occurred_at>?
        ) f JOIN devices d ON d.id=f.device_id AND d.tenant_id=f.tenant_id
        WHERE d.is_simulated=FALSE AND NOT EXISTS(SELECT 1 FROM real_notification_completions c
              WHERE c.tenant_id=f.tenant_id AND c.source_kind=f.source_kind AND c.source_id=f.source_id)
        """;
    private final JdbcTemplate jdbc;
    private final ContactPhoneStorage phones;
    private final AliyunNotificationClient client;
    private final NotificationResourceService resources;
    private final SuppressionCommands suppressions;
    private final NotificationProviderAttemptService attempts;
    private final NotificationProviderService provider;
    private final com.fireplatform.configuration.AlarmBaseConfigurationQueries baseConfiguration;
    private final NotificationChannelPolicyService channelPolicies;
    private final com.fireplatform.configuration.DatabaseTime time;
    private final boolean requested;
    private final NotificationSpool spool;
    private final AlarmSessionNotificationGate merge;
    private final com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel wechat;
    private final com.fireplatform.configuration.RuntimeConfig runtime;
    /** First-scan lower bound captured when this worker instance starts, not when the first event happens to arrive. */
    private final OffsetDateTime activationBoundary;
    // Scheduled traversal only; never saved as the durable activation floor or used by targeted dispatch.
    private ScanSweep fireSweep;
    private ScanSweep faultSweep;

    private record ScanPosition(OffsetDateTime occurredAt,String sourceId,String sourceKind) {
        static ScanPosition fromRow(Map<String,Object> row) {
            return new ScanPosition((OffsetDateTime)row.get("occurred_at"),(String)row.get("id"),(String)row.get("source_kind"));
        }

        boolean sameKey(ScanPosition other) {
            return occurredAt.isEqual(other.occurredAt) && sourceId.equals(other.sourceId) && sourceKind.equals(other.sourceKind);
        }
    }

    private record ScanSweep(ScanPosition after,ScanPosition through) {
        ScanSweep advance(List<Map<String,Object>> page) {
            if (page.size()<SCAN_PAGE_SIZE) return null;
            var last=ScanPosition.fromRow(page.getLast());
            return last.sameKey(through) ? null : new ScanSweep(last,through);
        }
    }
    @Autowired
    public RealNotificationService(JdbcTemplate jdbc, ContactPhoneStorage phones, AliyunNotificationClient client,
            NotificationResourceService resources, SuppressionCommands suppressions, NotificationProviderAttemptService attempts, NotificationProviderService provider,
            com.fireplatform.configuration.AlarmBaseConfigurationQueries baseConfiguration, NotificationChannelPolicyService channelPolicies, com.fireplatform.configuration.DatabaseTime time,
            NotificationSpool spool, @org.springframework.beans.factory.annotation.Value("${fire.notification.real-enabled:false}") boolean enabled,
            AlarmSessionNotificationGate merge, com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel wechat,
            com.fireplatform.configuration.RuntimeConfig runtime) {
        this.jdbc=jdbc;this.phones=phones;this.client=client;this.resources=resources;this.suppressions=suppressions;this.attempts=attempts;this.provider=provider;this.baseConfiguration=baseConfiguration;this.channelPolicies=channelPolicies;this.time=time;
        this.requested=enabled;this.spool=spool;this.merge=merge;this.wechat=wechat;this.runtime=runtime;
        this.activationBoundary=time.now();
    }

    @Scheduled(initialDelayString = "${fire.notification.real-initial-delay-ms:15000}",
            fixedDelayString = "${fire.notification.real-poll-ms:5000}")
    public void poll() {
        if (!requested || !workerRole()) return;
        OffsetDateTime floor = initializedCursor();
        var fires=firePage(floor);
        var faults=faultPage(floor);
        List<Map<String,Object>> events=new ArrayList<>(fires.size()+faults.size());
        events.addAll(fires);
        events.addAll(faults);
        if (events.isEmpty()) return;
        events.sort(Comparator.comparing((Map<String,Object> e)->(OffsetDateTime)e.get("occurred_at")).thenComparing(e->String.valueOf(e.get("id"))));
        for (Map<String, Object> event : events) {
            boolean complete;
            try { complete = sendAlarm(event,"INITIAL",null); } catch (RuntimeException error) { log.warn("real notification event failed: {}", error.getClass().getSimpleName()); complete = false; }
            if (!complete) continue;
            String tenant=String.valueOf(event.get("tenant_id")),sourceKind=String.valueOf(event.get("source_kind")),sourceId=String.valueOf(event.get("id"));
            jdbc.update("INSERT INTO real_notification_completions(tenant_id,source_kind,source_id,completed_at) SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM real_notification_completions WHERE tenant_id=? AND source_kind=? AND source_id=?)",
                tenant,sourceKind,sourceId,time.now(),tenant,sourceKind,sourceId);
        }
    }

    private List<Map<String,Object>> firePage(OffsetDateTime floor) {
        String order=" ORDER BY a.occurred_at,a.id LIMIT ?";
        String seek=" AND (a.occurred_at,a.id)>(?,?)";
        if (fireSweep!=null) {
            var after=fireSweep.after();
            var through=fireSweep.through();
            var rows=jdbc.query(FIRE_ROWS+FIRE_SCAN+seek+" AND (a.occurred_at,a.id)<=(?,?)"+order,SCAN_ROW,
                floor,after.occurredAt(),after.sourceId(),through.occurredAt(),through.sourceId(),SCAN_PAGE_SIZE);
            fireSweep=fireSweep.advance(rows);
            if (!rows.isEmpty()) return rows;
        }

        var rows=jdbc.query(FIRE_ROWS+FIRE_SCAN+order,SCAN_ROW,floor,SCAN_PAGE_SIZE);
        if (rows.size()==SCAN_PAGE_SIZE) {
            var after=ScanPosition.fromRow(rows.getLast());
            var through=jdbc.query("SELECT a.occurred_at,a.id "+FIRE_SCAN+seek+" ORDER BY a.occurred_at DESC,a.id DESC LIMIT 1",
                rs -> rs.next() ? new ScanPosition(rs.getObject(1,OffsetDateTime.class),rs.getString(2),"ALARM") : null,
                floor,after.occurredAt(),after.sourceId());
            if (through!=null) fireSweep=new ScanSweep(after,through);
        }
        return rows;
    }

    private List<Map<String,Object>> faultPage(OffsetDateTime floor) {
        String order=" ORDER BY f.occurred_at,f.source_id,f.source_kind LIMIT ?";
        String seek=" AND (f.occurred_at,f.source_id,f.source_kind)>(?,?,?)";
        if (faultSweep!=null) {
            var after=faultSweep.after();
            var through=faultSweep.through();
            var rows=jdbc.query(FAULT_ROWS+FAULT_SCAN+seek+" AND (f.occurred_at,f.source_id,f.source_kind)<=(?,?,?)"+order,SCAN_ROW,
                floor,floor,after.occurredAt(),after.sourceId(),after.sourceKind(),
                through.occurredAt(),through.sourceId(),through.sourceKind(),SCAN_PAGE_SIZE);
            faultSweep=faultSweep.advance(rows);
            if (!rows.isEmpty()) return rows;
        }

        var rows=jdbc.query(FAULT_ROWS+FAULT_SCAN+order,SCAN_ROW,floor,floor,SCAN_PAGE_SIZE);
        if (rows.size()==SCAN_PAGE_SIZE) {
            var after=ScanPosition.fromRow(rows.getLast());
            var through=jdbc.query("SELECT f.occurred_at,f.source_id,f.source_kind "+FAULT_SCAN+seek+
                    " ORDER BY f.occurred_at DESC,f.source_id DESC,f.source_kind DESC LIMIT 1",
                rs -> rs.next() ? new ScanPosition(rs.getObject(1,OffsetDateTime.class),rs.getString(2),rs.getString(3)) : null,
                floor,floor,after.occurredAt(),after.sourceId(),after.sourceKind());
            if (through!=null) faultSweep=new ScanSweep(after,through);
        }
        return rows;
    }

    public void dispatchInitialAlarm(String tenantId,String alarmId) {
        if(!requested||!workerRole())return;
        OffsetDateTime cursor=initializedCursor();
        var rows=jdbc.queryForList("SELECT a.id,a.tenant_id,a.device_id,a.device_name,a.address,a.occurred_at,a.source_time_known,a.physical_state,a.response_state,d.model AS device_model,'FIRE' AS event_kind,'ALARM' AS source_kind,a.event_type AS event_code FROM alarms a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE a.tenant_id=? AND a.id=? AND a.event_type='FIRE' AND a.is_simulated=FALSE AND a.occurred_at>? AND NOT EXISTS(SELECT 1 FROM real_notification_completions c WHERE c.tenant_id=a.tenant_id AND c.source_kind='ALARM' AND c.source_id=a.id)",tenantId,alarmId,cursor);
        if(rows.isEmpty())return;
        Map<String,Object> event=rows.getFirst();
        boolean complete;
        try { complete=sendAlarm(event,"INITIAL",null); }
        catch(RuntimeException error){log.warn("real notification event failed: {}",error.getClass().getSimpleName());return;}
        if(!complete)return;
        jdbc.update("INSERT INTO real_notification_completions(tenant_id,source_kind,source_id,completed_at) SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM real_notification_completions WHERE tenant_id=? AND source_kind=? AND source_id=?)",
            tenantId,"ALARM",alarmId,time.now(),tenantId,"ALARM",alarmId);
    }
    private boolean workerRole(){return runtime.realNotificationWorkerEnabled();}
    /** Establishes the durable first-scan lower bound without swallowing events committed after worker start. */
    private OffsetDateTime initializedCursor(){
        OffsetDateTime cursor=spool.cursor();
        if(cursor!=null)return cursor;
        spool.writeCursor(activationBoundary);
        return activationBoundary;
    }
    public Stats stats() {
        return new Stats(requested, spool.doneCount(), spool.deadCount(), spool.cursor());
    }
    public record ReminderOutcome(boolean complete, OffsetDateTime lastAcceptedAt) { }

    @org.springframework.transaction.annotation.Transactional
    public ReminderOutcome runDueReminder(String tenantId,String alarmId,long cycleNumber,OffsetDateTime cycleStartedAt) {
        if (cycleNumber < 1 || cycleStartedAt == null || !requested || !workerRole()) return new ReminderOutcome(false,null);
        var rows=jdbc.queryForList("SELECT a.id,a.tenant_id,a.device_id,a.device_name,a.address,a.occurred_at,a.source_time_known,a.physical_state,a.response_state,d.model AS device_model,'FIRE' AS event_kind,'ALARM' AS source_kind,a.event_type AS event_code FROM alarms a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE a.tenant_id=? AND a.id=? AND a.event_type='FIRE' AND a.is_simulated=FALSE",tenantId,alarmId);
        String occurrenceKey="REMINDER:"+cycleNumber;
        if(rows.isEmpty() || !sendAlarm(rows.getFirst(),occurrenceKey,cycleStartedAt)) return new ReminderOutcome(false,null);
        // The current pass may only settle the remaining channels. Read acceptance from the durable evidence,
        // including any earlier submission against a different representative of the same merge session.
        var accepted=jdbc.queryForObject(
            "SELECT MAX(p.finished_at) FROM notification_provider_attempts p WHERE p.tenant_id=? AND p.event_kind='FIRE' AND p.source_kind='ALARM' AND p.occurrence_key=? AND p.status='SENT' AND (p.source_id=? OR p.source_id IN (SELECT m.alarm_id FROM alarm_session_members origin JOIN alarm_session_members m ON m.tenant_id=origin.tenant_id AND m.session_id=origin.session_id WHERE origin.tenant_id=? AND origin.alarm_id=?))",
            OffsetDateTime.class,tenantId,occurrenceKey,alarmId,tenantId,alarmId);
        return new ReminderOutcome(true,accepted);
    }
    public static OffsetDateTime escalationDueAt(OffsetDateTime anchor,int level,long intervalSeconds) {
        if(level<1||intervalSeconds<0)throw new IllegalArgumentException("Invalid notification level schedule");
        return anchor.plusSeconds(Math.multiplyExact((long)level-1,intervalSeconds));
    }
    private static String sourceEventCode(Map<String,Object> event,String sourceKind) {
        if(!"RAW_FAULT".equals(sourceKind))return (String)event.get("event_code");
        if(!(event.get("subscription_detail") instanceof String detail))return null;
        try {
            var code=JSON.readTree(detail);
            if(code==null)return null;
            var value=code.path("event_code");
            return value.isTextual()&&!value.textValue().isBlank()?value.textValue():null;
        } catch(JsonProcessingException invalid){
            return null;
        }
    }
    private boolean sendAlarm(Map<String, Object> alarm,String occurrenceKey,OffsetDateTime cycleStartedAt) {
        boolean initial=occurrenceKey.equals("INITIAL");
        String tenant=String.valueOf(alarm.get("tenant_id")),sourceId=String.valueOf(alarm.get("id")),deviceId=String.valueOf(alarm.get("device_id")),deviceModel=String.valueOf(alarm.get("device_model"));
        String eventKind=String.valueOf(alarm.get("event_kind")),sourceKind=String.valueOf(alarm.get("source_kind"));
        String eventCode=sourceEventCode(alarm,sourceKind);
        boolean fire="FIRE".equals(eventKind);
        var mergeSession=merge!=null&&fire&&"ALARM".equals(sourceKind)?merge.sessionFor(tenant,sourceId):null;
        if(mergeSession!=null) {
            var active=merge.representative(tenant,mergeSession.id());
            if(active==null) { spool.markDead(occurrenceKey,sourceId,"DEVICE","SUPPRESSED","ALARM_RECOVERED",null);return true; }
            alarm=active;
            sourceId=String.valueOf(active.get("id"));
            deviceId=String.valueOf(active.get("device_id"));
            deviceModel=String.valueOf(active.get("device_model"));
            eventCode=String.valueOf(active.get("event_code"));
        }
        String spoolId=fire?sourceId:sourceKind+"__"+sourceId;
        if(eventCode==null||eventCode.isBlank()) { spool.markDead(occurrenceKey,spoolId,"DEVICE","SUPPRESSED","NOTIFICATION_EVENT_CODE_UNAVAILABLE",null);return true; }
        if(fire&&!"FIRE".equals(alarm.get("physical_state"))) { spool.markDead(occurrenceKey,spoolId,"DEVICE","SUPPRESSED","ALARM_RECOVERED",null);return true; }
        if(suppressions.blocked(tenant,deviceId,fire,sourceId)) { spool.markDead(occurrenceKey,spoolId,"DEVICE","SUPPRESSED","NOTIFICATION_SUPPRESSED",null);return true; }
        if(fire&&"HANDLED".equals(alarm.get("response_state"))) { spool.markDead(occurrenceKey,spoolId,"DEVICE","SUPPRESSED","ALARM_HANDLED",null);return true; }
        if(!baseConfiguration.effectiveForDevice(tenant,deviceId,eventKind).subscribed(deviceModel,eventCode)) {
            spool.markDead(occurrenceKey,spoolId,"DEVICE","SUPPRESSED","NOTIFICATION_SUBSCRIPTION_DISABLED",null);return true;
        }
        var providerSettings=provider.dispatchSettings();
        String testPhone=providerSettings.testRecipient();
        var contacts=NotificationRecipients.resolve(jdbc,tenant,deviceId,eventKind);
        OffsetDateTime scheduleAnchor=cycleStartedAt!=null?cycleStartedAt:(alarm.get("occurred_at") instanceof OffsetDateTime occurredAt?occurredAt:null);
        Map<String,String> params=paramsFor(alarm,fire,eventCode);
        boolean pending=false;
        for(var contact:contacts) {
            String contactId=contact.contactId(),channel=contact.channel();
            if(testPhone!=null&&"WECHAT".equals(channel))continue;
            if(scheduleAnchor!=null&&scheduleAnchor.plusSeconds(contact.delaySeconds()).isAfter(time.now())){pending=true;continue;}
            int level=contact.level();
            if("WECHAT".equals(channel)) {
                String sendAlarmId=sourceId;
                Map<String,Object> currentAlarm=alarm;
                if(mergeSession!=null) {
                    var active=merge.representative(tenant,mergeSession.id());
                    if(active==null) { spool.markDead(occurrenceKey,spoolId,contactId,"WECHAT","ALARM_RECOVERED",null);return true; }
                    if(!sourceId.equals(active.get("id")))return false;
                    currentAlarm=active;
                } else if(fire&&"HANDLED".equals(jdbc.queryForObject("SELECT response_state FROM alarms WHERE tenant_id=? AND id=?",String.class,tenant,sourceId))){spool.markDead(occurrenceKey,spoolId,"DEVICE","SUPPRESSED","ALARM_HANDLED",null);return true;}
                var effective=baseConfiguration.effectiveForDevice(tenant,deviceId,eventKind);
                var delivery=effective.deliveryPolicy("WECHAT");
                if(!effective.subscribed(deviceModel,eventCode)){spool.markDead(occurrenceKey,spoolId,contactId,"WECHAT","NOTIFICATION_SUBSCRIPTION_DISABLED",null);continue;}
                var policy=channelPolicies.effectiveForDevice(tenant,deviceId,"WECHAT");
                if(suppressions.blocked(tenant,deviceId,fire,sendAlarmId)||!delivery.enabled()||!delivery.receiveAlarm()||!policy.enabled()){spool.markDead(occurrenceKey,spoolId,contactId,"WECHAT","NOTIFICATION_POLICY_DISABLED",null);continue;}
                if(spool.isDone(occurrenceKey,spoolId,contactId,"WECHAT"))continue;
                if(spool.isDead(occurrenceKey,spoolId,contactId,"WECHAT"))continue;
                int nextAttempt=1;String claimId=null;
                if(mergeSession!=null) {
                    var decision=merge.claim(tenant,mergeSession,occurrenceKey,contact.ruleId(),contactId,"WECHAT",level,sendAlarmId,NotificationProviderAttemptService.MAX_ATTEMPTS);
                    if(!decision.proceed()) {
                        if("RETRY_EXHAUSTED".equals(decision.reason())) spool.markDead(occurrenceKey,spoolId,contactId,"WECHAT","SESSION_RETRY_EXHAUSTED",null);
                        else spool.markDone(occurrenceKey,spoolId,contactId,"WECHAT","SESSION_"+decision.reason(),null);
                        continue;
                    }
                    claimId=decision.claimId(); nextAttempt=decision.attemptNo();
                } else {
                    var latest=attempts.latest(tenant,eventKind,sourceKind,sendAlarmId,contactId,"WECHAT",occurrenceKey);
                    if(latest!=null) {
                        if(latest.status().equals("PENDING")){attempts.finish(latest.id(),"UNKNOWN","PENDING_ON_RESTART",null);continue;}
                        if(!latest.status().equals("FAILED")&&!latest.status().equals("RESOURCE_UNAVAILABLE"))continue;
                        nextAttempt=latest.attemptNo()+1;
                        if(nextAttempt>NotificationProviderAttemptService.MAX_ATTEMPTS)continue;
                    }
                }
                boolean delivered=false;String terminalStatus="FAILED",terminalCode=null,terminalProviderId=null;int lastAttempt=nextAttempt;
                for(int attemptNo=nextAttempt;attemptNo<=NotificationProviderAttemptService.MAX_ATTEMPTS&&(initial||attemptNo==nextAttempt);attemptNo++) {
                    lastAttempt=attemptNo;
                    com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.Prepared prepared;
                    try { prepared=wechat.prepare(tenant,contactId,currentAlarm,fire,eventCode); }
                    catch(com.fireplatform.notification.officialaccount.WechatOfficialAccountChannel.Unavailable unavailable) {
                        var failed=attempts.beginWechat(tenant,eventKind,sourceKind,sendAlarmId,deviceId,contactId,occurrenceKey,attemptNo,null,null,null,null,null,null,0,null,null,null);
                        attempts.finish(failed.id(),"FAILED",unavailable.code,null);
                        terminalStatus="FAILED";terminalCode=unavailable.code;break;
                    }
                    var attempt=attempts.beginWechat(tenant,eventKind,sourceKind,sendAlarmId,deviceId,contactId,occurrenceKey,attemptNo,
                        prepared.openidCipher(),prepared.openidNonce(),prepared.openidMasked(),prepared.destinationMac(),prepared.accountFingerprint(),
                        prepared.bindingId(),prepared.configRevision(),prepared.templateId(),prepared.payloadJson(),prepared.payloadDigest());
                    var result=wechat.send(prepared,attempt.id());
                    if(result.accepted()){attempts.finish(attempt.id(),"SENT",result.code(),result.msgid());wechat.recordAccepted(attempt.id(),tenant,result);delivered=true;terminalStatus="SENT";terminalCode=result.code();terminalProviderId=result.msgid();break;}
                    if(result.unknown()){attempts.finish(attempt.id(),"UNKNOWN",result.code(),null);terminalStatus="UNKNOWN";terminalCode=result.code();break;}
                    attempts.finish(attempt.id(),"FAILED",result.code(),null);terminalStatus="FAILED";terminalCode=result.code();
                    log.warn("wechat notification event={} contact={} code={}",sourceId,contactId,result.code());
                    if(!result.retryable())break;
                }
                if(claimId!=null) merge.finish(claimId,delivered?"SENT":("UNKNOWN".equals(terminalStatus)?"UNKNOWN":"FAILED"),lastAttempt,terminalCode,terminalProviderId);
                if(delivered)spool.markDone(occurrenceKey,spoolId,contactId,"WECHAT","SENT",terminalCode);
                else if(!"UNKNOWN".equals(terminalStatus)) {
                    if(initial||lastAttempt>=NotificationProviderAttemptService.MAX_ATTEMPTS)
                        spool.markDead(occurrenceKey,spoolId,contactId,"WECHAT",terminalCode==null?terminalStatus:terminalCode,null);
                    else pending=true;
                }
                log.info("wechat notification event={} kind={} contact={} status={} code={}",sourceId,eventKind,contactId,terminalStatus,terminalCode);
                continue;
            }
            String phone;
            if(testPhone!=null)phone=testPhone;
            else try { phone=phones.reveal(tenant,contact.phoneCipher(),contact.phoneNonce()); }
            catch(RuntimeException error) { spool.markDead(occurrenceKey,spoolId,contactId,"DECRYPT","PHONE_UNAVAILABLE",null);continue; }
            if("SMS".equals(channel)||"VOICE".equals(channel)) {
                String sendAlarmId=sourceId;
                if(mergeSession!=null) {
                    // Re-resolve immediately before external work: the first member may recover while another stays active.
                    var active=merge.representative(tenant,mergeSession.id());
                    if(active==null) { spool.markDead(occurrenceKey,spoolId,contactId,channel,"ALARM_RECOVERED",null);return true; }
                    if(!sourceId.equals(active.get("id")))return false;
                } else if(fire&&"HANDLED".equals(jdbc.queryForObject("SELECT response_state FROM alarms WHERE tenant_id=? AND id=?",String.class,tenant,sourceId))){spool.markDead(occurrenceKey,spoolId,"DEVICE","SUPPRESSED","ALARM_HANDLED",null);return true;}
                var effective=baseConfiguration.effectiveForDevice(tenant,deviceId,eventKind);
                var delivery=effective.deliveryPolicy(channel);
                if(!effective.subscribed(deviceModel,eventCode)){spool.markDead(occurrenceKey,spoolId,contactId,channel,"NOTIFICATION_SUBSCRIPTION_DISABLED",null);continue;}
                var policy=channelPolicies.effectiveForDevice(tenant,deviceId,channel);
                if(suppressions.blocked(tenant,deviceId,fire,sendAlarmId)||!delivery.enabled()||!delivery.receiveAlarm()||("SMS".equals(channel)&&!delivery.pushMessage())||!policy.enabled()){spool.markDead(occurrenceKey,spoolId,contactId,channel,"NOTIFICATION_POLICY_DISABLED",null);continue;}
                String key=initial?spoolId+"__"+contactId+"__"+channel:spoolId+"__"+occurrenceKey.replace(':','_')+"__"+contactId+"__"+channel;
                if(spool.isDone(occurrenceKey,spoolId,contactId,channel)||spool.isDead(occurrenceKey,spoolId,contactId,channel))continue;
                boolean sms=channel.equals("SMS");
                if(sms?!client.enabled():!client.voiceReady()) {
                    if(sms?providerSettings.smsEnabled():providerSettings.voiceEnabled()) {
                        // Stay in the existing scan window, without claiming a send or moving the completion boundary.
                        pending=true;
                        continue;
                    }
                    String code=channel+"_PROVIDER_NOT_READY";
                    if(attempts.latest(tenant,eventKind,sourceKind,sourceId,contactId,channel,occurrenceKey)==null) {
                        var unavailable=attempts.begin(tenant,eventKind,sourceKind,sourceId,deviceId,contactId,channel,occurrenceKey,1,null,null);
                        attempts.finish(unavailable.id(),"RESOURCE_UNAVAILABLE",code,null);
                    }
                    spool.markDead(occurrenceKey,spoolId,contactId,channel,code,null);
                    continue;
                }
                int nextAttempt=1;String claimId=null;
                if(mergeSession!=null) {
                    var decision=merge.claim(tenant,mergeSession,occurrenceKey,contact.ruleId(),contactId,channel,level,sendAlarmId,NotificationProviderAttemptService.MAX_ATTEMPTS);
                    if(!decision.proceed()) {
                        if("RETRY_EXHAUSTED".equals(decision.reason())) spool.markDead(occurrenceKey,spoolId,contactId,channel,"SESSION_RETRY_EXHAUSTED",null);
                        else spool.markDone(occurrenceKey,spoolId,contactId,channel,"SESSION_"+decision.reason(),null);
                        continue;
                    }
                    claimId=decision.claimId(); nextAttempt=decision.attemptNo();
                } else {
                    var latest=attempts.latest(tenant,eventKind,sourceKind,sendAlarmId,contactId,channel,occurrenceKey);
                    if(latest!=null) {
                        if(latest.status().equals("PENDING")){attempts.finish(latest.id(),"UNKNOWN","PENDING_ON_RESTART",null);continue;}
                        if(!latest.status().equals("FAILED")&&!latest.status().equals("RESOURCE_UNAVAILABLE"))continue;
                        nextAttempt=latest.attemptNo()+1;
                        if(nextAttempt>NotificationProviderAttemptService.MAX_ATTEMPTS)continue;
                    }
                }
                boolean delivered=false;String terminalStatus="FAILED",terminalCode=null,terminalProviderId=null;int lastAttempt=nextAttempt;
                for(int attemptNo=nextAttempt;attemptNo<=NotificationProviderAttemptService.MAX_ATTEMPTS&&(initial||attemptNo==nextAttempt);attemptNo++) {
                    lastAttempt=attemptNo;
                    var attempt=attempts.begin(tenant,eventKind,sourceKind,sendAlarmId,deviceId,contactId,channel,occurrenceKey,attemptNo,phone,client.accountFingerprint());
                    NotificationResourceService.Reservation reservation;
                    try {reservation=resources.reserve(tenant,deviceId,channel);}
                    catch(RuntimeException error){attempts.finish(attempt.id(),"RESOURCE_UNAVAILABLE","NOTIFICATION_RESOURCE_UNAVAILABLE",null);terminalStatus="RESOURCE_UNAVAILABLE";terminalCode="NOTIFICATION_RESOURCE_UNAVAILABLE";break;}
                    AliyunNotificationClient.Result result;
                    try {String actualPhone=phones.normalize(phone);result=channel.equals("SMS")?client.sendSms(actualPhone,params,NotificationProviderAttemptService.outIdFor(attempt.id())):client.sendVoice(actualPhone,params,NotificationProviderAttemptService.outIdFor(attempt.id()));}
                    catch(RuntimeException error){attempts.finish(attempt.id(),"UNKNOWN","PROVIDER_EXCEPTION",null);terminalStatus="UNKNOWN";terminalCode="PROVIDER_EXCEPTION";break;}
                    if(result.unknown()){attempts.finish(attempt.id(),"UNKNOWN",result.code(),result.providerId());terminalStatus="UNKNOWN";terminalCode=result.code();terminalProviderId=result.providerId();break;}
                    if(result.success()){attempts.finish(attempt.id(),"SENT",result.code(),result.providerId());delivered=true;terminalStatus="SENT";terminalCode=result.code();terminalProviderId=result.providerId();break;}
                    resources.release(reservation,key);attempts.finish(attempt.id(),"FAILED",result.code(),result.providerId());terminalStatus="FAILED";terminalCode=result.code();terminalProviderId=result.providerId();
                    log.warn("real notification event={} contact={} channel={} code={}",sourceId,contactId,channel,result.code());
                    if("NOT_CONFIGURED".equals(result.code())||"TEMPLATE_VARIABLE_MISSING".equals(result.code()))break;
                }
                if(claimId!=null) {
                    // Keep CLAIMED while retrying; a crash here stays ambiguous and can never trigger a blind resend.
                    merge.finish(claimId,delivered?"SENT":("UNKNOWN".equals(terminalStatus)?"UNKNOWN":"FAILED"),lastAttempt,terminalCode,terminalProviderId);
                }
                if(delivered)spool.markDone(occurrenceKey,spoolId,contactId,channel,"SENT",terminalCode);
                else if(!"UNKNOWN".equals(terminalStatus)) {
                    if(initial||"TEMPLATE_VARIABLE_MISSING".equals(terminalCode)||lastAttempt>=NotificationProviderAttemptService.MAX_ATTEMPTS)
                        spool.markDead(occurrenceKey,spoolId,contactId,channel,terminalCode==null?terminalStatus:terminalCode,null);
                    else pending=true;
                }
                log.info("real notification event={} kind={} contact={} channel={} status={} code={}",sourceId,eventKind,contactId,channel,terminalStatus,terminalCode);
            }
        }
        return !pending;
    }

    private static Map<String,String> paramsFor(Map<String,Object> alarm,boolean fire,String eventCode) {
        Map<String,String> params=new LinkedHashMap<>();
        String deviceName=Objects.toString(alarm.get("device_name"),null),address=Objects.toString(alarm.get("address"),null);
        if(address==null||address.isBlank()) address="位置未知";
        Object occurred=alarm.get("occurred_at");
        String alarmTime=occurred instanceof OffsetDateTime time?DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(time):Objects.toString(occurred,null);
        if(fire&&(Boolean.FALSE.equals(alarm.get("source_time_known"))||"false".equalsIgnoreCase(String.valueOf(alarm.get("source_time_known"))))) alarmTime="来源时间未知";
        if(fire)params.put("alarm_id",String.valueOf(alarm.get("id")));
        else {params.put("event_code",eventCode);params.put("event_kind","FAULT");}
        params.put("device_name",deviceName);params.put("address",address);params.put("deviceName",deviceName);params.put("addr",address);params.put("alarmTime",alarmTime);params.put("date",alarmTime);params.put("event",fire?"火灾报警":"设备故障");
        return params;
    }

}
