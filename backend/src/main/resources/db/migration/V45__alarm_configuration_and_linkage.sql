-- V45: persisted, tenant-owned alarm/linkage configuration and append-only software outcomes.
CREATE TABLE alarm_base_configurations (
    tenant_id VARCHAR(64) PRIMARY KEY REFERENCES tenants(id),
    config_json TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision >= 1),
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE alarm_configuration_outcomes (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    alarm_id VARCHAR(64),
    raw_message_id VARCHAR(64),
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('ALARM','FAULT_MESSAGE','LEGACY_FAULT')),
    source_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    configuration_revision BIGINT NOT NULL,
    outcome_kind VARCHAR(40) NOT NULL,
    outcome VARCHAR(48) NOT NULL,
    detail_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (tenant_id,alarm_id) REFERENCES alarms(tenant_id,id),
    FOREIGN KEY (raw_message_id) REFERENCES raw_messages(id),
    CHECK ((source_kind='ALARM' AND alarm_id=source_id AND raw_message_id IS NULL) OR (source_kind='FAULT_MESSAGE' AND alarm_id IS NULL AND raw_message_id=source_id) OR (source_kind='LEGACY_FAULT' AND alarm_id IS NULL AND raw_message_id IS NULL))
);
CREATE INDEX idx_alarm_configuration_outcomes_tenant_time ON alarm_configuration_outcomes(tenant_id, occurred_at DESC);
CREATE TABLE alarm_reminder_schedule (
    tenant_id VARCHAR(64) NOT NULL,
    alarm_id VARCHAR(64) NOT NULL,
    cycle_number BIGINT NOT NULL DEFAULT 0 CHECK (cycle_number>=0),
    period_hours INTEGER NOT NULL CHECK (period_hours BETWEEN 1 AND 720),
    cycle_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_accepted_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING','CANCELLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>=1),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,alarm_id),
    FOREIGN KEY (tenant_id,alarm_id) REFERENCES alarms(tenant_id,id)
);
CREATE INDEX idx_alarm_reminder_due ON alarm_reminder_schedule(status,next_due_at);

CREATE TABLE resource_linkage_rules (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name VARCHAR(120) NOT NULL,
    rule_kind VARCHAR(24) NOT NULL CHECK (rule_kind IN ('CUSTOM','DEFAULT_VIDEO','DEFAULT_SOUND_LIGHT')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    rule_json TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision >= 1),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_resource_linkage_tenant ON resource_linkage_rules(tenant_id, rule_kind, enabled, updated_at);

CREATE TABLE resource_linkage_outcomes (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    rule_id VARCHAR(64) NOT NULL REFERENCES resource_linkage_rules(id),
    alarm_id VARCHAR(64),
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('ALARM','RAW_FAULT','LEGACY_FAULT','LEGACY_RECOVERY')),
    source_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    outcome VARCHAR(40) NOT NULL CHECK (outcome IN ('MATCHED_SOFTWARE_ACTION','LOCAL_COMMAND_INTENT','UNSUPPORTED_EXTERNAL_ADAPTER','NOT_MATCHED')),
    detail_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (tenant_id,alarm_id) REFERENCES alarms(tenant_id,id),
    CHECK ((source_kind='ALARM' AND alarm_id=source_id) OR (source_kind<>'ALARM' AND alarm_id IS NULL))
);
CREATE INDEX idx_resource_linkage_outcomes_tenant_time ON resource_linkage_outcomes(tenant_id, occurred_at DESC);
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,p.permission FROM app_users u CROSS JOIN (VALUES ('alarm-base:read'),('alarm-base:write'),('resource-linkage:read'),('resource-linkage:write')) p(permission)
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions existing WHERE existing.user_id=u.id AND existing.permission=p.permission);
