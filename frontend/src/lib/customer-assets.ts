import { ApiError, request } from './api'
import { useSession } from './store'
import { createMutationIntent } from './mutation-intent'
import { type CommissionBody, type CreateRegistrationBody } from './device-registrations'
import type { Device, DevicePlaceHistory, Paginated, PlaceNode, VersionHistory } from './types'

export interface CustomerUnit { id: string; name: string; places: PlaceNode[] }
export interface CustomerDriverOption { key: string; mode: 'VENDOR_PLATFORM'; display_name: string; configured: boolean; enabled: boolean; legacy_origin_account: boolean; imported_ledger_association: boolean }
export interface CustomerPublishedOption { id: string; product_id: string; product_name: string; manufacturer: string; model: string; version_number: number }
export interface DeviceBatchTarget { device_id: string; expected_revision: number }
export interface DeviceBatchResult { items: Device[] }
export interface CustomerRegistrationSummary { id: string; driver_key: string; mode: 'NEW_VENDOR' | 'MATCH_EXISTING' | 'PRODUCT_MODEL'; status: string; revision: number; device_id: string | null; external_serial: string; device_name: string | null; cloud_state: string; local_state: string; place_id: string | null; product_version_id: string | null; created_at: string; updated_at: string; commissioned_at: string | null }
export interface CustomerRegistrationEvent { step: string; outcome: string; occurred_at: string }
export interface CustomerRegistrationDetail { registration: CustomerRegistrationSummary; device_model: string | null; location: string | null; acceptance_note: string | null; vendor_payload_stored: boolean; events: CustomerRegistrationEvent[] }

