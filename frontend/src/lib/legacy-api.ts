import { ApiError, request, scopeQuery } from './api'
import { useSession } from './store'

export type LegacyIngestState = 'NORMALIZED' | 'DUPLICATE' | 'PENDING_DECODER' | 'FAILED'
export type LegacyIngestEvent = { id?: string; kind: string; method?: string | null; params_json?: unknown; observed_at?: string | null }
export type LegacyIngestMessage = { id: string; tenant_id: string; ingress: string; vendor_platform: string; vendor_device_id: string; device_id: string | null; payload_raw?: string | null; received_at: string; observed_at?: string | null; state: LegacyIngestState; failure_code: string | null; duplicate_count?: number; event_count?: number; events?: LegacyIngestEvent[] }
export type LegacyMessagePage = { items: LegacyIngestMessage[]; total: number; page: number; size: number }
export type LegacyMessageDetail = { summary: LegacyIngestMessage; payload_raw: string | null; payload_redacted?: boolean; events: LegacyIngestEvent[] }
export type LegacyReconcileDifference = { id: string; run_id: string; kind: string; legacy_id: string | null; new_id: string | null; detail: string; created_at: string }
export type LegacyReconcilePage = { items: LegacyReconcileDifference[]; total: number; page: number; size: number }
export type LegacyUnit = { id: string; name: string }

export type LegacyExportKind = 'MESSAGES' | 'RECONCILE'
export type LegacyExportTarget = 'TENANT' | 'ALL_AUTHORIZED'
export type LegacyExportFile = { kind: string; filename: string; media_type: string; content_base64: string; content_sha256: string; row_count: number }
export type LegacyExportBody = { kind: LegacyExportKind; target: LegacyExportTarget; tenant_id?: string | null; state?: string; platform?: string; run_id?: string; max_rows?: number; reason: string }

export type LegacyDictionaryPreview = {
  source_sha256: string
  source_companies: number
  source_places: number
  source_products: number
  source_attributes: number
  source_decoder_files: number
  damaged_product_rows: number
  duplicate_model_rows: number
  empty_lines: number
  places_tenant_missing: number
  places_identity_conflict: number
  places_archived: number
  versions_existing: number
  decoders_existing: number
  hierarchy_configured: boolean
  hierarchy_source_rows: number
  grant_legacy_units: number
}
export type LegacyImportStats = { tenants: number; places: number; products: number; versions: number; attributes: number; decoders: number; differences: number; hierarchy_run_id?: string | null; hierarchy_source_sha256?: string | null; hierarchy_applied: number; hierarchy_skipped: number }
export type LegacyCompanyCount = { company_id: string; rows: number }
export type LegacyDevicePreview = {
  source_sha256: string
  total_rows: number
  selected_rows: number
  importable_new: number
  existing: number
  archived: number
  moved: number
  place_missing: number
  model_unmatched: number
  model_ambiguous: number
  model_single: number
  identity_invalid: number
  eui_missing: number
  eui_conflict: number
  alias_conflict: number
  companies: LegacyCompanyCount[]
}
export type LegacyDeviceImportStats = { devices: number; aliases: number; differences: number }

export type LegacyDecoderRevision = { legacy_product_id: string; revision: number; source_sha256: string; bytes: number; created_at: string; active: boolean }
export type LegacyDecoderPreview = { ok: boolean; code: string | null; message: string | null; events: number; events_json: string | null; source_sha256: string }
export type LegacyQuarantine = { id: string; vendor_platform: string; vendor_device_id: string | null; received_at: string; observed_at: string | null; reason: string; candidate_device_id: string | null }
export type LegacyQuarantinePage = { items: LegacyQuarantine[]; total: number; page: number; size: number }

export const legacyApi = {
  messages: (page = 1, size = 20, state: LegacyIngestState | '' = '', platform = '', signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    if (state) query.set('state', state)
    if (platform) query.set('platform', platform)
    return request<LegacyMessagePage>(scopeQuery(`/management/legacy-ingest/messages?${query}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal })
  },
  message: (id: string, signal?: AbortSignal, tenantId?: string) => request<LegacyMessageDetail>(`/management/legacy-ingest/messages/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, signal }),
  differences: (runId = '', page = 1, size = 20, signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    if (runId) query.set('runId', runId)
    return request<LegacyReconcilePage>(scopeQuery(`/management/legacy-ingest/reconcile?${query}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal })
  },
  /** Explicit operator targets for exports and device import; never inferred from a default tenant. */
  units: (signal?: AbortSignal) => request<LegacyUnit[]>('/management/legacy-ingest/units', { signal }),
  exportFile: (body: LegacyExportBody, signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) =>
    request<LegacyExportFile>(scopeQuery('/management/legacy-ingest/exports', tenantIds, placeIds), { method: 'POST', body: JSON.stringify(body), signal }),
  previewDictionaries: (signal?: AbortSignal) => request<LegacyDictionaryPreview>('/management/legacy-ingest/import-dictionaries/preview', { method: 'POST', signal }),
  confirmDictionaries: (body: { expected_sha256: string; reason: string }, key: string, signal?: AbortSignal) =>
    request<LegacyImportStats>('/management/legacy-ingest/import-dictionaries/confirm', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
  previewDevices: (companyIds: string[], signal?: AbortSignal) =>
    request<LegacyDevicePreview>('/management/legacy-ingest/import-devices/preview', { method: 'POST', body: JSON.stringify({ company_ids: companyIds }), signal }),
  confirmDevices: (body: { expected_sha256: string; company_ids: string[]; reason: string }, key: string, signal?: AbortSignal) =>
    request<LegacyDeviceImportStats>('/management/legacy-ingest/import-devices/confirm', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
  decoderRevisions: (productId: string, signal?: AbortSignal) =>
    request<LegacyDecoderRevision[]>(`/management/legacy-ingest/product-decoders/${encodeURIComponent(productId)}`, { signal }),
  importDecoder: (body: { legacy_product_id: string; source: string; reason: string }, key: string, signal?: AbortSignal) =>
    request<LegacyDecoderRevision>('/management/legacy-ingest/product-decoders', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
  /** Runs operator-supplied source in the server's bounded decoder sandbox; nothing is persisted. */
  previewDecoderSource: (body: { source: string; payload_hex: string; attrs?: Record<string, unknown> }, signal?: AbortSignal) =>
    request<LegacyDecoderPreview>('/management/legacy-ingest/product-decoders/preview', { method: 'POST', body: JSON.stringify(body), signal }),
  /** Executes the stored revision; the source text is never sent to the browser. */
  previewDecoderRevision: (productId: string, revision: number, body: { payload_hex: string; attrs?: Record<string, unknown> }, signal?: AbortSignal) =>
    request<LegacyDecoderPreview>(`/management/legacy-ingest/product-decoders/${encodeURIComponent(productId)}/revisions/${revision}/preview`, { method: 'POST', body: JSON.stringify(body), signal }),
  /** Parent-owned endpoint: dedicated ownership-exception permission, no raw payload. */
  quarantines: (page = 1, size = 20, signal?: AbortSignal) => {
    if (!useSession.getState().permissions.includes('ownership-exceptions:read'))
      return Promise.reject(new ApiError(403, 'OWNERSHIP_EXCEPTIONS_REQUIRED', '需要独立的归属异常读取权限'))
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    return request<LegacyQuarantinePage>(`/management/legacy-ingest/quarantines?${query}`, { signal })
  },
}
