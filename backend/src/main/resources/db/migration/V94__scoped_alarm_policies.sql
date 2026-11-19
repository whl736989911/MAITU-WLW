-- V45 remains the only unit policy and the hard unit service gate.
CREATE TABLE alarm_scoped_policies (
    event_kind VARCHAR(8) NOT NULL CHECK (event_kind IN ('FIRE','FAULT')),
    scope_kind VARCHAR(8) NOT NULL CHECK (scope_kind IN ('PLATFORM','PLACE','DEVICE')),
    scope_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) REFERENCES tenants(id),
    place_id VARCHAR(64),
    device_id VARCHAR(64),
    config_json TEXT,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_kind,scope_kind,scope_id),
    FOREIGN KEY (place_id,tenant_id) REFERENCES places(id,tenant_id),
    FOREIGN KEY (device_id,tenant_id) REFERENCES devices(id,tenant_id),
    CHECK ((scope_kind='PLATFORM' AND scope_id='platform' AND tenant_id IS NULL AND place_id IS NULL AND device_id IS NULL)
        OR (scope_kind='PLACE' AND tenant_id IS NOT NULL AND place_id IS NOT NULL AND place_id=scope_id AND device_id IS NULL)
        OR (scope_kind='DEVICE' AND tenant_id IS NOT NULL AND device_id IS NOT NULL AND device_id=scope_id AND place_id IS NULL))
);
