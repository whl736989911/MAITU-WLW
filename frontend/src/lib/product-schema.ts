// Typed projection of the JSON_POINTER_V1 product schema. The raw JSON document remains the
// authoritative input; this module only produces it from typed fields. Number fields stay lexical
// strings so no host JavaScript Number ever rewrites a declared decimal.
import type { Capabilities, CapabilityState, ProductEventType, ProductReading } from './types'
export type { ProductEventType } from './types'
import { jsonArrayItems, jsonFieldText, parseJsonDocument } from './json-document'

export type MeasurementValueType = ProductReading['value_type']
export type CapabilityKey = keyof Capabilities
export type MappingRawKind = 'STRING' | 'NUMBER' | 'BOOLEAN'

export interface MappingDraft { rawKind: MappingRawKind; rawText: string; eventType: ProductEventType }
export interface MeasurementDraft {
  key: string; label: string; pointer: string; valueType: MeasurementValueType; unit: string
  scale: string; offset: string; min: string; max: string; required: boolean
}
export interface BatteryDraft { pointer: string; scale: string; offset: string; min: string; max: string }
export interface SchemaDraft { eventPointer: string; eventMappings: MappingDraft[]; measurements: MeasurementDraft[]; battery: BatteryDraft | null }

const extendedCapabilityKeys = ['fault', 'heartbeat', 'telemetry'] as const
export const capabilityKeys: readonly CapabilityKey[] = ['fire', 'recovery', 'battery', ...extendedCapabilityKeys]
export const capabilityLabels: Record<CapabilityKey, string> = { fire: '火警', recovery: '恢复', battery: '电量', fault: '故障', heartbeat: '心跳', telemetry: '读数' }
export const capabilityEditorLabels: Record<CapabilityKey, string> = { fire: '火警能力', recovery: '恢复能力', battery: '电量能力', fault: '故障能力', heartbeat: '心跳能力', telemetry: '读数能力' }
export const eventTypeLabels: Record<ProductEventType, string> = { FIRE: '火警 FIRE', RECOVERY: '恢复 RECOVERY', FAULT: '故障 FAULT', HEARTBEAT: '心跳 HEARTBEAT', PARAM: '读数 PARAM', OTHER: '其它 OTHER' }
export const valueTypeLabels: Record<MeasurementValueType, string> = { NUMBER: '数值', STRING: '文本', BOOLEAN: '布尔' }
export const mappingRawKindLabels: Record<MappingRawKind, string> = { STRING: '字符串', NUMBER: '数字', BOOLEAN: '布尔' }

const eventTypes: Record<ProductEventType, true> = { FIRE: true, RECOVERY: true, FAULT: true, HEARTBEAT: true, PARAM: true, OTHER: true }
const valueTypes: Record<MeasurementValueType, true> = { NUMBER: true, STRING: true, BOOLEAN: true }
const numberLexeme = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/

export function isEventType(value: unknown): value is ProductEventType { return typeof value === 'string' && Object.hasOwn(eventTypes, value) }
export function isMeasurementValueType(value: unknown): value is MeasurementValueType { return typeof value === 'string' && Object.hasOwn(valueTypes, value) }
export function isNumberLexeme(value: unknown): value is string { return typeof value === 'string' && numberLexeme.test(value) }
export function capabilityState(value: unknown): CapabilityState | null {
  return value === 'SUPPORTED' || value === 'UNSUPPORTED' || value === 'PENDING' ? value : null
}

/** Normalizes declared states (including the optional extended declarations) after runtime validation. */
export function productCapabilities(value: Capabilities): Capabilities {
  const record: Record<string, unknown> = { ...value }
  const extended: Capabilities = { fire: 'PENDING', recovery: 'PENDING', battery: 'PENDING' }
  for (const key of ['fire', 'recovery', 'battery'] as const) { const state = capabilityState(record[key]); if (state === null) throw new Error('产品能力状态响应未确认'); extended[key] = state }
  for (const key of extendedCapabilityKeys) { if (!Object.hasOwn(record, key)) continue; const state = capabilityState(record[key]); if (state === null) throw new Error('产品能力状态响应未确认'); extended[key] = state }
  return extended
}

export function declaredCapabilityKeys(value: Capabilities): CapabilityKey[] {
  const keys: CapabilityKey[] = ['fire', 'recovery', 'battery']
  const record: Record<string, unknown> = { ...value }
  for (const key of extendedCapabilityKeys) if (capabilityState(record[key]) !== null) keys.push(key)
  return keys
}

export function pendingCapabilities(): Capabilities { return { fire: 'PENDING', recovery: 'PENDING', battery: 'PENDING', fault: 'PENDING', heartbeat: 'PENDING', telemetry: 'PENDING' } }



