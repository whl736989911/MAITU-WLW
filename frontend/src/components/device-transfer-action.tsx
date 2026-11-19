import { useState, type FormEvent } from 'react'
import { ArrowRightLeft } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ErrorNotice } from '@/components/common'
import { useMutationAction } from '@/components/identity-common'
import { api } from '@/lib/api'
import { deviceTransferApi } from '@/lib/device-transfer'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { placePath } from '@/lib/place-tree'
import type { Device, PlaceNode, ScopeUnit } from '@/lib/types'

export function DeviceTransferAction({ device, onSaved }: { device: Device; onSaved: () => void }) {
  const session = useSession()
  const [open, setOpen] = useState(false)
  const [targetTenant, setTargetTenant] = useState('')
  const [targetPlace, setTargetPlace] = useState('')
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  const tenants = useResource('device-transfer-tenants', signal => api.tenants(signal), open && session.permissions.includes('tenants:read'))
  const tree = useResource(`device-transfer-places:${targetTenant}`, signal => api.scopeTree(signal), open && !!targetTenant && session.permissions.includes('units:read'))
  const places = tree.data?.find((unit: ScopeUnit) => unit.id === targetTenant)?.places ?? []
  function close() { setOpen(false); setTargetTenant(''); setTargetPlace(''); setReason(''); action.touch() }
  if (session.audience !== 'management' || !session.permissions.includes('device:write') || !device.tenant_id) return null
  function submit(event: FormEvent) {
    event.preventDefault()
    const revision = device.revision
    if (revision == null || !Number.isSafeInteger(revision)) { action.setError(new Error('设备版本未确认，请刷新后重试')); return }
    if (!device.tenant_id || !targetTenant || targetTenant === device.tenant_id || !places.some((place: PlaceNode) => place.id === targetPlace) || !reason.trim()) {
      action.setError(new Error('必须明确选择其他已授权单位和该单位真实位置，并填写移交原因。')); return
    }
    void action.run(async (key, signal) => {
      await deviceTransferApi.transfer(device.id, { target_tenant_id: targetTenant, target_place_id: targetPlace, reason: reason.trim(), expected_revision: revision }, key, device.tenant_id as string, signal)
    }, () => { close(); onSaved() })
  }
  return <>
    <Button type="button" variant="ghost" size="sm" disabled={!Number.isSafeInteger(device.revision)} onClick={() => setOpen(true)}><ArrowRightLeft/>跨单位移交</Button>
    <Sheet open={open} onOpenChange={value => { if (!value && !action.busy) close() }}>
      <SheetContent className="review-sheet">
        <SheetHeader className="review-header"><SheetTitle>跨单位移交设备</SheetTitle><SheetDescription>将所选设备移交到明确的单位与位置，原单位保留历史记录。</SheetDescription></SheetHeader>
        <div className="review-body">
      <form className="form-stack" onSubmit={submit}>
        <fieldset className="form-stack" disabled={action.busy}>
        <label className="field">目标单位<select required value={targetTenant} onChange={event => { setTargetTenant(event.target.value); setTargetPlace(''); action.touch() }}><option value="">请选择已授权单位</option>{tenants.data?.items?.filter(item => item.id !== device.tenant_id).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        {tenants.error != null && <ErrorNotice error={tenants.error} retry={tenants.refresh}/>}
        <label className="field">目标位置<select required value={targetPlace} onChange={event => { setTargetPlace(event.target.value); action.touch() }}><option value="">请选择目标单位的真实位置</option>{places.map((place: PlaceNode) => <option key={place.id} value={place.id}>{placePath(place, places)}</option>)}</select></label>
        {tree.error != null && <ErrorNotice error={tree.error} retry={tree.refresh}/>}
        <label className="field">移交原因<Input required maxLength={1000} value={reason} onChange={event => { setReason(event.target.value); action.touch() }}/></label>
        <p className="muted-note">目标资产保留硬件原EUI，平台通过独立资产身份保留来源历史；不会改写EUI。海康绑定会受控重绑到新资产，但若存在未归属原始事件，服务端会在提交前拒绝。通用接入来源固定属于原单位：请先在来源管理显式解绑并在目标单位创建/配置新来源，不能静默迁移迟到消息。</p>
        {action.error != null && <ErrorNotice error={action.error}/>}
        <div className="notice"><strong>确认目标</strong><p>来源：{device.name} · {device.device_eui} · {device.tenant_id}</p><p>目标单位为 {targetTenant}，位置为 {targetPlace}；目标资产沿用原EUI。</p><p>来源单位保留全部历史与EUI。海康绑定会在事务内换指向；通用来源固定在原单位，必须先显式解绑并为目标单位配置新来源，不能静默迁移迟到消息。</p></div>
        </fieldset>
        <div className="identity-form-actions"><Button type="button" variant="outline" disabled={action.busy} onClick={close}>取消</Button><Button type="submit" disabled={action.busy || action.conflict}>{action.busy ? '正在移交…' : '确认移交'}</Button></div>
      </form>
        </div>
      </SheetContent>
    </Sheet>
  </>
}
