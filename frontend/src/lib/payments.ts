import { ApiError, request } from './api'
import type { Paginated } from './types'

/** Global (tenant-less) WeChat Pay configuration, as masked by the server. No secret is ever returned. */
export interface PaymentConfigView {
  configured: boolean
  enabled: boolean
  source: 'CONFIG' | 'ENV' | 'NONE'
  ready_for_new_orders: boolean
  mch_id_masked: string | null
  app_id_masked: string | null
  api_key_configured: boolean
  notify_url: string | null
  order_expiry_minutes: number | null
  auto_lifecycle_enabled: boolean
  auto_reconcile_enabled: boolean
  revision: number
  credentials_revision: number
  secret_storage_ready: boolean
  updated_at: string | null
}
export interface PaymentConfigStatus {
  secret_storage_ready: boolean
  configured: boolean
  enabled: boolean
  source: 'CONFIG' | 'ENV' | 'NONE'
  ready_for_new_orders: boolean
  order_expiry_minutes: number | null
  auto_lifecycle_enabled: boolean
  auto_reconcile_enabled: boolean
  pending_orders: number
  expired_pending_orders: number
  last_reconciliation_at: string | null
  last_reconciliation_status: string | null
}
export interface PaymentConfigInput {
  enabled: boolean
  mch_id: string | null
  app_id: string | null
  api_v2_key: string | null
  notify_url: string | null
  /** null clears the business expiry; there is no invented default TTL. */
  order_expiry_minutes: number | null
  auto_lifecycle_enabled: boolean
  auto_reconcile_enabled: boolean
  expected_revision: number
}
export interface PaymentRun {
  id: string
  bill_date: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  requested_by: string | null
  local_orders: number
  bill_rows: number
  matched_rows: number
  external_rows: number
  difference_count: number
  malformed_rows: number
  error_code: string | null
  error_message: string | null
  bill_sha256: string | null
  created_at: string
  finished_at: string | null
}
export interface PaymentDifference {
  kind: string
  order_id: string | null
  out_trade_no: string | null
  transaction_id_masked: string | null
  local_status: string | null
  local_amount_cents: number | null
  bill_amount_cents: number | null
  bill_trade_state: string | null
  detail: string | null
}
export interface PaymentRunDetail { run: PaymentRun; differences: PaymentDifference[] }
export interface PaymentOrderState {
  order_id: string
  status: string
  payment_state: string
  close_state: string | null
  query_state: string | null
  query_attempts: number
  close_attempts: number
  expires_at: string | null
  paid_at: string | null
}
export interface PaymentActionResult { action: 'query' | 'close'; outcome: string; message: string; order: PaymentOrderState | null }

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function invalid(code: string): never { throw new ApiError(200, code, '支付接口响应格式未确认，已拒绝显示') }
function integer(value: unknown, min = 0): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min) invalid('PAYMENT_RESPONSE_UNCONFIRMED'); return value }
function optionalString(value: unknown): string | null { if (value === null || value === undefined) return null; if (typeof value !== 'string') invalid('PAYMENT_RESPONSE_UNCONFIRMED'); return value }
function requiredBoolean(value: unknown): boolean { if (typeof value !== 'boolean') invalid('PAYMENT_RESPONSE_UNCONFIRMED'); return value }
function source(value: unknown): 'CONFIG' | 'ENV' | 'NONE' { if (value !== 'CONFIG' && value !== 'ENV' && value !== 'NONE') invalid('PAYMENT_RESPONSE_UNCONFIRMED'); return value }

