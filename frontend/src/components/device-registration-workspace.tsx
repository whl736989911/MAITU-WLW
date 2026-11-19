import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { scopeTargetTenantId } from '@/lib/operation-target'
import { errorMessage } from '@/lib/api'
import {
  deviceRegistrationsApi, buildLegacyVendorPayload, emptyLegacyVendorDraft, legacyVendorFamily,
  validateLegacyName, validateLegacySerial,
  type CreateRegistrationBody, type LegacyVendorDraft, type LegacyVendorFamily, type RegistrationDetail, type RegistrationMode,
} from '@/lib/device-registrations'
import { deviceServicesApi } from '@/lib/device-services'
import type { ProductVersion, ScopeUnit } from '@/lib/types'
import { placePath } from '@/lib/place-tree'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Card, CardContent, CardHeader, CardTitle } from './ui/card'
import { SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Empty, ErrorNotice, Loading } from './common'
import { OperationConfirmation, useMutationAction } from './identity-common'
import { PublishedVersionPicker } from './product-common'
import { LegacyVendorFields } from './legacy-vendor-fields'

const cloudStateLabel: Record<string, string> = {
  NOT_REQUIRED: '无云端步骤', NOT_ATTEMPTED: '尚未提交云端', IN_FLIGHT: '云端提交中/结果未确认',
  SUCCEEDED: '云端已接受（不代表激活/上线）', FAILED: '云端步骤失败', UNKNOWN: '云端结果未知（需核对）',
}
const importedCloudStateLabel: Record<string, string> = {
  NOT_REQUIRED: '无云端步骤', NOT_ATTEMPTED: '尚未执行远端只读核对与本地导入匹配', IN_FLIGHT: '远端只读核对中',
  SUCCEEDED: '远端EUI/台账ID核对及本地导入匹配成功（未写入远端）', FAILED: '远端核对或本地匹配未通过',
  UNKNOWN: '核对结果未确认',
}
const localStateLabel: Record<string, string> = {
  NOT_READY: '本地验收未就绪', READY: '可本地验收', SUCCEEDED: '本地验收完成', FAILED: '本地验收失败',
}
function importedIdentityVerified(cloudResult: unknown): boolean {
  if (!cloudResult || typeof cloudResult !== 'object' || Array.isArray(cloudResult)) return false
  const root = cloudResult as Record<string, unknown>
  const evidence = root.result ?? root.lookup_result
  if (!evidence || typeof evidence !== 'object' || Array.isArray(evidence)) return false
  const row = evidence as Record<string, unknown>
  return row.remote_lookup === true && row.identity_verified === true && row.local_match_state === 'FOUND'
}
function modeLabelFor(family: LegacyVendorFamily | null): Record<RegistrationMode, string> {
  if (family) return {
    NEW_VENDOR: '旧台账新增（厂商官方add）',
    MATCH_EXISTING: '关联导入旧台账（远端只读核对+本地精确匹配）',
    PRODUCT_MODEL: '产品模型本地登记（无云端）',
  }
  return {
    NEW_VENDOR: '厂商新增（官方add）', MATCH_EXISTING: '匹配已有云端资源（精确查询，不改归属）', PRODUCT_MODEL: '产品模型本地登记（无云端）',
  }
}

