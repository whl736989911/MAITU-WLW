import { useState } from 'react'
import { useSession } from '@/lib/store'
import { api, ApiError } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { queryDutyAnalytics } from '@/lib/duty-api'
import { Button } from '@/components/ui/button'
import { Empty, ErrorNotice, Loading, PageHeading } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { DashboardBars, DashboardLineChart, DashboardPanel } from '@/components/dashboard-charts'

function isAuthorizationFailure(error: unknown) { return error instanceof ApiError && (error.status === 401 || error.status === 403) }

export function DutyOverviewPage() {
  const session = useSession(), scopeKey = `${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`
  const empty = session.scopeInitialized && session.scopeTenantIds.length === 0 && session.scopePlaceIds.length === 0
  const [days, setDays] = useState<7 | 30>(7)
  const enabled = session.scopeInitialized && !empty && session.permissions.includes('summary:read')
  const summary = useResource(`duty-overview-summary:${scopeKey}`, signal => api.summary(signal, session.scopeTenantIds, session.scopePlaceIds), enabled)
  const management = session.audience === 'management'
  const ops = useResource(`duty-overview-ops:${scopeKey}`, signal => api.opsOverview(signal, session.scopeTenantIds, session.scopePlaceIds), enabled && management)
  const analytics = useResource(`duty-overview-analytics:${scopeKey}:${days}`, signal => queryDutyAnalytics(days, signal), enabled)
  const authorityError = isAuthorizationFailure(summary.error) ? summary.error : isAuthorizationFailure(ops.error) ? ops.error : isAuthorizationFailure(analytics.error) ? analytics.error : undefined
  const data = !authorityError && !analytics.loading && analytics.error == null ? analytics.data : undefined
  const summaryData = !authorityError && !summary.loading && summary.error == null ? summary.data : undefined
  const opsData = !authorityError && !ops.loading && ops.error == null ? ops.data : undefined
  const online = summaryData?.online, offline = summaryData?.offline, inactive = summaryData?.unactivated
  const statusPoints = online == null || offline == null || inactive == null ? [] : [{ label: '在线（当前策略）', value: online }, { label: '离线（当前策略）', value: offline }, { label: '未激活 / 未上报', value: inactive }]
  function refresh() { summary.refresh(); ops.refresh(); analytics.refresh() }
  return <>
    <PageHeading eyebrow="DUTY · OVERVIEW" title="值守概览" description="授权范围内的设备状态、待处理报警及真实事件趋势。">
      <PageHelp title="值守统计口径"><div className="form-stack"><p>报警按实际发生时间（ALARM_OCCURRED_AT）统计；接收时间不会替代事件时间。</p><p>报告故障仅含已存 FAULT 事实。观察时间未知的事件单独计数，不纳入日趋势。</p><p>当前离线策略命中、明确上报的 OFFLINE 事件、未上报设备彼此独立；设备列表页不会被用来推算总量。</p><p>物理状态、人工处置与来源报告是不同事实；人工处理不代表硬件恢复。</p></div></PageHelp>
      <Button type="button" variant="outline" disabled={!enabled} onClick={refresh}>刷新概览</Button>
    </PageHeading>
    <div className="list-toolbar"><div role="group" aria-label="值守概览时间范围">{([7, 30] as const).map(n => <Button key={n} type="button" disabled={!!authorityError} variant={days === n ? 'default' : 'outline'} onClick={() => setDays(n)}>近{n}天</Button>)}</div>
      {data && <span className="muted-note">事件区间 {data.from} 至 {data.to} · {data.timezone} · 报警按实际发生时间统计</span>}
    </div>
    {empty ? <Empty title="当前范围未选择">清空单位 / 位置范围后不读取或显示其他单位的历史结果。</Empty> : !session.scopeInitialized ? <Loading /> : !enabled ? <Empty title="无概览读取权限" /> : authorityError ? <ErrorNotice error={authorityError} retry={refresh} /> : <>
      <section className="dashboard-stat-strip" aria-label="值守状态">
        <div><span>设备总数</span><strong>{summaryData?.device_total ?? '未知'}</strong></div><div><span>当前在线</span><strong>{summaryData?.online ?? '未知'}</strong></div><div><span>当前离线策略命中</span><strong>{summaryData?.offline ?? '未知'}</strong></div><div><span>未激活 / 未上报</span><strong>{summaryData?.unactivated ?? '未知'}</strong></div><div><span>报警未处理</span><strong>{opsData?.alarms.unhandled ?? summaryData?.unhandled_alarms ?? '未知'}</strong></div>{management && <div><span>待发布变更</span><strong>{opsData?.channel.pending ?? '未知'}</strong></div>}<div><span>观察时间未知的故障（不纳入日趋势）</span><strong>{data?.untimed_fault_events ?? '未知'}</strong></div>
      </section>
      {summary.loading ? <Loading /> : summary.error != null ? <ErrorNotice error={summary.error} retry={summary.refresh} /> : !summaryData ? <Empty title="设备状态摘要尚未确认" /> : null}
      {analytics.loading ? <Loading /> : analytics.error != null ? <ErrorNotice error={analytics.error} retry={analytics.refresh} /> : !data ? <Empty title="历史趋势尚未确认" /> : null}
      {data && <>
      <DashboardPanel title="实际报警趋势">
        <DashboardLineChart label={`${days}天实际报警逐日趋势，UTC`} points={data.alarms_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 条" />
        <p className="muted-note">来源为授权范围内的报警记录，按 ALARM_OCCURRED_AT 对应 UTC 日期聚合。不是报警完整性或实时硬件在线保证。</p>
      </DashboardPanel>
      <section className="duty-ops-grid">
        <DashboardPanel title="已报告故障类型">
          <DashboardBars label="已报告故障类型统计" points={data.fault_types.map(row => ({ label: `${row.label} · ${row.code ?? '无故障码'}`, value: row.count }))} unit=" 条" />
          <DashboardLineChart label={`${days}天报告故障逐日趋势，UTC`} points={data.faults_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 条" />
          <p className="muted-note">完整窗口内的 legacy FAULT 事件按 FaultProjection 解码，空码和未知码分别保留；逐日趋势只含有观察时间且落在窗口内的事件。另有 {data.untimed_fault_events} 条观察时间未知的故障单独计数，不推定为零或归入任意日期。</p>
        </DashboardPanel>
        <DashboardPanel title="报告离线事件趋势">
          <DashboardLineChart label={`${days}天报告离线事件逐日趋势，UTC`} points={data.reported_offline_events_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 条" />
          <p className="muted-note">仅显示存储的 OFFLINE 上报事件，不等同于当前连接策略判断的离线设备。</p>
        </DashboardPanel>
      </section>
      </>}
      <DashboardPanel title="连接状态分布">
        {statusPoints.length ? <DashboardBars label="当前设备连接状态" points={statusPoints} unit=" 台" /> : <p className="muted-note">至少一类设备状态未知，不能绘制状态总量图。</p>}
        <p className="muted-note">设备当前状态摘要由受范围过滤的服务端汇总；不以显示中的设备列表页计算总数。</p>
      </DashboardPanel>
      {management && <details className="secondary-controls"><summary>值守处理与接入明细{ops.error != null ? ' · 读取失败' : ops.loading ? ' · 读取中' : ''}</summary><div className="secondary-controls-body"><DashboardPanel title="值守处理与接入状态">
        {ops.loading ? <Loading /> : ops.error != null ? <ErrorNotice error={ops.error} retry={ops.refresh} /> : !opsData ? <Empty title="值守接入摘要尚未确认" /> : <>
        <dl className="duty-overview-details"><div><dt>待处理报警</dt><dd>{opsData.alarms.unhandled}</dd></div><div><dt>其中物理 FIRE</dt><dd>{opsData.alarms.fire}</dd></div><div><dt>最早未处理发生时间</dt><dd>{opsData.alarms.oldest_unhandled_at ?? '无已知记录'}</dd></div><div><dt>已启用来源</dt><dd>{opsData.sources.enabled}</dd></div><div><dt>最近收到来源事件</dt><dd>{opsData.sources.last_received_at ?? '未知'}</dd></div></dl>
        <p className="muted-note">事件时间与接收时间是不同事实；接入时间不会替代报警发生时间。{opsData.semantics}</p>
        </>}
      </DashboardPanel></div></details>}
    </>}
  </>
}
