import { ApiError, request, scopeQuery } from './api'
import type { Paginated } from './types'

export interface ProviderAttempt {
  id: string; tenant_id: string; event_kind: 'FIRE' | 'FAULT'; source_kind: 'ALARM' | 'RAW_FAULT' | 'LEGACY_FAULT'; source_id: string; alarm_id: string | null; device_id: string; contact_id: string
  channel: 'SMS' | 'VOICE'; attempt_no: number; status: 'PENDING' | 'SENT' | 'FAILED' | 'UNKNOWN' | 'SUPPRESSED' | 'RESOURCE_UNAVAILABLE'
  provider_code: string | null; provider_id: string | null; created_at: string; finished_at: string | null
}
const bad = (): never => { throw new ApiError(200, 'NOTIFICATION_ATTEMPT_RESPONSE_UNCONFIRMED', '通知尝试记录响应不符合合同') }
const integer = (value: unknown, min = 0): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
function attempt(value: unknown): ProviderAttempt {
  if (!object(value) || typeof value.id !== 'string' || !value.id || typeof value.tenant_id !== 'string' || !value.tenant_id || (value.event_kind !== 'FIRE' && value.event_kind !== 'FAULT') || !['ALARM', 'RAW_FAULT', 'LEGACY_FAULT'].includes(String(value.source_kind)) || typeof value.source_id !== 'string' || !value.source_id || (value.alarm_id !== null && typeof value.alarm_id !== 'string') || (value.event_kind === 'FIRE' ? value.source_kind !== 'ALARM' || value.alarm_id !== value.source_id : value.source_kind === 'ALARM' || value.alarm_id !== null) || typeof value.device_id !== 'string' || !value.device_id || typeof value.contact_id !== 'string' || !value.contact_id || (value.channel !== 'SMS' && value.channel !== 'VOICE') || !integer(value.attempt_no, 1) || !['PENDING', 'SENT', 'FAILED', 'UNKNOWN', 'SUPPRESSED', 'RESOURCE_UNAVAILABLE'].includes(String(value.status)) || (value.provider_code !== null && typeof value.provider_code !== 'string') || (value.provider_id !== null && typeof value.provider_id !== 'string') || typeof value.created_at !== 'string' || (value.finished_at !== null && typeof value.finished_at !== 'string')) return bad()
  return value as unknown as ProviderAttempt
}
function decode(text: string, page: number): Paginated<ProviderAttempt> {
  let value: unknown
  try { value = JSON.parse(text) } catch { return bad() }
  if (!object(value) || !Array.isArray(value.items) || !integer(value.total) || !integer(value.page, 1) || value.page !== page || !integer(value.size, 1) || value.items.length > value.size || value.items.length > value.total) return bad()
  const items = value.items.map(attempt)
  if (new Set(items.map(item => item.id)).size !== items.length) return bad()
  return { items, total: value.total, page: value.page, size: value.size }
}
export const faultPushApi = {
  attempts: (page: number, tenantIds: string[], placeIds: string[], signal?: AbortSignal) => request<Paginated<ProviderAttempt>>(scopeQuery(`/management/notification-resources/provider-attempts?page=${page}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: text => decode(text, page) }),
}
