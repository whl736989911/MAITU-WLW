import { ApiError, request } from './api'
import { useSession } from './store'
import { parseJsonDocument } from './json-document'
import type { Paginated } from './types'

export const ARCHIVE_STATUSES = ['ACTIVE', 'ARCHIVED'] as const
export type ArchiveStatus = typeof ARCHIVE_STATUSES[number]

export interface ArchiveSummary {
  id: string; tenant_id: string; title: string; category: string; archive_number: string | null
  device_id: string | null; device_name: string | null; place_id: string | null; place_name: string | null
  issued_on: string | null; expires_on: string | null; description: string | null; status: ArchiveStatus
  revision: number; created_at: string; updated_at: string; archived_by: string | null
  archived_by_name: string | null; archived_at: string | null; archived_reason: string | null
  attachment_count: number
}
export interface ArchiveAttachment {
  id: string; filename: string; mime_type: string; byte_size: number; content_sha256: string
  uploaded_by: string; uploader_name: string | null; uploaded_at: string
}
export interface ArchiveHistory {
  id: string; action: string; revision_before: number | null; revision_after: number
  actor_id: string; actor_name: string | null; reason: string; created_at: string
}
export interface ArchiveDetail { archive: ArchiveSummary; attachments: ArchiveAttachment[] }
export type ArchivePage = Paginated<ArchiveSummary>

function fail(): never { throw new ApiError(200, 'FIRE_ARCHIVE_RESPONSE_UNCONFIRMED', '消防档案响应格式未确认；不推断空值或成功。') }
function obj(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail()
  const record = value as Record<string, unknown>
  for (const key of keys) if (!Object.hasOwn(record, key)) fail()
  return record
}
function text(value: unknown): string { if (typeof value !== 'string') fail(); return value }
function nullableText(value: unknown): string | null { return value === null ? null : text(value) }
function integer(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) fail()
  return value
}
function array(value: unknown, mapper: (item: unknown) => unknown): unknown[] {
  if (!Array.isArray(value)) fail()
  return value.map(mapper)
}
function summary(raw: unknown): ArchiveSummary {
  const row = obj(raw, ['id', 'tenant_id', 'title', 'category', 'archive_number', 'device_id', 'device_name',
    'place_id', 'place_name', 'issued_on', 'expires_on', 'description', 'status', 'revision', 'created_at',
    'updated_at', 'archived_by', 'archived_by_name', 'archived_at', 'archived_reason', 'attachment_count'])
  const status = text(row.status)
  if (!(ARCHIVE_STATUSES as readonly string[]).includes(status)) fail()
  return { id: text(row.id), tenant_id: text(row.tenant_id), title: text(row.title), category: text(row.category),
    archive_number: nullableText(row.archive_number), device_id: nullableText(row.device_id),
    device_name: nullableText(row.device_name), place_id: nullableText(row.place_id),
    place_name: nullableText(row.place_name), issued_on: nullableText(row.issued_on),
    expires_on: nullableText(row.expires_on), description: nullableText(row.description),
    status: status as ArchiveStatus, revision: integer(row.revision), created_at: text(row.created_at),
    updated_at: text(row.updated_at), archived_by: nullableText(row.archived_by),
    archived_by_name: nullableText(row.archived_by_name), archived_at: nullableText(row.archived_at),
    archived_reason: nullableText(row.archived_reason), attachment_count: integer(row.attachment_count) }
}
function attachment(raw: unknown): ArchiveAttachment {
  const row = obj(raw, ['id', 'filename', 'mime_type', 'byte_size', 'content_sha256', 'uploaded_by',
    'uploader_name', 'uploaded_at'])
  return { id: text(row.id), filename: text(row.filename), mime_type: text(row.mime_type),
    byte_size: integer(row.byte_size, 1), content_sha256: text(row.content_sha256),
    uploaded_by: text(row.uploaded_by), uploader_name: nullableText(row.uploader_name),
    uploaded_at: text(row.uploaded_at) }
}
function history(raw: unknown): ArchiveHistory {
  const row = obj(raw, ['id', 'action', 'revision_before', 'revision_after', 'actor_id', 'actor_name',
    'reason', 'created_at'])
  return { id: text(row.id), action: text(row.action),
    revision_before: row.revision_before === null ? null : integer(row.revision_before),
    revision_after: integer(row.revision_after), actor_id: text(row.actor_id), actor_name: nullableText(row.actor_name),
    reason: text(row.reason), created_at: text(row.created_at) }
}
function detail(raw: unknown): ArchiveDetail {
  const row = obj(raw, ['archive', 'attachments'])
  return { archive: summary(row.archive), attachments: array(row.attachments, attachment) as ArchiveAttachment[] }
}
export function decodeArchiveDetail(raw: string): ArchiveDetail { return detail(parseJsonDocument(raw)) }
export function decodeArchivePage(raw: string): ArchivePage {
  const row = obj(parseJsonDocument(raw), ['items', 'total', 'page', 'size'])
  return { items: array(row.items, summary) as ArchiveSummary[], total: integer(row.total),
    page: integer(row.page, 1), size: integer(row.size, 1) }
}
export function decodeArchiveHistory(raw: string): ArchiveHistory[] {
  return array(parseJsonDocument(raw), history) as ArchiveHistory[]
}

