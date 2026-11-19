package com.fireplatform.duty;

import com.fireplatform.legacyingest.FaultProjection;
import com.fireplatform.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projections over observed legacy fault events; device connectivity is not a fault event. */
@Service
public class DutyQueries {
    public record HandlingState(boolean handled,String note,long revision,String updatedBy,OffsetDateTime updatedAt) { }
    public record FaultEvent(String id, String tenantId, String deviceId, String kind, String method,
        String faultCode, String faultLabel, OffsetDateTime observedAt, OffsetDateTime createdAt,
        OffsetDateTime sourceMessageAt, String sourceTimeField, OffsetDateTime receivedAt,
        String deviceName, String deviceAddress, String placeId,HandlingState handling,String sourceFaultId,Boolean sourceRecovered,OffsetDateTime sourceRecoveredAt) { }
    public record FaultTypeCount(String faultCode, String faultLabel, long count) { }
    public record DeviceFaultSummary(String tenantId,String deviceId,String deviceName,String deviceAddress,String placeId,
        long eventCount,List<FaultTypeCount> faultTypes,OffsetDateTime latestObservedAt,OffsetDateTime latestReceivedAt) { }
    record SourceMessageTime(OffsetDateTime observedAt, String field) { }
    private record DeviceKey(String tenantId,String deviceId) { }
    private record FaultTypeKey(String code,String label) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EPOCH = Pattern.compile("\\d{10}|\\d{13}");
    private final JdbcTemplate jdbc;
    private final DeviceAccessScope access;
    private final ScopeFilter scope;
    private final PageSizePolicy sizes;
    public DutyQueries(JdbcTemplate jdbc, DeviceAccessScope access, ScopeFilter scope, PageSizePolicy sizes) {
        this.jdbc=jdbc; this.access=access; this.scope=scope; this.sizes=sizes;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<FaultEvent> faults(String actor,String tenant,ScopeFilter.Selection selection,String keyword,OffsetDateTime from,OffsetDateTime to,String handlingState,int page,Integer requestedSize) {
        return faultPage(actor,tenant,selection,keyword,from,to,page,requestedSize,null,handlingState);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<FaultEvent> faultsForDevice(String actor,String tenant,ScopeFilter.Selection selection,String deviceId,String keyword,OffsetDateTime from,OffsetDateTime to,String handlingState,int page,Integer requestedSize) {
        if(deviceId==null||deviceId.isBlank())throw ApiError.bad("设备ID无效");
        return faultPage(actor,tenant,selection,keyword,from,to,page,requestedSize,deviceId,handlingState);
    }
    private Page<FaultEvent> faultPage(String actor,String tenant,ScopeFilter.Selection selection,String keyword,OffsetDateTime from,OffsetDateTime to,int page,Integer requestedSize,String deviceId,String handlingState) {
        String handlingClause=switch(handlingState==null||handlingState.isBlank()?"":handlingState){
            case ""->"";
            case "UNRECORDED"->" AND h.event_id IS NULL";
            case "UNHANDLED"->" AND h.handled=FALSE";
            case "HANDLED"->" AND h.handled=TRUE";
            default->throw ApiError.bad("故障处置状态筛选无效");
        };
        var pagination=Pagination.of(page,requestedSize,sizes.defaultPageSize());
        if(selection.empty()) return new Page<>(java.util.List.of(),0,pagination.page(),pagination.size());
        var accessClause=access.predicate(actor,"e.tenant_id","e.device_id");
        var treeClause=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();
        if(tenant!=null) args.add(tenant);
        if(deviceId!=null) args.add(deviceId);
        args.addAll(accessClause.bindings()); args.addAll(treeClause.bindings());
        var where=new StringBuilder(" WHERE e.kind='FAULT' AND d.is_simulated=FALSE");
        if(tenant!=null) where.append(" AND e.tenant_id=?");
        if(deviceId!=null) where.append(" AND e.device_id=?");
        where.append(accessClause.and()).append(treeClause.and());
        where.append(handlingClause);
        if(keyword!=null&&!keyword.isBlank()) { where.append(" AND (LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(d.address,'')) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(e.method,'')) LIKE LOWER(?) ESCAPE '!')"); var like=LiteralLike.contains(keyword); args.add(like);args.add(like);args.add(like); }
        if(from!=null){where.append(" AND e.observed_at>=?");args.add(from);}
        if(to!=null){where.append(" AND e.observed_at<?");args.add(to);}
        String base=" FROM legacy_ingest_events e LEFT JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id LEFT JOIN duty_fault_handlings h ON h.event_id=e.id AND h.tenant_id=e.tenant_id LEFT JOIN hikvision_fault_state hf ON hf.normalized_event_id=e.id LEFT JOIN raw_messages rm ON rm.id=e.raw_message_id AND rm.tenant_id=e.tenant_id";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+base+where,Long.class,args.toArray());
        args.add(pagination.size());args.add(pagination.offset());
        var items=jdbc.query("SELECT e.id,e.tenant_id,e.device_id,e.kind,e.method,e.params_json,e.observed_at,e.created_at,COALESCE(m.vendor_platform,e.source_platform) AS vendor_platform,m.payload_raw,COALESCE(m.received_at,rm.received_at) AS received_at,d.name AS device_name,d.address AS device_address,d.place_id,h.handled AS handling_handled,h.note AS handling_note,h.revision AS handling_revision,h.updated_by AS handling_updated_by,h.updated_at AS handling_updated_at,hf.source_fault_id,hf.source_recovered AS source_recovered,hf.source_stop_time AS source_recovered_at"+base+" LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id"+where+" ORDER BY e.observed_at DESC NULLS LAST,e.created_at DESC,e.id LIMIT ? OFFSET ?",DutyQueries::row,args.toArray());
        return new Page<>(items,total,pagination.page(),pagination.size());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<DeviceFaultSummary> deviceFaults(String actor,String tenant,ScopeFilter.Selection selection,String keyword,OffsetDateTime from,OffsetDateTime to,int page,Integer requestedSize) {
        var pagination=Pagination.of(page,requestedSize,sizes.defaultPageSize());
        if(selection.empty()) return new Page<>(List.of(),0,pagination.page(),pagination.size());
        var accessClause=access.predicate(actor,"e.tenant_id","e.device_id");
        var treeClause=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();
        var where=new StringBuilder(" WHERE e.kind='FAULT' AND e.device_id IS NOT NULL AND d.is_simulated=FALSE");
        if(tenant!=null){where.append(" AND e.tenant_id=?");args.add(tenant);}
        where.append(accessClause.and()).append(treeClause.and());
        args.addAll(accessClause.bindings());args.addAll(treeClause.bindings());
        if(keyword!=null&&!keyword.isBlank()) { where.append(" AND (LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(d.address,'')) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(e.method,'')) LIKE LOWER(?) ESCAPE '!')"); var like=LiteralLike.contains(keyword);args.add(like);args.add(like);args.add(like); }
        if(from!=null){where.append(" AND e.observed_at>=?");args.add(from);}
        if(to!=null){where.append(" AND e.observed_at<?");args.add(to);}
        String fromSql=" FROM legacy_ingest_events e JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id LEFT JOIN raw_messages rm ON rm.id=e.raw_message_id AND rm.tenant_id=e.tenant_id";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT e.tenant_id,e.device_id"+fromSql+where+" GROUP BY e.tenant_id,e.device_id) device_groups",Long.class,args.toArray());
        var aggregateArgs=new ArrayList<Object>(args);aggregateArgs.add(pagination.size());aggregateArgs.add(pagination.offset());
        var groups=jdbc.query("SELECT e.tenant_id,e.device_id,d.name,d.address,d.place_id,COUNT(*) AS event_count,MAX(e.observed_at) AS latest_observed_at,MAX(COALESCE(m.received_at,rm.received_at)) AS latest_received_at"+fromSql+where+" GROUP BY e.tenant_id,e.device_id,d.name,d.address,d.place_id ORDER BY MAX(COALESCE(m.received_at,rm.received_at)) DESC NULLS LAST,d.name,e.tenant_id,e.device_id LIMIT ? OFFSET ?",
            (rs,n)->new DeviceFaultSummary(rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("name"),rs.getString("address"),rs.getString("place_id"),rs.getLong("event_count"),List.of(),rs.getObject("latest_observed_at",OffsetDateTime.class),rs.getObject("latest_received_at",OffsetDateTime.class)),aggregateArgs.toArray());
        if(groups.isEmpty()) return new Page<>(groups,total,pagination.page(),pagination.size());
        var typeWhere=new StringBuilder(where).append(" AND (");
        var typeArgs=new ArrayList<Object>(args);
        for(int i=0;i<groups.size();i++){
            if(i>0)typeWhere.append(" OR ");
            typeWhere.append("(e.tenant_id=? AND e.device_id=?)");
            typeArgs.add(groups.get(i).tenantId());typeArgs.add(groups.get(i).deviceId());
        }
        typeWhere.append(')');
        var counts=new HashMap<DeviceKey,Map<FaultTypeKey,Long>>();
        jdbc.query("SELECT e.tenant_id,e.device_id,e.method,e.params_json"+fromSql+typeWhere,(RowCallbackHandler)rs->{
            var device=new DeviceKey(rs.getString("tenant_id"),rs.getString("device_id"));
            var description=FaultProjection.describe(rs.getString("method"),rs.getString("params_json"));
            counts.computeIfAbsent(device,ignored->new HashMap<>()).merge(new FaultTypeKey(description.code(),description.label()),1L,Long::sum);
        },typeArgs.toArray());
        var items=groups.stream().map(group->{
            var device=new DeviceKey(group.tenantId(),group.deviceId());
            var types=counts.getOrDefault(device,Map.of()).entrySet().stream()
                .map(entry->new FaultTypeCount(entry.getKey().code(),entry.getKey().label(),entry.getValue()))
                .sorted(java.util.Comparator.comparing(FaultTypeCount::faultLabel).thenComparing(type->type.faultCode()==null?"":type.faultCode())).toList();
            return new DeviceFaultSummary(group.tenantId(),group.deviceId(),group.deviceName(),group.deviceAddress(),group.placeId(),group.eventCount(),types,group.latestObservedAt(),group.latestReceivedAt());
        }).toList();
        return new Page<>(items,total,pagination.page(),pagination.size());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public FaultEvent fault(String actor,String tenant,ScopeFilter.Selection selection,String id) {
        if(selection.empty()) throw ApiError.missing();
        var accessClause=access.predicate(actor,"e.tenant_id","e.device_id");
        var treeClause=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();args.add(id);if(tenant!=null)args.add(tenant);args.addAll(accessClause.bindings());args.addAll(treeClause.bindings());
        var rows=jdbc.query("SELECT e.id,e.tenant_id,e.device_id,e.kind,e.method,e.params_json,e.observed_at,e.created_at,COALESCE(m.vendor_platform,e.source_platform) AS vendor_platform,m.payload_raw,COALESCE(m.received_at,rm.received_at) AS received_at,d.name AS device_name,d.address AS device_address,d.place_id,h.handled AS handling_handled,h.note AS handling_note,h.revision AS handling_revision,h.updated_by AS handling_updated_by,h.updated_at AS handling_updated_at,hf.source_fault_id,hf.source_recovered AS source_recovered,hf.source_stop_time AS source_recovered_at FROM legacy_ingest_events e LEFT JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id LEFT JOIN duty_fault_handlings h ON h.event_id=e.id AND h.tenant_id=e.tenant_id LEFT JOIN hikvision_fault_state hf ON hf.normalized_event_id=e.id LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id LEFT JOIN raw_messages rm ON rm.id=e.raw_message_id AND rm.tenant_id=e.tenant_id WHERE e.id=? AND e.kind='FAULT' AND d.is_simulated=FALSE"+(tenant==null?"":" AND e.tenant_id=?")+accessClause.and()+treeClause.and(),DutyQueries::row,args.toArray());
        if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    static FaultEvent row(ResultSet rs,int n)throws SQLException {
        Long revision=(Long)rs.getObject("handling_revision");HandlingState handling=revision==null?null:new HandlingState(rs.getBoolean("handling_handled"),rs.getString("handling_note"),revision,rs.getString("handling_updated_by"),rs.getObject("handling_updated_at",OffsetDateTime.class));
        var description=FaultProjection.describe(rs.getString("method"),rs.getString("params_json"));
        var sourceTime=sourceMessageTime(rs.getString("vendor_platform"),rs.getString("payload_raw"));
        Object recovered=rs.getObject("source_recovered");Boolean sourceRecovered=recovered==null?null:rs.getBoolean("source_recovered");
        return new FaultEvent(rs.getString("id"),rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("kind"),rs.getString("method"),description.code(),description.label(),rs.getObject("observed_at",OffsetDateTime.class),rs.getObject("created_at",OffsetDateTime.class),sourceTime.observedAt(),sourceTime.field(),rs.getObject("received_at",OffsetDateTime.class),rs.getString("device_name"),rs.getString("device_address"),rs.getString("place_id"),handling,rs.getString("source_fault_id"),sourceRecovered,rs.getObject("source_recovered_at",OffsetDateTime.class));
    }
    static SourceMessageTime sourceMessageTime(String platform, String raw) {
        if("hikvision_firecloud".equals(platform)&&raw!=null) {
            try { JsonNode root=JSON.readTree(raw);for(String field:List.of("faultStartTime","faultTime","alarmStartTime","monitorTime","happenTime")){String value=root.path(field).asText(null);if(value!=null&&!value.isBlank())return new SourceMessageTime(OffsetDateTime.parse(value),field);} }
            catch(Exception ignored) { return new SourceMessageTime(null,null); }
            return new SourceMessageTime(null,null);
        }
        if (!"formttl".equals(platform) || raw == null || raw.isBlank()) return new SourceMessageTime(null, null);
        try {
            JsonNode time = JSON.readTree(raw).path("platformMsg").path("time");
            if (time.isMissingNode() || time.isNull()) return new SourceMessageTime(null, null);
            String text = time.isIntegralNumber() ? time.asText() : "";
            if (!EPOCH.matcher(text).matches()) return new SourceMessageTime(null, null);
            long epoch = Long.parseLong(text);
            OffsetDateTime value = text.length() == 10
                ? Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC)
                : Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.UTC);
            return new SourceMessageTime(value, "platformMsg.time");
        } catch (Exception ignored) {
            return new SourceMessageTime(null, null);
        }
    }
}
