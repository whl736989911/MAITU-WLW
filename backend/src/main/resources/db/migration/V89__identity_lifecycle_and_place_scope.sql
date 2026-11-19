-- PLACE_SUBTREE is a structural device scope: a non-empty set of same-unit place roots plus every
-- descendant place (and therefore every present or future device under them). ALL_TENANT keeps no
-- member rows; DEVICE_SET keeps explicit devices only.
--
-- The v1 CHECK constraint cannot be altered in place, so both scope tables are rebuilt with all rows
-- and the explicit device members preserved. No scope is widened and no default grant is created.
CREATE TABLE user_device_scopes_v89 (
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('ALL_TENANT', 'DEVICE_SET', 'PLACE_SUBTREE')),
    revision BIGINT NOT NULL,
    PRIMARY KEY (user_id, tenant_id)
);
INSERT INTO user_device_scopes_v89(user_id, tenant_id, mode, revision)
SELECT user_id, tenant_id, mode, revision FROM user_device_scopes;

CREATE TABLE user_device_scope_devices_v89 (
    user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, tenant_id, device_id),
    FOREIGN KEY (user_id, tenant_id) REFERENCES user_device_scopes_v89(user_id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id) ON DELETE CASCADE
);
INSERT INTO user_device_scope_devices_v89(user_id, tenant_id, device_id)
SELECT user_id, tenant_id, device_id FROM user_device_scope_devices;

DROP TABLE user_device_scope_devices;
DROP TABLE user_device_scopes;
ALTER TABLE user_device_scopes_v89 RENAME TO user_device_scopes;
ALTER TABLE user_device_scope_devices_v89 RENAME TO user_device_scope_devices;
CREATE INDEX idx_user_device_scope_devices_tenant ON user_device_scope_devices(tenant_id, device_id);

CREATE TABLE user_device_scope_places (
    user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    place_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, tenant_id, place_id),
    FOREIGN KEY (user_id, tenant_id) REFERENCES user_device_scopes(user_id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (place_id, tenant_id) REFERENCES places(id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX idx_user_device_scope_places_tenant ON user_device_scope_places(tenant_id, place_id);

-- Local fault disposition becomes its own permission so customer accounts are never widened by
-- default. Existing platform users that truly held device:write keep that ability: direct holders get
-- the direct permission and platform role templates get the template permission. Customer accounts
-- (direct or role) receive nothing here; an explicit assignment is required.
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, 'faults:handle' FROM app_users u
WHERE u.user_type = 'PLATFORM'
  AND EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission = 'device:write')
  AND NOT EXISTS (SELECT 1 FROM user_permissions existing WHERE existing.user_id = u.id AND existing.permission = 'faults:handle');

INSERT INTO access_role_permissions(role_id, permission)
SELECT r.id, 'faults:handle' FROM access_roles r
WHERE r.user_type = 'PLATFORM' AND r.deleted = FALSE
  AND EXISTS (SELECT 1 FROM access_role_permissions p WHERE p.role_id = r.id AND p.permission = 'device:write')
  AND NOT EXISTS (SELECT 1 FROM access_role_permissions existing WHERE existing.role_id = r.id AND existing.permission = 'faults:handle');
