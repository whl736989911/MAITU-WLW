CREATE TABLE products (
 id VARCHAR(64) PRIMARY KEY,
 name VARCHAR(200) NOT NULL,
 manufacturer VARCHAR(100) NOT NULL,
 model VARCHAR(100) NOT NULL,
 description VARCHAR(2000) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 UNIQUE(manufacturer,model)
);
CREATE TABLE product_versions (
 id VARCHAR(64) PRIMARY KEY,
 product_id VARCHAR(64) NOT NULL REFERENCES products(id),
 version_number BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED')),
 revision BIGINT NOT NULL DEFAULT 0,
 schema_json TEXT NOT NULL,
 capabilities_json TEXT NOT NULL,
 parser_tested BOOLEAN NOT NULL DEFAULT FALSE,
 test_summary_json TEXT,
 evidence_revision BIGINT,
 evidence_fingerprint VARCHAR(64),
 published_at TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(product_id,version_number)
);
ALTER TABLE devices RENAME COLUMN physical_state TO old_physical_state;
ALTER TABLE devices ADD COLUMN physical_state VARCHAR(16) CHECK(physical_state IN ('NORMAL','FIRE','UNKNOWN'));
UPDATE devices SET physical_state=old_physical_state;
ALTER TABLE devices ALTER COLUMN physical_state SET NOT NULL;
ALTER TABLE devices DROP COLUMN old_physical_state;
ALTER TABLE devices ADD COLUMN product_id VARCHAR(64) REFERENCES products(id);
ALTER TABLE devices ADD COLUMN product_version_id VARCHAR(64) REFERENCES product_versions(id);
ALTER TABLE devices ADD COLUMN product_name VARCHAR(200);
ALTER TABLE devices ADD COLUMN version_number BIGINT;
ALTER TABLE devices ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN integration_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_SIMULATION' CHECK(integration_state IN ('LEGACY_SIMULATION','PENDING_INTEGRATION'));
CREATE TABLE device_version_history (
 id VARCHAR(64) PRIMARY KEY,
 device_id VARCHAR(64) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 from_version_id VARCHAR(64) REFERENCES product_versions(id),
 to_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id),
 reason VARCHAR(1000) NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_device_version_history ON device_version_history(tenant_id,device_id,occurred_at);
INSERT INTO user_permissions(user_id,permission) SELECT id,'products:read' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='products:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'products:write' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='products:write');
INSERT INTO user_permissions(user_id,permission) SELECT id,'products:publish' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='products:publish');
INSERT INTO user_permissions(user_id,permission) SELECT id,'device:write' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='device:write');
INSERT INTO user_permissions(user_id,permission) SELECT id,'device:migrate' FROM app_users u WHERE u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='device:migrate');
