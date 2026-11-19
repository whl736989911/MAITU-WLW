import { ApiError, request, scopeQuery } from './api'
import { useSession } from './store'
import { parseJsonDocument } from './json-document'
import type { Paginated } from './types'

/** Server-paged selectors only; never a bulk asset preload. */
export const APPLICATION_PAGE_SIZE = 20
export const OUTPUT_MODES = ['NORMALIZED', 'SINGLE_DEVICE_RAW'] as const
export const OUTPUT_PROTOCOLS = ['HTTPS', 'MQTT_TLS_3_1_1'] as const
export const OUTPUT_AUTH_SCHEMES = ['NONE', 'BEARER', 'BASIC', 'HEADER'] as const
export const DELIVERY_STATUSES = ['PENDING', 'IN_FLIGHT', 'ACCEPTED', 'REJECTED', 'UNKNOWN', 'BLOCKED'] as const
export const SCOPE_MODES = ['ALL_TENANT', 'DEVICE_SUBSET'] as const
export const REFERENCE_KINDS = ['FORMAL_VERSION', 'LEGACY_PRODUCT', 'VENDOR_MODEL'] as const

export type OutputMode = typeof OUTPUT_MODES[number]
export type OutputProtocol = typeof OUTPUT_PROTOCOLS[number]
export type OutputAuthScheme = typeof OUTPUT_AUTH_SCHEMES[number]
export type DeliveryStatus = typeof DELIVERY_STATUSES[number]
export type ScopeMode = typeof SCOPE_MODES[number]
export type ReferenceKind = typeof REFERENCE_KINDS[number]
export interface Driver { key: string; name: string }
export interface ReferenceOption {
  id: string; label: string; detail: string; revision: number | null; sha256: string | null
  device_count: number | null
}
export interface ReferenceOptions {
  kind: ReferenceKind; platforms: string[]; drivers: Driver[]; items: ReferenceOption[]
  total: number; page: number; size: number
}

export interface ApplicationSummary {
  id: string; tenant_id: string; tenant_name: string; name: string; description: string
  status: 'ACTIVE' | 'ARCHIVED'; revision: number; authorization_count: number; output_count: number
  enabled_output_count: number; pending_deliveries: number; unknown_deliveries: number
  blocked_deliveries: number; created_at: string; updated_at: string
  archived_at: string | null; archived_by_name: string | null; archived_reason: string | null
}
export interface Authorization {
  id: string; reference_kind: ReferenceKind; reference_key: string; product_id: string | null
  product_name: string | null; manufacturer: string | null; model: string | null
  product_version_id: string | null; version_number: number | null
  source_platform: string | null; legacy_product_id: string | null; legacy_decoder_revision: number | null
  legacy_decoder_sha256: string | null; vendor_model_id: string | null; driver_key: string | null
  adapter_digest: string | null; scope_mode: ScopeMode
  status: 'ACTIVE' | 'REVOKED'; revision: number; device_count: number
  created_by: string | null; created_at: string; updated_at: string
  revoked_at: string | null; revoked_reason: string | null
}
export interface Output {
  id: string; name: string; mode: OutputMode; protocol: OutputProtocol; enabled: boolean
  endpoint_origin: string | null; endpoint_masked: string | null; endpoint_digest: string | null
  auth_scheme: OutputAuthScheme; auth_header_name: string | null
  credential_masked: string | null; credential_digest: string | null
  mqtt_auth_scheme: 'NONE' | 'USERNAME_PASSWORD'
  mqtt_topic_masked: string | null; mqtt_topic_digest: string | null
  mqtt_username_masked: string | null; mqtt_username_digest: string | null
  mqtt_password_masked: string | null; mqtt_password_digest: string | null
  timeout_ms: number; revision: number; created_at: string; updated_at: string
}
export interface DeliverySummary {
  pending: number; in_flight: number; accepted: number; rejected: number; unknown: number
  blocked: number; last_captured_at: string | null
}
export interface ApplicationDetail {
  application: ApplicationSummary
  authorizations: Authorization[]
  outputs: Output[]
  deliveries: DeliverySummary
}
export interface Delivery {
  id: string; application_id: string; output_id: string; output_name: string | null
  fact_id: string; device_id: string; device_name: string | null; product_version_id: string | null
  source_platform: string; source_product_kind: ReferenceKind | null; source_product_id: string | null
  source_driver_key: string | null; source_product_digest: string | null
  authorization_reference_kind: ReferenceKind; authorization_reference_key: string
  source_message_id: string | null; source_event_type: string | null
  source_event_at: string | null; received_at: string; alarm_id: string | null
  mode: OutputMode; protocol: OutputProtocol; single_device_proof: boolean; content_type: string
  payload_sha256: string; payload_bytes: number
  status: DeliveryStatus; failure_code: string | null; http_status: number | null
  failure_meaning: string; attempt_count: number
  captured_at: string; claimed_at: string | null; finished_at: string | null
}
export interface DeliveryAttempt {
  id: string; attempt_no: number; output_revision: number
  endpoint_origin: string | null; endpoint_masked: string | null; endpoint_digest: string | null
  mode: OutputMode; protocol: OutputProtocol; status: DeliveryStatus; http_status: number | null; failure_code: string | null
  failure_meaning: string
  started_at: string; finished_at: string; duration_ms: number
}
export interface DeliveryDetail {
  delivery: Delivery; attempts: DeliveryAttempt[]
  payload_retention: string; success_semantics: string
}
export interface ApplicationEvent {
  id: string; subject_type: string; subject_id: string; action: string
  revision_before: number | null; revision_after: number
  actor_id: string; actor_name: string | null; reason: string; created_at: string
}
export interface ApplicationCapabilities {
  encryption_ready: boolean; allowlist_configured: boolean; raw_forward_permitted: boolean
}
export type ApplicationPage = Paginated<ApplicationSummary>
export type DeliveryPage = Paginated<Delivery>
export type EventPage = Paginated<ApplicationEvent>

