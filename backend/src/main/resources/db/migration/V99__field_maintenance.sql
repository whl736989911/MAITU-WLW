CREATE TABLE field_maintenance_records (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    tenant_name VARCHAR(200) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(200),
    device_serial VARCHAR(200),
    device_revision BIGINT NOT NULL,
    place_id VARCHAR(64),
    place_name VARCHAR(200),
    address VARCHAR(500),
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    actor_name VARCHAR(200) NOT NULL,
    action VARCHAR(8) NOT NULL CHECK (action IN ('HANDLE','REPLACE')),
    reason_code VARCHAR(32) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    replacement_device_id VARCHAR(64),
    replacement_serial VARCHAR(200),
    source_binding_id VARCHAR(64),
    replacement_binding_id VARCHAR(64),
    cloud_command_id VARCHAR(64),
    active_device_id VARCHAR(64),
    UNIQUE (tenant_id, active_device_id),
    UNIQUE (cloud_command_id),
    CHECK ((action='HANDLE' AND replacement_device_id IS NULL AND completed_at IS NOT NULL AND active_device_id IS NULL)
        OR (action='REPLACE' AND replacement_device_id IS NOT NULL AND replacement_device_id<>device_id
            AND ((completed_at IS NULL AND active_device_id IS NOT NULL AND active_device_id=device_id)
                OR (completed_at IS NOT NULL AND active_device_id IS NULL)))),
    CHECK (cloud_command_id IS NULL OR (source_binding_id IS NOT NULL AND replacement_binding_id IS NOT NULL)),
    FOREIGN KEY (device_id,tenant_id) REFERENCES devices(id,tenant_id),
    FOREIGN KEY (replacement_device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_field_maintenance_scope ON field_maintenance_records(tenant_id,created_at,id);
CREATE INDEX idx_field_maintenance_device ON field_maintenance_records(tenant_id,device_id,created_at,id);
