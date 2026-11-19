import { useSession } from './store'
import { parseJsonDocument, jsonFieldText } from './json-document'
import { integerToken } from './parameter-json'
import { alarmChangeTenant, readChangeCoordinate, type ChangeCoordinate } from './alarm-changes'
export const channelSemantics = { kind: 'INVALIDATION_ONLY', publisher_liveness: 'UNCONFIRMED', delivery: 'PER_CONNECTION_SAMPLE' } as const
export interface ChannelBootstrap { v: 1; scope: { tenant_id: string; audience: string; session: string }; ticket: string; connect_timeout_ms: number; silence_timeout_ms: number }
export interface ChannelOwner { token: string | null; generation: number; audience: string; tenant: string | null | undefined; permissions: string; user: string | undefined }
export function channelOwner(): ChannelOwner { const s = useSession.getState(); return { token: s.token, generation: s.generation, audience: s.audience, tenant: alarmChangeTenant(), permissions: s.permissions.join('|'), user: s.user?.id } }
export function sameChannelOwner(owner: ChannelOwner) { const now = channelOwner(); return Object.keys(owner).every(k => owner[k as keyof ChannelOwner] === now[k as keyof ChannelOwner]) }
function invalid(): never { throw new Error('变更信号协议未确认；不推进人工检查位置') }
function fields(value: unknown, keys: string[]): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value, k))) return invalid(); return value as Record<string, unknown> }
function version(raw: string, value: Record<string, unknown>) { if (value.v !== 1 || jsonFieldText(raw, 'v') !== '1') invalid() }
export function readChannelBootstrap(raw: string, anchor: ChangeCoordinate): ChannelBootstrap {
  const data = fields(parseJsonDocument(raw), ['v', 'scope', 'ticket', 'connect_timeout_ms', 'silence_timeout_ms']); version(raw, data)
  const scope = fields(data.scope, ['tenant_id', 'audience', 'session'])
  if (scope.tenant_id !== anchor.tenant_id || scope.audience !== anchor.audience || scope.session !== anchor.session || typeof data.ticket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(data.ticket)) return invalid()
  const connect = jsonFieldText(raw, 'connect_timeout_ms'), silence = jsonFieldText(raw, 'silence_timeout_ms'); if (connect === undefined || silence === undefined) return invalid()
  const connectMs = integerToken(connect), silenceMs = integerToken(silence)
  if (connectMs < 1000 || connectMs > 60000 || silenceMs < 1000 || silenceMs > 180000) return invalid()
  return { v: 1, scope: scope as unknown as ChannelBootstrap['scope'], ticket: data.ticket, connect_timeout_ms: connectMs, silence_timeout_ms: silenceMs }
}
export function readChannelFrame(raw: unknown, anchor: ChangeCoordinate): ChangeCoordinate {
  if (typeof raw !== 'string' || new TextEncoder().encode(raw).length > 65536) return invalid()
  const data = fields(parseJsonDocument(raw), ['v', 'type', 'head_cursor', 'semantics']); version(raw, data)
  const semantics = fields(data.semantics, Object.keys(channelSemantics))
  if (data.type !== 'ALARM_CHANGE_SIGNAL' || Object.entries(channelSemantics).some(([k, v]) => semantics[k] !== v)) return invalid()
  const head = readChangeCoordinate(data.head_cursor)
  if (head.tenant_id !== anchor.tenant_id || head.audience !== anchor.audience || head.session !== anchor.session) return invalid()
  return head
}
