import { useSession } from './store'
import type { DevicePlaceHistory } from './types'
import { alarmHistoryResponse, alarmResponse, alarmsResponse, alarmWorkbenchResponse } from './alarm-json'
import { changesResponse, changeResponse, definitionsResponse, effectiveResponse, integerToken, policyResponse, revisionInteger, targetsResponse } from './parameter-json'
import { devicesResponse, taskDetailResponse, tasksResponse } from './processing-json'
import { applyResponse, credentialResponse, messageDetailResponse, messagesResponse, previewResponse, previewWriteResponse } from './ingestion-json'
import type { PlaceNode, NotificationProvider, NotificationProviderStatus, NotificationProviderTest, NotificationResourceAccount, NotificationResourcePricing, NotificationResourceOrder, NotificationResourceLedger, NotificationProviderAttempt, NotificationRechargePolicy, NotificationRechargeGrant, NotificationRechargeOptions, NotificationCustomerOrder, NotificationCustomerAccount, NotificationCustomerLedger, NotificationChannelPolicy, Alarm, AlarmHistory, AlarmWorkbench, AlarmWorkbenchFilters, Audience, AuthResponse, BatteryLowThreshold, Capabilities, Conclusion, CredentialResult, Device, DeviceCreate, EffectiveParameter, Health, IngestPreview, IngestSource, ManagedUser, MessageApplyResult, MessageDetail, MessageRaw, ParameterChange, ParameterDefinition, ParameterMutation, ParameterPolicy, ParameterScope, Paginated, ParserTestResult, PermissionCatalog, ProcessingDetail, ProcessingTask, Product, ProductVersion, RawMessage, DeviceDistribution, ImportJob, ImportPreview, OpsOverview, MyMessage, MyProfile, MySession, ProfileRule, ProfileSnapshot, ReparseDetail, ReparseJob, Setting, SourceBinding, Summary, TaskStatus, Tenant, Unit, UnitCreate, UnitUpdate, UserCreate, UserPasswordReset, UserUpdate, VersionHistory } from './types'
import { parseJsonDocument } from './json-document'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId: string | null = null) { super(message); this.name = 'ApiError' }
}
export function errorMessage(error: unknown) {
  if (error instanceof ApiError) return `${error.message}（${error.code}${error.requestId ? ` · 请求编号 ${error.requestId}` : ' · 请求编号未提供'}）`
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}
type RequestOptions = RequestInit & { public?: boolean; tenantScoped?: boolean; tenantOptional?: boolean; tenantId?: string; decode?: (text: string) => unknown }
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { public: isPublic = false, tenantScoped = false, tenantOptional = false, tenantId, decode, ...init } = options
  const session = useSession.getState()
  const token = session.token, authGeneration = session.authGeneration
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  if (!isPublic) {
    if (!token) throw new ApiError(401, 'SESSION_REQUIRED', '请先登录')
    headers.set('Authorization', `Bearer ${token}`)
    if (tenantScoped && session.audience === 'management') {
      if (tenantId) headers.set('X-Tenant-Id', tenantId)
      else if (!tenantOptional) throw new ApiError(400, 'TENANT_REQUIRED', '请先选择本次操作的已授权单位')
    }
  }
  let response: Response
  try { response = await fetch(`/api${path}`, { ...init, headers, credentials: 'omit', cache: 'no-store' }) }
  catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务端，请检查网络或后端服务；未生成替代数据')
  }
  // A late response from a previous session must never clear a new login.
  if (response.status === 401 && !isPublic && useSession.getState().token === token && useSession.getState().authGeneration === authGeneration) useSession.getState().clear('登录已失效，请重新登录。')
  const raw = await response.text()
  let payload: unknown
  try { payload = response.ok && decode ? decode(raw) : raw ? JSON.parse(raw) : undefined }
  catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务端返回了非 JSON 响应', response.headers.get('X-Request-Id')) }
  if (!response.ok) {
    const body = payload && typeof payload === 'object' ? payload as Record<string, unknown> : {}
    throw new ApiError(response.status, typeof body.code === 'string' ? body.code : `HTTP_${response.status}`, typeof body.message === 'string' ? body.message : `请求失败（HTTP ${response.status}）`, typeof body.request_id === 'string' ? body.request_id : response.headers.get('X-Request-Id'))
  }
  return payload as T
}
function meResponse(text: string): Pick<AuthResponse, 'user' | 'permissions'> {
  const payload: unknown = JSON.parse(text)
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('会话核验响应格式无效')
  const result = payload as Record<string, unknown>
  if (Object.keys(result).length !== 2 || !Object.hasOwn(result, 'user') || !Object.hasOwn(result, 'permissions') || !Array.isArray(result.permissions) || result.permissions.some(permission => typeof permission !== 'string')) throw new Error('会话核验响应格式无效')
  const rawUser = result.user
  if (!rawUser || typeof rawUser !== 'object' || Array.isArray(rawUser)) throw new Error('会话核验响应格式无效')
  const user = rawUser as Record<string, unknown>
  const userKeys = ['id', 'username', 'name', 'user_type', 'tenant_id', 'must_change_password']
  if (Object.keys(user).length !== userKeys.length || userKeys.some(key => !Object.hasOwn(user, key)) || userKeys.slice(0, 3).some(key => typeof user[key] !== 'string') || (user.user_type !== 'PLATFORM' && user.user_type !== 'CUSTOMER') || (user.tenant_id !== null && typeof user.tenant_id !== 'string') || typeof user.must_change_password !== 'boolean') throw new Error('会话核验响应格式无效')
  return { user: rawUser as AuthResponse['user'], permissions: [...result.permissions] as string[] }
}
const json = (value: unknown) => JSON.stringify(value)
// Customer balance/ledger projections are read-only; reject any shape that is not the agreed array/page.
function customerAccountsResponse(text: string): NotificationCustomerAccount[] {
  const value: unknown = JSON.parse(text)
  if (!Array.isArray(value) || value.some(item => !item || typeof item !== 'object' || typeof (item as Record<string, unknown>).account_id !== 'string')) throw new ApiError(200, 'CUSTOMER_BALANCE_RESPONSE_UNCONFIRMED', '账户余额响应格式未确认')
  return value as NotificationCustomerAccount[]
}
function customerLedgerResponse(text: string, expectedPage: number): Paginated<NotificationCustomerLedger> {
  const value = JSON.parse(text) as Record<string, unknown> | null
  if (!value || typeof value !== 'object' || !Array.isArray(value.items) || typeof value.total !== 'number' || typeof value.page !== 'number' || value.page !== expectedPage || typeof value.size !== 'number') throw new ApiError(200, 'CUSTOMER_LEDGER_RESPONSE_UNCONFIRMED', '账户明细响应格式未确认')
  return value as unknown as Paginated<NotificationCustomerLedger>
}
const prefix = () => `/${useSession.getState().audience}`
function listQuery(page: number, size?: number, keyword?: string) {
  const params = new URLSearchParams({ page: String(page) })
  if (size !== undefined) params.set('size', String(size))
  if (keyword !== undefined) params.set('keyword', keyword)
  return params
}
export function scopeQuery(path: string, tenantIds?: string[], placeIds?: string[]) {
  if (useSession.getState().audience !== 'management' || tenantIds === undefined && placeIds === undefined) return path
  const query = new URLSearchParams({ scope_selected: 'true', scope_tenant_ids: (tenantIds ?? []).join(','), scope_place_ids: (placeIds ?? []).join(',') })
  return `${path}${path.includes('?') ? '&' : '?'}${query}`
}
function parameterPath(scope: ParameterScope, scopeId: string, key: string, tenantId?: string) {
  if (scope !== 'TENANT' && scope !== 'PLACE' && scope !== 'DEVICE') throw new ApiError(400, 'PARAMETER_SCOPE_INVALID', '覆盖层级只允许单位、位置或单设备，全局仍用设置接口')
  const session = useSession.getState()
  if (session.audience !== 'management') throw new ApiError(403, 'PARAMETERS_MANAGEMENT_ONLY', '客户仅可读取自有设备的安全有效策略')
  const targetUnit = tenantId
  if (!targetUnit) throw new ApiError(400, 'TENANT_REQUIRED', '请先选择本次操作的已授权单位')
  if (scope === 'TENANT' && scopeId !== targetUnit) throw new ApiError(400, 'PARAMETER_SCOPE_MISMATCH', '单位覆盖目标必须是本次操作单位')
  return `/management/parameter-values/${scope}/${encodeURIComponent(scopeId)}/${encodeURIComponent(key)}`
}
export const api = {
  health: (signal?: AbortSignal) => request<Health>('/health', { public: true, signal }),
  login: (username: string, password: string, audience: Audience) => request<AuthResponse>('/auth/login', { public: true, method: 'POST', body: json({ username, password, audience }) }),
  me: (signal?: AbortSignal) => request<Pick<AuthResponse, 'user' | 'permissions'>>('/auth/me', { signal, decode: meResponse }),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  changePassword: (current_password: string, new_password: string) => request<void>('/auth/change-password', { method: 'POST', body: json({ current_password, new_password }) }),
  tenants: (signal?: AbortSignal) => request<{ items: Tenant[] }>('/management/tenants', { signal }),
  summary: (signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<Summary>(scopeQuery(`${prefix()}/summary`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  devices: async (page: number, keyword = '', size?: number, signal?: AbortSignal, field?: string, placeId?: string, tenantId?: string, scopeTenantIds?: string[], scopePlaceIds?: string[], filters?: { connectivity?: string; physical_state?: string; integration_state?: string; system_type?: string; device_category?: string }) => {
    // Keep the new threshold decoder off the eager API bundle; the device route loads it only when reading device rows.
    const battery = await import('./battery-json')
    const params = listQuery(page, size, keyword); if (field) params.set('field', field); if (placeId) params.set('place_id', placeId); if (tenantId) params.set('tenant_id', tenantId)
    if (filters) for (const [key, value] of Object.entries(filters)) if (value !== undefined && value !== '') params.set(key, String(value))
    return request<Paginated<Device>>(scopeQuery(`${prefix()}/devices?${params}`, scopeTenantIds, scopePlaceIds), { tenantScoped: true, tenantOptional: true, signal, decode: raw => devicesResponse(raw, battery.batteryRawForRow) })
  },
  alarms: (page: number, size?: number, signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<Paginated<Alarm>>(scopeQuery(`${prefix()}/alarms?${listQuery(page, size)}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: alarmsResponse }),
  alarmWorkbench: (filters: AlarmWorkbenchFilters, page: number, size?: number, signal?: AbortSignal, scopeTenantIds?: string[], scopePlaceIds?: string[]) => {
    if (!Number.isSafeInteger(page) || page < 1 || (size !== undefined && (!Number.isSafeInteger(size) || size < 1))) throw new ApiError(400, 'WORKBENCH_PAGINATION_INVALID', '分页参数未确认')
    const query = new URLSearchParams({ page: String(page) }); if (size !== undefined) query.set('size', String(size))
    for (const key of ['keyword', 'physical_state', 'response_state', 'conclusion', 'occurred_from', 'occurred_to'] as const) { const value = filters[key]; if (value !== null && value !== '') { if (typeof value !== 'string') throw new ApiError(400, 'WORKBENCH_FILTER_INVALID', '查询条件未确认'); query.set(key, value) } }
    return request<AlarmWorkbench>(scopeQuery(`${prefix()}/alarm-workbench?${query}`, scopeTenantIds, scopePlaceIds), { tenantScoped: true, tenantOptional: true, signal, decode: alarmWorkbenchResponse })
  },
  alarm: (id: string, signal?: AbortSignal, tenantId?: string) => request<Alarm>(`${prefix()}/alarms/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: alarmResponse }),
  alarmHistory: (id: string, page: number, signal?: AbortSignal, tenantId?: string) => { const audience = useSession.getState().audience; return request<AlarmHistory>(`/${audience}/alarms/${encodeURIComponent(id)}/history?${listQuery(page)}`, { tenantScoped: true, tenantId, signal, decode: text => alarmHistoryResponse(text, audience) }) },
  review: (alarm: Alarm, conclusion: Conclusion, remark: string, key: string, signal?: AbortSignal) => request<Alarm>(`${prefix()}/alarms/${encodeURIComponent(alarm.id)}/review`, { tenantScoped: true, tenantId: alarm.tenant_id, method: 'POST', headers: { 'Idempotency-Key': key }, body: json({ expected_revision: alarm.revision, conclusion, remark }), signal, decode: alarmResponse }),
  settings: (signal?: AbortSignal) => request<{ items: Setting[] }>('/management/settings', { signal }),
  updateSetting: (setting: Setting, value: number) => request<Setting>(`/management/settings/${encodeURIComponent(setting.key)}`, { method: 'PUT', body: json({ value, expected_revision: setting.revision }) }),
  permissionCatalog: (signal?: AbortSignal) => request<PermissionCatalog>('/auth/permission-catalog', { signal }),
  units: (page: number, keyword = '', signal?: AbortSignal) => request<Paginated<Unit>>(`/management/units?${listQuery(page, undefined, keyword)}`, { signal }),
  places: (signal?: AbortSignal, tenantIds?: string[], placeIds?: string[], applyTreeScope = false) => request<PlaceNode[]>(applyTreeScope ? scopeQuery('/management/places', tenantIds, placeIds) : '/management/places', { tenantScoped: true, tenantOptional: true, signal }),
  scopeTree: (signal?: AbortSignal) => request<import('./types').ScopeUnit[]>('/management/scope-tree', { signal }),
  relations: (signal?: AbortSignal, tenantIds?: string[], placeIds?: string[], devicePage = 1, userPage = 1) => request<{ places: PlaceNode[]; devices: Paginated<Device>; users: Paginated<ManagedUser> }>(scopeQuery(`/management/relations?device_page=${devicePage}&user_page=${userPage}&size=100`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  createPlace: (body: { name: string; parent_place_id: string | null }, signal?: AbortSignal, tenantId?: string) => request<PlaceNode>('/management/places', { tenantScoped: true, tenantId, method: 'POST', body: json(body), signal }),
  updatePlace: (id: string, body: { name: string; parent_place_id: string | null; expected_revision: number }, signal?: AbortSignal, tenantId?: string) => request<PlaceNode>(`/management/places/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, method: 'PUT', body: json(body), signal }),
  deletePlace: (id: string, body: { expected_revision: number }, signal?: AbortSignal, tenantId?: string) => request<void>(`/management/places/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, method: 'DELETE', body: json(body), signal }),
  notificationProvider: (signal?: AbortSignal) => request<NotificationProvider>('/management/notification-provider', { signal }),
  updateNotificationProvider: (body: Record<string, unknown>, key: string, signal?: AbortSignal) => request<NotificationProvider>('/management/notification-provider', { method: 'PUT', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  notificationProviderStatus: (signal?: AbortSignal) => request<NotificationProviderStatus>('/management/notification-provider/status', { signal }),
  testNotificationProvider: (body: Record<string, unknown>, signal?: AbortSignal) => request<NotificationProviderTest>('/management/notification-provider/test', { method: 'POST', body: json(body), signal }),
  notificationResources: (signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<NotificationResourceAccount[]>(scopeQuery('/management/notification-resources', tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  notificationResourceLedger: (accountId: string, page: number, signal?: AbortSignal, tenantId?: string) => request<Paginated<NotificationResourceLedger>>(`/management/notification-resources/${encodeURIComponent(accountId)}/ledger?page=${page}`, { tenantScoped: true, tenantId, signal }),
  notificationResourcePricing: (signal?: AbortSignal) => request<NotificationResourcePricing>('/management/notification-resources/pricing', { signal }),
  saveNotificationResourcePricing: (body: Record<string, unknown>, signal?: AbortSignal, tenantId?: string) => request<NotificationResourcePricing>('/management/notification-resources/pricing', { tenantScoped: true, tenantId, method: 'PUT', body: json(body), signal }),
  notificationResourceOrders: (page: number, signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<Paginated<NotificationResourceOrder>>(scopeQuery(`/management/notification-resources/orders?page=${page}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  createNotificationResourceOrder: (body: Record<string, unknown>, key: string, signal?: AbortSignal, tenantId?: string) => request<NotificationResourceOrder>('/management/notification-resources/orders', { method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal, tenantScoped: true, tenantId }),
  notificationResourceOrder: (id: string, signal?: AbortSignal, tenantId?: string) => request<NotificationResourceOrder>(`/management/notification-resources/orders/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal }),
  confirmNotificationResourceOrder: (id: string, body: Record<string, unknown>, signal?: AbortSignal, tenantId?: string) => request<NotificationResourceOrder>(`/management/notification-resources/orders/${encodeURIComponent(id)}/confirm`, { method: 'POST', body: json(body), signal, tenantScoped: true, tenantId }),
  notificationRechargePolicies: (page: number, tenantIds?: string[], placeIds?: string[], signal?: AbortSignal) => request<Paginated<Pick<NotificationRechargePolicy, 'mode' | 'revision'> & { tenant_id: string; tenant_name: string }>>(scopeQuery(`/management/notification-recharge/policies?page=${page}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  notificationRechargeGrantOverview: (page: number, tenantIds?: string[], placeIds?: string[], signal?: AbortSignal) => request<Paginated<NotificationRechargeGrant & { tenant_id: string; tenant_name: string }>>(scopeQuery(`/management/notification-recharge/grants/overview?page=${page}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  notificationRechargePolicy: (signal?: AbortSignal, tenantId?: string) => request<NotificationRechargePolicy>('/management/notification-recharge/policy', { tenantScoped: true, tenantId, signal }),
  saveNotificationRechargePolicy: (body: Record<string, unknown>, signal?: AbortSignal, tenantId?: string) => request<NotificationRechargePolicy>('/management/notification-recharge/policy', { method: 'PUT', body: json(body), signal, tenantScoped: true, tenantId }),
  notificationRechargeGrants: (signal?: AbortSignal, tenantId?: string) => request<NotificationRechargeGrant[]>('/management/notification-recharge/grants', { tenantScoped: true, tenantId, signal }),
  addNotificationRechargeGrant: (body: Record<string, unknown>, signal?: AbortSignal, tenantId?: string) => request<NotificationRechargeGrant>('/management/notification-recharge/grants', { method: 'POST', body: json(body), signal, tenantScoped: true, tenantId }),
  revokeNotificationRechargeGrant: (userId: string, ownerType: string, ownerId: string, signal?: AbortSignal, tenantId?: string) => request<void>(`/management/notification-recharge/grants/revoke?userId=${encodeURIComponent(userId)}&ownerType=${encodeURIComponent(ownerType)}&ownerId=${encodeURIComponent(ownerId)}`, { method: 'POST', signal, tenantScoped: true, tenantId }),
  customerRechargeOptions: (signal?: AbortSignal) => request<NotificationRechargeOptions>('/customer/notification-recharge/options', { tenantScoped: true, signal }),
  customerRechargeOrders: (page: number, signal?: AbortSignal) => request<Paginated<NotificationCustomerOrder>>(`/customer/notification-recharge/orders?page=${page}`, { tenantScoped: true, signal }),
  customerRechargeOrder: (id: string, signal?: AbortSignal) => request<NotificationCustomerOrder>(`/customer/notification-recharge/orders/${encodeURIComponent(id)}`, { tenantScoped: true, signal }),
  customerRechargeAccounts: (signal?: AbortSignal) => request<NotificationCustomerAccount[]>('/customer/notification-recharge/accounts', { tenantScoped: true, signal, decode: customerAccountsResponse }),
  customerRechargeLedger: (accountId: string, page: number, signal?: AbortSignal) => request<Paginated<NotificationCustomerLedger>>(`/customer/notification-recharge/accounts/${encodeURIComponent(accountId)}/ledger?page=${page}`, { tenantScoped: true, signal, decode: text => customerLedgerResponse(text, page) }),
  notificationChannelPolicy: (scopeType: 'UNIT' | 'PLACE', placeId: string | null, signal?: AbortSignal, tenantId?: string) => {
    const params = new URLSearchParams({ scopeType }); if (placeId) params.set('placeId', placeId)
    return request<NotificationChannelPolicy>(`/management/notification-channel-policy?${params}`, { tenantScoped: true, tenantId, signal })
  },
  saveNotificationChannelPolicy: (body: Record<string, unknown>, key: string, signal?: AbortSignal, tenantId?: string) => request<NotificationChannelPolicy>('/management/notification-channel-policy', { method: 'PUT', tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  createCustomerRechargeOrder: (body: Record<string, unknown>, key: string, signal?: AbortSignal) => request<NotificationCustomerOrder>('/customer/notification-recharge/orders', { method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal, tenantScoped: true }),
  notificationProviderAttempts: (page: number, signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<Paginated<NotificationProviderAttempt>>(scopeQuery(`/management/notification-resources/provider-attempts?page=${page}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  createUnit: (body: UnitCreate, key: string, signal?: AbortSignal) => request<Unit>('/management/units', { method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  updateUnit: (id: string, body: UnitUpdate, key: string, signal?: AbortSignal) => request<Unit>(`/management/units/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  users: (platform: boolean, page: number, keyword = '', signal?: AbortSignal, tenantIds?: string[], placeIds?: string[], applyTreeScope = false) => request<Paginated<ManagedUser>>(applyTreeScope && !platform ? scopeQuery(`${prefix()}/users?${listQuery(page, undefined, keyword)}`, tenantIds, placeIds) : `${platform ? '/management/platform-users' : `${prefix()}/users`}?${listQuery(page, undefined, keyword)}`, { tenantScoped: !platform, tenantOptional: true, signal }),
  createUser: (platform: boolean, body: UserCreate, key: string, signal?: AbortSignal, tenantId?: string) => {
    // Do not permit callers to attach grants to ordinary tenant-user requests.
    const payload = { username: body.username, name: body.name, initial_password: body.initial_password, permissions: body.permissions, ...(platform ? { tenant_ids: body.tenant_ids ?? [] } : {}) }
    return request<ManagedUser>(platform ? '/management/platform-users' : `${prefix()}/users`, { method: 'POST', tenantScoped: !platform, tenantId, headers: { 'Idempotency-Key': key }, body: json(payload), signal })
  },
  updateUser: (platform: boolean, id: string, body: UserUpdate, key: string, signal?: AbortSignal, tenantId?: string) => {
    const payload = { name: body.name, enabled: body.enabled, permissions: body.permissions, expected_revision: body.expected_revision, ...(platform ? { tenant_ids: body.tenant_ids ?? [] } : {}) }
    return request<ManagedUser>(`${platform ? '/management/platform-users' : `${prefix()}/users`}/${encodeURIComponent(id)}`, { method: 'PUT', tenantScoped: !platform, tenantId, headers: { 'Idempotency-Key': key }, body: json(payload), signal })
  },
  resetUserPassword: (platform: boolean, id: string, body: UserPasswordReset, key: string, signal?: AbortSignal, tenantId?: string) => request<ManagedUser>(`${platform ? '/management/platform-users' : `${prefix()}/users`}/${encodeURIComponent(id)}/reset-password`, { method: 'POST', tenantScoped: !platform, tenantId, headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  products: (page: number, keyword = '', signal?: AbortSignal) => request<Paginated<Product>>(`/management/products?${listQuery(page, undefined, keyword)}`, { signal }),
  createProduct: (body: { name: string; manufacturer: string; model: string; description: string }, key: string, signal?: AbortSignal) => request<Product>('/management/products', { method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  versions: async (id: string, signal?: AbortSignal) => { const { parseVersionsResponse } = await import('./product-json'); return request<{ items: ProductVersion[] }>(`/management/products/${encodeURIComponent(id)}/versions`, { signal, decode: parseVersionsResponse }) },
  createVersion: async (id: string, base_version_id: string | null, key: string, signal?: AbortSignal) => { const { parseVersionResponse } = await import('./product-json'); return request<ProductVersion>(`/management/products/${encodeURIComponent(id)}/versions`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: json({ base_version_id }), signal, decode: parseVersionResponse }) },
  updateVersion: async (id: string, body: { expected_revision: number; schema_json: string; capabilities: Capabilities; battery_low_threshold: BatteryLowThreshold | null }, key: string, signal?: AbortSignal) => {
    // Product-only input validation is lazy; invalid documents never create an HTTP request.
    const { schema_json, battery_low_threshold, ...envelope } = body
    const schemaValue = parseJsonDocument(schema_json)
    if (!schemaValue || Array.isArray(schemaValue) || typeof schemaValue !== 'object') throw new Error('Schema 必须是声明式 JSON 对象')
    let thresholdJson = 'null'
    if (battery_low_threshold) {
      // Split boundary, not a runtime-selected specifier: this literal module is product-version-only
      // input validation, and a static import puts it on the entry chunk that must stay within the
      // eager budget. Same deferred pattern as parseVersionResponse below.
      const { validThreshold } = await import('./battery-threshold')
      const { threshold_json: raw, ...wire } = battery_low_threshold
      if (!raw || !validThreshold(raw, battery_low_threshold.threshold)) throw new ApiError(400, 'BATTERY_THRESHOLD_INVALID', '电量阈值精度或范围未确认')
      thresholdJson = JSON.stringify({ ...wire, threshold: '__N05_THRESHOLD__' }).replace('"threshold":"__N05_THRESHOLD__"', `"threshold":${raw}`)
    }
    const serialized = json(envelope), requestBody = `${serialized.slice(0, -1)},"schema":${schema_json},"battery_low_threshold":${thresholdJson}}`
    return import('./product-json').then(({ parseVersionResponse }) => request<ProductVersion>(`/management/product-versions/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: requestBody, signal, decode: parseVersionResponse }))
  },
  testVersion: async (id: string, body: { expected_revision: number; samples_json: string }, key: string, signal?: AbortSignal) => {
    const { sampleDocument, parseTestResponse } = await import('./product-json')
    const { samples_json, ...envelope } = body
    sampleDocument(samples_json)
    const serialized = json(envelope)
    return request<ParserTestResult>(`/management/product-versions/${encodeURIComponent(id)}/test`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: `${serialized.slice(0, -1)},"samples":${samples_json}}`, signal, decode: parseTestResponse })
  },
  publishVersion: async (id: string, expected_revision: number, key: string, signal?: AbortSignal) => { const { parseVersionResponse } = await import('./product-json'); return request<ProductVersion>(`/management/product-versions/${encodeURIComponent(id)}/publish`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: json({ expected_revision }), signal, decode: parseVersionResponse }) },
  createDevice: (body: DeviceCreate, key: string, signal?: AbortSignal, tenantId?: string) => request<Device>('/management/devices', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  device: async (id: string, signal?: AbortSignal, tenantId?: string) => (await request<{ device: Device }>(`/management/devices/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal })).device,
  updateDevice: (id: string, body: { name: string; address: string; place_id?: string; system_type?: string | null; device_category?: string | null; expected_revision: number }, key: string, signal?: AbortSignal, tenantId?: string) => request<Device>(`/management/devices/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  migrateDevice: (id: string, body: { product_version_id: string; expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<Device>(`/management/devices/${encodeURIComponent(id)}/migrate-version`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  versionHistory: (id: string, signal?: AbortSignal, tenantId?: string) => request<{ items: VersionHistory[] }>(`/management/devices/${encodeURIComponent(id)}/version-history`, { tenantScoped: true, tenantId, signal }),
  placeHistory: (id: string, page: number, signal?: AbortSignal, tenantId?: string) => request<Paginated<DevicePlaceHistory>>(`/management/devices/${encodeURIComponent(id)}/place-history?${listQuery(page)}`, { tenantScoped: true, tenantOptional: true, tenantId, signal }),
  sources: (page: number, keyword = '', signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<Paginated<IngestSource>>(scopeQuery(`/management/sources?${listQuery(page, undefined, keyword)}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  createSource: (body: { name: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<CredentialResult>('/management/sources', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal, decode: credentialResponse }),
  updateSource: (id: string, body: { name: string; enabled: boolean; expected_revision: number }, key: string, signal?: AbortSignal, tenantId?: string) => request<IngestSource>(`/management/sources/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  rotateSourceToken: (id: string, expected_revision: number, key: string, signal?: AbortSignal, tenantId?: string) => request<CredentialResult>(`/management/sources/${encodeURIComponent(id)}/rotate-token`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json({ expected_revision }), signal, decode: credentialResponse }),
  sourceBindings: (id: string, signal?: AbortSignal, tenantId?: string) => request<{ items: SourceBinding[] }>(`/management/sources/${encodeURIComponent(id)}/bindings`, { tenantScoped: true, tenantId, signal }),
  bindSource: (id: string, body: { device_id: string; vendor_device_id: string; expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<{ source: IngestSource; binding: SourceBinding }>(`/management/sources/${encodeURIComponent(id)}/bindings`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  detachSource: (id: string, bindingId: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<{ source: IngestSource; binding: SourceBinding }>(`/management/sources/${encodeURIComponent(id)}/bindings/${encodeURIComponent(bindingId)}/detach`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal }),
  messages: (page: number, keyword = '', sourceId = '', signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => { const query = listQuery(page, undefined, keyword); if (sourceId) query.set('source_id', sourceId); return request<Paginated<RawMessage>>(scopeQuery(`/management/messages?${query}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: messagesResponse }) },
  messageDetail: (id: string, signal?: AbortSignal, tenantId?: string) => request<MessageDetail>(`/management/messages/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: messageDetailResponse }),
  messageRaw: (id: string, signal?: AbortSignal, tenantId?: string) => {
    const session = useSession.getState()
    if (session.audience !== 'management' || !session.permissions.includes('messages:raw') || !session.permissions.includes('messages:read')) return Promise.reject(new ApiError(403, 'RAW_PERMISSION_REQUIRED', '当前账户没有独立原文读取权限'))
    return request<MessageRaw>(`/management/messages/${encodeURIComponent(id)}/raw`, { tenantScoped: true, tenantId, signal })
  },
  previewMessage: (id: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<{ message: RawMessage; preview: IngestPreview }>(`/management/messages/${encodeURIComponent(id)}/preview`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal, decode: text => previewWriteResponse(text, useSession.getState().permissions.includes('messages:raw')) }),
  deviceDistribution: (signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<DeviceDistribution>(scopeQuery('/management/devices/distribution', tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: text => JSON.parse(text) as DeviceDistribution }),
  setDeviceLocation: (id: string, body: { latitude: number; longitude: number; expected_revision: number }, signal?: AbortSignal, tenantId?: string) => request<Device>(`/management/devices/${encodeURIComponent(id)}/location`, { tenantScoped: true, tenantId, method: 'PUT', body: json(body), signal }),
  clearDeviceLocation: (id: string, body: { expected_revision: number }, signal?: AbortSignal, tenantId?: string) => request<Device>(`/management/devices/${encodeURIComponent(id)}/location/clear`, { tenantScoped: true, tenantId, method: 'POST', body: json(body), signal }),
  opsOverview: (signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<OpsOverview>(scopeQuery('/management/ops-overview', tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: text => JSON.parse(text) as OpsOverview }),
  myProfile: (signal?: AbortSignal) => request<MyProfile>('/me', { signal, decode: text => JSON.parse(text) as MyProfile }),
  renameMe: (name: string, signal?: AbortSignal) => request<MyProfile>('/me/profile', { method: 'PUT', body: json({ name }), signal, decode: text => JSON.parse(text) as MyProfile }),
  mySessions: (signal?: AbortSignal) => request<MySession[]>('/me/sessions', { signal, decode: text => JSON.parse(text) as MySession[] }),
  revokeOtherSessions: (signal?: AbortSignal) => request<{ revoked: number }>('/me/sessions/revoke-others', { method: 'POST', signal, decode: text => JSON.parse(text) as { revoked: number } }),
  myMessages: (page: number, signal?: AbortSignal) => request<Paginated<MyMessage>>(`/me/messages?${listQuery(page)}`, { signal, decode: text => { const parsed = JSON.parse(text); return { items: parsed.items as MyMessage[], total: parsed.total, page: parsed.page, size: parsed.size } } }),
  readMessage: (id: string, revision: number, signal?: AbortSignal) => request<MyMessage>(`/me/messages/${encodeURIComponent(id)}/read`, { method: 'POST', body: json({ expected_revision: revision }), signal, decode: text => JSON.parse(text) as MyMessage }),
  handleMessage: (id: string, revision: number, signal?: AbortSignal) => request<MyMessage>(`/me/messages/${encodeURIComponent(id)}/handle`, { method: 'POST', body: json({ expected_revision: revision }), signal, decode: text => JSON.parse(text) as MyMessage }),
  previewImport: (csv: string, signal?: AbortSignal, tenantId?: string) => request<ImportPreview>('/management/devices/import/preview', { tenantScoped: true, tenantId, method: 'POST', body: json({ csv }), signal, decode: text => JSON.parse(text) as ImportPreview }),
  confirmImport: (body: { csv: string; expected_sha256: string; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<ImportJob>('/management/devices/import/confirm', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal, decode: text => JSON.parse(text) as ImportJob }),
  profileRule: (signal?: AbortSignal, tenantId?: string) => request<ProfileRule | null>('/management/profile-rule', { tenantScoped: true, tenantId, signal, decode: text => JSON.parse(text) as ProfileRule | null }),
  profileRules: (signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<{ items: ProfileRule[] }>(scopeQuery('/management/profile-rules', tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal }),
  configureProfileRule: (body: { window_days: number; min_samples: number; enabled: boolean; reason: string }, signal?: AbortSignal, tenantId?: string) => request<ProfileRule>('/management/profile-rule', { tenantScoped: true, tenantId, method: 'POST', body: json(body), signal, decode: text => JSON.parse(text) as ProfileRule }),
  deviceProfile: (id: string, signal?: AbortSignal, tenantId?: string, tenantIds?: string[], placeIds?: string[]) => request<ProfileSnapshot | null>(scopeQuery(`/management/devices/${encodeURIComponent(id)}/profile`, tenantIds, placeIds), { tenantScoped: true, tenantId, signal, decode: text => JSON.parse(text) as ProfileSnapshot | null }),
  profileHistory: (id: string, signal?: AbortSignal, tenantId?: string, tenantIds?: string[], placeIds?: string[]) => request<ProfileSnapshot[]>(scopeQuery(`/management/devices/${encodeURIComponent(id)}/profile/history?limit=20`, tenantIds, placeIds), { tenantScoped: true, tenantId, signal, decode: text => JSON.parse(text) as ProfileSnapshot[] }),
  recomputeProfile: (id: string, body: { reason: string }, signal?: AbortSignal, tenantId?: string) => request<ProfileSnapshot>(`/management/devices/${encodeURIComponent(id)}/profile/recompute`, { tenantScoped: true, tenantId, method: 'POST', body: json(body), signal, decode: text => JSON.parse(text) as ProfileSnapshot }),
  reparseMessage: (id: string, body: { reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<ReparseJob>(`/management/messages/${encodeURIComponent(id)}/reparse`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal, decode: text => JSON.parse(text) as ReparseJob }),
  reparseJob: (id: string, signal?: AbortSignal, tenantId?: string) => request<ReparseDetail>(`/management/reparse-jobs/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: text => JSON.parse(text) as ReparseDetail }),
  applyMessage: (id: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal, tenantId?: string) => request<MessageApplyResult>(`/management/messages/${encodeURIComponent(id)}/apply`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: json(body), signal, decode: applyResponse }),
  messagePreview: (id: string, previewId: string, signal?: AbortSignal, tenantId?: string) => request<IngestPreview>(`/management/messages/${encodeURIComponent(id)}/previews/${encodeURIComponent(previewId)}`, { tenantScoped: true, tenantId, signal, decode: text => previewResponse(text, useSession.getState().permissions.includes('messages:raw')) }),
  parameterDefinitions: (signal?: AbortSignal) => request<{ items: ParameterDefinition[] }>('/management/parameter-definitions', { signal, decode: definitionsResponse }),
  parameterTargets: (page: number, keyword = '', signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => request<Paginated<{ id: string; name: string; tenant_id: string }>>(scopeQuery(`/management/parameter-targets?${listQuery(page, undefined, keyword)}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: targetsResponse }),
  parameterPolicy: (scope: ParameterScope, scopeId: string, key: string, signal?: AbortSignal, tenantId?: string) => request<ParameterPolicy>(parameterPath(scope, scopeId, key, tenantId), { tenantScoped: true, tenantId, signal, decode: policyResponse }),
  parameterHistory: (scope: ParameterScope, scopeId: string, key: string, page: number, signal?: AbortSignal, tenantId?: string) => request<Paginated<ParameterChange>>(`${parameterPath(scope, scopeId, key, tenantId)}/history?${listQuery(page)}`, { tenantScoped: true, tenantId, signal, decode: changesResponse }),
  setParameter: (scope: ParameterScope, scopeId: string, key: string, body: { value_text: string; expected_revision: number; reason: string }, idempotencyKey: string, signal?: AbortSignal, tenantId?: string) => request<ParameterMutation>(parameterPath(scope, scopeId, key, tenantId), { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': idempotencyKey }, body: json({ value: integerToken(body.value_text), expected_revision: revisionInteger(body.expected_revision), reason: body.reason }), signal, decode: changeResponse }),
  clearParameter: (scope: ParameterScope, scopeId: string, key: string, body: { expected_revision: number; reason: string }, idempotencyKey: string, signal?: AbortSignal, tenantId?: string) => request<ParameterMutation>(`${parameterPath(scope, scopeId, key, tenantId)}/clear`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: json({ expected_revision: revisionInteger(body.expected_revision), reason: body.reason }), signal, decode: changeResponse }),
  effectiveParameters: (id: string, signal?: AbortSignal, tenantId?: string) => request<{ device_id: string; items: EffectiveParameter[]; consumer: 'CONNECTIVITY_AND_OFFLINE_EVENTS' }>(`${prefix()}/devices/${encodeURIComponent(id)}/effective-parameters`, { tenantScoped: true, tenantId, signal, decode: effectiveResponse }),
  processingTasks: (page: number, keyword = '', status: TaskStatus | '' = '', signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => { const query = listQuery(page, undefined, keyword); if (status) query.set('status', status); return request<Paginated<ProcessingTask>>(scopeQuery(`/management/processing-tasks?${query}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: tasksResponse }) },
  processingTask: (id: string, signal?: AbortSignal, tenantId?: string) => request<ProcessingDetail>(`/management/processing-tasks/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: taskDetailResponse }),
}
