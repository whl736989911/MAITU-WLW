-- Immutable versioned operational portrait rules/selections and per-device evaluations.
CREATE TABLE device_portrait_rule_versions (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 version INTEGER NOT NULL CHECK(version >= 1),
 window_days INTEGER NOT NULL CHECK(window_days BETWEEN 1 AND 365),
 false_alarm_count INTEGER NOT NULL CHECK(false_alarm_count BETWEEN 1 AND 1000000),
 fault_count INTEGER NOT NULL CHECK(fault_count BETWEEN 1 AND 1000000),
 offline_count INTEGER NOT NULL CHECK(offline_count BETWEEN 1 AND 1000000),
 weak_signal_percent NUMERIC(64,36) NOT NULL CHECK(weak_signal_percent BETWEEN 1 AND 100),
 weak_signal_metric VARCHAR(8) CHECK(weak_signal_metric IS NULL OR weak_signal_metric IN ('rssi','rsrp')),
 weak_signal_threshold NUMERIC(64,36),
 low_battery_percent NUMERIC(64,36) NOT NULL CHECK(low_battery_percent BETWEEN 0 AND 100),
 long_offline_days INTEGER NOT NULL CHECK(long_offline_days BETWEEN 1 AND 365),
 CHECK((weak_signal_metric IS NULL AND weak_signal_threshold IS NULL) OR (weak_signal_metric IS NOT NULL AND weak_signal_threshold IS NOT NULL)),
 selected_labels TEXT NOT NULL,
 enabled BOOLEAN NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tenant_id,version)
);
CREATE TABLE device_portrait_snapshots (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 rule_id VARCHAR(64) NOT NULL REFERENCES device_portrait_rule_versions(id),
 rule_version INTEGER NOT NULL,
 window_start TIMESTAMP WITH TIME ZONE NOT NULL,
 window_end TIMESTAMP WITH TIME ZONE NOT NULL,
 evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 result_json TEXT NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_portrait_rules_current ON device_portrait_rule_versions(tenant_id,version);
CREATE INDEX idx_portrait_snapshots_device ON device_portrait_snapshots(tenant_id,device_id,evaluated_at);
