import { request as baseRequest, ApiError, scopeQuery } from './api'
import { useSession } from './store'
import { canonicalAlarmInstant } from './alarm-workbench'
import type { Paginated } from './types'
function request<T>(...args: Parameters<typeof baseRequest<T>>): Promise<T> { return baseRequest<T>(...args).catch(error => { if (error instanceof ApiError && error.code === 'INVALID_RESPONSE') throw new ApiError(error.status, 'NOTIFICATION_CONTACT_RESPONSE_UNCONFIRMED', '联系人响应未确认：格式不符合合同', error.requestId); throw error }) }
export const contactTypes = ['FIRE_RESPONSIBLE', 'LEGAL_REPRESENTATIVE', 'CONTROL_ROOM', 'DUTY_OFFICER', 'OTHER'] as const
export interface NotificationContact { id: string; name: string; contact_type: string; phone_masked: string; wechat_binding_id: string | null; enabled: boolean; revision: number; created_at: string; tenant_id: string; tenant_name: string }
export type NotificationChannel = 'SMS' | 'VOICE' | 'WECHAT'
export interface NotificationRuleReceiver { channel: NotificationChannel; contact_id: string; level: number; contact_name: string; phone_masked: string; wechat_binding_id?: string | null; wechat_binding_status?: string | null; wechat_openid_masked?: string | null }
export interface NotificationRule { id: string; tenant_id: string; tenant_name: string; name: string; event_scope: 'FIRE' | 'FAULT'; enabled: boolean; revision: number; created_at: string; receivers: NotificationRuleReceiver[]; sms_escalation_interval_minutes: number | null; voice_escalation_interval_minutes: number | null; wechat_escalation_interval_minutes: number | null }
export interface NotificationRuleInput { name: string; enabled: boolean; event_scope: 'FIRE' | 'FAULT'; receivers: Array<{ channel: NotificationChannel; contact_id: string; level: number }>; sms_escalation_interval_minutes: number | null; voice_escalation_interval_minutes: number | null; wechat_escalation_interval_minutes: number | null; reason: string; expected_revision?: number }
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
function ruleReceiver(value: unknown): NotificationRuleReceiver {
  if (!object(value) || (value.channel !== 'SMS' && value.channel !== 'VOICE' && value.channel !== 'WECHAT') || typeof value.contact_id !== 'string' || !value.contact_id.trim() || !safeInt(value.level, 1) || value.level > 5 || typeof value.contact_name !== 'string' || typeof value.phone_masked !== 'string' || !value.phone_masked.includes('****') || /1[3-9]\d{9}/.test(value.phone_masked)) return ruleBad()
  for (const key of ['wechat_binding_id', 'wechat_binding_status', 'wechat_openid_masked'] as const) if (value[key] !== undefined && value[key] !== null && typeof value[key] !== 'string') return ruleBad()
  if (value.channel === 'WECHAT' && (typeof value.wechat_binding_id !== 'string' || !value.wechat_binding_id)) return ruleBad()
  return { channel: value.channel, contact_id: value.contact_id, level: value.level, contact_name: value.contact_name, phone_masked: value.phone_masked,
    wechat_binding_id: typeof value.wechat_binding_id === 'string' ? value.wechat_binding_id : null,
    wechat_binding_status: typeof value.wechat_binding_status === 'string' ? value.wechat_binding_status : null,
    wechat_openid_masked: typeof value.wechat_openid_masked === 'string' ? value.wechat_openid_masked : null }
}
export function notificationRule(value: unknown): NotificationRule {
  if (!object(value)) return ruleBad()
  const item = value as Record<string, unknown>
  if (typeof item.id !== 'string' || !item.id.trim() || typeof item.tenant_id !== 'string' || !item.tenant_id.trim() || typeof item.tenant_name !== 'string' || !item.tenant_name.trim() || typeof item.name !== 'string' || !item.name.trim() || (item.event_scope !== 'FIRE' && item.event_scope !== 'FAULT') || typeof item.enabled !== 'boolean' || !safeInt(item.revision) || !validTimestamp(item.created_at) || !Array.isArray(item.receivers)) return ruleBad()
  const receivers = item.receivers.map(ruleReceiver)
  const intervals = [item.sms_escalation_interval_minutes, item.voice_escalation_interval_minutes, item.wechat_escalation_interval_minutes]
  if (intervals.some(interval => interval !== null && (!safeInt(interval) || interval > 60))) return ruleBad()
  for (const channel of ['SMS', 'VOICE'] as const) {
    const channelReceivers = receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level)
    if (!channelReceivers.length || channelReceivers.length > 5 || channelReceivers.some((receiver, index) => receiver.level !== index + 1) || new Set(channelReceivers.map(receiver => receiver.contact_id)).size !== channelReceivers.length) return ruleBad()
    const interval = item[channel === 'SMS' ? 'sms_escalation_interval_minutes' : 'voice_escalation_interval_minutes']
    if ((channelReceivers.length === 1 && interval !== null && interval !== 0) || (channelReceivers.length > 1 && interval === null)) return ruleBad()
  }
  const wechatReceivers = receivers.filter(receiver => receiver.channel === 'WECHAT').sort((a, b) => a.level - b.level)
  if (wechatReceivers.length > 5 || (wechatReceivers.length && (wechatReceivers.some((receiver, index) => receiver.level !== index + 1) || new Set(wechatReceivers.map(receiver => receiver.contact_id)).size !== wechatReceivers.length))) return ruleBad()
  const wechatInterval = item.wechat_escalation_interval_minutes
  if (wechatReceivers.length ? ((wechatReceivers.length === 1 && wechatInterval !== null && wechatInterval !== 0) || (wechatReceivers.length > 1 && wechatInterval === null)) : wechatInterval !== null) return ruleBad()
  return { id: item.id, tenant_id: item.tenant_id, tenant_name: item.tenant_name, name: item.name, event_scope: item.event_scope, enabled: item.enabled, revision: item.revision, created_at: item.created_at, receivers, sms_escalation_interval_minutes: item.sms_escalation_interval_minutes as number | null, voice_escalation_interval_minutes: item.voice_escalation_interval_minutes as number | null, wechat_escalation_interval_minutes: item.wechat_escalation_interval_minutes as number | null }
}
function rulePageResponse(text: string, expectedPage: number): Paginated<NotificationRule> {
  const value = JSON.parse(text)
  if (!object(value) || !Array.isArray(value.items) || !safeInt(value.total) || !safeInt(value.page, 1) || value.page !== expectedPage || !safeInt(value.size, 1) || value.items.length > value.size || value.items.length > value.total) return ruleBad()
  const items = value.items.map(notificationRule)
  if (new Set(items.map(item => item.id)).size !== items.length) return ruleBad()
  return { items, total: value.total, page: value.page, size: value.size }
}
export const notificationApi = {
  contacts: (page: number, keyword = '', signal?: AbortSignal, tenantId?: string, enabled?: boolean) => {
    const params = new URLSearchParams({ page: String(page) }); if (keyword) params.set('keyword', keyword); if (enabled !== undefined) params.set('enabled', String(enabled))
    return request<Paginated<NotificationContact>>(scopePath(`/management/notification-contacts?${params}`), { tenantScoped: true, tenantOptional: true, tenantId, signal, decode: text => pageResponse(text, page) })
  },
  contact: (id: string, signal?: AbortSignal, tenantId?: string) => request<NotificationContact>(`/management/notification-contacts/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: text => notificationContact(JSON.parse(text)) }),
  saveContact: (id: string | null, input: Record<string, unknown>, key: string, signal?: AbortSignal, tenantId?: string) => request<NotificationContact>(`/management/notification-contacts${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PUT' : 'POST', tenantScoped: true, tenantId, signal, headers: { 'Idempotency-Key': key }, body: JSON.stringify(Object.fromEntries(['name', 'contact_type', 'phone', 'wechat_binding_id', 'enabled', 'reason', ...(id ? ['expected_revision'] : [])].filter(field => Object.hasOwn(input, field)).map(field => [field, input[field]]))), decode: text => notificationContact(JSON.parse(text)) }),
  rules: (page: number, keyword = '', signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(page), size: '20' }); if (keyword) params.set('keyword', keyword)
    return request<Paginated<NotificationRule>>(scopePath(`/management/notification-rules?${params}`), { tenantScoped: true, tenantOptional: true, signal, decode: text => rulePageResponse(text, page) })
  },
  rule: (id: string, signal?: AbortSignal, tenantId?: string) => request<NotificationRule>(`/management/notification-rules/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal, decode: text => notificationRule(JSON.parse(text)) }),
  saveRule: (id: string | null, input: NotificationRuleInput, key: string, signal?: AbortSignal, tenantId?: string) => request<NotificationRule>(`/management/notification-rules${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PUT' : 'POST', tenantScoped: true, tenantId, signal, headers: { 'Idempotency-Key': key }, body: JSON.stringify(Object.fromEntries(['name', 'enabled', 'event_scope', 'receivers', 'sms_escalation_interval_minutes', 'voice_escalation_interval_minutes', 'wechat_escalation_interval_minutes', 'expected_revision', 'reason'].filter(field => Object.hasOwn(input, field)).map(field => [field, input[field as keyof NotificationRuleInput]]))), decode: text => notificationRule(JSON.parse(text)) }),
}
export function notificationPhone(value: string, required: boolean): string | null { const phone = value.trim(); if (!phone && !required) return null; if (/^(?:\+86)?1[3-9]\d{9}$/.test(phone)) return phone; if (/^0\d{2,3}-\d{7,8}$/.test(phone)) return phone; throw new Error('号码仅支持中国11位手机号/可选+86，或0区号-7/8位座机；格式不代表实际可接通') }
