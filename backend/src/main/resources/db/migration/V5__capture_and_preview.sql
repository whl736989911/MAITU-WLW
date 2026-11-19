CREATE TABLE ingestion_sources (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 name VARCHAR(200) NOT NULL,
 protocol VARCHAR(32) NOT NULL CHECK(protocol='GENERIC_HTTP_JSON_V1'),
 is_simulated BOOLEAN NOT NULL,
 enabled BOOLEAN NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 token_hash VARCHAR(64) NOT NULL,
 token_generation BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 last_received_at TIMESTAMP WITH TIME ZONE,
 message_count BIGINT NOT NULL DEFAULT 0,
 duplicate_count BIGINT NOT NULL DEFAULT 0,
 UNIQUE(id,tenant_id)
);
CREATE INDEX idx_ingestion_sources_tenant ON ingestion_sources(tenant_id,id);
CREATE TABLE source_bindings (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 source_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 vendor_device_id VARCHAR(256) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 ended_at TIMESTAMP WITH TIME ZONE,
 bind_reason VARCHAR(1000) NOT NULL,
 bind_actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 detach_reason VARCHAR(1000),
 detach_actor_id VARCHAR(64) REFERENCES app_users(id),
 FOREIGN KEY(source_id,tenant_id) REFERENCES ingestion_sources(id,tenant_id),
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
 UNIQUE(id,source_id,device_id,vendor_device_id)
);
CREATE TABLE active_source_bindings (
 binding_id VARCHAR(64) PRIMARY KEY,
 source_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL UNIQUE,
 vendor_device_id VARCHAR(256) NOT NULL,
 FOREIGN KEY(binding_id,source_id,device_id,vendor_device_id) REFERENCES source_bindings(id,source_id,device_id,vendor_device_id),
 UNIQUE(source_id,vendor_device_id)
);
CREATE TABLE raw_messages (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 source_id VARCHAR(64) NOT NULL,
 source_name VARCHAR(200) NOT NULL,
 source_message_id VARCHAR(128) NOT NULL,
 vendor_device_id VARCHAR(256) NOT NULL,
 device_id VARCHAR(64),
 device_name VARCHAR(200),
 device_address VARCHAR(500),
 binding_id VARCHAR(64) REFERENCES source_bindings(id),
 product_version_id VARCHAR(64) REFERENCES product_versions(id),
 received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 reported_at_utf8 BYTEA,
 payload_utf8 BYTEA NOT NULL,
 payload_bytes BIGINT NOT NULL,
 payload_sha256 VARCHAR(64) NOT NULL,
 binding_state VARCHAR(16) NOT NULL CHECK(binding_state IN ('BOUND','UNBOUND')),
 duplicate_count BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0,
 latest_preview_id VARCHAR(64),
 parse_status VARCHAR(16) NOT NULL DEFAULT 'NOT_PREVIEWED' CHECK(parse_status IN ('NOT_PREVIEWED','PREVIEWED')),
 business_status VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLIED' CHECK(business_status='NOT_APPLIED'),
 FOREIGN KEY(source_id,tenant_id) REFERENCES ingestion_sources(id,tenant_id),
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
 UNIQUE(source_id,source_message_id)
);
CREATE INDEX idx_raw_messages_tenant ON raw_messages(tenant_id,received_at,id);
CREATE TABLE message_previews (
 id VARCHAR(64) PRIMARY KEY,
 raw_message_id VARCHAR(64) NOT NULL REFERENCES raw_messages(id),
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 product_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id),
 event_type VARCHAR(16) NOT NULL CHECK(event_type IN ('FIRE','RECOVERY','UNKNOWN')),
 battery_percent DECIMAL(64,36),
 diagnostics_json TEXT NOT NULL,
 reported_at_iso VARCHAR(64),
 time_quality VARCHAR(16) NOT NULL CHECK(time_quality IN ('REPORTED','MISSING','INVALID')),
 raw_event_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 business_status VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLIED' CHECK(business_status='NOT_APPLIED')
);
CREATE INDEX idx_message_previews_raw ON message_previews(tenant_id,raw_message_id,created_at,id);
INSERT INTO user_permissions(user_id,permission) SELECT id,'sources:read' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sources:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'sources:write' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sources:write');
INSERT INTO user_permissions(user_id,permission) SELECT id,'sources:bind' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sources:bind');
INSERT INTO user_permissions(user_id,permission) SELECT id,'sources:credentials' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sources:credentials');
INSERT INTO user_permissions(user_id,permission) SELECT id,'messages:read' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='messages:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'messages:raw' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='messages:raw');
INSERT INTO user_permissions(user_id,permission) SELECT id,'messages:preview' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='messages:preview');
