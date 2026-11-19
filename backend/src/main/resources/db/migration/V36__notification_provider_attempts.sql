CREATE TABLE notification_provider_attempts (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    alarm_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    contact_id VARCHAR(64) NOT NULL,
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('SMS','VOICE')),
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','SENT','FAILED','UNKNOWN','SUPPRESSED','RESOURCE_UNAVAILABLE')),
    provider_code VARCHAR(64),
    provider_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (alarm_id, contact_id, channel, attempt_no)
);
CREATE INDEX idx_notification_provider_attempts_tenant ON notification_provider_attempts(tenant_id, created_at DESC);
CREATE INDEX idx_notification_provider_attempts_alarm ON notification_provider_attempts(alarm_id, contact_id, channel);