export function paymentConfig(value: unknown): PaymentConfigView {
  if (!object(value)) return invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  const expiry = value.order_expiry_minutes
  if (expiry !== null && (typeof expiry !== 'number' || !Number.isSafeInteger(expiry) || expiry < 1 || expiry > 21600)) invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  return {
    configured: requiredBoolean(value.configured), enabled: requiredBoolean(value.enabled), source: source(value.source),
    ready_for_new_orders: requiredBoolean(value.ready_for_new_orders),
    mch_id_masked: optionalString(value.mch_id_masked), app_id_masked: optionalString(value.app_id_masked),
    api_key_configured: requiredBoolean(value.api_key_configured), notify_url: optionalString(value.notify_url),
    order_expiry_minutes: expiry as number | null, auto_lifecycle_enabled: requiredBoolean(value.auto_lifecycle_enabled),
    auto_reconcile_enabled: requiredBoolean(value.auto_reconcile_enabled), revision: integer(value.revision),
    credentials_revision: integer(value.credentials_revision), secret_storage_ready: requiredBoolean(value.secret_storage_ready),
    updated_at: optionalString(value.updated_at),
  }
}
export function paymentStatus(value: unknown): PaymentConfigStatus {
  if (!object(value)) return invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  return {
    secret_storage_ready: requiredBoolean(value.secret_storage_ready), configured: requiredBoolean(value.configured),
    enabled: requiredBoolean(value.enabled), source: source(value.source), ready_for_new_orders: requiredBoolean(value.ready_for_new_orders),
    order_expiry_minutes: (value.order_expiry_minutes === null ? null : integer(value.order_expiry_minutes, 1)) as number | null,
    auto_lifecycle_enabled: requiredBoolean(value.auto_lifecycle_enabled), auto_reconcile_enabled: requiredBoolean(value.auto_reconcile_enabled),
    pending_orders: integer(value.pending_orders), expired_pending_orders: integer(value.expired_pending_orders),
    last_reconciliation_at: optionalString(value.last_reconciliation_at), last_reconciliation_status: optionalString(value.last_reconciliation_status),
  }
}
export function paymentRun(value: unknown): PaymentRun {
  if (!object(value)) return invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  const status = value.status
  if (status !== 'RUNNING' && status !== 'COMPLETED' && status !== 'FAILED') invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  if (typeof value.bill_date !== 'string' || !/^\d{8}$/.test(value.bill_date) || typeof value.id !== 'string') invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  return {
    id: value.id, bill_date: value.bill_date, status, requested_by: optionalString(value.requested_by),
    local_orders: integer(value.local_orders), bill_rows: integer(value.bill_rows), matched_rows: integer(value.matched_rows),
    external_rows: integer(value.external_rows), difference_count: integer(value.difference_count), malformed_rows: integer(value.malformed_rows),
    error_code: optionalString(value.error_code), error_message: optionalString(value.error_message),
    bill_sha256: optionalString(value.bill_sha256), created_at: optionalString(value.created_at) ?? '', finished_at: optionalString(value.finished_at),
  }
}
export function paymentDifference(value: unknown): PaymentDifference {
  if (!object(value) || typeof value.kind !== 'string') return invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  return {
    kind: value.kind, order_id: optionalString(value.order_id), out_trade_no: optionalString(value.out_trade_no),
    transaction_id_masked: optionalString(value.transaction_id_masked), local_status: optionalString(value.local_status),
    local_amount_cents: value.local_amount_cents === null ? null : integer(value.local_amount_cents),
    bill_amount_cents: value.bill_amount_cents === null ? null : integer(value.bill_amount_cents),
    bill_trade_state: optionalString(value.bill_trade_state), detail: optionalString(value.detail),
  }
}
function runDetail(value: unknown): PaymentRunDetail {
  if (!object(value) || !Array.isArray(value.differences)) return invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  return { run: paymentRun(value.run), differences: value.differences.map(paymentDifference) }
}
function runPage(value: unknown, expectedPage: number): Paginated<PaymentRun> {
  if (!object(value) || !Array.isArray(value.items) || integer(value.total) < 0 || integer(value.page, 1) !== expectedPage) invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  const items = value.items.map(paymentRun)
  return { items, total: value.total as number, page: value.page as number, size: integer(value.size) }
}
function actionResult(value: unknown): PaymentActionResult {
  if (!object(value) || (value.action !== 'query' && value.action !== 'close') || typeof value.outcome !== 'string' || typeof value.message !== 'string') return invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  const order = value.order
  if (order !== null && !object(order)) invalid('PAYMENT_RESPONSE_UNCONFIRMED')
  return {
    action: value.action, outcome: value.outcome, message: value.message,
    order: order === null ? null : {
      order_id: String((order as Record<string, unknown>).order_id), status: String((order as Record<string, unknown>).status),
      payment_state: String((order as Record<string, unknown>).payment_state),
      close_state: optionalString((order as Record<string, unknown>).close_state), query_state: optionalString((order as Record<string, unknown>).query_state),
      query_attempts: integer((order as Record<string, unknown>).query_attempts), close_attempts: integer((order as Record<string, unknown>).close_attempts),
      expires_at: optionalString((order as Record<string, unknown>).expires_at), paid_at: optionalString((order as Record<string, unknown>).paid_at),
    },
  }
}

export const paymentApi = {
  config: (signal?: AbortSignal) => request<PaymentConfigView>('/management/payment-config', { signal, decode: text => paymentConfig(JSON.parse(text)) }),
  status: (signal?: AbortSignal) => request<PaymentConfigStatus>('/management/payment-config/status', { signal, decode: text => paymentStatus(JSON.parse(text)) }),
  saveConfig: (body: PaymentConfigInput, key: string, signal?: AbortSignal) => request<PaymentConfigView>('/management/payment-config', {
    method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => paymentConfig(JSON.parse(text)),
  }),
  runs: (page: number, signal?: AbortSignal) => request<Paginated<PaymentRun>>(`/management/payment-reconciliation?page=${page}`, { signal, decode: text => runPage(JSON.parse(text), page) }),
  run: (id: string, signal?: AbortSignal) => request<PaymentRunDetail>(`/management/payment-reconciliation/${encodeURIComponent(id)}`, { signal, decode: text => runDetail(JSON.parse(text)) }),
  reconcile: (body: { bill_date: string }, key: string, signal?: AbortSignal) => request<PaymentRunDetail>('/management/payment-reconciliation', {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => runDetail(JSON.parse(text)),
  }),
  queryOrder: (orderId: string, tenantId: string, signal?: AbortSignal) => request<PaymentActionResult>(`/management/notification-resources/orders/${encodeURIComponent(orderId)}/query`, {
    method: 'POST', tenantScoped: true, tenantId, signal, decode: text => actionResult(JSON.parse(text)),
  }),
  closeOrder: (orderId: string, tenantId: string, signal?: AbortSignal) => request<PaymentActionResult>(`/management/notification-resources/orders/${encodeURIComponent(orderId)}/close`, {
    method: 'POST', tenantScoped: true, tenantId, signal, decode: text => actionResult(JSON.parse(text)),
  }),
}
