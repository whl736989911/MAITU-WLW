package com.fireplatform.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads the one fixed operations-evidence manifest from the one configured directory.
 * The API never accepts a path/URL from a caller, never executes a shell or SQL dump, and never
 * derives backup/restore success from a filename. Missing evidence is an honest empty state;
 * malformed, oversized, symlinked or cross-section-inconsistent evidence fails closed.
 *
 * <p>Contract: {@code APP_OPS_EVIDENCE_DIR} is an absolute, real directory (not a symlink).
 * It contains the fixed {@code operations-evidence.json}, at most 256 KiB, UTF-8 JSON with the
 * strict schema below (camelCase Java names are bound to snake_case JSON by the application mapper).
 *
 * <pre>{@code
 * {"schema_version":1,"generated_at":"2026-11-12T10:00:00Z","producer":"release-evidence.sh",
 *  "environment":"PRODUCTION",
 *  "release":{"release_id":"20261112-rc1","artifact_sha256":"<64 lowercase hex>",
 *             "source_commit":null,"built_at":"2026-11-12T09:00:00Z"},
 *  "backup":{"status":"VERIFIED","completed_at":"2026-11-12T09:10:00Z","database_engine":"postgresql",
 *            "file_name":"fire-platform-20261112T091000Z.dump","file_sha256":"<64 lowercase hex>",
 *            "file_bytes":123,"encrypted":true,"offline":true},
 *  "restore":{"status":"VERIFIED","validated_at":"2026-11-12T09:30:00Z","isolated_target":true,
 *             "backup_sha256":"<same backup hash>","restored_schema_version":"81",
 *             "schema_version_matches":true},
 *  "migration":{"status":"VERIFIED","validated_at":"2026-11-12T09:35:00Z",
 *               "source_schema_version":"79","target_schema_version":"81",
 *               "isolated_copy":true,"checksum_validation":"PASSED"}}
 * }</pre>
 * A section that is honest but not verified uses {@code status="NOT_VERIFIED"} and a non-blank
 * {@code reason}; it must not carry invented success. Unknown fields, paths in the backup file name,
 * oversized files, symlinks, inconsistent hashes/versions or future timestamps fail the whole read.
 * {@code release.source_commit} is independently optional: an uncommitted workspace publishes null and
 * the UI reports it as not provided, never as the archive hash. {@code encrypted}/{@code offline} are
 * descriptive backup facts and are not required to be true.
 */
@Component
public class OpsEvidenceReader {
    public static final String DIRECTORY_ENV="APP_OPS_EVIDENCE_DIR";
    public static final String FILE_NAME="operations-evidence.json";
    private static final long MAX_BYTES=256*1024;
    private static final java.util.Set<String> ENVIRONMENTS=java.util.Set.of("PRODUCTION","STAGING","ISOLATED");
    private static final java.util.Set<String> STATUSES=java.util.Set.of("VERIFIED","NOT_VERIFIED");

    public record ReleaseEvidence(String releaseId,String artifactSha256,String sourceCommit,OffsetDateTime builtAt) { }
    public record BackupEvidence(String status,String reason,OffsetDateTime completedAt,String databaseEngine,
                                 String fileName,String fileSha256,Long fileBytes,Boolean encrypted,Boolean offline) { }
    public record RestoreEvidence(String status,String reason,OffsetDateTime validatedAt,Boolean isolatedTarget,
                                  String backupSha256,String restoredSchemaVersion,Boolean schemaVersionMatches) { }
    public record MigrationEvidence(String status,String reason,OffsetDateTime validatedAt,
                                    String sourceSchemaVersion,String targetSchemaVersion,Boolean isolatedCopy,String checksumValidation) { }
    public record Manifest(int schemaVersion,OffsetDateTime generatedAt,String producer,String environment,
                           ReleaseEvidence release,BackupEvidence backup,RestoreEvidence restore,MigrationEvidence migration) { }
    public record Evidence(boolean configured,boolean available,String reason,String fileName,String sha256,
                           Long bytes,OffsetDateTime modifiedAt,Manifest manifest) { }

