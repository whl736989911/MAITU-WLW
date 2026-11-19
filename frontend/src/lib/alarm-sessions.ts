import { request, scopeQuery } from './api'
import { useSession } from './store'
import type { Paginated } from './types'

export type AlarmMergePolicy = {
  tenant_id: string
  revision: number
  enabled: boolean
  window_seconds: number | null
  group_by: 'DEVICE_EVENT_TYPE'
  scope_kind: 'UNIT'
  updated_at: string | null
}
export type AlarmSession = {
  id: string
  tenant_id: string
  device_id: string
  device_name: string
  address: string
  event_type: 'FIRE'
  policy_revision: number
  policy_state: 'CURRENT' | 'STALE' | 'UNKNOWN'
  window_seconds: number
  first_source_at: string
  last_source_at: string
  open: boolean
  closed_at: string | null
  window_ends_at: string | null
  member_count: number
  active_member_count: number
  unhandled_member_count: number
  revision: number
  created_at: string
  updated_at: string
}
export type AlarmSessionClaims = { total: number; claimed: number; sent: number; unknown: number; failed: number }
export type AlarmSessionDetail = { session: AlarmSession; claims: AlarmSessionClaims }
export type AlarmSessionMember = {
  session_id: string
  alarm_id: string
  tenant_id: string
  device_id: string
  device_name: string
  address: string
  event_type: 'FIRE'
  source_at: string
  added_at: string
  occurred_at: string
  last_event_at: string | null
  occurrence_count: number
  physical_state: 'FIRE' | 'NORMAL'
  response_state: 'UNHANDLED' | 'HANDLED'
  conclusion: string | null
  revision: number
  simulated: boolean
}

const audiencePath = () => `/${useSession.getState().audience}/alarm-sessions`

export const alarmMergeApi = {
  get: (signal?: AbortSignal, tenantId?: string) =>
    request<AlarmMergePolicy>('/management/alarm-merge-policy', { tenantScoped: true, tenantId, signal }),
  history: (signal?: AbortSignal, tenantId?: string) =>
    request<AlarmMergePolicy[]>('/management/alarm-merge-policy/history', { tenantScoped: true, tenantId, signal }),
  save: (body: { expected_revision: number; enabled: boolean; window_seconds: number | null; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) =>
    request<AlarmMergePolicy>('/management/alarm-merge-policy', { tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, method: 'PUT', body: JSON.stringify(body), signal }),
}

export const alarmSessionsApi = {
  list: (page: number, size: number | undefined, signal?: AbortSignal, tenantIds?: string[], placeIds?: string[], deviceId?: string, eventType?: string) => {
    const params = new URLSearchParams({ page: String(page) })
    if (size !== undefined) params.set('size', String(size))
    if (deviceId) params.set('device_id', deviceId)
    if (eventType) params.set('event_type', eventType)
    return request<Paginated<AlarmSession>>(scopeQuery(`${audiencePath()}?${params}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal })
  },
  detail: (id: string, signal?: AbortSignal, tenantId?: string) =>
    request<AlarmSessionDetail>(`${audiencePath()}/${encodeURIComponent(id)}`, { tenantScoped: true, tenantOptional: true, tenantId, signal }),
  members: (id: string, page: number, size: number | undefined, signal?: AbortSignal, tenantId?: string) => {
    const params = new URLSearchParams({ page: String(page) })
    if (size !== undefined) params.set('size', String(size))
    return request<Paginated<AlarmSessionMember>>(`${audiencePath()}/${encodeURIComponent(id)}/members?${params}`, { tenantScoped: true, tenantOptional: true, tenantId, signal })
  },
}
