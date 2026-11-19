package com.fireplatform;

import java.math.BigDecimal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class ProcessingMigrationTest {
    @Test void v6PreservesIntegerZeroNullAndRootOnlyGrantsThenStoresExactFraction() {
        String url="jdbc:h2:mem:processing-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"; Flyway.configure().dataSource(url,"sa","").target("5").load().migrate(); var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit','unit')");
        for(String id:new String[]{"bootstrap-admin","other-protected","ordinary"}) jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,'PLATFORM','hash',FALSE,TRUE,?)",id,id,id,!id.equals("ordinary"));
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,battery_percent) VALUES ('zero','unit','zero','zero','model','address','FIRE',0),('known','unit','known','known','model','address','NORMAL',55),('missing','unit','missing','missing','model','address','UNKNOWN',NULL)");
        Flyway.configure().dataSource(url,"sa","").target("6").load().migrate();
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id='zero'",BigDecimal.class)).isEqualByComparingTo("0"); assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id='known'",BigDecimal.class)).isEqualByComparingTo("55"); assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id='missing'",BigDecimal.class)).isNull();
        jdbc.update("UPDATE devices SET battery_percent=? WHERE id='known'",new BigDecimal("0.10000000000000001")); assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id='known'",BigDecimal.class)).isEqualByComparingTo("0.10000000000000001");
        assertThatThrownBy(()->jdbc.update("UPDATE devices SET battery_percent=101 WHERE id='zero'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'",String.class)).containsExactlyInAnyOrder("tasks:read","tasks:simulate"); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id<>'bootstrap-admin'",Long.class)).isZero();
        for(String table:new String[]{"processing_tasks","processing_attempts","device_task_applications"}) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
        Flyway.configure().dataSource(url,"sa","").target("6").load().migrate(); assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='zero'",String.class)).isEqualTo("FIRE");
    }
}