function fail(): never {
  throw new ApiError(200, 'APPLICATION_RESPONSE_UNCONFIRMED', '应用输出响应格式未确认；不推断空值或成功。')
}
function obj(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail()
  const record = value as Record<string, unknown>
  for (const key of keys) if (!Object.hasOwn(record, key)) fail()
  return record
}
function text(value: unknown): string { if (typeof value !== 'string') fail(); return value }
function nullableText(value: unknown): string | null { return value === null ? null : text(value) }
function nullableInteger(value: unknown): number | null {
  return value === null ? null : integer(value)
}
function integer(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) fail()
  return value
}
function bool(value: unknown): boolean { if (typeof value !== 'boolean') fail(); return value }
function oneOf<T extends readonly string[]>(value: unknown, allowed: T): T[number] {
  if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) fail()
  return value as T[number]
}
function array(value: unknown, mapper: (item: unknown) => unknown): unknown[] {
  if (!Array.isArray(value)) fail()
  return value.map(mapper)
}
function page<T>(raw: unknown, row: (item: unknown) => T): Paginated<T> {
  const value = obj(raw, ['items', 'total', 'page', 'size'])
  return { items: array(value.items, row) as T[], total: integer(value.total), page: integer(value.page, 1), size: integer(value.size, 1) }
}
function summary(raw: unknown): ApplicationSummary {
  const row = obj(raw, ['id', 'tenant_id', 'tenant_name', 'name', 'description', 'status', 'revision',
    'authorization_count', 'output_count', 'enabled_output_count', 'pending_deliveries', 'unknown_deliveries',
    'blocked_deliveries', 'created_at', 'updated_at', 'archived_at', 'archived_by_name', 'archived_reason'])
  return {
    id: text(row.id), tenant_id: text(row.tenant_id), tenant_name: text(row.tenant_name), name: text(row.name),
    description: text(row.description), status: oneOf(row.status, ['ACTIVE', 'ARCHIVED'] as const),
    revision: integer(row.revision), authorization_count: integer(row.authorization_count),
    output_count: integer(row.output_count), enabled_output_count: integer(row.enabled_output_count),
    pending_deliveries: integer(row.pending_deliveries), unknown_deliveries: integer(row.unknown_deliveries),
    blocked_deliveries: integer(row.blocked_deliveries), created_at: text(row.created_at),
    updated_at: text(row.updated_at), archived_at: nullableText(row.archived_at),
    archived_by_name: nullableText(row.archived_by_name), archived_reason: nullableText(row.archived_reason),
  }
}
function authorization(raw: unknown): Authorization {
  const row = obj(raw, ['id', 'reference_kind', 'reference_key', 'product_id', 'product_name', 'manufacturer',
    'model', 'product_version_id', 'version_number', 'source_platform', 'legacy_product_id',
    'legacy_decoder_revision', 'legacy_decoder_sha256', 'vendor_model_id', 'driver_key', 'adapter_digest',
    'scope_mode', 'status', 'revision', 'device_count', 'created_by', 'created_at', 'updated_at', 'revoked_at',
    'revoked_reason'])
  return {
    id: text(row.id), reference_kind: oneOf(row.reference_kind, REFERENCE_KINDS),
    reference_key: text(row.reference_key), product_id: nullableText(row.product_id),
    product_name: nullableText(row.product_name), manufacturer: nullableText(row.manufacturer),
    model: nullableText(row.model), product_version_id: nullableText(row.product_version_id),
    version_number: nullableInteger(row.version_number), source_platform: nullableText(row.source_platform),
    legacy_product_id: nullableText(row.legacy_product_id),
    legacy_decoder_revision: nullableInteger(row.legacy_decoder_revision),
    legacy_decoder_sha256: nullableText(row.legacy_decoder_sha256),
    vendor_model_id: nullableText(row.vendor_model_id), driver_key: nullableText(row.driver_key),
    adapter_digest: nullableText(row.adapter_digest), scope_mode: oneOf(row.scope_mode, SCOPE_MODES),
    status: oneOf(row.status, ['ACTIVE', 'REVOKED'] as const), revision: integer(row.revision),
    device_count: integer(row.device_count), created_by: nullableText(row.created_by),
    created_at: text(row.created_at), updated_at: text(row.updated_at), revoked_at: nullableText(row.revoked_at),
    revoked_reason: nullableText(row.revoked_reason),
  }
}
function output(raw: unknown): Output {
  const row = obj(raw, ['id', 'name', 'mode', 'protocol', 'enabled', 'endpoint_origin', 'endpoint_masked',
    'endpoint_digest', 'auth_scheme', 'auth_header_name', 'credential_masked', 'credential_digest',
    'mqtt_auth_scheme', 'mqtt_topic_masked', 'mqtt_topic_digest', 'mqtt_username_masked', 'mqtt_username_digest',
    'mqtt_password_masked', 'mqtt_password_digest', 'timeout_ms', 'revision', 'created_at', 'updated_at'])
  return {
    id: text(row.id), name: text(row.name), mode: oneOf(row.mode, OUTPUT_MODES),
    protocol: oneOf(row.protocol, OUTPUT_PROTOCOLS), enabled: bool(row.enabled),
    endpoint_origin: nullableText(row.endpoint_origin), endpoint_masked: nullableText(row.endpoint_masked),
    endpoint_digest: nullableText(row.endpoint_digest), auth_scheme: oneOf(row.auth_scheme, OUTPUT_AUTH_SCHEMES),
    auth_header_name: nullableText(row.auth_header_name), credential_masked: nullableText(row.credential_masked),
    credential_digest: nullableText(row.credential_digest),
    mqtt_auth_scheme: oneOf(row.mqtt_auth_scheme, ['NONE', 'USERNAME_PASSWORD'] as const),
    mqtt_topic_masked: nullableText(row.mqtt_topic_masked), mqtt_topic_digest: nullableText(row.mqtt_topic_digest),
    mqtt_username_masked: nullableText(row.mqtt_username_masked),
    mqtt_username_digest: nullableText(row.mqtt_username_digest),
    mqtt_password_masked: nullableText(row.mqtt_password_masked),
    mqtt_password_digest: nullableText(row.mqtt_password_digest),
    timeout_ms: integer(row.timeout_ms, 1), revision: integer(row.revision),
    created_at: text(row.created_at), updated_at: text(row.updated_at),
  }
}
function deliverySummary(raw: unknown): DeliverySummary {
  const row = obj(raw, ['pending', 'in_flight', 'accepted', 'rejected', 'unknown', 'blocked', 'last_captured_at'])
  return {
    pending: integer(row.pending), in_flight: integer(row.in_flight), accepted: integer(row.accepted),
    rejected: integer(row.rejected), unknown: integer(row.unknown), blocked: integer(row.blocked),
    last_captured_at: nullableText(row.last_captured_at),
  }
}
function detail(raw: unknown): ApplicationDetail {
  const row = obj(raw, ['application', 'authorizations', 'outputs', 'deliveries'])
  return {
    application: summary(row.application),
    authorizations: array(row.authorizations, authorization) as Authorization[],
    outputs: array(row.outputs, output) as Output[],
    deliveries: deliverySummary(row.deliveries),
  }
}
function delivery(raw: unknown): Delivery {
  const row = obj(raw, ['id', 'application_id', 'output_id', 'output_name', 'fact_id', 'device_id', 'device_name',
    'product_version_id', 'source_platform', 'source_product_kind', 'source_product_id', 'source_driver_key',
    'source_product_digest', 'authorization_reference_kind', 'authorization_reference_key', 'source_message_id',
    'source_event_type', 'source_event_at', 'received_at', 'alarm_id', 'mode', 'protocol', 'single_device_proof',
    'content_type', 'payload_sha256', 'payload_bytes', 'status', 'failure_code', 'http_status', 'failure_meaning',
    'attempt_count', 'captured_at', 'claimed_at', 'finished_at'])
  const sourceProductKind = row.source_product_kind === null ? null : oneOf(row.source_product_kind, REFERENCE_KINDS)
  return {
    id: text(row.id), application_id: text(row.application_id), output_id: text(row.output_id),
    output_name: nullableText(row.output_name), fact_id: text(row.fact_id), device_id: text(row.device_id),
    device_name: nullableText(row.device_name), product_version_id: nullableText(row.product_version_id),
    source_platform: text(row.source_platform), source_product_kind: sourceProductKind,
    source_product_id: nullableText(row.source_product_id), source_driver_key: nullableText(row.source_driver_key),
    source_product_digest: nullableText(row.source_product_digest),
    authorization_reference_kind: oneOf(row.authorization_reference_kind, REFERENCE_KINDS),
    authorization_reference_key: text(row.authorization_reference_key),
    source_message_id: nullableText(row.source_message_id), source_event_type: nullableText(row.source_event_type),
    source_event_at: nullableText(row.source_event_at), received_at: text(row.received_at),
    alarm_id: nullableText(row.alarm_id), mode: oneOf(row.mode, OUTPUT_MODES),
    protocol: oneOf(row.protocol, OUTPUT_PROTOCOLS),
    single_device_proof: bool(row.single_device_proof), content_type: text(row.content_type),
    payload_sha256: text(row.payload_sha256), payload_bytes: integer(row.payload_bytes, 1),
    status: oneOf(row.status, DELIVERY_STATUSES), failure_code: nullableText(row.failure_code),
    http_status: nullableInteger(row.http_status), failure_meaning: text(row.failure_meaning),
    attempt_count: integer(row.attempt_count), captured_at: text(row.captured_at),
    claimed_at: nullableText(row.claimed_at), finished_at: nullableText(row.finished_at),
  }
}
function attempt(raw: unknown): DeliveryAttempt {
  const row = obj(raw, ['id', 'attempt_no', 'output_revision', 'endpoint_origin', 'endpoint_masked',
    'endpoint_digest', 'mode', 'protocol', 'status', 'http_status', 'failure_code', 'failure_meaning', 'started_at',
    'finished_at', 'duration_ms'])
  return {
    id: text(row.id), attempt_no: integer(row.attempt_no, 1), output_revision: integer(row.output_revision),
    endpoint_origin: nullableText(row.endpoint_origin), endpoint_masked: nullableText(row.endpoint_masked),
    endpoint_digest: nullableText(row.endpoint_digest), mode: oneOf(row.mode, OUTPUT_MODES),
    protocol: oneOf(row.protocol, OUTPUT_PROTOCOLS),
    status: oneOf(row.status, DELIVERY_STATUSES), http_status: nullableInteger(row.http_status),
    failure_code: nullableText(row.failure_code), failure_meaning: text(row.failure_meaning),
    started_at: text(row.started_at), finished_at: text(row.finished_at), duration_ms: integer(row.duration_ms),
  }
}
function event(raw: unknown): ApplicationEvent {
  const row = obj(raw, ['id', 'subject_type', 'subject_id', 'action', 'revision_before', 'revision_after',
    'actor_id', 'actor_name', 'reason', 'created_at'])
  return {
    id: text(row.id), subject_type: text(row.subject_type), subject_id: text(row.subject_id), action: text(row.action),
    revision_before: nullableInteger(row.revision_before), revision_after: integer(row.revision_after),
    actor_id: text(row.actor_id), actor_name: nullableText(row.actor_name), reason: text(row.reason),
    created_at: text(row.created_at),
  }
}

