CREATE TABLE notification_contact_key_guard (id INTEGER PRIMARY KEY CHECK(id=1), fingerprint VARCHAR(64));
INSERT INTO notification_contact_key_guard(id,fingerprint) VALUES (1,NULL);
CREATE TABLE notification_contacts (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id), name VARCHAR(200) NOT NULL,
 contact_type VARCHAR(32) NOT NULL CHECK(contact_type IN ('FIRE_RESPONSIBLE','LEGAL_REPRESENTATIVE','CONTROL_ROOM','DUTY_OFFICER','OTHER')),
 phone_cipher TEXT NOT NULL, phone_nonce VARCHAR(32) NOT NULL, phone_masked VARCHAR(64) NOT NULL, phone_mac VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL, revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,phone_mac)
);
CREATE TABLE notification_templates (
 id VARCHAR(64) PRIMARY KEY, name VARCHAR(200) NOT NULL, channel VARCHAR(5) NOT NULL CHECK(channel IN ('SMS','VOICE')),
 mode VARCHAR(16) NOT NULL CHECK(mode='SIMULATION'), body VARCHAR(2000) NOT NULL, enabled BOOLEAN NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE notification_rules (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id), name VARCHAR(200) NOT NULL,
 enabled BOOLEAN NOT NULL, revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), event_scope VARCHAR(8) NOT NULL CHECK(event_scope='FIRE'), mode VARCHAR(16) NOT NULL CHECK(mode='SIMULATION'),
 sms_template_id VARCHAR(64) NOT NULL REFERENCES notification_templates(id), voice_template_id VARCHAR(64) NOT NULL REFERENCES notification_templates(id),
 max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 20), retry_delay_seconds INTEGER NOT NULL CHECK(retry_delay_seconds BETWEEN 0 AND 86400),
 escalation_interval_seconds INTEGER CHECK(escalation_interval_seconds BETWEEN 1 AND 604800), created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(tenant_id,id)
);
CREATE TABLE notification_rule_receivers (
 tenant_id VARCHAR(64) NOT NULL, rule_id VARCHAR(64) NOT NULL, contact_id VARCHAR(64) NOT NULL, level INTEGER NOT NULL CHECK(level BETWEEN 1 AND 5),
 PRIMARY KEY(rule_id,contact_id), FOREIGN KEY(tenant_id,rule_id) REFERENCES notification_rules(tenant_id,id), FOREIGN KEY(tenant_id,contact_id) REFERENCES notification_contacts(tenant_id,id)
);
CREATE TABLE notification_dispatches (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id), alarm_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL,
 rule_id VARCHAR(64) NOT NULL REFERENCES notification_rules(id), mode VARCHAR(16) NOT NULL CHECK(mode='SIMULATION'),
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id), actor_revision BIGINT NOT NULL, tenant_revision BIGINT NOT NULL, alarm_revision BIGINT NOT NULL,
 scenario VARCHAR(24) NOT NULL CHECK(scenario IN ('SUCCESS','REJECTED','RETRY_THEN_SUCCESS','UNCERTAIN')), reason VARCHAR(1000) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tenant_id,alarm_id), UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,alarm_id) REFERENCES alarms(tenant_id,id)
);
CREATE TABLE notification_records (
 id VARCHAR(64) PRIMARY KEY, dispatch_id VARCHAR(64) NOT NULL REFERENCES notification_dispatches(id), tenant_id VARCHAR(64) NOT NULL, alarm_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL, device_name VARCHAR(200) NOT NULL, address VARCHAR(500) NOT NULL,
 rule_id VARCHAR(64) NOT NULL REFERENCES notification_rules(id), rule_name VARCHAR(200) NOT NULL, rule_revision BIGINT NOT NULL,
 template_id VARCHAR(64) NOT NULL REFERENCES notification_templates(id), template_name VARCHAR(200) NOT NULL, template_body VARCHAR(2000) NOT NULL, template_revision BIGINT NOT NULL,
 contact_id VARCHAR(64) NOT NULL, contact_name VARCHAR(200) NOT NULL, phone_masked VARCHAR(64) NOT NULL, contact_revision BIGINT NOT NULL,
 channel VARCHAR(5) NOT NULL CHECK(channel IN ('SMS','VOICE')), mode VARCHAR(16) NOT NULL CHECK(mode='SIMULATION'), level INTEGER NOT NULL CHECK(level BETWEEN 1 AND 5),
 max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 20), retry_delay_seconds INTEGER NOT NULL CHECK(retry_delay_seconds BETWEEN 0 AND 86400), escalation_interval_seconds INTEGER CHECK(escalation_interval_seconds BETWEEN 1 AND 604800),
 rendered_message TEXT NOT NULL, status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','WAIT_RETRY','SUCCEEDED_SIMULATION','FAILED','UNCERTAIN','CANCELLED','SUPPRESSED','REJECTED')),
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0 AND attempt_count<=max_attempts),
 available_at TIMESTAMP WITH TIME ZONE NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, finished_at TIMESTAMP WITH TIME ZONE, last_error_code VARCHAR(64),
 UNIQUE(alarm_id,contact_id,channel), FOREIGN KEY(tenant_id,alarm_id) REFERENCES alarms(tenant_id,id), FOREIGN KEY(tenant_id,contact_id) REFERENCES notification_contacts(tenant_id,id), FOREIGN KEY(tenant_id,dispatch_id) REFERENCES notification_dispatches(tenant_id,id), FOREIGN KEY(tenant_id,rule_id) REFERENCES notification_rules(tenant_id,id)
);
CREATE INDEX idx_notification_due ON notification_records(status,available_at,id);
CREATE INDEX idx_notification_tenant ON notification_records(tenant_id,created_at,id);
CREATE TABLE notification_attempts (
 id VARCHAR(64) PRIMARY KEY, record_id VARCHAR(64) NOT NULL REFERENCES notification_records(id), attempt_no INTEGER NOT NULL CHECK(attempt_no BETWEEN 1 AND 20),
 outcome VARCHAR(24) NOT NULL CHECK(outcome IN ('SUCCEEDED_SIMULATION','FAILED','RETRY_SCHEDULED','UNCERTAIN')),
 started_at TIMESTAMP WITH TIME ZONE NOT NULL, call_started_at TIMESTAMP WITH TIME ZONE NOT NULL, finished_at TIMESTAMP WITH TIME ZONE NOT NULL, error_code VARCHAR(64), UNIQUE(record_id,attempt_no)
);
CREATE TABLE notification_history (
 id VARCHAR(64) PRIMARY KEY, record_id VARCHAR(64) NOT NULL REFERENCES notification_records(id),
 action VARCHAR(32) NOT NULL CHECK(action IN ('QUEUED','SIMULATED_SUCCESS','SIMULATED_FAILURE','RETRY_SCHEDULED','SIMULATED_UNCERTAIN','CANCELLED','SUPPRESSED','REJECTED','SIMULATION_RECONCILED')),
 from_status VARCHAR(24), to_status VARCHAR(24) NOT NULL, recorded_at TIMESTAMP WITH TIME ZONE NOT NULL, actor_kind VARCHAR(16) NOT NULL CHECK(actor_kind IN ('SYSTEM','PLATFORM')),
 actor_id VARCHAR(64) REFERENCES app_users(id), actor_name VARCHAR(200), reason VARCHAR(1000)
);
CREATE INDEX idx_notification_history ON notification_history(record_id,recorded_at,id);
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,p.permission FROM app_users u CROSS JOIN (VALUES ('notification-config:read'),('notification-config:write'),('notification-templates:read'),('notification-templates:write'),('notifications:read'),('notifications:simulate')) p(permission)
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions existing WHERE existing.user_id=u.id AND existing.permission=p.permission);
