import { ApiError, request, scopeQuery } from './api'
import type { Paginated } from './types'

export type ReceiptState = 'UNCONFIRMED' | 'WAITING' | 'DELIVERED' | 'FAILED' | 'HEARD_FULL' | 'CONNECTED_PARTIAL' | 'NOT_CONNECTED' | 'ACCEPTED'
export interface DeliverySnapshot { destination_masked: string | null; destination_bound: boolean; account_bound: boolean; send_date: string | null; out_id: string | null }
export interface DeliveryReceipt { state: ReceiptState; reason: string | null; provider_status: string | null; provider_code: string | null; revision: number; first_observed_at: string; last_observed_at: string }
export interface DeliveryEligibility { queryable: boolean; reason: string | null }
export interface Delivery {
  id: string; tenant_id: string; event_kind: 'FIRE' | 'FAULT'; source_kind: 'ALARM' | 'RAW_FAULT' | 'LEGACY_FAULT'; source_id: string
  alarm_id: string | null; device_id: string; contact_id: string; channel: 'SMS' | 'VOICE' | 'WECHAT'; attempt_no: number
  status: 'PENDING' | 'SENT' | 'FAILED' | 'UNKNOWN' | 'SUPPRESSED' | 'RESOURCE_UNAVAILABLE'
  provider_code: string | null; provider_id: string | null; created_at: string; finished_at: string | null
  snapshot: DeliverySnapshot; receipt: DeliveryReceipt | null; eligibility: DeliveryEligibility
  binding_id?: string | null; config_revision?: number | null; template_id?: string | null; payload_digest?: string | null
}
export interface DeliveryObservation {
  id: string; source: 'MANUAL' | 'SCHEDULED' | 'SEND_RESULT' | 'PROVIDER_CALLBACK'; state: ReceiptState; reason: string | null; provider_status: string | null; provider_code: string | null
  provider_biz_id: string | null; provider_phone_masked: string | null; provider_send_at: string | null; provider_receive_at: string | null
  provider_created_at: string | null; duration_seconds: number | null; query_request_id: string | null; network_queried: boolean; evidence_digest: string; observed_at: string
}
export interface DeliveryDetail { delivery: Delivery; observations: DeliveryObservation[]; config: ReceiptConfig }
export interface ReceiptPolicyDesired {
  enabled: boolean; min_age_seconds: number; retry_seconds: number; max_queries_per_attempt: number; batch_size: number; revision: number; updated_at: string | null
}
export interface ReceiptPolicyEffective {
  enabled: boolean; desired_enabled: boolean; env_allowed: boolean; provider_ready: boolean
  min_age_seconds: number; retry_seconds: number; max_queries_per_attempt: number; batch_size: number; clamped_reasons: string[]
}
export interface ReceiptTechnical {
  sms_lookback_days: number; voice_lookback_months: number; sms_page_size: number; voice_prod_id: string; poll_ms: number
  max_batch_size: number; max_min_age_seconds: number; max_retry_seconds: number; max_queries_per_attempt: number
}
export interface ReceiptConfig { desired: ReceiptPolicyDesired; effective: ReceiptPolicyEffective; technical: ReceiptTechnical }
export interface ReconcileResult {
  attempt_id: string; channel: 'SMS' | 'VOICE'; state: ReceiptState; reason: string | null; provider_status: string | null; provider_code: string | null
  revision: number; observation_id: string; network_queried: boolean; observed_at: string
}

const states: ReceiptState[] = ['UNCONFIRMED', 'WAITING', 'DELIVERED', 'FAILED', 'HEARD_FULL', 'CONNECTED_PARTIAL', 'NOT_CONNECTED', 'ACCEPTED']
const bad = (): never => { throw new ApiError(200, 'NOTIFICATION_RECEIPT_RESPONSE_UNCONFIRMED', '通知回执响应不符合合同') }
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown, min = 0): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min
const text = (value: unknown): string | null => value === null ? null : typeof value === 'string' ? value : bad()
const state = (value: unknown): ReceiptState => states.includes(value as ReceiptState) ? value as ReceiptState : bad()

