import { request } from './api'

export type HealthState = 'READY' | 'CONFIGURED' | 'DISABLED' | 'UNKNOWN' | 'DEGRADED'
export interface HealthCheck { name: string; state: HealthState; detail: string }
export interface Deployment {
  release_id: string | null
  artifact_sha256: string | null
  source_commit: string | null
  built_at: string | null
  identity_status: 'PROVIDED' | 'INCOMPLETE' | 'INVALID' | 'NOT_PROVIDED'
  storage: string
  profiles: string[]
  roles: string[]
  started_at: string
  now: string
  uptime_seconds: number
}
export interface SchemaSummary {
  available: boolean
  current_version: string | null
  applied: number
  failed: number
  validation: 'APPLIED' | 'FAILED' | 'UNKNOWN'
  pending_check: 'NOT_CHECKED'
}
export interface ReleaseEvidence { release_id: string; artifact_sha256: string; source_commit: string | null; built_at: string }
export interface BackupEvidence {
  status: 'VERIFIED' | 'NOT_VERIFIED'
  reason: string | null
  completed_at: string | null
  database_engine: string | null
  file_name: string | null
  file_sha256: string | null
  file_bytes: number | null
  encrypted: boolean | null
  offline: boolean | null
}
export interface RestoreEvidence {
  status: 'VERIFIED' | 'NOT_VERIFIED'
  reason: string | null
  validated_at: string | null
  isolated_target: boolean | null
  backup_sha256: string | null
  restored_schema_version: string | null
  schema_version_matches: boolean | null
}
export interface MigrationEvidence {
  status: 'VERIFIED' | 'NOT_VERIFIED'
  reason: string | null
  validated_at: string | null
  source_schema_version: string | null
  target_schema_version: string | null
  isolated_copy: boolean | null
  checksum_validation: string | null
}
export interface OpsManifest {
  schema_version: number
  generated_at: string
  producer: string
  environment: string
  release: ReleaseEvidence
  backup: BackupEvidence
  restore: RestoreEvidence
  migration: MigrationEvidence
}
export interface OpsEvidence {
  configured: boolean
  available: boolean
  reason: string | null
  file_name: string | null
  sha256: string | null
  bytes: number | null
  modified_at: string | null
  manifest: OpsManifest | null
}
export interface PlatformOperationsOverview {
  generated_at: string
  deployment: Deployment
  schema: SchemaSummary
  health: HealthCheck[]
  evidence: OpsEvidence
}
export interface FlywayMigration {
  rank: number
  version: string | null
  description: string | null
  type: string | null
  script: string | null
  checksum: number | null
  installed_by: string | null
  installed_on: string | null
  execution_time: number | null
  success: boolean
}
export interface SchemaHistory {
  available: boolean
  reason: string | null
  items: FlywayMigration[]
  total: number
  page: number
  size: number
}

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
function nullableBool(value: unknown, label: string): boolean | null {
  if (value === null) return null
  return bool(value, label)
}
function strings(value: unknown, label: string): string[] {
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) throw new Error(`${label}响应格式无效`)
  return value as string[]
}
function healthState(value: unknown): HealthState {
  if (!['READY', 'CONFIGURED', 'DISABLED', 'UNKNOWN', 'DEGRADED'].includes(String(value))) throw new Error('健康状态响应未确认')
  return value as HealthState
}
function evidenceStatus(value: unknown): 'VERIFIED' | 'NOT_VERIFIED' {
  if (value !== 'VERIFIED' && value !== 'NOT_VERIFIED') throw new Error('运维证据状态响应未确认')
  return value
}

function manifest(value: unknown): OpsManifest | null {
  if (value === null) return null
  const m = obj(value, '运维证据清单')
  const release = obj(m.release, 'release')
  const backup = obj(m.backup, 'backup')
  const restore = obj(m.restore, 'restore')
  const migration = obj(m.migration, 'migration')
  return {
    schema_version: num(m.schema_version, 'schema_version'),
    generated_at: text(m.generated_at, 'generated_at'),
    producer: text(m.producer, 'producer'),
    environment: text(m.environment, 'environment'),
    release: {
      release_id: text(release.release_id, 'release.release_id'), artifact_sha256: text(release.artifact_sha256, 'release.artifact_sha256'),
      source_commit: nullableText(release.source_commit, 'release.source_commit'), built_at: text(release.built_at, 'release.built_at'),
    },
    backup: {
      status: evidenceStatus(backup.status), reason: nullableText(backup.reason, 'backup.reason'),
      completed_at: nullableText(backup.completed_at, 'backup.completed_at'), database_engine: nullableText(backup.database_engine, 'backup.database_engine'),
      file_name: nullableText(backup.file_name, 'backup.file_name'), file_sha256: nullableText(backup.file_sha256, 'backup.file_sha256'),
      file_bytes: nullableNum(backup.file_bytes, 'backup.file_bytes'), encrypted: nullableBool(backup.encrypted, 'backup.encrypted'),
      offline: nullableBool(backup.offline, 'backup.offline'),
    },
    restore: {
      status: evidenceStatus(restore.status), reason: nullableText(restore.reason, 'restore.reason'),
      validated_at: nullableText(restore.validated_at, 'restore.validated_at'), isolated_target: nullableBool(restore.isolated_target, 'restore.isolated_target'),
      backup_sha256: nullableText(restore.backup_sha256, 'restore.backup_sha256'), restored_schema_version: nullableText(restore.restored_schema_version, 'restore.restored_schema_version'),
      schema_version_matches: nullableBool(restore.schema_version_matches, 'restore.schema_version_matches'),
    },
    migration: {
      status: evidenceStatus(migration.status), reason: nullableText(migration.reason, 'migration.reason'),
      validated_at: nullableText(migration.validated_at, 'migration.validated_at'), source_schema_version: nullableText(migration.source_schema_version, 'migration.source_schema_version'),
      target_schema_version: nullableText(migration.target_schema_version, 'migration.target_schema_version'),
      isolated_copy: nullableBool(migration.isolated_copy, 'migration.isolated_copy'), checksum_validation: nullableText(migration.checksum_validation, 'migration.checksum_validation'),
    },
  }
}

