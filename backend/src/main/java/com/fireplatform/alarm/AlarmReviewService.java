package com.fireplatform.alarm;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.validation.constraints.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmReviewService {
    public record ReviewRequest(@NotNull @Min(0) Long expectedRevision,@NotBlank @Pattern(regexp="TRUE_FIRE|FALSE_ALARM|TEST|DEVICE_ALARM") String conclusion,@Size(max=Limits.MAX_ALARM_REMARK_LENGTH) String remark) { }
    private record Fingerprint(String tenantId,String alarmId,ReviewRequest body) { }
    private record Before(long revision,AlarmTraceModels.State state) { }
    private final JdbcTemplate jdbc; private final WriteAuthorization auth; private final IdempotencyStore idem; private final AuditService audit; private final AlarmQueryService reads; private final AlarmTraceWriter traces; private final ActorSnapshots actors;private final com.fireplatform.shared.DeviceAccessScope scopes;
    private final com.fireplatform.shared.ReminderScheduling reminders;
    public AlarmReviewService(JdbcTemplate jdbc,WriteAuthorization auth,IdempotencyStore idem,AuditService audit,AlarmQueryService reads,AlarmTraceWriter traces,ActorSnapshots actors,com.fireplatform.shared.DeviceAccessScope scopes,com.fireplatform.shared.ReminderScheduling reminders) { this.jdbc=jdbc; this.auth=auth; this.idem=idem; this.audit=audit; this.reads=reads; this.traces=traces; this.actors=actors;this.scopes=scopes;this.reminders=reminders; }
    @Transactional public AlarmQueryService.AlarmRow review(AuthService.Identity identity,String tenant,String alarmId,String key,ReviewRequest body,String requestId) {
        idem.validateKey(key); auth.lockAndAuthorize(identity,"alarm:review",tenant,null); validateRemark(body.remark());
        reads.alarm(identity.user().id(),tenant,alarmId);
        String hash=idem.fingerprint(new Fingerprint(tenant,alarmId,body));
        var replay=idem.replay(identity.user().id(),"review",key,hash,AlarmQueryService.AlarmRow.class); if(replay!=null) return replay;
        var actor=actors.current(identity.user().id());
        var scope=scopes.predicate(identity.user().id(),"a.tenant_id","a.device_id");var lockArgs=new java.util.ArrayList<Object>();lockArgs.add(alarmId);lockArgs.add(tenant);lockArgs.addAll(scope.bindings());
        var rows=jdbc.query("SELECT a.revision,a.physical_state,a.response_state,a.conclusion FROM alarms a WHERE a.id=? AND a.tenant_id=?"+scope.and()+" FOR UPDATE",(rs,n)->new Before(rs.getLong(1),new AlarmTraceModels.State(rs.getString(2),rs.getString(3),rs.getString(4))),lockArgs.toArray());
        if(rows.isEmpty()) throw ApiError.missing(); var before=rows.getFirst();
        if(before.revision()!=body.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT","报警已更新，请刷新后复核");
        var writeScope=scopes.predicate(identity.user().id(),"d.tenant_id","d.id");var writeArgs=new java.util.ArrayList<Object>();writeArgs.add(body.conclusion());writeArgs.add(body.remark());writeArgs.add(alarmId);writeArgs.add(tenant);writeArgs.add(body.expectedRevision());writeArgs.addAll(writeScope.bindings());
        int changed=jdbc.update("UPDATE alarms SET response_state='HANDLED',conclusion=?,remark=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=? AND EXISTS(SELECT 1 FROM devices d WHERE d.id=alarms.device_id AND d.tenant_id=alarms.tenant_id"+writeScope.and()+")",writeArgs.toArray());
        if(changed!=1) throw ApiError.conflict("REVISION_CONFLICT","报警已更新，请刷新后复核");
        reminders.cancelForHandled(tenant,alarmId);
        traces.append(tenant,alarmId,"REVIEWED",before.revision(),before.revision()+1,before.state(),new AlarmTraceModels.State(before.state().physicalState(),"HANDLED",body.conclusion()),null,actor,body.remark());
        var result=reads.alarm(identity.user().id(),tenant,alarmId); idem.save(identity.user().id(),"review",key,hash,result);
        audit.record(identity.user().id(),tenant,"REVIEW_ALARM",alarmId,"SUCCESS",requestId); return result;
    }
    private void validateRemark(String value) {
        if(value==null) return; if(value.length()>Limits.MAX_ALARM_REMARK_LENGTH || value.indexOf(0)>=0) throw ApiError.bad("备注无效");
        try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); } catch(CharacterCodingException e) { throw ApiError.bad("文本编码无效"); }
    }
}
