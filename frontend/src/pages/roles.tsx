import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Pencil, Plus, ShieldCheck, Trash2 } from 'lucide-react'
import { accessApi } from '@/lib/access-roles'
import { authorizationCloseAllowed, authorizationPage, useAuthorizationWrite, type RoleEditor, type RoleValues } from '@/lib/authorization-write'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { type PermissionCatalog, type PermissionItem } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { PageHelp } from '@/components/page-tools'

export function RolesPage() {
  const session = useSession()
  if (!session.user || session.user.must_change_password || session.audience !== 'management' || session.user.user_type !== 'PLATFORM' || !session.permissions.includes('roles:read')) return <Empty title="当前账户无全局角色模板读取权限"/>
  return <RolesContent key={session.generation}/>
}
function RolesContent() {
  const permissions = useSession(s => s.permissions), canWrite = permissions.includes('roles:write')
  const command = useAuthorizationWrite(), handled = useRef(0)
  const [userType, setUserType] = useState<'PLATFORM' | 'CUSTOMER'>('CUSTOMER'), [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [editor, setEditor] = useState<RoleEditor | null>(() => command.intent?.kind === 'ROLE' ? command.intent.editor : null), [notice, setNotice] = useState(''), [lookupRole, setLookupRole] = useState<{id:string;name:string}|null>(null), [assignedPage, setAssignedPage] = useState(1)
  const visibleEditor = command.intent?.kind === 'ROLE' ? command.intent.editor : editor
  const roles = useResource(`access-roles:${userType}:${page}:${keyword}`, signal => accessApi.roles(userType, page, 20, signal, keyword))
  const catalog = useResource('permission-catalog', signal => api.permissionCatalog(signal))
  const assigned = useResource(`role-assigned-users:${lookupRole?.id ?? ''}:${assignedPage}`, signal => lookupRole ? accessApi.assignedUsers(lookupRole.id,assignedPage,100,signal) : Promise.resolve({items:[],total:0,page:1,size:100}), !!lookupRole)
  const refresh = () => { roles.refresh(); catalog.refresh() }
  function close() { if (authorizationCloseAllowed() && command.edit()) setEditor(null) }
  useEffect(() => {
    const done = command.completion
    if (!done || done.kind !== 'ROLE' || done.serial === handled.current) return
    handled.current = done.serial
    setEditor(null); setNotice('角色模板变更已收到有效回执；正在重新读取当前状态。'); roles.refresh()
  }, [command.completion])
  const otherIntent = command.intent && command.intent.kind !== 'ROLE' ? command.intent : null
  return <>
    <PageHeading eyebrow="IDENTITY · GLOBAL ROLE TEMPLATES" title="全局角色模板">
      <PageHelp title="角色模板说明">模板只组合权限，不因名称自动获得授权；仅平台维护。停用保留分配但不再贡献有效权限。更改、停用或删除会影响已分配账号并撤销会话；不得编辑分配给自己的模板。每次写入需要理由和版本确认。</PageHelp>
    </PageHeading>
    <Card className="list-card management-list-panel"><CardContent>
      <div className="management-list-bar">
        <h2>角色模板列表</h2>
        <div className="list-toolbar management-list-actions">
          <DataTransferActions kind="ROLES" filters={{ user_type: userType, keyword }} onCompleted={result => { if (!('content_sha256' in result)) roles.refresh() }}/>
          <RefreshButton onClick={refresh}/>
          {canWrite && <Button disabled={!!command.intent} onClick={() => { setNotice(''); setEditor({ kind: 'create' }) }}><Plus/>创建角色模板</Button>}
        </div>
      </div>
      <p className="management-list-context"><ShieldCheck size={15} aria-hidden="true"/>仅有角色读取或写入权限的平台账号可查看或维护模板。</p>
      {otherIntent && <div className="notice" role="status">有尚未结束的授权操作，不能另建意图。<Button variant="outline" onClick={() => useSession.getState().navigate(authorizationPage(otherIntent))}>返回进行中的授权操作</Button></div>}
      {notice && <div role="status" className="notice">{notice}</div>}
      {catalog.error != null && <ErrorNotice error={catalog.error} retry={catalog.refresh}/>}
      <div className="filter-row management-named-filters">
        <div className="field"><Label htmlFor="role-type">模板账号类型</Label><select id="role-type" className="select-control" disabled={!!command.intent} value={userType} onChange={event => { setUserType(event.target.value as 'PLATFORM' | 'CUSTOMER'); setPage(1) }}><option value="CUSTOMER">CUSTOMER 单位账号</option><option value="PLATFORM">PLATFORM 平台账号</option></select></div>
        <div className="field"><Label htmlFor="role-keyword">角色名称或代码</Label><Input id="role-keyword" value={keyword} disabled={!!command.intent} onChange={event => { setKeyword(event.target.value); setPage(1) }} maxLength={100}/></div>
      </div>
      {roles.loading ? <Loading/> : roles.error != null ? <ErrorNotice error={roles.error} retry={roles.refresh}/> : roles.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>名称 / 不可变代码</TableHead><TableHead>类型</TableHead><TableHead>状态</TableHead><TableHead>权限</TableHead><TableHead>版本</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader><TableBody>{roles.data.items.map(role => <TableRow key={role.id}><TableCell><strong>{role.name}</strong><div className="table-sub mono">{role.code}</div></TableCell><TableCell>{role.user_type}</TableCell><TableCell><Badge variant="outline" className={`status ${role.enabled && !role.deleted ? 'status-success' : 'status-neutral'}`}>{role.deleted ? '已删除' : role.enabled ? '已启用' : '已停用'}</Badge></TableCell><TableCell><div className="identity-permission-summary">{role.permissions.length ? role.permissions.join(' · ') : '无权限'}</div></TableCell><TableCell>{role.revision}</TableCell><TableCell className="text-right">{canWrite && !role.deleted && <div className="version-actions"><Button variant="outline" size="sm" disabled={!!command.intent} onClick={() => setEditor({ kind: 'edit', role })}><Pencil/>编辑</Button><Button variant="outline" size="sm" disabled={!!command.intent} onClick={() => setEditor({ kind: 'delete', role })}><Trash2/>删除</Button></div>}</TableCell></TableRow>)}</TableBody></Table><Pager page={roles.data.page} size={roles.data.size} total={roles.data.total} onChange={setPage}/></> : <Empty title="暂无角色模板">不自动创建默认角色。按最小权限需要显式创建模板。</Empty>}
      <div className="list-toolbar"><div className="field"><Label htmlFor="assigned-role">关联用户查询</Label><select id="assigned-role" className="select-control" value={lookupRole?.id ?? ''} onChange={event => { const role = roles.data?.items.find(item => item.id === event.target.value); setLookupRole(role ? {id:role.id,name:role.name} : null); setAssignedPage(1) }}><option value="">选择当前页角色</option>{roles.data?.items.map(role => <option key={role.id} value={role.id}>{role.name}</option>)}</select></div></div>
      {lookupRole && <section className="field permission-field"><h3>{lookupRole.name} · 已分配用户</h3>{assigned.loading ? <Loading/> : assigned.error != null ? <ErrorNotice error={assigned.error} retry={assigned.refresh}/> : assigned.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>用户</TableHead><TableHead>单位</TableHead><TableHead>部门</TableHead><TableHead>状态</TableHead></TableRow></TableHeader><TableBody>{assigned.data.items.map(user => <TableRow key={user.id}><TableCell>{user.name}<div className="table-sub mono">{user.username}</div></TableCell><TableCell>{user.tenant}</TableCell><TableCell>{user.department || '未分配'}</TableCell><TableCell>{user.enabled ? '启用' : '停用'}</TableCell></TableRow>)}</TableBody></Table><Pager page={assigned.data.page} size={assigned.data.size} total={assigned.data.total} onChange={setAssignedPage}/></> : <Empty title="该角色尚未分配用户"/>}</section>}
    </CardContent></Card><Sheet open={!!visibleEditor} onOpenChange={open => { if (!open) close() }}><SheetContent className="review-sheet sm:max-w-xl">{visibleEditor && catalog.data && <RoleForm key={`${visibleEditor.kind}:${visibleEditor.kind === 'create' ? 'new' : visibleEditor.role.id}`} editor={visibleEditor} catalog={catalog.data} userType={userType} onReload={() => { setEditor(null); refresh() }}/>}</SheetContent></Sheet></>
}
function RoleForm({ editor, catalog, userType, onReload }: { editor: RoleEditor; catalog: PermissionCatalog; userType: 'PLATFORM' | 'CUSTOMER'; onReload: () => void }) {
  const command = useAuthorizationWrite(), active = command.intent?.kind === 'ROLE' ? command.intent : null
  const role = editor.kind === 'create' ? null : editor.role, kind = editor.kind
  const [draft, setDraft] = useState<RoleValues>(() => active?.form ?? { code: '', name: role?.name ?? '', user_type: role?.user_type ?? userType, enabled: role?.enabled ?? true, permissions: (role?.permissions ?? []).filter(key => !key.startsWith('sim-')), reason: '' })
  const [validation, setValidation] = useState<unknown>()
  const form = active?.form ?? draft, items = catalog.items.filter(item => !item.key.startsWith('sim-') && item.user_types.includes(form.user_type))
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!active) {
      if (!draft.reason.trim()) { setValidation(new Error('必须填写操作理由')); return }
      setValidation(undefined)
      command.prepare({ kind: 'ROLE', editor, form: { ...draft, code: draft.code.trim(), name: draft.name.trim(), permissions: [...draft.permissions] } })
      return
    }
    void command.submit()
  }
  return <><SheetHeader className="review-header"><div className="eyebrow">IDENTITY · ROLE TEMPLATE</div><SheetTitle>{kind === 'create' ? '创建角色模板' : kind === 'edit' ? '编辑角色模板' : '删除角色模板'}</SheetTitle><SheetDescription>模板权限按同类型目录校验，不授予用户设备范围。写入后相关用户会话撤销；有效回执仍须重新读取核验当前状态。</SheetDescription></SheetHeader><div className="review-body"><form aria-label="账号授权维护" className="form-stack" onSubmit={submit}>
    {!active ? <>{kind === 'create' && <div className="field"><Label htmlFor="role-code">不可变代码</Label><Input id="role-code" value={draft.code} onChange={e => setDraft({ ...draft, code: e.target.value })} required maxLength={64}/></div>}{kind !== 'delete' && <><div className="field"><Label htmlFor="role-name">模板名称</Label><Input id="role-name" value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} required maxLength={200}/></div>{kind === 'create' && <p className="muted-note">账号类型：{draft.user_type}</p>}<label className="toggle-field"><input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })}/>模板启用（停用后分配仍保留，但权限不再生效）</label><PermissionPicker items={items} value={draft.permissions} change={permissions => setDraft({ ...draft, permissions })}/></>}<div className="field"><Label htmlFor="role-reason">操作理由</Label><Input id="role-reason" value={draft.reason} onChange={e => setDraft({ ...draft, reason: e.target.value })} required maxLength={1000}/></div></> : <>
      <div className="notice">{kind === 'delete' ? `软删除 ${role?.name}。仍有分配时服务端拒绝；代码不会复用。` : `${form.name} · ${form.user_type} · ${form.permissions.length} 项权限 · ${form.enabled ? '启用' : '停用'}`}<br/>理由：{form.reason}{role && <div>本次固定版本：{role.revision}</div>}</div>
      {command.phase === 'UNCERTAIN' && <div role="status" className="notice">结果未确认，不表示失败。原始业务意图、版本和幂等键已保留；只能沿原键确认，不可修改、关闭或另建操作。</div>}
      {command.phase === 'PENDING' && <div role="status" className="notice">提交中，业务意图已冻结。离开页面不会另建幂等键；返回后继续等待原操作。</div>}
      {command.phase === 'CONFLICT' ? <div className="notice">当前版本或目标冲突，禁止继续提交。<Button type="button" variant="outline" onClick={() => { if (command.reloadConflict()) onReload() }}>刷新并重新打开</Button></div> : null}
    </>}{(command.error ?? validation) != null && <ErrorNotice error={command.error ?? validation}/>}<div className="form-actions">{!active ? <Button type="submit" disabled={!!command.intent}>继续核对</Button> : <><Button type="button" variant="outline" disabled={command.phase !== 'CONFIRMING'} onClick={() => { if (command.edit()) setValidation(undefined) }}>返回修改</Button><Button type="submit" disabled={command.phase === 'PENDING' || command.phase === 'CONFLICT'}>{command.phase === 'PENDING' ? '提交中…' : '确认提交'}</Button></>}</div>
  </form></div></>
}
function PermissionPicker({ items, value, change }: { items: PermissionItem[]; value: string[]; change: (next: string[]) => void }) {
  return <fieldset className="field permission-field"><legend>角色权限</legend><p className="muted-note">角色权限与目标直接权限取并集；只可授予当前目录中可委派项。</p><div className="permission-grid">{items.map(item => <label key={item.key} className={!item.delegable && !value.includes(item.key) ? 'permission-unavailable' : ''}><input type="checkbox" checked={value.includes(item.key)} disabled={!item.delegable && !value.includes(item.key)} onChange={e => change(e.target.checked ? [...value, item.key] : value.filter(v => v !== item.key))}/><span>{item.label}<small className="mono">{item.key}</small></span></label>)}</div></fieldset>
}
