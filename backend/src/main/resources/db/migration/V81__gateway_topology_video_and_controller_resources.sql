-- Real gateway topology, video resources and controller resources.
-- Gateway links are explicitly MANUAL or OBSERVED (with stored legacy evidence) and can be VERIFIED by an
-- operator; a child can only have one active gateway, so no cycle or silent re-parenting is possible.
-- Video endpoints/credentials are write-only encrypted values; media is only served through scoped sessions.
-- Controller resources point at one real device and one live capability, never at a free-form relay id.

CREATE TABLE gateway_nodes (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    address VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    protocol VARCHAR(32) NOT NULL CHECK (protocol IN ('LEGACY_TURMASS_MQTT','HIKVISION','MANUAL')),
    device_id VARCHAR(64),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
    active_key VARCHAR(200),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_gateway_nodes_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uq_gateway_nodes_address UNIQUE (tenant_id, address),
    CONSTRAINT fk_gateway_nodes_device FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CHECK (revision >= 0)
);
CREATE UNIQUE INDEX uq_gateway_nodes_active_device ON gateway_nodes(tenant_id, active_key);
CREATE INDEX idx_gateway_nodes_tenant ON gateway_nodes(tenant_id, state, address);

CREATE TABLE gateway_links (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    gateway_id VARCHAR(64) NOT NULL,
    child_kind VARCHAR(16) NOT NULL CHECK (child_kind IN ('DEVICE','MEASUREMENT')),
    device_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(64),
    provenance VARCHAR(16) NOT NULL CHECK (provenance IN ('OBSERVED','MANUAL')),
    observation_source VARCHAR(32) CHECK (observation_source IN ('LEGACY_MQTT_EVENT')),
    observation_event_id VARCHAR(64) REFERENCES legacy_ingest_events(id),
    observed_at TIMESTAMP WITH TIME ZONE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    verified_by VARCHAR(64) REFERENCES app_users(id),
    verified_at TIMESTAMP WITH TIME ZONE,
    verification_note VARCHAR(1000),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','REVOKED')),
    active_key VARCHAR(200),
    revoked_by VARCHAR(64) REFERENCES app_users(id),
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoke_reason VARCHAR(1000),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_gateway_links_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_gateway_links_gateway FOREIGN KEY (gateway_id, tenant_id) REFERENCES gateway_nodes(id, tenant_id),
    CONSTRAINT fk_gateway_links_device FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CONSTRAINT fk_gateway_links_channel FOREIGN KEY (channel_id, tenant_id, device_id) REFERENCES device_channels(id, tenant_id, device_id),
    CONSTRAINT ck_gateway_links_child CHECK ((child_kind = 'DEVICE' AND channel_id IS NULL) OR (child_kind = 'MEASUREMENT' AND channel_id IS NOT NULL)),
    CONSTRAINT ck_gateway_links_provenance CHECK (
        (provenance = 'OBSERVED' AND observation_source IS NOT NULL AND observation_event_id IS NOT NULL AND observed_at IS NOT NULL)
        OR (provenance = 'MANUAL' AND observation_source IS NULL AND observation_event_id IS NULL AND observed_at IS NULL)),
    CONSTRAINT ck_gateway_links_verified CHECK (verified = FALSE OR (verified_by IS NOT NULL AND verified_at IS NOT NULL AND verification_note IS NOT NULL)),
    CONSTRAINT ck_gateway_links_revoked CHECK ((state = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL) OR (state = 'ACTIVE' AND revoked_by IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL))
);
CREATE UNIQUE INDEX uq_gateway_links_active_child ON gateway_links(tenant_id, active_key);
CREATE INDEX idx_gateway_links_gateway ON gateway_links(tenant_id, gateway_id, state);
CREATE INDEX idx_gateway_links_device ON gateway_links(tenant_id, device_id, state);
CREATE INDEX idx_gateway_links_event ON gateway_links(observation_event_id);

CREATE TABLE video_resources (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    resource_kind VARCHAR(16) NOT NULL CHECK (resource_kind IN ('CAMERA','VIDEO_BOX','NVR')),
    protocol VARCHAR(16) NOT NULL CHECK (protocol IN ('HTTPS_MP4','HTTPS_HLS','HTTPS_JPEG','RTSP')),
    endpoint_cipher TEXT,
    endpoint_nonce VARCHAR(64),
    endpoint_host VARCHAR(255),
    credentials_cipher TEXT,
    credentials_nonce VARCHAR(64),
    gateway_cipher TEXT,
    gateway_nonce VARCHAR(64),
    gateway_host VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMP WITH TIME ZONE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_video_resources_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uq_video_resources_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_video_resources_endpoint CHECK ((endpoint_cipher IS NULL AND endpoint_nonce IS NULL AND endpoint_host IS NULL) OR (endpoint_cipher IS NOT NULL AND endpoint_nonce IS NOT NULL AND endpoint_host IS NOT NULL)),
    CONSTRAINT ck_video_resources_credentials CHECK ((credentials_cipher IS NULL AND credentials_nonce IS NULL) OR (credentials_cipher IS NOT NULL AND credentials_nonce IS NOT NULL)),
    CONSTRAINT ck_video_resources_gateway CHECK ((gateway_cipher IS NULL AND gateway_nonce IS NULL AND gateway_host IS NULL) OR (gateway_cipher IS NOT NULL AND gateway_nonce IS NOT NULL AND gateway_host IS NOT NULL))
);
CREATE INDEX idx_video_resources_tenant ON video_resources(tenant_id, archived_at, name);

