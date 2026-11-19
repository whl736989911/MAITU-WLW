import { useEffect, useRef, useState } from 'react'
import { ApiError } from './api'

export interface DomainWrite {
  reason: string
  setReason: (value: string) => void
  error: unknown
  setError: (value: unknown) => void
  saving: boolean
  submit: (identity: unknown, action: (key: string) => Promise<unknown>, after?: () => void) => void
  reset: () => void
}

/**
 * In-memory domain write helper: freezes one idempotency key per request identity and requires an explicit
 * reason. It never persists payloads or credentials: the live session authorizes each attempt. A differing
 * request identity after an uncertain failure is refused; the caller must refresh and re-confirm.
 */
export function useDomainWrite(): DomainWrite {
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>()
  const [saving, setSaving] = useState(false)
  const pending = useRef(false)
  const frozen = useRef<{ fingerprint: string; key: string } | null>(null)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])
  function reset() {
    frozen.current = null
    setError(undefined)
  }
  function submit(identity: unknown, action: (key: string) => Promise<unknown>, after?: () => void) {
    if (pending.current) return
    if (!reason.trim()) {
      setError(new Error('请输入操作原因'))
      return
    }
    const fingerprint = JSON.stringify(identity)
    if (frozen.current && frozen.current.fingerprint !== fingerprint) {
      setError(new Error('上次写入结果尚未确认，请先核对服务端当前事实；不能用新内容替换原请求'))
      return
    }
    if (!frozen.current) frozen.current = { fingerprint, key: crypto.randomUUID() }
    pending.current = true
    setSaving(true)
    setError(undefined)
    const operation = action(frozen.current.key)
    void operation
      .then(() => {
        if (!alive.current) return
        frozen.current = null
        after?.()
      })
      .catch(failure => {
        if (!alive.current) return
        if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && failure.code !== 'INVALID_RESPONSE')
          frozen.current = null
        setError(failure)
      })
      .finally(() => {
        pending.current = false
        if (alive.current) {
          setSaving(false)
        }
      })
  }
  return { reason, setReason, error, setError, saving, submit, reset }
}
