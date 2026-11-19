import { useEffect, useState } from 'react'
import { Alert, Card, Descriptions, Space, Table, Tag } from 'antd'
import { RefreshCw } from 'lucide-react'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { customerAssetsApi, customerRegistrationsApi, customerRegistrationAbandonMutation, customerRegistrationCommissionMutation, customerRegistrationCreateMutation, customerRegistrationReconcileMutation, customerRegistrationResumeMutation, type CustomerRegistrationDetail, type CustomerRegistrationSummary } from '@/lib/customer-assets'
import { buildLegacyVendorPayload, emptyLegacyVendorDraft, legacyVendorFamily, validateLegacyName, validateLegacySerial, type CreateRegistrationBody, type LegacyVendorDraft, type LegacyVendorFamily, type RegistrationMode } from '@/lib/device-registrations'
import type { Device, Paginated } from '@/lib/types'
import { placePath } from '@/lib/place-tree'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Empty, ErrorNotice, Loading } from './common'
import { CustomerWriteGate } from './customer-asset-write'
import { CustomerPublishedVersionPicker } from './customer-product-picker'
import { LegacyVendorFields } from './legacy-vendor-fields'

function modeLabelFor(family: LegacyVendorFamily | null): Record<RegistrationMode, string> {
  if (family) return { NEW_VENDOR: '旧台账新增（草稿，由平台提交）', MATCH_EXISTING: '关联本单位导入旧台账（远端只读核对+本地精确匹配）', PRODUCT_MODEL: '产品模型登记（平台维护）' }
  return { NEW_VENDOR: '厂商新增（官方add，草稿）', MATCH_EXISTING: '匹配本单位已关联来源（精确序列号）', PRODUCT_MODEL: '产品模型登记（平台维护）' }
}
const cloudLabel: Record<string, string> = { NOT_REQUIRED: '无云端步骤', NOT_ATTEMPTED: '尚未提交云端', IN_FLIGHT: '云端提交中/结果未确认', SUCCEEDED: '云端已接受（不代表激活/上线）', FAILED: '云端步骤失败', UNKNOWN: '云端结果未知（需核对）' }
const importedCloudLabel: Record<string, string> = { NOT_REQUIRED: '无云端步骤', NOT_ATTEMPTED: '尚未执行远端只读核对与本地导入匹配', IN_FLIGHT: '远端只读核对中', SUCCEEDED: '远端核对及本地导入匹配成功（未写入远端）', FAILED: '远端核对或本地匹配未通过', UNKNOWN: '核对结果未确认' }
const localLabel: Record<string, string> = { NOT_READY: '本地验收未就绪', READY: '可本地验收', SUCCEEDED: '本地验收完成', FAILED: '本地验收失败' }

/** Customer source registration: creates a real draft, never claims cloud acceptance or local commissioning. */
export function CustomerDeviceRegistrationWorkspace({ onCreated }: { onCreated?: () => void }) {
  const [registrationId, setRegistrationId] = useState<string | null>(null)
  const [page, setPage] = useState(1)
  const list = useResource(`customer-registrations:${page}`, signal => customerRegistrationsApi.list(page, 20, signal))
  if (registrationId) return <RegistrationDetailView registrationId={registrationId} onChanged={() => { list.refresh(); onCreated?.() }} back={() => { setRegistrationId(null); list.refresh() }}/>
  return <div className="review-body">
    <Alert type="info" showIcon title="来源登记是分阶段真实流程" description="创建只保存本单位草稿；厂商add接受不等于激活或接收入库，本地验收才建立设备资产。未关联的全局来源池不会向客户展示或匹配。"/>
    <RegistrationCreator onCreated={detail => { setRegistrationId(detail.registration.id); onCreated?.() }}/>
    <h3>本单位来源登记</h3>
    {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : list.data && <>
      <Table<CustomerRegistrationSummary> rowKey="id" size="small" dataSource={list.data.items} pagination={false} columns={[
        { title: '序列号', dataIndex: 'external_serial' }, { title: '方式', dataIndex: 'mode', render: (value: RegistrationMode, row: CustomerRegistrationSummary) => modeLabelFor(legacyVendorFamily(row.driver_key))[value] ?? value },
        { title: '云端', dataIndex: 'cloud_state', render: (value: string, row: CustomerRegistrationSummary) => { const labels = legacyVendorFamily(row.driver_key) && row.mode === 'MATCH_EXISTING' ? importedCloudLabel : cloudLabel; return <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'UNKNOWN' || value === 'FAILED' ? 'red' : 'default'}>{labels[value] ?? value}</Tag> } },
        { title: '本地', dataIndex: 'local_state', render: value => localLabel[value] ?? value },
        { title: '状态', dataIndex: 'status' }, { title: '版本', dataIndex: 'revision' },
        { title: '', render: (_: unknown, row: CustomerRegistrationSummary) => <Button type="button" variant="link" onClick={() => setRegistrationId(row.id)}>打开</Button> },
      ]}/>
      <div className="identity-form-actions"><Button type="button" variant="outline" disabled={page <= 1} onClick={() => setPage(value => value - 1)}>上一页</Button><span>第 {list.data.page} 页 · 共 {list.data.total} 条</span><Button type="button" variant="outline" disabled={(list.data.page * list.data.size) >= list.data.total} onClick={() => setPage(value => value + 1)}>下一页</Button><Button type="button" variant="ghost" onClick={list.refresh}><RefreshCw size={14}/>刷新</Button></div>
    </>}
  </div>
}