export function decodeApplicationDetail(raw: string): ApplicationDetail { return detail(parseJsonDocument(raw)) }
export function decodeApplicationPage(raw: string): ApplicationPage { return page(parseJsonDocument(raw), summary) }
export function decodeDeliveryPage(raw: string): DeliveryPage { return page(parseJsonDocument(raw), delivery) }
export function decodeEventPage(raw: string): EventPage { return page(parseJsonDocument(raw), event) }
export function decodeDeliveryDetail(raw: string): DeliveryDetail {
  const value = obj(parseJsonDocument(raw), ['delivery', 'attempts', 'payload_retention', 'success_semantics'])
  return {
    delivery: delivery(value.delivery), attempts: array(value.attempts, attempt) as DeliveryAttempt[],
    payload_retention: text(value.payload_retention), success_semantics: text(value.success_semantics),
  }
}
export function decodeCapabilities(raw: string): ApplicationCapabilities {
  const value = obj(parseJsonDocument(raw), ['encryption_ready', 'allowlist_configured', 'raw_forward_permitted'])
  return {
    encryption_ready: bool(value.encryption_ready), allowlist_configured: bool(value.allowlist_configured),
    raw_forward_permitted: bool(value.raw_forward_permitted),
  }
}

function referenceOption(raw: unknown): ReferenceOption {
  const row = obj(raw, ['id', 'label', 'detail', 'revision', 'sha256', 'device_count'])
  return {
    id: text(row.id), label: text(row.label), detail: text(row.detail),
    revision: nullableInteger(row.revision), sha256: nullableText(row.sha256),
    device_count: nullableInteger(row.device_count),
  }
}
function rawDriver(raw: unknown): Driver {
  const row = obj(raw, ['key', 'name'])
  return { key: text(row.key), name: text(row.name) }
}
export function decodeReferenceOptions(raw: string): ReferenceOptions {
  const value = obj(parseJsonDocument(raw), ['kind', 'platforms', 'drivers', 'items', 'total', 'page', 'size'])
  return {
    kind: oneOf(value.kind, REFERENCE_KINDS),
    platforms: array(value.platforms, text) as string[],
    drivers: array(value.drivers, rawDriver) as Driver[],
    items: array(value.items, referenceOption) as ReferenceOption[],
    total: integer(value.total), page: integer(value.page, 1), size: integer(value.size, 1),
  }
}

