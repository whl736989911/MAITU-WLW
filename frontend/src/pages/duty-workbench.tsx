import { useEffect, useRef, useState } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { api } from '@/lib/api'
import { queryFaults } from '@/lib/duty-api'
import { AlarmsPage } from './alarms'
import { FaultHistoryPage } from './fault-history'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading } from '@/components/common'
import { Card,CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { AlarmTable } from '@/components/data-tables'
import { AlarmReview } from '@/components/alarm-review'
import type { Alarm } from '@/lib/types'
import { publishObjectChange, useObjectRevision } from '@/lib/object-events'
export function DutyWorkbenchPage(){
 const session=useSession(),scopeKey=`${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`,empty=session.scopeInitialized&&session.scopeTenantIds.length===0&&session.scopePlaceIds.length===0
 const [tab,setTab]=useState<'alarms'|'faults'|'confirmed-fire'>('alarms'),[selected,setSelected]=useState<Alarm|null>(null)
 const alarms=useResource(`duty-workbench-stats:${scopeKey}`,signal=>api.alarmWorkbench({keyword:'',physical_state:null,response_state:null,conclusion:null,occurred_from:null,occurred_to:null},1,1,signal,session.scopeTenantIds,session.scopePlaceIds),session.scopeInitialized&&!empty&&session.permissions.includes('alarm:read'))
 const faults=useResource(`duty-workbench-fault-stats:${scopeKey}`,signal=>queryFaults(1,'','','',signal,{activeOnly:true}),session.scopeInitialized&&!empty&&session.permissions.includes('device:read'))
 const fire=useResource(`duty-workbench-fire:${scopeKey}`,signal=>api.alarmWorkbench({keyword:'',physical_state:'FIRE',response_state:null,conclusion:'TRUE_FIRE',occurred_from:null,occurred_to:null},1,20,signal,session.scopeTenantIds,session.scopePlaceIds),tab==='confirmed-fire'&&session.scopeInitialized&&!empty&&session.permissions.includes('alarm:read'))
 const eventRevision=useObjectRevision('events'), previousEventRevision=useRef(eventRevision)
 useEffect(()=>{if(previousEventRevision.current===eventRevision)return;previousEventRevision.current=eventRevision;alarms.refresh();faults.refresh();fire.refresh()},[eventRevision,alarms.refresh,faults.refresh,fire.refresh])
 const tabs=[['alarms','报警'],['faults','故障'],...(session.audience==='management'?[['confirmed-fire','真实火警'] as const]:[])] as const
 return <>
  {tab==='confirmed-fire'&&<PageHeading eyebrow="DUTY · WORKBENCH" title="值守工作台" description="查看报警、报告故障与真实火警复核。"><PageHelp title="工作台范围"><div className="form-stack"><p>本工作台不提供领取、独占工单或物理设备控制。</p><p>人工处置、物理状态与复核结论分别呈现；人工处理不代表设备恢复。</p></div></PageHelp></PageHeading>}
  {!empty&&<nav className="section-switcher" aria-label="值守工作台类别">{tabs.map(([id,label])=><Button type="button" key={id} variant={tab===id?'default':'outline'} aria-pressed={tab===id} onClick={()=>setTab(id)}>{label}</Button>)}</nav>}
  <details className="secondary-controls"><summary>全范围汇总快照</summary><div className="secondary-controls-body">
  <p className="muted-note">汇总独立于下方筛选与处置查询；需要最新汇总时请刷新。</p>
  <Button type="button" variant="outline" disabled={alarms.loading||faults.loading} onClick={()=>{alarms.refresh();faults.refresh()}}>{alarms.loading||faults.loading?'正在刷新汇总…':'刷新汇总'}</Button>
  <section className="device-workbench-metrics"><div><span>总报警（全匹配页）</span><strong>{alarms.data?.metrics.alarm_total??'—'}</strong></div><div><span>未处理报警</span><strong>{alarms.data?.metrics.unhandled??'—'}</strong></div><div><span>已处理报警</span><strong>{alarms.data?.metrics.handled??'—'}</strong></div><div><span>当前真实火警复核</span><strong>{alarms.data?.metrics.true_fire??'—'}</strong></div><div><span>当前故障事件总数</span><strong>{faults.data?.total??'—'}</strong></div></section>
  {alarms.error!=null&&<ErrorNotice error={alarms.error} retry={alarms.refresh}/>}
  {faults.error!=null&&<ErrorNotice error={faults.error} retry={faults.refresh}/>}
  {(alarms.loading||faults.loading)&&!alarms.data&&!faults.data&&<Loading/>}
  </div></details>
  {empty&&<Empty title="当前范围未选择"/>}
  {!empty&&tab==='alarms'&&<AlarmsPage/>}
  {!empty&&tab==='faults'&&<FaultHistoryPage workbench/>}
  {!empty&&tab==='confirmed-fire'&&(fire.loading?<Loading/>:fire.error!=null?<ErrorNotice error={fire.error} retry={fire.refresh}/>:!fire.data?<Empty title="真实火警数据尚未确认"/>:<Card><CardContent><h2>当前复核为真实火警的 FIRE 报警</h2><p className="muted-note">只按真实复核结论和物理 FIRE 同时匹配；人工复核不代表物理恢复。{fire.data.metrics.true_fire} 条符合当前筛选。</p>{fire.data.items.length?<AlarmTable items={fire.data.items} actions={alarm=><Button type="button" variant="outline" size="sm" onClick={()=>setSelected(alarm)}>查看与复核</Button>}/>:<Empty title="当前没有匹配的真实火警"/>}</CardContent></Card>)}
  <AlarmReview alarm={selected} onClose={()=>setSelected(null)} onReviewed={()=>publishObjectChange('events')}/>
 </>
}
