import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { Paginated } from '@/lib/types'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Empty, ErrorNotice, Loading, Pager } from './common'

type Target = { deviceId: string; tenantId: string }
type Row = { id: string; device_id: string; tenant_id: string; occurred_at: string }
type Connection = Row & { state: keyof typeof states; source: keyof typeof sources; received_at: string | null; duration_seconds: number | null }
type Lifecycle = Row & { action: keyof typeof actions; actor: string | null; detail: string | null }
const states = { ONLINE: '在线', OFFLINE: '离线', UNREGISTERED: '未注册', UNKNOWN: '未知' }
const sources = { HIKVISION: '海康云状态', SOURCE_REPORT: '来源离线记录', PLATFORM_SCAN: '平台离线判定' }
const actions = { REGISTER: '登记设备', MOVE: '移动位置', BIND: '绑定来源', UNBIND: '解除来源绑定', VERSION: '迁移产品版本', TRANSFER_IN: '移交迁入', TRANSFER_OUT: '移交迁出', ARCHIVE: '归档设备' }
const empty = { state: '', from: '', to: '' }
function queryFilters(filters: typeof empty) {
  const query = new URLSearchParams()
  if (filters.state) query.set('state', filters.state)
  if (filters.from) query.set('from', `${filters.from}+08:00`)
  if (filters.to) query.set('to', `${filters.to}+08:00`)
  return query
}

function history<T extends Row>(target: Target, kind: string, page: number, signal: AbortSignal, filters = empty) {
  const query = queryFilters(filters)
  query.set('page', String(page))
  return request<Paginated<T>>(`/${useSession.getState().audience}/devices/${encodeURIComponent(target.deviceId)}/${kind}?${query}`, {
    tenantScoped: true, tenantId: target.tenantId, signal, decode: raw => {
      const value = JSON.parse(raw) as Paginated<T>
      if (!value || !Array.isArray(value.items) || value.page !== page || !Number.isSafeInteger(value.size) || value.size < 1 || !Number.isSafeInteger(value.total) || value.total < value.items.length || value.items.length > value.size || value.items.some(row => !row || typeof row.id !== 'string' || row.device_id !== target.deviceId || row.tenant_id !== target.tenantId || !Number.isFinite(Date.parse(row.occurred_at)))) {
        throw new ApiError(200, 'DEVICE_HISTORY_UNCONFIRMED', '设备履历响应或归属未确认')
      }
      return value
    },
  })
}

function duration(seconds: number | null) {
  if (seconds === null) return '未确认'
  const hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds / 60) % 60
  return `${hours ? `${hours}小时` : ''}${minutes ? `${minutes}分钟` : ''}${seconds % 60}秒`
}

