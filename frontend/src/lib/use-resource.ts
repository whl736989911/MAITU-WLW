import { useCallback, useEffect, useRef, useState } from 'react'
import { useSession } from './store'

export function useResource<T>(key: string, load: (signal: AbortSignal) => Promise<T>, enabled = true) {
  const generation = useSession(s => s.generation)
  const [revision, setRevision] = useState(0)
  const identity = `${generation}:${key}:${enabled}`
  const scope = `${identity}:${revision}`
  const loader = useRef(load); loader.current = load
  const [state, setState] = useState<{ identity: string; scope: string; data?: T; error?: unknown; loading: boolean; loadedAt?: Date }>({ identity, scope, loading: enabled })
  useEffect(() => {
    const controller = new AbortController()
    if (!enabled) { setState({ identity, scope, loading: false }); return () => controller.abort() }
    setState(previous => ({ identity, scope, loading: true, ...(previous.identity === identity ? { data: previous.data, loadedAt: previous.loadedAt } : {}) }))
    loader.current(controller.signal).then(data => {
      if (!controller.signal.aborted) setState({ identity, scope, data, loading: false, loadedAt: new Date() })
    }, error => { if (!controller.signal.aborted) setState({ identity, scope, error, loading: false }) })
    return () => controller.abort()
  }, [scope, enabled])
  const visible: typeof state = state.identity === identity ? { ...state, error: state.scope === scope ? state.error : undefined, loading: enabled && (state.scope !== scope || state.loading) } : { identity, scope, loading: enabled }
  const refresh = useCallback(() => setRevision(value => value + 1), [])
  return { ...visible, refresh }
}
