package com.fireplatform;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** N04 V18: five tables, effective-permission view, legacy explicit ALL_TENANT, root-only management keys. */
class N04IdentityMigrationTest {
    private static final String URL = "jdbc:h2:mem:n04-identity-migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test void v18KeepsHistoryAndGrantsOnlyTheProtectedRootNewManagementKeys() {
        Flyway.configure().dataSource(URL, "sa", "").target("17").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, "sa", ""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit-a','A单位')");
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit-b','B单位')");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected)"
            + " VALUES ('bootstrap-admin','root','root','PLATFORM','root-hash',FALSE,TRUE,TRUE)");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected)"
            + " VALUES ('platform-plain','platform-plain','plain','PLATFORM','plain-hash',FALSE,TRUE,FALSE)");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected)"
            + " VALUES ('customer-plain','customer-plain','customer','CUSTOMER','unit-a','customer-hash',FALSE,TRUE,FALSE)");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('platform-plain','summary:read')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','unit-a')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('platform-plain','unit-b')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state)"
            + " VALUES ('legacy-device','unit-a','legacy','legacy-eui','demo','address','NORMAL')");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision)"
            + " VALUES ('legacy-alarm','unit-a','legacy-device','legacy','address','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',5)");
        jdbc.update("INSERT INTO alarm_trace_entries(id,tenant_id,alarm_id,action,revision_after,after_physical_state,after_response_state,recorded_at,actor_kind,origin)"
            + " VALUES ('legacy-entry','unit-a','legacy-alarm','MIGRATION_BASELINE',5,'FIRE','UNHANDLED',CURRENT_TIMESTAMP,'LEGACY_UNKNOWN','MIGRATION_SNAPSHOT')");
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at) VALUES ('legacy-entry','unit-a',CURRENT_TIMESTAMP),('unattributed-entry','unit-a',CURRENT_TIMESTAMP)");

        Flyway.configure().dataSource(URL, "sa", "").target("20").load().migrate();

        // 58 pre-N04 public tables + flyway_schema_history = 59 before V18; V18 adds exactly five (59 -> 64).
        Long tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'", Long.class);
        assertThat(tables).isEqualTo(64L);
        Long business = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'"
            + " AND table_name<>'flyway_schema_history'", Long.class);
        assertThat(business).isEqualTo(63L);
        Long view = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='VIEW'"
            + " AND table_name='effective_user_permissions'", Long.class);
        assertThat(view).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_roles", Long.class)).isZero();

        // Legacy explicit ALL_TENANT: customer home unit and every existing platform grant, including future devices.
        assertThat(jdbc.queryForList("SELECT user_id FROM user_device_scopes WHERE mode='ALL_TENANT' AND tenant_id='unit-a' ORDER BY user_id", String.class))
            .containsExactly("bootstrap-admin", "customer-plain");
        assertThat(jdbc.queryForList("SELECT user_id FROM user_device_scopes WHERE mode='ALL_TENANT' AND tenant_id='unit-b' ORDER BY user_id", String.class))
            .containsExactly("platform-plain");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scope_devices", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT revision FROM user_device_scopes", Long.class)).containsOnly(1L);

        // Only the protected bootstrap root receives the six new management keys.
        List<String> newKeys = List.of("roles:read", "roles:write", "users:assign-roles", "users:assign-scope",
            "platform-users:assign-roles", "platform-users:assign-scope");
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'", String.class)).containsAll(newKeys);
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='platform-plain'", String.class)).containsExactly("summary:read");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id='customer-plain'", Long.class)).isZero();

        // The read model is a UNION of direct and enabled, non-deleted role permissions.
        jdbc.update("INSERT INTO access_roles(id,code,name,user_type,enabled,deleted,revision) VALUES ('role-on','role_on','enabled','PLATFORM',TRUE,FALSE,0)");
        jdbc.update("INSERT INTO access_role_permissions(role_id,permission) VALUES ('role-on','alarm:history')");
        jdbc.update("INSERT INTO access_roles(id,code,name,user_type,enabled,deleted,revision) VALUES ('role-off','role_off','disabled','PLATFORM',FALSE,FALSE,0)");
        jdbc.update("INSERT INTO access_role_permissions(role_id,permission) VALUES ('role-off','settings:write')");
        jdbc.update("INSERT INTO access_roles(id,code,name,user_type,enabled,deleted,revision) VALUES ('role-gone','role_gone','deleted','PLATFORM',TRUE,TRUE,0)");
        jdbc.update("INSERT INTO access_role_permissions(role_id,permission) VALUES ('role-gone','parameters:write')");
        jdbc.update("INSERT INTO access_user_roles(user_id,role_id,user_type) VALUES ('platform-plain','role-on','PLATFORM')");
        jdbc.update("INSERT INTO access_user_roles(user_id,role_id,user_type) VALUES ('platform-plain','role-off','PLATFORM')");
        jdbc.update("INSERT INTO access_user_roles(user_id,role_id,user_type) VALUES ('platform-plain','role-gone','PLATFORM')");
        assertThat(jdbc.queryForList("SELECT permission FROM effective_user_permissions WHERE user_id='platform-plain' ORDER BY permission", String.class))
            .containsExactly("alarm:history", "summary:read");

        // An account created after the migration receives no scope and no automatic role rows.
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected)"
            + " VALUES ('customer-new','customer-new','new','CUSTOMER','unit-a','new-hash',TRUE,TRUE,FALSE)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id='customer-new'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_user_roles WHERE user_id='customer-new'", Long.class)).isZero();

        // Business history is untouched by the identity migration.
        assertThat(jdbc.queryForObject("SELECT revision FROM alarms WHERE id='legacy-alarm'", Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT device_id FROM alarm_change_outbox WHERE id='legacy-entry'",String.class)).isEqualTo("legacy-device");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_outbox WHERE id='unattributed-entry' AND device_id IS NULL",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id='customer-plain'", String.class)).isEqualTo("customer-hash");
        Flyway.configure().dataSource(URL, "sa", "").target("20").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants", Long.class)).isEqualTo(2L);
    }
}
