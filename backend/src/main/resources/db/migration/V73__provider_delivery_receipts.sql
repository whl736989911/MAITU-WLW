-- V73: provider delivery receipt reconciliation for real SMS/VOICE attempts.
-- Snapshots bind a send attempt to the exact provider account and destination used at submission time.
-- Old attempts keep NULL snapshots and can therefore never be "proved" by guessing current data.

ALTER TABLE notification_provider_attempts ADD COLUMN destination_cipher VARCHAR(2048);
ALTER TABLE notification_provider_attempts ADD COLUMN destination_nonce VARCHAR(64);
ALTER TABLE notification_provider_attempts ADD COLUMN destination_masked VARCHAR(64);
ALTER TABLE notification_provider_attempts ADD COLUMN destination_mac VARCHAR(64);
ALTER TABLE notification_provider_attempts ADD COLUMN account_fingerprint VARCHAR(64);
ALTER TABLE notification_provider_attempts ADD COLUMN snapshot_send_date DATE;
ALTER TABLE notification_provider_attempts ADD COLUMN out_id VARCHAR(15);
CREATE INDEX idx_notification_attempt_receipt_queue ON notification_provider_attempts(status,snapshot_send_date,created_at);

CREATE TABLE notification_delivery_receipts (
    attempt_id VARCHAR(64) PRIMARY KEY REFERENCES notification_provider_attempts(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('SMS','VOICE')),
    state VARCHAR(24) NOT NULL,
    reason VARCHAR(64),
    provider_status VARCHAR(32),
    provider_code VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_observation_id VARCHAR(64) NOT NULL,
    CHECK ((channel='SMS' AND state IN ('UNCONFIRMED','WAITING','DELIVERED','FAILED'))
        OR (channel='VOICE' AND state IN ('UNCONFIRMED','WAITING','HEARD_FULL','CONNECTED_PARTIAL','NOT_CONNECTED')))
);
CREATE INDEX idx_notification_delivery_receipt_state ON notification_delivery_receipts(tenant_id,state,last_observed_at DESC);

-- Append-only: application code never updates or deletes these rows.
CREATE TABLE notification_delivery_receipt_observations (
    id VARCHAR(64) PRIMARY KEY,
    attempt_id VARCHAR(64) NOT NULL REFERENCES notification_provider_attempts(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL,
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('SMS','VOICE')),
    source VARCHAR(16) NOT NULL CHECK (source IN ('MANUAL','SCHEDULED')),
    state VARCHAR(24) NOT NULL,
    reason VARCHAR(64),
    provider_status VARCHAR(32),
    provider_code VARCHAR(64),
    provider_message VARCHAR(200),
    provider_biz_id VARCHAR(128),
    provider_phone_masked VARCHAR(64),
    provider_send_at VARCHAR(40),
    provider_receive_at VARCHAR(40),
    provider_created_at VARCHAR(40),
    duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    query_request_id VARCHAR(128),
    network_queried BOOLEAN NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (attempt_id, evidence_digest),
    CHECK ((channel='SMS' AND state IN ('UNCONFIRMED','WAITING','DELIVERED','FAILED'))
        OR (channel='VOICE' AND state IN ('UNCONFIRMED','WAITING','HEARD_FULL','CONNECTED_PARTIAL','NOT_CONNECTED')))
);
CREATE INDEX idx_notification_delivery_observation_attempt ON notification_delivery_receipt_observations(attempt_id,created_at DESC);
CREATE INDEX idx_notification_delivery_observation_tenant ON notification_delivery_receipt_observations(tenant_id,created_at DESC);

-- Page-managed periodic query policy. The persisted default is OFF; deployment environment values are technical ceilings.
CREATE TABLE notification_delivery_receipt_policy (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    periodic_query_enabled BOOLEAN NOT NULL,
    min_age_seconds INTEGER NOT NULL CHECK (min_age_seconds BETWEEN 0 AND 86400),
    retry_seconds INTEGER NOT NULL CHECK (retry_seconds BETWEEN 60 AND 604800),
    max_queries_per_attempt INTEGER NOT NULL CHECK (max_queries_per_attempt BETWEEN 1 AND 500),
    batch_size INTEGER NOT NULL CHECK (batch_size BETWEEN 1 AND 100),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64)
);
INSERT INTO notification_delivery_receipt_policy(id,periodic_query_enabled,min_age_seconds,retry_seconds,max_queries_per_attempt,batch_size,revision,updated_at)
VALUES (1,FALSE,300,600,48,20,0,CURRENT_TIMESTAMP);
