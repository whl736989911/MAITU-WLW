import { useEffect, useRef, useState, type FormEvent } from 'react'
import { queryDeviceTelemetry, queryDeviceTelemetryHistory, type TelemetryReading } from '@/lib/duty-api'
import { useResource } from '@/lib/use-resource'
import { Empty, ErrorNotice, Loading, Pager } from './common'
import { Button } from './ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { DataTransferActions } from './data-transfer-actions'
import { useObjectRevision } from '@/lib/object-events'

const beijingTime = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium', hour12: false })
function SourceTime({ value }: { value: string | null }) {
  return value ? <time dateTime={value} title={value}>{beijingTime.format(new Date(value))}</time> : <>未提供</>
}

function ReadingValue({ reading }: { reading: TelemetryReading }) {
  return <><strong>{reading.value}</strong> {reading.unit ?? (reading.value_type === 'STRING' ? '文本' : reading.value_type === 'BOOLEAN' ? '布尔' : '单位未知')}</>
}

export function DeviceTelemetry({ deviceId, tenantId, cards = false }: { deviceId: string; tenantId: string; cards?: boolean }) {
  const [view, setView] = useState<'current' | 'history'>('current')
  if (cards) return <>
    <CurrentTelemetry deviceId={deviceId} tenantId={tenantId} cards/>
    <Button type="button" variant="link" size="sm" aria-expanded={view === 'history'} onClick={() => setView(view === 'history' ? 'current' : 'history')}>{view === 'history' ? '收起历史记录' : '查看监测历史'}</Button>
    {view === 'history' && <TelemetryHistory deviceId={deviceId} tenantId={tenantId}/>}
  </>
  return <>
    <div className="version-actions" role="tablist" aria-label="本设备监测视图">
      <Button type="button" role="tab" aria-selected={view === 'current'} variant={view === 'current' ? 'default' : 'outline'} onClick={() => setView('current')}>当前读数</Button>
      <Button type="button" role="tab" aria-selected={view === 'history'} variant={view === 'history' ? 'default' : 'outline'} onClick={() => setView('history')}>历史记录</Button>
    </div>
    {view === 'current' ? <CurrentTelemetry deviceId={deviceId} tenantId={tenantId}/> : <TelemetryHistory deviceId={deviceId} tenantId={tenantId}/>}
  </>
}

const preferredMetrics = ['signal_level', 'battery', 'humidity', 'temperature', 'water_vapor', 'maze_pollution', 'smoke_value']
const signalLevels: Record<string, string> = { '1': '弱', '2': '中', '3': '强' }
function metricOrder(key: string) { const position = preferredMetrics.indexOf(key); return position < 0 ? preferredMetrics.length : position }

function CurrentTelemetry({ deviceId, tenantId, cards = false }: { deviceId: string; tenantId: string; cards?: boolean }) {
  const data = useResource(`device-telemetry:${tenantId}:${deviceId}`, signal => queryDeviceTelemetry(deviceId, tenantId, signal))
  const revision = useObjectRevision('devices'), previousRevision = useRef(revision)
  useEffect(() => {
    const refreshWhenVisible = () => { if (document.visibilityState === 'visible') data.refresh() }
    const onVisibility = () => { if (document.visibilityState === 'visible') data.refresh() }
    if (previousRevision.current !== revision) refreshWhenVisible()
    previousRevision.current = revision
    const timer = window.setInterval(refreshWhenVisible, 30_000)
    document.addEventListener('visibilitychange', onVisibility)
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisibility) }
  }, [revision, data.refresh])
  const readings = !data.loading && data.error == null ? data.data?.items[0]?.readings ?? [] : []
  if (cards) {
    const sorted = [...readings].sort((a, b) => metricOrder(a.metric) - metricOrder(b.metric))
    return <>
      <div className="device-detail-toolbar"><Button type="button" size="sm" variant="ghost" onClick={data.refresh}>刷新监测值</Button>{data.data?.items[0]?.observed_at && <small>最近上报 <SourceTime value={data.data.items[0].observed_at}/></small>}</div>
      {data.loading ? <Loading/> : data.error != null ? <ErrorNotice error={data.error} retry={data.refresh}/> : !sorted.length ? <Empty title="暂无可展示的监测值"/> : <div className="device-monitor-cards">
        {sorted.map(reading => <div className="device-monitor-card" key={reading.metric} title={`上报：${reading.observed_at ?? '未提供'}；接收：${reading.received_at ?? '未提供'}`}>
          <strong>{reading.metric === 'signal_level' ? signalLevels[reading.value] ?? reading.value : reading.value}</strong>
          <span>{reading.label ?? reading.metric}{reading.unit ? ` (${reading.unit})` : ''}</span>
        </div>)}
      </div>}
    </>
  }
  return <section aria-label="本设备监测数据">
    <div className="version-actions"><h3>当前监测值</h3><Button type="button" variant="outline" onClick={data.refresh}>刷新监测数据</Button></div>
    <p className="muted-note">各指标保留自身最新上报时间，不把其他指标的新报文时间套用到旧值；时间按北京时间展示。</p>
    {data.loading ? <Loading/> : data.error != null ? <ErrorNotice error={data.error} retry={data.refresh}/> : readings.length === 0 ? <Empty title="暂无可展示的监测值"/> : <Table>
      <TableHeader><TableRow><TableHead>指标</TableHead><TableHead>监测值</TableHead><TableHead>设备上报时间</TableHead><TableHead>平台接收时间</TableHead></TableRow></TableHeader>
      <TableBody>{readings.map(reading => <TableRow key={reading.metric}>
        <TableCell><strong>{reading.label ?? reading.metric}</strong><div className="table-sub mono">{reading.metric}</div></TableCell>
        <TableCell><ReadingValue reading={reading}/></TableCell>
        <TableCell><SourceTime value={reading.observed_at}/></TableCell>
        <TableCell><SourceTime value={reading.received_at}/></TableCell>
      </TableRow>)}</TableBody>
    </Table>}
    <p className="muted-note">只读取已入库的真实上报，不下发设备指令，也不把接收时间当作设备心跳。</p>
  </section>
}

