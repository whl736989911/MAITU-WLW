import { useEffect, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { alarmMergeApi } from '@/lib/alarm-sessions'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { ErrorNotice, Loading } from '@/components/common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'

export function AlarmMergeSettings({ tenantId, canWrite }: { tenantId: string; canWrite: boolean }) {
  const canRead = useSession(s => s.permissions.includes('alarm-base:read'))
  if (!canRead) return <ErrorNotice error={new Error('当前账户没有报警基础配置读取权限')} />
  return <AlarmMergeSettingsContent key={tenantId} tenantId={tenantId} canWrite={canWrite} />
}

function AlarmMergeSettingsContent({ tenantId, canWrite }: { tenantId: string; canWrite: boolean }) {
  const resource = useResource(`alarm-merge-policy:${tenantId}`, signal => alarmMergeApi.get(signal, tenantId))
  const [enabled, setEnabled] = useState(false)
  const [windowSeconds, setWindowSeconds] = useState('')
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  useEffect(() => {
    const value = resource.data
    if (!value) return
    setEnabled(value.enabled)
    setWindowSeconds(value.window_seconds === null ? '' : String(value.window_seconds))
  }, [resource.data?.revision])

  function save(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('必须说明配置变更原因')); return }
    const parsed = windowSeconds.trim() === '' ? null : Number(windowSeconds)
    if (parsed !== null && (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > 604800)) { action.setError(new Error('分组窗口必须为1至604800秒的整数')); return }
    if (enabled && parsed === null) { action.setError(new Error('启用报警会合组必须明确配置源时间窗口秒数')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run(async (key, signal) => {
      const result = await alarmMergeApi.save({ expected_revision: resource.data?.revision ?? 0, enabled, window_seconds: parsed, reason: reason.trim() }, key, signal, tenantId)
      setEnabled(result.enabled)
      setWindowSeconds(result.window_seconds === null ? '' : String(result.window_seconds))
      setReason('')
    }, () => { action.setConfirming(false); resource.refresh() })
  }

  return <Card><CardContent><h2>报警会合组与通知合并</h2>
    <p className="muted-note">默认关闭。启用后，同一单位、设备、事件类型且源上报时间落在窗口内的新物理报警会加入同一逻辑会合组；不改写、不删除、不合并任何物理报警行，也不改变源事件去重、恢复、复核或历史。配置只影响此后新建的报警；旧历史不会被重新分组或补发通知。</p>
    {resource.loading ? <Loading/> : resource.error != null ? <ErrorNotice error={resource.error} retry={resource.refresh}/> : <form className="form-stack" onSubmit={save}>
      <div className="notice">
        <strong>{resource.data?.revision === 0 ? '尚未配置（默认关闭）' : `当前策略 revision ${resource.data?.revision}`}</strong>
        <p>策略版本不可改写；保存会追加不可变历史。分组窗口只使用源上报时间，不使用接收时间；源时间未知的报警不会伪造成员关系。</p>
        {resource.data?.updated_at && <p className="table-sub">最近更新：{resource.data.updated_at}</p>}
      </div>
      <label className="notification-checkbox"><input type="checkbox" checked={enabled} disabled={!canWrite} onChange={e => setEnabled(e.target.checked)}/>启用报警会合组与通知合并</label>
      <div className="field"><Label htmlFor="alarm-merge-window">源时间窗口（秒，1–604800）</Label><Input id="alarm-merge-window" type="number" min={1} max={604800} value={windowSeconds} disabled={!canWrite} onChange={e => setWindowSeconds(e.target.value)} placeholder="启用时必须明确填写"/><p className="muted-note">窗口以滑动的最新源事件时间为锚点。窗口内同一会话只对一个规则、通道、联系人和级别的通知做持久认领；首个成员恢复但仍有活动成员时，不会因此丢失该活动成员的通知。</p></div>
      <div className="notice"><strong>边界</strong><p>会话成员仍各自保留物理状态、复核结论与业务轨迹；打开成员进入原报警详情，不提供静默批量复核或伪造恢复。已接受或结果未知的认领会阻止重复外呼；明确的未发送失败才沿用同一有界重试预算，且预算按会话认领保存，不因成员变化重置。</p></div>
      {canWrite && <><div className="field"><Label htmlFor="alarm-merge-reason">变更原因</Label><Input id="alarm-merge-reason" value={reason} onChange={e => setReason(e.target.value)} maxLength={1000}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit" disabled={action.busy}>保存报警会合组策略</Button>{action.confirming && <OperationConfirmation action={action} title="确认保存报警会合组策略" submitLabel="确认保存" refresh={resource.refresh} warning="保存会关闭当前仍在收集的会合窗口，并只影响此后新建报警；不会改写物理报警、恢复或复核。"><p>策略 revision：{resource.data?.revision ?? 0}</p><p>启用：{enabled ? '是' : '否'}；窗口：{windowSeconds || '未配置'} 秒</p><p>变更原因：{reason}</p></OperationConfirmation>}</>}
    </form>}
  </CardContent></Card>
}
