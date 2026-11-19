import { jsonArrayItems, jsonFieldText } from './json-document'
import type { CredentialResult, IngestPreview, IngestSource, MessageApplyResult, MessageDetail, Paginated, ProductReading, RawMessage } from './types'
const sourceKeys = ['id', 'tenant_id', 'name', 'protocol', 'is_simulated', 'enabled', 'revision', 'created_at', 'last_received_at', 'message_count', 'duplicate_count'] as const
const messageKeys = ['id', 'tenant_id', 'source_id', 'source_name', 'source_message_id', 'vendor_device_id', 'device_id', 'device_name', 'binding_id', 'product_version_id', 'received_at', 'reported_at', 'occurred_at', 'occurred_at_raw', 'payload_bytes', 'payload_sha256', 'binding_state', 'duplicate_count', 'revision', 'latest_preview_id', 'parse_status', 'business_status', 'application_state', 'applied_at', 'apply_error', 'capture_origin'] as const
const applyKeys = ['raw_message_id', 'application_state', 'event_type', 'alarm_id', 'physical_state', 'battery_percent', 'sequence', 'occurred_at', 'outbox_event_type'] as const
const previewKeys = ['id', 'raw_message_id', 'product_version_id', 'event_type', 'battery_percent', 'diagnostics', 'reported_at', 'time_quality', 'created_at', 'actor_id', 'reason', 'business_status'] as const
function pick<T>(value: Record<string, unknown>, keys: readonly string[]): T { return Object.fromEntries(keys.filter(key => Object.hasOwn(value, key)).map(key => [key, value[key]])) as T }
function readingEvidence(document: string): { readings: ProductReading[]; readings_json: string } {
  const raw = jsonFieldText(document, 'readings')
  if (raw === undefined) throw new Error('解析读数结构未提供')
  const spans = jsonArrayItems(raw)
  if (spans.length > 64) throw new Error('解析读数数量超出合同')
  const keys = ['key', 'label', 'value_type', 'value', 'unit'] as const
  const sanitized = spans.map(span => `{${keys.map(key => {
    const token = jsonFieldText(span, key)
    if (token === undefined) throw new Error('解析读数字段未提供')
    return `${JSON.stringify(key)}:${token}`
  }).join(',')}}`)
  const readings = sanitized.map(span => {
    const row = JSON.parse(span) as ProductReading
    if (typeof row.key !== 'string' || typeof row.label !== 'string' || !(row.unit === null || typeof row.unit === 'string')
      || !(row.value_type === 'NUMBER' && typeof row.value === 'number' && Number.isFinite(row.value)
        || row.value_type === 'STRING' && typeof row.value === 'string'
        || row.value_type === 'BOOLEAN' && typeof row.value === 'boolean')) throw new Error('解析读数类型未确认')
    return row
  })
  return { readings, readings_json: `[${sanitized.join(',')}]` }
}
export const sourceMetadata = (value: Record<string, unknown>) => pick<IngestSource>(value, sourceKeys)
export const messageMetadata = (value: Record<string, unknown>) => {
  const message = pick<RawMessage>(value, messageKeys)
  if (!['NOT_APPLIED', 'APPLIED', 'STALE', 'REJECTED'].includes(message.application_state) ||
      !['AUTHENTICATED_SOURCE', 'LEGACY_UNVERIFIED'].includes(message.capture_origin) ||
      !(message.applied_at === null || typeof message.applied_at === 'string') ||
      !(message.apply_error === null || typeof message.apply_error === 'string') ||
      !(message.occurred_at === null || typeof message.occurred_at === 'string') ||
      !(message.occurred_at_raw === null || typeof message.occurred_at_raw === 'string')) throw new Error('真实消息应用状态未确认')
  return message
}
export function credentialResponse(text: string): CredentialResult {
  const parsed = JSON.parse(text)
  return { source: sourceMetadata(parsed.source), one_time_token: typeof parsed.one_time_token === 'string' ? parsed.one_time_token : null, credential_recovery_required: parsed.credential_recovery_required === true }
}
export function messagesResponse(text: string): Paginated<RawMessage> { const parsed = JSON.parse(text); return { items: parsed.items.map(messageMetadata), total: parsed.total, page: parsed.page, size: parsed.size } }
export function messageDetailResponse(text: string): MessageDetail {
  const parsed = JSON.parse(text)
  // Summary responses never retain payload/raw_event, even if a server regresses.
  const spans = jsonArrayItems(jsonFieldText(text, 'previews')!)
  return { message: messageMetadata(parsed.message), previews: spans.map(span => {
    const preview = JSON.parse(span) as Record<string, unknown>
    return { ...pick<IngestPreview>(preview, previewKeys), ...readingEvidence(span) }
  }) }
}
export function previewResponse(text: string, allowRaw: boolean): IngestPreview {
  const parsed: Record<string, unknown> = JSON.parse(text)
  const result = pick<IngestPreview>(parsed, previewKeys)
  result.battery_percent_json = jsonFieldText(text, 'battery_percent')
  Object.assign(result, readingEvidence(text))
  if (allowRaw && Object.hasOwn(parsed, 'raw_event')) { result.raw_event = parsed.raw_event; result.raw_event_json = jsonFieldText(text, 'raw_event') }
  return result
}
export function previewWriteResponse(text: string, allowRaw: boolean): { message: RawMessage; preview: IngestPreview } {
  const parsed = JSON.parse(text)
  return { message: messageMetadata(parsed.message), preview: previewResponse(jsonFieldText(text, 'preview')!, allowRaw) }
}

export function applyResponse(text: string): MessageApplyResult {
  const parsed: Record<string, unknown> = JSON.parse(text)
  const result = { ...pick<MessageApplyResult>(parsed, applyKeys), ...readingEvidence(text), battery_percent_json: jsonFieldText(text, 'battery_percent') }
  const state = result.application_state
  if (state !== 'APPLIED' && state !== 'STALE' && state !== 'REJECTED') throw new Error('未知应用状态')
  return result
}
