import { request } from './api'
import { notificationContact, notificationRule, type NotificationContact, type NotificationRule } from './notifications'
import type { Paginated } from './types'

export interface CustomerNotificationSettings { tenant_id: string }
export interface CustomerContactInput { name: string; contact_type: string; phone: string | null; wechat_binding_id?: string; enabled: boolean; expected_revision?: number; reason: string }
export interface CustomerRuleInput { name: string; enabled: boolean; event_scope: 'FIRE' | 'FAULT'; receivers: Array<{ channel: 'SMS' | 'VOICE' | 'WECHAT'; contact_id: string; level: number }>; sms_escalation_interval_minutes: number | null; voice_escalation_interval_minutes: number | null; wechat_escalation_interval_minutes: number | null; expected_revision?: number; reason: string }
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function page<T>(text: string, expected: number, decode: (value: unknown) => T): Paginated<T> {
  const value: unknown = JSON.parse(text)
  if (!object(value) || !Array.isArray(value.items) || !integer(value.total) || !integer(value.page) || value.page !== expected || !integer(value.size) || value.size < 1 || value.items.length > value.size || value.items.length > value.total) throw new Error('单位通知配置列表响应格式无效')
  return { items: value.items.map(decode), total: value.total, page: value.page, size: value.size }
}
export const customerNotificationApi = {
  rules: (pageNumber: number, keyword = '', signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(pageNumber), size: '20' }); if (keyword) params.set('keyword', keyword)
    return request<Paginated<NotificationRule>>(`/customer/notification-settings/rules?${params}`, { tenantScoped: true, signal, decode: text => page(text, pageNumber, notificationRule) })
  },
  settings: (signal?: AbortSignal) => request<CustomerNotificationSettings>('/customer/notification-settings', { tenantScoped: true, signal, decode: text => { const v: unknown = JSON.parse(text); if (!object(v) || typeof v.tenant_id !== 'string' || !v.tenant_id) throw new Error('单位通知配置响应格式无效'); return { tenant_id: v.tenant_id } } }),
  contacts: (pageNumber: number, keyword = '', signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(pageNumber), size: '20' }); if (keyword) params.set('keyword', keyword)
    return request<Paginated<NotificationContact>>(`/customer/notification-settings/contacts?${params}`, { tenantScoped: true, signal, decode: text => page(text, pageNumber, notificationContact) })
  },
  rule: (id: string, signal?: AbortSignal) => request<NotificationRule>(`/customer/notification-settings/rules/${encodeURIComponent(id)}`, { tenantScoped: true, signal, decode: text => notificationRule(JSON.parse(text)) }),
  saveContact: (id: string | null, input: CustomerContactInput, key: string, signal?: AbortSignal) => request<NotificationContact>(`/customer/notification-settings/contacts${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PUT' : 'POST', tenantScoped: true, signal, headers: { 'Idempotency-Key': key }, body: JSON.stringify(input), decode: text => notificationContact(JSON.parse(text)) }),
  saveRule: (id: string | null, input: CustomerRuleInput, key: string, signal?: AbortSignal) => request<NotificationRule>(`/customer/notification-settings/rules${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PUT' : 'POST', tenantScoped: true, signal, headers: { 'Idempotency-Key': key }, body: JSON.stringify(input), decode: text => notificationRule(JSON.parse(text)) }),
}
