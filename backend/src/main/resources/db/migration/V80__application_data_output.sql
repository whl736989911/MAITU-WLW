-- Open-application data output: tenant-owned outbound applications, exact product/version authorizations,
-- encrypted endpoint configuration and a durable per-fact delivery outbox with immutable attempts.
-- Additive only. Nothing is enabled by this migration, no legacy iot_app/iot_app_forward row or secret is
-- imported, and no permission is granted here: deployment/operator grants stay explicit.

CREATE TABLE output_applications (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_by VARCHAR(64) REFERENCES app_users(id),
    archived_at TIMESTAMP WITH TIME ZONE,
    archived_reason VARCHAR(1000),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, name),
    CHECK (status <> 'ARCHIVED' OR (archived_at IS NOT NULL AND archived_by IS NOT NULL)),
    CHECK (status = 'ARCHIVED' OR (archived_at IS NULL AND archived_by IS NULL))
);
CREATE INDEX idx_output_applications_scope ON output_applications(tenant_id, status, updated_at);

-- Immutable change journal for applications, authorizations, outputs and credential rotation.
CREATE TABLE output_application_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(24) NOT NULL CHECK (subject_type IN ('APPLICATION','AUTHORIZATION','OUTPUT','CREDENTIAL')),
    subject_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    revision_before BIGINT,
    revision_after BIGINT NOT NULL CHECK (revision_after >= 0),
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    detail_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (application_id, tenant_id) REFERENCES output_applications(id, tenant_id)
);
CREATE INDEX idx_output_application_events ON output_application_events(tenant_id, application_id, created_at);