CREATE TABLE video_associations (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    video_resource_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','REVOKED')),
    active_key VARCHAR(200),
    revoked_by VARCHAR(64) REFERENCES app_users(id),
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoke_reason VARCHAR(1000),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_video_associations_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_video_associations_resource FOREIGN KEY (video_resource_id, tenant_id) REFERENCES video_resources(id, tenant_id),
    CONSTRAINT fk_video_associations_device FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CONSTRAINT ck_video_associations_revoked CHECK ((state = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL) OR (state = 'ACTIVE' AND revoked_by IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL))
);
CREATE UNIQUE INDEX uq_video_associations_active ON video_associations(tenant_id, active_key);
CREATE INDEX idx_video_associations_device ON video_associations(tenant_id, device_id, state);
CREATE INDEX idx_video_associations_resource ON video_associations(tenant_id, video_resource_id, state);

CREATE TABLE video_media_sessions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    video_resource_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    audience VARCHAR(16) NOT NULL CHECK (audience IN ('management','customer')),
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('PLAYBACK','SNAPSHOT')),
    token_hash VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','REVOKED')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_video_media_sessions_resource FOREIGN KEY (video_resource_id, tenant_id) REFERENCES video_resources(id, tenant_id),
    CONSTRAINT fk_video_media_sessions_device FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id)
);
CREATE UNIQUE INDEX uq_video_media_sessions_token ON video_media_sessions(token_hash);
CREATE INDEX idx_video_media_sessions_expiry ON video_media_sessions(expires_at, state);

CREATE TABLE video_media_subresources (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES video_media_sessions(id),
    entry_no INTEGER NOT NULL CHECK (entry_no >= 0),
    host VARCHAR(255) NOT NULL,
    url_sha256 VARCHAR(64) NOT NULL,
    url_cipher TEXT NOT NULL,
    url_nonce VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_video_media_subresources UNIQUE (session_id, entry_no),
    CONSTRAINT uq_video_media_subresources_url UNIQUE (session_id, url_sha256)
);

CREATE TABLE video_capture_jobs (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    video_resource_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    association_id VARCHAR(64),
    requested_by VARCHAR(64) REFERENCES app_users(id),
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('MANUAL','ALARM','LEGACY_RECOVERY','RAW_FAULT','LEGACY_FAULT')),
    source_id VARCHAR(64) NOT NULL,
    rule_id VARCHAR(64) REFERENCES resource_linkage_rules(id),
    idem_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    protocol VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNSUPPORTED','NOT_CONFIGURED')),
    failure_code VARCHAR(64),
    failure_reason VARCHAR(1000),
    capture_at TIMESTAMP WITH TIME ZONE,
    image_sha256 VARCHAR(64),
    image_media_type VARCHAR(32),
    image_cipher TEXT,
    image_nonce VARCHAR(64),
    image_bytes BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_video_capture_jobs_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_video_capture_jobs_resource FOREIGN KEY (video_resource_id, tenant_id) REFERENCES video_resources(id, tenant_id),
    CONSTRAINT fk_video_capture_jobs_device FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CONSTRAINT uq_video_capture_jobs_idem UNIQUE (tenant_id, idem_key),
    CONSTRAINT ck_video_capture_jobs_success CHECK (state <> 'SUCCEEDED' OR (capture_at IS NOT NULL AND image_sha256 IS NOT NULL AND image_cipher IS NOT NULL AND image_nonce IS NOT NULL AND image_bytes IS NOT NULL))
);
CREATE INDEX idx_video_capture_jobs_queue ON video_capture_jobs(state, created_at);
CREATE INDEX idx_video_capture_jobs_resource ON video_capture_jobs(tenant_id, video_resource_id, created_at);

CREATE TABLE video_view_intents (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    video_resource_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('PREVIEW','PLAYBACK')),
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ALARM','LEGACY_RECOVERY','RAW_FAULT','LEGACY_FAULT')),
    source_id VARCHAR(64) NOT NULL,
    rule_id VARCHAR(64) REFERENCES resource_linkage_rules(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','OPENED','EXPIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_video_view_intents_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_video_view_intents_resource FOREIGN KEY (video_resource_id, tenant_id) REFERENCES video_resources(id, tenant_id),
    CONSTRAINT fk_video_view_intents_device FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id)
);
CREATE INDEX idx_video_view_intents_source ON video_view_intents(tenant_id, source_id, created_at);

CREATE TABLE controller_resources (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('start','openGate','closeGate','output','releaser','reset','io-start','io-stop')),
    service_key VARCHAR(64) NOT NULL,
    driver_key VARCHAR(64) NOT NULL,
    driver_mode VARCHAR(16) NOT NULL CHECK (driver_mode IN ('PRODUCT_MODEL','VENDOR_PLATFORM')),
    service_definition_id VARCHAR(64),
    capability_revision BIGINT NOT NULL DEFAULT 0,
    support_state VARCHAR(16) NOT NULL CHECK (support_state IN ('VERIFIED','UNVERIFIED')),
    risk_level VARCHAR(16) NOT NULL CHECK (risk_level IN ('NORMAL','ENGINEERING')),
    result_kind VARCHAR(16) NOT NULL CHECK (result_kind IN ('PARAMETERS','COMMAND','STATUS')),
    arguments_json TEXT NOT NULL,
    argument_keys_json TEXT NOT NULL,
    capability_evidence_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
    active_key VARCHAR(200),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_controller_resources_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_controller_resources_device FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CHECK (revision >= 0)
);
CREATE UNIQUE INDEX uq_controller_resources_active ON controller_resources(tenant_id, active_key);
CREATE INDEX idx_controller_resources_device ON controller_resources(tenant_id, device_id, state);
