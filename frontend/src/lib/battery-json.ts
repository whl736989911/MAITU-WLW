import type { BatteryPolicy, MetricParameterDefinition } from './types'
import { jsonFieldText } from './json-document'

const bad = (): never => { throw new Error('电量策略响应不完整或不一致') }
const obj = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function keys(value: Record<string, unknown>, expected: string[]) { return Object.keys(value).sort().join(',') === expected.sort().join(',') }
const numberLexeme = /^(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/
function decimalParts(value: string) {
  const match = /^(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value)
  if (!match) return bad()
  const exp = Number(match[3] ?? 0), fraction = match[2]?.length ?? 0
  if (!Number.isSafeInteger(exp) || Math.abs(exp) > 100 || Math.max(0, fraction - exp) > 36) return bad()
  return { coefficient: BigInt(match[1] + (match[2] ?? '')), exponent: exp - fraction }
}
function compareDecimal(left: string, right: string) {
  const a = decimalParts(left), b = decimalParts(right), exponent = Math.min(a.exponent, b.exponent)
  const x = a.coefficient * 10n ** BigInt(a.exponent - exponent), y = b.coefficient * 10n ** BigInt(b.exponent - exponent)
  return x < y ? -1 : x > y ? 1 : 0
}
function validThresholdRaw(raw: string, value: number) { return numberLexeme.test(raw) && Number.isFinite(value) && compareDecimal(raw, '0') >= 0 && compareDecimal(raw, '100') <= 0 && Number(raw) === value }
export function policy(value: unknown, raw?: string, batteryRaw?: string): BatteryPolicy {
  if (!obj(value) || !keys(value, ['parameter_key', 'metric_key', 'unit', 'status', 'comparator', 'threshold', 'source_scope', 'source_id', 'source_revision', 'evaluated_at']) || value.parameter_key !== 'battery_low_threshold_percent' || value.metric_key !== 'battery_percent' || value.unit !== '%' || !['NOT_CONFIGURED', 'NO_DATA', 'LOW', 'NORMAL'].includes(String(value.status)) || !['NONE', 'PRODUCT_VERSION', 'DEVICE'].includes(String(value.source_scope)) || !(value.comparator === null || value.comparator === 'LT' || value.comparator === 'LTE') || !(value.threshold === null || typeof value.threshold === 'number' && Number.isFinite(value.threshold)) || !(value.source_id === null || typeof value.source_id === 'string') || !Number.isSafeInteger(value.source_revision) || (value.source_revision as number) < 0 || typeof value.evaluated_at !== 'string' || !Number.isFinite(Date.parse(value.evaluated_at))) return bad()
  const threshold_json = value.threshold === null ? undefined : raw ? jsonFieldText(raw, 'threshold') : String(value.threshold)
  if (value.threshold === null ? value.comparator !== null : value.comparator === null || !threshold_json || !validThresholdRaw(threshold_json, value.threshold as number)) return bad()
  const configured = value.threshold !== null
  if ((value.status === 'NOT_CONFIGURED') !== !configured || (value.status === 'NO_DATA' && (!configured || batteryRaw !== undefined && batteryRaw !== 'null')) || (value.status === 'LOW' || value.status === 'NORMAL') && (!configured || batteryRaw !== undefined && batteryRaw === 'null')) return bad()
  if (configured && batteryRaw !== undefined && batteryRaw !== 'null') {
    const compared = compareDecimal(batteryRaw, threshold_json!)
    const low = value.comparator === 'LT' ? compared < 0 : compared <= 0
    if ((value.status === 'LOW') !== low) return bad()
  }
  if (value.source_scope === 'NONE' ? configured || value.source_id !== null || value.source_revision !== 0 : !configured || !value.source_id || Number(value.source_revision) < 1) return bad()
  return { ...value, threshold_json } as BatteryPolicy
}
export function definitionsResponse(text: string): { items: MetricParameterDefinition[] } {
  const data = JSON.parse(text)
  if (!obj(data) || !Array.isArray(data.items) || data.items.length !== 1) return bad()
  const d = data.items[0]
  if (!obj(d) || !keys(d, ['key', 'metric_key', 'name', 'unit', 'min', 'max', 'scale', 'comparators', 'allowed_scopes', 'consumer', 'effect']) || d.key !== 'battery_low_threshold_percent' || d.metric_key !== 'battery_percent' || typeof d.name !== 'string' || d.unit !== '%' || d.min !== 0 || d.max !== 100 || d.scale !== 36 || JSON.stringify(d.comparators) !== '["LT","LTE"]' || JSON.stringify(d.allowed_scopes) !== '["PRODUCT_VERSION","DEVICE"]' || d.consumer !== 'BATTERY_OBSERVATION_ONLY' || d.effect !== 'QUERY_ONLY') return bad()
  return { items: [{ key: 'battery_low_threshold_percent', metric_key: 'battery_percent', name: d.name, unit: '%', min: 0, max: 100, scale: 36, comparators: ['LT', 'LTE'], allowed_scopes: ['PRODUCT_VERSION', 'DEVICE'], consumer: 'BATTERY_OBSERVATION_ONLY', effect: 'QUERY_ONLY' }] }
}
export function batteryRawForRow(raw: string, row: Record<string, unknown>): BatteryPolicy {
  const policyRaw = jsonFieldText(raw, 'battery_policy')!, batteryRaw = jsonFieldText(raw, 'battery_percent')!
  return policy(row.battery_policy, policyRaw, batteryRaw)
}
