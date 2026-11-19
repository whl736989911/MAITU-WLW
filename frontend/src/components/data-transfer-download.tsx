import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { ErrorNotice } from '@/components/common'
import { saveDataTransferFile, type DataTransferFile } from '@/lib/data-transfer'
import { useSession } from '@/lib/store'

export function DataTransferDownload({ load, expectedDigest, disabled, children }: { load: (signal: AbortSignal) => Promise<DataTransferFile>; expectedDigest?: string; disabled?: boolean; children: ReactNode }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState<unknown>()
  const pending = useRef<AbortController | null>(null), alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false; pending.current?.abort() } }, [])
  async function download() {
    if (pending.current || disabled) return
    const owner = useSession.getState(), generation = owner.generation, authGeneration = owner.authGeneration
    const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(undefined)
    try {
      const file = await load(controller.signal)
      if (!alive.current || controller.signal.aborted || useSession.getState().generation !== generation || useSession.getState().authGeneration !== authGeneration) return
      if (expectedDigest && file.content_sha256 !== expectedDigest) throw new Error('文件与选定导出快照摘要不一致，未下载')
      await saveDataTransferFile(file, controller.signal)
    } catch (error) {
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === generation && useSession.getState().authGeneration === authGeneration) setError(error)
    } finally {
      if (pending.current === controller) pending.current = null
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === generation && useSession.getState().authGeneration === authGeneration) setBusy(false)
    }
  }
  return <div className="data-transfer-download"><Button type="button" variant="outline" size="sm" disabled={disabled || busy} onClick={() => void download()}>{busy ? '准备下载…' : children}</Button>{error != null && <ErrorNotice error={error}/>}</div>
}
