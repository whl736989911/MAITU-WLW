package com.fireplatform.device;

import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ApiError;
import java.util.Set;
import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Read-only device-owned event index; deliberately excludes raw payload parameters. */
@Service
public class DeviceEventHistoryService {
    private static final Set<String> KINDS=Set.of("HEARTBEAT","ALARM","FAULT","OFFLINE","PARAM","OTHER");
    public record EventRow(String id,String tenantId,String deviceId,String kind,String method,String status,OffsetDateTime observedAt,OffsetDateTime receivedAt,OffsetDateTime createdAt,String eventCode,String eventLabel) { }
    private final JdbcTemplate jdbc;
    private final DeviceQueryService devices;
    private final PageSizePolicy sizes;
    private final DeviceAccessScope scopes;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public DeviceEventHistoryService(JdbcTemplate jdbc,DeviceQueryService devices,PageSizePolicy sizes,DeviceAccessScope scopes,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.jdbc=jdbc; this.devices=devices; this.sizes=sizes; this.scopes=scopes; this.json=json;
    }

    private EventRow eventRow(ResultSet rs,int rowNumber) throws SQLException {
        String code=null,label=null,params=rs.getString("params_json"),method=rs.getString("method");
        if(params!=null&&"HIKVISION_DEVICE_EVENT".equals(method)) {
            try {
                var parsed=json.readTree(params);
                if(parsed!=null&&"HIKVISION_FIRECLOUD".equals(parsed.path("source").asText())) {
                    code=parsed.path("source_code").asText(null);
                    if(code!=null&&code.isBlank())code=null;
                    if(code!=null) {
                        label=com.fireplatform.hikvision.HikvisionEventCatalog.label(code);
                        if(label==null)label="未映射事件";
                    }
                }
            } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Legacy malformed parameters do not become fabricated event names.
            }
        }
        return new EventRow(rs.getString("id"),rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("kind"),method,rs.getString("status"),rs.getObject("observed_at",OffsetDateTime.class),rs.getObject("received_at",OffsetDateTime.class),rs.getObject("created_at",OffsetDateTime.class),code,label);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<EventRow> list(String actor,String tenant,String deviceId,int page,Integer requestedSize,String kind,OffsetDateTime observedFrom,OffsetDateTime observedTo) {
        if(kind!=null&&kind.isBlank())kind=null;
        if(kind!=null&&!KINDS.contains(kind))throw ApiError.bad("设备事件类型无效");
        if(observedFrom!=null&&observedTo!=null&&!observedFrom.isBefore(observedTo))throw ApiError.bad("结束时间必须晚于开始时间");
        devices.device(actor,tenant,deviceId,false);
        var pagination=Pagination.of(page,requestedSize,sizes.defaultPageSize());
        var scope=scopes.predicate(actor,"d.tenant_id","d.id");
        var args=new ArrayList<Object>(); args.add(deviceId); args.add(tenant); args.addAll(scope.bindings());
        String from=" FROM "+DeviceEventProjection.SQL+" e JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id WHERE e.device_id=? AND e.tenant_id=?"+scope.and();
        if(kind!=null){from+=" AND e.kind=?";args.add(kind);}
        if(observedFrom!=null){from+=" AND e.observed_at>=?";args.add(observedFrom);}
        if(observedTo!=null){from+=" AND e.observed_at<?";args.add(observedTo);}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        args.add(pagination.size()); args.add(pagination.offset());
        var items=jdbc.query("SELECT e.id,e.tenant_id,e.device_id,e.kind,e.method,e.status,e.observed_at,e.received_at,e.created_at,e.params_json"+from+" ORDER BY e.created_at DESC,e.id DESC LIMIT ? OFFSET ?",
            this::eventRow,args.toArray());
        return new Page<>(items,total,pagination.page(),pagination.size());
    }
}
