-- N04 Frozen v1: global role templates, explicit per-tenant device scopes, effective-permission read model.
-- Five new tables only (59 -> 64). The view below is a read model, not storage.

CREATE TABLE access_roles (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    user_type VARCHAR(16) NOT NULL CHECK (user_type IN ('PLATFORM', 'CUSTOMER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    -- Soft deletion keeps history; the code of a deleted template is never reusable.
    UNIQUE (user_type, code),
    UNIQUE (id, user_type)
);
CREATE INDEX idx_access_roles_type ON access_roles(user_type, deleted);

CREATE TABLE access_role_permissions (
    role_id VARCHAR(64) NOT NULL REFERENCES access_roles(id),
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

-- Composite foreign keys make a role assignment impossible across user types.
ALTER TABLE app_users ADD CONSTRAINT uq_app_users_id_user_type UNIQUE (id, user_type);

CREATE TABLE access_user_roles (
    user_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    user_type VARCHAR(16) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id, user_type) REFERENCES app_users(id, user_type),
    FOREIGN KEY (role_id, user_type) REFERENCES access_roles(id, user_type)
);
CREATE INDEX idx_access_user_roles_role ON access_user_roles(role_id);

-- One mode per (user, tenant). No row, or an empty DEVICE_SET, grants no device rights.
CREATE TABLE user_device_scopes (
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('ALL_TENANT', 'DEVICE_SET')),
    revision BIGINT NOT NULL,
    PRIMARY KEY (user_id, tenant_id)
);

CREATE TABLE user_device_scope_devices (
    user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, tenant_id, device_id),
    FOREIGN KEY (user_id, tenant_id) REFERENCES user_device_scopes(user_id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX idx_user_device_scope_devices_tenant ON user_device_scope_devices(tenant_id, device_id);

-- Read model only: effective permission = direct permissions UNION enabled, non-deleted assigned-role permissions.
-- Never lock this view; identity writes serialize through the actor row and security_management_lock.
CREATE VIEW effective_user_permissions AS
SELECT user_id, permission FROM user_permissions
UNION
SELECT ur.user_id, rp.permission
FROM access_user_roles ur
JOIN access_roles r ON r.id = ur.role_id
JOIN access_role_permissions rp ON rp.role_id = r.id
WHERE r.enabled = TRUE AND r.deleted = FALSE;

-- Legacy explicit ALL_TENANT: existing customer home-unit device access.
INSERT INTO user_device_scopes(user_id, tenant_id, mode, revision)
SELECT u.id, u.tenant_id, 'ALL_TENANT', 1 FROM app_users u WHERE u.user_type = 'CUSTOMER';

-- Legacy explicit ALL_TENANT: existing platform grants (including future devices in that unit).
INSERT INTO user_device_scopes(user_id, tenant_id, mode, revision)
SELECT g.user_id, g.tenant_id, 'ALL_TENANT', 1
FROM user_tenant_grants g JOIN app_users u ON u.id = g.user_id
WHERE u.user_type = 'PLATFORM';

-- Only the protected bootstrap root gains the new management keys. Ordinary accounts are never promoted.
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, 'roles:read' FROM app_users u
WHERE u.id = 'bootstrap-admin' AND u.user_type = 'PLATFORM' AND u.is_protected = TRUE
AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission = 'roles:read');
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, 'roles:write' FROM app_users u
WHERE u.id = 'bootstrap-admin' AND u.user_type = 'PLATFORM' AND u.is_protected = TRUE
AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission = 'roles:write');
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, 'users:assign-roles' FROM app_users u
WHERE u.id = 'bootstrap-admin' AND u.user_type = 'PLATFORM' AND u.is_protected = TRUE
AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission = 'users:assign-roles');
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, 'users:assign-scope' FROM app_users u
WHERE u.id = 'bootstrap-admin' AND u.user_type = 'PLATFORM' AND u.is_protected = TRUE
AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission = 'users:assign-scope');
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, 'platform-users:assign-roles' FROM app_users u
WHERE u.id = 'bootstrap-admin' AND u.user_type = 'PLATFORM' AND u.is_protected = TRUE
AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission = 'platform-users:assign-roles');
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, 'platform-users:assign-scope' FROM app_users u
WHERE u.id = 'bootstrap-admin' AND u.user_type = 'PLATFORM' AND u.is_protected = TRUE
AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission = 'platform-users:assign-scope');
