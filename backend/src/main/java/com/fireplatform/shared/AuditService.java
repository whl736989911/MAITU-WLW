package com.fireplatform.shared;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public AuditService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    public void record(String actor, String tenant, String action, String target, String outcome, String requestId) {
        jdbc.update("INSERT INTO audit_log(id,actor_id,tenant_id,action,target_id,outcome,request_id,occurred_at,client_platform) VALUES (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), actor, tenant, action, target, outcome, requestId, OffsetDateTime.now(clock), clientPlatform());
    }
    /** Access-change basis is retained verbatim but is not part of the public masked audit DTO. */
    public void recordReason(String actor, String tenant, String action, String target, String outcome, String requestId, String reason) {
        jdbc.update("INSERT INTO audit_log(id,actor_id,tenant_id,action,target_id,outcome,request_id,occurred_at,reason,client_platform) VALUES (?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), actor, tenant, action, target, outcome, requestId, OffsetDateTime.now(clock), reason, clientPlatform());
    }
    /** Client attribution is descriptive, never an authorization or device-origin assertion. */
    private static String clientPlatform() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return "SYSTEM";
        var request = attributes.getRequest();
        String declared = request.getHeader("X-Client-Platform");
        if ("APP".equals(declared) || "WEB".equals(declared) || "API".equals(declared)) return declared;
        return request.getHeader("Sec-Fetch-Mode") == null ? "API" : "WEB";
    }
}
