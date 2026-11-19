import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { Tree, TreeSelect } from 'antd'
import { Building2, MapPin, Plus, Pencil, MoveRight } from 'lucide-react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { usePlaceMove } from '@/lib/place-move'
import { usePlaceTransfer } from '@/lib/place-transfer'
import { placePath, placeTreeOptions } from '@/lib/place-tree'
import { publishObjectChange } from '@/lib/object-events'
import { allowedPage, type PlaceNode, type ScopeUnit } from '@/lib/types'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { IdentitySearch } from '@/components/identity-common'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { PageHelp } from '@/components/page-tools'
import { PlaceMoveEditor } from '@/components/place-move-editor'
import { PlaceTransferEditor } from '@/components/place-transfer-editor'
const DeviceDetail = lazy(() => import('@/components/device-detail').then(module => ({ default: module.DeviceDetail })))

export function PlacesPage() {
  const { audience, permissions, user, generation } = useSession()
  if (!user || !allowedPage('places', audience, permissions)) return <Empty title="当前账户无单位位置权限"/>
  return <PlacesContent key={generation}/>
}

function PlacesContent() {
  const session = useSession(), move = usePlaceMove()
  const transfer = usePlaceTransfer()
  const [transferTarget, setTransferTarget] = useState<PlaceNode | null>(null), [transferHidden, setTransferHidden] = useState(false)
  const handledTransfer = useRef(transfer.completion?.serial ?? 0)
  const canWrite = session.audience === 'management' && session.permissions.includes('units:write')
  const [editor, setEditor] = useState<{ place?: PlaceNode; parentId?: string; tenantId?: string } | null>(null)
  const [moveTarget, setMoveTarget] = useState<PlaceNode | null>(move.intent?.preview.place ?? null)
  const [keyword, setKeyword] = useState(''), [selectedId, setSelectedId] = useState<string | null>(null)
  const [notice, setNotice] = useState(''), [placePage, setPlacePage] = useState(1)
  const [deviceDetail, setDeviceDetail] = useState<{ id: string; tenantId: string } | null>(null)
  const handledMove = useRef(move.completion?.serial ?? 0)
  const places = useResource(`places:${JSON.stringify(session.scopeTenantIds)}:${JSON.stringify(session.scopePlaceIds)}:${session.scopeTreeRevision}`, signal => api.places(signal, session.scopeTenantIds, session.scopePlaceIds, true))
  const units = useResource('place-write-units', signal => api.scopeTree(signal), canWrite)
  const selected = places.data?.find(place => place.id === selectedId)
  const placeDevices = useResource(`place-devices:${selected?.id ?? ''}:${placePage}:${session.scopeTreeRevision}`, signal => api.devices(placePage, '', 20, signal, '', undefined, selected?.tenant_id, [], selected ? [selected.id] : []), !!selected && session.permissions.includes('device:read'))
  const byId = useMemo(() => new Map((places.data ?? []).map(place => [place.id, place])), [places.data])
  const visiblePlaces = useMemo(() => {
    const all = places.data ?? [], q = keyword.trim().toLowerCase()
    if (!q) return all
    const included = new Set<string>()
    for (const place of all) {
      if (!place.name.toLowerCase().includes(q) && !place.legacy_place_id?.toLowerCase().includes(q)) continue
      let current: PlaceNode | undefined = place
      while (current && !included.has(current.id)) { included.add(current.id); current = current.parent_id ? byId.get(current.parent_id) : undefined }
    }
    return all.filter(place => included.has(place.id))
  }, [places.data, keyword, byId])
  const trees = useMemo(() => {
    const grouped = new Map<string, { name: string; places: PlaceNode[] }>()
    for (const place of visiblePlaces) {
      const group = grouped.get(place.tenant_id) ?? { name: place.tenant_name, places: [] }
      group.places.push(place); grouped.set(place.tenant_id, group)
    }
    return [...grouped].map(([id, group]) => ({ key: `unit:${id}`, title: group.name, selectable: false, children: placeTreeOptions(group.places) }))
  }, [visiblePlaces])
  useEffect(() => {
    if (!move.completion || move.completion.serial === handledMove.current) return
    handledMove.current = move.completion.serial
    setMoveTarget(null); places.refresh(); session.refreshScopeTree()
    const result = move.completion.result
    setNotice(`${result.changed ? '位置整体移动已完成' : '目标未变化，未重复修改'}：${result.places_count} 个位置、${result.devices_count} 台设备；设备身份和历史保留。`)
  }, [move.completion])
  useEffect(() => {
    if (!transfer.completion || transfer.completion.serial === handledTransfer.current) return
    handledTransfer.current = transfer.completion.serial
    setTransferTarget(null); setSelectedId(transfer.completion.result.target_place_id)
    places.refresh(); session.refreshScopeTree()
    setNotice(`整批移交已完成：${transfer.completion.result.places_count} 个位置、${transfer.completion.result.devices_count} 台设备。原单位历史及权限未迁移。`)
  }, [transfer.completion])
  function saved() { setEditor(null); places.refresh(); session.refreshScopeTree(); publishObjectChange('organization'); setNotice('位置已保存，单位位置树已更新。') }
  const moving = move.intent?.preview.place ?? moveTarget
  const transferring = transfer.intent?.place ?? transferTarget
  return <>
    <PageHeading eyebrow="PLACES · 位置" title="单位位置与设备">
      <PageHelp title="位置树与范围操作">位置树按真实父子关系展示；搜索保留可见上级。移动会影响整个子树，并要求选择同单位目标位置。位置或设备超出当前跨页授权范围时仍由服务端校验；设备身份和历史不会因移动改变。</PageHelp>
      {session.audience === 'management' && session.scopeInitialized && <DataTransferActions kind="PLACES" filters={{ unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), keyword }} onCompleted={result => { if ('plan_id' in result) { places.refresh(); session.refreshScopeTree() } }}/>}
      <RefreshButton onClick={() => { places.refresh(); placeDevices.refresh() }}/>{canWrite && <Button type="button" onClick={() => setEditor({})}><Plus/>新增位置</Button>}
    </PageHeading>
    {notice && <div className="notice" role="status">{notice}</div>}
    {transfer.intent && transferHidden && <div className="notice" role="status">有一笔保留的整体移交操作，状态：{transfer.phase === 'UNCERTAIN' ? '结果未确认' : transfer.phase === 'CONFLICT' ? '范围已变化' : '等待确认'}。<Button type="button" variant="outline" onClick={() => setTransferHidden(false)}>继续原移交操作</Button></div>}
    <div className="places-workspace">
      <Card className="list-card"><CardContent><h2 className="section-heading"><Building2 size={18}/>真实位置树 <Badge variant="outline">{places.data?.length ?? '—'} 个</Badge></h2>
        <IdentitySearch aria-label="搜索位置" placeholder="位置名称或旧平台位置 ID" onSearch={setKeyword}/>
        {places.loading ? <Loading/> : places.error != null ? <ErrorNotice error={places.error} retry={places.refresh}/> : trees.length ? <Tree key={keyword} aria-label="单位位置树" blockNode showLine defaultExpandAll treeData={trees} selectedKeys={selectedId ? [selectedId] : []} onSelect={keys => { if (keys.length) { setSelectedId(String(keys[0])); setPlacePage(1) } }}/>: <Empty title={keyword ? '没有匹配的位置' : '当前范围暂无真实位置'}/>}
        <p className="muted-note">按真实父子关系展示；搜索保留可见上级。上级不在当前范围时明确标注，不改成一级位置。</p>
      </CardContent></Card>
      <Card className="list-card"><CardContent>{selected ? <>
        <div className="list-toolbar"><div><h2 className="section-heading"><MapPin size={18}/>{selected.name}</h2><p className="muted-note">{selected.tenant_name} · {placePath(selected, places.data ?? [])}</p></div>{canWrite && <div className="identity-row-actions"><Button variant="outline" size="sm" onClick={() => setEditor({ place: selected, tenantId: selected.tenant_id })}><Pencil/>编辑位置</Button><Button variant="outline" size="sm" onClick={() => setEditor({ parentId: selected.id, tenantId: selected.tenant_id })}><Plus/>新增下级</Button><Button variant="outline" size="sm" disabled={!!move.intent || !!transfer.intent} onClick={() => setMoveTarget(selected)}><MoveRight/>同单位整体移动</Button>{session.permissions.includes('device:write') && <Button variant="outline" size="sm" disabled={!!move.intent || !!transfer.intent} onClick={() => { setTransferTarget(selected); setTransferHidden(false) }}>跨单位整批移交</Button>}</div>}</div>
        <div className="notice">第 {selected.level} 级 · 直接挂载 {selected.device_count} 台设备。下方查询包含全部子位置中的有权设备；位置/设备授权仍由后端判断。</div>
        {!session.permissions.includes('device:read') ? <Empty title="无设备读取权限，位置维护不自动授予设备权限"/> : placeDevices.loading ? <Loading/> : placeDevices.error != null ? <ErrorNotice error={placeDevices.error} retry={placeDevices.refresh}/> : placeDevices.data?.items.length ? <>
          <Table><TableHeader><TableRow><TableHead>设备</TableHead><TableHead>序列号</TableHead><TableHead>挂载位置</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{placeDevices.data.items.map(device => <TableRow key={device.id}><TableCell>{device.name ?? '未命名设备'}</TableCell><TableCell className="mono">{device.device_eui ?? '未提供'}</TableCell><TableCell>{device.place_name ?? '未登记'}</TableCell><TableCell><Button variant="ghost" size="sm" onClick={() => setDeviceDetail({ id: device.id, tenantId: selected.tenant_id })}>查看详情</Button></TableCell></TableRow>)}</TableBody></Table><Pager page={placeDevices.data.page} size={placeDevices.data.size} total={placeDevices.data.total} onChange={setPlacePage}/>
        </> : <Empty title="该位置及子位置暂无可见设备"/>}
      </> : <Empty title="选择左侧位置开始操作">查看设备、编辑资料、新增下级或移动整个位置。</Empty>}</CardContent></Card>
    </div>
    <Sheet open={!!editor} onOpenChange={open => { if (!open) setEditor(null) }}><SheetContent className="review-sheet sm:max-w-xl">{editor && <PlaceEditor key={`${editor.place?.id ?? 'new'}:${editor.parentId ?? ''}`} place={editor.place} parentId={editor.parentId} initialTenantId={editor.tenantId ?? ''} units={units.data ?? []} close={() => setEditor(null)} saved={saved}/>}</SheetContent></Sheet>
    <Sheet open={!!moving} onOpenChange={open => { if (!open && move.edit()) setMoveTarget(null) }}><SheetContent className="review-sheet sm:max-w-2xl">{moving && <PlaceMoveEditor key={moving.id} place={moving}/>}</SheetContent></Sheet>
    <Sheet open={!!transferring && !transferHidden} onOpenChange={open => { if (!open && transfer.phase !== 'PENDING') { if (transfer.edit()) setTransferTarget(null); else setTransferHidden(true) } }}><SheetContent className="review-sheet sm:max-w-2xl">{transferring && <PlaceTransferEditor key={transferring.id} place={transferring} units={units.data ?? []}/>}</SheetContent></Sheet>
    {deviceDetail && <Suspense fallback={<Loading/>}><DeviceDetail deviceId={deviceDetail.id} tenantId={deviceDetail.tenantId} close={() => setDeviceDetail(null)}/></Suspense>}
  </>
}

