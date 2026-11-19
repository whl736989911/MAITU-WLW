import { useEffect, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { alarmBaseApi, type AlarmBaseValue } from '@/lib/alarm-configuration'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { PageHelp } from '@/components/page-tools'
import { ErrorNotice, Loading, PageHeading } from '@/components/common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { ConfigurationUnitTarget } from '@/components/configuration-unit-target'
import { AlarmMergeSettings } from '@/components/alarm-merge-settings'
import { AlarmScopedRules } from './alarm-base-config/scoped-rules'
import './alarm-base-config.css'

const fields = ['zone','position','eventSource','time','eventType']
const initial: AlarmBaseValue = {
  subscriptions:{FIRE:{},FAULT:{}}, pushRules:{periodHours:24},
  voiceBroadcast:{alarmEnabled:false,alarmTypes:[],alarmFields:fields,faultEnabled:false,faultTypes:[],faultFields:fields,continuous:false},
  unitService:{receiveAlarm:true,pushMessage:true},
  silenceIntervals:{alarmMinutes:0,faultMinutes:0},
}
const tabs = [['subscriptions','报警/故障订阅'],['push','报警/故障推送规则'],['merge','报警合并'],['voice','语音播报'],['service','单位服务'],['silence','其他配置']] as const
export function AlarmBaseConfigurationPage(){
  const permissions=useSession(s=>s.permissions)
  if(!permissions.includes('alarm-base:read'))return <><PageHeading eyebrow="CONFIGURATION · 本地策略" title="报警基础配置" description="订阅及通知策略影响软件通知出口；不修改报警事实、物理状态、人工处置或工作台可见性。"/><ErrorNotice error={new Error('当前账户没有报警基础配置读取权限')}/></>
  return <AlarmBaseConfigurationWorkspace canWrite={permissions.includes('alarm-base:write')}/>
}
function AlarmBaseConfigurationWorkspace({canWrite}:{canWrite:boolean}){
  const [view,setView]=useState<'unit'|'scoped'>('unit')
  return <section className="alarm-base-workspace">
    <PageHeading eyebrow="CONFIGURATION · 本地策略" title="报警基础配置" description="维护单位通知策略，以及 FIRE/FAULT 在平台、位置和设备范围的继承与覆盖。"><PageHelp title="配置作用范围"><div className="form-stack"><p>配置影响后续软件通知策略，不修改报警事实、设备物理状态或人工处置状态。</p><p>位置和设备覆盖按真实目标读取；位置子树授权、会话和版本仍由服务端逐次核验。</p><p>单位通知开关不会隐藏工作台中的真实报警；没有故障事实源时，FAULT 规则不会创建故障。</p></div></PageHelp></PageHeading>
    <div className="alarm-base-view-tabs" role="tablist" aria-label="报警配置范围">
      <Button id="alarm-base-unit-tab" type="button" role="tab" aria-selected={view==='unit'} aria-controls="alarm-base-unit-panel" variant={view==='unit'?'default':'outline'} onClick={()=>setView('unit')}>单位基础配置</Button>
      <Button id="alarm-base-scoped-tab" type="button" role="tab" aria-selected={view==='scoped'} aria-controls="alarm-base-scoped-panel" variant={view==='scoped'?'default':'outline'} onClick={()=>setView('scoped')}>FIRE/FAULT分层规则</Button>
    </div>
    <div id="alarm-base-unit-panel" role="tabpanel" aria-labelledby="alarm-base-unit-tab" hidden={view!=='unit'}><ConfigurationUnitTarget>{tenantId => <AlarmBaseConfigurationContent key={tenantId} tenantId={tenantId} canWrite={canWrite}/>}</ConfigurationUnitTarget></div>
    <div id="alarm-base-scoped-panel" role="tabpanel" aria-labelledby="alarm-base-scoped-tab" hidden={view!=='scoped'}><AlarmScopedRules canWrite={canWrite}/></div>
  </section>
}
function AlarmBaseConfigurationContent({canWrite,tenantId}:{canWrite:boolean;tenantId:string}){
  const resource=useResource(`alarm-base-configuration:${tenantId}`,signal=>alarmBaseApi.get(signal,tenantId))
  const [value,setValue]=useState<AlarmBaseValue>(initial),[tab,setTab]=useState<(typeof tabs)[number][0]>('subscriptions')
  const [reason,setReason]=useState(''),action=useMutationAction()
  useEffect(()=>{if(resource.data)setValue(resource.data.value)},[resource.data?.revision])
  function update<T extends keyof AlarmBaseValue>(key:T,next:AlarmBaseValue[T]){setValue(old=>({...old,[key]:next}))}
  function save(event:FormEvent){event.preventDefault();if(!reason.trim()){action.setError(new Error('必须说明配置变更原因'));return}if(!action.confirming){action.setConfirming(true);return}void action.run(async(key,signal)=>{const result=await alarmBaseApi.save({expected_revision:resource.data?.revision??0,reason:reason.trim(),value},key,signal,tenantId);setValue(result.value);setReason('')},()=>{action.setConfirming(false);resource.refresh()})}
  const service=value.unitService
  return <>
    <div className="list-toolbar" role="tablist" aria-label="报警基础配置页签">{tabs.map(([key,title])=><Button key={key} type="button" role="tab" aria-selected={tab===key} variant={tab===key?'default':'outline'} onClick={()=>setTab(key)}>{title}</Button>)}</div>
    {tab==='merge'?<AlarmMergeSettings tenantId={tenantId} canWrite={canWrite}/>:resource.loading?<Loading/>:resource.error!=null?<ErrorNotice error={resource.error} retry={resource.refresh}/>:<form onSubmit={save}>
      {tab==='subscriptions'&&<Card><CardContent><h2>报警/故障订阅</h2><p className="muted-note">列表内容是按设备系统型号填写的排除事件代码；空列表表示订阅该系统当前实际存在的事件。当前本地报警事实源仅能生成 FIRE；FAULT 配置会用于真实已存储故障证据，不会造出故障。</p>{(['FIRE','FAULT'] as const).map(event=> <div className="field" key={event}><Label htmlFor={`subscribe-${event}`}>{event==='FIRE'?'报警订阅':'故障订阅'} · 排除事件代码</Label><textarea id={`subscribe-${event}`} className="native-select" rows={4} value={Object.entries(value.subscriptions[event]).map(([system,codes])=>`${system}:${codes.join(',')}`).join('\n')} onChange={e=>{const map:Record<string,string[]>={};for(const line of e.target.value.split('\n').filter(Boolean)){const [system,codes='']=line.split(':',2);if(system)map[system.trim()]=codes.split(',').map(x=>x.trim()).filter(Boolean)}update('subscriptions',{...value.subscriptions,[event]:map})}} placeholder="device-model:200040,200070"/></div>)}</CardContent></Card>}
      {tab==='push'&&<Card><CardContent><h2>未恢复报警提醒规则</h2><p className="muted-note">已确认但仍未人工处理的火警会按周期触发现有真实通知引擎的渐进推送；每个周期保留稳定锚点与通道幂等，暂停、处理或关闭单位通知时取消后续提醒。不更改报警事实。</p><div className="field"><Label htmlFor="push-period">推送周期（小时）</Label><Input id="push-period" type="number" min={1} max={720} value={value.pushRules.periodHours} onChange={e=>update('pushRules',{...value.pushRules,periodHours:Number(e.target.value)})}/></div></CardContent></Card>}
      {tab==='voice'&&<Card><CardContent><h2>语音播报配置</h2><p className="muted-note">匹配真实 FIRE/FAULT 事实后，本地保存播报内容并由工作台提供用户点击朗读；不调用电话或设备扬声器。</p>{(['alarm','fault'] as const).map(kind=>{const enabledKey=kind==='alarm'?'alarmEnabled':'faultEnabled',typesKey=kind==='alarm'?'alarmTypes':'faultTypes',fieldsKey=kind==='alarm'?'alarmFields':'faultFields';return <section className="form-stack" key={kind}><h3>{kind==='alarm'?'报警播报':'故障播报'}</h3><label className="notification-checkbox"><input type="checkbox" checked={value.voiceBroadcast[enabledKey]} onChange={e=>update('voiceBroadcast',{...value.voiceBroadcast,[enabledKey]:e.target.checked})}/>{kind==='alarm'?'启用报警语音内容':'启用故障语音内容'}</label><div className="field"><Label htmlFor={`broadcast-${kind}-types`}>事件代码（逗号分隔）</Label><Input id={`broadcast-${kind}-types`} value={value.voiceBroadcast[typesKey].join(',')} onChange={e=>update('voiceBroadcast',{...value.voiceBroadcast,[typesKey]:e.target.value.split(',').map(x=>x.trim()).filter(Boolean)})}/></div><div className="field"><Label htmlFor={`broadcast-${kind}-fields`}>播报字段</Label>{fields.map(field=><label className="notification-checkbox" key={field}><input type="checkbox" checked={value.voiceBroadcast[fieldsKey].includes(field)} onChange={e=>{const selected=value.voiceBroadcast[fieldsKey].filter(x=>x!==field);if(e.target.checked)selected.push(field);update('voiceBroadcast',{...value.voiceBroadcast,[fieldsKey]:selected})}}/>{({zone:'区域',position:'详细位置',eventSource:'报警源/故障源',time:'发生时间',eventType:'报警/故障类型'} as Record<string,string>)[field]}</label>)}</div></section>})}<label className="notification-checkbox"><input type="checkbox" checked={value.voiceBroadcast.continuous} onChange={e=>update('voiceBroadcast',{...value.voiceBroadcast,continuous:e.target.checked})}/>连续朗读模式（仅本地用户点击后生效）</label></CardContent></Card>}
      {tab==='service'&&<Card><CardContent><h2>单位服务</h2><label className="notification-checkbox"><input type="checkbox" checked={service.receiveAlarm} onChange={e=>update('unitService',{...service,receiveAlarm:e.target.checked,pushMessage:e.target.checked&&service.pushMessage})}/>接收报警</label><label className="notification-checkbox"><input type="checkbox" checked={service.pushMessage} disabled={!service.receiveAlarm} onChange={e=>update('unitService',{...service,pushMessage:e.target.checked})}/>允许通知消息派发</label><div className="notice"><strong>工作台始终展示真实报警</strong><p>本单位通知开关不会隐藏或移除任何报警事实。</p></div></CardContent></Card>}
      {tab==='silence'&&<Card><CardContent><h2>其他配置</h2><p className="muted-note">静音间隔只作用于本地浏览器语音提醒；用户点击静音时按此时长停止当前浏览器语音，恢复定时器到期后可再次点击朗读。不会调用物理设备命令。</p>{(['alarmMinutes','faultMinutes'] as const).map(key=><div className="field" key={key}><Label htmlFor={key}>{key==='alarmMinutes'?'报警语音静音间隔（分钟）':'故障语音静音间隔（分钟）'}</Label><Input id={key} type="number" min={0} max={1440} value={value.silenceIntervals[key]} onChange={e=>update('silenceIntervals',{...value.silenceIntervals,[key]:Number(e.target.value)})}/></div>)}</CardContent></Card>}
      {canWrite&&<Card><CardContent><div className="field"><Label htmlFor="alarm-config-reason">变更原因</Label><Input id="alarm-config-reason" value={reason} onChange={e=>setReason(e.target.value)}/></div>{action.error!=null&&<ErrorNotice error={action.error}/>}<Button type="submit">保存报警基础配置</Button>{action.confirming&&<OperationConfirmation action={action} title="确认保存报警基础配置" submitLabel="确认保存" refresh={resource.refresh} warning="保存会影响后续软件通知策略；不会改写报警事实。"><p>修订号：{resource.data?.revision??0}</p><p>变更原因：{reason}</p></OperationConfirmation>}</CardContent></Card>}
    </form>}</>
}
