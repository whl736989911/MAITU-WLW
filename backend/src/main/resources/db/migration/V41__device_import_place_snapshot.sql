-- Preserve the requested place as import evidence, not a live foreign key.
-- Historical import rows remain NULL; never infer their original place from today's device.
-- TEXT also retains invalid/overlong input in rejected rows without truncating evidence.
ALTER TABLE device_import_rows ADD COLUMN place_id TEXT;
