import { ApiError, request } from './api'
import { definitionsResponse } from './battery-json'
import type { BatteryLowThreshold, MetricParameterDefinition } from './types'
import { useSession } from './store'
import { jsonFieldText, jsonArrayItems } from './json-document'
import { validThreshold } from './battery-threshold'

export interface BatteryConfiguration { parameter_key: 'battery_low_threshold_percent'; metric_key: 'battery_percent'; unit: '%'; configured: boolean; comparator: 'LT' | 'LTE' | null; threshold: number | null; threshold_json?: string; source_scope: 'NONE' | 'PRODUCT_VERSION' | 'DEVICE'; source_id: string | null; source_revision: number; evaluated_at: string }
export interface BatteryView { device_id: string; tenant_id: string; parameter_key: 'battery_low_threshold_percent'; metric_key: 'battery_percent'; unit: '%'; override: { has_value: boolean; comparator: 'LT' | 'LTE' | null; threshold: number | null; threshold_json?: string; revision: number }; effective: BatteryConfiguration; product_version: null | { product_version_id: string; version_number: number; battery_capability: 'SUPPORTED' | 'UNSUPPORTED' | 'PENDING' }; consumer: 'BATTERY_OBSERVATION_ONLY' }
export interface BatteryChange { id: string; action: 'SET' | 'CLEAR'; previous: { comparator: 'LT' | 'LTE'; threshold: number; threshold_json?: string } | null; new: { comparator: 'LT' | 'LTE'; threshold: number; threshold_json?: string } | null; revision_before: number; revision_after: number; actor_id: string; reason: string; created_at: string }
function fail(): never { throw new ApiError(200, 'BATTERY_RESPONSE_UNCONFIRMED', '电量策略响应未完整确认；不推断默认值、0或正常状态。') }
function viewResponse(raw: string, id: string, tenantId: string): BatteryView {
  const v = JSON.parse(raw)
  if (!v || Object.keys(v).sort().join(',') !== 'consumer,device_id,effective,metric_key,override,parameter_key,product_version,tenant_id,unit' || v.device_id !== id || typeof v.tenant_id !== 'string' || v.tenant_id !== tenantId || v.parameter_key !== 'battery_low_threshold_percent' || v.metric_key !== 'battery_percent' || v.unit !== '%' || v.consumer !== 'BATTERY_OBSERVATION_ONLY') return fail()
  const o = v.override
  if (!o || Object.keys(o).sort().join(',') !== 'comparator,has_value,revision,threshold' || typeof o.has_value !== 'boolean' || !Number.isSafeInteger(o.revision) || o.revision < 0 || (o.has_value ? !['LT', 'LTE'].includes(o.comparator) || typeof o.threshold !== 'number' || !Number.isFinite(o.threshold) || o.threshold < 0 || o.threshold > 100 : o.comparator !== null || o.threshold !== null)) return fail()
  const overrideRaw = jsonFieldText(raw, 'override')!
  if (o.has_value) {
    o.threshold_json = jsonFieldText(overrideRaw, 'threshold')
    if (!o.threshold_json || !validThreshold(o.threshold_json, o.threshold)) return fail()
  }
  const e = v.effective, effectiveRaw = jsonFieldText(raw, 'effective')!
  if (!e || Object.keys(e).sort().join(',') !== 'comparator,configured,evaluated_at,metric_key,parameter_key,source_id,source_revision,source_scope,threshold,unit' || e.parameter_key !== 'battery_low_threshold_percent' || e.metric_key !== 'battery_percent' || e.unit !== '%' || typeof e.configured !== 'boolean' || !(e.comparator === null || e.comparator === 'LT' || e.comparator === 'LTE') || !(e.threshold === null || typeof e.threshold === 'number' && Number.isFinite(e.threshold) && e.threshold >= 0 && e.threshold <= 100) || e.configured !== (e.threshold !== null) || (e.configured ? e.comparator === null : e.comparator !== null) || !['NONE', 'PRODUCT_VERSION', 'DEVICE'].includes(e.source_scope) || !(e.source_id === null || typeof e.source_id === 'string') || !Number.isSafeInteger(e.source_revision) || e.source_revision < 0 || typeof e.evaluated_at !== 'string' || !Number.isFinite(Date.parse(e.evaluated_at))) return fail()
  if (e.configured) { e.threshold_json = jsonFieldText(effectiveRaw, 'threshold'); if (!e.threshold_json || !validThreshold(e.threshold_json, e.threshold)) return fail() }
  if (e.source_scope === 'NONE' ? e.configured || e.source_id !== null || e.source_revision !== 0 : !e.configured || !e.source_id || e.source_revision < 1) return fail()
  if (o.has_value && (!e.configured || e.source_scope !== 'DEVICE' || e.source_id !== id)) return fail()
  if (v.product_version !== null && (!v.product_version || typeof v.product_version.product_version_id !== 'string' || !Number.isSafeInteger(v.product_version.version_number) || !['SUPPORTED', 'UNSUPPORTED', 'PENDING'].includes(v.product_version.battery_capability))) return fail()
  if (e.source_scope === 'DEVICE' && e.source_id !== id || e.source_scope === 'PRODUCT_VERSION' && (v.product_version === null || e.source_id !== v.product_version?.product_version_id || v.product_version?.battery_capability !== 'SUPPORTED') || e.source_scope !== 'DEVICE' && o.has_value) return fail()
  return { ...v, override: o, effective: e }
}
function change(raw: string): BatteryChange {
  const c = JSON.parse(raw)
  if (!c || typeof c.id !== 'string' || !['SET', 'CLEAR'].includes(c.action) || !Number.isSafeInteger(c.revision_before) || !Number.isSafeInteger(c.revision_after) || c.revision_after !== c.revision_before + 1 || typeof c.actor_id !== 'string' || typeof c.reason !== 'string' || typeof c.created_at !== 'string') return fail()
  for (const field of ['previous', 'new'] as const) {
    const item = c[field]
    if (item === null) continue
    const nested = jsonFieldText(raw, field)!
    const lexical = jsonFieldText(nested, 'threshold')
    if (!item || Object.keys(item).sort().join(',') !== 'comparator,threshold' || !['LT', 'LTE'].includes(item.comparator) || typeof item.threshold !== 'number' || !Number.isFinite(item.threshold) || !lexical || !validThreshold(lexical, item.threshold)) return fail()
    item.threshold_json = lexical
  }
  if ((c.action === 'SET') !== (c.new !== null)) return fail()
  return c
}
function historyResponse(raw: string, page: number) {
  const p = JSON.parse(raw)
  if (!Array.isArray(p.items) || p.page !== page || !Number.isSafeInteger(p.total) || p.total < 0 || !Number.isSafeInteger(p.size) || p.size < 1) return fail()
  const items = jsonArrayItems(jsonFieldText(raw, 'items')!).map(change)
  if (items.length !== Math.min(p.size, Math.max(0, p.total - (page - 1) * p.size))) return fail()
  return { ...p, items }
}
const path = (id: string) => `/management/devices/${encodeURIComponent(id)}/battery-low-threshold`
export const batteryApi = {
  definitions(signal?: AbortSignal) { return request<{items: MetricParameterDefinition[]}>('/management/metric-parameter-definitions', { signal, decode: definitionsResponse }) },
  view(id: string, tenantId: string, signal?: AbortSignal) { return request<BatteryView>(path(id), { tenantScoped: true, tenantId, signal, decode: raw => viewResponse(raw, id, tenantId) }) },
  history(id: string, tenantId: string, page: number, signal?: AbortSignal) { return request<{items: BatteryChange[]; total: number; page: number; size: number}>(`${path(id)}/history?page=${page}`, { tenantScoped: true, tenantId, signal, decode: raw => historyResponse(raw, page) }) },
  update(id: string, body: { expected_revision: number; battery_low_threshold: BatteryLowThreshold | null; battery_low_threshold_json: string | null; reason: string }, key: string, tenantId: string, signal?: AbortSignal) {
    const threshold = body.battery_low_threshold
    const thresholdJson = body.battery_low_threshold_json
    if (!Number.isSafeInteger(body.expected_revision) || body.expected_revision < 0 || !body.reason.trim() || !key || (threshold === null ? thresholdJson !== null : threshold.parameter_key !== 'battery_low_threshold_percent' || threshold.metric_key !== 'battery_percent' || threshold.unit !== '%' || !['LT', 'LTE'].includes(threshold.comparator) || typeof thresholdJson !== 'string' || !validThreshold(thresholdJson, threshold.threshold) || threshold.threshold_json !== undefined && threshold.threshold_json !== thresholdJson)) throw new ApiError(400, 'BATTERY_WRITE_INVALID', '请确认有效阈值、revision与原因')
    const { battery_low_threshold_json: thresholdJsonForWire, ...data } = body
    const thresholdValue = data.battery_low_threshold
    const wireThreshold = thresholdValue ? { parameter_key: thresholdValue.parameter_key, metric_key: thresholdValue.metric_key, unit: thresholdValue.unit, comparator: thresholdValue.comparator, threshold: thresholdValue.threshold } : null
    let text = JSON.stringify({ ...data, battery_low_threshold: wireThreshold })
    if (thresholdValue && thresholdJsonForWire) {
      const wireThresholdText = JSON.stringify(wireThreshold).replace(`"threshold":${JSON.stringify(thresholdValue.threshold)}`, `"threshold":${thresholdJsonForWire}`)
      text = text.replace(JSON.stringify(wireThreshold), wireThresholdText)
    }
    return request<{view: BatteryView; change: BatteryChange}>(path(id), { tenantScoped: true, tenantId, method: 'PUT', headers: {'Idempotency-Key': key}, body: text, signal, decode: raw => ({ view: viewResponse(jsonFieldText(raw, 'view')!, id, tenantId), change: change(jsonFieldText(raw, 'change')!) }) })
  },
}
