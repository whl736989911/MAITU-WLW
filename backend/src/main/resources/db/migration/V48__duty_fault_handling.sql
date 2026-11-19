CREATE TABLE duty_fault_handlings (
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    event_id VARCHAR(64) NOT NULL REFERENCES legacy_ingest_events(id),
    handled BOOLEAN NOT NULL DEFAULT FALSE,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_by VARCHAR(64) REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,event_id)
);
CREATE TABLE duty_fault_handling_history (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    handled BOOLEAN NOT NULL,
    note VARCHAR(1000) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (tenant_id,event_id) REFERENCES duty_fault_handlings(tenant_id,event_id),
    UNIQUE (tenant_id,event_id,revision)
);
