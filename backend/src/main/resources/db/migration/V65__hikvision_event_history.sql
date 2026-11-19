ALTER TABLE hikvision_discovered_devices ADD COLUMN event_history_from TIMESTAMP WITH TIME ZONE;
UPDATE hikvision_discovered_devices SET event_history_from=(SELECT MAX(occurred_at) FROM device_transfers WHERE target_device_id=hikvision_discovered_devices.local_device_id);
