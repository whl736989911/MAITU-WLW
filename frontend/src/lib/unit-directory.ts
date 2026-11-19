import { request, scopeQuery } from './api'
import type { Paginated } from './types'

export interface UnitDetails { id: string; name: string; enabled: boolean; revision: number; archived_at: string | null; credit_code: string | null; region_id: string | null; unit_type: string | null; industry: string | null; map_address: string | null; detail_address: string | null; contact_name: string | null; contact_phone: string | null; remark: string | null; latitude: number | null; longitude: number | null; service_start: string | null; service_end: string | null; engineering_service_start: string | null; engineering_service_end: string | null }
export interface UnitWrite { name: string; credit_code: string | null; region_id: string | null; unit_type: string | null; industry: string | null; map_address: string | null; detail_address: string | null; contact_name: string | null; contact_phone: string | null; remark: string | null; latitude: number | null; longitude: number | null; service_start: string | null; service_end: string | null; engineering_service_start: string | null; engineering_service_end: string | null }
export interface UnitCreate extends UnitWrite { admin_username?: string; admin_name?: string; admin_initial_password?: string; admin_permissions?: string[] }
const json = (value: unknown) => JSON.stringify(value)
export const unitDirectoryApi = {
  list: (page: number, keyword: string, status: string, tenantIds?: string[], placeIds?: string[], signal?: AbortSignal) => {
    const q = new URLSearchParams({ page: String(page), keyword }); if (status) q.set('status', status)
    return request<Paginated<UnitDetails>>(scopeQuery(`/management/units?${q}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal })
  },
  detail: (id: string, signal?: AbortSignal) => request<UnitDetails>(`/management/units/${encodeURIComponent(id)}`, { signal }),
  create: (body: UnitCreate, key: string, signal?: AbortSignal) => request<UnitDetails>('/management/units', { method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  update: (id: string, body: UnitWrite & { expected_revision: number }, key: string, signal?: AbortSignal) => request<UnitDetails>(`/management/units/${encodeURIComponent(id)}/details`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  updateStatus: (id: string, body: { name: string; enabled: boolean; expected_revision: number }, key: string, signal?: AbortSignal) => request<{ id: string; name: string; enabled: boolean; revision: number }>(`/management/units/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  archive: (id: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal) => request<UnitDetails>(`/management/units/${encodeURIComponent(id)}/archive`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  deleteIfEmpty: (id: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal) => request<{ id: string; outcome: 'DELETED'; reason: string }>(`/management/units/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
}
