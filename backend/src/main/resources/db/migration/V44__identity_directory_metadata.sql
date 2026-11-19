CREATE TABLE identity_departments (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    parent_id VARCHAR(64),
    name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE(id,tenant_id),
    FOREIGN KEY(parent_id,tenant_id) REFERENCES identity_departments(id,tenant_id)
);
CREATE INDEX idx_identity_departments_tenant_parent ON identity_departments(tenant_id,parent_id,name);
CREATE TABLE identity_user_metadata (
    user_id VARCHAR(64) PRIMARY KEY REFERENCES app_users(id),
    department_id VARCHAR(64),
    phone VARCHAR(18) NOT NULL DEFAULT '',
    contact_email VARCHAR(254) NOT NULL DEFAULT '',
    valid_until TIMESTAMP WITH TIME ZONE,
    long_term BOOLEAN NOT NULL DEFAULT TRUE,
    duty_personnel BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY(department_id) REFERENCES identity_departments(id),
    CHECK((long_term=TRUE AND valid_until IS NULL) OR (long_term=FALSE AND valid_until IS NOT NULL))
);
CREATE INDEX idx_identity_user_metadata_department ON identity_user_metadata(department_id,duty_personnel);
