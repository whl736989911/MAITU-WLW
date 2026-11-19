import { ApiError, request } from './api'

export interface SubscriptionLimitation {
  vendor_cas_supported: boolean
  vendor_retry_contract: boolean
  vendor_delivery_sla: boolean
  note: string
}
export interface SubscriptionRemoteRow {
  message_type: string
  destination_origin: string | null
  matches_receiver: boolean
  url_sha256: string | null
  updated_at: string | null
  supported: boolean
  duplicate: boolean
}
export interface SubscriptionInspect {
  checked_at: string
  receiver_revision: number
  hikvision_revision: number
  account_fingerprint_masked: string | null
  receiver_origin: string | null
  receiver_ready: boolean
  items: SubscriptionRemoteRow[]
  digests: Record<string, string>
  limitations: SubscriptionLimitation
}
export interface SubscriptionPreview {
  operation_id: string | null
  operation: 'ADD' | 'UPDATE' | 'DELETE'
  message_type: string
  state: string
  no_write_required: boolean
  current_origin: string | null
  intended_origin: string | null
  matches_receiver: boolean
  overwrite_required: boolean
  remote_digest: string
  remote_state: 'PRESENT' | 'ABSENT'
  previous_url_sha256: string | null
  receiver_revision: number
  hikvision_revision: number
  account_fingerprint_masked: string | null
  expires_at: string
  note: string
  limitations: SubscriptionLimitation
}
export interface SubscriptionAttempt {
  id: string
  attempt_no: number
  phase: 'PREVIEW' | 'SUBMIT' | 'READBACK' | 'RECONCILE'
  outcome: string
  vendor_code: string | null
  detail: unknown
  started_at: string
  finished_at: string | null
}
export interface SubscriptionObservation {
  id: string
  trigger: 'INSPECT' | 'PREVIEW' | 'READBACK' | 'RECONCILE'
  list_digest: string
  snapshot: unknown
  observed_at: string
}
export interface SubscriptionOperation {
  id: string
  operation: 'ADD' | 'UPDATE' | 'DELETE'
  message_type: string
  state: 'PREVIEW' | 'IN_FLIGHT' | 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'REJECTED' | 'UNKNOWN' | 'CANCELLED'
  revision: number
  intended_origin: string | null
  previous_origin: string | null
  matches_receiver: boolean
  overwrite_required: boolean
  overwrite_confirmed: boolean
  remote_digest: string
  remote_state: 'PRESENT' | 'ABSENT'
  vendor_code: string | null
  result: unknown
  account_fingerprint_masked: string | null
  actor_id: string
  reason: string
  created_at: string
  started_at: string | null
  finished_at: string | null
  expires_at: string
  attempts: SubscriptionAttempt[]
  observations: SubscriptionObservation[]
}
export interface SubscriptionOperationSummary {
  id: string
  operation: 'ADD' | 'UPDATE' | 'DELETE'
  message_type: string
  state: SubscriptionOperation['state']
  revision: number
  intended_origin: string | null
  previous_origin: string | null
  matches_receiver: boolean
  overwrite_required: boolean
  vendor_code: string | null
  actor_id: string
  created_at: string
  finished_at: string | null
}

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function bad(): never { throw new ApiError(200, 'SUBSCRIPTION_RESPONSE_UNCONFIRMED', '订阅响应未确认；不以本地推测替代') }
function text(value: unknown, nullable = false): string | null {
  if (value === null) { if (nullable) return null; bad() }
  if (typeof value !== 'string') bad()
  return value
}
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) bad()
  return value
}
function bool(value: unknown): boolean { if (typeof value !== 'boolean') bad(); return value }
function limitation(raw: unknown): SubscriptionLimitation {
  if (!object(raw)) return bad()
  return {
    vendor_cas_supported: bool(raw.vendor_cas_supported),
    vendor_retry_contract: bool(raw.vendor_retry_contract),
    vendor_delivery_sla: bool(raw.vendor_delivery_sla),
    note: text(raw.note) as string,
  }
}
function row(raw: unknown): SubscriptionRemoteRow {
  if (!object(raw)) return bad()
  return {
    message_type: text(raw.message_type) as string,
    destination_origin: text(raw.destination_origin, true),
    matches_receiver: bool(raw.matches_receiver),
    url_sha256: text(raw.url_sha256, true),
    updated_at: text(raw.updated_at, true),
    supported: bool(raw.supported),
    duplicate: bool(raw.duplicate),
  }
}
function inspectResponse(raw: string): SubscriptionInspect {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  const digests = object(parsed) ? parsed.digests : undefined
  if (!object(parsed) || !Array.isArray(items) || !object(digests)) return bad()
  const mapped: Record<string, string> = {}
  for (const [key, value] of Object.entries(digests)) { if (typeof value !== 'string') return bad(); mapped[key] = value }
  return {
    checked_at: text(parsed.checked_at) as string,
    receiver_revision: integer(parsed.receiver_revision),
    hikvision_revision: integer(parsed.hikvision_revision),
    account_fingerprint_masked: text(parsed.account_fingerprint_masked, true),
    receiver_origin: text(parsed.receiver_origin, true),
    receiver_ready: bool(parsed.receiver_ready),
    items: items.map(row),
    digests: mapped,
    limitations: limitation(parsed.limitations),
  }
}
function previewResponse(raw: string): SubscriptionPreview {
  const parsed: unknown = JSON.parse(raw)
  if (!object(parsed)) return bad()
  return {
    operation_id: text(parsed.operation_id, true),
    operation: parsed.operation as SubscriptionPreview['operation'],
    message_type: text(parsed.message_type) as string,
    state: text(parsed.state) as string,
    no_write_required: bool(parsed.no_write_required),
    current_origin: text(parsed.current_origin, true),
    intended_origin: text(parsed.intended_origin, true),
    matches_receiver: bool(parsed.matches_receiver),
    overwrite_required: bool(parsed.overwrite_required),
    remote_digest: text(parsed.remote_digest) as string,
    remote_state: parsed.remote_state === 'PRESENT' ? 'PRESENT' : 'ABSENT',
    previous_url_sha256: text(parsed.previous_url_sha256, true),
    receiver_revision: integer(parsed.receiver_revision),
    hikvision_revision: integer(parsed.hikvision_revision),
    account_fingerprint_masked: text(parsed.account_fingerprint_masked, true),
    expires_at: text(parsed.expires_at) as string,
    note: text(parsed.note) as string,
    limitations: limitation(parsed.limitations),
  }
}
function attempt(raw: unknown): SubscriptionAttempt {
  if (!object(raw)) return bad()
  return {
    id: text(raw.id) as string,
    attempt_no: integer(raw.attempt_no),
    phase: raw.phase as SubscriptionAttempt['phase'],
    outcome: text(raw.outcome) as string,
    vendor_code: text(raw.vendor_code, true),
    detail: raw.detail,
    started_at: text(raw.started_at) as string,
    finished_at: text(raw.finished_at, true),
  }
}
function observation(raw: unknown): SubscriptionObservation {
  if (!object(raw)) return bad()
  return {
    id: text(raw.id) as string,
    trigger: raw.trigger as SubscriptionObservation['trigger'],
    list_digest: text(raw.list_digest) as string,
    snapshot: raw.snapshot,
    observed_at: text(raw.observed_at) as string,
  }
}
function operationResponse(raw: string): SubscriptionOperation {
  const parsed: unknown = JSON.parse(raw)
  const attempts = object(parsed) ? parsed.attempts : undefined
  const observations = object(parsed) ? parsed.observations : undefined
  if (!object(parsed) || !Array.isArray(attempts) || !Array.isArray(observations)) return bad()
  return {
    id: text(parsed.id) as string,
    operation: parsed.operation as SubscriptionOperation['operation'],
    message_type: text(parsed.message_type) as string,
    state: parsed.state as SubscriptionOperation['state'],
    revision: integer(parsed.revision),
    intended_origin: text(parsed.intended_origin, true),
    previous_origin: text(parsed.previous_origin, true),
    matches_receiver: bool(parsed.matches_receiver),
    overwrite_required: bool(parsed.overwrite_required),
    overwrite_confirmed: bool(parsed.overwrite_confirmed),
    remote_digest: text(parsed.remote_digest) as string,
    remote_state: parsed.remote_state === 'PRESENT' ? 'PRESENT' : 'ABSENT',
    vendor_code: text(parsed.vendor_code, true),
    result: parsed.result,
    account_fingerprint_masked: text(parsed.account_fingerprint_masked, true),
    actor_id: text(parsed.actor_id) as string,
    reason: text(parsed.reason) as string,
    created_at: text(parsed.created_at) as string,
    started_at: text(parsed.started_at, true),
    finished_at: text(parsed.finished_at, true),
    expires_at: text(parsed.expires_at) as string,
    attempts: attempts.map(attempt),
    observations: observations.map(observation),
  }
}
function summary(raw: unknown): SubscriptionOperationSummary {
  if (!object(raw)) return bad()
  return {
    id: text(raw.id) as string,
    operation: raw.operation as SubscriptionOperationSummary['operation'],
    message_type: text(raw.message_type) as string,
    state: raw.state as SubscriptionOperationSummary['state'],
    revision: integer(raw.revision),
    intended_origin: text(raw.intended_origin, true),
    previous_origin: text(raw.previous_origin, true),
    matches_receiver: bool(raw.matches_receiver),
    overwrite_required: bool(raw.overwrite_required),
    vendor_code: text(raw.vendor_code, true),
    actor_id: text(raw.actor_id) as string,
    created_at: text(raw.created_at) as string,
    finished_at: text(raw.finished_at, true),
  }
}
function operationsResponse(raw: string): { items: SubscriptionOperationSummary[]; total: number; page: number; size: number } {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  if (!object(parsed) || !Array.isArray(items)) return bad()
  return { items: items.map(summary), total: integer(parsed.total), page: integer(parsed.page), size: integer(parsed.size) }
}

