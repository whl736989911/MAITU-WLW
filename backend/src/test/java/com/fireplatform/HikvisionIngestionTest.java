package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.duty.DutyQueries;
import com.fireplatform.duty.DutyTelemetryQueries;
import com.fireplatform.hikvision.HikvisionClient.Stream;
import com.fireplatform.hikvision.HikvisionIngestion;
import com.fireplatform.shared.ScopeFilter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"app.roles=api","fire.hikvision.enabled=false","spring.datasource.url=jdbc:h2:mem:hikvisionfacts;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test") @Transactional
class HikvisionIngestionTest {
    @Autowired HikvisionIngestion ingestion;
    @Autowired com.fireplatform.hikvision.HikvisionService hikvision;
    @Autowired com.fireplatform.hikvision.HikvisionCallbackService callback;
    @Autowired DutyTelemetryQueries telemetry;
    @Autowired DutyQueries duty;
    @Autowired com.fireplatform.device.DeviceEventHistoryService eventHistory;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final OffsetDateTime observed=OffsetDateTime.parse("2026-11-08T10:00:00+08:00");
    String tenant,device,actor;
    @BeforeEach void setup(){
        tenant=UUID.randomUUID().toString();device=UUID.randomUUID().toString();actor=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)",tenant,"Integration fixture");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES(?,?,?,?,?,?,'UNKNOWN',FALSE)",device,tenant,"Integration fixture",device,"800235","");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES(?,?,?,'PLATFORM',?,FALSE,TRUE)",actor,actor,"Projection fixture","unused-projection-fixture");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES(?,?)",actor,tenant);
        N04TestScopes.grantExisting(jdbc);
        accept(Stream.DEVICE,Map.of("deviceID","source-1","deviceSerial","fixture-serial","deviceName","Integration fixture","deviceType","600002","deviceModel","800235","createTime",observed.toString()));
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=? WHERE external_id='source-1'",device);
    }
    @Test void disabledOrInvalidCallbackCredentialReturnsNotFoundWithoutReceiptWrites(){
        byte[] body="{\"fps\":{\"msgList\":[]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->callback.receive("0".repeat(64),body))
            .isInstanceOf(com.fireplatform.shared.ApiError.class)
            .satisfies(error->assertThat(((com.fireplatform.shared.ApiError)error).status).isEqualTo(404));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts",Long.class)).isZero();
    }
    void accept(Stream stream,Map<String,?> record){ingestion.accept(stream,json.valueToTree(List.of(record)),observed.plusMinutes(1));}
    Map<String,Object> alarm(String id,String state,OffsetDateTime start,OffsetDateTime stop){
        var row=new java.util.HashMap<String,Object>();row.put("alarmID",id);row.put("resourceID","source-1");row.put("alarmType","200001");row.put("alarmStartTime",start.toString());row.put("alarmState",state);if(stop!=null)row.put("alarmStopTime",stop.toString());return row;
    }
    @Test void sourceAlarmRecoveryOnlyClosesItsOwnAlarmAndFaultRecoveryNeverClosesFire(){
        accept(Stream.ALARM,alarm("alarm-A","0",observed,null));
        accept(Stream.ALARM,alarm("alarm-B","0",observed.plusSeconds(1),null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isEqualTo(2L);
        var fault=Map.of("faultID","fault-A","resourceID","source-1","faultType","300004","faultStartTime",observed.toString(),"faultStopTime",observed.plusSeconds(5).toString(),"processDescription","故障自动恢复");
        accept(Stream.FAULT,fault);accept(Stream.FAULT,fault);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='FAULT'",Long.class,device)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND physical_state='FIRE'",Long.class,device)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT source_label FROM hikvision_fault_state WHERE source_fault_id='fault-A'",String.class)).isEqualTo("防拆故障");
        accept(Stream.ALARM,alarm("alarm-A","1",observed,observed.plusSeconds(10)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND physical_state='FIRE'",Long.class,device)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,device)).isEqualTo("FIRE");
    }
    @Test void snapshotsDoNotDoubleCountHistoryAndKeepSourceUnitsAndFractionalBattery(){
        var temperature=Map.of("monitorType","400006","monitorValue","24.4","monitorUnit","℃","circuitNo",0);
        accept(Stream.HISTORY_MONITOR,Map.of("resourceID","source-1","monitorTime",observed.toString(),"data",List.of(temperature)));
        accept(Stream.MONITOR,Map.of("resourceID","source-1","monitorTime",observed.toString(),"data",List.of(temperature,
            Map.of("monitorType","400016","monitorValue",60,"monitorUnit","%RH"),
            Map.of("monitorType","400001","monitorValue","37.25","monitorUnit","%"),
            Map.of("monitorType","400002","monitorValue",3,"monitorUnit","","remark","{\"snrValue\":29,\"rsrpValue\":-67,\"pciValue\":239}"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM'",Long.class,device)).isEqualTo(1L);
        var current=telemetry.current(actor,tenant,ScopeFilter.Selection.all(),null,1,20).items().getFirst();
        assertThat(current.readings()).anySatisfy(r->{assertThat(r.metric()).isEqualTo("humidity");assertThat(r.unit()).isEqualTo("%RH");assertThat(r.value()).isEqualTo("60");});
        assertThat(current.readings()).anySatisfy(r->{assertThat(r.metric()).isEqualTo("snr");assertThat(r.value()).isEqualTo("29");assertThat(r.unit()).isNull();assertThat(r.unitStatus()).isEqualTo("UNKNOWN");});
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM real_device_battery_observations WHERE device_id=?",java.math.BigDecimal.class,device)).isEqualByComparingTo("37.25");
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id=?",Integer.class,device)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,device)).isEqualTo(observed);
        assertThat(jdbc.queryForObject("SELECT DISTINCT ingress FROM legacy_ingest_messages WHERE device_id=?",String.class,device)).isEqualTo("HIKVISION_POLL");
    }
    @Test void currentOnlySourceObservationAdvancesDeviceTimeButNeverThePollingTime(){
        accept(Stream.MONITOR,Map.of("resourceID","source-1","monitorTime",observed.toString(),"data",List.of(Map.of("monitorType","400006","monitorValue","24.4","monitorUnit","℃"))));
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,device)).isEqualTo(observed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM'",Long.class,device)).isZero();
        assertThat(telemetry.current(actor,tenant,ScopeFilter.Selection.all(),"temperature",1,20).items().getFirst().readings().getFirst().value()).isEqualTo("24.4");
    }
    @Test void missingSourceAlarmTimeIsRetainedAsUnknownTimeRiskWithoutInventingPollTime(){
        accept(Stream.ALARM,Map.of("alarmID","untimed","resourceID","source-1","alarmType","200001","alarmState","0"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT source_time_known FROM alarms WHERE device_id=?",Boolean.class,device)).isFalse();
        assertThat(jdbc.queryForObject("SELECT platform_recorded_at IS NOT NULL FROM alarms WHERE device_id=?",Boolean.class,device)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id=?",OffsetDateTime.class,device)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM real_device_battery_observations WHERE device_id=?",Long.class,device)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='ALARM' AND observed_at IS NULL",Long.class,device)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE stream_kind='ALARM'",String.class)).isNull();
    }
    @Test void untimedAlarmRecoveryUsesItsSourceIdentityAndKeepsOtherFiresActive(){
        push(Stream.ALARM,Map.of("alarmID","untimed-recovery","resourceID","source-1","alarmType","200001","alarmState","0"));
        String alarmId=jdbc.queryForObject("SELECT local_alarm_id FROM hikvision_alarm_state WHERE source_alarm_id='untimed-recovery'",String.class);
        push(Stream.ALARM,alarm("other-active-fire","0",observed.plusSeconds(20),null));
        push(Stream.ALARM,alarm("untimed-recovery","1",observed,observed.plusSeconds(10)));
        push(Stream.ALARM,alarm("untimed-recovery","1",observed,observed.plusSeconds(10)));
        assertThat(jdbc.queryForObject("SELECT physical_state FROM alarms WHERE id=?",String.class,alarmId)).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT response_state FROM alarms WHERE id=?",String.class,alarmId)).isEqualTo("UNHANDLED");
        assertThat(jdbc.queryForObject("SELECT source_time_known FROM alarms WHERE id=?",Boolean.class,alarmId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries WHERE alarm_id=? AND action='ALARM_RECOVERED'",Long.class,alarmId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND physical_state='FIRE'",Long.class,device)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id=?",String.class,device)).isEqualTo("FIRE");
    }
    @Test void modelScopeRejectsKnownOutsideResourcesAndDefersUnresolvedEvents(){
        var outside=Map.of("deviceID","other-model","deviceSerial","other-serial","deviceName","Out of scope","deviceType","600002","deviceModel","800236","createTime",observed.toString());
        accept(Stream.DEVICE,outside);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE external_id='other-model'",Long.class)).isZero();
        var started=Map.of("alarmID","other-model-alarm","resourceID","other-model","alarmType","200001","alarmStartTime",observed.toString(),"alarmState","0");
        var event=Map.of("eventID","other-model-event","deviceID","other-model","eventType","100209","happenTime",observed.toString());
        // Previously discovered during a different model scope: these records are known not to match.
        ingestion.accept(Stream.DEVICE,json.valueToTree(List.of(outside)),observed.plusMinutes(1),"800236");
        accept(Stream.ALARM,started);
        accept(Stream.DEVICE_EVENT,event);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE stream_kind IN ('ALARM','DEVICE_EVENT') AND external_device_id='other-model'",Long.class)).isZero();

        // An unresolved identity may have arrived before pairing; keep the raw records without deriving facts.
        accept(Stream.ALARM,Map.of("alarmID","unresolved-alarm","resourceID","unresolved-device","alarmType","200001","alarmStartTime",observed.toString(),"alarmState","0"));
        accept(Stream.DEVICE_EVENT,Map.of("eventID","unresolved-event","deviceID","unresolved-device","eventType","100209","happenTime",observed.toString()));
        assertThat(jdbc.queryForList("SELECT apply_error FROM hikvision_raw_records WHERE external_device_id='unresolved-device'",String.class)).containsExactly("PENDING_ASSIGNMENT","PENDING_ASSIGNMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id=?",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE tenant_id=?",Long.class,tenant)).isZero();
    }
    @Test void vendorEventsDeduplicateByOccurrenceButKeepDistinctEventsAtSameTime(){
        accept(Stream.DEVICE_EVENT,Map.of("enventID","vendor-event-1","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",0));
        accept(Stream.DEVICE_EVENT,Map.of("enventID","vendor-event-1","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",0));
        accept(Stream.DEVICE_EVENT,Map.of("enventID","vendor-event-1","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",1));
        accept(Stream.DEVICE_EVENT,Map.of("eventID","vendor-event-1","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",1));
        accept(Stream.DEVICE_EVENT,Map.of("eventID","vendor-event-2","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT'",Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT' AND (applied_device_id IS NULL OR apply_error IS NOT NULL)",Long.class)).isZero();
        var rows=eventHistory.list(actor,tenant,device,1,20,null,null,null).items();
        assertThat(rows).hasSize(2).allSatisfy(row->{
            assertThat(row.kind()).isEqualTo("OTHER");
            assertThat(row.eventCode()).isEqualTo("100209");
            assertThat(row.eventLabel()).isEqualTo("开机");
            assertThat(row.observedAt()).isEqualTo(observed);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isZero();
    }
    @Test void pushAndPollCopiesOfOneEventNormalizeOnceAndRetainTransportProvenance(){
        var pushed=json.valueToTree(List.of(Map.of("eventID","transport-shared","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",0)));
        var polled=json.valueToTree(List.of(Map.of("enventID","transport-shared","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",1)));
        ingestion.accept(Stream.DEVICE_EVENT,pushed,observed.plusMinutes(1),"800235","HIKVISION_PUSH");
        ingestion.accept(Stream.DEVICE_EVENT,polled,observed.plusMinutes(2),"800235","HIKVISION_POLL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT' AND external_key='source-1|transport-shared'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT ingress FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT' AND external_key='source-1|transport-shared'",String.class)).containsExactlyInAnyOrder("HIKVISION_PUSH","HIKVISION_POLL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE method='HIKVISION_DEVICE_EVENT' AND tenant_id=?",Long.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ingress FROM legacy_ingest_messages WHERE vendor_device_id='source-1' AND payload_raw LIKE '%transport-shared%'",String.class)).isEqualTo("HIKVISION_PUSH");
        assertThat(eventHistory.list(actor,tenant,device,1,20,null,null,null).items()).hasSize(1);
    }
    @Test void unknownEventWithMissingTimeRemainsVisibleAndUnmapped(){
        accept(Stream.DEVICE_EVENT,Map.of("eventID","vendor-unknown","deviceID","source-1","eventType","999999"));
        var row=eventHistory.list(actor,tenant,device,1,20,null,null,null).items().getFirst();
        assertThat(row.eventCode()).isEqualTo("999999");
        assertThat(row.eventLabel()).isEqualTo("未映射事件");
        assertThat(row.observedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT observed_at FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT'",OffsetDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isZero();
    }
    @Test void firstAssociationReplaysPreviouslyUnboundDeviceHistory(){
        accept(Stream.DEVICE,Map.of("deviceID","source-unbound","deviceSerial","unbound-serial","deviceName","Unbound fixture","deviceType","600002","deviceModel","800235","createTime",observed.toString()));
        accept(Stream.DEVICE_EVENT,Map.of("eventID","unbound-event","deviceID","source-unbound","eventType","100209","happenTime",observed.toString()));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key LIKE '%unbound-event'",String.class)).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(eventHistory.list(actor,tenant,device,1,20,null,null,null).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isZero();
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=NULL WHERE external_id='source-1'");
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=? WHERE external_id='source-unbound'",device);
        ingestion.replay("source-unbound",observed.plusMinutes(3));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='source-unbound|unbound-event'",String.class)).isNull();
        assertThat(eventHistory.list(actor,tenant,device,1,20,null,null,null).items()).singleElement().satisfies(row->{
            assertThat(row.eventLabel()).isEqualTo("开机");
            assertThat(row.observedAt()).isEqualTo(observed);
        });
    }
    @Test void ownershipCutoffKeepsDelayedAndUntimedOldEventsOutOfTheNewUnit(){
        accept(Stream.DEVICE_EVENT,Map.of("eventID","old-before","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",0));
        String targetTenant=UUID.randomUUID().toString(),targetDevice=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)",targetTenant,"Transfer fixture");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES(?,?,?,?,?,?,'UNKNOWN',FALSE)",targetDevice,targetTenant,"Transferred fixture",targetDevice,"800235","");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES(?,?)",actor,targetTenant);
        N04TestScopes.grantExisting(jdbc);
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,event_history_from=? WHERE external_id='source-1'",targetDevice,observed.plusMinutes(2));
        ingestion.accept(Stream.DEVICE_EVENT,json.valueToTree(List.of(
            Map.of("eventID","old-before","deviceID","source-1","eventType","100209","happenTime",observed.toString(),"state",1),
            Map.of("eventID","delayed-old","deviceID","source-1","eventType","100211","happenTime",observed.toString()),
            Map.of("eventID","untimed-after-transfer","deviceID","source-1","eventType","100211"),
            Map.of("eventID","new-occurrence","deviceID","source-1","eventType","100209","happenTime",observed.plusMinutes(3).toString())
        )),observed.plusMinutes(4),"800235","HIKVISION_PUSH");
        assertThat(eventHistory.list(actor,targetTenant,targetDevice,1,20,null,null,null).items()).singleElement().satisfies(row->assertThat(row.observedAt()).isEqualTo(observed.plusMinutes(3)));
        assertThat(eventHistory.list(actor,tenant,device,1,20,null,null,null).items()).hasSize(1);
        assertThat(jdbc.queryForList("SELECT applied_device_id FROM hikvision_raw_records WHERE external_key='source-1|old-before'",String.class)).containsExactlyInAnyOrder(device,device);
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='source-1|delayed-old'",String.class)).isEqualTo("PRE_OWNERSHIP_HISTORY");
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='source-1|untimed-after-transfer'",String.class)).isEqualTo("OWNERSHIP_TIME_UNKNOWN");
        assertThat(hikvision.status().quarantinedEvents()).isEqualTo(2);
        assertThat(hikvision.status().mappingErrors()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id IN (?,?)",Long.class,device,targetDevice)).isZero();
    }
    @Test void statusSeparatesPendingAssignmentQuarantineAndGenuineMappingFailures(){
        jdbc.update("UPDATE hikvision_discovered_devices SET event_history_from=? WHERE external_id='source-1'",observed.plusMinutes(2));
        accept(Stream.DEVICE,Map.of("deviceID","pending-source","deviceSerial","pending-serial","deviceName","Pending fixture","deviceModel","800235"));
        ingestion.accept(Stream.DEVICE_EVENT,json.valueToTree(List.of(
            Map.of("eventID","before-cutover","deviceID","source-1","eventType","100209","happenTime",observed.toString()),
            Map.of("eventID","unknown-time","deviceID","source-1","eventType","100209"),
            Map.of("eventID","missing-type","deviceID","source-1","happenTime",observed.plusMinutes(3).toString()),
            Map.of("eventID","pending-owner","deviceID","pending-source","eventType","100209","happenTime",observed.plusMinutes(3).toString()),
            Map.of("eventID","valid-current","deviceID","source-1","eventType","100209","happenTime",observed.plusMinutes(3).toString())
        )),observed.plusMinutes(4));
        var status=hikvision.status();
        assertThat(status.unassignedEvents()).isEqualTo(1);
        assertThat(status.quarantinedEvents()).isEqualTo(2);
        assertThat(status.mappingErrors()).isEqualTo(1);
        assertThat(eventHistory.list(actor,tenant,device,1,20,null,null,null).items()).singleElement()
            .satisfies(event->assertThat(event.observedAt()).isEqualTo(observed.plusMinutes(3)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE stream_kind='DEVICE_EVENT'",Long.class)).isEqualTo(5);
    }
    void push(Stream stream,Map<String,Object>... records){ingestion.accept(stream,json.valueToTree(List.of(records)),observed.plusMinutes(1),"800235","HIKVISION_PUSH");}
    Map<String,Object> fault(String id,OffsetDateTime time,OffsetDateTime start,OffsetDateTime stop,String state){
        var row=new java.util.HashMap<String,Object>();row.put("faultID",id);row.put("resourceID","source-1");row.put("faultType","300004");
        if(time!=null)row.put("faultTime",time.toString());if(start!=null)row.put("faultStartTime",start.toString());if(stop!=null)row.put("faultStopTime",stop.toString());if(state!=null)row.put("faultState",state);
        return row;
    }
    Map<String,Object> flatMonitor(String code,String value,String unit,OffsetDateTime time){
        var row=new java.util.HashMap<String,Object>();row.put("resourceID","source-1");row.put("monitorType",code);row.put("monitorValue",value);
        if(unit!=null)row.put("monitorUnit",unit);row.put("circuitNo","0");row.put("happenTime",time.toString());
        return row;
    }
    @Test void faultPushKeepsRecoveryEvidenceIndependentOfTimestampAndLateUnrepairedDoesNotDowngrade(){
        push(Stream.FAULT,fault("fault-push-1",observed.plusMinutes(1),null,null,"1"));
        assertThat(jdbc.queryForObject("SELECT source_recovered FROM hikvision_fault_state WHERE source_fault_id='fault-push-1'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT source_stop_time FROM hikvision_fault_state WHERE source_fault_id='fault-push-1'",OffsetDateTime.class)).isNull();
        var listed=duty.faults(actor,tenant,ScopeFilter.Selection.all(),null,null,null,null,1,20).items();
        assertThat(listed).singleElement().satisfies(event->{
            assertThat(event.sourceFaultId()).isEqualTo("fault-push-1");
            assertThat(event.sourceRecovered()).isTrue();
            assertThat(event.sourceRecoveredAt()).isNull();
        });
        push(Stream.FAULT,fault("fault-push-1",observed.plusMinutes(2),null,null,"0"));
        assertThat(jdbc.queryForObject("SELECT source_recovered FROM hikvision_fault_state WHERE source_fault_id='fault-push-1'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='FAULT' AND device_id=?",Long.class,device)).isEqualTo(1L);
        push(Stream.FAULT,fault("fault-push-1",observed.plusMinutes(3),null,observed.plusMinutes(4),null));
        assertThat(jdbc.queryForObject("SELECT source_recovered FROM hikvision_fault_state WHERE source_fault_id='fault-push-1'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT source_stop_time FROM hikvision_fault_state WHERE source_fault_id='fault-push-1'",OffsetDateTime.class)).isEqualTo(observed.plusMinutes(4));
        push(Stream.FAULT,fault("fault-push-3",observed.plusMinutes(5),null,null,"0"));
        assertThat(jdbc.queryForObject("SELECT source_recovered FROM hikvision_fault_state WHERE source_fault_id='fault-push-3'",Boolean.class)).isFalse();
        push(Stream.FAULT,fault("fault-push-4",null,null,null,"0"));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='fault-push-4'",String.class)).isEqualTo("FAULT_SOURCE_TIME_MISSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_fault_state WHERE source_fault_id='fault-push-4'",Long.class)).isZero();
    }
    @Test void flatMonitorPushRecordsOneMeasuredSampleAndMergesCurrentWithoutErasingSiblings(){
        accept(Stream.MONITOR,Map.of("resourceID","source-1","monitorTime",observed.toString(),"data",List.of(Map.of("monitorType","400006","monitorValue","24.4","monitorUnit","℃","circuitNo","0"))));
        push(Stream.MONITOR,flatMonitor("400016","7.46","%RH",observed.plusMinutes(1)));
        var signal=flatMonitor("400002","3","",observed.plusMinutes(2));signal.put("remark","{\"snrValue\":29,\"rsrpValue\":-67}");
        push(Stream.MONITOR,signal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM' AND method='HIKVISION_MONITOR'",Long.class,device)).isEqualTo(2L);
        var history=telemetry.history(actor,tenant,ScopeFilter.Selection.all(),"humidity",null,null,1,20);
        assertThat(history.items()).singleElement().satisfies(sample->assertThat(sample.readings()).singleElement().satisfies(reading->{
            assertThat(reading.value()).isEqualTo("7.46");
            assertThat(reading.unit()).isEqualTo("%RH");
        }));
        var current=telemetry.current(actor,tenant,ScopeFilter.Selection.all(),null,1,20).items().getFirst();
        assertThat(current.readings()).anySatisfy(reading->{assertThat(reading.metric()).isEqualTo("temperature");assertThat(reading.value()).isEqualTo("24.4");assertThat(reading.unit()).isEqualTo("℃");});
        assertThat(current.readings()).anySatisfy(reading->{assertThat(reading.metric()).isEqualTo("humidity");assertThat(reading.value()).isEqualTo("7.46");assertThat(reading.unit()).isEqualTo("%RH");});
        assertThat(current.readings()).anySatisfy(reading->{assertThat(reading.metric()).isEqualTo("snr");assertThat(reading.value()).isEqualTo("29");assertThat(reading.unitStatus()).isEqualTo("UNKNOWN");});
        // The same real observation arriving through polling history must not duplicate the sample.
        accept(Stream.HISTORY_MONITOR,Map.of("resourceID","source-1","monitorTime",observed.plusMinutes(1).toString(),"data",List.of(Map.of("monitorType","400016","monitorValue","7.4600","monitorUnit","%RH","circuitNo","0"))));
        push(Stream.MONITOR,flatMonitor("400016","7.46","%RH",observed.plusMinutes(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM' AND method='HIKVISION_MONITOR'",Long.class,device)).isEqualTo(2L);
        // A genuinely changed value or unit at the same source time keeps its own sample.
        push(Stream.MONITOR,flatMonitor("400016","7.47","%RH",observed.plusMinutes(1)));
        push(Stream.MONITOR,flatMonitor("400016","7.47","%",observed.plusMinutes(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM' AND method='HIKVISION_MONITOR'",Long.class,device)).isEqualTo(4L);
    }
    @Test void transferKeepsExistingOccurrenceUpdatesAndDelayedObservationsWithTheOriginalOwner(){
        push(Stream.ALARM,alarm("push-alarm-old","0",observed,null));
        push(Stream.FAULT,fault("push-fault-old",observed,null,null,"0"));
        String targetTenant=UUID.randomUUID().toString(),targetDevice=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)",targetTenant,"Push transfer fixture");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES(?,?,?,?,?,?,'UNKNOWN',FALSE)",targetDevice,targetTenant,"Transferred push fixture",targetDevice,"800235","");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES(?,?)",actor,targetTenant);
        N04TestScopes.grantExisting(jdbc);
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,event_history_from=? WHERE external_id='source-1'",targetDevice,observed.plusMinutes(2));
        push(Stream.ALARM,alarm("push-alarm-old","1",observed,observed.plusMinutes(5)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND physical_state='FIRE'",Long.class,device)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,targetDevice)).isZero();
        assertThat(jdbc.queryForObject("SELECT r.applied_device_id FROM hikvision_alarm_state s JOIN hikvision_raw_records r ON r.id=s.latest_raw_id WHERE s.source_alarm_id='push-alarm-old'",String.class)).isEqualTo(device);
        push(Stream.FAULT,fault("push-fault-old",observed.plusMinutes(1),null,null,"1"));
        assertThat(jdbc.queryForObject("SELECT source_recovered FROM hikvision_fault_state WHERE source_fault_id='push-fault-old'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT r.applied_device_id FROM hikvision_fault_state s JOIN hikvision_raw_records r ON r.id=s.latest_raw_id WHERE s.source_fault_id='push-fault-old'",String.class)).isEqualTo(device);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='FAULT' AND device_id=?",Long.class,targetDevice)).isZero();
        push(Stream.ALARM,alarm("push-alarm-new","0",observed.plusMinutes(3),null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND physical_state='FIRE'",Long.class,targetDevice)).isEqualTo(1L);
        push(Stream.ALARM,alarm("push-alarm-stale","0",observed.plusMinutes(1),null));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='push-alarm-stale'",String.class)).isEqualTo("PRE_OWNERSHIP_HISTORY");
        push(Stream.FAULT,fault("push-fault-stale",observed.plusMinutes(1),null,null,"0"));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='push-fault-stale'",String.class)).isEqualTo("PRE_OWNERSHIP_HISTORY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_fault_state WHERE source_fault_id='push-fault-stale'",Long.class)).isZero();
        push(Stream.MONITOR,flatMonitor("400016","7.46","%RH",observed.plusMinutes(1)));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE stream_kind='MONITOR' AND observed_at=?",String.class,observed.plusMinutes(1))).isEqualTo("PRE_OWNERSHIP_HISTORY");
        push(Stream.MONITOR,flatMonitor("400016","7.46","%RH",observed.plusMinutes(3)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM' AND method='HIKVISION_MONITOR'",Long.class,targetDevice)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM' AND method='HIKVISION_MONITOR'",Long.class,device)).isZero();
    }
    @Test void alarmPushWithoutStateStaysDiagnosticAndWithoutStartIsRetainedAsUnknownTimeRisk(){
        push(Stream.ALARM,Map.of("alarmID","push-no-state","resourceID","source-1","alarmType","200001","alarmStartTime",observed.toString()));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='push-no-state'",String.class)).isEqualTo("ALARM_STATE_UNKNOWN");
        push(Stream.ALARM,Map.of("alarmID","push-no-start","resourceID","source-1","alarmType","200001","alarmState","0"));
        assertThat(jdbc.queryForObject("SELECT apply_error FROM hikvision_raw_records WHERE external_key='push-no-start'",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=? AND source_time_known=FALSE",Long.class,device)).isEqualTo(1L);
        push(Stream.ALARM,alarm("shared-alarm","0",observed,null));
        var polled=alarm("shared-alarm","0",observed,null);polled.put("alarmNum",2);
        ingestion.accept(Stream.ALARM,json.valueToTree(List.of(polled)),observed.plusMinutes(2),"800235","HIKVISION_POLL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id=?",Long.class,device)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND method='HIKVISION_ALARM'",Long.class,device)).isEqualTo(2L);
    }
    @Test void preV67HistoryObservationIsNotDuplicatedByEquivalentFlatPushAndCurrentKeepsOtherMetrics(){
        String temperatureMessage=UUID.randomUUID().toString(),humidityMessage=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,duplicate_count) VALUES(?,?,'HIKVISION_POLL','hikvision_firecloud',?,?,?,?,?,?,?,?,'NORMALIZED',0)",
            temperatureMessage,tenant,temperatureMessage,"source-1",device,"{}","0".repeat(64),2,observed.plusMinutes(1),observed);
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,duplicate_count) VALUES(?,?,'HIKVISION_POLL','hikvision_firecloud',?,?,?,?,?,?,?,?,'NORMALIZED',0)",
            humidityMessage,tenant,humidityMessage,"source-1",device,"{}","0".repeat(64),2,observed.plusMinutes(1),observed.minusMinutes(1));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,?,'PARAM','HIKVISION_MONITOR',?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(),tenant,temperatureMessage,device,
            "{\"temperature\":24.4000,\"source\":\"HIKVISION_FIRECLOUD\",\"source_event_id\":\"old-temperature\",\"source_metrics\":{\"temperature\":{\"source_code\":\"400006\",\"label\":\"温度\",\"unit\":\"℃\",\"circuit_no\":\"0\"}}}",observed);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,?,'PARAM','HIKVISION_MONITOR',?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(),tenant,humidityMessage,device,
            "{\"humidity\":56.97,\"source\":\"HIKVISION_FIRECLOUD\",\"source_event_id\":\"old-humidity\",\"source_metrics\":{\"humidity\":{\"source_code\":\"400016\",\"label\":\"湿度\",\"unit\":\"%RH\",\"circuit_no\":\"0\"}}}",observed.minusMinutes(1));
        push(Stream.MONITOR,flatMonitor("400006","24.4","℃",observed));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id=? AND kind='PARAM' AND method='HIKVISION_MONITOR'",Long.class,device)).isEqualTo(2L);
        var current=telemetry.current(actor,tenant,ScopeFilter.Selection.all(),null,1,20).items().getFirst();
        assertThat(current.readings()).anySatisfy(reading->{assertThat(reading.metric()).isEqualTo("temperature");assertThat(reading.value()).isEqualTo("24.4");assertThat(reading.unit()).isEqualTo("℃");});
        assertThat(current.readings()).anySatisfy(reading->{assertThat(reading.metric()).isEqualTo("humidity");assertThat(reading.value()).isEqualTo("56.97");assertThat(reading.unit()).isEqualTo("%RH");});
    }
}