export function RegistrationCreator({ defaultDriverKey, onCreated }: { defaultDriverKey?: string; onCreated: (detail: RegistrationDetail) => void }) {
  const drivers = useResource('registration-drivers', signal => deviceServicesApi.drivers(signal))
  const scope = useResource('registration-place-scope', signal => api.scopeTree(signal))
  const [driverKey, setDriverKey] = useState(defaultDriverKey ?? '')
  const [mode, setMode] = useState<RegistrationMode>('NEW_VENDOR')
  const [serial, setSerial] = useState('')
  const [name, setName] = useState('')
  const [model, setModel] = useState('')
  const [deviceType, setDeviceType] = useState('')
  const [communicationType, setCommunicationType] = useState('')
  const [verificationCode, setVerificationCode] = useState('')
  const [vendorUnitId, setVendorUnitId] = useState('')
  const [externalDeviceId, setExternalDeviceId] = useState('')
  const [legacy, setLegacy] = useState<LegacyVendorDraft | null>(() => emptyLegacyVendorDraft(defaultDriverKey ?? ''))
  const [location, setLocation] = useState('')
  const [latitude, setLatitude] = useState(''), [longitude, setLongitude] = useState('')
  const [tenantId, setTenantId] = useState('')
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds)
  const initialScope = useRef({ tenantIds: [...scopeTenantIds], placeIds: [...scopePlaceIds] })
  const targetPrefilled = useRef(false)
  const [placeId, setPlaceId] = useState('')
  const [versionId, setVersionId] = useState('')
  const [chosenVersion, setChosenVersion] = useState<ProductVersion | null>(null)
  const action = useMutationAction()
  const driver = drivers.data?.find(item => item.key === driverKey)
  const family = legacyVendorFamily(driverKey)
  const modeLabel = modeLabelFor(family)
  const units: ScopeUnit[] = scope.data ?? []
  useEffect(() => {
    if (targetPrefilled.current || !scope.data) return
    targetPrefilled.current = true
    setTenantId(scopeTargetTenantId(scope.data, initialScope.current.tenantIds, initialScope.current.placeIds) ?? '')
  }, [scope.data])
  const unit = units.find(item => item.id === tenantId)
  const selectedPlace = unit?.places.find(place => place.id === placeId)
  function chooseDriver(key: string) {
    setDriverKey(key)
    const found = drivers.data?.find(item => item.key === key)
    setMode(found?.mode === 'PRODUCT_MODEL' ? 'PRODUCT_MODEL' : 'NEW_VENDOR')
    setLegacy(emptyLegacyVendorDraft(key))
    setExternalDeviceId('')
    setVersionId(''); setChosenVersion(null)
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!driver) { action.setError(new Error('请选择接入驱动')); return }
    if (!serial.trim()) { action.setError(new Error('请填写精确序列号/设备标识')); return }
    if (!/^[\x21-\x7e]{1,200}$/.test(serial.trim())) { action.setError(new Error('序列号必须是1-200位安全ASCII，不能含空格或控制字符')); return }
    if (mode === 'PRODUCT_MODEL' && serial.trim().length > 64) { action.setError(new Error('产品模型设备标识必须是1-64位安全ASCII')); return }
    let vendorPayload: Record<string, unknown> | undefined
    if (mode === 'NEW_VENDOR' && family) {
      const nameError = validateLegacyName(family, name)
      if (nameError) { action.setError(new Error(nameError)); return }
      const serialError = validateLegacySerial(family, serial)
      if (serialError) { action.setError(new Error(serialError)); return }
      if (!legacy) { action.setError(new Error('旧台账字段未初始化')); return }
      try { vendorPayload = buildLegacyVendorPayload(legacy) } catch (failure) { action.setError(failure); return }
    }
    if (mode === 'MATCH_EXISTING' && family) {
      const serialError = validateLegacySerial(family, serial)
      if (serialError) { action.setError(new Error(serialError)); return }
      if (!externalDeviceId.trim()) { action.setError(new Error('关联已导入旧台账必须填写旧平台台账ID(dId)')); return }
      if (!/^[\x21-\x7e]{1,128}$/.test(externalDeviceId.trim())) { action.setError(new Error('旧平台台账ID必须是1-128位安全ASCII')); return }
    }
    if (!tenantId) { action.setError(new Error('来源登记必须明确选择本地归属单位')); return }
    if (!placeId || !unit?.places.some(place => place.id === placeId)) { action.setError(new Error('来源登记必须选择所选单位中的真实位置')); return }
    if (mode === 'NEW_VENDOR' && !family && (!name.trim() || !model.trim() || !/^\d+$/.test(deviceType) || !/^\d+$/.test(communicationType) || !vendorUnitId.trim())) {
      action.setError(new Error('厂商新增必须填写名称、型号、数字设备类型、数字通信类型与厂商unitID；官方未定义省略unitID时的默认归属'))
      return
    }
    if (mode === 'PRODUCT_MODEL' && (!name.trim() || !versionId)) { action.setError(new Error('产品模型登记必须填写本地名称并选择已发布版本')); return }
    if (!family && (latitude === '') !== (longitude === '')) { action.setError(new Error('经纬度必须同时填写或同时留空')); return }
    if (!family && latitude !== '' && (!Number.isFinite(Number(latitude)) || !Number.isFinite(Number(longitude)))) { action.setError(new Error('经纬度必须是数值')); return }
    const body: CreateRegistrationBody = {
      mode, driver_key: driverKey, external_serial: serial.trim(),
    }
    if (mode === 'MATCH_EXISTING' && family) body.external_device_id = externalDeviceId.trim()
    if (mode !== 'MATCH_EXISTING' && name.trim()) body.device_name = name.trim()
    if (mode === 'NEW_VENDOR' && family) body.vendor_payload = vendorPayload
    if (mode === 'NEW_VENDOR' && !family) {
      body.device_model = model.trim()
      body.device_type = Number(deviceType)
      body.communication_type = Number(communicationType)
      body.vendor_unit_id = vendorUnitId.trim()
      if (verificationCode.trim()) body.verification_code = verificationCode.trim()
      if (location.trim()) body.location = location.trim()
      if (latitude !== '') { body.latitude = Number(latitude); body.longitude = Number(longitude) }
    }
    if (mode === 'PRODUCT_MODEL' && versionId) body.product_version_id = versionId
    if (tenantId) body.tenant_id = tenantId
    if (placeId) body.place_id = placeId
    void action.run((key, signal) => deviceRegistrationsApi.create(body, key, tenantId || undefined, signal).then(result => {
      onCreated(result)
      setSerial(''); setName(''); setModel(''); setDeviceType(''); setCommunicationType(''); setVerificationCode(''); setVendorUnitId(''); setLocation(''); setLatitude(''); setLongitude(''); setExternalDeviceId('')
      setLegacy(emptyLegacyVendorDraft(driverKey))
      return result
    }), () => {})
  }
  const legacyPreview = mode === 'NEW_VENDOR' && legacy ? (() => {
    try { return Object.keys(buildLegacyVendorPayload(legacy)) } catch { return null }
  })() : null
  return <>
    <SheetHeader className="review-header"><div className="eyebrow">PREREGISTRATION · 来源登记</div><SheetTitle>来源登记与本地验收</SheetTitle>
      <SheetDescription>登记先保存为平台草稿。厂商接受不等于设备激活；本地验收需要真实单位、位置与产品身份，且不会自动删除厂商记录。</SheetDescription></SheetHeader>
    <div className="review-body">{drivers.loading ? <Loading/> : drivers.error != null ? <ErrorNotice error={drivers.error} retry={drivers.refresh}/> : <form className="form-stack" onSubmit={submit}>
        <div className="field"><Label htmlFor="registration-driver">接入驱动</Label><select id="registration-driver" className="native-select" required value={driverKey} onChange={event => chooseDriver(event.target.value)}><option value="">请选择驱动</option>{(drivers.data ?? []).map(item => <option key={item.key} value={item.key}>{item.display_name} · {item.mode}</option>)}</select>
          {driver && <p className="muted-note">配置状态 {driver.configuration_code} · 账号指纹 {driver.account_fingerprint ?? '未固定'} · {driver.configured && driver.enabled ? '可发起真实外部请求' : '未就绪；草稿可保存，外发前需由管理员启用真实来源'}</p>}
        </div>
        <div className="field"><Label htmlFor="registration-mode">登记方式</Label><select id="registration-mode" className="native-select" value={mode} onChange={event => setMode(event.target.value as RegistrationMode)} disabled={!driver}>{driver?.mode === 'PRODUCT_MODEL'
          ? <option value="PRODUCT_MODEL">产品模型本地登记（无云端）</option>
          : family
            ? <><option value="NEW_VENDOR">旧台账新增（厂商官方add）</option><option value="MATCH_EXISTING">关联导入旧台账（远端只读核对EUI+dId）</option></>
            : <><option value="NEW_VENDOR">厂商新增（官方add）</option><option value="MATCH_EXISTING">匹配已有云端资源</option></>}</select>
          {family && <p className="muted-note">旧平台驱动不套用正式产品版本：新增按台账add字段；关联先以当前来源账号只读核对旧平台详情EUI与dId，再在本平台导入台账精确匹配；不写入远端。</p>}
        </div>
        <div className="field"><Label htmlFor="registration-serial">{family && mode === 'MATCH_EXISTING' ? '精确设备编号/EUI（与导入台账一致）' : '精确序列号 / 本地设备标识'}</Label><Input id="registration-serial" required maxLength={200} value={serial} onChange={event => setSerial(event.target.value)}/><p className="muted-note">{family ? (family === 'LEGACY_TTL' ? '旧TTL编号为5-32位安全ASCII。' : '旧TSF编号为1-200位安全ASCII。') : ''}只做精确匹配；不做模糊或取第一行自动绑定。</p></div>
        {mode === 'MATCH_EXISTING' && family && <div className="field"><Label htmlFor="registration-ledger-id">旧平台台账ID(dId)</Label><Input id="registration-ledger-id" required maxLength={128} value={externalDeviceId} onChange={event => setExternalDeviceId(event.target.value)}/><p className="muted-note">必须与导入台账dId完全一致；TSF导入资产通常以 LEGACY-TSF-&lt;dId&gt; 形式存在，但仍以服务端精确匹配为准。</p></div>}
        {mode !== 'MATCH_EXISTING' && <div className="field"><Label htmlFor="registration-name">{mode === 'PRODUCT_MODEL' ? '本地设备名称' : family === 'LEGACY_TSF' ? '设备名称（厂商dName，1-20字符）' : '设备名称（厂商）'}</Label><Input id="registration-name" required maxLength={family === 'LEGACY_TSF' ? 20 : 200} value={name} onChange={event => setName(event.target.value)}/></div>}
        {mode === 'NEW_VENDOR' && !family && <>
          <div className="field"><Label htmlFor="registration-model">设备型号（厂商）</Label><Input id="registration-model" required maxLength={100} value={model} onChange={event => setModel(event.target.value)} placeholder="例如 800235"/></div>
          <div className="field"><Label htmlFor="registration-type">设备类型码</Label><Input id="registration-type" required inputMode="numeric" value={deviceType} onChange={event => setDeviceType(event.target.value)} placeholder="例如 600002"/></div>
          <div className="field"><Label htmlFor="registration-comm">通信类型码</Label><Input id="registration-comm" required inputMode="numeric" value={communicationType} onChange={event => setCommunicationType(event.target.value)} placeholder="例如 900002"/></div>
          <div className="field"><Label htmlFor="registration-unit">厂商unitID</Label><Input id="registration-unit" required maxLength={64} value={vendorUnitId} onChange={event => setVendorUnitId(event.target.value)}/><p className="muted-note">官方未说明省略unitID时的默认归属，必须由操作者明确提供。</p></div>
          <div className="field"><Label htmlFor="registration-code">验证码（可选，服务端加密保存）</Label><Input id="registration-code" type="password" autoComplete="off" maxLength={200} value={verificationCode} onChange={event => setVerificationCode(event.target.value)}/></div>
          <div className="field"><Label htmlFor="registration-location">厂商位置描述（可选）</Label><Input id="registration-location" maxLength={500} value={location} onChange={event => setLocation(event.target.value)}/></div>
          <div className="field"><Label>厂商经纬度（可选，成对）</Label><div className="inline-fields"><Input aria-label="纬度" inputMode="decimal" value={latitude} onChange={event => setLatitude(event.target.value)}/><Input aria-label="经度" inputMode="decimal" value={longitude} onChange={event => setLongitude(event.target.value)}/></div></div>
        </>}
        {mode === 'NEW_VENDOR' && family && legacy && <LegacyVendorFields draft={legacy} onChange={setLegacy}/>}
        {mode === 'PRODUCT_MODEL' && <PublishedVersionPicker value={versionId} onChange={(id, version) => { setVersionId(id); setChosenVersion(version) }}/>}
        <div className="field"><Label htmlFor="registration-tenant">目标归属单位（必选）</Label><select id="registration-tenant" className="native-select" required value={tenantId} onChange={event => { setTenantId(event.target.value); setPlaceId('') }}><option value="">请选择本次登记目标单位</option>{units.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></div>
        <div className="field"><Label htmlFor="registration-place">实际安装位置（必选）</Label><select id="registration-place" className="native-select" required value={placeId} disabled={!tenantId} onChange={event => setPlaceId(event.target.value)}><option value="">{tenantId ? '请选择目标单位中的实际位置' : '请先选择单位'}</option>{(unit?.places ?? []).map(place => <option key={place.id} value={place.id}>{placePath(place, unit?.places ?? [])}</option>)}</select>{scope.error != null && <ErrorNotice error={scope.error} retry={scope.refresh}/>}</div>
      <div className="notice"><strong>确认登记目标</strong><p>{modeLabel[mode]} · {driverKey} · 序列号 {serial} · 单位/位置：{unit?.name ?? '未确定'} / {selectedPlace ? placePath(selectedPlace, unit?.places ?? []) : '未确定'}</p><p>{mode === 'NEW_VENDOR' ? '创建只保存平台草稿；需另行显式提交厂商步骤，厂商接受后仍需本地验收，且不代表终端激活。' : mode === 'MATCH_EXISTING' ? '创建只保存平台草稿，并执行远端只读核对与本地导入精确匹配；不写入远端。' : '创建只保存平台草稿；不会调用厂商接口。'}</p></div>
        {action.error != null && <ErrorNotice error={action.error}/>}
        <Button type="submit" disabled={action.busy || !driver || mode === 'PRODUCT_MODEL' && !versionId}>{action.busy ? '正在创建…' : '创建来源登记草稿'}</Button>
    </form>}</div>
  </>
}