function snapshot(value: unknown): DeliverySnapshot {
  if (!object(value) || typeof value.destination_bound !== 'boolean' || typeof value.account_bound !== 'boolean') return bad()
  text(value.destination_masked); text(value.send_date); text(value.out_id)
  return value as unknown as DeliverySnapshot
}
function receipt(value: unknown): DeliveryReceipt | null {
  if (value === null || value === undefined) return null
  if (!object(value)) return bad()
  state(value.state); text(value.reason); text(value.provider_status); text(value.provider_code)
  if (!integer(value.revision) || typeof value.first_observed_at !== 'string' || typeof value.last_observed_at !== 'string') return bad()
  return value as unknown as DeliveryReceipt
}
function delivery(value: unknown): Delivery {
  if (!object(value)) return bad()
  if (typeof value.id !== 'string' || !value.id || typeof value.tenant_id !== 'string' || !value.tenant_id || (value.event_kind !== 'FIRE' && value.event_kind !== 'FAULT') || !['ALARM', 'RAW_FAULT', 'LEGACY_FAULT'].includes(String(value.source_kind)) || typeof value.source_id !== 'string' || !value.source_id) return bad()
  if ((value.alarm_id !== null && typeof value.alarm_id !== 'string') || typeof value.device_id !== 'string' || !value.device_id || typeof value.contact_id !== 'string' || !value.contact_id || !['SMS', 'VOICE', 'WECHAT'].includes(String(value.channel)) || !integer(value.attempt_no, 1)) return bad()
  if (!['PENDING', 'SENT', 'FAILED', 'UNKNOWN', 'SUPPRESSED', 'RESOURCE_UNAVAILABLE'].includes(String(value.status)) || (value.provider_code !== null && typeof value.provider_code !== 'string') || (value.provider_id !== null && typeof value.provider_id !== 'string') || typeof value.created_at !== 'string' || (value.finished_at !== null && typeof value.finished_at !== 'string')) return bad()
  if (!object(value.eligibility) || typeof value.eligibility.queryable !== 'boolean' || (value.eligibility.reason !== null && typeof value.eligibility.reason !== 'string')) return bad()
  snapshot(value.snapshot); receipt(value.receipt)
  return value as unknown as Delivery
}
function config(value: unknown): ReceiptConfig {
  if (!object(value) || !object(value.desired) || !object(value.effective) || !object(value.technical)) return bad()
  const desired = value.desired, effective = value.effective, technical = value.technical
  if (typeof desired.enabled !== 'boolean' || (desired.updated_at !== null && typeof desired.updated_at !== 'string')) return bad()
  if (!['min_age_seconds', 'retry_seconds', 'max_queries_per_attempt', 'batch_size', 'revision'].every(key => integer(desired[key]))) return bad()
  if (!['enabled', 'desired_enabled', 'env_allowed', 'provider_ready'].every(key => typeof effective[key] === 'boolean')) return bad()
  if (!['min_age_seconds', 'retry_seconds', 'max_queries_per_attempt', 'batch_size'].every(key => integer(effective[key]))) return bad()
  if (!Array.isArray(effective.clamped_reasons) || effective.clamped_reasons.some(reason => typeof reason !== 'string')) return bad()
  if (!['sms_lookback_days', 'voice_lookback_months', 'sms_page_size', 'poll_ms', 'max_batch_size', 'max_min_age_seconds', 'max_retry_seconds', 'max_queries_per_attempt'].every(key => integer(technical[key]))) return bad()
  if (typeof technical.voice_prod_id !== 'string') return bad()
  return value as unknown as ReceiptConfig
}
function observation(value: unknown): DeliveryObservation {
  if (!object(value) || typeof value.id !== 'string' || !value.id || !['MANUAL', 'SCHEDULED', 'SEND_RESULT', 'PROVIDER_CALLBACK'].includes(String(value.source)) || !states.includes(value.state as ReceiptState) || typeof value.network_queried !== 'boolean' || typeof value.evidence_digest !== 'string' || typeof value.observed_at !== 'string') return bad()
  if (value.duration_seconds !== null && !integer(value.duration_seconds)) return bad()
  text(value.reason); text(value.provider_status); text(value.provider_code); text(value.provider_biz_id); text(value.provider_phone_masked)
  text(value.provider_send_at); text(value.provider_receive_at); text(value.provider_created_at); text(value.query_request_id)
  return value as unknown as DeliveryObservation
}
function listResponse(textValue: string, page: number): Paginated<Delivery> {
  let value: unknown
  try { value = JSON.parse(textValue) } catch { return bad() }
  if (!object(value) || !Array.isArray(value.items) || !integer(value.total) || !integer(value.page, 1) || value.page !== page || !integer(value.size, 1) || value.items.length > value.size || value.items.length > value.total) return bad()
  const items = value.items.map(delivery)
  if (new Set(items.map(item => item.id)).size !== items.length) return bad()
  return { items, total: value.total, page: value.page, size: value.size }
}
function detailResponse(textValue: string): DeliveryDetail {
  let value: unknown
  try { value = JSON.parse(textValue) } catch { return bad() }
  if (!object(value) || !Array.isArray(value.observations) || value.observations.length > 500) return bad()
  return { delivery: delivery(value.delivery), observations: value.observations.map(observation), config: config(value.config) }
}
function resultResponse(textValue: string): ReconcileResult {
  let value: unknown
  try { value = JSON.parse(textValue) } catch { return bad() }
  if (!object(value) || typeof value.attempt_id !== 'string' || !value.attempt_id || !['SMS', 'VOICE', 'WECHAT'].includes(String(value.channel)) || !states.includes(value.state as ReceiptState)) return bad()
  if (!integer(value.revision) || typeof value.observation_id !== 'string' || !value.observation_id || typeof value.network_queried !== 'boolean' || typeof value.observed_at !== 'string') return bad()
  text(value.reason); text(value.provider_status); text(value.provider_code)
  return value as unknown as ReconcileResult
}

export const notificationDeliveryApi = {
  list: (page: number, tenantIds: string[], placeIds: string[], signal?: AbortSignal) =>
    request<Paginated<Delivery>>(scopeQuery(`/management/notification-deliveries?page=${page}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: textValue => listResponse(textValue, page) }),
  detail: (id: string, tenantIds: string[], placeIds: string[], signal?: AbortSignal) =>
    request<DeliveryDetail>(scopeQuery(`/management/notification-deliveries/${encodeURIComponent(id)}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal, decode: detailResponse }),
  config: (signal?: AbortSignal) => request<ReceiptConfig>('/management/notification-deliveries/config', { signal, decode: textValue => config(JSON.parse(textValue)) }),
  updateConfig: (body: { expected_revision: number; periodic_query_enabled: boolean; min_age_seconds: number; retry_seconds: number; max_queries_per_attempt: number; batch_size: number; reason: string }, key: string, signal?: AbortSignal) =>
    request<ReceiptConfig>('/management/notification-deliveries/config', { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: textValue => config(JSON.parse(textValue)) }),
  reconcile: (id: string, body: { expected_revision: number; reason: string }, key: string, tenantIds: string[], placeIds: string[], signal?: AbortSignal) =>
    request<ReconcileResult>(scopeQuery(`/management/notification-deliveries/${encodeURIComponent(id)}/reconcile`, tenantIds, placeIds), { method: 'POST', tenantScoped: true, tenantOptional: true, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: resultResponse }),
}
