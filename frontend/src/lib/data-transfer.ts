import { ApiError, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import { jsonArrayItems, jsonFieldText, parseJsonDocument } from '@/lib/json-document'
import { integerToken } from '@/lib/parameter-json'
import type { Audience, Paginated } from '@/lib/types'

export type DataTransferKind = 'DEVICES' | 'UNITS' | 'PLACES' | 'USERS' | 'PLATFORM_USERS' | 'ROLES' | 'CONTACTS' | 'SIM_CARDS' | 'PRODUCT_DRAFTS' | 'RULE_DRAFTS' | 'POLICY_DRAFTS' | 'UNIT_CONTACTS' | 'UNIT_NOTIFICATION_RULES' | 'MONITORING' | 'EVENTS' | 'ALARMS' | 'FAULTS' | 'NOTIFICATION_ATTEMPTS' | 'AUDIT' | 'MY_NOTIFICATIONS' | 'WORK_ORDERS' | 'FIRE_ARCHIVES' | 'ALARM_SESSIONS' | 'TOPOLOGY_LINKS' | 'VIDEO_RESOURCES' | 'CONTROLLER_RESOURCES' | 'APPLICATION_OUTPUTS' | 'RULE_FLOWS'
export type DataTransferFormat = 'CSV' | 'XLSX'
export type DataTransferJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'RECONFIRM_REQUIRED' | 'CANCELLED'
export type DataTransferEffect = 'MASTER_DATA' | 'DRAFT_ONLY' | 'DISABLED_DRAFT' | 'APPLY_ON_CONFIRM' | 'READ_ONLY'
export interface DataTransferCapability { kind: DataTransferKind; columns: string[]; read_permission: string; write_permission: string | null; importable: boolean; exportable: boolean; tenant_required: boolean; audiences: Audience[]; effect: DataTransferEffect }
export interface DataTransferCapabilities { kinds: DataTransferCapability[]; protected_storage_ready: boolean }
export interface DataTransferFile { filename: string; media_type: string; content_base64: string; content_sha256?: string }
export interface DataTransferPreviewRow { row_no: number; operation: string; status: 'VALID' | 'INVALID'; errors: string[] }
export interface DataTransferPreview { plan_id: string; kind: DataTransferKind; format: DataTransferFormat; tenant_id: string | null; source_sha256: string; row_count: number; valid_count: number; error_count: number; rows: DataTransferPreviewRow[] }
export interface DataTransferJob { id: string; kind: DataTransferKind; tenant_id: string | null; plan_id: string; source_sha256: string; status: DataTransferJobStatus; row_count: number; success_count: number; error_count: number; created_at: string; updated_at: string }
export interface DataTransferJobRow { row_no: number; status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'INVALID'; result_id: string | null; errors: string[] }
export interface DataTransferErrorRow { row_no: number; errors: string[] }
export type DataTransferRows = DataTransferJobRow[]
export type DataTransferErrors = DataTransferErrorRow[]
export interface DataTransferExport { id: string; kind: DataTransferKind; tenant_id: string | null; format: DataTransferFormat; status: 'COMPLETED' | 'EXPIRED'; content_sha256: string; row_count: number; created_at: string; expires_at: string }
export interface TransferScope { tenant_id?: string; filters?: Record<string, string | number | boolean | null>; selected_ids?: string[] }

export const dataTransferLabels: Record<DataTransferKind, string> = {
  DEVICES: '设备资产', UNITS: '单位', PLACES: '位置', USERS: '单位用户与授权', PLATFORM_USERS: '平台用户与授权', ROLES: '角色模板', CONTACTS: '通知联系人', SIM_CARDS: 'SIM卡', PRODUCT_DRAFTS: '产品物模型草稿', RULE_DRAFTS: '通知规则草稿', POLICY_DRAFTS: '离线阈值配置', UNIT_CONTACTS: '本单位联系人', UNIT_NOTIFICATION_RULES: '本单位通知规则草稿', MONITORING: '设备监测值', EVENTS: '设备事件', ALARMS: '报警记录', FAULTS: '故障记录', NOTIFICATION_ATTEMPTS: '真实发送尝试', AUDIT: '操作审计', MY_NOTIFICATIONS: '我的通知',
  WORK_ORDERS: '维保工单草稿与记录', FIRE_ARCHIVES: '消防档案元数据', ALARM_SESSIONS: '报警归并记录',
  TOPOLOGY_LINKS: '网关拓扑关系（仅人工关系）', VIDEO_RESOURCES: '视频资源元数据（不含端点/凭据）', CONTROLLER_RESOURCES: '控制器关联草稿（停用）', APPLICATION_OUTPUTS: '应用输出配置清单（不含地址凭据）',
  RULE_FLOWS: '规则流草稿（导入不启用）',
}
export const dataTransferEffects: Record<DataTransferEffect, string> = {
  MASTER_DATA: '确认后写入主数据', DRAFT_ONLY: '仅创建草稿，须另行审核发布或开启', DISABLED_DRAFT: '只创建停用规则，需另行审核启用', APPLY_ON_CONFIRM: '确认即发布应用，将改变有效配置', READ_ONLY: '只读导出，不改变业务数据',
}
const statuses: Record<DataTransferJobStatus, true> = { QUEUED: true, RUNNING: true, COMPLETED: true, COMPLETED_WITH_ERRORS: true, RECONFIRM_REQUIRED: true, CANCELLED: true }

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('数据交换响应结构未确认')
  return value as Record<string, unknown>
}
function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('数据交换文本字段未确认')
  return value
}
function natural(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) throw new Error('数据交换计数字段未确认')
  return value
}
function kindValue(value: unknown): DataTransferKind {
  const result = text(value)
  if (!Object.hasOwn(dataTransferLabels, result)) throw new Error('数据交换类型未确认')
  return result as DataTransferKind
}
function formatValue(value: unknown): DataTransferFormat {
  if (value !== 'CSV' && value !== 'XLSX') throw new Error('数据文件格式未确认')
  return value
}
function strings(value: unknown): string[] {
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) throw new Error('数据交换列表字段未确认')
  return [...value]
}
function numericTokens(raw: string, value: unknown): void {
  if (typeof value === 'number') { integerToken(raw); return }
  if (Array.isArray(value)) { const items = jsonArrayItems(raw); value.forEach((item, index) => numericTokens(items[index], item)); return }
  if (value && typeof value === 'object') for (const [key, item] of Object.entries(value)) numericTokens(jsonFieldText(raw, key)!, item)
}
function decodeDocument(raw: string): unknown { const value = parseJsonDocument(raw)
numericTokens(raw, value)
return value }
function digest(value: unknown): string {
  const result = text(value)
  if (!/^[a-f0-9]{64}$/.test(result)) throw new Error('数据文件摘要未确认')
  return result
}
function timestamp(value: unknown): string {
  const result = text(value)
  if (!Number.isFinite(Date.parse(result))) throw new Error('数据交换时间未确认')
  return result
}
function tenantValue(value: unknown): string | null {
  if (value === null) return null
  const result = text(value)
  if (!result || result.length > 64) throw new Error('数据交换单位范围未确认')
  return result
}
function capability(value: unknown): DataTransferCapability {
  const row = object(value), audiences = strings(row.audiences)
  if (audiences.length === 0 || audiences.some(value => value !== 'management' && value !== 'customer') || typeof row.importable !== 'boolean' || typeof row.exportable !== 'boolean' || typeof row.tenant_required !== 'boolean' || !Object.hasOwn(dataTransferEffects, String(row.effect))) throw new Error('数据交换能力声明未确认')
  const result = { kind: kindValue(row.kind), columns: strings(row.columns), read_permission: text(row.read_permission), write_permission: row.write_permission === null ? null : text(row.write_permission), importable: row.importable, exportable: row.exportable, tenant_required: row.tenant_required, audiences: audiences as Audience[], effect: row.effect as DataTransferEffect }
  if (result.importable && (!result.write_permission || result.columns.length === 0)) throw new Error('导入能力缺少真实写入合同')
  return result
}
function jobValue(value: unknown): DataTransferJob {
  const row = object(value)
  if (typeof row.status !== 'string' || !Object.hasOwn(statuses, row.status)) throw new Error('任务状态未确认')
  const result = { id: text(row.id), kind: kindValue(row.kind), tenant_id: tenantValue(row.tenant_id), plan_id: text(row.plan_id), source_sha256: digest(row.source_sha256), status: row.status as DataTransferJobStatus, row_count: natural(row.row_count), success_count: natural(row.success_count), error_count: natural(row.error_count), created_at: timestamp(row.created_at), updated_at: timestamp(row.updated_at) }
  if (result.success_count + result.error_count > result.row_count) throw new Error('任务结果计数不一致')
  return result
}
function exportValue(value: unknown): DataTransferExport {
  const row = object(value)
  if (row.status !== 'COMPLETED' && row.status !== 'EXPIRED') throw new Error('导出状态未确认')
  return { id: text(row.id), kind: kindValue(row.kind), tenant_id: tenantValue(row.tenant_id), format: formatValue(row.format), status: row.status, content_sha256: digest(row.content_sha256), row_count: natural(row.row_count), created_at: timestamp(row.created_at), expires_at: timestamp(row.expires_at) }
}
function fileValue(raw: string): DataTransferFile {
  const row = object(decodeDocument(raw)), filename = text(row.filename), mediaType = text(row.media_type), base64 = text(row.content_base64)
  if (!filename || /[\\/\u0000-\u001f]/.test(filename) || !(mediaType.startsWith('text/csv') || mediaType === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet') || base64.length % 4 !== 0) throw new Error('数据文件内容未确认')
  return { filename, media_type: mediaType, content_base64: base64, ...(row.content_sha256 == null ? {} : { content_sha256: digest(row.content_sha256) }) }
}
function artifactValue(raw: string): DataTransferFile {
  const result = fileValue(raw)
  if (!result.content_sha256) throw new Error('数据文件缺少摘要，未下载')
  return result
}
function pageValue<T>(raw: string, decode: (value: unknown) => T): Paginated<T> {
  const row = object(decodeDocument(raw))
  if (!Array.isArray(row.items)) throw new Error('数据交换分页未确认')
  return { items: row.items.map(decode), total: natural(row.total), page: natural(row.page, 1), size: natural(row.size, 1) }
}
function context(permission?: string, tenantId?: string | null) {
  const session = useSession.getState()
  if (!session.user || session.user.must_change_password || !['management', 'customer'].includes(session.audience)) throw new ApiError(403, 'TRANSFER_SESSION_REQUIRED', '当前会话不可使用数据导入导出')
  if (permission && !session.permissions.includes(permission)) throw new ApiError(403, 'TRANSFER_PERMISSION_REQUIRED', '当前账户未获相应数据交换权限')
  if (session.audience === 'customer' && (!session.user.tenant_id || tenantId != null && tenantId !== session.user.tenant_id)) throw new ApiError(403, 'TRANSFER_UNIT_MISMATCH', '客户数据范围只能是当前会话所属单位')
  return { base: `/${session.audience}/data-transfer`, tenant: session.audience === 'customer' ? session.user.tenant_id : tenantId ?? null }
}

export async function saveDataTransferFile(file: DataTransferFile, signal?: AbortSignal): Promise<void> {
  const owner = useSession.getState(), authGeneration = owner.authGeneration, generation = owner.generation, token = owner.token
  if (signal?.aborted) throw new DOMException('下载已取消', 'AbortError')
  const binary = atob(file.content_base64), bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
  if (file.content_sha256) {
    const hash = await crypto.subtle.digest('SHA-256', bytes)
    const actual = Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('')
    if (actual !== file.content_sha256) throw new Error('文件摘要校验失败，未下载内容')
  }
  const current = useSession.getState()
  if (signal?.aborted || current.authGeneration !== authGeneration || current.generation !== generation || current.token !== token) throw new DOMException('下载会话已变化', 'AbortError')
  const url = URL.createObjectURL(new Blob([bytes], { type: file.media_type })), link = document.createElement('a')
  try { link.href = url; link.download = file.filename; document.body.appendChild(link); link.click() }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 0) }
}

