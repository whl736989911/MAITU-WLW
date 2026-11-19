import { ApiError } from './api'
import { parseJsonDocument, jsonFieldText } from './json-document'
import { integerToken } from './parameter-json'
import { canonicalAlarmInstant } from './alarm-workbench'
import type { Device, EffectiveParameter } from './types'
import { readDeviceStatus } from './device-status-json'
import { policy as batteryPolicy } from './battery-json'
export type SafeDeviceRow = Device & { tenant_id: string; tenant_name: string | null; place_name: string | null; revision: number; system_type: string | null; device_category: string | null; offline_policy: EffectiveParameter }
function bad(): never { throw new ApiError(200, 'DEVICE_DETAIL_UNCONFIRMED', '设备详情/关联报警响应未确认；不替代为旧行、正常或0') }
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
const nullable = (v: unknown) => v === null || typeof v === 'string'
function pick<T>(v: unknown, keys: string[]): T { if (!v || typeof v !== 'object' || Array.isArray(v)) return bad(); return Object.fromEntries(keys.filter(k => Object.hasOwn(v, k)).map(k => [k, (v as Record<string, unknown>)[k]])) as T }
function field(raw: string, key: string) { const v = jsonFieldText(raw, key); if (v === undefined) return bad(); return v }
function integer(raw: string, key: string, min = 0) { const v = integerToken(field(raw, key)); if (v < min) return bad(); return v }
function batteryInRange(raw: string) {
  const m = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw); if (!m) return false
  const significant = (m[2] + (m[3] ?? '')).replace(/^0+/, ''); if (!significant) return true
  if (m[1]) return false
  const position = significant.length + Number(m[4] ?? 0) - (m[3]?.length ?? 0)
  return position < 3 || (position === 3 && /^10*$/.test(significant))
}
/** Shared lazy read-only row projection. Always consume original spans, never a reconstructed JSON object. */
export function readSafeDeviceRow(raw: string, context: { asOf: string; tenantId: string | null; expectedId?: string }): SafeDeviceRow {
  const device = pick<SafeDeviceRow>(parseJsonDocument(raw), ['id', 'tenant_id', 'tenant_name', 'name', 'device_eui', 'model', 'address', 'connectivity', 'physical_state', 'last_message_at', 'battery_percent', 'is_simulated', 'product_id', 'product_version_id', 'product_name', 'version_number', 'revision', 'place_id', 'place_name', 'integration_state', 'legacy_integration_state', 'system_type', 'device_category', 'offline_policy', 'battery_policy', 'status_display'])
  if (!text(device.id) || (context.expectedId !== undefined && device.id !== context.expectedId) || ![device.name, device.device_eui, device.model, device.address].every(nullable) || !nullable(device.tenant_name) || !nullable(device.place_name) || !nullable(device.system_type) || !nullable(device.device_category) || !['ONLINE', 'OFFLINE', 'UNACTIVATED', 'UNKNOWN'].includes(device.connectivity ?? '') || !['UNKNOWN', 'NORMAL', 'FIRE'].includes(device.physical_state ?? '') || !['CONNECTED', 'PENDING_INTEGRATION', 'LEGACY_UNVERIFIED', 'RETIRED_SIMULATION'].includes(device.integration_state ?? '')) return bad()
  if (!text(device.tenant_id) || (context.tenantId !== null && device.tenant_id !== context.tenantId)) return bad()
  canonicalAlarmInstant(context.asOf); if (device.last_message_at !== null) canonicalAlarmInstant(device.last_message_at!, false)
  if ((device.last_message_at === null) !== (device.connectivity === 'UNACTIVATED')) return bad()
  device.revision = integer(raw, 'revision')
  if (device.product_version_id === null) { if ([device.product_id, device.product_name, device.version_number].some(v => v !== null)) return bad() }
  else { if (![device.product_id, device.product_version_id, device.product_name].every(text)) return bad(); device.version_number = integer(raw, 'version_number', 1) }
  device.battery_percent_json = field(raw, 'battery_percent')
  if (device.battery_percent === null ? device.battery_percent_json !== 'null' : typeof device.battery_percent !== 'number' || !Number.isFinite(device.battery_percent) || !batteryInRange(device.battery_percent_json)) return bad()
  const policyRaw = field(raw, 'offline_policy'), policy = pick<EffectiveParameter>(device.offline_policy, ['key', 'value', 'unit', 'source_scope', 'source_id', 'source_revision', 'evaluated_at'])
  policy.value = integer(policyRaw, 'value', 1); policy.source_revision = integer(policyRaw, 'source_revision')
  const sourceMatches = policy.source_scope === 'GLOBAL' ? policy.source_id === null
    : policy.source_scope === 'DEVICE' ? policy.source_id === device.id
    : policy.source_scope === 'TENANT' ? policy.source_id === device.tenant_id
    : policy.source_scope === 'PLACE' && text(device.place_id) && text(policy.source_id)
  if (policy.key !== 'offline_threshold_minutes' || !text(policy.unit) || !sourceMatches || canonicalAlarmInstant(policy.evaluated_at) !== canonicalAlarmInstant(context.asOf)) return bad()
  if (Object.hasOwn(device, 'status_display')) device.status_display = readDeviceStatus(device.status_display)
  device.battery_policy = batteryPolicy(device.battery_policy, field(raw, 'battery_policy'), device.battery_percent_json)
  if (canonicalAlarmInstant(device.battery_policy.evaluated_at) !== canonicalAlarmInstant(context.asOf)) return bad()
  device.offline_policy = policy; return device
}
