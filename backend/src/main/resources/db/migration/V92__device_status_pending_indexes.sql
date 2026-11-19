CREATE INDEX idx_alarms_device_pending ON alarms(tenant_id, device_id, event_type, response_state);
CREATE INDEX idx_legacy_events_device_kind ON legacy_ingest_events(tenant_id, device_id, kind);
