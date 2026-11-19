package com.fireplatform.device;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 设备命令归 device 所有；保留清理只通过这里执行，且只删终态。 */
@Service
public class DeviceCommandRetention {
    private final JdbcTemplate jdbc;
    public DeviceCommandRetention(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public long countBefore(String tenant,OffsetDateTime cutoff) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM device_commands WHERE tenant_id=? AND created_at<? AND status IN ('ACKED','REJECTED','TIMEOUT','UNCERTAIN')",Long.class,tenant,cutoff);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public long purgeBefore(String tenant,OffsetDateTime cutoff) {
        jdbc.update("DELETE FROM device_command_attempts WHERE command_id IN (SELECT id FROM device_commands WHERE tenant_id=? AND created_at<? AND status IN ('ACKED','REJECTED','TIMEOUT','UNCERTAIN'))",tenant,cutoff);
        return jdbc.update("DELETE FROM device_commands WHERE tenant_id=? AND created_at<? AND status IN ('ACKED','REJECTED','TIMEOUT','UNCERTAIN')",tenant,cutoff);
    }
}
