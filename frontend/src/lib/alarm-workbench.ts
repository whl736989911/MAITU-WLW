import type { Alarm, AlarmMetricDefinitions, AlarmWorkbench, AlarmWorkbenchFilters } from './types'
export const emptyAlarmFilters: AlarmWorkbenchFilters = { keyword: '', physical_state: null, response_state: null, conclusion: null, occurred_from: null, occurred_to: null }
export const expectedMetricDefinitions: AlarmMetricDefinitions = { scope: 'FILTERED_ALL_PAGES', time_field: 'ALARM_OCCURRED_AT', time_bounds: 'FROM_INCLUSIVE_TO_EXCLUSIVE', unhandled: 'RESPONSE_UNHANDLED', unrecovered: 'PHYSICAL_FIRE', true_fire: 'CURRENT_CONCLUSION_TRUE_FIRE' }
const physical = ['FIRE', 'NORMAL'], response = ['UNHANDLED', 'HANDLED'], conclusions = ['TRUE_FIRE', 'FALSE_ALARM', 'TEST', 'DEVICE_ALARM']
export const safeCount = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
export interface AlarmFilterDraft { keyword: string; physical_state: string; response_state: string; conclusion: string; occurred_from: string; occurred_to: string }
export const emptyAlarmDraft: AlarmFilterDraft = { keyword: '', physical_state: '', response_state: '', conclusion: '', occurred_from: '', occurred_to: '' }
const timeError = () => new Error('时间必须为有效公元0001–9999年、明确offset的ISO时刻；查询边界最多微秒精度，不推定本地时区')
/** Fixed-width UTC comparison, with filter precision constrained to microseconds. No local Date parsing or rounding. */
export function canonicalAlarmInstant(value: string, filter = true): string {
  if (typeof value !== 'string') throw timeError()
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2})(?::(\d{2}))?)$/.exec(value)
  if (!match) throw timeError()
  const [, y, m, d, h, minute, second, fraction = '', zone, sign, oh = '0', om = '0', os = '0'] = match
  const year = Number(y), month = Number(m), day = Number(d), hour = Number(h), mins = Number(minute), sec = Number(second), offsetHour = Number(oh), offsetMinute = Number(om), offsetSecond = Number(os)
  if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || mins > 59 || sec > 59 || offsetHour > 18 || offsetMinute > 59 || offsetSecond > 59 || (offsetHour === 18 && (offsetMinute !== 0 || offsetSecond !== 0)) || (filter && /[1-9]/.test(fraction.slice(6)))) throw timeError()
  const utc = new Date(0); utc.setUTCFullYear(year, month - 1, day); utc.setUTCHours(hour, mins, sec, 0)
  if (utc.getUTCFullYear() !== year || utc.getUTCMonth() !== month - 1 || utc.getUTCDate() !== day) throw timeError()
  const offset = zone === 'Z' ? 0 : (offsetHour * 3600 + offsetMinute * 60 + offsetSecond) * (sign === '+' ? 1 : -1)
  utc.setTime(utc.getTime() - offset * 1000)
  if (utc.getUTCFullYear() < 1 || utc.getUTCFullYear() > 9999 || !Number.isFinite(utc.getTime())) throw timeError()
  return `${utc.toISOString().slice(0, 19)}.${fraction.padEnd(9, '0')}Z`
}
export function beijingInput(value: string): string | null {
  if (!value) return null
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(value)) throw timeError()
  const explicit = `${value.length === 16 ? `${value}:00` : value}+08:00`
  canonicalAlarmInstant(explicit); return explicit
}
export function compileAlarmDraft(draft: AlarmFilterDraft): AlarmWorkbenchFilters {
  const nullableEnum = (value: string, allowed: string[]) => { if (value === '') return null; if (!allowed.includes(value)) throw new Error('筛选枚举未确认，不能将未知值当全部'); return value }
  const filters: AlarmWorkbenchFilters = { keyword: draft.keyword.trim(), physical_state: nullableEnum(draft.physical_state, physical) as AlarmWorkbenchFilters['physical_state'], response_state: nullableEnum(draft.response_state, response) as AlarmWorkbenchFilters['response_state'], conclusion: nullableEnum(draft.conclusion, [...conclusions, 'UNREVIEWED']) as AlarmWorkbenchFilters['conclusion'], occurred_from: beijingInput(draft.occurred_from), occurred_to: beijingInput(draft.occurred_to) }
  if (filters.occurred_from && filters.occurred_to && canonicalAlarmInstant(filters.occurred_from) >= canonicalAlarmInstant(filters.occurred_to)) throw new Error('开始时间必须早于结束时间（开始含，结束不含），不自动交换边界')
  return filters
}
function canonicalFilters(value: AlarmWorkbenchFilters, fromServer = false): AlarmWorkbenchFilters {
  if (!value || typeof value.keyword !== 'string' || value.keyword !== value.keyword.trim() || !(value.physical_state === null || physical.includes(value.physical_state)) || !(value.response_state === null || response.includes(value.response_state)) || !(value.conclusion === null || [...conclusions, 'UNREVIEWED'].includes(value.conclusion))) throw new Error('筛选配置未确认')
  const time = (stamp: string | null) => { if (stamp === null) return null; if (fromServer && (typeof stamp !== 'string' || !stamp.endsWith('Z'))) throw timeError(); return canonicalAlarmInstant(stamp) }
  const result = { keyword: value.keyword, physical_state: value.physical_state, response_state: value.response_state, conclusion: value.conclusion, occurred_from: time(value.occurred_from), occurred_to: time(value.occurred_to) }
  if (result.occurred_from && result.occurred_to && result.occurred_from >= result.occurred_to) throw timeError()
  return result
}
function validAlarm(item: Alarm): boolean {
  return !!item && typeof item.id === 'string' && item.id.trim().length > 0 && typeof item.tenant_id === 'string' && item.tenant_id.trim().length > 0 && typeof item.device_id === 'string' && item.device_id.trim().length > 0 && (item.device_name === null || typeof item.device_name === 'string') && (item.address === null || typeof item.address === 'string') && (item.event_type === null || typeof item.event_type === 'string') && physical.includes(item.physical_state ?? '') && response.includes(item.response_state ?? '') && (item.conclusion === null || conclusions.includes(item.conclusion)) && ((item.response_state === 'HANDLED') === (item.conclusion !== null)) && safeCount(item.revision) && (item.occurred_at === null || !!canonicalAlarmInstant(item.occurred_at, false))
}
/** Fail closed: only this validated result can supply authoritative cards or rows. */
export function validateAlarmWorkbench(data: AlarmWorkbench, applied: AlarmWorkbenchFilters, page: number, size?: number): string | null {
  try {
    if (!data || !Array.isArray(data.items) || !safeCount(data.total) || !safeCount(data.page) || data.page < 1 || data.page !== page || !safeCount(data.size) || data.size < 1 || (size !== undefined && data.size !== size) || data.timezone !== 'Asia/Shanghai' || !canonicalAlarmInstant(data.as_of, false)) throw new Error('分页/as_of/timezone未确认')
    if (JSON.stringify(canonicalFilters(data.filters, true)) !== JSON.stringify(canonicalFilters(applied))) throw new Error('服务端实际filters与当前已应用条件失配')
    for (const [key, value] of Object.entries(expectedMetricDefinitions)) if (data.metric_definitions?.[key as keyof AlarmMetricDefinitions] !== value) throw new Error('统计口径定义未确认')
    const metrics = data.metrics
    if (!metrics || !['alarm_total', 'unhandled', 'handled', 'unrecovered', 'recovered', 'true_fire'].every(key => safeCount(metrics[key as keyof typeof metrics])) || metrics.alarm_total !== data.total || metrics.unhandled + metrics.handled !== data.total || metrics.unrecovered + metrics.recovered !== data.total || metrics.true_fire > metrics.handled) throw new Error('统计总量/独立状态关系未确认')
    const offset = (data.page - 1) * data.size
    if (!safeCount(offset) || data.items.length !== Math.min(data.size, Math.max(0, data.total - offset)) || new Set(data.items.map(item => item.id)).size !== data.items.length) throw new Error('当前页行数/身份与分页失配')
    const expected = canonicalFilters(applied)
    const requiresHandled = expected.response_state === 'HANDLED' || (expected.conclusion !== null && expected.conclusion !== 'UNREVIEWED')
    const requiresUnhandled = expected.response_state === 'UNHANDLED' || expected.conclusion === 'UNREVIEWED'
    if ((requiresHandled && (metrics.handled !== data.total || metrics.unhandled !== 0)) || (requiresUnhandled && (metrics.unhandled !== data.total || metrics.handled !== 0)) || (expected.physical_state === 'FIRE' && (metrics.unrecovered !== data.total || metrics.recovered !== 0)) || (expected.physical_state === 'NORMAL' && (metrics.recovered !== data.total || metrics.unrecovered !== 0)) || (expected.conclusion === 'TRUE_FIRE' && metrics.true_fire !== data.total) || (expected.conclusion !== null && expected.conclusion !== 'TRUE_FIRE' && metrics.true_fire !== 0)) throw new Error('全量统计与已应用枚举条件失配')
    const observed = { alarm_total: data.items.length, unhandled: 0, handled: 0, unrecovered: 0, recovered: 0, true_fire: 0 }
    for (const item of data.items) {
      if (!validAlarm(item) || (expected.physical_state !== null && item.physical_state !== expected.physical_state) || (expected.response_state !== null && item.response_state !== expected.response_state) || (expected.conclusion !== null && (expected.conclusion === 'UNREVIEWED' ? item.conclusion !== null : item.conclusion !== expected.conclusion))) throw new Error('报警身份/状态与筛选未确认')
      if (item.response_state === 'UNHANDLED') observed.unhandled++; else observed.handled++
      if (item.physical_state === 'FIRE') observed.unrecovered++; else observed.recovered++
      if (item.conclusion === 'TRUE_FIRE') observed.true_fire++
      if (expected.occurred_from || expected.occurred_to) { if (!item.occurred_at) throw timeError(); const stamp = canonicalAlarmInstant(item.occurred_at, false); if ((expected.occurred_from && stamp < expected.occurred_from) || (expected.occurred_to && stamp >= expected.occurred_to)) throw new Error('报警时刻与含/排边界失配') }
    }
    for (const [key, value] of Object.entries(observed)) if (value > metrics[key as keyof typeof metrics] || (data.items.length === data.total && value !== metrics[key as keyof typeof metrics])) throw new Error('页内已知计数超过全量或全量可见时计数不一致')
    return null
  } catch (error) { return error instanceof Error ? error.message : '响应信息未确认' }
}
