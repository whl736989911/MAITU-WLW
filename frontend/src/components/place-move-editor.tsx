import { useEffect, useRef, useState, type FormEvent } from 'react'
import { TreeSelect } from 'antd'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { previewPlaceMove, usePlaceMove } from '@/lib/place-move'
import { placeBranchIds, placeTreeOptions } from '@/lib/place-tree'
import type { PlaceNode } from '@/lib/types'
import { useSession } from '@/lib/store'
import { ErrorNotice, Loading } from './common'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'

export function PlaceMoveEditor({ place }: { place: PlaceNode }) {
  const command = usePlaceMove()
  const intent = command.intent
  const [parentId, setParentId] = useState(intent?.preview.target_parent?.id ?? place.parent_id ?? '')
  const [reason, setReason] = useState(intent?.reason ?? '')
  const [validation, setValidation] = useState<unknown>()
  const [previewing, setPreviewing] = useState(false)
  const previewRequest = useRef<AbortController | null>(null)
  useEffect(() => () => previewRequest.current?.abort(), [])
  const targets = useResource(`place-move-targets:${place.tenant_id}`, signal => api.places(signal, [place.tenant_id], [], true), !intent)
  const candidates = targets.data ?? []
  const options = placeTreeOptions(candidates, placeBranchIds(candidates, place.id))
  async function submit(event: FormEvent) {
    event.preventDefault()
    setValidation(undefined)
    if (intent) { await command.submit(); return }
    if (!reason.trim()) { setValidation(new Error('请填写移动原因')); return }
    if (targets.loading || targets.error || previewing) return
    const controller = new AbortController(), generation = useSession.getState().generation
    previewRequest.current = controller
    setPreviewing(true)
    try {
      const preview = await previewPlaceMove(place, parentId, controller.signal)
      if (!controller.signal.aborted && useSession.getState().generation === generation) command.prepare(preview, reason)
    } catch (error) { if (!controller.signal.aborted && useSession.getState().generation === generation) setValidation(error) }
    finally { if (!controller.signal.aborted && useSession.getState().generation === generation) setPreviewing(false) }
  }
  return <><SheetHeader className="review-header"><SheetTitle>移动整个位置 · {place.name}</SheetTitle><SheetDescription>{place.tenant_name} · 包含全部子位置和关联设备，不只当前页。</SheetDescription></SheetHeader>
    <div className="review-body"><form className="form-stack" onSubmit={submit}>
      {!intent ? <>
        <div className="notice">这是同单位的位置层级调整。设备身份、挂载位置及历史记录保留；不会移动设备物理坐标、执行云端控制或自动改变用户授权。跨单位不能通过此操作修改上级。</div>
        <div className="field"><Label htmlFor="move-place-parent">目标上级位置</Label>{targets.loading ? <Loading/> : targets.error ? <ErrorNotice error={targets.error} retry={targets.refresh}/> : <TreeSelect id="move-place-parent" aria-label="目标上级位置" style={{ width: '100%' }} showSearch treeNodeFilterProp="title" treeDefaultExpandAll allowClear disabled={previewing} placeholder="一级位置（无上级）" value={parentId || undefined} treeData={options} onChange={value => setParentId(value ?? '')}/>}<p className="muted-note">清空表示移动为本单位一级位置；自身和全部后代不可选。</p></div>
        <div className="field"><Label htmlFor="move-place-reason">移动原因</Label><Input id="move-place-reason" value={reason} onChange={event => setReason(event.target.value)} disabled={previewing} maxLength={1000} required/></div>
        {validation != null && <ErrorNotice error={validation}/>}<Button type="submit" disabled={previewing || targets.loading || !!targets.error}>{previewing ? '读取完整影响范围…' : '预览全部影响'}</Button>
      </> : <>
        <section className="confirmation-summary"><h3>核对整体移动</h3><dl className="detail-grid"><div><dt>单位</dt><dd>{intent.preview.place.tenant_name}</dd></div><div><dt>位置</dt><dd>{intent.preview.place.name}</dd></div><div><dt>目标上级</dt><dd>{intent.preview.target_parent?.name ?? '本单位一级位置'}</dd></div><div><dt>包含位置</dt><dd>{intent.preview.places_count} 个（含本位置及全部后代）</dd></div><div><dt>包含设备</dt><dd>{intent.preview.devices_count} 台（服务端完整范围）</dd></div><div><dt>原因</dt><dd>{intent.reason}</dd></div></dl></section>
        <p className="muted-note">提交时再次校验范围和版本；期间新增、移动或修改对象将要求重新预览，不会只搬一部分。</p>
        {command.phase === 'UNCERTAIN' && <div role="status" className="notice">结果未确认，不表示失败。保留了原目标、范围和幂等键，请沿原操作确认；不要新建重复移动。离开本页后可返回继续。</div>}
        {command.phase === 'PENDING' && <div role="status" className="notice">正在提交整体移动，不能修改目标或重复提交。</div>}
        {command.phase === 'CONFLICT' ? <div className="notice">位置或设备范围已变化，请重新读取后核对。<Button type="button" variant="outline" onClick={() => { if (command.reloadConflict()) targets.refresh() }}>重新读取影响范围</Button></div> : null}
        {command.error != null && <ErrorNotice error={command.error}/>}<div className="form-actions"><Button type="button" variant="outline" disabled={command.phase !== 'CONFIRMING'} onClick={() => command.edit()}>返回修改</Button><Button type="submit" disabled={command.phase === 'PENDING' || command.phase === 'CONFLICT'}>{command.phase === 'PENDING' ? '移动中…' : command.phase === 'UNCERTAIN' ? '沿原操作确认结果' : '确认整体移动'}</Button></div>
      </>}
    </form></div></>
}