function PlaceEditor({ place, parentId: initialParent, initialTenantId, units, close, saved }: { place?: PlaceNode; parentId?: string; initialTenantId: string; units: ScopeUnit[]; close: () => void; saved: () => void }) {
  const [tenantId, setTenantId] = useState(initialTenantId)
  const [name, setName] = useState(place?.name ?? ''), [parentId, setParentId] = useState(initialParent ?? place?.parent_id ?? '')
  const [busy, setBusy] = useState(false), [error, setError] = useState<unknown>()
  const places = useResource(`place-editor-options:${tenantId}`, signal => api.places(signal, [tenantId], [], true), !!tenantId)
  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!tenantId || !name.trim() || busy) return
    setBusy(true); setError(undefined)
    try {
      const body = { name: name.trim(), parent_place_id: parentId || null }
      if (place) await api.updatePlace(place.id, { ...body, expected_revision: place.revision }, undefined, tenantId)
      else await api.createPlace(body, undefined, tenantId)
      saved()
    } catch (failure) { setError(failure) } finally { setBusy(false) }
  }
  async function remove() {
    if (!place || busy || !window.confirm(`确认删除空位置“${place.name}”？只允许无设备、无下级位置的空位置；历史与设备不会级联删除。`)) return
    setBusy(true); setError(undefined)
    try { await api.deletePlace(place.id, { expected_revision: place.revision }, undefined, tenantId); saved() }
    catch (failure) { setError(failure) } finally { setBusy(false) }
  }
  return <><SheetHeader className="review-header"><SheetTitle>{place ? '编辑位置' : initialParent ? '新增下级位置' : '新增位置'}</SheetTitle></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}>
    <fieldset className="form-stack" disabled={busy}><div className="field"><Label htmlFor="place-tenant">所属单位</Label><select id="place-tenant" className="select-control" value={tenantId} required disabled={!!place || !!initialParent} onChange={event => { setTenantId(event.target.value); setParentId('') }}><option value="">请选择本次操作单位</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.name}</option>)}{tenantId && !units.some(unit => unit.id === tenantId) && <option value={tenantId}>{place?.tenant_name ?? tenantId}</option>}</select></div>
    <div className="field"><Label htmlFor="place-name">位置名称</Label><Input id="place-name" value={name} onChange={event => setName(event.target.value)} maxLength={200} required/></div>
    <div className="field"><Label htmlFor="place-parent">上级位置</Label>{places.loading ? <Loading/> : places.error != null ? <ErrorNotice error={places.error} retry={places.refresh}/> : <TreeSelect id="place-parent" aria-label="上级位置" style={{ width: '100%' }} showSearch treeNodeFilterProp="title" treeDefaultExpandAll allowClear value={parentId || undefined} placeholder="一级位置（无上级）" disabled={!tenantId || !!place} treeData={placeTreeOptions(places.data ?? [])} onChange={value => setParentId(value ?? '')}/>}<p className="muted-note">{place ? '调整上级请使用“移动整个位置”，先核对全部子位置与设备。' : '所属单位和父位置明确保存；不会伪造位置或自动升为一级。'}</p></div>
    </fieldset>
    {error != null && <ErrorNotice error={error}/>}<div className="version-actions">{place && <Button type="button" variant="destructive" disabled={busy || places.loading || !!places.error || place.device_count > 0 || places.data?.some(item => item.parent_id === place.id)} onClick={remove}>删除空位置</Button>}<Button type="submit" disabled={busy || !tenantId || places.loading || !!places.error}>{busy ? '保存中…' : '保存位置'}</Button><Button type="button" variant="ghost" disabled={busy} onClick={close}>取消</Button></div>
  </form></div></>
}

