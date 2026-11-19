ALTER TABLE product_versions ADD COLUMN battery_low_comparator VARCHAR(3);
ALTER TABLE product_versions ADD COLUMN battery_low_threshold NUMERIC(64,36);
ALTER TABLE product_versions ADD CONSTRAINT chk_product_version_battery_threshold_pair CHECK ((battery_low_comparator IS NULL AND battery_low_threshold IS NULL) OR (battery_low_comparator IS NOT NULL AND battery_low_threshold IS NOT NULL AND battery_low_comparator IN ('LT','LTE') AND battery_low_threshold BETWEEN 0 AND 100));

CREATE TABLE device_battery_threshold_overrides (
    device_id VARCHAR(64) PRIMARY KEY REFERENCES devices(id),
    comparator VARCHAR(3),
    threshold NUMERIC(64,36),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    CHECK ((comparator IS NULL AND threshold IS NULL) OR (comparator IS NOT NULL AND threshold IS NOT NULL AND comparator IN ('LT','LTE') AND threshold BETWEEN 0 AND 100))
);

CREATE TABLE device_battery_threshold_changes (
    id VARCHAR(64) PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL REFERENCES devices(id),
    action VARCHAR(5) NOT NULL CHECK (action IN ('SET','CLEAR')),
    previous_comparator VARCHAR(3),
    previous_threshold NUMERIC(64,36),
    new_comparator VARCHAR(3),
    new_threshold NUMERIC(64,36),
    revision_before BIGINT NOT NULL CHECK (revision_before >= 0),
    revision_after BIGINT NOT NULL CHECK (revision_after = revision_before + 1),
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(device_id, revision_after),
    CHECK ((previous_comparator IS NULL AND previous_threshold IS NULL) OR (previous_comparator IS NOT NULL AND previous_threshold IS NOT NULL AND previous_comparator IN ('LT','LTE') AND previous_threshold BETWEEN 0 AND 100)),
    CHECK ((new_comparator IS NULL AND new_threshold IS NULL) OR (new_comparator IS NOT NULL AND new_threshold IS NOT NULL AND new_comparator IN ('LT','LTE') AND new_threshold BETWEEN 0 AND 100)),
    CHECK ((action='SET' AND new_threshold IS NOT NULL) OR (action='CLEAR' AND new_threshold IS NULL))
);
CREATE INDEX idx_device_battery_threshold_changes ON device_battery_threshold_changes(device_id,created_at DESC,id DESC);
