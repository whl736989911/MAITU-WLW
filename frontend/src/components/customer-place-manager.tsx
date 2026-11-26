import { useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Card, Descriptions } from 'antd'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { customerAssetsApi, customerPlaceCreateMutation, customerPlaceDeleteMutation, customerPlaceUpdateMutation } from '@/lib/customer-assets'
import { placeBranchIds, placePath } from '@/lib/place-tree'
import { previewPlaceMove, usePlaceMove } from '@/lib/place-move'
import type { PlaceNode } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { ErrorNotice } from './common'
import { CustomerWriteGate } from './customer-asset-write'
import { DataTransferActions } from './data-transfer-actions'
import { PageHelp, RowActions } from './page-tools'
import { CustomerRecordPager, CustomerRecordsSearch, CustomerRecordTable } from './customer-records'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet'

type Editor =
  | { mode: 'create' }
  | { mode: 'edit'; place: PlaceNode }
  | { mode: 'delete'; place: PlaceNode }
  | { mode: 'move'; place: PlaceNode }
  | null

/**
 * Own-unit position maintenance for delegated customer unit administrators. All names and paths come
 * from the real server tree; parent selection never asks for a raw UUID. Subtree moves are previewed
 * with the server-expanded place/device counts and confirmed explicitly; deletes only succeed for empty
 * positions (the server re-checks children, devices and revision).
 */