export function getReferenceOptions(input: { kind: ReferenceKind; platform: string; page: number; keyword: string },
                                      tenantId: string, signal: AbortSignal) {
  if (!tenantId) return Promise.reject(new ApiError(400, 'TARGET_UNIT_REQUIRED', '请先明确选择授权所属单位。'))
  const params = new URLSearchParams({ kind: input.kind, page: String(input.page) })
  if (input.kind === 'VENDOR_MODEL' && input.platform) params.set('source_platform', input.platform)
  if (input.keyword) params.set('keyword', input.keyword)
  return request<ReferenceOptions>(`/management/applications/reference-options?${params}`,
    { tenantScoped: true, tenantId, signal, decode: decodeReferenceOptions })
}
export function getApplications(input: { page: number; status: string; keyword: string }, signal: AbortSignal) {
  const params = new URLSearchParams({ page: String(input.page) })
  if (input.status) params.set('status', input.status)
  if (input.keyword) params.set('keyword', input.keyword)
  const session = useSession.getState()
  return request<ApplicationPage>(scopeQuery(`/management/applications?${params}`, session.scopeTenantIds, session.scopePlaceIds),
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeApplicationPage })
}
export function getApplication(id: string, signal: AbortSignal, tenantId?: string) {
  const session = useSession.getState()
  const path = `/management/applications/${encodeURIComponent(id)}`
  return request<ApplicationDetail>(scopeQuery(path, session.scopeTenantIds, session.scopePlaceIds),
    { tenantScoped: true, tenantId, tenantOptional: true, signal, decode: decodeApplicationDetail })
}
export function getApplicationEvents(id: string, pageNumber: number, signal: AbortSignal) {
  const params = new URLSearchParams({ page: String(pageNumber) })
  const path = `/management/applications/${encodeURIComponent(id)}/events?${params}`
  const session = useSession.getState()
  return request<EventPage>(scopeQuery(path, session.scopeTenantIds, session.scopePlaceIds),
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeEventPage })
}
export function getApplicationDeliveries(id: string, input: { page: number; status: string; outputId: string },
                                         signal: AbortSignal) {
  const params = new URLSearchParams({ page: String(input.page) })
  if (input.status) params.set('status', input.status)
  if (input.outputId) params.set('output_id', input.outputId)
  const session = useSession.getState()
  const path = `/management/applications/${encodeURIComponent(id)}/deliveries?${params}`
  return request<DeliveryPage>(scopeQuery(path, session.scopeTenantIds, session.scopePlaceIds),
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeDeliveryPage })
}
export function getApplicationDelivery(id: string, deliveryId: string, signal: AbortSignal) {
  const path = `/management/applications/${encodeURIComponent(id)}/deliveries/${encodeURIComponent(deliveryId)}`
  const session = useSession.getState()
  return request<DeliveryDetail>(scopeQuery(path, session.scopeTenantIds, session.scopePlaceIds),
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeDeliveryDetail })
}
export function getApplicationCapabilities(signal: AbortSignal) {
  return request<ApplicationCapabilities>('/management/applications/capabilities', { signal, decode: decodeCapabilities })
}
