import { useEffect, useId, useState } from 'react'
import { History, ClipboardCheck } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime, reported, statusLabel, conclusions } from '@/lib/format'
import { alarmTimeText } from '@/lib/alarm-time'
import type { Alarm } from '@/lib/types'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Button } from './ui/button'
import { Empty, ErrorNotice, Loading } from './common'
import { AlarmReviewBody } from './alarm-review'
import { AlarmTrace, revisionText, safeRevision, TraceState } from './alarm-trace'
import { AlarmVideo } from './alarm-video'
import { publishObjectChange } from '@/lib/object-events'
export function AlarmDetail({ alarmId, tenantId, onClose, onChanged }: { alarmId: string | null; tenantId?: string; onClose: () => void; onChanged: () => void }) {
  const { user, audience, permissions, generation } = useSession()
  const allowed = !!user && !user.must_change_password && permissions.includes('alarm:read') && (audience === 'management' ? !!tenantId : !!user.tenant_id)
  return <Sheet open={!!alarmId && allowed} onOpenChange={open => { if (!open) onClose() }}><SheetContent className="review-sheet alarm-detail-sheet">{alarmId && allowed && <AlarmInspector key={`${generation}:${alarmId}:${tenantId ?? ''}:${permissions.join('|')}`} alarmId={alarmId} tenantId={tenantId} onChanged={onChanged}/>}</SheetContent></Sheet>
}
function reviewSnapshotValid(alarm: Alarm) { return safeRevision(alarm.revision) && (alarm.physical_state === 'FIRE' || alarm.physical_state === 'NORMAL') && (alarm.response_state === 'UNHANDLED' || alarm.response_state === 'HANDLED') && (alarm.conclusion === null || conclusions.some(([value]) => value === alarm.conclusion)) }
function AlarmInspector({ alarmId, tenantId, onChanged }: { alarmId: string; tenantId?: string; onChanged: () => void }) {
  const permissions = useSession(s => s.permissions)
  const canHistory = permissions.includes('alarm:read') && permissions.includes('alarm:history'), canReview = permissions.includes('alarm:review')
  const [showHistory, setShowHistory] = useState(false), [reviewing, setReviewing] = useState(false), [traceVersion, setTraceVersion] = useState(0)
  const stateHeadingId = useId()
  const detail = useResource(`alarm-current:${tenantId ?? 'customer'}:${alarmId}`, signal => api.alarm(alarmId, signal, tenantId))
  useEffect(() => { if (!canHistory) setShowHistory(false); if (!canReview) setReviewing(false) }, [canHistory, canReview])
  function refreshCurrent() { setReviewing(false); detail.refresh(); setTraceVersion(value => value + 1) }
  const alarm = detail.data, sameIdentity = alarm?.id === alarmId && (tenantId === undefined || alarm.tenant_id === tenantId)
  if (reviewing && canReview && alarm && sameIdentity && reviewSnapshotValid(alarm)) return <AlarmReviewBody key={`${alarm.id}:${alarm.revision}`} alarm={alarm} allowHandled onClose={refreshCurrent} onReviewed={() => { publishObjectChange('events'); onChanged(); refreshCurrent(); if (canHistory) setShowHistory(true) }}/>
  return <><SheetHeader className="review-header"><div className="eyebrow">ALARM · 当前状态与业务轨迹</div><SheetTitle>报警详情</SheetTitle><SheetDescription>读取不改变报警。人工复核、来源恢复、通知回执与设备控制分别留痕。</SheetDescription></SheetHeader>
    <div className="review-body"><div className="version-actions"><Button type="button" variant="outline" onClick={refreshCurrent}>刷新当前详情</Button></div>
      {detail.loading ? <Loading/> : detail.error != null ? <>{detail.error instanceof ApiError && detail.error.status === 403 && <div className="notice">当前报警读取已拒绝，旧详情及轨迹已清除。</div>}<ErrorNotice error={detail.error} retry={refreshCurrent}/></> : !alarm || !sameIdentity ? <Empty title="当前报警身份未确认">请刷新，不用列表旧快照替代当前详情。</Empty> : <>
        <div className="alarm-detail-title"><div><h2>{reported(alarm.device_name)}</h2><span className="mono">报警 {alarm.id} · 设备 {alarm.device_id}</span></div></div>
        <h3 className="mt-4 mb-3" id={stateHeadingId}>当前报警状态</h3><div role="group" aria-labelledby={stateHeadingId}><TraceState state={{ physical_state: alarm.physical_state, response_state: alarm.response_state, conclusion: alarm.conclusion }}/></div>
        <dl className="detail-list">
          <div><dt>事件设备名称快照</dt><dd>{reported(alarm.device_name)}</dd></div><div><dt>事件地址快照</dt><dd>{reported(alarm.address)}</dd></div>
          <div><dt>来源发生时间</dt><dd>{alarmTimeText(alarm)}</dd></div><div><dt>平台登记时间</dt><dd>{alarm.recorded_at ? dateTime(alarm.recorded_at) : '历史记录未提供，不推算'}</dd></div>
          <div><dt>事件类型</dt><dd>{alarm.event_type === 'FIRE' || alarm.event_type === 'RECOVERY' ? statusLabel(alarm.event_type) : '事件类型未确认'}</dd></div><div><dt>数据版本</dt><dd>{revisionText(alarm.revision)}</dd></div>
          <div><dt>通知投递及回执</dt><dd>发送记录中分别核对接口受理与送达/接听回执；本详情不推断已送达。</dd></div>
          <div><dt>设备远程操作</dt><dd>在设备详情查看已核验能力及操作记录；接口受理不代表硬件已执行。</dd></div>
        </dl>
        <div className="notice">人工复核不恢复物理状态，不执行静音/复位，也不会自动生成付费通知。</div>
        <div className="version-actions">{canReview && <Button type="button" disabled={!reviewSnapshotValid(alarm)} onClick={() => setReviewing(true)}><ClipboardCheck/>开始人工复核</Button>}{canHistory && <Button type="button" variant="outline" onClick={() => setShowHistory(value => !value)}><History/>{showHistory ? '收起业务轨迹' : '查看业务轨迹'}</Button>}</div>
        {!reviewSnapshotValid(alarm) && <div className="notice">当前状态或版本未确认，禁止用异常快照复核。</div>}
        {!canHistory && <div className="notice">未获得报警历史权限，不读取历史备注。</div>}
        {canHistory && showHistory && <AlarmTrace key={`${tenantId ?? 'customer'}:${alarmId}:${traceVersion}`} alarmId={alarmId} tenantId={tenantId}/>}
        <AlarmVideo alarm={alarm}/>
      </>}
    </div></>
}
