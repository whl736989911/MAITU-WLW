package com.fireplatform.bulkio;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.DeviceScopes;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.shared.ApiError;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.DataTransferPayloadCipher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
public class DataTransferService {
    public record Row(int row_no,String operation,String status,List<String> errors) { }
    public record Preview(String plan_id,String kind,String format,String tenant_id,String source_sha256,int row_count,int valid_count,int error_count,List<Row> rows) { }
    public record Job(String id,String kind,String tenant_id,String plan_id,String source_sha256,String status,int row_count,int success_count,int error_count,OffsetDateTime created_at,OffsetDateTime updated_at) { }
    public record JobRow(int row_no,String status,String result_id,List<String> errors) { }
    public record Export(String id,String kind,String tenant_id,String format,String status,String content_sha256,int row_count,OffsetDateTime created_at,OffsetDateTime expires_at) { }
    public record Artifact(String filename,String media_type,String content_base64,String content_sha256) { }
    public record ExportList(String id,String kind,String tenant_id,String format,String status,String content_sha256,int row_count,OffsetDateTime created_at,OffsetDateTime expires_at) { }
    public record ExportPage(List<ExportList> items,long total,int page,int size) { }
    public record JobPage(List<Job> items,long total,int page,int size) { }
    public record ErrorRow(int row_no,List<String> errors) { }
    public record Template(String kind,String filename,String media_type,String content_base64) { }
    private record ParsedRow(int rowNo,Map<String,String> cells) { }
    private record PlanRow(int rowNo,Map<String,String> cells,List<String> errors) { }
    private record ShortCredential(AuthService.Identity actor,Instant expiresAt) { }

    private static final int MAX_BYTES=2_000_000, MAX_ROWS=10_000, MAX_COLUMNS=64;
    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final DatabaseTime time; private final DataTransferPayloadCipher payloadCipher; private final ManagementGuard guard; private final AuditService audit; private final DeviceScopes scopes;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"data-transfer-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String,ShortCredential> credentials=new ConcurrentHashMap<>();
    private final TransactionTemplate rowTransaction;
    private final Map<String,DataTransferDomain> domains;

    public DataTransferService(JdbcTemplate jdbc,ObjectMapper mapper,DatabaseTime time,PlatformTransactionManager transactionManager,DeviceScopes scopes,DataTransferPayloadCipher payloadCipher,ManagementGuard guard,AuditService audit,List<DataTransferDomain> domainBeans){this.jdbc=jdbc;this.mapper=mapper;this.time=time;this.scopes=scopes;this.payloadCipher=payloadCipher;this.guard=guard;this.audit=audit;this.rowTransaction=new TransactionTemplate(transactionManager);var all=new LinkedHashMap<String,DataTransferDomain>();for(var domain:domainBeans)for(var kind:domain.kinds())if(all.putIfAbsent(kind.kind(),domain)!=null)throw new IllegalStateException("Duplicate data-transfer kind: "+kind.kind());this.domains=Map.copyOf(all);}

    @EventListener(ApplicationReadyEvent.class) public void recoverInterrupted(){var now=time.now();jdbc.update("UPDATE data_transfer_jobs SET status='RECONFIRM_REQUIRED',updated_at=? WHERE status IN ('QUEUED','RUNNING')",now);jdbc.update("UPDATE data_transfer_plans SET status='PREVIEWED' WHERE status='CONFIRMED'");}
    @Scheduled(cron="0 0 * * * *") public void expireSensitiveData(){var now=time.now();jdbc.update("UPDATE data_transfer_plans SET payload='',payload_nonce='',status='EXPIRED' WHERE expires_at<=? AND status<>'EXPIRED'",now);jdbc.update("UPDATE data_transfer_exports SET content='',content_nonce='' WHERE expires_at<=? AND (content<>'' OR content_nonce<>'')",now);}

