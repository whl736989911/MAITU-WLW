package com.fireplatform.ingestion;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原文与预览归 ingestion 所有；保留清理只通过这里执行。 */
@Service
public class RawMessageRetention {
    private final JdbcTemplate jdbc;
    public RawMessageRetention(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public long countBefore(String tenant,OffsetDateTime cutoff) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM raw_messages WHERE tenant_id=? AND received_at<?",Long.class,tenant,cutoff);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public long purgeBefore(String tenant,OffsetDateTime cutoff) {
        jdbc.update("DELETE FROM message_previews WHERE raw_message_id IN (SELECT id FROM raw_messages WHERE tenant_id=? AND received_at<?)",tenant,cutoff);
        return jdbc.update("DELETE FROM raw_messages m WHERE m.tenant_id=? AND m.received_at<? AND NOT EXISTS(SELECT 1 FROM processing_tasks t WHERE t.raw_message_id=m.id) AND NOT EXISTS(SELECT 1 FROM reparse_jobs j WHERE j.raw_message_id=m.id)",tenant,cutoff);
    }
}
