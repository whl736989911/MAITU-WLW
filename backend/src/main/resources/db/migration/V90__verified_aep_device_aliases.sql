-- Existing legacy aliases keep their original routing. Verified aliases use created_at as cutover.
ALTER TABLE device_aliases ADD COLUMN verified_platform VARCHAR(32);
ALTER TABLE device_aliases ADD CONSTRAINT ck_verified_aep_alias CHECK (verified_platform IS NULL OR (verified_platform = 'aep_turbo' AND kind = 'VENDOR_ID'));
ALTER TABLE legacy_ingest_messages ADD COLUMN aep_reported_imei VARCHAR(15);
CREATE INDEX idx_legacy_messages_vendor_received ON legacy_ingest_messages(vendor_platform, vendor_device_id, received_at);
