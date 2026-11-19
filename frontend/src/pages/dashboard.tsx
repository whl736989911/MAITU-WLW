import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ArrowRight, BellRing, Cable, CircleAlert, CircleCheck, Radio, RefreshCw, ShieldCheck, WifiOff } from 'lucide-react'
import { api } from '@/lib/api'
import { getAlarmChanges } from '@/lib/alarm-changes'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { reported } from '@/lib/format'
import { alarmTimeText } from '@/lib/alarm-time'
import { allowedPage, type Alarm } from '@/lib/types'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { PageHelp } from '@/components/page-tools'
import { AlarmReview } from '@/components/alarm-review'
import { publishObjectChange, useObjectRevision } from '@/lib/object-events'
import { Empty, ErrorNotice, Loading, PageHeading, RefreshButton } from '@/components/common'

type Stat = { label: string; value: ReactNode }
function ServiceCard({ icon, title, description, stats, action, onOpen }: { icon: ReactNode; title: string; description: string; stats: Stat[]; action: string; onOpen: () => void }) {
  return <div className="demo-service-card">
    <div className="service-ic">{icon}</div>
    <div className="service-body"><h3>{title}</h3><p>{description}</p><div className="service-stats">{stats.map(stat => <div key={stat.label}><b>{stat.value}</b><span>{stat.label}</span></div>)}</div></div>
    <Button type="button" className="service-go" onClick={onOpen}>{action}<ArrowRight size={15}/></Button>
  </div>
}

