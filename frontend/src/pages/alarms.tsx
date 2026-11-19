import { lazy, Suspense, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { SlidersHorizontal } from 'lucide-react'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { allowedPage, type Alarm, type AlarmWorkbenchFilters, type AlarmWorkbenchMetrics, type AlarmWorkbench } from '@/lib/types'
import { compileAlarmDraft, emptyAlarmDraft, emptyAlarmFilters, validateAlarmWorkbench, type AlarmFilterDraft } from '@/lib/alarm-workbench'
import { conclusions } from '@/lib/format'
import { AlarmTable } from '@/components/data-tables'
import { AlarmReview } from '@/components/alarm-review'
import { AlarmDetail } from '@/components/alarm-detail'
import { AlarmConfigVoice } from '@/components/alarm-config-voice'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PageHelp } from '@/components/page-tools'
import { useObjectRevision } from '@/lib/object-events'
import { publishObjectChange } from '@/lib/object-events'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
const AlarmChangesPanel = lazy(() => import('@/components/alarm-changes').then(m => ({ default: m.AlarmChangesPanel })))
interface ChangeBridge { nonce: number; query: string; generation: number; authorityEpoch: number; signal: AbortSignal; controller: AbortController; stop: () => void; resolve: () => void; reject: (error: unknown) => void }
const metricLabels: [keyof AlarmWorkbenchMetrics, string][] = [['alarm_total', '匹配报警总数'], ['unhandled', '人工未处理'], ['handled', '人工已处理'], ['unrecovered', '物理未恢复（FIRE）'], ['recovered', '物理已恢复（NORMAL）'], ['true_fire', '当前复核为真实火警']]
export function AlarmsPage() {
  const { user, audience, permissions, generation, token } = useSession()
  const identity = useRef({ token, permissions: permissions.join('|'), audience, userId: user?.id, userTenantId: user?.tenant_id, userType: user?.user_type, revision: 0 })
  if (identity.current.token !== token || identity.current.permissions !== permissions.join('|') || identity.current.audience !== audience || identity.current.userId !== user?.id || identity.current.userTenantId !== user?.tenant_id || identity.current.userType !== user?.user_type) identity.current = { token, permissions: permissions.join('|'), audience, userId: user?.id, userTenantId: user?.tenant_id, userType: user?.user_type, revision: identity.current.revision + 1 }
  if (!user || user.must_change_password || !allowedPage('alarms', audience, permissions)) return <Empty title="当前账户无报警查看权限"/>
  return <AlarmWorkbenchContent key={`${generation}:${identity.current.revision}`}/>
}
function FilterSummary({ filters, confirmed }: { filters: AlarmWorkbenchFilters; confirmed: boolean }) {
  const names: Record<string, string> = { FIRE: '火警', NORMAL: '正常', UNHANDLED: '未处理', HANDLED: '已处理', UNREVIEWED: '未复核（结论NULL）', TRUE_FIRE: '真实火警', FALSE_ALARM: '误报', TEST: '测试', DEVICE_ALARM: '设备报警' }
  return <section className="alarm-applied-filters">
<h3>{confirmed ? '实际应用条件（服务端已确认）' : '当前请求条件（等待服务端确认）'}</h3>
<dl>
<div>
<dt>字面关键字</dt>
<dd>{filters.keyword || '无条件'}</dd>
</div>
<div>
<dt>物理状态</dt>
<dd>{filters.physical_state === null ? '全部' : names[filters.physical_state]}</dd>
</div>
<div>
<dt>人工处置</dt>
<dd>{filters.response_state === null ? '全部' : names[filters.response_state]}</dd>
</div>
<div>
<dt>复核结论</dt>
<dd>{filters.conclusion === null ? '全部' : names[filters.conclusion]}</dd>
</div>
<div>
<dt>开始含</dt>
<dd>{filters.occurred_from ?? '无开始条件'}</dd>
</div>
<div>
<dt>结束不含</dt>
<dd>{filters.occurred_to ?? '无结束条件'}</dd>
</div>
</dl>
</section>
}
function AlarmWorkbenchContent() {
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeInitialized = useSession(s => s.scopeInitialized)
  const scopeEmpty = scopeInitialized && scopeTenantIds.length === 0 && scopePlaceIds.length === 0
  const [page, setPage] = useState(1), [size, setSize] = useState<number | undefined>()
  const [draft, setDraft] = useState<AlarmFilterDraft>({ ...emptyAlarmDraft }), [appliedDraft, setAppliedDraft] = useState<AlarmFilterDraft>({ ...emptyAlarmDraft })
  const [applied, setApplied] = useState<AlarmWorkbenchFilters>({ ...emptyAlarmFilters }), [filterError, setFilterError] = useState<unknown>()
  const [selected, setSelected] = useState<Alarm | null>(null), [detailId, setDetailId] = useState<string | null>(null), [detailTenantId, setDetailTenantId] = useState<string | undefined>(), [notice, setNotice] = useState('')
  const { generation } = useSession()
  const [changesOpen, setChangesOpen] = useState(false), [changeRefreshNonce, setChangeRefreshNonce] = useState(0), [knownDirty, setKnownDirty] = useState(false)
  const [authorityState, setAuthorityState] = useState({ epoch: 0, blocked: false, recovering: false })
  const authority = useRef(authorityState), recovery = useRef<{ epoch: number; nonce: number; query: string; generation: number; started: boolean; completed: boolean } | null>(null), alive = useRef(true)
  const dirtyEpoch = useRef(0), nextNonce = useRef(0), bridge = useRef<ChangeBridge | null>(null), starts = useRef(new WeakMap<AlarmWorkbench, { epoch: number; nonce: number; query: string; generation: number; authorityEpoch: number }>())
  const eventRevision = useObjectRevision('events'), handledEventRevision = useRef(eventRevision)
  const query = JSON.stringify([applied, page, size, scopeTenantIds, scopePlaceIds])
  useEffect(() => { setPage(1) }, [scopeTenantIds.join(','), scopePlaceIds.join(',')])
  function cancelBridge() { const pending = bridge.current; if (!pending) return; bridge.current = null; pending.signal.removeEventListener('abort', pending.stop); pending.controller.abort(); pending.reject(new DOMException('本次变更检查工作台重读已取消，请明确重新初始化', 'AbortError')) }
  function refreshWorkbench(signal: AbortSignal) {
    cancelBridge(); if (signal.aborted || authority.current.blocked || selected || detailId) return Promise.reject(new DOMException('请先关闭详情或复核再重建检查基线', 'AbortError'))
    return new Promise<void>((resolve, reject) => { const nonce = ++nextNonce.current, pending: ChangeBridge = { nonce, query, generation, authorityEpoch: authority.current.epoch, signal, controller: new AbortController(), stop: cancelBridge, resolve, reject }; bridge.current = pending; signal.addEventListener('abort', pending.stop, { once: true }); setChangeRefreshNonce(nonce) })
  }
  const alarms = useResource(`alarm-workbench:${query}:change-refresh:${changeRefreshNonce}:authority:${authorityState.epoch}:${scopeEmpty}`, signal => {
    const epoch = dirtyEpoch.current, authorityEpoch = authority.current.epoch, pending = bridge.current, matched = pending?.nonce === changeRefreshNonce && pending.query === query && pending.generation === generation && pending.authorityEpoch === authorityEpoch ? pending : null
    const attempt = recovery.current, restoring = attempt && attempt.epoch === authorityEpoch && attempt.nonce === changeRefreshNonce && attempt.query === query && attempt.generation === generation ? attempt : null
    if (restoring) restoring.started = true
    const combined = matched ? new AbortController() : null, stop = () => combined?.abort()
    const inputs = matched ? [signal, matched.controller.signal] : []; inputs.forEach(input => { if (input.aborted) stop(); else input.addEventListener('abort', stop, { once: true }) })
    const readSignal = combined?.signal ?? signal
    return api.alarmWorkbench(applied, page, size, readSignal, scopeTenantIds, scopePlaceIds).then(value => { if (readSignal.aborted) throw new DOMException('本次工作台重读已取消', 'AbortError'); if (value && typeof value === 'object') starts.current.set(value, { epoch, nonce: changeRefreshNonce, query, generation, authorityEpoch }); return value }).finally(() => { inputs.forEach(input => input.removeEventListener('abort', stop)); if (restoring) restoring.completed = true })
  }, !scopeEmpty && (!authorityState.blocked || authorityState.recovering))
  useEffect(() => {
    if (handledEventRevision.current === eventRevision) return
    if (selected || detailId || authority.current.blocked) return
    handledEventRevision.current = eventRevision
    cancelBridge(); alarms.refresh()
  }, [eventRevision, selected, detailId, alarms.refresh, authorityState.blocked])
  const invalid = useMemo(() => alarms.data ? validateAlarmWorkbench(alarms.data, applied, page, size) : null, [alarms.data, applied, page, size])
  const candidate = !alarms.loading && !alarms.error && !invalid ? alarms.data : undefined
  const candidateProof = candidate ? starts.current.get(candidate) : undefined
  const data = !authority.current.blocked && candidateProof?.authorityEpoch === authority.current.epoch ? candidate : undefined
  useEffect(() => {
    const attempt = recovery.current
    if (!attempt || !attempt.started || !attempt.completed || alarms.loading || !authority.current.recovering || attempt.epoch !== authority.current.epoch || attempt.nonce !== changeRefreshNonce || attempt.query !== query || attempt.generation !== generation) return
    recovery.current = null
    const success = !!candidate && candidateProof?.authorityEpoch === attempt.epoch && candidateProof.nonce === attempt.nonce && candidateProof.query === attempt.query && candidateProof.generation === attempt.generation
    const next = { epoch: attempt.epoch, blocked: !success, recovering: false }; authority.current = next; setAuthorityState(next)
  }, [candidate, candidateProof, alarms.loading, alarms.error, invalid, changeRefreshNonce, query, generation])
  useEffect(() => {
    const proof = data ? starts.current.get(data) : undefined
    if (!authority.current.blocked && knownDirty && proof?.authorityEpoch === authority.current.epoch && proof.epoch === dirtyEpoch.current) setKnownDirty(false)
    const pending = bridge.current
    if (authority.current.blocked || !pending || pending.authorityEpoch !== authority.current.epoch || pending.nonce !== changeRefreshNonce || pending.query !== query || pending.generation !== generation || alarms.loading) return
    bridge.current = null; pending.signal.removeEventListener('abort', pending.stop)
    if (data && proof?.nonce === pending.nonce && proof.query === pending.query && proof.generation === pending.generation) pending.resolve()
    else pending.reject(new Error('本次工作台重读未确认；不能用旧结果建立变更检查基线'))
  }, [data, alarms.loading, alarms.error, invalid, knownDirty, changeRefreshNonce, query, generation])
  useEffect(() => { alive.current = true; return () => { alive.current = false; cancelBridge() } }, [])
  function loseAuthority(capturedEpoch: number) {
    if (!alive.current || capturedEpoch !== authority.current.epoch || authority.current.blocked) return
    const next = { epoch: capturedEpoch + 1, blocked: true, recovering: false }; authority.current = next; setAuthorityState(next); recovery.current = null; dirtyEpoch.current++; cancelBridge(); setChangesOpen(false); setSelected(null); setDetailId(null); setNotice(''); setFilterError(undefined)
    setDraft({ ...emptyAlarmDraft }); setAppliedDraft({ ...emptyAlarmDraft }); setApplied({ ...emptyAlarmFilters }); setPage(1); setSize(undefined)
  }
  function recoverAuthority() {
    if (!authority.current.blocked || authority.current.recovering) return
    const nonce = ++nextNonce.current, next = { ...authority.current, recovering: true }; recovery.current = { epoch: next.epoch, nonce, query, generation, started: false, completed: false }; authority.current = next; setAuthorityState(next); setChangeRefreshNonce(nonce)
  }
  function refresh() { if (authority.current.blocked) return; cancelBridge(); alarms.refresh() }
  function markChanged() { if (authority.current.blocked) return; dirtyEpoch.current++; setKnownDirty(true) }
  function closeChanges() { cancelBridge(); setChangesOpen(false) }
  const dirty = JSON.stringify(draft) !== JSON.stringify(appliedDraft)
  function update(field: keyof AlarmFilterDraft, value: string) { setDraft(old => ({ ...old, [field]: value })); setFilterError(undefined) }
  function apply(event: FormEvent) { event.preventDefault(); cancelBridge(); try { const next = compileAlarmDraft(draft); setApplied(next); setAppliedDraft({ ...draft }); setPage(1); setFilterError(undefined); setNotice(''); setSelected(null); setDetailId(null); alarms.refresh() } catch (error) { setFilterError(error) } }
  function clear() { cancelBridge(); setDraft({ ...emptyAlarmDraft }); setAppliedDraft({ ...emptyAlarmDraft }); setApplied({ ...emptyAlarmFilters }); setPage(1); setFilterError(undefined); setNotice(''); setSelected(null); setDetailId(null); alarms.refresh() }
  function afterReview(publishedByDetail = false) { if (authority.current.blocked || authorityState.epoch !== authority.current.epoch) return; if (!publishedByDetail) publishObjectChange('events'); else alarms.refresh(); cancelBridge(); setNotice('已请求重读当前已应用条件的统计与列表；移出只表示不再匹配，不代表报警删除或物理恢复。') }
  if (scopeEmpty) return <><PageHeading eyebrow="ALARMS · 报警工作台" title="报警筛选与口径统计" description="左侧单位/位置范围未选择。"/><Empty title="当前范围未选择">请在左侧单位/位置树选择至少一个单位或位置。</Empty></>
  if (authorityState.blocked) return <><PageHeading eyebrow="ALARMS · 权限未确认" title="当前报警范围权限未确认" description="授权变化，旧行、统计、确认时间与敏感详情已隐藏；检查和草稿已销毁，不自动重读或退出仍在的会话。"/><div className="notice" role="alert" data-alarm-authority-blocked>当前范围已进入安全屏障，需显式真实重读确认权限；此前筛选草稿已清除。</div><Button type="button" disabled={authorityState.recovering} onClick={recoverAuthority}>重新读取当前工作台确认权限</Button>{authorityState.recovering && <Loading/>}{alarms.error != null && <ErrorNotice error={alarms.error}/>}<p>失败或未确认仍隐藏旧缓存；恢复成功也不会重开旧详情、备注、检查位置或信号通道。</p></>
  return <>
<PageHeading eyebrow="ALARMS · 报警工作台" title="报警信息" description="查看报警记录，核对设备状态并完成人工复核。">
<PageHelp title="筛选与统计口径"><div className="form-stack"><p>条件按 AND 组合；默认不设置“今天”或“近七天”窗口。统计覆盖全部匹配页，不只是当前页。</p><p>北京时间范围开始含、结束不含，依据报警发生时间。物理状态与人工处置状态互相独立：FIRE 表示设备物理未恢复，UNHANDLED 表示人工未处理。</p><p>人工复核不等于设备恢复，设备恢复也不等于完成处置；有复核权限者可直接提交。修改冲突时需重新核对，不会覆盖其他人的处置。</p><p>授权变化或变更检查失败会隐藏未确认的旧数据；恢复需显式重读，不自动沿用旧详情。</p></div></PageHelp>
<RefreshButton onClick={refresh}/><Button type="button" variant="outline" onClick={() => { if (!authority.current.blocked) setChangesOpen(true) }}><SlidersHorizontal/>打开报警变更检查</Button>
</PageHeading>
{knownDirty && <p className="notice" role="status" data-alarm-change-dirty>已知有已发布报警变更；当前工作台需刷新。提示不是报警数，关闭检查面板不会清除本提示。</p>}
{changesOpen && <Suspense fallback={<Loading/>}><AlarmChangesPanel refreshWorkbench={refreshWorkbench} onHint={markChanged} close={closeChanges} blocked={!!selected || !!detailId} authorityEpoch={authorityState.epoch} onAuthorityLost={loseAuthority}/></Suspense>}
<details className="secondary-controls alarm-filter-panel">
<summary>筛选报警{(applied.keyword || applied.physical_state || applied.response_state || applied.conclusion || applied.occurred_from || applied.occurred_to) && ' · 已提交筛选'}{dirty && ' · 修改未应用'}</summary>
<div className="secondary-controls-body">
<form onSubmit={apply} className="alarm-filter-form">
<div className="alarm-filter-grid">
<div className="field alarm-keyword">
<Label htmlFor="alarm-keyword">关键字（报警ID/名称/地址快照）</Label>
<Input id="alarm-keyword" value={draft.keyword} onChange={e => update('keyword', e.target.value)} placeholder="字面包含查询，不查当前设备目录或EUI"/>
</div>
<div className="field">
<Label htmlFor="alarm-physical">物理状态筛选</Label>
<select id="alarm-physical" className="native-select" value={draft.physical_state} onChange={e => update('physical_state', e.target.value)}>
<option value="">全部物理状态</option>
<option value="FIRE">未恢复（FIRE）</option>
<option value="NORMAL">已恢复（NORMAL）</option>
</select>
</div>
<div className="field">
<Label htmlFor="alarm-response">人工处置筛选</Label>
<select id="alarm-response" className="native-select" value={draft.response_state} onChange={e => update('response_state', e.target.value)}>
<option value="">全部人工状态</option>
<option value="UNHANDLED">未处理（UNHANDLED）</option>
<option value="HANDLED">已处理（HANDLED）</option>
</select>
</div>
<div className="field">
<Label htmlFor="alarm-conclusion">复核结论筛选</Label>
<select id="alarm-conclusion" className="native-select" value={draft.conclusion} onChange={e => update('conclusion', e.target.value)}>
<option value="">全部复核结论</option>
<option value="UNREVIEWED">未复核（结论NULL）</option>{conclusions.map(([value, text]) => <option key={value} value={value}>{text}（{value}）</option>)}</select>
</div>
<div className="field">
<Label htmlFor="alarm-from">开始时间（北京时间）</Label>
<Input id="alarm-from" type="datetime-local" step="0.000001" value={draft.occurred_from} onChange={e => update('occurred_from', e.target.value)}/>
</div>
<div className="field">
<Label htmlFor="alarm-to">结束时间（北京时间）</Label>
<Input id="alarm-to" type="datetime-local" step="0.000001" value={draft.occurred_to} onChange={e => update('occurred_to', e.target.value)}/>
</div>
</div>
<p className="muted-note">北京时间 UTC+08:00，开始含/结束不含，按报警occurred_at；仅填一侧也合法。字面补明确+08:00，不依赖浏览器本地时区，不自动换边界/取今天。%_!与SQL片段只作普通文本。</p>{filterError != null && <>
<ErrorNotice error={filterError}/>
<p className="muted-note">草稿未应用；当前已确认统计仍使用之前已应用条件。</p>
</>}<div className="alarm-filter-actions">
<Button type="submit">应用筛选</Button>
<Button type="button" variant="outline" onClick={clear}>清空筛选</Button>
<label className="size-picker">
<SlidersHorizontal size={15}/>
<select aria-label="每页报警条数" value={size ?? ''} onChange={e => { const next = e.target.value; if (next === '' || ['20', '50', '100'].includes(next)) { cancelBridge(); setPage(1); setSize(next === '' ? undefined : Number(next)) } }}>
<option value="">服务端默认条数</option>
<option value="20">20 条 / 页</option>
<option value="50">50 条 / 页</option>
<option value="100">100 条 / 页</option>
</select>
</label>
</div>{dirty && <p className="notice" role="status">草稿修改未应用；统计与列表仍沿用已应用条件，翻页不会偷偷采用新草稿。</p>}</form>
</div>
</details>{notice && <div className="notice" role="status">{notice}</div>}<section className="alarm-query-results" aria-busy={alarms.loading}>
<h2>本次查询统计（所有匹配页）</h2>
<div className="alarm-metric-grid">{metricLabels.map(([key, label]) => <Card key={key} className="alarm-query-metric">
<CardContent>
<span>{label}</span>
<strong data-workbench-metric={key}>{data ? data.metrics[key] : '未确认'}</strong>
</CardContent>
</Card>)}</div>{!data && <>{alarms.loading ? <Loading/> : alarms.error != null ? <ErrorNotice error={alarms.error} retry={refresh}/> : invalid ? <div className="notice" role="alert">
<strong>本次查询统计未确认</strong>
<p>{invalid}；不显示权威指标或错配列表，不补0。请刷新或核对已应用条件。</p>
</div> : null}</>}</section>{data && <Card className="list-card">
<CardContent>
<div className="section-header alarm-list-title">
<h2>当前筛选报警记录</h2>
<span className="muted-note">occurred_at DESC,id · 只展示服务端已确认查询</span>
</div>{data.items.length ? <AlarmTable items={data.items} onSelect={alarm => { if (authority.current.blocked || authorityState.epoch !== authority.current.epoch) return; cancelBridge(); setDetailId(null); setDetailTenantId(undefined); setSelected(alarm) }} onDetailSelect={alarm => { if (authority.current.blocked || authorityState.epoch !== authority.current.epoch || !alarm.tenant_id) return; cancelBridge(); setSelected(null); setDetailId(alarm.id); setDetailTenantId(alarm.tenant_id) }}/> : data.total === 0 ? <Empty title="当前条件无匹配报警">真实聚合为0，不改写/删除任何报警或补造记录。</Empty> : <Empty title="当前页无记录，匹配总量仍非0">
<Button type="button" variant="outline" onClick={() => { cancelBridge(); setPage(1) }}>返回第1页（沿用已应用条件）</Button>
</Empty>}<Pager page={data.page} size={data.size} total={data.total} onChange={next => { cancelBridge(); setPage(next) }}/>
</CardContent>
</Card>}
<details className="secondary-controls">
<summary>查询条件与统计说明{!data && ' · 尚未确认'}</summary>
<div className="secondary-controls-body">
{data && <p className="alarm-query-asof">查询评估时刻 as_of（服务端原值）：<time dateTime={data.as_of}>{data.as_of}</time> · timezone {data.timezone}。不是设备上报/报警新建时间。</p>}
<FilterSummary filters={data?.filters ?? applied} confirmed={!!data}/>
<p className="muted-note">时间字段 ALARM_OCCURRED_AT · 开始含/结束不含 · FILTERED_ALL_PAGES；人工、物理独立。当前复核为真实火警按当前有效结论TRUE_FIRE，可改判，不把每条FIRE或发生日期当已核实火灾。</p>
</div>
</details>
<AlarmReview alarm={selected} onClose={() => setSelected(null)} onReviewed={afterReview}/>
<AlarmDetail alarmId={detailId} tenantId={detailTenantId} onClose={() => { setDetailId(null); setDetailTenantId(undefined) }} onChanged={() => afterReview(true)}/>
{selected?.event_type === 'FIRE' && selected.tenant_id && <AlarmConfigVoice tenantId={selected.tenant_id} sourceId={selected.id} eventType="FIRE" occurredAt={selected.occurred_at ?? '发生时间未知'}/>}
</>
}
