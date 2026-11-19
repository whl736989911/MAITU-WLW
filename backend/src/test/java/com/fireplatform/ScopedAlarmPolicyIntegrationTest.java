package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.alarm.AlarmLifecycle;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.roles=api","app.storage=h2-dev","fire.alarm-reminder-initial-delay-ms=3600000",
    "spring.datasource.url=jdbc:h2:mem:scoped-alarm-policy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class ScopedAlarmPolicyIntegrationTest {
    private static final String BASE="/api/management/alarm-base-configuration";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired AlarmLifecycle lifecycle;
    @Autowired AlarmBaseConfigurationEventConsumer outcomes;
    @Autowired AlarmBaseConfigurationQueries policies;
    @Autowired TransactionTemplate tx;
    IngestionIntegrationTest fixture;

    @BeforeEach void reset() throws Exception {
        jdbc.update("DELETE FROM alarm_scoped_policies");
        fixture=new IngestionIntegrationTest(); fixture.mvc=mvc; fixture.json=json; fixture.jdbc=jdbc; fixture.bootstrap=bootstrap; fixture.parser=parser;
        fixture.reset();
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES ('floor','tenant-a','Floor',?),('room','tenant-a','Room','floor')",fixture.placeId);
        jdbc.update("UPDATE devices SET place_id='room' WHERE id='device-a'");
    }

    private static String path(String event,String scope,String id) { return BASE+"/"+event+"/scopes/"+scope+"/"+id; }

    private static Map<String,Object> ruleValue(int silence,Object period,List<String> exclusions) {
        return Map.of("subscriptions",Map.of("legacy-model",exclusions),"receive_alarm",true,"push_message",true,
            "push_period_hours",period,"voice_enabled",false,"voice_types",List.of(),"voice_fields",List.of(),
            "voice_continuous",false,"silence_minutes",silence);
    }

    private JsonNode put(String event,String scope,String id,long revision,int silence,int period,List<String> exclusions) throws Exception {
        return fixture.body(fixture.call(HttpMethod.PUT,path(event,scope,id)+(scope.equals("PLATFORM")?"":"?tenant_id=tenant-a"),fixture.root,null,UUID.randomUUID().toString(),
            Map.of("expected_revision",revision,"reason","Test effective rules","value",ruleValue(silence,period,exclusions))).andExpect(status().isOk()));
    }

    private void clear(String scope,String id,long revision) throws Exception {
        fixture.call(HttpMethod.POST,path("FIRE",scope,id)+"/clear?tenant_id=tenant-a",fixture.root,null,UUID.randomUUID().toString(),
            Map.of("expected_revision",revision,"reason","Inherit policy")).andExpect(status().isOk());
    }

    @Test void closestWholeFireRuleDrivesRealOutcomesAndReminderWithoutOverridingUnitHardGate() throws Exception {
        put("FIRE","PLATFORM","platform",0,1,2,List.of("FIRE"));
        var unit=new java.util.LinkedHashMap<>(AlarmBaseConfigurationQueries.defaults());
        unit.put("silenceIntervals",Map.of("alarmMinutes",2,"faultMinutes",0));
        fixture.call(HttpMethod.PUT,BASE,fixture.root,"tenant-a","unit-compat",Map.of("expected_revision",0,"reason","Legacy unit edit","value",unit)).andExpect(status().isOk());
        put("FIRE","PLACE","floor",0,3,4,List.of("FIRE"));
        put("FIRE","PLACE","room",0,4,5,List.of("FIRE"));
        var direct=put("FIRE","DEVICE","device-a",0,5,6,List.of());
        assertThat(direct.at("/effective/source_scope").asText()).isEqualTo("DEVICE");
        assertThat(direct.at("/effective/source_revision").asLong()).isEqualTo(1);
        var now=OffsetDateTime.parse("2026-11-20T02:00:00Z");
        var alarm=tx.execute(status->lifecycle.fire("tenant-a","device-a","Synthetic smoke detector","Room",now));
        assertThat(outcome(alarm,"SUBSCRIPTION").outcome()).isEqualTo("SUBSCRIBED");
        assertThat(outcome(alarm,"SILENCE_INTERVAL").detailJson()).contains("\"minutes\":5");
        assertThat(jdbc.queryForObject("SELECT period_hours FROM alarm_reminder_schedule WHERE alarm_id=?",Integer.class,alarm)).isEqualTo(6);
        clear("DEVICE","device-a",1);
        assertSource("PLACE","room",4);
        clear("PLACE","room",1);
        assertSource("PLACE","floor",3);
        clear("PLACE","floor",1);
        assertSource("UNIT","tenant-a",2);
        var foreign=policies.effectiveForDevice("tenant-b","device-b","FIRE");
        assertThat(foreign.sourceScope()).isEqualTo("PLATFORM");
        assertThat(foreign.subscribed("legacy-model","FIRE")).isFalse();
        fixture.call(HttpMethod.GET,path("FIRE","DEVICE","device-b")+"?tenant_id=tenant-a",fixture.root,null,null,null).andExpect(status().isNotFound());
        fixture.call(HttpMethod.GET,path("FIRE","DEVICE","device-a"),fixture.root,"tenant-a",null,null).andExpect(status().isBadRequest());
        fixture.call(HttpMethod.POST,path("FIRE","DEVICE","device-a")+"/clear?tenant_id=tenant-a",fixture.root,null,"stale-clear",
            Map.of("expected_revision",1,"reason","Old rule editor")).andExpect(status().isConflict());
        put("FIRE","DEVICE","device-a",2,5,6,List.of());
        unit.put("unitService",Map.of("receiveAlarm",false,"pushMessage",false));
        fixture.call(HttpMethod.PUT,BASE,fixture.root,"tenant-a","hard-disable",Map.of("expected_revision",1,"reason","Explicit service stop","value",unit)).andExpect(status().isOk());
        var suppressed=tx.execute(status->lifecycle.fire("tenant-a","device-a","Synthetic smoke detector","Room",now.plusSeconds(1)));
        assertThat(outcome(suppressed,"NOTIFICATION_GATE").outcome()).isEqualTo("SUPPRESSED_BY_UNIT_SERVICE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_reminder_schedule WHERE alarm_id=?",Long.class,suppressed)).isZero();
    }

    @Test void faultUsesItsOwnScopedRuleWithNoFireFallbackAndNoDeviceIdentifiersInPolicyTrace() throws Exception {
        put("FIRE","DEVICE","device-a",0,5,6,List.of());
        put("FAULT","PLACE","floor",0,17,0,List.of("LOW_BATTERY"));
        var now=OffsetDateTime.parse("2026-11-20T02:00:00Z");
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('test-fault','tenant-a','device-a','FAULT','fixture','{}',?,?)",now,now);
        tx.executeWithoutResult(status->outcomes.onLegacyFaultEvidence("tenant-a","test-fault","device-a","LOW_BATTERY",now));
        var subscription=outcome("test-fault","SUBSCRIPTION");
        assertThat(subscription.outcome()).isEqualTo("EXCLUDED_BY_SUBSCRIPTION");
        assertThat(subscription.configurationRevision()).isEqualTo(1);
        assertThat(subscription.detailJson()).contains("\"policy_source_scope\":\"PLACE\"").doesNotContain("device-a","fixture-a","floor");
        assertThat(outcome("test-fault","SILENCE_INTERVAL").detailJson()).contains("\"minutes\":17");
    }

    @Test void cannotWriteFractionalOrOutOfRangeIntegerPolicyValues() throws Exception {
        String scope=path("FIRE","DEVICE","device-a")+"?tenant_id=tenant-a";
        for (var period:List.of(new java.math.BigDecimal("2.0"), new java.math.BigInteger("18446744073709551616"))) {
            fixture.call(HttpMethod.PUT,scope,fixture.root,null,UUID.randomUUID().toString(),
                Map.of("expected_revision",0,"reason","Reject lossy numeric input","value",ruleValue(0,period,List.of())))
                .andExpect(status().isBadRequest());
        }
        assertThat(policies.effectiveForDevice("tenant-a","device-a","FIRE").sourceScope()).isNotEqualTo("DEVICE");
    }

    private void assertSource(String scope,String id,int silence) {
        var effective=policies.effectiveForDevice("tenant-a","device-a","FIRE");
        assertThat(effective.sourceScope()).isEqualTo(scope);
        assertThat(effective.sourceId()).isEqualTo(id);
        assertThat(effective.value().silenceMinutes()).isEqualTo(silence);
    }

    private AlarmBaseConfigurationEventConsumer.Outcome outcome(String source,String kind) {
        return policies.outcomes("tenant-a",source).stream().filter(item->item.outcomeKind().equals(kind)).findFirst().orElseThrow();
    }
}