-- Authorizations reference exactly one authoritative identity kind:
--   FORMAL_VERSION  — one published product_version_id (product_id + version)
--   LEGACY_PRODUCT  — source_platform + legacy_product_id from the existing legacy decoder registry
--   VENDOR_MODEL    — source_platform + vendor_model_id from source/discovery metadata (+ optional driver/adapter)
-- Scope is either the whole owning unit (requires the actor's current ALL_TENANT device scope at grant time) or
-- an explicit captured device snapshot.
CREATE TABLE application_product_authorizations (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(64) NOT NULL,
    reference_kind VARCHAR(16) NOT NULL CHECK (reference_kind IN ('FORMAL_VERSION','LEGACY_PRODUCT','VENDOR_MODEL')),
    -- Deterministic identity: FORMAL:<versionId> / LEGACY:<platform>:<legacyId> / VENDOR:<platform>:<modelId>.
    reference_key VARCHAR(300) NOT NULL,
    product_id VARCHAR(64) REFERENCES products(id),
    product_version_id VARCHAR(64) REFERENCES product_versions(id),
    source_platform VARCHAR(64),
    legacy_product_id VARCHAR(64),
    legacy_decoder_revision BIGINT,
    legacy_decoder_sha256 VARCHAR(64),
    vendor_model_id VARCHAR(200),
    driver_key VARCHAR(64),
    adapter_digest VARCHAR(64),
    scope_mode VARCHAR(16) NOT NULL CHECK (scope_mode IN ('ALL_TENANT','DEVICE_SUBSET')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_by VARCHAR(64) REFERENCES app_users(id),
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoked_reason VARCHAR(1000),
    UNIQUE (id, tenant_id),
    UNIQUE (application_id, reference_key),
    FOREIGN KEY (application_id, tenant_id) REFERENCES output_applications(id, tenant_id),
    CHECK (status <> 'REVOKED' OR revoked_at IS NOT NULL),
    CHECK (status = 'REVOKED' OR (revoked_at IS NULL AND revoked_reason IS NULL)),
    CHECK (
        (reference_kind = 'FORMAL_VERSION' AND product_id IS NOT NULL AND product_version_id IS NOT NULL
            AND source_platform IS NULL AND legacy_product_id IS NULL AND legacy_decoder_revision IS NULL
            AND legacy_decoder_sha256 IS NULL AND vendor_model_id IS NULL AND driver_key IS NULL
            AND adapter_digest IS NULL)
        OR (reference_kind = 'LEGACY_PRODUCT' AND product_id IS NULL AND product_version_id IS NULL
            AND source_platform IS NOT NULL AND legacy_product_id IS NOT NULL AND vendor_model_id IS NULL
            AND driver_key IS NULL AND adapter_digest IS NULL)
        OR (reference_kind = 'VENDOR_MODEL' AND product_id IS NULL AND product_version_id IS NULL
            AND source_platform IS NOT NULL AND vendor_model_id IS NOT NULL AND legacy_product_id IS NULL
            AND legacy_decoder_revision IS NULL AND legacy_decoder_sha256 IS NULL)
    )
);
CREATE INDEX idx_application_authorizations ON application_product_authorizations(tenant_id, application_id, status);
CREATE INDEX idx_application_authorizations_ref ON application_product_authorizations(tenant_id, status,
    reference_kind, product_version_id, legacy_product_id, vendor_model_id);

-- Explicit authorized asset snapshot. The captured version is advisory context only (unversioned legacy/vendor
-- assets have none); later device migration never silently widens or retargets the grant.
CREATE TABLE application_authorization_devices (
    authorization_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    captured_product_version_id VARCHAR(64) REFERENCES product_versions(id),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (authorization_id, device_id),
    FOREIGN KEY (authorization_id, tenant_id) REFERENCES application_product_authorizations(id, tenant_id),
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id)
);
CREATE INDEX idx_application_authorization_devices ON application_authorization_devices(tenant_id, device_id);

-- Outbound destination. The full URL and the credential are encrypted at rest; the operator-facing projection
-- keeps only the readable origin, a masked path and the URL digest. An output without a URL is a draft that
-- cannot be enabled.
CREATE TABLE application_outputs (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    mode VARCHAR(24) NOT NULL CHECK (mode IN ('NORMALIZED','SINGLE_DEVICE_RAW')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    endpoint_url_cipher TEXT,
    endpoint_url_nonce VARCHAR(64),
    endpoint_origin VARCHAR(255),
    endpoint_digest VARCHAR(64),
    endpoint_masked VARCHAR(255),
    auth_scheme VARCHAR(16) NOT NULL DEFAULT 'NONE' CHECK (auth_scheme IN ('NONE','BEARER','BASIC','HEADER')),
    auth_header_name VARCHAR(64),
    credential_cipher TEXT,
    credential_nonce VARCHAR(64),
    credential_masked VARCHAR(64),
    credential_digest VARCHAR(64),
    timeout_ms INTEGER NOT NULL DEFAULT 5000 CHECK (timeout_ms BETWEEN 1000 AND 30000),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id, tenant_id),
    UNIQUE (application_id, name),
    FOREIGN KEY (application_id, tenant_id) REFERENCES output_applications(id, tenant_id),
    CHECK (enabled = FALSE OR (endpoint_url_cipher IS NOT NULL AND endpoint_digest IS NOT NULL)),
    CHECK (auth_scheme <> 'HEADER' OR auth_header_name IS NOT NULL),
    CHECK (auth_scheme = 'NONE' OR credential_cipher IS NOT NULL)
);
CREATE INDEX idx_application_outputs_app ON application_outputs(tenant_id, application_id);

-- Durable capture, one row per (immutable fact, output, output revision). The payload is the exact bytes that
-- would be posted: the producer's normalized JSON string, or the producer's original raw bytes. Never a value
-- re-serialized by this domain. Configuration and authority are frozen here and re-verified before any I/O.
CREATE TABLE application_delivery_outbox (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(64) NOT NULL,
    output_id VARCHAR(64) NOT NULL,
    output_revision BIGINT NOT NULL CHECK (output_revision >= 0),
    authorization_id VARCHAR(64) NOT NULL,
    authorization_revision BIGINT NOT NULL CHECK (authorization_revision >= 0),
    authorization_reference_kind VARCHAR(16) NOT NULL
        CHECK (authorization_reference_kind IN ('FORMAL_VERSION','LEGACY_PRODUCT','VENDOR_MODEL')),
    authorization_reference_key VARCHAR(300) NOT NULL,
    fact_id VARCHAR(160) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    product_version_id VARCHAR(64) REFERENCES product_versions(id),
    source_platform VARCHAR(64) NOT NULL,
    source_product_kind VARCHAR(16) CHECK (source_product_kind IN ('LEGACY_PRODUCT','VENDOR_MODEL')),
    source_product_id VARCHAR(200),
    source_driver_key VARCHAR(64),
    source_product_digest VARCHAR(64),
    source_message_id VARCHAR(160),
    source_event_type VARCHAR(64),
    source_event_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    alarm_id VARCHAR(64),
    mode VARCHAR(24) NOT NULL CHECK (mode IN ('NORMALIZED','SINGLE_DEVICE_RAW')),
    single_device_proof BOOLEAN NOT NULL DEFAULT FALSE,
    content_type VARCHAR(128) NOT NULL,
    payload_base64 TEXT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    payload_bytes BIGINT NOT NULL CHECK (payload_bytes > 0),
    frozen_endpoint_origin VARCHAR(255),
    frozen_endpoint_digest VARCHAR(64),
    frozen_endpoint_masked VARCHAR(255),
    frozen_auth_scheme VARCHAR(16) NOT NULL CHECK (frozen_auth_scheme IN ('NONE','BEARER','BASIC','HEADER')),
    frozen_auth_header_name VARCHAR(64),
    frozen_timeout_ms INTEGER NOT NULL CHECK (frozen_timeout_ms BETWEEN 1000 AND 30000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','IN_FLIGHT','ACCEPTED','REJECTED','UNKNOWN','BLOCKED')),
    failure_code VARCHAR(64),
    http_status INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (fact_id, output_id, output_revision),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (application_id, tenant_id) REFERENCES output_applications(id, tenant_id),
    FOREIGN KEY (output_id, tenant_id) REFERENCES application_outputs(id, tenant_id),
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CHECK (status <> 'IN_FLIGHT' OR claimed_at IS NOT NULL),
    CHECK (status IN ('PENDING','IN_FLIGHT') OR finished_at IS NOT NULL)
);
CREATE INDEX idx_application_delivery_status ON application_delivery_outbox(status, captured_at);
CREATE INDEX idx_application_delivery_app ON application_delivery_outbox(tenant_id, application_id, captured_at);

-- Immutable attempt record. A retry is never implied: one terminal status per attempt, appended once.
CREATE TABLE application_delivery_attempts (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    outbox_id VARCHAR(64) NOT NULL,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    output_revision BIGINT NOT NULL CHECK (output_revision >= 0),
    endpoint_origin VARCHAR(255),
    endpoint_digest VARCHAR(64),
    endpoint_masked VARCHAR(255),
    mode VARCHAR(24) NOT NULL CHECK (mode IN ('NORMALIZED','SINGLE_DEVICE_RAW')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACCEPTED','REJECTED','UNKNOWN','BLOCKED')),
    http_status INTEGER,
    failure_code VARCHAR(64),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0),
    UNIQUE (outbox_id, attempt_no),
    FOREIGN KEY (outbox_id, tenant_id) REFERENCES application_delivery_outbox(id, tenant_id)
);
CREATE INDEX idx_application_delivery_attempts ON application_delivery_attempts(tenant_id, outbox_id, attempt_no);
