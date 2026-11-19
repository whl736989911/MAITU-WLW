package com.fireplatform.ruleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records compile attempts outside the rejected transaction so per-node errors survive a rollback.
 * It never touches a consumer.
 */
@Service
public class RuleFlowReports {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DatabaseTime time;

    public RuleFlowReports(JdbcTemplate jdbc, ObjectMapper json, DatabaseTime time) {
        this.jdbc = jdbc; this.json = json; this.time = time;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String tenant, String flowId, long draftRevision, String planHash, boolean ok,
            List<RuleFlowModels.Issue> issues, String actorId) {
        String payload;
        try { payload = json.writeValueAsString(issues); }
        catch (Exception failure) { throw new IllegalStateException("编译结果无法序列化", failure); }
        jdbc.update("INSERT INTO rule_flow_compile_reports(id,tenant_id,flow_id,draft_revision,plan_hash,ok,issues_json,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), tenant, flowId, draftRevision, planHash, ok, payload, actorId, time.now());
    }
}
