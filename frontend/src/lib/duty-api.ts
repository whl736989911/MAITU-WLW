import { request, scopeQuery, ApiError } from './api'
import { useSession } from './store'
import type { MeasurementValueType } from './product-schema'
import { canonicalAlarmInstant } from './alarm-workbench'

function telemetryValueType(value: unknown): MeasurementValueType | undefined {
  if (value === undefined) return undefined
  if (value === 'NUMBER' || value === 'STRING' || value === 'BOOLEAN') return value
  throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测指标值类型未确认')
}

export type DutyPage<T> = { items: T[]; total: number; page: number; size: number }
export type FaultEvent = { id: string; tenant_id: string; device_id: string | null; kind: 'FAULT'; method: string | null; fault_code: string | null; fault_label: string; observed_at: string | null; source_message_at:string|null; source_time_field:string|null; source_fault_id:string|null; source_recovered:boolean|null; source_recovered_at:string|null; received_at:string|null; created_at:string; device_name:string|null; device_address:string|null; place_id:string|null; handling:{handled:boolean;note:string;revision:number;updated_by:string;updated_at:string}|null }
export function sourceFaultRecoveryText(event: Pick<FaultEvent, 'source_recovered' | 'source_recovered_at'>, formatTime: (value: string) => string = value => value): string {
  if (event.source_recovered_at) return `来源已修复：${formatTime(event.source_recovered_at)}`
  if (event.source_recovered === true) return '来源已修复，恢复时间未提供'
  return event.source_recovered === false ? '来源未修复' : '来源未提供恢复状态'
}
export type TelemetryReading = { metric: string; label?: string; value: string; unit: string | null; unit_status: 'DECLARED' | 'UNKNOWN'; value_type?: MeasurementValueType; observed_at: string | null; received_at: string | null; recorded_at: string }
export type TelemetrySample = { event_id:string;tenant_id:string;device_id:string|null;device_name:string|null;address:string|null;observed_at:string|null;received_at:string|null;recorded_at:string;readings:TelemetryReading[] }
const parsePage = <T>(value: unknown): DutyPage<T> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '值守查询响应格式无效')
  const row = value as Record<string, unknown>
  if (!Array.isArray(row.items) || !Number.isSafeInteger(row.total) || !Number.isSafeInteger(row.page) || !Number.isSafeInteger(row.size)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '值守查询分页响应未确认')
  return { items: row.items as T[], total: row.total as number, page: row.page as number, size: row.size as number }
}
export type FaultHandling = { tenant_id:string; event_id:string; handled:boolean; note:string; revision:number; updated_by:string; updated_at:string }
export function getFaultHandlingHistory(id:string,tenantId:string,signal?:AbortSignal) {
  const session=useSession.getState(),[tenants,places]=scopeArgs()
  const path=scopeQuery(`/${session.audience}/duty/faults/${encodeURIComponent(id)}/handling-history`,tenants,places)
  return request<FaultHandling[]>(path,{tenantScoped:true,tenantId,tenantOptional:session.audience==='management',signal,decode:text=>{const value=JSON.parse(text);if(!Array.isArray(value))throw new ApiError(200,'DUTY_RESPONSE_INVALID','故障处理记录格式无效');return value as FaultHandling[]}})
}
export function setFaultHandling(event:FaultEvent,body:{expected_revision:number;handled:boolean;note:string},key:string,signal?:AbortSignal) {
  const session=useSession.getState()
  if(!session.permissions.includes('faults:handle'))return Promise.reject(new ApiError(403,'FAULT_HANDLE_REQUIRED','当前账户无本地故障处置权限'))
  return request<FaultHandling>(`/${session.audience}/duty/faults/${encodeURIComponent(event.id)}/handling`,{tenantScoped:true,tenantId:event.tenant_id,method:'POST',headers:{'Idempotency-Key':key},body:JSON.stringify(body),signal,decode:text=>JSON.parse(text) as FaultHandling})
}
function scopeArgs() { const session = useSession.getState(); return [session.scopeTenantIds, session.scopePlaceIds] as const }
export function queryFaults(page: number, keyword: string, from: string, to: string, signal?: AbortSignal, options?: { size?: number; systemType?: string; activeOnly?: boolean }) {
  const session = useSession.getState(), query = new URLSearchParams({ page: String(page) })
  if (options?.size !== undefined) query.set('size', String(options.size))
  if (options?.systemType) query.set('system_type', options.systemType)
  if (options?.activeOnly) query.set('handling_state', 'ACTIVE')
  if (keyword) query.set('keyword', keyword)
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  const [tenants, places] = scopeArgs()
  const path = scopeQuery(`/${session.audience}/duty/faults?${query}`, tenants, places)
  return request<DutyPage<FaultEvent>>(path, { tenantScoped: true, tenantOptional: session.audience === 'management', signal, decode: text => parsePage<FaultEvent>(JSON.parse(text)) })
}
export type DutyDailyCount = { date:string; count:number }
export type DutyAnalytics = { as_of:string; from:string; to:string; timezone:string; days:number; alarms:number; faults:number; untimed_fault_events:number; reported_offline_events:number; alarms_by_day:DutyDailyCount[]; faults_by_day:DutyDailyCount[]; reported_offline_events_by_day:DutyDailyCount[]; fault_types:{code:string|null;label:string;count:number}[]; reported_offline_devices:{device_id:string;tenant_id:string;device_name:string|null;address:string|null;reported_offline_events:number}[]; offline_semantics:'ONLY_REPORTED_OFFLINE_EVENTS'; fault_semantics:'ONLY_LEGACY_INGEST_EVENTS_KIND_FAULT' }
export function queryDutyAnalytics(days:7|30,signal?:AbortSignal) {
  const session=useSession.getState(),[tenants,places]=scopeArgs(),query=new URLSearchParams({days:String(days)})
  const path=scopeQuery(`/${session.audience}/duty/analytics?${query}`,tenants,places)
  return request<DutyAnalytics>(path,{tenantScoped:true,tenantOptional:session.audience==='management',signal,decode:text=>JSON.parse(text) as DutyAnalytics})
}
export type TelemetryMetric = { key: string; label: string; value_type?: MeasurementValueType }
export function queryTelemetryMetrics(signal?: AbortSignal) {
  const session = useSession.getState(), [tenants, places] = scopeArgs()
  const path = scopeQuery(`/${session.audience}/duty/telemetry/metrics`, tenants, places)
  return request<TelemetryMetric[]>(path, { tenantScoped: true, tenantOptional: session.audience === 'management', signal, decode: text => {
    const value = JSON.parse(text)
    if (!Array.isArray(value) || value.some(item => !item || typeof item.key !== 'string' || !item.key.trim() || typeof item.label !== 'string' || !item.label.trim()) || new Set(value.map(item => item.key)).size !== value.length) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测指标目录响应未确认')
    return value.map(item => ({ ...item, value_type: telemetryValueType((item as Record<string, unknown>).value_type) })) as TelemetryMetric[]
  } })
}
function parseTelemetryPage(text: string): DutyPage<TelemetrySample> {
  const page = parsePage<TelemetrySample>(JSON.parse(text))
  for (const sample of page.items) {
    if (!sample || typeof sample !== 'object' || !Array.isArray(sample.readings)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测读数样本响应未确认')
    for (const reading of sample.readings) {
      const record = reading as unknown as Record<string, unknown>
      if (typeof record.metric !== 'string' || !record.metric || typeof record.value !== 'string' || (record.unit !== null && typeof record.unit !== 'string') || (record.unit_status !== 'DECLARED' && record.unit_status !== 'UNKNOWN') || (record.label !== undefined && typeof record.label !== 'string')) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测读数响应未确认')
      telemetryValueType(record.value_type)
    }
  }
  return page
}
function parseTelemetryAnalytics(text: string): TelemetryAnalytics {
  const value: unknown = JSON.parse(text)
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测趋势响应未确认')
  const record = value as Record<string, unknown>
  if (typeof record.from !== 'string' || typeof record.to !== 'string' || record.timezone !== 'UTC' || typeof record.days !== 'number' || typeof record.metric !== 'string' || typeof record.untimed_sample_count !== 'number' || !Array.isArray(record.series)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测趋势响应未确认')
  const series: TelemetryAnalytics['series'] = record.series.map(item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测趋势序列响应未确认')
    const entry = item as Record<string, unknown>
    if ((entry.unit !== null && typeof entry.unit !== 'string') || (entry.unit_status !== 'DECLARED' && entry.unit_status !== 'UNKNOWN') || (entry.source_version !== null && typeof entry.source_version !== 'string') || !Array.isArray(entry.points)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测趋势序列响应未确认')
    const points = entry.points.map(point => {
      if (!point || typeof point !== 'object' || Array.isArray(point)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测趋势点响应未确认')
      const pointRecord = point as Record<string, unknown>
      if (typeof pointRecord.date !== 'string' || typeof pointRecord.sample_count !== 'number' || (pointRecord.average !== null && typeof pointRecord.average !== 'string') || (pointRecord.minimum !== null && typeof pointRecord.minimum !== 'string') || (pointRecord.maximum !== null && typeof pointRecord.maximum !== 'string')) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '监测趋势点响应未确认')
      return { date: pointRecord.date, sample_count: pointRecord.sample_count, average: pointRecord.average as string | null, minimum: pointRecord.minimum as string | null, maximum: pointRecord.maximum as string | null }
    })
    return { unit: entry.unit as string | null, unit_status: entry.unit_status as 'DECLARED' | 'UNKNOWN', source_version: entry.source_version as string | null, value_type: telemetryValueType(entry.value_type), points }
  })
  return { from: record.from, to: record.to, timezone: 'UTC', days: record.days, metric: record.metric, untimed_sample_count: record.untimed_sample_count, series }
}
export function queryTelemetry(page: number, metric: string, from: string, to: string, signal?: AbortSignal) {
  const session = useSession.getState(), query = new URLSearchParams({ page: String(page) })
  if (metric) query.set('metric', metric)
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  const [tenants, places] = scopeArgs()
  const path = scopeQuery(`/${session.audience}/duty/parameters?${query}`, tenants, places)
  return request<DutyPage<TelemetrySample>>(path, { tenantScoped: true, tenantOptional: session.audience === 'management', signal, decode: parseTelemetryPage })
}
export function queryCurrentTelemetry(page: number, metric: string, signal?: AbortSignal) {
  const session = useSession.getState(), query = new URLSearchParams({ page: String(page) })
  if (metric) query.set('metric', metric)
  const [tenants, places] = scopeArgs(), path = scopeQuery(`/${session.audience}/duty/parameters/current?${query}`, tenants, places)
  return request<DutyPage<TelemetrySample>>(path, { tenantScoped: true, tenantOptional: session.audience === 'management', signal, decode: parseTelemetryPage })
}
function deviceTelemetryPage(text: string, deviceId: string, tenantId: string) {
  const result = parseTelemetryPage(text)
  if (result.page < 1 || result.size < 1 || result.total < 0 || result.items.length > result.size || result.items.length > result.total
    || result.items.some(sample => sample.device_id !== deviceId || sample.tenant_id !== tenantId)) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '设备监测归属未确认')
  for (const sample of result.items) {
    if ([sample.observed_at, sample.received_at].some(value => value !== null && (typeof value !== 'string' || !Number.isFinite(Date.parse(value))))
      || typeof sample.recorded_at !== 'string' || !Number.isFinite(Date.parse(sample.recorded_at))) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '设备监测来源时间未确认')
    for (const reading of sample.readings) {
      if ([reading.observed_at, reading.received_at].some(value => value !== null && (typeof value !== 'string' || !Number.isFinite(Date.parse(value))))
        || typeof reading.recorded_at !== 'string' || !Number.isFinite(Date.parse(reading.recorded_at))) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '设备监测来源时间未确认')
    }
  }
  return result
}
export function queryDeviceTelemetry(deviceId: string, tenantId: string, signal?: AbortSignal) {
  return request<DutyPage<TelemetrySample>>(`/${useSession.getState().audience}/devices/${encodeURIComponent(deviceId)}/telemetry`, {
    tenantScoped: true, tenantId, signal,
    decode: text => {
      const result = deviceTelemetryPage(text, deviceId, tenantId)
      if (result.page !== 1 || result.size !== 1 || result.total > 1 || result.items.length !== result.total) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '设备当前监测分页未确认')
      return result
    },
  })
}
export function queryDeviceTelemetryHistory(deviceId: string, tenantId: string, page: number, from: string, to: string, signal?: AbortSignal) {
  const query = new URLSearchParams({ page: String(page) })
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  return request<DutyPage<TelemetrySample>>(`/${useSession.getState().audience}/devices/${encodeURIComponent(deviceId)}/telemetry/history?${query}`, {
    tenantScoped: true, tenantId, signal,
    decode: text => {
      const result = deviceTelemetryPage(text, deviceId, tenantId)
      if (result.page !== page) throw new ApiError(200, 'DUTY_RESPONSE_INVALID', '设备监测历史分页未确认')
      return result
    },
  })
}
export type TelemetryAnalytics = { from: string; to: string; timezone: 'UTC'; days: number; metric: string; untimed_sample_count: number; series: { unit: string | null; unit_status: 'DECLARED' | 'UNKNOWN'; source_version: string | null; value_type?: MeasurementValueType; points: { date: string; sample_count: number; average: string | null; minimum: string | null; maximum: string | null }[] }[] }
export function queryTelemetryAnalytics(days: 7 | 30, metric: string, signal?: AbortSignal) {
  const session = useSession.getState(), [tenants, places] = scopeArgs(), query = new URLSearchParams({ days: String(days), metric })
  const path = scopeQuery(`/${session.audience}/duty/parameters/analytics?${query}`, tenants, places)
  return request<TelemetryAnalytics>(path, { tenantScoped: true, tenantOptional: session.audience === 'management', signal, decode: parseTelemetryAnalytics })
}
export type DeviceFaultSummary = { tenant_id:string; device_id:string; device_name:string|null; device_address:string|null; place_id:string|null; event_count:number; fault_types:{fault_code:string|null;fault_label:string;count:number}[]; latest_observed_at:string|null; latest_received_at:string|null }
export function queryDeviceFaults(page:number,keyword:string,from:string,to:string,signal?:AbortSignal) {
  const session=useSession.getState(),[tenants,places]=scopeArgs(),query=new URLSearchParams({page:String(page)})
  if(keyword)query.set('keyword',keyword)
  if(from)query.set('from',from)
  if(to)query.set('to',to)
  const path=scopeQuery(`/${session.audience}/duty/device-faults?${query}`,tenants,places)
  return request<DutyPage<DeviceFaultSummary>>(path,{tenantScoped:true,tenantOptional:session.audience==='management',signal,decode:text=>parsePage<DeviceFaultSummary>(JSON.parse(text))})
}
export function queryDeviceFaultEvents(deviceId:string,page:number,signal?:AbortSignal) {
  const session=useSession.getState(),[tenants,places]=scopeArgs(),query=new URLSearchParams({page:String(page)})
  const path=scopeQuery(`/${session.audience}/duty/device-faults/${encodeURIComponent(deviceId)}/events?${query}`,tenants,places)
  return request<DutyPage<FaultEvent>>(path,{tenantScoped:true,tenantOptional:session.audience==='management',signal,decode:text=>parsePage<FaultEvent>(JSON.parse(text))})
}

