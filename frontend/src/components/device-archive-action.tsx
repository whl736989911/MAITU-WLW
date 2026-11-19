import { useState, type FormEvent } from 'react'
import { Archive } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { ErrorNotice } from '@/components/common'
import { useMutationAction } from '@/components/identity-common'
import { deviceArchiveApi } from '@/lib/device-archive'
import { useSession } from '@/lib/store'
import type { Device } from '@/lib/types'

export function DeviceArchiveAction({ device, onSaved }: { device: Device; onSaved: () => void }) {
  const session=useSession(), action=useMutationAction()
  const [open,setOpen]=useState(false),[reason,setReason]=useState('')
  if(session.audience!=='management'||!session.permissions.includes('device:write')||!device.tenant_id)return null
  function close(){setOpen(false);setReason('');action.touch()}
  function submit(event: FormEvent) {
    event.preventDefault()
    const revision = device.revision
    if (revision == null || !Number.isSafeInteger(revision)) { action.setError(new Error('设备版本未确认，请刷新后重试')); return }
    if (!reason.trim()) { action.setError(new Error('必须填写删除原因')); return }
    void action.run((key, signal) => deviceArchiveApi.archive(device.id, { expected_revision: revision, reason: reason.trim() }, key, device.tenant_id as string, signal), () => { close(); onSaved() })
  }
  return <>
    <Button type="button" variant="ghost" size="sm" disabled={!Number.isSafeInteger(device.revision)} onClick={()=>setOpen(true)}><Archive/>删除设备（保留历史）</Button>
    <Sheet open={open} onOpenChange={value=>{if(!value&&!action.busy)close()}}>
      <SheetContent className="review-sheet">
        <SheetHeader className="review-header"><SheetTitle>删除设备（保留历史）</SheetTitle><SheetDescription>从日常资产列表移除，保留设备与全部历史；不会删除厂商云端记录。</SheetDescription></SheetHeader>
        <div className="review-body"><form className="form-stack" onSubmit={submit}>
          <div className="notice"><strong>目标设备</strong><p>{device.name} · {device.device_eui} · {device.tenant_id}</p></div>
          <p className="notice">设备与全部历史/报警/轨迹保留且不改动 EUI，不会删除或注销厂商云端记录。若仍有受控接入或海康绑定，服务端会明确拒绝：必须先到来源边界解绑后再删除。厂商台账删除是另一个独立操作。</p>
          <label className="field">删除原因<Input required maxLength={1000} disabled={action.busy} value={reason} onChange={event=>{setReason(event.target.value);action.touch()}}/></label>
          {action.error!=null&&<ErrorNotice error={action.error}/>}
          <div className="identity-form-actions"><Button type="button" variant="outline" disabled={action.busy} onClick={close}>取消</Button><Button type="submit" variant="destructive" disabled={action.busy || action.conflict}>{action.busy?'处理中…':'确认删除设备（保留历史）'}</Button></div>
        </form></div>
      </SheetContent>
    </Sheet>
  </>
}
