package com.fireplatform;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AlarmChangeMigrationTest {
    @Test void v10ToV11CreatesOnlyThreeFeedTablesWithNoBackfillBusinessFkOrChangedFacts(){
        String url="jdbc:h2:mem:alarm-change-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("10").load().migrate();var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('old-unit','Old')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES ('old-device','old-unit','Old','old-eui','model','address','FIRE')");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision,is_simulated) VALUES ('old-alarm','old-unit','old-device','Old snapshot','Old address','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',0,TRUE)");
        jdbc.update("INSERT INTO alarm_trace_entries(id,tenant_id,alarm_id,action,revision_after,after_physical_state,after_response_state,recorded_at,actor_kind,origin) VALUES ('old-trace','old-unit','old-alarm','FIRE_CREATED',0,'FIRE','UNHANDLED',CURRENT_TIMESTAMP,'SYSTEM','APPLICATION')");
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("tenants","devices","alarms","alarm_trace_entries","numeric_settings","user_permissions"))before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        Flyway.configure().dataSource(url,"sa","").target("11").load().migrate();for(var entry:before.entrySet())assertThat(jdbc.queryForList("SELECT * FROM "+entry.getKey())).isEqualTo(entry.getValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_outbox",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_heads",Long.class)).isZero();assertThat(jdbc.queryForList("SELECT id FROM alarm_change_publisher_coord",Integer.class)).containsExactly(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_name IN ('alarm_change_outbox','alarm_change_heads','alarm_change_publisher_coord') AND constraint_type='FOREIGN KEY'",Long.class)).isZero();
        Flyway.configure().dataSource(url,"sa","").target("11").load().migrate();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_change_outbox",Long.class)).isZero();
    }
    @Test void realCutoverRotatesEveryExistingEpochWithoutRewritingOutboxHistory(){
        String url="jdbc:h2:mem:real-feed-epoch-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("56").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        String oldA=UUID.randomUUID().toString(),oldB=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarm_change_heads(tenant_id,stream_id,last_sequence) VALUES('unit-a',?,2),('unit-b',?,0)",oldA,oldB);
        jdbc.update("INSERT INTO alarm_change_outbox(id,tenant_id,recorded_at,publication_sequence) VALUES('published-a','unit-a',CURRENT_TIMESTAMP,1),('published-b','unit-a',CURRENT_TIMESTAMP,2),('pending-a','unit-a',CURRENT_TIMESTAMP,NULL)");
        var before=jdbc.queryForList("SELECT * FROM alarm_change_outbox ORDER BY id");
        Flyway.configure().dataSource(url,"sa","").target("57").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM alarm_change_outbox ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM alarm_change_heads WHERE tenant_id='unit-a'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT stream_id FROM alarm_change_heads WHERE tenant_id='unit-a'",String.class)).isNotEqualTo(oldA).isNotEqualTo(oldB);
        assertThat(jdbc.queryForObject("SELECT stream_id FROM alarm_change_heads WHERE tenant_id='unit-b'",String.class)).isNotEqualTo(oldB).isNotEqualTo(oldA);
        var after=jdbc.queryForList("SELECT * FROM alarm_change_heads ORDER BY tenant_id");
        Flyway.configure().dataSource(url,"sa","").target("57").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM alarm_change_heads ORDER BY tenant_id")).isEqualTo(after);
    }
}
