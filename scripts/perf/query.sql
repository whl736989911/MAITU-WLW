\set ON_ERROR_STOP on

EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, name, device_eui, model, address, physical_state, last_message_at, battery_percent
FROM devices
WHERE tenant_id = 'perf-tenant-00001'
ORDER BY id
LIMIT 50 OFFSET 1000;

EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, name, device_eui, model, address, physical_state, last_message_at, battery_percent
FROM devices
WHERE tenant_id = 'perf-tenant-00001'
  AND physical_state = 'FIRE'
ORDER BY id
LIMIT 50 OFFSET 0;

EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, name, device_eui, model, address, physical_state, last_message_at, battery_percent
FROM devices
WHERE tenant_id = 'perf-tenant-00001'
  AND (name ILIKE '%PERF-00001%' OR address ILIKE '%PERF-00001%')
ORDER BY id
LIMIT 50 OFFSET 0;

EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT physical_state, COUNT(*) AS device_count
FROM devices
WHERE tenant_id = 'perf-tenant-00001'
GROUP BY physical_state
ORDER BY physical_state;
