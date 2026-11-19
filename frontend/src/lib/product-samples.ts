// Synthetic parser samples. Wire numbers are kept as lexical strings so the editor never converts a
// declared decimal through a host Number. Everything here is explicitly synthetic test evidence.
import { jsonArrayItems, jsonFieldText, parseJsonDocument } from './json-document'
import type { Capabilities, ParserSample, ProductEventType } from './types'
import {
  capabilityLabels, compareDecimal, isNumberLexeme, mappingJsonToken, pointerSegments,
  type MeasurementDraft, type MeasurementValueType, type SchemaDraft,
} from './product-schema'

export type SampleCaseKind = ProductEventType | 'UNKNOWN' | 'MALFORMED'
export type SampleEvent = ProductEventType | 'UNKNOWN'
export type SampleMeasurementState = 'VALID' | 'MISSING' | 'INVALID'
export interface SampleMeasurementDraft { key: string; state: SampleMeasurementState; valueText: string; valueKind: MeasurementValueType }
export interface SampleDraft { name: string; caseKind: SampleCaseKind; payload: string; expectedEvent: SampleEvent; expectedBattery: string; expectedMeasurements: SampleMeasurementDraft[] }

const caseKinds: Record<SampleCaseKind, true> = { FIRE: true, RECOVERY: true, FAULT: true, HEARTBEAT: true, PARAM: true, OTHER: true, UNKNOWN: true, MALFORMED: true }
const sampleStates: Record<SampleMeasurementState, true> = { VALID: true, MISSING: true, INVALID: true }
const measurementCapability: Record<'FIRE' | 'RECOVERY' | 'FAULT' | 'HEARTBEAT', 'fire' | 'recovery' | 'fault' | 'heartbeat'> = { FIRE: 'fire', RECOVERY: 'recovery', FAULT: 'fault', HEARTBEAT: 'heartbeat' }
export const sampleStateLabels: Record<SampleMeasurementState, string> = { VALID: '有效值', MISSING: '缺失', INVALID: '无效值' }
export const sampleCaseLabels: Record<SampleCaseKind, string> = { FIRE: '火警', RECOVERY: '恢复', FAULT: '故障', HEARTBEAT: '心跳', PARAM: '读数', OTHER: '其它', UNKNOWN: '未知事件', MALFORMED: '无法解析' }

function isCaseKind(value: unknown): value is SampleCaseKind { return typeof value === 'string' && Object.hasOwn(caseKinds, value) }
function isSampleEvent(value: unknown): value is SampleEvent { return (typeof value === 'string' && Object.hasOwn(caseKinds, value) && value !== 'MALFORMED') }
function isSampleState(value: unknown): value is SampleMeasurementState { return typeof value === 'string' && Object.hasOwn(sampleStates, value) }

/** Exact raw token to display text plus its JSON kind; used to round-trip advanced samples losslessly. */
function tokenText(raw: string): { text: string; kind: MeasurementValueType } {
  if (raw.startsWith('"')) { const value: unknown = JSON.parse(raw); if (typeof value !== 'string') throw new Error('样例期望值类型无效'); return { text: value, kind: 'STRING' } }
  if (raw === 'true' || raw === 'false') return { text: raw, kind: 'BOOLEAN' }
  if (isNumberLexeme(raw)) return { text: raw, kind: 'NUMBER' }
  throw new Error('样例期望值只允许JSON字符串、布尔或普通十进制数值')
}

