package com.fireplatform;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AuditRecordMigrationTest {
    record Db(String url,JdbcTemplate jdbc) { }
    Db v11(){String url="jdbc:h2:mem:audit-upgrade-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";Flyway.configure().dataSource(url,"sa","").target("11").load().migrate();var j=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));j.update("INSERT INTO tenants(id,name) VALUES ('unit','original unit')");return new Db(url,j);}
    void account(JdbcTemplate j,String id,String type,boolean protectedRoot){j.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,?,?,'unchanged-secret-hash',FALSE,TRUE,?)",id,id,id,type,type.equals("CUSTOMER")?"unit":null,protectedRoot);}
    void latest(Db db){Flyway.configure().dataSource(db.url(),"sa","").target("12").load().migrate();}
    Map<String,List<Map<String,Object>>> facts(JdbcTemplate j){var facts=new TreeMap<String,List<Map<String,Object>>>();for(String table:j.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name NOT IN ('flyway_schema_history','user_permissions') ORDER BY table_name",String.class))facts.put(table,j.queryForList("SELECT * FROM "+table));return facts;}
    @Test void v11To12AddsExactlyOneRootPermissionWithoutAnyOtherFactOrSchemaChange(){
        var db=v11();var j=db.jdbc();account(j,"bootstrap-admin","PLATFORM",true);account(j,"other-protected","PLATFORM",true);account(j,"ordinary","PLATFORM",false);account(j,"customer","CUSTOMER",true);
        for(String id:List.of("bootstrap-admin","other-protected","ordinary","customer"))j.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,'summary:read')",id);
        j.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','unit')");
        j.update("INSERT INTO audit_log(id,actor_id,tenant_id,action,target_id,outcome,request_id,occurred_at) VALUES ('legacy-row','bootstrap-admin','unit','OLD_FREE_ACTION','SECRET/path','OLD_RESULT','SECRET_REQUEST',TIMESTAMP WITH TIME ZONE '2001-01-01 00:00:00+00')");
        var before=facts(j);var permissions=j.queryForList("SELECT * FROM user_permissions ORDER BY user_id,permission");latest(db);
        assertThat(facts(j)).isEqualTo(before);assertThat(j.queryForList("SELECT user_id FROM user_permissions WHERE permission='audit:read'",String.class)).containsExactly("bootstrap-admin");
        assertThat(j.queryForList("SELECT * FROM user_permissions WHERE permission<>'audit:read' ORDER BY user_id,permission")).isEqualTo(permissions);
        assertThat(j.queryForObject("SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("12");
        assertThat(j.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='12' AND success=TRUE",Integer.class)).isEqualTo(1);
        var after=facts(j);var rights=j.queryForList("SELECT * FROM user_permissions ORDER BY user_id,permission");latest(db);assertThat(facts(j)).isEqualTo(after);assertThat(j.queryForList("SELECT * FROM user_permissions ORDER BY user_id,permission")).isEqualTo(rights);
    }
    @Test void bootstrapIdAloneOrProtectionAloneDoesNotGrantToCustomerOrUnprotectedPlatform(){
        for(String type:List.of("CUSTOMER","PLATFORM")){var db=v11();account(db.jdbc(),"bootstrap-admin",type,type.equals("CUSTOMER"));account(db.jdbc(),"other-protected","PLATFORM",true);var before=facts(db.jdbc());latest(db);assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM user_permissions WHERE permission='audit:read'",Integer.class)).isZero();assertThat(facts(db.jdbc())).isEqualTo(before);}
    }
    @Test void existingExplicitRightsAreNotDeletedAndRootBackfillIsDuplicateSafe(){
        var db=v11();var j=db.jdbc();account(j,"bootstrap-admin","PLATFORM",true);account(j,"ordinary","PLATFORM",false);
        for(String id:List.of("bootstrap-admin","ordinary"))j.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,'audit:read')",id);
        var before=j.queryForList("SELECT * FROM user_permissions ORDER BY user_id,permission");latest(db);latest(db);assertThat(j.queryForList("SELECT * FROM user_permissions ORDER BY user_id,permission")).isEqualTo(before);
    }
}
