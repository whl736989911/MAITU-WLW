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

export function OperationsOverviewPage() {
  const session = useSession(), scopeKey = `${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`
  const empty = session.scopeInitialized && session.scopeTenantIds.length === 0 && session.scopePlaceIds.length === 0
  const [days, setDays] = useState<7 | 30>(7)
  const enabled = session.scopeInitialized && !empty && session.permissions.includes('summary:read')
  const summary = useResource(`ops-detail-summary:${scopeKey}`, signal => api.summary(signal, session.scopeTenantIds, session.scopePlaceIds), enabled)
  const analytics = useResource(`ops-analytics:${scopeKey}:${days}`, signal => queryDutyAnalytics(days, signal), enabled)
  const authorityError = isAuthorizationFailure(summary.error) ? summary.error : isAuthorizationFailure(analytics.error) ? analytics.error : undefined
  const data = !authorityError && !analytics.loading && analytics.error == null ? analytics.data : undefined
  const summaryData = !authorityError && !summary.loading && summary.error == null ? summary.data : undefined
  const online = summaryData?.online, offline = summaryData?.offline, inactive = summaryData?.unactivated
  const statusPoints = online == null || offline == null || inactive == null ? [] : [{ label: '在线（当前策略）', value: online }, { label: '离线（当前策略）', value: offline }, { label: '未激活 / 未上报', value: inactive }]
  function refresh() { summary.refresh(); analytics.refresh() }
  return <>
    <PageHeading eyebrow="DUTY · OPERATIONS" title="运维概览" description="授权范围内的当前设备状态、真实报警与已报告故障趋势。">
      <PageHelp title="运维统计口径"><div className="form-stack"><p>报警按实际发生时间、来源故障按观察时间分别统计；观察时间未知的故障单独计数，不推定为零或纳入趋势。</p><p>报告 OFFLINE 事件和当前策略离线命中是不同事实。连接策略状态是当前快照，不代表历史事件数。</p><p>趋势、故障类别及离线设备排名均来自范围过滤后的完整服务端窗口，不由当前显示列表累加。</p></div></PageHelp>
      <Button type="button" variant="outline" disabled={!enabled} onClick={refresh}>刷新运维概览</Button>
    </PageHeading>
    <div className="list-toolbar overview-query"><div role="group" aria-label="运维时间范围">{([7, 30] as const).map(n => <Button key={n} type="button" disabled={!!authorityError} aria-pressed={days === n} variant={days === n ? 'default' : 'outline'} onClick={() => setDays(n)}>近{n}天</Button>)}</div>
      {data && <span className="muted-note">{data.from} 至 {data.to} · {data.timezone} · 完整时间窗口聚合</span>}
    </div>
    {empty ? <Empty title="当前范围未选择">空范围不会退化为平台全量统计。</Empty> : !session.scopeInitialized ? <Loading /> : !enabled ? <Empty title="无概览读取权限" /> : authorityError ? <ErrorNotice error={authorityError} retry={refresh} /> : <>
      <section className="dashboard-stat-strip" aria-label="运维关键指标">
        <div><span>设备总数 · 当前状态</span><strong>{summaryData?.device_total ?? '未知'}</strong></div><div><span>在线策略命中</span><strong>{summaryData?.online ?? '未知'}</strong></div><div><span>离线策略命中</span><strong>{summaryData?.offline ?? '未知'}</strong></div><div><span>未激活 / 未上报</span><strong>{summaryData?.unactivated ?? '未知'}</strong></div><div><span>{days}天报警</span><strong>{data?.alarms ?? '未知'}</strong></div><div><span>{days}天有观察时间的来源故障</span><strong>{data?.faults ?? '未知'}</strong></div><div><span>观察时间未知的故障（不纳入所选日趋势）</span><strong>{data?.untimed_fault_events ?? '未知'}</strong></div><div><span>{days}天报告离线事件</span><strong>{data?.reported_offline_events ?? '未知'}</strong></div>
      </section>
      {summary.loading ? <Loading /> : summary.error != null ? <ErrorNotice error={summary.error} retry={summary.refresh} /> : !summaryData ? <Empty title="设备状态摘要尚未确认" /> : null}
      {analytics.loading ? <Loading /> : analytics.error != null ? <ErrorNotice error={analytics.error} retry={analytics.refresh} /> : !data ? <Empty title="运维趋势尚未确认" /> : null}
      {data && <>
      <section className="duty-ops-grid">
        <DashboardPanel title="报警与报告故障趋势">
          <DashboardLineChart label={`${days}天报警逐日趋势，UTC`} points={data.alarms_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 条" />
          <DashboardLineChart label={`${days}天报告故障逐日趋势，UTC`} points={data.faults_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 条" />
          <p className="muted-note">报警按 ALARM_OCCURRED_AT、来源故障按 observed_at 分别聚合；故障日趋势不含观察时间未知事件。另有 {data.untimed_fault_events} 条观察时间未知的历史故障单独计数，不推定为零或归入任意日期。</p>
        </DashboardPanel>
        <DashboardPanel title="报告离线事件趋势">
          <DashboardLineChart label={`${days}天报告离线事件逐日趋势，UTC`} points={data.reported_offline_events_by_day.map(point => ({ label: point.date, value: point.count }))} unit=" 条" />
          <p className="muted-note">仅计算明确存储的 OFFLINE 事件，不将策略离线设备计为历史离线事件。</p>
        </DashboardPanel>
      </section>
      <section className="duty-ops-grid">
        <DashboardPanel title="来源故障类别 TOP 10">
          <DashboardBars label="故障类别排名" points={data.fault_types.map(row => ({ label: `${row.label} · ${row.code ?? '无故障码'}`, value: row.count }))} unit=" 次" />
          <p className="muted-note">FaultProjection 对 method 与 params_json 解析故障码；不同未知码与无码类型分别保留。</p>
        </DashboardPanel>
        <DashboardPanel title="报告离线设备 TOP 10">
          <DashboardBars label="报告离线次数设备排名" points={data.reported_offline_devices.map(row => ({ label: row.device_name ?? row.address ?? row.device_id, value: row.reported_offline_events }))} unit=" 次" />
          {data.reported_offline_devices.length > 0 && <div className="dashboard-table-wrap"><table className="dashboard-table"><thead><tr><th>设备</th><th>地址</th><th>单位</th><th>报告事件</th></tr></thead><tbody>{data.reported_offline_devices.map(row => <tr key={`${row.tenant_id}:${row.device_id}`}><td>{row.device_name ?? row.device_id}</td><td>{row.address ?? '未知'}</td><td>{row.tenant_id}</td><td>{row.reported_offline_events}</td></tr>)}</tbody></table></div>}
          {!data.reported_offline_devices.length && <Empty title="窗口内无可排名的设备离线事件" />}
        </DashboardPanel>
      </section>
      </>}
      <DashboardPanel title="设备当前连接状态">
        {statusPoints.length ? <DashboardBars label="当前连接策略设备数" points={statusPoints} unit=" 台" /> : <p className="muted-note">至少一类设备状态未知，不能绘制状态总量图。</p>}
        <p className="muted-note">当前策略状态是快照，不等价于统计区间内的 OFFLINE 事件数。所有总量由范围过滤后的服务端汇总返回。</p>
      </DashboardPanel>
    </>}
  </>
}