function RegistrationCreator({ onCreated }: { onCreated: (detail: CustomerRegistrationDetail) => void }) {
  const drivers = useResource('customer-registration-drivers', signal => customerRegistrationsApi.drivers(signal))
  const scope = useResource('customer-registration-places', signal => customerAssetsApi.scopeTree(signal))
  const canNewVendor = useSession(s => s.permissions.includes('device-registrations:write'))
  const [driverKey, setDriverKey] = useState('')
  const [mode, setMode] = useState<'NEW_VENDOR' | 'MATCH_EXISTING'>(canNewVendor ? 'NEW_VENDOR' : 'MATCH_EXISTING')
  useEffect(() => { if (!canNewVendor) setMode(previous => previous === 'NEW_VENDOR' ? 'MATCH_EXISTING' : previous) }, [canNewVendor])
  const [serial, setSerial] = useState(''), [name, setName] = useState(''), [model, setModel] = useState('')
  const [deviceType, setDeviceType] = useState(''), [communicationType, setCommunicationType] = useState('')
  const [vendorUnitId, setVendorUnitId] = useState(''), [verificationCode, setVerificationCode] = useState('')
  const [externalDeviceId, setExternalDeviceId] = useState('')
  const [legacy, setLegacy] = useState<LegacyVendorDraft | null>(null)
  const [location, setLocation] = useState(''), [latitude, setLatitude] = useState(''), [longitude, setLongitude] = useState('')
  const [placeId, setPlaceId] = useState('')
  const driverItems = drivers.data?.items ?? []
  const driver = driverItems.find(item => item.key === driverKey)
  const family = legacyVendorFamily(driverKey)
  const importedLedger = driver?.imported_ledger_association === true
  const unit = scope.data?.[0]
  const configured = driver?.configured === true && driver?.enabled === true
  const legacyPreview = mode === 'NEW_VENDOR' && legacy ? (() => {
    try { return Object.keys(buildLegacyVendorPayload(legacy)) } catch { return null }
  })() : null
  function chooseDriver(key: string) {
    setDriverKey(key)
    setExternalDeviceId('')
    setLegacy(emptyLegacyVendorDraft(key))
    if (legacyVendorFamily(key)) setMode('MATCH_EXISTING')
  }
  return <Card title="新建来源登记草稿">
    {drivers.loading ? <Loading/> : drivers.error != null ? <ErrorNotice error={drivers.error} retry={drivers.refresh}/> : driverItems.length ? <CustomerWriteGate store={customerRegistrationCreateMutation} title="创建来源登记" submitLabel="确认创建草稿" warning={mode === 'NEW_VENDOR' ? (family ? '创建只保存草稿；客户不能提交厂商add，需平台管理员核对后执行，厂商接受后仍需本地验收。' : '创建只保存草稿；需另行显式提交厂商add，厂商接受后仍需本地验收。') : importedLedger ? '先以当前来源账号只读核对旧平台详情的EUI与dId，再与本地导入台账精确匹配；不新增、删除或修改厂商记录。' : '匹配只接受本单位已关联的精确来源资源；不会查询或列出全局未分配池。'} summary={<><p>{modeLabelFor(family)[mode]} · {driverKey}</p><p>序列号：{serial || '未填写'} · 位置：{unit?.places.find(place => place.id === placeId)?.name ?? '未选择'}</p>{mode === 'MATCH_EXISTING' && importedLedger ? <p>旧台账ID(dId)：{externalDeviceId || '未填写'}</p> : null}{mode === 'NEW_VENDOR' && family ? <p>厂商字段：{legacyPreview?.length ? legacyPreview.join('、') : '尚未通过校验'}（值不回显，整体加密保存）</p> : null}</>} buildRequest={() => {
      if (!driverKey) throw new Error('请选择厂商接入驱动')
      if (!serial.trim()) throw new Error('请填写精确序列号/设备标识')
      if (!/^[\x21-\x7e]{1,200}$/.test(serial.trim())) throw new Error('序列号必须是1-200位安全ASCII，不能含空格或控制字符')
      let vendorPayload: Record<string, unknown> | undefined
      if (family) {
        const serialError = validateLegacySerial(family, serial)
        if (serialError) throw new Error(serialError)
      }
      if (!unit?.places.some(place => place.id === placeId)) throw new Error('请选择本单位真实位置')
      if (mode === 'NEW_VENDOR' && family) {
        if (!canNewVendor) throw new Error('当前账号没有独立的厂商新增登记权限')
        if (!configured) throw new Error('厂商接入未启用或凭证不可用，当前不能创建台账新增草稿')
        const nameError = validateLegacyName(family, name)
        if (nameError) throw new Error(nameError)
        if (!legacy) throw new Error('旧台账字段未初始化')
        try { vendorPayload = buildLegacyVendorPayload(legacy) } catch (failure) { throw failure instanceof Error ? failure : new Error('旧台账字段未通过校验') }
      }
      if (mode === 'NEW_VENDOR' && !family) {
        if (!canNewVendor) throw new Error('当前账号没有独立的厂商新增登记权限')
        if (!name.trim() || !model.trim() || !/^\d+$/.test(deviceType) || !/^\d+$/.test(communicationType) || !vendorUnitId.trim()) throw new Error('厂商新增必须填写名称、型号、数字设备类型、数字通信类型与厂商unitID')
        if (!configured) throw new Error('厂商接入未启用或凭证不可用，当前不能发起新增登记')
        if ((latitude === '') !== (longitude === '')) throw new Error('经纬度必须同时填写或同时留空')
        if (latitude !== '' && (!Number.isFinite(Number(latitude)) || !Number.isFinite(Number(longitude)))) throw new Error('经纬度必须是数值')
      }
      if (mode === 'MATCH_EXISTING' && importedLedger) {
        if (!externalDeviceId.trim()) throw new Error('关联已导入旧台账必须填写旧平台台账ID(dId)')
        if (!/^[\x21-\x7e]{1,128}$/.test(externalDeviceId.trim())) throw new Error('旧平台台账ID必须是1-128位安全ASCII')
      }
      const body: CreateRegistrationBody = { mode, driver_key: driverKey, external_serial: serial.trim(), place_id: placeId }
      if (mode === 'NEW_VENDOR') {
        body.device_name = name.trim()
        if (family) {
          body.vendor_payload = vendorPayload
        } else {
          body.device_model = model.trim(); body.device_type = Number(deviceType); body.communication_type = Number(communicationType); body.vendor_unit_id = vendorUnitId.trim()
          if (verificationCode.trim()) body.verification_code = verificationCode.trim()
          if (location.trim()) body.location = location.trim()
          if (latitude !== '') { body.latitude = Number(latitude); body.longitude = Number(longitude) }
        }
      }
      if (mode === 'MATCH_EXISTING' && importedLedger) body.external_device_id = externalDeviceId.trim()
      return { path: '/device-registrations', method: 'POST', body: { ...body }, display: `创建来源登记 ${serial.trim()}` }
    }} onSaved={onCreated}>
      <div className="field"><Label htmlFor="customer-registration-driver">厂商接入驱动</Label><select id="customer-registration-driver" className="native-select" required value={driverKey} onChange={event => chooseDriver(event.target.value)}><option value="">请选择已配置的厂商驱动</option>{driverItems.map(item => <option key={item.key} value={item.key}>{item.display_name} · {item.configured && item.enabled ? '已启用' : '未就绪'}{item.imported_ledger_association ? ' · 导入台账' : ''}</option>)}</select>
        {driver && family && <p className="muted-note">该驱动为旧台账接入：可保存旧台账新增草稿（由平台管理员提交厂商add），或关联本单位已导入的精确资产（EUI+dId，只读）。当前来源 {configured ? '已启用' : '未就绪'}。</p>}
      </div>
      <div className="field"><Label htmlFor="customer-registration-mode">登记方式</Label><select id="customer-registration-mode" className="native-select" value={mode} onChange={event => setMode(event.target.value as 'NEW_VENDOR' | 'MATCH_EXISTING')}>
        <option value="NEW_VENDOR" disabled={!canNewVendor}>{family ? '旧台账新增草稿（由平台提交）' : '厂商新增（官方add）'}{!canNewVendor ? ' · 需独立授权' : ''}</option>
        <option value="MATCH_EXISTING">匹配本单位已关联来源</option>
      </select>{!canNewVendor && <p className="muted-note">当前账号没有独立的厂商新增登记权限；可匹配本单位已关联来源，或落户普通设备开户。</p>}{family && <p className="muted-note">旧台账新增只保存草稿：客户不能自行提交厂商add，由平台管理员核对后提交；LoRa凭据只写不显示。</p>}</div>
      <div className="field"><Label htmlFor="customer-registration-serial">{importedLedger && mode === 'MATCH_EXISTING' ? '精确设备编号/EUI（与导入台账一致）' : '精确序列号 / 设备标识'}</Label><Input id="customer-registration-serial" required maxLength={200} value={serial} onChange={event => setSerial(event.target.value)}/><p className="muted-note">只做精确匹配；不做模糊或取第一行自动绑定。</p></div>
      {mode === 'MATCH_EXISTING' && importedLedger && <div className="field"><Label htmlFor="customer-registration-ledger-id">旧平台台账ID(dId)</Label><Input id="customer-registration-ledger-id" required maxLength={128} value={externalDeviceId} onChange={event => setExternalDeviceId(event.target.value)}/><p className="muted-note">服务端按本单位、精确EUI与台账dId重新校验；命中不唯一或不在授权范围时拒绝。</p></div>}
      {mode === 'NEW_VENDOR' && !family && <>
        <div className="field"><Label htmlFor="customer-registration-name">设备名称（厂商）</Label><Input id="customer-registration-name" required maxLength={200} value={name} onChange={event => setName(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-registration-model">设备型号（厂商）</Label><Input id="customer-registration-model" required maxLength={100} value={model} onChange={event => setModel(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-registration-type">设备类型码</Label><Input id="customer-registration-type" required inputMode="numeric" value={deviceType} onChange={event => setDeviceType(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-registration-comm">通信类型码</Label><Input id="customer-registration-comm" required inputMode="numeric" value={communicationType} onChange={event => setCommunicationType(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-registration-unit">厂商unitID（本单位归属材料）</Label><Input id="customer-registration-unit" required maxLength={64} value={vendorUnitId} onChange={event => setVendorUnitId(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-registration-code">验证码（可选，服务端加密保存）</Label><Input id="customer-registration-code" type="password" autoComplete="off" maxLength={200} value={verificationCode} onChange={event => setVerificationCode(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-registration-location">厂商位置描述（可选）</Label><Input id="customer-registration-location" maxLength={500} value={location} onChange={event => setLocation(event.target.value)}/></div>
        <div className="field"><Label>厂商经纬度（可选，成对）</Label><div className="inline-fields"><Input aria-label="纬度" inputMode="decimal" value={latitude} onChange={event => setLatitude(event.target.value)}/><Input aria-label="经度" inputMode="decimal" value={longitude} onChange={event => setLongitude(event.target.value)}/></div></div>
      </>}
      {mode === 'NEW_VENDOR' && family && <>
        <div className="field"><Label htmlFor="customer-registration-name">{family === 'LEGACY_TSF' ? '设备名称（厂商dName，1-20字符）' : '设备名称（厂商）'}</Label><Input id="customer-registration-name" required maxLength={family === 'LEGACY_TSF' ? 20 : 200} value={name} onChange={event => setName(event.target.value)}/></div>
        {legacy && <LegacyVendorFields draft={legacy} onChange={setLegacy}/>}
        <Alert type="info" showIcon title="旧台账新增只保存草稿" description="客户不能自行提交厂商add；平台管理员核对台账字段后执行。LoRa凭据只写不显示，服务端整体加密保存；客户详情只显示保存状态，不回显字段名或值。"/>
      </>}
      {mode === 'MATCH_EXISTING' && !importedLedger && <Alert type="info" showIcon title="只匹配本单位已关联来源" description="系统按精确序列号查找已由本单位本地设备关联的云端资源；未关联、其他单位或匹配不唯一都会拒绝。"/>}
      {mode === 'MATCH_EXISTING' && importedLedger && <Alert type="info" showIcon title="只匹配本单位已导入的旧台账资产" description="系统先以当前来源账号只读核对旧平台详情的EUI与台账dId，再在本平台导入台账中精确匹配；不会新增、删除或修改厂商记录。"/>}
      <div className="field"><Label htmlFor="customer-registration-place">本单位位置（必选）</Label><select id="customer-registration-place" className="native-select" required value={placeId} onChange={event => setPlaceId(event.target.value)}><option value="">请选择位置</option>{(unit?.places ?? []).map(place => <option key={place.id} value={place.id}>{placePath(place, unit?.places ?? [])}</option>)}</select>{scope.error != null && <ErrorNotice error={scope.error} retry={scope.refresh}/>}</div>
    </CustomerWriteGate> : <Empty title="当前没有可用的厂商接入驱动">平台尚未配置厂商平台驱动或凭证；不会伪造可登记状态。请联系平台管理员启用真实接入。</Empty>}
  </Card>
}

function RegistrationDetailView({ registrationId, onChanged, back }: { registrationId: string; onChanged?: () => void; back: () => void }) {
  const detail = useResource(`customer-registration:${registrationId}`, signal => customerRegistrationsApi.detail(registrationId, signal))
  if (detail.loading) return <Loading/>
  if (detail.error != null) return <ErrorNotice error={detail.error} retry={detail.refresh}/>
  if (!detail.data) return <Empty title="登记详情未确认"/>
  return <RegistrationWorkspace detail={detail.data} refresh={detail.refresh} onChanged={onChanged} back={back}/>
}

function RegistrationWorkspace({ detail, refresh, onChanged, back }: { detail: CustomerRegistrationDetail; refresh: () => void; onChanged?: () => void; back: () => void }) {
  const registration = detail.registration
  const { user } = useSession()
  const tenant = user?.tenant_id ?? ''
  const family = legacyVendorFamily(registration.driver_key)
  const imported = family !== null && registration.mode === 'MATCH_EXISTING'
  const legacyNewVendor = family !== null && registration.mode === 'NEW_VENDOR'
  const modeLabel = modeLabelFor(family)
  const cloudLabels = imported ? importedCloudLabel : cloudLabel
  const done = () => { refresh(); onChanged?.() }
  const cloudDone = registration.cloud_state === 'SUCCEEDED'
  const canResume = !legacyNewVendor && registration.mode !== 'PRODUCT_MODEL' && !cloudDone && registration.cloud_state !== 'IN_FLIGHT' && registration.status !== 'COMMISSIONED' && registration.status !== 'ABANDONED'
  const canReconcile = registration.mode !== 'PRODUCT_MODEL' && registration.status !== 'ABANDONED' && (registration.cloud_state === 'IN_FLIGHT' || registration.cloud_state === 'UNKNOWN' || (imported && cloudDone && registration.status !== 'COMMISSIONED'))
  const canAbandon = registration.mode !== 'PRODUCT_MODEL' && registration.status !== 'COMMISSIONED' && registration.status !== 'ABANDONED' && (imported || !cloudDone)
  return <div className="review-body">
    <div className="identity-form-actions"><Button type="button" variant="ghost" onClick={back}>返回登记列表</Button><Button type="button" variant="outline" onClick={refresh}><RefreshCw size={14}/>刷新</Button></div>
    <Descriptions column={{ xs: 1, sm: 2 }} size="small" bordered items={[
      { key: 'mode', label: '登记方式', children: modeLabel[registration.mode] ?? registration.mode },
      { key: 'serial', label: '序列号', children: <span className="mono">{registration.external_serial}</span> },
      { key: 'cloud', label: imported ? '远端核对与导入匹配' : '云端状态', children: <Tag color={cloudDone ? 'green' : registration.cloud_state === 'UNKNOWN' || registration.cloud_state === 'FAILED' ? 'red' : 'default'}>{cloudLabels[registration.cloud_state] ?? registration.cloud_state}</Tag> },
      { key: 'local', label: '本地状态', children: localLabel[registration.local_state] ?? registration.local_state },
      { key: 'status', label: '登记状态 / 版本', children: `${registration.status} · revision ${registration.revision}` },
      { key: 'place', label: '单位 / 位置', children: `${tenant || '未确定'} / ${registration.place_id ?? '未确定'}` },
      { key: 'version', label: '固定版本', children: registration.product_version_id ?? (imported ? '验收时保留导入资产版本' : '验收时确定') },
      { key: 'payload', label: '厂商字段', children: detail.vendor_payload_stored ? '已整体加密保存（不回显值）' : '未保存' },
    ]}/>
    {legacyNewVendor && !cloudDone && registration.status !== 'COMMISSIONED' && registration.status !== 'ABANDONED' && <Alert type="info" showIcon title="旧台账新增草稿待平台提交" description="客户不能自行提交厂商add；平台管理员核对台账字段后执行，厂商接受后仍需本地验收。草稿阶段可由客户放弃。"/>}
    <Space wrap style={{ marginBlock: 12 }}>
      {canResume && <ResumeAction registrationId={registration.id} imported={imported} onDone={done}/>}
      {canReconcile && <ReconcileAction registrationId={registration.id} imported={imported} onDone={done}/>}
      {canAbandon && <AbandonAction registrationId={registration.id} imported={imported} onDone={done}/>}
    </Space>
    {registration.mode !== 'PRODUCT_MODEL' && cloudDone && registration.status !== 'COMMISSIONED' && registration.status !== 'ABANDONED' && <CommissionAction detail={detail} imported={imported} onDone={done}/>}
    <h3>登记时间线</h3>
    <ol className="binding-history">{detail.events.map((item, index) => <li key={`${item.step}:${item.occurred_at}:${index}`}><strong>{item.step} · {item.outcome}</strong><span>{item.occurred_at}</span></li>)}</ol>
  </div>
}

function ResumeAction({ registrationId, imported, onDone }: { registrationId: string; imported: boolean; onDone: () => void }) {
  return <CustomerWriteGate store={customerRegistrationResumeMutation} title={imported ? '远端只读核对并匹配导入台账' : '提交云端步骤'} submitLabel={imported ? '确认只读核对' : '确认提交一次'} warning={imported ? '只读查询旧平台设备详情并核对EUI/台账ID，再与本地导入台账精确匹配；不新增、删除或修改任何厂商/NS资源。' : '只在核对序列号与登记内容后提交一次；未知结果只能核对，不能重复提交。'} summary={<p>{imported ? '按登记EUI与台账dId执行远端只读核对与本地导入匹配；命中不唯一即拒绝。' : '提交厂商请求；创建不代表云端已接受。'}</p>} buildRequest={() => ({ path: `/device-registrations/${encodeURIComponent(registrationId)}/resume`, method: 'POST', body: {}, display: `${imported ? '核对远端并匹配导入台账' : '提交厂商云端步骤'} ${registrationId}` })} onSaved={onDone}><div/></CustomerWriteGate>
}
function ReconcileAction({ registrationId, imported, onDone }: { registrationId: string; imported: boolean; onDone: () => void }) {
  return <CustomerWriteGate store={customerRegistrationReconcileMutation} title={imported ? '远端只读核对与导入匹配' : '按序列号核对云端'} submitLabel="确认只读核对" warning={imported ? '只读查询旧平台设备详情并核对EUI/台账ID，再与本地导入台账匹配；不会新增、删除或修改厂商记录。' : '核对为只读查询，不会创建或删除云端记录。'} summary={<p>{imported ? '按登记EUI与台账dId执行远端只读核对与本地导入匹配。' : '按精确序列号查询；不做模糊匹配。'}</p>} buildRequest={() => ({ path: `/device-registrations/${encodeURIComponent(registrationId)}/reconcile`, method: 'POST', body: {}, display: `${imported ? '核对远端与导入台账' : '核对厂商云端记录'} ${registrationId}` })} onSaved={onDone}><div/></CustomerWriteGate>
}
function AbandonAction({ registrationId, imported, onDone }: { registrationId: string; imported: boolean; onDone: () => void }) {
  const [reason, setReason] = useState('')
  return <CustomerWriteGate store={customerRegistrationAbandonMutation} title="放弃登记草稿" submitLabel="确认放弃" warning={imported ? '放弃只关闭平台草稿；导入台账资产、厂商记录与终端状态均不受影响。' : '放弃只关闭平台草稿；若厂商记录已存在必须由平台按厂商台账删除流程处理。'} summary={<p>{reason.trim() || '未填写原因'}</p>} buildRequest={() => { if (!reason.trim()) throw new Error('请填写放弃原因'); return { path: `/device-registrations/${encodeURIComponent(registrationId)}/abandon`, method: 'POST', body: { reason: reason.trim() }, display: `放弃来源登记草稿 ${registrationId}` } }} onSaved={onDone}><div className="field"><Label htmlFor="customer-registration-abandon">放弃原因</Label><Input id="customer-registration-abandon" maxLength={1000} value={reason} onChange={event => setReason(event.target.value)}/></div></CustomerWriteGate>
}

function CommissionAction({ detail, imported, onDone }: { detail: CustomerRegistrationDetail; imported: boolean; onDone: () => void }) {
  const registration = detail.registration
  const tenant = useSession(s => s.user?.tenant_id ?? '')
  const scope = useResource('customer-commission-places', signal => customerAssetsApi.scopeTree(signal))
  const [placeId, setPlaceId] = useState(registration.place_id ?? ''), [versionId, setVersionId] = useState(registration.product_version_id ?? '')
  const [acceptance, setAcceptance] = useState(''), [deviceName, setDeviceName] = useState(registration.device_name ?? ''), [address, setAddress] = useState(detail.location ?? '')
  const [existingId, setExistingId] = useState(''), [searchDraft, setSearchDraft] = useState(registration.external_serial)
  const [search, setSearch] = useState(registration.external_serial), [devicePage, setDevicePage] = useState(1)
  const candidates = useResource<Paginated<Device>>(`customer-commission-candidates:${devicePage}:${search}`, signal => api.devices(devicePage, search, 20, signal), registration.mode === 'MATCH_EXISTING')
  const candidateRows = (candidates.data?.items ?? []).filter(device => device.tenant_id === tenant)
  const chosen = candidateRows.find(device => device.id === existingId)
  function changeDevicePage(page: number) { setDevicePage(page); setExistingId('') }
  function searchDevices() { setSearch(searchDraft.trim()); changeDevicePage(1) }
  const unit = scope.data?.[0]
  const selectedPlace = unit?.places.find(place => place.id === placeId)
  const requiresVersion = !imported
  return <Card size="small" title="本地验收（建立本地资产，不新增厂商记录）">
    <CustomerWriteGate key={`${devicePage}:${search}:${existingId}`} store={customerRegistrationCommissionMutation} title="本地验收" submitLabel="确认验收" warning="验收只建立本地资产与来源关联；不会改变终端物理状态或补造历史事实。" summary={<><p>位置：{selectedPlace ? placePath(selectedPlace, unit?.places ?? []) : '未选择'} · {requiresVersion ? `版本：${versionId || '未选择'}` : '保留导入资产固定产品版本'}</p><p>{registration.mode === 'MATCH_EXISTING' ? `已有设备：${chosen?.name || existingId || '未选择'}` : `新建设备：${deviceName || registration.external_serial}`}</p><p>验收说明：{acceptance || '未填写'}</p></>} buildRequest={() => {
      if (!placeId || !unit?.places.some(place => place.id === placeId) || !acceptance.trim()) throw new Error('必须选择本单位位置并填写验收说明')
      if (requiresVersion && !versionId) throw new Error('必须选择已发布版本')
      if (registration.mode === 'MATCH_EXISTING' && (candidates.loading || candidates.error != null || !chosen || !Number.isSafeInteger(chosen.revision))) throw new Error('请在当前查询结果中明确选择版本有效的本单位已有设备')
      return { path: `/device-registrations/${encodeURIComponent(registration.id)}/commission`, method: 'POST', body: { tenant_id: tenant || unit?.id, place_id: placeId, product_version_id: requiresVersion ? versionId : undefined, existing_device_id: registration.mode === 'MATCH_EXISTING' ? existingId : undefined, existing_device_revision: chosen?.revision, acceptance_note: acceptance.trim(), device_name: deviceName.trim() || undefined, address: address.trim() || undefined }, display: `本地验收 ${registration.id} · ${registration.external_serial}` }
    }} onSaved={onDone}>
      <div className="field"><Label htmlFor="customer-commission-place">本单位位置</Label><select id="customer-commission-place" className="native-select" required value={placeId} onChange={event => setPlaceId(event.target.value)}><option value="">请选择位置</option>{(unit?.places ?? []).map(place => <option key={place.id} value={place.id}>{placePath(place, unit?.places ?? [])}</option>)}</select></div>
      {requiresVersion
        ? <CustomerPublishedVersionPicker value={versionId} onChange={option => setVersionId(option?.id ?? '')}/>
        : <p className="muted-note">关联已导入旧台账资产：验收保留该设备导入时的固定产品版本，不能替换为其他正式产品版本。服务端要求该登记已有远端只读核对标记；若验收被拒绝，请先执行远端核对与导入匹配。{chosen?.product_version_id ? ` 当前所选设备版本 ${chosen.product_version_id}。` : ''}</p>}
      {registration.mode === 'MATCH_EXISTING' && <section aria-label="选择已有本地设备" className="form-stack">
        <div className="field"><Label htmlFor="customer-commission-search">{imported ? '查找本单位导入设备（建议按登记EUI）' : '查找本单位已有设备'}</Label><div className="registration-device-search"><Input id="customer-commission-search" value={searchDraft} onChange={event => setSearchDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); searchDevices() } }} placeholder="按设备名称、EUI或地址字面查询"/><Button type="button" variant="outline" onClick={searchDevices}>查询设备</Button></div><p className="muted-note">只查询本单位本地台账，不提交厂商登记。{imported ? '服务端仍按登记EUI与台账dId精确校验。' : '不会自动选择首个候选。'}</p></div>
        {candidates.loading ? <Loading/> : candidates.error != null ? <ErrorNotice error={candidates.error} retry={candidates.refresh}/> : <div className="field"><Label htmlFor="customer-commission-device">已有设备（显式选择）</Label><select id="customer-commission-device" className="native-select" value={existingId} onChange={event => setExistingId(event.target.value)}><option value="">请选择</option>{candidateRows.map(device => <option key={device.id} value={device.id}>{device.name ?? device.device_eui ?? device.id} · {device.device_eui ?? device.id} · rev {device.revision}</option>)}</select></div>}
        {candidates.data && <div className="version-actions"><Button type="button" variant="outline" size="sm" disabled={candidates.loading || candidates.data.page <= 1} onClick={() => changeDevicePage(devicePage - 1)}>上一页</Button><span>第 {candidates.data.page} 页 · 共 {candidates.data.total} 条</span><Button type="button" variant="outline" size="sm" disabled={candidates.loading || candidates.data.page * candidates.data.size >= candidates.data.total} onClick={() => changeDevicePage(devicePage + 1)}>下一页</Button></div>}
      </section>}
      <div className="field"><Label htmlFor="customer-commission-name">设备名称（可选覆盖）</Label><Input id="customer-commission-name" maxLength={200} value={deviceName} onChange={event => setDeviceName(event.target.value)}/></div>
      <div className="field"><Label htmlFor="customer-commission-address">安装地址（可选）</Label><Input id="customer-commission-address" maxLength={500} value={address} onChange={event => setAddress(event.target.value)}/></div>
      <div className="field"><Label htmlFor="customer-commission-acceptance">验收说明</Label><textarea id="customer-commission-acceptance" rows={3} maxLength={1000} value={acceptance} onChange={event => setAcceptance(event.target.value)}/></div>
    </CustomerWriteGate>
  </Card>
}