/** Null when the text is not an array of samples the typed editor can express. */
export function parseSampleDrafts(text: string): SampleDraft[] | null {
  try {
    const value = parseJsonDocument(text)
    if (!Array.isArray(value)) return null
    const itemTexts = jsonArrayItems(text)
    return value.map((item, index): SampleDraft => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error('样例必须是对象')
      const record = item as Record<string, unknown>
      const itemText = itemTexts[index]
      const name = record.name, caseKind = record.case_kind, payload = record.payload, expectedEvent = record.expected_event
      if (typeof name !== 'string') throw new Error('样例名称必须是字符串')
      if (!isCaseKind(caseKind)) throw new Error('样例case_kind无效')
      if (typeof payload !== 'string') throw new Error('样例payload必须是字符串')
      if (!isSampleEvent(expectedEvent)) throw new Error('样例expected_event无效')
      const batteryRaw = jsonFieldText(itemText, 'expected_battery_percent')
      if (record.expected_battery_percent !== null && (batteryRaw === undefined || !isNumberLexeme(batteryRaw))) throw new Error('样例期望电量必须是数值或null')
      const measurementRaw = jsonFieldText(itemText, 'expected_measurements')
      const expectedMeasurements: SampleMeasurementDraft[] = []
      if (measurementRaw !== undefined && measurementRaw !== 'null') {
        const measurements = jsonArrayItems(measurementRaw)
        const values: unknown = JSON.parse(measurementRaw)
        if (!Array.isArray(values)) throw new Error('expected_measurements必须是数组')
        values.forEach((entry, measurementIndex) => {
          if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new Error('样例读数期望必须是对象')
          const measurement = entry as Record<string, unknown>
          const key = measurement.key, state = measurement.state
          if (typeof key !== 'string' || !/^[a-z][a-z0-9_]{0,63}$/.test(key)) throw new Error('样例读数key无效')
          if (!isSampleState(state)) throw new Error('样例读数state无效')
          const rawValue = jsonFieldText(measurements[measurementIndex], 'value')
          const decoded = rawValue === undefined ? null : tokenText(rawValue)
          if (state === 'VALID' && decoded === null) throw new Error(`样例读数 ${key} 的VALID状态必须提供value`)
          if (state !== 'VALID' && decoded !== null) throw new Error(`样例读数 ${key} 的${state}状态不得提供value`)
          expectedMeasurements.push({ key, state, valueText: decoded?.text ?? '', valueKind: decoded?.kind ?? 'NUMBER' })
        })
      }
      return {
        name, caseKind, payload, expectedEvent,
        expectedBattery: record.expected_battery_percent === null ? '' : batteryRaw!, expectedMeasurements,
      }
    })
  } catch { return null }
}

function decimalParts(text: string) {
  const negative = text.startsWith('-'), body = negative ? text.slice(1) : text
  const [integer, fraction = ''] = body.split('.')
  return { units: BigInt(`${integer || '0'}${fraction}`), fraction: fraction.length, negative }
}
function decimalText(units: bigint, fraction: number, negative: boolean): string {
  const digits = units.toString().padStart(fraction + 1, '0')
  const text = fraction === 0 ? digits : `${digits.slice(0, -fraction)}.${digits.slice(-fraction)}`
  return `${negative && units !== 0n ? '-' : ''}${text.includes('.') ? text.replace(/0+$/, '').replace(/\.$/, '') : text}`
}
/** Exact declared scale/offset projection, same field semantics as the battery mapping. */
function transformMeasurement(raw: string, scale: string, offset: string): string {
  const value = decimalParts(raw), factor = scale === '' ? { units: 1n, fraction: 0, negative: false } : decimalParts(scale), shift = offset === '' ? { units: 0n, fraction: 0, negative: false } : decimalParts(offset)
  const productFraction = value.fraction + factor.fraction
  const common = Math.max(productFraction, shift.fraction)
  const sign = (parts: { units: bigint; negative: boolean }) => parts.negative ? -parts.units : parts.units
  const product = sign(value) * sign(factor) * (10n ** BigInt(common - productFraction))
  const total = product + sign(shift) * (10n ** BigInt(common - shift.fraction))
  return decimalText(total < 0n ? -total : total, common, total < 0n)
}
function syntheticReadingValue(measurement: MeasurementDraft): { token: string; expectedText: string } {
  const candidates = ['0', '1', '-1', '10', '100', ...(measurement.min === '' ? [] : [measurement.min]), ...(measurement.max === '' ? [] : [measurement.max])]
  for (const raw of candidates) {
    const expected = transformMeasurement(raw, measurement.scale, measurement.offset)
    if ((measurement.min === '' || compareDecimal(expected, measurement.min) >= 0) && (measurement.max === '' || compareDecimal(expected, measurement.max) <= 0)) return { token: raw, expectedText: expected }
  }
  return { token: '0', expectedText: transformMeasurement('0', measurement.scale, measurement.offset) }
}

