package com.fireplatform.legacyingest.facts;

import com.fireplatform.alarm.AlarmLifecycle;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LegacyAlarmLifecycleFixture implements AlarmLifecycle {
    private final JdbcTemplate jdbc;
    LegacyAlarmLifecycleFixture(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public String fire(String tenant,String deviceId,String name,String address,OffsetDateTime at) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision,is_simulated,last_event_at) VALUES (?,?,?, ?,?,'FIRE',?,'FIRE','UNHANDLED',0,FALSE,?)",id,tenant,deviceId,name,address,at,at);
        return id;
    }
    @Override public boolean recoverAlarm(String tenant,String deviceId,String alarmId,OffsetDateTime at) {
        return jdbc.update("UPDATE alarms SET physical_state='NORMAL',revision=revision+1 WHERE tenant_id=? AND device_id=? AND id=? AND physical_state='FIRE' AND occurred_at<=?",tenant,deviceId,alarmId,at)==1;
    }
    @Override public void recoverDevice(String tenant,String deviceId) { jdbc.update("UPDATE alarms SET physical_state='NORMAL',revision=revision+1 WHERE tenant_id=? AND device_id=? AND physical_state='FIRE'",tenant,deviceId); }
    @Override public boolean hasActiveFire(String tenant,String deviceId) { return jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id=? AND device_id=? AND physical_state='FIRE'",Long.class,tenant,deviceId)>0; }
}
