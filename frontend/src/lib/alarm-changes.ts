import { ApiError, request } from './api'
import { useSession } from './store'
import { parseJsonDocument, jsonFieldText } from './json-document'
import { integerToken } from './parameter-json'
import { canonicalAlarmInstant } from './alarm-workbench'
import type { Audience } from './types'
export interface ChangeCoordinate { v: 1; tenant_id: string; audience: Audience; session: string; stream_id: string | null; sequence: string }
export interface ChangeQuery { cursor?: string; through?: string; size?: number }
export const changeSemantics = { kind: 'INVALIDATION_ONLY', order: 'PER_TENANT_PUBLICATION', publisher_liveness: 'UNCONFIRMED', baseline: 'HEAD_BEFORE_SEPARATE_WORKBENCH_READ', historical_coverage: 'APPLICATION_TRACES_FROM_V11' } as const
export interface AlarmChanges {
  mode: 'BASELINE' | 'CHANGES'; as_of: string; timezone: 'Asia/Shanghai'; scope: { tenant_id: string; audience: Audience; stream_id: string | null }; size: number
  items: { delivery_id: string; sequence: string; type: 'ALARM_CHANGED' }[]; next_cursor: string; through_cursor: string; has_more: boolean; pending_count: string; semantics: Omit<typeof changeSemantics, 'order'> & { order: 'PER_TENANT_PUBLICATION' | 'PER_ACTOR_VISIBLE_PUBLICATION_RANK' }
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
function bad(): never { throw new ApiError(200, 'ALARM_CHANGES_UNCONFIRMED', '报警变更检查响应未确认；不推进位置、不推定无变化或已同步') }
function record(v: unknown, keys: readonly string[]): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).length !== keys.length || keys.some(k => !Object.hasOwn(v, k))) return bad(); return v as Record<string, unknown> }
function decimal(v: unknown): string { if (typeof v !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(v) || BigInt(v) > 9223372036854775807n) return bad(); return v }
function encoded(raw: string) { return btoa(String.fromCharCode(...new TextEncoder().encode(raw))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') }
export function readChangeCoordinate(cursor: unknown): ChangeCoordinate {
  if (typeof cursor !== 'string' || cursor.length < 1 || cursor.length > 2048 || !/^[A-Za-z0-9_-]+$/.test(cursor)) return bad()
  const base = cursor.replace(/-/g, '+').replace(/_/g, '/'), raw = new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(atob(base + '='.repeat((4 - base.length % 4) % 4)), c => c.charCodeAt(0)))
  const value = record(parseJsonDocument(raw), ['v', 'tenant_id', 'audience', 'session', 'stream_id', 'sequence'])
  if (value.v !== 1 || jsonFieldText(raw, 'v') !== '1' || typeof value.tenant_id !== 'string' || !value.tenant_id || !['management', 'customer'].includes(value.audience as string) || typeof value.session !== 'string' || !/^[0-9a-f]{64}$/.test(value.session) || (value.stream_id !== null && (typeof value.stream_id !== 'string' || !uuid.test(value.stream_id)))) return bad()
  const result: ChangeCoordinate = { v: 1, tenant_id: value.tenant_id, audience: value.audience as Audience, session: value.session, stream_id: value.stream_id as string | null, sequence: decimal(value.sequence) }
  if ((result.stream_id === null && result.sequence !== '0') || encoded(JSON.stringify(result)) !== cursor) return bad()
  return result
}
export function readAlarmChanges(raw: string, query: ChangeQuery, tenantId: string, audience: Audience): AlarmChanges {
  const data = record(parseJsonDocument(raw), ['mode', 'as_of', 'timezone', 'scope', 'size', 'items', 'next_cursor', 'through_cursor', 'has_more', 'pending_count', 'semantics']) as unknown as AlarmChanges
  const scope = record(data.scope, ['tenant_id', 'audience', 'stream_id']), semantics = record(data.semantics, Object.keys(changeSemantics))
  if (scope.tenant_id !== tenantId || scope.audience !== audience || (scope.stream_id !== null && (typeof scope.stream_id !== 'string' || !uuid.test(scope.stream_id))) || typeof data.as_of !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(data.as_of) || data.timezone !== 'Asia/Shanghai' || typeof data.has_more !== 'boolean' || !Array.isArray(data.items)) return bad()
  canonicalAlarmInstant(data.as_of); data.pending_count = decimal(data.pending_count)
  const sizeRaw = jsonFieldText(raw, 'size'); if (sizeRaw === undefined) return bad(); data.size = integerToken(sizeRaw)
  if (data.size < 1 || data.size > 100 || (query.size !== undefined && data.size !== query.size) || Object.entries(changeSemantics).some(([k, v]) => k === 'order' ? semantics[k] !== v && semantics[k] !== 'PER_ACTOR_VISIBLE_PUBLICATION_RANK' : semantics[k] !== v)) return bad()
  const next = readChangeCoordinate(data.next_cursor), through = readChangeCoordinate(data.through_cursor), after = query.cursor === undefined ? undefined : readChangeCoordinate(query.cursor), target = query.through === undefined ? undefined : readChangeCoordinate(query.through)
  const responseCoordinates = [next, through], allCoordinates = [...responseCoordinates, ...(after ? [after] : []), ...(target ? [target] : [])]
  if (allCoordinates.some(c => c.tenant_id !== tenantId || c.audience !== audience || c.session !== next.session) || responseCoordinates.some(c => c.stream_id !== scope.stream_id) || [after, target].some(c => c && c.stream_id !== scope.stream_id && !(c.stream_id === null && c.sequence === '0' && scope.stream_id !== null))) return bad()
  if (target && (!after || through.sequence !== target.sequence)) return bad()
  if (query.cursor === undefined) { if (query.through !== undefined || data.mode !== 'BASELINE' || data.items.length !== 0 || data.has_more || data.next_cursor !== data.through_cursor) return bad(); return data }
  if (!after || data.mode !== 'CHANGES') return bad()
  const start = BigInt(after.sequence), end = BigInt(through.sequence), position = BigInt(next.sequence)
  if (end < start || position < start || position > end) return bad()
  const remaining = end - start, expectedLength = remaining < BigInt(data.size) ? Number(remaining) : data.size
  if (data.items.length !== expectedLength || position !== start + BigInt(expectedLength) || data.has_more !== (position < end)) return bad()
  const ids = new Set<string>(); data.items.forEach((item, i) => { const v = record(item, ['delivery_id', 'sequence', 'type']); if (typeof v.delivery_id !== 'string' || !uuid.test(v.delivery_id) || ids.has(v.delivery_id) || v.type !== 'ALARM_CHANGED' || BigInt(decimal(v.sequence)) !== start + BigInt(i) + 1n) return bad(); ids.add(v.delivery_id) })
  return data
}
export function alarmChangeTenant() {
  const session = useSession.getState()
  if (session.audience !== 'management') return session.user?.tenant_id ?? undefined
  return session.scopeInitialized && session.scopeTenantIds.length === 1 && session.scopePlaceIds.length === 0 ? session.scopeTenantIds[0] : undefined
}
export async function getAlarmChanges(query: ChangeQuery = {}, signal?: AbortSignal): Promise<AlarmChanges> {
  const session = useSession.getState(), tenantId = alarmChangeTenant()
  if (!tenantId || query.through !== undefined && query.cursor === undefined || query.size !== undefined && (!Number.isSafeInteger(query.size) || query.size < 1 || query.size > 100)) throw new ApiError(400, 'ALARM_CHANGES_QUERY_INVALID', '报警变更范围或分页未确认')
  for (const cursor of [query.cursor, query.through]) if (cursor !== undefined) { const c = readChangeCoordinate(cursor); if (c.tenant_id !== tenantId || c.audience !== session.audience) throw new ApiError(400, 'CURSOR_SCOPE_MISMATCH', '游标不能跨单位或受众使用') }
  const params = new URLSearchParams(); for (const key of ['cursor', 'through', 'size'] as const) { const value = query[key]; if (value !== undefined) params.set(key, String(value)) }
  try { const value = await request<AlarmChanges>(`/${session.audience}/alarm-changes${params.size ? `?${params}` : ''}`, { tenantScoped: true, tenantId, signal, decode: raw => readAlarmChanges(raw, query, tenantId, session.audience) }); if (!value) return bad(); return value }
  catch (error) { if (error instanceof ApiError && error.code === 'INVALID_RESPONSE') return bad(); throw error }
}
