-- Hikvision-style exact key + legacy 180s window support on normalized legacy events.
ALTER TABLE legacy_ingest_events ADD COLUMN business_key VARCHAR(64);
ALTER TABLE legacy_ingest_events ADD COLUMN business_code VARCHAR(64);
CREATE UNIQUE INDEX uq_legacy_events_business ON legacy_ingest_events(tenant_id, device_id, business_key);
CREATE INDEX idx_legacy_events_window ON legacy_ingest_events(tenant_id, device_id, business_code, observed_at);
