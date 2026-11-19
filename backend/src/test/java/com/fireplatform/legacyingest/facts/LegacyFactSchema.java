package com.fireplatform.legacyingest.facts;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Fact-application tests run against the shipped Flyway schema so every column, check and foreign key the
 * production applier relies on is exercised, instead of maintaining a parallel handcrafted schema.
 */
final class LegacyFactSchema {
    private LegacyFactSchema() { }

    static JdbcTemplate migrate(String name) {
        String url = "jdbc:h2:mem:" + name + "-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }

    /** Only the controlled fixture unit and device; no production fallback rows are seeded. */
    static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant','Tenant fixture')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state)"
            + " VALUES('device','tenant','Device','device-eui','model','address','NORMAL')");
    }
}
