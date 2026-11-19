package com.fireplatform.governance;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.device.DeviceCommandRetention;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.ingestion.RawMessageRetention;
import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N16: retention policy orchestration. Default unconfigured = disabled. Deletion is delegated to the
 * owning domain. Preview is a frozen candidate snapshot; execution requires that snapshot, the frozen
 * cutoff, the current policy revision, and an exact candidate-set digest. Any drift rolls the whole
 * transaction back instead of returning a partial "success".
 *
 * <p>Hardware command ledgers ({@code device_service_commands}) and payment/notification financial
 * facts are intentionally not retention kinds. The existing {@code DEVICE_COMMANDS} kind only owns
 * the legacy command table and keeps PENDING/DISPATCHED rows.
 *
 * <p>Tenant-wide policy/config/preview/execute require the actor's current explicit ALL_TENANT device
 * scope. The check runs after the same management-coordination and actor locks used by scope
 * administration, so a scope revocation committed after preview is visible and denies execute.
 */
@Service
public class RetentionService {
    public static final List<String> KINDS=List.of("RAW_MESSAGES","DEVICE_COMMANDS","AUDIT_LOG");
    private static final Duration PREVIEW_TTL=Duration.ofMinutes(15);

    public record Policy(String tenantId,String tenantName,String kind,Integer keepDays,boolean enabled,
                         OffsetDateTime updatedAt,long revision,boolean configured) { }
    public record PolicyPage(String mode,List<Policy> items,long total,int page,int size,
                             long authorizedUnits,long configuredPolicies,long unconfiguredKindSlots,
                             List<String> scopeTenantIds,List<String> scopePlaceIds,boolean scopeEmpty) { }
    /**
     * {@code deleted} is the real deletion count for executions. For a new preview it is 0.
     * A legacy dry-run row (created before V79) stored a potential count in the old deleted column;
     * it is exposed as {@code eligible} with {@code legacyPotentialCount=true} and never relabelled
     * as an actual deletion. Historical rows are not rewritten.
     */
    public record Run(String id,String tenantId,String tenantName,String kind,int keepDays,
                      OffsetDateTime cutoffAt,boolean dryRun,long scanned,Long eligible,Long deleted,
                      boolean legacyPotentialCount,String candidateFingerprint,Long policyRevision,
                      OffsetDateTime expiresAt,String sourcePreviewId,String reason,OffsetDateTime createdAt) { }

    private record PolicyRow(int keepDays,boolean enabled,OffsetDateTime updatedAt,long revision) { }
    private record Candidates(long scanned,long eligible,String fingerprint) { }

    private final JdbcTemplate jdbc; private final DatabaseTime time; private final ExecutionAuthorization auth;
    private final TenantFence tenants; private final ScopeFilter scope; private final DeviceAccessScope devices;
    private final AuditService audit; private final IdempotencyStore idem;
    private final RawMessageRetention rawMessages; private final DeviceCommandRetention commands; private final AuditRetention auditRetention;

    public RetentionService(JdbcTemplate jdbc,DatabaseTime time,ExecutionAuthorization auth,TenantFence tenants,ScopeFilter scope,
                            DeviceAccessScope devices,AuditService audit,IdempotencyStore idem,RawMessageRetention rawMessages,
                            DeviceCommandRetention commands,AuditRetention auditRetention) {
        this.jdbc=jdbc; this.time=time; this.auth=auth; this.tenants=tenants; this.scope=scope; this.devices=devices;
        this.audit=audit; this.idem=idem;
        this.rawMessages=rawMessages; this.commands=commands; this.auditRetention=auditRetention;
    }

    static void kind(String kind) { if(kind==null || !KINDS.contains(kind)) throw ApiError.bad("保留类型未确认"); }