export function DeviceConnectivityHistory(target: Target) {
  const [page, setPage] = useState(1), [draft, setDraft] = useState(empty), [filters, apply] = useState(empty), [invalid, setInvalid] = useState(false)
  const resource = useResource(`connectivity-history:${target.tenantId}:${target.deviceId}:${page}:${JSON.stringify(filters)}`, signal => history<Connection>(target, 'connectivity-history', page, signal, filters))
  const [exporting, setExporting] = useState(false), [exportError, setExportError] = useState<unknown>()
  const exportRequest = useRef<AbortController | null>(null)
  useEffect(() => () => exportRequest.current?.abort(), [])
  async function download() {
    const { token, audience, authGeneration } = useSession.getState(), abort = new AbortController()
    if (!token || exportRequest.current) return
    exportRequest.current = abort; setExportError(undefined); setExporting(true)
    try {
      const headers = new Headers({ Accept: 'text/csv', Authorization: `Bearer ${token}` })
      if (audience === 'management') headers.set('X-Tenant-Id', target.tenantId)
      const response = await fetch(`/api/${audience}/devices/${encodeURIComponent(target.deviceId)}/connectivity-history/export?${queryFilters(filters)}`, {
        signal: abort.signal, headers, credentials: 'omit', redirect: 'error', cache: 'no-store',
      })
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('text/csv')) throw new Error(`导出未确认（HTTP ${response.status}）`)
      const blob = await response.blob()
      if (abort.signal.aborted || useSession.getState().authGeneration !== authGeneration) return
      const url = URL.createObjectURL(blob), anchor = document.createElement('a')
      anchor.href = url; anchor.download = 'connectivity-history.csv'; anchor.click()
      setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (error) { if (!abort.signal.aborted) setExportError(error) }
    finally { exportRequest.current = null; if (!abort.signal.aborted) setExporting(false) }
  }
  function filter(event: FormEvent) {
    event.preventDefault()
    if (draft.from && draft.to && draft.from >= draft.to) { setInvalid(true); return }
    setInvalid(false); setPage(1); apply(draft)
  }
  return <section aria-label="本设备在离线记录">
    <form onSubmit={filter} className="device-detail-toolbar">
      <select className="native-select" aria-label="在离线状态" value={draft.state} onChange={event => setDraft({ ...draft, state: event.target.value })}><option value="">全部状态</option>{Object.entries(states).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
      <Input aria-label="在离线开始时间（北京时间）" type="datetime-local" step="1" value={draft.from} onChange={event => setDraft({ ...draft, from: event.target.value })}/>
      <Input aria-label="在离线结束时间（北京时间）" type="datetime-local" step="1" value={draft.to} onChange={event => setDraft({ ...draft, to: event.target.value })}/>
      <Button type="button" size="sm" variant="outline" disabled={exporting} onClick={() => void download()}>导出</Button>
      <Button type="submit" size="sm">查询</Button><Button size="sm" variant="outline" onClick={resource.refresh}>刷新</Button>
    </form>
    {exportError != null && <ErrorNotice error={exportError}/>}
    {invalid && <p role="alert">结束时间必须晚于开始时间</p>}
    <p className="muted-note">只有相邻的已知来源状态才计算持续时间；一般心跳和最后一次上报不作为状态变化。</p>
    {resource.loading ? <Loading/> : resource.error != null ? <ErrorNotice error={resource.error} retry={resource.refresh}/> : resource.data && <>
      {resource.data.items.length ? <Table><TableHeader><TableRow><TableHead>上报／判定时间</TableHead><TableHead>状态</TableHead><TableHead>持续时间</TableHead><TableHead>来源</TableHead></TableRow></TableHeader><TableBody>
        {resource.data.items.map(row => <TableRow key={`${row.source}:${row.id}`}><TableCell>{dateTime(row.occurred_at)}</TableCell><TableCell>{states[row.state] ?? '未知'}</TableCell><TableCell>{duration(row.duration_seconds)}</TableCell><TableCell>{sources[row.source] ?? '未确认'}</TableCell></TableRow>)}
      </TableBody></Table> : <Empty title="无已记录的在离线变化"/>}
      <Pager page={resource.data.page} size={resource.data.size} total={resource.data.total} onChange={setPage}/>
    </>}
  </section>
}

export function DeviceLifecycleHistory(target: Target) {
  const [page, setPage] = useState(1)
  const resource = useResource(`lifecycle:${target.tenantId}:${target.deviceId}:${page}`, signal => history<Lifecycle>(target, 'lifecycle', page, signal))
  return <section aria-label="本设备生命周期">
    <div className="device-detail-toolbar"><Button size="sm" variant="outline" onClick={resource.refresh}>刷新生命周期</Button></div>
    {resource.loading ? <Loading/> : resource.error != null ? <ErrorNotice error={resource.error} retry={resource.refresh}/> : resource.data && <>
      {resource.data.items.length ? <ol className="device-lifecycle">{resource.data.items.map(row => <li key={row.id}>
        <time dateTime={row.occurred_at}>{dateTime(row.occurred_at)}</time><div><strong>{actions[row.action] ?? row.action}</strong>{row.detail && <> · {row.detail}</>}</div><p>操作人：{row.actor ?? '未记录'}</p>
      </li>)}</ol> : <Empty title="暂无已留存的生命周期记录"/>}
      <Pager page={resource.data.page} size={resource.data.size} total={resource.data.total} onChange={setPage}/>
    </>}
  </section>
}