    private final Environment env; private final ObjectMapper json;
    public OpsEvidenceReader(Environment env,ObjectMapper json) { this.env=env; this.json=json; }

    public Evidence read() {
        String configured=env.getProperty(DIRECTORY_ENV,env.getProperty("app.ops-evidence-dir"));
        if(configured==null || configured.isBlank())
            return new Evidence(false,false,"NOT_CONFIGURED",null,null,null,null,null);
        Path directory;
        try { directory=Path.of(configured.trim()); }
        catch(InvalidPathException failure) { throw new ApiError(503,"OPS_EVIDENCE_CONFIG_INVALID","运维证据目录配置无效"); }
        if(!directory.isAbsolute())
            throw new ApiError(503,"OPS_EVIDENCE_CONFIG_INVALID","运维证据目录必须为绝对路径");
        if(Files.isSymbolicLink(directory))
            throw new ApiError(503,"OPS_EVIDENCE_CONFIG_INVALID","运维证据目录不能是符号链接");
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))
            return new Evidence(true,false,"DIRECTORY_NOT_FOUND",null,null,null,null,null);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
            throw new ApiError(503,"OPS_EVIDENCE_CONFIG_INVALID","运维证据目录不是真实目录");
        Path file=directory.resolve(FILE_NAME);
        if(Files.isSymbolicLink(file))
            throw new ApiError(503,"OPS_EVIDENCE_INVALID","运维证据文件不能是符号链接");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))
            return new Evidence(true,false,"NOT_PROVIDED",null,null,null,null,null);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
            throw new ApiError(503,"OPS_EVIDENCE_INVALID","运维证据文件不是普通文件");
        byte[] bytes;
        try (InputStream input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            bytes=input.readNBytes((int)MAX_BYTES+1);
            if(bytes.length>MAX_BYTES) throw new ApiError(503,"OPS_EVIDENCE_INVALID","运维证据文件超过大小上限");
            if(input.read()!=-1) throw new ApiError(503,"OPS_EVIDENCE_INVALID","运维证据文件超过大小上限");
            if(bytes.length<2) throw new ApiError(503,"OPS_EVIDENCE_INVALID","运维证据文件为空");
        } catch(ApiError failure) { throw failure; }
        catch(IOException|RuntimeException failure) { throw new ApiError(503,"OPS_EVIDENCE_UNREADABLE","运维证据文件无法读取"); }
        OffsetDateTime modifiedAt;
        try { modifiedAt=OffsetDateTime.ofInstant(Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant(),ZoneOffset.UTC); }
        catch(IOException|RuntimeException failure) { throw new ApiError(503,"OPS_EVIDENCE_UNREADABLE","运维证据文件无法读取"); }
        Manifest manifest;
        try { manifest=json.readValue(bytes,Manifest.class); }
        catch(Exception failure) { throw new ApiError(503,"OPS_EVIDENCE_INVALID","运维证据清单格式无效"); }
        validate(manifest);
        return new Evidence(true,true,null,FILE_NAME,HexFormat.of().formatHex(sha256(bytes)),
            (long)bytes.length,modifiedAt,manifest);
    }

    private static void validate(Manifest manifest) {
        if(manifest==null || manifest.schemaVersion()!=1) invalid("schema_version必须为1");
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        timestamp(manifest.generatedAt(),"generated_at",now);
        text(manifest.producer(),"producer",100);
        if(manifest.environment()==null || !ENVIRONMENTS.contains(manifest.environment())) invalid("environment未确认");
        var release=manifest.release();
        if(release==null) invalid("release缺失");
        text(release.releaseId(),"release.release_id",200);
        if(release.releaseId()!=null && !release.releaseId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) invalid("release.release_id格式无效");
        digest(release.artifactSha256(),"release.artifact_sha256");
        if(release.sourceCommit()!=null && !release.sourceCommit().matches("[0-9a-f]{7,64}")) invalid("release.source_commit格式无效");
        timestamp(release.builtAt(),"release.built_at",now);

        var backup=manifest.backup();
        if(backup==null) invalid("backup缺失");
        status(backup.status(),"backup.status",backup.reason());
        if("VERIFIED".equals(backup.status())) {
            timestamp(backup.completedAt(),"backup.completed_at",now);
            if(!java.util.Set.of("postgresql","h2").contains(backup.databaseEngine())) invalid("backup.database_engine未确认");
            safeFileName(backup.fileName(),"backup.file_name");
            digest(backup.fileSha256(),"backup.file_sha256");
            if(backup.fileBytes()==null || backup.fileBytes()<=0) invalid("backup.file_bytes必须为正数");
        }

        var restore=manifest.restore();
        if(restore==null) invalid("restore缺失");
        status(restore.status(),"restore.status",restore.reason());
        if("VERIFIED".equals(restore.status())) {
            timestamp(restore.validatedAt(),"restore.validated_at",now);
            if(!Boolean.TRUE.equals(restore.isolatedTarget())) invalid("restore.isolated_target必须为true");
            digest(restore.backupSha256(),"restore.backup_sha256");
            version(restore.restoredSchemaVersion(),"restore.restored_schema_version");
            if(!Boolean.TRUE.equals(restore.schemaVersionMatches())) invalid("restore.schema_version_matches必须为true");
            if(!"VERIFIED".equals(backup.status())) invalid("restore验证要求backup同时为VERIFIED");
            if(!restore.backupSha256().equals(backup.fileSha256()))
                invalid("restore.backup_sha256必须与已验证备份一致");
        }

        var migration=manifest.migration();
        if(migration==null) invalid("migration缺失");
        status(migration.status(),"migration.status",migration.reason());
        if("VERIFIED".equals(migration.status())) {
            timestamp(migration.validatedAt(),"migration.validated_at",now);
            version(migration.sourceSchemaVersion(),"migration.source_schema_version");
            version(migration.targetSchemaVersion(),"migration.target_schema_version");
            if(!Boolean.TRUE.equals(migration.isolatedCopy())) invalid("migration.isolated_copy必须为true");
            if(!"PASSED".equals(migration.checksumValidation())) invalid("migration.checksum_validation必须为PASSED");
            if("VERIFIED".equals(restore.status()) && !migration.targetSchemaVersion().equals(restore.restoredSchemaVersion()))
                invalid("migration.target_schema_version必须与已验证恢复的schema版本一致");
        }
    }

    private static void status(String status,String field,String reason) {
        if(status==null || !STATUSES.contains(status)) invalid(field+"未确认");
        if("NOT_VERIFIED".equals(status)) text(reason,field+"对应的reason",500);
    }
    private static void text(String value,String field,int max) {
        if(value==null || value.isBlank() || value.length()>max) invalid(field+"无效");
    }
    private static void timestamp(OffsetDateTime value,String field,OffsetDateTime now) {
        if(value==null) invalid(field+"缺失");
        if(!value.getOffset().equals(ZoneOffset.UTC)) invalid(field+"必须为UTC时间");
        if(value.isAfter(now.plusMinutes(5))) invalid(field+"不能晚于当前时间");
    }
    private static void digest(String value,String field) {
        if(value==null || !value.matches("[0-9a-f]{64}")) invalid(field+"必须为64位小写SHA-256");
    }
    private static void version(String value,String field) {
        if(value==null || !value.matches("[0-9][0-9A-Za-z._-]{0,63}")) invalid(field+"格式无效");
    }
    private static void safeFileName(String value,String field) {
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || value.contains("..")) invalid(field+"必须是无路径的文件名");
    }
    private static void invalid(String detail) { throw new ApiError(503,"OPS_EVIDENCE_INVALID","运维证据清单无效："+detail); }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable",failure); }
    }
}
