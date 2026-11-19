package com.fireplatform.duty;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local duty disposition only; never changes the source event or device physical/connectivity state. */
@Service
public class FaultHandlingService {
    public record Handling(String tenantId,String eventId,boolean handled,String note,long revision,String updatedBy,OffsetDateTime updatedAt) { }
    private record Fingerprint(String tenant,String event,boolean handled,String note,long revision) { }
    private final JdbcTemplate jdbc;private final ExecutionAuthorization auth;private final WriteAuthorization writes;private final TenantFence tenants;private final IdempotencyStore idem;private final AuditService audit;private final DutyQueries reads;private final DatabaseTime time;
    public FaultHandlingService(JdbcTemplate jdbc,ExecutionAuthorization auth,WriteAuthorization writes,TenantFence tenants,IdempotencyStore idem,AuditService audit,DutyQueries reads,DatabaseTime time){this.jdbc=jdbc;this.auth=auth;this.writes=writes;this.tenants=tenants;this.idem=idem;this.audit=audit;this.reads=reads;this.time=time;}
    @Transactional public Handling set(AuthService.Identity actor,String audience,String tenant,String event,String key,boolean handled,long expected,String note,String request){
        idem.validateKey(key);
        if("management".equals(audience)) {
            auth.lockRequest(actor,tenant,List.of("faults:handle","device:read"));
        } else {
            // Customer disposition uses its own permission and current session; no management grant is implied.
            writes.lockAndAuthorize(actor,"faults:handle",tenant,"customer");
            writes.lockAndAuthorize(actor,"device:read",tenant,"customer");
        }
        tenants.lockEnabled(tenant);
        // Applies the actor's current device scope, including a dynamic place subtree, before any write.
        reads.fault(actor.user().id(),tenant,ScopeFilter.Selection.all(),event);
        if(note==null)note="";if(note.length()>1000||note.indexOf('\0')>=0)throw ApiError.bad("本地处置说明无效");
        String hash=idem.fingerprint(new Fingerprint(tenant,event,handled,note,expected));var replay=idem.replay(actor.user().id(),"duty-fault-handle",key,hash,Handling.class);if(replay!=null)return replay;
        var current=jdbc.query("SELECT handled,note,revision,updated_by,updated_at FROM duty_fault_handlings WHERE tenant_id=? AND event_id=? FOR UPDATE",(rs,n)->new Handling(tenant,event,rs.getBoolean(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getObject(5,OffsetDateTime.class)),tenant,event);
        long revision=current.isEmpty()?0:current.getFirst().revision();if(revision!=expected)throw ApiError.conflict("REVISION_CONFLICT","故障处置记录已更新，请刷新后重试");
        var now=time.now().withOffsetSameInstant(java.time.ZoneOffset.UTC);long next=revision+1;
        if(current.isEmpty())jdbc.update("INSERT INTO duty_fault_handlings(tenant_id,event_id,handled,note,revision,updated_by,updated_at) VALUES(?,?,?,?,?,?,?)",tenant,event,handled,note,next,actor.user().id(),now);
        else if(jdbc.update("UPDATE duty_fault_handlings SET handled=?,note=?,revision=?,updated_by=?,updated_at=? WHERE tenant_id=? AND event_id=? AND revision=?",handled,note,next,actor.user().id(),now,tenant,event,revision)!=1)throw ApiError.conflict("REVISION_CONFLICT","故障处置记录已更新，请刷新后重试");
        jdbc.update("INSERT INTO duty_fault_handling_history(id,tenant_id,event_id,actor_id,handled,note,revision,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),tenant,event,actor.user().id(),handled,note,next,now);
        var result=new Handling(tenant,event,handled,note,next,actor.user().id(),now);idem.save(actor.user().id(),"duty-fault-handle",key,hash,result);audit.record(actor.user().id(),tenant,"DUTY_FAULT_HANDLING",event,handled?"HANDLED":"UNHANDLED",request);return result;
    }
    @Transactional(readOnly=true) public List<Handling> history(String actor,String tenant,String event){reads.fault(actor,tenant,ScopeFilter.Selection.all(),event);return jdbc.query("SELECT tenant_id,event_id,handled,note,revision,actor_id,created_at FROM duty_fault_handling_history WHERE tenant_id=? AND event_id=? ORDER BY revision DESC",(rs,n)->new Handling(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getObject(7,OffsetDateTime.class)),tenant,event);}
}
