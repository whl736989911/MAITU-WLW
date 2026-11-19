package com.fireplatform.device;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N12：设备批量导入；导出由 scope-preserving DataTransferService 提供。 */
@Service
public class DeviceBulkService {
    public record Row(int rowNo,String name,String deviceEui,String address,String productVersionId,String placeId,String systemType,String deviceCategory,String status,String error) { }
    public record Preview(String sourceSha256,List<Row> rows,int okCount,int errorCount) { }
    public record Job(String id,String status,int rowCount,int okCount,int errorCount,OffsetDateTime createdAt) { }

    private final JdbcTemplate jdbc; private final DatabaseTime time; private final ExecutionAuthorization auth; private final TenantFence tenants; private final DeviceManagementService devices; private final AuditService audit;
    public DeviceBulkService(JdbcTemplate jdbc,DatabaseTime time,ExecutionAuthorization auth,TenantFence tenants,DeviceManagementService devices,AuditService audit) { this.jdbc=jdbc; this.time=time; this.auth=auth; this.tenants=tenants; this.devices=devices; this.audit=audit; }

    private static final int MAX_ROWS=200;
    private static final String IMPORT_HEADER="name,device_eui,address,product_version_id,place_id";
    private static final String CLASSIFIED_IMPORT_HEADER=IMPORT_HEADER+",system_type,device_category";
    static String sha256(String value) {
        try { var digest=MessageDigest.getInstance("SHA-256"); return HexFormat.of().withUpperCase().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }

    /** Five-column imports remain valid; seven-column imports carry explicit manual classification. */
    public Preview preview(String tenant,String csv) {
        if(csv==null || csv.isBlank()) throw ApiError.bad("导入内容为空");
        var lines=csv.replace("\r\n","\n").split("\n");
        if(lines.length<2) throw ApiError.bad("至少需要表头与一行数据");
        String header=lines[0].trim();
        boolean classified=header.equalsIgnoreCase(CLASSIFIED_IMPORT_HEADER);
        if(!classified && !header.equalsIgnoreCase(IMPORT_HEADER)) throw ApiError.bad("表头必须为 "+IMPORT_HEADER+" 或 "+CLASSIFIED_IMPORT_HEADER);
        if(lines.length-1>MAX_ROWS) throw ApiError.bad("一次最多导入"+MAX_ROWS+"行");
        var rows=new ArrayList<Row>(); var seen=new HashSet<String>(); int ok=0;
        for(int i=1;i<lines.length;i++) {
            var line=lines[i]; if(line.isBlank()) continue;
            var parts=line.split(",",-1);
            if(parts.length!=(classified?7:5)) { rows.add(new Row(i,"","","","",null,null,null,"ERROR","COLUMN_COUNT")); continue; }
            String name=parts[0].trim(), eui=parts[1].trim(), address=parts[2].trim(), version=parts[3].trim(), place=parts[4].trim();
            String systemType=classified&&!parts[5].trim().isEmpty()?parts[5].trim():null,deviceCategory=classified&&!parts[6].trim().isEmpty()?parts[6].trim():null;
            String error=null;
            if(name.isEmpty() || name.length()>200) error="NAME_INVALID";
            else if(!eui.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) error="DEVICE_EUI_INVALID";
            else if(seen.contains(eui)) error="DUPLICATE_IN_BATCH";
            else if(!jdbc.queryForList("SELECT id FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'",String.class,eui).isEmpty()) error="DEVICE_EUI_EXISTS";
            else if(address.isEmpty() || address.length()>500) error="ADDRESS_INVALID";
            else if(version.isEmpty() || jdbc.queryForList("SELECT status FROM product_versions WHERE id=? AND status='PUBLISHED'",String.class,version).isEmpty()) error="VERSION_NOT_PUBLISHED";
            else if(place.isEmpty()) error="PLACE_REQUIRED";
            else if(place.length()>64 || jdbc.queryForList("SELECT id FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",String.class,place,tenant).isEmpty()) error="PLACE_NOT_FOUND";
            else if(!validClassification(systemType) || !validClassification(deviceCategory)) error="CLASSIFICATION_INVALID";
            seen.add(eui);
            if(error==null) ok++;
            rows.add(new Row(i,name,eui,address,version,place,systemType,deviceCategory,error==null?"OK":"ERROR",error));
        }
        if(rows.isEmpty()) throw ApiError.bad("没有可导入的数据行");
        return new Preview(sha256(csv.replace("\r\n","\n")),rows,ok,rows.size()-ok);
    }
    private static boolean validClassification(String value) {
        return value==null || value.isBlank() || (value.trim().length()<=80 && value.codePoints().noneMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE));
    }

