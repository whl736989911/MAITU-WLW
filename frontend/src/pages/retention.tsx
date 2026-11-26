import { useEffect, useState, type FormEvent } from 'react'
import { Select } from 'antd'
import { useSession } from '@/lib/store'
import { api, ApiError } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import {
  RETENTION_KINDS, retentionApi, retentionKindLabels, retentionKindNotes,
  type RetentionKind, type RetentionPolicy, type RetentionRun,
} from '@/lib/retention'
import { useRetentionPolicyIntent, useRetentionRunIntent } from '@/lib/retention-intent'
import type { MutationIntent } from '@/lib/mutation-intent'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'

const PAGE = 'data-governance'
const PAGE_SIZE = 20
const mono = { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', overflowWrap: 'anywhere' as const }

export function RetentionPage() {
  const s = useSession()
  if (!s.token || !s.user || s.user.user_type !== 'PLATFORM' || s.user.must_change_password || s.audience !== 'management'
    || !s.permissions.includes('retention:read') || String(s.page) !== PAGE) return <Empty title="当前无数据保留读取权限"/>
  const frame = `${s.authGeneration}:${s.scopeTenantIds.join(',')}:${s.scopePlaceIds.join(',')}:${s.permissions.join(',')}`
  return <Content key={frame} permissions={[...s.permissions]} scopeTenantIds={[...s.scopeTenantIds]} scopePlaceIds={[...s.scopePlaceIds]}/>
}

function Content({ permissions, scopeTenantIds, scopePlaceIds }: { permissions: string[]; scopeTenantIds: string[]; scopePlaceIds: string[] }) {
  const canWrite = permissions.includes('retention:write')
  const policyIntent = useRetentionPolicyIntent()
  const runIntent = useRetentionRunIntent()
  const [target, setTarget] = useState('')
  const [kind, setKind] = useState<RetentionKind>('RAW_MESSAGES')
  const [overviewPage, setOverviewPage] = useState(1)
  const [historyPage, setHistoryPage] = useState(1)
  const [days, setDays] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState<RetentionRun | null>(null)
  const [executeReason, setExecuteReason] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [localError, setLocalError] = useState<unknown>()
  const scopeTreeRevision = useSession(s => s.scopeTreeRevision)
  const units = useResource(`retention-target-units:${scopeTreeRevision}`, signal => api.scopeTree(signal), true)
  const overview = useResource(`retention-overview:${overviewPage}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`,
    signal => retentionApi.policies(overviewPage, PAGE_SIZE, undefined, scopeTenantIds, scopePlaceIds, signal), true)
  const unit = useResource(`retention-unit:${target}`,
    signal => retentionApi.policies(1, RETENTION_KINDS.length, target, [], [], signal), !!target)
  const historyKind = target ? kind : ''
  const runs = useResource(`retention-runs:${target}:${historyKind}:${historyPage}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`,
    signal => retentionApi.runs(historyPage, PAGE_SIZE, historyKind, target || undefined, scopeTenantIds, scopePlaceIds, signal), true)

  const scopePage = overview.data, runPage = runs.data
  const rows = target ? unit.data?.items ?? [] : []
  const selected = rows.find(item => item.kind === kind)
  const targetName = selected?.tenant_name ?? units.data?.find(item => item.id === target)?.name ?? ''
  const busy = policyIntent.phase === 'PENDING' || runIntent.phase === 'PENDING'
  const hasIntent = !!(policyIntent.intent || runIntent.intent)
  const completion = policyIntent.completion ?? runIntent.completion
  const locked = busy || hasIntent
  const previewExpiresAt = preview?.expires_at
  const previewExpired = previewExpiresAt ? Date.parse(previewExpiresAt) <= Date.now() : false
  const previewStale = !!preview && (!selected || preview.policy_revision !== selected.revision || preview.kind !== selected.kind)
  const canExecute = canWrite && !!selected && selected.configured && selected.enabled && !!preview && preview.dry_run
    && !previewExpired && !previewStale && preview.legacy_potential_count === false

  useEffect(() => {
    if (policyIntent.completion) {
      overview.refresh()
      if (target) unit.refresh()
    }
  }, [policyIntent.completion?.serial])
  useEffect(() => {
    const done = runIntent.completion
    if (!done) return
    runs.refresh()
    if (done.path.endsWith('/preview')) setPreview(done.result)
    else { setPreview(null); setConfirmDelete(false); if (target) unit.refresh() }
  }, [runIntent.completion?.serial])
  useEffect(() => {
    setPreview(null)
    setConfirmDelete(false)
    setExecuteReason('')
    if (!selected || !selected.configured || selected.keep_days === null) { setDays(''); setEnabled(false); return }
    setDays(String(selected.keep_days))
    setEnabled(selected.enabled)
  }, [target, kind, selected?.configured, selected?.keep_days, selected?.enabled, selected?.revision])

  function refreshAll() {
    setLocalError(undefined)
    policyIntent.dismissCompletion()
    runIntent.dismissCompletion()
    overview.refresh()
    units.refresh()
    if (target) unit.refresh()
    runs.refresh()
  }
  function chooseTarget(value: string) {
    if (locked) return
    policyIntent.dismissCompletion()
    runIntent.dismissCompletion()
    setTarget(value)
    setOverviewPage(1)
    setHistoryPage(1)
    setPreview(null)
    setLocalError(undefined)
  }
  function chooseKind(value: RetentionKind) {
    if (locked) return
    policyIntent.dismissCompletion()
    runIntent.dismissCompletion()
    setKind(value)
    setHistoryPage(1)
    setPreview(null)
    setConfirmDelete(false)
    setLocalError(undefined)
  }
  function reject(message: string) { setLocalError(new ApiError(400, 'INPUT_REQUIRED', message)) }
  function submitConfig(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || !target || !selected || locked) return
    const value = Number(days)
    if (!Number.isSafeInteger(value) || value < 1 || value > 3650) { reject('保留天数须为 1..3650 的整数；没有默认值。'); return }
    if (!reason.trim()) { reject('必须填写本次配置原因。'); return }
    const body = { kind, keep_days: value, enabled, expected_revision: selected.revision, reason: reason.trim() }
    const request = { path: '/retention/policies', method: 'POST' as const, tenant: target, body,
      display: `配置保留策略 · ${selected.tenant_name} · ${retentionKindLabels[kind]} · ${value}天 · ${enabled ? '启用' : '不启用'} · revision ${selected.revision}` }
    runIntent.dismissCompletion()
    setLocalError(undefined)
    void policyIntent.perform(request)
  }
  function submitPreview() {
    if (!canWrite || !target || !selected || locked) return
    if (!selected.configured || !selected.enabled) { reject('未配置或未启用的策略不会试算或清理任何数据。'); return }
    const request = { path: '/retention/preview', method: 'POST' as const, tenant: target, body: { kind },
      display: `试算保留候选 · ${selected.tenant_name} · ${retentionKindLabels[kind]} · revision ${selected.revision}（不删除）` }
    policyIntent.dismissCompletion()
    setLocalError(undefined)
    void runIntent.perform(request)
  }
  function submitExecute(event: FormEvent) {
    event.preventDefault()
    if (!canExecute || !preview || !selected || !target || locked) return
    if (!confirmDelete) { reject('请显式勾选确认：本次执行会永久删除候选数据。'); return }
    if (!executeReason.trim()) { reject('必须填写本次清理原因。'); return }
    const request = { path: '/retention/execute', method: 'POST' as const, tenant: target,
      body: { kind, preview_id: preview.id, expected_revision: selected.revision, reason: executeReason.trim() },
      display: `执行永久删除 · ${selected.tenant_name} · ${retentionKindLabels[kind]} · 预览 ${preview.id} · revision ${selected.revision} · 候选 ${preview.eligible ?? '未知'}` }
    policyIntent.dismissCompletion()
    setLocalError(undefined)
    void runIntent.perform(request)
  }
  function retryPolicy() {
    void policyIntent.retry()
  }
  function retryRun() {
    void runIntent.retry()
  }

  return <section className="replica-page" aria-label="数据保留">
    <PageHeading eyebrow="系统管理 / 数据治理" title="数据保留" description="显式配置、冻结试算、显式执行与真实历史。默认未配置即不启用。">
      <PageHelp title="数据保留安全边界"><div className="form-stack">
        <p>未配置或未启用的类型不会自动清理。页面不预设 30/90 天，所有天数必须显式填写。配置、试算和执行要求当前账户拥有本单位明确的全部设备范围（ALL_TENANT）；试算后被撤销范围的执行会被拒绝。</p>
        <p>试算只生成冻结候选快照与实际删除为 0 的运行记录；不会删除任何业务数据。执行必须引用同一快照、当前策略 revision、幂等键和原因。</p>
        <p>候选集或策略在快照后变化时，服务端拒绝执行并回滚整个事务，不返回部分成功。原文清理保护处理/重解析引用；旧设备命令只清理终态，独立硬件命令台账与支付事实不属于本页类型。</p>
      </div></PageHelp>
      <Button type="button" variant="outline" onClick={refreshAll} disabled={busy}>刷新数据保留</Button>
    </PageHeading>

    {(policyIntent.intent || runIntent.intent) && <FrozenIntent policy={policyIntent} run={runIntent} retryPolicy={retryPolicy} retryRun={retryRun}
      refresh={() => { refreshAll(); setPreview(null) }}/>}
    {completion && <section className="notice" aria-label="保留操作回执">
      <h2>服务端已返回结果</h2>
      {'deleted' in completion.result
        ? <p>运行 {completion.result.id} · {completion.result.dry_run ? '试算' : '执行'} · 候选 {completion.result.eligible ?? '未知'} · 实际删除 {completion.result.deleted ?? '未记录'}</p>
        : <p>策略 {completion.result.tenant_name} · {retentionKindLabels[completion.result.kind]} · revision {completion.result.revision} · {completion.result.configured ? `${completion.result.keep_days}天 · ${completion.result.enabled ? '启用' : '不启用'}` : '未配置'}</p>}
      <p>这是服务端返回快照，不代表当前事实。请刷新读取当前策略、试算与历史。</p>
      <div className="version-actions">
        <Button variant="outline" onClick={refreshAll}>刷新读取当前事实</Button>
        <Button variant="ghost" onClick={() => { policyIntent.dismissCompletion(); runIntent.dismissCompletion() }}>关闭回执（不刷新）</Button>
      </div>
    </section>}
    {localError != null && <ErrorNotice error={localError}/>}

    <Card><CardContent>
      <h2>选择单位配置策略</h2>
      <p className="muted-note">{canWrite ? '选定单位后可配置三种保留策略、试算和清理。尚未配置的类型不会自动清理。' : '当前账号只有查看权限；选定单位后可查看全部三种策略。'} 本页目标不继承左侧读取范围。</p>
      {units.loading ? <Loading/> : units.error != null ? <ErrorNotice error={units.error} retry={units.refresh}/> : <div className="field">
        <Label htmlFor="retention-target-unit">目标单位</Label>
        <Select id="retention-target-unit" aria-label="目标单位" showSearch optionFilterProp="label" value={target || undefined}
          placeholder="搜索并明确选择本次操作单位" disabled={locked}
          options={(units.data ?? []).map(item => ({ value: item.id, label: `${item.name} · ${item.id}` }))}
          onChange={value => chooseTarget(value ? String(value) : '')}/>
      </div>}
      {target && <p className="muted-note">当前操作单位：{targetName}。切换单位会清除未执行的试算；冻结中的请求键和参数不会随单位切换改变。</p>}
    </CardContent></Card>
    <section className="dashboard-stat-strip" aria-label="保留范围概览">
      <div><span>授权范围单位</span><strong>{scopePage?.authorized_units ?? '未知'}</strong></div>
      <div><span>已配置策略</span><strong>{scopePage?.configured_policies ?? '未知'}</strong></div>
      <div><span>未配置类型槽（默认不启用）</span><strong>{scopePage?.unconfigured_kind_slots ?? '未知'}</strong></div>
      <div><span>当前读取范围</span><strong>{scopePage?.scope_empty ? '显式空选择' : `${scopeTenantIds.length} 单位 / ${scopePlaceIds.length} 位置`}</strong></div>
    </section>

    <Card><CardContent>
      <div className="list-toolbar"><h2>授权范围内的已配置策略</h2><span className="muted-note">未配置的类型不会出现在此表；请选择明确单位查看全部三种类型。</span></div>
      {overview.loading ? <Loading/> : overview.error != null ? <ErrorNotice error={overview.error} retry={overview.refresh}/> : scopePage?.scope_empty ? <Empty title="当前范围为显式空选择">空范围不会退化为平台全量策略。</Empty> : scopePage && scopePage.items.length > 0 ? <>
        <Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>类型</TableHead><TableHead>保留天数</TableHead><TableHead>启用</TableHead><TableHead>revision</TableHead><TableHead>最近更新（UTC）</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{scopePage.items.map(item => <TableRow key={`${item.tenant_id}:${item.kind}`}>
          <TableCell>{item.tenant_name}</TableCell><TableCell>{retentionKindLabels[item.kind]}</TableCell><TableCell>{item.keep_days} 天</TableCell><TableCell>{item.enabled ? '是' : '否（不清理）'}</TableCell><TableCell>{item.revision}</TableCell><TableCell className="mono">{item.updated_at ?? '未记录'}</TableCell>
          <TableCell><Button type="button" variant="outline" size="sm" disabled={locked} onClick={() => chooseTarget(item.tenant_id)}>选择此单位</Button></TableCell>
        </TableRow>)}</TableBody></Table>
        <Pager page={scopePage.page} size={scopePage.size} total={scopePage.total} onChange={setOverviewPage}/>
      </> : <Empty title="当前范围没有已配置策略">默认未配置即不启用；没有自动清理。</Empty>}
    </CardContent></Card>

    {target && <Card><CardContent>
      <div className="list-toolbar"><h2>单位策略与冻结试算</h2><span className="muted-note">{targetName}</span></div>
      <div role="group" aria-label="保留类型" className="version-actions">{RETENTION_KINDS.map(value => <Button key={value} type="button"
        variant={kind === value ? 'default' : 'outline'} disabled={locked} onClick={() => chooseKind(value)}>{retentionKindLabels[value]}</Button>)}</div>
      {unit.loading ? <Loading/> : unit.error != null ? <ErrorNotice error={unit.error} retry={unit.refresh}/> : selected ? <>
        <section className="notice"><strong>{retentionKindLabels[kind]} · {selected.configured ? `已配置 revision ${selected.revision}` : '未配置（默认不启用）'}</strong>
          <p>{retentionKindNotes[kind]}</p>
          <p>{selected.configured ? `保留 ${selected.keep_days} 天 · ${selected.enabled ? '已启用' : '未启用，不会清理'} · 最近更新 ${selected.updated_at ?? '未记录'}（UTC）` : '该类型没有策略行；不会按默认天数自动清理。请显式保存配置后再试算。'}</p>
        </section>
        {canWrite ? <form className="form-stack" onSubmit={submitConfig}>
          <div className="field"><Label htmlFor="retention-days">保留天数（1..3650，无默认值）</Label><Input id="retention-days" type="number" min={1} max={3650} value={days} disabled={locked} onChange={event => setDays(event.target.value)} placeholder="显式填写"/></div>
          <label className="notification-checkbox"><input type="checkbox" checked={enabled} disabled={locked} onChange={event => setEnabled(event.target.checked)}/>启用该策略（未勾选时保存后仍不清理）</label>
          <div className="field"><Label htmlFor="retention-reason">配置原因</Label><Input id="retention-reason" maxLength={1000} value={reason} disabled={locked} onChange={event => setReason(event.target.value)} placeholder="说明保留期调整依据"/></div>
          
          <div className="version-actions"><Button type="submit" disabled={locked}>{selected.configured ? '保存策略更新' : '创建策略配置'}</Button></div>
        </form> : <p className="muted-note">当前账户只有读取权限，不能配置、试算或执行。</p>}

        <section aria-label="保留试算" style={{ borderTop: '1px solid #dce3d5', paddingTop: 16 }}>
          <h3>试算（不删除）</h3>
          <p className="muted-note">试算会计算冻结截止时间、候选集和精确候选指纹，并记录一次 dry-run 历史；实际删除为 0。</p>
          {canWrite && <div className="form-stack">
            
            <div className="version-actions"><Button type="button" variant="outline" disabled={locked || !selected.configured || !selected.enabled} onClick={submitPreview}>生成冻结试算</Button></div>
          </div>}
          {!selected.configured || !selected.enabled ? <p className="muted-note">未配置或未启用，不能试算，也不会清理任何数据。</p> : null}
          {preview && <div className="notice" aria-label="冻结试算快照">
            <h3>冻结候选快照</h3>
            <dl className="detail-list"><div><dt>截止时间（UTC）</dt><dd className="mono">{preview.cutoff_at}</dd></div>
              <div><dt>扫描条数</dt><dd>{preview.scanned}</dd></div><div><dt>试算候选 / WOULD_DELETE</dt><dd>{preview.eligible ?? '未记录'}</dd></div>
              <div><dt>实际删除</dt><dd>{preview.deleted === null ? '未记录（历史试算）' : preview.deleted}</dd></div>
              <div><dt>策略 revision</dt><dd>{preview.policy_revision ?? '未记录'}</dd></div><div><dt>过期时间（UTC）</dt><dd className="mono">{preview.expires_at ?? '未记录'}</dd></div>
              <div><dt>候选集指纹</dt><dd className="mono" style={mono}>{preview.candidate_fingerprint ?? '未记录（历史试算）'}</dd></div></dl>
            <p className="muted-note">候选数不是删除结果。{previewExpired ? '该试算已过期，请重新试算。' : previewStale ? '策略或类型已变化，该试算已失效，请重新试算。' : '执行必须引用本次试算和当前策略 revision。'}</p>
          </div>}
        </section>

        <section aria-label="保留执行" style={{ borderTop: '1px solid #dce3d5', paddingTop: 16 }}>
          <h3>执行永久删除</h3>
          <p className="muted-note">仅在明确确认后执行。服务端会重新校验冻结截止时间、候选集指纹、当前策略 revision、有效会话与当前权限；候选变化或删除条数不一致时整笔回滚。</p>
          {canWrite ? <form className="form-stack" onSubmit={submitExecute}>
            <label className="notification-checkbox"><input type="checkbox" checked={confirmDelete} disabled={!canExecute || locked} onChange={event => setConfirmDelete(event.target.checked)}/>我确认按上方冻结试算永久删除候选数据</label>
            <div className="field"><Label htmlFor="retention-execute-reason">执行原因</Label><Input id="retention-execute-reason" maxLength={1000} value={executeReason} disabled={!canExecute || locked} onChange={event => setExecuteReason(event.target.value)} placeholder="记录本次不可逆清理依据"/></div>
            
            <div className="version-actions"><Button type="submit" variant="destructive" disabled={!canExecute || !confirmDelete || locked}>执行永久删除</Button></div>
          </form> : null}
          {!preview && <p className="muted-note">没有有效试算快照时不能执行。</p>}
        </section>
      </> : <Empty title="策略状态尚未确认"/>}
    </CardContent></Card>}

    <Card><CardContent>
      <div className="list-toolbar"><h2>运行历史</h2><span className="muted-note">{target ? `${targetName} · ${retentionKindLabels[kind]}` : '当前读取范围内的全部类型历史'}</span></div>
      {runs.loading ? <Loading/> : runs.error != null ? <ErrorNotice error={runs.error} retry={runs.refresh}/> : runPage && runPage.items.length > 0 ? <>
        <Table><TableHeader><TableRow><TableHead>创建时间（UTC）</TableHead><TableHead>单位</TableHead><TableHead>类型</TableHead><TableHead>方式</TableHead><TableHead>截止时间（UTC）</TableHead><TableHead>扫描</TableHead><TableHead>候选 / WOULD_DELETE</TableHead><TableHead>实际删除</TableHead><TableHead>revision / 来源</TableHead><TableHead>原因</TableHead></TableRow></TableHeader><TableBody>{runPage.items.map(run => <TableRow key={run.id}>
          <TableCell className="mono">{run.created_at}</TableCell><TableCell>{run.tenant_name}</TableCell><TableCell>{retentionKindLabels[run.kind]}</TableCell><TableCell>{run.dry_run ? '试算' : '执行'}</TableCell><TableCell className="mono">{run.cutoff_at}</TableCell><TableCell>{run.scanned}</TableCell>
          <TableCell>{run.eligible ?? '未记录'}{run.legacy_potential_count ? '（历史潜在条数）' : ''}</TableCell>
          <TableCell>{run.legacy_potential_count ? '未记录（未删除）' : run.deleted ?? '未记录'}</TableCell>
          <TableCell className="mono">{run.policy_revision ?? '未记录'}{run.source_preview_id ? ` / ${run.source_preview_id}` : ''}</TableCell>
          <TableCell>{run.reason ?? '未记录'}</TableCell>
        </TableRow>)}</TableBody></Table>
        {runPage.items.some(run => run.legacy_potential_count) && <p className="muted-note">标记“历史潜在条数”的旧试算行创建于新语义之前，其 deleted 列保存的是当时潜在数，现按试算候选展示且不重写历史行。</p>}
        <Pager page={runPage.page} size={runPage.size} total={runPage.total} onChange={setHistoryPage}/>
      </> : <Empty title="当前范围没有运行记录"/>}
    </CardContent></Card>
  </section>
}

