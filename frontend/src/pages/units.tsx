import { useMemo, useState } from 'react'
import { Building2, MapPin, Pencil, Plus } from 'lucide-react'
import { unitDirectoryApi, type UnitCreate, type UnitDetails } from '@/lib/unit-directory'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Empty, ErrorNotice, Loading, PageHeading, RefreshButton } from '@/components/common'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { UnitLifecycleEditor } from '@/components/unit-lifecycle-editor'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { PageHelp } from '@/components/page-tools'

const blank = (): UnitCreate => ({ name: '', credit_code: '', region_id: '', unit_type: '', industry: '', map_address: '', detail_address: '', contact_name: '', contact_phone: '', remark: '', latitude: null, longitude: null, service_start: null, service_end: null, engineering_service_start: null, engineering_service_end: '' })

export function UnitsPage() {
  const session = useSession()
  if (session.audience !== 'management' || !session.permissions.includes('units:read')) return <Empty title="当前账户无单位管理权限" />
  return <UnitsContent />
}

function UnitsContent() {
  const session = useSession()
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [status, setStatus] = useState(''), [editing, setEditing] = useState<UnitDetails | 'new' | null>(null), [statusEditing, setStatusEditing] = useState<UnitDetails | null>(null), [lifecycle, setLifecycle] = useState<{unit:UnitDetails;mode:'archive'|'delete'}|null>(null), [selected, setSelected] = useState<UnitDetails | null>(null)
  const canWrite = session.permissions.includes('units:write')
  const scopeReady = session.scopeInitialized && (session.scopeTenantIds.length > 0 || session.scopePlaceIds.length > 0)
  const list = useResource(`units:${session.generation}:${page}:${keyword}:${status}:${JSON.stringify(session.scopeTenantIds)}:${JSON.stringify(session.scopePlaceIds)}`, signal => unitDirectoryApi.list(page, keyword, status, session.scopeTenantIds, session.scopePlaceIds, signal), scopeReady)
  const pages = useMemo(() => Math.max(1, Math.ceil((list.data?.total ?? 0) / (list.data?.size ?? 20))), [list.data])
  return <>
    <PageHeading eyebrow="UNITS · 单位" title="单位管理">
      <PageHelp title="单位目录说明">按当前授权单位与位置范围浏览；单位登记信息、联系人、服务有效期与详情由管理 API 提供。新增单位不依赖当前浏览范围，新增和生命周期维护需要对应写入权限。</PageHelp>
      {session.scopeInitialized && <DataTransferActions kind="UNITS" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), keyword, status }} onCompleted={result => { if ('plan_id' in result) list.refresh() }}/>}
      <RefreshButton onClick={list.refresh} />{canWrite && <Button onClick={() => setEditing('new')}><Plus />新增单位</Button>}
    </PageHeading>
    <Card className="list-card"><CardContent>
      <div className="filter-row"><Input aria-label="单位搜索" value={keyword} onChange={event => { setKeyword(event.target.value); setPage(1) }} placeholder="单位名称、统一社会信用代码或地址" /><select className="native-select" aria-label="单位状态" value={status} onChange={event => { setStatus(event.target.value); setPage(1) }}><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option></select></div>
      {!scopeReady ? <Empty title={session.scopeInitialized ? '当前未选择范围' : '正在初始化授权范围'}>请在左侧勾选至少一个已授权单位或位置后浏览。新增单位不依赖当前浏览范围。</Empty> : list.loading ? <Loading /> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh} /> : list.data?.items.length ? <Table><TableHeader><TableRow><TableHead>单位名称</TableHead><TableHead>统一社会信用代码</TableHead><TableHead>类别 / 行业</TableHead><TableHead>地址</TableHead><TableHead>联系人</TableHead><TableHead>服务状态</TableHead><TableHead>状态</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{list.data.items.map(unit => <TableRow key={unit.id}><TableCell><Button variant="link" onClick={() => setSelected(unit)}><Building2 />{unit.name}</Button></TableCell><TableCell className="mono">{unit.credit_code || '—'}</TableCell><TableCell>{[unit.unit_type, unit.industry].filter(Boolean).join(' / ') || '—'}</TableCell><TableCell>{[unit.map_address, unit.detail_address].filter(Boolean).join(' ') || '—'}</TableCell><TableCell>{[unit.contact_name, unit.contact_phone].filter(Boolean).join(' · ') || '—'}</TableCell><TableCell>{serviceLabel(unit)}</TableCell><TableCell><Badge variant={unit.enabled ? 'secondary' : 'outline'}>{unit.archived_at ? '已归档' : unit.enabled ? '启用' : '停用'}</Badge></TableCell><TableCell>{canWrite && <><Button variant="ghost" size="sm" disabled={!!unit.archived_at} onClick={() => setEditing(unit)}><Pencil />编辑</Button><Button variant="ghost" size="sm" disabled={!!unit.archived_at} onClick={() => setStatusEditing(unit)}>{unit.enabled ? '停用' : '启用'}</Button><Button variant="ghost" size="sm" disabled={!!unit.archived_at} onClick={() => setLifecycle({unit,mode:'archive'})}>归档单位</Button><Button variant="ghost" size="sm" disabled={!!unit.archived_at} onClick={() => setLifecycle({unit,mode:'delete'})}>删除空单位</Button></>}</TableCell></TableRow>)}</TableBody></Table> : <Empty title={keyword ? '没有匹配的单位' : '当前授权范围暂无单位'} />}
      {list.data && <div className="version-actions"><span>共 {list.data.total} 个单位</span><Button variant="outline" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</Button><span>{page} / {pages}</span><Button variant="outline" disabled={page >= pages} onClick={() => setPage(page + 1)}>下一页</Button></div>}
    </CardContent></Card>
    {selected && <UnitDetailsPanel unit={selected} close={() => setSelected(null)} />}
    <Sheet open={editing !== null} onOpenChange={open => { if (!open) setEditing(null) }}><SheetContent className="review-sheet sm:max-w-2xl">{editing !== null && <UnitEditor key={editing === 'new' ? 'new' : editing.id} unit={editing === 'new' ? undefined : editing} close={() => setEditing(null)} saved={() => { setEditing(null); list.refresh() }} />}</SheetContent></Sheet>
    <Sheet open={statusEditing !== null} onOpenChange={open => { if (!open) setStatusEditing(null) }}><SheetContent className="review-sheet sm:max-w-xl">{statusEditing && <UnitStatusEditor unit={statusEditing} close={() => setStatusEditing(null)} saved={() => { setStatusEditing(null); list.refresh(); useSession.getState().refreshTenants(); useSession.getState().refreshScopeTree() }} />}</SheetContent></Sheet>
    <Sheet open={lifecycle !== null} onOpenChange={open => { if (!open) setLifecycle(null) }}><SheetContent className="review-sheet sm:max-w-xl">{lifecycle && <UnitLifecycleEditor unit={lifecycle.unit} mode={lifecycle.mode} close={() => setLifecycle(null)} saved={() => { setLifecycle(null); list.refresh(); useSession.getState().refreshTenants(); useSession.getState().refreshScopeTree() }} />}</SheetContent></Sheet>
  </>
}

