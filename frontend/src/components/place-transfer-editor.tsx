import { useEffect, useRef, useState, type FormEvent } from 'react'
import { TreeSelect } from 'antd'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { previewPlaceTransfer, usePlaceTransfer } from '@/lib/place-transfer'
import { placeTreeOptions } from '@/lib/place-tree'
import type { PlaceNode, ScopeUnit } from '@/lib/types'
import { useSession } from '@/lib/store'
import { ErrorNotice, Loading } from './common'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'

export function PlaceTransferEditor({ place, units }: { place: PlaceNode; units: ScopeUnit[] }) {
  const command = usePlaceTransfer(), intent = command.intent
  const [tenant, setTenant] = useState(intent?.preview.target_tenant_id ?? '')
  const [parent, setParent] = useState(intent?.preview.target_parent_id ?? '')
  const [reason, setReason] = useState(intent?.reason ?? ''), [confirm, setConfirm] = useState(false)
  const [error, setError] = useState<unknown>(), [busy, setBusy] = useState(false)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  const targets = useResource(`place-transfer-targets:${tenant}`, signal => api.places(signal, [tenant], [], true), !!tenant && !intent)
  async function submit(event: FormEvent) {
    event.preventDefault(); setError(undefined)
    if (intent) { await command.submit(); return }
    if (!tenant || !reason.trim() || targets.loading || targets.error || busy) return
    const pending = new AbortController(), generation = useSession.getState().generation
    controller.current = pending; setBusy(true)
    try {
      const preview = await previewPlaceTransfer(place, tenant, parent, pending.signal)
      if (!pending.signal.aborted && useSession.getState().generation === generation) {
        if (preview.bound_source_count > 0) { setError(new Error('存在固定单位来源绑定。请先在来源管理明确解绑、准备目标来源，再重新预览。')); return }
        if (preview.active_fire_count > 0 && !confirm) { setError(new Error(`仍有 ${preview.active_fire_count} 条未恢复火警，请先核实现场，并明确勾选原单位继续处置。`)); return }
        command.prepare(place, preview, reason, confirm)
      }
    } catch (failure) { if (!pending.signal.aborted && useSession.getState().generation === generation) setError(failure) }
    finally { if (!pending.signal.aborted && useSession.getState().generation === generation) setBusy(false) }
  }
  return <><SheetHeader className="review-header"><SheetTitle>跨单位移交 · {place.name}</SheetTitle><SheetDescription>服务端展开全部下级位置和在用设备，整批成功或整批回滚。</SheetDescription></SheetHeader>
    <div className="review-body"><form className="form-stack" onSubmit={submit}>{!intent ? <>
      <div className="notice">原单位的位置、资产、报警及历史保留归档；目标单位创建独立资产。不会迁移用户权限、工单或云账号，也不会代替现场搬迁和火警处置。须具备两单位完整设备范围。</div>
      <div className="field"><Label htmlFor="place-transfer-unit">目标单位</Label><select id="place-transfer-unit" className="select-control" value={tenant} disabled={busy} required onChange={event => { setTenant(event.target.value); setParent('') }}><option value="">请选择实际目标单位</option>{units.filter(unit => unit.id !== place.tenant_id).map(unit => <option key={unit.id} value={unit.id}>{unit.name}</option>)}</select></div>
      <div className="field"><Label htmlFor="place-transfer-parent">目标上级位置</Label>{targets.loading ? <Loading/> : targets.error ? <ErrorNotice error={targets.error} retry={targets.refresh}/> : <TreeSelect id="place-transfer-parent" aria-label="移交目标上级位置" style={{ width: '100%' }} showSearch treeNodeFilterProp="title" treeDefaultExpandAll allowClear disabled={!tenant || busy} value={parent || undefined} placeholder="目标单位一级位置（无上级）" treeData={placeTreeOptions(targets.data ?? [])} onChange={value => setParent(value ?? '')}/>}</div>
      <div className="field"><Label htmlFor="place-transfer-reason">移交原因</Label><Input id="place-transfer-reason" value={reason} onChange={event => setReason(event.target.value)} maxLength={1000} disabled={busy} required/></div>
      <label className="checkbox-field"><input type="checkbox" checked={confirm} disabled={busy} onChange={event => setConfirm(event.target.checked)}/>如有未恢复火警，已核实现场，确认仍由原单位继续处置；不会因移交自动恢复。</label>
      {error != null && <ErrorNotice error={error}/>}<Button type="submit" disabled={!tenant || !reason.trim() || busy || targets.loading || !!targets.error}>{busy ? '核对整批范围…' : '预览全部移交范围'}</Button>
    </> : <>
      <section className="confirmation-summary"><h3>核对跨单位整体移交</h3><dl className="detail-grid"><div><dt>来源</dt><dd>{intent.place.tenant_name} · {intent.place.name}</dd></div><div><dt>目标单位</dt><dd>{units.find(unit => unit.id === intent.preview.target_tenant_id)?.name ?? intent.preview.target_tenant_id}</dd></div><div><dt>全部位置</dt><dd>{intent.preview.places_count} 个</dd></div><div><dt>全部设备</dt><dd>{intent.preview.devices_count} 台</dd></div><div><dt>未恢复火警</dt><dd>{intent.preview.active_fire_count} 条（原单位保留）</dd></div><div><dt>原因</dt><dd>{intent.reason}</dd></div></dl></section>
      <ul className="muted-note">{intent.preview.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
      {command.phase === 'UNCERTAIN' && <div className="notice" role="status">结果未确认，不表示失败。原目标、范围和幂等键仍保留；请沿原操作确认，禁止重新创建移交。可以收起后返回继续。</div>}
      {command.phase === 'CONFLICT' ? <div className="notice">范围或版本变化，请重新预览。<Button type="button" variant="outline" onClick={() => { if (command.reload()) targets.refresh() }}>重新读取范围</Button></div> : null}
      {command.error != null && <ErrorNotice error={command.error}/>}<div className="form-actions"><Button type="button" variant="outline" disabled={command.phase !== 'CONFIRMING'} onClick={() => command.edit()}>返回修改</Button><Button type="submit" disabled={command.phase === 'PENDING' || command.phase === 'CONFLICT'}>{command.phase === 'PENDING' ? '正在整批移交…' : command.phase === 'UNCERTAIN' ? '沿原操作确认结果' : '确认整批移交'}</Button></div>
    </>}</form></div></>
}
