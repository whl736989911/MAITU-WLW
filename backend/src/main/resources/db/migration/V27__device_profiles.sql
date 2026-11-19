-- N11：可解释画像。观测历史 + 规则版本 + 不可变快照。
CREATE TABLE device_battery_observations (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 battery_percent NUMERIC(64,36) NOT NULL CHECK(battery_percent BETWEEN 0 AND 100),
 source_kind VARCHAR(24) NOT NULL CHECK(source_kind IN ('SIMULATED_APPLY')),
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_battery_obs_device ON device_battery_observations(tenant_id,device_id,observed_at);

CREATE TABLE profile_rules (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 version INTEGER NOT NULL CHECK(version >= 1),
 window_days INTEGER NOT NULL CHECK(window_days BETWEEN 1 AND 365),
 min_samples INTEGER NOT NULL CHECK(min_samples BETWEEN 1 AND 1000),
 enabled BOOLEAN NOT NULL,
 created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tenant_id,version)
);

CREATE TABLE device_profile_snapshots (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 rule_id VARCHAR(64) NOT NULL REFERENCES profile_rules(id),
 rule_version INTEGER NOT NULL,
 window_days INTEGER NOT NULL,
 min_samples INTEGER NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('OK','UNSUPPORTED','MISSING','INSUFFICIENT')),
 sample_count INTEGER NOT NULL,
 latest_percent NUMERIC(64,36),
 min_percent NUMERIC(64,36),
 max_percent NUMERIC(64,36),
 mean_percent NUMERIC(64,36),
 window_start TIMESTAMP WITH TIME ZONE NOT NULL,
 window_end TIMESTAMP WITH TIME ZONE NOT NULL,
 computed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_profile_snapshots_device ON device_profile_snapshots(tenant_id,device_id,computed_at);