function scopedQuery(params: URLSearchParams): string {
  const session = useSession.getState()
  if (session.audience === 'management') {
    params.set('scope_selected', 'true')
    params.set('scope_tenant_ids', session.scopeTenantIds.join(','))
    params.set('scope_place_ids', session.scopePlaceIds.join(','))
  }
  return `?${params}`
}
export function archiveListQuery(input: { page: number; status: string; keyword: string }) {
  const params = new URLSearchParams({ page: String(input.page) })
  if (input.status) params.set('status', input.status)
  if (input.keyword) params.set('keyword', input.keyword)
  return scopedQuery(params)
}
export function getArchives(input: { page: number; status: string; keyword: string }, signal: AbortSignal) {
  const session = useSession.getState()
  return request<ArchivePage>(`/${session.audience}/fire-archives${archiveListQuery(input)}`,
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeArchivePage })
}
export function getArchive(id: string, signal: AbortSignal, tenantId?: string) {
  const session = useSession.getState()
  return request<ArchiveDetail>(`/${session.audience}/fire-archives/${encodeURIComponent(id)}${scopedQuery(new URLSearchParams())}`,
    { tenantScoped: true, tenantOptional: true, tenantId, signal, decode: decodeArchiveDetail })
}
export function getArchiveHistory(id: string, signal: AbortSignal) {
  const session = useSession.getState()
  return request<ArchiveHistory[]>(`/${session.audience}/fire-archives/${encodeURIComponent(id)}/history${scopedQuery(new URLSearchParams())}`,
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeArchiveHistory })
}

type Snapshot = { token: string | null; authGeneration: number }
function snapshot(): Snapshot { const s = useSession.getState(); return { token: s.token, authGeneration: s.authGeneration } }
function stale(owner: Snapshot) { const now = useSession.getState(); return now.token !== owner.token || now.authGeneration !== owner.authGeneration }

export async function downloadAttachment(archiveId: string, file: ArchiveAttachment, tenantId: string | undefined,
                                         signal: AbortSignal): Promise<void> {
  const session = useSession.getState(), owner = snapshot()
  if (!session.token) throw new ApiError(401, 'SESSION_REQUIRED', '请先登录')
  const headers = new Headers({ Accept: 'application/octet-stream', Authorization: `Bearer ${session.token}` })
  if (session.audience === 'management' && tenantId) headers.set('X-Tenant-Id', tenantId)
  const params = new URLSearchParams()
  if (session.audience === 'management') {
    params.set('scope_selected', 'true')
    params.set('scope_tenant_ids', session.scopeTenantIds.join(','))
    params.set('scope_place_ids', session.scopePlaceIds.join(','))
  }
  let response: Response
  try {
    response = await fetch(`/api/${session.audience}/fire-archives/${encodeURIComponent(archiveId)}/attachments/${encodeURIComponent(file.id)}/download?${params}`,
      { headers, signal, credentials: 'omit', cache: 'no-store', redirect: 'error' })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'ATTACHMENT_DOWNLOAD_UNCONFIRMED', '附件下载未确认，请显式重试。')
  }
  if (response.status === 401) { useSession.getState().clear('登录已失效，请重新登录。'); throw new ApiError(401, 'SESSION_EXPIRED', '登录已失效，请重新登录。') }
  if (!response.ok) throw new ApiError(response.status, 'ATTACHMENT_DOWNLOAD_FAILED', `附件下载失败（HTTP ${response.status}）`)
  if (!(response.headers.get('Content-Type') ?? '').toLowerCase().startsWith('application/octet-stream'))
    throw new ApiError(200, 'ATTACHMENT_RESPONSE_UNCONFIRMED', '附件响应类型未确认，未保存文件。')
  const bytes = new Uint8Array(await response.arrayBuffer())
  if (bytes.length !== file.byte_size) throw new ApiError(200, 'ATTACHMENT_RESPONSE_UNCONFIRMED', '附件大小与元数据不一致，未保存文件。')
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  const actual = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
  if (actual !== file.content_sha256) throw new ApiError(200, 'ATTACHMENT_RESPONSE_UNCONFIRMED', '附件摘要与元数据不一致，未保存文件。')
  if (stale(owner)) throw new DOMException('档案会话已变化', 'AbortError')
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }))
  const link = document.createElement('a')
  try {
    link.href = url
    link.download = file.filename
    document.body.appendChild(link)
    link.click()
  } finally {
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

/** Reads a browser File as bounded base64 without trusting its declared name/type. */
export async function attachmentPayload(file: File): Promise<{ filename: string; mime_type: string; content_base64: string }> {
  if (file.size < 1 || file.size > 1_048_576) throw new ApiError(400, 'ATTACHMENT_SIZE_INVALID', '附件必须为1字节至1MiB。')
  const mime = file.type && /^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}\/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}$/.test(file.type)
    ? file.type : 'application/octet-stream'
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ''
  for (let offset = 0; offset < bytes.length; offset += 0x8000) binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000))
  return { filename: file.name, mime_type: mime, content_base64: btoa(binary) }
}