function bad(message: string): never { throw new ApiError(200, 'CUSTOMER_ASSET_RESPONSE_UNCONFIRMED', message) }
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) return bad('响应结构未确认'); return value as Record<string, unknown> }
function text(value: unknown, nullable = false): string | null {
  if (value === null && nullable) return null
  if (typeof value !== 'string' || !value.trim()) return bad('响应文本字段未确认')
  return value
}
function integer(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) return bad('响应整数未确认')
  return value
}
function flag(value: unknown, message: string): boolean {
  if (typeof value !== 'boolean') return bad(message)
  return value
}
function ownTenant(): string {
  const session = useSession.getState()
  if (session.audience !== 'customer' || !session.user?.tenant_id) throw new ApiError(403, 'CUSTOMER_UNIT_REQUIRED', '此入口只维护当前客户单位')
  return session.user.tenant_id
}
function place(raw: unknown, tenant: string): PlaceNode {
  const row = object(raw)
  const id = text(row.id) as string, tenantId = text(row.tenant_id) as string, name = text(row.name) as string
  if (tenantId !== tenant) return bad('位置归属与当前单位不一致')
  const parent = text(row.parent_id, true), legacy = text(row.legacy_place_id, true), tenantName = text(row.tenant_name) as string
  return { id, tenant_id: tenantId, tenant_name: tenantName, name, parent_id: parent, legacy_place_id: legacy, device_count: integer(row.device_count), level: integer(row.level), revision: integer(row.revision) }
}
function placeList(raw: string, tenant: string): PlaceNode[] {
  const value: unknown = JSON.parse(raw)
  if (!Array.isArray(value)) return bad('位置列表未确认')
  return value.map(item => place(item, tenant))
}
function unitList(raw: string, tenant: string): CustomerUnit[] {
  const value: unknown = JSON.parse(raw)
  if (!Array.isArray(value) || value.length !== 1) return bad('单位位置树未确认')
  const row = object(value[0])
  if (text(row.id) !== tenant) return bad('单位位置树不属于当前单位')
  const places = Array.isArray(row.places) ? row.places.map(item => place(item, tenant)) : bad('单位位置树未确认')
  return [{ id: tenant, name: text(row.name) as string, places }]
}
export function decodeDevice(raw: string): Device {
  const tenant = ownTenant()
  const row = object(JSON.parse(raw))
  if (text(row.id) === null) return bad('设备响应未确认')
  if (row.tenant_id !== undefined && text(row.tenant_id) !== tenant) return bad('设备归属与当前单位不一致')
  if (row.revision === undefined) return bad('设备版本未确认')
  integer(row.revision)
  const device = row as unknown as Device
  if (device.place_id !== undefined && device.place_id !== null && text(device.place_id) === null) return bad('设备位置未确认')
  return device
}
function versionHistory(raw: string): { items: VersionHistory[] } {
  const row = object(JSON.parse(raw))
  if (!Array.isArray(row.items)) return bad('设备版本历史未确认')
  const items = row.items.map(value => {
    const item = object(value)
    return { id: text(item.id) as string, from_version_id: text(item.from_version_id, true), to_version_id: text(item.to_version_id) as string, reason: text(item.reason) as string, actor_id: text(item.actor_id) as string, occurred_at: text(item.occurred_at) as string }
  })
  return { items }
}
function placeHistory(raw: string, expectedPage: number): Paginated<DevicePlaceHistory> {
  const row = object(JSON.parse(raw))
  if (!Array.isArray(row.items) || integer(row.page, 1) !== expectedPage || integer(row.size, 1) < 1 || integer(row.total) < 0) return bad('设备位置历史未确认')
  const items = row.items.map((value): DevicePlaceHistory => {
    const item = object(value)
    const action = text(item.action)
    if (action !== 'REGISTER' && action !== 'MOVE') return bad('设备位置历史动作未确认')
    return { id: text(item.id) as string, action, from_place_id: text(item.from_place_id, true), from_place_name: text(item.from_place_name, true), to_place_id: text(item.to_place_id) as string, to_place_name: text(item.to_place_name) as string, revision_before: item.revision_before === null ? null : integer(item.revision_before), revision_after: integer(item.revision_after), actor_id: text(item.actor_id) as string, occurred_at: text(item.occurred_at) as string }
  })
  return { items, total: integer(row.total), page: integer(row.page, 1), size: integer(row.size, 1) }
}
function published(raw: string): Paginated<CustomerPublishedOption> {
  const row = object(JSON.parse(raw))
  if (!Array.isArray(row.items) || integer(row.page, 1) < 1 || integer(row.size, 1) < 1 || integer(row.total) < 0) return bad('已发布产品版本未确认')
  const items = row.items.map(value => {
    const item = object(value)
    return { id: text(item.id) as string, product_id: text(item.product_id) as string, product_name: text(item.product_name) as string, manufacturer: text(item.manufacturer) as string, model: text(item.model) as string, version_number: integer(item.version_number, 1) }
  })
  return { items, total: integer(row.total), page: integer(row.page, 1), size: integer(row.size, 1) }
}
function batchResult(raw: string): DeviceBatchResult {
  const tenant = ownTenant()
  const row = object(JSON.parse(raw))
  if (!Array.isArray(row.items)) return bad('批量设备结果未确认')
  const items = row.items.map(item => {
    const device = object(item)
    if (text(device.id) === null || text(device.tenant_id, true) !== tenant && device.tenant_id !== undefined || integer(device.revision) < 0) return bad('批量设备结果未确认')
    return device as unknown as Device
  })
  return { items }
}
function batchBody(targets: DeviceBatchTarget[]): void {
  if (targets.length < 1 || targets.length > 100 || targets.some(target => !target.device_id || !Number.isSafeInteger(target.expected_revision) || target.expected_revision < 0) || new Set(targets.map(target => target.device_id)).size !== targets.length) throw new Error('批量设备目标未确认；请选择同单位内且revision有效的1–100台设备。')
}

const post = <T,>(path: string, body: unknown, key: string, signal: AbortSignal | undefined, decode: (raw: string) => T) =>
  request<T>(path, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode })

