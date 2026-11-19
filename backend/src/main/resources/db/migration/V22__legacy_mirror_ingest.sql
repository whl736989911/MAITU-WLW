-- N05b legacy read-only mirror ingest (additive only; existing tables keep their semantics).
-- Product identity: products = (cleaned manufacturer, model); product_versions = one per legacy p_id.
-- Device binding comes from the legacy d_product_id column, never from d_pid (which is a place id).

CREATE TABLE places (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    parent_place_id VARCHAR(64),
    legacy_place_id VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id)
);
CREATE INDEX idx_places_tenant ON places(tenant_id, name);
CREATE INDEX idx_places_legacy ON places(tenant_id, legacy_place_id);

ALTER TABLE devices ADD COLUMN place_id VARCHAR(64);
ALTER TABLE devices ADD CONSTRAINT fk_devices_place FOREIGN KEY (place_id, tenant_id) REFERENCES places(id, tenant_id);

CREATE TABLE device_aliases (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('EUI','DEVADDR','IMEI','VENDOR_ID','MAC')),
    alias_value VARCHAR(128) NOT NULL,
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('LEGACY_TTL','LEGACY_TSF','MANUAL')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    UNIQUE (tenant_id, kind, alias_value)
);
CREATE INDEX idx_device_aliases_device ON device_aliases(tenant_id, device_id);

CREATE TABLE legacy_ingest_messages (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    ingress VARCHAR(32) NOT NULL CHECK (ingress IN ('MQTT_IOT_UPLINK','MQTT_GATEWAY_UPLINK','HTTP_NOTIFY','HTTP_FORM_TTL','LORAWAN_NS')),
    vendor_platform VARCHAR(32) NOT NULL,
    external_key VARCHAR(160) NOT NULL,
    vendor_device_id VARCHAR(128),
    device_id VARCHAR(64),
    payload_raw TEXT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    payload_bytes BIGINT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE,
    state VARCHAR(24) NOT NULL CHECK (state IN ('NORMALIZED','DUPLICATE','PENDING_DECODER','FAILED')),
    failure_code VARCHAR(64),
    duplicate_count BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    UNIQUE (ingress, vendor_platform, external_key)
);
CREATE INDEX idx_legacy_messages_state ON legacy_ingest_messages(state, received_at);
CREATE INDEX idx_legacy_messages_device ON legacy_ingest_messages(tenant_id, device_id, received_at);

CREATE TABLE legacy_ingest_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) REFERENCES legacy_ingest_messages(id),
    device_id VARCHAR(64),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('HEARTBEAT','ALARM','FAULT','OFFLINE','PARAM','OTHER')),
    method VARCHAR(64),
    params_json TEXT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id)
);
CREATE INDEX idx_legacy_events_message ON legacy_ingest_events(message_id);
CREATE INDEX idx_legacy_events_device ON legacy_ingest_events(tenant_id, device_id, observed_at);

CREATE TABLE legacy_alarm_codes (
    code VARCHAR(32) PRIMARY KEY,
    label VARCHAR(200),
    mapped_kind VARCHAR(16) CHECK (mapped_kind IN ('ALARM','FAULT','OFFLINE','HEARTBEAT','PARAM','OTHER')),
    mapped_event_type VARCHAR(16) CHECK (mapped_event_type IN ('FIRE','RECOVERY')),
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('OBSERVED','UNMAPPED')),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Attributes belong to one legacy product record (= one version), because the legacy catalog
-- reuses the same model string for records with different attribute sets and decoders.
CREATE TABLE product_version_attributes (
    product_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id),
    attribute_key VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    unit VARCHAR(32),
    data_type VARCHAR(32) NOT NULL,
    enum_json TEXT,
    num_min DECIMAL(64,36),
    num_max DECIMAL(64,36),
    legacy_attr_id VARCHAR(64),
    PRIMARY KEY (product_version_id, attribute_key)
);

CREATE TABLE legacy_product_decoders (
    legacy_product_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    source TEXT NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (legacy_product_id, revision)
);

ALTER TABLE product_versions ADD COLUMN schema_format VARCHAR(24) NOT NULL DEFAULT 'JSON_POINTER_V1'
    CHECK (schema_format IN ('JSON_POINTER_V1','LEGACY_ATTR_JS_V1'));
ALTER TABLE product_versions ADD COLUMN legacy_product_id VARCHAR(64);
ALTER TABLE product_versions ADD COLUMN legacy_decoder_revision BIGINT;

-- 报警会话：重复上报累加次数与最后时间；revision 只用于 CAS，不冒充计数
ALTER TABLE alarms ADD COLUMN occurrence_count BIGINT NOT NULL DEFAULT 1;
ALTER TABLE alarms ADD COLUMN last_event_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE legacy_reconcile_differences (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('DEVICE_MISSING_IN_TSF','DEVICE_MISSING_IN_TTL','TENANT_UNASSIGNED','PRODUCT_UNMAPPED','DECODER_MISSING','ALIAS_CONFLICT','LEGACY_FIELD_MISMATCH')),
    legacy_id VARCHAR(128) NOT NULL,
    new_id VARCHAR(128),
    detail VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_legacy_reconcile_run ON legacy_reconcile_differences(run_id, kind);

INSERT INTO user_permissions(user_id,permission) SELECT id,'legacy-ingest:read' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='legacy-ingest:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'legacy-ingest:write' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='legacy-ingest:write');
