-- Shared schema for the documented alarm/fault/monitor push streams and explicit receipt reparse.
-- Fault recovery evidence is independent of the recovery timestamp: a source 980004 record may
-- report faultState=1 without a stop time, and the backfill below only promotes rows that already
-- carried an explicit source_stop_time.
ALTER TABLE hikvision_fault_state ADD COLUMN source_recovered BOOLEAN;
UPDATE hikvision_fault_state SET source_recovered=TRUE WHERE source_stop_time IS NOT NULL;
-- Existing callback configuration keeps its current behaviour (device events only) until an operator
-- explicitly opts in; no new vendor stream is enabled by this migration.
ALTER TABLE hikvision_callback_config ADD COLUMN enabled_message_types_json TEXT NOT NULL DEFAULT '["980016"]';
ALTER TABLE hikvision_callback_receipts ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE hikvision_callback_receipts ADD COLUMN reparse_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE hikvision_callback_receipts ADD COLUMN last_reparse_at TIMESTAMP WITH TIME ZONE;
