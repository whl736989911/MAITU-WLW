import type { Alarm, AlarmCoverage, AlarmHistory, AlarmTraceEntry, AlarmTraceState, Audience, Paginated, AlarmWorkbench, AlarmWorkbenchFilters, AlarmWorkbenchMetrics, AlarmMetricDefinitions } from './types'
const alarmKeys = ['id', 'tenant_id', 'device_id', 'device_name', 'address', 'event_type', 'occurred_at', 'physical_state', 'response_state', 'conclusion', 'revision', 'is_simulated', 'time_quality', 'recorded_at', 'latest_alarm_at', 'recovered_at'] as const
const entryKeys = ['id', 'alarm_id', 'action', 'revision_before', 'revision_after', 'occurred_at', 'recorded_at', 'actor_kind', 'actor_label', 'is_self', 'remark', 'origin'] as const
const customerLabels = ['本人', '平台人员', '单位人员', '系统处理', '历史未知'] as const
function pick<T>(value: Record<string, unknown>, keys: readonly string[]): T { return Object.fromEntries(keys.filter(key => Object.hasOwn(value, key)).map(key => [key, value[key]])) as T }
function alarmMetadata(value: Record<string, unknown>): Alarm {
  return { ...pick<Alarm>(value, alarmKeys), device_eui: typeof value.device_eui === 'string' ? value.device_eui : null }
}
function stateMetadata(value: Record<string, unknown>): AlarmTraceState { return pick<AlarmTraceState>(value, ['physical_state', 'response_state', 'conclusion']) }
export function alarmResponse(text: string): Alarm { return alarmMetadata(JSON.parse(text)) }
export function alarmsResponse(text: string): Paginated<Alarm> { const parsed = JSON.parse(text); return { items: parsed.items.map(alarmMetadata), total: parsed.total, page: parsed.page, size: parsed.size } }
export function alarmWorkbenchResponse(text: string): AlarmWorkbench {
  const parsed = JSON.parse(text)
  return { items: parsed.items.map(alarmMetadata), total: parsed.total, page: parsed.page, size: parsed.size, as_of: parsed.as_of, timezone: parsed.timezone,
    filters: pick<AlarmWorkbenchFilters>(parsed.filters, ['keyword', 'physical_state', 'response_state', 'conclusion', 'occurred_from', 'occurred_to']),
    metrics: pick<AlarmWorkbenchMetrics>(parsed.metrics, ['alarm_total', 'unhandled', 'handled', 'unrecovered', 'recovered', 'true_fire']),
    metric_definitions: pick<AlarmMetricDefinitions>(parsed.metric_definitions, ['scope', 'time_field', 'time_bounds', 'unhandled', 'unrecovered', 'true_fire']) }
}
export function alarmHistoryResponse(text: string, audience: Audience): AlarmHistory {
  const parsed = JSON.parse(text)
  const items = parsed.items.map((value: Record<string, unknown>) => {
    const entry = pick<AlarmTraceEntry>(value, audience === 'management' ? [...entryKeys, 'actor_id', 'actor_name'] : entryKeys)
    if (Object.hasOwn(value, 'before')) entry.before = value.before === null ? null : stateMetadata(value.before as Record<string, unknown>)
    if (Object.hasOwn(value, 'after')) entry.after = stateMetadata(value.after as Record<string, unknown>)
    // Customer labels are a closed safe projection, not arbitrary names/usernames.
    if (audience === 'customer') {
      const kinds: Record<string, string> = { PLATFORM: '平台人员', CUSTOMER: '单位人员', SYSTEM: '系统处理', LEGACY_UNKNOWN: '历史未知' }
      const expected = typeof entry.is_self !== 'boolean' ? undefined : entry.is_self === true ? entry.actor_kind === 'CUSTOMER' ? '本人' : undefined : kinds[entry.actor_kind]
      if (!customerLabels.includes(entry.actor_label as typeof customerLabels[number]) || entry.actor_label !== expected) entry.actor_label = '身份标签未确认'
    }
    return entry
  })
  return { items, total: parsed.total, page: parsed.page, size: parsed.size, coverage: pick<AlarmCoverage>(parsed.coverage, ['kind', 'first_revision', 'journal_through_revision', 'current_revision', 'entry_total', 'baseline_recorded_at']) }
}
