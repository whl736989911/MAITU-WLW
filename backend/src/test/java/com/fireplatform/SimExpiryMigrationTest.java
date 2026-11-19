package com.fireplatform;

import com.fireplatform.simexpiry.SimExpiryQueries;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.roles=api","app.simulation-enabled=false","app.simulated-notifications-enabled=false","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:sim-expiry-migration-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class SimExpiryMigrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){IngestionIntegrationTest.environment(registry);}
    @Autowired JdbcTemplate jdbc;
    @Autowired SimExpiryQueries queries;
    @Test void migrationAddsExactlyFiveEmptyIndependentTablesAndNoSeededConfiguration(){for(String table:new String[]{"sim_expiry_config","sim_expiry_recipients","sim_expiry_rounds","sim_expiry_notices","sim_expiry_reads"})assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();var config=queries.config("tenant-a");assertThat(config.enabled()).isFalse();assertThat(config.revision()).isZero();assertThat(config.timezone()).isNull();assertThat(config.lead_days()).isNull();assertThat(config.repeat_interval_days()).isNull();assertThat(config.repeat_count()).isNull();assertThat(config.updated_at()).isNull();}
    @Test void v16UpgradePreservesEveryOldFactExceptTheThreePreciseBootstrapGrants(){
        var fixtures=new SimCardMigrationTest();var db=fixtures.db("16");var j=db.jdbc();fixtures.tenant(j);
        fixtures.user(j,"bootstrap-admin","PLATFORM",true);fixtures.user(j,"other-protected","PLATFORM",true);fixtures.user(j,"ordinary","PLATFORM",false);fixtures.user(j,"actor","PLATFORM",false);
        j.update("INSERT INTO user_permissions(user_id,permission) VALUES ('bootstrap-admin','device:read'),('ordinary','sim-expiry:read')");
        String card=fixtures.card(j,"a","000Case");fixtures.change(j,card,"a",0,"CREATE");
        var old=fixtures.tables(j);assertThat(old).hasSize(54);
        var before=fixtures.facts(j,old);before.put("user_permissions",j.queryForList("SELECT * FROM user_permissions ORDER BY user_id,permission"));
        Flyway.configure().dataSource(db.url(),"sa","").target("17").load().migrate();
        assertThat(fixtures.tables(j)).hasSize(59).containsAll(old);
        var after=fixtures.facts(j,old);after.put("user_permissions",j.queryForList("SELECT * FROM user_permissions WHERE NOT(user_id='bootstrap-admin' AND permission IN ('sim-expiry:read','sim-expiry:handle','sim-expiry:write')) ORDER BY user_id,permission"));
        assertThat(after).isEqualTo(before);
        assertThat(j.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin' AND permission LIKE 'sim-expiry:%'",String.class)).containsExactlyInAnyOrder("sim-expiry:read","sim-expiry:handle","sim-expiry:write");
        assertThat(j.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id='other-protected'",Long.class)).isZero();
        for(String table:List.of("sim_expiry_config","sim_expiry_recipients","sim_expiry_rounds","sim_expiry_notices","sim_expiry_reads"))assertThat(j.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
        var all=fixtures.facts(j,fixtures.tables(j));Flyway.configure().dataSource(db.url(),"sa","").target("17").load().migrate();assertThat(fixtures.facts(j,fixtures.tables(j))).isEqualTo(all);
    }
    @Test void newRightsRequireTheExactProtectedPlatformBootstrapIdentity(){
        var fixtures=new SimCardMigrationTest();
        for(String type:List.of("PLATFORM","CUSTOMER"))for(boolean protectedUser:List.of(false,true)){
            var db=fixtures.db("16");var j=db.jdbc();fixtures.tenant(j);fixtures.user(j,"bootstrap-admin",type,protectedUser);fixtures.user(j,"other-protected","PLATFORM",true);
            Flyway.configure().dataSource(db.url(),"sa","").target("17").load().migrate();
            assertThat(j.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE permission LIKE 'sim-expiry:%'",Long.class)).isEqualTo(type.equals("PLATFORM")&&protectedUser?3L:0L);
            assertThat(j.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id='other-protected'",Long.class)).isZero();
        }
        var absent=fixtures.db("16");Flyway.configure().dataSource(absent.url(),"sa","").target("17").load().migrate();assertThat(absent.jdbc().queryForObject("SELECT COUNT(*) FROM user_permissions",Long.class)).isZero();
    }
}
