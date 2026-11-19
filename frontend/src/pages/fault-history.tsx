import { DataTransferActions } from '@/components/data-transfer-actions'
import { useState,type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { getFaultHandlingHistory, queryFaults, sourceFaultRecoveryText, type FaultEvent } from '@/lib/duty-api'
import { beijingInput, canonicalAlarmInstant } from '@/lib/alarm-workbench'
import { dateTime } from '@/lib/format'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Card,CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Empty,ErrorNotice,Loading,PageHeading,Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { ConvertFaultToWorkOrder } from '@/components/convert-fault-to-work-order'
import './duty-faults.css'
export function FaultHistoryPage() {
  const session = useSession()
  if (!session.user || session.user.must_change_password || !session.permissions.includes('device:read')) return <Empty title="当前账户无故障历史读取权限"/>
  const scope = JSON.stringify([session.generation, session.audience, session.scopeTenantIds, session.scopePlaceIds, session.scopeInitialized])
  return <FaultHistoryContent key={scope}/>
}

function FaultHistoryContent() {
  const session = useSession()
  const empty = session.scopeInitialized && !session.scopeTenantIds.length && !session.scopePlaceIds.length
  const enabled = session.scopeInitialized && !empty
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [from, setFrom] = useState(''), [to, setTo] = useState('')
  const [applied, setApplied] = useState({ keyword: '', from: '', to: '' })
  const [selected, setSelected] = useState<FaultEvent | null>(null), [filterError, setFilterError] = useState<unknown>()
  const result = useResource(`fault-history:${JSON.stringify([page, applied])}`, signal => queryFaults(page, applied.keyword, applied.from, applied.to, signal), enabled)
  const data = !result.loading && result.error == null ? result.data : undefined

  function changePage(next: number) { setSelected(null); setPage(next) }
  function applyFilters(event: FormEvent) {
    event.preventDefault()
    try {
      const start = beijingInput(from), end = beijingInput(to)
      if (start && end && canonicalAlarmInstant(start) >= canonicalAlarmInstant(end)) throw new Error('开始时间必须早于结束时间。')
      setApplied({ keyword: keyword.trim(), from: start ?? '', to: end ?? '' }); setFilterError(undefined); changePage(1)
    } catch (error) { setFilterError(error) }
  }
  function refresh() { setSelected(null); result.refresh() }

  return <section className="duty-fault-page">
    <PageHeading eyebrow="运行中心 / 历史" title="故障历史" description="查询来源明确报告的故障，核对来源恢复和历次人工处置。">
      <PageHelp title="故障来源口径"><p>只显示已存来源 FAULT 事件。离线不等于故障，接收时间不替代来源观察时间，人工处置不代表来源恢复。</p></PageHelp>
      <Button type="button" variant="outline" disabled={!enabled} onClick={refresh}>刷新故障历史</Button>
      {enabled && <DataTransferActions kind="FAULTS" label="导出故障" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), ...applied }}/>}
    </PageHeading>
    <details className="secondary-controls">
      <summary>筛选故障记录{(applied.keyword || applied.from || applied.to) && ' · 已启用筛选'}</summary>
      <Card><CardContent>
        <form className="device-workbench-grid" onSubmit={applyFilters}>
          <label className="field">设备名、位置或事件方法<Input value={keyword} onChange={e => setKeyword(e.target.value)}/></label>
          <label className="field">观察时间从（北京时间）<Input type="datetime-local" value={from} onChange={e => setFrom(e.target.value)}/></label>
          <label className="field">至（不含）<Input type="datetime-local" value={to} onChange={e => setTo(e.target.value)}/></label>
          <div className="list-toolbar">
            <Button type="submit" disabled={!enabled}>筛选历史</Button>
            <Button type="button" variant="outline" onClick={() => { setKeyword(''); setFrom(''); setTo(''); setApplied({ keyword: '', from: '', to: '' }); setFilterError(undefined); changePage(1) }}>清除筛选</Button>
          </div>
        </form>
        {filterError != null && <ErrorNotice error={filterError}/>}
      </CardContent></Card>
    </details>
    {!session.scopeInitialized ? <Loading/> : empty ? <Empty title="当前范围未选择">请在左侧选择单位或位置。</Empty> :
      result.loading ? <Loading/> : result.error != null ? <ErrorNotice error={result.error} retry={refresh}/> :
      !data ? <div className="notice" role="alert">故障历史响应未确认。</div> : <Card><CardContent>
        <div className="section-header"><h2>来源故障记录</h2><span className="muted-note">当前筛选共 {data.total} 条</span></div>
        {data.items.length ? <Table>
          <TableHeader><TableRow><TableHead>设备 / 地址</TableHead><TableHead>故障类型</TableHead><TableHead>来源观察时间</TableHead><TableHead>来源恢复</TableHead><TableHead>人工处置</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
          <TableBody>{data.items.map(event => <TableRow key={`${event.tenant_id}:${event.id}`}>
            <TableCell><strong>{event.device_name ?? event.device_id ?? '无设备关联'}</strong><div className="table-sub">{event.device_address ?? '地址未记录'}</div></TableCell>
            <TableCell>{event.fault_label || '类型待识别'}{event.fault_code ? ` (${event.fault_code})` : ''}</TableCell>
            <TableCell>{event.observed_at ? dateTime(event.observed_at) : '观察时间未知'}</TableCell>
            <TableCell>{sourceFaultRecoveryText(event, dateTime)}</TableCell>
            <TableCell>{event.handling ? event.handling.handled ? '已处置' : '未处置' : '尚未记录'}</TableCell>
            <TableCell><Button type="button" variant="outline" size="sm" aria-label={`查看 ${event.device_name ?? event.device_id ?? event.id} 故障详情`} onClick={() => setSelected(event)}>查看详情</Button></TableCell>
          </TableRow>)}</TableBody>
        </Table> : <Empty title={data.total ? '本页无故障事件' : '当前筛选没有已报告的故障事件'}>{data.total > 0 && <Button type="button" variant="outline" onClick={() => changePage(1)}>返回第 1 页</Button>}</Empty>}
        <Pager page={data.page} size={data.size} total={data.total} onChange={changePage}/>
      </CardContent></Card>}
    {data && selected && <Card><CardContent>
      <div className="section-header">
        <h2 tabIndex={-1} ref={heading => { heading?.focus() }}>故障事件详情</h2>
        <Button type="button" variant="outline" size="sm" onClick={() => setSelected(null)}>关闭详情</Button>
      </div>
      <p>{selected.device_name ?? selected.device_id ?? '未关联设备'} · {selected.fault_label || '类型待识别'}</p>
      <FaultEvidence key={`${selected.tenant_id}:${selected.id}`} event={selected}/>
    </CardContent></Card>}
  </section>
}