export type FaultHandlingFilter = '' | 'UNRECORDED' | 'UNHANDLED' | 'HANDLED'
export function queryDeviceDetailFaultEvents(deviceId: string, tenantId: string, page: number, from: string, to: string, handlingState: FaultHandlingFilter, signal?: AbortSignal) {
  const query = new URLSearchParams({ page: String(page) })
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  if (handlingState) query.set('handling_state', handlingState)
  const lower = from ? canonicalAlarmInstant(from, false) : null, upper = to ? canonicalAlarmInstant(to, false) : null
  return request<DutyPage<FaultEvent>>(`/${useSession.getState().audience}/duty/device-faults/${encodeURIComponent(deviceId)}/events?${query}`, {
    tenantScoped: true, tenantId, signal,
    decode: text => {
      const result = parsePage<FaultEvent>(JSON.parse(text))
      const time = (value: unknown) => value === null || typeof value === 'string' && Number.isFinite(Date.parse(value))
      const nullableText = (value: unknown) => value === null || typeof value === 'string'
      if (result.page !== page || result.page < 1 || result.size < 1 || result.total < 0 || result.items.length > result.size || result.items.length > result.total
        || result.items.some(event => !event || event.device_id !== deviceId || event.tenant_id !== tenantId || event.kind !== 'FAULT'
          || typeof event.id !== 'string' || !event.id
          || typeof event.fault_label !== 'string' || !time(event.observed_at) || !time(event.received_at)
          || (lower !== null || upper !== null) && (event.observed_at === null || lower !== null && canonicalAlarmInstant(event.observed_at, false) < lower || upper !== null && canonicalAlarmInstant(event.observed_at, false) >= upper)
          || !time(event.source_recovered_at) || typeof event.created_at !== 'string' || !time(event.created_at)
          || !nullableText(event.method) || !nullableText(event.fault_code) || !nullableText(event.source_fault_id)
          || event.source_recovered !== null && typeof event.source_recovered !== 'boolean'
          || event.handling !== null && (!event.handling || typeof event.handling.handled !== 'boolean' || typeof event.handling.note !== 'string')
          || handlingState === 'UNRECORDED' && event.handling !== null
          || handlingState === 'UNHANDLED' && event.handling?.handled !== false
          || handlingState === 'HANDLED' && event.handling?.handled !== true)
        || new Set(result.items.map(event => event.id)).size !== result.items.length) {
        throw new ApiError(200, 'DEVICE_FAULT_RESPONSE_INVALID', '本设备故障记录范围或状态未确认')
      }
      return result
    },
  })
}