function serializeMeasurementValue(measurement: SampleMeasurementDraft): string {
  if (measurement.valueKind === 'STRING') return JSON.stringify(measurement.valueText)
  if (measurement.valueKind === 'BOOLEAN') return measurement.valueText === 'true' ? 'true' : 'false'
  if (!isNumberLexeme(measurement.valueText)) throw new Error(`样例读数 ${measurement.key} 的期望数值字面量无效`)
  return measurement.valueText
}

export function serializeSampleDrafts(drafts: SampleDraft[]): string {
  return `[${drafts.map(draft => {
    const fields = [`"name":${JSON.stringify(draft.name)}`, `"case_kind":${JSON.stringify(draft.caseKind)}`, `"payload":${JSON.stringify(draft.payload)}`, `"expected_event":${JSON.stringify(draft.expectedEvent)}`]
    if (draft.expectedBattery !== '' && !isNumberLexeme(draft.expectedBattery)) throw new Error('样例期望电量字面量无效')
    fields.push(`"expected_battery_percent":${draft.expectedBattery === '' ? 'null' : draft.expectedBattery}`)
    if (draft.expectedMeasurements.length > 0) fields.push(`"expected_measurements":[${draft.expectedMeasurements.map(measurement => {
      const base = `"key":${JSON.stringify(measurement.key)},"state":${JSON.stringify(measurement.state)}`
      return `{${base}${measurement.state === 'VALID' ? `,"value":${serializeMeasurementValue(measurement)}` : ''}}`
    }).join(',')}]`)
    return `{${fields.join(',')}}`
  }).join(',')}]`
}

export function validateSampleDrafts(drafts: SampleDraft[], declarations: MeasurementDraft[]): void {
  if (drafts.length === 0) throw new Error('至少需要一条合成样例')
  if (drafts.length > 16) throw new Error('合成样例最多16条')
  const names = new Set<string>()
  const declared = new Map(declarations.map(measurement => [measurement.key, measurement]))
  for (const draft of drafts) {
    if (draft.name.trim() === '' || draft.name.length > 100) throw new Error('样例名称不能为空且最长100字符')
    if (names.has(draft.name)) throw new Error(`样例名称 ${draft.name} 重复`)
    names.add(draft.name)
    if (!isCaseKind(draft.caseKind) || !isSampleEvent(draft.expectedEvent)) throw new Error(`样例 ${draft.name} 的事件类型无效`)
    if (draft.caseKind === 'MALFORMED' && draft.expectedEvent !== 'UNKNOWN') throw new Error(`样例 ${draft.name} 是无法解析报文，期望事件必须是UNKNOWN`)
    if (draft.expectedBattery !== '' && !isNumberLexeme(draft.expectedBattery)) throw new Error(`样例 ${draft.name} 的期望电量必须是普通十进制数值`)
    const keys = new Set<string>()
    for (const measurement of draft.expectedMeasurements) {
      if (keys.has(measurement.key)) throw new Error(`样例 ${draft.name} 的读数 ${measurement.key} 重复`)
      keys.add(measurement.key)
      const declaration = declared.get(measurement.key)
      if (!declaration) throw new Error(`样例 ${draft.name} 引用了未声明的读数 ${measurement.key}`)
      if (measurement.state === 'VALID') {
        if (measurement.valueKind !== declaration.valueType) throw new Error(`样例 ${draft.name} 的读数 ${measurement.key} 期望值类型与声明不一致`)
        if (measurement.valueKind === 'NUMBER' && !isNumberLexeme(measurement.valueText)) throw new Error(`样例 ${draft.name} 的读数 ${measurement.key} 期望数值字面量无效`)
        if (measurement.valueKind === 'BOOLEAN' && measurement.valueText !== 'true' && measurement.valueText !== 'false') throw new Error(`样例 ${draft.name} 的读数 ${measurement.key} 期望布尔值无效`)
      }
    }
  }
}

