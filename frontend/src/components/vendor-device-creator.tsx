import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { scopeTargetTenantId } from '@/lib/operation-target'
import { api } from '@/lib/api'
import { placePath } from '@/lib/place-tree'
import type { Device, ScopeUnit } from '@/lib/types'
import { vendorDevicesApi, type CreateVendorDeviceBody, type LegacyRealtimeProfile, type VendorDeviceSource } from '@/lib/vendor-devices'
import type { CreateRegistrationBody, RegistrationDetail } from '@/lib/device-registrations'
import { VendorDeviceImporter } from '@/components/vendor-device-importer'
import { LegacyProfilePicker } from '@/components/vendor-device-profile-picker'
import { useMutationAction, OperationConfirmation } from '@/components/identity-common'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'

const intField = (value: string, label: string): number | undefined => {
  if (!value.trim()) return undefined
  const numeric = Number(value.trim())
  if (!/^\d+$/.test(value.trim()) || !Number.isSafeInteger(numeric) || numeric < 1) throw new Error(`${label}须填写准确的正整数代码`)
  return numeric
}

export function VendorDeviceCreator({ onCreated, onPending, onBatchCreated, entry }: {
  onCreated: (device: Device) => void
  onPending: (detail: RegistrationDetail) => void
  onBatchCreated: (devices: Device[]) => void
  entry: VendorDeviceSource | 'import'
}) {
  const session = useSession()
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds)
  const importing = entry === 'import'
  const source = importing ? 'LEGACY_REALTIME' : entry
  const [tenantId, setTenantId] = useState('')
  const [name, setName] = useState(''), [serial, setSerial] = useState(''), [address, setAddress] = useState('')
  const [placeId, setPlaceId] = useState(''), [profile, setProfile] = useState<LegacyRealtimeProfile | null>(null), [upstreamId, setUpstreamId] = useState('')
  const [deviceModel, setDeviceModel] = useState(''), [deviceType, setDeviceType] = useState(''), [communicationType, setCommunicationType] = useState('')
  const [vendorUnitId, setVendorUnitId] = useState(''), [verificationCode, setVerificationCode] = useState('')
  const [producterId, setProducterId] = useState(''), [productModelId, setProductModelId] = useState('')
  const created = useRef<Device | null>(null)
  const registration = useRef<RegistrationDetail | null>(null), hikvisionDevice = useRef<Device | null>(null), deviceReadError = useRef<unknown>(undefined)
  const action = useMutationAction()
  const writable = session.audience === 'management' && session.permissions.includes('device:write')
  const units = useResource('vendor-device-creator-scope', signal => api.scopeTree(signal), writable)
  const unit = units.data?.find((item: ScopeUnit) => item.id === tenantId)
  const initialScope = useRef({ tenantIds: [...scopeTenantIds], placeIds: [...scopePlaceIds] })
  const targetPrefilled = useRef(false)
  useEffect(() => {
    if (targetPrefilled.current || !units.data) return
    targetPrefilled.current = true
    setTenantId(scopeTargetTenantId(units.data, initialScope.current.tenantIds, initialScope.current.placeIds) ?? '')
  }, [units.data])

  function changeTenant(value: string) { setTenantId(value); setPlaceId(''); action.touch() }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!writable) { action.setError(new Error('当前账户没有设备新增权限')); return }
    if (!tenantId) { action.setError(new Error('请明确选择目标单位')); return }
    if (!unit?.places.some(place => place.id === placeId)) { action.setError(new Error('请从目标单位位置树中选择实际位置')); return }
    if (!name.trim() || name.trim().length > 200) { action.setError(new Error('设备名称必填且不超过200字符')); return }
    if (!/^[\x21-\x7e]{1,64}$/.test(serial.trim())) { action.setError(new Error('EUI/SN需为1–64位安全ASCII字符')); return }
    if (address.length > 500) { action.setError(new Error('地址不能超过500字符')); return }
    if (source === 'LEGACY_REALTIME' && !profile) { action.setError(new Error('请选择当前目录中已有的设备型号/协议定义')); return }
    if (source === 'LEGACY_REALTIME' && upstreamId.trim().length > 128) { action.setError(new Error('上报标识不能超过128字符')); return }
    if (source === 'HIKVISION_FIRECLOUD' && !session.permissions.includes('integrations:write')) { action.setError(new Error('海康云新增需要设备管理和云接入管理权限')); return }
    if (source === 'HIKVISION_FIRECLOUD' && (!deviceModel.trim() || deviceModel.trim().length > 100)) { action.setError(new Error('请填写海康云设备型号（最多100字符）')); return }
    if (source === 'HIKVISION_FIRECLOUD' && (!deviceType.trim() || !communicationType.trim())) { action.setError(new Error('请填写海康官方设备类型和通信方式代码')); return }
    if (source === 'HIKVISION_FIRECLOUD' && (!vendorUnitId.trim() || vendorUnitId.trim().length > 64)) { action.setError(new Error('请填写海康云目标单位标识（最多64字符）')); return }
    if (source === 'HIKVISION_FIRECLOUD' && verificationCode.length > 200) { action.setError(new Error('校验码不能超过200字符')); return }
    let body: CreateVendorDeviceBody | CreateRegistrationBody
    try {
      body = source === 'LEGACY_REALTIME'
        ? { source, name: name.trim(), serial: serial.trim(), place_id: placeId, ...(address.trim() ? { address: address.trim() } : {}), legacy_profile_id: profile!.id, ...(upstreamId.trim() ? { upstream_device_id: upstreamId.trim() } : {}) }
        : {
          mode: 'NEW_VENDOR', driver_key: 'HIKVISION_FIRECLOUD', external_serial: serial.trim(), device_name: name.trim(), device_model: deviceModel.trim(),
          ...(deviceType.trim() ? { device_type: intField(deviceType, '设备类型') } : {}),
          ...(communicationType.trim() ? { communication_type: intField(communicationType, '通信方式') } : {}),
          ...(producterId.trim() ? { producter_id: intField(producterId, '厂商ID') } : {}),
          ...(productModelId.trim() ? { product_model_id: intField(productModelId, '产品型号ID') } : {}),
          ...(vendorUnitId.trim() ? { vendor_unit_id: vendorUnitId.trim() } : {}), ...(verificationCode ? { verification_code: verificationCode } : {}),
          ...(address.trim() ? { location: address.trim() } : {}), tenant_id: tenantId, place_id: placeId,
        }
    } catch (error) { action.setError(error instanceof Error ? error : new Error('官方字段无效')); return }
    if (source === 'HIKVISION_FIRECLOUD' && !action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    created.current = null; registration.current = null; hikvisionDevice.current = null; deviceReadError.current = undefined
    void action.run(async (key, signal) => {
      if (source === 'LEGACY_REALTIME') created.current = await vendorDevicesApi.create(body as CreateVendorDeviceBody, key, tenantId, signal)
      else {
        registration.current = await vendorDevicesApi.createHikvision(body as CreateRegistrationBody, key, tenantId, signal)
        const current = registration.current
        if (current.registration.status === 'COMMISSIONED' && current.registration.device_id) {
          try { hikvisionDevice.current = await api.device(current.registration.device_id, signal, tenantId) }
          catch (error) { deviceReadError.current = error }
        }
      }
    }, () => {
      if (created.current) { onCreated(created.current); return }
      const detail = registration.current
      if (!detail) return
      if (hikvisionDevice.current) { onCreated(hikvisionDevice.current); return }
      if (deviceReadError.current) action.setError(deviceReadError.current instanceof Error ? deviceReadError.current : new Error('已建档设备详情暂不可读取；登记仍保留在待完成接入中'))
      onPending(detail)
    })
  }

  if (!writable) return <ErrorNotice error={new Error('此入口仅向具备 device:write 权限的管理账户开放')}/>
  if (importing) return <>{units.error != null && <ErrorNotice error={units.error} retry={units.refresh}/>}<VendorDeviceImporter units={units.data ?? []} onImported={onBatchCreated}/></>
  const selectedPlace = unit?.places.find(place => place.id === placeId)
  const missingRequired = [
    !tenantId && '归属单位', !placeId && '实际位置', !name.trim() && '设备名称', !serial.trim() && '设备序列号 / EUI',
    source === 'LEGACY_REALTIME' && !profile && '设备型号',
    source === 'HIKVISION_FIRECLOUD' && !deviceModel.trim() && '海康设备型号',
    source === 'HIKVISION_FIRECLOUD' && !deviceType.trim() && '海康设备类型代码',
    source === 'HIKVISION_FIRECLOUD' && !communicationType.trim() && '通信方式代码',
    source === 'HIKVISION_FIRECLOUD' && !vendorUnitId.trim() && '海康云单位 / 组织标识',
  ].filter(Boolean)
  return <>
      <section className="form-stack" aria-label="本次目标单位与实际位置">
        <div className="field"><Label htmlFor="vendor-device-tenant">归属单位</Label><select id="vendor-device-tenant" className="native-select" value={tenantId} onChange={event => changeTenant(event.target.value)} disabled={units.loading || units.error != null || action.busy || action.confirming}><option value="">请明确选择目标单位</option>{(units.data ?? []).map((item: ScopeUnit) => <option key={item.id} value={item.id}>{item.name} · {item.id}</option>)}</select>{units.error != null && <ErrorNotice error={units.error} retry={units.refresh}/>}<p className="muted-note">左侧单位/位置筛选不会自动成为写入目标；本次新增以此处选择为准。</p></div>
        <div className="field"><Label htmlFor="vendor-device-place">实际位置</Label><select id="vendor-device-place" className="native-select" value={placeId} onChange={event => { setPlaceId(event.target.value); action.touch() }} disabled={!unit || units.loading || units.error != null || action.busy || action.confirming}><option value="">{unit ? '请选择目标单位中的实际位置' : '请先选择归属单位'}</option>{(unit?.places ?? []).map(place => <option key={place.id} value={place.id}>{placePath(place, unit!.places)}</option>)}</select>{unit && !unit.places.length && <p className="muted-note">该单位暂无可选位置，请先维护真实位置。</p>}</div>
      </section>
      <form className="form-stack" onSubmit={submit}>
        {!action.confirming ? <fieldset className="form-stack" disabled={action.busy}>
          <div className="field"><Label htmlFor="vendor-device-name">设备名称</Label><Input id="vendor-device-name" required maxLength={200} value={name} onChange={event => { setName(event.target.value); action.touch() }}/></div>
          <div className="field"><Label htmlFor="vendor-device-serial">设备序列号 / EUI</Label><Input id="vendor-device-serial" required maxLength={64} autoComplete="off" value={serial} onChange={event => { setSerial(event.target.value); action.touch() }}/><p className="muted-note">按设备真实EUI或序列号填写；不会推测或自动生成云端标识。</p></div>
          <div className="field"><Label htmlFor="vendor-device-address">安装地址（可选）</Label><Input id="vendor-device-address" maxLength={500} value={address} onChange={event => { setAddress(event.target.value); action.touch() }}/></div>
          {source === 'LEGACY_REALTIME' && <section className="form-stack" aria-label="旧烟感本地直连配置">
            <details className="form-stack"><summary>直连接入说明</summary><p className="muted-note">网关、MQTT或运营商回调须直接向本平台上报，不调用旧业务平台。建档不会自动改写网络配置；鉴权HTTP入口为 <code>/api/notify/v1/&#123;接入token&#125;/&#123;协议类型&#125;/telemetry</code>，需由接入管理员配置。</p></details>
            <LegacyProfilePicker value={profile} onChange={value => { setProfile(value); action.touch() }} note="只需在此选择一次设备型号，上方搜索框可以留空。"/>
            <div className="field"><Label htmlFor="vendor-device-upstream-id">本平台上报别名（可选）</Label><Input id="vendor-device-upstream-id" maxLength={128} value={upstreamId} onChange={event => { setUpstreamId(event.target.value); action.touch() }} placeholder="仅当上报标识与设备序列号不同时填写"/></div>
          </section>}
          {source === 'HIKVISION_FIRECLOUD' && <section className="form-stack" aria-label="海康云开户字段">
            <p className="notice">一次确认将调用海康云新增设备，再保存本地资产/登记。请依海康官方资料填写，不要求先同步设备清单或发布本平台通用产品版本。未知结果会保留登记，不能盲目重试。</p>
            <div className="field"><Label htmlFor="hik-device-model">海康设备型号</Label><Input id="hik-device-model" required maxLength={100} value={deviceModel} onChange={event => { setDeviceModel(event.target.value); action.touch() }}/></div>
            <div className="field"><Label htmlFor="hik-device-type">海康设备类型代码（官方字段）</Label><Input id="hik-device-type" required inputMode="numeric" value={deviceType} onChange={event => { setDeviceType(event.target.value); action.touch() }}/><p className="muted-note">填写海康云文档或设备资料中的准确代码，不推测默认类型。</p></div>
            <div className="field"><Label htmlFor="hik-communication-type">通信方式代码（官方字段）</Label><Input id="hik-communication-type" required inputMode="numeric" value={communicationType} onChange={event => { setCommunicationType(event.target.value); action.touch() }}/></div>
            <div className="field"><Label htmlFor="hik-vendor-unit">海康云单位 / 组织标识</Label><Input id="hik-vendor-unit" required maxLength={64} value={vendorUnitId} onChange={event => { setVendorUnitId(event.target.value); action.touch() }}/><p className="muted-note">填写海康云目标单位的真实标识，不提供虚构默认值。</p></div>
            <div className="field"><Label htmlFor="hik-verification-code">海康校验码（可选）</Label><Input id="hik-verification-code" type="password" maxLength={200} autoComplete="new-password" value={verificationCode} onChange={event => { setVerificationCode(event.target.value); action.touch() }}/><p className="muted-note">敏感字段仅提交保存，不在响应中回传。</p></div>
            <details className="form-stack"><summary>其他海康官方字段（仅在官方资料明确时填写）</summary>
              <div className="field"><Label htmlFor="hik-producer-id">厂商ID</Label><Input id="hik-producer-id" inputMode="numeric" value={producterId} onChange={event => { setProducterId(event.target.value); action.touch() }}/></div>
              <div className="field"><Label htmlFor="hik-product-model-id">产品型号ID</Label><Input id="hik-product-model-id" inputMode="numeric" value={productModelId} onChange={event => { setProductModelId(event.target.value); action.touch() }}/></div>
            </details>
          </section>}
          {action.error != null && <ErrorNotice error={action.error}/>}
          {missingRequired.length > 0 && <p id="vendor-device-required" className="muted-note" role="status" aria-live="polite">尚未完成：{missingRequired.join('、')}。点击{source === 'HIKVISION_FIRECLOUD' ? '核对' : '保存'}可定位必填项。</p>}
          <Button type="submit" disabled={action.busy} aria-describedby={missingRequired.length ? 'vendor-device-required' : undefined}>{action.busy ? '正在提交…' : source === 'HIKVISION_FIRECLOUD' ? '核对新增设备' : '保存设备'}</Button>
        </fieldset> : <OperationConfirmation action={action} title="确认调用海康云新增并保存本地登记" submitLabel="确认新增" refresh={() => action.setConfirming(false)} warning="本次确认授权海康云新增及本地资产/登记保存，不代表设备已激活或现场已验收。未知结果请进入待完成接入核对，不要再次提交。">
          <p>方式：海康云开户并保存本地资产</p><p>设备：{name.trim()} · {serial.trim()}</p><p>归属单位：{unit?.name ?? tenantId} · 位置：{selectedPlace ? placePath(selectedPlace, unit!.places) : placeId}</p><p>地址：{address.trim() || '未填写'}</p><p>海康设备型号：{deviceModel.trim()} · 官方设备类型/通信代码：{deviceType || '未填写'} / {communicationType || '未填写'} · 云单位：{vendorUnitId || '未填写'}</p>
        </OperationConfirmation>}
      </form>
  </>
}
