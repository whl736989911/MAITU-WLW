import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { scopeTargetTenantId } from '@/lib/operation-target'
import { battery, dateTime, reported } from '@/lib/format'
import type { Device, ProductVersion } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Empty, ErrorNotice, Loading, Pager, RefreshButton, Status } from './common'
import { OperationConfirmation, useMutationAction } from './identity-common'
import { PublishedVersionPicker } from './product-common'
import { CustomerWriteGate } from './customer-asset-write'
import { CustomerPublishedVersionPicker } from './customer-product-picker'
import { customerAssetsApi, customerDeviceCreateMutation, customerDeviceUpdateMutation, type CustomerPublishedOption } from '@/lib/customer-assets'
import { placePath } from '@/lib/place-tree'
export type DeviceEditorState =
  | { mode: 'create'; device: null }
  | { mode: 'edit'; device: Device }
  | { mode: 'migrate'; device: Device }
  | { mode: 'history'; device: Device }
  | { mode: 'place-history'; device: Device }
export function DeviceEditor({ editor, onSaved, onRefresh }: { editor: DeviceEditorState; onSaved: () => void; onRefresh: () => void }) {
  const audience = useSession(s => s.audience)
  if (audience === 'customer') return <CustomerDeviceEditor editor={editor} onSaved={onSaved}/>
  if (editor.mode === 'history') return <DeviceHistory device={editor.device}/>
  if (editor.mode === 'place-history') return <DevicePlaceHistory device={editor.device}/>
  return <DeviceForm editor={editor} onSaved={onSaved} onRefresh={onRefresh}/>
}
function DeviceHistory({ device }: { device: Device }) {
  const history = useResource(`device-history:${device.tenant_id ?? 'none'}:${device.id}`, signal => api.versionHistory(device.id, signal, device.tenant_id))
  return <><SheetHeader className="review-header"><SheetTitle>设备版本绑定历史</SheetTitle><SheetDescription>只展示当前租户服务端历史，不为旧演示设备虚构记录。迁移不重写旧报警。</SheetDescription></SheetHeader><div className="review-body"><h3>{reported(device.name)}</h3><div className="identity-readonly mono">{device.id} · 当前绑定 {device.product_version_id ?? '未绑定模板'}</div>{history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data?.items.length ? <ol className="binding-history">{history.data.items.map(item => <li key={item.id}><strong>{item.reason === 'REGISTER' ? '正式开户绑定' : '显式版本迁移'}</strong><div className="mono">{item.from_version_id ?? '无原绑定'} → {item.to_version_id}</div><p>{item.reason}</p><span>操作人 {item.actor_id} · {dateTime(item.occurred_at)}</span></li>)}</ol> : <Empty title="暂无绑定历史">旧未绑定演示设备不代表已有正式版本。</Empty>}</div></>
}
function DevicePlaceHistory({ device }: { device: Device }) {
  const [page, setPage] = useState(1)
  const history = useResource(`device-place-history:${device.tenant_id ?? 'none'}:${device.id}:${page}`, signal => api.placeHistory(device.id, page, signal, device.tenant_id))
  return <>
    <SheetHeader className="review-header"><SheetTitle>设备位置变更历史</SheetTitle><SheetDescription>仅记录本功能启用后的开户和同单位位置移动，不补造历史归属。位置名称为操作当时快照。</SheetDescription></SheetHeader>
    <div className="review-body">
      <h3>{reported(device.name)}</h3><div className="identity-readonly mono">{device.id}</div>
      <RefreshButton onClick={history.refresh}/>
      {history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data && <>
        {history.data.items.length ? <ol className="binding-history">{history.data.items.map(item => <li key={item.id}>
          <strong>{item.action === 'REGISTER' ? '开户初始位置' : '同单位位置移动'}</strong>
          <div>{item.from_place_name ?? (item.action === 'REGISTER' ? '新设备' : '原位置未记录')} → {item.to_place_name}</div>
          <div className="mono">{item.from_place_id ?? '—'} → {item.to_place_id}</div>
          <p>设备 revision {item.revision_before ?? '—'} → {item.revision_after}</p>
          <span>操作人 {item.actor_id} · {dateTime(item.occurred_at)}</span>
        </li>)}</ol> : <Empty title="暂无位置变更记录">历史设备的当前位置不代表已知过去归属；只有实际开户或位置改变才新增记录。</Empty>}
        <Pager page={history.data.page} size={history.data.size} total={history.data.total} onChange={setPage}/>
      </>}
    </div>
  </>
}
function DeviceForm({ editor, onSaved, onRefresh }: { editor: Exclude<DeviceEditorState, { mode: 'history' | 'place-history' }>; onSaved: () => void; onRefresh: () => void }) {
  const device = editor.device
  const [name, setName] = useState(device?.name ?? ''), [address, setAddress] = useState(device?.address ?? ''), [eui, setEui] = useState('')
  const [versionId, setVersionId] = useState(''), [chosenVersion, setChosenVersion] = useState<ProductVersion | null>(null), [placeId, setPlaceId] = useState(device?.place_id ?? '')
  const [systemType, setSystemType] = useState(device?.system_type ?? '')
  const [deviceCategory, setDeviceCategory] = useState(device?.device_category ?? '')
  const [createTenantId, setCreateTenantId] = useState('')
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds)
  const initialScope = useRef({ tenantIds: [...scopeTenantIds], placeIds: [...scopePlaceIds] })
  const targetPrefilled = useRef(false)
  const ownerTenantId = editor.mode === 'create' ? createTenantId || undefined : device?.tenant_id
  const createScope = useResource('device-place-scope-tree', signal => api.scopeTree(signal), editor.mode !== 'migrate')
  useEffect(() => {
    if (editor.mode !== 'create' || targetPrefilled.current || !createScope.data) return
    targetPrefilled.current = true
    setCreateTenantId(scopeTargetTenantId(createScope.data, initialScope.current.tenantIds, initialScope.current.placeIds) ?? '')
  }, [createScope.data, editor.mode])
  const unit = createScope.data?.find(item => item.id === ownerTenantId)
  const selectedPlace = unit?.places.find(place => place.id === placeId)
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  const title = editor.mode === 'create' ? '正式设备开户' : editor.mode === 'edit' ? '编辑设备元数据' : '显式迁移产品版本'
  const showPlace = editor.mode === 'create' || editor.mode === 'edit'
  function submit(event: FormEvent) {
    event.preventDefault()
    if (editor.mode === 'create' && !ownerTenantId) { action.setError(new Error('请选择设备开户归属单位')); return }
    if (editor.mode !== 'migrate' && !name.trim()) { action.setError(new Error('请填写设备名称')); return }
    if (editor.mode === 'create' && !placeId) { action.setError(new Error('请先选择设备所属位置；设备不能没有位置')); return }
    if (editor.mode === 'edit' && !placeId) { action.setError(new Error('请选择设备所属位置；设备不能没有位置')); return }
    if (editor.mode === 'create' && !/^[\x21-\x7e]{1,64}$/.test(eui)) { action.setError(new Error('设备标识需为1–64位安全ASCII，不假设所有品牌为16位十六进制')); return }
    if (editor.mode !== 'edit' && (!chosenVersion || chosenVersion.status !== 'PUBLISHED' || chosenVersion.id !== versionId)) { action.setError(new Error('必须显式选择一个具体已发布版本，不能使用草稿或默认最新版本')); return }
    if (editor.mode === 'migrate' && !device?.place_id) { action.setError(new Error('设备尚未归属真实位置；请先在元数据编辑中显式选择位置，再进行版本迁移')); return }
    if (editor.mode === 'migrate' && (!reason.trim() || versionId === device?.product_version_id || (device?.product_id && chosenVersion?.product_id !== device.product_id))) { action.setError(new Error('迁移需填写原因、选择同产品的不同已发布版本')); return }
    if (device && !Number.isSafeInteger(device.revision)) { action.setError(new Error('当前设备未提供有效revision，请刷新后重新打开')); return }
    if (editor.mode === 'migrate' && !action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    void action.run((key, signal) => {
      if (editor.mode === 'create') return api.createDevice({ name: name.trim(), address: address.trim(), place_id: placeId, device_eui: eui, product_version_id: versionId, system_type: systemType.trim() || null, device_category: deviceCategory.trim() || null }, key, signal, ownerTenantId)
      if (editor.mode === 'edit') return api.updateDevice(device!.id, { name: name.trim(), address: address.trim(), place_id: placeId, system_type: systemType.trim(), device_category: deviceCategory.trim(), expected_revision: device!.revision! }, key, signal, ownerTenantId)
      return api.migrateDevice(device!.id, { product_version_id: versionId, expected_revision: device!.revision!, reason: reason.trim() }, key, signal, ownerTenantId)
    }, onSaved)
  }
  return <>
    <SheetHeader className="review-header">
      <div className="eyebrow">DEVICE · 显式操作</div><SheetTitle>{title}</SheetTitle>
      <SheetDescription>{editor.mode === 'create' ? '开户不是一次上报。新设备为UNKNOWN / 未激活 / 待接入联调，不生成报警；系统与类别仅由操作者登记。' : editor.mode === 'migrate' ? '只更新固定版本绑定和revision，绝不改物理状态、上报时间、电量、旧事件或报警。' : '编辑名称、地址、所属位置及手工登记的所属系统/设备类别；旧报警和已记录的位置快照不改写。'}</SheetDescription>
    </SheetHeader>
    <div className="review-body"><form className="form-stack" onSubmit={submit}>
      {action.confirming && editor.mode === 'migrate' ? <OperationConfirmation action={action} title={`二次确认 · ${title}`} submitLabel={`确认${title}`} refresh={onRefresh} warning="显式迁移只变更绑定，不改变设备物理状态或旧事件/报警；不会批量切换到最新版本。">
        <p>设备：{device?.id ?? eui} · {name}</p>
        <p>具体目标版本：v{chosenVersion?.version_number} · {versionId}</p>
        {device && <p>expected_revision：{device.revision}</p>}
        <p>原因：{reason}</p>
      </OperationConfirmation> : <>
        {device && <><div className="identity-readonly mono">{device.id} · EUI {reported(device.device_eui)} · revision {device.revision ?? '未上报'}<br/>版本 {device.product_version_id ?? '未绑定模板'}</div><div className="device-snapshot"><Status value={device.physical_state} dimension="physical"/><span>电量 {battery(device.battery_percent, device.battery_percent_json)} · 最后上报 {dateTime(device.last_message_at)}</span></div></>}
        {editor.mode !== 'migrate' && <>
          {editor.mode === 'create' && <div className="field"><Label htmlFor="device-owner-tenant">开户归属单位（必选）</Label><select id="device-owner-tenant" className="native-select" required value={createTenantId} disabled={createScope.loading} onChange={event => { setCreateTenantId(event.target.value); setPlaceId(''); action.touch() }}><option value="">{createScope.loading ? '读取授权单位…' : '请选择单位'}</option>{createScope.data?.map(item => <option key={item.id} value={item.id}>{item.name} · {item.id}</option>)}</select>{createScope.error != null && <ErrorNotice error={createScope.error} retry={createScope.refresh}/>}</div>}
          <div className="field"><Label htmlFor="device-name">设备名称</Label><Input id="device-name" required value={name} onChange={e => { setName(e.target.value); action.touch() }}/></div>
          <div className="field"><Label htmlFor="device-address">安装地址</Label><Input id="device-address" value={address} onChange={e => { setAddress(e.target.value); action.touch() }}/></div>
          <div className="field"><Label htmlFor="device-system-type">所属系统（人工登记）</Label><Input id="device-system-type" maxLength={80} value={systemType} onChange={e => { setSystemType(e.target.value); action.touch() }} placeholder="未指定时留空"/><p className="muted-note">手工登记，不根据型号或历史报警推断；留空表示未指定。</p></div>
          <div className="field"><Label htmlFor="device-category">设备类别（人工登记）</Label><Input id="device-category" maxLength={80} value={deviceCategory} onChange={e => { setDeviceCategory(e.target.value); action.touch() }} placeholder="未指定时留空"/></div>
          {showPlace && <div className="field"><Label htmlFor="device-place">所属位置（必选）</Label><select id="device-place" className="native-select" required value={placeId} disabled={!ownerTenantId || createScope.loading || createScope.error != null} onChange={e => { setPlaceId(e.target.value); action.touch() }}><option value="">{!ownerTenantId ? (editor.mode === 'create' ? '请先明确选择开户单位' : '设备归属单位未确认') : createScope.loading ? '读取位置…' : '请选择位置'}</option>{(unit?.places ?? []).map(place => <option key={place.id} value={place.id}>{placePath(place, unit?.places ?? [])}</option>)}</select>{createScope.error != null && <ErrorNotice error={createScope.error} retry={createScope.refresh}></ErrorNotice>}{ownerTenantId && !createScope.loading && createScope.error == null && !(unit?.places?.length) && <p className="muted-note">设备所属单位还没有可用位置，请先在「单位位置」创建位置。</p>}{device && !device.place_id && <p className="notice">历史设备尚未归属位置。请选择真实位置完成修复；不自动指定占位位置。</p>}</div>}
        </>}
        {editor.mode === 'create' && <div className="field"><Label htmlFor="device-eui">设备EUI / 标识</Label><Input id="device-eui" required maxLength={64} autoComplete="off" value={eui} onChange={e => { setEui(e.target.value); action.touch() }}/><p className="muted-note">平台唯一，安全ASCII，上限64；不假设固定16位十六进制。</p></div>}
        {editor.mode !== 'edit' && <PublishedVersionPicker value={versionId} lockedProductId={editor.mode === 'migrate' ? device?.product_id : undefined} excludedVersionId={device?.product_version_id} onChange={(id, version) => { setVersionId(id); setChosenVersion(version); action.touch() }}/>}
        {editor.mode === 'migrate' && <div className="field"><Label htmlFor="migration-reason">版本迁移原因</Label><textarea id="migration-reason" required rows={4} value={reason} onChange={e => { setReason(e.target.value); action.touch() }}/></div>}
        {action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit" disabled={action.busy || (editor.mode === 'create' && !versionId)}>{action.busy ? '正在保存…' : editor.mode === 'migrate' ? '核对并迁移版本' : editor.mode === 'create' ? '创建设备开户' : '保存设备元数据'}</Button>
      </>}
    </form></div>
  </>
}

function CustomerDeviceEditor({ editor, onSaved }: { editor: DeviceEditorState; onSaved: () => void }) {
  if (editor.mode === 'history') return <CustomerDeviceHistory device={editor.device}/>
  if (editor.mode === 'place-history') return <CustomerDevicePlaceHistory device={editor.device}/>
  if (editor.mode === 'migrate') return <div className="review-body"><Empty title="客户入口不提供产品版本迁移"><p>版本迁移属于平台固定版本治理；客户只维护本单位设备名称、地址、位置与手工分类。请联系平台管理员处理版本变更。</p></Empty></div>
  return <CustomerDeviceForm editor={editor} onSaved={onSaved}/>
}

function CustomerDeviceHistory({ device }: { device: Device }) {
  const history = useResource(`customer-device-history:${device.tenant_id ?? 'none'}:${device.id}`, signal => customerAssetsApi.versionHistory(device.id, signal))
  return <><SheetHeader className="review-header"><SheetTitle>设备版本绑定历史</SheetTitle><SheetDescription>只展示当前单位服务端历史；不修改设备状态、旧报警或固定版本。</SheetDescription></SheetHeader><div className="review-body"><h3>{reported(device.name)}</h3><div className="identity-readonly mono">{device.id} · 当前绑定 {device.product_version_id ?? '未绑定模板'}</div>{history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data?.items.length ? <ol className="binding-history">{history.data.items.map(item => <li key={item.id}><strong>{item.reason === 'REGISTER' ? '正式开户绑定' : '显式版本迁移'}</strong><div className="mono">{item.from_version_id ?? '无原绑定'} → {item.to_version_id}</div><p>{item.reason}</p><span>{dateTime(item.occurred_at)}</span></li>)}</ol> : <Empty title="暂无版本绑定记录">历史设备的固定绑定不代表当前产品版本可迁移。</Empty>}</div></>
}

function CustomerDevicePlaceHistory({ device }: { device: Device }) {
  const [page, setPage] = useState(1)
  const history = useResource(`customer-device-place-history:${device.tenant_id ?? 'none'}:${device.id}:${page}`, signal => customerAssetsApi.placeHistory(device.id, page, signal))
  return <><SheetHeader className="review-header"><SheetTitle>设备位置变更历史</SheetTitle><SheetDescription>只记录实际开户和本单位位置移动；位置名称为操作当时快照，不补造历史归属。</SheetDescription></SheetHeader><div className="review-body"><h3>{reported(device.name)}</h3><div className="identity-readonly mono">{device.id}</div><RefreshButton onClick={history.refresh}/>{history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data && <>{history.data.items.length ? <ol className="binding-history">{history.data.items.map(item => <li key={item.id}><strong>{item.action === 'REGISTER' ? '开户初始位置' : '本单位位置移动'}</strong><div>{item.from_place_name ?? (item.action === 'REGISTER' ? '新设备' : '原位置未记录')} → {item.to_place_name}</div><p>设备 revision {item.revision_before ?? '—'} → {item.revision_after}</p><span>{dateTime(item.occurred_at)}</span></li>)}</ol> : <Empty title="暂无位置变更记录"/>}<Pager page={history.data.page} size={history.data.size} total={history.data.total} onChange={setPage}/></>}</div></>
}

function CustomerDeviceForm({ editor, onSaved }: { editor: Extract<DeviceEditorState, { mode: 'create' | 'edit' }>; onSaved: () => void }) {
  const { user, permissions } = useSession()
  const device = editor.device
  const [name, setName] = useState(device?.name ?? ''), [address, setAddress] = useState(device?.address ?? '')
  const [eui, setEui] = useState('')
  const [placeId, setPlaceId] = useState(device?.place_id ?? '')
  const [systemType, setSystemType] = useState(device?.system_type ?? '')
  const [deviceCategory, setDeviceCategory] = useState(device?.device_category ?? '')
  const [chosenVersion, setChosenVersion] = useState<CustomerPublishedOption | null>(null)
  const [versionId, setVersionId] = useState('')
  const scope = useResource(`customer-device-place-options:${user?.tenant_id ?? 'none'}`, signal => customerAssetsApi.scopeTree(signal))
  const unit = scope.data?.[0]
  const selectedPlace = unit?.places.find(place => place.id === placeId)
  const canWrite = permissions.includes('device:write')
  const store = editor.mode === 'create' ? customerDeviceCreateMutation : customerDeviceUpdateMutation
  const title = editor.mode === 'create' ? '客户新建设备' : '编辑本单位设备资料'
  if (!canWrite) return <div className="review-body"><Empty title="当前账号未获设备维护权限"/></div>
  return <>
    <SheetHeader className="review-header"><div className="eyebrow">DEVICE · 本单位自助</div><SheetTitle>{title}</SheetTitle><SheetDescription>{editor.mode === 'create' ? '新建设备只写入本单位真实位置，初始状态未知、未激活、待接入联调；不生成报警，也不调用厂商新增或控制。' : '只更新名称、地址、所属位置及手工登记的所属系统/设备类别；不改固定产品版本、上报事实、旧报警或位置历史。'}</SheetDescription></SheetHeader>
    <div className="review-body">
      {device && <><div className="identity-readonly mono">{device.id} · EUI {reported(device.device_eui)} · revision {device.revision ?? '未上报'}<br/>固定版本 {device.product_version_id ?? '未绑定模板'}</div><div className="device-snapshot"><Status value={device.physical_state} dimension="physical"/><span>电量 {battery(device.battery_percent, device.battery_percent_json)} · 最后上报 {dateTime(device.last_message_at)}</span></div></>}
      <CustomerWriteGate store={store} title={title} submitLabel={editor.mode === 'create' ? '确认新建设备' : '确认保存资料'} warning={editor.mode === 'create' ? '新建设备不是一次上报，也不代表厂商已连通；只有后续真实来源事实才会更新状态。' : '只更新所列资料；实际位置变化会保留原位置名称快照，并让设备 revision 增加。'} summary={<><p>归属单位：{unit?.name ?? user?.tenant_id ?? '本单位'}</p><p>设备：{editor.mode === 'create' ? eui || '未填写标识' : device?.id} · {name.trim() || '未填写名称'}</p><p>位置：{selectedPlace ? placePath(selectedPlace, unit!.places) : placeId || '未选择'}{selectedPlace && <span className="mono">（{placeId}）</span>}</p>{editor.mode === 'create' && <p>固定版本：{chosenVersion ? `${chosenVersion.product_name} · ${chosenVersion.model} · v${chosenVersion.version_number}` : '未选择'}</p>}{editor.mode === 'edit' && <p>expected_revision：{device?.revision}</p>}</>} buildRequest={() => {
        if (!name.trim()) throw new Error('请填写设备名称')
        if (!address.trim()) throw new Error('请填写安装地址')
        if (!placeId) throw new Error('请选择本单位真实位置；设备不能没有位置')
        if (editor.mode === 'create') {
          if (!/^[\x21-\x7e]{1,64}$/.test(eui)) throw new Error('设备标识需为1–64位安全ASCII')
          if (!chosenVersion || chosenVersion.id !== versionId) throw new Error('必须显式选择一个具体的已发布产品版本')
          return { path: '/devices', method: 'POST', body: { name: name.trim(), address: address.trim(), place_id: placeId, device_eui: eui, product_version_id: versionId, system_type: systemType.trim() || null, device_category: deviceCategory.trim() || null }, display: `新建设备 ${name.trim()} · ${eui}` }
        }
        if (!device || !Number.isSafeInteger(device.revision)) throw new Error('设备 revision 未确认，请刷新后重新打开')
        return { path: `/devices/${encodeURIComponent(device.id)}`, method: 'PUT', body: { name: name.trim(), address: address.trim(), place_id: placeId, system_type: systemType.trim() || null, device_category: deviceCategory.trim() || null, expected_revision: device.revision }, display: `编辑设备 ${device.name ?? device.id}` }
      }} onSaved={() => onSaved()}>
        {editor.mode === 'create' && <div className="field"><Label htmlFor="customer-device-eui">设备EUI / 标识</Label><Input id="customer-device-eui" required maxLength={64} autoComplete="off" value={eui} onChange={event => setEui(event.target.value)}/><p className="muted-note">平台唯一，1–64位安全ASCII。</p></div>}
        <div className="field"><Label htmlFor="customer-device-name">设备名称</Label><Input id="customer-device-name" required maxLength={200} value={name} onChange={event => setName(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-device-address">安装地址</Label><Input id="customer-device-address" required maxLength={500} value={address} onChange={event => setAddress(event.target.value)}/></div>
        <div className="field"><Label htmlFor="customer-device-system">所属系统（人工登记）</Label><Input id="customer-device-system" maxLength={80} value={systemType} onChange={event => setSystemType(event.target.value)} placeholder="未指定时留空"/></div>
        <div className="field"><Label htmlFor="customer-device-category">设备类别（人工登记）</Label><Input id="customer-device-category" maxLength={80} value={deviceCategory} onChange={event => setDeviceCategory(event.target.value)} placeholder="未指定时留空"/></div>
        <div className="field"><Label htmlFor="customer-device-place">所属位置（必选）</Label><select id="customer-device-place" className="native-select" required value={placeId} onChange={event => setPlaceId(event.target.value)}><option value="">{scope.loading ? '读取本单位位置…' : '请选择位置'}</option>{(unit?.places ?? []).map(place => <option key={place.id} value={place.id}>{placePath(place, unit?.places ?? [])}</option>)}</select>{scope.error != null && <ErrorNotice error={scope.error} retry={scope.refresh}/>}<p className="muted-note">只展示本单位真实位置；没有位置时请先在「本单位设置 → 本单位位置」创建。</p></div>
        {editor.mode === 'create' && <CustomerPublishedVersionPicker value={versionId} onChange={option => { setChosenVersion(option); setVersionId(option?.id ?? '') }}/>}
        {editor.mode === 'create' && <p className="muted-note">客户新建设备要求当前账号具备全单位设备范围；不含厂商凭据、产品发布或工程控制。厂商新增/已关联来源登记请使用「来源登记」。</p>}
      </CustomerWriteGate>
    </div>
  </>
}
