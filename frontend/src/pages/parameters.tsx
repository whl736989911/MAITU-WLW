import { DataTransferActions } from '@/components/data-transfer-actions'
import { lazy, Suspense, useState, type FormEvent } from 'react'
import { TreeSelect } from 'antd'
import { Eraser, SlidersHorizontal } from 'lucide-react'
import { api, request } from '@/lib/api'
import { batteryApi } from '@/lib/battery-api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime, reported } from '@/lib/format'
import { definedInteger, revisionInteger } from '@/lib/parameter-json'
import { placeTreeOptions } from '@/lib/place-tree'
import type { ParameterDefinition, ParameterMutation, ParameterPolicy, ParameterScope, ScopeUnit } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { IngestionGate } from '@/components/ingestion-common'
import { IdentitySearch, OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { OfflinePolicy, OverrideValue, parameterScopeNames } from '@/components/offline-policy'
import { PageHelp } from '@/components/page-tools'
export function ParametersPage() { return <IngestionGate page="parameters"><ParametersContent/></IngestionGate> }
const BatteryOverride = lazy(() => import('@/components/battery-override').then(module => ({ default: module.BatteryOverride })))
function ParametersContent() {
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeInitialized = useSession(s => s.scopeInitialized)
  const scopeKey = `${scopeInitialized}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`
  const tree = useResource('parameter-scope-units', signal => api.scopeTree(signal))
  const selectedUnits = tree.data?.filter(unit => scopeTenantIds.includes(unit.id) || unit.places.some(place => scopePlaceIds.includes(place.id)))
  const definitions = useResource('parameter-definitions', signal => api.parameterDefinitions(signal))
  const [scope, setScope] = useState<ParameterScope | ''>(''), [tenantId, setTenantId] = useState(''), [device, setDevice] = useState<{ id: string; tenant_id: string } | null>(null), [key, setKey] = useState('')
  const [placeId, setPlaceId] = useState(''), [policyRevision, setPolicyRevision] = useState(0)
  const [groupId, setGroupId] = useState('')
  const chosenUnit = tree.data?.find(unit => unit.id === tenantId)
  const definition = definitions.data?.items.find(item => item.key === key && !!scope && item.allowed_scopes.includes(scope))
  const scopeId = scope === 'TENANT' ? tenantId : scope === 'PLACE' ? placeId : scope === 'GROUP' ? groupId : device?.id ?? ''
  const targetTenant = scope === 'DEVICE' ? device?.tenant_id ?? '' : tenantId
  const refreshPolicies = () => setPolicyRevision(revision => revision + 1)
  return <>
    <PageHeading eyebrow="PARAMETERS · 判定策略" title="设备策略" description="设置设备上报超时阈值，查看每台设备实际采用的规则。">
      <RefreshButton onClick={() => { definitions.refresh(); refreshPolicies() }}/><DataTransferActions kind="POLICY_DRAFTS" label="离线阈值配置" tenantId={targetTenant || undefined} filters={{ unit_ids: scopeTenantIds.join(','), place_ids: scopePlaceIds.join(',') }} onCompleted={receipt => { if ('plan_id' in receipt) refreshPolicies() }}/>
      <PageHelp title="策略继承与生效边界"><p>优先级：单台设备 → 设备分组 → 最近有配置的位置（含上级位置）→ 单位 → 全局。设备加入或退出分组后按当前成员关系计算；多个组有覆盖时，取较长阈值，同值按分组 ID 确定来源。分组不改变授权。</p><p>在阈值内有有效上报（包括自检）即显示在线。超时且有独立周期上报证据才判离线，否则显示“上报超时，离线未确认”。连接状态不改变物理火警，也不会因此发送付费通知。</p><p>左侧范围只筛选读取，不会更改已经选定的编辑目标。</p></PageHelp>
    </PageHeading>
    <Card className="list-card"><CardHeader><CardTitle>调整上报超时阈值</CardTitle></CardHeader><CardContent>
      <div className="parameter-selection">
      <div className="field"><Label htmlFor="parameter-scope">覆盖层级</Label>
        <select id="parameter-scope" className="native-select" value={scope} onChange={e => {
          setScope(e.target.value as ParameterScope | ''); setTenantId(''); setPlaceId(''); setGroupId(''); setDevice(null); setKey('')
        }}>
          <option value="">请先明确选择层级</option>
          <option value="TENANT">单位覆盖</option><option value="PLACE">位置覆盖</option>
          <option value="GROUP">设备分组覆盖</option><option value="DEVICE">单设备覆盖</option>
        </select>
      </div>
      {!!scope && scope !== 'DEVICE' && <div className="field"><Label htmlFor="parameter-tenant">目标单位</Label>{tree.loading ? <Loading/> : tree.error != null ? <ErrorNotice error={tree.error} retry={tree.refresh}/> : <select id="parameter-tenant" className="native-select" value={tenantId} onChange={e => { setTenantId(e.target.value); setPlaceId(''); setGroupId(''); setKey('') }}><option value="">请选择本次操作单位</option>{chosenUnit && !selectedUnits?.some(unit => unit.id === chosenUnit.id) && <option value={chosenUnit.id}>{chosenUnit.name}</option>}{selectedUnits?.map(unit => <option key={unit.id} value={unit.id}>{unit.name} · {unit.id}</option>)}</select>}</div>}
      {scope === 'PLACE' && <div className="field"><Label htmlFor="parameter-place">目标位置</Label><TreeSelect id="parameter-place" aria-label="目标位置" disabled={!chosenUnit} allowClear showSearch treeNodeFilterProp="title" value={placeId || undefined} treeData={placeTreeOptions(chosenUnit?.places ?? [])} placeholder="选择实际位置" onChange={value => { setPlaceId(value ?? ''); setKey('') }}/></div>}
      {scope === 'GROUP' && chosenUnit && <ParameterGroupPicker key={chosenUnit.id} tenantId={chosenUnit.id} value={groupId} choose={id => { setGroupId(id); setKey('') }}/>}
      {scope === 'DEVICE' && <ParameterTargetPicker value={device?.id ?? ''} choose={target => { setDevice(target); setKey('') }} scopeTenantIds={scopeTenantIds} scopePlaceIds={scopePlaceIds} scopeKey={scopeKey} scopeInitialized={scopeInitialized}/>}
      <div className="field"><Label htmlFor="parameter-key">覆盖参数</Label>{definitions.loading ? <Loading/> : definitions.error != null ? <ErrorNotice error={definitions.error} retry={definitions.refresh}/> : <select id="parameter-key" className="native-select" value={key} disabled={!scope || !scopeId} onChange={e => { const item = definitions.data?.items.find(item => item.key === e.target.value && !!scope && item.allowed_scopes.includes(scope) && item.value_type === 'INTEGER' && (item.key === 'offline_threshold_minutes' ? item.effect === 'LOCAL_DERIVATION' && item.consumer === 'CONNECTIVITY_AND_OFFLINE_EVENTS' : item.effect === 'QUERY_ONLY')); setKey(item?.key ?? '') }}><option value="">请选择允许该层覆盖的参数（不默认）</option>{definitions.data?.items.map(item => <option key={item.key} value={item.key} disabled={!scope || !item.allowed_scopes.includes(scope) || item.value_type !== 'INTEGER' || (item.key === 'offline_threshold_minutes' ? item.effect !== 'LOCAL_DERIVATION' || item.consumer !== 'CONNECTIVITY_AND_OFFLINE_EVENTS' : item.effect !== 'QUERY_ONLY')}>{item.name} · {item.key}</option>)}</select>}</div>
      {definition && <div className="parameter-definition"><strong>{definition.name}</strong><p>定义范围：{definition.min}–{definition.max} {definition.unit} · 类型 {definition.value_type} · 效果 {definition.effect}</p><p>目录读取时全局基础值：{definition.global_value} {definition.unit} · global revision {definition.global_revision}</p><p className="muted-note">消费者：{definition.consumer}。以同次PolicyView的effective/chain为准。</p></div>}
      </div>
    </CardContent></Card>
    {scope && scopeId && targetTenant && definition && !definitions.loading && <ParameterReader key={`${scope}:${scopeId}:${definition.key}:${definition.global_revision}`} scope={scope} scopeId={scopeId} tenantId={targetTenant} definition={definition} onSaved={refreshPolicies}/>}
    {scope === 'DEVICE' && device && <Suspense fallback={<Loading/>}><BatteryOverride key={device.id} deviceId={device.id} tenantId={device.tenant_id}/></Suspense>}
    <ParameterOverview definitions={definitions.data?.items ?? []} units={tree.data ?? []} scopeTenantIds={scopeTenantIds} scopePlaceIds={scopePlaceIds} scopeInitialized={scopeInitialized} scopeKey={`${scopeKey}:${policyRevision}`}/>
  </>
}
function ParameterOverview({ definitions, units, scopeTenantIds, scopePlaceIds, scopeInitialized, scopeKey }: { definitions: ParameterDefinition[]; units: ScopeUnit[]; scopeTenantIds: string[]; scopePlaceIds: string[]; scopeInitialized: boolean; scopeKey: string }) {
  const [devicePage, setDevicePage] = useState(1)
  const targets = useResource(`parameter-overview-targets:${devicePage}:${scopeKey}`, signal => api.parameterTargets(devicePage, '', signal, scopeTenantIds, scopePlaceIds), scopeInitialized)
  const tenantUnits = units.filter(unit => scopeTenantIds.includes(unit.id) || unit.places.some(place => scopePlaceIds.includes(place.id)))
  const offlineDefinition = definitions.find(item => item.key === 'offline_threshold_minutes')
  const tenantOverview = useResource(`parameter-overview-tenants:${scopeKey}:${tenantUnits.map(unit => unit.id).join(',')}:${offlineDefinition?.global_revision ?? ''}`, async signal => Promise.all(tenantUnits.map(async unit => ({ unit: unit.name, target: unit.id, id: unit.id, scope: 'TENANT' as const, policy: await api.parameterPolicy('TENANT', unit.id, 'offline_threshold_minutes', signal, unit.id) }))), scopeInitialized && !!offlineDefinition)
  const deviceOverview = useResource(`parameter-overview-devices:${devicePage}:${scopeKey}:${offlineDefinition?.global_revision ?? ''}`, async signal => Promise.all((targets.data?.items ?? []).map(async target => {
    if (!target.tenant_id) throw new Error('参数目标缺少单位归属')
    return { unit: units.find(unit => unit.id === target.tenant_id)?.name ?? target.tenant_id, target: target.name, id: target.id, scope: 'DEVICE' as const, policy: await api.parameterPolicy('DEVICE', target.id, 'offline_threshold_minutes', signal, target.tenant_id) }
  })), scopeInitialized && !!offlineDefinition && !!targets.data)
  const batteryDefinitions = useResource('battery-metric-definitions', signal => batteryApi.definitions(signal))
  const batteryOverview = useResource(`parameter-overview-battery:${devicePage}:${scopeKey}`, async signal => Promise.all((targets.data?.items ?? []).map(async target => {
    if (!target.tenant_id) throw new Error('参数目标缺少单位归属')
    return { unit: units.find(unit => unit.id === target.tenant_id)?.name ?? target.tenant_id, target: target.name, id: target.id, view: await batteryApi.view(target.id, target.tenant_id, signal) }
  })), scopeInitialized && !!targets.data)
  return <Card className="list-card"><CardHeader><CardTitle>当前授权范围策略总览</CardTitle><p className="muted-note">展示所选单位及所选位置所属单位的单位级策略，以及所选单位/位置内设备的设备级策略。</p></CardHeader><CardContent>
    {definitions.length === 0 ? <Empty title="暂无参数定义"/> : <div className="parameter-definition">{definitions.map(item => <p key={item.key}><strong>{item.name}</strong> · {item.global_value} {item.unit} · {item.effect} · {item.consumer}</p>)}</div>}
    {tenantOverview.loading || targets.loading || deviceOverview.loading || batteryDefinitions.loading || batteryOverview.loading ? <Loading/> : tenantOverview.error != null ? <ErrorNotice error={tenantOverview.error} retry={tenantOverview.refresh}/> : targets.error != null ? <ErrorNotice error={targets.error} retry={targets.refresh}/> : deviceOverview.error != null ? <ErrorNotice error={deviceOverview.error} retry={deviceOverview.refresh}/> : batteryDefinitions.error != null ? <ErrorNotice error={batteryDefinitions.error} retry={batteryDefinitions.refresh}/> : batteryOverview.error != null ? <ErrorNotice error={batteryOverview.error} retry={batteryOverview.refresh}/> : <>
      <Table><TableHeader><TableRow><TableHead>层级</TableHead><TableHead>单位 / 设备</TableHead><TableHead>目标</TableHead><TableHead>本层覆盖</TableHead><TableHead>当前生效值</TableHead><TableHead>来源</TableHead><TableHead>Revision</TableHead></TableRow></TableHeader><TableBody>
        {[...(tenantOverview.data ?? []), ...(deviceOverview.data ?? [])].flatMap(row => definitions.filter(item => item.key === row.policy.key).map(item => <TableRow key={`${row.scope}:${row.id}:${item.key}`}><TableCell>{row.scope === 'TENANT' ? '单位' : '设备'}</TableCell><TableCell>{row.unit}</TableCell><TableCell>{row.target}</TableCell><TableCell>{row.policy.has_override ? `${row.policy.value} ${item.unit}` : row.policy.revision === 0 ? '无本层覆盖 · 继承' : '已清除 · 继承'}</TableCell><TableCell>{row.policy.effective.value} {item.unit}</TableCell><TableCell>{row.policy.effective.source_scope} {row.policy.effective.source_id ?? ''}</TableCell><TableCell>{row.policy.revision}</TableCell></TableRow>))}
      </TableBody></Table>
      {batteryDefinitions.data?.items.map(definition => <p key={definition.key}><strong>{definition.name}</strong> · {definition.metric_key} · {definition.min}–{definition.max}{definition.unit} · {definition.effect} · {definition.consumer}</p>)}
      {(batteryOverview.data?.length ?? 0) > 0 && <Table><TableHeader><TableRow><TableHead>电量策略单位</TableHead><TableHead>设备</TableHead><TableHead>设备覆盖</TableHead><TableHead>当前生效值</TableHead><TableHead>来源</TableHead><TableHead>Revision</TableHead></TableRow></TableHeader><TableBody>{batteryOverview.data?.map(row => <TableRow key={`battery:${row.id}`}><TableCell>{row.unit}</TableCell><TableCell>{row.target} · {row.id}</TableCell><TableCell>{row.view.override.has_value ? `${row.view.override.threshold_json}${row.view.override.comparator}` : '未设置覆盖'}</TableCell><TableCell>{row.view.effective.configured ? `${row.view.effective.threshold_json}${row.view.effective.comparator}` : '未配置'}</TableCell><TableCell>{row.view.effective.source_scope} {row.view.effective.source_id ?? ''}</TableCell><TableCell>{row.view.override.revision}</TableCell></TableRow>)}</TableBody></Table>}
      {(tenantOverview.data?.length ?? 0) + (deviceOverview.data?.length ?? 0) === 0 && <Empty title="当前选中范围暂无策略目标"/>}
      {targets.data && <Pager page={targets.data.page} size={targets.data.size} total={targets.data.total} onChange={setDevicePage}/>}
    </>}
  </CardContent></Card>
}
function ParameterGroupPicker({ tenantId, value, choose }: { tenantId: string; value: string; choose: (id: string) => void }) {
  const [page, setPage] = useState(1)
  const groups = useResource(`parameter-groups:${tenantId}:${page}`, signal =>
    request<{ items: { id: string; name: string }[]; total: number; size: number }>(`/management/parameter-groups?page=${page}`, { signal, tenantScoped: true, tenantId }))
  return <div className="field"><Label htmlFor="parameter-group">目标设备分组</Label>
    {groups.loading ? <Loading/> : groups.error != null ? <ErrorNotice error={groups.error} retry={groups.refresh}/> :
      groups.data && (groups.data.items.length > 0 ? <>
        <select id="parameter-group" className="native-select" value={value} onChange={e => choose(groups.data?.items.find(item => item.id === e.target.value)?.id ?? '')}>
          <option value="">请选择当前单位的设备分组</option>
          {groups.data.items.map(group => <option key={group.id} value={group.id}>{group.name}</option>)}
        </select>
        {groups.data.total > groups.data.size && <Pager page={page} size={groups.data.size} total={groups.data.total} onChange={next => { choose(''); setPage(next) }}/>}
      </> : <Empty title="当前单位未建分组">先到「设备分组」创建分组并维护成员。</Empty>)}
    <p className="muted-note">设置影响当前和以后加入分组的设备，需要全单位设备权限。</p>
  </div>
}
function ParameterTargetPicker({ value, choose, scopeTenantIds, scopePlaceIds, scopeKey, scopeInitialized }: { value: string; choose: (target: { id: string; tenant_id: string } | null) => void; scopeTenantIds: string[]; scopePlaceIds: string[]; scopeKey: string; scopeInitialized: boolean }) {
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState('')
  const targets = useResource(`parameter-targets:${page}:${keyword}:${scopeKey}`, signal => api.parameterTargets(page, keyword, signal, scopeTenantIds, scopePlaceIds), scopeInitialized)
  return <section className="parameter-target" aria-label="选择参数目标设备"><IdentitySearch aria-label="搜索参数目标设备" placeholder="按最小设备目录名称或标识查询（字面匹配）" onSearch={value => { setKeyword(value); setPage(1); choose(null) }}/><Label htmlFor="parameter-device">参数目标设备</Label>{targets.loading ? <Loading/> : targets.error != null ? <ErrorNotice error={targets.error} retry={targets.refresh}/> : <><select id="parameter-device" className="native-select" value={value} onChange={e => { const target = targets.data?.items.find(item => item.id === e.target.value); choose(target?.tenant_id ? { id: target.id, tenant_id: target.tenant_id } : null) }}><option value="">请明确选择已授权范围内的设备（不默认）</option>{targets.data?.items.map(item => <option key={item.id} value={item.id}>{reported(item.name)} · {item.id} · {item.tenant_id}</option>)}</select>{targets.data && <Pager page={targets.data.page} size={targets.data.size} total={targets.data.total} onChange={next => { setPage(next); choose(null) }}/>}</>}<p className="muted-note">仅parameter-targets最小目录，只返回id/name/tenant_id，不要求device:read、device:write或其它来源权限。</p></section>
}
function ParameterReader({ scope, scopeId, tenantId, definition, onSaved }: { scope: ParameterScope; scopeId: string; tenantId: string; definition: ParameterDefinition; onSaved: () => void }) {
  const canWrite = useSession(s => s.permissions.includes('parameters:write'))
  const [historyPage, setHistoryPage] = useState(1), [editor, setEditor] = useState<'SET' | 'CLEAR' | null>(null), [notice, setNotice] = useState('')
  const identity = `${scope}:${scopeId}:${definition.key}:${tenantId}`
  const policy = useResource(`parameter-policy:${identity}`, signal => api.parameterPolicy(scope, scopeId, definition.key, signal, tenantId))
  const history = useResource(`parameter-history:${identity}:${historyPage}`, signal => api.parameterHistory(scope, scopeId, definition.key, historyPage, signal, tenantId))
  const current = policy.data
  const matches = definition.value_type === 'INTEGER' && (definition.key === 'offline_threshold_minutes' ? definition.effect === 'LOCAL_DERIVATION' && definition.consumer === 'CONNECTIVITY_AND_OFFLINE_EVENTS' : definition.effect === 'QUERY_ONLY') && definition.allowed_scopes.includes(scope) && Number.isSafeInteger(definition.min) && Number.isSafeInteger(definition.max) && definition.min <= definition.max && !!current && current.scope === scope && current.scope_id === scopeId && current.key === definition.key && current.tenant_id === tenantId && typeof current.has_override === 'boolean' && Number.isSafeInteger(current.revision) && current.revision >= 0 && current.consumer === 'CONNECTIVITY_AND_OFFLINE_EVENTS'
  function refresh() { setEditor(null); setHistoryPage(1); policy.refresh(); history.refresh() }
  function saved(result: ParameterMutation) { setNotice(`已保存 ${result.change.action} · 变更 ${result.change.id}；当前生效阈值 ${result.policy.effective.value} ${definition.unit}。`); refresh(); onSaved() }
  return <Card className="parameter-policy-card">
    <CardHeader className="section-header"><div><CardTitle>{parameterScopeNames[scope]}覆盖 · {definition.name}</CardTitle><p className="mono">{scopeId} · {definition.key}</p></div><Button type="button" variant="outline" onClick={refresh}>刷新覆盖与历史</Button></CardHeader>
    <CardContent>
      {notice && <div role="status" className="notice">{notice}</div>}
      {policy.loading ? <Loading/> : policy.error != null ? <ErrorNotice error={policy.error} retry={policy.refresh}/> : current && <>
        <dl className="detail-list"><div><dt>当前覆盖</dt><dd>{current.has_override ? <OverrideValue value={current.value}/> : current.revision === 0 ? '从未覆盖 · 继承' : '已清除覆盖 · 继承（保留tombstone）'}</dd></div><div><dt>本层revision</dt><dd>{current.revision}</dd></div><div><dt>实际消费者</dt><dd>{current.consumer}</dd></div></dl>
        <OfflinePolicy policy={current.effective} detailed/>
        {!matches && <div role="alert" className="notice">策略身份或revision未确认，禁止写入；请刷新，不会套用默认值。</div>}
        <div className="version-actions">{canWrite
          ? <><Button type="button" disabled={!matches} onClick={() => setEditor('SET')}><SlidersHorizontal/>设置参数覆盖</Button><Button type="button" variant="outline" disabled={!matches || !current.has_override} onClick={() => setEditor('CLEAR')}><Eraser/>清除本层覆盖</Button></>
          : <span className="muted-note">当前仅parameters:read：可读策略/继承/历史，不能设置或清除。</span>}
        </div>
        <h3 className="mt-5 mb-3">实际继承链</h3>
        <Table><TableHeader><TableRow><TableHead>层级 / 目标</TableHead><TableHead>覆盖值</TableHead><TableHead>revision</TableHead><TableHead>是否生效</TableHead></TableRow></TableHeader><TableBody>
          {current.chain.map(entry => <TableRow key={`${entry.scope}:${entry.scope_id}`}><TableCell>{parameterScopeNames[entry.scope] ?? '未知层级'} · {entry.scope}<div className="table-sub mono">{entry.scope_id ?? '全局基础项'}</div></TableCell><TableCell><OverrideValue value={entry.has_override ? entry.value : null}/></TableCell><TableCell>{entry.revision}</TableCell><TableCell>{entry.is_effective ? '当前有效来源' : '未生效'}</TableCell></TableRow>)}
        </TableBody></Table>
      </>}
      <section aria-labelledby="parameter-history-heading">
      <h3 id="parameter-history-heading" className="mt-5 mb-3">不可变覆盖历史</h3>
      {history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data && <>
        {history.data.items.length ? <Table><TableHeader><TableRow><TableHead>动作 / 变更</TableHead><TableHead>前值 → 后值</TableHead><TableHead>revision</TableHead><TableHead>操作人 / 原因 / 时间</TableHead></TableRow></TableHeader><TableBody>
          {history.data.items.map(change => <TableRow key={change.id}><TableCell>{change.action}<div className="table-sub mono">{change.id}</div></TableCell><TableCell><OverrideValue value={change.previous_value}/> → <OverrideValue value={change.value}/></TableCell><TableCell>{change.revision_before} → {change.revision_after}</TableCell><TableCell>{change.actor_id}<div className="table-sub">{change.reason}</div><div className="table-sub">{dateTime(change.created_at)}</div></TableCell></TableRow>)}
        </TableBody></Table> : <Empty title="暂无覆盖变更历史"/>}
        <Pager page={history.data.page} size={history.data.size} total={history.data.total} onChange={setHistoryPage}/>
      </>}
      </section>
      <Sheet open={!!editor && canWrite && matches} onOpenChange={open => { if (!open) setEditor(null) }}>
        <SheetContent className="review-sheet sm:max-w-xl">{editor && canWrite && matches && current && <ParameterForm key={`${editor}:${identity}:${current.revision}`} actionType={editor} policy={current} definition={definition} saved={saved} refresh={refresh}/>}</SheetContent>
      </Sheet>
    </CardContent>
  </Card>
}
function ParameterForm({ actionType, policy, definition, saved, refresh }: { actionType: 'SET' | 'CLEAR'; policy: ParameterPolicy; definition: ParameterDefinition; saved: (result: ParameterMutation) => void; refresh: () => void }) {
  const [value, setValue] = useState(policy.has_override && policy.value !== null ? String(policy.value) : ''), [reason, setReason] = useState('')
  const action = useMutationAction()
  const title = actionType === 'SET' ? '设置参数覆盖' : '清除本层覆盖'
  function submit(event: FormEvent) {
    event.preventDefault()
    try { revisionInteger(policy.revision); if (actionType === 'SET') definedInteger(value, definition); if (!reason.trim()) throw new Error('请明确填写本次设置/清除原因'); if (actionType === 'CLEAR' && !policy.has_override) throw new Error('当前没有覆盖，不能伪造CLEAR历史') } catch (error) { action.setError(error); return }
    if (!action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    let result: ParameterMutation
    void action.run(async (key, signal) => { result = actionType === 'SET' ? await api.setParameter(policy.scope, policy.scope_id, policy.key, { value_text: value, expected_revision: policy.revision, reason: reason.trim() }, key, signal, policy.tenant_id) : await api.clearParameter(policy.scope, policy.scope_id, policy.key, { expected_revision: policy.revision, reason: reason.trim() }, key, signal, policy.tenant_id) }, () => saved(result))
  }
  return <>
    <SheetHeader className="review-header"><SheetTitle>{title}</SheetTitle><SheetDescription>阈值只影响连接判定，不改变物理状态，也不发送付费通知。</SheetDescription></SheetHeader>
    <div className="review-body"><form className="form-stack" onSubmit={submit}>
      <div className="identity-readonly mono">{parameterScopeNames[policy.scope]} · {policy.scope_id} · revision {policy.revision}</div>
      {!action.confirming ? <fieldset className="form-stack" disabled={action.busy}>
        {actionType === 'SET' ? <div className="field">
          <Label htmlFor="parameter-value">覆盖整数值</Label><Input id="parameter-value" type="text" inputMode="numeric" required value={value} onChange={e => { setValue(e.target.value); action.touch() }} placeholder="输入上报超时阈值"/>
          <p className="muted-note">{definition.min}–{definition.max} {definition.unit}，只接受整数。</p>
        </div> : <div className="notice">清除 {policy.value} {definition.unit} 的覆盖后，重新继承最近的上级规则；历史保留。</div>}
        <div className="field"><Label htmlFor="parameter-reason">参数变更原因</Label><Input id="parameter-reason" required value={reason} onChange={e => { setReason(e.target.value); action.touch() }}/></div>
        {action.error != null && <ErrorNotice error={action.error}/>}
        <Button type="submit" disabled={action.busy}>核对参数变更</Button>
      </fieldset> : <OperationConfirmation action={action} title={`确认${title}`} submitLabel={`确认${title}`} refresh={refresh}
        warning={policy.scope === 'GROUP' ? '影响当前及以后加入此分组、且由该组策略生效的设备。' : policy.scope === 'PLACE' ? '影响该位置及继承其策略的下级设备。' : '影响该范围内继承此策略的设备。'}>
        <p>目标：{parameterScopeNames[policy.scope]} · {policy.scope_id}</p>
        <p>当前覆盖：{policy.has_override ? `${policy.value} ${definition.unit}` : '未设置，继承上级'}</p>
        <p>拟变更：{actionType === 'SET' ? `${value} ${definition.unit}` : '清除本层覆盖，恢复继承上级规则'}</p><p>原因：{reason.trim()}</p>
      </OperationConfirmation>}
    </form></div>
  </>
}
