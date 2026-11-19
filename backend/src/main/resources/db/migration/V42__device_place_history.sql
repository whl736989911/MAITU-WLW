-- No backfill: current device placement cannot establish a historical move.
-- Place IDs/names are snapshots, deliberately not live foreign keys to places.
CREATE TABLE device_place_history (
 id VARCHAR(64) PRIMARY KEY,
 device_id VARCHAR(64) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL CHECK(action IN ('REGISTER','MOVE')),
 from_place_id VARCHAR(64),
 from_place_name VARCHAR(200),
 to_place_id VARCHAR(64) NOT NULL,
 to_place_name VARCHAR(200) NOT NULL,
 revision_before BIGINT,
 revision_after BIGINT NOT NULL,
 actor_id VARCHAR(64) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
 UNIQUE(device_id,revision_after),
 CHECK ((action='REGISTER' AND from_place_id IS NULL AND from_place_name IS NULL AND revision_before IS NULL AND revision_after=0)
     OR (action='MOVE' AND revision_before IS NOT NULL AND revision_before>=0 AND revision_after=revision_before+1)),
 CHECK ((from_place_id IS NULL AND from_place_name IS NULL) OR (from_place_id IS NOT NULL AND from_place_name IS NOT NULL))
);
CREATE INDEX idx_device_place_history ON device_place_history(tenant_id,device_id,revision_after);
