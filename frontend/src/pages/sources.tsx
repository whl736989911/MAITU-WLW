import { useState, type FormEvent } from 'react'
import { KeyRound, Link2, Pencil, Plus, Unlink } from 'lucide-react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime, reported } from '@/lib/format'
import type { CredentialResult, Device, Health, IngestSource, SourceBinding } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { IdentitySearch, OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { credentialDrawer, IngestionBoundary, IngestionGate, OneTimeCredential, SourceEndpoint, type CredentialDrawerState } from '@/components/ingestion-common'
import { PageHelp } from '@/components/page-tools'

type SourceEditor = { mode: 'create'; source: null; binding?: never } | { mode: 'edit' | 'rotate' | 'bind' | 'detach'; source: IngestSource; binding?: SourceBinding }

function sourceIntakeState(source: IngestSource, health?: Health) {
  if (!source.enabled) return '已停用'
  if (health?.generic_ingest_enabled === false) return '通用 HTTP 入口未启用'
  if (source.last_received_at != null || source.message_count > 0) return '有接收记录'
  return '已登记 · 尚无接收记录'
}
export function SourcesPage({ health }: { health?: Health }) { return <IngestionGate page="sources"><SourcesContent health={health}/></IngestionGate> }
function SourcesContent({ health }: { health?: Health }) {
  const permissions = useSession(s => s.permissions), scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeInitialized = useSession(s => s.scopeInitialized)
  const canWrite = permissions.includes('sources:write'), canRotate = permissions.includes('sources:credentials'), canBind = permissions.includes('sources:bind') && permissions.includes('device:write')
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [selectedId, setSelectedId] = useState('')
  const scopeKey = `${scopeInitialized}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`
  const sources = useResource(`sources:${page}:${keyword}:${scopeKey}`, signal => api.sources(page, keyword, signal, scopeTenantIds, scopePlaceIds), scopeInitialized)
  const [editor, setEditor] = useState<SourceEditor | null>(null), [credential, setCredential] = useState<CredentialDrawerState | null>(null), [notice, setNotice] = useState('')
  const selected = sources.data?.items.find(item => item.id === selectedId)
  function saved(result?: CredentialResult) { setEditor(null); sources.refresh(); setNotice('来源配置操作已保存，不推进设备/报警，不改变设备来源或产品版本。'); if (result) setCredential(credentialDrawer(result)) }
  return <>
    <PageHeading eyebrow="SOURCES · 接入来源" title="通用 HTTP 接入来源"><PageHelp title="接入来源怎么使用"><p>用于自有网关或第三方系统向平台主动上报。创建只登记单位和来源名称，并由平台生成一次性可见的令牌，不需要填厂商账号。</p><p>在发送端配置上报地址和令牌；在平台显式绑定同单位设备及其在来源中的标识，再到「消息追踪」核对是否接收到数据。不会按 EUI 自动匹配或抢占已有主源。</p><p>海康使用独立的「海康云接入」，不在此登记。启用一条来源不代表有设备接入。</p></PageHelp></PageHeading>
    <IngestionBoundary health={health}/>
    <Card className="list-card management-list-panel"><CardContent>
      <div className="management-list-bar"><h2>接入来源列表</h2>
        <div className="list-toolbar management-list-actions">
          <RefreshButton onClick={sources.refresh}/>
          {canWrite && <Button onClick={() => setEditor({ mode: 'create', source: null })}><Plus/>创建接入来源</Button>}
        </div>
      </div>
      {notice && <div role="status" className="notice">{notice}</div>}
      <IdentitySearch placeholder="按来源名称查询（字面匹配）" aria-label="搜索接入来源" onSearch={value => { setPage(1); setKeyword(value) }}/>
      {sources.loading ? <Loading/> : sources.error != null ? <ErrorNotice error={sources.error} retry={sources.refresh}/> : sources.data?.items.length ? <>
        <Table><TableHeader><TableRow><TableHead>来源 / 协议</TableHead><TableHead>状态</TableHead><TableHead>接收统计</TableHead><TableHead>最后接收</TableHead><TableHead>版本</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
          <TableBody>{sources.data.items.map(source => <TableRow key={source.id}>
            <TableCell><strong>{source.name}</strong><div className="table-sub mono">{source.id}</div><div className="table-sub mono">单位：{source.tenant_id}</div><div className="table-sub">{source.protocol}</div></TableCell>
            <TableCell><Badge variant="outline">{sourceIntakeState(source, health)}</Badge></TableCell>
            <TableCell>接收 {source.message_count}<div className="table-sub">重复 {source.duplicate_count}</div></TableCell>
            <TableCell>{source.last_received_at ? dateTime(source.last_received_at) : '无接收时间'}</TableCell><TableCell>{source.revision}</TableCell>
            <TableCell><div className="identity-row-actions">
              <Button variant="ghost" size="sm" onClick={() => setSelectedId(source.id)}><Link2/>绑定与历史</Button>
              {canWrite && <Button variant="ghost" size="sm" onClick={() => setEditor({ mode: 'edit', source })}><Pencil/>来源配置</Button>}
              {canRotate && <Button variant="ghost" size="sm" onClick={() => setEditor({ mode: 'rotate', source })}><KeyRound/>轮换凭据</Button>}
            </div></TableCell>
          </TableRow>)}</TableBody>
        </Table><Pager page={sources.data.page} size={sources.data.size} total={sources.data.total} onChange={setPage}/>
      </> : <Empty title="暂无接入来源">只有准备向平台发送通用 HTTP 报文时才需要创建来源；海康和旧设备原有通道不依赖这张表。</Empty>}
    </CardContent></Card>
    {selected && <SourceBindings key={`${selected.id}:${selected.revision}`} source={selected} canBind={canBind} open={setEditor}/>}
    <Sheet open={!!editor} onOpenChange={open => { if (!open) setEditor(null) }}><SheetContent className="review-sheet sm:max-w-xl">{editor && <SourceForm key={`${editor.mode}:${editor.source?.id ?? 'new'}:${editor.source?.revision ?? ''}:${editor.binding?.id ?? ''}`} editor={editor} onSaved={saved} onRefresh={() => { setEditor(null); sources.refresh() }}/>}</SheetContent></Sheet>
    <Sheet open={!!credential} onOpenChange={open => { if (!open) setCredential(null) }}><SheetContent className="review-sheet sm:max-w-xl">{credential && <OneTimeCredential credential={credential}/>}</SheetContent></Sheet>
  </>
}
function SourceBindings({ source, canBind, open }: { source: IngestSource; canBind: boolean; open: (editor: SourceEditor) => void }) {
  const bindings = useResource(`source-bindings:${source.id}:${source.revision}`, signal => api.sourceBindings(source.id, signal, source.tenant_id))
  return <Card className="product-versions"><CardHeader className="section-header"><div><CardTitle>{source.name} · 显式主源绑定</CardTitle><p>同设备只能一个主源；不会静默抢占。解绑保留历史，不回填旧报文或迁移产品版本。</p></div>{canBind && <Button variant="outline" onClick={() => open({ mode: 'bind', source })}><Link2/>绑定设备</Button>}</CardHeader><CardContent>{bindings.loading ? <Loading/> : bindings.error != null ? <ErrorNotice error={bindings.error} retry={bindings.refresh}/> : bindings.data?.items.length ? <Table><TableHeader><TableRow><TableHead>设备 / 来源设备标识</TableHead><TableHead>状态</TableHead><TableHead>创建 / 结束</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{bindings.data.items.map(binding => <TableRow key={binding.id}><TableCell><strong className="mono">{binding.device_id}</strong><div className="table-sub mono">{binding.vendor_device_id}</div></TableCell><TableCell>{binding.is_active ? '当前主源绑定' : '已解绑 · 历史'}</TableCell><TableCell>{dateTime(binding.created_at)}<div className="table-sub">{dateTime(binding.ended_at)}</div></TableCell><TableCell>{canBind && binding.is_active && <Button variant="outline" size="sm" onClick={() => open({ mode: 'detach', source, binding })}><Unlink/>显式解绑</Button>}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="暂无绑定历史">旧设备标识不会自动变成绑定关系。</Empty>}<SourceEndpoint id={source.id}/></CardContent></Card>
}
function BindingDevicePicker({ source, value, choose }: { source: IngestSource; value: string; choose: (device: Device | null) => void }) {
  const allowed = useSession(s => s.permissions.includes('device:read'))
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [search, setSearch] = useState('')
  const devices = useResource(`binding-devices:${source.tenant_id}:${page}:${keyword}`, signal => api.devices(page, keyword, undefined, signal, undefined, undefined, source.tenant_id), allowed)
  if (!allowed) return <div className="notice">需要当前单位设备读取权限才能明确选择；不会用EUI猜测或伪造设备。</div>
  const eligible = (item: Device) => !!item.product_version_id
  return <div className="field"><Label htmlFor="source-device">当前单位设备</Label><div className="picker-search"><Input aria-label="搜索可绑定设备" placeholder="按设备名称或标识查询" value={search} onChange={e => setSearch(e.target.value)}/><Button type="button" variant="outline" onClick={() => { setKeyword(search.trim()); setPage(1); choose(null) }}>查询设备</Button></div>{devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : <><select id="source-device" className="native-select" value={value} required onChange={e => choose(devices.data?.items.find(item => item.id === e.target.value && eligible(item)) ?? null)}><option value="">请显式选择当前单位内已绑定发布模板的设备</option>{devices.data?.items.map(item => <option value={item.id} key={item.id} disabled={!eligible(item)}>{reported(item.name)} · {item.id}{!eligible(item) ? '（未绑定已发布模板）' : ` · ${item.product_version_id}`}</option>)}</select>{devices.data && <Pager page={devices.data.page} size={devices.data.size} total={devices.data.total} onChange={next => { setPage(next); choose(null) }}/>}</>}<p className="muted-note">设备已有固定版本由设备API提供、后端再次核验发布状态与单位。若已有其它主源，必须先明确解绑，不能在此抢占。</p></div>
}
function SourceForm({ editor, onSaved, onRefresh }: { editor: SourceEditor; onSaved: (result?: CredentialResult) => void; onRefresh: () => void }) {
  const source = editor.source
  const units = useResource('source-write-units', signal => api.scopeTree(signal))
  const [createTenantId, setCreateTenantId] = useState(''), [name, setName] = useState(source?.name ?? ''), [enabled, setEnabled] = useState(source?.enabled ?? true)
  const [chosen, setChosen] = useState<Device | null>(null), [vendorId, setVendorId] = useState(''), [reason, setReason] = useState('')
  const action = useMutationAction()
  const title = { create: '创建接入来源', edit: '保存来源配置', rotate: '轮换接入凭据', bind: '绑定当前主源', detach: '显式解绑主源' }[editor.mode]
  function submit(event: FormEvent) {
    event.preventDefault()
    if (editor.mode === 'create' && !units.data?.some(unit => unit.id === createTenantId)) { action.setError(new Error('请明确选择已授权目标单位')); return }
    if ((editor.mode === 'create' || editor.mode === 'edit') && !name.trim()) { action.setError(new Error('请填写来源名称')); return }
    if (editor.mode === 'bind' && (!chosen || !chosen.product_version_id || !vendorId.trim())) { action.setError(new Error('请明确选择当前单位中已绑定发布模板的设备，并填写来源设备标识')); return }
    if ((editor.mode === 'bind' || editor.mode === 'detach') && !reason.trim()) { action.setError(new Error('绑定/解绑原因必填')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    let credential: CredentialResult | undefined
    void action.run(async (key, signal) => {
      if (editor.mode === 'create') { credential = await api.createSource({ name: name.trim() }, key, signal, createTenantId); return }
      if (editor.mode === 'rotate') { credential = await api.rotateSourceToken(source!.id, source!.revision, key, signal, source!.tenant_id); return }
      if (editor.mode === 'edit') return api.updateSource(source!.id, { name: name.trim(), enabled, expected_revision: source!.revision }, key, signal, source!.tenant_id)
      if (editor.mode === 'bind') return api.bindSource(source!.id, { device_id: chosen!.id, vendor_device_id: vendorId, expected_revision: source!.revision, reason: reason.trim() }, key, signal, source!.tenant_id)
      return api.detachSource(source!.id, editor.binding!.id, { expected_revision: source!.revision, reason: reason.trim() }, key, signal, source!.tenant_id)
    }, () => onSaved(credential))
  }
  return <><SheetHeader className="review-header"><SheetTitle>{title}</SheetTitle><SheetDescription>来源按真实单位所有权读取；创建必须明确选择单位。协议与单位创建后不可改。</SheetDescription></SheetHeader><div className="review-body">{editor.mode === 'create' && <div className="field"><Label htmlFor="source-target-unit">创建目标单位</Label>{units.loading ? <Loading/> : units.error != null ? <ErrorNotice error={units.error} retry={units.refresh}/> : <select id="source-target-unit" className="native-select" value={createTenantId} onChange={e => { setCreateTenantId(units.data?.some(unit => unit.id === e.target.value) ? e.target.value : ''); action.touch() }}><option value="">请明确选择已授权单位（不默认）</option>{units.data?.map(unit => <option key={unit.id} value={unit.id}>{unit.name} · {unit.id}</option>)}</select>}</div>}<form className="form-stack" onSubmit={submit}>{!action.confirming ? <>{source && <div className="identity-readonly">{source.name} · {source.tenant_id} · {source.id} · revision {source.revision}</div>}{(editor.mode === 'create' || editor.mode === 'edit') && <div className="field"><Label htmlFor="source-name">来源名称</Label><Input id="source-name" required value={name} onChange={e => { setName(e.target.value); action.touch() }}/></div>}{editor.mode === 'edit' && <label className="confirmation"><input type="checkbox" checked={enabled} onChange={e => { setEnabled(e.target.checked); action.touch() }}/>启用接入来源（停用拒绝接收，不批量修改设备状态）</label>}{editor.mode === 'rotate' && <div className="notice">轮换提交后旧令牌立即失效。只有首次成功响应可见明文；若首次响应丢失，同key重试只返回恢复提示，需您再次显式轮换。</div>}{editor.mode === 'bind' && <><BindingDevicePicker source={source!} value={chosen?.id ?? ''} choose={device => { setChosen(device); action.touch() }}/><div className="field"><Label htmlFor="vendor-device-id">来源设备标识（不透明值）</Label><Input id="vendor-device-id" required value={vendorId} onChange={e => { setVendorId(e.target.value); action.touch() }}/><p className="muted-note">由调用方显式使用的标识，不默认用EUI，不要求16位十六进制；不可空白，长度限制由服务端集中校验。</p></div></>}{editor.mode === 'detach' && <div className="identity-readonly">绑定 {editor.binding?.id} · {editor.binding?.device_id} · {editor.binding?.vendor_device_id}</div>}{(editor.mode === 'bind' || editor.mode === 'detach') && <div className="field"><Label htmlFor="binding-reason">绑定操作原因</Label><textarea id="binding-reason" rows={3} required value={reason} onChange={e => { setReason(e.target.value); action.touch() }}/></div>}{action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit">核对并继续</Button></> : <OperationConfirmation action={action} title={`二次确认 · ${title}`} submitLabel={`确认${title}`} refresh={onRefresh} warning={editor.mode === 'rotate' ? '旧令牌提交后立即失效，新明文只在独立一次性抽屉内提供，不自动复制或下载。' : editor.mode === 'bind' || editor.mode === 'detach' ? '不会抢占其它主源、迁移产品版本或回填旧报文；设备与报警状态不变。' : '不改变任何设备/报警状态；新凭据仅首次响应明文、丢失后需显式轮换。'}><p>来源：{source?.id ?? '服务端分配'} · {name}</p><p>目标单位：{source?.tenant_id ?? createTenantId}</p><p>协议固定：GENERIC_HTTP_JSON_V1 · 真实通用来源</p>{source && <p>expected_revision：{source.revision}</p>}{editor.mode === 'edit' && <p>配置：{enabled ? '启用接收' : '停用接收'}</p>}{chosen && <p>设备：{chosen.id} · 来源设备标识 {vendorId}</p>}{reason && <p>原因：{reason}</p>}</OperationConfirmation>}</form></div></>
}
