import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { ApiError, api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { captureGroupScope, getDeviceGroups, getGroupMembers, getGroupCandidates, groupCommandIdentity, groupKeyword, groupMetadata, submitGroupCommand, GroupWriteUnconfirmed, type DeviceGroup, type GroupDevice, type GroupCommand, type GroupPage, type GroupMemberPage, type GroupCandidatePage } from '@/lib/device-groups'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { publishObjectChange } from '@/lib/object-events'
import { scopeTargetTenantId } from '@/lib/operation-target'
const wrap = { minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' as const }
type Query = { page: number; keyword: string; revision: number }
const initial = (): Query => ({ page: 1, keyword: '', revision: 0 })
function useGroupRead<T>(key: string, enabled: boolean, load: (signal: AbortSignal, live: () => boolean) => Promise<T>, failure: (e: unknown) => void) {
  const serial = useRef(0), pending = useRef<AbortController | null>(null), loader = useRef(load), onError = useRef(failure); loader.current = load; onError.current = failure
  const [state, setState] = useState<{ key: string; loading: boolean; data?: T; error?: unknown }>({ key, loading: enabled })
  function invalidate() { ++serial.current; pending.current?.abort(); pending.current = null }
  useLayoutEffect(() => {
    const id = ++serial.current, c = new AbortController(), scope = captureGroupScope(); pending.current = c
    const live = () => serial.current === id && !c.signal.aborted && scope()
    setState({ key, loading: enabled })
    if (enabled) void Promise.resolve().then(() => { if (!live()) throw new DOMException('已取消', 'AbortError'); return loader.current(c.signal, live) }).then(data => { if (live()) setState({ key, loading: false, data }) }, error => { if (live()) { setState({ key, loading: false, error }); onError.current(error) } })
    return () => { if (serial.current === id) ++serial.current; c.abort() }
  }, [key, enabled])
  const visible = state.key === key ? state : { key, loading: enabled }
  return { ...visible, invalidate, clear: () => { invalidate(); setState({ key, loading: false }) } }
}
export function DeviceGroupsPage() {
  const s = useSession(), metadata = JSON.stringify([s.user, s.generation, s.permissions, s.audience, s.page]), frame = useRef({ token: s.token, metadata, version: 0 })
  if (frame.current.token !== s.token || frame.current.metadata !== metadata) frame.current = { token: s.token, metadata, version: frame.current.version + 1 }
  const management = s.audience === 'management', correct = management ? s.user?.user_type === 'PLATFORM' : s.user?.user_type === 'CUSTOMER'
  if (!s.token || !correct || s.user?.must_change_password || !s.permissions.includes('device:read') || s.page !== 'device-groups') return <Empty title="当前无设备分组读取范围"/>
  return <Content key={frame.current.version} writable={management && s.permissions.includes('device:write')} management={management} tenantIds={s.scopeTenantIds} placeIds={s.scopePlaceIds} initialized={management ? s.scopeInitialized : true}/>
}
type Editor = { kind: 'create'; group?: undefined; name: string; description: string } | { kind: 'edit'; group: DeviceGroup; name: string; description: string } | { kind: 'add' | 'remove'; group: DeviceGroup; device: GroupDevice }
function Search({ label, apply }: { label: string; apply: (keyword: string) => void }) { const [draft, setDraft] = useState(''), [error, setError] = useState<unknown>(); function submit(e: FormEvent) { e.preventDefault(); try { const k = groupKeyword(draft); setError(undefined); apply(k) } catch (e) { setError(e) } }; return <form onSubmit={submit} className="search-form" style={{ ...wrap, flexWrap: 'wrap', marginBottom: 12 }}><Input aria-label={label} value={draft} onChange={e => setDraft(e.target.value)} placeholder="字面搜索，% / _ / !不是通配符"/><Button type="submit" variant="outline">查询{label.replace('关键字', '')}</Button>{error != null && <ErrorNotice error={error}/>}</form> }
function Reference({ device }: { device: GroupDevice }) { return <dl className="detail-list" style={wrap}><div><dt>设备ID</dt><dd style={wrap}>{device.device_id}</dd></div><div><dt>设备名称</dt><dd style={wrap}>{device.device_name === '' ? '空字符串（原值）' : device.device_name}</dd></div><div><dt>设备EUI</dt><dd style={wrap}>{device.device_eui === '' ? '空字符串（原值）' : device.device_eui}</dd></div><div><dt>安装地址</dt><dd style={wrap}>{device.address === '' ? '空字符串（原值）' : device.address}</dd></div></dl> }
function Content({ writable, management, tenantIds, placeIds, initialized }: { writable: boolean; management: boolean; tenantIds: string[]; placeIds: string[]; initialized: boolean }) {
  const [listQuery, setListQuery] = useState(initial), [memberQuery, setMemberQuery] = useState(initial), [candidateQuery, setCandidateQuery] = useState(initial), [selected, setSelected] = useState<string | null>(null), [candidatesOpen, setCandidatesOpen] = useState(false), [gate, setGate] = useState(true)
  const scopeKey = `${tenantIds.join(',')}:${placeIds.join(',')}:${initialized}`
  const targetTree = useResource('device-group-write-targets', signal => api.scopeTree(signal), writable)
  const allowedTargets = targetTree.data ?? []
  const [targetTenant, setTargetTenant] = useState('')
  const targetPrefill = useRef<{ tenantIds: string[]; placeIds: string[] } | null>(null)
  const [editor, setEditor] = useState<Editor | null>(null), [confirming, setConfirming] = useState(false), [busy, setBusy] = useState(false), [writeError, setWriteError] = useState<unknown>(), [conflict, setConflict] = useState(false), [error, setError] = useState<unknown>(), [notice, setNotice] = useState(''), [receipt, setReceipt] = useState<DeviceGroup>()
  const alive = useRef(true), scope = useRef(captureGroupScope(false)), selection = useRef(selected), writeSerial = useRef(0), pending = useRef<{ id: number; controller: AbortController } | null>(null), intent = useRef<{ identity: string; key: string; uncertain: boolean } | null>(null), drafts = useRef(new Map<string, { name: string; description: string; targetTenant?: string }>())
  const active = () => alive.current && scope.current()
  const canReadGroups = gate && (!management || tenantIds.length > 0 || placeIds.length > 0)
  const groups = useGroupRead<GroupPage>(JSON.stringify([listQuery, scopeKey]), canReadGroups, (s, live) => getDeviceGroups(listQuery, s, live, tenantIds, placeIds), readFailure)
  const members = useGroupRead<GroupMemberPage>(JSON.stringify([selected, memberQuery, scopeKey]), gate && !!selected, (s, live) => getGroupMembers(selected!, memberQuery, s, live, tenantIds, placeIds), readFailure)
  const candidates = useGroupRead<GroupCandidatePage>(JSON.stringify([selected, candidateQuery, scopeKey]), gate && !!selected && candidatesOpen && writable, (s, live) => getGroupCandidates(selected!, candidateQuery, s, live, tenantIds, placeIds), readFailure)
  function stopWrite() { ++writeSerial.current; pending.current?.controller.abort(); pending.current = null; if (active()) setBusy(false) }
  useLayoutEffect(() => { alive.current = true; return () => { alive.current = false; ++writeSerial.current; pending.current?.controller.abort(); pending.current = null; intent.current = null; drafts.current.clear() } }, [])
  function clearReads() { groups.clear(); members.clear(); candidates.clear() }
  function remember() { if (editor?.kind === 'create' || editor?.kind === 'edit') drafts.current.set(`${editor.kind}:${editor.group?.id ?? ''}`, { name: editor.name, description: editor.description, ...(editor.kind === 'create' ? { targetTenant } : {}) }) }
  function closeEditor() { if (!active()) return; remember(); if (pending.current && intent.current) intent.current.uncertain = true; stopWrite(); setEditor(null); setConfirming(false); setWriteError(undefined) }
  function readFailure(e: unknown) { if (!active()) return; if (e instanceof ApiError && e.status === 403) { closeEditor(); clearReads(); setGate(false); selection.current = null; setSelected(null); setCandidatesOpen(false) }; setError(e) }
  function refresh() { if (!active()) return; closeEditor(); if (intent.current?.uncertain && !conflict) intent.current.uncertain = false; else intent.current = null; setConflict(false); clearReads(); selection.current = null; setSelected(null); setCandidatesOpen(false); setGate(true); setListQuery(q => ({ ...q, revision: q.revision + 1 })); setError(undefined); setNotice(''); setReceipt(undefined) }
  function choose(id: string | null) { if (!active()) return; closeEditor(); intent.current = null; members.clear(); candidates.clear(); selection.current = id; setSelected(id); setCandidatesOpen(false); setMemberQuery(q => ({ ...initial(), revision: q.revision + 1 })); setCandidateQuery(q => ({ ...initial(), revision: q.revision + 1 })); setError(undefined); setReceipt(undefined); setNotice('') }
  function listChange(page: number, keyword = listQuery.keyword) { if (!active()) return; choose(null); groups.invalidate(); setListQuery(q => ({ page, keyword, revision: q.revision + 1 })) }
  function memberChange(page: number, keyword = memberQuery.keyword) { if (!active()) return; closeEditor(); members.invalidate(); candidates.clear(); setCandidatesOpen(false); setMemberQuery(q => ({ page, keyword, revision: q.revision + 1 })); setError(undefined) }
  function candidateChange(page: number, keyword = candidateQuery.keyword) { if (!active()) return; closeEditor(); candidates.invalidate(); setCandidateQuery(q => ({ page, keyword, revision: q.revision + 1 })); setError(undefined) }
  function closeCandidates() { if (!active()) return; closeEditor(); candidates.clear(); setCandidatesOpen(false) }
  function latestGroup(value: DeviceGroup) { return [members.data?.group, candidates.data?.group].reduce<DeviceGroup>((latest, v) => v?.id === latest.id && v.tenant_id === latest.tenant_id && v.revision > latest.revision ? v : latest, value) }
  useEffect(() => {
    const snapshot = targetPrefill.current
    if (!snapshot || !targetTree.data) return
    targetPrefill.current = null
    setTargetTenant(scopeTargetTenantId(targetTree.data, snapshot.tenantIds, snapshot.placeIds) ?? '')
  }, [targetTree.data])
  function open(next: Editor) { if (!active() || !writable) return; closeEditor(); if (next.kind === 'edit') { const latest = latestGroup(next.group); next = { ...next, group: latest, name: latest.name, description: latest.description } }; const draft = next.kind === 'create' || next.kind === 'edit' ? drafts.current.get(`${next.kind}:${next.group?.id ?? ''}`) : undefined; if (next.kind === 'create') { if (draft) setTargetTenant(draft.targetTenant ?? ''); else { const snapshot = { tenantIds: [...tenantIds], placeIds: [...placeIds] }; if (targetTree.data) setTargetTenant(scopeTargetTenantId(targetTree.data, snapshot.tenantIds, snapshot.placeIds) ?? ''); else targetPrefill.current = snapshot } }; setEditor(draft && (next.kind === 'create' || next.kind === 'edit') ? { ...next, ...draft } : next); setConfirming(false); setWriteError(undefined); setNotice('') }
  function command(): GroupCommand { if (!editor) throw new Error('请先显式选择本次维护目标。'); return editor.kind === 'create' ? { kind: 'create', tenant_id: targetTenant, ...groupMetadata(editor.name, editor.description) } : editor.kind === 'edit' ? { kind: 'edit', group: editor.group!, ...groupMetadata(editor.name, editor.description) } : { kind: editor.kind, group: editor.group, device_id: editor.device.device_id } }
  function submit(event: FormEvent) {
    event.preventDefault(); if (!active() || !writable || pending.current || conflict) return
    let c: GroupCommand, identity: string
    try { c = command(); const operationTenant = c.kind === 'create' ? c.tenant_id : c.group.tenant_id; identity = groupCommandIdentity(c, operationTenant); if (intent.current?.uncertain && identity !== intent.current.identity) throw new Error('旧意图结果未确认；修改业务内容前必须显式刷新分组并重开新意图。'); if (c.kind !== 'create' && c.kind !== 'edit' && !confirming) { setWriteError(undefined); setConfirming(true); return } } catch (e) { setWriteError(e); return }
    const operationTenant = c.kind === 'create' ? c.tenant_id : c.group.tenant_id
    if (!operationTenant) { setWriteError(new Error('请明确选择本次分组写入单位。')); return }
    if (!intent.current || intent.current.identity !== identity) intent.current = { identity, key: crypto.randomUUID(), uncertain: false }
    const original = intent.current, controller = new AbortController(), id = ++writeSerial.current, selectedAtStart = selection.current, current = () => active() && id === writeSerial.current && !controller.signal.aborted && selection.current === selectedAtStart
    pending.current = { id, controller }; setBusy(true); setWriteError(undefined); original.uncertain = true
    void submitGroupCommand(c, operationTenant, original.key, controller.signal, current).then(result => {
      if (!current()) return
      setBusy(false); pending.current = null; setEditor(null); setConfirming(false); setWriteError(undefined); setConflict(false); setReceipt(result); setNotice('本次提交已确认；以下仅提交快照，请显式刷新读取当前事实。'); drafts.current.clear(); intent.current = null; clearReads(); setGate(false); selection.current = null; setSelected(null); setCandidatesOpen(false); ++writeSerial.current; publishObjectChange('organization')
    }, e => {
      if (!current()) return
      setBusy(false); if (pending.current?.id === id) pending.current = null
      setWriteError(e)
      if (e instanceof ApiError && e.status === 409) { original.uncertain = false; setConflict(true); remember() }
      else if (e instanceof ApiError && e.status === 403) { closeEditor(); clearReads(); setGate(false); selection.current = null; setSelected(null); setCandidatesOpen(false); setError(e) }
    }).finally(() => { if (current() && pending.current?.id === id) { pending.current = null; setBusy(false) } })
  }
  function cancelSubmission() { if (!active()) return; const sent = !!pending.current; if (sent && intent.current) intent.current.uncertain = true; stopWrite(); if (sent) setWriteError(new GroupWriteUnconfirmed()) }
  const selectedGroup = members.data?.group
  const title = editor?.kind === 'create' ? '创建分组' : editor?.kind === 'edit' ? '编辑分组' : editor?.kind === 'add' ? '加入设备' : '移出设备'
  const listActions = <><Button variant="outline" onClick={refresh}>刷新分组</Button>{writable && gate && <Button aria-label="创建分组" onClick={() => open({ kind: 'create', name: '', description: '' })}>创建分组</Button>}</>
  return <section aria-label="设备分组" className="device-workbench" style={wrap}>
    <PageHeading eyebrow="GROUP · 单位内组织" title="设备分组"><PageHelp title="设备分组操作边界">读取范围由左侧单位 / 位置树控制，分组是单位内的多对多组织。成员数不是在线数、授权数或历史次数。设备引用是读取时元数据；按设备ID维护关系，不修改设备revision、名称、地址、上报或报警。当前不支持删除、批量、树、导入导出、组授权或通知规则。</PageHelp>{!management && listActions}</PageHeading>
    {notice && <p role="status">{notice}</p>}{error != null && <ErrorNotice error={error}/>}
    {receipt && <section aria-label="分组提交快照" data-group-receipt-id={receipt.id} className="notice" style={wrap}><h2>提交快照（不代表当前版本）</h2><p>{receipt.id} · {receipt.name} · revision {receipt.revision} · 提交时成员 {receipt.member_count}</p><p style={{ whiteSpace: 'pre-wrap' }}>{receipt.description}</p><p>未patch列表或成员；请点击刷新分组。</p></section>}
    <section aria-label="分组列表" className={management ? 'management-list-panel management-record-list' : undefined} style={wrap}>
      {management ? <>
        <div className="management-list-bar"><h2>单位分组</h2><div className="list-toolbar management-list-actions">{listActions}</div></div>
        {writable && <p className="management-list-context">创建分组须明确选择写入单位，不改变下方读取范围。</p>}
      </> : <h2>单位分组</h2>}
      <Search label="分组关键字" apply={k => listChange(1, k)}/>
      {management && initialized && tenantIds.length === 0 && placeIds.length === 0 ? <Empty title="当前范围未选择"/> : groups.loading ? <Loading/> : groups.data ? <>
        <p className={management ? 'management-list-context' : undefined}>已应用分组关键字：{listQuery.keyword || '无'} · 本次每页 {groups.data.size} 条（服务端实际值）</p>
        {groups.data.items.length === 0 ? <Empty title="本筛选无匹配分组"/> : management ? <Table className="management-record-table" aria-label="设备分组记录">
          <TableHeader><TableRow><TableHead>分组 / 说明</TableHead><TableHead>单位 / 编号</TableHead><TableHead>成员数</TableHead><TableHead>版本</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>{groups.data.items.map(g => <TableRow key={g.id} data-device-group-id={g.id}>
            <TableCell><strong>{g.name}</strong><p className="table-sub whitespace-pre-wrap">{g.description}</p></TableCell>
            <TableCell>{g.tenant_id}<div className="table-sub mono">{g.id}</div></TableCell><TableCell>{g.member_count}</TableCell><TableCell>{g.revision}</TableCell>
            <TableCell className="text-right"><div className="version-actions"><Button variant="outline" size="sm" onClick={() => choose(g.id)}>查看组成员</Button>{writable && <Button variant="outline" size="sm" onClick={() => open({ kind: 'edit', group: g, name: g.name, description: g.description })}>编辑分组</Button>}</div></TableCell>
          </TableRow>)}</TableBody>
        </Table> : <div style={{ display: 'grid', gap: 12 }}>{groups.data.items.map(g => <article key={g.id} data-device-group-id={g.id} style={{ ...wrap, border: '1px solid #e3e8db', borderRadius: 12, padding: 16 }}><h3>{g.name}</h3><p style={{ whiteSpace: 'pre-wrap' }}>{g.description}</p><p style={wrap}>{g.id} · 单位ID {g.tenant_id} · revision {g.revision} · 全部成员 {g.member_count}</p><div className="version-actions"><Button variant="outline" onClick={() => choose(g.id)}>查看组成员</Button>{writable && <Button variant="outline" onClick={() => open({ kind: 'edit', group: g, name: g.name, description: g.description })}>编辑分组</Button>}</div></article>)}</div>}
        <Pager page={groups.data.page} size={groups.data.size} total={groups.data.total} onChange={p => listChange(p)}/>
      </> : null}
    </section>
    {selected && <section aria-label="分组成员" data-selected-group-id={selected} style={wrap}><h2>分组成员</h2><Search key={selected} label="成员关键字" apply={k => memberChange(1, k)}/><div className="version-actions"><Button variant="outline" onClick={() => memberChange(memberQuery.page)}>刷新成员</Button><Button variant="outline" onClick={() => choose(null)}>关闭成员</Button>{writable && selectedGroup && <Button variant="outline" onClick={() => { closeEditor(); setCandidatesOpen(true); setCandidateQuery(q => ({ ...q, revision: q.revision + 1 })) }}>打开设备候选</Button>}</div>
      {members.loading ? <Loading/> : members.data ? <><p style={wrap}>{members.data.group.name} · revision {members.data.group.revision} · 全部成员 {members.data.group.member_count} · 匹配成员 {members.data.total} · 本次每页 {members.data.size} 条</p>{members.data.items.length === 0 ? <Empty title={members.data.group.member_count > 0 ? '本筛选无匹配成员' : '本组无成员'}/> : members.data.items.map(d => <article key={d.device_id} data-group-member-id={d.device_id} style={{ ...wrap, marginTop: 12, padding: 12, border: '1px solid #e3e8db' }}><Reference device={d}/>{writable && <Button variant="outline" onClick={() => open({ kind: 'remove', group: members.data!.group, device: d })}>移出成员</Button>}</article>)}<Pager page={members.data.page} size={members.data.size} total={members.data.total} onChange={p => memberChange(p)}/></> : null}
    </section>}
    {selected && candidatesOpen && writable && <section aria-label="分组设备候选" style={wrap}><h2>当前单位设备候选</h2><Search key={selected} label="候选关键字" apply={k => candidateChange(1, k)}/><div className="version-actions"><Button variant="outline" onClick={() => candidateChange(candidateQuery.page)}>刷新候选</Button><Button variant="outline" onClick={closeCandidates}>关闭候选</Button></div>{candidates.loading ? <Loading/> : candidates.data ? <><p>候选快照group revision {candidates.data.group.revision} · 全部成员 {candidates.data.group.member_count} · 匹配设备 {candidates.data.total} · 本次每页 {candidates.data.size} 条</p>{candidates.data.items.length === 0 ? <Empty title="本筛选无匹配候选设备"/> : candidates.data.items.map(d => <article key={d.device_id} data-group-candidate-id={d.device_id} style={{ ...wrap, marginTop: 12, padding: 12, border: '1px solid #e3e8db' }}><Reference device={d}/>{d.is_member ? <p>已加入</p> : <Button variant="outline" onClick={() => open({ kind: 'add', group: candidates.data!.group, device: d })}>选择加入设备</Button>}</article>)}<Pager page={candidates.data.page} size={candidates.data.size} total={candidates.data.total} onChange={p => candidateChange(p)}/></> : null}</section>}
    {editor && writable && <section aria-label="分组变更表单" style={{ ...wrap, border: '1px solid #c6d5bb', borderRadius: 12, padding: 16 }}><h2>{title}</h2><form onSubmit={submit} className="form-stack">
      {editor.kind === 'create' || editor.kind === 'edit' ? <fieldset className="form-stack" disabled={busy || conflict || !!intent.current?.uncertain}>{editor.kind === 'create' && <div className="field"><Label htmlFor="group-target-unit">写入目标单位</Label><select id="group-target-unit" className="native-select" required value={targetTenant} disabled={targetTree.loading} onChange={event => setTargetTenant(event.target.value)}><option value="">{targetTree.loading ? '读取授权单位…' : '明确选择目标单位'}</option>{allowedTargets.map(unit => <option key={unit.id} value={unit.id}>{unit.name} · {unit.id}</option>)}</select>{targetTree.error != null && <ErrorNotice error={targetTree.error} retry={targetTree.refresh}/>}</div>}<div className="field"><Label htmlFor="group-name">分组名称</Label><Input id="group-name" value={editor.name} onChange={e => setEditor({ ...editor, name: e.target.value })} autoComplete="off"/></div><div className="field"><Label htmlFor="group-description">分组描述</Label><textarea id="group-description" className="native-select" rows={3} value={editor.description} onChange={e => setEditor({ ...editor, description: e.target.value })}/></div><p>只维护分组名称和描述，不授予权限或修改设备；名称在本单位内区分大小写且不可重复。</p></fieldset> : confirming ? <div className="notice" style={wrap}>二次确认：{title}。<p>分组 {editor.group.id} · expected_revision {editor.group.revision} · 单位 {editor.group.tenant_id}</p><Reference device={editor.device}/>只维护本组设备关系，不授予权限或修改设备。</div> : <Reference device={editor.device}/>}
      {writeError != null && <ErrorNotice error={writeError}/>} {conflict && <p className="notice">提交冲突，需刷新重开。非敏感名称/描述草稿可保留，旧revision和提交资格不保留。</p>}
      <div className="version-actions">{!confirming ? <Button type="submit" disabled={busy || conflict}>{busy ? '正在提交分组变更…' : editor.kind === 'create' || editor.kind === 'edit' ? '保存分组' : '核对分组变更'}</Button> : <><Button type="submit" disabled={busy || conflict}>{busy ? '正在提交分组变更…' : editor.kind === 'add' ? '确认加入设备' : '确认移出设备'}</Button><Button type="button" variant="outline" disabled={busy || conflict || !!intent.current?.uncertain} onClick={() => { stopWrite(); setConfirming(false) }}>返回修改</Button></>}{busy && <Button type="button" variant="outline" onClick={cancelSubmission}>取消本次提交</Button>}<Button type="button" variant="outline" onClick={closeEditor}>关闭分组表单</Button>{conflict && <Button type="button" variant="outline" onClick={refresh}>刷新后重新打开</Button>}</div>
    </form></section>}
  </section>
}
