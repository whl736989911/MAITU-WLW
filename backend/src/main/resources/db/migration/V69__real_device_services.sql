-- Real device lifecycle and services: versioned service definitions, immutable command snapshots,
-- durable attempt/receipt state, explicit vendor registrations, and source binding history.
-- Vendor acceptance is never stored as physical effect; every state below names its own evidence.

CREATE TABLE device_service_definitions (
    id VARCHAR(64) PRIMARY KEY,
    product_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id),
    operation_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    category VARCHAR(32) NOT NULL CHECK(category IN ('QUERY','CONFIGURATION','CONTROL')),
    risk_level VARCHAR(16) NOT NULL CHECK(risk_level IN ('NORMAL','ENGINEERING')),
    result_kind VARCHAR(16) NOT NULL CHECK(result_kind IN ('PARAMETERS','COMMAND','STATUS')),
    input_schema_json TEXT NOT NULL,
    applicability_json TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    support_state VARCHAR(16) NOT NULL CHECK(support_state IN ('VERIFIED','UNVERIFIED')),
    status VARCHAR(16) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED')),
    revision BIGINT NOT NULL DEFAULT 0,
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(product_version_id, operation_key)
);
CREATE INDEX idx_device_service_definitions_version ON device_service_definitions(product_version_id, operation_key);

CREATE TABLE device_capability_evidence (
    id VARCHAR(64) PRIMARY KEY,
    driver_key VARCHAR(64) NOT NULL,
    model VARCHAR(100) NOT NULL,
    service_key VARCHAR(64) NOT NULL,
    evidence VARCHAR(2000) NOT NULL,
    verified_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(driver_key, model, service_key)
);

CREATE TABLE device_parameter_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    driver_key VARCHAR(64) NOT NULL,
    source_generation BIGINT,
    schema_json TEXT NOT NULL,
    values_json TEXT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    command_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_device_parameter_snapshots_device ON device_parameter_snapshots(tenant_id,device_id,observed_at);

CREATE TABLE device_service_commands (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    driver_key VARCHAR(64) NOT NULL,
    driver_mode VARCHAR(16) NOT NULL CHECK(driver_mode IN ('PRODUCT_MODEL','VENDOR_PLATFORM')),
    service_key VARCHAR(64) NOT NULL,
    service_definition_id VARCHAR(64),
    risk_level VARCHAR(16) NOT NULL CHECK(risk_level IN ('NORMAL','ENGINEERING')),
    result_kind VARCHAR(16) NOT NULL CHECK(result_kind IN ('PARAMETERS','COMMAND','STATUS')),
    state VARCHAR(16) NOT NULL CHECK(state IN ('QUEUED','IN_FLIGHT','ACCEPTED','REJECTED','UNKNOWN','SUCCEEDED','FAILED','CANCELLED')),
    status_version BIGINT NOT NULL DEFAULT 0,
    arguments_json TEXT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    accepted_reference VARCHAR(200),
    vendor_code VARCHAR(80),
    result_json TEXT,
    next_action VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
    UNIQUE(actor_id, idem_key)
);
CREATE INDEX idx_device_service_commands_device ON device_service_commands(tenant_id,device_id,created_at);
CREATE INDEX idx_device_service_commands_queue ON device_service_commands(state,created_at);
CREATE INDEX idx_device_service_commands_reference ON device_service_commands(driver_key,accepted_reference);

CREATE TABLE device_service_command_attempts (
    id VARCHAR(64) PRIMARY KEY,
    command_id VARCHAR(64) NOT NULL REFERENCES device_service_commands(id),
    attempt_no INTEGER NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK(phase IN ('SUBMIT','QUERY')),
    outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('IN_FLIGHT','ACCEPTED','REJECTED','UNKNOWN','SUCCEEDED','FAILED','ABORTED')),
    vendor_code VARCHAR(80),
    detail_json TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(command_id, attempt_no)
);
CREATE INDEX idx_device_service_attempts_command ON device_service_command_attempts(command_id,attempt_no);

CREATE TABLE device_source_bindings (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    driver_key VARCHAR(64) NOT NULL,
    source_external_id VARCHAR(128) NOT NULL,
    source_serial VARCHAR(200),
    account_fingerprint VARCHAR(64),
    generation BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK(state IN ('ACTIVE','RELEASED')),
    pending_risk_json TEXT,
    bound_at TIMESTAMP WITH TIME ZONE NOT NULL,
    bound_by VARCHAR(64) REFERENCES app_users(id),
    released_at TIMESTAMP WITH TIME ZONE,
    released_by VARCHAR(64) REFERENCES app_users(id),
    release_reason VARCHAR(1000),
    FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
    UNIQUE(driver_key, source_external_id, generation)
);
CREATE INDEX idx_device_source_bindings_device ON device_source_bindings(tenant_id,device_id,state);
CREATE INDEX idx_device_source_bindings_source ON device_source_bindings(driver_key,source_external_id,state);

