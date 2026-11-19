ALTER TABLE alarm_change_outbox ADD COLUMN device_id VARCHAR(64);

-- Historical outbox ids are trace-entry ids, not alarm ids. Attribute only rows with a
-- trustworthy trace-to-alarm-to-device chain; unresolved legacy rows remain tenant-wide
-- invalidations for ALL_TENANT scopes and are excluded from DEVICE_SET scopes.
UPDATE alarm_change_outbox o
SET device_id = (
    SELECT a.device_id
    FROM alarm_trace_entries t
    JOIN alarms a ON a.tenant_id=t.tenant_id AND a.id=t.alarm_id
    WHERE t.id=o.id AND t.tenant_id=o.tenant_id
);

CREATE INDEX idx_alarm_change_device_page
ON alarm_change_outbox(tenant_id,device_id,publication_sequence,id);