export function RegistrationDetailView({ registrationId, onChanged }: { registrationId: string; onChanged?: () => void }) {
  const detail = useResource(`device-registration:${registrationId}`, signal => deviceRegistrationsApi.detail(registrationId, signal))
  if (detail.loading) return <Loading/>
  if (detail.error != null) return <ErrorNotice error={detail.error} retry={detail.refresh}/>
  if (!detail.data) return <Empty title="登记详情未确认"/>
  return <RegistrationWorkspace detail={detail.data} refresh={detail.refresh} onChanged={onChanged}/>
}

function RegistrationWorkspace({ detail, refresh, onChanged }: { detail: RegistrationDetail; refresh: () => void; onChanged?: () => void }) {
  const registration = detail.registration
  const permissions = useSession(s => s.permissions)
  const drivers = useResource('registration-drivers', signal => deviceServicesApi.drivers(signal), permissions.includes('device-services:read'))
  const canWrite = permissions.includes('device:write')
  const commission = useMutationAction()
  const family = legacyVendorFamily(registration.driver_key)
  const imported = family !== null && registration.mode === 'MATCH_EXISTING'
  const productModel = registration.mode === 'PRODUCT_MODEL'
  const nativeHikvision = registration.driver_key === 'HIKVISION_FIRECLOUD' && registration.mode === 'NEW_VENDOR'
  const requiresVersion = !imported && !nativeHikvision
  const modeLabel = modeLabelFor(family)
  const cloudLabels = imported ? importedCloudStateLabel : cloudStateLabel
  const [tenantId, setTenantId] = useState(registration.tenant_id ?? '')
  const [placeId, setPlaceId] = useState(registration.place_id ?? '')
  const [versionId, setVersionId] = useState(registration.product_version_id ?? '')
  const [existingDeviceId, setExistingDeviceId] = useState('')
  const [acceptance, setAcceptance] = useState('')
  const [deviceName, setDeviceName] = useState(registration.device_name ?? '')
  const [address, setAddress] = useState(detail.location ?? '')
  const [deviceSearchDraft, setDeviceSearchDraft] = useState(registration.external_serial)
  const [deviceSearch, setDeviceSearch] = useState(registration.external_serial)
  const [devicePage, setDevicePage] = useState(1)
  const scope = useResource('registration-commission-scope', signal => api.scopeTree(signal), canWrite)
  const candidates = useResource(`registration-commission-devices:${tenantId}:${devicePage}:${deviceSearch}`, signal => api.devices(devicePage, deviceSearch, 20, signal, undefined, undefined, tenantId || undefined), canWrite && !!tenantId && registration.mode === 'MATCH_EXISTING')
  const units: ScopeUnit[] = scope.data ?? []
  const unit = units.find(item => item.id === tenantId)
  const selectedPlace = unit?.places.find(place => place.id === placeId)
  const candidateRows = (candidates.data?.items ?? []).filter(device => device.tenant_id === tenantId)
  const chosenCandidate = candidateRows.find(device => device.id === existingDeviceId)
  function changeDevicePage(page: number) { setDevicePage(page); setExistingDeviceId(''); commission.touch() }
  function searchDevices() { setDeviceSearch(deviceSearchDraft.trim()); changeDevicePage(1) }
  const cloudDone = registration.cloud_state === 'SUCCEEDED'
  const importedVerified = !imported || importedIdentityVerified(detail.cloud_result)
  const needsImportedVerification = imported && cloudDone && !importedVerified
  const cloudText = needsImportedVerification ? '导入台账已命中，但缺少远端只读核对标记（需重新核对）' : cloudLabels[registration.cloud_state] ?? registration.cloud_state
  const canResume = canWrite && !productModel && !cloudDone && registration.cloud_state !== 'IN_FLIGHT' && registration.status !== 'COMMISSIONED' && registration.status !== 'ABANDONED'
  const canReconcile = canWrite && !productModel && registration.status !== 'ABANDONED' && (registration.cloud_state === 'IN_FLIGHT' || registration.cloud_state === 'UNKNOWN' || needsImportedVerification)
  const canCommission = canWrite && registration.status !== 'COMMISSIONED' && registration.status !== 'ABANDONED' && (productModel || (cloudDone && importedVerified))
  const canAbandon = canWrite && registration.status !== 'COMMISSIONED' && registration.status !== 'ABANDONED' && (imported || !cloudDone)
  const done = () => { refresh(); onChanged?.() }
  const driver = drivers.data?.find(item => item.key === registration.driver_key)
  return <div className="review-body">
    <div className="identity-readonly mono">{registration.id} · {registration.driver_key} · revision {registration.revision}</div>
    <dl className="detail-list">
      <div><dt>方式</dt><dd>{modeLabel[registration.mode]}</dd></div>
      <div><dt>序列号</dt><dd className="mono">{registration.external_serial}</dd></div>
      {imported && <div><dt>旧台账ID(dId)</dt><dd className="mono">{registration.external_device_id ?? '未保存'}</dd></div>}
      <div><dt>{imported ? '远端核对与导入匹配' : '云端状态'}</dt><dd>{cloudText}{!imported && registration.external_device_id ? ` · ${registration.external_device_id}` : ''}</dd></div>
      <div><dt>本地状态</dt><dd>{localStateLabel[registration.local_state] ?? registration.local_state}{registration.device_id ? ` · 设备 ${registration.device_id}` : ''}</dd></div>
      <div><dt>单位 / 位置 / 版本</dt><dd className="mono">{registration.tenant_id ?? '未确定'} / {registration.place_id ?? '未确定'} / {registration.product_version_id ?? (imported ? '验收时保留导入资产版本' : nativeHikvision ? '海康原生设备，无需通用产品版本' : '未确定')}</dd></div>
      <div><dt>校验码</dt><dd>{detail.verification_code_stored ? '已加密保存（不回显）' : '未提供'}</dd></div>
      <div><dt>厂商字段</dt><dd>{detail.vendor_payload_stored ? <>{detail.vendor_payload_fields.length ? detail.vendor_payload_fields.join('、') : '字段名未确认'}（整体加密，不回显值）</> : '未保存'}</dd></div>
      <div><dt>厂商账号指纹</dt><dd className="mono">{detail.account_fingerprint ? `${detail.account_fingerprint} · 续做必须保持同一指纹` : '未固定（首次外发时固定为当前驱动账号指纹）'}</dd></div>
    </dl>
    {driver && <p className="muted-note">驱动 {driver.display_name} · 配置状态 {driver.configuration_code} · {driver.configured && driver.enabled ? '当前可外发' : '当前未就绪；不会发起外部请求'}</p>}
    {detail.account_fingerprint && driver?.account_fingerprint && detail.account_fingerprint !== driver.account_fingerprint && <p className="notice">厂商账号指纹已轮换：该登记固定旧账号指纹，不能用新账号续做，请新建登记。</p>}
    {needsImportedVerification && <p className="notice">当前证据缺少远端只读核对标记（旧版本地匹配）；该结果不能用于验收，请重新执行远端核对与导入匹配。</p>}
    {imported && registration.local_state === 'NOT_READY' && <p className="muted-note">导入关联以设备导入时的固定产品版本验收；登记本身的版本字段在验收前为空，这不代表缺少产品身份。</p>}
    {detail.cloud_result != null && <><h3>{imported ? '远端核对与导入匹配证据' : '云端结果证据'}</h3><StructuredEvidence value={detail.cloud_result}/></>}
    <div className="notification-actions">
      {canResume && <ResumeRegistration registrationId={registration.id} imported={imported} onDone={done}/>}
      {canReconcile && <ReconcileRegistration registrationId={registration.id} imported={imported} onDone={done}/>}
      {canAbandon && <AbandonRegistration registrationId={registration.id} imported={imported} onDone={done}/>}
    </div>
    {canCommission && <Card className="service-card"><CardHeader><CardTitle>本地验收</CardTitle></CardHeader><CardContent>
      <form className="form-stack" onSubmit={event => {
        event.preventDefault()
        if (!tenantId || !placeId || !acceptance.trim()) { commission.setError(new Error('必须选择单位、位置并填写验收说明')); return }
        if (requiresVersion && !versionId) { commission.setError(new Error('必须选择已发布产品版本')); return }
        if (registration.mode === 'MATCH_EXISTING' && (candidates.loading || candidates.error != null || !chosenCandidate || !Number.isSafeInteger(chosenCandidate.revision))) { commission.setError(new Error('请在当前查询结果中明确选择版本有效的本地设备')); return }
        if (!commission.confirming) { commission.setConfirming(true); return }
        void commission.run((key, signal) => deviceRegistrationsApi.commission(registration.id, {
          tenant_id: tenantId, place_id: placeId, product_version_id: requiresVersion ? versionId : undefined,
          existing_device_id: registration.mode === 'MATCH_EXISTING' ? existingDeviceId : undefined,
          existing_device_revision: chosenCandidate?.revision,
          acceptance_note: acceptance.trim(),
          device_name: deviceName.trim() || undefined,
          address: address.trim() || undefined,
        }, key, signal), done)
      }}>
        {!commission.confirming ? <>
          <div className="field"><Label htmlFor="commission-tenant">单位</Label><select id="commission-tenant" className="native-select" required value={tenantId} onChange={event => { setTenantId(event.target.value); setPlaceId(''); changeDevicePage(1) }}><option value="">请选择单位</option>{units.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></div>
          <div className="field"><Label htmlFor="commission-place">位置</Label><select id="commission-place" className="native-select" required value={placeId} disabled={!tenantId} onChange={event => setPlaceId(event.target.value)}><option value="">请选择位置</option>{(unit?.places ?? []).map(place => <option key={place.id} value={place.id}>{placePath(place, unit?.places ?? [])}</option>)}</select></div>
          {imported
            ? <p className="muted-note">关联已导入旧台账资产：验收保留该设备导入时的固定产品版本，不能替换为其他正式产品版本。{chosenCandidate?.product_version_id ? ` 当前所选设备版本 ${chosenCandidate.product_version_id}。` : ''}</p>
            : nativeHikvision
              ? <p className="muted-note">海康云原生设备不要求本平台通用产品模型版本；保留海康型号与登记信息。</p>
              : <PublishedVersionPicker value={versionId} onChange={id => setVersionId(id)}/>}
          {registration.mode === 'MATCH_EXISTING' && <section aria-label="选择已有本地设备" className="form-stack">
            <div className="field"><Label htmlFor="commission-device-search">{imported ? '查找本单位导入设备（建议按登记EUI）' : '查找本单位已有设备'}</Label><div className="registration-device-search"><Input id="commission-device-search" value={deviceSearchDraft} onChange={event => setDeviceSearchDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); searchDevices() } }} placeholder="按设备名称、EUI或地址字面查询"/><Button type="button" variant="outline" onClick={searchDevices}>查询设备</Button></div><p className="muted-note">只查询本单位本地台账，不提交厂商登记。{imported ? '服务端仍按登记EUI与台账dId精确校验。' : '不会自动选择首个候选。'}</p></div>
            {candidates.loading ? <Loading/> : candidates.error != null ? <ErrorNotice error={candidates.error} retry={candidates.refresh}/> : <div className="field"><Label htmlFor="commission-device">已有本地设备（显式选择）</Label><select id="commission-device" className="native-select" required value={existingDeviceId} onChange={event => { setExistingDeviceId(event.target.value); commission.touch() }}><option value="">请选择</option>{candidateRows.map(item => <option key={item.id} value={item.id}>{item.name ?? item.device_eui ?? item.id} · {item.device_eui ?? item.id} · rev {item.revision}</option>)}</select></div>}
            {candidates.data && <div className="version-actions"><Button type="button" variant="outline" size="sm" disabled={candidates.loading || candidates.data.page <= 1} onClick={() => changeDevicePage(devicePage - 1)}>上一页</Button><span>第 {candidates.data.page} 页 · 共 {candidates.data.total} 条</span><Button type="button" variant="outline" size="sm" disabled={candidates.loading || candidates.data.page * candidates.data.size >= candidates.data.total} onClick={() => changeDevicePage(devicePage + 1)}>下一页</Button></div>}
          </section>}
          <div className="field"><Label htmlFor="commission-name">设备名称（可选覆盖）</Label><Input id="commission-name" maxLength={200} value={deviceName} onChange={event => setDeviceName(event.target.value)}/></div>
          <div className="field"><Label htmlFor="commission-address">安装地址（可选）</Label><Input id="commission-address" maxLength={500} value={address} onChange={event => setAddress(event.target.value)}/></div>
          <div className="field"><Label htmlFor="commission-acceptance">验收说明</Label><textarea id="commission-acceptance" required rows={3} maxLength={1000} value={acceptance} onChange={event => setAcceptance(event.target.value)}/></div>
          {commission.error != null && <ErrorNotice error={commission.error}/>}
          <Button type="submit" disabled={requiresVersion && !versionId}>核对并验收</Button>
        </> : <OperationConfirmation action={commission} title="二次确认 · 本地验收" submitLabel="确认验收" refresh={() => commission.setConfirming(false)} warning="验收只建立本地资产与来源关联；不会新增或删除厂商记录，也不会改变终端物理状态。">
          <p>单位/位置：{unit?.name ?? tenantId} / {selectedPlace ? placePath(selectedPlace, unit?.places ?? []) : placeId} · {requiresVersion ? `版本：${versionId}` : nativeHikvision ? '海康原生设备，不要求通用产品版本' : '保留导入资产固定版本'}</p>
          <p>{registration.mode === 'MATCH_EXISTING' ? `已有设备：${existingDeviceId}` : `新建设备：${deviceName || registration.device_name || registration.external_serial}`}</p><p>验收说明：{acceptance}</p>
        </OperationConfirmation>}
      </form>
    </CardContent></Card>}
    <h3>登记时间线</h3>
    <ol className="binding-history">{detail.events.map(item => <li key={item.id}><strong>{item.step} · {item.outcome}</strong><span>{item.occurred_at} · {item.actor_id}</span>{item.detail != null && <StructuredEvidence value={item.detail}/>}</li>)}</ol>
  </div>
}