function FrozenIntent({ policy, run, retryPolicy, retryRun, refresh }: {
  policy: MutationIntent<RetentionPolicy>
  run: MutationIntent<RetentionRun>
  retryPolicy: () => void
  retryRun: () => void
  refresh: () => void
}) {
  const active = policy.intent ? policy : run
  const isRun = !policy.intent && !!run.intent
  const frozen = active.intent
  if (!frozen) return null
  return <section className="notice" role="alert" aria-label="冻结的保留操作意图">
    <h2>{active.phase === 'PENDING' ? '正在提交，结果未知' : active.phase === 'UNCERTAIN' ? '结果未确认（不是失败）' : active.phase === 'CONFLICT' ? '提交冲突' : '上次提交未成功'}</h2>
    <p style={{ whiteSpace: 'pre-wrap' }}>{frozen.display}</p>
    <p>冻结的请求参数与幂等键仅保留在当前页面会话内存中，不随SPA导航或单位切换改变；密码不保存。浏览器刷新会丢失。</p>
    {active.error != null && <ErrorNotice error={active.error}/>}
    {active.phase === 'CONFLICT' ? <div className="version-actions">
      <Button type="button" variant="outline" onClick={() => { refresh(); if (isRun) { void run.reload(); } else { void policy.reload(); } }}>刷新并放弃旧意图</Button>
    </div> : <div className="form-stack">
      
      <div className="version-actions">
        <Button type="button" variant="outline" disabled={active.phase === 'PENDING'} onClick={isRun ? retryRun : retryPolicy}>沿原操作确认结果</Button>
        {active.phase === 'CONFIRMING' && <Button type="button" variant="outline" onClick={() => { void active.edit(); refresh() }}>放弃旧请求并重新填写</Button>}
        <Button type="button" variant="outline" onClick={refresh}>刷新核对</Button>
      </div>
    </div>}
  </section>
}
