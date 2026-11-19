\set ON_ERROR_STOP on

-- Synthetic-only cohort. Set tenant_count and device_count with psql -v.
INSERT INTO tenants (id, name)
SELECT 'perf-tenant-' || to_char(n, 'FM00000'),
       'PERF Synthetic Tenant ' || to_char(n, 'FM00000')
FROM generate_series(1, :tenant_count::integer) AS n
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO devices (
    id, tenant_id, name, device_eui, model, address,
    physical_state, last_message_at, is_simulated, battery_percent
)
SELECT
    'perf-device-' || to_char(n, 'FM00000'),
    'perf-tenant-' || to_char(((n - 1) % :tenant_count::integer) + 1, 'FM00000'),
    'PERF-Device-' || to_char(n, 'FM00000'),
    'PERF-' || to_char(n, 'FM00000'),
    'PERF-Synthetic-Model',
    'PERF Synthetic Address ' || to_char(n, 'FM00000'),
    CASE WHEN n % 4 = 0 THEN 'FIRE' ELSE 'NORMAL' END,
    TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00' + (n * INTERVAL '1 second'),
    TRUE,
    (n % 101)::integer
FROM generate_series(1, :device_count::integer) AS n
ON CONFLICT (id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    name = EXCLUDED.name,
    device_eui = EXCLUDED.device_eui,
    model = EXCLUDED.model,
    address = EXCLUDED.address,
    physical_state = EXCLUDED.physical_state,
    last_message_at = EXCLUDED.last_message_at,
    is_simulated = EXCLUDED.is_simulated,
    battery_percent = EXCLUDED.battery_percent;

ANALYZE tenants;
ANALYZE devices;
