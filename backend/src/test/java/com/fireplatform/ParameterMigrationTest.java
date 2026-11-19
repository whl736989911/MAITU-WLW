package com.fireplatform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class ParameterMigrationTest {
    @Test void v7AddsOnlyEmptyConfigurationTablesAndRootRightsPreservingEveryExistingFact() {
        String url="jdbc:h2:mem:parameter-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"; Flyway.configure().dataSource(url,"sa","").target("6").load().migrate(); var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa","")); jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit','unit')");
        for(String id:new String[]{"bootstrap-admin","other-protected","ordinary"}) jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,'PLATFORM','hash',FALSE,TRUE,?)",id,id,id,!id.equals("ordinary"));
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,battery_percent) VALUES ('zero','unit','zero','zero','model','address','FIRE',0),('missing','unit','missing','missing','model','address','UNKNOWN',NULL)"); var devices=jdbc.queryForList("SELECT * FROM devices ORDER BY id"); var globals=jdbc.queryForList("SELECT * FROM numeric_settings ORDER BY setting_key"); var tenants=jdbc.queryForList("SELECT * FROM tenants");
        Flyway.configure().dataSource(url,"sa","").target("7").load().migrate(); assertThat(jdbc.queryForList("SELECT * FROM devices ORDER BY id")).isEqualTo(devices); assertThat(jdbc.queryForList("SELECT * FROM numeric_settings ORDER BY setting_key")).isEqualTo(globals); assertThat(jdbc.queryForList("SELECT * FROM tenants")).isEqualTo(tenants); assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'",String.class)).containsExactlyInAnyOrder("parameters:read","parameters:write"); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id<>'bootstrap-admin'",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_overrides",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_changes",Long.class)).isZero();
        jdbc.update("INSERT INTO parameter_overrides(id,tenant_id,scope,scope_id,device_id,parameter_key,override_value,revision) VALUES ('policy','unit','DEVICE','zero','zero','offline_threshold_minutes',10,1)");
        assertThatThrownBy(()->jdbc.update("INSERT INTO parameter_overrides(id,tenant_id,scope,scope_id,device_id,parameter_key,override_value,revision) VALUES ('duplicate','unit','DEVICE','zero','zero','offline_threshold_minutes',10,1)")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(long value:new long[]{0,525601}) assertThatThrownBy(()->jdbc.update("UPDATE parameter_overrides SET override_value=? WHERE id='policy'",value)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("UPDATE parameter_overrides SET override_value=NULL,revision=2 WHERE id='policy'"); Flyway.configure().dataSource(url,"sa","").target("7").load().migrate(); assertThat(jdbc.queryForObject("SELECT revision FROM parameter_overrides WHERE id='policy'",Long.class)).isEqualTo(2); assertThat(jdbc.queryForObject("SELECT override_value FROM parameter_overrides WHERE id='policy'",Long.class)).isNull();
    }
}
