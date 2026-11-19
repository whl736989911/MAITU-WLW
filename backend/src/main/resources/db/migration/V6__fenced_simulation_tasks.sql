ALTER TABLE devices ALTER COLUMN battery_percent TYPE NUMERIC(64,36);
CREATE TABLE processing_tasks (
 id VARCHAR(64) PRIMARY KEY,
 kind VARCHAR(40) NOT NULL CHECK(kind='SIMULATED_MESSAGE_APPLY'),
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 raw_message_id VARCHAR(64) NOT NULL REFERENCES raw_messages(id),
 source_id VARCHAR(64) NOT NULL REFERENCES ingestion_sources(id),
 source_message_id VARCHAR(128) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 device_name VARCHAR(200) NOT NULL,
 product_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id),
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 actor_revision BIGINT NOT NULL,
 tenant_revision BIGINT NOT NULL,
 source_revision BIGINT NOT NULL,
 expected_device_revision BIGINT NOT NULL,
 recovery_alarm_id VARCHAR(64),
 reason VARCHAR(1000) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','REJECTED','CANCELLED')),
 revision BIGINT NOT NULL DEFAULT 0,
 attempt_count INTEGER NOT NULL DEFAULT 0,
 max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 20),
 available_at TIMESTAMP WITH TIME ZONE NOT NULL,
 lease_owner VARCHAR(64),
 lease_until TIMESTAMP WITH TIME ZONE,
 lease_token BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 finished_at TIMESTAMP WITH TIME ZONE,
 last_error_code VARCHAR(64),
 cancel_reason VARCHAR(1000),
 cancel_actor_id VARCHAR(64) REFERENCES app_users(id),
 result_json TEXT,
 UNIQUE(raw_message_id,kind),
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_processing_claim ON processing_tasks(status,available_at,lease_until);
CREATE INDEX idx_processing_tenant ON processing_tasks(tenant_id,created_at,id);
CREATE TABLE processing_attempts (
 id VARCHAR(64) PRIMARY KEY,
 task_id VARCHAR(64) NOT NULL REFERENCES processing_tasks(id),
 attempt_no INTEGER NOT NULL,
 lease_token BIGINT NOT NULL,
 worker_id VARCHAR(64) NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 finished_at TIMESTAMP WITH TIME ZONE,
 outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('RUNNING','SUCCEEDED','REJECTED','RETRY','EXPIRED','CANCELLED')),
 error_code VARCHAR(64),
 UNIQUE(task_id,attempt_no),
 UNIQUE(task_id,lease_token)
);
CREATE TABLE device_task_applications (
 task_id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 event_id VARCHAR(64) NOT NULL REFERENCES device_events(id),
 result_json TEXT NOT NULL,
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
INSERT INTO user_permissions(user_id,permission) SELECT id,'tasks:read' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='tasks:read');
INSERT INTO user_permissions(user_id,permission) SELECT id,'tasks:simulate' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='tasks:simulate');
