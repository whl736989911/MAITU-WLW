import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ShieldAlert } from 'lucide-react'
import type { MutationIntent, MutationRequest } from '@/lib/mutation-intent'
import { Button } from './ui/button'
import { ErrorNotice } from './common'

type Store<T> = () => MutationIntent<T>

/**
 * Explicit customer write gate. The canonical intent/key live in the module-level mutation store, so
 * SPA navigation does not discard an unconfirmed write. The request carries no credential: the live
 * session authorizes it. A 409 requires reloading current facts, not blind retry.
 */
export function CustomerWriteGate<T>({ store, title, warning, submitLabel, summary, buildRequest, onSaved, disabled, children }: {
  store: Store<T>
  title: string
  warning: string
  submitLabel: string
  summary: ReactNode
  buildRequest: () => MutationRequest
  onSaved: (result: T) => void
  disabled?: boolean
  children: ReactNode
}) {
  const state = store()
  const [error, setError] = useState<unknown>()
  const handled = useRef<number | null>(null)
  useEffect(() => {
    if (!state.completion || handled.current === state.completion.serial) return
    handled.current = state.completion.serial
    onSaved(state.completion.result)
    state.dismissCompletion()
  }, [state, state.completion, onSaved])
  const pending = state.phase === 'PENDING'
  const uncertain = state.phase === 'UNCERTAIN'
  const conflict = state.phase === 'CONFLICT'
  if (pending || uncertain || conflict) return <section className="sensitive-confirmation" aria-live="polite">
    <h3><ShieldAlert size={18}/>{pending ? '写入处理中' : conflict ? '数据已变化，原操作未提交' : '结果未确认，保留原请求'}</h3>
    <p>{state.intent?.display ?? '原写入请求'}</p>
    <p className="notice">{pending ? '正在等待服务端结果；不要重复新建操作。' : conflict ? '服务端事实已变化。请关闭本操作、刷新当前数据后重新核对；不会自动覆盖或重试。' : '上一次请求可能已到达服务端。请沿原幂等键和原内容确认结果；不得改用其他内容。'}</p>
    {conflict ? <Button type="button" variant="outline" onClick={() => state.reload()}>放弃旧意图并重新读取</Button> : <>
      <Button type="button" disabled={pending} onClick={() => { setError(undefined); void state.retry().catch(setError) }}>{pending ? '等待结果…' : '用原请求确认结果'}</Button>
      {state.error != null && <ErrorNotice error={state.error}/>}
      {error != null && <ErrorNotice error={error}/>}
    </>}
  </section>
  function submit() {
    try {
      const request = buildRequest()
      setError(undefined)
      void state.perform(request).catch(setError)
    } catch (failure) { setError(failure) }
  }
  return <form className="form-stack" onSubmit={event => { event.preventDefault(); submit() }}>
    <h3><ShieldAlert size={18}/>{title}</h3>
    {children}
    <div className="confirmation-summary">{summary}</div>
    <div className="notice">{warning}</div>
    {error != null && <ErrorNotice error={error}/>}
    {state.error != null && <ErrorNotice error={state.error}/>}
    <div className="identity-form-actions"><Button type="submit" disabled={disabled}>{submitLabel}</Button></div>
  </form>
}