function parseOverview(raw: string): PlatformOperationsOverview {
  const value = obj(JSON.parse(raw), '平台运维')
  const deployment = obj(value.deployment, 'deployment')
  const schema = obj(value.schema, 'schema')
  const evidence = obj(value.evidence, 'evidence')
  if (!Array.isArray(value.health)) throw new Error('健康检查响应格式无效')
  return {
    generated_at: text(value.generated_at, 'generated_at'),
    deployment: {
      release_id: nullableText(deployment.release_id, 'release_id'), artifact_sha256: nullableText(deployment.artifact_sha256, 'artifact_sha256'),
      source_commit: nullableText(deployment.source_commit, 'source_commit'), built_at: nullableText(deployment.built_at, 'built_at'),
      identity_status: (() => { const status = text(deployment.identity_status, 'identity_status'); if (!['PROVIDED', 'INCOMPLETE', 'INVALID', 'NOT_PROVIDED'].includes(status)) throw new Error('发布身份状态响应未确认'); return status as Deployment['identity_status'] })(),
      storage: text(deployment.storage, 'storage'), profiles: strings(deployment.profiles, 'profiles'), roles: strings(deployment.roles, 'roles'),
      started_at: text(deployment.started_at, 'started_at'), now: text(deployment.now, 'now'), uptime_seconds: num(deployment.uptime_seconds, 'uptime_seconds'),
    },
    schema: {
      available: bool(schema.available, 'schema.available'), current_version: nullableText(schema.current_version, 'schema.current_version'),
      applied: num(schema.applied, 'schema.applied'), failed: num(schema.failed, 'schema.failed'),
      validation: (() => { const status = text(schema.validation, 'schema.validation'); if (!['APPLIED', 'FAILED', 'UNKNOWN'].includes(status)) throw new Error('schema校验状态响应未确认'); return status as SchemaSummary['validation'] })(),
      pending_check: (() => { const status = text(schema.pending_check, 'schema.pending_check'); if (status !== 'NOT_CHECKED') throw new Error('schema待执行检查响应未确认'); return 'NOT_CHECKED' as const })(),
    },
    health: value.health.map(item => {
      const row = obj(item, 'health')
      return { name: text(row.name, 'health.name'), state: healthState(row.state), detail: text(row.detail, 'health.detail') }
    }),
    evidence: {
      configured: bool(evidence.configured, 'evidence.configured'), available: bool(evidence.available, 'evidence.available'),
      reason: nullableText(evidence.reason, 'evidence.reason'), file_name: nullableText(evidence.file_name, 'evidence.file_name'),
      sha256: nullableText(evidence.sha256, 'evidence.sha256'), bytes: nullableNum(evidence.bytes, 'evidence.bytes'),
      modified_at: nullableText(evidence.modified_at, 'evidence.modified_at'), manifest: manifest(evidence.manifest),
    },
  }
}

function parseSchema(raw: string): SchemaHistory {
  const value = obj(JSON.parse(raw), 'schema历史')
  if (!Array.isArray(value.items)) throw new Error('schema历史响应格式无效')
  return {
    available: bool(value.available, 'available'), reason: nullableText(value.reason, 'reason'),
    total: num(value.total, 'total'), page: num(value.page, 'page'), size: num(value.size, 'size'),
    items: value.items.map(item => {
      const row = obj(item, 'migration')
      return {
        rank: num(row.rank, 'rank'), version: nullableText(row.version, 'version'), description: nullableText(row.description, 'description'),
        type: nullableText(row.type, 'type'), script: nullableText(row.script, 'script'), checksum: nullableNum(row.checksum, 'checksum'),
        installed_by: nullableText(row.installed_by, 'installed_by'), installed_on: nullableText(row.installed_on, 'installed_on'),
        execution_time: nullableNum(row.execution_time, 'execution_time'), success: bool(row.success, 'success'),
      }
    }),
  }
}

export const platformOperationsApi = {
  overview: (signal?: AbortSignal) => request<PlatformOperationsOverview>('/management/platform-operations', { signal, decode: parseOverview }),
  schema: (page: number, size: number, signal?: AbortSignal) => request<SchemaHistory>(`/management/platform-operations/schema?page=${page}&size=${size}`, { signal, decode: parseSchema }),
}
