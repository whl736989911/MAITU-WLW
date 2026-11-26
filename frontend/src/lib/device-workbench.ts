import { request, ApiError, scopeQuery } from './api'
import { useSession } from './store'
import { parseJsonDocument, jsonFieldText, jsonArrayItems } from './json-document'
import { integerToken } from './parameter-json'
import { canonicalAlarmInstant } from './alarm-workbench'
import { readSafeDeviceRow, type SafeDeviceRow } from './device-row'
import { applyDeviceSort, type DeviceSort } from './device-sort'
export const deviceFilterEnums = { connectivity: ['ONLINE', 'OFFLINE', 'UNACTIVATED', 'UNKNOWN'], physical_state: ['FIRE', 'NORMAL', 'UNKNOWN'] } as const
export interface DeviceFilters { keyword: string; connectivity: string | null; physical_state: string | null; system_type: string | null; device_category: string | null }
export type DeviceDraft = Record<keyof DeviceFilters, string>
export const emptyDeviceFilters: DeviceFilters = { keyword: '', connectivity: null, physical_state: null, system_type: null, device_category: null }
export const emptyDeviceDraft: DeviceDraft = { keyword: '', connectivity: '', physical_state: '', system_type: '', device_category: '' }
export const deviceMetricKeys = ['device_total', 'online', 'offline', 'unactivated', 'unknown_connectivity', 'fire', 'normal', 'unknown', 'battery_low', 'battery_normal', 'battery_not_configured', 'battery_no_data'] as const
export type DeviceMetrics = Record<typeof deviceMetricKeys[number], number>
export const deviceMetricDefinitions = { scope: 'FILTERED_ALL_PAGES', connectivity: 'REPORT_INTERVAL_EVIDENCE_AT_AS_OF', physical_state: 'CURRENT_DEVICE_PHYSICAL_STATE', binding: 'DEVICE_SAVED_METADATA_NOT_LATEST', consumer: 'CONNECTIVITY_AND_OFFLINE_EVENTS', battery: 'EFFECTIVE_BATTERY_LOW_THRESHOLD_AT_AS_OF_STORED_PERCENT' } as const
export interface DeviceWorkbench { items: SafeDeviceRow[]; total: number; page: number; size: number; as_of: string; timezone: 'Asia/Shanghai'; filters: DeviceFilters; metrics: DeviceMetrics; metric_definitions: typeof deviceMetricDefinitions }
const fail = (): never => { throw new ApiError(200, 'DEVICE_WORKBENCH_UNCONFIRMED', '设备工作台响应未确认；不保留旧卡片/行、不推定正常或0') }
function pick<T>(v: unknown, keys: readonly string[]): T { if (!v || typeof v !== 'object' || Array.isArray(v)) return fail(); return Object.fromEntries(keys.filter(k => Object.hasOwn(v, k)).map(k => [k, (v as Record<string, unknown>)[k]])) as T }
function field(raw: string, key: string) { const v = jsonFieldText(raw, key); if (v === undefined) return fail(); return v }
function integer(raw: string, key: string, min = 0) { const v = integerToken(field(raw, key)); if (v < min) return fail(); return v }
function normalized(filters: DeviceFilters): DeviceFilters {
  if (!filters || typeof filters.keyword !== 'string' || filters.keyword.includes('\0')) throw new Error('设备关键字未确认，不能发送NUL或缺失字段')
  for (const char of filters.keyword) { const code = char.codePointAt(0)!; if (code >= 0xd800 && code <= 0xdfff) throw new Error('设备关键字文本编码未确认') }
  const result = { ...emptyDeviceFilters, keyword: filters.keyword.trim() }
  for (const key of ['connectivity', 'physical_state'] as const) { const value = filters[key]; if (value !== null && !(deviceFilterEnums[key] as readonly string[]).includes(value)) throw new Error('设备筛选枚举未确认，不猜测全部'); result[key] = value }
  for (const key of ['system_type', 'device_category'] as const) { const value = filters[key]; if (value !== null && (typeof value !== 'string' || !value.trim() || value.trim().length > 80 || /[\u0000-\u001f\u007f]/u.test(value))) throw new Error('系统/设备类别筛选值无效'); result[key] = value?.trim() ?? null }
  return result
}
export function compileDeviceDraft(draft: DeviceDraft): DeviceFilters { return normalized({ keyword: draft.keyword, connectivity: draft.connectivity || null, physical_state: draft.physical_state || null, system_type: draft.system_type || null, device_category: draft.device_category || null }) }
export function readDeviceWorkbench(raw: string, applied: DeviceFilters, page: number, size: number | undefined, tenantId: string | null): DeviceWorkbench {
  const root = parseJsonDocument(raw), data = pick<DeviceWorkbench>(root, ['items', 'total', 'page', 'size', 'as_of', 'timezone', 'filters', 'metrics', 'metric_definitions'])
  if (typeof data.as_of !== 'string' || !data.as_of.endsWith('Z') || data.timezone !== 'Asia/Shanghai') return fail(); canonicalAlarmInstant(data.as_of)
  data.total = integer(raw, 'total'); data.page = integer(raw, 'page', 1); data.size = integer(raw, 'size', 1)
  if (data.page !== page || (size !== undefined && data.size !== size)) return fail()
  data.filters = pick<DeviceFilters>(data.filters, Object.keys(emptyDeviceFilters)); const server = normalized(data.filters), expected = normalized(applied)
  if (data.filters.keyword !== server.keyword || Object.keys(emptyDeviceFilters).some(k => server[k as keyof DeviceFilters] !== expected[k as keyof DeviceFilters])) return fail()
  data.metric_definitions = pick(data.metric_definitions, Object.keys(deviceMetricDefinitions)); for (const [key, value] of Object.entries(deviceMetricDefinitions)) if (data.metric_definitions[key as keyof typeof deviceMetricDefinitions] !== value) return fail()
  const metricRaw = field(raw, 'metrics'); data.metrics = Object.fromEntries(deviceMetricKeys.map(key => [key, integer(metricRaw, key)])) as DeviceMetrics
  const m = data.metrics, axes = [['online', 'offline', 'unactivated', 'unknown_connectivity'], ['fire', 'normal', 'unknown'], ['battery_low', 'battery_normal', 'battery_not_configured', 'battery_no_data']] as const
  if (m.device_total !== data.total || deviceMetricKeys.some(k => m[k] > data.total) || axes.some(axis => axis.reduce((sum, k) => sum + m[k], 0) !== data.total)) return fail()
  const forced = [expected.connectivity?.toLowerCase() ?? null, expected.physical_state?.toLowerCase() ?? null]
  axes.slice(0, 2).forEach((axis, i) => {
    const selected = i === 0 && forced[i] === 'unknown' ? 'unknown_connectivity' : forced[i]
    if (selected != null && axis.some(k => m[k] !== (k === selected ? data.total : 0))) return fail()
  })
  data.items = jsonArrayItems(field(raw, 'items')).map(row => readSafeDeviceRow(row, { asOf: data.as_of, tenantId }))
  const offset = (data.page - 1) * data.size
  if (!Number.isSafeInteger(offset) || offset < 0 || data.items.length !== Math.min(data.size, Math.max(0, data.total - offset)) || new Set(data.items.map(d => d.id)).size !== data.items.length) return fail()
  const observed = Object.fromEntries(deviceMetricKeys.map(k => [k, 0])) as DeviceMetrics
  for (const row of data.items) {
    if (Object.entries(expected).some(([key, value]) => key !== 'keyword' && value !== null && row[key as keyof SafeDeviceRow] !== value)) return fail()
    observed.device_total++
    observed[row.connectivity === 'UNKNOWN' ? 'unknown_connectivity' : row.connectivity!.toLowerCase() as 'online' | 'offline' | 'unactivated']++
    observed[row.physical_state!.toLowerCase() as 'fire' | 'normal' | 'unknown']++
    observed[`battery_${row.battery_policy.status.toLowerCase()}` as keyof DeviceMetrics]++
  }
  if (deviceMetricKeys.some(k => observed[k] > m[k] || (data.items.length === data.total && observed[k] !== m[k]))) return fail()
  return data
}
export function getDeviceWorkbench(applied: DeviceFilters, page: number, size?: number, signal?: AbortSignal, sort?: DeviceSort): Promise<DeviceWorkbench> {
  try {
    const filters = normalized(applied); if (!Number.isSafeInteger(page) || page < 1 || (size !== undefined && (!Number.isSafeInteger(size) || size < 1))) throw new Error('设备分页未确认')
    const session = useSession.getState(), tenantId = session.audience === 'management' ? null : session.user?.tenant_id ?? null, query = new URLSearchParams({ page: String(page) })
    if (size !== undefined) query.set('size', String(size)); for (const key of Object.keys(emptyDeviceFilters) as (keyof DeviceFilters)[]) { const value = filters[key]; if (value !== null && value !== '') query.set(key, String(value)) }
    applyDeviceSort(query, sort)
    const path = scopeQuery(`/${session.audience}/device-workbench?${query}`, session.scopeTenantIds, session.scopePlaceIds)
    return request<DeviceWorkbench>(path, { tenantScoped: true, tenantOptional: session.audience === 'management', signal, decode: raw => readDeviceWorkbench(raw, filters, page, size, tenantId) }).catch(error => { if (error instanceof ApiError && error.code === 'INVALID_RESPONSE') return fail(); throw error })
  } catch (error) { return Promise.reject(error) }
}
