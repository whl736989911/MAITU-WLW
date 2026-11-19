-- V84: product lifecycle state + bounded normalized-product measurement/event evidence.
-- Additive/portable DDL only. No business rows are invented and no existing published version is touched.

-- Soft product archive. Archived products keep every published version, device binding and stored fact; the
-- lifecycle gate only refuses new draft creation/clone, publishing, new registration, new binding and a new
-- migration target. manufacturer/model/id stay immutable identity.
ALTER TABLE products ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (state IN ('ACTIVE','ARCHIVED'));
ALTER TABLE products ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE products ADD COLUMN archived_by VARCHAR(64) REFERENCES app_users(id);
ALTER TABLE products ADD COLUMN archived_reason VARCHAR(1000);
ALTER TABLE products ADD CONSTRAINT ck_product_archive_fields CHECK (
    (state = 'ACTIVE' AND archived_at IS NULL AND archived_by IS NULL AND archived_reason IS NULL)
    OR (state = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_by IS NOT NULL));
CREATE INDEX idx_products_state ON products(state,id);

-- Provenance for normalized generic-product evidence bridged into the existing legacy event projection
-- (fault pages/handling/maintenance conversion, telemetry history/current/trends, device history, export).
-- message_id stays NULL for these rows: no fake legacy_ingest_messages receipt is created.
ALTER TABLE legacy_ingest_events ADD COLUMN raw_message_id VARCHAR(64) REFERENCES raw_messages(id) ON DELETE SET NULL;
ALTER TABLE legacy_ingest_events ADD COLUMN source_platform VARCHAR(64);
CREATE INDEX idx_legacy_events_raw_message ON legacy_ingest_events(raw_message_id);

-- Preview now carries the same typed readings as the parser and must accept every applicable event kind.
-- No FK references this table, so a portable copy/replace is safe and preserves existing evidence rows.
CREATE TABLE message_previews_v84 (
    id VARCHAR(64) PRIMARY KEY,
    raw_message_id VARCHAR(64) NOT NULL REFERENCES raw_messages(id),
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    product_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id),
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('FIRE','RECOVERY','FAULT','HEARTBEAT','PARAM','OTHER','UNKNOWN')),
    battery_percent DECIMAL(64,36),
    readings_json TEXT NOT NULL,
    diagnostics_json TEXT NOT NULL,
    reported_at_iso VARCHAR(64),
    time_quality VARCHAR(16) NOT NULL CHECK (time_quality IN ('REPORTED','MISSING','INVALID')),
    raw_event_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    business_status VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLIED' CHECK (business_status = 'NOT_APPLIED')
);
INSERT INTO message_previews_v84(id,raw_message_id,tenant_id,product_version_id,event_type,battery_percent,
    readings_json,diagnostics_json,reported_at_iso,time_quality,raw_event_json,created_at,actor_id,reason,business_status)
SELECT id,raw_message_id,tenant_id,product_version_id,event_type,battery_percent,
    '[]',diagnostics_json,reported_at_iso,time_quality,raw_event_json,created_at,actor_id,reason,business_status
FROM message_previews;
DROP TABLE message_previews;
ALTER TABLE message_previews_v84 RENAME TO message_previews;
CREATE INDEX idx_message_previews_raw ON message_previews(tenant_id,raw_message_id,created_at,id);
