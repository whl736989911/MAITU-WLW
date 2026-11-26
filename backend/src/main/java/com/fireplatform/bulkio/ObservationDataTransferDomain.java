package com.fireplatform.bulkio;

import com.fireplatform.device.DeviceEventProjection;

import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.duty.DutyTelemetryQueries;
import com.fireplatform.duty.DutyQueries;
import com.fireplatform.audit.AuditRecordFilter;
import java.time.OffsetDateTime;
import java.util.*;
import com.fireplatform.audit.AuditRecordProjection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only, scope-preserving exports of operational observations. */
@Component
public final class ObservationDataTransferDomain implements DataTransferDomain {
    private static final Map<String,List<String>> COLUMNS=Map.of(
        "MONITORING",List.of("id","device_id","device_name","address","metric","value","unit","observed_at","received_at","source"),
        "EVENTS",List.of("id","device_id","kind","source_kind","method","status","observed_at","received_at","event_code","event_label"),
        "ALARMS",List.of("id","device_id","event_type","occurred_at","physical_state","response_state","conclusion","revision","time_quality","platform_recorded_at"),
        "FAULTS",List.of("id","device_id","fault_type","fault_code","fault_label","method","source_kind","source_id","observed_at","received_at","source_fault_id","recovered_at","handled"),
        "NOTIFICATION_ATTEMPTS",List.of("id","event_kind","source_kind","source_id","device_id","contact_id","channel","attempt_no","status","provider_code","provider_id","created_at","finished_at"),
        "AUDIT",List.of("id","tenant_id","tenant_name","occurred_at","action","outcome","actor_ref","actor_reference_state","target_ref","target_reference_state"),
        "MY_NOTIFICATIONS",List.of("id","kind","title","body","status","created_at","read_at","handled_at","revision"));
    private final JdbcTemplate jdbc;
    private final DeviceAccessScope devices;
    private final ScopeFilter scope;
    private final DutyQueries duty;
    private final DutyTelemetryQueries telemetry;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public ObservationDataTransferDomain(JdbcTemplate jdbc,DeviceAccessScope devices,ScopeFilter scope,DutyQueries duty,DutyTelemetryQueries telemetry,com.fasterxml.jackson.databind.ObjectMapper json){this.jdbc=jdbc;this.devices=devices;this.scope=scope;this.duty=duty;this.telemetry=telemetry;this.json=json;}

    @Override public List<Kind> kinds(){
        var result=new ArrayList<Kind>();
        for(String kind:COLUMNS.keySet()) {
            String permission=switch(kind){case "ALARMS"->"alarm:read";case "MY_NOTIFICATIONS"->"inbox:read";case "NOTIFICATION_ATTEMPTS"->"notification-resources:read";case "AUDIT"->"audit:read";default->"device:read";};
            boolean customer=kind.equals("MONITORING")||kind.equals("EVENTS")||kind.equals("ALARMS")||kind.equals("FAULTS")||kind.equals("MY_NOTIFICATIONS");
            result.add(new Kind(kind,List.of(),permission,null,false,true,false,customer?Set.of("management","customer"):Set.of("management"),"READ_ONLY"));
        }
        return List.copyOf(result);
    }