export const hikvisionSubscriptionsApi = {
  inspect: (signal?: AbortSignal) =>
    request<SubscriptionInspect>('/management/hikvision/subscriptions/inspect', { method: 'POST', signal, decode: inspectResponse }),
  preview: (body: { operation: 'ADD' | 'UPDATE' | 'DELETE'; message_type: string; expected_receiver_revision: number; expected_hikvision_revision: number; reason: string }, key: string, signal?: AbortSignal) =>
    request<SubscriptionPreview>('/management/hikvision/subscriptions/previews', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: previewResponse }),
  execute: (id: string, body: { expected_remote_digest: string; overwrite_confirmed: boolean }, key: string, signal?: AbortSignal) =>
    request<SubscriptionOperation>(`/management/hikvision/subscriptions/${encodeURIComponent(id)}/execute`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: operationResponse }),
  reconcile: (id: string, key: string, signal?: AbortSignal) =>
    request<SubscriptionOperation>(`/management/hikvision/subscriptions/${encodeURIComponent(id)}/reconcile`, { method: 'POST', headers: { 'Idempotency-Key': key }, signal, decode: operationResponse }),
  cancel: (id: string, body: { reason: string }, key: string, signal?: AbortSignal) =>
    request<SubscriptionOperation>(`/management/hikvision/subscriptions/${encodeURIComponent(id)}/cancel`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: operationResponse }),
  operations: (page: number, size: number, signal?: AbortSignal) =>
    request<{ items: SubscriptionOperationSummary[]; total: number; page: number; size: number }>(`/management/hikvision/subscriptions/operations?page=${page}&size=${size}`, { signal, decode: operationsResponse }),
  operation: (id: string, signal?: AbortSignal) =>
    request<SubscriptionOperation>(`/management/hikvision/subscriptions/${encodeURIComponent(id)}`, { signal, decode: operationResponse }),
}
