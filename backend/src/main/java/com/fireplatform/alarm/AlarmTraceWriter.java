package com.fireplatform.alarm;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.ActorSnapshots;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Alarm-owned immutable journal. No independent transaction, no controller, no successful trace before commit. */
@Service @Transactional(propagation=Propagation.MANDATORY)
public class AlarmTraceWriter {
    private final JdbcTemplate jdbc; private final DatabaseTime time; private final AlarmChangeCapture changes; private final ApplicationEventPublisher events;
    public AlarmTraceWriter(JdbcTemplate jdbc,DatabaseTime time,AlarmChangeCapture changes,ApplicationEventPublisher events) { this.jdbc=jdbc; this.time=time; this.changes=changes;this.events=events; }
    public void append(String tenant,String alarm,String action,Long beforeRevision,long afterRevision,AlarmTraceModels.State before,AlarmTraceModels.State after,OffsetDateTime occurred,ActorSnapshots.Actor actor,String remark) {
        String traceId=UUID.randomUUID().toString();var recordedAt=time.now();
        jdbc.update("INSERT INTO alarm_trace_entries(id,tenant_id,alarm_id,action,revision_before,revision_after,before_physical_state,before_response_state,before_conclusion,after_physical_state,after_response_state,after_conclusion,occurred_at,recorded_at,actor_kind,actor_id,actor_name,remark,origin) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'APPLICATION')",traceId,tenant,alarm,action,beforeRevision,afterRevision,before==null?null:before.physicalState(),before==null?null:before.responseState(),before==null?null:before.conclusion(),after.physicalState(),after.responseState(),after.conclusion(),occurred,recordedAt,actor==null?"SYSTEM":actor.kind(),actor==null?null:actor.id(),actor==null?null:actor.name(),remark);
        changes.capture(traceId,tenant,recordedAt);
        events.publishEvent(new AlarmApplicationChangeCommitted(tenant,alarm,traceId));
    }
}
