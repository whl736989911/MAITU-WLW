package com.fireplatform.shared;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 审计表归属 shared：保留清理只通过这里执行，避免其它域直接写 audit_log。 */
@Service
public class AuditRetention {
    private final JdbcTemplate jdbc;
    public AuditRetention(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public long countBefore(String tenant,OffsetDateTime cutoff) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND occurred_at<?",Long.class,tenant,cutoff);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public long purgeBefore(String tenant,OffsetDateTime cutoff) {
        return jdbc.update("DELETE FROM audit_log WHERE tenant_id=? AND occurred_at<?",tenant,cutoff);
    }
}