function TelemetryHistory({ deviceId, tenantId }: { deviceId: string; tenantId: string }) {
  const [page, setPage] = useState(1)
  const [draft, setDraft] = useState({ from: '', to: '' }), [applied, setApplied] = useState({ from: '', to: '' })
  const [error, setError] = useState<unknown>()
  const from = applied.from ? `${applied.from}+08:00` : '', to = applied.to ? `${applied.to}+08:00` : ''
  const data = useResource(`device-telemetry-history:${tenantId}:${deviceId}:${page}:${from}:${to}`, signal => queryDeviceTelemetryHistory(deviceId, tenantId, page, from, to, signal))
  function apply(event: FormEvent) {
    event.preventDefault()
    if (draft.from && draft.to && Date.parse(`${draft.from}+08:00`) >= Date.parse(`${draft.to}+08:00`)) { setError(new Error('结束时间必须晚于开始时间')); return }
    setError(undefined); setApplied({ ...draft }); setPage(1)
  }
  function clear() { setDraft({ from: '', to: '' }); setApplied({ from: '', to: '' }); setPage(1); setError(undefined) }
  return <section aria-label="本设备监测历史">
    <form onSubmit={apply}>
      <div className="device-detail-grid">
        <div className="field"><Label htmlFor="device-telemetry-history-from">开始时间（北京时间，含）</Label><Input id="device-telemetry-history-from" type="datetime-local" step="1" value={draft.from} onChange={event => setDraft(old => ({ ...old, from: event.target.value }))}/></div>
        <div className="field"><Label htmlFor="device-telemetry-history-to">结束时间（北京时间，不含）</Label><Input id="device-telemetry-history-to" type="datetime-local" step="1" value={draft.to} onChange={event => setDraft(old => ({ ...old, to: event.target.value }))}/></div>
      </div>
      <div className="version-actions"><Button type="submit">查询历史</Button><Button type="button" variant="outline" onClick={clear}>清空时间条件</Button><Button type="button" variant="outline" onClick={data.refresh}>刷新历史</Button></div>
    </form>
    <div className="version-actions"><DataTransferActions kind="MONITORING" label="导出本设备监测历史" filters={{ unit_ids: tenantId, device_id: deviceId, history_only: true, observed_from: from, observed_to: to }}/></div>
    {error != null && <ErrorNotice error={error}/>}
    {(draft.from !== applied.from || draft.to !== applied.to) && <p className="muted-note">时间条件尚未应用，翻页和导出仍沿用上次查询条件。</p>}
    <p className="muted-note">按上报记录分页，一条记录可包含多个指标；不将轮询快照重复计入历史。未提供来源时间的记录不会改用接收时间。</p>
    {data.loading ? <Loading/> : data.error != null ? <ErrorNotice error={data.error} retry={data.refresh}/> : !data.data ? <Empty title="历史读取尚未确认"/> : <>
      {data.data.items.length === 0 ? <Empty title={data.data.total ? '当前页没有历史记录' : '暂无符合条件的监测历史'}/> : <Table>
        <TableHeader><TableRow><TableHead>设备上报时间</TableHead><TableHead>历史读数</TableHead><TableHead>平台接收时间</TableHead></TableRow></TableHeader>
        <TableBody>{data.data.items.map(sample => <TableRow key={sample.event_id} data-telemetry-history-id={sample.event_id}>
          <TableCell><SourceTime value={sample.observed_at}/></TableCell>
          <TableCell>{sample.readings.length ? sample.readings.map(reading => <div key={reading.metric}><span>{reading.label ?? reading.metric}：</span><ReadingValue reading={reading}/></div>) : '未解析出可展示指标'}</TableCell>
          <TableCell><SourceTime value={sample.received_at}/></TableCell>
        </TableRow>)}</TableBody>
      </Table>}
      <Pager page={data.data.page} size={data.data.size} total={data.data.total} onChange={setPage}/>
    </>}
  </section>
}
