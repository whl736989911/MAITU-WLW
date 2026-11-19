import { request as baseRequest, ApiError, scopeQuery } from './api'
import { useSession } from './store'
import { canonicalAlarmInstant } from './alarm-workbench'
import type { Paginated } from './types'
function request<T>(...args: Parameters<typeof baseRequest<T>>): Promise<T> { return baseRequest<T>(...args).catch(error => { if (error instanceof ApiError && error.code === 'INVALID_RESPONSE') throw new ApiError(error.status, 'NOTIFICATION_CONTACT_RESPONSE_UNCONFIRMED', '联系人响应未确认：格式不符合合同', error.requestId); throw error }) }
export const contactTypes = ['FIRE_RESPONSIBLE', 'LEGAL_REPRESENTATIVE', 'CONTROL_ROOM', 'DUTY_OFFICER', 'OTHER'] as const
export interface NotificationContact { id: string; name: string; contact_type: string; phone_masked: string; wechat_binding_id: string | null; enabled: boolean; revision: number; created_at: string; tenant_id: string; tenant_name: string }
export type NotificationChannel = 'SMS' | 'VOICE' | 'WECHAT'
export const notificationRuleScopeTypes = ['UNIT', 'PLACE', 'DEVICE'] as const
export type NotificationRuleScopeType = typeof notificationRuleScopeTypes[number]
export type NotificationEventScope = 'FIRE' | 'FAULT'
export interface NotificationRuleScope { scope_type: NotificationRuleScopeType; scope_id: string }
export type NotificationRuleTargetOption = { id: string; name: string }
export type NotificationRuleTargets = Paginated<NotificationRuleTargetOption> & { access: 'NONE' | 'ALL' | 'DEVICE_ONLY' }
export interface NotificationEffectiveQuery { tenantId: string; deviceId: string; eventScope: NotificationEventScope }
export interface NotificationEffectiveReceiver extends Pick<NotificationRuleReceiver, 'contact_id' | 'contact_name' | 'phone_masked' | 'channel' | 'level'> {
  delay_seconds: number
  source_scope_type: NotificationRuleScopeType
  source_scope_id: string
  source_rule_id: string
  source_revision: number
}
export interface NotificationEffectiveReceivers { tenant_id: string; device_id: string; event_scope: NotificationEventScope; receivers: NotificationEffectiveReceiver[] }
export interface NotificationRuleReceiver { channel: NotificationChannel; contact_id: string; level: number; contact_name: string; phone_masked: string; wechat_binding_id?: string | null; wechat_binding_status?: string | null; wechat_openid_masked?: string | null }
export interface NotificationRule extends NotificationRuleScope { id: string; tenant_id: string; tenant_name: string; name: string; event_scope: NotificationEventScope; enabled: boolean; revision: number; created_at: string; receivers: NotificationRuleReceiver[]; sms_escalation_interval_minutes: number | null; voice_escalation_interval_minutes: number | null; wechat_escalation_interval_minutes: number | null }
export interface NotificationRuleInput extends NotificationRuleScope { name: string; enabled: boolean; event_scope: NotificationEventScope; receivers: Array<{ channel: NotificationChannel; contact_id: string; level: number }>; sms_escalation_interval_minutes: number | null; voice_escalation_interval_minutes: number | null; wechat_escalation_interval_minutes: number | null; reason: string; expected_revision?: number }
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function bad(): never { throw new ApiError(200, 'NOTIFICATION_CONTACT_RESPONSE_UNCONFIRMED', '联系人响应未确认') }
function pick<T>(value: unknown, keys: string[]): T { if (!object(value)) return bad(); return Object.fromEntries(keys.filter(key => Object.hasOwn(value, key)).map(key => [key, value[key]])) as T }
const safeInt = (value: unknown, min = 0): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min
function validTimestamp(value: unknown): value is string { try { if (typeof value !== 'string') return false; canonicalAlarmInstant(value, false); return true } catch { return false } }
export function notificationContact(value: unknown): NotificationContact {
  const item = pick<NotificationContact>(value, ['id', 'name', 'contact_type', 'phone_masked', 'wechat_binding_id', 'enabled', 'revision', 'created_at', 'tenant_id', 'tenant_name'])
  if (typeof item.id !== 'string' || !item.id.trim() || typeof item.name !== 'string' || !contactTypes.includes(item.contact_type as typeof contactTypes[number]) || typeof item.phone_masked !== 'string' || !item.phone_masked.includes('****') || /1[3-9]\d{9}/.test(item.phone_masked) || (item.wechat_binding_id !== null && typeof item.wechat_binding_id !== 'string') || typeof item.enabled !== 'boolean' || !safeInt(item.revision) || !validTimestamp(item.created_at) || typeof item.tenant_id !== 'string' || !item.tenant_id.trim() || typeof item.tenant_name !== 'string' || !item.tenant_name.trim()) return bad()
  return item
}
function scopePath(url: string) { const session = useSession.getState(); return session.scopeInitialized ? scopeQuery(url, session.scopeTenantIds, session.scopePlaceIds) : url }
function pageResponse(text: string, expectedPage: number): Paginated<NotificationContact> {
  const value = JSON.parse(text)
  if (!object(value) || !Array.isArray(value.items) || !safeInt(value.total) || !safeInt(value.page, 1) || value.page !== expectedPage || !safeInt(value.size, 1) || value.items.length > value.size || value.items.length > value.total) return bad()
  const items = value.items.map(notificationContact)
  if (new Set(items.map(item => item.id)).size !== items.length) return bad()
  return { items, total: value.total, page: value.page, size: value.size }
}
function ruleBad(): never { throw new ApiError(200, 'NOTIFICATION_RULE_RESPONSE_UNCONFIRMED', '通知规则响应未确认') }
export function requestNotificationRule<T>(path: string, options: Parameters<typeof baseRequest<T>>[1]): Promise<T> {
  return baseRequest<T>(path, options).catch(error => {
    if (error instanceof ApiError && error.code === 'INVALID_RESPONSE') {
      throw new ApiError(error.status, 'NOTIFICATION_RULE_RESPONSE_UNCONFIRMED', '通知规则响应未确认：格式或目标与请求不符', error.requestId)
    }
    throw error
  })
}
export function notificationRuleWriteUncertain(error: unknown): boolean {
  return error instanceof ApiError && (error.status >= 500 || error.code === 'NETWORK_ERROR' || (error.status >= 200 && error.status < 300 && error.code === 'NOTIFICATION_RULE_RESPONSE_UNCONFIRMED'))
}
const nonblank = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const maskedPhone = (value: unknown): value is string => typeof value === 'string' && value.includes('****') && !/1[3-9]\d{9}/.test(value)
const ruleScopeType = (value: unknown): value is NotificationRuleScopeType => value === 'UNIT' || value === 'PLACE' || value === 'DEVICE'
function receiverFields(value: unknown): Pick<NotificationRuleReceiver, 'channel' | 'contact_id' | 'level' | 'contact_name' | 'phone_masked'> {
  if (!object(value) || (value.channel !== 'SMS' && value.channel !== 'VOICE' && value.channel !== 'WECHAT') || !nonblank(value.contact_id) || !safeInt(value.level, 1) || value.level > 5 || typeof value.contact_name !== 'string' || !maskedPhone(value.phone_masked)) return ruleBad()
  return { channel: value.channel, contact_id: value.contact_id, level: value.level, contact_name: value.contact_name, phone_masked: value.phone_masked }
}
function ruleReceiver(value: unknown): NotificationRuleReceiver {
  const receiver = receiverFields(value)
  if (!object(value)) return ruleBad()
  for (const key of ['wechat_binding_id', 'wechat_binding_status', 'wechat_openid_masked'] as const) if (value[key] !== undefined && value[key] !== null && typeof value[key] !== 'string') return ruleBad()
  if (receiver.channel === 'WECHAT' && !nonblank(value.wechat_binding_id)) return ruleBad()
  return { ...receiver,
    wechat_binding_id: typeof value.wechat_binding_id === 'string' ? value.wechat_binding_id : null,
    wechat_binding_status: typeof value.wechat_binding_status === 'string' ? value.wechat_binding_status : null,
    wechat_openid_masked: typeof value.wechat_openid_masked === 'string' ? value.wechat_openid_masked : null }
}
export function notificationRule(item: unknown): NotificationRule {
  if (!object(item) || !nonblank(item.id) || !nonblank(item.tenant_id) || !nonblank(item.tenant_name) || !nonblank(item.name) || (item.event_scope !== 'FIRE' && item.event_scope !== 'FAULT') || typeof item.enabled !== 'boolean' || !safeInt(item.revision) || !validTimestamp(item.created_at) || !Array.isArray(item.receivers)) return ruleBad()
  if (!ruleScopeType(item.scope_type) || !nonblank(item.scope_id) || (item.scope_type === 'UNIT' && item.scope_id !== item.tenant_id)) return ruleBad()
  const receivers = item.receivers.map(ruleReceiver)
  if (!receivers.length) return ruleBad()
  const intervals = [item.sms_escalation_interval_minutes, item.voice_escalation_interval_minutes, item.wechat_escalation_interval_minutes]
  if (intervals.some(interval => interval !== null && (!safeInt(interval) || interval > 60))) return ruleBad()
  for (const channel of ['SMS', 'VOICE'] as const) {
    const channelReceivers = receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level)
    if ((!channelReceivers.length && item.scope_type === 'UNIT') || channelReceivers.length > 5 || channelReceivers.some((receiver, index) => receiver.level !== index + 1) || new Set(channelReceivers.map(receiver => receiver.contact_id)).size !== channelReceivers.length) return ruleBad()
    const interval = item[channel === 'SMS' ? 'sms_escalation_interval_minutes' : 'voice_escalation_interval_minutes']
    if ((channelReceivers.length <= 1 && interval !== null && interval !== 0) || (channelReceivers.length > 1 && interval === null)) return ruleBad()
  }
  const wechatReceivers = receivers.filter(receiver => receiver.channel === 'WECHAT').sort((a, b) => a.level - b.level)
  if (wechatReceivers.length > 5 || (wechatReceivers.length && (wechatReceivers.some((receiver, index) => receiver.level !== index + 1) || new Set(wechatReceivers.map(receiver => receiver.contact_id)).size !== wechatReceivers.length))) return ruleBad()
  const wechatInterval = item.wechat_escalation_interval_minutes
  if (wechatReceivers.length ? ((wechatReceivers.length === 1 && wechatInterval !== null && wechatInterval !== 0) || (wechatReceivers.length > 1 && wechatInterval === null)) : wechatInterval !== null) return ruleBad()
  return { id: item.id, tenant_id: item.tenant_id, tenant_name: item.tenant_name, scope_type: item.scope_type, scope_id: item.scope_id, name: item.name, event_scope: item.event_scope, enabled: item.enabled, revision: item.revision, created_at: item.created_at, receivers, sms_escalation_interval_minutes: item.sms_escalation_interval_minutes as number | null, voice_escalation_interval_minutes: item.voice_escalation_interval_minutes as number | null, wechat_escalation_interval_minutes: item.wechat_escalation_interval_minutes as number | null }
}
export function rulePageResponse(text: string, expectedPage: number, scope?: NotificationRuleScope, tenantId?: string): Paginated<NotificationRule> {
  const value = JSON.parse(text)
  if (!object(value) || !Array.isArray(value.items) || !safeInt(value.total) || !safeInt(value.page, 1) || value.page !== expectedPage || !safeInt(value.size, 1) || value.items.length > value.size || value.items.length > value.total) return ruleBad()
  const items = value.items.map(notificationRule)
  if (new Set(items.map(item => item.id)).size !== items.length || items.some(item => (tenantId && item.tenant_id !== tenantId) || (scope && (item.scope_type !== scope.scope_type || item.scope_id !== scope.scope_id)))) return ruleBad()
  return { items, total: value.total, page: value.page, size: value.size }
}
export function ruleResult(text: string, tenantId: string, id: string | null, scope?: NotificationRuleScope): NotificationRule {
  const rule = notificationRule(JSON.parse(text))
  if (rule.tenant_id !== tenantId || (id !== null && rule.id !== id) || (scope && (rule.scope_type !== scope.scope_type || rule.scope_id !== scope.scope_id))) return ruleBad()
  return rule
}
export function ruleListQuery(page: number, keyword: string, scope?: NotificationRuleScope) {
  const params = new URLSearchParams({ page: String(page), size: '20' })
  if (keyword) params.set('keyword', keyword)
  if (scope) {
    params.set('scopeType', scope.scope_type)
    params.set('scopeId', scope.scope_id)
  }
  return params
}
export function ruleWriteBody(input: NotificationRuleInput, id: string | null): string {
  const { name, enabled, event_scope, scope_type, scope_id, sms_escalation_interval_minutes, voice_escalation_interval_minutes, wechat_escalation_interval_minutes, reason, expected_revision } = input
  const receivers = input.receivers.map(({ channel, contact_id, level }) => ({ channel, contact_id, level }))
  return JSON.stringify({ name, enabled, event_scope, scope_type, scope_id, receivers, sms_escalation_interval_minutes, voice_escalation_interval_minutes, wechat_escalation_interval_minutes, reason, ...(id !== null ? { expected_revision } : {}) })
}
export function effectiveRulePath(path: string, { deviceId, eventScope }: NotificationEffectiveQuery) {
  return `${path}/effective?${new URLSearchParams({ deviceId, eventScope })}`
}
export function effectiveRuleResponse(text: string, expected: NotificationEffectiveQuery): NotificationEffectiveReceivers {
  const value: unknown = JSON.parse(text)
  if (!object(value) || value.tenant_id !== expected.tenantId || value.device_id !== expected.deviceId || value.event_scope !== expected.eventScope || !Array.isArray(value.receivers)) return ruleBad()
  const receivers = value.receivers.map((row: unknown): NotificationEffectiveReceiver => {
    if (!object(row) || !ruleScopeType(row.source_scope_type) || !nonblank(row.source_scope_id) || !nonblank(row.source_rule_id) || !safeInt(row.source_revision) || !safeInt(row.delay_seconds)) return ruleBad()
    if ((row.source_scope_type === 'UNIT' && row.source_scope_id !== expected.tenantId) || (row.source_scope_type === 'DEVICE' && row.source_scope_id !== expected.deviceId)) return ruleBad()
    return { ...receiverFields(row), delay_seconds: row.delay_seconds, source_scope_type: row.source_scope_type, source_scope_id: row.source_scope_id, source_rule_id: row.source_rule_id, source_revision: row.source_revision }
  })
  return { tenant_id: expected.tenantId, device_id: expected.deviceId, event_scope: expected.eventScope, receivers }
}

