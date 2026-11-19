package com.fireplatform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class ProductMigrationTest {
    @Test void v4PreservesOldPhysicalFactsAndAlarmsAddsUnknownWithoutPromotingOrdinaryUsers() {
        String url="jdbc:h2:mem:product-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("3").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit','existing unit')");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected) VALUES ('bootstrap-admin','root','root','PLATFORM','root-hash',FALSE,TRUE,TRUE)");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled) VALUES ('ordinary','ordinary','ordinary','CUSTOMER','unit','ordinary-hash',FALSE,TRUE)");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES ('ordinary','device:read')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,last_message_at,battery_percent,is_simulated) VALUES ('normal','unit','normal','normal-eui','old-model','old-address','NORMAL',CURRENT_TIMESTAMP,0,TRUE)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,last_message_at,battery_percent,is_simulated) VALUES ('fire','unit','fire','fire-eui','old-model','old-address','FIRE',CURRENT_TIMESTAMP,55,TRUE)");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision) VALUES ('alarm','unit','fire','old-fire-snapshot','old-address','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',7)");
        String oldColumns="id,physical_state,last_message_at,battery_percent,is_simulated";
        var before=jdbc.queryForList("SELECT "+oldColumns+" FROM devices ORDER BY id"); var alarm=jdbc.queryForMap("SELECT * FROM alarms WHERE id='alarm'");
        Flyway.configure().dataSource(url,"sa","").target("4").load().migrate();
        assertThat(jdbc.queryForList("SELECT "+oldColumns+" FROM devices ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM alarms WHERE id='alarm'")).isEqualTo(alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE integration_state='LEGACY_SIMULATION' AND product_id IS NULL AND product_version_id IS NULL AND revision=0",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_version_history",Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='ordinary'",String.class)).containsExactly("device:read");
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'",String.class)).contains("products:read","products:write","products:publish","device:write","device:migrate");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES ('new','unit','new','new-eui','model','address','UNKNOWN')");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='new'",String.class)).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->jdbc.update("UPDATE devices SET physical_state='MISSING' WHERE id='new'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Flyway.configure().dataSource(url,"sa","").target("4").load().migrate(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices",Long.class)).isEqualTo(3);
    }
}
