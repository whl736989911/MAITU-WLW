-- Retire fake execution while preserving task, attempt, event, alarm, and audit history.
-- Preserve the previous marker independently; never relabel legacy records as real.
ALTER TABLE devices RENAME COLUMN integration_state TO legacy_integration_state;
ALTER TABLE devices ADD COLUMN integration_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED'
    CHECK(integration_state IN ('CONNECTED','PENDING_INTEGRATION','LEGACY_UNVERIFIED','RETIRED_SIMULATION'));
UPDATE devices SET integration_state=CASE
    WHEN is_simulated=TRUE THEN 'RETIRED_SIMULATION'
    WHEN legacy_integration_state='PENDING_INTEGRATION' THEN 'PENDING_INTEGRATION'
    ELSE 'LEGACY_UNVERIFIED'
END;
ALTER TABLE devices ALTER COLUMN legacy_integration_state DROP DEFAULT;
ALTER TABLE devices ALTER COLUMN legacy_integration_state DROP NOT NULL;
ALTER TABLE raw_messages ADD COLUMN capture_provenance VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED'
    CHECK(capture_provenance IN ('AUTHENTICATED_SOURCE','LEGACY_UNVERIFIED'));
ALTER TABLE raw_messages ADD COLUMN occurred_at_raw VARCHAR(64);
CREATE INDEX idx_raw_authenticated_apply ON raw_messages(capture_provenance,binding_state,application_state,received_at,id);
CREATE TABLE real_device_battery_observations (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    battery_percent NUMERIC(64,36) NOT NULL CHECK(battery_percent BETWEEN 0 AND 100),
    source_kind VARCHAR(24) NOT NULL CHECK(source_kind IN ('GENERIC_INGEST','HIKVISION_FIRECLOUD')),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_real_battery_obs_device ON real_device_battery_observations(tenant_id,device_id,observed_at);
UPDATE processing_attempts
SET outcome='CANCELLED', finished_at=CURRENT_TIMESTAMP, error_code='SIMULATION_RETIRED'
WHERE outcome='RUNNING' AND task_id IN (SELECT id FROM processing_tasks WHERE status IN ('PENDING','RUNNING'));
UPDATE processing_tasks
SET status='CANCELLED', revision=revision+1, finished_at=CURRENT_TIMESTAMP,
    cancel_reason='SIMULATION_RETIRED_V54', last_error_code='SIMULATION_RETIRED'
WHERE status IN ('PENDING','RUNNING');
UPDATE access_roles SET revision=revision+1 WHERE id IN (
    SELECT role_id FROM access_role_permissions WHERE permission IN (
        'simulation:write','device-commands:read','device-commands:write','tasks:simulate',
        'notifications:simulate','notification-automation:read','notification-automation:write',
        'notification-templates:read','notification-templates:write'));
DELETE FROM access_role_permissions WHERE permission IN (
    'simulation:write','device-commands:read','device-commands:write','tasks:simulate',
    'notifications:simulate','notification-automation:read','notification-automation:write',
    'notification-templates:read','notification-templates:write');
DELETE FROM user_permissions WHERE permission IN (
    'simulation:write','device-commands:read','device-commands:write','tasks:simulate',
    'notifications:simulate','notification-automation:read','notification-automation:write',
    'notification-templates:read','notification-templates:write');
