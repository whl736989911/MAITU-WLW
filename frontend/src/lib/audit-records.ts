import { ApiError } from './api'
import { useSession } from './store'
import { parseJsonDocument, jsonFieldText, jsonArrayItems } from './json-document'
import { integerToken } from './parameter-json'
import { canonicalAlarmInstant, beijingInput } from './alarm-workbench'

// Persisted action registry, including retired history; preserve case/hyphens and sort definitions in ASCII order.
export const auditActionLabels = {
  LOGIN: '登录', CHANGE_PASSWORD: '修改密码', LOGOUT: '退出登录', REQUEST: '请求鉴权', BUSINESS_REQUEST: '业务请求', INVALID_REQUEST: '请求校验', CHANNEL_UPGRADE: '信号通道握手', CREATE_UNIT: '创建单位', UPDATE_UNIT: '更新单位',
  'CUSTOMER-CREATE': '创建单位账号', 'CUSTOMER-UPDATE': '更新单位账号', 'CUSTOMER-RESET': '重置单位账号密码', 'PLATFORM-CREATE': '创建平台账号', 'PLATFORM-UPDATE': '更新平台账号', 'PLATFORM-RESET': '重置平台账号密码',
  UPDATE_UNIT_DETAILS: '编辑单位资料', 'IDENTITY-DEPARTMENT-CREATE': '创建部门', 'IDENTITY-DEPARTMENT-UPDATE': '编辑部门', 'IDENTITY-USER-METADATA': '编辑人员资料', 'ROLE-CREATE': '创建角色', 'ROLE-UPDATE': '编辑角色', 'ROLE-DELETE': '删除角色',
  ALARM_BASE_CONFIG_UPDATED: '编辑报警基础配置', RESOURCE_LINKAGE_DEFAULT_UPDATED: '编辑默认联动规则', RESOURCE_LINKAGE_CREATED: '创建联动规则', RESOURCE_LINKAGE_UPDATED: '编辑联动规则', RESOURCE_LINKAGE_ENABLED: '启用联动规则', RESOURCE_LINKAGE_DISABLED: '停用联动规则', RESOURCE_LINKAGE_DELETED: '删除联动规则', RESOURCE_LINKAGE_EVALUATED: '评估联动规则', DEVICE_PORTRAIT_RULE_CONFIGURE: '配置运维画像规则', DEVICE_PORTRAIT_EVALUATE: '分析运维画像', DUTY_FAULT_HANDLING: '处置故障', SUPPRESSION_CREATED: '创建通知屏蔽', SUPPRESSION_CANCELLED: '取消通知屏蔽',
  SUPPRESSION_UPDATED: '编辑通知屏蔽时段', SUPPRESSION_ENABLED: '启用通知屏蔽', SUPPRESSION_DISABLED: '停用通知屏蔽', SUPPRESSION_DELETED: '删除失效通知屏蔽', DEVICE_CHANNEL_CREATED: '登记人工传感器通道', DEVICE_CHANNEL_UPDATED: '编辑人工传感器通道',
  'device-register': '设备开户', 'device-edit': '编辑设备', 'device-migrate': '迁移设备版本', 'product-create': '创建产品', 'version-create': '创建产品版本', 'version-edit': '编辑产品版本', 'version-test': '版本合成测试', 'version-publish': '发布产品版本',
  MESSAGE_APPLY: '应用真实来源报文', MESSAGE_AUTO_APPLY: '自动应用真实来源报文', 'hikvision-sync': '请求海康真实同步', 'hikvision-assign': '分配海康来源设备',
  'source-create': '创建接入来源', 'source-update': '更新接入来源', 'source-rotate': '轮换来源凭据', 'source-bind': '绑定主源', 'source-detach': '解绑主源', CAPTURE: '捕获报文', CAPTURE_DUPLICATE: '记录重复报文', MESSAGE_PREVIEW: '报文归一化预览', PARAMETER_SET: '设置参数覆盖', PARAMETER_CLEAR: '清除参数覆盖', UPDATE_SETTING: '更新全局设置', SIMULATE_FIRE: '模拟火警', SIMULATE_RECOVERY: '模拟恢复', SIMULATION_DEDUPLICATED: '模拟消息去重', REVIEW_ALARM: '复核报警',
  PROCESSING_SUBMIT: '提交模拟处理任务', PROCESSING_CANCEL: '取消模拟处理任务', PROCESSING_SUCCEEDED: '模拟处理成功', PROCESSING_REJECTED: '模拟处理拒绝', PROCESSING_RETRY: '模拟处理待重试', NOTIFICATION_CONTACT_WRITE: '维护通知联系人', NOTIFICATION_TEMPLATE_CREATE: '创建通知模板', NOTIFICATION_TEMPLATE_EDIT: '编辑通知模板', NOTIFICATION_RULE_WRITE: '维护通知规则', NOTIFICATION_QUEUED: '模拟通知排队', NOTIFICATION_CANCELLED: '取消模拟通知', NOTIFICATION_SIMULATION_RECONCILED: '核对不确定模拟结果', NOTIFICATION_REJECTED: '模拟通知拒绝', NOTIFICATION_SUPPRESSED: '模拟通知抑制', NOTIFICATION_SIMULATED_SUCCESS: '模拟通知成功', NOTIFICATION_SIMULATED_UNCERTAIN: '模拟通知结果不确定', NOTIFICATION_RETRY_SCHEDULED: '安排模拟通知重试', NOTIFICATION_SIMULATED_FAILURE: '模拟通知失败', NOTIFICATION_AUTOMATION_POLICY: '设置自动模拟策略', NOTIFICATION_AUTOMATION_QUEUED: '自动模拟通知排队', NOTIFICATION_AUTOMATION_ALREADY_DISPATCHED: '已有模拟派发记录', NOTIFICATION_AUTOMATION_SKIPPED: '跳过自动模拟通知', NOTIFICATION_AUTOMATION_REJECTED: '拒绝自动模拟通知', UNCLASSIFIED: '未分类（原值不展示）',
} as const
export const auditOutcomeLabels = { SUCCESS: '操作成功', DENIED: '请求拒绝', SUCCEEDED: '模拟处理成功', REJECTED: '已拒绝', RETRY: '待重试', SUCCEEDED_SIMULATION: '模拟通知成功', FAILED: '模拟通知失败', UNCERTAIN: '模拟结果不确定', WAIT_RETRY: '等待模拟重试', SUPPRESSED: '已抑制', QUEUED: '已排队', ALREADY_DISPATCHED: '已有派发记录', SKIPPED: '已跳过', HANDLED: '已处理', UNHANDLED: '未处理', APPLIED: '真实事实已应用', STALE: '已隔离过时消息', REQUESTED: '已请求', UNCLASSIFIED: '未分类（原值不展示）' } as const
export interface AuditDefinition { code: string; label: string }
function definitions(labels: Record<string, string>): AuditDefinition[] { return Object.entries(labels).filter(([code]) => code !== 'UNCLASSIFIED').sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([code, label]) => ({ code, label })).concat({ code: 'UNCLASSIFIED', label: labels.UNCLASSIFIED }) }
export const auditActionDefinitions = definitions(auditActionLabels), auditOutcomeDefinitions = definitions(auditOutcomeLabels)
export const auditCoverage = { source: 'PERSISTED_AUDIT_LOG', scope: 'AUTHORIZED_UNITS_ONLY', missing_history: 'NOT_RECONSTRUCTED', references: 'UUID_ONLY', raw_request_id: 'NOT_EXPOSED' } as const
export type AuditAction = keyof typeof auditActionLabels
export type AuditOutcome = keyof typeof auditOutcomeLabels
export type AuditReferenceState = 'UUID' | 'NOT_RECORDED' | 'WITHHELD'
export const auditModules = { IDENTITY: '身份与单位', DEVICE: '设备管理', ALARM: '报警值守', NOTIFICATION: '通知服务', INGESTION: '接入与处理', SYSTEM: '系统管理', UNCLASSIFIED: '未分类' } as const
export const auditPlatforms = { WEB: 'Web', APP: 'App', API: 'API', SYSTEM: '后台任务', UNKNOWN: '历史未记录' } as const
export interface AuditLogSearch { keyword: string | null; username: string | null; module: keyof typeof auditModules | null; platform: keyof typeof auditPlatforms | null }
export const emptyAuditSearch: AuditLogSearch = { keyword: null, username: null, module: null, platform: null }
export interface AuditLogMetadata { actor_username: string | null; module: keyof typeof auditModules; platform: keyof typeof auditPlatforms }
export function compileAuditSearch(value: Record<keyof AuditLogSearch, string | null>): AuditLogSearch {
  const text = (v: string | null) => { const result = v?.trim() || null; if (result && (result.length > 100 || result.includes('\0'))) throw new ApiError(400, 'AUDIT_QUERY_INVALID', '日志关键字最多100字且不能包含空字符'); return result }
  if (value.module && !Object.hasOwn(auditModules, value.module) || value.platform && !Object.hasOwn(auditPlatforms, value.platform)) throw new ApiError(400, 'AUDIT_QUERY_INVALID', '日志分类无效')
  return { keyword: text(value.keyword), username: text(value.username), module: (value.module || null) as AuditLogSearch['module'], platform: (value.platform || null) as AuditLogSearch['platform'] }
}
export interface AuditFilters { action: AuditAction | null; outcome: AuditOutcome | null; actor_ref: string | null; target_ref: string | null; occurred_from: string | null; occurred_to: string | null }
export type AuditDraft = Record<keyof AuditFilters, string>
export const emptyAuditFilters: AuditFilters = { action: null, outcome: null, actor_ref: null, target_ref: null, occurred_from: null, occurred_to: null }
export const emptyAuditDraft: AuditDraft = { action: '', outcome: '', actor_ref: '', target_ref: '', occurred_from: '', occurred_to: '' }
export interface AuditRecord { id: string; tenant_id: string; tenant_name: string; occurred_at: string; action: AuditAction; outcome: AuditOutcome; actor_ref: string | null; actor_reference_state: AuditReferenceState; target_ref: string | null; target_reference_state: AuditReferenceState }
export interface AuditRecords { items: AuditRecord[]; total: number; page: number; size: number; as_of: string; timezone: 'Asia/Shanghai'; scope: { kind: 'AUTHORIZED_UNITS'; tenant_ids: string[]; selected_tenant_ids: string[]; selected_place_ids: string[]; audience: 'management' }; filters: AuditFilters; action_definitions: AuditDefinition[]; outcome_definitions: AuditDefinition[]; coverage: typeof auditCoverage; search: AuditLogSearch; details: Record<string, AuditLogMetadata> }
const fail = (): never => { throw new ApiError(200, 'AUDIT_READ_UNCONFIRMED', '审计读取未确认；不保留旧行、不推断空记录或0。') }
function exact<T>(value: unknown, keys: readonly string[]): T { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value, k))) return fail(); return value as T }
function field(raw: string, key: string) { const v = jsonFieldText(raw, key); if (v === undefined) return fail(); return v }
function integer(raw: string, key: string, min = 0, max = Number.MAX_SAFE_INTEGER) { const v = field(raw, key); if (!/^(?:0|[1-9]\d*)$/.test(v)) return fail(); const n = integerToken(v); if (n < min || n > max) return fail(); return n }
export function canonicalAuditUuid(value: unknown): value is string { return typeof value === 'string' && value.length === 36 && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value) }
function stamp(value: unknown) { if (typeof value !== 'string' || !value.endsWith('Z')) return fail(); return canonicalAlarmInstant(value) }
function choice(value: unknown, labels: object) { return typeof value === 'string' && Object.hasOwn(labels, value) }
function normalized(value: AuditFilters, server = false): AuditFilters {
  exact(value, Object.keys(emptyAuditFilters))
  if (value.action !== null && !choice(value.action, auditActionLabels) || value.outcome !== null && !choice(value.outcome, auditOutcomeLabels) || value.actor_ref !== null && !canonicalAuditUuid(value.actor_ref) || value.target_ref !== null && !canonicalAuditUuid(value.target_ref)) return fail()
  const time = (v: string | null) => v === null ? null : server ? stamp(v) : canonicalAlarmInstant(v)
  const result: AuditFilters = { action: value.action, outcome: value.outcome, actor_ref: value.actor_ref, target_ref: value.target_ref, occurred_from: time(value.occurred_from), occurred_to: time(value.occurred_to) }
  if (result.occurred_from && result.occurred_to && result.occurred_from >= result.occurred_to) return fail()
  return result
}
export function compileAuditDraft(draft: AuditDraft): AuditFilters { return normalized({ action: (draft.action || null) as AuditAction | null, outcome: (draft.outcome || null) as AuditOutcome | null, actor_ref: draft.actor_ref || null, target_ref: draft.target_ref || null, occurred_from: beijingInput(draft.occurred_from), occurred_to: beijingInput(draft.occurred_to) }) }
function checkDefinitions(value: unknown, expected: AuditDefinition[]) { if (!Array.isArray(value) || value.length !== expected.length) return fail(); value.forEach((entry, index) => { const d = exact<AuditDefinition>(entry, ['code', 'label']); if (d.code !== expected[index].code || d.label !== expected[index].label) return fail() }) }
function reference(ref: unknown, state: unknown) { if (state === 'UUID') { if (!canonicalAuditUuid(ref)) return fail() } else if (!['NOT_RECORDED', 'WITHHELD'].includes(state as string) || ref !== null) return fail() }
export function readAuditRecords(raw: string, applied: AuditFilters, page: number, size: number | undefined, selection: { tenantIds: string[]; placeIds: string[] }, search: AuditLogSearch = emptyAuditSearch): AuditRecords {
  try {
    const r = exact<AuditRecords>(parseJsonDocument(raw), ['items', 'total', 'page', 'size', 'as_of', 'timezone', 'scope', 'filters', 'action_definitions', 'outcome_definitions', 'coverage', 'search', 'details'])
    stamp(r.as_of); if (r.timezone !== 'Asia/Shanghai') return fail()
    exact(r.scope, ['kind', 'tenant_ids', 'selected_tenant_ids', 'selected_place_ids', 'audience'])
    const stringList = (value: unknown) => { if (!Array.isArray(value) || value.some(item => typeof item !== 'string' || !item)) return fail(); return value as string[] }
    const tenantIds = stringList(r.scope.tenant_ids), selectedTenantIds = stringList(r.scope.selected_tenant_ids), selectedPlaceIds = stringList(r.scope.selected_place_ids)
    if (r.scope.kind !== 'AUTHORIZED_UNITS' || r.scope.audience !== 'management' || new Set(tenantIds).size !== tenantIds.length || new Set(selectedTenantIds).size !== selectedTenantIds.length || new Set(selectedPlaceIds).size !== selectedPlaceIds.length || JSON.stringify(selectedTenantIds) !== JSON.stringify(selection.tenantIds) || JSON.stringify(selectedPlaceIds) !== JSON.stringify(selection.placeIds)) return fail()
    exact(r.coverage, Object.keys(auditCoverage)); if (Object.entries(auditCoverage).some(([k, v]) => r.coverage[k as keyof typeof auditCoverage] !== v)) return fail()
    checkDefinitions(r.action_definitions, auditActionDefinitions); checkDefinitions(r.outcome_definitions, auditOutcomeDefinitions)
    r.total = integer(raw, 'total'); r.page = integer(raw, 'page', 1, 1000000); r.size = integer(raw, 'size', 1, 100)
    if (r.page !== page || size !== undefined && r.size !== size) return fail()
    const expected = normalized(applied), actual = normalized(r.filters, true); if (JSON.stringify(expected) !== JSON.stringify(actual)) return fail(); r.filters = actual
    exact(r.search, Object.keys(emptyAuditSearch)); if (JSON.stringify(compileAuditSearch(r.search)) !== JSON.stringify(compileAuditSearch(search))) return fail()
    r.items = jsonArrayItems(field(raw, 'items')).map(text => {
      const item = exact<AuditRecord>(parseJsonDocument(text), ['id', 'tenant_id', 'tenant_name', 'occurred_at', 'action', 'outcome', 'actor_ref', 'actor_reference_state', 'target_ref', 'target_reference_state'])
      if (!canonicalAuditUuid(item.id) || !tenantIds.includes(item.tenant_id) || typeof item.tenant_name !== 'string' || !item.tenant_name || !choice(item.action, auditActionLabels) || !choice(item.outcome, auditOutcomeLabels)) return fail()
      const at = stamp(item.occurred_at); reference(item.actor_ref, item.actor_reference_state); reference(item.target_ref, item.target_reference_state)
      if (expected.action !== null && item.action !== expected.action || expected.outcome !== null && item.outcome !== expected.outcome || expected.actor_ref !== null && item.actor_ref !== expected.actor_ref || expected.target_ref !== null && item.target_ref !== expected.target_ref || expected.occurred_from !== null && at < expected.occurred_from || expected.occurred_to !== null && at >= expected.occurred_to) return fail()
      return item
    })
    exact(r.details, r.items.map(item => item.id))
    for (const metadata of Object.values(r.details)) {
      exact(metadata, ['actor_username', 'module', 'platform'])
      if (metadata.actor_username !== null && (typeof metadata.actor_username !== 'string' || metadata.actor_username.length > 100) || !Object.hasOwn(auditModules, metadata.module) || !Object.hasOwn(auditPlatforms, metadata.platform)) return fail()
      if (search.module && metadata.module !== search.module || search.platform && metadata.platform !== search.platform) return fail()
    }
    const offset = (r.page - 1) * r.size
    if (!Number.isSafeInteger(offset) || r.items.length !== Math.min(r.size, Math.max(0, r.total - offset)) || new Set(r.items.map(item => item.id)).size !== r.items.length) return fail()
    for (let i = 1; i < r.items.length; i++) { const prev = r.items[i - 1], next = r.items[i]; if (stamp(prev.occurred_at) < stamp(next.occurred_at) || stamp(prev.occurred_at) === stamp(next.occurred_at) && prev.id > next.id) return fail() }
    return r
  } catch { return fail() }
}
function owner() { const s = useSession.getState(); return { token: s.token, user: JSON.stringify(s.user), generation: s.generation, permissions: JSON.stringify(s.permissions), audience: s.audience, scopeTenantIds: [...s.scopeTenantIds], scopePlaceIds: [...s.scopePlaceIds], scopeInitialized: s.scopeInitialized, page: s.page, allowed: !!s.user && s.user.user_type === 'PLATFORM' && !s.user.must_change_password && s.audience === 'management' && s.permissions.includes('audit:read') && s.page === 'audit-records' } }
function cancelled(): never { throw new DOMException('审计读取已取消', 'AbortError') }
export async function getAuditRecords(applied: AuditFilters, page: number, size: number | undefined, signal: AbortSignal, live: () => boolean, search: AuditLogSearch = emptyAuditSearch): Promise<AuditRecords> {
  const filters = normalized(applied)
  if (!Number.isSafeInteger(page) || page < 1 || page > 1000000 || size !== undefined && (!Number.isSafeInteger(size) || size < 1 || size > 100)) throw new ApiError(400, 'AUDIT_QUERY_INVALID', '审计分页未确认。')
  const before = owner(), current = () => !signal.aborted && live() && JSON.stringify(owner()) === JSON.stringify(before)
  if (!current()) return cancelled()
  if (!before.allowed || !before.token) throw new ApiError(403, 'AUDIT_SCOPE_UNCONFIRMED', '当前无审计读取权限。')
  const query = new URLSearchParams({ page: String(page), scope_selected: 'true', scope_tenant_ids: before.scopeTenantIds.join(','), scope_place_ids: before.scopePlaceIds.join(',') }); if (size !== undefined) query.set('size', String(size)); for (const [key, value] of Object.entries(filters)) if (value !== null) query.set(key, value)
  for (const [key, value] of Object.entries(compileAuditSearch(search))) if (value !== null) query.set(key, value)
  const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${before.token}` })
  let response: Response
  try { response = await fetch(`/api/management/audit-records?${query}`, { method: 'GET', signal, headers, cache: 'no-store', credentials: 'omit', redirect: 'error' }) } catch { if (!current()) return cancelled(); throw new ApiError(0, 'AUDIT_NETWORK_UNCONFIRMED', '审计读取网络未确认，请显式重试。') }
  if (!current()) return cancelled()
  if (response.status === 401) { useSession.getState().clear('登录已失效，请重新登录。'); throw new ApiError(401, 'AUDIT_SESSION_EXPIRED', '登录已失效，请重新登录。') }
  if (!response.ok) throw new ApiError(response.status, 'AUDIT_READ_UNCONFIRMED', '审计读取未确认；不保留旧行，请显式核验。')
  if (!current()) return cancelled()
  let text: string
  try { text = await response.text() } catch { if (!current()) return cancelled(); return fail() }
  if (!current()) return cancelled()
  const result = readAuditRecords(text, filters, page, size, { tenantIds: before.scopeTenantIds, placeIds: before.scopePlaceIds }, search)
  if (!current()) return cancelled()
  return result
}

export async function exportAuditRecords(applied: AuditFilters, search: AuditLogSearch, signal: AbortSignal, live: () => boolean): Promise<Blob> {
  const before = owner(), current = () => !signal.aborted && live() && JSON.stringify(owner()) === JSON.stringify(before)
  if (!current()) return cancelled()
  if (!before.allowed || !before.token || !useSession.getState().permissions.includes('exports:read')) throw new ApiError(403, 'AUDIT_EXPORT_FORBIDDEN', '导出日志需要审计及导出读取权限')
  const query = new URLSearchParams({ scope_selected: 'true', scope_tenant_ids: before.scopeTenantIds.join(','), scope_place_ids: before.scopePlaceIds.join(',') })
  for (const [key, value] of Object.entries({ ...normalized(applied), ...compileAuditSearch(search) })) if (value !== null) query.set(key, value)
  let response: Response
  try { response = await fetch(`/api/management/audit-records/export?${query}`, { method: 'GET', signal, headers: { Accept: 'text/csv', Authorization: `Bearer ${before.token}` }, cache: 'no-store', credentials: 'omit', redirect: 'error' }) }
  catch { if (!current()) return cancelled(); throw new ApiError(0, 'AUDIT_NETWORK_UNCONFIRMED', '日志导出网络未确认，请显式重试') }
  if (!current()) return cancelled()
  if (response.status === 401) { useSession.getState().clear('登录已失效，请重新登录。'); throw new ApiError(401, 'AUDIT_SESSION_EXPIRED', '登录已失效，请重新登录。') }
  if (!response.ok || !response.headers.get('Content-Type')?.startsWith('text/csv')) throw new ApiError(response.status, 'AUDIT_EXPORT_UNCONFIRMED', '日志导出未确认，未生成替代文件')
  const blob = await response.blob()
  if (!current()) return cancelled()
  return blob
}
