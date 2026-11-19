-- V71: configurable logical alarm sessions and notification merge claims.
-- Physical alarm rows, source-event dedup, recovery and review remain untouched and independent.
CREATE TABLE alarm_merge_policies (
    tenant_id VARCHAR(64) PRIMARY KEY REFERENCES tenants(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    window_seconds INTEGER CHECK (window_seconds BETWEEN 1 AND 604800),
    group_by VARCHAR(32) NOT NULL DEFAULT 'DEVICE_EVENT_TYPE' CHECK (group_by='DEVICE_EVENT_TYPE'),
    scope_kind VARCHAR(16) NOT NULL DEFAULT 'UNIT' CHECK (scope_kind='UNIT'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>=1),
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (enabled=FALSE OR (window_seconds IS NOT NULL AND group_by='DEVICE_EVENT_TYPE' AND scope_kind='UNIT'))
);

CREATE TABLE alarm_merge_policy_revisions (
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    revision BIGINT NOT NULL CHECK (revision>=1),
    enabled BOOLEAN NOT NULL,
    window_seconds INTEGER CHECK (window_seconds BETWEEN 1 AND 604800),
    group_by VARCHAR(32) NOT NULL CHECK (group_by='DEVICE_EVENT_TYPE'),
    scope_kind VARCHAR(16) NOT NULL CHECK (scope_kind='UNIT'),
    reason VARCHAR(1000) NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,revision),
    CHECK (enabled=FALSE OR window_seconds IS NOT NULL)
);

CREATE TABLE alarm_sessions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    device_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(200) NOT NULL,
    address VARCHAR(500) NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type='FIRE'),
    group_by VARCHAR(32) NOT NULL CHECK (group_by='DEVICE_EVENT_TYPE'),
    policy_revision BIGINT NOT NULL CHECK (policy_revision>=1),
    window_seconds INTEGER NOT NULL CHECK (window_seconds BETWEEN 1 AND 604800),
    first_source_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_source_at TIMESTAMP WITH TIME ZONE NOT NULL,
    open_slot VARCHAR(64) NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id,tenant_id),
    UNIQUE (tenant_id,device_id,event_type,open_slot),
    FOREIGN KEY (device_id,tenant_id) REFERENCES devices(id,tenant_id),
    CHECK ((open_slot='OPEN' AND closed_at IS NULL) OR (open_slot<>'OPEN' AND closed_at IS NOT NULL)),
    CHECK (last_source_at>=first_source_at)
);
CREATE INDEX idx_alarm_sessions_tenant_time ON alarm_sessions(tenant_id,last_source_at DESC);
CREATE INDEX idx_alarm_sessions_device ON alarm_sessions(tenant_id,device_id,event_type,last_source_at DESC);

CREATE TABLE alarm_session_members (
    session_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    alarm_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    source_at TIMESTAMP WITH TIME ZONE NOT NULL,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id,alarm_id),
    UNIQUE (tenant_id,alarm_id),
    FOREIGN KEY (session_id,tenant_id) REFERENCES alarm_sessions(id,tenant_id),
    FOREIGN KEY (tenant_id,alarm_id) REFERENCES alarms(tenant_id,id)
);
CREATE INDEX idx_alarm_session_members_source ON alarm_session_members(session_id,source_at,alarm_id);

CREATE TABLE alarm_session_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    alarm_id VARCHAR(64),
    event_kind VARCHAR(24) NOT NULL CHECK (event_kind IN ('SESSION_OPENED','MEMBER_ADDED','MEMBER_RECOVERED','SESSION_CLOSED','POLICY_CLOSED')),
    occurred_at TIMESTAMP WITH TIME ZONE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (session_id,tenant_id) REFERENCES alarm_sessions(id,tenant_id),
    FOREIGN KEY (tenant_id,alarm_id) REFERENCES alarms(tenant_id,id)
);
CREATE INDEX idx_alarm_session_events_session ON alarm_session_events(tenant_id,session_id,recorded_at);

CREATE TABLE alarm_notification_claims (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    session_id VARCHAR(64) NOT NULL,
    alarm_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type='FIRE'),
    rule_id VARCHAR(64) NOT NULL,
    contact_id VARCHAR(64) NOT NULL,
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('SMS','VOICE')),
    level INTEGER NOT NULL CHECK (level BETWEEN 1 AND 5),
    occurrence_key VARCHAR(80) NOT NULL,
    policy_revision BIGINT NOT NULL CHECK (policy_revision>=1),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CLAIMED','SENT','UNKNOWN','FAILED')),
    attempt_no INTEGER NOT NULL DEFAULT 0 CHECK (attempt_no>=0),
    provider_code VARCHAR(64),
    provider_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (session_id,occurrence_key,rule_id,contact_id,channel,level),
    FOREIGN KEY (session_id,tenant_id) REFERENCES alarm_sessions(id,tenant_id),
    FOREIGN KEY (tenant_id,alarm_id) REFERENCES alarms(tenant_id,id)
);
CREATE INDEX idx_alarm_notification_claims_session ON alarm_notification_claims(tenant_id,session_id,status);
CREATE INDEX idx_alarm_notification_claims_updated ON alarm_notification_claims(tenant_id,updated_at DESC);