function StructuredEvidence({ value, depth = 0 }: { value: unknown; depth?: number }) {
  if (value === null || value === undefined) return null
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return <span className="mono">{String(value)}</span>
  if (depth > 3) return <span className="muted-note">（更深层证据已折叠）</span>
  if (Array.isArray(value)) return <ol className="binding-history">{value.map((item, index) => <li key={index}><StructuredEvidence value={item} depth={depth + 1}/></li>)}</ol>
  if (typeof value === 'object') return <dl className="detail-list">{Object.entries(value as Record<string, unknown>).map(([key, item]) => <div key={key}><dt>{key}</dt><dd><StructuredEvidence value={item} depth={depth + 1}/></dd></div>)}</dl>
  return null
}

function ResumeRegistration({ registrationId, imported, onDone }: { registrationId: string; imported: boolean; onDone: () => void }) {
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => deviceRegistrationsApi.resume(registrationId, key, signal), onDone)
  }
  return <form className="inline-action" onSubmit={submit}>
    {!action.confirming ? <Button type="submit" variant="outline">{imported ? '远端只读核对并匹配导入台账' : '提交云端步骤'}</Button>
      : <OperationConfirmation action={action} title={imported ? '二次确认 · 远端只读核对与导入匹配' : '二次确认 · 云端步骤'} submitLabel="确认执行" refresh={() => action.setConfirming(false)} warning={imported ? '只读查询旧平台设备详情以核对EUI与台账ID，再与本地导入台账精确匹配；不新增、删除或修改任何厂商/NS资源，也不改变本地设备。' : '提交前请确认序列号与登记内容；未知结果只能核对，不能重复提交。'}><p>{imported ? '按登记EUI与台账dId核对远端详情并精确匹配本地导入资产，命中不唯一即拒绝。' : '仅提交一次厂商请求；崩溃或未知结果不会自动重发。'}</p></OperationConfirmation>}
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}