function ruleTargetsResponse(text: string, tenantId: string, type: NotificationRuleScopeType, page: number, targetId?: string): NotificationRuleTargets {
  const value: unknown = JSON.parse(text)
  if (!object(value) || value.tenant_id !== tenantId || value.scope_type !== type || value.page !== page ||
      !safeInt(value.total) || !safeInt(value.size, 1) || !Array.isArray(value.items) ||
      (value.access !== 'NONE' && value.access !== 'ALL' && value.access !== 'DEVICE_ONLY')) return ruleBad()
  const { access, total, size } = value
  if (value.items.length !== Math.min(size, Math.max(0, total - (page - 1) * size)) ||
      ((access === 'NONE' || type === 'UNIT' || (access === 'DEVICE_ONLY' && type === 'PLACE')) && total !== 0) ||
      (targetId !== undefined && total > 1)) return ruleBad()
  const seen = new Set<string>()
  const items = value.items.map((item: unknown) => {
    if (!object(item) || !nonblank(item.id) || typeof item.name !== 'string' || seen.has(item.id) ||
        (targetId !== undefined && item.id !== targetId)) return ruleBad()
    seen.add(item.id)
    return { id: item.id, name: item.name }
  })
  return { access, items, total, page, size }
}

const managementRulesPath = '/management/notification-rules'
export const notificationApi = {
  contacts: (page: number, keyword = '', signal?: AbortSignal, tenantId?: string, enabled?: boolean) => {
    const params = new URLSearchParams({ page: String(page) }); if (keyword) params.set('keyword', keyword); if (enabled !== undefined) params.set('enabled', String(enabled))
    const path = `/management/notification-contacts?${params}`
    return request<Paginated<NotificationContact>>(tenantId ? path : scopePath(path), { tenantScoped: true, tenantOptional: true, tenantId, signal, decode: text => pageResponse(text, page) })
  },
  contact: (id: string, signal?: AbortSignal, tenantId?: string) => request<NotificationContact>(`/management/notification-contacts/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: text => notificationContact(JSON.parse(text)) }),
  saveContact: (id: string | null, input: Record<string, unknown>, key: string, signal?: AbortSignal, tenantId?: string) => request<NotificationContact>(`/management/notification-contacts${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PUT' : 'POST', tenantScoped: true, tenantId, signal, headers: { 'Idempotency-Key': key }, body: JSON.stringify(Object.fromEntries(['name', 'contact_type', 'phone', 'wechat_binding_id', 'enabled', 'reason', ...(id ? ['expected_revision'] : [])].filter(field => Object.hasOwn(input, field)).map(field => [field, input[field]]))), decode: text => notificationContact(JSON.parse(text)) }),
  rules: (page: number, keyword = '', signal?: AbortSignal, scope?: NotificationRuleScope, tenantId?: string) => {
    const path = scopePath(`${managementRulesPath}?${ruleListQuery(page, keyword, scope)}`)
    return requestNotificationRule<Paginated<NotificationRule>>(path, { tenantScoped: true, tenantOptional: !scope, tenantId, signal, decode: text => rulePageResponse(text, page, scope, tenantId) })
  },
  rule: (id: string, signal: AbortSignal | undefined, tenantId: string) => requestNotificationRule<NotificationRule>(`${managementRulesPath}/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: text => ruleResult(text, tenantId, id) }),
  targets: (tenantId: string, type: NotificationRuleScopeType, page: number, keyword: string, targetId: string | undefined, signal: AbortSignal) => {
    const params = new URLSearchParams({ scopeType: type, page: String(page) })
    if (keyword) params.set('keyword', keyword)
    if (targetId !== undefined) params.set('targetId', targetId)
    return requestNotificationRule<NotificationRuleTargets>(`${managementRulesPath}/targets?${params}`, { tenantScoped: true, tenantId, signal, decode: text => ruleTargetsResponse(text, tenantId, type, page, targetId) })
  },
  saveRule: (id: string | null, input: NotificationRuleInput, key: string, signal: AbortSignal | undefined, tenantId: string) =>
    requestNotificationRule<NotificationRule>(`${managementRulesPath}${id === null ? '' : `/${encodeURIComponent(id)}`}`, { method: id === null ? 'POST' : 'PUT', tenantScoped: true, tenantId, signal, headers: { 'Idempotency-Key': key }, body: ruleWriteBody(input, id), decode: text => ruleResult(text, tenantId, id, input) }),
  effective: (query: NotificationEffectiveQuery, signal?: AbortSignal) =>
    requestNotificationRule<NotificationEffectiveReceivers>(effectiveRulePath(managementRulesPath, query), { tenantScoped: true, tenantId: query.tenantId, signal, decode: text => effectiveRuleResponse(text, query) }),
}
export function notificationPhone(value: string, required: boolean): string | null { const phone = value.trim(); if (!phone && !required) return null; if (/^(?:\+86)?1[3-9]\d{9}$/.test(phone)) return phone; if (/^0\d{2,3}-\d{7,8}$/.test(phone)) return phone; throw new Error('号码仅支持中国11位手机号/可选+86，或0区号-7/8位座机；格式不代表实际可接通') }
