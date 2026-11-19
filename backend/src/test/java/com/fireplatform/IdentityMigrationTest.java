package com.fireplatform;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class IdentityMigrationTest {
    @Test void v3ProtectsOnlyBootstrapAndDoesNotPromoteOrdinaryUsersOrLoseData() {
        String url="jdbc:h2:mem:identity-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("2").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('existing-unit','original unit')");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES ('bootstrap-admin','root','root','PLATFORM','old-root-hash',FALSE,TRUE)");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled) VALUES ('ordinary','ordinary','ordinary','CUSTOMER','existing-unit','old-user-hash',FALSE,TRUE)");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('ordinary','summary:read')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','existing-unit')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES ('existing-device','existing-unit','original-device','upgrade-identity-eui','model','address','FIRE')");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision) VALUES ('existing-alarm','existing-unit','existing-device','original-device','address','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',5)");
        Flyway.configure().dataSource(url,"sa","").target("3").load().migrate();
        assertThat(jdbc.queryForObject("SELECT is_protected FROM app_users WHERE id='bootstrap-admin'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_protected FROM app_users WHERE id='ordinary'",Boolean.class)).isFalse();
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='ordinary'",String.class)).containsExactly("summary:read");
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'",String.class)).contains("units:write","users:write","platform-users:reset-password");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id='ordinary'",String.class)).isEqualTo("old-user-hash");
        assertThat(jdbc.queryForObject("SELECT revision FROM alarms WHERE id='existing-alarm'",Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT enabled FROM tenants WHERE id='existing-unit'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants",Long.class)).isEqualTo(1);
        Flyway.configure().dataSource(url,"sa","").target("3").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants",Long.class)).isEqualTo(1);
    }
}
