import { useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { ApiError } from '@/lib/api'
import { useWorkOrderIntent } from '@/lib/work-order-intent'
import { WORK_ORDER_PRIORITIES } from '@/lib/maintenance'
import type { WorkOrderDetail } from '@/lib/maintenance'
import { ErrorNotice } from '@/components/common'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/**
 * Reusable fault-to-work-order action. It creates a local work order that references the existing fault;
 * the source fault/device/recovery state is never rewritten. The mutation intent/key survives SPA navigation
 * in memory (never a password, never browser reload) so an unknown write cannot silently get a new key.
 */
export function ConvertFaultToWorkOrder({ faultId, deviceId, tenantId, defaultTitle, onCreated }: {
  faultId: string; deviceId: string; tenantId?: string; defaultTitle?: string
  onCreated?: (detail: WorkOrderDetail) => void
}) {
  const session = useSession()
  const intent = useWorkOrderIntent()
  const [open, setOpen] = useState(false)
  const [title, setTitle] = useState(defaultTitle ?? '')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('')
  const [due, setDue] = useState('')
  const [reason, setReason] = useState('故障转维修工单')
  const [error, setError] = useState<unknown>()

  if (!session.permissions.includes('maintenance:write') || !session.token || session.user?.must_change_password) return null
  const management = session.audience === 'management'
  const targetTenant = management ? tenantId : undefined
  const frozen = intent.intent && intent.intent.body.fault_event_id === faultId && intent.intent.body.device_id === deviceId ? intent.intent : null
  const completion = intent.completion
  const completed = completion && completion.path === '/maintenance/work-orders' && completion.result.order.fault_event_id === faultId && completion.result.order.device_id === deviceId ? completion.result : null
  const otherIntent = intent.intent && !frozen
  const busy = intent.phase === 'PENDING' && !!frozen
  const showFrozen = !!frozen && (intent.phase === 'PENDING' || intent.phase === 'UNCERTAIN' || intent.phase === 'CONFLICT' || (intent.phase === 'CONFIRMING' && !open))

  function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || showFrozen || otherIntent) return
    if (management && !targetTenant) { setError(new ApiError(400, 'TENANT_REQUIRED', '故障或设备记录未明确所属单位，无法创建工单。')); return }
    const body: Record<string, unknown> = { fault_event_id: faultId, device_id: deviceId, title, description, priority: priority || null,
      due_at: due ? new Date(due).toISOString() : null, reason }
    const request = {
      path: '/maintenance/work-orders', method: 'POST' as const, tenant: targetTenant, body,
      display: `故障转维修工单 · ${title.trim() || '未命名'} · 设备 ${deviceId}`,
    }
    setError(undefined)
    void intent.perform(request).then(() => {
      const state = useWorkOrderIntent.getState()
      if (state.completion && state.completion.path === '/maintenance/work-orders' && state.completion.result.order.fault_event_id === faultId && state.completion.result.order.device_id === deviceId) onCreated?.(state.completion.result)
    })
  }
  function retry() {
    void intent.retry().then(() => {
      const state = useWorkOrderIntent.getState()
      if (state.completion && state.completion.path === '/maintenance/work-orders' && state.completion.result.order.fault_event_id === faultId && state.completion.result.order.device_id === deviceId) onCreated?.(state.completion.result)
    })
  }

  return <section aria-label="故障转维修工单" style={{ minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' }}>
    {completed ? <p role="status">已创建本地维修工单 {completed.order.id}（状态 {completed.order.status}）。源故障与设备状态未改变；请刷新工单队列查看。</p>
      : frozen ? <section className="notice" role="alert" style={{ marginTop: 8 }}>
        <p><strong>{intent.phase === 'PENDING' ? '正在提交，结果未知' : intent.phase === 'UNCERTAIN' ? '结果未确认（不是失败）' : intent.phase === 'CONFLICT' ? '提交冲突' : '上次提交未成功'}</strong></p>
        <p>{frozen.display}</p>
        <p className="muted-note">冻结请求与同一幂等键仅保留在当前页面会话内存；离开后再返回仍可重试，浏览器刷新会丢失。</p>
        {intent.error != null && <ErrorNotice error={intent.error}/>}
        {intent.phase === 'UNCERTAIN' && <div className="form-stack"><div className="version-actions"><Button variant="outline" onClick={retry}>沿原操作确认结果</Button><Button variant="outline" onClick={() => useSession.getState().navigate('maintenance-work-orders')}>前往维保工单刷新核对</Button></div></div>}
        {intent.phase === 'CONFLICT' && <div className="version-actions"><Button variant="outline" onClick={() => { void intent.reload(); useSession.getState().navigate('maintenance-work-orders') }}>前往维保工单重新读取</Button></div>}
        {intent.phase === 'CONFIRMING' && <div className="form-stack"><div className="version-actions"><Button variant="outline" onClick={retry}>沿原请求重试</Button><Button variant="outline" onClick={() => { void intent.edit(); setOpen(true) }}>放弃旧请求并重新填写</Button></div></div>}
        {intent.phase === 'PENDING' && <p className="muted-note">正在等待服务端结果；不要创建另一意图。</p>}
      </section>
      : <>
        {otherIntent && <div className="notice" role="alert"><p>另一笔未确认/待处理的写入仍在内存中；请先到维保工单页面刷新核对，再发起本次转换。</p><Button variant="outline" size="sm" onClick={() => useSession.getState().navigate('maintenance-work-orders')}>前往维保工单处理</Button></div>}
        {!open && <Button variant="outline" onClick={() => { setOpen(true); setError(undefined) }} disabled={!!otherIntent}>转为维修工单</Button>}
        {management && !targetTenant && <p className="muted-note">当前故障或设备记录未明确原单位归属，无法确定工单目标。</p>}
        {open && <form className="form-stack" onSubmit={submit} style={{ marginTop: 12 }}>
          <p className="muted-note">仅创建本地工单并引用该故障；不会改写故障、恢复或设备物理状态，也不会发送通知或执行厂商指令。</p>
          <div className="field"><Label htmlFor="fault-wo-title">工单标题</Label><Input id="fault-wo-title" value={title} maxLength={200} onChange={event => setTitle(event.target.value)} required/></div>
          <div className="field"><Label htmlFor="fault-wo-description">工单说明</Label><textarea id="fault-wo-description" className="native-select" rows={3} maxLength={2000} value={description} onChange={event => setDescription(event.target.value)}/></div>
          <div className="field"><Label htmlFor="fault-wo-priority">优先级（可空）</Label><select id="fault-wo-priority" className="native-select" value={priority} onChange={event => setPriority(event.target.value)}><option value="">不指定</option>{WORK_ORDER_PRIORITIES.map(value => <option key={value} value={value}>{value}</option>)}</select></div>
          <div className="field"><Label htmlFor="fault-wo-due">计划完成时间（可空）</Label><Input id="fault-wo-due" type="datetime-local" value={due} onChange={event => setDue(event.target.value)}/></div>
          <div className="field"><Label htmlFor="fault-wo-reason">变更原因</Label><Input id="fault-wo-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
          <p className="muted-note">设备ID：{deviceId} · 故障事件ID：{faultId}</p>
          {error != null && <ErrorNotice error={error}/>}
          {intent.error != null && intent.phase === 'CONFIRMING' && <ErrorNotice error={intent.error}/>}
          <div className="version-actions"><Button type="submit" disabled={busy || !!otherIntent}>{busy ? '正在提交…' : '确认创建工单'}</Button><Button type="button" variant="outline" onClick={() => { setOpen(false) }} disabled={busy}>关闭</Button></div>
        </form>}
      </>}
  </section>
}
