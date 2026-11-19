ALTER TABLE devices ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE devices ADD COLUMN archived_reason VARCHAR(1000);
ALTER TABLE devices ADD COLUMN archived_by VARCHAR(64) REFERENCES app_users(id);
ALTER TABLE devices ADD CONSTRAINT ck_device_archive_fields CHECK (
    (archived_at IS NULL AND archived_reason IS NULL AND archived_by IS NULL) OR
    (archived_at IS NOT NULL AND archived_reason IS NOT NULL AND archived_by IS NOT NULL)
);
ALTER TABLE tenants ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tenants ADD COLUMN archived_reason VARCHAR(1000);
ALTER TABLE tenants ADD COLUMN archived_by VARCHAR(64) REFERENCES app_users(id);
ALTER TABLE tenants ADD CONSTRAINT ck_tenant_archive_fields CHECK (
    (archived_at IS NULL AND archived_reason IS NULL AND archived_by IS NULL) OR
    (archived_at IS NOT NULL AND archived_reason IS NOT NULL AND archived_by IS NOT NULL)
);

CREATE TABLE device_transfers (
    id VARCHAR(64) PRIMARY KEY,
    source_device_id VARCHAR(64) NOT NULL,
    source_tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    target_device_id VARCHAR(64) NOT NULL UNIQUE,
    target_tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(source_device_id, source_tenant_id, target_device_id, target_tenant_id),
    FOREIGN KEY(source_device_id, source_tenant_id) REFERENCES devices(id, tenant_id),
    FOREIGN KEY(target_device_id, target_tenant_id) REFERENCES devices(id, tenant_id)
);
CREATE INDEX idx_device_transfers_source ON device_transfers(source_tenant_id, source_device_id);
