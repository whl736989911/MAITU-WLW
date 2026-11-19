package com.fireplatform.device;

import com.fireplatform.hikvision.HikvisionService;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic local registration of a bounded batch of devices discovered by one vendor source. */
@Service
public class VendorDeviceBulkService {
    public record BatchResult(List<DeviceQueryService.DeviceRow> items) { }

    private static final String OPERATION = "vendor-device-bulk-import";
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final CoordinationFence coordination;
    private final DeviceAccessScope scopes;
    private final IdempotencyStore idem;
    private final LegacyRealtimeDeviceService legacy;
    private final HikvisionService hikvision;
    private final DeviceQueryService queries;

    public VendorDeviceBulkService(JdbcTemplate jdbc, ManagementGuard guard, CoordinationFence coordination,
            DeviceAccessScope scopes, IdempotencyStore idem, LegacyRealtimeDeviceService legacy,
            HikvisionService hikvision, DeviceQueryService queries) {
        this.jdbc=jdbc;this.guard=guard;this.coordination=coordination;this.scopes=scopes;this.idem=idem;
        this.legacy=legacy;this.hikvision=hikvision;this.queries=queries;
    }

    @Transactional
    public BatchResult importDevices(AuthService.Identity actor, String tenant, String key,
            VendorDeviceBulkInputs.Import input, String requestId) {
        coordination.lock();
        guard.begin(actor,"device:write",tenant,"management");
        if ("HIKVISION_FIRECLOUD".equals(input.source()))
            guard.requireCurrent(actor,"integrations:write",tenant,"management");
        scopes.requireAll(actor.user().id(),tenant);
        idem.validateKey(key);
        validateSourceFields(input);

        Set<String> serials=new HashSet<>();
        for(int i=0;i<input.rows().size();i++) {
            var row=input.rows().get(i);
            if(!serials.add(row.serial()))
                throw rowError(i,row,ApiError.bad("批次内设备序列号重复"));
        }

        var canonical=new ArrayList<Object>(input.rows().size()+2);
        canonical.add(tenant);canonical.add(input.source());
        for(var row:input.rows()) {
            canonical.add(List.of(row.name(),row.serial(),row.placeId(),value(row.address()),value(row.legacyProfileId()),value(row.upstreamDeviceId())));
            if(row.sourceRow()!=null)canonical.add(row.sourceRow());
        }
        String fingerprint=idem.fingerprint(canonical);
        var replay=idem.replay(actor.user().id(),OPERATION,key,fingerprint,BatchResult.class);
        if(replay!=null)return replay;

        var results=new ArrayList<DeviceQueryService.DeviceRow>(input.rows().size());
        for(int i=0;i<input.rows().size();i++) {
            var row=input.rows().get(i);
            try {
                String rowKey=rowKey(actor.user().id(),key,i);
                String id;
                if("LEGACY_REALTIME".equals(input.source())) {
                    // Original XLS preview provides its worksheet row; only that import path is new assets only.
                    if(row.sourceRow()!=null) {
                        legacy.requireNewTerminalIdentity(row.serial());
                        if(row.upstreamDeviceId()!=null&&!row.upstreamDeviceId().equals(row.serial()))
                            legacy.requireNewTerminalIdentity(row.upstreamDeviceId());
                    }
                    var registration=new VendorDeviceInputs.Register("LEGACY_REALTIME",row.name(),row.serial(),row.placeId(),
                            row.address(),row.legacyProfileId(),row.upstreamDeviceId());
                    id=legacy.registerAuthorized(actor,tenant,rowKey,registration,requestId);
                } else {
                    id=hikvision.registerAuthorizedBySerial(actor,tenant,rowKey,row.name(),row.serial(),row.placeId(),row.address(),requestId);
                }
                results.add(queries.device(actor.user().id(),tenant,id,false));
            } catch(ApiError e) {
                throw rowError(i,row,e);
            }
        }
        var result=new BatchResult(List.copyOf(results));
        idem.save(actor.user().id(),OPERATION,key,fingerprint,result);
        return result;
    }

    private static void validateSourceFields(VendorDeviceBulkInputs.Import input) {
        for(int i=0;i<input.rows().size();i++) {
            var row=input.rows().get(i);
            if("LEGACY_REALTIME".equals(input.source())) {
                if(row.legacyProfileId()==null || row.legacyProfileId().isBlank())
                    throw rowError(i,row,ApiError.bad("旧实时来源必须明确选择现存旧接口解析配置，不能使用未发布物模型"));
            } else if(row.legacyProfileId()!=null || row.upstreamDeviceId()!=null || row.sourceRow()!=null) {
                throw rowError(i,row,ApiError.bad("海康来源不接受旧实时解析配置或上报标识"));
            }
        }
    }

    private static ApiError rowError(int index,VendorDeviceBulkInputs.Row row,ApiError error) {
        int line=row.sourceRow()==null?index+1:row.sourceRow();
        return new ApiError(error.status,error.code,"第"+line+"行（"+row.serial()+"）："+error.getMessage());
    }
    private static String value(String value) { return value==null?"":value; }
    private static String rowKey(String actor,String batchKey,int row) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((actor+"\n"+batchKey+"\n"+row).getBytes(StandardCharsets.UTF_8));
            return "vdb-"+java.util.HexFormat.of().formatHex(digest,0,24);
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
