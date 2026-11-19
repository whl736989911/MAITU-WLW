import { ApiError } from './api'
import { useSession } from './store'
import { parseJsonDocument, jsonArrayItems, jsonFieldText } from './json-document'
import { integerToken } from './parameter-json'
export const groupNameMax = 200, groupDescriptionMax = 1000
export interface DeviceGroup { id: string; tenant_id: string; name: string; description: string; revision: number; member_count: number }
export interface GroupDevice { device_id: string; device_name: string; device_eui: string; address: string }
export interface GroupCandidate extends GroupDevice { is_member: boolean }
export interface GroupPage { items: DeviceGroup[]; total: number; page: number; size: number }
export interface GroupMemberPage { group: DeviceGroup; items: GroupDevice[]; total: number; page: number; size: number }
export interface GroupCandidatePage { group: DeviceGroup; items: GroupCandidate[]; total: number; page: number; size: number }
export interface GroupQuery { page: number; keyword: string; size?: number }
export type GroupCommand = { kind: 'create'; tenant_id: string; name: string; description: string } | { kind: 'edit'; group: DeviceGroup; name: string; description: string } | { kind: 'add' | 'remove'; group: DeviceGroup; device_id: string }
export class GroupWriteUnconfirmed extends ApiError { constructor(status = 0) { super(status, 'DEVICE_GROUP_WRITE_UNCONFIRMED', '本次提交结果未确认，不是失败。保留当前表单并再次提交会沿用原业务键；取消不会撤销已提交业务。') } }
const invalid = (): never => { throw new ApiError(400, 'DEVICE_GROUP_INPUT_INVALID', '分组输入未确认，请核对文本、目标和当前版本。') }
const unreadable = (): never => { throw new ApiError(200, 'DEVICE_GROUP_READ_UNCONFIRMED', '分组读取未确认；不保留旧数据、不推断空集合或0。') }
export function validGroupText(value: unknown): value is string { return typeof value === 'string' && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(value) }
const isoControl = /[\u0000-\u001F\u007F-\u009F]/
export const javaGroupTrim = (value: string) => value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')
export function groupMetadata(name: string, description: string) {
  if (!validGroupText(name) || isoControl.test(name) || !validGroupText(description) || description.includes('\0')) return invalid()
  const n = javaGroupTrim(name), d = javaGroupTrim(description)
  if (!n || n.length > groupNameMax || d.length > groupDescriptionMax) return invalid()
  return { name: n, description: d }
}
export function groupKeyword(value: string) { if (!validGroupText(value) || value.includes('\0')) return invalid(); const normalized = value.trim(); if (normalized.length > 100) return invalid(); return normalized }
export function groupUuid(value: unknown): value is string { return typeof value === 'string' && value.length === 36 && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value) }
export function groupDeviceId(value: unknown): value is string { return validGroupText(value) && value.length > 0 && value.length <= 64 && !isoControl.test(value) }
function exact(value: unknown, keys: string[]): asserts value is Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value, k))) unreadable() }
function field(raw: string, key: string) { const value = jsonFieldText(raw, key); if (value === undefined) return unreadable(); return value }
function integer(raw: string, key: string, min = 0, max = Number.MAX_SAFE_INTEGER) { const token = field(raw, key); if (!/^(?:0|[1-9]\d*)$/.test(token)) return unreadable(); const n = integerToken(token); if (!Number.isSafeInteger(n) || n < min || n > max) return unreadable(); return n }
function group(raw: string, tenant?: string, id?: string): DeviceGroup {
  const v = parseJsonDocument(raw); exact(v, ['id', 'tenant_id', 'name', 'description', 'revision', 'member_count'])
  if (!groupUuid(v.id) || typeof v.tenant_id !== 'string' || v.tenant_id.length === 0 || tenant !== undefined && v.tenant_id !== tenant || id !== undefined && v.id !== id || typeof v.name !== 'string' || typeof v.description !== 'string') return unreadable()
  const m = groupMetadata(v.name, v.description); if (m.name !== v.name || m.description !== v.description) return unreadable()
  return { id: v.id, tenant_id: v.tenant_id, ...m, revision: integer(raw, 'revision'), member_count: integer(raw, 'member_count') }
}
function device(raw: string, candidate: boolean): GroupDevice | GroupCandidate {
  const v = parseJsonDocument(raw); exact(v, ['device_id', 'device_name', 'device_eui', 'address', ...(candidate ? ['is_member'] : [])])
  if (!groupDeviceId(v.device_id) || !validGroupText(v.device_name) || v.device_name.length > 200 || !validGroupText(v.device_eui) || v.device_eui.length > 64 || !validGroupText(v.address) || v.address.length > 500 || candidate && typeof v.is_member !== 'boolean') return unreadable()
  const result = { device_id: v.device_id, device_name: v.device_name, device_eui: v.device_eui, address: v.address }
  return candidate ? { ...result, is_member: v.is_member as boolean } : result
}
function query(input: GroupQuery): GroupQuery { if (!Number.isSafeInteger(input.page) || input.page < 1 || input.page > 1000000 || input.size !== undefined && (!Number.isSafeInteger(input.size) || input.size < 1 || input.size > 100)) return invalid(); return { page: input.page, keyword: groupKeyword(input.keyword), ...(input.size === undefined ? {} : { size: input.size }) } }
function paging(raw: string, input: GroupQuery, length: number) { const total = integer(raw, 'total'), page = integer(raw, 'page', 1, 1000000), size = integer(raw, 'size', 1, 100); if (page !== input.page || input.size !== undefined && size !== input.size || length !== Math.min(size, Math.max(total - (page - 1) * size, 0))) return unreadable(); return { total, page, size } }
export function readGroupPage(raw: string, input: GroupQuery, tenant?: string): GroupPage {
  try { const v = parseJsonDocument(raw); exact(v, ['items', 'total', 'page', 'size']); const items = jsonArrayItems(field(raw, 'items')).map(r => group(r, tenant)); const ids = items.map(r => r.id); if (new Set(ids).size !== ids.length || ids.some((id, i) => i > 0 && id <= ids[i - 1])) return unreadable(); return { items, ...paging(raw, query(input), items.length) } } catch { return unreadable() }
}
export function readGroupMembers(raw: string, input: GroupQuery, tenant: string | undefined, id: string): GroupMemberPage {
  try { const v = parseJsonDocument(raw); exact(v, ['group', 'items', 'total', 'page', 'size']); const g = group(field(raw, 'group'), tenant, id), items = jsonArrayItems(field(raw, 'items')).map(r => device(r, false) as GroupDevice), p = paging(raw, query(input), items.length); if (new Set(items.map(r => r.device_id)).size !== items.length || p.total > g.member_count) return unreadable(); return { group: g, items, ...p } } catch { return unreadable() }
}
export function readGroupCandidates(raw: string, input: GroupQuery, tenant: string | undefined, id: string): GroupCandidatePage {
  try { const v = parseJsonDocument(raw); exact(v, ['group', 'items', 'total', 'page', 'size']); const g = group(field(raw, 'group'), tenant, id), items = jsonArrayItems(field(raw, 'items')).map(r => device(r, true) as GroupCandidate), p = paging(raw, query(input), items.length); if (new Set(items.map(r => r.device_id)).size !== items.length || items.filter(r => r.is_member).length > g.member_count) return unreadable(); return { group: g, items, ...p } } catch { return unreadable() }
}
export function normalizedGroupCommand(command: GroupCommand) {
  if (command.kind === 'create') return { kind: command.kind, body: groupMetadata(command.name, command.description), path: '', method: 'POST' }
  if (!['edit', 'add', 'remove'].includes(command.kind) || !command.group || !groupUuid(command.group.id) || !Number.isSafeInteger(command.group.revision) || command.group.revision < 0 || command.group.revision >= Number.MAX_SAFE_INTEGER || !Number.isSafeInteger(command.group.member_count) || command.group.member_count < 0 || command.kind === 'add' && command.group.member_count >= Number.MAX_SAFE_INTEGER) return invalid()
  if (command.kind === 'edit') return { kind: command.kind, body: { ...groupMetadata(command.name, command.description), expected_revision: command.group.revision }, path: `/${command.group.id}`, method: 'PUT' }
  if (!groupDeviceId(command.device_id) || command.kind === 'remove' && command.group.member_count < 1) return invalid()
  return { kind: command.kind, body: { device_id: command.device_id, expected_revision: command.group.revision }, path: `/${command.group.id}/members${command.kind === 'remove' ? '/remove' : ''}`, method: 'POST' }
}
export function groupCommandIdentity(command: GroupCommand, tenantId: string) { if (!tenantId || command.kind !== 'create' && command.group.tenant_id !== tenantId) return invalid(); const c = normalizedGroupCommand(command); return JSON.stringify([tenantId, c.kind, c.path, c.body]) }
function owner(includeReadScope = true) { const s = useSession.getState(), management = s.audience === 'management', match = !!s.user && (management ? s.user.user_type === 'PLATFORM' : s.user.user_type === 'CUSTOMER'); return { token: s.token, user: JSON.stringify(s.user), generation: s.generation, permissions: JSON.stringify(s.permissions), audience: s.audience, scopeTenantIds: includeReadScope ? JSON.stringify(s.scopeTenantIds) : undefined, scopePlaceIds: includeReadScope ? JSON.stringify(s.scopePlaceIds) : undefined, scopeInitialized: includeReadScope ? s.scopeInitialized : undefined, page: s.page, read: match && !s.user?.must_change_password && s.permissions.includes('device:read') && s.page === 'device-groups', write: management && match && !s.user?.must_change_password && s.permissions.includes('device:read') && s.permissions.includes('device:write') && s.page === 'device-groups' } }
export function captureGroupScope(includeReadScope = true) { const captured = JSON.stringify(owner(includeReadScope)); return () => JSON.stringify(owner(includeReadScope)) === captured }
function cancelled(): never { throw new DOMException('分组操作已失效', 'AbortError') }
async function exchange<T>(path: string, signal: AbortSignal, live: () => boolean, decode: (raw: string, tenant?: string) => T, write?: { method: string; body: object; key: string }, targetTenant?: string) {
  const before = owner(!write), current = () => !signal.aborted && live() && JSON.stringify(owner(!write)) === JSON.stringify(before)
  if (!current()) return cancelled()
  if (!before.token || !before.read || write && (!before.write || !targetTenant)) throw new ApiError(403, 'DEVICE_GROUP_SCOPE_UNCONFIRMED', '当前无设备分组读取或维护范围。')
  const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${before.token}` }); if (write && targetTenant) headers.set('X-Tenant-Id', targetTenant)
  if (write) { headers.set('Content-Type', 'application/json'); headers.set('Idempotency-Key', write.key) }
  let response: Response
  try { response = await fetch(`/api/${before.audience}/device-groups${path}`, { method: write?.method ?? 'GET', headers, signal, credentials: 'omit', cache: 'no-store', redirect: 'error', ...(write ? { body: JSON.stringify(write.body) } : {}) }) } catch { if (!current()) return cancelled(); if (write) throw new GroupWriteUnconfirmed(); throw new ApiError(0, 'DEVICE_GROUP_READ_UNCONFIRMED', '分组网络读取未确认，请显式刷新。') }
  if (!current()) return cancelled(); const status = response.status; if (!current()) return cancelled()
  if (status === 401) { useSession.getState().clear('登录已失效，请重新登录。'); throw new ApiError(401, 'DEVICE_GROUP_SESSION_EXPIRED', '登录已失效，请重新登录。') }
  if (status !== 200) { if (write && (status >= 500 || status >= 200 && status < 300)) throw new GroupWriteUnconfirmed(status); throw new ApiError(status, status === 409 ? 'DEVICE_GROUP_CONFLICT' : 'DEVICE_GROUP_READ_UNCONFIRMED', status === 409 ? '分组提交冲突：可能是本单位已有同名分组，或记录已变更。请刷新后重新打开表单。' : '当前分组操作未获核验；不保留旧组、成员或候选，请显式刷新。') }
  if (!current()) return cancelled(); let raw: string
  try { raw = await response.text() } catch { if (!current()) return cancelled(); if (write) throw new GroupWriteUnconfirmed(status); return unreadable() }
  if (!current()) return cancelled(); let result: T
  try { result = decode(raw, targetTenant) } catch { if (!current()) return cancelled(); if (write) throw new GroupWriteUnconfirmed(status); return unreadable() }
  if (!current()) return cancelled(); return result
}
const search = (input: GroupQuery, tenantIds = useSession.getState().scopeTenantIds, placeIds = useSession.getState().scopePlaceIds) => { const q = query(input), p = new URLSearchParams({ page: String(q.page) }); if (useSession.getState().audience === 'management') { p.set('scope_selected', 'true'); p.set('scope_tenant_ids', tenantIds.join(',')); p.set('scope_place_ids', placeIds.join(',')) } if (q.keyword) p.set('keyword', q.keyword); if (q.size !== undefined) p.set('size', String(q.size)); return `?${p}` }
export const getDeviceGroups = (input: GroupQuery, signal: AbortSignal, live: () => boolean, tenantIds = useSession.getState().scopeTenantIds, placeIds = useSession.getState().scopePlaceIds) => exchange(search(input, tenantIds, placeIds), signal, live, raw => readGroupPage(raw, input))
export function getGroupMembers(id: string, input: GroupQuery, signal: AbortSignal, live: () => boolean, tenantIds = useSession.getState().scopeTenantIds, placeIds = useSession.getState().scopePlaceIds) { if (!groupUuid(id)) return Promise.reject(new ApiError(404, 'DEVICE_GROUP_NOT_FOUND', '分组未确认或不在当前授权范围。')); return exchange(`/${id}/members${search(input, tenantIds, placeIds)}`, signal, live, raw => readGroupMembers(raw, input, undefined, id)) }
export function getGroupCandidates(id: string, input: GroupQuery, signal: AbortSignal, live: () => boolean, tenantIds = useSession.getState().scopeTenantIds, placeIds = useSession.getState().scopePlaceIds) { if (!groupUuid(id)) return Promise.reject(new ApiError(404, 'DEVICE_GROUP_NOT_FOUND', '分组未确认或不在当前授权范围。')); if (!owner().write) return Promise.reject(new ApiError(403, 'DEVICE_GROUP_SCOPE_UNCONFIRMED', '当前无候选维护范围。')); return exchange(`/${id}/candidates${search(input, tenantIds, placeIds)}`, signal, live, raw => readGroupCandidates(raw, input, undefined, id)) }
export async function submitGroupCommand(command: GroupCommand, tenantId: string, key: string, signal: AbortSignal, live: () => boolean): Promise<DeviceGroup> {
  const c = normalizedGroupCommand(command)
  if (!tenantId || command.kind === 'create' && command.tenant_id !== tenantId || command.kind !== 'create' && command.group.tenant_id !== tenantId || !groupUuid(key)) return invalid()
  return exchange(c.path, signal, live, (raw, tenant) => { const result = group(raw, tenant, command.kind === 'create' ? undefined : command.group.id), metadata = command.kind === 'create' || command.kind === 'edit' ? groupMetadata(command.name, command.description) : command.group; if (command.kind !== 'create' && command.group.tenant_id !== tenant || result.name !== metadata.name || result.description !== metadata.description || result.revision !== (command.kind === 'create' ? 0 : command.group.revision + 1) || result.member_count !== (command.kind === 'create' ? 0 : command.group.member_count + (command.kind === 'add' ? 1 : command.kind === 'remove' ? -1 : 0))) return unreadable(); return result }, { method: c.method, body: c.body, key }, tenantId)
}