function FaultEvidence({ event }: { event: FaultEvent }) {
  const history = useResource(`fault-handling-evidence:${event.tenant_id}:${event.id}`, async signal => {
    const entries = await getFaultHandlingHistory(event.id, event.tenant_id, signal)
    if (entries.some(entry => entry?.event_id !== event.id || entry.tenant_id !== event.tenant_id)) throw new Error('本地处置记录归属未确认')
    return entries
  })
  return <div className="form-stack">
    <h3>来源事实</h3>
    <dl>
      <dt>平台事件 ID</dt><dd className="mono">{event.id}</dd>
      <dt>厂商故障 ID</dt><dd>{event.source_fault_id ?? '未报告'}</dd>
      <dt>安装地址</dt><dd>{event.device_address ?? '地址未记录'}</dd>
      <dt>位置 ID</dt><dd>{event.place_id ?? '未记录位置'}</dd>
      <dt>类型 / 来源方法</dt><dd>{event.fault_label || '类型待识别'} · {event.fault_code ?? '编码未提供'} · {event.method ?? '方法未提供'}</dd>
      <dt>来源恢复</dt><dd>{sourceFaultRecoveryText(event, dateTime)}</dd>
      <dt>来源观察时间</dt><dd>{event.observed_at ? dateTime(event.observed_at) : '未知（不以接收时间替代）'}</dd>
      <dt>来源报文时间</dt><dd>{event.source_message_at ? dateTime(event.source_message_at) : '未知'} · {event.source_time_field ?? '来源字段未记录'}</dd>
      <dt>平台接收时间</dt><dd>{event.received_at ? dateTime(event.received_at) : '接收时间未知'}</dd>
      <dt>平台入库时间</dt><dd>{dateTime(event.created_at)}</dd>
    </dl>
    <div className="section-header"><h3>本地处置历史</h3><Button type="button" variant="outline" size="sm" onClick={history.refresh}>刷新处置历史</Button></div>
    <p className="muted-note">本地记录不改变来源故障或恢复状态。</p>
    {history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> :
      !history.data ? <div className="notice" role="alert">本地处置历史未确认。</div> : !history.data.length ? <Empty title="尚无本地处置记录"/> :
      <Table><TableHeader><TableRow><TableHead>版本</TableHead><TableHead>处置</TableHead><TableHead>操作人</TableHead><TableHead>操作时间</TableHead><TableHead>说明</TableHead></TableRow></TableHeader>
        <TableBody>{history.data.map(entry => <TableRow key={entry.revision}><TableCell>{entry.revision}</TableCell><TableCell>{entry.handled ? '已处置' : '未处置'}</TableCell><TableCell>{entry.updated_by || '未记录'}</TableCell><TableCell>{dateTime(entry.updated_at)}</TableCell><TableCell>{entry.note || '未填写'}</TableCell></TableRow>)}</TableBody>
      </Table>}
    {event.device_id && <ConvertFaultToWorkOrder faultId={event.id} deviceId={event.device_id} tenantId={event.tenant_id}/>}
  </div>
}
