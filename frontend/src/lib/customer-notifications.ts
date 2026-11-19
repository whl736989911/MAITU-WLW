import { ApiError, request } from './api'
import { useSession } from './store'
import { effectiveRulePath, effectiveRuleResponse, notificationContact, requestNotificationRule, ruleListQuery, rulePageResponse, ruleResult, ruleWriteBody, type NotificationContact, type NotificationEffectiveQuery, type NotificationEffectiveReceivers, type NotificationRule, type NotificationRuleInput, type NotificationRuleScope } from './notifications'
import type { Paginated } from './types'

export interface CustomerNotificationSettings { tenant_id: string }
export interface CustomerContactInput { name: string; contact_type: string; phone: string | null; wechat_binding_id?: string; enabled: boolean; expected_revision?: number; reason: string }
const rulesPath = '/customer/notification-settings/rules'
function ownTenant() {
  const session = useSession.getState()
  if (session.audience !== 'customer' || !session.user?.tenant_id) throw new ApiError(403, 'CUSTOMER_UNIT_REQUIRED', '此入口只维护当前客户单位')
  return session.user.tenant_id
}
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function page<T>(text: string, expected: number, decode: (value: unknown) => T): Paginated<T> {
  const value: unknown = JSON.parse(text)
  if (!object(value) || !Array.isArray(value.items) || !integer(value.total) || !integer(value.page) || value.page !== expected || !integer(value.size) || value.size < 1 || value.items.length > value.size || value.items.length > value.total) throw new Error('单位通知配置列表响应格式无效')
  return { items: value.items.map(decode), total: value.total, page: value.page, size: value.size }
}
export const customerNotificationApi = {
  async rules(pageNumber: number, keyword = '', signal?: AbortSignal, scope?: NotificationRuleScope) {
    const tenant = ownTenant()
    return requestNotificationRule<Paginated<NotificationRule>>(`${rulesPath}?${ruleListQuery(pageNumber, keyword, scope)}`, { tenantScoped: true, signal, decode: text => rulePageResponse(text, pageNumber, scope, tenant) })
  },
  settings: (signal?: AbortSignal) => request<CustomerNotificationSettings>('/customer/notification-settings', { tenantScoped: true, signal, decode: text => { const v: unknown = JSON.parse(text); if (!object(v) || typeof v.tenant_id !== 'string' || !v.tenant_id) throw new Error('单位通知配置响应格式无效'); return { tenant_id: v.tenant_id } } }),
  contacts: (pageNumber: number, keyword = '', signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(pageNumber), size: '20' }); if (keyword) params.set('keyword', keyword)
    return request<Paginated<NotificationContact>>(`/customer/notification-settings/contacts?${params}`, { tenantScoped: true, signal, decode: text => page(text, pageNumber, notificationContact) })
  },
  async rule(id: string, signal?: AbortSignal) {
    const tenant = ownTenant()
    return requestNotificationRule<NotificationRule>(`${rulesPath}/${encodeURIComponent(id)}`, { tenantScoped: true, signal, decode: text => ruleResult(text, tenant, id) })
  },
  saveContact: (id: string | null, input: CustomerContactInput, key: string, signal?: AbortSignal) => request<NotificationContact>(`/customer/notification-settings/contacts${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PUT' : 'POST', tenantScoped: true, signal, headers: { 'Idempotency-Key': key }, body: JSON.stringify(input), decode: text => notificationContact(JSON.parse(text)) }),
  async saveRule(id: string | null, input: NotificationRuleInput, key: string, signal?: AbortSignal) {
    const tenant = ownTenant()
    return requestNotificationRule<NotificationRule>(`${rulesPath}${id === null ? '' : `/${encodeURIComponent(id)}`}`, { method: id === null ? 'POST' : 'PUT', tenantScoped: true, signal, headers: { 'Idempotency-Key': key }, body: ruleWriteBody(input, id), decode: text => ruleResult(text, tenant, id, input) })
  },
  async effective(query: NotificationEffectiveQuery, signal?: AbortSignal) {
    if (query.tenantId !== ownTenant()) throw new ApiError(403, 'CUSTOMER_UNIT_REQUIRED', '仅可查询本单位设备')
    return requestNotificationRule<NotificationEffectiveReceivers>(effectiveRulePath(rulesPath, query), { tenantScoped: true, signal, decode: text => effectiveRuleResponse(text, query) })
  },
}
