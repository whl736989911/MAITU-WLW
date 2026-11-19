package com.fireplatform.alarm;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Required trace participant. Always captures; no publication or foreign-domain locks. */
@Service
public class AlarmChangeCapture {
    private final JdbcTemplate jdbc;
    public AlarmChangeCapture(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void capture(String id,String tenant,OffsetDateTime recordedAt){
        int changed=jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,device_id) VALUES (?,?,?,(SELECT a.device_id FROM alarm_trace_entries t JOIN alarms a ON a.tenant_id=t.tenant_id AND a.id=t.alarm_id WHERE t.id=? AND t.tenant_id=?))",id,tenant,recordedAt,id,tenant);
        if(changed!=1)throw new IllegalStateException("Alarm change was not captured");
    }
}
