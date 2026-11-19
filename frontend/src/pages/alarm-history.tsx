import { DataTransferActions } from '@/components/data-transfer-actions'
import { useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { api } from '@/lib/api'
import { compileAlarmDraft, emptyAlarmDraft, emptyAlarmFilters, validateAlarmWorkbench } from '@/lib/alarm-workbench'
import { conclusions } from '@/lib/format'
import { useResource } from '@/lib/use-resource'
import { Card,CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { AlarmTable } from '@/components/data-tables'
import { AlarmDetail } from '@/components/alarm-detail'
import type { Alarm } from '@/lib/types'
const metrics = [['alarm_total', '匹配报警'], ['unhandled', '人工未处理'], ['handled', '人工已处理'], ['unrecovered', '物理未恢复'], ['recovered', '物理已恢复'], ['true_fire', '当前复核为真实火警']] as const

export function AlarmHistoryPage() {
  const session = useSession()
  if (!session.user || session.user.must_change_password || !session.permissions.includes('alarm:read')) return <Empty title="当前账户无报警查看权限"/>
  return <AlarmHistoryContent key={JSON.stringify([session.generation, session.audience, session.scopeTenantIds, session.scopePlaceIds, session.scopeInitialized])}/>
}

function AlarmHistoryContent() {
  const session = useSession()
  const empty = session.scopeInitialized && !session.scopeTenantIds.length && !session.scopePlaceIds.length
  const enabled = session.scopeInitialized && !empty
  const [draft, setDraft] = useState(emptyAlarmDraft), [applied, setApplied] = useState(emptyAlarmFilters)
  const [page, setPage] = useState(1), [size, setSize] = useState(20)
  const [selected, setSelected] = useState<Alarm | null>(null), [filterError, setFilterError] = useState<unknown>()
  const alarms = useResource(`alarm-history:${JSON.stringify([page, size, applied])}`, async signal => {
    const snapshot = await api.alarmWorkbench(applied, page, size, signal, session.scopeTenantIds, session.scopePlaceIds)
    const invalid = validateAlarmWorkbench(snapshot, applied, page, size)
    if (invalid) throw new Error(invalid)
    return snapshot
  }, enabled)

  function applyFilters(event: FormEvent) {
    event.preventDefault()
    try {
      setApplied(compileAlarmDraft(draft)); setPage(1); setSelected(null); setFilterError(undefined)
    } catch (error) { setFilterError(error) }
  }
  function refresh() { setSelected(null); alarms.refresh() }
  const data = !alarms.loading && alarms.error == null ? alarms.data : undefined

  return <>
    <PageHeading eyebrow="运行中心 / 历史" title="报警历史" description="检索来源报警，分别核对物理恢复、人工复核和业务轨迹。">
      <PageHelp title="报警历史口径"><p>按来源发生时间筛选，北京时间开始含、结束不含。人工复核不会改写设备物理恢复；业务轨迹按实际权限读取。</p></PageHelp>
      <Button type="button" variant="outline" disabled={!enabled} onClick={refresh}>刷新报警历史</Button>
      {enabled && <DataTransferActions kind="ALARMS" label="导出报警" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), ...applied }}/>}
    </PageHeading>
    <Card><CardContent>
      <form className="device-workbench-grid" onSubmit={applyFilters}>
        <label className="field">设备或地址<Input value={draft.keyword} onChange={e => setDraft({ ...draft, keyword: e.target.value })}/></label>
        <label className="field">物理状态<select className="native-select" value={draft.physical_state} onChange={e => setDraft({ ...draft, physical_state: e.target.value })}><option value="">全部</option><option value="FIRE">未恢复</option><option value="NORMAL">已恢复</option></select></label>
        <label className="field">人工处置<select className="native-select" value={draft.response_state} onChange={e => setDraft({ ...draft, response_state: e.target.value })}><option value="">全部</option><option value="UNHANDLED">未处理</option><option value="HANDLED">已处理</option></select></label>
        <label className="field">当前复核结论<select className="native-select" value={draft.conclusion} onChange={e => setDraft({ ...draft, conclusion: e.target.value })}><option value="">全部</option><option value="UNREVIEWED">未复核</option>{conclusions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label className="field">发生时间从（北京时间）<Input type="datetime-local" value={draft.occurred_from} onChange={e => setDraft({ ...draft, occurred_from: e.target.value })}/></label>
        <label className="field">至（不含）<Input type="datetime-local" value={draft.occurred_to} onChange={e => setDraft({ ...draft, occurred_to: e.target.value })}/></label>
        <div className="list-toolbar">
          <Button type="submit" disabled={!enabled}>应用筛选</Button>
          <Button type="button" variant="outline" onClick={() => { setDraft(emptyAlarmDraft); setApplied(emptyAlarmFilters); setPage(1); setSelected(null); setFilterError(undefined) }}>清除筛选</Button>
        </div>
      </form>
      {filterError != null && <ErrorNotice error={filterError}/>}
    </CardContent></Card>
    {!session.scopeInitialized ? <Loading/> : empty ? <Empty title="当前范围未选择">请在左侧选择单位或位置。</Empty> :
      alarms.loading ? <Loading/> : alarms.error != null ? <ErrorNotice error={alarms.error} retry={refresh}/> :
      !data ? <div className="notice" role="alert">报警历史响应未确认。</div> : <>
        <section className="alarm-query-results" aria-label="匹配范围统计"><div className="alarm-metric-grid">
          {metrics.map(([key, label]) => <Card className="alarm-query-metric" key={key}><CardContent><span>{label}</span><strong>{data.metrics[key]}</strong></CardContent></Card>)}
        </div></section>
        <Card><CardContent>
          <div className="section-header"><h2>报警记录</h2><label>每页 <select className="native-select" value={size} onChange={e => { setSize(Number(e.target.value)); setPage(1); setSelected(null) }}>{[20, 50, 100].map(n => <option key={n} value={n}>{n}</option>)}</select></label></div>
          {data.items.length ? <AlarmTable items={data.items} actions={alarm => <Button type="button" variant="outline" size="sm" onClick={() => setSelected(alarm)}>查看报警详情</Button>}/> :
            <Empty title={data.total ? '本页无报警记录' : '当前筛选没有匹配的报警记录'}>{data.total > 0 && <Button type="button" variant="outline" onClick={() => setPage(1)}>返回第 1 页</Button>}</Empty>}
          <Pager page={data.page} size={data.size} total={data.total} onChange={next => { setSelected(null); setPage(next) }}/>
        </CardContent></Card>
      </>}
    {selected && alarms.error == null && <AlarmDetail alarmId={selected.id} tenantId={selected.tenant_id} onClose={() => setSelected(null)} onChanged={alarms.refresh}/>}
  </>
}
