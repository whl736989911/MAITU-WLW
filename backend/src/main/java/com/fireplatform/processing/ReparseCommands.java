package com.fireplatform.processing;

import com.fireplatform.ingestion.CapturedMessageCatalog;
import com.fireplatform.ingestion.IngestionGuard;
import com.fireplatform.product.PublishedMessageNormalizer;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N07：重解析的创建与查询。创建只排队；解析由 worker 执行，全程无业务写入。 */
@Service
public class ReparseCommands {
    private final JdbcTemplate jdbc; private final IngestionGuard guard; private final CapturedMessageCatalog captures; private final ReparseQueue queue; private final IdempotencyStore json; private final AuditService audit; private final com.fireplatform.configuration.DatabaseTime time; private final DeviceAccessScope devices;
    public ReparseCommands(JdbcTemplate jdbc,IngestionGuard guard,CapturedMessageCatalog captures,ReparseQueue queue,IdempotencyStore json,AuditService audit,com.fireplatform.configuration.DatabaseTime time,DeviceAccessScope devices) { this.jdbc=jdbc; this.guard=guard; this.captures=captures; this.queue=queue; this.json=json; this.audit=audit; this.time=time; this.devices=devices; }

    @Transactional public ReparseModels.Job create(com.fireplatform.identity.AuthService.Identity identity,String tenant,String rawId,String key,String reason) {
        guard.begin(identity,tenant,"messages:reparse",key,false);
        String actor=identity.user().id();
        var fact=captures.read(tenant,rawId);
        devices.requireDevice(actor,tenant,fact.deviceId());
        if(!"BOUND".equals(fact.bindingState()) || fact.productVersionId()==null) throw ApiError.conflict("CAPTURED_VERSION_REQUIRED","该报文未绑定可用版本，不能重解析");
        var existing=jdbc.queryForList("SELECT id FROM reparse_jobs WHERE raw_message_id=? AND tenant_id=?",String.class,rawId,tenant);
        if(!existing.isEmpty()) throw ApiError.conflict("REPARSE_ALREADY_EXISTS","该消息已有重解析任务");
        String id=UUID.randomUUID().toString(); var now=time.now();
        jdbc.update("INSERT INTO reparse_jobs(id,tenant_id,raw_message_id,product_version_id,actor_id,reason,status,attempt_count,max_attempts,available_at,created_at) VALUES(?,?,?,?,?,?,'PENDING',0,3,?,?)",id,tenant,rawId,fact.productVersionId(),actor,reason,now,now);
        audit.record(actor,tenant,"REPARSE_QUEUED",id,"SUCCESS","api");
        return job(tenant,id);
    }

    private ReparseModels.Job job(String tenant,String id) {
        var rows=jdbc.query("SELECT id,tenant_id,raw_message_id,product_version_id,actor_id,reason,status,attempt_count,max_attempts,revision,created_at,started_at,finished_at,last_error_code,result_json FROM reparse_jobs WHERE id=? AND tenant_id=?",(rs,n)->new ReparseModels.Job(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8),rs.getInt(9),rs.getLong(10),rs.getObject(11,java.time.OffsetDateTime.class),rs.getObject(12,java.time.OffsetDateTime.class),rs.getObject(13,java.time.OffsetDateTime.class),rs.getString(14),rs.getString(15)),id,tenant);
        if(rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public ReparseModels.Detail detail(String actorId,String tenant,String id) {
        var access=devices.predicate(actorId,"rm.tenant_id","rm.device_id");
        var args=new ArrayList<Object>();
        args.add(id);
        args.add(tenant);
        args.addAll(access.bindings());
        var jobs=jdbc.query("SELECT r.id FROM reparse_jobs r WHERE r.id=? AND r.tenant_id=? AND EXISTS(SELECT 1 FROM raw_messages rm WHERE rm.id=r.raw_message_id AND rm.tenant_id=r.tenant_id"+access.and()+")",(rs,n)->rs.getString(1),args.toArray());
        if(jobs.isEmpty()) throw ApiError.missing();
        var job=job(tenant,id);
        var attempts=jdbc.query("SELECT id,job_id,attempt_no,lease_token,worker_id,started_at,finished_at,outcome,error_code,result_json FROM reparse_attempts WHERE job_id=? ORDER BY attempt_no",(rs,n)->new ReparseModels.Attempt(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getLong(4),rs.getString(5),rs.getObject(6,java.time.OffsetDateTime.class),rs.getObject(7,java.time.OffsetDateTime.class),rs.getString(8),rs.getString(9),rs.getString(10)),id);
        return new ReparseModels.Detail(job,attempts);
    }

    public Page<ReparseModels.Job> list(String actorId,String tenant,int page,int size,String status) {
        int limit=Math.min(Math.max(size,1),100), offset=(Math.max(page,1)-1)*limit;
        var access=devices.predicate(actorId,"rm.tenant_id","rm.device_id");
        String where="r.tenant_id=? AND EXISTS(SELECT 1 FROM raw_messages rm WHERE rm.id=r.raw_message_id AND rm.tenant_id=r.tenant_id"+access.and()+")";
        List<Object> args=new ArrayList<>(); args.add(tenant); args.addAll(access.bindings());
        if(status!=null && !status.isBlank()) { where+=" AND r.status=?"; args.add(status); }
        var total=jdbc.queryForObject("SELECT COUNT(*) FROM reparse_jobs r WHERE "+where,Long.class,args.toArray());
        var items=jdbc.query("SELECT r.id,r.tenant_id,r.raw_message_id,r.product_version_id,r.actor_id,r.reason,r.status,r.attempt_count,r.max_attempts,r.revision,r.created_at,r.started_at,r.finished_at,r.last_error_code,r.result_json FROM reparse_jobs r WHERE "+where+" ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",(rs,n)->new ReparseModels.Job(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8),rs.getInt(9),rs.getLong(10),rs.getObject(11,java.time.OffsetDateTime.class),rs.getObject(12,java.time.OffsetDateTime.class),rs.getObject(13,java.time.OffsetDateTime.class),rs.getString(14),rs.getString(15)),append(args,limit,offset));
        return new Page<>(items,total==null?0:total,page,limit);
    }

    private static Object[] append(List<Object> args,int... extra) { var copy=new ArrayList<>(args); for(int value:extra) copy.add(value); return copy.toArray(); }

    public CapturedMessageCatalog.Fact fact(String tenant,String raw) { return captures.read(tenant,raw); }
    public ReparseQueue queue() { return queue; }
    public String json(Object value) { return json.json(value); }
}