export function Dashboard() {
  const session = useSession(), { audience, permissions, navigate, scopeTenantIds, scopePlaceIds, scopeInitialized } = session
  const management = audience === 'management', scopeKey = `${scopeInitialized}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`
  const summary = useResource(`summary:${scopeKey}`, signal => api.summary(signal, scopeTenantIds, scopePlaceIds), scopeInitialized)
  const ops = useResource(`ops-overview:${scopeKey}`, signal => api.opsOverview(signal, scopeTenantIds, scopePlaceIds), scopeInitialized && management)
  const alarms = useResource(`summary-alarms:${scopeKey}`, signal => api.alarms(1, 6, signal, scopeTenantIds, scopePlaceIds), scopeInitialized && permissions.includes('alarm:read'))
  const [selected, setSelected] = useState<Alarm | null>(null)
  const [catchUp, setCatchUp] = useState<{ pending: number; hasMore: boolean; asOf: string } | null>(null)
  const [soundNote, setSoundNote] = useState(''), [catchNote, setCatchNote] = useState('')
  const revisions = { events: useObjectRevision('events'), devices: useObjectRevision('devices'), organization: useObjectRevision('organization'), notifications: useObjectRevision('notifications') }
  const previousRevisions = useRef(revisions)
  useEffect(() => {
    const previous = previousRevisions.current
    if (previous.events !== revisions.events) alarms.refresh()
    if (previous.events !== revisions.events || previous.devices !== revisions.devices || previous.organization !== revisions.organization) summary.refresh()
    if (previous.events !== revisions.events || previous.devices !== revisions.devices || previous.organization !== revisions.organization || previous.notifications !== revisions.notifications) ops.refresh()
    previousRevisions.current = revisions
  }, [revisions.events, revisions.devices, revisions.organization, revisions.notifications, alarms.refresh, summary.refresh, ops.refresh])
  const data = summary.data, live = ops.data
  async function runCatchUp() {
    try { const changes = await getAlarmChanges({}); setCatchUp({ pending: Number(changes.pending_count), hasMore: changes.has_more, asOf: changes.as_of }) }
    catch (error) { setCatchUp(null); setCatchNote('补拉失败：' + String(error instanceof Error ? error.message : error)) }
  }
  async function testSound() {
    try {
      const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      if (!Ctx) { setSoundNote('浏览器不支持音频输出：声音提醒不可用（已显示，不静默）'); return }
      const ctx = new Ctx(); await ctx.resume(); const ok = ctx.state === 'running'; await ctx.close()
      setSoundNote(ok ? '声音授权成功' : '声音授权被拒绝：提醒仅页面可见（不静默降级）')
    } catch { setSoundNote('声音授权失败：提醒仅页面可见') }
  }
  const refresh = () => { summary.refresh(); ops.refresh(); alarms.refresh() }
  const alarmItems = alarms.data?.items ?? []
  return <>
    <PageHeading eyebrow="OVERVIEW · 运行总览" title="运行总览" description="待处理报警、设备状态与接入积压。">
      <PageHelp title="数据与处置边界" label="数据说明"><div className="form-stack"><p><CircleCheck size={15}/> 人工复核或处置不自动恢复设备物理状态。</p><p><WifiOff size={15}/> 离线不等于无火警；未上报不等于正常。</p><p><CircleAlert size={15}/> 通知仍受厂商、资源和屏蔽边界约束。</p></div></PageHelp>
      <Button type="button" variant="outline" size="sm" onClick={() => void runCatchUp()}><RefreshCw size={14}/>立即补拉</Button>
      <Button type="button" variant="outline" size="sm" onClick={() => void testSound()}><BellRing size={14}/>测试声音</Button>
      <RefreshButton onClick={refresh}/>
    </PageHeading>
    {summary.error != null && <ErrorNotice error={summary.error} retry={summary.refresh}/>}
    {ops.error != null && <ErrorNotice error={ops.error} retry={ops.refresh}/>}
    <section className="dashboard-stat-strip" aria-label="当前风险摘要">
      <div><span>未处理报警</span><strong>{summary.loading ? '…' : reported(live?.alarms.unhandled ?? data?.unhandled_alarms)}</strong></div>
      <div><span>{management ? '当前火警' : '今日报警'}</span><strong>{summary.loading ? '…' : reported(management ? live?.alarms.fire : data?.today_alarms)}</strong></div>
      <div><span>离线策略命中</span><strong>{reported(data?.offline)}</strong></div>
      <div><span>低电量设备</span><strong>{reported(data?.low_battery)}</strong></div>
      {management && <div><span>待处理接入任务</span><strong>{reported(live?.tasks.raw_message_pending)}</strong></div>}
    </section>
    <div className="demo-workbench">
      <aside className="demo-side">
        <Card><CardContent>
          <div className="demo-side-head"><h3>最新报警</h3><Button variant="ghost" size="sm" onClick={() => navigate('alarms')}>全部<ArrowRight size={13}/></Button></div>
          {!permissions.includes('alarm:read') ? <Empty title="无报警查看权限"/> : alarms.loading ? <Loading/> : alarms.error != null ? <ErrorNotice error={alarms.error} retry={alarms.refresh}/> : alarmItems.length ? <div className="demo-alarm-feed">{alarmItems.map(alarm => <button key={alarm.id} type="button" className="demo-alarm-item" onClick={() => setSelected(alarm)}><span className={`alarm-dot ${alarm.event_type === 'FIRE' ? 'red' : 'green'}`}/><span><strong>{alarm.event_type === 'FIRE' ? '火灾报警' : '事件'}</strong><small>{reported(alarm.device_name)} · {reported(alarm.address)}</small><em>{alarmTimeText(alarm)}</em></span></button>)}</div> : <Empty title="暂无报警记录"/>}
        </CardContent></Card>
        {(catchUp || catchNote || soundNote) && <Card><CardContent>
          <div className="demo-side-head"><h3>补拉与提醒操作</h3><span className="muted">本浏览器 / 当前会话</span></div>
          {catchUp && <p role="status" className="notice">补拉结果：待处理 {catchUp.pending} · 还有更多 {catchUp.hasMore ? '是' : '否'} · {catchUp.asOf}</p>}
          {catchNote && <p role="status" className="notice">{catchNote}</p>}
          {soundNote && <p role="status" className="notice">{soundNote}</p>}
        </CardContent></Card>}
      </aside>
      <div className="demo-service-list">
        {allowedPage('alarms', audience, permissions) && <ServiceCard icon={<BellRing size={22}/>} title="监控值守" description="查看报警与人工处置情况" action="进入报警中心" onOpen={() => navigate('alarms')} stats={[
          { label: '未处理报警', value: summary.loading ? '…' : reported(live?.alarms.unhandled ?? data?.unhandled_alarms) },
          ...(management ? [
            { label: '当前火警', value: reported(live?.alarms.fire) },
            { label: '最早待处理时间', value: live ? live.alarms.oldest_unhandled_at ? <small>{live.alarms.oldest_unhandled_at}（含时间来源未核验的历史记录）</small> : live.alarms.unhandled > 0 ? '来源发生时间未知' : '无待处理报警' : '未确认' },
          ] : []),
          { label: '今日报警', value: reported(data?.today_alarms) },
        ]}/>}
        <ServiceCard icon={<Radio size={22}/>} title="设备与资源" description="设备连接、物理状态与资源分布" action="进入设备监测" onOpen={() => navigate('devices')} stats={[
          { label: '设备', value: reported(data?.device_total) },
          { label: '在线', value: reported(data?.online) },
          { label: '离线', value: reported(data?.offline) },
          { label: '未激活', value: reported(data?.unactivated) },
          { label: '上报超时，离线未确认', value: reported(data?.unknown_connectivity) },
        ]}/>
        {allowedPage('messages', audience, permissions) && <ServiceCard icon={<Cable size={22}/>} title="接入与消息" description="查看消息接收、解析及处理积压" action="进入消息追踪" onOpen={() => navigate('messages')} stats={[
          { label: '启用来源', value: reported(live?.sources.enabled) },
          { label: '待处理报文', value: reported(live?.tasks.raw_message_pending) },
          { label: '重解析', value: reported(live?.tasks.reparse_pending) },
          { label: '未发布变更', value: reported(live?.channel.pending) },
        ]}/>}
        {allowedPage('notification-deliveries', audience, permissions) && <ServiceCard icon={<ShieldCheck size={22}/>} title="通知与规则" description="查看短信与语音通知的真实受理情况" action="查看发送记录" onOpen={() => navigate('notification-deliveries')} stats={[
          { label: '未处理报警', value: reported(live?.alarms.unhandled) },
          { label: '低电量设备', value: reported(data?.low_battery) },
          { label: '最近接收', value: live?.sources.last_received_at ? <small>{live.sources.last_received_at}</small> : '—' },
        ]}/>}
      </div>
    </div>
    <p className="muted-note">设备摘要最近读取：{summary.loadedAt?.toLocaleTimeString('zh-CN', { hour12: false }) ?? '尚未成功读取'}</p>
    <AlarmReview alarm={selected} onClose={() => setSelected(null)} onReviewed={() => publishObjectChange('events')}/>
  </>
}