-- Existing deployed Hik links become historical binding generation 1. Archived devices keep their raw link on the
-- discovery row only; the active slot was never released silently by this migration.
INSERT INTO device_source_bindings(id,tenant_id,device_id,driver_key,source_external_id,source_serial,account_fingerprint,generation,state,pending_risk_json,bound_at,bound_by,released_at,released_by,release_reason)
 SELECT 'V69-BIND-' || CAST((SELECT COUNT(*) FROM hikvision_discovered_devices h2 WHERE h2.external_id <= h.external_id) AS VARCHAR(20)), d.tenant_id, d.id, 'HIKVISION_FIRECLOUD', h.external_id, h.serial, NULL, 1, 'ACTIVE', NULL,
        COALESCE(h.first_seen_at,h.last_seen_at), NULL, NULL, NULL, NULL
 FROM hikvision_discovered_devices h JOIN devices d ON d.id=h.local_device_id AND d.archived_at IS NULL
 WHERE NOT EXISTS (SELECT 1 FROM device_source_bindings b WHERE b.driver_key='HIKVISION_FIRECLOUD' AND b.source_external_id=h.external_id AND b.state='ACTIVE');

CREATE TABLE device_registrations (
    id VARCHAR(64) PRIMARY KEY,
    driver_key VARCHAR(64) NOT NULL,
    mode VARCHAR(24) NOT NULL CHECK(mode IN ('NEW_VENDOR','MATCH_EXISTING','PRODUCT_MODEL')),
    status VARCHAR(24) NOT NULL CHECK(status IN ('DRAFT','CLOUD_PENDING','CLOUD_REGISTERED','COMMISSIONED','FAILED','UNKNOWN','ABANDONED')),
    revision BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(64) REFERENCES tenants(id),
    place_id VARCHAR(64),
    product_version_id VARCHAR(64) REFERENCES product_versions(id),
    device_id VARCHAR(64) REFERENCES devices(id),
    external_device_id VARCHAR(128),
    external_serial VARCHAR(200) NOT NULL,
    device_name VARCHAR(200),
    device_model VARCHAR(100),
    device_type INTEGER,
    communication_type INTEGER,
    access_mode INTEGER,
    producter_id INTEGER,
    product_model_id INTEGER,
    vendor_unit_id VARCHAR(64),
    verification_cipher TEXT,
    verification_nonce TEXT,
    location VARCHAR(500),
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    auto_add_channel BOOLEAN,
    account_fingerprint VARCHAR(64),
    cloud_state VARCHAR(16) NOT NULL CHECK(cloud_state IN ('NOT_REQUIRED','NOT_ATTEMPTED','IN_FLIGHT','SUCCEEDED','FAILED','UNKNOWN')),
    cloud_result_json TEXT,
    local_state VARCHAR(16) NOT NULL CHECK(local_state IN ('NOT_READY','READY','SUCCEEDED','FAILED')),
    acceptance_note VARCHAR(1000),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    commissioned_at TIMESTAMP WITH TIME ZONE,
    CHECK ((tenant_id IS NULL AND place_id IS NULL) OR (tenant_id IS NOT NULL)),
    CHECK ((latitude IS NULL AND longitude IS NULL) OR (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);
CREATE INDEX idx_device_registrations_serial ON device_registrations(driver_key,external_serial);
CREATE INDEX idx_device_registrations_status ON device_registrations(status,created_at);
CREATE INDEX idx_device_registrations_tenant ON device_registrations(tenant_id,created_at);

CREATE TABLE device_registration_events (
    id VARCHAR(64) PRIMARY KEY,
    registration_id VARCHAR(64) NOT NULL REFERENCES device_registrations(id),
    step VARCHAR(24) NOT NULL CHECK(step IN ('CREATE','CLOUD_SUBMIT','CLOUD_RESULT','CLOUD_LOOKUP','LOCAL_COMMISSION','ABANDON')),
    outcome VARCHAR(32) NOT NULL,
    detail_json TEXT,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_device_registration_events ON device_registration_events(registration_id,occurred_at);

CREATE TABLE integration_driver_config (
    driver_key VARCHAR(64) PRIMARY KEY,
    endpoint VARCHAR(500) NOT NULL,
    token_cipher TEXT NOT NULL,
    token_nonce VARCHAR(64) NOT NULL,
    account_fingerprint VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id)
);
