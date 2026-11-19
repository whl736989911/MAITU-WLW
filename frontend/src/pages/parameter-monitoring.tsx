import { DataTransferActions } from '@/components/data-transfer-actions'
import { useMemo, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { queryCurrentTelemetry, queryTelemetry, queryTelemetryAnalytics, queryTelemetryMetrics, type TelemetryReading } from '@/lib/duty-api'
import { dateTime } from '@/lib/format'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { DashboardLineChart, DashboardPanel } from '@/components/dashboard-charts'
import { PageHelp } from '@/components/page-tools'
import { Badge } from '@/components/ui/badge'
import type { MeasurementValueType } from '@/lib/product-schema'
const dateValue = (value: string) => value ? new Date(value).toISOString() : ''

/** NUMBER keeps the legacy unit surface; STRING/BOOLEAN never pretend a unit or a numeric magnitude. */
function readingAccessibleName(reading: TelemetryReading, label: string, declaredType?: MeasurementValueType) {
  const valueType = reading.value_type ?? declaredType ?? 'NUMBER'
  if (valueType === 'NUMBER') return `${label} ${reading.value} ${reading.unit ?? '单位未知'}`
  return `${label} ${reading.value}${reading.unit ? ` ${reading.unit}` : ''} ${valueType === 'BOOLEAN' ? '布尔' : '文本'}`
}
function ReadingValue({ reading, declaredType }: { reading: TelemetryReading; declaredType?: MeasurementValueType }) {
  const valueType = reading.value_type ?? declaredType ?? 'NUMBER'
  if (valueType === 'NUMBER') return <>{reading.value} <span className={reading.unit_status === 'UNKNOWN' ? 'dashboard-unknown' : ''}>{reading.unit ?? '单位未知'}</span></>
  return <>{reading.value}{reading.unit ? ` ${reading.unit}` : ''} <Badge variant="outline">{valueType === 'BOOLEAN' ? '布尔' : '文本'}</Badge></>
}

export function ParameterMonitoringPage() {
  const session = useSession(), scopeKey = `${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${session.scopeInitialized}`
  const [metric, setMetric] = useState(''), [from, setFrom] = useState(''), [to, setTo] = useState('')
  const [page, setPage] = useState(1), [currentPage, setCurrentPage] = useState(1), [days, setDays] = useState<7 | 30>(7)
  const [applied, setApplied] = useState<{metric:string;from:string;to:string}>({ metric: '', from: '', to: '' })
  const [trendMetric, setTrendMetric] = useState('')
  const empty = session.scopeInitialized && session.scopeTenantIds.length === 0 && session.scopePlaceIds.length === 0
  const enabled = session.scopeInitialized && !empty && session.permissions.includes('device:read')
  const metricOptions = useResource(`duty-telemetry-metrics:${scopeKey}`, signal => queryTelemetryMetrics(signal), enabled)
  const metrics = metricOptions.data ?? []
  const metricLabel = (key: string) => metrics.find(metricOption => metricOption.key === key)?.label ?? key
  const data = useResource(`duty-parameters:${scopeKey}:${JSON.stringify([page, applied])}`, signal => queryTelemetry(page, applied.metric, applied.from, applied.to, signal), enabled)
  const current = useResource(`duty-parameters-current:${scopeKey}:${currentPage}:${applied.metric}`, signal => queryCurrentTelemetry(currentPage, applied.metric, signal), enabled)
  const trend = useResource(`duty-parameters-analytics:${scopeKey}:${days}:${trendMetric}`, signal => queryTelemetryAnalytics(days, trendMetric, signal), enabled && trendMetric !== '')
  const historyResult = !data.loading && data.error == null ? data.data : undefined
  const currentResult = !current.loading && current.error == null ? current.data : undefined
  const samples = historyResult?.items ?? [], currentSamples = currentResult?.items ?? []
  const latest = useMemo(() => currentSamples.map(sample => ({ ...sample, name: sample.device_name ?? sample.address ?? sample.device_id ?? sample.event_id, values: new Map(sample.readings.map(reading => [reading.metric, reading])) })), [currentSamples])
  const visibleMetrics = useMemo(() => {
    if (applied.metric) return [applied.metric]
    const keys = new Set(metrics.map(option => option.key))
    for (const sample of currentSamples) for (const reading of sample.readings) keys.add(reading.metric)
    return [...keys]
  }, [applied.metric, metrics, currentSamples])
  function refresh() { metricOptions.refresh(); current.refresh(); data.refresh(); if (trendMetric) trend.refresh() }
  const metricValueType = (key: string): MeasurementValueType => metrics.find(option => option.key === key)?.value_type ?? 'NUMBER'
  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setApplied({ metric, from: dateValue(from), to: dateValue(to) }); setPage(1); setCurrentPage(1)
  }
  return <>
    <PageHeading eyebrow="DUTY · PARAMETER MONITORING" title="数据监测" description="查看已接收的设备读数、历史样本与完整窗口趋势。"><PageHelp title="监测数据口径" label="数据说明"><div className="form-stack"><p>当前值只使用有来源观察时间的记录；分项上报的指标可能来自不同时刻，汇总时间不表示所有数值同时测得。各指标原始时间请查历史；未知观察时间不会改用服务接收时间冒充最新值。</p><p>本页展示已接收快照，不自动轮询。点击“刷新监测数据”重新查询，不主动触发设备上报。</p><p>历史统计不重复计入快照。指标筛选应用于当前值与历史表，历史时间筛选仅影响历史表；趋势独立选择指标及 7/30 天完整窗口。</p><p>趋势按单位声明分组；单位未知时只绘样本数，不比较数值。单位来自厂商原始声明或设备绑定产品版本，未声明时不推断。文本/布尔离散读数只展示原始值与样本数，不做数值平均、最小值或最大值比较。</p></div></PageHelp><Button type="button" variant="outline" disabled={!enabled} onClick={refresh}>刷新监测数据</Button><DataTransferActions kind="MONITORING" label="导出监测值" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), metric: applied.metric, observed_from: applied.from, observed_to: applied.to }}/></PageHeading>
    <details className="secondary-controls"><summary>筛选监测记录{(applied.metric || applied.from || applied.to) && ' · 已启用筛选'}</summary>
    <Card><CardContent><form className="device-workbench-grid" onSubmit={apply}>
      <div className="field"><Label htmlFor="parameter-metric">监测指标</Label><select id="parameter-metric" className="native-select" value={metric} onChange={event => setMetric(event.target.value)}><option value="">全部支持指标</option>{metrics.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}</select></div>
      <div className="field"><Label htmlFor="parameter-from">历史开始时间（本地）</Label><Input id="parameter-from" type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></div>
      <div className="field"><Label htmlFor="parameter-to">历史结束时间（本地）</Label><Input id="parameter-to" type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></div>
      <div className="parameter-filter-actions"><Button type="submit">应用筛选</Button><Button type="button" variant="outline" onClick={() => { setMetric(''); setFrom(''); setTo(''); setApplied({ metric: '', from: '', to: '' }); setPage(1); setCurrentPage(1) }}>清除筛选</Button></div>
    </form></CardContent></Card>
    </details>
    {empty ? <Empty title="当前范围未选择">不扩大为全部单位或读取此前范围缓存。</Empty> : !session.scopeInitialized ? <Loading /> : !session.permissions.includes('device:read') ? <Empty title="无设备读取权限" /> : <>
      {metricOptions.error != null && <ErrorNotice error={metricOptions.error} retry={metricOptions.refresh} />}
        <details className="secondary-controls"><summary>查询统计</summary><section className="dashboard-stat-strip" aria-label="当前样本概况">
          <div><span>历史筛选匹配事件</span><strong>{historyResult?.total ?? '未确认'}</strong></div><div><span>当前有观察时间样本设备数</span><strong>{currentResult?.total ?? '未确认'}</strong></div><div><span>当前页最新样本</span><strong>{currentResult ? currentSamples.length : '未确认'}</strong></div><div><span>当前历史页观察时间未知</span><strong>{historyResult ? samples.filter(sample => sample.observed_at === null).length : '未确认'}</strong></div>
        </section></details>
        <DashboardPanel title="设备当前监测值">
          {current.loading ? <Loading /> : current.error != null ? <ErrorNotice error={current.error} retry={current.refresh} /> : !currentResult ? <Empty title="当前监测值尚未确认" /> : <>
          <div className="dashboard-table-wrap"><table className="dashboard-table monitoring-values-table"><thead><tr><th>设备</th>{visibleMetrics.map(key => <th key={key}>{metricLabel(key)}</th>)}<th>汇总观察时间</th><th>汇总接收时间</th></tr></thead><tbody>
            {latest.map(sample => <tr key={`${sample.tenant_id}:${sample.device_id ?? sample.event_id}`}><td><strong className="monitoring-device-name" title={sample.name}>{sample.name}</strong><small className="monitoring-subline" title={sample.address ?? sample.device_id ?? undefined}>{sample.address ?? sample.device_id ?? '设备标识未知'}</small></td>{visibleMetrics.map(key => { const reading = sample.values.get(key); return <td key={key} title={reading?.label ?? metricLabel(key)} aria-label={reading ? readingAccessibleName(reading, reading.label ?? metricLabel(key), metricValueType(key)) : `${metricLabel(key)} 未上报`}>{reading ? <ReadingValue reading={reading} declaredType={metricValueType(key)}/> : '未上报'}</td> })}<td className={sample.observed_at ? '' : 'dashboard-unknown'}>{sample.observed_at ? dateTime(sample.observed_at) : '未知（未用接收时间替代）'}</td><td>{sample.received_at ? dateTime(sample.received_at) : <><span>接收时间未知</span><small className="monitoring-subline">记录于 {dateTime(sample.recorded_at)}</small></> }</td></tr>)}
          </tbody></table></div>
          {!latest.length && <p className="muted-note" role="status">当前筛选暂无带观察时间的监测值。</p>}
          <Pager page={currentResult.page} size={currentResult.size} total={currentResult.total} onChange={setCurrentPage} />
          <p className="muted-note">各指标可能来自不同时刻；汇总时间不表示同时测量，未知观察时间不回填。当前为已存快照，可刷新重新读取。</p>
          </>}
        </DashboardPanel>
        <DashboardPanel title="历史样本筛选结果">
          {data.loading ? <Loading /> : data.error != null ? <ErrorNotice error={data.error} retry={data.refresh} /> : !historyResult ? <Empty title="历史监测样本尚未确认" /> : <>
          <div className="dashboard-table-wrap"><table className="dashboard-table"><thead><tr><th>设备 / 地址</th><th>事件观察时间</th><th>服务接收时间</th><th>参数样本</th></tr></thead><tbody>
            {samples.map(sample => <tr key={sample.event_id}><td>{sample.device_name ?? '未命名设备'}<small className="monitoring-subline">{sample.address ?? sample.device_id ?? '未关联设备'}</small></td><td className={sample.observed_at ? '' : 'dashboard-unknown'}>{sample.observed_at ? dateTime(sample.observed_at) : '未知'}</td><td>{sample.received_at ? dateTime(sample.received_at) : <><span>接收时间未知</span><small className="monitoring-subline">记录于 {dateTime(sample.recorded_at)}</small></> }</td><td>{sample.readings.map(reading => <span className="monitoring-reading" key={reading.metric}>{reading.label ?? metricLabel(reading.metric)} <ReadingValue reading={reading} declaredType={metricValueType(reading.metric)}/></span>)}{sample.readings.length === 0 && '无支持的读数'}</td></tr>)}
          </tbody></table></div>
          {!samples.length && <p className="muted-note" role="status">筛选范围内无历史样本。</p>}
          <Pager page={historyResult.page} size={historyResult.size} total={historyResult.total} onChange={setPage} />
          </>}
        </DashboardPanel>
      <details className="secondary-controls"><summary>趋势分析</summary><div className="secondary-controls-body">
      <DashboardPanel title="完整窗口监测趋势">
        <div className="parameter-trend-controls"><div className="field"><Label htmlFor="trend-metric">趋势指标</Label><select id="trend-metric" className="native-select" value={trendMetric} onChange={event => setTrendMetric(event.target.value)}><option value="">选择指标</option>{metrics.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}</select></div><div role="group" aria-label="监测趋势统计窗口">{([7, 30] as const).map(n => <Button key={n} type="button" variant={days === n ? 'default' : 'outline'} onClick={() => setDays(n)}>近{n}天</Button>)}</div></div>
        {metricValueType(trendMetric) !== 'NUMBER' && trendMetric !== '' && <p className="muted-note">该指标为{metricValueType(trendMetric) === 'BOOLEAN' ? '布尔' : '文本'}离散状态：趋势仅统计每日样本数，不做数值平均、最小值或最大值比较。</p>}
        {trendMetric === '' ? <Empty title="请选择趋势指标" /> : trend.loading ? <Loading /> : trend.error != null ? <ErrorNotice error={trend.error} retry={trend.refresh} /> : trend.data ? <>
          <p className="muted-note">完整范围窗口：{trend.data.from} 至 {trend.data.to} · 每日 UTC 桶。另有 {trend.data.untimed_sample_count} 条观察时间未知的匹配 PARAM 历史事件；这些事件无法归入所选时间窗口，未使用接收时间补桶。</p>
          {trend.data.series.map(series => {
            const numeric = (series.value_type ?? metricValueType(trendMetric)) === 'NUMBER'
            return <section className="monitoring-series" key={`${series.unit_status}:${series.unit ?? 'unknown'}:${series.source_version ?? 'shared'}`}>
              {numeric && series.unit_status === 'DECLARED' ? <DashboardLineChart label={`${metricLabel(trendMetric)}按${series.unit}聚合的逐日均值`} points={series.points.filter(point => point.average !== null).map(point => ({ label: point.date, value: Number(point.average) }))} unit={` ${series.unit}`} /> : <><DashboardLineChart label={numeric ? `${metricLabel(trendMetric)}单位未知样本数，不表示数值大小` : `${metricLabel(trendMetric)}离散状态每日样本数，不做数值平均`} points={series.points.map(point => ({ label: point.date, value: point.sample_count }))} unit=" 个样本" /><p className="muted-note">{numeric ? '单位未知：仅展示样本数量，不进行跨单位数值比较。' : '离散状态（文本/布尔）仅展示样本数量，不计算均值、最小值或最大值。'}</p></>}
              {numeric && series.unit_status === 'DECLARED' && <p className="muted-note">每日样本数、均值、最小值与最大值为服务端完整窗口结果，单位一致（{series.unit}）。</p>}
            </section>
          })}
          {!trend.data.series.length && <Empty title="所选范围没有可聚合的样本" />}
        </> : <Empty title="趋势数据尚未确认" />}
      </DashboardPanel>
      </div></details>
    </>}
  </>
}