export const customerAssetsApi = {
  scopeTree(signal?: AbortSignal) { const tenant = ownTenant(); return request<CustomerUnit[]>('/customer/scope-tree', { signal, decode: raw => unitList(raw, tenant) }) },
  places(signal?: AbortSignal) { const tenant = ownTenant(); return request<PlaceNode[]>('/customer/places', { signal, decode: raw => placeList(raw, tenant) }) },
  createPlace(body: { name: string; parent_place_id?: string | null }, key: string, signal?: AbortSignal) {
    const tenant = ownTenant()
    return post<PlaceNode>('/customer/places', body, key, signal, raw => place(JSON.parse(raw), tenant))
  },
  updatePlace(id: string, body: { name: string; parent_place_id?: string | null; expected_revision: number }, key: string, signal?: AbortSignal) {
    const tenant = ownTenant()
    return request<PlaceNode>(`/customer/places/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: raw => place(JSON.parse(raw), tenant) })
  },
  deletePlace(id: string, body: { expected_revision: number }, key: string, signal?: AbortSignal) {
    return request<void>(`/customer/places/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: () => undefined })
  },
  createDevice(body: { name: string; device_eui: string; address: string; place_id: string; product_version_id: string; system_type?: string | null; device_category?: string | null }, key: string, signal?: AbortSignal) {
    return post<Device>('/customer/devices', body, key, signal, decodeDevice)
  },
  updateDevice(id: string, body: { name: string; address: string; place_id?: string | null; system_type?: string | null; device_category?: string | null; expected_revision: number }, key: string, signal?: AbortSignal) {
    return request<Device>(`/customer/devices/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: decodeDevice })
  },
  batchMove(targets: DeviceBatchTarget[], placeId: string, key: string, signal?: AbortSignal) {
    batchBody(targets)
    if (!placeId || !key) throw new Error('批量移动目标位置或幂等键未确认。')
    return post<DeviceBatchResult>('/customer/devices/batch-move', { devices: targets, place_id: placeId }, key, signal, batchResult)
  },
  versionHistory(id: string, signal?: AbortSignal) { return request<{ items: VersionHistory[] }>(`/customer/devices/${encodeURIComponent(id)}/version-history`, { signal, decode: versionHistory }) },
  placeHistory(id: string, page: number, signal?: AbortSignal) { return request<Paginated<DevicePlaceHistory>>(`/customer/devices/${encodeURIComponent(id)}/place-history?page=${page}`, { signal, decode: raw => placeHistory(raw, page) }) },
  publishedVersions(page: number, keyword = '', size?: number, signal?: AbortSignal) {
    const query = new URLSearchParams({ page: String(page) }); if (keyword) query.set('keyword', keyword); if (size !== undefined) query.set('size', String(size))
    return request<Paginated<CustomerPublishedOption>>(`/customer/device-catalog/published-versions?${query}`, { signal, decode: published })
  },
}

function customerRegistrationSummary(value: unknown): CustomerRegistrationSummary {
  const row = object(value)
  const mode = text(row.mode)
  if (mode !== 'NEW_VENDOR' && mode !== 'MATCH_EXISTING' && mode !== 'PRODUCT_MODEL') return bad('登记方式未确认')
  return {
    id: text(row.id) as string, driver_key: text(row.driver_key) as string, mode, status: text(row.status) as string, revision: integer(row.revision),
    device_id: text(row.device_id, true), external_serial: text(row.external_serial) as string,
    device_name: text(row.device_name, true), cloud_state: text(row.cloud_state) as string,
    local_state: text(row.local_state) as string, place_id: text(row.place_id, true),
    product_version_id: text(row.product_version_id, true), created_at: text(row.created_at) as string,
    updated_at: text(row.updated_at) as string, commissioned_at: text(row.commissioned_at, true),
  }
}
function customerRegistrationDetail(raw: string): CustomerRegistrationDetail {
  const row = object(JSON.parse(raw))
  if (!Array.isArray(row.events)) return bad('登记详情未确认')
  return {
    registration: customerRegistrationSummary(row.registration),
    device_model: text(row.device_model, true), location: text(row.location, true),
    acceptance_note: text(row.acceptance_note, true),
    vendor_payload_stored: typeof row.vendor_payload_stored === 'boolean' ? row.vendor_payload_stored : bad('厂商字段保存状态未确认'),
    events: row.events.map(value => { const event = object(value); return { step: text(event.step) as string, outcome: text(event.outcome) as string, occurred_at: text(event.occurred_at) as string } }),
  }
}
function customerRegistrationPage(raw: string): { items: CustomerRegistrationSummary[]; total: number; page: number; size: number } {
  const row = object(JSON.parse(raw))
  if (!Array.isArray(row.items) || integer(row.page, 1) < 1 || integer(row.size, 1) < 1 || integer(row.total) < 0) return bad('登记列表未确认')
  return { items: row.items.map(customerRegistrationSummary), total: integer(row.total), page: integer(row.page, 1), size: integer(row.size, 1) }
}

export const customerRegistrationsApi = {
  drivers(signal?: AbortSignal) {
    return request<{ items: CustomerDriverOption[] }>('/customer/device-registrations/drivers', { signal, decode: raw => {
      const row = object(JSON.parse(raw))
      if (!Array.isArray(row.items)) return bad('来源驱动选项未确认')
      return { items: row.items.map(value => { const item = object(value); if (text(item.mode) !== 'VENDOR_PLATFORM') return bad('来源驱动模式未确认'); return { key: text(item.key) as string, mode: 'VENDOR_PLATFORM' as const, display_name: text(item.display_name) as string, configured: flag(item.configured, '驱动配置状态未确认'), enabled: flag(item.enabled, '驱动启用状态未确认'), legacy_origin_account: flag(item.legacy_origin_account, '旧平台来源账号能力未确认'), imported_ledger_association: flag(item.imported_ledger_association, '导入台账关联能力未确认') } }) }
    } })
  },
  list(page: number, size?: number, signal?: AbortSignal) { const query = new URLSearchParams({ page: String(page) }); if (size !== undefined) query.set('size', String(size)); return request<{ items: CustomerRegistrationSummary[]; total: number; page: number; size: number }>(`/customer/device-registrations?${query}`, { signal, decode: customerRegistrationPage }) },
  create(body: CreateRegistrationBody, key: string, signal?: AbortSignal) { return post<CustomerRegistrationDetail>('/customer/device-registrations', body, key, signal, customerRegistrationDetail) },
  detail(id: string, signal?: AbortSignal) { return request<CustomerRegistrationDetail>(`/customer/device-registrations/${encodeURIComponent(id)}`, { signal, decode: customerRegistrationDetail }) },
  resume(id: string, signal?: AbortSignal) { return request<CustomerRegistrationDetail>(`/customer/device-registrations/${encodeURIComponent(id)}/resume`, { method: 'POST', signal, decode: customerRegistrationDetail }) },
  reconcile(id: string, signal?: AbortSignal) { return request<CustomerRegistrationDetail>(`/customer/device-registrations/${encodeURIComponent(id)}/reconcile`, { method: 'POST', signal, decode: customerRegistrationDetail }) },
  commission(id: string, body: CommissionBody, key: string, signal?: AbortSignal) { return post<CustomerRegistrationDetail>(`/customer/device-registrations/${encodeURIComponent(id)}/commission`, body, key, signal, customerRegistrationDetail) },
  abandon(id: string, reason: string, key: string, signal?: AbortSignal) { return post<CustomerRegistrationDetail>(`/customer/device-registrations/${encodeURIComponent(id)}/abandon`, { reason }, key, signal, customerRegistrationDetail) },
}

// Frozen in-memory intents: SPA navigation retains the exact canonical key/payload/passwordless body.
// Nothing here is persisted to storage and a changed session generation clears it.
export const customerDeviceCreateMutation = createMutationIntent<Device>(decodeDevice)
export const customerDeviceUpdateMutation = createMutationIntent<Device>(decodeDevice)
export const customerDeviceBatchMoveMutation = createMutationIntent<DeviceBatchResult>(batchResult)
export const customerPlaceCreateMutation = createMutationIntent<PlaceNode>(raw => place(JSON.parse(raw), ownTenant()))
export const customerPlaceUpdateMutation = createMutationIntent<PlaceNode>(raw => place(JSON.parse(raw), ownTenant()))
export const customerPlaceDeleteMutation = createMutationIntent<void>(() => undefined)
export const customerRegistrationCreateMutation = createMutationIntent<CustomerRegistrationDetail>(customerRegistrationDetail)
export const customerRegistrationResumeMutation = createMutationIntent<CustomerRegistrationDetail>(customerRegistrationDetail)
export const customerRegistrationReconcileMutation = createMutationIntent<CustomerRegistrationDetail>(customerRegistrationDetail)
export const customerRegistrationCommissionMutation = createMutationIntent<CustomerRegistrationDetail>(customerRegistrationDetail)
export const customerRegistrationAbandonMutation = createMutationIntent<CustomerRegistrationDetail>(customerRegistrationDetail)
