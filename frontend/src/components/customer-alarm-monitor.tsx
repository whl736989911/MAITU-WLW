import { useEffect, useRef, useState } from 'react'
import { useObjectRevision } from '@/lib/object-events'
import { api, errorMessage } from '@/lib/api'
import { emptyAlarmFilters, validateAlarmWorkbench } from '@/lib/alarm-workbench'
import { alarmTimeText } from '@/lib/alarm-time'
import { getAlarmChanges, readChangeCoordinate } from '@/lib/alarm-changes'
import { AlarmChangeSocket } from '@/lib/alarm-change-socket'
import { reported } from '@/lib/format'
import { useSession } from '@/lib/store'
import type { Alarm, AlarmWorkbenchFilters } from '@/lib/types'
import { AlarmDetail } from '@/components/alarm-detail'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'

const filters: AlarmWorkbenchFilters = { ...emptyAlarmFilters, physical_state: 'FIRE', response_state: 'UNHANDLED' }
const pageSize = 100
const identity = (alarm: Alarm) => `${alarm.id}:${alarm.revision}`

/** A read-only, source-independent reminder. Only the existing review endpoint changes an alarm. */
export function CustomerAlarmMonitor() {
  const { audience, user, permissions, generation } = useSession()
  if (audience !== 'customer' || !user?.tenant_id || user.must_change_password || !permissions.includes('alarm:read')) return null
  return <Monitor key={generation}/>
}

function Monitor() {
  const [alarms, setAlarms] = useState<Alarm[]>([])
  const [dismissed, setDismissed] = useState<Set<string>>(() => new Set())
  const [detailId, setDetailId] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [channelReady, setChannelReady] = useState(false)
  const dismissedRef = useRef(dismissed)
  const refresh = useRef<() => void>(() => {})
  const canReview = useSession(s => s.permissions.includes('alarm:review'))
  const eventRevision = useObjectRevision('events'), observedRevision = useRef(eventRevision)
  useEffect(() => {
    if (observedRevision.current === eventRevision) return
    observedRevision.current = eventRevision
    refresh.current()
  }, [eventRevision])
  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined, reconnectTimer: ReturnType<typeof setTimeout> | undefined
    let running = false, reloadRequested = false, connecting = false, lastHead = ''
    let authorityVersion = 0, reading: AbortController | null = null
    const live = () => !controller.signal.aborted
    function reconnect() {
      if (!live() || reconnectTimer !== undefined) return
      reconnectTimer = setTimeout(() => { reconnectTimer = undefined; void connect() }, 5000)
    }
    const channel = new AlarmChangeSocket({
      state: () => {},
      head: head => {
        if (!live()) return
        const position = `${head.stream_id}:${head.sequence}`
        if (position !== lastHead) { lastHead = position; void load() }
      },
      authorityLost: () => { if (live()) { authorityVersion++; reading?.abort(); setAlarms([]); setDetailId(null); reconnect(); void load() } },
      lifecycle: event => {
        if (!live()) return
        connecting = false
        setChannelReady(event.type === 'ready')
        if (event.type === 'ended' && event.reason !== 'cancelled') { reconnect(); void load() }
      },
    })
    async function load() {
      if (!live()) return
      if (running) { reloadRequested = true; return }
      clearTimeout(timer); running = true
      const capturedAuthority = authorityVersion, request = new AbortController()
      reading = request
      const readable = () => live() && !request.signal.aborted && capturedAuthority === authorityVersion
      try {
        const current = new Map<string, Alarm>()
        for (let page = 1; ; page++) {
          const data = await api.alarmWorkbench(filters, page, pageSize, request.signal)
          if (!readable()) return
          const invalid = validateAlarmWorkbench(data, filters, page, pageSize)
          if (invalid) throw new Error(invalid)
          for (const alarm of data.items) current.set(alarm.id, alarm)
          if (page * data.size >= data.total) break
        }
        if (!readable()) return
        const rows = [...current.values()], active = new Set(rows.map(identity))
        const kept = new Set([...dismissedRef.current].filter(key => active.has(key)))
        dismissedRef.current = kept; setDismissed(kept)
        setAlarms(rows); setError('')
      } catch (cause) {
        if (readable()) { setAlarms([]); setError(errorMessage(cause)) }
      } finally {
        running = false
        if (live()) {
          const delay = reloadRequested ? 0 : 5000
          reloadRequested = false
          if (delay !== undefined) timer = setTimeout(() => void load(), delay)
        }
      }
    }
    async function connect() {
      if (!live() || connecting || channel.getReady()) return
      connecting = true
      try {
        const baseline = await getAlarmChanges({}, controller.signal)
        if (!live()) return
        const coordinate = readChangeCoordinate(baseline.next_cursor)
        lastHead = `${coordinate.stream_id}:${coordinate.sequence}`
        await load()
        if (!live()) return
        const connection = channel.connect(baseline.next_cursor, useSession.getState().generation)
        connecting = true
        await connection
      } catch {
        if (live()) { connecting = false; setChannelReady(false); reconnect() }
      }
    }
    refresh.current = () => { void load() }
    const foreground = () => { if (document.visibilityState === 'visible') { void load(); void connect() } }
    document.addEventListener('visibilitychange', foreground)
    void load(); void connect()
    return () => { controller.abort(); reading?.abort(); clearTimeout(timer); clearTimeout(reconnectTimer); channel.disconnect(false); document.removeEventListener('visibilitychange', foreground); refresh.current = () => {} }
  }, [])
  const pending = alarms.filter(alarm => !dismissed.has(identity(alarm)))
  const current = pending[0]
  function dismiss() {
    if (!current) return
    const next = new Set(dismissedRef.current); next.add(identity(current))
    dismissedRef.current = next; setDismissed(next)
  }
  return <>
    {error && <div role="alert" className="notice">报警自动刷新失败：{error}。网络恢复后自动重试；不能据此判断无报警。<Button type="button" variant="outline" size="sm" onClick={() => refresh.current()}>立即重试</Button></div>}
    {!channelReady && <p role="status" className="muted-note">报警即时通道正在连接；期间每5秒补查一次。网络异常时不能保证即时提醒。</p>}
    <Sheet open={!!current && !detailId} onOpenChange={open => { if (!open) dismiss() }}><SheetContent className="review-sheet sm:max-w-xl">
      <SheetHeader className="review-header"><SheetTitle>火灾报警提醒</SheetTitle><SheetDescription>有 {pending.length} 条待查看的未处置火警。即时信号到达后自动核对当前权限并读取报警，定时补查仅作兜底；请按消防预案核查。</SheetDescription></SheetHeader>
      {current && <div className="review-body form-stack"><h2>{reported(current.device_name)}</h2><dl className="detail-list"><div><dt>安装地址</dt><dd>{reported(current.address)}</dd></div><div><dt>来源发生时间</dt><dd>{alarmTimeText(current)}</dd></div></dl><div className="identity-row-actions"><Button type="button" onClick={() => { setDetailId(current.id); dismiss() }}>{canReview ? '立即处理' : '查看报警'}</Button><Button type="button" variant="outline" onClick={dismiss}>稍后处理</Button></div><p className="muted-note">关闭提醒不会处置报警，也不会使设备恢复正常。</p></div>}
    </SheetContent></Sheet>
    <AlarmDetail alarmId={detailId} onClose={() => { setDetailId(null); refresh.current() }} onChanged={() => {}}/>
  </>
}
