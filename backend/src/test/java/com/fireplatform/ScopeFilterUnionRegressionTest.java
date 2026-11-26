package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fireplatform.shared.ScopeFilter;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ScopeFilterUnionRegressionTest {
    @Test void unitAndOtherUnitPlaceSubtreeAreUnionedForDevicesAndAlarms() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:scope-union-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE TABLE places(id VARCHAR PRIMARY KEY, tenant_id VARCHAR, parent_place_id VARCHAR)");
            jdbc.execute("CREATE TABLE devices(id VARCHAR PRIMARY KEY, tenant_id VARCHAR, place_id VARCHAR)");
            jdbc.execute("CREATE TABLE alarms(id VARCHAR PRIMARY KEY, tenant_id VARCHAR, device_id VARCHAR)");
            jdbc.update("INSERT INTO places VALUES ('a-root','a',NULL),('b-root','b',NULL),('b-child','b','b-root'),('b-other','b',NULL),('c-root','c',NULL)");
            jdbc.update("INSERT INTO devices VALUES ('a-device','a','a-root'),('b-child-device','b','b-child'),('b-other-device','b','b-other'),('c-device','c','c-root')");
            jdbc.update("INSERT INTO alarms SELECT id,tenant_id,id FROM devices");
            var filter = new ScopeFilter(jdbc);
            var deviceScope = filter.clause("d", List.of("a"), List.of("b-root"));
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + deviceScope.sql(), String.class, deviceScope.bindings().toArray()))
                .containsExactlyInAnyOrder("a-device", "b-child-device");
            var alarmScope = filter.alarmDevices("a", List.of("a"), List.of("b-root"));
            assertThat(jdbc.queryForList("SELECT a.id FROM alarms a WHERE " + alarmScope.sql(), String.class, alarmScope.bindings().toArray()))
                .containsExactlyInAnyOrder("a-device", "b-child-device");
            var placeOnly = filter.clause("d", List.of(), List.of("b-root"));
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + placeOnly.sql(), String.class, placeOnly.bindings().toArray()))
                .containsExactly("b-child-device");
        }
    }

    @Test void aggregateReadsIntersectLiveGrantsAndKeepExplicitEmptySeparateFromAll() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:scope-read-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE TABLE tenants(id VARCHAR PRIMARY KEY, enabled BOOLEAN)");
            jdbc.execute("CREATE TABLE user_tenant_grants(user_id VARCHAR,tenant_id VARCHAR)");
            jdbc.execute("CREATE TABLE app_users(id VARCHAR PRIMARY KEY,user_type VARCHAR,tenant_id VARCHAR,enabled BOOLEAN,is_protected BOOLEAN DEFAULT FALSE)");
            jdbc.execute("CREATE TABLE user_device_scopes(user_id VARCHAR,tenant_id VARCHAR,mode VARCHAR,revision BIGINT)");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V97_1__client_platform_operator_scope.sql")).populate(connection);
            jdbc.execute("CREATE TABLE places(id VARCHAR PRIMARY KEY,tenant_id VARCHAR,parent_place_id VARCHAR)");
            jdbc.execute("CREATE TABLE devices(id VARCHAR PRIMARY KEY,tenant_id VARCHAR,place_id VARCHAR)");
            jdbc.update("INSERT INTO tenants VALUES ('a',TRUE),('b',TRUE),('disabled',FALSE),('hidden',TRUE)");
            jdbc.update("INSERT INTO user_tenant_grants VALUES ('reader','a'),('reader','b'),('reader','disabled')");
            jdbc.update("INSERT INTO places VALUES ('a-root','a',NULL),('b-root','b',NULL),('b-child','b','b-root'),('b-other','b',NULL),('hidden-root','hidden',NULL)");
            jdbc.update("INSERT INTO devices VALUES ('a-device','a','a-root'),('b-device','b','b-child'),('b-other','b','b-other'),('disabled-device','disabled',NULL),('hidden-device','hidden','hidden-root')");
            var filter = new ScopeFilter(jdbc);
            var all = filter.units("reader", "d", "tenant_id", null, ScopeFilter.Selection.all());
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + all.sql(), String.class, all.bindings().toArray()))
                .containsExactlyInAnyOrder("a-device", "b-device", "b-other");
            var mixed = filter.devices("reader", "d", null, new ScopeFilter.Selection(List.of("a"), List.of("b-root")));
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + mixed.sql(), String.class, mixed.bindings().toArray()))
                .containsExactlyInAnyOrder("a-device", "b-device");
            var none = filter.devices("reader", "d", null, ScopeFilter.Selection.none());
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + none.sql(), String.class, none.bindings().toArray())).isEmpty();
            var forged = filter.units("reader", "d", "tenant_id", null, new ScopeFilter.Selection(List.of("hidden", "disabled"), List.of()));
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + forged.sql(), String.class, forged.bindings().toArray())).isEmpty();
            jdbc.update("INSERT INTO app_users(id,user_type,tenant_id,enabled) VALUES ('customer','CUSTOMER','b',TRUE)");
            var customer = filter.devices("customer", "d", "b", ScopeFilter.Selection.all());
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + customer.sql(), String.class, customer.bindings().toArray()))
                .containsExactlyInAnyOrder("b-device", "b-other");
            jdbc.update("DELETE FROM user_tenant_grants WHERE user_id='reader' AND tenant_id='b'");
            assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE " + all.sql(), String.class, all.bindings().toArray())).containsExactly("a-device");
        }
    }
}
