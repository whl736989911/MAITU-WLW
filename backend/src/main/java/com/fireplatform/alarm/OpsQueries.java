package com.fireplatform.alarm;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N14：值守概览。只读统计；head/计数不构成"无漏报"保证。 */
@Service
public class OpsQueries {
    public record Alarms(long unhandled,long fire,OffsetDateTime oldestUnhandledAt) { }
    public record Channel(Long head,long pending,OffsetDateTime lastRecordedAt) { }
    public record Tasks(long rawMessagePending,long reparsePending) { }
    public record Sources(long enabled,OffsetDateTime lastReceivedAt) { }
    public record Overview(OffsetDateTime computedAt,Alarms alarms,Channel channel,Tasks tasks,Sources sources,String semantics) { }

    private final JdbcTemplate jdbc; private final com.fireplatform.configuration.DatabaseTime time; private final com.fireplatform.shared.DeviceAccessScope scopes;
    public OpsQueries(JdbcTemplate jdbc,com.fireplatform.configuration.DatabaseTime time,com.fireplatform.shared.DeviceAccessScope scopes) { this.jdbc=jdbc; this.time=time; this.scopes=scopes; }

    @Transactional(readOnly=true) public Overview read(String actor,String tenant) {
        String tenantAnd=tenant==null?"":" AND tenant_id=?";
        var tenantArgs=tenant==null?java.util.List.<Object>of():java.util.List.<Object>of(tenant);
        var alarmScope=scopes.predicate(actor,"alarms.tenant_id","alarms.device_id");
        var alarmArgs=new java.util.ArrayList<Object>(tenantArgs); alarmArgs.addAll(alarmScope.bindings());
        String realAlarm=" AND is_simulated=FALSE";
        long unhandled=jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE response_state='UNHANDLED'"+realAlarm+tenantAnd+alarmScope.and(),Long.class,alarmArgs.toArray());
        long fire=jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE response_state='UNHANDLED' AND physical_state='FIRE'"+realAlarm+tenantAnd+alarmScope.and(),Long.class,alarmArgs.toArray());
        List<OffsetDateTime> oldest=jdbc.queryForList("SELECT MIN(occurred_at) FROM alarms WHERE response_state='UNHANDLED' AND source_time_known IS NOT FALSE"+realAlarm+tenantAnd+alarmScope.and(),OffsetDateTime.class,alarmArgs.toArray());

        var channelScope=scopes.predicate(actor,"alarm_change_outbox.tenant_id","alarm_change_outbox.device_id");
        var channelArgs=new java.util.ArrayList<Object>(tenantArgs); channelArgs.addAll(channelScope.bindings());
        String channelWhere=tenant==null?" WHERE ":" WHERE tenant_id=? AND ";
        String realChange=" AND NOT EXISTS (SELECT 1 FROM alarm_trace_entries t JOIN alarms a ON a.tenant_id=t.tenant_id AND a.id=t.alarm_id WHERE t.id=alarm_change_outbox.id AND t.tenant_id=alarm_change_outbox.tenant_id AND a.is_simulated=TRUE)";
        Long head=jdbc.queryForObject("SELECT CASE WHEN COUNT(*)=0 THEN NULL ELSE COUNT(*) END FROM alarm_change_outbox WHERE publication_sequence IS NOT NULL"+tenantAnd+channelScope.and()+realChange,Long.class,channelArgs.toArray());
        long pending=jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_outbox WHERE publication_sequence IS NULL"+tenantAnd+channelScope.and()+realChange,Long.class,channelArgs.toArray());
        List<OffsetDateTime> lastRecorded=jdbc.queryForList("SELECT MAX(recorded_at) FROM alarm_change_outbox"+channelWhere+channelScope.sql()+realChange,OffsetDateTime.class,channelArgs.toArray());

        var rawScope=scopes.predicate(actor,"rm.tenant_id","rm.device_id");
        var rawArgs=new java.util.ArrayList<Object>(); rawArgs.addAll(tenantArgs); rawArgs.addAll(rawScope.bindings());
        long rawMessagePending=jdbc.queryForObject("SELECT COUNT(*) FROM raw_messages rm WHERE rm.capture_provenance='AUTHENTICATED_SOURCE' AND rm.binding_state='BOUND' AND rm.application_state='NOT_APPLIED'"+(tenant==null?"":" AND rm.tenant_id=?")+rawScope.and(),Long.class,rawArgs.toArray());

        var reparseScope=scopes.predicate(actor,"rm.tenant_id","rm.device_id");
        var reparseArgs=new java.util.ArrayList<Object>(); reparseArgs.addAll(tenantArgs); reparseArgs.addAll(reparseScope.bindings());
        String reparseWhere=tenant==null?" WHERE ":" WHERE r.tenant_id=? AND ";
        String reparseVisible="EXISTS (SELECT 1 FROM raw_messages rm WHERE rm.id=r.raw_message_id AND rm.tenant_id=r.tenant_id AND "+reparseScope.sql()+")";
        long reparsePending=jdbc.queryForObject("SELECT COUNT(*) FROM reparse_jobs r"+reparseWhere+"r.status='PENDING' AND "+reparseVisible,Long.class,reparseArgs.toArray());

        // Ingestion sources are tenant-level, not device-attributed. Include only tenants for
        // which this actor has complete device visibility; restricted tenants are not inferred.
        var sourceScope=scopes.allPredicate(actor,"ingestion_sources.tenant_id");
        var sourceArgs=new java.util.ArrayList<Object>(tenantArgs); sourceArgs.addAll(sourceScope.bindings());
        String sourceWhere=tenant==null?" WHERE ":" WHERE tenant_id=? AND ";
        Long sourcesEnabled=jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_sources"+sourceWhere+sourceScope.sql()+" AND enabled=TRUE AND is_simulated=FALSE",Long.class,sourceArgs.toArray());
        List<OffsetDateTime> lastReceived=jdbc.queryForList("SELECT MAX(last_received_at) FROM ingestion_sources"+sourceWhere+sourceScope.sql()+" AND enabled=TRUE AND is_simulated=FALSE",OffsetDateTime.class,sourceArgs.toArray());
        return new Overview(time.now(),
            new Alarms(unhandled,fire,oldest.isEmpty()?null:oldest.getFirst()),
            new Channel(head,pending,lastRecorded.isEmpty()?null:lastRecorded.getFirst()),
            new Tasks(rawMessagePending,reparsePending),
            new Sources(sourcesEnabled,lastReceived.isEmpty()?null:lastReceived.getFirst()),
            "设备归属的真实报警、变更及任务统计严格按当前设备范围筛选；来源统计仅包含其设备范围完整可见的真实单位。head与计数只是当前观测，不构成无漏报保证；补拉以alarm-changes游标与has_more为准，断线期间不推断已补齐。");
    }
}
