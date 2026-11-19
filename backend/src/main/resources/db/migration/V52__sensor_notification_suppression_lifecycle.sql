ALTER TABLE device_suppressions ADD COLUMN resource_kind VARCHAR(16) NOT NULL DEFAULT 'DEVICE' CHECK(resource_kind IN ('DEVICE','SENSOR'));
ALTER TABLE device_suppressions ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE device_suppressions ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device_suppressions ADD CONSTRAINT uq_suppression_tenant UNIQUE(id,tenant_id);
CREATE TABLE suppression_channels (
 suppression_id VARCHAR(64) NOT NULL,
 channel_id VARCHAR(64) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 channel_key VARCHAR(64) NOT NULL,
 channel_name VARCHAR(120) NOT NULL,
 channel_type VARCHAR(64) NOT NULL,
 device_name VARCHAR(120) NOT NULL,
 PRIMARY KEY(suppression_id,channel_id),
 FOREIGN KEY(suppression_id,tenant_id) REFERENCES device_suppressions(id,tenant_id),
 FOREIGN KEY(channel_id,tenant_id,device_id) REFERENCES device_channels(id,tenant_id,device_id)
);
ALTER TABLE alarms ADD CONSTRAINT uq_alarm_channel_owner UNIQUE(id,tenant_id,device_id);
CREATE TABLE alarm_device_channels (
 alarm_id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 channel_id VARCHAR(64) NOT NULL,
 FOREIGN KEY(alarm_id,tenant_id,device_id) REFERENCES alarms(id,tenant_id,device_id),
 FOREIGN KEY(channel_id,tenant_id,device_id) REFERENCES device_channels(id,tenant_id,device_id)
);
CREATE INDEX idx_suppression_channels_match ON suppression_channels(tenant_id,device_id,channel_id,suppression_id);
CREATE TABLE suppression_changes (
 id VARCHAR(64) PRIMARY KEY,
 suppression_id VARCHAR(64) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 action VARCHAR(64) NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 request_id VARCHAR(128) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 snapshot_json TEXT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 FOREIGN KEY(suppression_id,tenant_id) REFERENCES device_suppressions(id,tenant_id)
);
CREATE INDEX idx_suppression_changes_history ON suppression_changes(tenant_id,suppression_id,occurred_at);
