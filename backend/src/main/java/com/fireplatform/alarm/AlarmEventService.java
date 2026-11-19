package com.fireplatform.alarm;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation=Propagation.MANDATORY)
public class AlarmEventService implements AlarmLifecycle {
    private record Current(long revision,OffsetDateTime occurred,Boolean sourceTimeKnown,String deviceId,AlarmTraceModels.State state) { }
    private final JdbcTemplate jdbc; private final AlarmTraceWriter traces;
    private final com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer configuration;
    private final com.fireplatform.alarmgroup.AlarmSessionGrouping grouping;
    private final Clock clock;
    public AlarmEventService(JdbcTemplate jdbc,AlarmTraceWriter traces,com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer configuration,com.fireplatform.alarmgroup.AlarmSessionGrouping grouping,Clock clock) { this.jdbc=jdbc;this.traces=traces;this.configuration=configuration;this.grouping=grouping;this.clock=clock; }
    private String snapshotAddress(String tenant,String deviceId,String address) {
        if(address!=null && !address.isBlank()) return address;
        return jdbc.query("SELECT p.name FROM devices d JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id WHERE d.tenant_id=? AND d.id=?",
            rs->rs.next()?rs.getString(1):"",tenant,deviceId);
    }
    @Override public String fire(String tenant,String deviceId,String deviceName,String address,OffsetDateTime occurredAt) {
        String id=UUID.randomUUID().toString();
        OffsetDateTime platformRecordedAt=OffsetDateTime.now(clock);
        OffsetDateTime effectiveOccurredAt=occurredAt==null?platformRecordedAt:occurredAt;
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,last_event_at,physical_state,response_state,revision,is_simulated,source_time_known,platform_recorded_at) VALUES (?,?,?,?,?,'FIRE',?,?,'FIRE','UNHANDLED',0,FALSE,?,?)",id,tenant,deviceId,deviceName,snapshotAddress(tenant,deviceId,address),effectiveOccurredAt,effectiveOccurredAt,occurredAt!=null,platformRecordedAt);
        traces.append(tenant,id,"FIRE_CREATED",null,0,null,new AlarmTraceModels.State("FIRE","UNHANDLED",null),occurredAt,null,null);
        configuration.onAlarmCreated(tenant,id,deviceId,"FIRE",effectiveOccurredAt);
        grouping.onAlarmCreated(tenant,id,deviceId,"FIRE",occurredAt);
        return id;
    }
    private Current current(java.sql.ResultSet rs) throws java.sql.SQLException { return new Current(rs.getLong("revision"),rs.getObject("occurred_at",OffsetDateTime.class),rs.getObject("source_time_known",Boolean.class),rs.getString("device_id"),new AlarmTraceModels.State(rs.getString("physical_state"),rs.getString("response_state"),rs.getString("conclusion"))); }
    @Override public boolean recoverAlarm(String tenant,String deviceId,String alarmId,OffsetDateTime occurredAt) {
        var rows=jdbc.query("SELECT * FROM alarms WHERE id=? AND tenant_id=? AND device_id=? AND is_simulated=FALSE FOR UPDATE",(rs,n)->current(rs),alarmId,tenant,deviceId);
        if(rows.isEmpty() || !rows.getFirst().state().physicalState().equals("FIRE") || !Boolean.FALSE.equals(rows.getFirst().sourceTimeKnown()) && rows.getFirst().occurred().isAfter(occurredAt)) return false;
        recover(tenant,alarmId,rows.getFirst(),"ALARM_RECOVERED",occurredAt); return true;
    }
    private void recover(String tenant,String alarm,Current before,String action,OffsetDateTime occurred) {
        int changed=jdbc.update("UPDATE alarms SET physical_state='NORMAL',revision=revision+1 WHERE id=? AND tenant_id=? AND revision=? AND physical_state='FIRE'",alarm,tenant,before.revision()); if(changed!=1) throw new IllegalStateException("Locked alarm changed during recovery");
        var after=new AlarmTraceModels.State("NORMAL",before.state().responseState(),before.state().conclusion()); traces.append(tenant,alarm,action,before.revision(),before.revision()+1,before.state(),after,occurred,null,null);
        configuration.onAlarmRecovered(tenant,alarm,before.deviceId(),occurred);
        grouping.onAlarmRecovered(tenant,alarm,before.deviceId(),occurred);
    }
    @Override public boolean hasActiveFire(String tenant,String deviceId) { return jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id=? AND device_id=? AND physical_state='FIRE' AND is_simulated=FALSE",Long.class,tenant,deviceId)>0; }
    @Override public void recoverDevice(String tenant,String deviceId) {
        record Target(String id,Current before) { }
        var rows=jdbc.query("SELECT * FROM alarms WHERE tenant_id=? AND device_id=? AND physical_state='FIRE' AND is_simulated=FALSE ORDER BY id FOR UPDATE",(rs,n)->new Target(rs.getString("id"),current(rs)),tenant,deviceId);
        for(var row:rows) recover(tenant,row.id(),row.before(),"DEVICE_RECOVERED",null);
    }
}