    /** Unit mode (explicit X-Tenant-Id) lists configured and unconfigured kinds. Scope mode lists configured rows only. */
    @Transactional(readOnly=true)
    public PolicyPage policies(String actorId,String tenant,ScopeFilter.Selection selection,int page,Integer requestedSize) {
        var paging=Pagination.of(page,requestedSize,20);
        if(tenant!=null) {
            var rows=policyRows(tenant,false);
            var name=tenantName(tenant);
            var items=new ArrayList<Policy>();
            for(String kind:KINDS) {
                var row=rows.get(kind);
                items.add(row==null ? new Policy(tenant,name,kind,null,false,null,0,false)
                    : new Policy(tenant,name,kind,row.keepDays(),row.enabled(),row.updatedAt(),row.revision(),true));
            }
            long configured=rows.size();
            return new PolicyPage("UNIT",List.copyOf(items),KINDS.size(),1,KINDS.size(),1,configured,KINDS.size()-configured,
                List.of(tenant),List.of(),false);
        }
        var clause=scope.units(actorId,"t","id",null,selection);
        long authorized=count("SELECT COUNT(*) FROM tenants t WHERE 1=1"+clause.and(),clause.bindings());
        long configured=count("SELECT COUNT(*) FROM retention_policies p JOIN tenants t ON t.id=p.tenant_id WHERE 1=1"+clause.and(),clause.bindings());
        var bindings=new ArrayList<Object>(clause.bindings()); bindings.add(paging.size()); bindings.add(paging.offset());
        var items=jdbc.query("SELECT p.tenant_id,t.name,p.kind,p.keep_days,p.enabled,p.updated_at,p.revision "
            +"FROM retention_policies p JOIN tenants t ON t.id=p.tenant_id WHERE 1=1"+clause.and()
            +" ORDER BY t.name,p.tenant_id,p.kind LIMIT ? OFFSET ?",
            (rs,n)->new Policy(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getBoolean(5),
                utc(rs.getObject(6,OffsetDateTime.class)),rs.getLong(7),true),bindings.toArray());
        long unconfigured=Math.max(0,authorized*KINDS.size()-configured);
        return new PolicyPage("SCOPE",items,configured,paging.page(),paging.size(),authorized,configured,unconfigured,
            selection.tenantIds(),selection.placeIds(),selection.empty());
    }

    @Transactional public Policy configure(AuthService.Identity identity,String tenant,String key,
                                           String kind,int keepDays,boolean enabled,long expectedRevision,String reason,String requestId) {
        auth.lockRequest(identity,tenant,List.of("retention:write")); tenants.lockEnabled(tenant);
        devices.requireAll(identity.user().id(),tenant); kind(kind); idem.validateKey(key);
        if(keepDays<1 || keepDays>3650) throw ApiError.bad("保留天数须在1..3650");
        if(expectedRevision<0) throw ApiError.bad("expected_revision无效");
        var basis=requiredReason(reason);
        var hash=idem.fingerprint(List.of(tenant,kind,keepDays,enabled,expectedRevision,basis));
        var replay=idem.replay(identity.user().id(),"retention-policy",key,hash,Policy.class);
        if(replay!=null) return replay;
        var current=policyRows(tenant,true);
        var existing=current.get(kind);
        long revision=existing==null ? 0 : existing.revision();
        if(revision!=expectedRevision) throw ApiError.conflict("REVISION_CONFLICT","保留策略已变化，请刷新后重新确认");
        var now=time.now();
        if(existing==null) {
            try {
                // Absent policy has virtual revision 0 for expected_revision, but the first durable
                // revision is 1. A second creator that read unconfigured revision 0 must conflict,
                // never silently update the row created by the first creator.
                jdbc.update("INSERT INTO retention_policies(id,tenant_id,kind,keep_days,enabled,updated_by,updated_at,revision) VALUES(?,?,?,?,?,?,?,1)",
                    UUID.randomUUID().toString(),tenant,kind,keepDays,enabled,identity.user().id(),now);
            } catch(DuplicateKeyException race) {
                throw ApiError.conflict("REVISION_CONFLICT","保留策略已变化，请刷新后重新确认");
            }
        } else {
            int updated=jdbc.update("UPDATE retention_policies SET keep_days=?,enabled=?,updated_by=?,updated_at=?,revision=revision+1 WHERE tenant_id=? AND kind=? AND revision=?",
                keepDays,enabled,identity.user().id(),now,tenant,kind,revision);
            if(updated!=1) throw ApiError.conflict("REVISION_CONFLICT","保留策略已变化，请刷新后重新确认");
        }
        audit.recordReason(identity.user().id(),tenant,"RETENTION_POLICY_UPDATED",kind,"SUCCESS",requestId,basis);
        var result=policy(tenant,kind);
        idem.save(identity.user().id(),"retention-policy",key,hash,result);
        return result;
    }

