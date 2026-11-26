import { useEffect, useRef, useState } from 'react'
import { Modal } from 'antd'
import { ChevronDown, Flame } from 'lucide-react'
import { useObjectRevision } from '@/lib/object-events'
import { api, errorMessage } from '@/lib/api'
import { emptyAlarmFilters, validateAlarmWorkbench } from '@/lib/alarm-workbench'
import { alarmTimeText } from '@/lib/alarm-time'
import { getAlarmChanges, readChangeCoordinate } from '@/lib/alarm-changes'
import { AlarmChangeSocket } from '@/lib/alarm-change-socket'
import { useSession } from '@/lib/store'
import type { Alarm, AlarmWorkbenchFilters } from '@/lib/types'
import { AlarmDetail } from '@/components/alarm-detail'
import { DeviceControlPanel } from '@/components/device-detail-actions'
import { Button } from '@/components/ui/button'

const filters: AlarmWorkbenchFilters = { ...emptyAlarmFilters, physical_state: 'FIRE', response_state: 'UNHANDLED' }
const pageSize = 100
const identity = (alarm: Alarm) => `${alarm.id}:${alarm.revision}`

/** Source-independent reminders; closing them never writes alarm or device state. */
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
  function dismiss(entries: readonly Alarm[]) {
    const next = new Set(dismissedRef.current)
    for (const alarm of entries) next.add(identity(alarm))
    dismissedRef.current = next; setDismissed(next)
  }
  const address = current?.address?.trim() || '位置未登记'
  const serial = current?.device_eui?.trim()
  const deviceName = current?.device_name?.trim()
  return <>
    {error && <div role="alert" className="notice">报警自动刷新失败：{error}。网络恢复后自动重试；不能据此判断无报警。<Button type="button" variant="outline" size="sm" onClick={() => refresh.current()}>立即重试</Button></div>}
    {!channelReady && <p role="status" className="muted-note">报警即时通道正在连接；期间每5秒补查一次。网络异常时不能保证即时提醒。</p>}
    <Modal open={!!current && !detailId} centered rootClassName="legacy-alarm-overlay" className="fire-reminder"
      title={<h2 className="fire-reminder-title"><ChevronDown aria-hidden="true" size={20}/>火灾报警 - {address}</h2>}
      width={826} footer={null} closable={false} keyboard={false} mask={{ closable: false }} destroyOnHidden>
      {current && !detailId && <div className="fire-reminder-body">
        <div className="fire-reminder-content">
          <div className="fire-reminder-signal" aria-hidden="true"><Flame className="fire-reminder-icon" size={98}/></div>
          <dl className="fire-reminder-facts">
            <div><dt>报警时间</dt><dd>{alarmTimeText(current)}</dd></div>
            <div><dt>报警类型</dt><dd>火灾报警</dd></div>
            <div><dt>安装位置</dt><dd>{address}</dd></div>
            <div><dt>设备名称</dt><dd>{deviceName || '未登记'}</dd></div>
            <div><dt>设备编号</dt><dd>{serial || '未登记'}</dd></div>
          </dl>
        </div>
        <div className="fire-reminder-footer">
          <div className="fire-reminder-actions">
            {current.device_id && current.tenant_id && <DeviceControlPanel key={identity(current)} appearance="alarm" deviceId={current.device_id} tenantId={current.tenant_id} deviceName={deviceName}/>}
            <Button onClick={() => { setDetailId(current.id); dismiss([current]) }}>{canReview ? '复核' : '查看详情'}</Button>
            <Button aria-describedby="fire-reminder-close-note" onClick={() => dismiss([current])}>关闭</Button>
            <Button aria-describedby="fire-reminder-close-note" onClick={() => dismiss(pending)}>关闭所有</Button>
          </div>
          <p id="fire-reminder-close-note">关闭只隐藏提醒，不会更新处置结果或设备状态。{pending.length > 1 && <span role="status"> 另有 {pending.length - 1} 条待查看火警。</span>}</p>
        </div>
      </div>}
    </Modal>
    <AlarmDetail alarmId={detailId} startReview={canReview} onClose={() => { setDetailId(null); refresh.current() }} onChanged={() => {}}/>
  </>
}
