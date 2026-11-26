import { useState,type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { compileDeviceDraft,emptyDeviceDraft,emptyDeviceFilters,getDeviceWorkbench,type DeviceDraft,type DeviceFilters } from '@/lib/device-workbench'
import { DeviceTable } from '@/components/data-tables'
import { Card,CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
const labels=[['device_total','设备总数'],['online','近期有上报'],['offline','有心跳依据的超时'],['unactivated','未上报'],['unknown_connectivity','上报超时，链路未确认'],['fire','来源火情：火警'],['normal','来源火情：正常'],['unknown','来源火情未确认'],['battery_low','电量阈值低']] as const
export function DeviceMonitoringPage(){
 const session=useSession(),scopeKey=`${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`,empty=session.scopeInitialized&&session.scopeTenantIds.length===0&&session.scopePlaceIds.length===0
 const [draft,setDraft]=useState<DeviceDraft>({...emptyDeviceDraft}),[applied,setApplied]=useState<DeviceFilters>({...emptyDeviceFilters}),[page,setPage]=useState(1),[filterError,setFilterError]=useState('')
 const result=useResource(`device-monitoring:${scopeKey}:${page}:${JSON.stringify(applied)}`,signal=>getDeviceWorkbench(applied,page,undefined,signal),session.scopeInitialized&&!empty&&session.permissions.includes('device:read'))
 function update(key:keyof DeviceDraft,value:string){setDraft(old=>({...old,[key]:value}))}
 function submit(e:FormEvent){e.preventDefault();try{setApplied(compileDeviceDraft(draft));setPage(1);setFilterError('')}catch(error){setFilterError(String(error))}}
 const data=result.data
 const management = session.audience === 'management'
 const refresh = <Button type="button" variant="outline" onClick={result.refresh}>刷新设备监测</Button>
 const actions = <>
  <Button type="submit">应用监测筛选</Button>
  <Button type="button" variant="outline" onClick={()=>{setDraft({...emptyDeviceDraft});setApplied({...emptyDeviceFilters});setPage(1);setFilterError('')}}>清空</Button>
 </>
 const filters = <>
    <form className={management ? 'filter-row management-named-filters' : 'device-workbench-grid'} onSubmit={submit}>
     <div className="field"><Label htmlFor="monitor-keyword">设备关键字</Label><Input id="monitor-keyword" value={draft.keyword} onChange={e=>update('keyword',e.target.value)}/></div>
     <div className="field"><Label htmlFor="monitor-connection">链路诊断</Label><select id="monitor-connection" className="native-select" value={draft.connectivity} onChange={e=>update('connectivity',e.target.value)}><option value="">全部</option>{['ONLINE','OFFLINE','UNACTIVATED','UNKNOWN'].map(v=><option key={v}>{v}</option>)}</select></div>
     <div className="field"><Label htmlFor="monitor-physical">来源火情</Label><select id="monitor-physical" className="native-select" value={draft.physical_state} onChange={e=>update('physical_state',e.target.value)}><option value="">全部</option>{['FIRE','NORMAL','UNKNOWN'].map(v=><option key={v}>{v}</option>)}</select></div>
     <div className="field"><Label htmlFor="monitor-system">系统类型（精确匹配）</Label><Input id="monitor-system" maxLength={80} value={draft.system_type} onChange={e=>update('system_type',e.target.value)} placeholder="留空=全部；未知仍为空值"/></div>
     <div className="field"><Label htmlFor="monitor-category">设备类别（精确匹配）</Label><Input id="monitor-category" maxLength={80} value={draft.device_category} onChange={e=>update('device_category',e.target.value)} placeholder="留空=全部；未知仍为空值"/></div>
     {management ? <div className="management-query-actions">{actions}</div> : actions}
    </form>
    {filterError&&<p className="notice" role="alert">{filterError}</p>}
 </>
 const rows = data && <>
  <p className={management ? 'management-list-context' : 'muted-note'}>服务端求值 {data.as_of}；匹配总量 {data.total}。</p>
  {data.items.length?<DeviceTable items={data.items}/>:<Empty title="当前监测条件无匹配设备"/>}
  <Pager page={data.page} size={data.size} total={data.total} onChange={setPage}/>
 </>
 const results = empty?<Empty title="当前范围未选择"/>:result.loading?<Loading/>:result.error!=null?<ErrorNotice error={result.error} retry={result.refresh}/>:!data?<Empty title="设备监测尚未确认"/>:<>
  <section className="device-workbench-metrics">{labels.map(([key,label])=><div key={key}><span>{label}</span><strong>{data.metrics[key]}</strong></div>)}</section>
  {management ? rows : <Card><CardContent>{rows}</CardContent></Card>}
 </>
 return <>
  <PageHeading eyebrow="DUTY · DEVICE MONITORING" title="设备监测" description="链路诊断、来源火情与电池观测。">
   <PageHelp title="设备监测口径"><p>列表在线状态只按有效上报间隔计算；链路诊断还要求独立心跳依据。来源火情不等于人工处置状态。</p></PageHelp>
   {!management && refresh}
  </PageHeading>
  {management ? <Card className="list-card management-list-panel"><CardContent>
   <div className="management-list-bar"><h2>设备监测列表</h2><div className="list-toolbar management-list-actions">{refresh}</div></div>
   {filters}
   {results}
  </CardContent></Card> : <><Card><CardContent>{filters}</CardContent></Card>{results}</>}
 </>
}
