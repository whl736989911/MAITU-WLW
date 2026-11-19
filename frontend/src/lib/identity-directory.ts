import { request } from './api'
import { useSession } from './store'
import type { Paginated } from './types'

export type IdentityDepartment = { id: string; tenant_id: string; parent_id: string | null; name: string; enabled: boolean; revision: number }
export type IdentityUserProfile = { id: string; username: string; name: string; tenant_id: string; tenant_name: string; department_id: string | null; department_name: string | null; phone: string; contact_email: string; valid_until: string | null; long_term: boolean; enabled: boolean; revision: number }
export type UserMetadataDraft = { department_id: string | null; phone: string; contact_email: string; long_term: boolean; valid_until: string | null; expected_revision: number }
const base = () => `/${useSession.getState().audience}`
export const identityDirectoryApi = {
  departments: (signal?: AbortSignal, tenantId?: string) => request<IdentityDepartment[]>(`${base()}/identity/departments`, { tenantScoped: true, tenantId, signal }),
  saveDepartment: (id: string | null, body: { name: string; parent_id: string | null; enabled: boolean; expected_revision: number }, key: string, signal?: AbortSignal, tenantId?: string) => request<IdentityDepartment>(`${base()}/identity/departments${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PUT' : 'POST', tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
  users: (page: number, keyword = '', signal?: AbortSignal, tenantIds?: string[], placeIds?: string[], tenantId?: string, departmentId?: string, enabled?: boolean, size?: number) => {
    const params = new URLSearchParams({ page: String(page), keyword })
    if (size !== undefined) params.set('size',String(size))
    if (tenantIds !== undefined || placeIds !== undefined) { params.set('scope_selected', 'true'); params.set('scope_tenant_ids', (tenantIds ?? []).join(',')); params.set('scope_place_ids', (placeIds ?? []).join(',')) }
    if (departmentId) params.set('department_id', departmentId)
    if (enabled !== undefined) params.set('enabled', String(enabled))
    return request<Paginated<IdentityUserProfile>>(`${base()}/identity/users?${params}`, { tenantScoped: true, tenantOptional: true, tenantId, signal })
  },
  updateUser: (id: string, body: UserMetadataDraft, key: string, signal?: AbortSignal, tenantId?: string) => request<IdentityUserProfile>(`${base()}/identity/users/${encodeURIComponent(id)}`, { method: 'PUT', tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
}
