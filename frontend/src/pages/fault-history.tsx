import { DataTransferActions } from '@/components/data-transfer-actions'
import { useState,type FormEvent } from 'react'
import { MapPin, Radio } from 'lucide-react'
import { dateTime } from '@/lib/format'
import { useObjectRevision } from '@/lib/object-events'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { queryFaults, sourceFaultRecoveryText, type FaultEvent } from '@/lib/duty-api'
import { Card,CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty,ErrorNotice,Loading,PageHeading,Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { ConvertFaultToWorkOrder } from '@/components/convert-fault-to-work-order'
import { DeviceFaultsPage, FaultDeviceDrawer } from './device-faults'
import './duty-faults.css'
export function FaultHistoryPage({ workbench = false }: { workbench?: boolean } = {}){
 const session=useSession()
 const scope=JSON.stringify([session.generation,session.audience,session.scopeTenantIds,session.scopePlaceIds,session.scopeInitialized])
 return workbench ? <FaultHistoryContent key={scope} workbench/> : <FaultHistoryViews key={scope}/>
}
function FaultHistoryViews() {
 const [byDevice,setByDevice]=useState(false)
 return <>
  <PageHeading eyebrow="DUTY · FAULT HISTORY" title="故障历史" description="按事件追溯来源故障，或按设备查看故障汇总。">
   <PageHelp title="故障来源口径"><p>只显示来源明确报告的故障，不从离线推断。人工处置不会改写来源恢复。时间筛选按来源观察时间，接收时间不代替观察时间。</p></PageHelp>
  </PageHeading>
  <div className="list-toolbar" role="tablist" aria-label="故障历史视图">
   <Button type="button" role="tab" aria-selected={!byDevice} onClick={()=>setByDevice(false)}>按事件</Button>
   <Button type="button" role="tab" aria-selected={byDevice} onClick={()=>setByDevice(true)}>按设备</Button>
  </div>
  {byDevice?<DeviceFaultsPage embedded/>:<FaultHistoryContent workbench={false}/>}
 </>
}
function FaultHistoryContent({ workbench }: { workbench: boolean }){
 const session=useSession(),scopeKey=`${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`,empty=session.scopeInitialized&&session.scopeTenantIds.length===0&&session.scopePlaceIds.length===0
 const [page,setPage]=useState(1),[keyword,setKeyword]=useState(''),[applied,setApplied]=useState(''),[from,setFrom]=useState(''),[to,setTo]=useState(''),[appliedRange,setAppliedRange]=useState({from:'',to:''}),[selected,setSelected]=useState<FaultEvent|null>(null)
 const [filterError,setFilterError]=useState('')
 const revision=useObjectRevision('events')
 const result=useResource(`fault-history:${workbench}:${scopeKey}:${page}:${applied}:${JSON.stringify(appliedRange)}:${revision}`,signal=>queryFaults(page,applied,appliedRange.from,appliedRange.to,signal,{activeOnly:workbench}),session.scopeInitialized&&!empty&&session.permissions.includes('device:read'))
 const data=result.data
 function changePage(next:number){setSelected(null);setPage(next)}
 function applyFilters(event:FormEvent){
  event.preventDefault()
  const start=from?new Date(from):null,end=to?new Date(to):null
  if ((start&&!Number.isFinite(start.getTime()))||(end&&!Number.isFinite(end.getTime()))||(start&&end&&start>end)) {
   setFilterError('请选择有效的起止时间，结束时间不得早于开始时间。')
   return
  }
  setFilterError('');setApplied(keyword.trim());setAppliedRange({from:start?.toISOString()??'',to:end?.toISOString()??''});changePage(1)
 }
 return <section className="duty-fault-page">
  <Card className="list-card management-list-panel"><CardContent>
   <div className="management-list-bar">
    <h2>{workbench ? '当前故障' : '来源故障记录'}</h2>
    <div className="list-toolbar management-list-actions">
     <DataTransferActions kind="FAULTS" label="导出故障" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), keyword: applied, from: appliedRange.from, to: appliedRange.to, ...(workbench ? { handling_state: 'ACTIVE' } : {}) }}/>
     <Button type="button" variant="outline" onClick={result.refresh}>{workbench ? '刷新故障事件' : '刷新故障历史'}</Button>
    </div>
   </div>
   <details className="secondary-controls">
    <summary>筛选故障记录{(applied || appliedRange.from || appliedRange.to) && ' · 已启用筛选'}</summary>
    <form className="alarm-filter-form" onSubmit={applyFilters}>
     <div className="alarm-filter-grid">
      <label className="field">设备名、位置或事件方法<Input value={keyword} onChange={e=>setKeyword(e.target.value)} placeholder="匹配已存设备与事件方法"/></label>
      <label className="field">开始时间<Input type="datetime-local" value={from} onChange={e=>setFrom(e.target.value)}/></label>
      <label className="field">结束时间<Input type="datetime-local" value={to} onChange={e=>setTo(e.target.value)}/></label>
     </div>
     <div className={workbench ? 'alarm-filter-actions' : 'management-query-actions'}>
      <Button type="submit">{workbench ? '应用筛选' : '筛选历史'}</Button>
      <Button type="button" variant="outline" onClick={()=>{setKeyword('');setApplied('');setFrom('');setTo('');setAppliedRange({from:'',to:''});setFilterError('');changePage(1)}}>清除筛选</Button>
     </div>
    </form>
    {filterError && <p className="notice" role="alert">{filterError}</p>}
   </details>
   {empty ? <Empty title="当前范围未选择"/> : !session.scopeInitialized || result.loading ? <Loading/> : result.error != null ? <ErrorNotice error={result.error} retry={result.refresh}/> : !data ? <Empty title="故障记录尚未确认"/> : <>
    <dl className="management-list-counts"><div><dt>匹配故障事件</dt><dd>{data.total}</dd></div><div><dt>本页来源事件</dt><dd>{data.items.length}</dd></div></dl>
    {data.items.length ? <Table className={workbench ? 'alarm-table' : 'management-record-table'} aria-label={workbench ? '故障事件列表' : '来源故障记录'}>
     <TableHeader><TableRow><TableHead>设备 / 位置</TableHead><TableHead>事件类型</TableHead><TableHead>来源恢复</TableHead><TableHead>人工处置</TableHead><TableHead>来源观察时间</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader>
     <TableBody>{data.items.map(event => {
      const deviceName = event.device_name ?? event.device_id ?? '无设备关联'
      return <TableRow key={event.id}>
       <TableCell><div className="table-primary"><span className="device-symbol"><Radio size={17}/></span><div><strong>{deviceName}</strong><div className="table-sub"><MapPin size={12}/>{event.device_address ?? '地址未记录'}</div></div></div></TableCell>
       <TableCell>{event.fault_label}{event.fault_code && ` (${event.fault_code})`}<div className="table-sub">{event.method ?? '来源未标注方法'}</div></TableCell>
       <TableCell>{sourceFaultRecoveryText(event, dateTime)}</TableCell>
       <TableCell>{event.handling ? event.handling.handled ? '已处置' : '未处置' : '尚未记录'}</TableCell>
       <TableCell>{event.observed_at ? dateTime(event.observed_at) : '观察时间未知'}</TableCell>
       <TableCell className="text-right"><Button type="button" size="sm" variant="outline" aria-label={`查看 ${deviceName} 的故障详情`} onClick={()=>setSelected(event)}>查看详情</Button></TableCell>
      </TableRow>
     })}</TableBody>
    </Table> : <Empty title={data.total ? '本页无故障事件' : '未检索到已报告故障事件'}/>}
    <Pager page={data.page} size={data.size} total={data.total} onChange={changePage}/>
   </>}
  </CardContent></Card>
  {workbench && selected?.device_id && <FaultDeviceDrawer key={selected.id} device={{
    device_id: selected.device_id, tenant_id: selected.tenant_id, device_name: selected.device_name, device_address: selected.device_address,
  }} initialEvent={selected} onClose={()=>setSelected(null)}/>}
  {selected && (!workbench || !selected.device_id) && <Card className="list-card management-list-panel"><CardContent>
   <div className="management-list-bar">
    <h2>故障事件详情</h2>
    <div className="list-toolbar management-list-actions"><Button type="button" variant="outline" onClick={()=>setSelected(null)}>关闭详情</Button></div>
   </div>
   <div className="management-record-detail">
    <dl><dt>事件ID</dt><dd>{selected.id}</dd><dt>来源故障ID</dt><dd>{selected.source_fault_id??'未报告'}</dd><dt>来源恢复状态</dt><dd>{sourceFaultRecoveryText(selected)}</dd><dt>故障类型</dt><dd>{selected.fault_label}{selected.fault_code?` (${selected.fault_code})`:''}</dd><dt>来源方法</dt><dd>{selected.method??'未记录方法'}</dd><dt>观察时间</dt><dd>{selected.observed_at??'未知（未回退为接收时间）'}</dd><dt>来源报文时间</dt><dd>{selected.source_message_at??'未知'} · {selected.source_time_field??'来源字段未记录'}（非故障发生时间）</dd><dt>接收时间</dt><dd>{selected.received_at??'未知'}（接收记录，不是故障发生时间）</dd><dt>事件入库时间</dt><dd>{selected.created_at}</dd><dt>设备</dt><dd>{selected.device_name??selected.device_id??'未关联设备'} · {selected.device_address??'地址未知'}</dd><dt>本地人工处置</dt><dd>{selected.handling?.handled?'已处理':selected.handling?'未处理':'无本地处理记录'}</dd><dt>位置ID</dt><dd>{selected.place_id??'未记录位置'}</dd></dl>
    <p className="muted-note">来源恢复时间仅表示来源报告，不能自动视作本地已处置或替代现场核查。平台报文时间与接收时间均不是已确认的故障发生时间。</p>
    {selected.device_id && <ConvertFaultToWorkOrder faultId={selected.id} deviceId={selected.device_id} tenantId={selected.tenant_id}/>}
   </div>
  </CardContent></Card>}
 </section>
}