    public Template template(String kind,String format){List<String> columns=columns(kind);if(!Set.of("CSV","XLSX").contains(format))throw ApiError.bad("文件格式无效");String csv=csv(columns,List.of());byte[] bytes="XLSX".equals(format)?xlsx(csv):csv.getBytes(StandardCharsets.UTF_8);String extension="XLSX".equals(format)?".xlsx":".csv";return new Template(kind,kind.toLowerCase(Locale.ROOT)+"-template"+extension,"XLSX".equals(format)?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv;charset=utf-8",Base64.getEncoder().encodeToString(bytes));}

    @Transactional public Preview preview(AuthService.Identity actor,String tenant,String kind,String format,String encoded){
        DataTransferDomain.Kind descriptor=descriptor(kind);if(!descriptor.importable()||!descriptor.audiences().contains(actor.audience()))throw ApiError.forbidden("该类型不允许当前受众导入");
        List<String> header=columns(kind);if(!Set.of("CSV","XLSX").contains(format))throw ApiError.bad("文件格式无效");byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw ApiError.bad("文件编码无效");}if(bytes.length==0||bytes.length>MAX_BYTES)throw ApiError.bad("文件大小超出限制");
        String digest=sha256(bytes);List<ParsedRow> parsed="XLSX".equals(format)?readXlsx(bytes,header):readCsv(bytes,header);if(parsed.size()>MAX_ROWS)throw ApiError.bad("导入行数超过上限");
        List<PlanRow> planRows=new ArrayList<>(parsed.size());List<Row> results=new ArrayList<>(parsed.size());int valid=0;
        for(ParsedRow row:parsed){
            var cells=row.cells();List<String> errors=validate(kind,cells,actor,tenant);
            if(errors.isEmpty())valid++;
            results.add(new Row(row.rowNo(),operation(kind,cells),errors.isEmpty()?"VALID":"INVALID",errors));
            planRows.add(new PlanRow(row.rowNo(),cells,errors));
        }
        String id=UUID.randomUUID().toString();OffsetDateTime now=time.now();String payload;try{payload=mapper.writeValueAsString(planRows);}catch(Exception e){throw new IllegalStateException("导入计划序列化失败",e);}var encrypted=payloadCipher.encrypt(tenant,payload);
        jdbc.update("INSERT INTO data_transfer_plans(id,tenant_id,actor_id,kind,format,source_sha256,payload,payload_nonce,row_count,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,?,?,?, 'PREVIEWED')",id,tenant,actor.user().id(),kind,format,digest,encrypted.ciphertext(),encrypted.nonce(),parsed.size(),now,now.plusHours(2));
        return new Preview(id,kind,format,tenant,digest,parsed.size(),valid,parsed.size()-valid,List.copyOf(results));
    }

    @Transactional public Job confirm(AuthService.Identity actor,String tenant,String planId,String expectedSha,String reason,String requestKey){return confirmPlan(actor,tenant,planId,expectedSha,reason,requestKey,false);}
    private Job confirmPlan(AuthService.Identity actor,String tenant,String planId,String expectedSha,String reason,String requestKey,boolean reconfirm){
        validateReason(reason);validateRequestKey(requestKey);String action=reconfirm?"RECONFIRM":"CONFIRM";
        guard.begin(actor,"imports:write",tenant,actor.audience());
        String fingerprint=controlFingerprint(action,planId+"|"+Objects.toString(expectedSha,"")+"|"+Objects.toString(tenant,""),reason);Job replay=controlReplay(actor,action,requestKey,fingerprint);if(replay!=null)return replay;
        var plans=jdbc.query("SELECT kind,source_sha256,payload,payload_nonce,row_count,expires_at,status FROM data_transfer_plans WHERE id=? AND tenant_id IS NOT DISTINCT FROM ? AND actor_id=? FOR UPDATE",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getObject(6,OffsetDateTime.class),rs.getString(7)},planId,tenant,actor.user().id());
        if(plans.isEmpty())throw ApiError.missing();Object[] p=plans.getFirst();String kind=(String)p[0],sha=(String)p[1],encrypted=(String)p[2],nonce=(String)p[3];int count=(int)p[4];OffsetDateTime expiry=(OffsetDateTime)p[5];String planStatus=(String)p[6];
        replay=controlReplay(actor,action,requestKey,fingerprint);if(replay!=null)return replay;
        DataTransferDomain.Kind descriptor=descriptor(kind);if(!descriptor.importable()||!descriptor.audiences().contains(actor.audience()))throw ApiError.forbidden("该类型不允许当前受众导入");guard.requireCurrent(actor,descriptor.writePermission(),tenant,actor.audience());
        if(!Set.of("PREVIEWED","CONFIRMED","CONSUMED").contains(planStatus))throw ApiError.conflict("PLAN_STATE","导入计划状态无效");
        if(!MessageDigest.isEqual(sha.getBytes(StandardCharsets.US_ASCII),Objects.toString(expectedSha,"").getBytes(StandardCharsets.US_ASCII)))throw ApiError.conflict("PREVIEW_CHANGED","预览内容已变化，请重新预览");if(expiry.isBefore(time.now()))throw ApiError.conflict("PLAN_EXPIRED","预览已过期，请重新上传");
        String jobId=jdbc.query("SELECT id FROM data_transfer_jobs WHERE plan_id=? FOR UPDATE",(rs,n)->rs.getString(1),planId).stream().findFirst().orElse(null);OffsetDateTime now=time.now();
        if(jobId!=null){Job current=job(tenant,jobId,actor);if(!reconfirm){saveControl(actor,action,requestKey,jobId,fingerprint);return current;}if(!Set.of("RECONFIRM_REQUIRED","COMPLETED_WITH_ERRORS").contains(current.status()))throw ApiError.conflict("JOB_STATE","导入任务当前不能重新确认");}
        else if(reconfirm)throw ApiError.conflict("JOB_STATE","任务不存在，不能重新确认");
        String payload=payloadCipher.decrypt(tenant,encrypted,nonce);List<PlanRow> rows;try{rows=mapper.readValue(payload,new TypeReference<>(){});}catch(Exception e){throw ApiError.bad("导入计划损坏");}for(PlanRow row:rows)if(row.errors().isEmpty()&&!validate(kind,row.cells(),actor,tenant).isEmpty())throw ApiError.conflict("PLAN_INVALID","导入计划校验已变化");
        if(rows.stream().noneMatch(row->row.errors().isEmpty()))throw ApiError.bad("没有可提交的有效数据行");
        if(jobId==null){jobId=UUID.randomUUID().toString();jdbc.update("INSERT INTO data_transfer_jobs(id,plan_id,tenant_id,actor_id,kind,status,row_count,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',?,?,?)",jobId,planId,tenant,actor.user().id(),kind,count,now,now);for(PlanRow row:rows)jdbc.update("INSERT INTO data_transfer_job_rows(job_id,row_no,status,errors) VALUES(?,?,?,?)",jobId,row.rowNo(),row.errors().isEmpty()?"PENDING":"INVALID",json(row.errors()));}
        else {jdbc.update("UPDATE data_transfer_job_rows SET status='PENDING',errors='[]' WHERE job_id=? AND status='FAILED'",jobId);jdbc.update("UPDATE data_transfer_jobs SET status='QUEUED',updated_at=? WHERE id=?",now,jobId);}
        saveControl(actor,action,requestKey,jobId,fingerprint);jdbc.update("UPDATE data_transfer_plans SET status='CONFIRMED' WHERE id=?",planId);credentials.put(jobId,new ShortCredential(actor,Instant.now().plusSeconds(900)));String finalJobId=jobId;String finalKind=kind;String finalPayload=payload;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){try{worker.execute(()->process(finalJobId,tenant,finalKind,finalPayload,reason));}catch(RejectedExecutionException e){credentials.remove(finalJobId);jdbc.update("UPDATE data_transfer_jobs SET status='RECONFIRM_REQUIRED',updated_at=? WHERE id=?",time.now(),finalJobId);}}});
        return job(tenant,jobId,actor);
    }
    private static void validateReason(String reason){if(reason==null||reason.isBlank()||reason.length()>1000)throw ApiError.bad("必须填写导入原因");}
    private static void validateRequestKey(String key){if(key==null||key.isBlank()||key.length()>128)throw ApiError.bad("请求幂等键无效");}
    private String controlFingerprint(String action,String reference,String reason){return sha256(json(List.of(action,reference,reason.trim())).getBytes(StandardCharsets.UTF_8));}
    private Job controlReplay(AuthService.Identity actor,String action,String key,String fingerprint){var rows=jdbc.query("SELECT job_id,request_fingerprint FROM data_transfer_control_requests WHERE actor_id=? AND operation=? AND request_key=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},actor.user().id(),action,key);if(rows.isEmpty())return null;String[] row=rows.getFirst();if(!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),row[1].getBytes(StandardCharsets.US_ASCII)))throw ApiError.conflict("IDEMPOTENCY_KEY_REUSED","幂等键已用于不同任务操作");return job(null,row[0],actor);}
    private void saveControl(AuthService.Identity actor,String action,String key,String jobId,String fingerprint){jdbc.update("INSERT INTO data_transfer_control_requests(actor_id,operation,request_key,job_id,request_fingerprint,created_at) VALUES(?,?,?,?,?,?)",actor.user().id(),action,key,jobId,fingerprint,time.now());}

    private void process(String jobId,String tenant,String kind,String payload,String reason){
        ShortCredential credential=credentials.get(jobId);if(credential==null)return;AuthService.Identity actor=credential.actor();boolean recheckRequired=false;
        try{
            if(jdbc.update("UPDATE data_transfer_jobs SET status='RUNNING',updated_at=? WHERE id=? AND status='QUEUED'",time.now(),jobId)!=1)return;
            List<PlanRow> rows=mapper.readValue(payload,new TypeReference<>(){});
            for(PlanRow row:rows){
                if(!credential.expiresAt().isAfter(Instant.now())){recheckRequired=true;break;}
                try{
                    rowTransaction.executeWithoutResult(tx -> {
                        String jobState=jdbc.queryForObject("SELECT status FROM data_transfer_jobs WHERE id=? FOR UPDATE",String.class,jobId);if("CANCELLED".equals(jobState))return;
                        String status=jdbc.queryForObject("SELECT status FROM data_transfer_job_rows WHERE job_id=? AND row_no=? FOR UPDATE",String.class,jobId,row.rowNo());
                        if(Set.of("SUCCEEDED","INVALID").contains(status))return;
                        if(!Set.of("PENDING","FAILED").contains(status))return;
                        DataTransferDomain.Kind descriptor=descriptor(kind);
                        guard.requireCurrent(actor,"imports:write",tenant,actor.audience());guard.requireCurrent(actor,descriptor.writePermission(),tenant,actor.audience());
                        String result=domain(kind).apply(kind,row.cells(),actor,tenant,reason,jobId,row.rowNo());
                        jdbc.update("UPDATE data_transfer_job_rows SET status='SUCCEEDED',result_id=?,errors='[]' WHERE job_id=? AND row_no=?",result,jobId,row.rowNo());refreshCounts(jobId);
                    });
                }catch(ApiError denied){if(denied.status==401||denied.status==403){recheckRequired=true;break;}jdbc.update("UPDATE data_transfer_job_rows SET status='FAILED',errors=? WHERE job_id=? AND row_no=?",json(List.of(bounded(denied.getMessage()))),jobId,row.rowNo());refreshCounts(jobId);}
                catch(RuntimeException failure){jdbc.update("UPDATE data_transfer_job_rows SET status='FAILED',errors=? WHERE job_id=? AND row_no=?",json(List.of(bounded(failure.getMessage()))),jobId,row.rowNo());refreshCounts(jobId);}
                if("CANCELLED".equals(jdbc.queryForObject("SELECT status FROM data_transfer_jobs WHERE id=?",String.class,jobId)))break;
            }
            int pending=jdbc.queryForObject("SELECT COUNT(*) FROM data_transfer_job_rows WHERE job_id=? AND status='PENDING'",Integer.class,jobId);
            int failed=jdbc.queryForObject("SELECT COUNT(*) FROM data_transfer_job_rows WHERE job_id=? AND status IN ('FAILED','INVALID')",Integer.class,jobId);
            if(!"CANCELLED".equals(jdbc.queryForObject("SELECT status FROM data_transfer_jobs WHERE id=?",String.class,jobId))){String status=recheckRequired||pending>0?"RECONFIRM_REQUIRED":failed>0?"COMPLETED_WITH_ERRORS":"COMPLETED";jdbc.update("UPDATE data_transfer_jobs SET status=?,updated_at=? WHERE id=?",status,time.now(),jobId);if("COMPLETED".equals(status))jdbc.update("UPDATE data_transfer_plans SET status='CONSUMED',payload='',payload_nonce='' WHERE id=(SELECT plan_id FROM data_transfer_jobs WHERE id=?)",jobId);}
        }catch(Exception failure){jdbc.update("UPDATE data_transfer_jobs SET status='RECONFIRM_REQUIRED',updated_at=? WHERE id=?",time.now(),jobId);}
        finally{credentials.remove(jobId);}
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Export export(AuthService.Identity actor,String tenant,String kind,String format,Map<String,String> filters,List<String> selectedIds,String reason,String requestKey){
        DataTransferDomain.Kind descriptor=descriptor(kind);if(!descriptor.exportable()||!descriptor.audiences().contains(actor.audience()))throw ApiError.forbidden("该类型不允许当前受众导出");
        if(!Set.of("CSV","XLSX").contains(format))throw ApiError.bad("文件格式无效");
        if(reason==null||reason.isBlank()||reason.length()>1000)throw ApiError.bad("必须填写导出原因");
        guard.begin(actor,"exports:write",tenant,actor.audience());guard.requireCurrent(actor,descriptor.readPermission(),tenant,actor.audience());
        if(requestKey==null||requestKey.isBlank()||requestKey.length()>128)throw ApiError.bad("导出请求幂等键无效");
        String requestFingerprint=exportRequestFingerprint(tenant,kind,format,filters,selectedIds,reason);
        Export replay=existingExport(actor,requestKey,requestFingerprint);if(replay!=null)return replay;
        Export result=exportDomain(actor,tenant,kind,format,filters,selectedIds,requestKey,requestFingerprint);
        audit.recordReason(actor.user().id(),tenant,"DATA_EXPORT",kind,"SUCCESS","data-transfer",reason);return result;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Export exportCustomer(AuthService.Identity actor,String tenant,String kind,String format,String reason,Map<String,String> filters,List<String> selectedIds,String requestKey){
        DataTransferDomain.Kind descriptor=descriptor(kind);if(!descriptor.exportable()||!descriptor.audiences().contains("customer")||!actor.audience().equals("customer"))throw ApiError.forbidden("该类型不允许客户导出");
        if(reason==null||reason.isBlank()||reason.length()>1000)throw ApiError.bad("必须说明导出原因");
        if(!Set.of("CSV","XLSX").contains(format))throw ApiError.bad("文件格式无效");
        guard.begin(actor,"exports:write",tenant,"customer");guard.requireCurrent(actor,descriptor.readPermission(),tenant,"customer");if(requestKey==null||requestKey.isBlank()||requestKey.length()>128)throw ApiError.bad("导出请求幂等键无效");String requestFingerprint=exportRequestFingerprint(tenant,kind,format,filters,selectedIds,reason);Export replay=existingExport(actor,requestKey,requestFingerprint);if(replay!=null)return replay;Export result=exportDomain(actor,tenant,kind,format,filters,selectedIds,requestKey,requestFingerprint);audit.recordReason(actor.user().id(),tenant,"DATA_EXPORT",kind,"SUCCESS","data-transfer",reason);return result;
    }
    private String exportRequestFingerprint(String tenant,String kind,String format,Map<String,String> filters,List<String> selectedIds,String reason){return sha256(json(List.of(tenant==null?"":tenant,kind,format,filters==null?Map.of():new TreeMap<>(filters),selectedIds==null?List.of():selectedIds,selectedIds==null?"ALL":"SELECTED",reason.trim())).getBytes(StandardCharsets.UTF_8));}
    private Export existingExport(AuthService.Identity actor,String key,String fingerprint){var rows=jdbc.query("SELECT id,kind,tenant_id,format,content_sha256,row_count,created_at,expires_at,request_fingerprint FROM data_transfer_exports WHERE actor_id=? AND request_key=?",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getObject(7,OffsetDateTime.class),rs.getObject(8,OffsetDateTime.class),rs.getString(9)},actor.user().id(),key);if(rows.isEmpty())return null;Object[] row=rows.getFirst();if(!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),((String)row[8]).getBytes(StandardCharsets.US_ASCII)))throw ApiError.conflict("IDEMPOTENCY_KEY_REUSED","幂等键已用于不同导出请求");OffsetDateTime expiry=(OffsetDateTime)row[7];return new Export((String)row[0],(String)row[1],(String)row[2],(String)row[3],expiry.isBefore(time.now())?"EXPIRED":"COMPLETED",(String)row[4],(int)row[5],(OffsetDateTime)row[6],expiry);}
    private Export exportDomain(AuthService.Identity actor,String tenant,String kind,String format,Map<String,String> filters,List<String> selectedIds,String requestKey,String requestFingerprint){
        var data=domain(kind).export(kind,actor,tenant,filters,selectedIds);
        String content=csv(data.columns(),data.rows());byte[] bytes="XLSX".equals(format)?xlsx(content):content.getBytes(StandardCharsets.UTF_8);
        return saveExport(actor,tenant,kind,format,bytes,data.rows().size(),requestKey,requestFingerprint);
    }
    private String scopeFingerprint(AuthService.Identity actor,String tenant){var state=new StringBuilder(actor.audience()).append('|');var permissions=jdbc.queryForList("SELECT permission FROM effective_user_permissions WHERE user_id=? ORDER BY permission",String.class,actor.user().id());state.append(String.join(",",permissions)).append('|');List<String> grants=tenant==null?jdbc.queryForList("SELECT tenant_id FROM effective_user_tenant_grants WHERE user_id=? ORDER BY tenant_id",String.class,actor.user().id()):List.of(tenant);for(String grant:grants)state.append(grant).append(':').append(scopes.fingerprint(actor.user().id(),grant)).append(':').append(jdbc.queryForObject("SELECT enabled FROM tenants WHERE id=?",Boolean.class,grant)).append(';');return sha256(state.toString().getBytes(StandardCharsets.UTF_8));}
    private Export saveExport(AuthService.Identity actor,String tenant,String kind,String format,byte[] bytes,int count,String requestKey,String requestFingerprint){String digest=sha256(bytes),id=UUID.randomUUID().toString();OffsetDateTime now=time.now(),expiry=now.plusDays(1);String fingerprint=scopeFingerprint(actor,tenant);var encrypted=payloadCipher.encryptScope(exportContentScope(id,actor.user().id(),tenant),bytes);jdbc.update("INSERT INTO data_transfer_exports(id,tenant_id,actor_id,scope_fingerprint,kind,format,request_key,request_fingerprint,content_sha256,content,content_nonce,row_count,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,tenant,actor.user().id(),fingerprint,kind,format,requestKey,requestFingerprint,digest,encrypted.ciphertext(),encrypted.nonce(),count,now,expiry);return new Export(id,kind,tenant,format,"COMPLETED",digest,count,now,expiry);}
    private static String exportContentScope(String id,String actorId,String tenant){return "bulkio-export-v1\\0"+id+"\\0"+actorId+"\\0"+String.valueOf(tenant);}
    public Artifact download(AuthService.Identity actor,String tenant,String id){return jdbc.query("SELECT kind,format,content_sha256,content,content_nonce,expires_at,actor_id,scope_fingerprint,tenant_id FROM data_transfer_exports WHERE id=? AND actor_id=?",(rs,n)->{String kind=rs.getString(1),actualTenant=rs.getString(9);if(tenant!=null&&!Objects.equals(tenant,actualTenant))throw ApiError.missing();DataTransferDomain.Kind descriptor=descriptor(kind);if(!descriptor.exportable()||!descriptor.audiences().contains(actor.audience())||rs.getObject(6,OffsetDateTime.class).isBefore(time.now())||!Objects.equals(scopeFingerprint(actor,actualTenant),rs.getString(8)))throw ApiError.missing();guard.requireCurrent(actor,"exports:read",actualTenant,actor.audience());guard.requireCurrent(actor,descriptor.readPermission(),actualTenant,actor.audience());byte[] bytes=payloadCipher.decryptScopeBytes(exportContentScope(id,actor.user().id(),actualTenant),rs.getString(4),rs.getString(5));if(!MessageDigest.isEqual(sha256(bytes).getBytes(StandardCharsets.US_ASCII),rs.getString(3).getBytes(StandardCharsets.US_ASCII)))throw new ApiError(503,"DATA_TRANSFER_ENCRYPTION_UNAVAILABLE","导出文件完整性校验失败");String format=rs.getString(2);return new Artifact(kind.toLowerCase(Locale.ROOT)+"."+format.toLowerCase(Locale.ROOT),"XLSX".equals(format)?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv;charset=utf-8",Base64.getEncoder().encodeToString(bytes),rs.getString(3));},id,actor.user().id()).stream().findFirst().orElseThrow(ApiError::missing);}
    private static String csv(List<String> headers,List<List<String>> rows){StringBuilder out=new StringBuilder(String.join(",",headers.stream().map(DataTransferService::cell).toList())).append("\r\n");for(List<String> row:rows)out.append(String.join(",",row.stream().map(DataTransferService::cell).toList())).append("\r\n");return out.toString();}
    private static String cell(String value){String safe=value==null?"":value;if(!safe.isEmpty()&&"=+-@".indexOf(safe.charAt(0))>=0)safe="'"+safe;return "\""+safe.replace("\"","\"\"")+"\"";}
    private static byte[] xlsx(String csv){List<List<String>> data=parseCsv(csv);try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){Sheet sheet=workbook.createSheet("data");for(int r=0;r<data.size();r++){org.apache.poi.ss.usermodel.Row row=sheet.createRow(r);for(int c=0;c<data.get(r).size();c++)row.createCell(c,CellType.STRING).setCellValue(data.get(r).get(c));}workbook.write(out);return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}


    public Job job(String tenant,String id,AuthService.Identity actor){var result=jdbc.query("SELECT j.tenant_id,j.kind,j.id,j.status,j.row_count,j.success_count,j.error_count,j.created_at,j.plan_id,p.source_sha256,j.updated_at FROM data_transfer_jobs j JOIN data_transfer_plans p ON p.id=j.plan_id WHERE j.id=? AND j.actor_id=?",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getObject(8,OffsetDateTime.class),rs.getString(9),rs.getString(10),rs.getObject(11,OffsetDateTime.class)},id,actor.user().id());if(result.isEmpty())throw ApiError.missing();Object[] row=result.getFirst();String actualTenant=(String)row[0],kind=(String)row[1];if(tenant!=null&&!Objects.equals(tenant,actualTenant))throw ApiError.missing();DataTransferDomain.Kind descriptor=descriptor(kind);if(!descriptor.audiences().contains(actor.audience())||!actor.permissions().contains("imports:read")||!actor.permissions().contains(descriptor.writePermission()))throw ApiError.missing();if(descriptor.tenantRequired()&&(actualTenant==null||actualTenant.isBlank()))throw ApiError.missing();if(actualTenant!=null)guard.requireCurrent(actor,descriptor.writePermission(),actualTenant,actor.audience());return new Job((String)row[2],kind,actualTenant,(String)row[8],(String)row[9],(String)row[3],(int)row[4],(int)row[5],(int)row[6],(OffsetDateTime)row[7],(OffsetDateTime)row[10]);}
    public List<JobRow> rows(String tenant,String id,AuthService.Identity actor){job(tenant,id,actor);return jdbc.query("SELECT row_no,status,result_id,errors FROM data_transfer_job_rows WHERE job_id=? ORDER BY row_no",(rs,n)->new JobRow(rs.getInt(1),rs.getString(2),rs.getString(3),decodeErrors(rs.getString(4))),id);}
    public List<ErrorRow> errors(String tenant,String id,AuthService.Identity actor){job(tenant,id,actor);return jdbc.query("SELECT row_no,errors FROM data_transfer_job_rows WHERE job_id=? AND status IN ('FAILED','INVALID') ORDER BY row_no",(rs,n)->new ErrorRow(rs.getInt(1),decodeErrors(rs.getString(2))),id);}
    @Transactional public Job reconfirm(AuthService.Identity actor,String jobId,String reason,String requestKey){validateReason(reason);validateRequestKey(requestKey);Job current=job(null,jobId,actor);guard.begin(actor,"imports:write",current.tenant_id(),actor.audience());String fingerprint=controlFingerprint("RECONFIRM",current.plan_id()+"|"+current.source_sha256()+"|"+Objects.toString(current.tenant_id(),""),reason);Job replay=controlReplay(actor,"RECONFIRM",requestKey,fingerprint);if(replay!=null)return replay;return confirmPlan(actor,current.tenant_id(),current.plan_id(),current.source_sha256(),reason,requestKey,true);}
    @Transactional public Job cancel(AuthService.Identity actor,String jobId,String reason,String requestKey){validateReason(reason);validateRequestKey(requestKey);Job current=job(null,jobId,actor);guard.begin(actor,"imports:write",current.tenant_id(),actor.audience());String fingerprint=controlFingerprint("CANCEL",jobId,reason);Job replay=controlReplay(actor,"CANCEL",requestKey,fingerprint);if(replay!=null)return replay;var status=jdbc.query("SELECT status FROM data_transfer_jobs WHERE id=? AND actor_id=? FOR UPDATE",(rs,n)->rs.getString(1),jobId,actor.user().id());if(status.isEmpty())throw ApiError.missing();replay=controlReplay(actor,"CANCEL",requestKey,fingerprint);if(replay!=null)return replay;if(Set.of("COMPLETED","COMPLETED_WITH_ERRORS","CANCELLED").contains(status.getFirst()))throw ApiError.conflict("JOB_STATE","该任务不能取消");jdbc.update("UPDATE data_transfer_jobs SET status='CANCELLED',updated_at=? WHERE id=?",time.now(),jobId);jdbc.update("UPDATE data_transfer_plans SET payload='',payload_nonce='',status='EXPIRED' WHERE id=? AND status<>'CONSUMED'",current.plan_id());credentials.remove(jobId);saveControl(actor,"CANCEL",requestKey,jobId,fingerprint);audit.recordReason(actor.user().id(),current.tenant_id(),"DATA_TRANSFER_CANCEL",jobId,"SUCCESS","data-transfer",reason);return job(null,jobId,actor);}
    public JobPage jobs(AuthService.Identity actor,int page,int size){int safePage=Math.max(1,page),safeSize=Math.max(1,Math.min(size,100));var ids=jdbc.query("SELECT id FROM data_transfer_jobs WHERE actor_id=? ORDER BY created_at DESC,id DESC",(rs,n)->rs.getString(1),actor.user().id());var visible=new ArrayList<Job>();for(String id:ids)try{visible.add(job(null,id,actor));}catch(ApiError ignored){}int from=Math.min((safePage-1)*safeSize,visible.size()),to=Math.min(from+safeSize,visible.size());return new JobPage(List.copyOf(visible.subList(from,to)),visible.size(),safePage,safeSize);}
    public ExportPage exports(AuthService.Identity actor,int page,int size,String kind) {
        int safePage=Math.max(1,page),safeSize=Math.max(1,Math.min(size,100));
        if(kind!=null) descriptor(kind);
        String sql="SELECT id,kind,tenant_id,format,content_sha256,row_count,created_at,expires_at FROM data_transfer_exports WHERE actor_id=?";
        Object[] args=kind==null?new Object[]{actor.user().id()}:new Object[]{actor.user().id(),kind};
        var now=time.now();
        var visible=jdbc.query(sql+(kind==null?"":" AND kind=?")+" ORDER BY created_at DESC,id DESC",(rs,n)->{
            var expiresAt=rs.getObject(8,OffsetDateTime.class);
            return new ExportList(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                expiresAt.isBefore(now)?"EXPIRED":"COMPLETED",rs.getString(5),rs.getInt(6),rs.getObject(7,OffsetDateTime.class),expiresAt);
        },args).stream().filter(row->{
            try {
                var descriptor=descriptor(row.kind());
                return descriptor.audiences().contains(actor.audience())&&descriptor.exportable()
                    &&actor.permissions().contains("exports:read")&&actor.permissions().contains(descriptor.readPermission());
            } catch(ApiError denied) { return false; }
        }).toList();
        int from=(int)Math.min((long)(safePage-1)*safeSize,visible.size()),to=Math.min(from+safeSize,visible.size());
        return new ExportPage(List.copyOf(visible.subList(from,to)),visible.size(),safePage,safeSize);
    }
    public Artifact errorFile(AuthService.Identity actor,String id,String format){Job job=job(null,id,actor);if(!Set.of("CSV","XLSX").contains(format))throw ApiError.bad("文件格式无效");List<ErrorRow> rows=errors(null,id,actor);List<List<String>> data=new ArrayList<>();for(ErrorRow row:rows)data.add(List.of(Integer.toString(row.row_no()),String.join("; ",row.errors())));String content=csv(List.of("row_no","errors"),data);byte[] bytes="XLSX".equals(format)?xlsx(content):content.getBytes(StandardCharsets.UTF_8);return new Artifact("data-transfer-"+job.id()+"-errors."+format.toLowerCase(Locale.ROOT),"XLSX".equals(format)?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv;charset=utf-8",Base64.getEncoder().encodeToString(bytes),sha256(bytes));}
    private List<String> decodeErrors(String source){try{return mapper.readValue(source,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Stored transfer errors are invalid",e);}}
    private void refreshCounts(String id){jdbc.update("UPDATE data_transfer_jobs SET success_count=(SELECT COUNT(*) FROM data_transfer_job_rows WHERE job_id=? AND status='SUCCEEDED'),error_count=(SELECT COUNT(*) FROM data_transfer_job_rows WHERE job_id=? AND status IN ('FAILED','INVALID')),updated_at=? WHERE id=?",id,id,time.now(),id);}

    private DataTransferDomain domain(String kind){var domain=domains.get(kind);if(domain==null)throw ApiError.bad("数据类型未开放");return domain;}
    public DataTransferDomain.Kind descriptor(String kind){return domain(kind).kinds().stream().filter(item->item.kind().equals(kind)).findFirst().orElseThrow(ApiError::missing);}
    public boolean protectedStorageReady(){return payloadCipher.ready();}
    public List<DataTransferDomain.Kind> kinds(AuthService.Identity actor,String audience){return domains.values().stream().distinct().flatMap(item->item.kinds().stream()).filter(kind->kind.audiences().contains(audience)).filter(kind->(kind.exportable()&&actor.permissions().contains("exports:write")&&actor.permissions().contains(kind.readPermission()))||(kind.importable()&&actor.permissions().contains("imports:write")&&actor.permissions().contains(kind.writePermission()))).sorted(Comparator.comparing(DataTransferDomain.Kind::kind)).toList();}
    private List<String> columns(String kind){var descriptor=descriptor(kind);if(!descriptor.importable())throw ApiError.bad("该类型不支持导入");return descriptor.columns();}
    private List<String> validate(String kind,Map<String,String> row,AuthService.Identity actor,String tenant){return domain(kind).validate(kind,row,actor,tenant);}
    private String operation(String kind,Map<String,String> row){return domain(kind).operation(kind,row);}
    private static List<ParsedRow> readCsv(byte[] bytes,List<String> expected){String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException malformed){throw ApiError.bad("CSV必须是有效UTF-8");}if(text.startsWith("\uFEFF"))text=text.substring(1);List<List<String>> records=parseCsv(text);if(records.isEmpty()||records.size()>MAX_ROWS+1)throw ApiError.bad("CSV行数无效");List<String> header=records.getFirst();if(!header.equals(expected))throw ApiError.bad("模板表头不匹配");List<ParsedRow> rows=new ArrayList<>();for(int i=1;i<records.size();i++){List<String> record=records.get(i);if(record.size()==1&&record.getFirst().isBlank())continue;if(record.size()!=header.size())throw ApiError.bad("CSV列数不一致，行 "+(i+1));Map<String,String> map=new LinkedHashMap<>();for(int j=0;j<header.size();j++)map.put(header.get(j),record.get(j));rows.add(new ParsedRow(rows.size()+2,map));}return rows;}
    private static List<List<String>> parseCsv(String text){List<List<String>> records=new ArrayList<>();List<String> record=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,closedQuote=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(c=='"'&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else if(c=='"'){quoted=false;closedQuote=true;}else cell.append(c);}else if(c=='"'){if(cell.length()!=0||closedQuote)throw ApiError.bad("CSV引号位置无效");quoted=true;}else if(c==','){record.add(cell.toString());cell.setLength(0);closedQuote=false;}else if(c=='\n'||c=='\r'){if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;record.add(cell.toString());records.add(record);record=new ArrayList<>();cell.setLength(0);closedQuote=false;}else{if(closedQuote)throw ApiError.bad("CSV引号后存在非法字符");cell.append(c);}}if(quoted)throw ApiError.bad("CSV引号未闭合");if(cell.length()>0||!record.isEmpty()||closedQuote){record.add(cell.toString());records.add(record);}return records;}
    private static List<ParsedRow> readXlsx(byte[] bytes,List<String> expected){if(bytes.length<4||bytes[0]!='P'||bytes[1]!='K')throw ApiError.bad("XLSX文件无效");try(InputStream in=new ByteArrayInputStream(bytes);Workbook workbook=WorkbookFactory.create(in)){if(!(workbook instanceof XSSFWorkbook xssf))throw ApiError.bad("仅支持XLSX格式");if(xssf.getPackage().getParts().stream().anyMatch(part->part.getPartName().getName().toLowerCase(Locale.ROOT).contains("vbaproject")))throw ApiError.bad("不允许含宏文件");if(workbook.getNumberOfSheets()!=1)throw ApiError.bad("XLSX仅允许一个工作表");Sheet sheet=workbook.getSheetAt(0);if(sheet.getLastRowNum()>MAX_ROWS)throw ApiError.bad("XLSX行数超过上限");DataFormatter formatter=new DataFormatter(Locale.ROOT);org.apache.poi.ss.usermodel.Row headerRow=sheet.getRow(0);if(headerRow==null)throw ApiError.bad("缺少表头");List<String> header=new ArrayList<>();for(int c=0;c<MAX_COLUMNS;c++){Cell cell=headerRow.getCell(c);if(cell==null)break;if(cell.getCellType()==CellType.FORMULA)throw ApiError.bad("模板不允许公式");header.add(formatter.formatCellValue(cell).trim());}if(!header.equals(expected))throw ApiError.bad("模板表头不匹配");List<ParsedRow> rows=new ArrayList<>();for(int r=1;r<=sheet.getLastRowNum();r++){org.apache.poi.ss.usermodel.Row source=sheet.getRow(r);if(source==null)continue;Map<String,String> map=new LinkedHashMap<>();boolean blank=true;for(int c=0;c<header.size();c++){Cell cell=source.getCell(c);if(cell!=null&&cell.getCellType()==CellType.FORMULA)throw ApiError.bad("模板不允许公式，第"+(r+1)+"行");String column=header.get(c).toLowerCase(Locale.ROOT);if(cell!=null&&cell.getCellType()==CellType.NUMERIC&&((column.equals("id") || column.endsWith("_id"))||column.contains("eui")||column.contains("phone")||column.contains("iccid")||column.contains("code")))throw ApiError.bad("ID/编码/号码必须以文本单元格导入，第"+(r+1)+"行");String value=cell==null?"":formatter.formatCellValue(cell);if (value.length() > 32767) throw ApiError.bad("单元格超过Excel的32767字符限制");if(!value.isBlank())blank=false;map.put(header.get(c),value);}if(!blank)rows.add(new ParsedRow(r+1,map));}return rows;}catch(ApiError e){throw e;}catch(Exception e){throw ApiError.bad("XLSX文件无法解析");}}
    private static String sha256(byte[] content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}catch(Exception e){throw new IllegalStateException(e);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("数据转移计划序列化失败",e);}}
    private static String bounded(String value){if(value==null||value.isBlank())return "处理失败";String s=value.replaceAll("[\\r\\n\\t]"," ");return s.substring(0,Math.min(500,s.length()));}
}