    /** Real dry-run: computes the frozen candidate set and persists the preview, but deletes nothing. */
    @Transactional public Run preview(AuthService.Identity identity,String tenant,String key,String kind,String requestId) {
        auth.lockRequest(identity,tenant,List.of("retention:write")); tenants.lockEnabled(tenant);
        devices.requireAll(identity.user().id(),tenant); kind(kind); idem.validateKey(key);
        var policy=requiredPolicy(tenant,kind,true);
        requireEnabled(policy);
        var hash=idem.fingerprint(List.of(tenant,kind,policy.revision()));
        var replay=idem.replay(identity.user().id(),"retention-preview",key,hash,Run.class);
        if(replay!=null) return replay;
        var now=time.now();
        var cutoff=cutoff(now,policy.keepDays());
        var candidates=candidates(tenant,kind,cutoff);
        var id=UUID.randomUUID().toString();
        var expires=now.plus(PREVIEW_TTL);
        jdbc.update("INSERT INTO retention_runs(id,tenant_id,kind,keep_days,cutoff_at,dry_run,scanned,deleted,actor_id,created_at,"
                +"eligible,candidate_fingerprint,policy_revision,expires_at,source_preview_id,reason,request_id) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,?)",
            id,tenant,kind,policy.keepDays(),cutoff,true,candidates.scanned(),0L,identity.user().id(),now,
            candidates.eligible(),candidates.fingerprint(),policy.revision(),expires,requestId);
        audit.record(identity.user().id(),tenant,"RETENTION_PREVIEWED",kind,"SUCCESS",requestId);
        var result=run(id);
        idem.save(identity.user().id(),"retention-preview",key,hash,result);
        return result;
    }

    /**
     * Irreversible execution against a frozen preview. The policy revision, frozen cutoff, eligible
     * count and exact candidate digest must all still match; the actual deleted count must equal the
     * previewed eligible count. Otherwise the transaction throws and every partial delete is rolled back.
     */
    @Transactional public Run execute(AuthService.Identity identity,String tenant,String key,String kind,
                                      String previewId,long expectedRevision,String reason,String requestId) {
        auth.lockRequest(identity,tenant,List.of("retention:write")); tenants.lockEnabled(tenant);
        devices.requireAll(identity.user().id(),tenant); kind(kind); idem.validateKey(key);
        var basis=requiredReason(reason);
        if(previewId==null || previewId.isBlank() || previewId.length()>64) throw ApiError.bad("preview_id无效");
        var hash=idem.fingerprint(List.of(tenant,kind,previewId,expectedRevision,basis));
        var replay=idem.replay(identity.user().id(),"retention-execute",key,hash,Run.class);
        if(replay!=null) return replay;
        var policy=requiredPolicy(tenant,kind,true);
        requireEnabled(policy);
        if(policy.revision()!=expectedRevision) throw ApiError.conflict("REVISION_CONFLICT","保留策略已变化，请重新试算后再执行");
        var preview=preview(previewId,tenant,kind);
        if(preview==null) throw ApiError.conflict("RETENTION_PREVIEW_NOT_FOUND","试算记录不存在或不属于当前单位/类型");
        if(preview.policyRevision()==null || preview.policyRevision()!=policy.revision()
            || preview.keepDays()!=policy.keepDays())
            throw ApiError.conflict("RETENTION_PREVIEW_STALE","试算对应的策略版本已变化，请重新试算");
        var now=time.now();
        if(preview.expiresAt()==null || !preview.expiresAt().isAfter(now))
            throw ApiError.conflict("RETENTION_PREVIEW_EXPIRED","试算已过期，请重新试算");
        var candidates=candidates(tenant,kind,preview.cutoffAt());
        if(preview.eligible()==null || preview.candidateFingerprint()==null
            || preview.eligible()!=candidates.eligible()
            || !preview.candidateFingerprint().equals(candidates.fingerprint()))
            throw ApiError.conflict("RETENTION_CANDIDATES_CHANGED","待删除数据在试算后发生变化，本次未删除任何数据，请重新试算");
        long deleted=purge(kind,tenant,preview.cutoffAt());
        if(deleted!=candidates.eligible())
            throw ApiError.conflict("RETENTION_CANDIDATE_MISMATCH","实际删除条数与冻结候选不一致，事务已回滚，未产生部分清理");
        var id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO retention_runs(id,tenant_id,kind,keep_days,cutoff_at,dry_run,scanned,deleted,actor_id,created_at,"
                +"eligible,candidate_fingerprint,policy_revision,expires_at,source_preview_id,reason,request_id) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,?)",
            id,tenant,kind,policy.keepDays(),preview.cutoffAt(),false,candidates.scanned(),deleted,identity.user().id(),now,
            candidates.eligible(),candidates.fingerprint(),policy.revision(),previewId,basis,requestId);
        audit.recordReason(identity.user().id(),tenant,"RETENTION_PURGED",kind,"SUCCESS",requestId,basis);
        var result=run(id);
        idem.save(identity.user().id(),"retention-execute",key,hash,result);
        return result;
    }

