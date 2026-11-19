import type { ParserSample, ParserTestResult, ParserTestRow, ProductVersion } from './types'
import { jsonArrayItems, jsonFieldText, parseJsonDocument } from './json-document'
import { validThreshold } from './battery-threshold'
import { parseSampleDrafts } from './product-samples'

export function schemaDocument(text: string): Record<string, unknown> {
  const result = parseJsonDocument(text)
  if (!result || Array.isArray(result) || typeof result !== 'object') throw new Error('Schema 必须是声明式 JSON 对象')
  return result as Record<string, unknown>
}
export function sampleDocument(text: string): ParserSample[] {
  parseJsonDocument(text)
  const drafts = parseSampleDrafts(text)
  if (drafts === null) throw new Error('合成样例字段或类型无效，请按合同填写；payload 必须保留为字符串，数字必须是JSON数值')
  if (drafts.length === 0 || drafts.length > 16) throw new Error('合成样例数量必须为1至16条')
  const names = new Set<string>()
  for (const draft of drafts) { if (draft.name.trim() === '' || draft.name.length > 100) throw new Error('合成样例名称不能为空且最长100字符'); if (names.has(draft.name)) throw new Error(`合成样例名称重复：${draft.name}`); names.add(draft.name) }
  return JSON.parse(text) as ParserSample[]
}
function preserveResults(results: ParserTestRow[], raw: string | undefined) {
  const spans = raw ? jsonArrayItems(raw) : []
  return results.map((result, index) => ({ ...result, raw_event_json: spans[index] ? jsonFieldText(spans[index], 'raw_event') : undefined, readings_json: spans[index] ? jsonFieldText(spans[index], 'readings') : undefined }))
}
export function parseVersionsResponse(text: string): { items: ProductVersion[] } {
  const parsed: { items: ProductVersion[] } = JSON.parse(text)
  const spans = jsonArrayItems(jsonFieldText(text, 'items') ?? '[]')
  return { ...parsed, items: parsed.items.map((version, index) => {
    const versionRaw = spans[index]
    const threshold = version.battery_low_threshold
    if (threshold !== null && (!threshold || threshold.parameter_key !== 'battery_low_threshold_percent' || threshold.metric_key !== 'battery_percent' || threshold.unit !== '%' || !['LT', 'LTE'].includes(threshold.comparator) || typeof threshold.threshold !== 'number' || !Number.isFinite(threshold.threshold) || threshold.threshold < 0 || threshold.threshold > 100)) throw new Error('Version battery threshold is invalid')
    const thresholdRaw = jsonFieldText(versionRaw, 'battery_low_threshold')
    if (thresholdRaw === undefined) throw new Error('Missing required version battery threshold')
    let normalized = threshold
    if (threshold !== null) {
      const rawThreshold = jsonFieldText(thresholdRaw, 'threshold')
      if (!rawThreshold || !validThreshold(rawThreshold, threshold.threshold)) throw new Error('Version threshold lexical value is invalid')
      normalized = { ...threshold, threshold_json: rawThreshold }
    }
    const summary = version.parser_test_summary, summaryRaw = jsonFieldText(versionRaw, 'parser_test_summary')
    return { ...version, battery_low_threshold: normalized, schema_json: jsonFieldText(versionRaw, 'schema'), parser_test_summary: summary ? { ...summary, results: preserveResults(summary.results, summaryRaw === undefined ? undefined : jsonFieldText(summaryRaw, 'results')) } : null }
  }) }
}
export function parseVersionResponse(text: string): ProductVersion {
  const version = JSON.parse(text) as ProductVersion
  const raw = jsonFieldText(text, 'battery_low_threshold'), schemaJson = jsonFieldText(text, 'schema')
  if (raw === undefined) throw new Error('Missing required version battery threshold')
  if (version.battery_low_threshold === null) return { ...version, schema_json: schemaJson }
  const value = version.battery_low_threshold, thresholdRaw = jsonFieldText(raw, 'threshold')
  if (!value || value.parameter_key !== 'battery_low_threshold_percent' || value.metric_key !== 'battery_percent' || value.unit !== '%' || !['LT', 'LTE'].includes(value.comparator) || !thresholdRaw || !validThreshold(thresholdRaw, value.threshold)) throw new Error('Invalid version battery threshold')
  return { ...version, schema_json: schemaJson, battery_low_threshold: { ...value, threshold_json: thresholdRaw } }
}
export function parseTestResponse(text: string): ParserTestResult {
  const parsed: ParserTestResult = JSON.parse(text)
  return { ...parsed, results: preserveResults(parsed.results, jsonFieldText(text, 'results')) }
}
export const syntheticSamples: ParserSample[] = [
  { name: '合成火警与有效电量', case_kind: 'FIRE', payload: '{"event":"FIRE","battery":60}', expected_event: 'FIRE', expected_battery_percent: 60 },
  { name: '合成恢复与真实零电量', case_kind: 'RECOVERY', payload: '{"event":"RECOVERY","battery":0}', expected_event: 'RECOVERY', expected_battery_percent: 0 },
  { name: '合成未知事件与缺失电量', case_kind: 'UNKNOWN', payload: '{"event":"not-mapped"}', expected_event: 'UNKNOWN', expected_battery_percent: null },
  { name: '合成无效JSON', case_kind: 'MALFORMED', payload: '{invalid-json', expected_event: 'UNKNOWN', expected_battery_percent: null },
]
