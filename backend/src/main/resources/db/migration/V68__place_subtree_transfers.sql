ALTER TABLE places ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE places ADD COLUMN archived_reason VARCHAR(1000);
ALTER TABLE places ADD COLUMN archived_by VARCHAR(64);
CREATE INDEX idx_places_active ON places(tenant_id,archived_at,parent_place_id);
ALTER TABLE devices ADD COLUMN ownership_from TIMESTAMP WITH TIME ZONE;

CREATE TABLE place_transfers (
    id VARCHAR(64) PRIMARY KEY,
    source_tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    target_tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    source_place_id VARCHAR(64) NOT NULL REFERENCES places(id),
    target_place_id VARCHAR(64) NOT NULL REFERENCES places(id),
    actor_id VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    place_count BIGINT NOT NULL,
    device_count BIGINT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE place_transfer_nodes (
    transfer_id VARCHAR(64) NOT NULL REFERENCES place_transfers(id),
    source_place_id VARCHAR(64) NOT NULL REFERENCES places(id),
    target_place_id VARCHAR(64) NOT NULL REFERENCES places(id),
    PRIMARY KEY(transfer_id,source_place_id),
    UNIQUE(transfer_id,target_place_id)
);
CREATE TABLE device_transfer_alias_history (
    id VARCHAR(64) PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL REFERENCES device_transfers(id),
    alias_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    alias_value VARCHAR(128) NOT NULL,
    source_tenant_id VARCHAR(64) NOT NULL,
    source_device_id VARCHAR(64) NOT NULL,
    target_tenant_id VARCHAR(64) NOT NULL,
    target_device_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE legacy_ingest_messages ALTER COLUMN tenant_id DROP NOT NULL;
CREATE TABLE legacy_ingest_quarantines (
    message_id VARCHAR(64) PRIMARY KEY REFERENCES legacy_ingest_messages(id),
    candidate_device_id VARCHAR(64) REFERENCES devices(id),
    transfer_id VARCHAR(64) REFERENCES device_transfers(id),
    reason VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
