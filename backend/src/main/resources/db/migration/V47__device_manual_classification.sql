ALTER TABLE devices ADD COLUMN system_type VARCHAR(80);
ALTER TABLE devices ADD COLUMN device_category VARCHAR(80);
CREATE INDEX idx_devices_tenant_classification ON devices(tenant_id, system_type, device_category);
ALTER TABLE devices ADD CONSTRAINT ck_device_system_type CHECK (system_type IS NULL OR length(trim(system_type)) BETWEEN 1 AND 80);
ALTER TABLE devices ADD CONSTRAINT ck_device_category CHECK (device_category IS NULL OR length(trim(device_category)) BETWEEN 1 AND 80);

-- Rejected import rows preserve the entered value, including over-limit data.
ALTER TABLE device_import_rows ADD COLUMN system_type TEXT;
ALTER TABLE device_import_rows ADD COLUMN device_category TEXT;