function serviceLabel(unit: UnitDetails) {
  const today = new Date().toISOString().slice(0, 10)
  const statuses: string[] = []
  for (const [label, start, end] of [['单位服务', unit.service_start, unit.service_end], ['工程服务', unit.engineering_service_start, unit.engineering_service_end]] as const) {
    if (!start && !end) continue
    if (start && start > today) statuses.push(`${label}未开始`)
    else if (end && end < today) statuses.push(`${label}已到期`)
    else statuses.push(`${label}有效`)
  }
  return statuses.length ? statuses.join(' · ') : '未登记'
}

function UnitStatusEditor({ unit, close, saved }: { unit: UnitDetails; close: () => void; saved: () => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState<unknown>()
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError(undefined)
    try { await unitDirectoryApi.updateStatus(unit.id, { name: unit.name, enabled: !unit.enabled, expected_revision: unit.revision }, crypto.randomUUID()); saved() }
    catch (failure) { setError(failure) }
    finally { setBusy(false) }
  }
  return <><SheetHeader><SheetTitle>{unit.enabled ? '停用单位' : '启用单位'} · {unit.name}</SheetTitle></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}><p className="muted-note">{unit.enabled ? '停用后该单位账号不能继续访问；相关客户会话将被撤销。' : '启用后该单位重新纳入授权单位读取范围。'}</p>{error != null && <ErrorNotice error={error} />}<div className="version-actions"><Button type="submit" disabled={busy}>{busy ? '处理中…' : unit.enabled ? '确认停用' : '确认启用'}</Button><Button type="button" variant="ghost" onClick={close}>取消</Button></div></form></div></>
}