function stringField(objectText: string, key: string): string | undefined {
  const raw = jsonFieldText(objectText, key)
  if (raw === undefined || raw === 'null') return undefined
  if (!raw.startsWith('"')) throw new Error(`${key} 必须是字符串`)
  const value: unknown = JSON.parse(raw)
  if (typeof value !== 'string') throw new Error(`${key} 必须是字符串`)
  return value
}
function numberField(objectText: string, key: string): string | undefined {
  const raw = jsonFieldText(objectText, key)
  if (raw === undefined || raw === 'null') return undefined
  if (!isNumberLexeme(raw)) throw new Error(`${key} 必须是普通十进制数值字面量`)
  return raw
}
function booleanField(objectText: string, key: string): boolean | undefined {
  const raw = jsonFieldText(objectText, key)
  if (raw === undefined || raw === 'null') return undefined
  if (raw !== 'true' && raw !== 'false') throw new Error(`${key} 必须是布尔值`)
  return raw === 'true'
}
function mappingFromRaw(rawValue: string, eventType: ProductEventType): MappingDraft {
  if (rawValue.startsWith('"')) {
    const value: unknown = JSON.parse(rawValue)
    if (typeof value !== 'string') throw new Error('raw_value 必须是字符串')
    return { rawKind: 'STRING', rawText: value, eventType }
  }
  if (rawValue === 'true' || rawValue === 'false') return { rawKind: 'BOOLEAN', rawText: rawValue, eventType }
  if (isNumberLexeme(rawValue)) return { rawKind: 'NUMBER', rawText: rawValue, eventType }
  throw new Error('raw_value 只允许JSON字符串、布尔或普通十进制数值')
}

/** Returns null when the document is not a JSON_POINTER_V1 object the typed editor can express. */
export function parseSchemaDraft(text: string): SchemaDraft | null {
  try {
    const document = parseJsonDocument(text)
    if (!document || typeof document !== 'object' || Array.isArray(document)) return null
    const root = document as Record<string, unknown>
    if (root.format !== 'JSON_POINTER_V1') return null
    const eventPointer = stringField(text, 'event_pointer') ?? ''
    const mappings: MappingDraft[] = []
    const mappingRaw = jsonFieldText(text, 'event_mappings')
    if (mappingRaw !== undefined && mappingRaw !== 'null') {
      for (const item of jsonArrayItems(mappingRaw)) {
        const eventTypeValue: unknown = JSON.parse(jsonFieldText(item, 'event_type') ?? 'null')
        if (!isEventType(eventTypeValue)) throw new Error('event_type 无效')
        const rawValue = jsonFieldText(item, 'raw_value')
        if (rawValue === undefined) throw new Error('缺少 raw_value')
        mappings.push(mappingFromRaw(rawValue, eventTypeValue))
      }
    }
    const measurements: MeasurementDraft[] = []
    const measurementRaw = jsonFieldText(text, 'measurements')
    if (measurementRaw !== undefined && measurementRaw !== 'null') {
      for (const item of jsonArrayItems(measurementRaw)) {
        const key = stringField(item, 'key'), label = stringField(item, 'label'), pointer = stringField(item, 'pointer')
        const typeValue: unknown = JSON.parse(jsonFieldText(item, 'value_type') ?? 'null')
        if (key === undefined || label === undefined || pointer === undefined || !isMeasurementValueType(typeValue)) throw new Error('读数声明字段无效')
        measurements.push({
          key, label, pointer, valueType: typeValue, unit: stringField(item, 'unit') ?? '',
          scale: numberField(item, 'scale') ?? '', offset: numberField(item, 'offset') ?? '',
          min: numberField(item, 'min') ?? '', max: numberField(item, 'max') ?? '',
          required: booleanField(item, 'required') ?? false,
        })
      }
    }
    let battery: BatteryDraft | null = null
    const batteryRaw = jsonFieldText(text, 'battery')
    if (batteryRaw !== undefined && batteryRaw !== 'null') {
      const batteryValue: unknown = JSON.parse(batteryRaw)
      if (!batteryValue || typeof batteryValue !== 'object' || Array.isArray(batteryValue)) throw new Error('battery 必须是对象或 null')
      const pointer = stringField(batteryRaw, 'pointer') ?? '', scale = numberField(batteryRaw, 'scale'), offset = numberField(batteryRaw, 'offset'), min = numberField(batteryRaw, 'min'), max = numberField(batteryRaw, 'max')
      if (scale === undefined || offset === undefined || min === undefined || max === undefined) throw new Error('battery 字段不完整')
      battery = { pointer, scale, offset, min, max }
    }
    return { eventPointer, eventMappings: mappings, measurements, battery }
  } catch { return null }
}

