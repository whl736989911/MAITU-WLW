package com.fireplatform.ingestion;

import com.fireplatform.configuration.RuntimeConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries only post-V54 authenticated captures; legacy rows are never candidates. */
@Component
public class GenericMessageApplyWorker {
    private final JdbcTemplate jdbc; private final RuntimeConfig config; private final MessageApplyCommands applies;
    public GenericMessageApplyWorker(JdbcTemplate jdbc,RuntimeConfig config,MessageApplyCommands applies){this.jdbc=jdbc;this.config=config;this.applies=applies;}
    @Scheduled(fixedDelayString="${app.task-poll-ms:500}",initialDelayString="${app.task-poll-ms:500}")
    public void poll(){if(!config.genericMessageWorkerEnabled())return;for(String id:jdbc.queryForList("SELECT id FROM raw_messages WHERE capture_provenance='AUTHENTICATED_SOURCE' AND binding_state='BOUND' AND application_state='NOT_APPLIED' ORDER BY received_at,id LIMIT 20",String.class)){try{applies.applyCaptured(id);}catch(RuntimeException ignored){/* A later poll retries transient failures; durable raw capture remains authoritative. */}}}
}
