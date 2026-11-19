-- N18：设备人工经纬度（无底图分布）。坐标必须成对，且只能是人工维护值。
ALTER TABLE devices ADD COLUMN latitude NUMERIC(9,6);
ALTER TABLE devices ADD COLUMN longitude NUMERIC(9,6);
ALTER TABLE devices ADD CONSTRAINT chk_devices_location_pair CHECK ((latitude IS NULL AND longitude IS NULL) OR (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));
