package com.fireplatform.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.alarm.AlarmLifecycle;
import com.fireplatform.deviceservice.DeviceRegistrationSourceLinked;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.legacyingest.LegacyEventSink;
import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HikvisionIngestion {
    private static final Map<String,String> SIGNAL_KEYS=Map.of("snrValue","snr","rsrpValue","rsrp","pciValue","pci");
    private static final Map<String,String> SIGNAL_LABELS=Map.of("snr","信噪比（来源值）","rsrp","RSRP（来源值）","pci","PCI");
    private record Raw(String id,String appliedDevice,String applyError) { }
    private record Device(String id,String tenant,String name,String address) { }
    private record RawPayload(String payload,String ingress) { }
    /** An already-observed source occurrence and the local device that owns it, independent of any later transfer. */
    private record SourceOwner(String externalDevice,String localId,Device device) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectReader sourceJson;
    private final HikvisionCatalog catalog;
    private final LegacyEventSink sink;
    private final AlarmLifecycle alarms;
    private final CoordinationFence coordination;
    private final HikvisionConfigService configuration;
    public HikvisionIngestion(JdbcTemplate jdbc,ObjectMapper json,HikvisionCatalog catalog,LegacyEventSink sink,
            AlarmLifecycle alarms,CoordinationFence coordination,HikvisionConfigService configuration) {
        this.jdbc=jdbc;this.json=json;this.catalog=catalog;this.sink=sink;this.alarms=alarms;this.coordination=coordination;
        this.configuration=configuration;
        this.sourceJson=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @EventListener
    public void sourceLinked(DeviceRegistrationSourceLinked event) {
        if ("HIKVISION_FIRECLOUD".equals(event.driverKey())) replay(event.externalDeviceId(), event.linkedAt());
    }
    @Transactional
    public void accept(HikvisionClient.Stream stream,JsonNode records,OffsetDateTime received) {
        accept(stream,records,received,configuration.refreshRuntime().modelScope(),"HIKVISION_POLL");
    }
    @Transactional
    public void accept(HikvisionClient.Stream stream,JsonNode records,OffsetDateTime received,String modelScope) {
        accept(stream,records,received,modelScope,"HIKVISION_POLL");
    }
    @Transactional
    public void accept(HikvisionClient.Stream stream,JsonNode records,OffsetDateTime received,String modelScope,String ingress) {
        // Alarm, fault, monitor and native event facts share the same creation order fence; the fence is
        // transaction-local and reentrant for the callback worker that already holds it.
        if(stream==HikvisionClient.Stream.ALARM||stream==HikvisionClient.Stream.FAULT||stream==HikvisionClient.Stream.DEVICE_EVENT
            ||stream==HikvisionClient.Stream.MONITOR||stream==HikvisionClient.Stream.HISTORY_MONITOR) coordination.lock();
        for(JsonNode record:records) store(stream,record,received,modelScope,ingress);
    }
    private void store(HikvisionClient.Stream stream,JsonNode record,OffsetDateTime received,String modelScope,String ingress) {
        boolean monitor=stream==HikvisionClient.Stream.MONITOR||stream==HikvisionClient.Stream.HISTORY_MONITOR;
        boolean flat=monitor&&flatMonitor(record);
        String resource=text(record,stream==HikvisionClient.Stream.DEVICE||stream==HikvisionClient.Stream.STATUS||stream==HikvisionClient.Stream.DEVICE_EVENT?"deviceID":stream==HikvisionClient.Stream.SENSOR?"sensorID":"resourceID");
        if(!inConfiguredModelScope(stream,record,resource,modelScope))return;
        String payload=record.toString(),sha=hash(payload);
        String key=switch(stream){case FAULT->text(record,"faultID");case ALARM->text(record,"alarmID");case UNIT->text(record,"unitID");case DEVICE_EVENT->{String id=nativeEventId(record);yield id==null?null:Objects.toString(resource,"")+"|"+id;}default->resource;};
        OffsetDateTime observed=sourceObserved(stream,record,flat);
        if(monitor)key=resource+":"+Objects.toString(observed,"")+":"+sha;
        if(stream==HikvisionClient.Stream.DEVICE_EVENT&&key==null)key="HASH:"+sha;
        if(key==null||key.length()>200)key="HASH:"+sha;
        if(stream==HikvisionClient.Stream.DEVICE||stream==HikvisionClient.Stream.SENSOR) {
            discover(stream,record,received);
            jdbc.update("UPDATE hikvision_discovered_devices SET source_created_at=? WHERE external_id=? AND source_created_at IS NULL",timestamp(record,"createTime"),resource);
        }
        if(monitor&&resource!=null&&observed!=null) {
            jdbc.update("UPDATE hikvision_discovered_devices SET last_observed_at=?,online_state=COALESCE(?,online_state) WHERE external_id=? AND (last_observed_at IS NULL OR last_observed_at<=?)",observed,text(record,"onlineState"),resource,observed);
            for(JsonNode reading:monitorReadings(record,flat)) {
                String code=text(reading,"monitorType"),circuit=text(reading,"circuitNo"),metric=catalog.metric(code,circuit);
                if(metric!=null) {
                    String label=catalog.metricLabel(code,circuit);
                    if(jdbc.update("UPDATE hikvision_metric_catalog SET label=?,last_seen_at=? WHERE metric_key=?",label,received,metric)==0)
                        jdbc.update("INSERT INTO hikvision_metric_catalog(metric_key,source_code,label,last_seen_at) VALUES(?,?,?,?)",metric,code,label,received);
                }
            }
        }
        if(stream==HikvisionClient.Stream.STATUS&&resource!=null) {
            OffsetDateTime updated=timestamp(record,"updateTime");
            jdbc.update("UPDATE hikvision_discovered_devices SET source_status=?,source_updated_at=? WHERE external_id=? AND (source_updated_at IS NULL OR source_updated_at<=?)",text(record,"deviceStatus"),updated,resource,updated);
        }
        var existing=jdbc.query("SELECT id,applied_device_id,apply_error FROM hikvision_raw_records WHERE stream_kind=? AND external_key=? AND payload_sha256=? AND ingress=?",
            (r,n)->new Raw(r.getString(1),r.getString(2),r.getString(3)),stream.name(),key,sha,ingress);
        String rawId;
        if(existing.isEmpty()) {
            rawId=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO hikvision_raw_records(id,stream_kind,external_key,external_device_id,payload_sha256,payload_json,observed_at,received_at,ingress) VALUES(?,?,?,?,?,?,?,?,?)",rawId,stream.name(),key,resource,sha,payload,observed,received,ingress);
        } else {
            rawId=existing.getFirst().id();
            if(existing.getFirst().appliedDevice()!=null||"PRE_OWNERSHIP_HISTORY".equals(existing.getFirst().applyError())||"OWNERSHIP_TIME_UNKNOWN".equals(existing.getFirst().applyError()))return;
        }
        // The flat monitor shape is documented for push only; it is never inferred for polling payloads.
        if(flat&&!"HIKVISION_PUSH".equals(ingress)){error(rawId,"FLAT_MONITOR_REQUIRES_PUSH");return;}
        apply(rawId,stream,record,resource,observed,received,ingress);
    }
    private boolean inConfiguredModelScope(HikvisionClient.Stream stream,JsonNode record,String resource,String required) {
        if(stream==HikvisionClient.Stream.UNIT)return true;
        if(stream==HikvisionClient.Stream.DEVICE)return required.equals(text(record,"deviceModel"));
        if(stream==HikvisionClient.Stream.SENSOR) {
            String model=text(record,"deviceModel"),parent=text(record,"deviceID");
            if(model!=null)return required.equals(model);
            return parent!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE external_id=? AND model=?",Long.class,parent,required)>0;
        }
        if(resource==null)return false;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE external_id=?",Long.class,resource)==0)
            return true; // Retain unknown-resource callbacks for replay after the exact add identity is bound.
        return jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE external_id=? AND model=?",Long.class,resource,required)>0;
    }
    private void discover(HikvisionClient.Stream stream,JsonNode record,OffsetDateTime received) {
        boolean sensor=stream==HikvisionClient.Stream.SENSOR;
        String id=text(record,sensor?"sensorID":"deviceID");if(id==null)return;
        String payload=record.toString();
        var prior=jdbc.queryForList("SELECT metadata_json FROM hikvision_discovered_devices WHERE external_id=?",String.class,id);
        String name=text(record,sensor?"sensorName":"deviceName");if(name==null)name=id;
        if(prior.isEmpty()) {
            jdbc.update("INSERT INTO hikvision_discovered_devices(external_id,resource_kind,parent_external_id,serial,name,model,device_type,system_type,source_status,source_updated_at,metadata_json,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,sensor?"SENSOR":"DEVICE",sensor?text(record,"deviceID"):null,text(record,sensor?"sensorSerial":"deviceSerial"),name,text(record,"deviceModel"),text(record,sensor?"sensorType":"deviceType"),text(record,"systemType"),text(record,"deviceStatus"),timestamp(record,"updateTime"),payload,received,received);
        } else if(!payload.equals(prior.getFirst())) {
            jdbc.update("UPDATE hikvision_discovered_devices SET serial=?,name=?,model=?,device_type=?,system_type=?,source_status=?,source_updated_at=?,metadata_json=?,last_seen_at=?,revision=revision+1 WHERE external_id=?",
                text(record,sensor?"sensorSerial":"deviceSerial"),name,text(record,"deviceModel"),text(record,sensor?"sensorType":"deviceType"),text(record,"systemType"),text(record,"deviceStatus"),timestamp(record,"updateTime"),payload,received,id);
        } else jdbc.update("UPDATE hikvision_discovered_devices SET last_seen_at=? WHERE external_id=?",received,id);
    }
    private void apply(String rawId,HikvisionClient.Stream stream,JsonNode record,String resource,OffsetDateTime observed,OffsetDateTime received,String ingress) {
        if(stream==HikvisionClient.Stream.UNIT||stream==HikvisionClient.Stream.DEVICE||stream==HikvisionClient.Stream.SENSOR||stream==HikvisionClient.Stream.STATUS)return;
        if(resource==null){error(rawId,"RESOURCE_ID_MISSING");return;}
        if(stream==HikvisionClient.Stream.DEVICE_EVENT){event(rawId,record,resource,observed,received);return;}
        if(stream==HikvisionClient.Stream.ALARM){alarm(rawId,record,resource,observed,received,ingress);return;}
        if(stream==HikvisionClient.Stream.FAULT){fault(rawId,record,resource,observed,received,ingress);return;}
        monitor(rawId,record,resource,observed,received,stream==HikvisionClient.Stream.MONITOR,ingress);
    }
    private Device currentOwner(String resource,boolean lock) {
        var linked=jdbc.query("SELECT d.id,d.tenant_id,d.name,d.address FROM hikvision_discovered_devices h JOIN devices d ON d.id=h.local_device_id WHERE h.external_id=? AND d.is_simulated=FALSE"+(lock?" FOR UPDATE":""),
            (r,n)->new Device(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),resource);
        return linked.isEmpty()?null:linked.getFirst();
    }
    /** Resolves the owner recorded when the source occurrence first arrived; never the current binding. */
    private SourceOwner alarmOwner(String sourceAlarmId) {
        var rows=jdbc.query("SELECT s.external_device_id,s.local_alarm_id,a.tenant_id,a.device_id,d.name,d.address FROM hikvision_alarm_state s LEFT JOIN alarms a ON a.id=s.local_alarm_id LEFT JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE s.source_alarm_id=?",
            (r,n)->new SourceOwner(r.getString(1),r.getString(2),r.getString(3)==null||r.getString(4)==null?null:new Device(r.getString(4),r.getString(3),r.getString(5),r.getString(6))),sourceAlarmId);
        if(rows.isEmpty())return null;
        return rows.getFirst();
    }
    private SourceOwner faultOwner(String sourceFaultId) {
        var rows=jdbc.query("SELECT s.external_device_id,s.normalized_event_id,d.tenant_id,d.id,d.name,d.address FROM hikvision_fault_state s LEFT JOIN legacy_ingest_events e ON e.id=s.normalized_event_id LEFT JOIN hikvision_raw_records r ON r.id=s.latest_raw_id LEFT JOIN devices d ON d.id=COALESCE(e.device_id,r.applied_device_id) WHERE s.source_fault_id=?",
            (r,n)->new SourceOwner(r.getString(1),null,r.getString(3)==null||r.getString(4)==null?null:new Device(r.getString(4),r.getString(3),r.getString(5),r.getString(6))),sourceFaultId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    /**
     * Transfers keep old observations with the original unit. Records without a source time, or with a
     * source time before the binding cutover, remain quarantined instead of appearing under the new owner.
     */
    private boolean ownershipAllows(String rawId,String resource,OffsetDateTime observed) {
        var rows=jdbc.query("SELECT event_history_from FROM hikvision_discovered_devices WHERE external_id=?",(r,n)->r.getObject(1,OffsetDateTime.class),resource);
        if(rows.isEmpty()||rows.getFirst()==null)return true;
        if(observed==null){error(rawId,"OWNERSHIP_TIME_UNKNOWN");return false;}
        if(observed.isBefore(rows.getFirst())){error(rawId,"PRE_OWNERSHIP_HISTORY");return false;}
        return true;
    }
    private void observeRealtimeReport(String ingress,String resource,Device owner,OffsetDateTime observed) {
        if(!"HIKVISION_PUSH".equals(ingress)||observed==null)return;
        // An occurrence can still accept history after transfer; only the exact current binding can claim liveness.
        jdbc.update("""
            UPDATE devices SET last_message_at=?
            WHERE id=? AND tenant_id=? AND is_simulated=FALSE AND (last_message_at IS NULL OR last_message_at<?)
              AND EXISTS (SELECT 1 FROM hikvision_discovered_devices h WHERE h.external_id=?
                AND h.local_device_id=devices.id AND (h.event_history_from IS NULL OR h.event_history_from<=?))
            """,observed,owner.id(),owner.tenant(),observed,resource,observed);
    }
    private void fault(String rawId,JsonNode record,String resource,OffsetDateTime observed,OffsetDateTime received,String ingress) {
        String id=text(record,"faultID"),code=text(record,"faultType");if(id==null||code==null){error(rawId,"FAULT_ID_OR_TYPE_MISSING");return;}
        if(observed==null){error(rawId,"FAULT_SOURCE_TIME_MISSING");return;}
        String state=text(record,"faultState");
        if(state!=null&&!"0".equals(state)&&!"1".equals(state)){error(rawId,"FAULT_STATE_UNKNOWN");return;}
        OffsetDateTime started=timestamp(record,"faultStartTime"),stopped=timestamp(record,"faultStopTime");
        OffsetDateTime first=started==null?observed:started;
        if(stopped!=null&&stopped.isBefore(first)){error(rawId,"FAULT_RECOVERY_TIME_INVALID");return;}
        if(started!=null&&started.isAfter(observed)){error(rawId,"FAULT_START_TIME_INVALID");return;}
        Boolean evidence=stopped!=null?Boolean.TRUE:"1".equals(state)?Boolean.TRUE:"0".equals(state)?Boolean.FALSE:null;
        SourceOwner prior=faultOwner(id);
        if(prior!=null&&!Objects.equals(prior.externalDevice(),resource)){error(rawId,"EXTERNAL_RESOURCE_MISMATCH");return;}
        Device device;
        if(prior!=null) {
            if(prior.device()==null){error(rawId,"ORIGINAL_OWNER_UNKNOWN");return;}
            device=prior.device();
        } else {
            device=currentOwner(resource,true);
            if(device==null){error(rawId,"PENDING_ASSIGNMENT");return;}
            if(!ownershipAllows(rawId,resource,observed))return;
        }
        // Battery, tamper, smoke chamber and smoke sensor are detector reports, not cloud/link faults.
        if(!Boolean.TRUE.equals(evidence) && switch(code) {
            case "300003","300004","300005","300116" -> true;
            default -> false;
        }) observeRealtimeReport(ingress,resource,device,observed);
        String event=null;
        if(prior==null) {
            ObjectNode params=json.createObjectNode();params.put("source","HIKVISION_FIRECLOUD").put("source_event_id",id).put("code",code).put("fault_label",catalog.faultLabel(code));
            writeEvent(rawId,device,resource,"FAULT","HIKVISION_FAULT",params,observed,received);
            var events=jdbc.queryForList("SELECT id FROM legacy_ingest_events WHERE message_id=? AND kind='FAULT'",String.class,rawId);
            if(!events.isEmpty())event=events.getFirst();
            jdbc.update("INSERT INTO hikvision_fault_state(source_fault_id,external_device_id,normalized_event_id,source_code,source_label,source_start_time,source_stop_time,source_recovered,latest_raw_id,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,resource,event,code,catalog.faultLabel(code),started,stopped,evidence,rawId,received);
            applied(rawId,device,received);
            return;
        }
        // Recorded occurrence: update only source facts. Never reopen/close fire, never create a second event.
        // Optional evidence is applied through separate SET clauses so no untyped NULL parameter reaches PostgreSQL.
        var sets=new ArrayList<String>();var args=new ArrayList<Object>();
        if(event!=null){sets.add("normalized_event_id=COALESCE(normalized_event_id,?)");args.add(event);}
        if(started!=null){sets.add("source_start_time=CASE WHEN source_start_time IS NULL OR source_start_time>? THEN ? ELSE source_start_time END");args.add(started);args.add(started);}
        if(stopped!=null){sets.add("source_stop_time=CASE WHEN source_stop_time IS NULL OR source_stop_time<? THEN ? ELSE source_stop_time END");args.add(stopped);args.add(stopped);}
        if(evidence!=null){sets.add("source_recovered=CASE WHEN COALESCE(source_recovered,FALSE)=TRUE THEN TRUE ELSE ? END");args.add(evidence);}
        sets.add("latest_raw_id=?");args.add(rawId);sets.add("updated_at=?");args.add(received);args.add(id);
        jdbc.update("UPDATE hikvision_fault_state SET "+String.join(",",sets)+" WHERE source_fault_id=?",args.toArray());
        appliedTo(rawId,device,received);
    }
    private void alarm(String rawId,JsonNode record,String resource,OffsetDateTime observed,OffsetDateTime received,String ingress) {
        String id=text(record,"alarmID"),code=text(record,"alarmType");
        if(id==null||code==null){error(rawId,"ALARM_ID_OR_TYPE_MISSING");return;}
        if(catalog.simulatedAlarm(code)){error(rawId,"SOURCE_SIMULATED_ALARM");return;}
        if(!catalog.fire(code)){error(rawId,"UNMAPPED_ALARM_TYPE:"+code);return;}
        String state=text(record,"alarmState");
        if(!"0".equals(state)&&!"1".equals(state)){error(rawId,"ALARM_STATE_UNKNOWN");return;}
        OffsetDateTime stopped=timestamp(record,"alarmStopTime");
        // A recovery without a source time has no causal proof; an ongoing fire without one is still real risk.
        if("1".equals(state)&&(observed==null||stopped==null||stopped.isBefore(observed))){error(rawId,"ALARM_RECOVERY_TIME_INVALID");return;}
        SourceOwner prior=alarmOwner(id);
        if(prior!=null&&!Objects.equals(prior.externalDevice(),resource)){error(rawId,"EXTERNAL_RESOURCE_MISMATCH");return;}
        Device device;
        if(prior!=null) {
            if(prior.device()==null||prior.localId()==null){error(rawId,"ORIGINAL_OWNER_UNKNOWN");return;}
            device=prior.device();
        } else {
            device=currentOwner(resource,true);
            if(device==null){error(rawId,"PENDING_ASSIGNMENT");return;}
            if(!ownershipAllows(rawId,resource,observed))return;
        }
        if("0".equals(state))observeRealtimeReport(ingress,resource,device,observed);
        String alarm=prior==null?null:prior.localId();
        if(alarm==null) {
            alarm=alarms.fire(device.tenant(),device.id(),device.name(),device.address(),observed);
            ObjectNode params=json.createObjectNode();params.put("source","HIKVISION_FIRECLOUD").put("source_event_id",id).put("source_alarm_type",code).put("source_alarm_label",catalog.alarmLabel(code));
            writeEvent(rawId,device,resource,"ALARM","HIKVISION_ALARM",params,observed,received);
            jdbc.update("INSERT INTO hikvision_alarm_state(source_alarm_id,external_device_id,local_alarm_id,source_code,source_start_time,source_stop_time,latest_raw_id,updated_at) VALUES(?,?,?,?,?,?,?,?)",id,resource,alarm,code,observed,stopped,rawId,received);
        }
        long occurrences=record.path("alarmNum").canConvertToLong()?record.path("alarmNum").asLong():1;
        OffsetDateTime latest=timestamp(record,"alarmTime");if(latest==null||observed!=null&&latest.isBefore(observed))latest=observed;
        jdbc.update("UPDATE alarms SET occurrence_count=CASE WHEN occurrence_count<? THEN ? ELSE occurrence_count END,last_event_at=CASE WHEN last_event_at IS NULL OR last_event_at<? THEN ? ELSE last_event_at END WHERE id=? AND tenant_id=? AND is_simulated=FALSE",Math.max(1,occurrences),Math.max(1,occurrences),latest,latest,alarm,device.tenant());
        if("1".equals(state)&&stopped!=null&&!stopped.isBefore(observed)) {
            alarms.recoverAlarm(device.tenant(),device.id(),alarm,stopped);
            jdbc.update("UPDATE hikvision_alarm_state SET source_stop_time=CASE WHEN source_stop_time IS NULL OR source_stop_time<? THEN ? ELSE source_stop_time END,latest_raw_id=?,updated_at=? WHERE source_alarm_id=?",stopped,stopped,rawId,received,id);
        } else jdbc.update("UPDATE hikvision_alarm_state SET latest_raw_id=?,updated_at=? WHERE source_alarm_id=?",rawId,received,id);
        jdbc.update("UPDATE devices SET physical_state=? WHERE id=?",alarms.hasActiveFire(device.tenant(),device.id())?"FIRE":"NORMAL",device.id());
        if(prior==null) { if(observed==null) appliedTo(rawId,device,received); else applied(rawId,device,received); } else appliedTo(rawId,device,received);
    }
    private void monitor(String rawId,JsonNode record,String resource,OffsetDateTime observed,OffsetDateTime received,boolean currentSnapshot,String ingress) {
        boolean flat=flatMonitor(record);
        if(flat&&!"HIKVISION_PUSH".equals(ingress)){error(rawId,"FLAT_MONITOR_REQUIRES_PUSH");return;}
        if(observed==null){error(rawId,"MONITOR_SOURCE_TIME_MISSING");return;}
        List<JsonNode> readings=monitorReadings(record,flat);if(readings.isEmpty()){error(rawId,"MONITOR_DATA_MISSING");return;}
        Device device=currentOwner(resource,true);
        if(device==null){error(rawId,"PENDING_ASSIGNMENT");return;}
        if(!ownershipAllows(rawId,resource,observed))return;
        ObjectNode params=json.createObjectNode(),metadata=params.putObject("source_metrics");
        params.put("source","HIKVISION_FIRECLOUD").put("source_event_id",resource+":"+observed+":"+hash(readings.toString()));
        for(JsonNode reading:readings) {
            String code=text(reading,"monitorType"),circuit=text(reading,"circuitNo"),metric=catalog.metric(code,circuit);if(metric==null)continue;
            if("400002".equals(code))signal(reading,params,metadata,received);
            String value=text(reading,"monitorValue");BigDecimal number;
            try{number=new BigDecimal(value);}catch(RuntimeException e){continue;}
            params.put(metric,number);String unit=text(reading,"monitorUnit"),label=catalog.metricLabel(code,circuit);
            ObjectNode meta=metadata.putObject(metric);meta.put("source_code",code).put("label",label);if(unit!=null)meta.put("unit",unit);if(circuit!=null)meta.put("circuit_no",circuit);
        }
        if(metadata.isEmpty()){error(rawId,"MONITOR_NUMERIC_READINGS_MISSING");return;}
        // Flat pushes are real measurements: one history sample that also feeds the current projection.
        // Polling snapshots stay current-only and historical measurements stay history-only.
        boolean history=!currentSnapshot||flat;
        if(history&&!retainUnrecordedMonitor(device.tenant(),device.id(),observed,params,metadata)){applied(rawId,device,received);return;}
        if(!history && jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM hikvision_raw_records raw JOIN hikvision_raw_records applied
              ON applied.stream_kind=raw.stream_kind AND applied.external_key=raw.external_key
              AND applied.payload_sha256=raw.payload_sha256 AND applied.ingress<>raw.ingress
              WHERE raw.id=? AND applied.applied_device_id=?)
            """,Boolean.class,rawId,device.id())){applied(rawId,device,received);return;}
        writeEvent(rawId,device,resource,history?"PARAM":"OTHER",history?"HIKVISION_MONITOR":"HIKVISION_CURRENT_MONITOR",params,observed,received);
        // Snapshots and historical measurements are separate projections, never double-counted.
        if(params.has("battery")&&"%".equals(params.path("source_metrics").path("battery").path("unit").asText())) {
            BigDecimal percent=params.path("battery").decimalValue();
            if(percent.signum()>=0&&percent.compareTo(BigDecimal.valueOf(100))<=0) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM real_device_battery_observations WHERE tenant_id=? AND device_id=? AND observed_at=? AND battery_percent=?",Long.class,device.tenant(),device.id(),observed,percent)==0)
                    jdbc.update("INSERT INTO real_device_battery_observations(id,tenant_id,device_id,observed_at,battery_percent,source_kind,recorded_at) VALUES(?,?,?,?,?,'HIKVISION_FIRECLOUD',?)",UUID.randomUUID().toString(),device.tenant(),device.id(),observed,percent,received);
                OffsetDateTime newest=jdbc.queryForObject("SELECT MAX(observed_at) FROM real_device_battery_observations WHERE tenant_id=? AND device_id=?",OffsetDateTime.class,device.tenant(),device.id());
                if(!observed.isBefore(newest)) {
                    Integer integer;try{integer=percent.intValueExact();}catch(ArithmeticException e){integer=null;}
                    jdbc.update("UPDATE devices SET battery_percent=? WHERE id=?",integer,device.id());
                }
            }
        }
        applied(rawId,device,received);
    }
    /**
     * Cross-transport dedup for the same real measurement: an identical metric/value/unit/circuit at the
     * same source time is recorded once, while any genuine change keeps its own sample.
     */
    private boolean retainUnrecordedMonitor(String tenant,String deviceId,OffsetDateTime observed,ObjectNode params,ObjectNode metadata) {
        var metrics=new ArrayList<String>();
        params.fieldNames().forEachRemaining(key->{JsonNode value=params.get(key);if(!"source".equals(key)&&!"source_event_id".equals(key)&&!"source_metrics".equals(key)&&value.isNumber())metrics.add(key);});
        if(metrics.isEmpty())return false;
        var rows=jdbc.queryForList("SELECT params_json FROM legacy_ingest_events WHERE tenant_id=? AND device_id=? AND kind='PARAM' AND method='HIKVISION_MONITOR' AND observed_at=?",String.class,tenant,deviceId,observed);
        if(rows.isEmpty())return true;
        var existing=new ArrayList<JsonNode>();
        for(String raw:rows){JsonNode parsed=parseParams(raw);if(parsed!=null)existing.add(parsed);}
        var recorded=new ArrayList<String>();
        for(String metric:metrics) {
            BigDecimal value=params.get(metric).decimalValue();
            String unit=metadata.path(metric).path("unit").asText(null),circuit=circuit(metadata.path(metric).path("circuit_no").asText(null));
            for(JsonNode prior:existing) {
                JsonNode priorValue=prior.get(metric);
                if(priorValue==null||!priorValue.isNumber()||priorValue.decimalValue().compareTo(value)!=0)continue;
                if(!Objects.equals(unit,prior.path("source_metrics").path(metric).path("unit").asText(null)))continue;
                if(!Objects.equals(circuit,circuit(prior.path("source_metrics").path(metric).path("circuit_no").asText(null))))continue;
                recorded.add(metric);break;
            }
        }
        for(String metric:recorded){params.remove(metric);metadata.remove(metric);}
        return metrics.size()>recorded.size();
    }
    private static String circuit(String value){return value==null||value.isBlank()||"0".equals(value)?null:value;}
    private void event(String rawId,JsonNode record,String resource,OffsetDateTime observed,OffsetDateTime received) {
        String occurrence=jdbc.queryForObject("SELECT external_key FROM hikvision_raw_records WHERE id=?",String.class,rawId);
        var recorded=jdbc.query("SELECT applied_device_id FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT' AND external_key=? AND applied_device_id IS NOT NULL ORDER BY received_at,id LIMIT 1",
            (r,n)->r.getString(1),occurrence);
        if(!recorded.isEmpty()) {
            // Keep every changed source payload, but one occurrence and its original owner.
            appliedTo(rawId,recorded.getFirst(),received);
            return;
        }
        long excluded=jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT' AND external_key=? AND apply_error IN ('PRE_OWNERSHIP_HISTORY','OWNERSHIP_TIME_UNKNOWN')",Long.class,occurrence);
        if(excluded>0){error(rawId,"PRE_OWNERSHIP_HISTORY");return;}
        if(!ownershipAllows(rawId,resource,observed))return;
        Device device=currentOwner(resource,true);
        if(device==null){error(rawId,"PENDING_ASSIGNMENT");return;}
        String sourceId=nativeEventId(record),code=text(record,"eventType");
        if(sourceId==null||code==null){error(rawId,"EVENT_ID_OR_TYPE_MISSING");return;}
        ObjectNode params=json.createObjectNode();
        params.put("source","HIKVISION_FIRECLOUD").put("source_event_id",sourceId).put("source_code",code)
            .put("resource_serial",Objects.toString(text(record,"resourceSerial"),""));
        String state=text(record,"state");
        if(state!=null)params.put("source_state",state);
        writeEvent(rawId,device,resource,"OTHER","HIKVISION_DEVICE_EVENT",params,observed,received);
        applied(rawId,device,received);
    }
    private void writeEvent(String rawId,Device device,String resource,String kind,String method,ObjectNode params,OffsetDateTime observed,OffsetDateTime received) {
        RawPayload source=jdbc.queryForObject("SELECT payload_json,ingress FROM hikvision_raw_records WHERE id=?",(r,n)->new RawPayload(r.getString(1),r.getString(2)),rawId);
        String payload=source.payload(),ingress=source.ingress();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_messages WHERE id=?",Long.class,rawId)==0)
            jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,duplicate_count) VALUES(?,?,?,'hikvision_firecloud',?,?,?,?,?,?,?,?,'NORMALIZED',0)",rawId,device.tenant(),ingress,rawId,resource,device.id(),payload,hash(payload),payload.getBytes(StandardCharsets.UTF_8).length,received,observed);
        String measurementKind="HIKVISION_CURRENT_MONITOR".equals(method)?"PARAM":kind;
        sink.accept(rawId,new LegacyNormalizedEvent(device.tenant(),device.id(),resource,"hikvision_firecloud",measurementKind,observed,null,false,List.of(new LegacyNormalizedEvent.LegacyEvent(kind,method,params.toString(),observed))));
    }
    private void signal(JsonNode reading,ObjectNode params,ObjectNode metadata,OffsetDateTime received) {
        JsonNode remark=reading.get("remark");if(remark==null)return;
        try{if(remark.isTextual())remark=sourceJson.readTree(remark.asText());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){return;}
        if(remark==null||!remark.isObject())return;
        for(var field:SIGNAL_KEYS.entrySet()) {
            JsonNode value=remark.get(field.getKey());if(value==null||!value.isNumber())continue;
            String metric=field.getValue(),label=SIGNAL_LABELS.get(metric),code="400002:"+field.getKey();
            params.put(metric,value.decimalValue());
            metadata.putObject(metric).put("source_code",code).put("label",label);
            if(jdbc.update("UPDATE hikvision_metric_catalog SET label=?,last_seen_at=? WHERE metric_key=?",label,received,metric)==0)
                jdbc.update("INSERT INTO hikvision_metric_catalog(metric_key,source_code,label,last_seen_at) VALUES(?,?,?,?)",metric,code,label,received);
        }
    }
    private void applied(String rawId,Device device,OffsetDateTime received){jdbc.update("UPDATE hikvision_raw_records SET applied_device_id=?,applied_at=?,apply_error=NULL WHERE id=?",device.id(),received,rawId);jdbc.update("UPDATE devices SET integration_state='CONNECTED' WHERE id=?",device.id());}
    /** Historical occurrence updates bind to the original owner without claiming connectivity for it. */
    private void appliedTo(String rawId,String deviceId,OffsetDateTime received){jdbc.update("UPDATE hikvision_raw_records SET applied_device_id=?,applied_at=?,apply_error=NULL WHERE id=?",deviceId,received,rawId);}
    private void appliedTo(String rawId,Device device,OffsetDateTime received){appliedTo(rawId,device.id(),received);}
    private void error(String rawId,String code){jdbc.update("UPDATE hikvision_raw_records SET apply_error=? WHERE id=?",code,rawId);}
    @Transactional
    public void replay(String resource,OffsetDateTime received) {
        coordination.lock();
        var records=jdbc.query("SELECT id,stream_kind,payload_json,observed_at,received_at,ingress FROM hikvision_raw_records WHERE external_device_id=? AND applied_at IS NULL AND (apply_error IS NULL OR apply_error NOT IN ('PRE_OWNERSHIP_HISTORY','OWNERSHIP_TIME_UNKNOWN')) ORDER BY observed_at NULLS FIRST,received_at,id",
            (r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getObject(4,OffsetDateTime.class),r.getObject(5,OffsetDateTime.class),r.getString(6)},resource);
        for(var record:records) {
            JsonNode payload;try{payload=sourceJson.readTree((String)record[2]);}catch(com.fasterxml.jackson.core.JsonProcessingException e){error((String)record[0],"INVALID_STORED_PAYLOAD");continue;}
            HikvisionClient.Stream stream=HikvisionClient.Stream.valueOf((String)record[1]);
            // Reuse the stored source time and provenance; replay never invents either.
            apply((String)record[0],stream,payload,resource,(OffsetDateTime)record[3],(OffsetDateTime)record[4],(String)record[5]);
        }
    }
    /** FireCloud's actual response uses enventID; retain eventID compatibility for documented payloads. */
    private static String nativeEventId(JsonNode record) {
        String id=text(record,"enventID");
        return id!=null?id:text(record,"eventID");
    }
    private static boolean flatMonitor(JsonNode record){return text(record,"monitorType")!=null&&!record.path("data").isArray();}
    private static List<JsonNode> monitorReadings(JsonNode record,boolean flat) {
        if(flat)return List.of(record);
        JsonNode data=record.path("data");if(!data.isArray())return List.of();
        var readings=new ArrayList<JsonNode>(data.size());
        for(JsonNode reading:data)if(reading.isObject())readings.add(reading);
        return readings;
    }
    private static OffsetDateTime sourceObserved(HikvisionClient.Stream stream,JsonNode record,boolean flat) {
        return switch(stream) {
            case FAULT->{OffsetDateTime time=timestamp(record,"faultTime");yield time!=null?time:timestamp(record,"faultStartTime");}
            case ALARM->timestamp(record,"alarmStartTime");
            case MONITOR,HISTORY_MONITOR->timestamp(record,flat?"happenTime":"monitorTime");
            case DEVICE_EVENT->timestamp(record,"happenTime");
            default->timestamp(record,"updateTime");
        };
    }
    private JsonNode parseParams(String value){try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){return null;}}
    public static String text(JsonNode record,String name){JsonNode value=record.get(name);if(value==null||!value.isValueNode()||value.isNull())return null;String text=value.asText().trim();return text.isEmpty()?null:text;}
    public static OffsetDateTime timestamp(JsonNode record,String name){String value=text(record,name);if(value==null)return null;try{return OffsetDateTime.parse(value);}catch(RuntimeException e){return null;}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}}
}
