import { useState, type FormEvent } from 'react'
import { queryDeviceDetailFaultEvents, sourceFaultRecoveryText, type FaultHandlingFilter } from '@/lib/duty-api'
import { dateTime } from '@/lib/format'
import { useResource } from '@/lib/use-resource'
import { beijingInput, canonicalAlarmInstant } from '@/lib/alarm-workbench'
import { DataTransferActions } from './data-transfer-actions'
import { Empty, ErrorNotice, Loading, Pager } from './common'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'

const emptyFaultFilters = { from: '', to: '', handling_state: '' as FaultHandlingFilter }

function FaultTime({ value, missing }: { value: string | null; missing: string }) {
  return value ? <time dateTime={value} title={value}>{dateTime(value)}</time> : <span>{missing}</span>
}

export function DeviceFaultRecords({ deviceId, tenantId }: { deviceId: string; tenantId: string }) {
  const [page, setPage] = useState(1)
  const [draft, setDraft] = useState({ ...emptyFaultFilters }), [appliedDraft, setAppliedDraft] = useState({ ...emptyFaultFilters }), [applied, setApplied] = useState({ ...emptyFaultFilters })
  const [error, setError] = useState<unknown>()
  const data = useResource(`device-detail-faults:${tenantId}:${deviceId}:${page}:${JSON.stringify(applied)}`, signal => queryDeviceDetailFaultEvents(deviceId, tenantId, page, applied.from, applied.to, applied.handling_state, signal))
  function apply(event: FormEvent) {
    event.preventDefault()
    try {
      const from = beijingInput(draft.from) ?? '', to = beijingInput(draft.to) ?? ''
      if (from && to && canonicalAlarmInstant(from) >= canonicalAlarmInstant(to)) throw new Error('结束时间必须晚于开始时间')
      setError(undefined); setApplied({ from, to, handling_state: draft.handling_state }); setAppliedDraft({ ...draft }); setPage(1); data.refresh()
    } catch (error) { setError(error) }
  }
  function clear() { setDraft({ ...emptyFaultFilters }); setAppliedDraft({ ...emptyFaultFilters }); setApplied({ ...emptyFaultFilters }); setPage(1); setError(undefined); data.refresh() }
  return <section aria-label="本设备故障记录">
    <div className="version-actions"><h3>故障记录</h3><Button type="button" variant="outline" onClick={data.refresh}>刷新本设备故障</Button>{!data.loading && data.error == null && data.data && <DataTransferActions kind="FAULTS" label="导出本设备故障记录" filters={{ unit_ids: tenantId, device_id: deviceId, from: applied.from, to: applied.to, handling_state: applied.handling_state }}/>}</div>
    <form onSubmit={apply}>
      <div className="device-detail-grid">
        <div className="field"><Label htmlFor="device-fault-handling">故障本地处置状态</Label><select id="device-fault-handling" className="native-select" value={draft.handling_state} onChange={event => setDraft(old => ({ ...old, handling_state: event.target.value as FaultHandlingFilter }))}><option value="">全部状态</option><option value="UNRECORDED">尚未记录</option><option value="UNHANDLED">未处置</option><option value="HANDLED">已处置</option></select></div>
        <div className="field"><Label htmlFor="device-fault-from">故障开始时间（北京时间，含）</Label><Input id="device-fault-from" type="datetime-local" step="1" value={draft.from} onChange={event => setDraft(old => ({ ...old, from: event.target.value }))}/></div>
        <div className="field"><Label htmlFor="device-fault-to">故障结束时间（北京时间，不含）</Label><Input id="device-fault-to" type="datetime-local" step="1" value={draft.to} onChange={event => setDraft(old => ({ ...old, to: event.target.value }))}/></div>
      </div>
      <div className="version-actions"><Button type="submit">查询故障记录</Button><Button type="button" variant="outline" onClick={clear}>清空故障条件</Button></div>
    </form>
    {error != null && <ErrorNotice error={error}/>}
    {(draft.from !== appliedDraft.from || draft.to !== appliedDraft.to || draft.handling_state !== appliedDraft.handling_state) && <p className="muted-note">筛选条件尚未应用，翻页和导出仍沿用上次查询条件。</p>}
    <p className="muted-note">按来源观察时间查询，不用接收或入库时间补齐。来源恢复与人工处置分别显示，不把离线判定或人工处理当作设备恢复。</p>
    {data.loading ? <Loading/> : data.error != null ? <ErrorNotice error={data.error} retry={data.refresh}/> : !data.data ? <Empty title="本设备故障记录尚未确认"/> : <>
      {data.data.items.length === 0 ? <Empty title={data.data.total ? '当前页没有故障记录' : applied.from || applied.to || applied.handling_state ? '本设备暂无符合条件的故障记录' : '本设备暂无故障记录'}/> : <Table>
        <TableHeader><TableRow><TableHead>故障类型</TableHead><TableHead>来源观察时间</TableHead><TableHead>平台接收时间</TableHead><TableHead>来源恢复</TableHead><TableHead>本地处置</TableHead></TableRow></TableHeader>
        <TableBody>{data.data.items.map(event => <TableRow key={event.id} data-device-fault-id={event.id}>
          <TableCell><strong>{event.fault_label || '类型待识别'}</strong><details><summary>记录详情</summary><dl className="detail-list">
            <div><dt>故障编码</dt><dd>{event.fault_code ?? '来源未提供编码'}</dd></div>
            <div><dt>来源协议方法</dt><dd>{event.method ?? '来源未提供'}</dd></div>
            <div><dt>平台事件标识</dt><dd className="mono">{event.id}</dd></div>
            <div><dt>厂商故障标识</dt><dd className="mono">{event.source_fault_id ?? '来源未提供'}</dd></div>
            <div><dt>平台记录时间</dt><dd><FaultTime value={event.created_at} missing="未提供"/></dd></div>
            {event.handling && <div><dt>处置说明</dt><dd>{event.handling.note || '未填写'}</dd></div>}
          </dl></details></TableCell>
          <TableCell><FaultTime value={event.observed_at} missing="观察时间未知"/></TableCell>
          <TableCell><FaultTime value={event.received_at} missing="接收时间未知"/></TableCell>
          <TableCell>{sourceFaultRecoveryText(event, dateTime)}</TableCell>
          <TableCell>{event.handling?.handled ? '已处置' : event.handling ? '未处置' : '尚未记录'}</TableCell>
        </TableRow>)}</TableBody>
      </Table>}
      <Pager page={data.data.page} size={data.data.size} total={data.data.total} onChange={setPage}/>
    </>}
  </section>
}