    @Override public ExportData export(String kind,AuthService.Identity actor,String tenant,Map<String,String> filters,List<String> selectedIds){
        if(filters==null)filters=Map.of();
        if(!COLUMNS.containsKey(kind))throw ApiError.bad("不支持的观察导出类型");
        Set<String> extras=switch(kind){case "MONITORING"->Set.of("metric","observed_from","observed_to","device_id","history_only");case "EVENTS"->Set.of("kind","status","occurred_from","occurred_to","device_id");case "ALARMS"->Set.of("keyword","physical_state","response_state","conclusion","occurred_from","occurred_to","device_id");case "FAULTS"->Set.of("keyword","from","to","device_id","handling_state");case "NOTIFICATION_ATTEMPTS"->Set.of("event_kind","source_kind","status","occurred_from","occurred_to");case "AUDIT"->Set.of("action","outcome","occurred_from","occurred_to");default->Set.of("status");};
        ScopeFilter.Selection selection=DataTransferFilters.selection(filters,extras);
        validateFilters(kind,filters);
        var checkedIds=DataTransferFilters.selectedIds(selectedIds);
        if(selectedIds!=null&&checkedIds.isEmpty())return new ExportData(COLUMNS.get(kind),List.of());
        if(tenant==null&&kind.equals("MY_NOTIFICATIONS")&&"customer".equals(actor.audience()))throw ApiError.bad("客户收件箱导出需要当前单位");
        if(kind.equals("FAULTS")&&selectedIds!=null)return DataTransferFilters.selected(new ExportData(COLUMNS.get(kind),selectedFaults(actor,tenant,selection,filters,checkedIds)),selectedIds);
        List<List<String>> rows=switch(kind){
            case "MONITORING"->monitoring(actor,tenant,selection,filters,selectedIds==null?null:checkedIds);
            case "EVENTS"->events(actor,tenant,selection,filters,selectedIds);
            case "ALARMS"->alarms(actor,tenant,selection,filters,selectedIds);
            case "FAULTS"->faults(actor,tenant,selection,filters);
            case "NOTIFICATION_ATTEMPTS"->attempts(actor,tenant,selection,filters,selectedIds);
            case "AUDIT"->audit(actor,tenant,selection,filters,selectedIds);
            case "MY_NOTIFICATIONS"->inbox(actor,tenant,selection,filters,selectedIds);
            default->throw ApiError.bad("不支持的观察导出类型");
        };
        if(kind.equals("MONITORING")&&selectedIds!=null){var missing=new HashSet<>(checkedIds);for(var row:rows)missing.remove(row.getFirst());if(!missing.isEmpty())throw ApiError.forbidden("勾选记录不存在或不在当前授权及筛选范围内");return new ExportData(COLUMNS.get(kind),rows);}
        return DataTransferFilters.selected(new ExportData(COLUMNS.get(kind),rows),selectedIds);
    }

