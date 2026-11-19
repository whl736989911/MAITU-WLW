import { DataTransferActions } from '@/components/data-transfer-actions'
import { useState,type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { queryFaults, sourceFaultRecoveryText, type FaultEvent } from '@/lib/duty-api'
import { Card,CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Empty,ErrorNotice,Loading,PageHeading,Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { ConvertFaultToWorkOrder } from '@/components/convert-fault-to-work-order'
import './duty-faults.css'
export function FaultHistoryPage(){
 const session=useSession()
 const scope=JSON.stringify([session.generation,session.audience,session.scopeTenantIds,session.scopePlaceIds,session.scopeInitialized])
 return <FaultHistoryContent key={scope}/>
}
function FaultHistoryContent(){
 const session=useSession(),scopeKey=`${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`,empty=session.scopeInitialized&&session.scopeTenantIds.length===0&&session.scopePlaceIds.length===0
 const [page,setPage]=useState(1),[keyword,setKeyword]=useState(''),[applied,setApplied]=useState(''),[from,setFrom]=useState(''),[to,setTo]=useState(''),[appliedRange,setAppliedRange]=useState({from:'',to:''}),[selected,setSelected]=useState<FaultEvent|null>(null)
 const result=useResource(`fault-history:${scopeKey}:${page}:${applied}:${JSON.stringify(appliedRange)}`,signal=>queryFaults(page,applied,appliedRange.from,appliedRange.to,signal),session.scopeInitialized&&!empty&&session.permissions.includes('device:read'))
 const data=result.data
 function changePage(next:number){setSelected(null);setPage(next)}
 function applyFilters(event:FormEvent){event.preventDefault();setApplied(keyword.trim());setAppliedRange({from:from?new Date(from).toISOString():'',to:to?new Date(to).toISOString():''});changePage(1)}
 return <section className="duty-fault-page">
  <PageHeading eyebrow="DUTY · FAULT HISTORY" title="故障历史" description="筛选已存来源 FAULT 事件及人工处置记录。"><PageHelp title="故障来源口径"><div className="form-stack"><p>只列出持久化来源明确标记为 FAULT 的事件；离线或未上报状态不会推断为故障。</p><p>时间筛选依据来源观察时间。未知时间不会以来源报文时间或接收时间替代。</p><p>原始载荷不在普通设备读取接口暴露；当前查询遵循左侧单位与位置范围。</p></div></PageHelp><Button type="button" variant="outline" onClick={()=>result.refresh()}>刷新故障历史</Button><DataTransferActions kind="FAULTS" label="导出故障" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), keyword: applied, from: appliedRange.from, to: appliedRange.to }}/></PageHeading>
  <details className="secondary-controls"><summary>筛选故障记录{(applied || appliedRange.from || appliedRange.to) && ' · 已启用筛选'}</summary>
  <Card><CardContent><form className="device-workbench-grid" onSubmit={applyFilters}><label className="field">设备名、位置或事件方法<Input value={keyword} onChange={e=>setKeyword(e.target.value)} placeholder="匹配已存设备与事件方法"/></label><label className="field">开始时间<Input type="datetime-local" value={from} onChange={e=>setFrom(e.target.value)}/></label><label className="field">结束时间<Input type="datetime-local" value={to} onChange={e=>setTo(e.target.value)}/></label><Button type="submit">筛选历史</Button><Button type="button" variant="outline" onClick={()=>{setKeyword('');setApplied('');setFrom('');setTo('');setAppliedRange({from:'',to:''});changePage(1)}}>清除筛选</Button></form></CardContent></Card>
  </details>
  {empty?<Empty title="当前范围未选择"/>:result.loading?<Loading/>:result.error!=null?<ErrorNotice error={result.error} retry={result.refresh}/>:!data?<Empty title="故障历史尚未确认"/>:<><div className="device-workbench-metrics"><div><span>匹配 FAULT 事件</span><strong>{data.total}</strong></div><div><span>本页来源事件</span><strong>{data.items.length}</strong></div></div><Card><CardContent>{data.items.length?data.items.map(event=><button type="button" key={event.id} className="duty-event-row duty-event-button" onClick={()=>setSelected(event)}><strong>{event.device_name??event.device_id??'无设备关联'}</strong><time>{event.observed_at??'观察时间未知'}</time><span>{event.fault_label}{event.fault_code?` (${event.fault_code})`:''} · {event.method??'来源未标注方法'} · {sourceFaultRecoveryText(event)} · {event.handling?.handled?'本地已处置':'本地未处置/无记录'}</span><small>{event.device_address??'地址未记录'} · 来源故障ID {event.source_fault_id??'未报告'} · 报文 {event.source_message_at??'未知'} · 接收 {event.received_at??'未知'}</small></button>):<Empty title={data.total?'本页无故障事件':'未检索到已报告故障事件'}/>}<Pager page={data.page} size={data.size} total={data.total} onChange={changePage}/></CardContent></Card></>}
  {selected&&<Card><CardContent><h2>故障事件详情</h2><dl><dt>事件ID</dt><dd>{selected.id}</dd><dt>来源故障ID</dt><dd>{selected.source_fault_id??'未报告'}</dd><dt>来源恢复状态</dt><dd>{sourceFaultRecoveryText(selected)}</dd><dt>故障类型</dt><dd>{selected.fault_label}{selected.fault_code?` (${selected.fault_code})`:''}</dd><dt>来源方法</dt><dd>{selected.method??'未记录方法'}</dd><dt>观察时间</dt><dd>{selected.observed_at??'未知（未回退为接收时间）'}</dd><dt>来源报文时间</dt><dd>{selected.source_message_at??'未知'} · {selected.source_time_field??'来源字段未记录'}（非故障发生时间）</dd><dt>接收时间</dt><dd>{selected.received_at??'未知'}（接收记录，不是故障发生时间）</dd><dt>事件入库时间</dt><dd>{selected.created_at}</dd><dt>设备</dt><dd>{selected.device_name??selected.device_id??'未关联设备'} · {selected.device_address??'地址未知'}</dd><dt>本地人工处置</dt><dd>{selected.handling?.handled?'已处理':selected.handling?'未处理':'无本地处理记录'}</dd><dt>位置ID</dt><dd>{selected.place_id??'未记录位置'}</dd></dl><p className="muted-note">来源恢复时间仅表示来源报告，不能自动视作本地已处置或替代现场核查。平台报文时间与接收时间均不是已确认的故障发生时间。</p><Button type="button" variant="outline" onClick={()=>setSelected(null)}>关闭详情</Button></CardContent></Card>}
  {selected?.device_id&&<ConvertFaultToWorkOrder faultId={selected.id} deviceId={selected.device_id} tenantId={selected.tenant_id}/>}
 </section>
}
