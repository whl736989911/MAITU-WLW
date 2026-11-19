-- Maintenance work orders and unit fire archive metadata. Local human records only:
-- no vendor commands, notifications, patrol, approvals or source/physical state rewrites.
CREATE TABLE maintenance_work_orders (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    device_id VARCHAR(64) NOT NULL,
    fault_event_id VARCHAR(64) REFERENCES legacy_ingest_events(id),
    -- Set only while this order is the single active order for its source fault; cleared on close/cancel.
    active_fault_event_id VARCHAR(64) REFERENCES legacy_ingest_events(id),
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    priority VARCHAR(16),
    due_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT','OPEN','ASSIGNED','IN_PROGRESS','PENDING_REVIEW','CLOSED','CANCELLED')),
    assignee_user_id VARCHAR(64) REFERENCES app_users(id),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    CHECK (priority IS NULL OR priority IN ('LOW','NORMAL','HIGH','URGENT')),
    CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
    CHECK (status = 'CLOSED' OR closed_at IS NULL),
    CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
    CHECK (status = 'CANCELLED' OR cancelled_at IS NULL),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, active_fault_event_id),
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id)
);
CREATE INDEX idx_maintenance_work_orders_scope ON maintenance_work_orders(tenant_id, status, updated_at);
CREATE INDEX idx_maintenance_work_orders_device ON maintenance_work_orders(tenant_id, device_id);
CREATE INDEX idx_maintenance_work_orders_fault ON maintenance_work_orders(tenant_id, fault_event_id);

CREATE TABLE maintenance_work_logs (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    work_order_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    note VARCHAR(2000) NOT NULL,
    result VARCHAR(24) NOT NULL CHECK (result IN ('REPAIRED','NOT_REPAIRED','REPLACED','OTHER')),
    revision_after BIGINT NOT NULL CHECK (revision_after >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (work_order_id, tenant_id) REFERENCES maintenance_work_orders(id, tenant_id),
    UNIQUE (work_order_id, revision_after)
);

CREATE TABLE maintenance_work_order_transitions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    work_order_id VARCHAR(64) NOT NULL,
    action VARCHAR(24) NOT NULL CHECK (action IN ('CREATE','OPEN','ASSIGN','WORK_LOG','SUBMIT_REVIEW','REVIEW_PASS','REVIEW_RETURN','REOPEN','CANCEL')),
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    assignee_user_id VARCHAR(64) REFERENCES app_users(id),
    work_log_id VARCHAR(64) REFERENCES maintenance_work_logs(id),
    revision_before BIGINT,
    revision_after BIGINT NOT NULL CHECK (revision_after >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (work_order_id, tenant_id) REFERENCES maintenance_work_orders(id, tenant_id),
    UNIQUE (work_order_id, revision_after)
);

-- Replacement is recorded as history only; source hardware/vendor retirement is never implied.
CREATE TABLE maintenance_work_order_replacements (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    work_order_id VARCHAR(64) NOT NULL,
    work_log_id VARCHAR(64) NOT NULL UNIQUE REFERENCES maintenance_work_logs(id),
    source_device_id VARCHAR(64) NOT NULL,
    replacement_device_id VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (work_order_id, tenant_id) REFERENCES maintenance_work_orders(id, tenant_id),
    FOREIGN KEY (source_device_id, tenant_id) REFERENCES devices(id, tenant_id),
    FOREIGN KEY (replacement_device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CHECK (source_device_id <> replacement_device_id)
);

CREATE TABLE fire_archives (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    title VARCHAR(200) NOT NULL,
    -- Category is entered user text; no fixed industry/legal classification is inferred.
    category VARCHAR(120) NOT NULL,
    archive_number VARCHAR(120),
    device_id VARCHAR(64),
    place_id VARCHAR(64),
    issued_on DATE,
    expires_on DATE,
    description VARCHAR(4000),
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
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    FOREIGN KEY (place_id, tenant_id) REFERENCES places(id, tenant_id),
    CHECK ((status='ACTIVE' AND archived_at IS NULL AND archived_by IS NULL AND archived_reason IS NULL)
        OR (status='ARCHIVED' AND archived_at IS NOT NULL AND archived_by IS NOT NULL AND archived_reason IS NOT NULL))
);
CREATE INDEX idx_fire_archives_scope ON fire_archives(tenant_id, status, updated_at);
CREATE INDEX idx_fire_archives_device ON fire_archives(tenant_id, device_id);

CREATE TABLE fire_archive_history (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    archive_id VARCHAR(64) NOT NULL,
    action VARCHAR(24) NOT NULL CHECK (action IN ('CREATE','UPDATE','ARCHIVE','ATTACHMENT_ADD')),
    revision_before BIGINT,
    revision_after BIGINT NOT NULL CHECK (revision_after >= 0),
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (archive_id, tenant_id) REFERENCES fire_archives(id, tenant_id)
);
CREATE INDEX idx_fire_archive_history_archive ON fire_archive_history(tenant_id, archive_id, created_at);

CREATE TABLE fire_archive_attachments (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    archive_id VARCHAR(64) NOT NULL,
    filename VARCHAR(200) NOT NULL,
    mime_type VARCHAR(120) NOT NULL,
    byte_size INTEGER NOT NULL CHECK (byte_size BETWEEN 1 AND 1048576),
    content_sha256 VARCHAR(64) NOT NULL,
    ciphertext TEXT NOT NULL,
    nonce VARCHAR(32) NOT NULL,
    uploaded_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (archive_id, tenant_id) REFERENCES fire_archives(id, tenant_id)
);
CREATE INDEX idx_fire_archive_attachments_archive ON fire_archive_attachments(tenant_id, archive_id, uploaded_at);
