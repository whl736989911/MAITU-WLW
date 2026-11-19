import { create } from 'zustand'
import { ApiError, request } from './api'
import { useSession } from './store'

export type MutationPhase = 'EDITING' | 'CONFIRMING' | 'PENDING' | 'UNCERTAIN' | 'CONFLICT'

/** Frozen request. It carries no credential; authorization is the current session only. */
export interface MutationRequest {
  path: string
  method: 'POST' | 'PUT' | 'DELETE'
  tenant?: string
  body: Record<string, unknown>
  display: string
}

export interface MutationIntent<T> {
  generation: number
  intent: MutationRequest | null
  key: string | null
  phase: MutationPhase
  error: unknown
  completion: { serial: number; result: T; path: string } | null
  perform: (request: MutationRequest) => Promise<void>
  retry: () => Promise<void>
  edit: () => boolean
  reload: () => boolean
  dismissCompletion: () => void
}

function stable(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`
  if (value && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>).sort(([left], [right]) => left.localeCompare(right))
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${stable(item)}`).join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}
function identity(request: MutationRequest) {
  return stable([request.path, request.method, request.tenant ?? '', request.body])
}

/**
 * Canonical in-memory mutation intent store: SPA navigation retains a frozen key/intent,
 * Only authentication/permission transitions abort and clear it; changing an operation unit retains its fixed target.
 * Nothing persists across reload; every call is authorized by the live session.
 */
export function createMutationIntent<T>(decode: (raw: string) => T) {
  let controller: AbortController | null = null
  let serial = 0
  const empty = () => ({
    generation: useSession.getState().authGeneration,
    intent: null,
    key: null,
    phase: 'EDITING' as const,
    error: undefined,
    completion: null,
  })
  const store = create<MutationIntent<T>>((set, get) => {
    async function execute(intent: MutationRequest, key: string, wasUncertain: boolean) {
      const session = useSession.getState(), generation = get().generation
      if (get().phase === 'PENDING' || session.authGeneration !== generation) return
      const pending = new AbortController()
      controller = pending
      set({ phase: 'PENDING', error: undefined })
      try {
        const result = await request<T>(`/${session.audience}${intent.path}`, {
          method: intent.method, tenantScoped: true, tenantId: intent.tenant,
          headers: { 'Idempotency-Key': key }, signal: pending.signal,
          body: JSON.stringify(intent.body), decode,
        })
        if (get().generation !== generation || useSession.getState().authGeneration !== generation || get().key !== key) return
        set({ intent: null, key: null, phase: 'EDITING', error: undefined, completion: { serial: ++serial, result, path: intent.path } })
      } catch (error) {
        if (get().generation !== generation || useSession.getState().authGeneration !== generation || get().key !== key) return
        const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.code !== 'INVALID_RESPONSE'
        set({ error, phase: wasUncertain || !rejected ? 'UNCERTAIN' : error instanceof ApiError && error.status === 409 ? 'CONFLICT' : 'CONFIRMING' })
      } finally {
        if (controller === pending) controller = null
      }
    }
    return {
      ...empty(),
      perform: async next => {
        const state = get(), session = useSession.getState()
        if (state.phase === 'PENDING' || state.generation !== session.authGeneration) return
        if (state.phase === 'CONFLICT') {
          set({ error: new Error('冲突后必须先刷新读取当前事实并放弃旧意图，不能直接重试。') })
          return
        }
        if (state.phase === 'UNCERTAIN') {
          if (!state.intent || !state.key || identity(state.intent) !== identity(next)) {
            set({ error: new Error('结果未确认，冻结意图不可修改；请沿原操作确认结果或先刷新核对。') })
            return
          }
          await execute(state.intent, state.key, true)
          return
        }
        const key = crypto.randomUUID()
        set({ intent: next, key, error: undefined, completion: null })
        await execute(next, key, false)
      },
      retry: async () => {
        const state = get()
        if (!state.intent || !state.key || state.phase === 'PENDING' || state.phase === 'CONFLICT') return
        await execute(state.intent, state.key, state.phase === 'UNCERTAIN')
      },
      edit: () => {
        const phase = get().phase
        if (phase !== 'EDITING' && phase !== 'CONFIRMING') return false
        set({ intent: null, key: null, phase: 'EDITING', error: undefined })
        return true
      },
      reload: () => {
        if (get().phase !== 'CONFLICT') return false
        set({ intent: null, key: null, phase: 'EDITING', error: undefined })
        return true
      },
      dismissCompletion: () => set({ completion: null }),
    }
  })
  useSession.subscribe((current, previous) => {
    if (current.authGeneration === previous.authGeneration) return
    controller?.abort()
    controller = null
    store.setState(empty())
  })
  return store
}
