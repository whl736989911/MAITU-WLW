import { useEffect, useRef, useState, type FormEvent } from 'react'
import { CheckCheck, CircleAlert, Clock3, MapPin, Radio } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import { conclusions, reported, statusLabel } from '@/lib/format'
import { alarmTimeText } from '@/lib/alarm-time'
import type { Alarm, Conclusion } from '@/lib/types'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Button } from './ui/button'
import { Label } from './ui/label'
import { ErrorNotice, Status } from './common'

export function AlarmReview({ alarm, onClose, onReviewed }: { alarm: Alarm | null; onClose: () => void; onReviewed: () => void }) {
  const generation = useSession(s => s.generation)
  return <Sheet open={!!alarm} onOpenChange={open => { if (!open) onClose() }}><SheetContent className="review-sheet sm:max-w-xl">{alarm && <AlarmReviewBody key={`${generation}:${alarm.id}:${alarm.revision}`} alarm={alarm} onClose={onClose} onReviewed={onReviewed}/>}</SheetContent></Sheet>
}
export function AlarmReviewBody({ alarm, onClose, onReviewed, allowHandled = false }: { alarm: Alarm; onClose: () => void; onReviewed: () => void; allowHandled?: boolean }) {
  const canReview = useSession(s => s.permissions.includes('alarm:review'))
  const [conclusion, setConclusion] = useState<Conclusion | ''>('')
  const [remark, setRemark] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const attempt = useRef<{ payload: string; key: string } | null>(null)
  const pending = useRef<AbortController | null>(null), alive = useRef(true)
  const generation = useSession(s => s.generation)
  useEffect(() => { alive.current = true; return () => { alive.current = false; pending.current?.abort() } }, [generation])
  const conflict = error instanceof ApiError && error.status === 409
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!canReview || conflict || pending.current) return
    setError(undefined)
    if (!conclusions.some(([value]) => value === conclusion)) { setError(new Error('请选择现场核查结论')); return }
    if (!Number.isSafeInteger(alarm.revision) || alarm.revision < 0) { setError(new Error('当前报警revision未确认，请刷新后重开')); return }
    const scope = useSession.getState().generation, controller = new AbortController(); pending.current = controller
    setBusy(true)
    const payload = JSON.stringify({ conclusion, remark, revision: alarm.revision })
    if (!attempt.current || attempt.current.payload !== payload) attempt.current = { payload, key: crypto.randomUUID() }
    try { await api.review(alarm, conclusion as Conclusion, remark, attempt.current.key, controller.signal); if (alive.current && !controller.signal.aborted && useSession.getState().generation === scope) { onReviewed(); onClose() } }
    catch (error) { if (alive.current && !controller.signal.aborted && useSession.getState().generation === scope) setError(error) }
    finally { pending.current = null; if (alive.current && !controller.signal.aborted && useSession.getState().generation === scope) setBusy(false) }
  }
  return <><SheetHeader className="review-header"><div className="eyebrow">ALARM DETAIL · 报警详情</div><SheetTitle>核查报警，留存处置记录</SheetTitle><SheetDescription>人工复核不会更改设备物理状态；设备恢复以服务端事件为准。</SheetDescription></SheetHeader><div className="review-body"><div className="alarm-detail-title"><span className="alarm-symbol"><CircleAlert/></span><div><h2>{reported(alarm.device_name)}</h2><span className="mono">{alarm.id}</span></div></div>
    <div className="detail-states"><div><span>设备物理状态</span><Status value={alarm.physical_state} dimension="physical"/></div><div><span>人工处置状态</span><Status value={alarm.response_state} dimension="response"/></div></div>
    <dl className="detail-list"><div><dt><MapPin/>安装位置</dt><dd>{reported(alarm.address)}</dd></div><div><dt><Clock3/>来源发生时间</dt><dd>{alarmTimeText(alarm)}</dd></div><div><dt><Radio/>上报事件</dt><dd>{statusLabel(alarm.event_type)}</dd></div><div><dt><CheckCheck/>现有结论</dt><dd>{statusLabel(alarm.conclusion)}</dd></div><div><dt>数据版本</dt><dd>revision {alarm.revision}</dd></div></dl>
    {error != null && <ErrorNotice error={error}/>}
    {conflict && <div className="notice">该记录已更新。为避免覆盖他人处置，请刷新列表后重新打开。<Button variant="outline" onClick={() => { onReviewed(); onClose() }}>刷新最新版本</Button></div>}
    {canReview && (allowHandled || alarm.response_state !== 'HANDLED') ? <form className="review-form form-stack" onSubmit={submit}><div><h3>人工复核</h3><p>依据现场核查结果选择结论，不等同于解除火警。</p></div><div className="field"><Label htmlFor="conclusion">复核结论</Label><select id="conclusion" className="native-select" value={conclusion} onChange={e => setConclusion(e.target.value as Conclusion | '')} required><option value="">请选择现场核查结论</option>{conclusions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div><div className="field"><Label htmlFor="remark">现场情况 / 处置备注</Label><textarea id="remark" value={remark} onChange={e => setRemark(e.target.value)} maxLength={2000} rows={5} placeholder="请填写现场核查情况与处置过程" required/></div><Button type="submit" disabled={busy || conflict}><CheckCheck/>{busy ? '正在提交…' : '提交复核结论'}</Button></form> : <div className="notice">{alarm.response_state === 'HANDLED' ? '该报警已完成人工处置，当前展示服务端记录。' : '当前账户无报警复核权限。'}</div>}
    {allowHandled && <Button type="button" variant="outline" disabled={busy} onClick={onClose}>返回只读详情</Button>}
    <p className="muted-note">处置需服从现场消防预案。如有真实火情，请立即按应急流程处理。</p>
  </div></>
}
