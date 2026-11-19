package com.fireplatform.governance;

import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Pagination;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only platform operations evidence. It reports deployment identity from process environment,
 * the real Flyway history table, explicit configured/unknown health, and the validated evidence
 * manifest. It never executes backups, shell commands, SQL dumps, restarts or deletes.
 */
@Service
public class PlatformOperationsService {
    public record Deployment(String releaseId,String artifactSha256,String sourceCommit,OffsetDateTime builtAt,
                             String identityStatus,String storage,List<String> profiles,List<String> roles,
                             OffsetDateTime startedAt,OffsetDateTime now,long uptimeSeconds) { }
    public record SchemaSummary(boolean available,String currentVersion,long applied,long failed,
                                String validation,String pendingCheck) { }
    public record HealthCheck(String name,String state,String detail) { }
    public record Overview(OffsetDateTime generatedAt,Deployment deployment,SchemaSummary schema,
                           List<HealthCheck> health,OpsEvidenceReader.Evidence evidence) { }
    public record Migration(int rank,String version,String description,String type,String script,Integer checksum,
                            String installedBy,OffsetDateTime installedOn,Integer executionTime,boolean success) { }
    public record SchemaHistory(boolean available,String reason,List<Migration> items,long total,int page,int size) { }

    private final JdbcTemplate jdbc; private final RuntimeConfig config; private final Environment env;
    private final OpsEvidenceReader evidence; private final Clock clock;
    public PlatformOperationsService(JdbcTemplate jdbc,RuntimeConfig config,Environment env,OpsEvidenceReader evidence,Clock clock) {
        this.jdbc=jdbc; this.config=config; this.env=env; this.evidence=evidence; this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Overview overview() {
        var now=clock.instant().atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var deployment=deployment(now);
        var schema=schemaSummary();
        var stored=evidence.read();
        verifyEvidenceRelease(deployment,stored.manifest());
        verifyEvidenceSchema(schema,stored.manifest());
        var health=new ArrayList<HealthCheck>();
        health.add(databaseHealth());
        health.add(schemaHealth(schema));
        health.add(new HealthCheck("storage","CONFIGURED",
            config.storage()+(config.development()?"（development profile）":"（deployment-configured）")));
        health.add(new HealthCheck("roles","CONFIGURED","roles="+String.join(",",config.roles())+"；未执行外部探测"));
        health.add(new HealthCheck("generic_ingest",config.genericIngestEnabled()?"CONFIGURED":"DISABLED",
            config.genericIngestEnabled()?"由部署开关启用；未执行外部探测":"部署开关关闭"));
        health.add(new HealthCheck("reparse_worker",config.reparseWorkerEnabled()?"CONFIGURED":"DISABLED",
            config.reparseWorkerEnabled()?"worker角色已启用；未执行历史重解析":"未启用"));
        health.add(flag("hikvision_polling","HIKVISION_ENABLED","fire.hikvision.enabled"));
        health.add(flag("real_notifications","FIRE_NOTIFICATION_REAL_ENABLED","fire.notification.real-enabled"));
        health.add(new HealthCheck("release_identity",switch(deployment.identityStatus()) {
            case "PROVIDED" -> "READY"; case "INVALID" -> "DEGRADED"; default -> "UNKNOWN";
        },switch(deployment.identityStatus()) {
            case "PROVIDED" -> deployment.sourceCommit()==null
                ? "release/build identity provided; source commit not provided"
                : "release/build identity provided by deployment environment";
            case "INVALID" -> "deployment release environment contains an invalid value";
            case "INCOMPLETE" -> "deployment release environment is partially provided";
            default -> "deployment release environment is not provided";
        }));
        var manifest=stored.manifest();
        boolean evidenceVerified=manifest!=null && "VERIFIED".equals(manifest.backup().status())
            && "VERIFIED".equals(manifest.restore().status()) && "VERIFIED".equals(manifest.migration().status());
        health.add(new HealthCheck("operations_evidence",
            !stored.available()?"UNKNOWN":evidenceVerified?"READY":"DEGRADED",
            stored.available()?(evidenceVerified?"validated manifest "+stored.fileName()+" sha256="+stored.sha256()
                :"manifest已加载但至少一项验证为NOT_VERIFIED；不推断成功")
                :(!stored.configured()?"未配置 "+OpsEvidenceReader.DIRECTORY_ENV:"未提供 "+stored.reason()+"；没有推断备份成功")));
        return new Overview(now,deployment,schema,List.copyOf(health),stored);
    }

    @Transactional(readOnly=true)
    public SchemaHistory schemaHistory(int page,Integer requestedSize) {
        var paging=Pagination.of(page,requestedSize,20);
        if(!schemaAvailable()) return new SchemaHistory(false,"FLYWAY_HISTORY_NOT_FOUND",List.of(),0,paging.page(),paging.size());
        long total=count("SELECT COUNT(*) FROM flyway_schema_history");
        var items=jdbc.query("SELECT installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success "
                +"FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT ? OFFSET ?",
            (rs,n)->new Migration(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getObject(6,Integer.class),rs.getString(7),time(rs.getObject(8)),rs.getObject(9,Integer.class),rs.getBoolean(10)),
            paging.size(),paging.offset());
        return new SchemaHistory(true,null,items,total,paging.page(),paging.size());
    }

    private Deployment deployment(OffsetDateTime now) {
        String releaseId=textEnv("APP_RELEASE_ID",200);
        String sha=textEnv("APP_RELEASE_ARTIFACT_SHA256",64);
        String commit=textEnv("APP_RELEASE_SOURCE_COMMIT",64);
        String builtAtRaw=textEnv("APP_RELEASE_BUILT_AT",64);
        boolean invalid=false;
        if(sha!=null && !sha.matches("[0-9a-f]{64}")) { sha=null; invalid=true; }
        if(commit!=null && !commit.matches("[0-9a-f]{7,64}")) { commit=null; invalid=true; }
        if(releaseId!=null && !releaseId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) { releaseId=null; invalid=true; }
        OffsetDateTime builtAt=null;
        if(builtAtRaw!=null) { try { builtAt=OffsetDateTime.parse(builtAtRaw).withOffsetSameInstant(ZoneOffset.UTC); if(builtAt.isAfter(now.plusMinutes(5))) invalid=true; } catch(Exception failure) { invalid=true; } }
        String status;
        if(invalid) status="INVALID";
        else if(releaseId==null && sha==null && commit==null && builtAt==null) status="NOT_PROVIDED";
        else if(releaseId!=null && sha!=null && builtAt!=null) status="PROVIDED";
        else status="INCOMPLETE";
        var runtime=ManagementFactory.getRuntimeMXBean();
        var startedAt=Instant.ofEpochMilli(runtime.getStartTime()).atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        return new Deployment(releaseId,sha,commit,builtAt,status,config.storage(),
            List.of(env.getActiveProfiles()),List.copyOf(config.roles()),startedAt,now,
            Math.max(0,runtime.getUptime()/1000));
    }

    private String textEnv(String name,int max) {
        String value=env.getProperty(name);
        if(value==null || value.isBlank()) return null;
        value=value.trim();
        return value.length()>max ? null : value;
    }

    private void verifyEvidenceRelease(Deployment deployment,OpsEvidenceReader.Manifest manifest) {
        if(manifest==null) return;
        var release=manifest.release();
        if(deployment.releaseId()!=null && release.releaseId()!=null && !deployment.releaseId().equals(release.releaseId()))
            throw new ApiError(503,"OPS_EVIDENCE_STALE","运维证据清单不属于当前发布版本");
        if(deployment.artifactSha256()!=null && release.artifactSha256()!=null && !deployment.artifactSha256().equals(release.artifactSha256()))
            throw new ApiError(503,"OPS_EVIDENCE_STALE","运维证据清单的构建哈希与当前部署不一致");
        if(deployment.sourceCommit()!=null && release.sourceCommit()!=null && !deployment.sourceCommit().equals(release.sourceCommit()))
            throw new ApiError(503,"OPS_EVIDENCE_STALE","运维证据清单的源码版本与当前部署不一致");
        if(deployment.builtAt()!=null && release.builtAt()!=null && !deployment.builtAt().toInstant().equals(release.builtAt().toInstant()))
            throw new ApiError(503,"OPS_EVIDENCE_STALE","运维证据清单的构建时间与当前部署不一致");
    }

    private void verifyEvidenceSchema(SchemaSummary schema,OpsEvidenceReader.Manifest manifest) {
        if(manifest==null || !schema.available() || schema.currentVersion()==null) return;
        if("VERIFIED".equals(manifest.migration().status())
            && !schema.currentVersion().equals(manifest.migration().targetSchemaVersion()))
            throw new ApiError(503,"OPS_EVIDENCE_STALE","运维证据清单的迁移目标版本与当前Flyway版本不一致");
        if("VERIFIED".equals(manifest.restore().status())
            && !schema.currentVersion().equals(manifest.restore().restoredSchemaVersion()))
            throw new ApiError(503,"OPS_EVIDENCE_STALE","运维证据清单的恢复schema版本与当前Flyway版本不一致");
    }

    private HealthCheck flag(String name,String envName,String propertyName) {
        try {
            boolean enabled=env.getProperty(envName,Boolean.class,env.getProperty(propertyName,Boolean.class,false));
            return new HealthCheck(name,enabled?"CONFIGURED":"DISABLED",enabled?"部署开关开启；未执行厂商/外部探测":"部署开关关闭");
        } catch(RuntimeException failure) {
            return new HealthCheck(name,"UNKNOWN","部署开关值无法解析；不推断状态");
        }
    }

    private HealthCheck databaseHealth() {
        try {
            Integer value=jdbc.queryForObject("SELECT 1",Integer.class);
            return new HealthCheck("database",value!=null&&value==1?"READY":"DEGRADED",
                value!=null&&value==1?"JDBC query succeeded":"JDBC returned an unexpected value");
        } catch(Exception failure) {
            return new HealthCheck("database","DEGRADED","JDBC query failed；未推断成功");
        }
    }

    private HealthCheck schemaHealth(SchemaSummary schema) {
        if(!schema.available()) return new HealthCheck("schema","UNKNOWN","Flyway历史表不可读；不推断迁移成功");
        if(schema.failed()>0) return new HealthCheck("schema","DEGRADED",schema.failed()+" 条Flyway迁移失败");
        return new HealthCheck("schema","READY","当前版本 "+(schema.currentVersion()==null?"无版本行":schema.currentVersion())
            +"；已应用 "+schema.applied()+" 条；classpath待执行项未在读取接口中推断");
    }

    private SchemaSummary schemaSummary() {
        if(!schemaAvailable()) return new SchemaSummary(false,null,0,0,"UNKNOWN","NOT_CHECKED");
        long applied=count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE");
        long failed=count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=FALSE");
        var versions=jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",String.class);
        String current=versions.isEmpty()?null:versions.getFirst();
        return new SchemaSummary(true,current,applied,failed,failed>0?"FAILED":"APPLIED","NOT_CHECKED");
    }

    private boolean schemaAvailable() {
        try { jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history",Long.class); return true; }
        catch(BadSqlGrammarException missing) { return false; }
    }

    private long count(String sql) {
        Long value=jdbc.queryForObject(sql,Long.class);
        return value==null?0:value;
    }

    private static OffsetDateTime time(Object value) {
        if(value instanceof OffsetDateTime offset) return offset.withOffsetSameInstant(ZoneOffset.UTC);
        if(value instanceof Instant instant) return instant.atOffset(ZoneOffset.UTC);
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        if(value instanceof LocalDateTime local) return local.atOffset(ZoneOffset.UTC);
        return null;
    }
}
