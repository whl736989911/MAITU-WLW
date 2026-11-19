import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { queryDutyAnalytics } from '@/lib/duty-api'
import { dateTime } from '@/lib/format'
import { alarmTimeText } from '@/lib/alarm-time'
import type { Alarm } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { DashboardBars, DashboardLineChart, DashboardPanel } from '@/components/dashboard-charts'

type Prefs = { sound: boolean; refreshSeconds: number }
const PREF_KEY = 'fire-platform.wall.preferences.v1'
function readPrefs(): Prefs {
  try {
    const raw = localStorage.getItem(PREF_KEY)
    if (raw) { const parsed = JSON.parse(raw) as Partial<Prefs>; return { sound: parsed.sound === true, refreshSeconds: typeof parsed.refreshSeconds === 'number' && parsed.refreshSeconds >= 5 ? Math.min(parsed.refreshSeconds, 120) : 15 } }
  } catch { /* Corrupt preferences must not enable sound. */ }
  return { sound: false, refreshSeconds: 15 }
}

export function WallPage() {
  const session = useSession(), { user, audience, permissions } = session
  if (!user || user.must_change_password || audience !== 'management' || !permissions.includes('summary:read')) return <Empty title="当前账户无大屏权限" />
  return <WallContent key={JSON.stringify([session.generation, audience, session.scopeTenantIds, session.scopePlaceIds, session.scopeInitialized, permissions])} />
}