type PayloadNode = string | { [key: string]: PayloadNode }
/** Build one synthetic frame from pointer→raw-token pairs, keeping raw decimal lexemes intact. */
export function buildSamplePayload(tokens: { pointer: string; token: string }[]): string {
  const root: { [key: string]: PayloadNode } = {}
  for (const { pointer, token } of tokens) {
    const segments = pointerSegments(pointer)
    if (!segments || segments.length === 0) throw new Error(`合成样例路径无效：${pointer}`)
    let node = root
    segments.forEach((segment, index) => {
      if (index === segments.length - 1) {
        if (typeof node[segment] === 'object') throw new Error(`合成样例路径冲突：${pointer}`)
        node[segment] = token
      } else {
        const next = node[segment]
        if (next === undefined) node[segment] = {}
        else if (typeof next === 'string') throw new Error(`合成样例路径冲突：${pointer}`)
        node = node[segment] as { [key: string]: PayloadNode }
      }
    })
  }
  return serializeNode(root)
}
function serializeNode(node: PayloadNode): string {
  if (typeof node === 'string') return node
  return `{${Object.entries(node).map(([key, value]) => `${JSON.stringify(key)}:${serializeNode(value)}`).join(',')}}`
}

const legacySamples: ParserSample[] = [
  { name: '合成火警与有效电量', case_kind: 'FIRE', payload: '{"event":"FIRE","battery":60}', expected_event: 'FIRE', expected_battery_percent: 60 },
  { name: '合成恢复与真实零电量', case_kind: 'RECOVERY', payload: '{"event":"RECOVERY","battery":0}', expected_event: 'RECOVERY', expected_battery_percent: 0 },
  { name: '合成未知事件与缺失电量', case_kind: 'UNKNOWN', payload: '{"event":"not-mapped"}', expected_event: 'UNKNOWN', expected_battery_percent: null },
  { name: '合成无效JSON', case_kind: 'MALFORMED', payload: '{invalid-json', expected_event: 'UNKNOWN', expected_battery_percent: null },
]

/**
 * Capability-aware synthetic set. Legacy products keep the previous four smoke samples verbatim;
 * products that declare measurements never receive invented FIRE/RECOVERY evidence.
 */
