package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.bulkio.ObservationDataTransferDomain;
import com.fireplatform.identity.AuthService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:observation-transfer-domain;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class ObservationDataTransferDomainTest {
    private static final String INITIAL="Initial-Observation-Transfer-2026!", PASSWORD="Changed-Observation-Transfer-2026!";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){registry.add("APP_BOOTSTRAP_USERNAME",()->"admin");registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL);}
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder encoder;
    @Autowired ObservationDataTransferDomain domain;
    private AuthService.Identity actor;

    @BeforeEach void reset(){
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM user_messages");
        jdbc.update("DELETE FROM user_permissions WHERE user_id LIKE 'other-%'");
        jdbc.update("DELETE FROM user_tenant_grants WHERE user_id LIKE 'other-%'");
        jdbc.update("DELETE FROM app_users WHERE id LIKE 'other-%'");
        for(String table:List.of("notification_provider_attempts","duty_fault_handlings","hikvision_fault_state","alarms","legacy_ingest_events","legacy_ingest_messages","raw_messages","active_source_bindings","source_bindings","ingestion_sources","user_device_scope_devices","user_device_scopes","user_tenant_grants","devices","tenants","audit_log"))jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=TRUE WHERE id='bootstrap-admin'",encoder.encode(INITIAL));
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Observation A'),('tenant-b','Observation B')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES('device-a','tenant-a','Device A','observation-a','model','A','NORMAL',FALSE),('device-b','tenant-b','Device B','observation-b','model','B','NORMAL',FALSE),('device-c','tenant-a','Device C','observation-c','model','C','NORMAL',FALSE)");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc);
        var login=auth.login(new AuthService.LoginRequest("admin",INITIAL,"management"),"observation-transfer-login");
        auth.changePassword(auth.authenticate(login.accessToken()),new AuthService.ChangePasswordRequest(INITIAL,PASSWORD),"observation-transfer-password");
        actor=auth.authenticate(auth.login(new AuthService.LoginRequest("admin",PASSWORD,"management"),"observation-transfer-current").accessToken());
    }

    @Test void monitoringExportsMeasuredValuesAndSourceTimestampsWithoutRawPayload(){
        var at=OffsetDateTime.parse("2026-11-09T10:00:00Z");
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,device_id,payload_raw,payload_sha256,payload_bytes,received_at,state) VALUES('generic-msg','tenant-a','HTTP_NOTIFY','aep_turbo','generic-key','device-a','{\"secret\":\"not-exported\"}',?,2,?,'NORMALIZED')","0".repeat(64),at.plusSeconds(4));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('generic-reading','tenant-a','generic-msg','device-a','PARAM','reported.param','{\"rssi\":-71,\"battery\":0}',?,?)",at,at);
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,device_id,payload_raw,payload_sha256,payload_bytes,received_at,state) VALUES('hik-msg','tenant-a','HTTP_NOTIFY','hikvision_firecloud','hik-key','device-a','{\"secret\":\"not-exported\"}',?,2,?,'NORMALIZED')","1".repeat(64),at.plusSeconds(8));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('hik-reading','tenant-a','hik-msg','device-a','PARAM','hikvision.monitor','{\"source\":\"HIKVISION_FIRECLOUD\",\"temperature\":24.4,\"source_metrics\":{\"temperature\":{\"unit\":\"C\"}}}',?,?)",at.plusSeconds(1),at.plusSeconds(1));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('legacy-reading','tenant-a','device-a','PARAM','legacy.param','{\"battery\":84}',?,?)",at.plusSeconds(2),at.plusSeconds(2));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('excluded-reading','tenant-a','device-c','PARAM','legacy.param','{\"rssi\":-20}',?,?)",at.plusSeconds(2),at.plusSeconds(2));
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES('bootstrap-admin','tenant-a','device-a')");
        var result=domain.export("MONITORING",actor,"tenant-a",Map.of("observed_from",at.minusSeconds(1).toString(),"observed_to",at.plusSeconds(10).toString()),null);
        assertThat(result.rows()).anySatisfy(row->{assertThat(row).contains("generic-reading","rssi","-71","aep_turbo");});
        assertThat(result.rows()).anySatisfy(row->{assertThat(row).contains("hik-reading","temperature","24.4","hikvision_firecloud","C");});
        assertThat(result.rows()).anySatisfy(row->{assertThat(row).contains("legacy-reading","battery","84");});
        assertThat(result.rows()).noneMatch(row->row.getFirst().equals("excluded-reading"));
        assertThat(result.rows().toString()).doesNotContain("not-exported");
        assertThat(result.rows()).noneMatch(row->row.get(1).equals("device-b"));
        var selected=domain.export("MONITORING",actor,"tenant-a",Map.of(),List.of("generic-reading"));
        assertThat(selected.rows()).extracting(row->row.get(4)).containsExactlyInAnyOrder("battery","rssi");
        assertThat(selected.rows()).anySatisfy(row->{assertThat(row).contains("battery","0");assertThat(OffsetDateTime.parse(row.get(7))).isEqualTo(at);assertThat(OffsetDateTime.parse(row.get(8))).isEqualTo(at.plusSeconds(4));});
    }

    @Test void deviceHistoryExportKeepsAllMatchingPagesAndExcludesSnapshotsAndPeerDevices(){
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        var entries=new java.util.ArrayList<Object[]>();
        for(int i=0;i<101;i++){
            entries.add(new Object[]{"own-"+i,"tenant-a","device-a","PARAM","legacy.param","{\"battery\":"+i+"}",at.plusNanos(i*1000L),at.plusDays(2)});
            entries.add(new Object[]{"peer-"+i,"tenant-a","device-c","PARAM","legacy.param","{\"battery\":900}",at.plusDays(1),at});
        }
        entries.add(new Object[]{"untimed-own","tenant-a","device-a","PARAM","legacy.param","{\"temperature\":0}",null,at});
        entries.add(new Object[]{"foreign-history","tenant-b","device-b","PARAM","legacy.param","{\"battery\":800}",at,at});
        entries.add(new Object[]{"current-only","tenant-a","device-a","OTHER","HIKVISION_CURRENT_MONITOR","{\"source\":\"HIKVISION_FIRECLOUD\",\"battery\":700}",at.plusDays(3),at});
        jdbc.batchUpdate("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,?,?,?,?,?)",entries);
        jdbc.update("UPDATE legacy_ingest_events SET source_platform='hikvision_firecloud' WHERE id='current-only'");
        var filters=Map.of("unit_ids","tenant-a","device_id","device-a","history_only","true");
        var history=domain.export("MONITORING",actor,null,filters,null).rows();
        assertThat(history).hasSize(102).allSatisfy(row->assertThat(row.get(1)).isEqualTo("device-a"));
        assertThat(history).extracting(row->row.getFirst()).contains("own-0","own-100","untimed-own").doesNotContain("current-only","peer-0","foreign-history");
        assertThat(history).filteredOn(row->row.getFirst().equals("untimed-own")).singleElement().satisfies(row->{assertThat(row.get(5)).isEqualTo("0");assertThat(row.get(7)).isEmpty();assertThat(row.get(8)).isEmpty();});
        var narrowed=new java.util.HashMap<>(filters);
        narrowed.put("observed_from",at.plusNanos(49_000).toString());narrowed.put("observed_to",at.plusNanos(51_000).toString());
        assertThat(domain.export("MONITORING",actor,null,narrowed,null).rows()).extracting(row->row.getFirst()).containsExactly("own-50","own-49");
        assertThatThrownBy(()->domain.export("MONITORING",actor,null,filters,List.of("peer-0"))).isInstanceOf(com.fireplatform.shared.ApiError.class);
        var withCurrent=new java.util.HashMap<>(filters);withCurrent.put("history_only","false");
        assertThat(domain.export("MONITORING",actor,null,withCurrent,null).rows()).allSatisfy(row->assertThat(row.get(1)).isEqualTo("device-a")).anySatisfy(row->assertThat(row.getFirst()).isEqualTo("current-only"));
    }

    @Test void deviceHistoryExportIntersectsUnitAndDeviceAuthorizationAndValidatesItsMode(){
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('scope-own','tenant-a','device-a','PARAM','legacy.param','{\"battery\":25}',?,?),('scope-peer','tenant-a','device-c','PARAM','legacy.param','{\"battery\":50}',?,?),('scope-foreign','tenant-b','device-b','PARAM','legacy.param','{\"battery\":75}',?,?)",at,at,at,at,at,at);
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES('bootstrap-admin','tenant-a','device-a')");
        assertThat(domain.export("MONITORING",actor,null,Map.of("unit_ids","tenant-a","device_id","device-a","history_only","true"),null).rows()).extracting(row->row.getFirst()).containsExactly("scope-own");
        assertThat(domain.export("MONITORING",actor,null,Map.of("unit_ids","tenant-a","device_id","device-c","history_only","true"),null).rows()).isEmpty();
        assertThat(domain.export("MONITORING",actor,null,Map.of("unit_ids","tenant-a","device_id","device-b","history_only","true"),null).rows()).isEmpty();
        assertThat(domain.export("MONITORING",actor,"tenant-b",Map.of("device_id","device-a","history_only","true"),null).rows()).isEmpty();
        assertThatThrownBy(()->domain.export("MONITORING",actor,null,Map.of("history_only","yes"),null)).isInstanceOf(com.fireplatform.shared.ApiError.class);
    }

    @Test void eventAlarmAndAuditExportsUseTheirProjectedStateFields(){
        var at=OffsetDateTime.parse("2026-11-09T10:00:00Z");String alarmId=UUID.randomUUID().toString(),auditId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,device_id,payload_raw,payload_sha256,payload_bytes,received_at,state) VALUES('event-msg','tenant-a','HTTP_NOTIFY','aep_turbo','event-key','device-a','{}',?,2,?,'NORMALIZED')","2".repeat(64),at);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('event-row','tenant-a','event-msg','device-a','ALARM','provider.alarm','{}',?,?)",at,at);
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,device_id,payload_raw,payload_sha256,payload_bytes,received_at,state) VALUES('hik-event-msg','tenant-a','HTTP_NOTIFY','hikvision_firecloud','hik-key','device-a','{}',?,2,?,'NORMALIZED')","3".repeat(64),at);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('hik-event-row','tenant-a','hik-event-msg','device-a','FAULT','HIKVISION_FAULT','{}',?,?)",at,at);
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,device_id,payload_raw,payload_sha256,payload_bytes,received_at,state) VALUES('legacy-event-msg','tenant-a','MQTT_IOT_UPLINK','legacy_platform','legacy-key','device-a','{}',?,2,?,'NORMALIZED')","4".repeat(64),at);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('legacy-event-row','tenant-a','legacy-event-msg','device-a','HEARTBEAT','legacy.heartbeat','{}',?,?)",at,at);
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision,is_simulated) VALUES(?, 'tenant-a','device-a','Device A','A','FIRE',?,'FIRE','UNHANDLED',0,FALSE)",alarmId,at);
        jdbc.update("INSERT INTO audit_log(id,actor_id,tenant_id,action,target_id,outcome,request_id,occurred_at) VALUES(?, 'bootstrap-admin','tenant-a','LOGIN',NULL,'SUCCESS',?,?)",auditId,UUID.randomUUID().toString(),at);
        var filters=Map.of("occurred_from",at.minusSeconds(1).toString(),"occurred_to",at.plusSeconds(1).toString());
        var events=domain.export("EVENTS",actor,"tenant-a",Map.of("device_id","device-a"),null).rows();
        assertThat(events).anySatisfy(row->{assertThat(row).contains("event-row","aep_turbo","provider.alarm","NORMALIZED");});
        assertThat(events).anySatisfy(row->{assertThat(row).contains("hik-event-row","hikvision_firecloud","HIKVISION_FAULT","NORMALIZED");});
        assertThat(events).anySatisfy(row->{assertThat(row).contains("legacy-event-row","legacy_platform","legacy.heartbeat");});
        var selected=domain.export("EVENTS",actor,"tenant-a",Map.of("device_id","device-a"),List.of("hik-event-row")).rows();
        assertThat(selected).extracting(row->row.getFirst()).containsExactly("hik-event-row");
        assertThat(domain.export("ALARMS",actor,"tenant-a",Map.of("physical_state","FIRE","keyword","device a"),null).rows()).anySatisfy(row->{assertThat(row).contains(alarmId,"FIRE","UNHANDLED");});
        assertThat(domain.export("AUDIT",actor,"tenant-a",filters,null).rows()).anySatisfy(row->{assertThat(row).contains(auditId,"LOGIN","SUCCESS");});
    }

    @Test void deviceAlarmExportKeepsAllMatchingPagesAndSourceTimeBoundaries(){
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        var entries=new java.util.ArrayList<Object[]>();
        for(int i=0;i<101;i++){
            entries.add(new Object[]{"own-alarm-"+i,"tenant-a","device-a",at.plusNanos(i*1000L),"FIRE","UNHANDLED",null,true,false});
            entries.add(new Object[]{"peer-alarm-"+i,"tenant-a","device-c",at.plusDays(1),"FIRE","UNHANDLED",null,true,false});
        }
        entries.add(new Object[]{"untimed-alarm","tenant-a","device-a",at.plusNanos(49_000),"FIRE","UNHANDLED",null,false,false});
        entries.add(new Object[]{"reviewed-alarm","tenant-a","device-a",at,"NORMAL","HANDLED","FALSE_ALARM",true,false});
        entries.add(new Object[]{"simulated-alarm","tenant-a","device-a",at,"FIRE","UNHANDLED",null,true,true});
        jdbc.batchUpdate("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,conclusion,source_time_known,is_simulated) VALUES(?,?,?,'Literal %_!','Same snapshot','FIRE',?,?,?,?,?,?)",entries);
        var filters=Map.of("unit_ids","tenant-a","device_id","device-a","keyword","%_!","physical_state","FIRE","response_state","UNHANDLED","conclusion","UNREVIEWED");
        var rows=domain.export("ALARMS",actor,null,filters,null).rows();
        assertThat(rows).hasSize(102).allSatisfy(row->assertThat(row.get(1)).isEqualTo("device-a"));
        assertThat(rows).extracting(List::getFirst).contains("own-alarm-0","own-alarm-100","untimed-alarm").doesNotContain("peer-alarm-0","reviewed-alarm","simulated-alarm");
        assertThat(rows).filteredOn(row->row.getFirst().equals("untimed-alarm")).singleElement().satisfies(row->{assertThat(row.get(3)).isEmpty();assertThat(row.get(8)).isEqualTo("SOURCE_TIME_MISSING");});
        var bounded=new java.util.HashMap<>(filters);
        bounded.put("occurred_from",at.plusNanos(49_000).toString());bounded.put("occurred_to",at.plusNanos(51_000).toString());
        assertThat(domain.export("ALARMS",actor,null,bounded,null).rows()).extracting(List::getFirst).containsExactly("own-alarm-50","own-alarm-49");
        assertThat(domain.export("ALARMS",actor,null,Map.of("device_id","device-a","conclusion","FALSE_ALARM"),null).rows()).extracting(List::getFirst).containsExactly("reviewed-alarm");
        assertThatThrownBy(()->domain.export("ALARMS",actor,null,filters,List.of("peer-alarm-0"))).isInstanceOf(com.fireplatform.shared.ApiError.class);
    }

    @Test void deviceAlarmExportIntersectsDeviceAndUnitAuthorization(){
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,is_simulated) VALUES('own-alarm','tenant-a','device-a','Same snapshot','Same address','FIRE',?,'FIRE','UNHANDLED',FALSE),('peer-alarm','tenant-a','device-c','Same snapshot','Same address','FIRE',?,'FIRE','UNHANDLED',FALSE),('foreign-alarm','tenant-b','device-b','Same snapshot','Same address','FIRE',?,'FIRE','UNHANDLED',FALSE)",at,at,at);
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES('bootstrap-admin','tenant-a','device-a')");
        assertThat(domain.export("ALARMS",actor,null,Map.of("unit_ids","tenant-a","device_id","device-a"),null).rows()).extracting(List::getFirst).containsExactly("own-alarm");
        assertThat(domain.export("ALARMS",actor,null,Map.of("unit_ids","tenant-a","device_id","device-c"),null).rows()).isEmpty();
        assertThat(domain.export("ALARMS",actor,null,Map.of("unit_ids","tenant-a","device_id","device-b"),null).rows()).isEmpty();
        assertThat(domain.export("ALARMS",actor,"tenant-b",Map.of("device_id","device-a"),null).rows()).isEmpty();
        assertThatThrownBy(()->domain.export("ALARMS",actor,null,Map.of("device_id","device-c"),List.of("peer-alarm"))).isInstanceOf(com.fireplatform.shared.ApiError.class);
    }

    @Test void faultExportKeepsFaultEventIdentityAndRejectsForeignSelection(){
        var at=OffsetDateTime.parse("2026-11-09T10:00:00Z");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at,business_code) VALUES('fault-a','tenant-a','device-a','FAULT','reported.fault','{\"deviceStateCode\":\"1002001\"}',?,?,?)",at,at,"1002001");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at,business_code) VALUES('fault-b','tenant-b','device-b','FAULT','reported.fault','{\"deviceStateCode\":\"1002001\"}',?,?,?)",at,at,"1002001");
        var filters=Map.of("from",at.minusSeconds(1).toString(),"to",at.plusSeconds(1).toString());
        var result=domain.export("FAULTS",actor,"tenant-a",filters,List.of("fault-a"));
        assertThat(result.rows()).hasSize(1);assertThat(result.rows().getFirst().getFirst()).isEqualTo("fault-a");
        assertThatThrownBy(()->domain.export("FAULTS",actor,"tenant-a",filters,List.of("fault-b"))).isInstanceOf(RuntimeException.class);
    }

    @Test void faultExportsKeepMissingHandlingDistinctFromExplicitFalseAndTrue(){
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('unrecorded-fault','tenant-a','device-a','FAULT','reported.fault','{}',?,?),('unhandled-fault','tenant-a','device-a','FAULT','reported.fault','{}',?,?),('handled-fault','tenant-a','device-a','FAULT','reported.fault','{}',?,?)",at,at,at,at,at,at);
        jdbc.update("INSERT INTO duty_fault_handlings(tenant_id,event_id,handled,updated_by,updated_at) VALUES('tenant-a','unhandled-fault',FALSE,'bootstrap-admin',?),('tenant-a','handled-fault',TRUE,'bootstrap-admin',?)",at,at);
        var expected=Map.of("unrecorded-fault","","unhandled-fault","false","handled-fault","true");
        var selected=List.of("unrecorded-fault","unhandled-fault","handled-fault");
        for(List<String> selection:java.util.Arrays.asList(null,selected)){
            var result=domain.export("FAULTS",actor,"tenant-a",Map.of("device_id","device-a"),selection);
            int column=result.columns().indexOf("handled");
            assertThat(result.rows().stream().collect(java.util.stream.Collectors.toMap(List::getFirst,row->row.get(column)))).isEqualTo(expected);
        }
        var states=Map.of("UNRECORDED","unrecorded-fault","UNHANDLED","unhandled-fault","HANDLED","handled-fault");
        for(var state:states.entrySet()){
            var filters=Map.of("device_id","device-a","handling_state",state.getKey());
            for(List<String> selection:java.util.Arrays.asList(null,List.of(state.getValue()))){
                var rows=domain.export("FAULTS",actor,"tenant-a",filters,selection).rows();
                assertThat(rows).singleElement().satisfies(row->{assertThat(row.getFirst()).isEqualTo(state.getValue());assertThat(row.getLast()).isEqualTo(expected.get(state.getValue()));});
            }
        }
        assertThat(domain.export("FAULTS",actor,"tenant-a",Map.of("handling_state","HANDLED"),null).rows()).extracting(List::getFirst).containsExactly("handled-fault");
        assertThatThrownBy(()->domain.export("FAULTS",actor,"tenant-a",Map.of("handling_state","HANDLED"),List.of("unrecorded-fault"))).isInstanceOf(com.fireplatform.shared.ApiError.class);
        assertThatThrownBy(()->domain.export("FAULTS",actor,"tenant-a",Map.of("handling_state","UNKNOWN"),null)).isInstanceOf(com.fireplatform.shared.ApiError.class);
    }

    @Test void deviceFaultExportKeepsAllPagesAndSourceTimeFiltersWithDeviceAuthorization(){
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        var entries=new java.util.ArrayList<Object[]>();
        for(int i=0;i<101;i++){
            entries.add(new Object[]{"own-fault-"+i,"tenant-a","device-a",at.plusNanos(i*1000L),at.plusDays(2)});
            entries.add(new Object[]{"peer-fault-"+i,"tenant-a","device-c",at.plusDays(1),at});
        }
        entries.add(new Object[]{"untimed-fault","tenant-a","device-a",null,at});
        entries.add(new Object[]{"foreign-fault","tenant-b","device-b",at,at});
        jdbc.batchUpdate("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'FAULT','reported.fault','{\"deviceStateCode\":\"1002003\"}',?,?)",entries);
        var filters=Map.of("unit_ids","tenant-a","device_id","device-a","keyword","reported.fault");
        var all=domain.export("FAULTS",actor,null,filters,null).rows();
        assertThat(all).hasSize(102).allSatisfy(row->assertThat(row.get(1)).isEqualTo("device-a"));
        assertThat(all).filteredOn(row->row.getFirst().equals("untimed-fault")).singleElement().satisfies(row->assertThat(row.get(8)).isEmpty());
        var bounded=new java.util.HashMap<>(filters);bounded.put("from",at.plusNanos(49_000).toString());bounded.put("to",at.plusNanos(51_000).toString());
        assertThat(domain.export("FAULTS",actor,null,bounded,null).rows()).extracting(List::getFirst).containsExactly("own-fault-50","own-fault-49");
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES('bootstrap-admin','tenant-a','device-a')");
        assertThat(domain.export("FAULTS",actor,null,Map.of("unit_ids","tenant-a","device_id","device-c"),null).rows()).isEmpty();
        assertThat(domain.export("FAULTS",actor,null,Map.of("unit_ids","tenant-a","device_id","device-b"),null).rows()).isEmpty();
    }

    @Test void notificationAttemptsPreserveFaultSourceIdentity(){
        jdbc.update("INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,occurrence_key,status,created_at,event_kind,source_kind,source_id) VALUES('fault-attempt','tenant-a',NULL,'device-a','contact-a','SMS',1,'INITIAL','UNKNOWN',CURRENT_TIMESTAMP,'FAULT','LEGACY_FAULT','fault-event')");
        var result=domain.export("NOTIFICATION_ATTEMPTS",actor,"tenant-a",Map.of("event_kind","FAULT","source_kind","LEGACY_FAULT"),null);
        assertThat(result.rows()).contains(List.of("fault-attempt","FAULT","LEGACY_FAULT","fault-event","device-a","contact-a","SMS","1","UNKNOWN","","",result.rows().getFirst().get(11),result.rows().getFirst().get(12)));
    }

    @Test void notificationInboxIsRestrictedToTheAuthenticatedUser(){
        String other="other-"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled) VALUES(?,?,'Other','CUSTOMER','tenant-a',?,FALSE,TRUE)",other,other,encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_messages(id,tenant_id,user_id,kind,title,body,status,created_at) VALUES('mine','tenant-a','bootstrap-admin','SYSTEM','Own','Inbox','UNREAD',CURRENT_TIMESTAMP),('other','tenant-a',?,'SYSTEM','Other','Inbox','UNREAD',CURRENT_TIMESTAMP)",other);
        var result=domain.export("MY_NOTIFICATIONS",actor,"tenant-a",Map.of(),null);
        assertThat(result.rows()).extracting(row->row.getFirst()).containsExactly("mine");
    }
    @Test void genericRawMessagesAreIndexedWithoutInventingAlarmFactsAndRespectFiltersAndSelection(){
        var at=OffsetDateTime.parse("2026-11-09T10:00:00Z");
        jdbc.update("INSERT INTO ingestion_sources(id,tenant_id,name,protocol,is_simulated,enabled,revision,token_hash,token_generation,created_at) VALUES('generic-source','tenant-a','Generic source','GENERIC_HTTP_JSON_V1',FALSE,TRUE,0,?,1,?),('foreign-source','tenant-b','Foreign source','GENERIC_HTTP_JSON_V1',FALSE,TRUE,0,?,1,?)","0".repeat(64),at,"1".repeat(64),at);
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,device_id,received_at,payload_utf8,payload_bytes,payload_sha256,binding_state,application_state,capture_provenance,occurred_at) VALUES('raw-own','tenant-a','generic-source','Generic source','own','vendor-a','device-a',?,X'7B7D',2,?,'BOUND','APPLIED','AUTHENTICATED_SOURCE',?),('raw-rejected','tenant-a','generic-source','Generic source','rejected','vendor-a','device-a',?,X'7B7D',2,?,'BOUND','REJECTED','AUTHENTICATED_SOURCE',?),('raw-other-device','tenant-a','generic-source','Generic source','other-device','vendor-c','device-c',?,X'7B7D',2,?,'BOUND','REJECTED','AUTHENTICATED_SOURCE',?),('raw-other-tenant','tenant-b','foreign-source','Foreign','other-tenant','vendor-b','device-b',?,X'7B7D',2,?,'BOUND','APPLIED','AUTHENTICATED_SOURCE',?)",at.plusSeconds(5),"5".repeat(64),at,at.plusSeconds(6),"6".repeat(64),at,at.plusSeconds(7),"7".repeat(64),at,at.plusSeconds(8),"8".repeat(64),at);
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES('bootstrap-admin','tenant-a','device-a')");
        var visible=domain.export("EVENTS",actor,"tenant-a",Map.of(),null).rows();
        assertThat(visible).anySatisfy(row->{assertThat(row).contains("GENERIC_MESSAGE:raw-own","OTHER","GENERIC_HTTP_JSON_V1","APPLIED");assertThat(row.get(6)).isEqualTo(at.toString());assertThat(row.get(7)).isEqualTo(at.plusSeconds(5).toString());});
        assertThat(visible).noneMatch(row->row.getFirst().equals("GENERIC_MESSAGE:raw-other-device")||row.getFirst().equals("GENERIC_MESSAGE:raw-other-tenant"));
        assertThat(domain.export("EVENTS",actor,"tenant-a",Map.of("status","REJECTED"),null).rows()).extracting(row->row.getFirst()).containsExactly("GENERIC_MESSAGE:raw-rejected");
        assertThat(domain.export("EVENTS",actor,"tenant-a",Map.of(),List.of("GENERIC_MESSAGE:raw-own")).rows()).extracting(row->row.getFirst()).containsExactly("GENERIC_MESSAGE:raw-own");
        assertThat(domain.export("EVENTS",actor,"tenant-a",Map.of("status","APPLIED"),List.of("GENERIC_MESSAGE:raw-own")).rows()).extracting(row->row.getFirst()).containsExactly("GENERIC_MESSAGE:raw-own");
    }
}
