import { useSyncExternalStore } from 'react'
import { useSession } from './store'

/**
 * In-memory write-intent store for SPA navigation. It never persists passwords or payloads to
 * localStorage/sessionStorage; entries are scoped to the current actor and auth generation and are
 * dropped on logout. A retry always reuses the original key and payload digest.
 */
export type WriteIntentState = 'PENDING' | 'UNKNOWN'
export interface WriteIntent<T = unknown> {
  scope: string
  key: string
  digest: string
  payload: T
  label: string
  state: WriteIntentState
  actorId: string
  generation: number
}

const intents = new Map<string, WriteIntent>()
const listeners = new Set<() => void>()
let version = 0
const emit = () => { version += 1; for (const listener of listeners) listener() }

// Permission/session transitions discard old payloads; operation-unit changes do not rotate authGeneration.
useSession.subscribe((state, previous) => { if ((!state.user || state.authGeneration !== previous.authGeneration) && intents.size > 0) { intents.clear(); emit() } })

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  const record = value as Record<string, unknown>
  return `{${Object.keys(record).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(record[key])}`).join(',')}}`
}

function scopeKey(scope: string): string | null {
  const session = useSession.getState()
  if (!session.user) { intents.clear(); return null }
  return `${session.authGeneration}:${session.user.id}:${scope}`
}

export function getWriteIntent<T = unknown>(scope: string): WriteIntent<T> | null {
  const key = scopeKey(scope)
  if (!key) return null
  const intent = intents.get(key)
  if (!intent) return null
  const session = useSession.getState()
  if (intent.generation !== session.authGeneration || intent.actorId !== session.user?.id) {
    intents.delete(key)
    return null
  }
  return intent as WriteIntent<T>
}

export function beginWriteIntent<T>(scope: string, payload: T, label: string, key?: string): WriteIntent<T> {
  const scoped = scopeKey(scope)
  if (!scoped) throw new Error('登录会话已结束，不能开始写入意图')
  const digest = canonicalJson(payload)
  const existing = intents.get(scoped) as WriteIntent<T> | undefined
  if (existing) {
    if (existing.digest !== digest) throw new Error('已有未确认的写入意图；请恢复原请求或先核对服务端状态，不能以不同内容新建第二条命令')
    return existing
  }
  const session = useSession.getState()
  const intent: WriteIntent<T> = { scope: scoped, key: key ?? crypto.randomUUID(), digest, payload, label, state: 'PENDING', actorId: session.user!.id, generation: session.authGeneration }
  intents.set(scoped, intent as WriteIntent)
  emit()
  return intent
}

export function markWriteIntentUnknown(scope: string, key: string): void {
  const scoped = scopeKey(scope)
  if (!scoped) return
  const intent = intents.get(scoped)
  if (intent && intent.key === key) { intent.state = 'UNKNOWN'; emit() }
}

export function clearWriteIntent(scope: string, key?: string): void {
  const scoped = scopeKey(scope)
  if (!scoped) return
  const intent = intents.get(scoped)
  if (!intent) return
  if (key && intent.key !== key) return
  intents.delete(scoped); emit()
}

export function subscribeWriteIntent(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function useWriteIntent<T = unknown>(scope: string): WriteIntent<T> | null {
  useSyncExternalStore(subscribeWriteIntent, () => version, () => version)
  return getWriteIntent<T>(scope)
}
