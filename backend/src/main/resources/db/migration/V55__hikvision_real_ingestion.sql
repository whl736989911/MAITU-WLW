-- FireCloud owns connection facts, never local tenant/place ownership.
CREATE TABLE hikvision_sync_state (
 source_key VARCHAR(64) PRIMARY KEY,
 lease_token VARCHAR(64),
 lease_until TIMESTAMP WITH TIME ZONE,
 last_started_at TIMESTAMP WITH TIME ZONE,
 last_success_at TIMESTAMP WITH TIME ZONE,
 last_error VARCHAR(1000)
);
INSERT INTO hikvision_sync_state(source_key) VALUES('HIKVISION_FIRECLOUD');
CREATE TABLE hikvision_stream_state (
 stream_kind VARCHAR(32) PRIMARY KEY,
 last_success_at TIMESTAMP WITH TIME ZONE,
 last_error VARCHAR(1000),
 record_total BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE hikvision_discovered_devices (
 external_id VARCHAR(128) PRIMARY KEY,
 resource_kind VARCHAR(16) NOT NULL CHECK(resource_kind IN ('DEVICE','SENSOR')),
 parent_external_id VARCHAR(128),
 source_created_at TIMESTAMP WITH TIME ZONE,
 serial VARCHAR(200),
 name VARCHAR(500) NOT NULL,
 model VARCHAR(200),
 device_type VARCHAR(64),
 system_type VARCHAR(64),
 source_status VARCHAR(64),
 source_updated_at TIMESTAMP WITH TIME ZONE,
 last_observed_at TIMESTAMP WITH TIME ZONE,
 online_state VARCHAR(64),
 metadata_json TEXT NOT NULL,
 local_device_id VARCHAR(64) REFERENCES devices(id),
 revision BIGINT NOT NULL DEFAULT 0,
 first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
 last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX idx_hikvision_local_device ON hikvision_discovered_devices(local_device_id);
CREATE INDEX idx_hikvision_serial ON hikvision_discovered_devices(serial);
CREATE TABLE hikvision_raw_records (
 id VARCHAR(64) PRIMARY KEY,
 stream_kind VARCHAR(32) NOT NULL,
 external_key VARCHAR(200) NOT NULL,
 external_device_id VARCHAR(128),
 payload_sha256 VARCHAR(64) NOT NULL,
 payload_json TEXT NOT NULL,
 observed_at TIMESTAMP WITH TIME ZONE,
 received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 applied_device_id VARCHAR(64) REFERENCES devices(id),
 applied_at TIMESTAMP WITH TIME ZONE,
 apply_error VARCHAR(1000),
 UNIQUE(stream_kind,external_key,payload_sha256)
);
CREATE INDEX idx_hikvision_pending_events ON hikvision_raw_records(external_device_id,applied_at,received_at);
CREATE TABLE hikvision_fault_state (
 source_fault_id VARCHAR(128) PRIMARY KEY,
 external_device_id VARCHAR(128) NOT NULL,
 normalized_event_id VARCHAR(64),
 source_code VARCHAR(64),
 source_label VARCHAR(200),
 source_start_time TIMESTAMP WITH TIME ZONE,
 source_stop_time TIMESTAMP WITH TIME ZONE,
 latest_raw_id VARCHAR(64) NOT NULL REFERENCES hikvision_raw_records(id),
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX idx_hikvision_fault_event ON hikvision_fault_state(normalized_event_id);
CREATE TABLE hikvision_alarm_state (
 source_alarm_id VARCHAR(128) PRIMARY KEY,
 external_device_id VARCHAR(128) NOT NULL,
 local_alarm_id VARCHAR(64),
 source_code VARCHAR(64),
 source_start_time TIMESTAMP WITH TIME ZONE,
 source_stop_time TIMESTAMP WITH TIME ZONE,
 latest_raw_id VARCHAR(64) NOT NULL REFERENCES hikvision_raw_records(id),
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE hikvision_metric_catalog (
 metric_key VARCHAR(96) PRIMARY KEY,
 source_code VARCHAR(64) NOT NULL,
 label VARCHAR(200) NOT NULL,
 last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);
INSERT INTO user_permissions(user_id,permission)
 SELECT id,'integrations:read' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
 AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='integrations:read');
INSERT INTO user_permissions(user_id,permission)
 SELECT id,'integrations:write' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
 AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='integrations:write');
INSERT INTO user_permissions(user_id,permission)
 SELECT id,'notification-recharge:read' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
 AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='notification-recharge:read');
INSERT INTO user_permissions(user_id,permission)
 SELECT id,'notification-recharge:write' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
 AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='notification-recharge:write');
