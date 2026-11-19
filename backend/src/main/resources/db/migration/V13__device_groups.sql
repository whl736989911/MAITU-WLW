CREATE TABLE device_groups (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
    UNIQUE(id,tenant_id),
    CONSTRAINT uq_device_groups_tenant_name UNIQUE(tenant_id,name)
);
CREATE INDEX idx_device_groups_tenant_id ON device_groups(tenant_id,id);
CREATE TABLE device_group_members (
    tenant_id VARCHAR(64) NOT NULL,
    group_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    PRIMARY KEY(group_id,device_id),
    FOREIGN KEY(group_id,tenant_id) REFERENCES device_groups(id,tenant_id),
    FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
