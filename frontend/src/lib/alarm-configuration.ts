import { request } from './api'

export type AlarmBaseValue = {
  subscriptions: { FIRE: Record<string, string[]>; FAULT: Record<string, string[]> }
  pushRules: { periodHours: number }
  voiceBroadcast: { alarmEnabled: boolean; alarmTypes: string[]; alarmFields: string[]; faultEnabled: boolean; faultTypes: string[]; faultFields: string[]; continuous: boolean }
  unitService: { receiveAlarm: boolean; pushMessage: boolean }
  silenceIntervals: { alarmMinutes: number; faultMinutes: number }
}
export type AlarmBaseConfiguration = { tenant_id: string; revision: number; value: AlarmBaseValue; updated_at: string | null }
export type AlarmEventKind = 'FIRE' | 'FAULT'
export type AlarmConfigurationScope = 'UNIT' | 'PLACE' | 'DEVICE' | 'PLATFORM'
export type AlarmOverrideScope = Exclude<AlarmConfigurationScope, 'UNIT'>
export type AlarmScopedValue = {
  subscriptions: Record<string, string[]>
  receive_alarm: boolean
  push_message: boolean
  push_period_hours: number
  voice_enabled: boolean
  voice_types: string[]
  voice_fields: string[]
  voice_continuous: boolean
  silence_minutes: number
}
export type AlarmScopedConfiguration = {
  event_kind: AlarmEventKind
  scope: AlarmConfigurationScope
  scope_id: string
  tenant_id: string | null
  revision: number
  value?: AlarmScopedValue | null
  effective: {
    source_scope: AlarmConfigurationScope
    source_id: string
    source_revision: number
    value: AlarmScopedValue
    unit_receive_alarm: boolean
    unit_push_message: boolean
  }
}
type AlarmScopedWrite = { expected_revision: number; reason: string; value: AlarmScopedValue }
type AlarmScopedClear = { expected_revision: number; reason: string }
function alarmScopedPath(event: AlarmEventKind, scope: AlarmOverrideScope, targetId: string, tenantId?: string, action = '') {
  const path = `/management/alarm-base-configuration/${event}/scopes/${scope}/${encodeURIComponent(targetId)}${action}`
  return (scope === 'DEVICE' || scope === 'PLACE') && tenantId ? `${path}?${new URLSearchParams({ tenant_id: tenantId })}` : path
}
function alarmScopedOptions(scope: AlarmOverrideScope, tenantId?: string, signal?: AbortSignal) {
  return { tenantScoped: scope !== 'PLATFORM', tenantId, signal }
}
export type AlarmConfigurationOutcome = { id: string; tenant_id: string; alarm_id: string | null; event_type: string; configuration_revision: number; outcome_kind: string; outcome: string; detail_json: string; occurred_at: string }
export const alarmBaseApi = {
  save: (body: { expected_revision: number; reason: string; value: AlarmBaseValue }, key: string, signal?: AbortSignal, tenantId?: string) => request<AlarmBaseConfiguration>('/management/alarm-base-configuration', { tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, method: 'PUT', body: JSON.stringify(body), signal }),
  get: (signal?: AbortSignal, tenantId?: string) => request<AlarmBaseConfiguration>('/management/alarm-base-configuration', { tenantScoped: true, tenantId, signal }),
  outcomes: (sourceId: string, signal?: AbortSignal, tenantId?: string) => request<AlarmConfigurationOutcome[]>(`/management/alarm-base-configuration/outcomes/${encodeURIComponent(sourceId)}`, { tenantScoped: true, tenantId, signal }),
  getScoped: (event: AlarmEventKind, scope: AlarmOverrideScope, targetId: string, signal?: AbortSignal, tenantId?: string) =>
    request<AlarmScopedConfiguration>(alarmScopedPath(event, scope, targetId, tenantId), alarmScopedOptions(scope, tenantId, signal)),
  saveScoped: (event: AlarmEventKind, scope: AlarmOverrideScope, targetId: string, body: AlarmScopedWrite, key: string, signal?: AbortSignal, tenantId?: string) =>
    request<AlarmScopedConfiguration>(alarmScopedPath(event, scope, targetId, tenantId), { ...alarmScopedOptions(scope, tenantId, signal), headers: { 'Idempotency-Key': key }, method: 'PUT', body: JSON.stringify(body) }),
  clearScoped: (event: AlarmEventKind, scope: AlarmOverrideScope, targetId: string, body: AlarmScopedClear, key: string, signal?: AbortSignal, tenantId?: string) =>
    request<AlarmScopedConfiguration>(alarmScopedPath(event, scope, targetId, tenantId, '/clear'), { ...alarmScopedOptions(scope, tenantId, signal), headers: { 'Idempotency-Key': key }, method: 'POST', body: JSON.stringify(body) }),
}
export type LinkageRule = { id: string; tenant_id: string; name: string; kind: 'CUSTOM' | 'DEFAULT_VIDEO' | 'DEFAULT_SOUND_LIGHT'; enabled: boolean; config: Record<string, unknown>; revision: number; updated_at: string | null }
export const resourceLinkageApi = {
  list: (signal?: AbortSignal, tenantId?: string) => request<{ items: LinkageRule[] }>('/management/resource-linkage', { tenantScoped: true, tenantId, signal }),
  saveDefault: (kind: 'DEFAULT_VIDEO' | 'DEFAULT_SOUND_LIGHT', body: { expected_revision: number; enabled: boolean; config: Record<string, unknown>; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<LinkageRule>(`/management/resource-linkage/defaults/${kind}`, { tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, method: 'POST', body: JSON.stringify(body), signal }),
  create: (body: { name: string; config: Record<string, unknown>; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<LinkageRule>('/management/resource-linkage/rules', { tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, method: 'POST', body: JSON.stringify(body), signal }),
  update: (id: string, body: { expected_revision: number; name: string; config: Record<string, unknown>; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<LinkageRule>(`/management/resource-linkage/rules/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, method: 'PUT', body: JSON.stringify(body), signal }),
  setEnabled: (id: string, body: { expected_revision: number; enabled: boolean; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<LinkageRule>(`/management/resource-linkage/rules/${encodeURIComponent(id)}/enabled`, { tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, method: 'POST', body: JSON.stringify(body), signal }),
  remove: (id: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<void>(`/management/resource-linkage/rules/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, method: 'DELETE', body: JSON.stringify(body), signal }),
  outcomes: (sourceId: string, signal?: AbortSignal, tenantId?: string) => request<ResourceLinkageOutcome[]>(`/management/resource-linkage/evaluations/${encodeURIComponent(sourceId)}`, { tenantScoped: true, tenantId, signal }),
}

export interface ResourceLinkageOutcome {
  id: string
  rule_id: string
  rule_name: string
  event_type: string
  outcome: 'MATCHED_SOFTWARE_ACTION' | 'LOCAL_COMMAND_INTENT' | 'UNSUPPORTED_EXTERNAL_ADAPTER' | 'NOT_MATCHED'
  detail: Record<string, unknown>
  occurred_at: string
}