function ReconcileRegistration({ registrationId, imported, onDone }: { registrationId: string; imported: boolean; onDone: () => void }) {
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    void action.run((key, signal) => deviceRegistrationsApi.reconcile(registrationId, key, signal), onDone)
  }
  return <form className="inline-action" onSubmit={submit}>
    <Button type="submit" variant="outline" disabled={action.busy}>{action.busy ? '正在核对…' : imported ? '重新核对远端详情与导入台账' : '按序列号核对云端'}</Button>
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}

function AbandonRegistration({ registrationId, imported, onDone }: { registrationId: string; imported: boolean; onDone: () => void }) {
  const action = useMutationAction()
  const [reason, setReason] = useState('')
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('请填写放弃原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => deviceRegistrationsApi.abandon(registrationId, reason.trim(), key, signal), onDone)
  }
  return <form className="inline-action" onSubmit={submit}>
    {!action.confirming ? <Button type="submit" variant="ghost">放弃登记</Button>
      : <><Input aria-label="放弃原因" placeholder="放弃原因" maxLength={1000} value={reason} onChange={event => setReason(event.target.value)}/><OperationConfirmation action={action} title="二次确认 · 放弃登记" submitLabel="确认放弃" refresh={() => action.setConfirming(false)} warning={imported ? '放弃只关闭平台草稿；导入台账资产、厂商记录与终端状态均不受影响。' : '放弃只关闭平台草稿；若厂商记录已存在必须通过厂商台账删除流程处理。'}><p>{reason}</p></OperationConfirmation></>}
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}
