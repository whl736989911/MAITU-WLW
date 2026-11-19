-- Real Hik subscription management: durable intents, masked remote observations, no automatic activation.
CREATE TABLE hikvision_subscription_operations (
    id VARCHAR(64) PRIMARY KEY,
    operation VARCHAR(16) NOT NULL CHECK(operation IN ('ADD','UPDATE','DELETE')),
    message_type VARCHAR(16) NOT NULL,
    receiver_revision BIGINT NOT NULL,
    hikvision_revision BIGINT NOT NULL,
    account_fingerprint VARCHAR(64) NOT NULL,
    intended_url_cipher TEXT,
    intended_url_nonce VARCHAR(64),
    intended_url_sha256 VARCHAR(64),
    intended_origin VARCHAR(200),
    previous_url_sha256 VARCHAR(64),
    previous_origin VARCHAR(200),
    remote_digest VARCHAR(64) NOT NULL,
    remote_state VARCHAR(16) NOT NULL CHECK(remote_state IN ('PRESENT','ABSENT')),
    overwrite_required BOOLEAN NOT NULL DEFAULT FALSE,
    overwrite_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    request_digest VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    state VARCHAR(24) NOT NULL CHECK(state IN ('PREVIEW','IN_FLIGHT','PENDING_CONFIRMATION','CONFIRMED','REJECTED','UNKNOWN','CANCELLED')),
    revision BIGINT NOT NULL DEFAULT 0,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    vendor_code VARCHAR(80),
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(actor_id, idem_key)
);
CREATE INDEX idx_hikvision_subscription_operations_state ON hikvision_subscription_operations(state,created_at);
CREATE INDEX idx_hikvision_subscription_operations_type ON hikvision_subscription_operations(message_type,created_at);

CREATE TABLE hikvision_subscription_attempts (
    id VARCHAR(64) PRIMARY KEY,
    operation_id VARCHAR(64) NOT NULL REFERENCES hikvision_subscription_operations(id),
    attempt_no INTEGER NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK(phase IN ('PREVIEW','SUBMIT','READBACK','RECONCILE')),
    outcome VARCHAR(24) NOT NULL CHECK(outcome IN ('IN_FLIGHT','ACCEPTED','CONFIRMED','PENDING','REJECTED','UNKNOWN','ABORTED','CANCELLED')),
    vendor_code VARCHAR(80),
    detail_json TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(operation_id, attempt_no)
);
CREATE INDEX idx_hikvision_subscription_attempts ON hikvision_subscription_attempts(operation_id,attempt_no);

CREATE TABLE hikvision_subscription_observations (
    id VARCHAR(64) PRIMARY KEY,
    operation_id VARCHAR(64) REFERENCES hikvision_subscription_operations(id),
    account_fingerprint VARCHAR(64) NOT NULL,
    hikvision_revision BIGINT NOT NULL,
    trigger_kind VARCHAR(16) NOT NULL CHECK(trigger_kind IN ('INSPECT','PREVIEW','READBACK','RECONCILE')),
    list_digest VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_hikvision_subscription_observations ON hikvision_subscription_observations(operation_id,observed_at);
CREATE INDEX idx_hikvision_subscription_observations_time ON hikvision_subscription_observations(observed_at);
