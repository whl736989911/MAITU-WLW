package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** N14：值守概览的当前观测与语义声明。 */
@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:opstests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.hikari.connection-init-sql=SET TIME ZONE '+08:00'"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class OpsOverviewTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap;
    String root;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM product_version_attributes");
        jdbc.update("DELETE FROM duty_fault_handling_history");
        jdbc.update("DELETE FROM duty_fault_handlings");
        jdbc.update("DELETE FROM legacy_ingest_events");
        jdbc.update("DELETE FROM legacy_ingest_messages");
        jdbc.update("UPDATE devices SET place_id=NULL");
        jdbc.update("DELETE FROM places");
        for(String table:List.of("alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","user_messages","device_import_rows","device_import_jobs","device_export_snapshots","device_profile_snapshots","profile_rules","real_device_battery_observations","device_battery_observations","device_command_attempts","device_commands","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_change_outbox","alarm_change_heads","alarm_change_publisher_coord","alarm_trace_entries","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_events","alarms","devices","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO alarm_change_publisher_coord(id) VALUES(1)");
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Ops test tenant A'),('tenant-b','Ops test tenant B')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES('device-a','tenant-a','Ops device A','ops-a','model','A','NORMAL',FALSE),('device-b','tenant-b','Ops device B','ops-b','model','B','NORMAL',FALSE)");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        call(HttpMethod.POST,"/api/management/users",root,"tenant-a",UUID.randomUUID().toString(),Map.of("username","customer-a","name","Ops customer fixture","initial_password",INITIAL,"permissions",new java.util.ArrayList<>(com.fireplatform.shared.Limits.CUSTOMER_PERMISSIONS))).andExpect(status().isOk());
        N04TestScopes.grantExisting(jdbc);
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    void alarm(String id,String tenant,String device,String response,String physical) { jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,conclusion,revision,is_simulated) VALUES(?,?,?,?,?,'FIRE',?,?,?,?,0,FALSE)",id,tenant,device,"Device","Addr",OffsetDateTime.now().minusMinutes(30),physical,response,"HANDLED".equals(response)?"FALSE_ALARM":null); }

    @Test void overviewReportsUnhandledFireAndPendingPublication() throws Exception {
        alarm("op-a1","tenant-a","device-a","UNHANDLED","FIRE");
        alarm("op-a2","tenant-a","device-a","UNHANDLED","NORMAL");
        alarm("op-a3","tenant-a","device-a","HANDLED","NORMAL");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision,is_simulated) VALUES('op-sim','tenant-a','device-a','Sim','Addr','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',0,TRUE)");
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,publication_sequence) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"tenant-a",OffsetDateTime.now(),7L);
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,publication_sequence) VALUES(?,?,?,NULL)",UUID.randomUUID().toString(),"tenant-a",OffsetDateTime.now());
        var overview=body(call(HttpMethod.GET,"/api/management/ops-overview",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(overview.at("/alarms/unhandled").asLong()).isEqualTo(2);
        assertThat(overview.at("/alarms/fire").asLong()).isEqualTo(1);
        assertThat(overview.at("/channel/head").asLong()).isEqualTo(1);
        assertThat(overview.at("/channel/pending").asLong()).isEqualTo(1);
        assertThat(overview.at("/sources/enabled").asLong()).isZero();
        assertThat(overview.at("/tasks/raw_message_pending").asLong()).isZero();
        assertThat(overview.at("/tasks/reparse_pending").asLong()).isZero();
        assertThat(overview.at("/tasks").has("processing_pending")).isFalse();
        assertThat(overview.at("/tasks").has("processing_running")).isFalse();
    }

    @Test void overviewCountsOnlyPendingAuthenticatedBoundRawMessagesAndRealSources() throws Exception {
        var now=OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("INSERT INTO ingestion_sources(id,tenant_id,name,protocol,is_simulated,enabled,token_hash,token_generation,created_at,last_received_at) VALUES('real-source','tenant-a','Real','GENERIC_HTTP_JSON_V1',FALSE,TRUE,'x',1,?,?)",now,now);
        jdbc.update("INSERT INTO ingestion_sources(id,tenant_id,name,protocol,is_simulated,enabled,token_hash,token_generation,created_at,last_received_at) VALUES('sim-source','tenant-a','Old simulation','GENERIC_HTTP_JSON_V1',TRUE,TRUE,'y',1,?,?)",now,now.plusSeconds(10));
        byte[] payload="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,device_id,received_at,payload_utf8,payload_bytes,payload_sha256,binding_state,capture_provenance,application_state) VALUES('pending-raw','tenant-a','real-source','Real','m1','device-a','device-a',?,?,?,?,'BOUND','AUTHENTICATED_SOURCE','NOT_APPLIED')",now,payload,payload.length,digest);
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,device_id,received_at,payload_utf8,payload_bytes,payload_sha256,binding_state,capture_provenance,application_state) VALUES('legacy-raw','tenant-a','sim-source','Old simulation','m2','device-a','device-a',?,?,?,?,'BOUND','LEGACY_UNVERIFIED','NOT_APPLIED')",now,payload,payload.length,digest);
        var overview=body(call(HttpMethod.GET,"/api/management/ops-overview",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(overview.at("/tasks/raw_message_pending").asLong()).isEqualTo(1);
        assertThat(overview.at("/sources/enabled").asLong()).isEqualTo(1);
        assertThat(OffsetDateTime.parse(overview.at("/sources/last_received_at").asText())).isEqualTo(now);
    }

    @Test void overviewIsTenantScopedAndEmptyTenantIsZeroNotNull() throws Exception {
        alarm("op-b1","tenant-b","device-b","UNHANDLED","FIRE");
        var overview=body(call(HttpMethod.GET,"/api/management/ops-overview",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(overview.at("/alarms/unhandled").asLong()).isZero();
        assertThat(overview.at("/channel/head").isNull()).isTrue();
        assertThat(overview.at("/channel/pending").asLong()).isZero();
    }

    @Test void opsOverviewRespectsDeviceSetForDeviceOwnedCounts() throws Exception {
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES('bootstrap-admin','tenant-a','device-a')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES(?,?,?,?,?,?,?,FALSE)",
            "scope-hidden-device","tenant-a","scope hidden","scope-hidden-eui","synthetic","test address","NORMAL");
        alarm("scope-visible-fire","tenant-a","device-a","UNHANDLED","FIRE");
        alarm("scope-hidden-fire","tenant-a","scope-hidden-device","UNHANDLED","FIRE");
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,publication_sequence,device_id) VALUES(?,?,?,?,?)",
            "scope-visible-event","tenant-a",OffsetDateTime.parse("2026-01-01T00:00:00Z"),11L,"device-a");
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,publication_sequence,device_id) VALUES(?,?,?,?,?)",
            "scope-hidden-event","tenant-a",OffsetDateTime.parse("2026-01-02T00:00:00Z"),88L,"scope-hidden-device");
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,publication_sequence,device_id) VALUES(?,?,?,NULL,?)",
            "scope-visible-pending","tenant-a",OffsetDateTime.parse("2026-01-03T00:00:00Z"),"device-a");
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,publication_sequence,device_id) VALUES(?,?,?,NULL,?)",
            "scope-hidden-pending","tenant-a",OffsetDateTime.parse("2026-01-04T00:00:00Z"),"scope-hidden-device");

        var overview=body(call(HttpMethod.GET,"/api/management/ops-overview",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(overview.at("/alarms/unhandled").asLong()).isEqualTo(1);
        assertThat(overview.at("/alarms/fire").asLong()).isEqualTo(1);
        assertThat(overview.at("/channel/head").asLong()).isEqualTo(1);
        assertThat(overview.at("/channel/pending").asLong()).isEqualTo(1);
        assertThat(overview.at("/channel/last_recorded_at").asText()).isEqualTo("2026-01-03T00:00:00Z");
    }

    @Test void deviceFaultHandlingFiltersSeparateMissingFalseAndTrueBeforeCountingAndPaging() throws Exception {
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES('handling-peer','tenant-a','Peer','handling-peer','test','Peer','NORMAL',FALSE)");
        var entries=List.of(new Object[]{"state-null-1","device-a",at,at},new Object[]{"state-null-2","device-a",null,at},new Object[]{"state-false","device-a",at,at},new Object[]{"state-true","device-a",at,at},new Object[]{"state-peer","handling-peer",at,at});
        jdbc.batchUpdate("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,'tenant-a',?,'FAULT','reported.fault','{}',?,?)",entries);
        jdbc.update("INSERT INTO duty_fault_handlings(tenant_id,event_id,handled,updated_by,updated_at) VALUES('tenant-a','state-false',FALSE,'bootstrap-admin',?),('tenant-a','state-true',TRUE,'bootstrap-admin',?),('tenant-a','state-peer',TRUE,'bootstrap-admin',?)",at,at,at);
        var expected=Map.of("UNRECORDED",java.util.Set.of("state-null-1","state-null-2"),"UNHANDLED",java.util.Set.of("state-false"),"HANDLED",java.util.Set.of("state-true"));
        String path="/api/management/duty/device-faults/device-a/events";
        for(var state:expected.entrySet()){
            var ids=new java.util.HashSet<String>();
            for(int page=1;page<=state.getValue().size();page++){
                var result=body(call(HttpMethod.GET,path+"?handling_state="+state.getKey()+"&size=1&page="+page,root,"tenant-a",null,null).andExpect(status().isOk()));
                assertThat(result.get("total").asInt()).isEqualTo(state.getValue().size());
                assertThat(result.get("items").size()).isEqualTo(1);
                assertThat(result.at("/items/0/device_id").asText()).isEqualTo("device-a");
                ids.add(result.at("/items/0/id").asText());
            }
            assertThat(ids).isEqualTo(state.getValue());
        }
        var bounded=body(call(HttpMethod.GET,path+"?handling_state=UNRECORDED&from="+at+"&to="+at.plusSeconds(1),root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(bounded.get("items").findValuesAsText("id")).containsExactly("state-null-1");
        call(HttpMethod.GET,"/api/management/duty/faults?handling_state=UNKNOWN",root,"tenant-a",null,null).andExpect(status().isBadRequest());
    }

    @Test void deviceFaultTimeFiltersKeepSourceBoundariesAndDeviceScopeBeforePaging() throws Exception {
        var at=OffsetDateTime.parse("2026-11-14T00:00:00Z");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES('fault-peer','tenant-a','Peer','fault-peer','test','Peer','NORMAL',FALSE)");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('fault-start','tenant-a','device-a','FAULT','reported.fault','{\"deviceStateCode\":\"1002003\"}',?,?),('fault-middle','tenant-a','device-a','FAULT','reported.fault','{}',?,?),('fault-end','tenant-a','device-a','FAULT','reported.fault','{}',?,?),('fault-untimed','tenant-a','device-a','FAULT','reported.fault','{}',NULL,?),('fault-other-kind','tenant-a','device-a','PARAM','reported.param','{}',?,?),('fault-peer-event','tenant-a','fault-peer','FAULT','reported.fault','{}',?,?),('fault-foreign','tenant-b','device-b','FAULT','reported.fault','{}',?,?)",
            at,at.plusDays(1),at.plusNanos(1000),at,at.plusNanos(2000),at,at.plusNanos(1000),at.plusNanos(1000),at,at,at,at,at);
        String path="/api/management/duty/device-faults/device-a/events";
        var ids=new java.util.HashSet<String>();
        for(int page=1;page<=2;page++){
            var result=body(call(HttpMethod.GET,path+"?from="+at+"&to="+at.plusNanos(2000)+"&size=1&page="+page,root,"tenant-a",null,null).andExpect(status().isOk()));
            assertThat(result.get("total").asInt()).isEqualTo(2);
            assertThat(result.get("page").asInt()).isEqualTo(page);
            assertThat(result.get("items").size()).isEqualTo(1);
            assertThat(result.at("/items/0/device_id").asText()).isEqualTo("device-a");
            ids.add(result.at("/items/0/id").asText());
        }
        assertThat(ids).containsExactlyInAnyOrder("fault-start","fault-middle");
        var all=body(call(HttpMethod.GET,path,root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(all.get("total").asInt()).isEqualTo(4);
        assertThat(all.get("items").findValuesAsText("fault_code")).contains("1002003");
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='tenant-a'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES('bootstrap-admin','tenant-a','device-a')");
        var peer=body(call(HttpMethod.GET,"/api/management/duty/device-faults/fault-peer/events?from="+at,root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(peer.get("total").asInt()).isZero();
    }

    @Test void deviceFaultTimeFiltersRejectInvalidParametersAndCustomerScopeOverrides() throws Exception {
        String path="/api/management/duty/device-faults/device-a/events";
        for(String suffix:List.of("?from=2026-11-14","?from=2026-11-14T00:00:00Z&to=2026-11-14T00:00:00Z","?from=2026-11-15T00:00:00Z&to=2026-11-14T00:00:00Z","?from=2026-11-14T00:00:00Z&from=2026-11-15T00:00:00Z","?to=bad","?device_id=device-b","?handling_state=UNKNOWN","?handling_state=HANDLED&handling_state=UNRECORDED")){
            call(HttpMethod.GET,path+suffix,root,"tenant-a",null,null).andExpect(status().isBadRequest());
        }
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES('own-fault','tenant-a','device-a','FAULT','reported.fault','{}',NULL,CURRENT_TIMESTAMP),('foreign-fault','tenant-b','device-b','FAULT','reported.fault','{}',NULL,CURRENT_TIMESTAMP)");
        String customer=login("customer-a",INITIAL,"customer");
        call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        customer=login("customer-a",PASSWORD,"customer");
        var own=body(call(HttpMethod.GET,"/api/customer/duty/device-faults/device-a/events",customer,null,null,null).andExpect(status().isOk()));
        assertThat(own.get("items").findValuesAsText("id")).containsExactly("own-fault");
        var foreign=body(call(HttpMethod.GET,"/api/customer/duty/device-faults/device-b/events?from=2026-11-14T00:00:00Z",customer,null,null,null).andExpect(status().isOk()));
        assertThat(foreign.get("total").asInt()).isZero();
        call(HttpMethod.GET,"/api/customer/duty/device-faults/device-b/events",customer,"tenant-b",null,null).andExpect(status().isForbidden());
    }

    @Test void dutyAnalyticsAggregatesWholeWindowAndDecodesFaultCodes() throws Exception {
        var observed=OffsetDateTime.now().minusMinutes(2);
        for(int index=0;index<12;index++)jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'FAULT','reported.fault',?,?,?)",
            "analytics-fault-"+index,"tenant-a","device-a","{\"deviceStateCode\":\"1002001\"}",observed.plusSeconds(index),observed.plusSeconds(index));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'FAULT','reported.fault','{\"deviceStateCode\":\"1002003\"}',NULL,?)",
            "analytics-fault-untimed","tenant-a","device-a",observed);
        var result=body(call(HttpMethod.GET,"/api/management/duty/analytics?days=7",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(result.get("faults").asLong()).isEqualTo(12);
        assertThat(result.get("untimed_fault_events").asLong()).isEqualTo(1);
        assertThat(result.at("/fault_types/0/code").asText()).isEqualTo("1002001");
        assertThat(result.at("/fault_types/0/label").asText()).isEqualTo("烟感故障报警");
        assertThat(result.at("/fault_types/0/count").asLong()).isEqualTo(12);
        long daily=result.get("faults_by_day").findValuesAsText("count").stream().mapToLong(Long::parseLong).sum();
        assertThat(daily).isEqualTo(12);
    }

    @Test void dailyFaultTrendUsesUtcDayEvenWhenDatabaseAndSourceOffsetsDiffer() throws Exception {
        var databaseNow=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",OffsetDateTime.class);
        var expectedDay=databaseNow.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().minusDays(1);
        var observed=expectedDay.atTime(23,30).atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(14));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'FAULT','reported.fault','{\"deviceStateCode\":\"1002001\"}',?,?)",
            "analytics-utc-boundary","tenant-a","device-a",observed,observed);
        var result=body(call(HttpMethod.GET,"/api/management/duty/analytics?days=7",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(result.get("faults").asLong()).isEqualTo(1);
        for(JsonNode day:result.get("faults_by_day"))
            assertThat(day.get("count").asLong()).isEqualTo(day.get("date").asText().equals(expectedDay.toString())?1:0);
    }

    @Test void telemetryAnalyticsKeepsUntimedAndUnknownUnitReadingsOutOfNumericTrends() throws Exception {
        jdbc.update("UPDATE devices SET product_version_id=NULL WHERE id='device-a' AND tenant_id='tenant-a'");
        var observed=OffsetDateTime.now().minusMinutes(2).withNano(0);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'PARAM','reported.param','{\"rssi\":-71}',?,?)",
            "analytics-param-timed","tenant-a","device-a",observed,observed);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'PARAM','reported.param','{\"rssi\":-72}',?,?)",
            "analytics-param-untimed","tenant-a","device-a",null,observed.plusSeconds(1));
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,device_id,ingress,vendor_platform,external_key,payload_raw,payload_sha256,payload_bytes,received_at,state) VALUES(?,?,?,'HTTP_FORM_TTL','formttl',?,'{}',?,2,?,'NORMALIZED')",
            "analytics-param-message-old","tenant-a","device-a","analytics-param-message-old","0".repeat(64),observed.minusDays(365));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,message_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,?,'PARAM','reported.param','{\"rssi\":-73}',NULL,?)",
            "analytics-param-untimed-old","tenant-a","device-a","analytics-param-message-old",observed.minusDays(365).plusSeconds(1));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'PARAM','reported.param','{\"temperature\":21}',NULL,?)",
            "analytics-param-unrelated","tenant-a","device-a",observed);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'PARAM','reported.param','{\"temperature\":22}',?,?)",
            "analytics-param-newer-other-metric","tenant-a","device-a",observed.plusSeconds(30),observed.plusSeconds(30));
        var history=body(call(HttpMethod.GET,"/api/management/duty/parameters?page=1&size=20&metric=rssi",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(history.get("total").asLong()).isEqualTo(3);
        var untimedHistory=java.util.stream.StreamSupport.stream(history.get("items").spliterator(),false)
            .filter(item->item.get("event_id").asText().equals("analytics-param-untimed-old")).findFirst().orElseThrow();
        assertThat(untimedHistory.get("observed_at").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(untimedHistory.get("received_at").asText())).isEqualTo(observed.minusDays(365));
        assertThat(OffsetDateTime.parse(untimedHistory.get("recorded_at").asText())).isEqualTo(observed.minusDays(365).plusSeconds(1));
        var latest=body(call(HttpMethod.GET,"/api/management/duty/parameters/current?metric=rssi",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(latest.at("/items/0/event_id").asText()).isEqualTo("analytics-param-timed");
        assertThat(latest.at("/items/0/readings/0/value").asText()).isEqualTo("-71");
        var result=body(call(HttpMethod.GET,"/api/management/duty/parameters/analytics?days=7&metric=rssi",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(result.get("untimed_sample_count").asLong()).isEqualTo(2);
        var series=result.get("series").get(0);
        assertThat(series.get("unit_status").asText()).isEqualTo("UNKNOWN");
        assertThat(series.get("source_version").isNull()).isTrue();
        for(JsonNode point:series.get("points")) {
            assertThat(point.get("average").isNull()).isTrue();
            assertThat(point.get("minimum").isNull()).isTrue();
            assertThat(point.get("maximum").isNull()).isTrue();
        }
        assertThat(series.findValuesAsText("sample_count").stream().mapToLong(Long::parseLong).sum()).isEqualTo(1);
    }

    @Test void dutyAnalyticsExcludesOtherTenantAndPlaceAndZeroFillsUtcDays() throws Exception {
        String rootPlace="analytics-root-"+UUID.randomUUID(),otherPlace="analytics-other-"+UUID.randomUUID(),otherDevice="analytics-device-"+UUID.randomUUID();
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES(?,?,?,NULL),(?,?,?,NULL)",rootPlace,"tenant-a","Analytics root",otherPlace,"tenant-a","Analytics other");
        jdbc.update("UPDATE devices SET place_id=? WHERE id='device-a' AND tenant_id='tenant-a'",rootPlace);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id) VALUES(?,?,?,?,?,'Other place','NORMAL',FALSE,?)",
            otherDevice,"tenant-a","Other place device","analytics-eui-"+UUID.randomUUID(),"Synthetic",otherPlace);
        var observed=OffsetDateTime.now().minusMinutes(2);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'FAULT','reported.fault','{\"code\":\"1002001\"}',?,?)",
            "analytics-in-scope","tenant-a","device-a",observed,observed);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'FAULT','reported.fault','{\"code\":\"1002001\"}',?,?)",
            "analytics-other-place","tenant-a",otherDevice,observed,observed);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'FAULT','reported.fault','{\"code\":\"1002001\"}',?,?)",
            "analytics-other-tenant","tenant-b","device-b",observed,observed);
        var result=body(call(HttpMethod.GET,"/api/management/duty/analytics?days=7&scope_selected=true&scope_tenant_ids=&scope_place_ids="+rootPlace,root,null,null,null).andExpect(status().isOk()));
        assertThat(result.get("faults").asLong()).isEqualTo(1);
        assertThat(result.get("fault_types").get(0).get("count").asLong()).isEqualTo(1);
        assertThat(result.get("faults_by_day").size()).isEqualTo(8);
        assertThat(result.get("faults_by_day").findValuesAsText("count").stream().mapToLong(Long::parseLong).sum()).isEqualTo(1);
        assertThat(result.get("faults_by_day").findValuesAsText("count").stream().filter("0"::equals).count()).isEqualTo(7);
    }

    @Test void telemetryAnalyticsKeepsDifferentDeclaredUnitsInSeparateZeroFilledSeries() throws Exception {
        String product=UUID.randomUUID().toString(),versionA=UUID.randomUUID().toString(),versionB=UUID.randomUUID().toString(),unitDevice=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO products(id,name,manufacturer,model,description) VALUES(?,?,?,?,?)",product,"Telemetry fixture","Test","Telemetry-"+product,"Unit-separated test fixture");
        var created=OffsetDateTime.now();
        for(var version:List.of(versionA,versionB))jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,revision,schema_json,capabilities_json,parser_tested,created_at) VALUES(?,?,?,'PUBLISHED',0,'{}','{}',TRUE,?)",version,product,version.equals(versionA)?1:2,created);
        jdbc.update("INSERT INTO product_version_attributes(product_version_id,attribute_key,name,unit,data_type) VALUES(?,?,?,?,?),(?,?,?,?,?)",
            versionA,"rssi","RSSI","dBm","NUMBER",versionB,"rssi","RSSI","mV","NUMBER");
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id='device-a' AND tenant_id='tenant-a'",versionA);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,product_version_id) VALUES(?,?,?,?,?,'Telemetry address','NORMAL',FALSE,?)",
            unitDevice,"tenant-a","Unit device","telemetry-eui-"+unitDevice,"Synthetic",versionB);
        var observed=OffsetDateTime.now().minusMinutes(2);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'PARAM','reported.param','{\"rssi\":-70}',?,?)",
            "analytics-declared-a","tenant-a","device-a",observed,observed);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES(?,?,?,'PARAM','reported.param','{\"rssi\":5}',?,?)",
            "analytics-declared-b","tenant-a",unitDevice,observed,observed);
        var result=body(call(HttpMethod.GET,"/api/management/duty/parameters/analytics?days=7&metric=rssi",root,"tenant-a",null,null).andExpect(status().isOk()));
        assertThat(result.get("series").size()).isEqualTo(2);
        assertThat(result.get("series").findValuesAsText("unit")).containsExactlyInAnyOrder("dBm","mV");
        for(JsonNode series:result.get("series")) {
            assertThat(series.get("unit_status").asText()).isEqualTo("DECLARED");
            assertThat(series.get("points").size()).isEqualTo(8);
            assertThat(series.get("points").findValuesAsText("sample_count").stream().mapToLong(Long::parseLong).sum()).isEqualTo(1);
            assertThat(series.get("points").findValuesAsText("sample_count").stream().filter("0"::equals).count()).isEqualTo(7);
        }
    }

    @Test void customerAudienceCannotReadManagementOverview() throws Exception {
        String customer=login("customer-a",INITIAL,"customer");
        call(HttpMethod.GET,"/api/management/ops-overview",customer,"tenant-a",null,null).andExpect(status().isForbidden());
    }
}
