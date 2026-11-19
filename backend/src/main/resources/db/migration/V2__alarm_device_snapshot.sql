-- Alarm owns its historical presentation fields; reads do not join another module's table.
ALTER TABLE alarms ADD COLUMN device_name VARCHAR(200);
ALTER TABLE alarms ADD COLUMN address VARCHAR(500);
UPDATE alarms SET device_name = (SELECT name FROM devices WHERE devices.id = alarms.device_id), address = (SELECT address FROM devices WHERE devices.id = alarms.device_id);
ALTER TABLE alarms ALTER COLUMN device_name SET NOT NULL;
ALTER TABLE alarms ALTER COLUMN address SET NOT NULL;
