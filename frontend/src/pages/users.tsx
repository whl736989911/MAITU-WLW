import { useEffect, useRef, useState, type FormEvent } from 'react'
import { KeyRound, Pencil, Plus, ShieldCheck, UserRound, Fingerprint } from 'lucide-react'
import { accessApi } from '@/lib/access-roles'
import { identityDirectoryApi, type IdentityUserProfile } from '@/lib/identity-directory'
import { useSession } from '@/lib/store'
import { accountPasswordHint, validAccountPassword } from '@/lib/account-password'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { allowedPage, type ManagedUser, type PermissionCatalog, type PermissionItem } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { UserMetadataEditor } from '@/components/user-metadata-editor'
import { IdentityImport } from '@/components/identity-import'
import { IdentityDepartmentMove } from '@/components/identity-department-move'
import { IdentitySearch, OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { authorizationCloseAllowed, authorizationPage, useAuthorizationWrite } from '@/lib/authorization-write'
import { UserDeleteAction } from '@/components/user-delete-action'
import { UserAccessBatch } from '@/components/user-access-batch'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { PageHelp, RowActions } from '@/components/page-tools'
import { CustomerRecordPager, CustomerRecordsSearch, CustomerRecordTable } from '@/components/customer-records'
import { DeviceScopePicker, PlaceSubtreePicker } from '@/components/device-scope-picker'
import { TargetUnitSelect } from '@/components/asset-picker'
type Editor = { mode: 'create'; target: null } | { mode: 'edit' | 'reset'; target: ManagedUser }
type ScopeMode = 'ALL_TENANT' | 'DEVICE_SET' | 'PLACE_SUBTREE'
export function UsersPage({ platform }: { platform: boolean }) {
  const { audience, permissions, user, generation } = useSession()
  if (!user || user.must_change_password || !allowedPage(platform ? 'platform-users' : 'users', audience, permissions)) return <Empty title="当前账户无此账号管理权限"/>
  return <UsersContent key={`${platform}:${generation}`} platform={platform}/>
}
function UsersContent({ platform }: { platform: boolean }) {
  const session = useSession()
  const authorization = useAuthorizationWrite(), handledAuthorization = useRef(0)
  const permissionPrefix = platform ? 'platform-users' : 'users'
  const canWrite = session.permissions.includes(`${permissionPrefix}:write`)
  const canReset = session.permissions.includes(`${permissionPrefix}:reset-password`)
  const customerTenant = !platform && session.audience === 'customer' ? session.user?.tenant_id ?? '' : ''
  const [targetUnit, setTargetUnit] = useState('')
  const explicitUnit = platform ? '' : customerTenant || targetUnit
  const [accessTarget, setAccessTarget] = useState<ManagedUser | null>(() => authorization.intent && authorization.intent.kind !== 'ROLE' && authorization.intent.platform === platform ? authorization.intent.target : null)
  const visibleAccessTarget = authorization.intent && authorization.intent.kind !== 'ROLE' && authorization.intent.platform === platform ? authorization.intent.target : accessTarget
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState('')
  const [draft, setDraft] = useState('')
  const users = useResource(`users:${platform}:${session.generation}:${page}:${keyword}:${JSON.stringify(session.scopeTenantIds)}:${JSON.stringify(session.scopePlaceIds)}`, signal => api.users(platform, page, keyword, signal, session.scopeTenantIds, session.scopePlaceIds, !platform && session.audience === 'management'))
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  useEffect(() => setSelectedIds([]), [users.data])
  const catalog = useResource(`permission-catalog:${platform}`, signal => api.permissionCatalog(signal))
  const [editor, setEditor] = useState<Editor | null>(null)
  const [metadataSelection, setMetadataSelection] = useState(''), [metadataTarget, setMetadataTarget] = useState<IdentityUserProfile | null>(null)
  const [toolsOpen, setToolsOpen] = useState(false)
  const metadata = useResource(`identity-directory:${platform}:${session.generation}:${page}:${keyword}:${JSON.stringify(session.scopeTenantIds)}:${JSON.stringify(session.scopePlaceIds)}:${explicitUnit}`, signal => identityDirectoryApi.users(page, keyword, signal, session.scopeTenantIds, session.scopePlaceIds, explicitUnit || undefined), !platform && toolsOpen)
  const metadataDepartments = useResource(`identity-departments-edit:${metadataTarget?.tenant_id ?? ''}`, signal => identityDirectoryApi.departments(signal, metadataTarget?.tenant_id), !!metadataTarget)
  const [notice, setNotice] = useState('')
  const close = () => setEditor(null)
  const refresh = () => { users.refresh(); catalog.refresh() }
  useEffect(() => {
    const done = authorization.completion
    if (!done || done.kind === 'ROLE' || done.platform !== platform || done.serial === handledAuthorization.current) return
    handledAuthorization.current = done.serial
    users.refresh(); setNotice('授权变更已收到有效回执；目标账号版本与授权明细须重新读取。')
  }, [authorization.completion])
  function saved() { const created = editor?.mode === 'create'; close(); users.refresh(); setNotice(created ? '账号已创建。初始密码不在响应中返回，首次登录必须修改；请通过安全渠道告知目标用户。' : '变更已保存，目标账号全部旧会话已撤销。重新启用不恢复旧会话；重置密码后首次登录必须修改。') }
  const legacy = session.audience === 'customer'
  const visibleUsers = users.data?.items ?? []
  const allVisibleSelected = visibleUsers.length > 0 && visibleUsers.every(item => selectedIds.includes(item.id))
  return <>
    {legacy ? <section className="legacy-record-page" aria-label="用户管理">
      <CustomerRecordsSearch field="用户名或姓名" keyword={draft} onKeyword={setDraft}
        onSearch={event => { event.preventDefault(); setPage(1); setKeyword(draft.trim()) }}
        onReset={() => { setDraft(''); setKeyword(''); setPage(1) }}
        actions={<>
          {canWrite && <button type="button" className="legacy-device-button" disabled={!catalog.data || catalog.loading}
            onClick={() => { setNotice(''); setEditor({ mode: 'create', target: null }) }}>添加用户</button>}
          {visibleUsers.length > 0 && <button type="button" className="legacy-device-button" aria-pressed={allVisibleSelected}
            onClick={() => setSelectedIds(allVisibleSelected ? [] : visibleUsers.map(item => item.id))}>选择本页账号</button>}
          <DataTransferActions kind="USERS" tenantId={customerTenant || undefined} filters={{ unit_ids: customerTenant, place_ids: session.scopePlaceIds.join(','), keyword }}
            selectedIds={selectedIds} onCompleted={users.refresh}/>
          {(canWrite || session.permissions.includes('users:assign-roles') || session.permissions.includes('users:assign-scope')) &&
            <button type="button" className="legacy-device-link" onClick={() => setToolsOpen(true)}>账号维护工具</button>}
        </>}/>
      {notice && <p role="status" className="notice">{notice}</p>}
      {catalog.error != null && <ErrorNotice error={catalog.error} retry={catalog.refresh}/>}
      <CustomerRecordTable name="用户管理" columns={['选择', '用户名', '昵称', '状态', '权限', '操作']}
        loading={users.loading} error={users.error} retry={refresh} rows={visibleUsers.map(item => ({
          key: item.id, cells: [
            <input type="checkbox" aria-label={`选择账号 ${item.username}`} checked={selectedIds.includes(item.id)}
              onChange={event => setSelectedIds(current => event.target.checked ? [...current, item.id] : current.filter(id => id !== item.id))}/>,
            item.username, item.name, `${item.enabled ? '已启用' : '已停用'}${item.must_change_password ? ' · 需改密' : ''}`,
            item.permissions.length ? `${item.permissions.length} 项` : '无',
            <RowActions label={`${item.username} 操作`} text="操作">
              <button type="button" className="legacy-device-link" disabled={!!authorization.intent} onClick={() => setAccessTarget(item)}>角色 / 设备范围</button>
              {!item.is_protected && item.id !== session.user?.id && <>
                {canWrite && <button type="button" className="legacy-device-link" disabled={!catalog.data}
                  onClick={() => setEditor({ mode: 'edit', target: item })}>编辑</button>}
                {canReset && <button type="button" className="legacy-device-link" disabled={!catalog.data}
                  onClick={() => setEditor({ mode: 'reset', target: item })}>重置密码</button>}
                <UserDeleteAction user={item} platform={false} onSaved={() => { users.refresh(); setNotice('账号已删除。') }}/>
              </>}
            </RowActions>],
        }))}/>
      {users.data && <CustomerRecordPager page={users.data.page} size={users.data.size} total={users.data.total} onPage={setPage}/>}
    </section> : <>
    <PageHeading eyebrow={platform ? 'PLATFORM USERS · 平台用户' : 'UNIT USERS · 单位用户'} title={platform ? '平台用户' : '单位用户'}>
      <PageHelp title="账号与权限说明">平台账号独立管理，无需选择当前租户。单位用户默认查看全部授权单位，左侧树可收窄列表；创建必须明确写入单位。编辑或重置固定到目标账号所属单位。超出当前管理权限时服务端拒绝，不会修改目标；403 会保留有效登录并显示原因。</PageHelp>
    </PageHeading>
    <Card className="list-card management-list-panel"><CardContent>
      <div className="management-list-bar">
        <h2>{platform ? '平台账号列表' : '单位账号列表'}</h2>
        <div className="list-toolbar management-list-actions">
          {(!platform && canWrite || users.data && (session.permissions.includes(platform ? 'platform-users:assign-roles' : 'users:assign-roles') || session.permissions.includes(platform ? 'platform-users:assign-scope' : 'users:assign-scope'))) && <Button variant="outline" onClick={() => setToolsOpen(true)}>账号维护工具</Button>}
          {(platform || session.scopeInitialized) && <DataTransferActions kind={platform ? 'PLATFORM_USERS' : 'USERS'} tenantId={platform ? undefined : customerTenant || undefined} filters={platform ? { keyword } : { unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), keyword }} selectedIds={selectedIds} onCompleted={result => { if ('plan_id' in result) users.refresh() }}/>}
          <RefreshButton onClick={refresh}/>
          {canWrite && <Button disabled={!catalog.data || catalog.loading} onClick={() => { setNotice(''); setEditor({ mode: 'create', target: null }) }}><Plus/>创建{platform ? '平台' : '单位'}用户</Button>}
        </div>
      </div>
      <div className="management-list-context"><ShieldCheck size={15} aria-hidden="true"/>{platform ? '全局平台账号目录 · 需专门的平台用户权限' : <>全部授权单位账号目录 · 本页写入目标：{targetUnit || '尚未明确选择'}</>}<span>自我与受保护根账户只读</span></div>
      {notice && <div role="status" className="notice">{notice}</div>}
      {catalog.error != null && <ErrorNotice error={catalog.error} retry={catalog.refresh}/>}
      <IdentitySearch onSearch={value => { setPage(1); setKeyword(value) }}/>
      {users.loading ? <Loading/> : users.error != null ? <ErrorNotice error={users.error} retry={users.refresh}/> : users.data?.items.length ? <><Table><TableHeader><TableRow><><TableHead><input type="checkbox" aria-label="选择本页账号" checked={allVisibleSelected} onChange={event => setSelectedIds(event.target.checked ? visibleUsers.map(item => item.id) : [])}/></TableHead><TableHead>账号 / 姓名</TableHead></>{!platform && <TableHead>单位</TableHead>}<TableHead>状态</TableHead><TableHead>权限</TableHead>{platform && <TableHead>单位授权</TableHead>}<TableHead>版本</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader><TableBody>{users.data.items.map(item => {
      const readonly = item.is_protected || item.id === session.user?.id
      return <TableRow key={item.id}><TableCell><input type="checkbox" aria-label={`选择账号 ${item.username}`} checked={selectedIds.includes(item.id)} onChange={event => setSelectedIds(current => event.target.checked ? [...current, item.id] : current.filter(id => id !== item.id))}/></TableCell><TableCell><div className="table-primary"><span className="device-symbol"><UserRound size={17}/></span><div><strong>{item.name}</strong><div className="table-sub mono">{item.username}</div>{readonly && <Badge variant="outline" className="mt-2 text-[9px]">{item.is_protected ? '受保护账号 · 只读' : '当前账号 · 请使用自助改密'}</Badge>}</div></div></TableCell>{!platform && <TableCell>{item.tenant_name ?? item.tenant_id}</TableCell>}<TableCell><div className="identity-status"><Badge variant="outline" className={`status ${item.enabled ? 'status-success' : 'status-neutral'}`}>{item.enabled ? '已启用' : '已停用'}</Badge>{item.must_change_password && <Badge variant="outline" className="source-badge">首次需改密</Badge>}</div></TableCell><TableCell><div className="identity-permission-summary">{item.permissions.length ? item.permissions.map(key => <span key={key}>{catalog.data?.items.find(p => p.key === key)?.label ?? key}</span>) : '未授予直接权限'}</div></TableCell>{platform && <TableCell><div className="identity-permission-summary mono">{item.tenant_ids.length ? item.tenant_ids.join(' · ') : '无单位授权'}</div></TableCell>}<TableCell>{item.revision}</TableCell><TableCell className="text-right"><Button variant="ghost" size="sm" disabled={!!authorization.intent} onClick={() => setAccessTarget(item)}><Fingerprint/>角色 / 设备范围</Button>{readonly ? <span className="muted-note">只读</span> : <div className="identity-row-actions">{canWrite && <Button variant="ghost" size="sm" disabled={!catalog.data} onClick={() => setEditor({ mode: 'edit', target: item })}><Pencil/>编辑 / 直接权限</Button>}{canReset && <Button variant="ghost" size="sm" disabled={!catalog.data} onClick={() => setEditor({ mode: 'reset', target: item })}><KeyRound/>重置密码</Button>}<UserDeleteAction user={item} platform={platform} onSaved={() => { users.refresh(); setNotice('账号已删除；该账号无任何业务或审计历史引用。') }}/>{!canWrite && !canReset && <span className="muted-note">只读</span>}</div>}</TableCell></TableRow>
    })}</TableBody></Table><Pager page={users.data.page} size={users.data.size} total={users.data.total} onChange={setPage}/></> : <Empty title="暂无账号记录">真实开户从「创建用户」开始，账号密码不预填、不返回。</Empty>}</CardContent></Card>
    </>}
    <Sheet open={!!editor} onOpenChange={open => { if (!open) close() }}><SheetContent className="review-sheet sm:max-w-xl">{editor && catalog.data && <UserEditor key={`${editor.mode}:${editor.target?.id ?? 'new'}`} platform={platform} editor={editor} catalog={catalog.data} defaultTargetUnit={targetUnit} onSaved={saved} onRefresh={() => { close(); refresh() }}/>}</SheetContent></Sheet>
    <Sheet open={!!visibleAccessTarget} onOpenChange={open => { if (!open && authorizationCloseAllowed() && authorization.edit()) setAccessTarget(null) }}><SheetContent className="review-sheet sm:max-w-2xl">{visibleAccessTarget && <AccountAccessPanel key={`${platform}:${visibleAccessTarget.id}:${session.generation}`} platform={platform} target={visibleAccessTarget} onReload={() => { setAccessTarget(null); users.refresh() }}/>}</SheetContent></Sheet>
    <Sheet open={toolsOpen} onOpenChange={setToolsOpen}><SheetContent className="review-sheet sm:max-w-2xl"><SheetHeader className="review-header"><SheetTitle>账号维护工具</SheetTitle><SheetDescription>低频组织资料与批量授权工具；列表与账号状态保持在主页面。</SheetDescription></SheetHeader><div className="review-body">
      {!platform && canWrite && <div className="form-stack">{session.audience === 'management' && <TargetUnitSelect audience="management" permissions={session.permissions} value={targetUnit} onChange={unit => { setTargetUnit(unit); setMetadataSelection(''); setMetadataTarget(null) }}/>}<div className="field"><Label htmlFor="identity-metadata-user">用户组织与联系资料</Label><select id="identity-metadata-user" className="select-control" value={metadataSelection} onChange={event => setMetadataSelection(event.target.value)}><option value="">选择当前页用户</option>{metadata.data?.items.map(profile => <option key={profile.id} value={profile.id}>{profile.name} · {profile.username} · {profile.tenant_name}</option>)}</select></div><Button variant="outline" disabled={!metadataSelection} onClick={() => setMetadataTarget(metadata.data?.items.find(profile => profile.id === metadataSelection) ?? null)}>编辑部门、联系方式与有效期</Button>{metadata.error != null && <ErrorNotice error={metadata.error} retry={metadata.refresh}/>} {metadataTarget && <UserMetadataEditor value={metadataTarget} departments={metadataDepartments.data ?? []} tenantId={metadataTarget.tenant_id} onClose={() => setMetadataTarget(null)} onSaved={() => { setMetadataTarget(null); metadata.refresh(); users.refresh(); setNotice('用户组织与联系资料已保存并重新读取；账号有效期沿用本次提交值。') }}/>}<IdentityDepartmentMove profiles={metadata.data?.items ?? []} onSaved={() => { metadata.refresh(); users.refresh(); setNotice('批量组织变更已逐账号完成；请核对各自结果与重新读取状态。') }}/>{!platform && canWrite && catalog.data && <IdentityImport platform={platform} catalog={catalog.data} tenantId={explicitUnit || undefined} disabled={false} onImported={() => { metadata.refresh(); users.refresh(); setNotice('逐行导入已提交；账号有效期与权限以各自服务端结果为准。') }}/>}</div>}
      {users.data && (session.permissions.includes(platform ? 'platform-users:assign-roles' : 'users:assign-roles') || session.permissions.includes(platform ? 'platform-users:assign-scope' : 'users:assign-scope')) && <UserAccessBatch users={users.data.items} platform={platform} onCompleted={() => { users.refresh(); setNotice('批量操作逐账号提交完成；请按逐项结果区分成功、拒绝与未知。') }}/>}
    </div></SheetContent></Sheet>
  </>
}
function PermissionPicker({ items, value, change }: { items: PermissionItem[]; value: string[]; change: (next: string[]) => void }) {
  const unlisted = value.filter(key => !items.some(p => p.key === key))
  return <fieldset className="field permission-field"><legend>直接权限（服务端目录）</legend><p className="muted-note">仅可授予当前目录中可委派的权限。已有但不可委派项可移除，不能重新授出。</p><div className="permission-grid">{items.map(item => {
    const checked = value.includes(item.key)
    return <label key={item.key} className={!item.delegable && !checked ? 'permission-unavailable' : ''}><input type="checkbox" checked={checked} disabled={!item.delegable && !checked} onChange={e => change(e.target.checked ? [...value, item.key] : value.filter(key => key !== item.key))}/><span>{item.label}<small className="mono">{item.key}</small>{!item.delegable && <small>{checked ? '不可委派，仅可移除' : '当前不可委派'}</small>}</span></label>
  })}</div>{unlisted.map(key => <div className="notice" key={key}>现有权限不在该账户适用目录：{key}。不可新增；保存前请核对。<Button type="button" variant="outline" size="sm" onClick={() => change(value.filter(v => v !== key))}>移除此权限</Button></div>)}</fieldset>
}
function GrantPicker({ value, change }: { value: string[]; change: (next: string[]) => void }) {
  const canRead = useSession(s => s.permissions.includes('units:read'))
  const [page, setPage] = useState(1)
  const units = useResource(`grant-units:${page}`, signal => api.units(page, '', signal), canRead)
  return <fieldset className="field permission-field"><legend>平台单位访问授权</legend><p className="muted-note">候选项仅来自服务端返回的您已有的单位授权；可翻页选择，不代表自动获得全部单位。停用单位的授权也不允许访问其业务。</p>{!canRead ? <div className="notice">无单位目录读取权限，不能新增单位授权；可移除已有授权。保存仍由服务端核验每个grant。</div> : units.loading ? <Loading/> : units.error != null ? <ErrorNotice error={units.error} retry={units.refresh}/> : <><div className="permission-grid">{units.data?.items.map(unit => <label key={unit.id}><input type="checkbox" checked={value.includes(unit.id)} onChange={e => change(e.target.checked ? [...value, unit.id] : value.filter(id => id !== unit.id))}/><span>{unit.name}<small className="mono">{unit.id}{!unit.enabled ? ' · 已停用' : ''}</small></span></label>)}</div>{units.data && <Pager page={units.data.page} size={units.data.size} total={units.data.total} onChange={setPage}/>}</>}{value.length > 0 && <div className="selected-grants"><strong>当前选中授权（保存时再次核验）</strong>{value.map(id => <div key={id}><span className="mono">{id}</span><Button type="button" size="xs" variant="ghost" onClick={() => change(value.filter(v => v !== id))}>移除</Button></div>)}</div>}</fieldset>
}
function UserEditor({ platform, editor, catalog, defaultTargetUnit, onSaved, onRefresh }: { platform: boolean; editor: Editor; catalog: PermissionCatalog; defaultTargetUnit: string; onSaved: () => void; onRefresh: () => void }) {
  const session = useSession()
  const target = editor.target
  const creating = editor.mode === 'create', resetting = editor.mode === 'reset'
  const [username, setUsername] = useState(target?.username ?? '')
  const [name, setName] = useState(target?.name ?? '')
  const [enabled, setEnabled] = useState(target?.enabled ?? true)
  const [permissions, setPermissions] = useState<string[]>(target?.permissions ?? [])
  const [tenantIds, setTenantIds] = useState<string[]>(target?.tenant_ids ?? [])
  const [createUnit, setCreateUnit] = useState(platform ? '' : session.user?.tenant_id && session.audience === 'customer' ? session.user.tenant_id : defaultTargetUnit)
  const [password, setPassword] = useState(''), [confirmation, setConfirmation] = useState('')
  const action = useMutationAction()
  const type = platform ? 'PLATFORM' : 'CUSTOMER'
  const items = catalog.items.filter(item => item.user_types.includes(type))
  const policy = catalog.password_policy
  const title = resetting ? '重置账号密码' : creating ? `创建${platform ? '平台' : '单位'}用户` : '编辑账号与授权'
  function submit(event: FormEvent) {
    event.preventDefault()
    if (creating || resetting) {
      if (password !== confirmation) { action.setError(new Error('两次密码须一致')); return }
      if (!validAccountPassword(password, policy)) { action.setError(new Error(accountPasswordHint(policy))); return }
    }
    if (creating && !platform && !createUnit) { action.setError(new Error('请先明确本次创建账号的目标单位')); return }
    if (!action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    void action.run((key, signal) => {
      if (resetting && target) return api.resetUserPassword(platform, target.id, { new_password: password, expected_revision: target.revision }, key, signal, platform ? undefined : target.tenant_id ?? undefined)
      if (creating) return api.createUser(platform, { username: username.trim(), name: name.trim(), initial_password: password, permissions, ...(platform ? { tenant_ids: tenantIds } : {}) }, key, signal, platform ? undefined : createUnit)
      return api.updateUser(platform, target!.id, { name: name.trim(), enabled, permissions, expected_revision: target!.revision, ...(platform ? { tenant_ids: tenantIds } : {}) }, key, signal, platform ? undefined : target!.tenant_id ?? undefined)
    }, () => { setPassword(''); setConfirmation(''); onSaved() })
  }
  return <><SheetHeader className="review-header"><div className="eyebrow">IDENTITY · {platform ? '平台账号' : '单位账号'}</div><SheetTitle>{title}</SheetTitle><SheetDescription>{creating ? '新账号首次登录必须改密。初始密码不会由服务端响应返回。创建目标只影响本次开户，不改变左侧筛选。' : '保存启停、权限、授权或密码变更均撤销目标全部会话；自我与受保护账号禁止管理。'}</SheetDescription></SheetHeader><div className="review-body"><form aria-label="账号授权维护" className="form-stack" onSubmit={submit}>{!action.confirming ? <>{creating && !platform && <TargetUnitSelect audience={session.audience} permissions={session.permissions} value={createUnit} onChange={unit => { setCreateUnit(unit); action.touch() }} disabled={action.busy}/>}{!resetting && <><div className="field"><Label htmlFor="managed-username">用户名</Label><Input id="managed-username" name="username" value={username} autoComplete="off" required maxLength={100} disabled={!creating} onChange={e => { setUsername(e.target.value); action.touch() }}/></div><div className="field"><Label htmlFor="managed-name">姓名</Label><Input id="managed-name" value={name} required maxLength={200} onChange={e => { setName(e.target.value); action.touch() }}/></div>{!creating && <label className="confirmation"><input type="checkbox" checked={enabled} onChange={e => { setEnabled(e.target.checked); action.touch() }}/>启用账号（取消勾选即停用，保存后撤销旧会话）</label>}<PermissionPicker items={items} value={permissions} change={next => { setPermissions(next); action.touch() }}/>{platform && <GrantPicker value={tenantIds} change={next => { setTenantIds(next); action.touch() }}/>}</>}{(creating || resetting) && <><div className="field"><Label htmlFor="managed-password">{creating ? '初始密码' : '新密码'}</Label><Input id="managed-password" type="password" autoComplete="new-password" value={password} required minLength={policy.min_length} maxLength={policy.max_length} aria-describedby="managed-password-policy" onChange={e => { setPassword(e.target.value); action.touch() }}/><p id="managed-password-policy" className="muted-note">{accountPasswordHint(policy)}。</p></div><div className="field"><Label htmlFor="managed-password-confirm">确认{creating ? '初始密码' : '新密码'}</Label><Input id="managed-password-confirm" type="password" autoComplete="new-password" value={confirmation} required onChange={e => { setConfirmation(e.target.value); action.touch() }}/></div></>}{target && <div className="identity-readonly">目标：{target.username} · {target.id} · revision {target.revision}</div>}{action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit">核对并继续</Button></> : <OperationConfirmation action={action} title={`二次确认 · ${title}`} submitLabel={creating ? '确认创建账号' : resetting ? '确认重置并撤销会话' : '确认保存并撤销会话'} refresh={onRefresh} warning={creating ? '新账号首次登录必须修改初始密码，密码不会在结果中回显。' : resetting ? '将撤销目标全部会话，要求首次登录改密；旧密码立即失效。' : '将撤销目标全部旧会话。停用阻止登录，重新启用也不恢复旧会话。'}><p>目标：{username} · {name}{target ? ` · revision ${target.revision}` : ' · 新账号'}</p>{!resetting && <><p>启用状态：{enabled ? '启用' : '停用'}</p><p>权限：{permissions.length ? permissions.map(key => items.find(item => item.key === key)?.label ?? key).join('、') : '未授予'}</p>{platform && <p>单位授权：{tenantIds.length ? tenantIds.join('、') : '无'}</p>}</>}{creating && !platform && <p>创建目标单位：{createUnit || '尚未明确选择'}</p>}<p>密码：{creating || resetting ? '已输入，不回显' : '本次不变更'}</p></OperationConfirmation>}</form></div></>
}
function AccountAccessPanel({ platform, target, onReload }: { platform: boolean; target: ManagedUser; onReload: () => void }) {
  const session = useSession(), prefix = platform ? 'platform-users' : 'users', command = useAuthorizationWrite(), handled = useRef(0)
  const canAssignRoles = session.permissions.includes(`${prefix}:assign-roles`), canAssignScope = session.permissions.includes(`${prefix}:assign-scope`), canReadScope = session.permissions.includes(platform ? 'platform-users:read' : 'users:read')
  const canReadRoles = session.permissions.includes('roles:read'), canOperate = !target.is_protected && target.id !== session.user?.id
  const active = command.intent && command.intent.kind !== 'ROLE' && command.intent.platform === platform && command.intent.target.id === target.id ? command.intent : null
  const roleIntent = active?.kind === 'ACCOUNT_ROLES' ? active : null, scopeIntent = active?.kind === 'DEVICE_SCOPE' ? active : null, locked = !!command.intent
  const [showScope, setShowScope] = useState(!!scopeIntent), [roleIds, setRoleIds] = useState<string[]>(roleIntent?.roleIds ?? [])
  const [roleReason, setRoleReason] = useState(roleIntent?.reason ?? ''), [scopeReason, setScopeReason] = useState(scopeIntent?.reason ?? '')
  const [scopeTenant, setScopeTenant] = useState(scopeIntent?.tenantId ?? (platform ? '' : target.tenant_id ?? ''))
  const [scopeMode, setScopeMode] = useState<ScopeMode>(scopeIntent?.mode ?? 'DEVICE_SET')
  const [deviceIds, setDeviceIds] = useState<string[]>(scopeIntent?.deviceIds ?? []), [placeIds, setPlaceIds] = useState<string[]>(scopeIntent?.placeIds ?? [])
  const [validation, setValidation] = useState<unknown>()
  const access = useResource(`user-access:${platform}:${target.id}:${target.tenant_id ?? ''}`, signal => accessApi.userAccess(platform, target.id, signal, platform ? undefined : target.tenant_id ?? undefined), true)
  const roles = useResource(`assignable-roles:${target.user_type}:${session.audience}`, signal => session.audience === 'customer' ? accessApi.customerRoles(1, 100, signal) : accessApi.roles(target.user_type, 1, 100, signal), canReadRoles && canAssignRoles)
  const scope = useResource(`user-scope:${platform}:${target.id}:${scopeTenant}`, signal => accessApi.userScope(platform, target.id, signal, scopeTenant || undefined), showScope && canReadScope && !!scopeTenant)
  useEffect(() => { if (access.data && !locked) setRoleIds(access.data.roles.map(role => role.id)) }, [access.data, locked])
  useEffect(() => { if (scope.data && !locked) { setScopeMode(scope.data.mode === 'ALL_TENANT' ? 'ALL_TENANT' : scope.data.mode === 'PLACE_SUBTREE' ? 'PLACE_SUBTREE' : 'DEVICE_SET'); setDeviceIds(scope.data.device_ids); setPlaceIds(scope.data.place_ids) } }, [scope.data, locked])
  useEffect(() => {
    const done = command.completion
    if (!done || done.kind === 'ROLE' || done.platform !== platform || done.targetId !== target.id || done.tenantId !== (done.kind === 'DEVICE_SCOPE' && platform ? scopeTenant : platform ? null : target.tenant_id) || done.serial === handled.current) return
    handled.current = done.serial
    setRoleReason(''); setScopeReason(''); setValidation(undefined)
    access.refresh(); if (showScope) scope.refresh()
  }, [command.completion])
  function attempt() { void command.submit() }
  function submitRoles(event: FormEvent) {
    event.preventDefault()
    if (roleIntent) { attempt(); return }
    if (locked || !canOperate || !canAssignRoles || !canReadRoles || !access.data || roles.loading || roles.error) return
    if (!roleReason.trim()) { setValidation(new Error('请填写角色分配理由')); return }
    setValidation(undefined)
    command.prepare({ kind: 'ACCOUNT_ROLES', platform, target, tenantId: platform ? null : target.tenant_id, roleIds: [...roleIds], revision: access.data.revision, reason: roleReason })
  }
  function submitScope(event: FormEvent) {
    event.preventDefault()
    if (scopeIntent) { attempt(); return }
    if (locked || !canOperate || !canAssignScope || !scope.data) return
    if (!scopeTenant || !scopeReason.trim()) { setValidation(new Error('须明确目标单位并填写变更理由')); return }
    const devices = scopeMode === 'DEVICE_SET' ? [...new Set(deviceIds)] : []
    const places = scopeMode === 'PLACE_SUBTREE' ? [...new Set(placeIds)] : []
    if (scopeMode === 'PLACE_SUBTREE' && places.length === 0) { setValidation(new Error('位置动态范围必须至少选择一个位置根')); return }
    setValidation(undefined)
    command.prepare({ kind: 'DEVICE_SCOPE', platform, target, tenantId: scopeTenant, mode: scopeMode, deviceIds: devices, placeIds: places, revision: scope.data.revision, reason: scopeReason })
  }
  const otherIntent = command.intent && !active ? command.intent : null
  const scopeTenants = platform ? [...target.tenant_ids] : target.tenant_id ? [target.tenant_id] : []
  return <><SheetHeader className="review-header"><div className="eyebrow">IDENTITY · ACCOUNT ACCESS</div><SheetTitle>角色与设备范围 · {target.name}</SheetTitle><SheetDescription>{target.username} · {target.user_type}。直接权限编辑仍在原账号表单独立进行；自我和受保护根账户禁止授权操作。</SheetDescription></SheetHeader>
    <div className="review-body"><div className="notice">角色分配与设备范围是独立操作，各自单独理由、版本和幂等键。任何操作未结束时不能另建意图。有效回执仍须重新读取；浏览器已显示的数据不能由前端撤回。</div>
    {otherIntent && <div role="status" className="notice">有尚未结束的授权操作。<Button variant="outline" onClick={() => useSession.getState().navigate(authorizationPage(otherIntent))}>返回进行中的授权操作</Button></div>}
    {validation != null && <ErrorNotice error={validation}/>}
    {access.loading ? <Loading/> : access.error != null ? <ErrorNotice error={access.error} retry={access.refresh}/> : access.data && <><section className="field permission-field"><h3>当前授权明细</h3><p><strong>直接权限：</strong>{access.data.direct_permissions.length ? access.data.direct_permissions.join(' · ') : '无'}</p><p><strong>有效权限（直接与启用角色并集）：</strong>{access.data.effective_permissions.length ? access.data.effective_permissions.join(' · ') : '无'}</p><p><strong>已分配角色：</strong>{access.data.roles.length ? access.data.roles.map(role => `${role.name}${role.enabled && !role.deleted ? '' : '（停用/删除，不贡献权限）'}`).join(' · ') : '无'}</p><p className="muted-note">账号版本 {access.data.revision} · 直接权限沿用原编辑器，角色不会授予设备范围。</p></section>
      {canAssignRoles && <form className="form-stack" onSubmit={submitRoles}><fieldset className="field permission-field" disabled={locked || !canOperate}><legend>角色分配（独立于直接权限）</legend>
        {!canOperate ? <div className="notice">当前账号或受保护账号不可修改。</div> : !canReadRoles ? <div className="notice">缺少 roles:read，不能加载可分配模板；不会发起模板读取。</div> : roles.loading ? <Loading/> : roles.error != null ? <ErrorNotice error={roles.error} retry={roles.refresh}/> : <><p className="muted-note">仅读取当前账号类型模板，不自动读取用户或设备目录。</p><div className="permission-grid">{roles.data?.items.map(role => { const checked = roleIds.includes(role.id), existing = access.data!.roles.some(r => r.id === role.id); return <label key={role.id} className={!role.enabled || role.deleted ? 'permission-unavailable' : ''}><input type="checkbox" checked={checked} disabled={!existing && (!role.enabled || role.deleted)} onChange={e => setRoleIds(e.target.checked ? [...roleIds, role.id] : roleIds.filter(id => id !== role.id))}/><span>{role.name}<small className="mono">{role.code} · {role.enabled && !role.deleted ? '启用' : '停用/删除'}</small></span></label>})}{access.data.roles.filter(role => !roles.data?.items.some(option => option.id === role.id)).map(role => <label key={role.id}><input type="checkbox" checked={roleIds.includes(role.id)} onChange={e => setRoleIds(e.target.checked ? [...roleIds, role.id] : roleIds.filter(id => id !== role.id))}/><span>{role.name}<small>当前分配但不可再读取；取消勾选可移除</small></span></label>)}</div></>}
        <div className="field"><Label htmlFor="access-reason">角色分配理由</Label><Input id="access-reason" value={roleReason} onChange={e => setRoleReason(e.target.value)} required maxLength={1000}/></div></fieldset>
        {roleIntent ? <><div className="notice">本次固定账号版本 {roleIntent.revision} · {roleIntent.roleIds.length} 个角色<br/>理由：{roleIntent.reason}</div><AccessConfirmation submitLabel="确认角色分配" onReload={onReload}/></> : <Button type="submit" disabled={locked || !canOperate || !canReadRoles || roles.loading || !!roles.error}>核对角色分配</Button>}
      </form>}
      {canAssignScope && <section className="field permission-field"><h3>单位设备访问范围</h3><p className="muted-note">设备范围与操作角色分别设置；先明确目标单位并读取现有范围。动态位置范围按位置树计算，不缓存设备集合。</p>
        {!canOperate ? <div className="notice">当前账号或受保护账号不可修改。</div> : <>
          {platform ? (scopeTenants.length === 0 ? <div className="notice">目标平台账号没有单位授权，无法分配设备范围。</div> : <div className="field"><Label htmlFor="scope-target-unit">目标单位（来自目标账号的授权）</Label><select id="scope-target-unit" className="select-control" value={scopeTenant} disabled={locked} onChange={event => { setScopeTenant(event.target.value); setShowScope(false) }}><option value="">请选择</option>{scopeTenants.map(id => <option key={id} value={id}>{id}</option>)}</select></div>) : <div className="field"><Label>目标单位（账号记录归属）</Label><p className="muted-note">{target.tenant_id ?? '账号记录未提供单位'}</p></div>}
          {!scopeTenant ? <div className="notice">平台管理员须先选择目标单位，再读取该目标的范围。</div> : !showScope ? <Button type="button" variant="outline" disabled={locked} onClick={() => setShowScope(true)}>明确读取目标单位范围</Button> : scope.loading ? <Loading/> : scope.error != null ? <ErrorNotice error={scope.error} retry={scope.refresh}/> : scope.data && <>
          <div className="notice">{!scope.data.configured ? '尚未配置：当前无设备访问权。' : scope.data.mode === 'ALL_TENANT' ? '当前可见：本单位全部现有及未来设备。' : scope.data.mode === 'PLACE_SUBTREE' ? `当前可见：${scope.data.place_ids.length} 个位置根及其全部后代与未来新增设备。` : `当前可见：明确选择的 ${scope.data.device_ids.length} 台设备${scope.data.device_ids.length === 0 ? '（空集即无设备访问权）' : ''}。`}<div>范围版本：{scope.data.revision}</div></div>
          <form className="form-stack" onSubmit={submitScope}><fieldset className="field permission-field" disabled={locked}><legend>设置设备范围（单独确认）</legend><label><input type="radio" name="device-scope-mode" checked={scopeMode === 'ALL_TENANT'} onChange={() => setScopeMode('ALL_TENANT')}/>本单位全部设备（包含未来新增设备）</label><label><input type="radio" name="device-scope-mode" checked={scopeMode === 'DEVICE_SET'} onChange={() => setScopeMode('DEVICE_SET')}/>只看明确选择的设备（不随位置变化自动扩权）</label><label><input type="radio" name="device-scope-mode" checked={scopeMode === 'PLACE_SUBTREE'} onChange={() => setScopeMode('PLACE_SUBTREE')}/>只看明确位置子树（含后代及未来新增设备；移出即失去范围）</label>
            {scopeMode === 'DEVICE_SET' && <DeviceScopePicker key={`${session.generation}:${scopeTenant}`} tenantId={scopeTenant} value={deviceIds} onChange={setDeviceIds} disabled={locked}/>}
            {scopeMode === 'PLACE_SUBTREE' && <PlaceSubtreePicker key={`${session.generation}:${scopeTenant}`} tenantId={scopeTenant} value={placeIds} onChange={setPlaceIds} disabled={locked}/>}
            <p className="muted-note">{scopeMode === 'ALL_TENANT' ? '将授予本单位全部现有及未来设备；不是仅当前列表。' : scopeMode === 'PLACE_SUBTREE' ? '只保存位置根；后续新增设备自动进入，设备移出子树自动失去可见性。DEVICE_SET 不能因为当前覆盖这些设备而授予未来位置权。' : scope.data.mode === 'ALL_TENANT' ? `将从全单位范围改为明确选择的 ${deviceIds.length} 台，其余设备将不再可见。` : `比现有集合新增 ${deviceIds.filter(id => !scope.data!.device_ids.includes(id)).length} 台、移除 ${scope.data.device_ids.filter(id => !deviceIds.includes(id)).length} 台。`}后端仍会校验每个对象、单位、结构范围；任何无效或越权对象整体拒绝。</p>
            <div className="field"><Label htmlFor="scope-reason">设备范围变更理由</Label><Input id="scope-reason" value={scopeReason} onChange={e => setScopeReason(e.target.value)} required maxLength={1000}/></div></fieldset>
            {scopeIntent ? <><div className="notice">本次固定单位 {scopeIntent.tenantId} · 范围版本 {scopeIntent.revision}<br/>{scopeIntent.mode === 'ALL_TENANT' ? '明确授予当前单位全部现有及未来设备。' : scopeIntent.mode === 'PLACE_SUBTREE' ? `动态位置根 ${scopeIntent.placeIds.length} 个：${scopeIntent.placeIds.join(' · ')}（包含后代与未来新增设备）` : scopeIntent.deviceIds.length ? `仅授予这 ${scopeIntent.deviceIds.length} 个明确设备：${scopeIntent.deviceIds.join(' · ')}` : '空 DEVICE_SET：保存后目标无任何设备访问权；这不是默认全量授权。'}<br/>理由：{scopeIntent.reason}</div><AccessConfirmation submitLabel="确认设备范围" onReload={onReload}/></> : <Button type="submit" disabled={locked}>核对设备范围</Button>}
          </form></>}
        </>}
      </section>}
    </>}</div></>
}
function AccessConfirmation({ submitLabel, onReload }: { submitLabel: string; onReload: () => void }) {
  const command = useAuthorizationWrite()
  return <>{command.phase === 'UNCERTAIN' && <div role="status" className="notice">结果未确认，不表示失败。原始业务意图、版本和幂等键已保留；只能沿原键确认，不能修改、关闭或另建操作。离开页面后仍可在原会话返回。</div>}
    {command.phase === 'PENDING' && <div role="status" className="notice">提交中，业务字段已冻结；返回此页面仍等待同一操作。</div>}
    {command.phase === 'CONFLICT' ? <div className="notice">当前版本或目标冲突，不能继续提交。<Button type="button" variant="outline" onClick={() => { if (command.reloadConflict()) onReload() }}>刷新并重新打开</Button></div> : null}
    {command.error != null && <ErrorNotice error={command.error}/>}<div className="form-actions"><Button type="button" variant="outline" disabled={command.phase !== 'CONFIRMING'} onClick={() => command.edit()}>返回修改</Button><Button type="submit" disabled={command.phase === 'PENDING' || command.phase === 'CONFLICT'}>{command.phase === 'PENDING' ? '提交中…' : submitLabel}</Button></div></>
}
