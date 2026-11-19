import { DataTransferActions } from '@/components/data-transfer-actions'
import { useState } from 'react'
import { useSession } from '@/lib/store'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { Card,CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { AlarmTable } from '@/components/data-tables'
import { AlarmDetail } from '@/components/alarm-detail'
import type { Alarm } from '@/lib/types'
const metrics=[['alarm_total','筛选匹配报警'],['unhandled','人工未处理'],['handled','人工已处理'],['unrecovered','物理未恢复'],['recovered','物理已恢复'],['true_fire','当前真实火警复核']] as const
export function AlarmHistoryPage(){
 const session=useSession(),scopeKey=`${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`,empty=session.scopeInitialized&&session.scopeTenantIds.length===0&&session.scopePlaceIds.length===0
 const [page,setPage]=useState(1),[size,setSize]=useState(20),[physical,setPhysical]=useState(''),[selected,setSelected]=useState<Alarm|null>(null),[applied,setApplied]=useState(''),[from,setFrom]=useState(''),[to,setTo]=useState(''),[range,setRange]=useState({from:'',to:''})
 const alarms=useResource(`alarm-history:${scopeKey}:${page}:${size}:${physical}:${applied}:${JSON.stringify(range)}`,signal=>api.alarmWorkbench({keyword:applied,physical_state:(physical||null) as 'FIRE'|'NORMAL'|null,response_state:null,conclusion:null,occurred_from:range.from||null,occurred_to:range.to||null},page,size,signal,session.scopeTenantIds,session.scopePlaceIds),session.scopeInitialized&&!empty&&session.permissions.includes('alarm:read'))
 const data=alarms.data
 return <><PageHeading eyebrow="DUTY · ALARM HISTORY" title="报警信息" description="检索与复核已存报警记录。">
  <PageHelp title="报警历史口径"><div className="form-stack"><p>报警、故障、复核结论和设备物理状态是独立事实；人工处理不会改写物理恢复状态。</p><p>时间范围基于报警发生时间。当前单位 / 位置范围为空时不会读取其它范围。</p></div></PageHelp>
  <Button type="button" variant="outline" onClick={()=>alarms.refresh()}>刷新报警历史</Button>
  <DataTransferActions kind="ALARMS" label="导出报警" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), keyword: applied, physical_state: physical, occurred_from: range.from, occurred_to: range.to }}/>
 </PageHeading>
 <details className="secondary-controls alarm-filter-panel">
  <summary>筛选报警{(applied || physical || range.from || range.to) && ' · 已提交筛选'}</summary>
  <div className="secondary-controls-body">
   <form className="device-workbench-grid" onSubmit={e=>{e.preventDefault();const input=new FormData(e.currentTarget);setApplied(String(input.get('keyword')??'').trim());setRange({from:from?new Date(from).toISOString():'',to:to?new Date(to).toISOString():''});setPage(1)}}>
    <label className="field">设备/地址关键字<Input name="keyword" defaultValue={applied}/></label>
    <label className="field">物理状态<select className="native-select" value={physical} onChange={e=>{setPhysical(e.target.value);setPage(1)}}><option value="">全部</option><option value="FIRE">FIRE</option><option value="NORMAL">NORMAL</option></select></label>
    <label className="field">开始时间<Input type="datetime-local" value={from} onChange={e=>setFrom(e.target.value)}/></label>
    <label className="field">结束时间<Input type="datetime-local" value={to} onChange={e=>setTo(e.target.value)}/></label>
    <label className="field">每页记录<select className="native-select" value={size} onChange={e=>{setSize(Number(e.target.value));setPage(1)}}>{[20,50,100].map(n=><option key={n} value={n}>{n}</option>)}</select></label>
    <Button type="submit">应用筛选</Button>
   </form>
  </div>
 </details>
 {empty?<Empty title="当前范围未选择"/>:alarms.loading?<Loading/>:alarms.error!=null?<ErrorNotice error={alarms.error} retry={alarms.refresh}/>:!data?<Empty title="报警历史尚未确认"/>:<>
  <section className="alarm-query-results"><div className="alarm-metric-grid">{metrics.map(([key,label])=><Card className="alarm-query-metric" key={key}><CardContent><span>{label}</span><strong>{data.metrics[key]}</strong></CardContent></Card>)}</div></section>
  <Card><CardContent><div className="section-header"><h2>报警记录</h2></div>
   {data.items.length?<AlarmTable items={data.items} actions={alarm=><Button type="button" variant="outline" size="sm" onClick={()=>setSelected(alarm)}>查看详情与处理历史</Button>}/>:<Empty title={data.total?'本页无记录':'筛选范围无报警记录'}/>}
   <Pager page={data.page} size={data.size} total={data.total} onChange={setPage}/>
  </CardContent></Card>
 </>}
 {selected&&<AlarmDetail alarmId={selected.id} tenantId={selected.tenant_id} onClose={()=>setSelected(null)} onChanged={()=>alarms.refresh()}/>}
 </>
}