function UnitEditor({ unit, close, saved }: { unit?: UnitDetails; close: () => void; saved: () => void }) {
  const [form, setForm] = useState<UnitCreate>(() => ({ ...blank(), ...unit }))
  const [admin, setAdmin] = useState(false), [adminPermissions, setAdminPermissions] = useState<string[]>([]), [busy, setBusy] = useState(false), [error, setError] = useState<unknown>()
  const catalog = useResource('unit-admin-permission-catalog', signal => api.permissionCatalog(signal), admin)
  const set = (key: Exclude<keyof UnitCreate, 'latitude' | 'longitude' | 'admin_permissions'>, value: string) => setForm(previous => ({ ...previous, [key]: value }))
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError(undefined)
    try {
      const key = crypto.randomUUID()
      const payload = { ...form, credit_code: form.credit_code?.trim() || null }
      if (unit) await unitDirectoryApi.update(unit.id, { ...payload, expected_revision: unit.revision }, key)
      else { const body = { ...payload }; if (admin) body.admin_permissions = adminPermissions; else { delete body.admin_username; delete body.admin_name; delete body.admin_initial_password; delete body.admin_permissions } await unitDirectoryApi.create(body, key) }
      useSession.getState().refreshTenants()
      useSession.getState().refreshScopeTree()
      saved()
    } catch (failure) { setError(failure) } finally { setBusy(false) }
  }
  return <><SheetHeader><SheetTitle>{unit ? `编辑单位 · ${unit.name}` : '登记单位'}</SheetTitle></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}>
    <div className="field"><Label htmlFor="unit-name">单位名称</Label><Input id="unit-name" required maxLength={32} value={form.name} onChange={e => set('name', e.target.value)} /></div>
    <div className="field"><Label htmlFor="unit-credit-code">统一社会信用代码</Label><Input id="unit-credit-code" minLength={9} maxLength={18} value={form.credit_code ?? ''} onChange={e => set('credit_code', e.target.value)} /></div>
    <div className="field"><Label htmlFor="unit-region">行政区划代码</Label><Input id="unit-region" maxLength={64} value={form.region_id ?? ''} onChange={e => set('region_id', e.target.value)} /></div>
    <div className="field"><Label>单位类别 / 行业</Label><div className="filter-row"><Input aria-label="单位类别" placeholder="单位类别" maxLength={64} value={form.unit_type ?? ''} onChange={e => set('unit_type', e.target.value)} /><Input aria-label="所属行业" placeholder="所属行业" maxLength={128} value={form.industry ?? ''} onChange={e => set('industry', e.target.value)} /></div></div>
    <div className="field"><Label>地图地址 / 详细地址</Label><Input aria-label="地图地址" placeholder="地图地址" maxLength={256} value={form.map_address ?? ''} onChange={e => set('map_address', e.target.value)} /><Input aria-label="详细地址" placeholder="详细地址" maxLength={40} value={form.detail_address ?? ''} onChange={e => set('detail_address', e.target.value)} /></div>
    <div className="field"><Label>联系人 / 联系电话</Label><div className="filter-row"><Input aria-label="联系人" maxLength={30} value={form.contact_name ?? ''} onChange={e => set('contact_name', e.target.value)} /><Input aria-label="联系电话" type="tel" maxLength={18} value={form.contact_phone ?? ''} onChange={e => set('contact_phone', e.target.value)} /></div></div>
    <div className="field"><Label>经纬度</Label><div className="filter-row"><Input aria-label="纬度" type="number" step="any" min="-90" max="90" placeholder="纬度" value={form.latitude ?? ''} onChange={e => setForm(p => ({ ...p, latitude: e.target.value ? Number(e.target.value) : null }))} /><Input aria-label="经度" type="number" step="any" min="-180" max="180" placeholder="经度" value={form.longitude ?? ''} onChange={e => setForm(p => ({ ...p, longitude: e.target.value ? Number(e.target.value) : null }))} /></div></div>
    <div className="field"><Label>单位服务有效期</Label><div className="filter-row"><Input aria-label="单位服务开始日期" type="date" value={form.service_start ?? ''} onChange={e => set('service_start', e.target.value)} /><Input aria-label="单位服务结束日期" type="date" value={form.service_end ?? ''} onChange={e => set('service_end', e.target.value)} /></div></div>
    <div className="field"><Label>工程服务有效期</Label><div className="filter-row"><Input aria-label="工程服务开始日期" type="date" value={form.engineering_service_start ?? ''} onChange={e => set('engineering_service_start', e.target.value)} /><Input aria-label="工程服务结束日期" type="date" value={form.engineering_service_end ?? ''} onChange={e => set('engineering_service_end', e.target.value)} /></div></div>
    <div className="field"><Label htmlFor="unit-remark">备注</Label><Input id="unit-remark" maxLength={70} value={form.remark ?? ''} onChange={e => set('remark', e.target.value)} /></div>
    {!unit && <label className="inline-check"><input type="checkbox" checked={admin} onChange={e => setAdmin(e.target.checked)} />同时创建单位管理员（操作员设置初始密码，不发送短信）</label>}
    {admin && !unit && <>
      <div className="field"><Label htmlFor="unit-admin-username">管理员用户名</Label><Input id="unit-admin-username" required autoComplete="username" value={form.admin_username ?? ''} onChange={e => set('admin_username', e.target.value)} /></div>
      <div className="field"><Label htmlFor="unit-admin-name">管理员姓名</Label><Input id="unit-admin-name" value={form.admin_name ?? ''} onChange={e => set('admin_name', e.target.value)} /></div>
      <div className="field"><Label htmlFor="unit-admin-initial-password">管理员初始密码</Label><Input id="unit-admin-initial-password" required type="password" minLength={12} maxLength={128} autoComplete="new-password" value={form.admin_initial_password ?? ''} onChange={e => set('admin_initial_password', e.target.value)} /><p className="muted-note">首次登录必须修改密码；不会向管理员发送密码短信。</p></div>
      <fieldset className="field"><legend>管理员初始权限</legend><details><summary>设置初始权限（已选 {adminPermissions.length} 项）</summary>{catalog.loading ? <Loading /> : catalog.error != null ? <ErrorNotice error={catalog.error} retry={catalog.refresh} /> : (catalog.data?.items ?? []).filter(permission => permission.user_types.includes('CUSTOMER') && permission.delegable).map(permission => <label key={permission.key} className="inline-check"><input type="checkbox" checked={adminPermissions.includes(permission.key)} onChange={event => setAdminPermissions(current => event.target.checked ? [...current, permission.key] : current.filter(item => item !== permission.key))} />{permission.label}</label>)}</details></fieldset>
    </>}
    {error != null && <ErrorNotice error={error} />}<div className="version-actions"><Button type="submit" disabled={busy || admin && (catalog.loading || catalog.error != null || !catalog.data)}>{busy ? '保存中…' : '保存单位'}</Button><Button type="button" variant="ghost" onClick={close}>取消</Button></div>
  </form></div></>
}

