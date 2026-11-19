package com.fireplatform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AlarmTraceMigrationTest {
    @Test void v8PreservesExistingFactsAndOnlyRecordsHonestMigrationBaselinesAndRootPermission() {
        String url="jdbc:h2:mem:alarm-trace-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"; Flyway.configure().dataSource(url,"sa","").target("7").load().migrate(); var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa","")); jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit','unit')");
        for(String id:new String[]{"bootstrap-admin","other-protected","ordinary"}) jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,'PLATFORM','hash',FALSE,TRUE,?)",id,id,id,!id.equals("ordinary"));
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected) VALUES ('customer','customer','Customer','CUSTOMER','unit','hash',FALSE,TRUE,FALSE)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,battery_percent) VALUES ('zero','unit','zero','zero','model','address','FIRE',0),('missing','unit','missing','missing','model','address','UNKNOWN',NULL)");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,conclusion,remark,revision,is_simulated) VALUES ('old-handled','unit','zero','historical name','historical address','FIRE',TIMESTAMP WITH TIME ZONE '2001-01-01 00:00:00+00','FIRE','HANDLED','TEST','last legacy note',7,TRUE),('old-zero','unit','missing','unknown snapshot','address','FIRE',TIMESTAMP WITH TIME ZONE '2002-01-01 00:00:00+00','NORMAL','UNHANDLED',NULL,NULL,0,FALSE)");
        var alarms=jdbc.queryForList("SELECT * FROM alarms ORDER BY id"); var devices=jdbc.queryForList("SELECT * FROM devices ORDER BY id"); var users=jdbc.queryForList("SELECT * FROM app_users ORDER BY id"); var globals=jdbc.queryForList("SELECT * FROM numeric_settings ORDER BY setting_key");
        Flyway.configure().dataSource(url,"sa","").target("8").load().migrate(); assertThat(jdbc.queryForList("SELECT * FROM alarms ORDER BY id")).isEqualTo(alarms); assertThat(jdbc.queryForList("SELECT * FROM devices ORDER BY id")).isEqualTo(devices); assertThat(jdbc.queryForList("SELECT * FROM app_users ORDER BY id")).isEqualTo(users); assertThat(jdbc.queryForList("SELECT * FROM numeric_settings ORDER BY setting_key")).isEqualTo(globals);
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'",String.class)).containsExactly("alarm:history"); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id<>'bootstrap-admin'",Long.class)).isZero();
        var rows=jdbc.queryForList("SELECT * FROM alarm_trace_entries ORDER BY alarm_id"); assertThat(rows).hasSize(2); for(var row:rows) { assertThat(row.get("action")).isEqualTo("MIGRATION_BASELINE"); assertThat(row.get("origin")).isEqualTo("MIGRATION_SNAPSHOT"); assertThat(row.get("actor_kind")).isEqualTo("LEGACY_UNKNOWN"); for(String field:new String[]{"revision_before","before_physical_state","before_response_state","before_conclusion","occurred_at","actor_id","actor_name"}) assertThat(row.get(field)).isNull(); assertThat(row.get("recorded_at")).isNotNull(); }
        assertThat(rows.getFirst().get("revision_after")).isEqualTo(7L); assertThat(rows.getFirst().get("remark")).isEqualTo("last legacy note"); assertThat(rows.get(1).get("revision_after")).isEqualTo(0L); assertThat(rows.get(1).get("remark")).isNull();
        assertThatThrownBy(()->jdbc.update("INSERT INTO alarm_trace_entries(id,tenant_id,alarm_id,action,revision_after,after_physical_state,after_response_state,recorded_at,actor_kind,origin) VALUES ('duplicate','unit','old-zero','MIGRATION_BASELINE',0,'NORMAL','UNHANDLED',CURRENT_TIMESTAMP,'LEGACY_UNKNOWN','MIGRATION_SNAPSHOT')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE alarm_trace_entries SET action='FIRE_CREATED',origin='APPLICATION',actor_kind='SYSTEM' WHERE alarm_id='old-handled'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Flyway.configure().dataSource(url,"sa","").target("8").load().migrate(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_trace_entries",Long.class)).isEqualTo(2);
    }
}
