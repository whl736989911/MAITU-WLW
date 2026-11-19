import { request, scopeQuery } from './api'

export const RETENTION_KINDS = ['RAW_MESSAGES', 'DEVICE_COMMANDS', 'AUDIT_LOG'] as const
export type RetentionKind = (typeof RETENTION_KINDS)[number]
export const retentionKindLabels: Record<RetentionKind, string> = {
  RAW_MESSAGES: '原文与预览',
  DEVICE_COMMANDS: '旧设备命令与尝试',
  AUDIT_LOG: '审计日志',
}
export const retentionKindNotes: Record<RetentionKind, string> = {
  RAW_MESSAGES: '按接收时间；存在处理任务或重解析任务的消息永不删除，先删除其预览。',
  DEVICE_COMMANDS: '只清理终态旧命令；PENDING/DISPATCHED 永不删除，独立硬件命令台账与支付事实不在此类型内。',
  AUDIT_LOG: '按发生时间；不涉及财务流水、通知对账或独立设备服务命令。',
}

export interface RetentionPolicy {
  tenant_id: string
  tenant_name: string
  kind: RetentionKind
  keep_days: number | null
  enabled: boolean
  updated_at: string | null
  revision: number
  configured: boolean
}
export interface RetentionPolicyPage {
  mode: 'UNIT' | 'SCOPE'
  items: RetentionPolicy[]
  total: number
  page: number
  size: number
  authorized_units: number
  configured_policies: number
  unconfigured_kind_slots: number
  scope_tenant_ids: string[]
  scope_place_ids: string[]
  scope_empty: boolean
}
export interface RetentionRun {
  id: string
  tenant_id: string
  tenant_name: string
  kind: RetentionKind
  keep_days: number
  cutoff_at: string
  dry_run: boolean
  scanned: number
  eligible: number | null
  deleted: number | null
  legacy_potential_count: boolean
  candidate_fingerprint: string | null
  policy_revision: number | null
  expires_at: string | null
  source_preview_id: string | null
  reason: string | null
  created_at: string
}
export interface RetentionRunPage { items: RetentionRun[]; total: number; page: number; size: number }