function WallContent() {
  const session = useSession(), scopeTenantIds = session.scopeTenantIds, scopePlaceIds = session.scopePlaceIds
  const scopeEmpty = session.scopeInitialized && scopeTenantIds.length === 0 && scopePlaceIds.length === 0
  const scopeKey = `${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}:${session.scopeInitialized}`
  const canReadAlarms = session.permissions.includes('alarm:read')
  const [prefs, setPrefs] = useState<Prefs>(readPrefs), [days, setDays] = useState<7 | 30>(7), [page, setPage] = useState(1)
  const [physical, setPhysical] = useState<'' | 'FIRE' | 'NORMAL'>('FIRE'), [response, setResponse] = useState<'' | 'UNHANDLED' | 'HANDLED'>('')
  const [refreshedAt, setRefreshedAt] = useState<string | null>(null), [online, setOnline] = useState(navigator.onLine), [speechNote, setSpeechNote] = useState(''), [fullScreen, setFullScreen] = useState(document.fullscreenElement === document.documentElement)
  const spoken = useRef<Set<string>>(new Set())
  const confirmedLoads = useRef<{ summary: Date; analytics: Date; alarms?: Date } | null>(null)
  const summary = useResource(`wall-summary:${scopeKey}`, signal => api.summary(signal, scopeTenantIds, scopePlaceIds), session.scopeInitialized && !scopeEmpty)
  const analytics = useResource(`wall-analytics:${scopeKey}:${days}`, signal => queryDutyAnalytics(days, signal), session.scopeInitialized && !scopeEmpty)
  const alarms = useResource(`wall-alarms:${scopeKey}:${page}:${physical}:${response}`, signal => api.alarmWorkbench({ keyword: '', physical_state: physical || null, response_state: response || null, conclusion: null, occurred_from: null, occurred_to: null }, page, 8, signal, scopeTenantIds, scopePlaceIds), session.permissions.includes('alarm:read') && session.scopeInitialized && !scopeEmpty)
  function savePrefs(next: Prefs) {
    setPrefs(next)
    try { localStorage.setItem(PREF_KEY, JSON.stringify(next)) } catch { setSpeechNote('浏览器拒绝保存偏好；当前设置仅保留在本页') }
  }
  const refreshWall = useCallback(() => { summary.refresh(); analytics.refresh(); alarms.refresh() }, [summary.refresh, analytics.refresh, alarms.refresh])
  useEffect(() => { const timer = setInterval(refreshWall, prefs.refreshSeconds * 1000); return () => clearInterval(timer) }, [prefs.refreshSeconds, refreshWall])
  useEffect(() => { const update = () => setOnline(navigator.onLine); window.addEventListener('online', update); window.addEventListener('offline', update); return () => { window.removeEventListener('online', update); window.removeEventListener('offline', update) } }, [])
  useEffect(() => { const update = () => setFullScreen(document.fullscreenElement === document.documentElement); document.addEventListener('fullscreenchange', update); return () => document.removeEventListener('fullscreenchange', update) }, [])
  useEffect(() => {
    if (!session.scopeInitialized || scopeEmpty || summary.loading || analytics.loading || canReadAlarms && alarms.loading ||
      summary.error != null || analytics.error != null || canReadAlarms && alarms.error != null ||
      !summary.data || !analytics.data || !summary.loadedAt || !analytics.loadedAt || canReadAlarms && (!alarms.data || !alarms.loadedAt)) return
    const previous = confirmedLoads.current
    if (previous && (previous.summary === summary.loadedAt || previous.analytics === analytics.loadedAt || canReadAlarms && previous.alarms === alarms.loadedAt)) return
    confirmedLoads.current = { summary: summary.loadedAt, analytics: analytics.loadedAt, alarms: canReadAlarms ? alarms.loadedAt : undefined }
    setRefreshedAt(new Date().toISOString())
  }, [summary.data, analytics.data, alarms.data, summary.loadedAt, analytics.loadedAt, alarms.loadedAt, summary.loading, analytics.loading, alarms.loading, summary.error, analytics.error, alarms.error, session.scopeInitialized, scopeEmpty, canReadAlarms])
  useEffect(() => {
    if (!prefs.sound || alarms.loading || alarms.error != null) return
    const items = alarms.data?.items ?? []
    if (typeof window.speechSynthesis === 'undefined') { setSpeechNote('浏览器不支持语音播报'); return }
    for (const alarm of items) {
      if (alarm.response_state !== 'UNHANDLED' || alarm.physical_state !== 'FIRE' || spoken.current.has(alarm.id)) continue
      spoken.current.add(alarm.id)
      try { const utterance = new SpeechSynthesisUtterance(`火警 ${alarm.device_name ?? alarm.device_id}`); utterance.lang = 'zh-CN'; window.speechSynthesis.speak(utterance); setSpeechNote('已发起语音播报（不代表已送达或已处置）') }
      catch { setSpeechNote('语音播报失败；请检查浏览器支持和权限') }
    }
  }, [alarms.data, alarms.loading, alarms.error, prefs.sound])
  const stale = refreshedAt !== null && Date.now() - Date.parse(refreshedAt) > prefs.refreshSeconds * 2000
  const refreshing = summary.loading || analytics.loading || canReadAlarms && alarms.loading
  const refreshFailed = summary.error != null || analytics.error != null || canReadAlarms && alarms.error != null
  const onlineDevices = summary.data?.online, offline = summary.data?.offline, inactive = summary.data?.unactivated
  const statusPoints = onlineDevices == null || offline == null || inactive == null ? [] : [{ label: '在线', value: onlineDevices }, { label: '离线策略命中', value: offline }, { label: '未激活 / 未上报', value: inactive }]
  async function toggleFullScreen() {
    try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen(); setFullScreen(Boolean(document.fullscreenElement)) }
    catch { setSpeechNote('全屏模式不可用；可继续使用普通大屏布局') }
  }
  return <div className="wall-dashboard">
    <PageHeading eyebrow="WALL · 大屏" title="值守大屏" description="授权范围内的实时设备与待处理报警。"><PageHelp title="大屏展示口径"><div className="form-stack"><p>仅查询当前已确认的授权单位与位置范围；范围为空时不扩大为全量数据。</p><p>当前设备状态为策略快照。报警趋势排除明确缺失来源发生时间的新记录；旧历史时间保留但来源未核验。来源故障按观察时间统计，未知时间单独计数，不以接收时间替代。</p><p>页面自动刷新与语音偏好仅在本浏览器生效；不构成无漏报或通知送达保证。语音仅针对用户可见的未处理记录，不发出物理设备命令。</p></div></PageHelp></PageHeading>
    <div className="wall-toolbar">
      <span role="status" className="muted-note">最近完整刷新：{refreshedAt ? dateTime(refreshedAt) : '尚未加载'} · 每 {prefs.refreshSeconds} 秒刷新</span>
      {refreshing && <Badge variant="outline">正在刷新</Badge>}{refreshFailed && <Badge variant="outline">部分数据读取失败</Badge>}
      {!online && <Badge variant="outline">浏览器离线</Badge>}{stale && <Badge variant="outline">数据已陈旧（超过两个刷新周期）</Badge>}
      <Button type="button" variant="outline" size="sm" onClick={refreshWall}>立即刷新</Button>
      <Button type="button" variant="outline" size="sm" onClick={() => savePrefs({ ...prefs, sound: !prefs.sound })}>{prefs.sound ? '关闭语音播报' : '启用语音播报'}</Button>
      <Button type="button" variant="outline" size="sm" disabled={prefs.refreshSeconds <= 5} onClick={() => savePrefs({ ...prefs, refreshSeconds: Math.max(5, prefs.refreshSeconds - 5) })}>加快刷新</Button>
      <Button type="button" variant="outline" size="sm" onClick={() => savePrefs({ ...prefs, refreshSeconds: Math.min(120, prefs.refreshSeconds + 5) })}>放慢刷新</Button>
      <Button type="button" variant="outline" size="sm" onClick={toggleFullScreen}>{fullScreen ? '退出全屏' : '全屏显示'}</Button>
    </div>
    <p role="status" className="muted-note">语音播报：{prefs.sound ? '已启用' : '已关闭（默认关闭）'}{speechNote ? ` · ${speechNote}` : ''}</p>
    <div className="wall-filter-bar"><div role="group" aria-label="大屏趋势时间范围">{([7, 30] as const).map(n => <Button key={n} type="button" variant={days === n ? 'default' : 'outline'} onClick={() => { setDays(n); setPage(1) }}>近{n}天</Button>)}</div>
      <label>物理状态<select className="native-select" value={physical} onChange={event => { setPhysical(event.target.value as typeof physical); setPage(1) }}><option value="">全部</option><option value="FIRE">火警</option><option value="NORMAL">已恢复</option></select></label>
      <label>处置状态<select className="native-select" value={response} onChange={event => { setResponse(event.target.value as typeof response); setPage(1) }}><option value="">全部</option><option value="UNHANDLED">待处理</option><option value="HANDLED">已处理</option></select></label>
    </div>
    {scopeEmpty && <Empty title="当前范围未选择">清空单位 / 位置树后不查询业务数据，也不扩大到全部单位。</Empty>}
    {!session.scopeInitialized && <Loading />}
    {summary.error != null && <ErrorNotice error={summary.error} retry={summary.refresh} />}
    {analytics.error != null && <ErrorNotice error={analytics.error} retry={analytics.refresh} />}
    {summary.data && <section className="dashboard-stat-strip" aria-label="设备与报警统计"><div><span>设备总数</span><strong>{summary.data.device_total ?? '未知'}</strong></div><div><span>在线</span><strong>{summary.data.online ?? '未知'}</strong></div><div><span>离线策略命中</span><strong>{summary.data.offline ?? '未知'}</strong></div><div><span>未激活 / 未上报</span><strong>{summary.data.unactivated ?? '未知'}</strong></div><div><span>今日报警</span><strong>{summary.data.today_alarms ?? '未知'}</strong></div><div><span>未处理报警</span><strong>{summary.data.unhandled_alarms ?? '未知'}</strong></div></section>}
    <DashboardPanel title="最新报警">
      {!session.permissions.includes('alarm:read') ? <Empty title="无报警查看权限" /> : scopeEmpty ? null : alarms.loading ? <Loading /> : alarms.error != null ? <ErrorNotice error={alarms.error} retry={alarms.refresh} /> : alarms.data?.items.length ? <>
        <p className="muted-note">报警列表不受趋势窗口限制，包含来源发生时间未知的在用报警；当前筛选全部匹配 {alarms.data.total} 条 · 页 {alarms.data.page} / {Math.max(1, Math.ceil(alarms.data.total / alarms.data.size))}</p>
        <ul className="dashboard-wall-alarms">{alarms.data.items.map((alarm: Alarm) => <li key={alarm.id}><strong>{alarm.device_name ?? alarm.device_id}</strong><span>{alarm.physical_state === 'FIRE' ? '火警' : '已恢复'} · {alarm.response_state === 'UNHANDLED' ? '待处理' : '已处理'}</span><time>{alarmTimeText(alarm)}</time><small>平台登记：{alarm.recorded_at ? dateTime(alarm.recorded_at) : '历史未记录，不推算'}</small></li>)}</ul>
        <Pager page={alarms.data.page} size={alarms.data.size} total={alarms.data.total} onChange={setPage} />
      </> : <Empty title="当前状态筛选暂无报警" />}
    </DashboardPanel>
    {analytics.data && <p className="muted-note">观察时间未知的历史故障 {analytics.data.untimed_fault_events} 条，单独统计且不纳入所选窗口逐日趋势；不会以接收时间代替事件时间。</p>}
    {analytics.data && <section className="wall-dashboard-grid">
      <DashboardPanel title="授权范围设备状态">{statusPoints.length ? <DashboardBars label="设备在线离线分布" points={statusPoints} unit=" 台" /> : <p className="muted-note">至少一类设备状态未知，不能绘制状态总量图。</p>}<p className="muted-note">当前策略状态快照，不表示统计窗口的离线事件总数。</p></DashboardPanel>
      <DashboardPanel title="报警逐日趋势"><DashboardLineChart label={`${days}天带历史发生时间的报警趋势 UTC`} points={analytics.data.alarms_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 条" /><p className="muted-note">按已存发生时间的UTC日期统计；旧记录时间来源未核验。明确缺少来源发生时间的新报警不入趋势，但仍显示在风险列表。</p></DashboardPanel>
      <DashboardPanel title="报告故障类别与趋势"><DashboardBars label="故障类别统计" points={analytics.data.fault_types.map(row => ({ label: `${row.label} · ${row.code ?? '无故障码'}`, value: row.count }))} unit=" 次" /><DashboardLineChart label={`${days}天有观察时间的来源故障 UTC 日趋势`} points={analytics.data.faults_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 次" /><p className="muted-note">窗口趋势仅含带观察时间的 FAULT；观察时间未知的历史故障另行计数 {analytics.data.untimed_fault_events} 条，不当作零或任意日期事件。</p></DashboardPanel>
    </section>}
  </div>
}