export const dataTransferApi = {
  capabilities(signal?: AbortSignal) {
    const { base } = context()
    return request<DataTransferCapabilities>(`${base}/kinds`, { signal, decode: raw => {
      const row = object(decodeDocument(raw))
      if (!Array.isArray(row.kinds) || typeof row.protected_storage_ready !== 'boolean') throw new Error('数据交换能力响应未确认')
      const result = row.kinds.map(capability)
      if (new Set(result.map(item => item.kind)).size !== result.length) throw new Error('数据交换能力重复')
      return { kinds: result, protected_storage_ready: row.protected_storage_ready }
    } })
  },
  template(kind: DataTransferKind, format: DataTransferFormat, tenantId?: string, signal?: AbortSignal) {
    const { base, tenant } = context('imports:read', tenantId), query = new URLSearchParams({ kind, format })
    return request<DataTransferFile>(`${base}/template?${query}`, { tenantScoped: !!tenant, tenantId: tenant ?? undefined, signal, decode: fileValue })
  },
  preview(kind: DataTransferKind, format: DataTransferFormat, file: DataTransferFile, tenantId?: string, signal?: AbortSignal) {
    const { base, tenant } = context('imports:write', tenantId)
    return request<DataTransferPreview>(`${base}/preview`, { method: 'POST', tenantScoped: !!tenant, tenantId: tenant ?? undefined, signal, body: JSON.stringify({ kind, format, tenant_id: tenant, content_base64: file.content_base64 }), decode: raw => {
      const row = object(decodeDocument(raw))
      if (!Array.isArray(row.rows)) throw new Error('导入预览缺少逐行结果')
      const result: DataTransferPreview = { plan_id: text(row.plan_id), kind: kindValue(row.kind), format: formatValue(row.format), tenant_id: tenantValue(row.tenant_id), source_sha256: digest(row.source_sha256), row_count: natural(row.row_count), valid_count: natural(row.valid_count), error_count: natural(row.error_count), rows: row.rows.map(value => { const item = object(value); if (item.status !== 'VALID' && item.status !== 'INVALID') throw new Error('预览行状态未确认'); return { row_no: natural(item.row_no, 2), operation: text(item.operation), status: item.status, errors: strings(item.errors) } }) }
      if (result.kind !== kind || result.format !== format || result.tenant_id !== tenant || result.valid_count + result.error_count !== result.row_count || result.rows.length !== result.row_count) throw new Error('预览范围或计数不一致')
      return result
    } })
  },
  confirm(preview: DataTransferPreview, reason: string, key: string, signal?: AbortSignal) {
    const { base, tenant } = context('imports:write', preview.tenant_id)
    return request<DataTransferJob>(`${base}/confirm`, { method: 'POST', tenantScoped: !!tenant, tenantId: tenant ?? undefined, headers: { 'Idempotency-Key': key }, signal, body: JSON.stringify({ plan_id: preview.plan_id, tenant_id: tenant, expected_sha256: preview.source_sha256, reason }), decode: raw => {
      const job = jobValue(decodeDocument(raw))
      if (job.kind !== preview.kind || job.tenant_id !== tenant || job.plan_id !== preview.plan_id || job.source_sha256 !== preview.source_sha256) throw new Error('提交回执与已确认预览不一致')
      return job
    } })
  },
  jobs(page = 1, signal?: AbortSignal) {
    const { base } = context('imports:read')
    return request<Paginated<DataTransferJob>>(`${base}/jobs?page=${page}`, { signal, decode: raw => pageValue(raw, jobValue) })
  },
  job(id: string, signal?: AbortSignal) {
    const { base } = context('imports:read')
    return request<DataTransferJob>(`${base}/jobs/${encodeURIComponent(id)}`, { signal, decode: raw => jobValue(decodeDocument(raw)) })
  },
  rows(id: string, signal?: AbortSignal) {
    const { base } = context('imports:read')
    return request<DataTransferRows>(`${base}/jobs/${encodeURIComponent(id)}/rows`, { signal, decode: raw => {
      const rows = decodeDocument(raw)
      if (!Array.isArray(rows)) throw new Error('导入任务逐行结果未确认')
      return rows.map(value => { const row = object(value); if (!['PENDING', 'SUCCEEDED', 'FAILED', 'INVALID'].includes(String(row.status))) throw new Error('导入行状态未确认'); return { row_no: natural(row.row_no, 2), status: row.status as DataTransferJobRow['status'], result_id: row.result_id === null ? null : text(row.result_id), errors: strings(row.errors) } })
    } })
  },
  errorsDownload(id: string, format: DataTransferFormat, signal?: AbortSignal) {
    const { base } = context('imports:read')
    return request<DataTransferFile>(`${base}/jobs/${encodeURIComponent(id)}/errors/download?format=${format}`, { signal, decode: artifactValue })
  },
  control(job: DataTransferJob, action: 'reconfirm' | 'cancel', reason: string, key: string, signal?: AbortSignal) {
    const { base, tenant } = context('imports:write', job.tenant_id)
    return request<DataTransferJob>(`${base}/jobs/${encodeURIComponent(job.id)}/${action}`, { method: 'POST', tenantScoped: !!tenant, tenantId: tenant ?? undefined, headers: { 'Idempotency-Key': key }, signal, body: JSON.stringify({ reason }), decode: raw => jobValue(decodeDocument(raw)) })
  },
  createExport(kind: DataTransferKind, format: DataTransferFormat, reason: string, key: string, scope: TransferScope = {}, signal?: AbortSignal) {
    const { base, tenant } = context('exports:write', scope.tenant_id)
    return request<DataTransferExport>(`${base}/exports`, { method: 'POST', tenantScoped: !!tenant, tenantId: tenant ?? undefined, headers: { 'Idempotency-Key': key }, signal, body: JSON.stringify({ kind, format, tenant_id: tenant, filters: scope.filters, selected_ids: scope.selected_ids, reason }), decode: raw => {
      const exported = exportValue(decodeDocument(raw))
      if (exported.kind !== kind || exported.tenant_id !== tenant || exported.format !== format) throw new Error('导出回执与已确认范围不一致')
      return exported
    } })
  },
  exports(page = 1, signal?: AbortSignal) {
    const { base } = context('exports:read')
    return request<Paginated<DataTransferExport>>(`${base}/exports?page=${page}`, { signal, decode: raw => pageValue(raw, exportValue) })
  },
  downloadExport(id: string, signal?: AbortSignal) {
    const { base } = context('exports:read')
    return request<DataTransferFile>(`${base}/exports/${encodeURIComponent(id)}/download`, { signal, decode: artifactValue })
  },
}
