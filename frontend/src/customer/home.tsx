import { useState } from 'react'
import { ArrowRight, BellRing, CircleAlert, Radio } from 'lucide-react'
import { AlarmReview } from '@/components/alarm-review'
import { Empty, ErrorNotice, Loading, RefreshButton, Status } from '@/components/common'
import { Button } from '@/components/ui/button'
import { alarmTimeText } from '@/lib/alarm-time'
import { api } from '@/lib/api'
import { publishObjectChange, useObjectRevision } from '@/lib/object-events'
import { useSession } from '@/lib/store'
import { allowedPage, type Alarm } from '@/lib/types'
import { useResource } from '@/lib/use-resource'
import './customer.css'

export function CustomerHome() {
  const { audience, permissions, scopeInitialized, user } = useSession()
  if (audience !== 'customer' || !user?.tenant_id || !scopeInitialized || !allowedPage('summary', audience, permissions)) return <Empty title="当前会话无法读取客户首页"/>
  return <DailyOverview key={user.tenant_id}/>
}

function DailyOverview() {
  const { audience, permissions, navigate } = useSession()
  const [selected, setSelected] = useState<Alarm | null>(null)
  const events = useObjectRevision('events'), devices = useObjectRevision('devices'), organization = useObjectRevision('organization')
  const canAlarms = allowedPage('alarms', audience, permissions)
  const canDevices = allowedPage('devices', audience, permissions)
  const summary = useResource(`customer-home:${events}:${devices}:${organization}`, signal => api.summary(signal))
  const alarms = useResource(`customer-home-alarms:${events}`, signal => api.alarms(1, 6, signal), canAlarms)
  const data = summary.data
  return <div className="customer-hub">
    <header className="customer-page-head">
      <div><span className="customer-kicker">本单位 · 消防运行</span><h1>运行首页</h1></div>
      <RefreshButton onClick={() => { summary.refresh(); if (canAlarms) alarms.refresh() }}/>
    </header>
    {summary.error != null && <ErrorNotice error={summary.error} retry={summary.refresh}/>}
    <div className="customer-home-layout">
      <section className="customer-surface" aria-labelledby="customer-latest-alarms">
        <header className="customer-section-head"><h2 id="customer-latest-alarms"><BellRing size={19} aria-hidden="true"/>最近报警</h2>{canAlarms && <Button type="button" variant="outline" onClick={() => navigate('alarms')}>全部报警<ArrowRight size={16}/></Button>}</header>
        {canAlarms ? <>
          <div className="customer-risk"><span>未处理报警</span><strong data-alert={data?.unhandled_alarms != null && data.unhandled_alarms > 0}>{summary.loading ? '…' : data?.unhandled_alarms ?? '—'}</strong></div>
          {alarms.loading ? <Loading/> : alarms.error != null ? <ErrorNotice error={alarms.error} retry={alarms.refresh}/> : alarms.data?.items.length ? <ul className="customer-records">
            {alarms.data.items.map(alarm => <li key={alarm.id}><button type="button" className="customer-record-button" onClick={() => setSelected(alarm)}>
              <span className="customer-record-icon" data-alert={alarm.physical_state === 'FIRE'}><CircleAlert size={18} aria-hidden="true"/></span>
              <span className="customer-record-label"><strong>{alarm.device_name || '设备名称未记录'}</strong><small>{alarm.address || '位置未记录'} · {alarmTimeText(alarm)}</small></span>
              <span className="customer-alarm-labels"><Status value={alarm.physical_state} dimension="physical"/><Status value={alarm.response_state} dimension="response"/></span>
              <ArrowRight size={16} aria-hidden="true"/>
            </button></li>)}
          </ul> : <Empty title="暂无报警记录"/>}
        </> : <Empty title="当前账号无报警查看权限"/>}
      </section>
      {canDevices && <section className="customer-surface" aria-labelledby="customer-device-summary">
        <header className="customer-section-head"><h2 id="customer-device-summary"><Radio size={19} aria-hidden="true"/>设备概况</h2></header>
        <dl className="customer-metrics">
          {([['设备总数', data?.device_total], ['已报告在线', data?.online], ['离线策略命中', data?.offline], ['超时，离线未确认', data?.unknown_connectivity], ['未激活', data?.unactivated], ['低电量', data?.low_battery]] as const).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{summary.loading ? '…' : value ?? '—'}</dd></div>)}
        </dl>
        <Button type="button" variant="outline" onClick={() => navigate('devices')}>查看设备<ArrowRight size={16}/></Button>
        {summary.loadedAt && <p className="customer-subtle">查询于 {summary.loadedAt.toLocaleTimeString('zh-CN', { hour12: false })}，不是设备上报时间。</p>}
      </section>}
    </div>
    <p className="customer-subtle">复核只记录人工处理结果，不代表设备物理恢复。报警记录不能替代现场核查。</p>
    <AlarmReview alarm={selected} onClose={() => setSelected(null)} onReviewed={() => publishObjectChange('events')}/>
  </div>
}