type Obj = Record<string, unknown>
function obj(value: unknown, label: string): Obj {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}响应格式无效`)
  return value as Obj
}
function text(value: unknown, label: string): string {
  if (typeof value !== 'string') throw new Error(`${label}响应格式无效`)
  return value
}
function nullableText(value: unknown, label: string): string | null {
  if (value === null) return null
  return text(value, label)
}
function num(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new Error(`${label}响应格式无效`)
  return value
}
function nullableNum(value: unknown, label: string): number | null {
  if (value === null) return null
  return num(value, label)
}
function bool(value: unknown, label: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`${label}响应格式无效`)
  return value
}
function kind(value: unknown): RetentionKind {
  if (typeof value !== 'string' || !(RETENTION_KINDS as readonly string[]).includes(value)) throw new Error('保留类型响应未确认')
  return value as RetentionKind
}
function stringArray(value: unknown, label: string): string[] {
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) throw new Error(`${label}响应格式无效`)
  return value as string[]
}

export function parseRetentionPolicy(value: unknown): RetentionPolicy {
  const row = obj(value, '保留策略')
  return {
    tenant_id: text(row.tenant_id, 'tenant_id'),
    tenant_name: text(row.tenant_name, 'tenant_name'),
    kind: kind(row.kind),
    keep_days: nullableNum(row.keep_days, 'keep_days'),
    enabled: bool(row.enabled, 'enabled'),
    updated_at: nullableText(row.updated_at, 'updated_at'),
    revision: num(row.revision, 'revision'),
    configured: bool(row.configured, 'configured'),
  }
}


export function parseRetentionRun(value: unknown): RetentionRun {
  const row = obj(value, '保留运行')
  return {
    id: text(row.id, 'id'),
    tenant_id: text(row.tenant_id, 'tenant_id'),
    tenant_name: text(row.tenant_name, 'tenant_name'),
    kind: kind(row.kind),
    keep_days: num(row.keep_days, 'keep_days'),
    cutoff_at: text(row.cutoff_at, 'cutoff_at'),
    dry_run: bool(row.dry_run, 'dry_run'),
    scanned: num(row.scanned, 'scanned'),
    eligible: nullableNum(row.eligible, 'eligible'),
    deleted: nullableNum(row.deleted, 'deleted'),
    legacy_potential_count: bool(row.legacy_potential_count, 'legacy_potential_count'),
    candidate_fingerprint: nullableText(row.candidate_fingerprint, 'candidate_fingerprint'),
    policy_revision: nullableNum(row.policy_revision, 'policy_revision'),
    expires_at: nullableText(row.expires_at, 'expires_at'),
    source_preview_id: nullableText(row.source_preview_id, 'source_preview_id'),
    reason: nullableText(row.reason, 'reason'),
    created_at: text(row.created_at, 'created_at'),
  }
}


function parsePolicyPage(raw: string): RetentionPolicyPage {
  const value = obj(JSON.parse(raw), '保留策略列表')
  if (!Array.isArray(value.items)) throw new Error('保留策略列表响应格式无效')
  if (value.mode !== 'UNIT' && value.mode !== 'SCOPE') throw new Error('保留策略模式响应未确认')
  return {
    mode: value.mode as 'UNIT' | 'SCOPE',
    items: value.items.map(parseRetentionPolicy),
    total: num(value.total, 'total'),
    page: num(value.page, 'page'),
    size: num(value.size, 'size'),
    authorized_units: num(value.authorized_units, 'authorized_units'),
    configured_policies: num(value.configured_policies, 'configured_policies'),
    unconfigured_kind_slots: num(value.unconfigured_kind_slots, 'unconfigured_kind_slots'),
    scope_tenant_ids: stringArray(value.scope_tenant_ids, 'scope_tenant_ids'),
    scope_place_ids: stringArray(value.scope_place_ids, 'scope_place_ids'),
    scope_empty: bool(value.scope_empty, 'scope_empty'),
  }
}
function parseRunPage(raw: string): RetentionRunPage {
  const value = obj(JSON.parse(raw), '保留运行列表')
  if (!Array.isArray(value.items)) throw new Error('保留运行列表响应格式无效')
  return {
    items: value.items.map(parseRetentionRun),
    total: num(value.total, 'total'),
    page: num(value.page, 'page'),
    size: num(value.size, 'size'),
  }
}

const retentionRead = { tenantScoped: true, tenantOptional: true } as const

export const retentionApi = {
  policies: (page: number, size: number, tenantId: string | undefined, scopeTenantIds: string[], scopePlaceIds: string[], signal?: AbortSignal) => {
    const path = tenantId
      ? `/management/retention/policies?page=1&size=3`
      : scopeQuery(`/management/retention/policies?page=${page}&size=${size}`, scopeTenantIds, scopePlaceIds)
    return request<RetentionPolicyPage>(path, { ...retentionRead, tenantId, signal, decode: parsePolicyPage })
  },
  configure: (body: { kind: RetentionKind; keep_days: number; enabled: boolean; expected_revision: number; reason: string }, key: string, tenantId: string, signal?: AbortSignal) =>
    request<RetentionPolicy>('/management/retention/policies', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: raw => parseRetentionPolicy(JSON.parse(raw)) }),
  preview: (body: { kind: RetentionKind }, key: string, tenantId: string, signal?: AbortSignal) =>
    request<RetentionRun>('/management/retention/preview', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: raw => parseRetentionRun(JSON.parse(raw)) }),
  execute: (body: { kind: RetentionKind; preview_id: string; expected_revision: number; reason: string }, key: string, tenantId: string, signal?: AbortSignal) =>
    request<RetentionRun>('/management/retention/execute', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: raw => parseRetentionRun(JSON.parse(raw)) }),
  runs: (page: number, size: number, kind: RetentionKind | '', tenantId: string | undefined, scopeTenantIds: string[], scopePlaceIds: string[], signal?: AbortSignal) => {
    const kindQuery = kind ? `&kind=${encodeURIComponent(kind)}` : ''
    const path = tenantId
      ? `/management/retention/runs?page=${page}&size=${size}${kindQuery}`
      : scopeQuery(`/management/retention/runs?page=${page}&size=${size}${kindQuery}`, scopeTenantIds, scopePlaceIds)
    return request<RetentionRunPage>(path, { ...retentionRead, tenantId, signal, decode: parseRunPage })
  },
}
