import { request as baseRequest, ApiError } from './api'
import { useSession } from './store'
import { readSafeDeviceRow } from './device-row'
import { parseJsonDocument, jsonFieldText, jsonArrayItems } from './json-document'
import { integerToken } from './parameter-json'
import { alarmWorkbenchResponse } from './alarm-json'
import { canonicalAlarmInstant, validateAlarmWorkbench } from './alarm-workbench'
import type { Device, EffectiveParameter, AlarmWorkbench, AlarmWorkbenchFilters, Paginated } from './types'
export interface DeviceDetail { as_of: string; timezone: 'Asia/Shanghai'; consumer: 'CONNECTIVITY_AND_OFFLINE_EVENTS'; device: Device & { revision: number; offline_policy: EffectiveParameter } }
export interface DeviceAlarms extends AlarmWorkbench { device_id: string }
export interface DeviceEventRecord { id: string; tenant_id: string; device_id: string; kind: 'HEARTBEAT' | 'ALARM' | 'FAULT' | 'OFFLINE' | 'PARAM' | 'OTHER'; method: string | null; status: string | null; event_code: string | null; event_label: string | null; observed_at: string | null; received_at: string | null; created_at: string }
export interface DeviceEventFilters { kind: DeviceEventRecord['kind'] | ''; from: string; to: string }
function readDeviceEvents(raw: string, deviceId: string, tenantId: string, page: number, size: number | undefined, filters: DeviceEventFilters): Paginated<DeviceEventRecord> {
  const parsed = parseJsonDocument(raw)
  if (!object(parsed) || !Array.isArray(parsed.items) || !Number.isSafeInteger(parsed.total) || !Number.isSafeInteger(parsed.page) || !Number.isSafeInteger(parsed.size)) return bad()
  const total = parsed.total as number, currentPage = parsed.page as number, pageSize = parsed.size as number
  if (currentPage !== page || pageSize < 1 || (size !== undefined && pageSize !== size) || parsed.items.length > pageSize || parsed.items.length > total) return bad()
  const rows = jsonArrayItems(jsonFieldText(raw, 'items')!)
  const items = parsed.items.map((item: unknown, index: number) => {
    const row = pick<DeviceEventRecord>(item, ['id','tenant_id','device_id','kind','method','status','event_code','event_label','observed_at','received_at','created_at'])
    if (!row.id || row.id.includes('\0') || row.device_id !== deviceId || row.tenant_id !== tenantId || !['HEARTBEAT','ALARM','FAULT','OFFLINE','PARAM','OTHER'].includes(row.kind) || !(row.method === null || typeof row.method === 'string') || !(row.status === null || typeof row.status === 'string') || !(row.event_code === null || typeof row.event_code === 'string') || !(row.event_label === null || typeof row.event_label === 'string') || !(row.observed_at === null || typeof row.observed_at === 'string') || !(row.received_at === null || typeof row.received_at === 'string') || typeof row.created_at !== 'string') return bad()
    if (filters.kind && row.kind !== filters.kind) return bad()
    if (row.observed_at !== null) canonicalAlarmInstant(row.observed_at)
    canonicalAlarmInstant(row.created_at)
    if (jsonFieldText(rows[index], 'params_json') !== undefined) return bad()
    return row
  })
  return { items, total: parsed.total as number, page: parsed.page as number, size: parsed.size as number }
}
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
function bad(): never { throw new ApiError(200, 'DEVICE_DETAIL_UNCONFIRMED', '设备详情/关联报警响应未确认；不替代为旧行、正常或0') }
function pick<T>(v: unknown, keys: string[]): T { if (!object(v)) return bad(); return Object.fromEntries(keys.filter(k => Object.hasOwn(v, k)).map(k => [k, v[k]])) as T }
function rawField(raw: string, key: string) { const field = jsonFieldText(raw, key); if (field === undefined) return bad(); return field }
function integer(raw: string, key: string, min = 0) { const value = integerToken(rawField(raw, key)); if (value < min) return bad(); return value }
export function readDeviceDetail(raw: string, deviceId: string, tenantId: string | null): DeviceDetail {
  const parsed = parseJsonDocument(raw), result = pick<DeviceDetail>(parsed, ['as_of', 'timezone', 'consumer', 'device'])
  if (result.timezone !== 'Asia/Shanghai' || result.consumer !== 'CONNECTIVITY_AND_OFFLINE_EVENTS') return bad()
  result.device = readSafeDeviceRow(rawField(raw, 'device'), { asOf: result.as_of, tenantId, expectedId: deviceId }); return result
}
export function readDeviceAlarms(raw: string, deviceId: string, tenantId: string, applied: AlarmWorkbenchFilters, page: number, size?: number): DeviceAlarms {
  const parsed = parseJsonDocument(raw); if (!object(parsed) || parsed.device_id !== deviceId) return bad()
  const data = alarmWorkbenchResponse(raw) as DeviceAlarms; data.device_id = deviceId
  data.total = integer(raw, 'total'); data.page = integer(raw, 'page', 1); data.size = integer(raw, 'size', 1)
  const metricsRaw = rawField(raw, 'metrics'); for (const key of ['alarm_total', 'unhandled', 'handled', 'unrecovered', 'recovered', 'true_fire'] as const) data.metrics[key] = integer(metricsRaw, key)
  const rows = jsonArrayItems(rawField(raw, 'items')); data.items.forEach((row, i) => { if (row.device_id !== deviceId || row.tenant_id !== tenantId) return bad(); row.revision = integer(rows[i], 'revision') })
  canonicalAlarmInstant(data.as_of); if (validateAlarmWorkbench(data, applied, page, size)) return bad(); return data
}
function request<T>(...args: Parameters<typeof baseRequest<T>>): Promise<T> { return baseRequest<T>(...args).catch(error => { if (error instanceof ApiError && error.code === 'INVALID_RESPONSE') return bad(); throw error }) }
const prefix = () => `/${useSession.getState().audience}`
export const deviceDetailApi = {
  detail: (id: string, signal?: AbortSignal, tenantId?: string) => {
    if (!tenantId) return Promise.reject(new ApiError(400, 'DEVICE_DETAIL_UNCONFIRMED', '设备所属单位未确认'))
    return request<DeviceDetail>(`${prefix()}/devices/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: raw => readDeviceDetail(raw, id, tenantId) })
  },
  events: (id: string, filters: DeviceEventFilters, page: number, size?: number, signal?: AbortSignal, tenantId?: string) => {
    if (!Number.isSafeInteger(page) || page < 1 || (size !== undefined && (!Number.isSafeInteger(size) || size < 1))) return Promise.reject(new ApiError(400, 'DEVICE_EVENT_PAGE_INVALID', '设备事件分页未确认'))
    if (!tenantId) return Promise.reject(new ApiError(400, 'DEVICE_DETAIL_UNCONFIRMED', '设备所属单位未确认'))
    const query = new URLSearchParams({ page: String(page) }); if (size !== undefined) query.set('size', String(size))
    for (const key of ['kind', 'from', 'to'] as const) if (filters[key]) query.set(key, filters[key])
    return request<Paginated<DeviceEventRecord>>(`${prefix()}/devices/${encodeURIComponent(id)}/events?${query}`, { tenantScoped: true, tenantId, signal, decode: raw => readDeviceEvents(raw, id, tenantId, page, size, filters) })
  },
  alarms: (id: string, applied: AlarmWorkbenchFilters, page: number, size?: number, signal?: AbortSignal, tenantId?: string) => {
    if (!Number.isSafeInteger(page) || page < 1 || (size !== undefined && (!Number.isSafeInteger(size) || size < 1))) return Promise.reject(new ApiError(400, 'DEVICE_ALARM_PAGE_INVALID', '关联报警分页未确认'))
    if (!tenantId) return Promise.reject(new ApiError(400, 'DEVICE_DETAIL_UNCONFIRMED', '设备所属单位未确认'))
    const query = new URLSearchParams({ page: String(page) }); if (size !== undefined) query.set('size', String(size))
    for (const key of ['keyword', 'physical_state', 'response_state', 'conclusion', 'occurred_from', 'occurred_to'] as const) { const value = applied[key]; if (value !== null && value !== '') query.set(key, value) }
    return request<DeviceAlarms>(`${prefix()}/devices/${encodeURIComponent(id)}/alarms?${query}`, { tenantScoped: true, tenantId, signal, decode: raw => readDeviceAlarms(raw, id, tenantId, applied, page, size) })
  },
}