    @Transactional(noRollbackFor=ApiError.class) public Job confirm(AuthService.Identity actor,String tenant,String csv,String expectedSha,String reason) {
        auth.lockRequest(actor,tenant,List.of("imports:write")); tenants.lockEnabled(tenant);
        if(reason==null || reason.trim().isEmpty() || reason.length()>1000) throw ApiError.bad("必须说明导入原因");
        var preview=preview(tenant,csv);
        if(!preview.sourceSha256().equalsIgnoreCase(expectedSha)) throw ApiError.conflict("IMPORT_SOURCE_CHANGED","导入内容已变化，请重新预览");
        var replay=jdbc.queryForList("SELECT id FROM device_import_jobs WHERE tenant_id=? AND source_sha256=? AND status='CONFIRMED' ORDER BY created_at LIMIT 1",String.class,tenant,preview.sourceSha256());
        if(!replay.isEmpty()) return job(tenant,replay.getFirst());
        String id=UUID.randomUUID().toString(); var now=time.now();
        if(preview.errorCount()>0) {
            jdbc.update("INSERT INTO device_import_jobs(id,tenant_id,source_sha256,status,row_count,ok_count,error_count,actor_id,reason,created_at) VALUES(?,?,?,'REJECTED',?,?,?,?,?,?)",id,tenant,preview.sourceSha256(),preview.rows().size(),preview.okCount(),preview.errorCount(),actor.user().id(),reason.trim(),now);
            for(var row:preview.rows()) insertRow(id,row,null);
            throw ApiError.conflict("IMPORT_HAS_ERRORS","存在校验失败的行，整批未导入");
        }
        var created=new HashMap<Integer,String>();
        for(var row:preview.rows()) {
            var result=devices.register(actor,tenant,UUID.randomUUID().toString(),new DeviceInputs.Register(row.name(),row.deviceEui(),row.address(),row.placeId(),row.productVersionId(),row.systemType(),row.deviceCategory()),"bulk-import","management");
            created.put(row.rowNo(),result.id());
        }
        jdbc.update("INSERT INTO device_import_jobs(id,tenant_id,source_sha256,status,row_count,ok_count,error_count,actor_id,reason,created_at) VALUES(?,?,?,'CONFIRMED',?,?,0,?,?,?)",id,tenant,preview.sourceSha256(),preview.rows().size(),preview.okCount(),actor.user().id(),reason.trim(),now);
        for(var row:preview.rows()) insertRow(id,row,created.get(row.rowNo()));
        audit.record(actor.user().id(),tenant,"DEVICE_IMPORT_CONFIRMED",id,"SUCCESS","api");
        return new Job(id,"CONFIRMED",preview.rows().size(),preview.okCount(),0,now);
    }

    private void insertRow(String jobId,Row row,String deviceId) {
        jdbc.update("INSERT INTO device_import_rows(job_id,row_no,name,device_eui,place_id,status,error_code,device_id,system_type,device_category) VALUES(?,?,?,?,?,?,?,?,?,?)",jobId,row.rowNo(),row.name().length()>200?row.name().substring(0,200):row.name(),row.deviceEui().length()>64?row.deviceEui().substring(0,64):row.deviceEui(),row.placeId(),row.status(),row.error(),deviceId,row.systemType(),row.deviceCategory());
    }

    public Job job(String tenant,String id) {
        var rows=jdbc.query("SELECT id,status,row_count,ok_count,error_count,created_at FROM device_import_jobs WHERE id=? AND tenant_id=?",(rs,n)->new Job(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getObject(6,OffsetDateTime.class)),id,tenant);
        if(rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public List<Row> jobRows(String tenant,String id) {
        job(tenant,id);
        return jdbc.query("SELECT row_no,name,device_eui,place_id,system_type,device_category,status,error_code FROM device_import_rows WHERE job_id=? ORDER BY row_no",(rs,n)->new Row(rs.getInt(1),rs.getString(2),rs.getString(3),"","",rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),id);
    }

}
