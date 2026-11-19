import { request } from './api'
import { useSession } from './store'
import type { Paginated } from './types'
import { parseJsonDocument } from './json-document'

export type AccessRole = { id: string; code: string; name: string; user_type: 'PLATFORM' | 'CUSTOMER'; enabled: boolean; deleted: boolean; revision: number; permissions: string[] }
export type UserAccess = { user_id: string; user_type: 'PLATFORM' | 'CUSTOMER'; revision: number; direct_permissions: string[]; effective_permissions: string[]; roles: AccessRole[] }
export type DeviceScope = { user_id: string; tenant_id: string; configured: boolean; mode: 'ALL_TENANT' | 'DEVICE_SET' | 'PLACE_SUBTREE' | null; revision: number; device_ids: string[]; place_ids: string[] }
export type RolePage = Paginated<AccessRole>

function invalid(): never { throw new Error('授权服务响应格式无效') }
function record(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const row = value as Record<string, unknown>
  if (Object.keys(row).length !== keys.length || keys.some(key => !Object.hasOwn(row, key))) return invalid()
  return row
}
function str(value: unknown): string { return typeof value === 'string' ? value : invalid() }
function bool(value: unknown): boolean { return typeof value === 'boolean' ? value : invalid() }
function int(value: unknown): number { return Number.isSafeInteger(value) && !Object.is(value, -0) && (value as number) >= 0 ? value as number : invalid() }
function strings(value: unknown): string[] { if (!Array.isArray(value) || value.some(v => typeof v !== 'string')) return invalid(); return [...value] }
function role(value: unknown): AccessRole {
  const r = record(value, ['id', 'code', 'name', 'user_type', 'enabled', 'deleted', 'revision', 'permissions'])
  if (r.user_type !== 'PLATFORM' && r.user_type !== 'CUSTOMER') return invalid()
  return { id: str(r.id), code: str(r.code), name: str(r.name), user_type: r.user_type, enabled: bool(r.enabled), deleted: bool(r.deleted), revision: int(r.revision), permissions: strings(r.permissions) }
}
function page(value: unknown): RolePage {
  const p = record(value, ['items', 'total', 'page', 'size'])
  if (!Array.isArray(p.items)) return invalid()
  const total = int(p.total), pageNo = int(p.page), size = int(p.size)
  if (pageNo < 1 || size < 1 || size > 100) return invalid()
  return { items: p.items.map(role), total, page: pageNo, size }
}
function userAccess(value: unknown): UserAccess {
  const a = record(value, ['user_id', 'user_type', 'revision', 'direct_permissions', 'effective_permissions', 'roles'])
  if ((a.user_type !== 'PLATFORM' && a.user_type !== 'CUSTOMER') || !Array.isArray(a.roles)) return invalid()
  return { user_id: str(a.user_id), user_type: a.user_type, revision: int(a.revision), direct_permissions: strings(a.direct_permissions), effective_permissions: strings(a.effective_permissions), roles: a.roles.map(role) }
}
function scope(value: unknown): DeviceScope {
  const s = record(value, ['user_id', 'tenant_id', 'configured', 'mode', 'revision', 'device_ids', 'place_ids'])
  if (s.mode !== null && s.mode !== 'ALL_TENANT' && s.mode !== 'DEVICE_SET' && s.mode !== 'PLACE_SUBTREE') return invalid()
  const result: DeviceScope = { user_id: str(s.user_id), tenant_id: str(s.tenant_id), configured: bool(s.configured), mode: s.mode, revision: int(s.revision), device_ids: strings(s.device_ids), place_ids: strings(s.place_ids) }
  if (!result.configured && (result.mode !== null || result.revision !== 0 || result.device_ids.length || result.place_ids.length)) return invalid()
  if (result.mode === 'ALL_TENANT' && (result.device_ids.length || result.place_ids.length)) return invalid()
  if (result.mode === 'DEVICE_SET' && result.place_ids.length) return invalid()
  if (result.mode === 'PLACE_SUBTREE' && (result.device_ids.length || result.place_ids.length === 0)) return invalid()
  if (result.configured !== (result.mode !== null)) return invalid()
  return result
}
function decoded<T>(text: string, parser: (v: unknown) => T): T {
  try {
    const value = parseJsonDocument(text)
    // Every numeric field in these contracts is a nonnegative safe integer; inspect its original token before rounding.
    for (const match of text.matchAll(/"(?:\\.|[^"\\])*"|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g)) {
      const token = match[1]
      if (token !== undefined && (!/^(?:0|[1-9]\d*)$/.test(token) || !Number.isSafeInteger(Number(token)))) return invalid()
    }
    return parser(value)
  } catch { return invalid() }
}
const q = (page: number, size: number) => new URLSearchParams({ page: String(page), size: String(size) })
function baseUser(platform: boolean, id: string) { return `${platform ? '/management/platform-users' : `/${useSession.getState().audience}/users`}/${encodeURIComponent(id)}` }
export const accessApi = {
  assignedUsers: (roleId: string, pageNo = 1, size = 20, signal?: AbortSignal) => request<Paginated<{id:string;username:string;name:string;department:string;tenant:string;enabled:boolean}>>(`/management/roles/${encodeURIComponent(roleId)}/users?${q(pageNo,size)}`, { signal }),
  roles: (userType: 'PLATFORM' | 'CUSTOMER', pageNo = 1, size = 20, signal?: AbortSignal, keyword = '') => request<RolePage>(`/management/roles?${new URLSearchParams({ user_type: userType, page: String(pageNo), size: String(size), keyword })}`, { signal, decode: text => decoded(text, page) }),
  customerRoles: (pageNo = 1, size = 20, signal?: AbortSignal) => request<RolePage>(`/customer/roles?${q(pageNo, size)}`, { signal, decode: text => decoded(text, page) }),
  createRole: (body: { code: string; name: string; user_type: 'PLATFORM' | 'CUSTOMER'; enabled: boolean; permissions: string[]; reason: string }, key: string, signal?: AbortSignal) => request<AccessRole>('/management/roles', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => decoded(text, role) }),
  updateRole: (id: string, body: { name: string; enabled: boolean; permissions: string[]; expected_revision: number; reason: string }, key: string, signal?: AbortSignal) => request<AccessRole>(`/management/roles/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => decoded(text, role) }),
  deleteRole: (id: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal) => request<AccessRole>(`/management/roles/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => decoded(text, role) }),
  userAccess: (platform: boolean, id: string, signal?: AbortSignal, tenantId?: string) => request<UserAccess>(`${baseUser(platform, id)}/access`, { tenantScoped: !platform, tenantId, signal, decode: text => decoded(text, userAccess) }),
  assignRoles: (platform: boolean, id: string, body: { role_ids: string[]; expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<UserAccess>(`${baseUser(platform, id)}/access/roles`, { method: 'PUT', tenantScoped: !platform, tenantId, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => decoded(text, userAccess) }),
  userScope: (platform: boolean, id: string, signal?: AbortSignal, tenantId?: string) => request<DeviceScope>(`${baseUser(platform, id)}/scope`, { tenantScoped: true, tenantId, signal, decode: text => decoded(text, scope) }),
  updateUserScope: (platform: boolean, id: string, body: { mode: 'ALL_TENANT' | 'DEVICE_SET' | 'PLACE_SUBTREE'; device_ids: string[]; place_ids: string[]; expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<DeviceScope>(`${baseUser(platform, id)}/scope`, { method: 'PUT', tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => decoded(text, scope) }),
}
export const accessDecoders = { role: (value: unknown) => role(value), page: (value: unknown) => page(value), userAccess: (value: unknown) => userAccess(value), scope: (value: unknown) => scope(value) }
