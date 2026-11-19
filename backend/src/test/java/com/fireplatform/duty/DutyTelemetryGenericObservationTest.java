package com.fireplatform.duty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fireplatform.N04TestScopes;
import com.fireplatform.shared.ScopeFilter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"app.roles=api","fire.hikvision.enabled=false","spring.datasource.url=jdbc:h2:mem:genericbatterytelemetry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test") @Transactional
class DutyTelemetryGenericObservationTest {
    @Autowired DutyTelemetryQueries telemetry;
    @Autowired JdbcTemplate jdbc;
    private String tenant,device,actor;
    private OffsetDateTime now;

    @BeforeEach void setup(){
        tenant=UUID.randomUUID().toString();device=UUID.randomUUID().toString();actor=UUID.randomUUID().toString();
        now=OffsetDateTime.now().withNano(0);
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)",tenant,"Generic telemetry fixture");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES(?,?,?,?,?,?,'UNKNOWN',FALSE)",device,tenant,"Generic detector",device,"generic","Fixture address");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES(?,?,?,'PLATFORM',?,FALSE,TRUE)",actor,actor,"Projection fixture","unused-projection-fixture");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES(?,?)",actor,tenant);
        N04TestScopes.grantExisting(jdbc);
        observation("battery-old",now.minusHours(2),"35");
        observation("battery-zero",now.minusHours(1),"0");
    }

    private void observation(String id,OffsetDateTime observed,String value){
        jdbc.update("INSERT INTO real_device_battery_observations(id,tenant_id,device_id,observed_at,battery_percent,source_kind,recorded_at) VALUES(?,?,?,?,?,'GENERIC_INGEST',?)",id,tenant,device,observed,new BigDecimal(value),now);
    }

    @Test void genericObservationsProjectAsBatteryAcrossHistoryCurrentAndAnalyticsWithoutChangingDevice(){
        assertThat(telemetry.metrics(actor,tenant,ScopeFilter.Selection.all())).contains(new DutyTelemetryQueries.Metric("battery","电量","NUMBER"));
        var history=telemetry.history(actor,tenant,ScopeFilter.Selection.all(),"battery",now.minusDays(1),now.plusSeconds(1),1,20);
        assertThat(history.total()).isEqualTo(2);
        assertThat(history.items()).hasSize(2);
        var latest=history.items().getFirst();
        assertThat(latest.eventId()).isEqualTo(DutyTelemetryQueries.GENERIC_BATTERY_PREFIX+"battery-zero");
        assertThat(latest.observedAt()).isEqualTo(now.minusHours(1));
        assertThat(latest.receivedAt()).isNull();
        assertThat(latest.recordedAt()).isEqualTo(now);
        assertThat(latest.readings()).containsExactly(new DutyTelemetryQueries.Reading("battery","0","%","DECLARED","电量","NUMBER",now.minusHours(1),null,now));
        assertThat(history.items().get(1).readings().getFirst().value()).isEqualTo("35");

        var current=telemetry.current(actor,tenant,ScopeFilter.Selection.all(),"battery",1,20);
        assertThat(current.total()).isEqualTo(1);
        assertThat(current.items()).singleElement().satisfies(sample->{
            assertThat(sample.eventId()).isEqualTo(DutyTelemetryQueries.GENERIC_BATTERY_PREFIX+"battery-zero");
            assertThat(sample.readings().getFirst().value()).isEqualTo("0");
            assertThat(sample.receivedAt()).isNull();
        });

        var analytics=telemetry.analytics(actor,tenant,ScopeFilter.Selection.all(),"battery",7);
        assertThat(analytics.series()).singleElement().satisfies(series->{
            assertThat(series.unit()).isEqualTo("%");
            assertThat(series.unitStatus()).isEqualTo("DECLARED");
            assertThat(series.sourceVersion()).isNull();
            var day=series.points().stream().filter(point->point.sampleCount()==2).findFirst().orElseThrow();
            assertThat(day.average()).isEqualTo("17.5");
            assertThat(day.minimum()).isEqualTo("0");
            assertThat(day.maximum()).isEqualTo("35");
        });
        var excluded=telemetry.history(actor,tenant,new ScopeFilter.Selection(List.of(),List.of("missing-place")),"battery",null,null,1,20);
        assertThat(excluded.total()).isZero();
        assertThat(excluded.items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id=?",Integer.class,device)).isNull();
    }
}