    @Transactional(readOnly=true)
    public Page<Run> runs(String actorId,String tenant,ScopeFilter.Selection selection,String kind,int page,Integer requestedSize) {
        if(kind!=null && !kind.isBlank()) kind(kind); else kind=null;
        var paging=Pagination.of(page,requestedSize,20);
        var clause=scope.units(actorId,"t","id",tenant,selection);
        var where=" FROM retention_runs p JOIN tenants t ON t.id=p.tenant_id WHERE 1=1"+clause.and()+(kind==null?"":" AND p.kind=?");
        var args=new ArrayList<Object>(clause.bindings()); if(kind!=null) args.add(kind);
        long total=count("SELECT COUNT(*)"+where,args);
        var pageArgs=new ArrayList<Object>(args); pageArgs.add(paging.size()); pageArgs.add(paging.offset());
        var items=jdbc.query("SELECT p.id,p.tenant_id,t.name,p.kind,p.keep_days,p.cutoff_at,p.dry_run,p.scanned,p.eligible,p.deleted,"
                +"p.candidate_fingerprint,p.policy_revision,p.expires_at,p.source_preview_id,p.reason,p.created_at"+where
                +" ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",
            (rs,n)->mapRun(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),
                utc(rs.getObject(6,OffsetDateTime.class)),rs.getBoolean(7),rs.getLong(8),rs.getObject(9,Long.class),
                rs.getObject(10,Long.class),rs.getString(11),rs.getObject(12,Long.class),
                utc(rs.getObject(13,OffsetDateTime.class)),rs.getString(14),rs.getString(15),utc(rs.getObject(16,OffsetDateTime.class))),
            pageArgs.toArray());
        return new Page<>(items,total,paging.page(),paging.size());
    }

    private Run run(String id) {
        return jdbc.queryForObject("SELECT p.id,p.tenant_id,t.name,p.kind,p.keep_days,p.cutoff_at,p.dry_run,p.scanned,p.eligible,p.deleted,"
                +"p.candidate_fingerprint,p.policy_revision,p.expires_at,p.source_preview_id,p.reason,p.created_at "
                +"FROM retention_runs p JOIN tenants t ON t.id=p.tenant_id WHERE p.id=?",
            (rs,n)->mapRun(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),
                utc(rs.getObject(6,OffsetDateTime.class)),rs.getBoolean(7),rs.getLong(8),rs.getObject(9,Long.class),
                rs.getObject(10,Long.class),rs.getString(11),rs.getObject(12,Long.class),
                utc(rs.getObject(13,OffsetDateTime.class)),rs.getString(14),rs.getString(15),utc(rs.getObject(16,OffsetDateTime.class))),id);
    }

    private static Run mapRun(String id,String tenantId,String tenantName,String kind,int keepDays,OffsetDateTime cutoff,
                              boolean dryRun,long scanned,Long eligible,Long storedDeleted,String fingerprint,Long policyRevision,
                              OffsetDateTime expiresAt,String sourcePreviewId,String reason,OffsetDateTime createdAt) {
        boolean legacy=dryRun && eligible==null;
        Long shownEligible=eligible!=null ? eligible : (legacy ? storedDeleted : null);
        Long shownDeleted=legacy ? null : storedDeleted;
        return new Run(id,tenantId,tenantName,kind,keepDays,cutoff,dryRun,scanned,shownEligible,shownDeleted,legacy,
            fingerprint==null||fingerprint.isBlank()?null:fingerprint,policyRevision,expiresAt,sourcePreviewId,reason,createdAt);
    }

    private Policy policy(String tenant,String kind) {
        var rows=jdbc.query("SELECT p.keep_days,p.enabled,p.updated_at,p.revision FROM retention_policies p WHERE p.tenant_id=? AND p.kind=?",
            (rs,n)->new PolicyRow(rs.getInt(1),rs.getBoolean(2),utc(rs.getObject(3,OffsetDateTime.class)),rs.getLong(4)),tenant,kind);
        if(rows.isEmpty()) throw ApiError.missing();
        var row=rows.getFirst();
        return new Policy(tenant,tenantName(tenant),kind,row.keepDays(),row.enabled(),row.updatedAt(),row.revision(),true);
    }

    private Map<String,PolicyRow> policyRows(String tenant,boolean lock) {
        var result=new LinkedHashMap<String,PolicyRow>();
        jdbc.query("SELECT kind,keep_days,enabled,updated_at,revision FROM retention_policies WHERE tenant_id=?"+(lock?" FOR UPDATE":""),
            (RowCallbackHandler)rs->result.put(rs.getString(1),new PolicyRow(rs.getInt(2),rs.getBoolean(3),
                utc(rs.getObject(4,OffsetDateTime.class)),rs.getLong(5))),tenant);
        return result;
    }

    private PolicyRow requiredPolicy(String tenant,String kind,boolean lock) {
        var row=policyRows(tenant,lock).get(kind);
        if(row==null) throw ApiError.conflict("RETENTION_NOT_CONFIGURED","该类型尚未配置保留策略，未清理任何数据");
        return row;
    }

    private static void requireEnabled(PolicyRow policy) {
        if(!policy.enabled()) throw ApiError.conflict("RETENTION_DISABLED","该类型保留策略未启用，未清理任何数据");
    }

    private Run preview(String id,String tenant,String kind) {
        var rows=jdbc.query("SELECT id,tenant_id,kind,keep_days,cutoff_at,dry_run,scanned,eligible,candidate_fingerprint,policy_revision,expires_at,created_at "
                +"FROM retention_runs WHERE id=? AND tenant_id=? AND kind=? AND dry_run=TRUE",
            (rs,n)->new Run(rs.getString(1),rs.getString(2),null,rs.getString(3),rs.getInt(4),
                utc(rs.getObject(5,OffsetDateTime.class)),true,rs.getLong(7),rs.getObject(8,Long.class),0L,false,
                rs.getString(9),rs.getObject(10,Long.class),utc(rs.getObject(11,OffsetDateTime.class)),null,null,
                utc(rs.getObject(12,OffsetDateTime.class))),id,tenant,kind);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private long count(String sql,List<Object> bindings) {
        Long value=jdbc.queryForObject(sql,Long.class,bindings.toArray());
        return value==null?0:value;
    }

    private String tenantName(String tenant) {
        var rows=jdbc.queryForList("SELECT name FROM tenants WHERE id=?",String.class,tenant);
        if(rows.isEmpty()) throw new ApiError(403,"UNIT_DISABLED","单位不可用");
        return rows.getFirst();
    }

    private static OffsetDateTime cutoff(OffsetDateTime now,int keepDays) {
        return now.minusDays(keepDays).truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value==null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String requiredReason(String reason) {
        if(reason==null || reason.isBlank() || reason.length()>1000) throw ApiError.bad("必须填写变更原因（最多1000字）");
        return reason.trim();
    }

    /**
     * Candidate identity must use exactly the same eligibility predicates as the domain purge.
     * The fingerprint covers tenant, kind, frozen cutoff and each eligible ID in deterministic order.
     */
    private Candidates candidates(String tenant,String kind,OffsetDateTime cutoff) {
        String idSql;
        long scanned;
        switch(kind) {
            case "RAW_MESSAGES" -> {
                scanned=rawMessages.countBefore(tenant,cutoff);
                idSql="SELECT m.id FROM raw_messages m WHERE m.tenant_id=? AND m.received_at<? "
                    +"AND NOT EXISTS(SELECT 1 FROM processing_tasks t WHERE t.raw_message_id=m.id) "
                    +"AND NOT EXISTS(SELECT 1 FROM reparse_jobs j WHERE j.raw_message_id=m.id) ORDER BY m.id";
            }
            case "DEVICE_COMMANDS" -> {
                scanned=commands.countBefore(tenant,cutoff);
                idSql="SELECT id FROM device_commands WHERE tenant_id=? AND created_at<? AND status IN ('ACKED','REJECTED','TIMEOUT','UNCERTAIN') ORDER BY id";
            }
            default -> {
                scanned=auditRetention.countBefore(tenant,cutoff);
                idSql="SELECT id FROM audit_log WHERE tenant_id=? AND occurred_at<? ORDER BY id";
            }
        }
        var digest=sha256();
        digest.update((kind+"\n"+tenant+"\n"+cutoff.toInstant()).getBytes(StandardCharsets.UTF_8));
        long[] eligible={0};
        jdbc.query(idSql,(RowCallbackHandler)rs->{
            digest.update(rs.getString(1).getBytes(StandardCharsets.UTF_8)); digest.update((byte)'\n'); eligible[0]++;
        },tenant,cutoff);
        return new Candidates(scanned,eligible[0],HexFormat.of().formatHex(digest.digest()));
    }

    private long purge(String kind,String tenant,OffsetDateTime cutoff) {
        return switch(kind) {
            case "RAW_MESSAGES" -> rawMessages.purgeBefore(tenant,cutoff);
            case "DEVICE_COMMANDS" -> commands.purgeBefore(tenant,cutoff);
            default -> auditRetention.purgeBefore(tenant,cutoff);
        };
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable",failure); }
    }
}