export function syntheticSampleDrafts(schema: SchemaDraft, capabilities: Capabilities): SampleDraft[] {
  if (schema.measurements.length === 0) return legacySamples
    .filter(sample => sample.case_kind === 'FIRE' ? capabilities.fire !== 'UNSUPPORTED' : sample.case_kind === 'RECOVERY' ? capabilities.recovery !== 'UNSUPPORTED' : true)
    .map(sample => ({ name: sample.name, caseKind: sample.case_kind, payload: sample.payload, expectedEvent: sample.expected_event, expectedBattery: sample.expected_battery_percent === null ? '' : String(sample.expected_battery_percent), expectedMeasurements: [] }))
  const samples: SampleDraft[] = []
  const measurementTokens = schema.measurements.map(measurement => measurement.valueType === 'STRING'
    ? { token: '"synthetic"', expected: { key: measurement.key, state: 'VALID' as const, valueText: 'synthetic', valueKind: 'STRING' as const } }
    : measurement.valueType === 'BOOLEAN'
      ? { token: 'true', expected: { key: measurement.key, state: 'VALID' as const, valueText: 'true', valueKind: 'BOOLEAN' as const } }
      : (() => { const value = syntheticReadingValue(measurement); return { token: value.token, expected: { key: measurement.key, state: 'VALID' as const, valueText: value.expectedText, valueKind: 'NUMBER' as const } } })())
  const allExpected = measurementTokens.map(entry => entry.expected)
  const identityBattery = schema.battery !== null && schema.battery.scale === '1' && schema.battery.offset === '0' && isNumberLexeme(schema.battery.min) && isNumberLexeme(schema.battery.max)
  const batteryToken = identityBattery && capabilities.battery === 'SUPPORTED' && compareDecimal('60', schema.battery!.min) >= 0 && compareDecimal('60', schema.battery!.max) <= 0 ? schema.battery!.pointer : null
  const batteryExpected = batteryToken === null ? '' : '60'
  const batteryTokens = batteryToken === null ? [] : [{ pointer: batteryToken, token: '60' }]
  const eventTokens = (type: ProductEventType): { pointer: string; token: string }[] => {
    const mapping = schema.eventMappings.find(entry => entry.eventType === type)
    return mapping && schema.eventPointer !== '' ? [{ pointer: schema.eventPointer, token: mappingJsonToken(mapping) }] : []
  }
  const pushEvent = (type: 'FIRE' | 'RECOVERY' | 'FAULT' | 'HEARTBEAT') => {
    if (capabilities[measurementCapability[type]] !== 'SUPPORTED' || !schema.eventMappings.some(entry => entry.eventType === type)) return
    const tokens = [...eventTokens(type), ...measurementTokens.map((entry, index) => ({ pointer: schema.measurements[index].pointer, token: entry.token }))]
    if (batteryToken !== null) tokens.push({ pointer: batteryToken, token: '60' })
    samples.push({ name: `合成${capabilityLabels[measurementCapability[type]]}事件与声明读数`, caseKind: type, payload: buildSamplePayload(tokens), expectedEvent: type, expectedBattery: batteryExpected, expectedMeasurements: allExpected })
  }
  ;(['FIRE', 'RECOVERY', 'FAULT', 'HEARTBEAT'] as const).forEach(pushEvent)
  const mappedTokens = new Set(schema.eventMappings.map(entry => mappingJsonToken(entry)))
  let unmapped = '"not-mapped"', unmappedIndex = 0
  while (mappedTokens.has(unmapped)) unmapped = `"__unmapped_event_${unmappedIndex++}__"`
  const baseParamTokens = schema.eventMappings.length > 0 && schema.eventPointer !== '' ? [{ pointer: schema.eventPointer, token: unmapped }] : []
  samples.push({
    name: '合成读数全部有效', caseKind: 'PARAM', expectedEvent: 'PARAM', expectedBattery: batteryExpected,
    payload: buildSamplePayload([...baseParamTokens, ...batteryTokens, ...measurementTokens.map((entry, index) => ({ pointer: schema.measurements[index].pointer, token: entry.token }))]),
    expectedMeasurements: allExpected,
  })
  const requiredIndex = schema.measurements.findIndex(measurement => measurement.required)
  const target = requiredIndex >= 0 ? requiredIndex : 0
  const missingExpected = allExpected.map((entry, index) => index === target ? { ...entry, state: 'MISSING' as const, valueText: '', valueKind: schema.measurements[index].valueType } : entry)
  const otherTokens = measurementTokens.flatMap((entry, index) => index === target ? [] : [{ pointer: schema.measurements[index].pointer, token: entry.token }])
  samples.push({
    name: `合成读数缺失 · ${schema.measurements[target].key}`, caseKind: otherTokens.length > 0 ? 'PARAM' : 'UNKNOWN',
    expectedEvent: otherTokens.length > 0 ? 'PARAM' : 'UNKNOWN', expectedBattery: batteryExpected,
    payload: buildSamplePayload([...baseParamTokens, ...batteryTokens, ...otherTokens]), expectedMeasurements: missingExpected,
  })
  const invalidExpected = allExpected.map((entry, index) => index === target ? { ...entry, state: 'INVALID' as const, valueText: '', valueKind: schema.measurements[index].valueType } : entry)
  const invalidTokens = measurementTokens.flatMap((entry, index) => index !== target ? [{ pointer: schema.measurements[index].pointer, token: entry.token }] : [{ pointer: schema.measurements[index].pointer, token: schema.measurements[index].valueType === 'NUMBER' ? '"not-a-number"' : schema.measurements[index].valueType === 'STRING' ? '123' : '"maybe"' }])
  samples.push({
    name: `合成读数无效 · ${schema.measurements[target].key}`, caseKind: otherTokens.length > 0 ? 'PARAM' : 'UNKNOWN',
    expectedEvent: otherTokens.length > 0 ? 'PARAM' : 'UNKNOWN', expectedBattery: batteryExpected,
    payload: buildSamplePayload([...baseParamTokens, ...batteryTokens, ...invalidTokens]), expectedMeasurements: invalidExpected,
  })
  samples.push({ name: '合成未知事件与无读数', caseKind: 'UNKNOWN', payload: schema.eventMappings.length > 0 && schema.eventPointer !== '' ? buildSamplePayload([{ pointer: schema.eventPointer, token: unmapped }]) : '{}', expectedEvent: 'UNKNOWN', expectedBattery: '', expectedMeasurements: [] })
  samples.push({ name: '合成无效JSON', caseKind: 'MALFORMED', payload: '{invalid-json', expectedEvent: 'UNKNOWN', expectedBattery: '', expectedMeasurements: [] })
  return samples
}