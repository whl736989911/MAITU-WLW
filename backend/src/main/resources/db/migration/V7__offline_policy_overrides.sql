CREATE TABLE parameter_overrides (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 scope VARCHAR(16) NOT NULL CHECK(scope IN ('TENANT','DEVICE')),
 scope_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64),
 parameter_key VARCHAR(64) NOT NULL CHECK(parameter_key='offline_threshold_minutes'),
 override_value BIGINT CHECK(override_value BETWEEN 1 AND 525600),
 revision BIGINT NOT NULL CHECK(revision>=1),
 UNIQUE(tenant_id,scope,scope_id,parameter_key),
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
 CHECK((scope='TENANT' AND scope_id=tenant_id AND device_id IS NULL) OR (scope='DEVICE' AND device_id IS NOT NULL AND device_id=scope_id))
);
CREATE TABLE parameter_changes (
 id VARCHAR(64) PRIMARY KEY,
 override_id VARCHAR(64) NOT NULL REFERENCES parameter_overrides(id),
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 scope VARCHAR(16) NOT NULL CHECK(scope IN ('TENANT','DEVICE')),
 scope_id VARCHAR(64) NOT NULL,
 parameter_key VARCHAR(64) NOT NULL CHECK(parameter_key='offline_threshold_minutes'),
 action VARCHAR(16) NOT NULL CHECK(action IN ('SET','CLEAR')),
 previous_value BIGINT CHECK(previous_value BETWEEN 1 AND 525600),
 new_value BIGINT CHECK(new_value BETWEEN 1 AND 525600),
 revision_before BIGINT NOT NULL CHECK(revision_before>=0),
 revision_after BIGINT NOT NULL CHECK(revision_after=revision_before+1),
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(override_id,revision_after),
 CHECK((action='SET' AND new_value IS NOT NULL) OR (action='CLEAR' AND new_value IS NULL AND previous_value IS NOT NULL))
);
CREATE INDEX idx_parameter_history ON parameter_changes(tenant_id,scope,scope_id,parameter_key,created_at,id);
INSERT INTO user_permissions(user_id,permission) SELECT id,'parameters:read' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='parameters:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'parameters:write' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='parameters:write');
