-- NULL deliberately means historical provenance was never recorded; do not invent evidence.
ALTER TABLE alarms ADD COLUMN source_time_known BOOLEAN;
ALTER TABLE alarms ADD COLUMN platform_recorded_at TIMESTAMP WITH TIME ZONE;
