package com.fireplatform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MigrationUpgradeTest {
    @Test void existingV1AlarmsReceiveDeviceSnapshotWithoutChangingState() {
        String url="jdbc:h2:mem:upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("1").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('upgrade-tenant','tenant')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES ('upgrade-device','upgrade-tenant','original-name','upgrade-eui','demo','original-address','FIRE')");
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,event_type,occurred_at,physical_state,response_state,revision) VALUES ('upgrade-alarm','upgrade-tenant','upgrade-device','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',0)");
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        var result=jdbc.queryForMap("SELECT device_name,address,physical_state,response_state,revision FROM alarms WHERE id='upgrade-alarm'");
        assertThat(result.get("device_name")).isEqualTo("original-name"); assertThat(result.get("address")).isEqualTo("original-address");
        assertThat(result.get("physical_state")).isEqualTo("FIRE"); assertThat(result.get("response_state")).isEqualTo("UNHANDLED"); assertThat(((Number)result.get("revision")).longValue()).isZero();
    }
}
