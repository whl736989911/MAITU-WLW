import { useEffect, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { ApiError } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import {
  WORK_ORDER_PRIORITIES, WORK_ORDER_RESULTS, WORK_ORDER_STATUSES,
  getAssignees, getWorkOrder, getWorkOrders,
  type Assignee, type WorkOrderDetail, type WorkOrderPage, type WorkOrderResult,
} from '@/lib/maintenance'
import { useWorkOrderIntent } from '@/lib/work-order-intent'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { DeviceSelector, TargetUnitSelect, type AssetReference } from '@/components/asset-picker'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'

const wrap = { minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' as const }
const PAGE = 'maintenance-work-orders'
type Query = { page: number; status: string; keyword: string }
type ActionKind = 'open' | 'assign' | 'worklog' | 'submit' | 'review' | 'reopen' | 'cancel'

export function MaintenanceWorkOrdersPage() {
  const s = useSession()
  const correct = s.audience === 'management' ? s.user?.user_type === 'PLATFORM' : s.user?.user_type === 'CUSTOMER'
  if (!s.token || !correct || s.user?.must_change_password || !s.permissions.includes('maintenance:read') || String(s.page) !== PAGE)
    return <Empty title="当前无维修工单读取范围"/>
  const frame = `${s.authGeneration}`
  return <Content key={frame} permissions={[...s.permissions]} audience={s.audience} sessionTenant={s.user?.tenant_id ?? ''}
    scopeTenantIds={[...s.scopeTenantIds]} scopePlaceIds={[...s.scopePlaceIds]}/>
}

function StatusOptions() {
  return <><option value="">全部状态</option>{WORK_ORDER_STATUSES.map(value => <option key={value} value={value}>{value}</option>)}</>
}

function Content({ permissions, audience, sessionTenant, scopeTenantIds, scopePlaceIds }: {
  permissions: string[]; audience: 'management' | 'customer'; sessionTenant: string
  scopeTenantIds: string[]; scopePlaceIds: string[]
}) {
  const intent = useWorkOrderIntent()
  const [query, setQuery] = useState<Query>({ page: 1, status: '', keyword: '' })
  const [keywordDraft, setKeywordDraft] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [action, setAction] = useState<ActionKind | null>(null)
  const [targetUnit, setTargetUnit] = useState('')
  const [device, setDevice] = useState('')
  const [deviceRef, setDeviceRef] = useState<AssetReference>()
  const [createTitle, setCreateTitle] = useState('')
  const [createDescription, setCreateDescription] = useState('')
  const [createFault, setCreateFault] = useState('')
  const [createPriority, setCreatePriority] = useState('')
  const [createDue, setCreateDue] = useState('')
  const [reason, setReason] = useState('')
  const [note, setNote] = useState('')
  const [assignee, setAssignee] = useState('')
  const [result, setResult] = useState<WorkOrderResult>('REPAIRED')
  const [replacement, setReplacement] = useState('')
  const [pass, setPass] = useState(true)
  const [localError, setLocalError] = useState<unknown>()
  useEffect(() => { setDevice(''); setDeviceRef(undefined) }, [audience])

  const canWrite = permissions.includes('maintenance:write')
  const transferFilters: Record<string, string | number | boolean | null> = {
    keyword: query.keyword,
    ...(query.status ? { status: query.status } : {}),
    ...(audience === 'management' ? { unit_ids: scopeTenantIds.join(','), place_ids: scopePlaceIds.join(',') } : {}),
  }
  const canAssign = permissions.includes('maintenance:assign')
  const canReview = permissions.includes('maintenance:review')
  const scopeKey = audience === 'management' ? `${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}` : sessionTenant
  const list = useResource<WorkOrderPage>(`maintenance-list:${scopeKey}:${query.page}:${query.status}:${query.keyword}`,
    signal => getWorkOrders(query, signal), true)
  const detail = useResource<WorkOrderDetail>(`maintenance-detail:${scopeKey}:${selected ?? ''}`,
    signal => getWorkOrder(selected!, signal), !!selected)
  const assignees = useResource<Assignee[]>(`maintenance-assignees:${scopeKey}:${selected ?? ''}`,
    signal => getAssignees(selected!, signal), !!selected && action === 'assign')

  const order = detail.data?.order
  const busy = intent.phase === 'PENDING'
  const completion = intent.completion
  const hasIntent = !!intent.intent && intent.phase !== 'EDITING'
  const frozenBanner = hasIntent && !completion && (intent.phase !== 'CONFIRMING' || (!action && !creating))
  const freezeNavigation = !!intent.intent || !!completion
  const formLocked = busy || !!completion || (!!intent.intent && intent.phase !== 'CONFIRMING')

  function resetCreateFields() {
    setDevice(''); setDeviceRef(undefined); setCreateTitle(''); setCreateDescription(''); setCreateFault('')
    setCreatePriority(''); setCreateDue('')
  }
  function refreshAll() {
    setLocalError(undefined); setAction(null); setCreating(false)
    setTargetUnit(''); resetCreateFields(); setReason('')
    intent.dismissCompletion(); detail.refresh(); list.refresh()
  }
  function choose(id: string | null) {
    if (freezeNavigation) return
    setSelected(id); setAction(null); setCreating(false); setLocalError(undefined)
    resetCreateFields()
  }
  function search(event: FormEvent) {
    event.preventDefault()
    if (freezeNavigation) return
    choose(null)
    setQuery(current => ({ ...current, page: 1, keyword: keywordDraft.trim() }))
  }
  function beginCreate() {
    if (!canWrite || freezeNavigation) return
    setLocalError(undefined); setReason(''); setAction(null); setCreating(true); resetCreateFields()
  }
  function beginAction(kind: ActionKind) {
    if (!order || freezeNavigation) return
    setCreating(false)
    setAction(kind); setReason(''); setNote(''); setAssignee(''); setReplacement('')
    setResult('REPAIRED'); setPass(true); setLocalError(undefined)
  }
  function retry() {
    void intent.retry()
  }
  function targetUnitChanged(unit: string) {
    setTargetUnit(unit); setDevice(''); setDeviceRef(undefined)
  }
  function deviceChanged(id: string, reference?: AssetReference) {
    setDevice(id); setDeviceRef(id ? reference : undefined)
  }
  function submitCreate(event: FormEvent) {
    event.preventDefault()
    if (formLocked) return
    const management = audience === 'management'
    const unit = management ? targetUnit.trim() : sessionTenant
    if (management && !unit) { setLocalError(new ApiError(400, 'TARGET_UNIT_REQUIRED', '请明确选择本次写入的已授权单位。')); return }
    if (!device.trim() || !createTitle.trim() || !reason.trim()) { setLocalError(new ApiError(400, 'INPUT_REQUIRED', '设备、标题和原因均为必填。')); return }
    if (deviceRef?.tenantId && unit && deviceRef.tenantId !== unit) { setLocalError(new ApiError(400, 'ASSET_UNIT_MISMATCH', '所选设备不属于当前目标单位，请重新选择。')); return }
    const body: Record<string, unknown> = { fault_event_id: createFault.trim() || null, device_id: device.trim(), title: createTitle,
      description: createDescription, priority: createPriority || null,
      due_at: createDue ? new Date(createDue).toISOString() : null, reason }
    const request = {
      path: '/maintenance/work-orders', method: 'POST' as const, tenant: management ? unit : undefined, body,
      display: `新建工单 · ${createTitle.trim()} · 设备 ${device.trim()} · 单位 ${management ? unit : sessionTenant}`,
    }
    setLocalError(undefined)
    void intent.perform(request)
  }
  function submitAction(event: FormEvent) {
    event.preventDefault()
    if (!order || !action || formLocked) return
    if (action === 'assign' && !assignee) { setLocalError(new ApiError(400, 'ASSIGNEE_REQUIRED', '请选择负责人。')); return }
    if (action === 'worklog' && !note.trim()) { setLocalError(new ApiError(400, 'WORK_LOG_NOTE_REQUIRED', '维护记录必须填写。')); return }
    if (action === 'worklog' && result === 'REPLACED' && !replacement.trim()) { setLocalError(new ApiError(400, 'REPLACEMENT_REQUIRED', '已更换设备必须填写更换后的设备ID。')); return }
    const path = action === 'open' ? `/maintenance/work-orders/${encodeURIComponent(order.id)}/open`
      : action === 'assign' ? `/maintenance/work-orders/${encodeURIComponent(order.id)}/assign`
      : action === 'worklog' ? `/maintenance/work-orders/${encodeURIComponent(order.id)}/work-logs`
      : action === 'submit' ? `/maintenance/work-orders/${encodeURIComponent(order.id)}/submit-review`
      : action === 'review' ? `/maintenance/work-orders/${encodeURIComponent(order.id)}/review`
      : action === 'reopen' ? `/maintenance/work-orders/${encodeURIComponent(order.id)}/reopen`
      : `/maintenance/work-orders/${encodeURIComponent(order.id)}/cancel`
    const body: Record<string, unknown> = action === 'assign'
      ? { expected_revision: order.revision, assignee_user_id: assignee, reason }
      : action === 'worklog'
        ? { expected_revision: order.revision, note, result, replacement_device_id: result === 'REPLACED' ? replacement.trim() : null, reason }
        : action === 'review' ? { expected_revision: order.revision, pass, note, reason }
          : { expected_revision: order.revision, note, reason }
    const labels: Record<ActionKind, string> = { open: '开始处理', assign: '指派负责人', worklog: '登记维护记录', submit: '提交复核', review: '复核工单', reopen: '重新打开', cancel: '取消工单' }
    const request = { path, method: 'POST' as const, tenant: audience === 'management' ? order.tenant_id : undefined, body,
      display: `${labels[action]} · 工单 ${order.id} · revision ${order.revision}` }
    setLocalError(undefined)
    void intent.perform(request)
  }

  return <section aria-label="维修工单" className="device-workbench" style={wrap}>
    <PageHeading eyebrow="MAINTENANCE · 本地维修流程" title="维修工单">
      <PageHelp title="维修工单边界">工单是本地人工记录：指派、维护记录、复核、关闭/退回、重开与取消都写不可变历史，但不会改变源故障、恢复状态、设备物理状态或厂商记录。更换设备只登记关系，必须另行处理实际退役。导入只创建草稿，不自动指派或通知。</PageHelp>
      {!freezeNavigation && <DataTransferActions kind="WORK_ORDERS" tenantId={audience === 'management' ? targetUnit || undefined : undefined} filters={transferFilters}/>}
      <Button variant="outline" onClick={refreshAll}>刷新工单与队列</Button>
      {canWrite && <Button onClick={beginCreate} disabled={creating || freezeNavigation}>新建工单</Button>}
    </PageHeading>
    {completion && <section aria-label="工单提交回执" className="notice" style={wrap}>
      <h2>提交已确认（快照）</h2>
      <p>{completion.result.order.id} · {completion.result.order.status} · revision {completion.result.order.revision} · 设备 {completion.result.order.device_id}</p>
      <p style={{ whiteSpace: 'pre-wrap' }}>{completion.result.order.title}</p>
      <p>这是提交返回的快照，不代表当前事实。请显式刷新队列与详情；刷新前不要创建另一意图。</p>
      <div className="version-actions"><Button variant="outline" onClick={refreshAll}>刷新读取当前事实</Button><Button variant="ghost" onClick={() => intent.dismissCompletion()}>关闭回执（不刷新）</Button></div>
    </section>}
    {frozenBanner && intent.intent && <section aria-label="冻结的工单意图" className="notice" style={wrap} role="alert">
      <h2>{intent.phase === 'PENDING' ? '正在提交，结果未知' : intent.phase === 'UNCERTAIN' ? '结果未确认（不是失败）' : intent.phase === 'CONFLICT' ? '提交冲突' : '上次提交未成功'}</h2>
      <p style={{ whiteSpace: 'pre-wrap' }}>{intent.intent.display}</p>
      <p>冻结的请求参数与幂等键仅保留在当前页面会话内存中；密码不保存。离开页面再返回仍会看到本回执；浏览器刷新会丢失。请先刷新核对当前事实，禁止另建新意图。</p>
      {intent.error != null && <ErrorNotice error={intent.error}/>}
      {intent.phase === 'UNCERTAIN' && <div className="form-stack">
        <div className="version-actions"><Button variant="outline"  onClick={retry}>沿原操作确认结果</Button><Button variant="outline" onClick={refreshAll}>刷新核对</Button></div></div>}
      {intent.phase === 'CONFIRMING' && <div className="form-stack">
        <div className="version-actions"><Button variant="outline"  onClick={retry}>沿原请求重试</Button><Button variant="outline" onClick={() => { void intent.edit(); setLocalError(undefined) }}>放弃旧请求并重新填写</Button><Button variant="outline" onClick={refreshAll}>刷新核对</Button></div></div>}
      {intent.phase === 'CONFLICT' && <div className="version-actions"><Button variant="outline" onClick={() => { refreshAll(); void intent.reload() }}>刷新并放弃旧意图</Button></div>}
      {intent.phase === 'PENDING' && <p className="muted-note">正在等待服务端结果；不要关闭浏览器或创建另一意图。</p>}
    </section>}
    {audience === 'management' && scopeTenantIds.length === 0 && scopePlaceIds.length === 0 && <p className="muted-note">当前未选择单位/位置范围，队列为空；请从左侧范围树选择后再查询。</p>}
    {localError != null && <ErrorNotice error={localError}/>}
    {creating && canWrite && !frozenBanner && !completion && <section aria-label="新建维修工单" style={{ ...wrap, border: '1px solid #c6d5bb', borderRadius: 12, padding: 16 }}>
      <h2>新建工单</h2>
      <form className="form-stack" onSubmit={submitCreate}>
        <TargetUnitSelect audience={audience} permissions={permissions} value={targetUnit} onChange={targetUnitChanged} disabled={busy}/>
        <p className="muted-note">目标单位只影响本次写入；不会改变左侧单位/位置读取范围，也不会增加权限。</p>
        <DeviceSelector audience={audience} tenantId={audience === 'management' ? targetUnit || undefined : undefined}
          value={device} current={deviceRef} onChange={deviceChanged} canReadDevices={permissions.includes('device:read')}
          canReadPlaces={permissions.includes('units:read')} disabled={busy}/>
        <div className="field"><Label htmlFor="wo-fault">源故障事件ID（可空，精确引用）</Label><Input id="wo-fault" value={createFault} maxLength={64} onChange={event => setCreateFault(event.target.value)}/></div>
        <div className="field"><Label htmlFor="wo-title">标题</Label><Input id="wo-title" value={createTitle} maxLength={200} onChange={event => setCreateTitle(event.target.value)} required/></div>
        <div className="field"><Label htmlFor="wo-description">说明</Label><textarea id="wo-description" className="native-select" rows={3} maxLength={2000} value={createDescription} onChange={event => setCreateDescription(event.target.value)}/></div>
        <div className="field"><Label htmlFor="wo-priority">优先级（可空）</Label><select id="wo-priority" className="native-select" value={createPriority} onChange={event => setCreatePriority(event.target.value)}><option value="">不指定</option>{WORK_ORDER_PRIORITIES.map(value => <option key={value} value={value}>{value}</option>)}</select></div>
        <div className="field"><Label htmlFor="wo-due">计划完成时间（可空）</Label><Input id="wo-due" type="datetime-local" value={createDue} onChange={event => setCreateDue(event.target.value)}/></div>
        <div className="field"><Label htmlFor="wo-reason">变更原因</Label><Input id="wo-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
        
        {intent.error != null && intent.phase === 'CONFIRMING' && <ErrorNotice error={intent.error}/>}
        <div className="version-actions"><Button type="submit" disabled={formLocked || !device || !createTitle.trim() || !reason.trim() || audience === 'management' && !targetUnit}>{busy ? '正在创建…' : '确认创建'}</Button><Button variant="outline" onClick={() => { setCreating(false); setLocalError(undefined); void intent.edit() }} disabled={busy}>关闭</Button></div>
      </form>
    </section>}
    <section aria-label="工单队列" style={wrap}>
      <h2>工单队列</h2>
      <form className="search-form" onSubmit={search} style={{ ...wrap, flexWrap: 'wrap', marginBottom: 12 }}>
        <Input aria-label="工单关键字" value={keywordDraft} maxLength={100} onChange={event => setKeywordDraft(event.target.value)} placeholder="标题 / 说明 / 设备名（字面匹配）"/>
        <select aria-label="工单状态" className="native-select" value={query.status} onChange={event => { if (freezeNavigation) return; choose(null); setQuery(current => ({ ...current, page: 1, status: event.target.value })) }}><StatusOptions/></select>
        <Button type="submit" variant="outline" disabled={freezeNavigation}>查询工单</Button>
      </form>
      {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : list.data ? <>
        <p>已应用状态：{query.status || '全部'} · 关键字：{query.keyword || '无'} · 本次每页 {list.data.size} 条（服务端实际值）</p>
        {list.data.items.length === 0 ? <Empty title="本筛选无维修工单"/> : <div style={{ display: 'grid', gap: 12 }}>
          {list.data.items.map(item => <article key={item.id} data-work-order-id={item.id} style={{ ...wrap, border: '1px solid #e3e8db', borderRadius: 12, padding: 16 }}>
            <h3>{item.title}</h3>
            <p>{item.status} · 优先级 {item.priority ?? '未指定'} · 设备 {item.device_name ?? item.device_id} · 负责人 {item.assignee_name ?? '未指派'}</p>
            <p style={wrap}>{item.id} · 单位 {item.tenant_id} · revision {item.revision} · 更新 {item.updated_at}</p>
            <Button variant="outline" onClick={() => choose(item.id)} disabled={freezeNavigation}>打开工单</Button>
          </article>)}
        </div>}
        <Pager page={list.data.page} size={list.data.size} total={list.data.total} onChange={page => { if (freezeNavigation) return; choose(null); setQuery(current => ({ ...current, page })) }}/>
      </> : null}
    </section>
    {selected && <section aria-label="工单详情" data-selected-work-order-id={selected} style={wrap}>
      <h2>工单详情</h2>
      {detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : detail.data && order ? <>
        <dl className="detail-list" style={wrap}>
          <div><dt>工单ID</dt><dd>{order.id}</dd></div>
          <div><dt>状态</dt><dd>{order.status} · revision {order.revision}</dd></div>
          <div><dt>单位 / 设备</dt><dd>{order.tenant_id} / {order.device_name ?? order.device_id}（{order.device_id}）</dd></div>
          <div><dt>源故障</dt><dd>{order.fault_event_id ?? '无（不改变源故障）'}</dd></div>
          <div><dt>标题</dt><dd>{order.title}</dd></div>
          <div><dt>说明</dt><dd style={{ whiteSpace: 'pre-wrap' }}>{order.description || '空'}</dd></div>
          <div><dt>优先级 / 计划完成</dt><dd>{order.priority ?? '未指定'} / {order.due_at ?? '未指定'}</dd></div>
          <div><dt>负责人</dt><dd>{order.assignee_name ?? '未指派'}（{order.assignee_user_id ?? '无'}）</dd></div>
          <div><dt>创建 / 更新 / 关闭 / 取消</dt><dd>{order.created_at} / {order.updated_at} / {order.closed_at ?? '无'} / {order.cancelled_at ?? '无'}</dd></div>
        </dl>
        <div className="version-actions" style={{ flexWrap: 'wrap' }}>
          {canAssign && ['OPEN', 'ASSIGNED', 'IN_PROGRESS'].includes(order.status) && <Button variant="outline" onClick={() => beginAction('assign')} disabled={freezeNavigation}>指派负责人</Button>}
          {canWrite && order.status === 'DRAFT' && <Button variant="outline" onClick={() => beginAction('open')} disabled={freezeNavigation}>开始处理</Button>}
          {canWrite && ['OPEN', 'ASSIGNED', 'IN_PROGRESS'].includes(order.status) && <Button variant="outline" onClick={() => beginAction('worklog')} disabled={freezeNavigation}>登记维护记录</Button>}
          {canWrite && order.status === 'IN_PROGRESS' && <Button variant="outline" onClick={() => beginAction('submit')} disabled={freezeNavigation}>提交复核</Button>}
          {canReview && order.status === 'PENDING_REVIEW' && <Button variant="outline" onClick={() => beginAction('review')} disabled={freezeNavigation}>复核工单</Button>}
          {canWrite && order.status === 'CLOSED' && <Button variant="outline" onClick={() => beginAction('reopen')} disabled={freezeNavigation}>重新打开</Button>}
          {canWrite && !['CLOSED', 'CANCELLED'].includes(order.status) && <Button variant="outline" onClick={() => beginAction('cancel')} disabled={freezeNavigation}>取消工单</Button>}
          <Button variant="outline" onClick={refreshAll}>刷新工单</Button>
          <Button variant="outline" onClick={() => choose(null)} disabled={freezeNavigation}>关闭详情</Button>
        </div>
        {action && !frozenBanner && !completion && <form className="form-stack" onSubmit={submitAction} style={{ marginTop: 12 }}>
          <h3>{action === 'open' ? '开始处理' : action === 'assign' ? '指派负责人' : action === 'worklog' ? '登记维护记录' : action === 'submit' ? '提交复核' : action === 'review' ? '复核工单' : action === 'reopen' ? '重新打开' : '取消工单'}</h3>
          {action === 'assign' && <div className="field"><Label htmlFor="wo-assignee">负责人</Label>
            {assignees.loading ? <Loading/> : assignees.data ? <select id="wo-assignee" className="native-select" value={assignee} onChange={event => setAssignee(event.target.value)} required>
              <option value="">请选择负责人</option>
              {assignees.data.map(item => <option key={item.user_id} value={item.user_id}>{item.name}（{item.username}）</option>)}
            </select> : assignees.error != null ? <ErrorNotice error={assignees.error} retry={assignees.refresh}/> : <Loading/>}
            <p className="muted-note">候选仅包含当前单位内、已启用且可访问该设备并具有维护权限的用户。</p></div>}
          {action === 'worklog' && <>
            <div className="field"><Label htmlFor="wo-result">维护结果（人工如实记录）</Label><select id="wo-result" className="native-select" value={result} onChange={event => setResult(event.target.value as WorkOrderResult)}>{WORK_ORDER_RESULTS.map(value => <option key={value} value={value}>{value}</option>)}</select></div>
            {result === 'REPLACED' && <div className="field"><Label htmlFor="wo-replacement">更换后的设备ID</Label><Input id="wo-replacement" value={replacement} maxLength={64} onChange={event => setReplacement(event.target.value)} required/><p className="muted-note">仅登记关系，不转移、不注销、不归档源设备；实际退役请单独处理。</p></div>}
          </>}
          {action === 'review' && <div className="field"><Label htmlFor="wo-pass">复核结果</Label><select id="wo-pass" className="native-select" value={pass ? 'PASS' : 'RETURN'} onChange={event => setPass(event.target.value === 'PASS')}><option value="PASS">PASS（关闭本地工单）</option><option value="RETURN">RETURN（退回处理中）</option></select><p className="muted-note">CLOSED 仅表示本地人工复核通过，不代表源故障已修复或设备已恢复。</p></div>}
          {action !== 'assign' && action !== 'review' && <div className="field"><Label htmlFor="wo-note">{action === 'worklog' ? '维护记录（必填）' : '说明（可空）'}</Label><textarea id="wo-note" className="native-select" rows={2} maxLength={action === 'worklog' ? 2000 : 1000} value={note} onChange={event => setNote(event.target.value)} required={action === 'worklog'}/></div>}
          {action === 'review' && <div className="field"><Label htmlFor="wo-note">复核说明（可空）</Label><textarea id="wo-note" className="native-select" rows={2} maxLength={1000} value={note} onChange={event => setNote(event.target.value)}/></div>}
          <div className="field"><Label htmlFor="wo-action-reason">变更原因</Label><Input id="wo-action-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
          
          <p className="muted-note">以读取时的 revision {order.revision} 提交；冲突或未确认时会冻结请求与同一幂等键，刷新核对后再处理。</p>
          {intent.error != null && intent.phase === 'CONFIRMING' && <ErrorNotice error={intent.error}/>}
          <div className="version-actions"><Button type="submit" disabled={formLocked || action === 'assign' && !assignee || action === 'worklog' && (!note.trim() || result === 'REPLACED' && !replacement.trim())}>{busy ? '正在提交…' : '确认提交'}</Button><Button variant="outline" onClick={() => { setAction(null); setLocalError(undefined); void intent.edit() }} disabled={busy}>取消</Button></div>
        </form>}
        <section aria-label="工单历史" style={{ marginTop: 16 }}>
          <h3>状态时间线（不可变）</h3>
          {detail.data.timeline.length === 0 ? <Empty title="无时间线记录"/> : detail.data.timeline.map(entry => <article key={entry.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
            <p>{entry.action} · {entry.from_status ?? '无'} → {entry.to_status} · revision {entry.revision_before ?? '无'} → {entry.revision_after}</p>
            <p>操作者：{entry.actor_name ?? entry.actor_id} · {entry.created_at}{entry.assignee_user_id ? ` · 负责人 ${entry.assignee_user_id}` : ''}</p>
            <p style={{ whiteSpace: 'pre-wrap' }}>{entry.reason}</p>
          </article>)}
          <h3>维护记录</h3>
          {detail.data.work_logs.length === 0 ? <Empty title="暂无维护记录"/> : detail.data.work_logs.map(log => <article key={log.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
            <p>{log.result} · {log.created_at} · {log.actor_name ?? log.actor_id}</p>
            <p style={{ whiteSpace: 'pre-wrap' }}>{log.note}</p>
            {log.replacement_device_id && <p>更换记录：源 {order.device_id} → {log.replacement_device_name ?? log.replacement_device_id}（{log.replacement_device_id}）</p>}
          </article>)}
          <h3>更换关系</h3>
          {detail.data.replacements.length === 0 ? <Empty title="暂无更换关系"/> : detail.data.replacements.map(item => <p key={item.id}>{item.source_device_id} → {item.replacement_device_name ?? item.replacement_device_id}（{item.replacement_device_id}）· {item.created_by} · {item.created_at}</p>)}
        </section>
      </> : null}
    </section>}
  </section>
}