export function mappingJsonToken(mapping: MappingDraft): string {
  if (mapping.rawKind === 'STRING') return JSON.stringify(mapping.rawText)
  if (mapping.rawKind === 'BOOLEAN') return mapping.rawText === 'true' ? 'true' : 'false'
  if (!isNumberLexeme(mapping.rawText)) throw new Error('事件映射原始数字字面量无效')
  return mapping.rawText
}
function optionalNumber(key: string, value: string): string {
  if (value === '') return ''
  if (!isNumberLexeme(value)) throw new Error(`${key} 必须是普通十进制数值字面量`)
  return `,"${key}":${value}`
}
function batchedNumber(key: string, value: string): string {
  if (!isNumberLexeme(value)) throw new Error(`${key} 必须是普通十进制数值字面量`)
  return `"${key}":${value}`
}

export function serializeSchemaDraft(draft: SchemaDraft): string {
  const section: string[] = ['"format":"JSON_POINTER_V1"']
  if (draft.eventMappings.length > 0) {
    section.push(`"event_pointer":${JSON.stringify(draft.eventPointer)}`)
    section.push(`"event_mappings":[${draft.eventMappings.map(mapping => `{"raw_value":${mappingJsonToken(mapping)},"event_type":${JSON.stringify(mapping.eventType)}}`).join(',')}]`)
  }
  if (draft.measurements.length > 0) {
    section.push(`"measurements":[${draft.measurements.map(measurement => {
      const parts = [`"key":${JSON.stringify(measurement.key)}`, `"label":${JSON.stringify(measurement.label)}`, `"pointer":${JSON.stringify(measurement.pointer)}`, `"value_type":${JSON.stringify(measurement.valueType)}`]
      if (measurement.unit !== '') parts.push(`"unit":${JSON.stringify(measurement.unit)}`)
      if (measurement.valueType === 'NUMBER') {
        for (const key of ['scale', 'offset', 'min', 'max'] as const) { const value = optionalNumber(key, measurement[key]); if (value !== '') parts.push(value.slice(1)) }
      }
      if (measurement.required) parts.push('"required":true')
      return `{${parts.join(',')}}`
    }).join(',')}]`)
  }
  section.push(`"battery":${draft.battery === null ? 'null' : `{${[
    `"pointer":${JSON.stringify(draft.battery.pointer)}`,
    batchedNumber('scale', draft.battery.scale), batchedNumber('offset', draft.battery.offset),
    batchedNumber('min', draft.battery.min), batchedNumber('max', draft.battery.max),
  ].join(',')}}`}`)
  return `{${section.join(',')}}`
}

