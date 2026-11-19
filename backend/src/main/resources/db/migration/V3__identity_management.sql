ALTER TABLE tenants ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tenants ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_users ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_users ADD COLUMN is_protected BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE app_users SET is_protected=TRUE WHERE id='bootstrap-admin' AND user_type='PLATFORM';
CREATE TABLE security_management_lock (id INTEGER PRIMARY KEY);
INSERT INTO security_management_lock(id) VALUES (1);
CREATE TABLE identity_command_records (
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    operation VARCHAR(64) NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    secret_hash VARCHAR(512),
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(actor_id,operation,idem_key)
);
INSERT INTO user_permissions(user_id,permission) SELECT id,'units:read' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='units:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'units:write' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='units:write');
INSERT INTO user_permissions(user_id,permission) SELECT id,'users:read' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='users:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'users:write' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='users:write');
INSERT INTO user_permissions(user_id,permission) SELECT id,'users:reset-password' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='users:reset-password');
INSERT INTO user_permissions(user_id,permission) SELECT id,'platform-users:read' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='platform-users:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'platform-users:write' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='platform-users:write');
INSERT INTO user_permissions(user_id,permission) SELECT id,'platform-users:reset-password' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS (SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='platform-users:reset-password');
