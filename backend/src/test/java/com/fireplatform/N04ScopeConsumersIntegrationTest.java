package com.fireplatform;

import com.fireplatform.identity.DeviceScopes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes the production scope predicate against H2 rows instead of inspecting generated SQL. */
class N04ScopeConsumersIntegrationTest {
    private JdbcTemplate jdbc;
    private com.fireplatform.ingestion.MessageQueries messageQueries;
    private DeviceScopes scopes;

    @BeforeEach void database() {
        String url = "jdbc:h2:mem:n04-scope-" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.execute("CREATE TABLE user_device_scopes(user_id VARCHAR NOT NULL,tenant_id VARCHAR NOT NULL,mode VARCHAR NOT NULL,revision BIGINT NOT NULL,PRIMARY KEY(user_id,tenant_id))");
        jdbc.execute("CREATE TABLE user_device_scope_devices(user_id VARCHAR NOT NULL,tenant_id VARCHAR NOT NULL,device_id VARCHAR NOT NULL,PRIMARY KEY(user_id,tenant_id,device_id))");
        jdbc.execute("CREATE TABLE user_device_scope_places(user_id VARCHAR NOT NULL,tenant_id VARCHAR NOT NULL,place_id VARCHAR NOT NULL,PRIMARY KEY(user_id,tenant_id,place_id))");
        jdbc.execute("CREATE TABLE places(id VARCHAR NOT NULL,tenant_id VARCHAR NOT NULL,name VARCHAR NOT NULL,parent_place_id VARCHAR,revision BIGINT NOT NULL,PRIMARY KEY(id,tenant_id))");
        jdbc.execute("CREATE TABLE devices(id VARCHAR,tenant_id VARCHAR,name VARCHAR,place_id VARCHAR)");
        jdbc.execute("CREATE TABLE alarms(id VARCHAR,tenant_id VARCHAR,device_id VARCHAR)");
        jdbc.execute("CREATE TABLE raw_messages(id VARCHAR,tenant_id VARCHAR,device_id VARCHAR,source_id VARCHAR,source_name VARCHAR,source_message_id VARCHAR,vendor_device_id VARCHAR,device_name VARCHAR,binding_id VARCHAR,product_version_id VARCHAR,received_at TIMESTAMP WITH TIME ZONE,reported_at_utf8 BINARY,payload_bytes BIGINT,payload_sha256 VARCHAR,binding_state VARCHAR,duplicate_count BIGINT,revision BIGINT,latest_preview_id VARCHAR,parse_status VARCHAR,business_status VARCHAR,application_state VARCHAR,applied_at TIMESTAMP WITH TIME ZONE,apply_error VARCHAR,capture_provenance VARCHAR,occurred_at TIMESTAMP WITH TIME ZONE,occurred_at_raw VARCHAR)");
        jdbc.execute("CREATE TABLE device_groups(id VARCHAR,tenant_id VARCHAR)");
        jdbc.execute("CREATE TABLE notifications(id VARCHAR,tenant_id VARCHAR,device_id VARCHAR)");
        jdbc.execute("CREATE TABLE device_group_members(group_id VARCHAR,tenant_id VARCHAR,device_id VARCHAR)");
        jdbc.execute("CREATE TABLE tenants(id VARCHAR PRIMARY KEY,enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE app_users(id VARCHAR PRIMARY KEY,user_type VARCHAR,enabled BOOLEAN,tenant_id VARCHAR)");
        jdbc.execute("CREATE TABLE user_tenant_grants(user_id VARCHAR,tenant_id VARCHAR)");
        jdbc.update("INSERT INTO tenants VALUES ('tenant-a',TRUE),('tenant-b',TRUE)");
        jdbc.update("INSERT INTO user_tenant_grants VALUES ('set-actor','tenant-a'),('all-actor','tenant-b'),('empty-actor','tenant-a'),('place-actor','tenant-a')");
        jdbc.update("INSERT INTO user_device_scopes VALUES ('set-actor','tenant-a','DEVICE_SET',1),('all-actor','tenant-b','ALL_TENANT',1),('empty-actor','tenant-a','DEVICE_SET',1),('place-actor','tenant-a','PLACE_SUBTREE',1)");
        jdbc.update("INSERT INTO user_device_scope_devices VALUES ('set-actor','tenant-a','d1')");
        jdbc.update("INSERT INTO user_device_scope_places VALUES ('place-actor','tenant-a','root')");
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id,revision) VALUES ('root','tenant-a','Root',NULL,0),('child','tenant-a','Child','root',0),('grand','tenant-a','Grand','child',0),('outside','tenant-a','Outside',NULL,0),('root-b','tenant-b','Foreign root',NULL,0)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,place_id) VALUES ('d1','tenant-a','visible','child'),('d2','tenant-a','hidden','outside'),('future','tenant-b','future',NULL)");
        jdbc.update("INSERT INTO alarms VALUES ('a1','tenant-a','d1'),('a2','tenant-a','d2'),('b1','tenant-b','future')");
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,device_id,source_message_id) VALUES ('m1','tenant-a','d1','one'),('m2','tenant-a','d2','two'),('unbound','tenant-a',NULL,'free'),('b-unbound','tenant-b',NULL,'free-b')");
        jdbc.update("INSERT INTO notifications VALUES ('n1','tenant-a','d1'),('n2','tenant-a','d2')");
        jdbc.update("INSERT INTO device_groups VALUES ('g1','tenant-a')");
        jdbc.update("INSERT INTO device_group_members VALUES ('g1','tenant-a','d2')");
        scopes = new DeviceScopes(jdbc);
        messageQueries = new com.fireplatform.ingestion.MessageQueries(jdbc, () -> 25L,
            new com.fireplatform.shared.IdempotencyStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC()), scopes, new com.fireplatform.shared.ScopeFilter(jdbc));
    }

    @AfterEach void closeDatabase() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test void unqualifiedOrReservedColumnsFailClosedInsteadOfCorrelatingToAuthorizationRows() {
        assertThatThrownBy(() -> scopes.predicate("set-actor", "tenant_id", "device_id"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scopes.predicate("set-actor", "devices.tenant_id", "id"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scopes.predicate("set-actor", "__authz_scope.tenant_id", "devices.id"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scopes.placePredicate("set-actor", "__authz_scope.tenant_id", "p.id"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scopes.allPredicate("set-actor", "__authz_scope.tenant_id"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void allScopeInAnotherTenantNeverWidensThisTenantDeviceSet() {
        jdbc.update("INSERT INTO user_device_scopes VALUES ('mixed-actor','tenant-a','DEVICE_SET',1),('mixed-actor','tenant-b','ALL_TENANT',1)");
        jdbc.update("INSERT INTO user_device_scope_devices VALUES ('mixed-actor','tenant-a','d1')");
        assertThat(deviceIds("mixed-actor", "tenant-a")).containsExactly("d1");
        assertThat(deviceIds("mixed-actor", "tenant-b")).containsExactly("future");
    }

    private java.util.List<String> deviceIds(String actor, String tenant) {
        var f = scopes.predicate(actor, "d.tenant_id", "d.id");
        var args = new java.util.ArrayList<Object>(); args.add(tenant); args.addAll(f.bindings());
        return jdbc.queryForList("SELECT d.id FROM devices d WHERE d.tenant_id=? AND (" + f.sql() + ") ORDER BY d.id", String.class, args.toArray());
    }

    @Test void setAndAllScopesReturnOnlyAuthorizedRowsIncludingFutureDevices() {
        assertThat(deviceIds("set-actor", "tenant-a")).containsExactly("d1");
        assertThat(deviceIds("all-actor", "tenant-b")).containsExactly("future");
        assertThat(deviceIds("set-actor", "tenant-b")).isEmpty();
        assertThat(deviceIds("empty-actor", "tenant-a")).isEmpty();
        assertThat(deviceIds("missing-actor", "tenant-a")).isEmpty();
    }

    @Test void predicatesCorrelateToOuterDeviceAndAlarmAliasesWithoutCrossTenantLeak() {
        var device = scopes.predicate("set-actor", "d.tenant_id", "d.id");
        var alarm = scopes.predicate("set-actor", "s.tenant_id", "s.device_id");
        assertThat(jdbc.queryForList("SELECT d.id FROM devices d WHERE (" + device.sql() + ") ORDER BY d.id", String.class, device.bindings().toArray())).containsExactly("d1");
        assertThat(jdbc.queryForList("SELECT s.id FROM alarms s WHERE (" + alarm.sql() + ") ORDER BY s.id", String.class, alarm.bindings().toArray())).containsExactly("a1");
        var notification = scopes.predicate("set-actor", "s.tenant_id", "s.device_id");
        assertThat(jdbc.queryForList("SELECT s.id FROM notifications s WHERE (" + notification.sql() + ") ORDER BY s.id", String.class, notification.bindings().toArray())).containsExactly("n1");
        var otherTenant = scopes.predicate("all-actor", "s.tenant_id", "s.device_id");
        assertThat(jdbc.queryForList("SELECT s.id FROM alarms s WHERE (" + otherTenant.sql() + ") ORDER BY s.id", String.class, otherTenant.bindings().toArray())).containsExactly("b1");
    }

    @Test void messageQueriesEnforceBoundUnboundAndEmptyVisiblePageResults() {
        var restrictedPage = messageQueries.messages("set-actor", "tenant-a", com.fireplatform.shared.ScopeFilter.Selection.all(), 1, 25, "", null);
        assertThat(restrictedPage.total()).isEqualTo(1);
        assertThat(restrictedPage.items()).extracting(com.fireplatform.ingestion.IngestionModels.RawSummary::id).containsExactly("m1");
        assertThat(messageQueries.messages("set-actor", "tenant-a", com.fireplatform.shared.ScopeFilter.Selection.all(), 1, 25, "two", null).total()).isZero();
        var allPage = messageQueries.messages("all-actor", "tenant-b", com.fireplatform.shared.ScopeFilter.Selection.all(), 1, 25, "", null);
        assertThat(allPage.total()).isEqualTo(1);
        assertThat(allPage.items()).extracting(com.fireplatform.ingestion.IngestionModels.RawSummary::id).containsExactly("b-unbound");
    }

    @Test void groupMembershipDoesNotGrantAndScopeChangesAreObservedByExistingActor() {
        assertThat(deviceIds("set-actor", "tenant-a")).doesNotContain("d2");
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id='set-actor' AND tenant_id='tenant-a' AND device_id='d1'");
        jdbc.update("INSERT INTO user_device_scope_devices VALUES ('set-actor','tenant-a','d2')");
        jdbc.update("UPDATE user_device_scopes SET revision=revision+1 WHERE user_id='set-actor' AND tenant_id='tenant-a'");
        assertThat(deviceIds("set-actor", "tenant-a")).containsExactly("d2");
        jdbc.update("DELETE FROM user_device_scopes WHERE user_id='set-actor' AND tenant_id='tenant-a'");
        assertThat(deviceIds("set-actor", "tenant-a")).isEmpty();
    }

    /** PLACE_SUBTREE resolves the current place hierarchy, so future devices enter and moved devices leave without rewriting scope rows. */
    @Test void placeSubtreeScopeFollowsCurrentPlacementAndFutureDescendantDevices() {
        var stored = scopes.scope("place-actor", "tenant-a");
        assertThat(stored.configured()).isTrue();
        assertThat(stored.mode()).isEqualTo("PLACE_SUBTREE");
        assertThat(stored.deviceIds()).isEmpty();
        assertThat(stored.placeIds()).containsExactly("root");

        assertThat(deviceIds("place-actor", "tenant-a")).containsExactly("d1");
        assertThat(scopes.contains("place-actor", "tenant-a", "d2")).isFalse();
        assertThat(scopes.contains("place-actor", "tenant-b", "future")).isFalse();

        jdbc.update("INSERT INTO devices(id,tenant_id,name,place_id) VALUES ('d-future','tenant-a','future descendant','grand')");
        assertThat(deviceIds("place-actor", "tenant-a")).containsExactlyInAnyOrder("d1", "d-future");
        assertThat(scopes.contains("place-actor", "tenant-a", "d-future")).isTrue();

        // Moving a device to a place outside the stored root removes it; the stored root set is untouched.
        jdbc.update("UPDATE devices SET place_id='outside' WHERE id='d1'");
        assertThat(deviceIds("place-actor", "tenant-a")).containsExactly("d-future");
        assertThat(scopes.contains("place-actor", "tenant-a", "d1")).isFalse();
        assertThat(jdbc.queryForObject("SELECT revision FROM user_device_scopes WHERE user_id='place-actor' AND tenant_id='tenant-a'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT place_id FROM user_device_scope_places WHERE user_id='place-actor' AND tenant_id='tenant-a'", String.class)).containsExactly("root");
    }

    @Test void placeCoverageUsesAncestryAndNeverWidensAcrossModeOrTenant() {
        assertThat(scopes.coversPlace("place-actor", "tenant-a", "root")).isTrue();
        assertThat(scopes.coversPlace("place-actor", "tenant-a", "child")).isTrue();
        assertThat(scopes.coversPlace("place-actor", "tenant-a", "grand")).isTrue();
        assertThat(scopes.coversPlace("place-actor", "tenant-a", "outside")).isFalse();
        assertThat(scopes.coversPlace("place-actor", "tenant-a", "missing")).isFalse();
        assertThat(scopes.coversPlace("place-actor", "tenant-b", "root-b")).isFalse();
        assertThat(scopes.coversPlace("all-actor", "tenant-b", "root-b")).isTrue();
        assertThat(scopes.coversPlace("all-actor", "tenant-a", "root")).isFalse();
        assertThat(scopes.coversPlace("set-actor", "tenant-a", "root")).isFalse();
        assertThat(scopes.coversPlaces("place-actor", "tenant-a", java.util.List.of("root", "grand"))).isTrue();
        assertThat(scopes.coversPlaces("place-actor", "tenant-a", java.util.List.of("outside"))).isFalse();
        assertThat(scopes.coversPlaces("set-actor", "tenant-a", java.util.List.of("root"))).isFalse();

        var placeFilter = scopes.placePredicate("place-actor", "p.tenant_id", "p.id");
        assertThat(jdbc.queryForList("SELECT p.id FROM places p WHERE p.tenant_id='tenant-a' AND (" + placeFilter.sql() + ") ORDER BY p.id",
            String.class, placeFilter.bindings().toArray())).containsExactly("child", "grand", "root");
    }

    /** Bulk membership must answer every mode from stored rows; a place root grants only its current descendants. */
    @Test void bulkMembershipCombinesAllSetAndPlaceModesWithoutCrossTenantOrFuturePlaceLeak() {
        assertThat(scopes.usersWithDevice(java.util.List.of("set-actor", "all-actor", "place-actor", "empty-actor", "missing-actor"), "tenant-a", "d1"))
            .containsExactlyInAnyOrder("set-actor", "place-actor");
        assertThat(scopes.usersWithDevice(java.util.List.of("set-actor", "all-actor", "place-actor"), "tenant-a", "d2")).isEmpty();
        assertThat(scopes.usersWithDevice(java.util.List.of("set-actor", "all-actor", "place-actor"), "tenant-b", "future"))
            .containsExactlyInAnyOrder("all-actor");
        assertThat(scopes.usersWithDevice(java.util.List.of(), "tenant-a", "d1")).isEmpty();
    }

    /** Existing ALL_TENANT and DEVICE_SET receipts must keep their canonical fingerprint when placement changes. */
    @Test void allAndSetFingerprintsStayStableWhenPlacementOrRevisionChanges() {
        String setFingerprint = scopes.fingerprint("set-actor", "tenant-a");
        String allFingerprint = scopes.fingerprint("all-actor", "tenant-b");
        String placeFingerprint = scopes.fingerprint("place-actor", "tenant-a");

        jdbc.update("UPDATE devices SET place_id='grand' WHERE id='d1'");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,place_id) VALUES ('d-added','tenant-a','added','child')");
        jdbc.update("UPDATE user_device_scopes SET revision=revision+1 WHERE user_id='set-actor' AND tenant_id='tenant-a'");

        assertThat(scopes.fingerprint("set-actor", "tenant-a")).isEqualTo(setFingerprint);
        assertThat(scopes.fingerprint("all-actor", "tenant-b")).isEqualTo(allFingerprint);
        assertThat(scopes.fingerprint("place-actor", "tenant-a")).isEqualTo(placeFingerprint);

        jdbc.update("INSERT INTO user_device_scope_devices VALUES ('set-actor','tenant-a','d2')");
        assertThat(scopes.fingerprint("set-actor", "tenant-a")).isNotEqualTo(setFingerprint);
        jdbc.update("INSERT INTO user_device_scope_places VALUES ('place-actor','tenant-a','grand')");
        assertThat(scopes.fingerprint("place-actor", "tenant-a")).isNotEqualTo(placeFingerprint);
    }
    @Test void hiddenAndUnboundBindingStatesRemainDistinct() {
        String card = "123e4567-e89b-12d3-a456-426614174000";
        String timestamp = "2026-10-30T12:00:00.000000Z";
        var concealed = new com.fireplatform.simbinding.SimBindingModels.State(card, "tenant-a", null,
            com.fireplatform.simbinding.SimBindingModels.DeviceVisibility.CONCEALED, 3, timestamp);
        var unbound = new com.fireplatform.simbinding.SimBindingModels.State(card, "tenant-a", null,
            com.fireplatform.simbinding.SimBindingModels.DeviceVisibility.UNBOUND, 3, timestamp);
        assertThat(com.fireplatform.simbinding.SimBindingProjection.valid(concealed, "tenant-a", card).deviceVisibility())
            .isEqualTo(com.fireplatform.simbinding.SimBindingModels.DeviceVisibility.CONCEALED);
        assertThat(com.fireplatform.simbinding.SimBindingProjection.valid(unbound, "tenant-a", card).deviceVisibility())
            .isEqualTo(com.fireplatform.simbinding.SimBindingModels.DeviceVisibility.UNBOUND);
    }
}