    private List<List<String>> monitoring(AuthService.Identity actor,String tenant,ScopeFilter.Selection sel,Map<String,String> f,List<String> selectedIds){
        if(sel.empty())return List.of();
        OffsetDateTime from=blank(f.get("observed_from"))?null:date(f.get("observed_from")),to=blank(f.get("observed_to"))?null:date(f.get("observed_to"));String metric=blank(f.get("metric"))?null:f.get("metric");
        String deviceId=blank(f.get("device_id"))?null:f.get("device_id");
        var rows=new ArrayList<List<String>>();var seen=new HashSet<String>();
        var wanted=selectedIds==null?null:new HashSet<>(selectedIds);
        for(int page=1;;page++){
            var result=telemetry.history(actor.user().id(),tenant,sel,metric,from,to,page,100,deviceId);
            var sources=sampleSources(result.items());
            for(var sample:result.items())if(wanted==null||wanted.contains(sample.eventId()))for(var reading:sample.readings()){String identity=sample.eventId()+"\u0000"+reading.metric();if(!seen.add(identity))continue;rows.add(List.of(sample.eventId(),Objects.toString(sample.deviceId(),""),Objects.toString(sample.deviceName(),""),Objects.toString(sample.address(),""),reading.metric(),reading.value(),Objects.toString(reading.unit(),""),Objects.toString(sample.observedAt(),""),Objects.toString(sample.receivedAt(),""),sources.getOrDefault(sample.eventId(),"UNKNOWN")));}
            if(rows.size()>10_000)throw ApiError.bad("导出超过10000行，请收窄范围");if((long)page*result.size()>=result.total()||result.items().isEmpty())break;
        }
        if(!"true".equals(f.get("history_only"))&&from==null&&to==null)for(int page=1;;page++){
            var result=telemetry.current(actor.user().id(),tenant,sel,metric,page,100,deviceId);
            var sources=sampleSources(result.items());
            for(var sample:result.items())if(wanted==null||wanted.contains(sample.eventId()))for(var reading:sample.readings()){String identity=sample.eventId()+"\u0000"+reading.metric();if(!seen.add(identity))continue;rows.add(List.of(sample.eventId(),Objects.toString(sample.deviceId(),""),Objects.toString(sample.deviceName(),""),Objects.toString(sample.address(),""),reading.metric(),reading.value(),Objects.toString(reading.unit(),""),Objects.toString(sample.observedAt(),""),Objects.toString(sample.receivedAt(),""),sources.getOrDefault(sample.eventId(),"UNKNOWN")));}
            if(rows.size()>10_000)throw ApiError.bad("导出超过10000行，请收窄范围");if((long)page*result.size()>=result.total()||result.items().isEmpty())break;
        }
        return rows;
    }
    private Map<String,String> sampleSources(List<DutyTelemetryQueries.Sample> samples) {
        if(samples.isEmpty())return Map.of();
        var result=new HashMap<String,String>();
        var legacy=new ArrayList<String>();var generic=new ArrayList<String>();
        for(var sample:samples){if(sample.eventId().startsWith(DutyTelemetryQueries.GENERIC_BATTERY_PREFIX))generic.add(sample.eventId().substring(DutyTelemetryQueries.GENERIC_BATTERY_PREFIX.length()));else legacy.add(sample.eventId());}
        if(!legacy.isEmpty()){String placeholders=String.join(",",Collections.nCopies(legacy.size(),"?"));jdbc.query("SELECT e.id,COALESCE(m.vendor_platform,e.source_platform,'LEGACY') FROM legacy_ingest_events e LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id WHERE e.id IN ("+placeholders+")",rs->{while(rs.next())result.put(rs.getString(1),rs.getString(2));return null;},legacy.toArray());}
        if(!generic.isEmpty()){String placeholders=String.join(",",Collections.nCopies(generic.size(),"?"));jdbc.query("SELECT id,source_kind FROM real_device_battery_observations WHERE id IN ("+placeholders+") AND source_kind='GENERIC_INGEST'",rs->{while(rs.next())result.put(DutyTelemetryQueries.GENERIC_BATTERY_PREFIX+rs.getString(1),rs.getString(2));return null;},generic.toArray());}
        return result;
    }
    private String source(String eventId){return jdbc.query("SELECT COALESCE(m.vendor_platform,e.source_platform,'LEGACY') FROM legacy_ingest_events e LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id WHERE e.id=?",(rs,n)->rs.getString(1),eventId).stream().findFirst().orElse("UNKNOWN");}
    private List<List<String>> selectedFaults(AuthService.Identity actor,String tenant,ScopeFilter.Selection sel,Map<String,String> filters,List<String> ids){
        var rows=new ArrayList<List<String>>();OffsetDateTime from=blank(filters.get("from"))?null:date(filters.get("from")),to=blank(filters.get("to"))?null:date(filters.get("to"));String keyword=LiteralLike.normalizedKeyword(filters.get("keyword")),handlingState=filters.get("handling_state");
        for(String id:ids){var event=duty.fault(actor.user().id(),tenant,sel,id,"ACTIVE".equals(handlingState));if(!blank(filters.get("device_id"))&&!filters.get("device_id").equals(event.deviceId()))continue;if(from!=null&&(event.observedAt()==null||event.observedAt().isBefore(from)))continue;if(to!=null&&(event.observedAt()==null||!event.observedAt().isBefore(to)))continue;if(!blank(keyword)){String q=keyword.toLowerCase(Locale.ROOT);if(!Objects.toString(event.deviceName(),"").toLowerCase(Locale.ROOT).contains(q)&&!Objects.toString(event.deviceAddress(),"").toLowerCase(Locale.ROOT).contains(q)&&!Objects.toString(event.method(),"").toLowerCase(Locale.ROOT).contains(q))continue;}
            if("UNRECORDED".equals(handlingState)&&event.handling()!=null||"UNHANDLED".equals(handlingState)&&(event.handling()==null||event.handling().handled())||"HANDLED".equals(handlingState)&&(event.handling()==null||!event.handling().handled()))continue;
            rows.add(List.of(event.id(),Objects.toString(event.deviceId(),""),"FAULT",Objects.toString(event.faultCode(),""),event.faultLabel(),Objects.toString(event.method(),""),source(event.id()),event.id(),Objects.toString(event.observedAt(),""),Objects.toString(event.receivedAt(),""),Objects.toString(event.sourceFaultId(),""),Objects.toString(event.sourceRecoveredAt(),""),event.handling()==null?"":Boolean.toString(event.handling().handled())));
        }return rows;
    }
    private List<List<String>> events(AuthService.Identity actor,String tenant,ScopeFilter.Selection sel,Map<String,String> f,List<String> selectedIds){return deviceRows(actor,tenant,sel,f,"EVENTS",selectedIds);}
    private List<List<String>> faults(AuthService.Identity actor,String tenant,ScopeFilter.Selection sel,Map<String,String> f){
        OffsetDateTime from=blank(f.get("from"))?null:date(f.get("from")),to=blank(f.get("to"))?null:date(f.get("to"));
        var result=new ArrayList<List<String>>();
        for(int page=1;;page++){
            var data=blank(f.get("device_id"))?duty.faults(actor.user().id(),tenant,sel,f.get("keyword"),from,to,f.get("handling_state"),page,100):duty.faultsForDevice(actor.user().id(),tenant,sel,f.get("device_id"),f.get("keyword"),from,to,f.get("handling_state"),page,100);
            for(var event:data.items())result.add(List.of(event.id(),Objects.toString(event.deviceId(),""),"FAULT",Objects.toString(event.faultCode(),""),event.faultLabel(),Objects.toString(event.method(),""),source(event.id()),event.id(),Objects.toString(event.observedAt(),""),Objects.toString(event.receivedAt(),""),Objects.toString(event.sourceFaultId(),""),Objects.toString(event.sourceRecoveredAt(),""),event.handling()==null?"":Boolean.toString(event.handling().handled())));
            if(result.size()>10_000)throw ApiError.bad("导出超过10000行，请收窄范围");
            if((long)page*data.size()>=data.total()||data.items().isEmpty())break;
        }
        return result;
    }
    private String[] eventFields(String params) {
        if(params==null)return new String[]{null,null};
        try {
            var node=json.readTree(params);
            if(!"HIKVISION_FIRECLOUD".equals(node.path("source").asText()))return new String[]{null,null};
            String code=node.path("source_code").asText(null);
            if(code==null||code.isBlank())return new String[]{null,null};
            String label=com.fireplatform.hikvision.HikvisionEventCatalog.label(code);
            return new String[]{code,label==null?"未映射事件":label};
        } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return new String[]{null,null};
        }
    }
    private List<List<String>> deviceRows(AuthService.Identity actor,String tenant,ScopeFilter.Selection sel,Map<String,String> f,String kind,List<String> selectedIds){
        var access=devices.predicate(actor.user().id(),"e.tenant_id","e.device_id");var tree=scope.devices(actor.user().id(),"d",tenant,sel);var args=new ArrayList<Object>();if(tenant!=null)args.add(tenant);
        String filter="";if(kind.equals("FAULTS"))filter+=" AND e.kind='FAULT'";if(!blank(f.get("kind"))&&kind.equals("EVENTS")){filter+=" AND e.kind=?";args.add(f.get("kind"));}
        if(!blank(f.get("status"))&&kind.equals("EVENTS")){filter+=" AND e.status=?";args.add(f.get("status"));}
        if(kind.equals("EVENTS")){if(!blank(f.get("device_id"))){filter+=" AND e.device_id=?";args.add(f.get("device_id"));}if(!blank(f.get("occurred_from"))){filter+=" AND e.observed_at>=?";args.add(date(f.get("occurred_from")));}if(!blank(f.get("occurred_to"))){filter+=" AND e.observed_at<?";args.add(date(f.get("occurred_to")));}}
        String idFilter=idFilter("e",selectedIds,args);args.addAll(access.bindings());args.addAll(tree.bindings());
        return jdbc.query("SELECT e.id,e.device_id,e.kind,e.method,e.observed_at,e.created_at,e.received_at,e.source_kind,e.status,e.params_json FROM "+DeviceEventProjection.SQL+" e JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id WHERE 1=1"+(tenant==null?"":" AND e.tenant_id=?")+filter+idFilter+access.and()+tree.and()+" ORDER BY e.created_at DESC,e.id LIMIT 10001",(rs,n)->{
            var row=new ArrayList<String>(List.of(rs.getString(1),Objects.toString(rs.getString(2),""),rs.getString(3),Objects.toString(rs.getString(8),"UNKNOWN"),Objects.toString(rs.getString(4),""),Objects.toString(rs.getString(9),"UNKNOWN"),Objects.toString(rs.getObject(5),""),Objects.toString(rs.getObject(7),"")));
            if(kind.equals("EVENTS")){String[] event=eventFields(rs.getString(10));row.add(Objects.toString(event[0],""));row.add(Objects.toString(event[1],""));}
            return row;
        },args.toArray());
    }
    private List<List<String>> alarms(AuthService.Identity actor,String tenant,ScopeFilter.Selection selection,Map<String,String> filters,List<String> selectedIds){
        var units=scope.units(actor.user().id(),"a","tenant_id",tenant,selection);
        var access=devices.predicate(actor.user().id(),"a.tenant_id","a.device_id");
        var tree=scope.alarmDevices("a",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();args.addAll(units.bindings());args.addAll(access.bindings());args.addAll(tree.bindings());
        String selected=idFilter("a",selectedIds,args);
        String where="1=1"+units.and()+access.and()+tree.and()+selected+" AND a.is_simulated=FALSE";
        if(!blank(filters.get("device_id"))){where+=" AND a.device_id=?";args.add(filters.get("device_id"));}
        for(String key:List.of("physical_state","response_state"))if(!blank(filters.get(key))){where+=" AND a."+key+"=?";args.add(filters.get(key));}
        if("UNREVIEWED".equals(filters.get("conclusion")))where+=" AND a.conclusion IS NULL";
        else if(!blank(filters.get("conclusion"))){where+=" AND a.conclusion=?";args.add(filters.get("conclusion"));}
        if(!blank(filters.get("keyword"))){String like=LiteralLike.contains(LiteralLike.normalizedKeyword(filters.get("keyword")));where+=" AND (LOWER(a.id) LIKE LOWER(?) ESCAPE '!' OR LOWER(a.device_name) LIKE LOWER(?) ESCAPE '!' OR LOWER(a.address) LIKE LOWER(?) ESCAPE '!')";args.add(like);args.add(like);args.add(like);}
        if(!blank(filters.get("occurred_from"))||!blank(filters.get("occurred_to")))where+=" AND a.source_time_known IS NOT FALSE";
        return jdbc.query("SELECT a.id,a.device_id,a.event_type,CASE WHEN a.source_time_known=FALSE THEN NULL ELSE a.occurred_at END,a.physical_state,a.response_state,a.conclusion,a.revision,CASE WHEN a.source_time_known IS NULL THEN 'LEGACY_UNVERIFIED' WHEN a.source_time_known=TRUE THEN 'SOURCE_REPORTED' ELSE 'SOURCE_TIME_MISSING' END,a.platform_recorded_at FROM alarms a WHERE "+where+timeFilter(filters,args,"a.occurred_at")+" ORDER BY a.occurred_at DESC,a.id LIMIT 10001",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3),Objects.toString(rs.getObject(4),""),rs.getString(5),rs.getString(6),Objects.toString(rs.getString(7),""),Long.toString(rs.getLong(8)),rs.getString(9),Objects.toString(rs.getObject(10),"")),args.toArray());
    }
    private List<List<String>> attempts(AuthService.Identity actor,String tenant,ScopeFilter.Selection sel,Map<String,String> f,List<String> selectedIds){var units=scope.units(actor.user().id(),"a","tenant_id",tenant,sel);var access=devices.predicate(actor.user().id(),"d.tenant_id","d.id");var tree=scope.devices(actor.user().id(),"d",tenant,sel);var args=new ArrayList<Object>();args.addAll(units.bindings());args.addAll(access.bindings());args.addAll(tree.bindings());String idFilter=idFilter("a",selectedIds,args);String where=" WHERE 1=1"+units.and()+access.and()+tree.and()+idFilter;for(String key:List.of("event_kind","source_kind","status"))if(!blank(f.get(key))){where+=" AND a."+key+"=?";args.add(f.get(key));}return jdbc.query("SELECT a.id,a.event_kind,a.source_kind,a.source_id,a.device_id,a.contact_id,a.channel,a.attempt_no,a.status,a.provider_code,a.provider_id,a.created_at,a.finished_at FROM notification_provider_attempts a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id"+where+timeFilter(f,args,"a.created_at")+" ORDER BY a.created_at DESC,a.id LIMIT 10001",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),Objects.toString(rs.getString(5),""),Objects.toString(rs.getString(6),""),rs.getString(7),Integer.toString(rs.getInt(8)),rs.getString(9),Objects.toString(rs.getString(10),""),Objects.toString(rs.getString(11),""),Objects.toString(rs.getObject(12),""),Objects.toString(rs.getObject(13),"")),args.toArray());}
    private List<List<String>> audit(AuthService.Identity actor,String tenant,ScopeFilter.Selection selection,Map<String,String> f,List<String> selectedIds){
        if(selection.empty())return List.of();
        var filter=AuditRecordFilter.parse(f.get("action"),f.get("outcome"),null,null,f.get("occurred_from"),f.get("occurred_to"));
        var authorization=scope.units(actor.user().id(),"a","tenant_id",null,selection);var permitted=authorization.sql();var bindings=new ArrayList<Object>(authorization.bindings());
        if(tenant!=null){permitted+=" AND a.tenant_id=?";bindings.add(tenant);}
        var plan=filter.sql(new ScopeFilter.Clause(permitted,List.copyOf(bindings)));var queryBindings=new ArrayList<Object>(plan.bindings());String selected=idFilter("a",selectedIds,queryBindings);
        return jdbc.query("SELECT "+AuditRecordProjection.COLUMNS+" FROM audit_log a JOIN tenants t ON t.id=a.tenant_id"+plan.where()+selected+" ORDER BY a.occurred_at DESC,a.id ASC LIMIT 10001",(rs,n)->{var row=AuditRecordProjection.row(rs);return List.of(row.id(),row.tenantId(),row.tenantName(),row.occurredAt().toString(),row.action(),row.outcome(),Objects.toString(row.actorRef(),""),row.actorReferenceState(),Objects.toString(row.targetRef(),""),row.targetReferenceState());},queryBindings.toArray());
    }
    private List<List<String>> inbox(AuthService.Identity actor,String tenant,ScopeFilter.Selection sel,Map<String,String> f,List<String> selectedIds){var unit=scope.units(actor.user().id(),"m","tenant_id",tenant,sel);var args=new ArrayList<Object>();String where=" WHERE m.user_id=?";args.add(actor.user().id());if(tenant!=null){where+=" AND m.tenant_id=?";args.add(tenant);}args.addAll(unit.bindings());where+=unit.and();String idFilter=idFilter("m",selectedIds,args);where+=idFilter;if(!blank(f.get("status"))){where+=" AND m.status=?";args.add(f.get("status"));}return jdbc.query("SELECT m.id,m.kind,m.title,m.body,m.status,m.created_at,m.read_at,m.handled_at,m.revision FROM user_messages m"+where+" ORDER BY m.created_at DESC,m.id LIMIT 10001",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),Objects.toString(rs.getObject(6),""),Objects.toString(rs.getObject(7),""),Objects.toString(rs.getObject(8),""),Long.toString(rs.getLong(9))),args.toArray());}
    private static String idFilter(String alias,List<String> ids,List<Object> args){if(ids==null)return "";args.addAll(ids);return " AND "+alias+".id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";}
    private static void validateFilters(String kind,Map<String,String> filters){
        if(kind.equals("MONITORING"))choice(filters,"history_only",Set.of("true","false"));
        if(kind.equals("EVENTS")){choice(filters,"kind",Set.of("HEARTBEAT","ALARM","FAULT","OFFLINE","PARAM","OTHER"));choice(filters,"status",Set.of("NORMALIZED","DUPLICATE","PENDING_DECODER","FAILED","NOT_APPLIED","APPLIED","STALE","REJECTED"));}
        if(kind.equals("FAULTS"))choice(filters,"handling_state",Set.of("UNRECORDED","UNHANDLED","HANDLED","ACTIVE"));
        if(kind.equals("ALARMS")){choice(filters,"physical_state",Set.of("FIRE","NORMAL"));choice(filters,"response_state",Set.of("UNHANDLED","HANDLED"));choice(filters,"conclusion",Set.of("TRUE_FIRE","FALSE_ALARM","TEST","DEVICE_ALARM","UNREVIEWED"));}
        if(kind.equals("NOTIFICATION_ATTEMPTS")){choice(filters,"event_kind",Set.of("FIRE","FAULT"));choice(filters,"source_kind",Set.of("ALARM","RAW_FAULT","LEGACY_FAULT"));choice(filters,"status",Set.of("PENDING","SENT","FAILED","UNKNOWN","SUPPRESSED","RESOURCE_UNAVAILABLE"));}
        if(kind.equals("MY_NOTIFICATIONS"))choice(filters,"status",Set.of("UNREAD","READ","HANDLED"));
        String start=kind.equals("FAULTS")?"from":kind.equals("MONITORING")?"observed_from":"occurred_from",end=kind.equals("FAULTS")?"to":kind.equals("MONITORING")?"observed_to":"occurred_to";
        if(!blank(filters.get(start))&&!blank(filters.get(end))&&!date(filters.get(start)).isBefore(date(filters.get(end))))throw ApiError.bad("开始时间必须早于结束时间");
    }
    private static void choice(Map<String,String> filters,String key,Set<String> choices){String value=filters.get(key);if(!blank(value)&&!choices.contains(value))throw ApiError.bad(key+"筛选无效");}
    private static String timeFilter(Map<String,String> filters,List<Object> args,String column){
        var sql=new StringBuilder();
        if(!blank(filters.get("occurred_from"))){sql.append(" AND ").append(column).append(">=?");args.add(date(filters.get("occurred_from")));}
        if(!blank(filters.get("occurred_to"))){sql.append(" AND ").append(column).append("<?");args.add(date(filters.get("occurred_to")));}
        return sql.toString();
    }
    private static OffsetDateTime date(String value){try{return OffsetDateTime.parse(value);}catch(Exception e){throw ApiError.bad("时间筛选格式无效");}}
    private static String extract(String json,String key){try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path(key).asText("");}catch(Exception e){return "";}}
    private static boolean blank(String value){return value==null||value.isBlank();}
}