function decimal(value: string) {
  const negative = value.startsWith('-'), body = negative ? value.slice(1) : value
  const [integer, fraction = ''] = body.split('.')
  return { negative, units: BigInt(`${integer || '0'}${fraction}`), fractionLength: fraction.length, zero: /^0*$/.test(`${integer}${fraction}`) }
}
export function compareDecimal(left: string, right: string): number {
  const a = decimal(left), b = decimal(right)
  if (a.zero && b.zero) return 0
  const length = Math.max(a.fractionLength, b.fractionLength)
  const scaledA = a.units * (10n ** BigInt(length - a.fractionLength)) * (a.negative ? -1n : 1n)
  const scaledB = b.units * (10n ** BigInt(length - b.fractionLength)) * (b.negative ? -1n : 1n)
  return scaledA < scaledB ? -1 : scaledA > scaledB ? 1 : 0
}
export function pointerSegments(pointer: string): string[] | null {
  if (pointer === '' || !pointer.startsWith('/')) return null
  return pointer.slice(1).split('/').map(segment => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
}
function pointerProblem(pointer: string): string | null {
  if (pointer === '') return '路径不能为空'
  if (pointer.length > 256) return '路径超过256字符'
  if (!pointer.startsWith('/') || pointer.includes('*') || pointer.includes('$')) return '路径必须是JSON Pointer，且不允许通配符或脚本符号'
  const segments = pointer.split('/').slice(1)
  if (segments.length > 16) return '路径层级超过16'
  if (segments.some(segment => segment === '..')) return '路径不允许 .. 片段'
  for (const segment of segments) for (let i = 0; i < segment.length; i++) if (segment[i] === '~' && segment[i + 1] !== '0' && segment[i + 1] !== '1') return '路径中的 ~ 必须转义为 ~0 或 ~1'
  return null
}

/** Structural validation only. Backend remains authoritative for capability/publish consistency. */
export function validateSchemaDraft(draft: SchemaDraft, capabilities: Capabilities): void {
  if (draft.measurements.length > 64) throw new Error('监测读数最多64条')
  if (draft.eventMappings.length > 64) throw new Error('事件映射最多64条')
  if (draft.measurements.length === 0 && draft.eventMappings.length === 0) throw new Error('请至少声明一个事件映射或一条监测读数')
  if (draft.eventMappings.length > 0) {
    const eventProblem = pointerProblem(draft.eventPointer)
    if (eventProblem) throw new Error(`事件路径：${eventProblem}`)
    const rawKeys = new Set<string>()
    for (const mapping of draft.eventMappings) {
      if (!isEventType(mapping.eventType)) throw new Error('事件映射的事件类型无效')
      if (mapping.rawKind === 'STRING' && mapping.rawText === '') throw new Error('事件映射原始值不能为空字符串')
      if (mapping.rawKind === 'NUMBER' && !isNumberLexeme(mapping.rawText)) throw new Error('事件映射原始数字字面量无效')
      const normalized = `${mapping.rawKind}:${mapping.rawKind === 'STRING' ? mapping.rawText : mapping.rawText}`
      if (rawKeys.has(normalized)) throw new Error('事件映射原始值按类型与值重复')
      rawKeys.add(normalized)
    }
  }
  const keys = new Set<string>()
  for (const measurement of draft.measurements) {
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(measurement.key)) throw new Error(`读数标识 ${measurement.key || '（空）'} 无效：小写字母开头，仅小写字母/数字/下划线，最长64`)
    if (keys.has(measurement.key)) throw new Error(`读数标识 ${measurement.key} 重复`)
    keys.add(measurement.key)
    if (measurement.label.trim() === '' || measurement.label.length > 128) throw new Error(`读数 ${measurement.key} 的标签不能为空且最长128字符`)
    if (measurement.unit.length > 32) throw new Error(`读数 ${measurement.key} 的单位最长32字符`)
    const problem = pointerProblem(measurement.pointer)
    if (problem) throw new Error(`读数 ${measurement.key} 的路径：${problem}`)
    if (!isMeasurementValueType(measurement.valueType)) throw new Error(`读数 ${measurement.key} 的值类型无效`)
    if (measurement.valueType !== 'NUMBER' && (measurement.scale || measurement.offset || measurement.min || measurement.max)) throw new Error(`读数 ${measurement.key} 仅NUMBER可配置缩放、偏移与范围`)
    if (measurement.valueType === 'NUMBER') {
      for (const [field, label] of [['scale', '缩放'], ['offset', '偏移'], ['min', '最小值'], ['max', '最大值']] as const) {
        const value = measurement[field]
        if (value !== '' && !isNumberLexeme(value)) throw new Error(`读数 ${measurement.key} 的${label}必须是普通十进制数值字面量`)
      }
      if (measurement.scale !== '' && compareDecimal(measurement.scale, '0') <= 0) throw new Error(`读数 ${measurement.key} 的缩放必须大于0`)
      if (measurement.min !== '' && measurement.max !== '' && compareDecimal(measurement.min, measurement.max) > 0) throw new Error(`读数 ${measurement.key} 的最小值不能大于最大值`)
    }
  }
  if (draft.measurements.length > 0 && capabilities.telemetry === 'UNSUPPORTED') throw new Error('声明读数前必须将读数能力设为SUPPORTED或PENDING')
  if (capabilities.telemetry === 'SUPPORTED' && draft.measurements.length === 0) throw new Error('读数能力声明支持时至少需要一条监测读数')
  const pointers = [...(draft.eventMappings.length > 0 ? [draft.eventPointer] : []), ...draft.measurements.map(measurement => measurement.pointer), ...(draft.battery === null ? [] : [draft.battery.pointer])]
  for (let left = 0; left < pointers.length; left++) for (let right = left + 1; right < pointers.length; right++) {
    const a = pointers[left], b = pointers[right]
    if (a === b) throw new Error(`Schema 路径重复：${a}`)
    if (a.startsWith(`${b}/`) || b.startsWith(`${a}/`)) throw new Error(`Schema 路径层级冲突：${a} 与 ${b}`)
  }
  if (draft.battery !== null) {
    const problem = pointerProblem(draft.battery.pointer)
    if (problem) throw new Error(`电量路径：${problem}`)
    for (const [field, label] of [['scale', '缩放'], ['offset', '偏移'], ['min', '最小值'], ['max', '最大值']] as const) {
      if (!isNumberLexeme(draft.battery[field])) throw new Error(`电量${label}必须是普通十进制数值字面量`)
    }
    if (compareDecimal(draft.battery.scale, '0') <= 0) throw new Error('电量缩放必须大于0')
    if (compareDecimal(draft.battery.min, draft.battery.max) > 0) throw new Error('电量最小值不能大于最大值')
  }
  if (capabilities.battery === 'SUPPORTED' && draft.battery === null) throw new Error('电量能力声明支持时必须声明电量映射')
}

export function emptyMeasurement(): MeasurementDraft { return { key: '', label: '', pointer: '/', valueType: 'NUMBER', unit: '', scale: '', offset: '', min: '', max: '', required: false } }
export function emptyMapping(): MappingDraft { return { rawKind: 'STRING', rawText: '', eventType: 'FIRE' } }
