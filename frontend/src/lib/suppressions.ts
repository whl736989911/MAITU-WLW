import { request, scopeQuery } from './api'
import type { Paginated, SuppressionRecord } from './types'

export type SuppressionScope = 'TENANT' | 'DEVICES' | 'SENSORS'
export type SuppressionEffectiveState = SuppressionRecord['effective_state']
export type Suppression = SuppressionRecord
export interface SuppressionTarget { channel_id: string; device_id: string; channel_key: string; channel_name: string; channel_type: string; device_name: string }
export interface SuppressionHistoryEvent { id: string; action: string; actor_id: string; outcome: string; request_id: string; occurred_at: string; reason: string | null; snapshot_json: string | null }
export interface SuppressionPageQuery { page: number; size?: number; scope: SuppressionScope; status?: string; scope_tenant_ids: string[]; scope_place_ids: string[] }
export interface SuppressionCreate { scope: SuppressionScope; device_ids?: string[]; channel_ids?: string[]; fire_confirmed: boolean; reason: string; starts_at: string; ends_at: string }
export interface SuppressionMutation { expected_revision: number; reason: string }
const json = (body: unknown) => JSON.stringify(body)
export function listSuppressions(query: SuppressionPageQuery, signal?: AbortSignal): Promise<Paginated<Suppression>> {
  const params = new URLSearchParams({ page: String(query.page), size: String(query.size ?? 20), scope: query.scope })
  if (query.status) params.set('status', query.status)
  return request<Paginated<Suppression>>(scopeQuery(`/management/suppressions?${params}`, query.scope_tenant_ids, query.scope_place_ids), { tenantScoped: true, tenantOptional: true, signal })
}
export function getSuppressionTargets(id: string, tenantId: string, signal?: AbortSignal): Promise<SuppressionTarget[]> {
  return request<SuppressionTarget[]>(`/management/suppressions/${encodeURIComponent(id)}/targets`, { tenantScoped: true, tenantId, signal })
}
export function getSuppressionHistory(id: string, tenantId: string, signal?: AbortSignal): Promise<SuppressionHistoryEvent[]> {
  return request<SuppressionHistoryEvent[]>(`/management/suppressions/${encodeURIComponent(id)}/history`, { tenantScoped: true, tenantId, signal })
}
function mutate<T>(path: string, body: unknown, key: string, tenantId: string, signal?: AbortSignal, method = 'POST'): Promise<T> {
  return request<T>(path, { tenantScoped: true, tenantId, method, headers: { 'Idempotency-Key': key }, body: json(body), signal })
}
export function createSuppression(body: SuppressionCreate, key: string, tenantId: string, signal?: AbortSignal): Promise<Suppression> {
  return mutate('/management/suppressions', body, key, tenantId, signal)
}
export function updateSuppression(id: string, body: SuppressionMutation & { starts_at: string; ends_at: string }, key: string, tenantId: string, signal?: AbortSignal): Promise<Suppression> {
  return mutate(`/management/suppressions/${encodeURIComponent(id)}`, body, key, tenantId, signal, 'PUT')
}
export function setSuppressionEnabled(id: string, body: SuppressionMutation & { enabled: boolean }, key: string, tenantId: string, signal?: AbortSignal): Promise<Suppression> {
  return mutate(`/management/suppressions/${encodeURIComponent(id)}/enabled`, body, key, tenantId, signal)
}
export function cancelSuppression(id: string, body: SuppressionMutation, key: string, tenantId: string, signal?: AbortSignal): Promise<Suppression> {
  return mutate(`/management/suppressions/${encodeURIComponent(id)}/cancel`, body, key, tenantId, signal)
}
export function deleteSuppression(id: string, body: SuppressionMutation, key: string, tenantId: string, signal?: AbortSignal): Promise<Suppression> {
  return mutate(`/management/suppressions/${encodeURIComponent(id)}`, body, key, tenantId, signal, 'DELETE')
}
