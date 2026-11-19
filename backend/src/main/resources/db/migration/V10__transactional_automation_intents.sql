CREATE TABLE notification_automation_policies (
 tenant_id VARCHAR(64) PRIMARY KEY REFERENCES tenants(id), enabled BOOLEAN NOT NULL, revision BIGINT NOT NULL CHECK(revision>=1), epoch VARCHAR(64) NOT NULL UNIQUE,
 rule_id VARCHAR(64) REFERENCES notification_rules(id), rule_name VARCHAR(200), rule_revision BIGINT, scenario VARCHAR(24), sealed_plan TEXT,
 authorizer_id VARCHAR(64) NOT NULL REFERENCES app_users(id), authorizer_revision BIGINT NOT NULL, tenant_revision BIGINT NOT NULL, changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK((enabled=TRUE AND rule_id IS NOT NULL AND rule_name IS NOT NULL AND rule_revision IS NOT NULL AND scenario IS NOT NULL AND scenario IN ('SUCCESS','REJECTED','RETRY_THEN_SUCCESS','UNCERTAIN') AND sealed_plan IS NOT NULL) OR (enabled=FALSE AND rule_id IS NULL AND rule_name IS NULL AND rule_revision IS NULL AND scenario IS NULL AND sealed_plan IS NULL))
);
CREATE TABLE notification_automation_changes (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL REFERENCES notification_automation_policies(tenant_id), revision BIGINT NOT NULL, epoch VARCHAR(64) NOT NULL UNIQUE,
 enabled BOOLEAN NOT NULL, rule_id VARCHAR(64), rule_name VARCHAR(200), rule_revision BIGINT, scenario VARCHAR(24), sealed_plan TEXT,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id), actor_name VARCHAR(200) NOT NULL, reason VARCHAR(1000) NOT NULL, recorded_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(tenant_id,revision)
);
CREATE TABLE notification_automation_intents (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id), alarm_id VARCHAR(64) NOT NULL UNIQUE, device_id VARCHAR(64) NOT NULL,
 device_name VARCHAR(200) NOT NULL, address VARCHAR(500) NOT NULL, alarm_revision BIGINT NOT NULL, policy_revision BIGINT, epoch VARCHAR(64), rule_name VARCHAR(200), scenario VARCHAR(24), sealed_plan TEXT,
 captured_at TIMESTAMP WITH TIME ZONE NOT NULL, processed_at TIMESTAMP WITH TIME ZONE, revision BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','QUEUED','ALREADY_DISPATCHED','SKIPPED','REJECTED')), error_code VARCHAR(64), dispatch_id VARCHAR(64) REFERENCES notification_dispatches(id), created_count INTEGER NOT NULL DEFAULT 0 CHECK(created_count BETWEEN 0 AND 100),
 FOREIGN KEY(tenant_id,alarm_id) REFERENCES alarms(tenant_id,id), UNIQUE(tenant_id,id),
 CHECK((status='PENDING' AND processed_at IS NULL AND dispatch_id IS NULL AND created_count=0 AND sealed_plan IS NOT NULL) OR (status='QUEUED' AND processed_at IS NOT NULL AND dispatch_id IS NOT NULL AND created_count BETWEEN 2 AND 100) OR (status='ALREADY_DISPATCHED' AND processed_at IS NOT NULL AND dispatch_id IS NOT NULL AND created_count=0) OR (status IN ('SKIPPED','REJECTED') AND processed_at IS NOT NULL AND dispatch_id IS NULL AND created_count=0))
);
CREATE INDEX idx_automation_pending ON notification_automation_intents(status,captured_at,id);
CREATE INDEX idx_automation_tenant ON notification_automation_intents(tenant_id,captured_at,id);
ALTER TABLE notification_dispatches ADD COLUMN origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','AUTOMATIC_SIMULATION'));
ALTER TABLE notification_dispatches ADD COLUMN automation_intent_id VARCHAR(64) UNIQUE REFERENCES notification_automation_intents(id);
ALTER TABLE notification_dispatches ADD COLUMN automation_epoch VARCHAR(64);
ALTER TABLE notification_dispatches ADD CONSTRAINT notification_dispatch_origin CHECK((origin='MANUAL' AND automation_intent_id IS NULL AND automation_epoch IS NULL) OR (origin='AUTOMATIC_SIMULATION' AND automation_intent_id IS NOT NULL AND automation_epoch IS NOT NULL));
INSERT INTO user_permissions(user_id,permission) SELECT u.id,p.permission FROM app_users u CROSS JOIN (VALUES ('notification-automation:read'),('notification-automation:write')) p(permission) WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions x WHERE x.user_id=u.id AND x.permission=p.permission);
