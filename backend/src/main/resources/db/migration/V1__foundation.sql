CREATE TABLE bootstrap_lock (id INTEGER PRIMARY KEY);
INSERT INTO bootstrap_lock (id) VALUES (1);
CREATE TABLE tenants (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL
);
CREATE TABLE app_users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    user_type VARCHAR(16) NOT NULL CHECK (user_type IN ('PLATFORM', 'CUSTOMER')),
    tenant_id VARCHAR(64) REFERENCES tenants(id),
    password_hash VARCHAR(512) NOT NULL,
    must_change_password BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK ((user_type = 'CUSTOMER' AND tenant_id IS NOT NULL) OR (user_type = 'PLATFORM' AND tenant_id IS NULL))
);
CREATE TABLE user_permissions (
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, permission)
);
CREATE TABLE user_tenant_grants (
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    PRIMARY KEY (user_id, tenant_id)
);
CREATE TABLE sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    audience VARCHAR(16) NOT NULL CHECK (audience IN ('management', 'customer')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE TABLE devices (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    device_eui VARCHAR(64) NOT NULL UNIQUE,
    model VARCHAR(100) NOT NULL,
    address VARCHAR(500) NOT NULL,
    physical_state VARCHAR(16) NOT NULL CHECK (physical_state IN ('NORMAL', 'FIRE')),
    last_message_at TIMESTAMP WITH TIME ZONE,
    is_simulated BOOLEAN NOT NULL DEFAULT FALSE,
    battery_percent INTEGER CHECK (battery_percent >= 0 AND battery_percent <= 100),
    UNIQUE (id, tenant_id)
);
CREATE INDEX idx_devices_tenant ON devices(tenant_id);
CREATE TABLE alarms (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    device_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type = 'FIRE'),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    physical_state VARCHAR(16) NOT NULL CHECK (physical_state IN ('NORMAL', 'FIRE')),
    response_state VARCHAR(16) NOT NULL CHECK (response_state IN ('UNHANDLED', 'HANDLED')),
    conclusion VARCHAR(32) CHECK (conclusion IN ('TRUE_FIRE','FALSE_ALARM','TEST','DEVICE_ALARM')),
    remark VARCHAR(2000),
    revision BIGINT NOT NULL DEFAULT 0,
    is_simulated BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    CHECK ((response_state = 'UNHANDLED' AND conclusion IS NULL) OR (response_state = 'HANDLED' AND conclusion IS NOT NULL))
);
CREATE INDEX idx_alarms_tenant_time ON alarms(tenant_id, occurred_at);
CREATE TABLE device_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    device_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('FIRE', 'RECOVERY')),
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    result_json TEXT NOT NULL,
    FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
    UNIQUE (tenant_id, device_id, message_id)
);
CREATE TABLE idempotency_records (
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    operation VARCHAR(32) NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, operation, idem_key)
);
CREATE TABLE numeric_settings (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO numeric_settings (setting_key, setting_value) VALUES ('offline_threshold_minutes', 2880);
INSERT INTO numeric_settings (setting_key, setting_value) VALUES ('default_page_size', 20);
CREATE TABLE audit_log (
    id VARCHAR(64) PRIMARY KEY,
    actor_id VARCHAR(64),
    tenant_id VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    target_id VARCHAR(128),
    outcome VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_tenant_time ON audit_log(tenant_id, occurred_at);