export function CustomerPlaceManager() {
  const { permissions, user } = useSession()
  const canRead = permissions.includes('units:read'), canWrite = permissions.includes('units:write')
  const [editor, setEditor] = useState<Editor>(null)
  const [notice, setNotice] = useState('')
  const [draft, setDraft] = useState(''), [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const places = useResource(`customer-places:${user?.tenant_id ?? 'none'}:${user?.tenant_id ?? ''}`, signal => customerAssetsApi.places(signal), canRead)
  const rows = places.data ?? []
  const byId = useMemo(() => new Map(rows.map(place => [place.id, place])), [rows])
  const visible = useMemo(() => {
    const needle = keyword.trim().toLowerCase()
    if (!needle) return rows
    return rows.filter(place => placePath(place, rows).toLowerCase().includes(needle))
  }, [rows, keyword])
  const childCount = (id: string) => rows.filter(place => place.parent_id === id).length
  function refresh() { setNotice(''); places.refresh() }
  function saved(message: string) { setEditor(null); setNotice(message); places.refresh(); useSession.getState().refreshScopeTree() }
  if (!canRead) return <Card><Alert type="warning" showIcon title="当前账号未获本单位位置读取权限"/></Card>
  return <section className="unit-settings-grid">
    <div className="identity-row-actions"><PageHelp title="位置树与操作说明"><p>位置名称、上级和路径均来自本单位服务端位置树，不需要手工输入位置 ID。搜索保留匹配项的上级路径，不将子位置冒充为顶级位置。</p><p>整枝移动会先由服务端展开全部子位置和设备，并显示实际影响数量。删除仅允许空位置，服务端会再次检查；不会删除设备、报警或历史。跨单位移交不在此开放。</p></PageHelp></div>
    {notice && <Alert type="success" showIcon title={notice}/>}
    <CustomerRecordsSearch field="名称或路径" searchLabel="搜索位置名称或完整路径" keyword={draft} onKeyword={setDraft}
      onSearch={event => { event.preventDefault(); setKeyword(draft.trim()); setPage(1) }}
      onReset={() => { setDraft(''); setKeyword(''); setPage(1) }}
      actions={<>
        {canWrite && <button type="button" className="legacy-device-button" onClick={() => setEditor({ mode: 'create' })}>添加位置</button>}
        <DataTransferActions kind="PLACES" tenantId={user?.tenant_id ?? undefined} filters={keyword ? { place_ids: visible.map(place => place.id).join(',') } : undefined}
          onCompleted={result => { if ('plan_id' in result) refresh() }}/>
      </>}/>
    <CustomerRecordTable name="位置管理" columns={['名称', '所属位置', '设备数', '操作']}
      loading={places.loading} error={places.error} retry={refresh} rows={visible.slice((page - 1) * 10, page * 10).map(place => {
        const path = placePath(place, rows), children = childCount(place.id)
        return { key: place.id, cells: [place.name, place.parent_id ? byId.get(place.parent_id)?.name : '顶级位置', place.device_count,
          canWrite && <RowActions label={`位置操作 · ${path}`} text="操作">
            <button type="button" className="legacy-device-link" onClick={() => setEditor({ mode: 'edit', place })}>编辑</button>
            <button type="button" className="legacy-device-link" onClick={() => setEditor({ mode: 'move', place })}>移动整枝</button>
            <button type="button" className="legacy-device-link" disabled={place.device_count > 0 || children > 0} onClick={() => setEditor({ mode: 'delete', place })}>删除</button>
          </RowActions>,
        ] }
      })}/>
    {places.data && <CustomerRecordPager page={page} size={10} total={visible.length} onPage={setPage}/>}
    <Sheet open={editor !== null} onOpenChange={open => { if (!open) setEditor(null) }}><SheetContent className="review-sheet sm:max-w-2xl">
      {editor && editor.mode !== 'move' && <PlaceMetadataEditor key={`${editor.mode}:${editor.mode === 'create' ? 'new' : editor.place.id}`} editor={editor} places={rows} close={() => setEditor(null)} saved={saved}/>}
      {editor?.mode === 'move' && <PlaceSubtreeMover key={editor.place.id} place={editor.place} places={rows} byId={byId} close={() => setEditor(null)} saved={saved}/>}
    </SheetContent></Sheet>
  </section>
}

function parentOptions(places: PlaceNode[], exceptId?: string) {
  const excluded = exceptId ? placeBranchIds(places, exceptId) : new Set<string>()
  return places.filter(place => !excluded.has(place.id)).map(place => ({ id: place.id, label: `${placePath(place, places)} · rev ${place.revision}`, level: place.level }))
}

function PlaceMetadataEditor({ editor, places, close, saved }: { editor: Exclude<Editor, null | { mode: 'move' }>; places: PlaceNode[]; close: () => void; saved: (message: string) => void }) {
  const place = editor.mode === 'create' ? null : editor.place
  const [name, setName] = useState(place?.name ?? ''), [parentId, setParentId] = useState(place?.parent_id ?? '')
  const options = parentOptions(places, place?.id)
  if (editor.mode === 'delete') return <><SheetHeader className="review-header"><SheetTitle>删除位置 · 影响预览</SheetTitle></SheetHeader><div className="review-body">
    <Descriptions column={1} size="small" items={[
      { key: 'path', label: '完整路径', children: placePath(editor.place, places) },
      { key: 'devices', label: '在用设备', children: `${editor.place.device_count} 台` },
      { key: 'children', label: '下级位置', children: `${places.filter(item => item.parent_id === editor.place.id).length} 个` },
      { key: 'revision', label: '当前版本', children: editor.place.revision },
    ]}/>
    <Alert type="warning" showIcon title="删除是本地归档空位置，不移除、停用或删除任何设备，也不删除历史记录。" description="服务端会再次检查子位置与在用设备；非空位置将拒绝删除。" style={{ marginBlock: 12 }}/>
    <CustomerWriteGate store={customerPlaceDeleteMutation} title="确认删除空位置" submitLabel="确认删除" warning="删除只影响当前空位置；不会删除设备、报警、历史或云端资源。" summary={<><p>{placePath(editor.place, places)}</p><p>expected_revision：{editor.place.revision}</p></>} buildRequest={() => ({ path: `/places/${encodeURIComponent(editor.place.id)}`, method: 'DELETE', body: { expected_revision: editor.place.revision }, display: `删除空位置 ${placePath(editor.place, places)}` })} onSaved={() => saved('位置已归档；设备与历史未改变。')}><div/></CustomerWriteGate>
    <Button type="button" variant="ghost" onClick={close}>取消</Button>
  </div></>
  return <><SheetHeader className="review-header"><SheetTitle>{editor.mode === 'create' ? '新建本单位位置' : '编辑本单位位置'}</SheetTitle></SheetHeader><div className="review-body">
    {editor.mode === 'create' ? <CustomerWriteGate store={customerPlaceCreateMutation} title="新建位置" submitLabel="确认新建" warning="位置创建只写入本单位真实位置树；不会自动创建或移动设备。" summary={<><p>名称：{name.trim() || '未填写'}</p><p>上级：{parentId ? options.find(item => item.id === parentId)?.label : '顶级位置'}</p></>} buildRequest={() => {
      if (!name.trim()) throw new Error('请填写位置名称')
      return { path: '/places', method: 'POST', body: { name: name.trim(), parent_place_id: parentId || null }, display: `新建位置 ${name.trim()}` }
    }} onSaved={() => saved('位置已新建；可在设备或位置设置中继续使用。')}>
      <div className="field"><Label htmlFor="customer-place-name">位置名称</Label><Input id="customer-place-name" required maxLength={200} value={name} onChange={event => setName(event.target.value)}/></div>
      <div className="field"><Label htmlFor="customer-place-parent">上级位置</Label><select id="customer-place-parent" className="native-select" value={parentId} onChange={event => setParentId(event.target.value)}><option value="">顶级位置</option>{options.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select><p className="muted-note">按完整路径选择，不需要输入位置ID。</p></div>
    </CustomerWriteGate> : <CustomerWriteGate store={customerPlaceUpdateMutation} title="编辑位置" submitLabel="确认保存" warning="只修改名称和上级关系；本位置下的设备与历史不会被删除或改写。" summary={<><p>当前：{placePath(editor.place, places)}</p><p>名称：{name.trim() || '未填写'} · 上级：{parentId ? options.find(item => item.id === parentId)?.label : '顶级位置'}</p><p>expected_revision：{editor.place.revision}</p></>} buildRequest={() => {
      if (!name.trim()) throw new Error('请填写位置名称')
      return { path: `/places/${encodeURIComponent(editor.place.id)}`, method: 'PUT', body: { name: name.trim(), parent_place_id: parentId || null, expected_revision: editor.place.revision }, display: `编辑位置 ${editor.place.name}` }
    }} onSaved={() => saved('位置已保存；服务端 revision 已更新。')}>
      <div className="field"><Label htmlFor="customer-place-edit-name">位置名称</Label><Input id="customer-place-edit-name" required maxLength={200} value={name} onChange={event => setName(event.target.value)}/></div>
      <div className="field"><Label htmlFor="customer-place-edit-parent">上级位置</Label><select id="customer-place-edit-parent" className="native-select" value={parentId} onChange={event => setParentId(event.target.value)}><option value="">顶级位置</option>{options.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select></div>
    </CustomerWriteGate>}
    <Button type="button" variant="ghost" onClick={close}>取消</Button>
  </div></>
}

function PlaceSubtreeMover({ place, places, byId, close, saved }: { place: PlaceNode; places: PlaceNode[]; byId: Map<string, PlaceNode>; close: () => void; saved: (message: string) => void }) {
  const move = usePlaceMove()
  const [targetId, setTargetId] = useState(''), [reason, setReason] = useState(''), [preview, setPreview] = useState<{ places_count: number; devices_count: number; snapshot: string } | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState<unknown>()
  const options = parentOptions(places, place.id).filter(option => option.id !== place.id)
  const current = move.intent?.preview.place.id === place.id ? move.intent : null
  const handled = useRef<number | null>(null)
  useEffect(() => {
    if (!move.completion || move.completion.result.place?.id !== place.id || handled.current === move.completion.serial) return
    handled.current = move.completion.serial
    saved('位置整枝移动完成；服务端已保留位置与设备归属历史。')
    move.dismissCompletion()
  }, [move.completion, place.id, saved])
  async function loadPreview() {
    setPreview(null); setError(undefined); setBusy(true)
    try {
      const result = await previewPlaceMove(place, targetId)
      setPreview({ places_count: result.places_count, devices_count: result.devices_count, snapshot: result.snapshot })
      move.prepare(result, reason)
    } catch (failure) { setError(failure) } finally { setBusy(false) }
  }
  const impact = current?.preview ?? preview
  const targetParent = current ? current.preview.target_parent : byId.get(targetId)
  const targetLabel = targetParent ? placePath(targetParent, places) : !current && targetId ? targetId : '顶级位置'
  return <><SheetHeader className="review-header"><SheetTitle>移动整个位置 · 影响预览</SheetTitle></SheetHeader><div className="review-body">
    <Descriptions column={1} size="small" items={[
      { key: 'source', label: '移动范围', children: placePath(place, places) },
      { key: 'target', label: '目标上级', children: targetLabel },
      { key: 'impact', label: '服务端影响预览', children: impact ? `本枝 ${impact.places_count} 个位置，${impact.devices_count} 台在用设备` : '尚未预览' },
      { key: 'revision', label: '当前版本', children: place.revision },
    ]}/>
    {!current && !move.intent && <>
      <div className="field"><Label htmlFor="customer-move-target">目标上级位置</Label><select id="customer-move-target" className="native-select" value={targetId} onChange={event => { setTargetId(event.target.value); setPreview(null) }}><option value="">顶级位置</option>{options.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select></div>
      <div className="field"><Label htmlFor="customer-move-reason">移动原因</Label><Input id="customer-move-reason" maxLength={1000} value={reason} onChange={event => setReason(event.target.value)}/></div>
      {error != null && <ErrorNotice error={error}/>}
      <div className="identity-form-actions"><Button type="button" disabled={busy || !reason.trim()} onClick={() => void loadPreview()}>{busy ? '读取影响范围…' : '核对影响范围'}</Button><Button type="button" variant="ghost" onClick={close}>取消</Button></div>
    </>}
    {current && <form className="form-stack" onSubmit={event => { event.preventDefault(); void move.submit() }}>
      <Alert type="warning" showIcon title="整枝移动会同时改变全部下级位置与设备的本单位归属路径" description="服务端已展开全部下级位置和设备；不会只操作当前页，也不会跨单位移交。移动后历史保留。"/>
      <Descriptions column={1} size="small" items={[{ key: 'count', label: '位置 / 设备', children: `${current.preview.places_count} / ${current.preview.devices_count}` }, { key: 'reason', label: '原因', children: current.reason }, { key: 'target', label: '目标', children: targetLabel }]}/>
      {move.error != null && <ErrorNotice error={move.error}/>}
      <div className="identity-form-actions"><Button type="submit" disabled={move.phase === 'PENDING' || move.phase === 'CONFLICT'}>{move.phase === 'PENDING' ? '移动中…' : '确认移动整枝'}</Button><Button type="button" variant="ghost" onClick={() => { move.edit(); close() }}>取消</Button></div>
      {move.phase === 'UNCERTAIN' && <p className="notice">结果未确认：保留原幂等键与内容，沿原操作确认；不会重复新建操作。</p>}
      {move.phase === 'CONFLICT' && <Button type="button" variant="outline" onClick={() => move.reloadConflict()}>重新读取并放弃旧预览</Button>}
    </form>}
    {!current && move.intent && <Alert type="info" showIcon title="存在未完成的位置移动意图" description="请刷新后重新预览；不会用新内容覆盖原请求。" action={<Button type="button" variant="outline" onClick={() => { move.edit(); close() }}>放弃本地意图</Button>}/>}
    {!current && <Button type="button" variant="ghost" onClick={close}>取消</Button>}
  </div></>
}
