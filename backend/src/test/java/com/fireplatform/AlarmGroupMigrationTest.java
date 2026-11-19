package com.fireplatform;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AlarmGroupMigrationTest {
    record Db(String url,JdbcTemplate jdbc) { }
    Db db(String version){String url="jdbc:h2:mem:alarm-group-migration-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";Flyway.configure().dataSource(url,"sa","").target(version).load().migrate();return new Db(url,new JdbcTemplate(new DriverManagerDataSource(url,"sa","")));}
    void latest(Db db){Flyway.configure().dataSource(db.url(),"sa","").target("71").load().migrate();}
    List<String> columns(JdbcTemplate j,String table){return j.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name=? ORDER BY ordinal_position",String.class,table);}
    void identities(JdbcTemplate j,String tenant,String device){j.update("INSERT INTO tenants(id,name) VALUES (?,?)",tenant,"Unit "+tenant);j.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES (?,?,?,?,'m','Address','NORMAL')","device-"+tenant,tenant,"Device "+tenant,"eui-"+tenant);}
    void bootstrapUser(JdbcTemplate j){j.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected) VALUES ('test-actor','test-actor','Test actor','PLATFORM',NULL,'hash',FALSE,TRUE,FALSE)");}
    String alarm(JdbcTemplate j,String tenant,String device){String id=UUID.randomUUID().toString();j.update("INSERT INTO alarms(id,tenant_id,device_id,event_type,occurred_at,physical_state,response_state,revision,is_simulated,device_name,address) VALUES (?,?,?,'FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',0,FALSE,'Device','Address')",id,tenant,device);return id;}
    String session(JdbcTemplate j,String tenant,String device){String id=UUID.randomUUID().toString();j.update("INSERT INTO alarm_sessions(id,tenant_id,device_id,device_name,address,event_type,group_by,policy_revision,window_seconds,first_source_at,last_source_at,open_slot,closed_at,revision,created_at,updated_at) VALUES (?,?,?,'Device','Address','FIRE','DEVICE_EVENT_TYPE',1,60,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'OPEN',NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,tenant,device);return id;}

    @Test void latestCreatesOnlyEmptyLogicalTablesWithoutSeedingPoliciesOrPermissions(){
        var db=db("71");var j=db.jdbc();
        assertThat(columns(j,"alarm_merge_policies")).containsExactly("tenant_id","enabled","window_seconds","group_by","scope_kind","revision","updated_by","updated_at");
        assertThat(columns(j,"alarm_merge_policy_revisions")).contains("reason","updated_by");
        assertThat(columns(j,"alarm_sessions")).contains("policy_revision","window_seconds","first_source_at","last_source_at","open_slot","closed_at","revision");
        assertThat(columns(j,"alarm_session_members")).containsExactly("session_id","tenant_id","alarm_id","device_id","source_at","added_at");
        assertThat(columns(j,"alarm_session_events")).contains("event_kind","recorded_at");
        assertThat(columns(j,"alarm_notification_claims")).contains("session_id","rule_id","contact_id","channel","level","occurrence_key","policy_revision","status","attempt_no");
        for(String table:List.of("alarm_merge_policies","alarm_merge_policy_revisions","alarm_sessions","alarm_session_members","alarm_session_events","alarm_notification_claims")) assertThat(j.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).as(table).isZero();
        assertThat(j.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE permission IN ('alarm-config:read','alarm-config:write','alarm-merge:read','alarm-merge:write')",Long.class)).isZero();
        assertThat(j.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='71' AND success=TRUE",Long.class)).isEqualTo(1);
    }

    @Test void upgradePreservesEveryPhysicalAlarmAndPermissionRow(){
        var db=db("70");var j=db.jdbc();identities(j,"a","device-a");bootstrapUser(j);
        String alarm=alarm(j,"a","device-a");
        var alarmBefore=j.queryForMap("SELECT * FROM alarms WHERE id=?",alarm);
        var factsBefore=j.queryForList("SELECT user_id,permission FROM user_permissions ORDER BY user_id,permission");
        latest(db);
        assertThat(j.queryForMap("SELECT * FROM alarms WHERE id=?",alarm)).isEqualTo(alarmBefore);
        assertThat(j.queryForList("SELECT user_id,permission FROM user_permissions ORDER BY user_id,permission")).isEqualTo(factsBefore);
        assertThat(j.queryForObject("SELECT COUNT(*) FROM alarm_sessions",Long.class)).isZero();
    }

    @Test void policyRequiresExplicitWindowAndCompositeTenantForeignKeysRejectCrossUnitPairings(){
        var db=db("71");var j=db.jdbc();identities(j,"a","device-a");identities(j,"b","device-b");bootstrapUser(j);
        String alarmA=alarm(j,"a","device-a"),alarmB=alarm(j,"b","device-b");
        assertThatThrownBy(()->j.update("INSERT INTO alarm_merge_policies(tenant_id,enabled,window_seconds,updated_by,updated_at) VALUES('a',TRUE,NULL,'test-actor',CURRENT_TIMESTAMP)")).isInstanceOf(DataAccessException.class);
        j.update("INSERT INTO alarm_merge_policies(tenant_id,enabled,window_seconds,updated_by,updated_at) VALUES('a',TRUE,60,'test-actor',CURRENT_TIMESTAMP)");
        String sessionA=session(j,"a","device-a"),sessionB=session(j,"b","device-b");
        j.update("INSERT INTO alarm_session_members(session_id,tenant_id,alarm_id,device_id,source_at,added_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sessionA,"a",alarmA,"device-a");
        assertThatThrownBy(()->j.update("INSERT INTO alarm_session_members(session_id,tenant_id,alarm_id,device_id,source_at,added_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sessionA,"a",alarmB,"device-b")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->j.update("INSERT INTO alarm_session_members(session_id,tenant_id,alarm_id,device_id,source_at,added_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sessionB,"a",alarmA,"device-a")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->j.update("INSERT INTO alarm_notification_claims(id,tenant_id,session_id,alarm_id,event_type,rule_id,contact_id,channel,level,occurrence_key,policy_revision,status,attempt_no,created_at,updated_at) VALUES(?,?,?,?,'FIRE','rule','contact','SMS',1,'INITIAL',1,'CLAIMED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),"a",sessionA,alarmB)).isInstanceOf(DataAccessException.class);
        j.update("INSERT INTO alarm_notification_claims(id,tenant_id,session_id,alarm_id,event_type,rule_id,contact_id,channel,level,occurrence_key,policy_revision,status,attempt_no,created_at,updated_at) VALUES(?,?,?,?,'FIRE','rule','contact','SMS',1,'INITIAL',1,'CLAIMED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),"a",sessionA,alarmA);
        assertThatThrownBy(()->j.update("INSERT INTO alarm_notification_claims(id,tenant_id,session_id,alarm_id,event_type,rule_id,contact_id,channel,level,occurrence_key,policy_revision,status,attempt_no,created_at,updated_at) VALUES(?,?,?,?,'FIRE','rule','contact','SMS',1,'INITIAL',1,'CLAIMED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),"a",sessionA,alarmA)).isInstanceOf(DataAccessException.class);
    }
}
