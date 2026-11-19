import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { CheckCheck, Search, ShieldAlert } from 'lucide-react'
import { ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { ErrorNotice } from './common'

// The key never fingerprints/retains business input. Changing input rotates it and closes the
// confirmation step. Authorization comes solely from the current session; nothing is cached.
export interface MutationAction {
  confirming: boolean
  setConfirming: (value: boolean) => void
  busy: boolean
  error: unknown
  setError: (error: unknown) => void
  touch: () => void
  run: (action: (key: string, signal: AbortSignal) => Promise<unknown>, success: () => void) => Promise<void>
  conflict: boolean
}
export function useMutationAction(): MutationAction {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const key = useRef(crypto.randomUUID())
  const pending = useRef<AbortController | null>(null)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false; pending.current?.abort() } }, [])
  function touch() { key.current = crypto.randomUUID(); setConfirming(false); setError(undefined) }
  async function run(action: (key: string, signal: AbortSignal) => Promise<unknown>, success: () => void) {
    if (pending.current) return
    const scope = useSession.getState().generation
    const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(undefined)
    try {
      await action(key.current, controller.signal)
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === scope) success()
    } catch (error) {
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === scope) setError(error)
    } finally {
      pending.current = null
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === scope) setBusy(false)
    }
  }
  return { confirming, setConfirming, busy, error, setError, touch, run, conflict: error instanceof ApiError && error.status === 409 }
}
export function OperationConfirmation({ action, title, children, submitLabel, refresh, warning }: { action: MutationAction; title: string; children: ReactNode; submitLabel: string; refresh: () => void; warning: string }) {
  return <section className="sensitive-confirmation"><h3><ShieldAlert size={18}/>{title}</h3><div className="confirmation-summary">{children}</div><div className="notice">这是二次确认。{warning} 请核对目标与变更。</div>{action.error != null && <ErrorNotice error={action.error}/>} {action.conflict && <div className="notice">数据版本或幂等内容已冲突。请刷新列表，重新打开表单核对后操作。<Button type="button" variant="outline" onClick={refresh}>刷新后重新打开</Button></div>}<div className="identity-form-actions"><Button type="button" variant="outline" disabled={action.busy || action.conflict} onClick={() => action.setConfirming(false)}>返回修改</Button><Button type="submit" disabled={action.busy || action.conflict}><CheckCheck/>{action.busy ? '正在提交…' : submitLabel}</Button></div></section>
}
export function IdentitySearch({ onSearch, children, placeholder = '按名称或账号查询（字面匹配）', 'aria-label': ariaLabel = '搜索名称或账号' }: { onSearch: (keyword: string) => void; children?: ReactNode; placeholder?: string; 'aria-label'?: string }) {
  const [draft, setDraft] = useState('')
  return <div className="list-toolbar"><form className="search-form" onSubmit={(e: FormEvent) => { e.preventDefault(); onSearch(draft.trim()) }}><div className="input-icon"><Search size={16}/><Input aria-label={ariaLabel} value={draft} onChange={e => setDraft(e.target.value)} placeholder={placeholder}/></div><Button type="submit" variant="outline">查询</Button></form>{children}</div>
}