function UnitDetailsPanel({ unit, close }: { unit: UnitDetails; close: () => void }) {
  const [tab, setTab] = useState<'base'|'places'|'devices'|'accounts'>('base')
  const detail = useResource(`unit-detail:${unit.id}`, signal => unitDirectoryApi.detail(unit.id, signal))
  const places = useResource(`unit-places:${unit.id}`, signal => api.places(signal, [unit.id], [], true))
  const devices = useResource(`unit-devices:${unit.id}`, signal => api.devices(1, '', 100, signal, undefined, undefined, unit.id))
  const accounts = useResource(`unit-accounts:${unit.id}`, signal => api.users(false, 1, '', signal, [unit.id], [], true))
  const current = detail.data ?? unit
  return <Card style={{ marginTop: 14 }}><CardContent><div className="version-actions"><div><h2 className="section-heading"><Building2 />{current.name} · 单位详情</h2><p className="muted-note">单位登记信息及当前本地单位、位置和账号数据。</p></div><Button variant="ghost" onClick={close}>关闭详情</Button></div><div className="filter-row">{(['base','places','devices','accounts'] as const).map(value => <Button key={value} variant={tab === value ? 'default' : 'outline'} onClick={() => setTab(value)}>{({base:'基本信息与服务',places:'位置',devices:'设备',accounts:'账号'})[value]}</Button>)}</div>
    {tab === 'base' && (detail.loading ? <Loading /> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh} /> : <Table><TableBody>{[['统一社会信用代码',current.credit_code],['行政区划',current.region_id],['单位类别',current.unit_type],['所属行业',current.industry],['地址',[current.map_address,current.detail_address].filter(Boolean).join(' ')],['联系人',[current.contact_name,current.contact_phone].filter(Boolean).join(' · ')],['单位服务有效期',[current.service_start,current.service_end].filter(Boolean).join(' 至 ')],['工程服务有效期',[current.engineering_service_start,current.engineering_service_end].filter(Boolean).join(' 至 ')],['经纬度',current.latitude == null ? null : `${current.latitude}, ${current.longitude}`],['备注',current.remark]].map(([label,value]) => <TableRow key={String(label)}><TableHead>{label}</TableHead><TableCell>{value || '—'}</TableCell></TableRow>)}</TableBody></Table>)}
    {tab === 'places' && (places.loading ? <Loading /> : places.error != null ? <ErrorNotice error={places.error} retry={places.refresh} /> : places.data?.length ? <ul>{places.data.map(place => <li key={place.id}><MapPin size={14} /> {place.name} · {place.device_count} 台设备</li>)}</ul> : <Empty title="该单位暂无登记位置" />)}
    {tab === 'devices' && (devices.loading ? <Loading /> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh} /> : devices.data?.items.length ? <Table><TableHeader><TableRow><TableHead>设备名称</TableHead><TableHead>设备编号</TableHead><TableHead>位置 ID</TableHead><TableHead>接入状态</TableHead></TableRow></TableHeader><TableBody>{devices.data.items.map(device => <TableRow key={device.id}><TableCell>{device.name}</TableCell><TableCell>{device.id}</TableCell><TableCell>{device.place_id || '—'}</TableCell><TableCell>{device.connectivity || '—'}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="该单位暂无设备" />)}
    {tab === 'accounts' && (accounts.loading ? <Loading /> : accounts.error != null ? <ErrorNotice error={accounts.error} retry={accounts.refresh} /> : accounts.data?.items.length ? <Table><TableHeader><TableRow><TableHead>用户名</TableHead><TableHead>姓名</TableHead><TableHead>状态</TableHead><TableHead>首次登录改密</TableHead></TableRow></TableHeader><TableBody>{accounts.data.items.map(account => <TableRow key={account.id}><TableCell>{account.username}</TableCell><TableCell>{account.name}</TableCell><TableCell>{account.enabled ? '启用' : '停用'}</TableCell><TableCell>{account.must_change_password ? '是' : '否'}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="该单位暂无账号" />)}
  </CardContent></Card>
}
