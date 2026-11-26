import { lazy, Suspense, useEffect, useState, type FormEvent } from 'react'
import { Dropdown, Pagination, Popover, Tabs } from 'antd'
import { Search, SlidersHorizontal, Plus, ChevronDown, Pencil, History, GitCompareArrows, Building2, ListChecks, MoveRight, Ellipsis, Filter, RefreshCw, Server, Flame, Battery, Layers } from 'lucide-react'
import { api } from '@/lib/api'
import { deviceBatchApi, type DeviceBatchTarget } from '@/lib/device-batch'
import { customerAssetsApi, customerDeviceBatchMoveMutation } from '@/lib/customer-assets'
import { placePath } from '@/lib/place-tree'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { allowedPage, type Device, type PlaceNode, type ScopeUnit } from '@/lib/types'
import { defaultDeviceSort, nextDeviceSort, type DeviceColumnKey } from '@/lib/device-sort'
import { emptyDeviceFilters, getDeviceWorkbench, type DeviceMetrics } from '@/lib/device-workbench'
import type { VendorDeviceSource } from '@/lib/vendor-devices'
import { VendorDeviceCreator } from '@/components/vendor-device-creator'
import type { DeviceEditorState } from '@/components/device-editor'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { DeviceTable, configurableDeviceColumns } from '@/components/data-tables'
import { CustomerDeviceSearch, CustomerDeviceStatus, CustomerDeviceStatusFilter, CustomerDeviceTable, CustomerDeviceTools } from '@/components/customer-device-ledger'
import { CustomerSystemTabs } from '@/components/customer-records'
import { PublishedVersionPicker } from '@/components/product-common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { Card, CardContent } from '@/components/ui/card'
import './device-assets.css'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Empty, ErrorNotice, Loading } from '@/components/common'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { DeviceTransferAction } from '@/components/device-transfer-action'
import { DeviceArchiveAction } from '@/components/device-archive-action'
import { DeviceServicesPanel } from '@/components/device-services-panel'
import { RegistrationCreator, RegistrationDetailView } from '@/components/device-registration-workspace'
import { DeviceRegistrationQueue } from '@/components/device-registration-queue'
import { CustomerDeviceRegistrationWorkspace } from '@/components/customer-device-registration'
import { CustomerWriteGate } from '@/components/customer-asset-write'
import { PageHelp, RowActions } from '@/components/page-tools'
import { publishObjectChange, useObjectRevision } from '@/lib/object-events'
const DeviceEditor = lazy(() => import('@/components/device-editor').then(m => ({ default: m.DeviceEditor })))
const DeviceDetail = lazy(() => import('@/components/device-detail').then(m => ({ default: m.DeviceDetail })))
const DeviceWorkbench = lazy(() => import('@/components/device-workbench').then(m => ({ default: m.DeviceWorkbench })))
const HikvisionAssociationSheet = lazy(() => import('@/components/hikvision-pending-devices').then(m => ({ default: m.HikvisionAssociationSheet })))
const CustomerDeviceDetail = lazy(() => import('@/components/customer-device-detail'))
type DeviceIntake = VendorDeviceSource | 'import' | 'advanced'
const intakeCopy = {
  LEGACY_REALTIME: { title: '旧烟感建档', description: '创建本地资产。设备通过网关或运营商直连本平台；建档不代表设备已上线。' },
  HIKVISION_FIRECLOUD: { title: '海康云新增', description: '确认后在海康云新增，再保存本地资产或接入登记。结果未知时从「待完成接入」继续核对。' },
  import: { title: '导入设备来源', description: '旧烟感表格可选单位、位置和型号；海康表格只关联已发现的设备。' },
} satisfies Record<Exclude<DeviceIntake, 'advanced'>, { title: string; description: string }>
const customerFields = [['eui', '设备编号'], ['name', '设备名称'], ['model', '设备型号'], ['address', '安装位置']] as const
const assetSystemTabs = [
  ['', '全部系统'], ['烟温火灾探测报警系统', '独立式烟温感系统'], ['传统消防接入报警系统', '火灾报警系统'],
  ['电气火灾监控预警系统', '电气火灾系统'], ['可燃气体系统', '可燃气体系统'], ['消防用水监测报警系统', '消防用水系统'],
  ['视频监控系统', '视频监控系统'], ['充电桩系统', '充电桩系统'], ['防排烟系统', '防排烟系统'],
  ['防火门监控系统', '防火门监控系统'], ['电源监控系统', '电源监控系统'], ['其他系统', '其他系统'],
] as const
const assetColumnOptions: typeof configurableDeviceColumns = [...configurableDeviceColumns, { key: 'business', label: '业务状态' }]
const assetColumns: DeviceColumnKey[] = ['name', 'category', 'eui', 'unit']
const monitoringColumns: DeviceColumnKey[] = ['name', 'eui', 'unit', 'placement', 'business', 'battery', 'last']
const connectivityOptions = [['', '全部连接状态'], ['ONLINE', '在线'], ['OFFLINE', '离线'], ['UNACTIVATED', '未激活'], ['UNKNOWN', '状态未确认']] as const
const deviceStatusOptions = [['', '全部设备状态'], ['ONLINE', '在线'], ['OFFLINE', '离线'], ['UNACTIVATED', '未激活'], ['FIRE', '报警'], ['FAULT', '故障'], ['SHIELDED', '屏蔽']] as const
const assetMetricGroups = [
  { icon: Server, title: '在线状态', values: [['在线', 'online', 'good'], ['离线', 'offline', 'danger'], ['未激活', 'unactivated', 'neutral'], ['未确认', 'unknown_connectivity', 'neutral']] },
  { icon: Flame, title: '火情状态', values: [['正常', 'normal', 'good'], ['火警', 'fire', 'danger'], ['未知', 'unknown', 'neutral']] },
  { icon: Battery, title: '电池状态', values: [['正常', 'battery_normal', 'good'], ['低电量', 'battery_low', 'warning'], ['未配置阈值', 'battery_not_configured', 'neutral'], ['未上报', 'battery_no_data', 'neutral']] },
] as const

function AssetMetrics({ metrics, loading, unavailable }: { metrics?: DeviceMetrics; loading: boolean; unavailable: boolean }) {
  return <section className="asset-metrics" aria-label="所选范围设备概况" aria-busy={loading}
    title={unavailable ? '统计暂不可用，请刷新设备列表' : '按左侧授权范围与系统分类统计全部分页，不受列表关键字影响'}>
    <div className="asset-metric asset-metric-total"><Layers size={17} aria-hidden="true"/><span>设备总数</span><strong>{metrics?.device_total ?? '—'}</strong></div>
    {assetMetricGroups.map(({ icon: Icon, title, values }) => <div className="asset-metric" key={title}>
      <div className="asset-metric-label"><Icon size={16} aria-hidden="true"/><span>{title}</span></div>
      <dl>{values.map(([label, key, tone]) => <div key={key} data-tone={tone}><dt>{label}</dt><dd>{metrics?.[key] ?? '—'}</dd></div>)}</dl>
    </div>)}
    {unavailable && <span className="asset-metrics-error" role="status">统计暂不可用，可刷新重读</span>}
  </section>
}

function AssetChoiceFilter({ label, value, options, onChange }: { label: string; value: string; options: readonly (readonly [string, string])[]; onChange: (value: string) => void }) {
  return <Dropdown trigger={['click']} menu={{ items: options.map(([key, text]) => ({ key: key || 'all', label: text })), selectable: true, selectedKeys: [value || 'all'], onClick: ({ key }) => onChange(key === 'all' ? '' : key) }}>
    <Button variant="ghost" size="icon-sm" className="asset-header-filter" aria-label={label} title={label} data-active={!!value}><Filter size={13}/></Button>
  </Dropdown>
}

function AssetCategoryFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const [open, setOpen] = useState(false)
  function apply(value: string) { onChange(value.trim()); setOpen(false) }
  return <Popover trigger="click" open={open} onOpenChange={setOpen} destroyOnHidden content={
    <form className="asset-category-filter" onSubmit={event => { event.preventDefault(); apply(String(new FormData(event.currentTarget).get('category') ?? '')) }}>
      <Input name="category" aria-label="按设备类别筛选" defaultValue={value} maxLength={80} placeholder="输入完整设备类别"/>
      <div><Button size="sm" variant="ghost" onClick={() => apply('')}>清除类别</Button><Button size="sm" type="submit">应用</Button></div>
    </form>
  }><Button variant="ghost" size="icon-sm" className="asset-header-filter" aria-label="筛选设备类型" title="筛选设备类型" data-active={!!value}><Filter size={13}/></Button></Popover>
}
export function DevicesPage({ onHikvisionEntryOpened }: { onHikvisionEntryOpened?: () => void } = {}) {
  const { user, permissions, audience, generation } = useSession()
  if (!user || user.must_change_password || !allowedPage('devices', audience, permissions)) return <Empty title="当前账户无设备查看权限"/>
  return <DevicesContent key={generation} onHikvisionEntryOpened={onHikvisionEntryOpened}/>
}
function DevicesContent({ onHikvisionEntryOpened }: { onHikvisionEntryOpened?: () => void }) {
  const session = useSession(), { audience, permissions } = session
  const [advanced, setAdvanced] = useState(false)
  const [notice, setNotice] = useState('')
  const [newDeviceOpen, setNewDeviceOpen] = useState(false)
  const [intake, setIntake] = useState<DeviceIntake | null>(null)
  const [registrationId, setRegistrationId] = useState<string | null>(null)
  const [queueOpen, setQueueOpen] = useState(false)
  function openDetail(device: { id: string; tenant_id?: string }) { setSelectedDetail({ id: device.id, tenantId: device.tenant_id }) }
  function closeDetail() { setSelectedDetail(null) }
  const management = audience === 'management'
  const canWrite = permissions.includes('device:write')
  const canReadHikvision = management && permissions.includes('integrations:read')
  const canWriteHikvision = permissions.includes('integrations:write')
  const canMigrate = management && permissions.includes('device:migrate')
  const [editor, setEditor] = useState<DeviceEditorState | null>(null), [selectedDetail, setSelectedDetail] = useState<{ id: string; tenantId?: string } | null>(null)
  const [movingDevice, setMovingDevice] = useState<Device | null>(null)
  const [customerMoveTargets, setCustomerMoveTargets] = useState<Device[]>([])
  const [selectedDevices, setSelectedDevices] = useState<Map<string, Device>>(new Map())
  const [batchTargets, setBatchTargets] = useState<Device[]>([])
  const [pendingOpen, setPendingOpen] = useState(() => canReadHikvision && !!onHikvisionEntryOpened)
  useEffect(() => { onHikvisionEntryOpened?.() }, [onHikvisionEntryOpened])
  const [serviceDevice, setServiceDevice] = useState<{ id: string; tenantId?: string; name?: string | null } | null>(null)
  const close = () => setEditor(null)
  const [page, setPage] = useState(1)
  const [sort, setSort] = useState(defaultDeviceSort)
  const [size, setSize] = useState<number | undefined>(management ? undefined : 10)
  const [draft, setDraft] = useState('')
  const [keyword, setKeyword] = useState('')
  const [field, setField] = useState(management ? '' : customerFields[0][0])
  const [customerStatus, setCustomerStatus] = useState('')
  const [placeId, setPlaceId] = useState('')
  const [tenantFilter, setTenantFilter] = useState('')
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds)
  const effectiveTenantScope = management && session.scopeInitialized ? scopeTenantIds : undefined
  const effectivePlaceScope = management && session.scopeInitialized ? scopePlaceIds : undefined
  const canReadTenants = management && permissions.includes('tenants:read')
  const tenants = useResource('device-filter-tenants', signal => api.tenants(signal), canReadTenants)
  const canReadPlaces = permissions.includes('units:read')
  const placeTree = useResource('device-ledger-place-options', signal => api.scopeTree(signal), !advanced && canReadPlaces && management)
  const placeOptions: PlaceNode[] = (placeTree.data ?? []).flatMap((unit: ScopeUnit) => unit.places)
  const filteredPlaceOptions = placeOptions.filter(place => !tenantFilter || place.tenant_id === tenantFilter)
  const [columnOpen, setColumnOpen] = useState(false)
  const [monitoring, setMonitoring] = useState(false)
  const [connectivity, setConnectivity] = useState(''), [physicalState, setPhysicalState] = useState(''), [systemType, setSystemType] = useState(''), [deviceCategory, setDeviceCategory] = useState('')
  const [columns, setColumns] = useState<DeviceColumnKey[]>(() => {
    try {
      const stored: unknown = JSON.parse(localStorage.getItem('fire.device.columns') || '[]')
      const saved = Array.isArray(stored) ? assetColumnOptions.map(c => c.key).filter(key => stored.includes(key)) : []
      return saved.length ? saved : assetColumns
    } catch { return assetColumns }
  })
  const eventRevision = useObjectRevision('events'), notificationRevision = useObjectRevision('notifications'), deviceRevision = useObjectRevision('devices')
  const statusFilters = { connectivity, physical_state: physicalState, system_type: systemType, device_category: deviceCategory, customer_status: customerStatus }
  const devices = useResource(`devices:${JSON.stringify([page, size, keyword, field, placeId, tenantFilter, connectivity, physicalState, systemType, deviceCategory, customerStatus, effectiveTenantScope, effectivePlaceScope, eventRevision, notificationRevision, sort])}`,
    signal => api.devices(page, keyword, size, signal, field, placeId, tenantFilter || undefined, effectiveTenantScope, effectivePlaceScope, statusFilters, sort), !advanced)
  const metrics = useResource(`asset-metrics:${JSON.stringify([effectiveTenantScope, effectivePlaceScope, systemType, eventRevision, deviceRevision])}`,
    signal => getDeviceWorkbench({ ...emptyDeviceFilters, system_type: systemType.trim() || null }, 1, 1, signal),
    management && !advanced && session.scopeInitialized)
  const refreshAssets = () => { devices.refresh(); metrics.refresh() }
  useEffect(() => { setPage(1); setSelectedDevices(new Map()) }, [scopeTenantIds.join(','), scopePlaceIds.join(',')])
  const setFirstPage = () => { setPage(1); setSelectedDevices(new Map()) }
  function search(event: FormEvent) { event.preventDefault(); setPage(1); setSelectedDevices(new Map()); setKeyword(draft.trim()) }
  function sortBy(column: DeviceColumnKey) { setSort(current => nextDeviceSort(current, column)); setPage(1); setSelectedDevices(new Map()) }
  function resetSearch() { setDraft(''); setKeyword(''); setField(management ? '' : customerFields[0][0]); setPlaceId(''); setTenantFilter(''); setConnectivity(''); setPhysicalState(''); setSystemType(''); setDeviceCategory(''); setCustomerStatus(''); setFirstPage() }
  function saveColumns(next: DeviceColumnKey[]) { setColumns(next); try { localStorage.setItem('fire.device.columns', JSON.stringify(next)) } catch {} }
  function toggleColumn(key: DeviceColumnKey, checked: boolean) { if (checked) saveColumns([...columns, key]); else if (columns.length > 1) saveColumns(columns.filter(value => value !== key)) }
  function showAllColumns() { saveColumns(assetColumnOptions.map(c => c.key)) }
  function switchMode(next: boolean) { closeDetail(); setEditor(null); setSelectedDevices(new Map()); setNotice(''); setPage(1); setSize(undefined); setDraft(''); setKeyword(''); setAdvanced(next) }
  function selectDevice(device: Device, checked: boolean) { setSelectedDevices(current => { const next = new Map(current); if (checked) next.set(device.id, device); else next.delete(device.id); return next }) }
  function selectPage(items: Device[], checked: boolean) { setSelectedDevices(current => { const next = new Map(current); for (const device of items) { if (checked) next.set(device.id, device); else next.delete(device.id) } return next }) }
  if (advanced) return <Suspense fallback={<Loading/>}><DeviceWorkbench back={() => switchMode(false)} /></Suspense>
  if (!management) {
    const tenantId = session.user?.tenant_id
    if (!tenantId) return <Empty title="当前账户未绑定单位"/>
    const items = devices.data?.items ?? []
    const canSelect = canWrite || permissions.includes('exports:write')
    function edit(device: Device) {
      if (canWrite && Number.isSafeInteger(device.revision)) setEditor({ mode: 'edit', device })
    }
    function refresh() { devices.refresh(); setSelectedDevices(new Map()); publishObjectChange('devices') }
    return <section className="legacy-device-ledger" aria-label="无线终端信息">
      {selectedDetail ? <Suspense fallback={<Loading/>}><CustomerDeviceDetail deviceId={selectedDetail.id} tenantId={tenantId}
        close={closeDetail} onEdit={canWrite ? edit : undefined}/></Suspense> : <>
        <CustomerSystemTabs system={systemType} onSystem={value => { setSystemType(value); setFirstPage() }}/>
        <CustomerDeviceSearch fields={customerFields} initialField={field} draft={draft} onDraft={setDraft}
          onSearch={(nextField, value) => { setField(nextField); setKeyword(value); setFirstPage() }} onReset={resetSearch}>
          <CustomerDeviceTools kind="无线终端" create={canWrite ? () => setNewDeviceOpen(true) : undefined} transfer={mode =>
            <DataTransferActions key={mode} kind="DEVICES" direct={mode} label={mode === 'import' ? '批量添加' : '批量导出'}
              tenantId={tenantId} filters={{ unit_ids: tenantId, keyword, field, system_type: systemType, customer_status: customerStatus }}
              selectedIds={[...selectedDevices.keys()]} onCompleted={result => { if ('plan_id' in result) refresh() }}/>}>
            {canWrite && selectedDevices.size > 0 && <button type="button" className="legacy-device-link" disabled={devices.loading} onClick={() => setCustomerMoveTargets([...selectedDevices.values()])}>移动位置</button>}
          </CustomerDeviceTools>
        </CustomerDeviceSearch>
        <CustomerDeviceTable page={page} size={size ?? 10} list={{ ...devices, data: devices.data && { ...devices.data,
          items: items.map(device => ({ id: device.id, device, eui: device.device_eui, name: device.name, model: device.model,
            address: device.address ?? device.place_name, status: <CustomerDeviceStatus code={device.status_display?.customer_status}/>, last: device.last_message_at })) } }}
          setPage={value => { setPage(value); setSelectedDevices(new Map()) }} setSize={value => { setSize(value); setFirstPage() }}
          open={row => openDetail(row.device)} edit={canWrite ? row => edit(row.device) : undefined}
          statusFilter={<CustomerDeviceStatusFilter value={customerStatus} onChange={value => { setCustomerStatus(value); setFirstPage() }}/>}
          selection={canSelect ? { has: id => selectedDevices.has(id), set: (id, value) => {
            const device = items.find(item => item.id === id)
            if (device) selectDevice(device, value)
          }, setPage: value => selectPage(items, value) } : undefined}/>
      </>}
      <Sheet open={newDeviceOpen} onOpenChange={setNewDeviceOpen}><SheetContent className="review-sheet">
        <SheetHeader className="review-header"><SheetTitle>添加无线终端</SheetTitle></SheetHeader><CustomerDeviceRegistrationWorkspace onCreated={refresh}/>
      </SheetContent></Sheet>
      <Sheet open={!!editor} onOpenChange={open => { if (!open) close() }}><SheetContent className="review-sheet">{editor &&
        <DeviceEditor key={`${editor.device?.id}:${editor.device?.revision}`} editor={editor} onRefresh={() => { close(); refresh() }} onSaved={() => { close(); refresh() }}/>}
      </SheetContent></Sheet>
      <Sheet open={customerMoveTargets.length > 0} onOpenChange={open => { if (!open) setCustomerMoveTargets([]) }}><SheetContent className="review-sheet">
        <SheetHeader className="review-header"><SheetTitle>移动设备位置</SheetTitle></SheetHeader>
        {customerMoveTargets.length > 0 && <DeviceBatchActions devices={customerMoveTargets} canWrite={canWrite} canMigrate={false}
          onCancel={() => setCustomerMoveTargets([])} onSaved={() => { setCustomerMoveTargets([]); refresh(); useSession.getState().refreshScopeTree() }}/>}
      </SheetContent></Sheet>
    </section>
  }
  if (selectedDetail) return <Suspense fallback={<Loading/>}><DeviceDetail deviceId={selectedDetail.id} tenantId={selectedDetail.tenantId} close={closeDetail} onEdit={device => { closeDetail(); setSelectedDevices(new Map()); setEditor({ mode: 'edit', device }) }}/></Suspense>
  return <section className="device-ledger" aria-labelledby="device-ledger-title">
    <h1 className="asset-heading" id="device-ledger-title">设备资产</h1>
    {canWrite && <Sheet open={queueOpen && !registrationId} onOpenChange={setQueueOpen}><SheetContent className="review-sheet sm:max-w-5xl">
      <SheetHeader className="review-header"><SheetTitle>待完成接入</SheetTitle><SheetDescription>继续核对来源或完成本地验收。</SheetDescription></SheetHeader>
      <div className="review-body">{queueOpen && !registrationId && <DeviceRegistrationQueue onCompleted={() => { refreshAssets(); useSession.getState().refreshScopeTree(); publishObjectChange('devices') }}/>}</div>
    </SheetContent></Sheet>}
    <Sheet open={intake !== null} onOpenChange={open => { if (!open) setIntake(null) }}><SheetContent className="review-sheet sm:max-w-2xl">
      {intake === 'advanced' ? <RegistrationCreator onCreated={detail => {
        setIntake(null); setRegistrationId(detail.registration.id); setQueueOpen(true); devices.refresh(); publishObjectChange('devices')
      }}/> : intake && <>
        <SheetHeader className="review-header"><SheetTitle>{intakeCopy[intake].title}</SheetTitle><SheetDescription>{intakeCopy[intake].description}</SheetDescription></SheetHeader>
        <div className="review-body"><VendorDeviceCreator entry={intake}
          onCreated={device => {
            setIntake(null); setSelectedDevices(new Map()); devices.refresh(); useSession.getState().refreshScopeTree(); publishObjectChange('devices')
            setNotice(`设备 ${device.name ?? device.device_eui ?? device.id} 已在平台建档。服务端初始状态：连接 ${device.connectivity ?? '未返回'} · 物理 ${device.physical_state ?? '未返回'} · 接入 ${device.integration_state ?? '未返回'} · 最近上报 ${device.last_message_at ?? '暂无真实上报'}；建档不代表设备已激活或验收。`)
          }}
          onPending={detail => {
            setIntake(null); setRegistrationId(detail.registration.id); setQueueOpen(true); devices.refresh(); publishObjectChange('devices')
          }}
          onBatchCreated={items => {
            setIntake(null); setSelectedDevices(new Map()); devices.refresh(); useSession.getState().refreshScopeTree(); publishObjectChange('devices')
            setNotice(`已完成 ${items.length} 台设备的本地批量新增或已有海康设备关联；不会调用旧平台新增或海康云批量开户。`)
          }}/></div>
      </>}
    </SheetContent></Sheet>
    {registrationId && <Sheet open={!!registrationId} onOpenChange={open => { if (!open) setRegistrationId(null) }}><SheetContent className="review-sheet sm:max-w-2xl"><SheetHeader className="review-header"><SheetTitle>登记处理</SheetTitle><SheetDescription>云端与本地步骤分离；未知结果按精确序列号或台账dId只读核对。</SheetDescription></SheetHeader><RegistrationDetailView registrationId={registrationId} onChanged={() => { devices.refresh(); useSession.getState().refreshScopeTree(); publishObjectChange('devices') }}/></SheetContent></Sheet>}
    {canReadHikvision && pendingOpen && <Suspense fallback={<Loading/>}>
      <HikvisionAssociationSheet onClose={() => setPendingOpen(false)} openDevice={device => { setPendingOpen(false); openDetail(device) }}/>
    </Suspense>}
    {notice && <div role="status" className="notice">{notice}</div>}
    <div className="asset-list">
      <Tabs size="small" animated={false} activeKey={monitoring ? 'monitoring' : 'ledger'} onChange={view => {
        setMonitoring(view === 'monitoring')
        if (view === 'monitoring') setSelectedDevices(new Map())
      }} items={[{ key: 'ledger', label: '资产台账' }, { key: 'monitoring', label: '运行监控' }]}/>
      <Tabs className="asset-tabs" size="small" animated={false} activeKey={systemType || 'all'}
        onChange={value => { setSystemType(value === 'all' ? '' : value); setFirstPage() }}
        items={[...assetSystemTabs.map(([key, label]) => ({ key: key || 'all', label })),
          ...(systemType && !assetSystemTabs.some(([key]) => key === systemType) ? [{ key: systemType, label: systemType }] : [])]}/>
      <AssetMetrics metrics={metrics.data?.metrics} loading={metrics.loading} unavailable={metrics.error != null}/>
      <div className="asset-toolbar">
        <form onSubmit={search} className="asset-search">
          <Input type="search" aria-label="搜索设备" placeholder={field === 'eui' ? '搜索设备编号' : field === 'name' ? '搜索设备名称' : field === 'model' ? '搜索设备型号' : field === 'address' ? '搜索安装位置' : '设备名称、序列号、安装位置'} value={draft} onChange={e => setDraft(e.target.value)}/>
          <Button variant="ghost" type="submit" size="icon-sm" title="查询" aria-label="查询"><Search size={16}/></Button>
        </form>
        {!monitoring && (canWrite || canReadHikvision && canWriteHikvision) && <Dropdown trigger={['click']} menu={{ items: [
          ...(canWrite ? [{ key: 'LEGACY_REALTIME', label: intakeCopy.LEGACY_REALTIME.title, onClick: () => setIntake('LEGACY_REALTIME') }] : []),
          ...(canWrite && canWriteHikvision ? [{ key: 'HIKVISION_FIRECLOUD', label: intakeCopy.HIKVISION_FIRECLOUD.title, onClick: () => setIntake('HIKVISION_FIRECLOUD') }] : []),
          ...(canReadHikvision && canWriteHikvision ? [{ key: 'association', label: '关联已有海康设备', onClick: () => setPendingOpen(true) }] : []),
          ...(canWrite ? [{ key: 'advanced', label: '高级接入登记', onClick: () => setIntake('advanced') }] : []),
        ] }}><Button type="button" size="sm"><Plus/>添加设备<ChevronDown/></Button></Dropdown>}
        {!monitoring && canWrite && <Button type="button" variant="ghost" size="sm" onClick={() => setQueueOpen(true)}><ListChecks/>待完成接入</Button>}
        {!monitoring && <DataTransferActions kind="DEVICES" assetMenu={{ scopeReady: session.scopeInitialized, onSourceImport: canWrite ? () => setIntake('import') : undefined }}
          filters={{ unit_ids: scopeTenantIds.join(','), place_ids: scopePlaceIds.join(','), keyword, field, place_id: placeId, tenant_id: tenantFilter, ...statusFilters }}
          selectedIds={[...selectedDevices.keys()]} onCompleted={result => { if ('plan_id' in result) { refreshAssets(); setSelectedDevices(new Map()) } }}/>}
        {!monitoring && canReadHikvision && !canWriteHikvision && <Button type="button" variant="ghost" size="sm" onClick={() => setPendingOpen(true)}><Building2/>查看海康待关联设备</Button>}
        {!monitoring && (canWrite || canMigrate) && selectedDevices.size > 0 && <Button variant="ghost" size="sm" onClick={() => setBatchTargets([...selectedDevices.values()])}><ListChecks/>批量操作（{selectedDevices.size}）</Button>}
        <RowActions label="设备列表工具" text="更多配置" closeOnClick={false}>
          <Button type="button" variant="outline" onClick={() => switchMode(true)}>打开设备筛选工作台</Button>
          <PageHelp title="设备资产说明">左侧单位和位置仅筛选读取范围。引入、移动和移交必须另选真实目标。未知字段不作正常、离线或零值推定。</PageHelp>
        </RowActions>
        <div className="asset-toolbar-end">
          <Button type="button" size="icon-sm" variant="ghost" aria-label="刷新数据" title="刷新数据" onClick={refreshAssets}><RefreshCw/></Button>
          <Popover trigger="click" placement="bottomRight" destroyOnHidden content={<div className="asset-filters">
          <label className="size-picker"><select aria-label="搜索字段" value={field} onChange={e => { setField(e.target.value); setFirstPage() }}><option value="">全部字段</option><option value="eui">设备编号</option><option value="name">设备名称</option><option value="model">设备型号</option><option value="address">安装位置</option></select></label>
          {canReadPlaces && <label className="size-picker"><Building2 size={15}/><select aria-label="按安装位置筛选" value={placeId} onChange={e => { setPlaceId(e.target.value); setFirstPage() }}><option value="">全部位置</option>{filteredPlaceOptions.map(place => <option key={place.id} value={place.id}>{`${place.tenant_name} · ${'　'.repeat(Math.max(0, place.level - 1))}${place.name}`}</option>)}</select></label>}
          {canReadTenants && <label className="size-picker"><Building2 size={15}/><select aria-label="按单位筛选" value={tenantFilter} onChange={e => { setTenantFilter(e.target.value); setPlaceId(''); setFirstPage() }}><option value="">全部单位</option>{tenants.data?.items?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>}
          <label className="size-picker">在线状态<select aria-label="按链路诊断筛选" value={connectivity} onChange={e => { setConnectivity(e.target.value); setFirstPage() }}>{connectivityOptions.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
          <label className="size-picker">来源火情<select aria-label="按来源火情筛选" value={physicalState} onChange={e => { setPhysicalState(e.target.value); setFirstPage() }}><option value="">全部</option><option value="FIRE">火警</option><option value="NORMAL">正常</option><option value="UNKNOWN">未知</option></select></label>
          <label className="size-picker">设备状态<select aria-label="按设备状态筛选" value={customerStatus} onChange={e => { setCustomerStatus(e.target.value); setFirstPage() }}>{deviceStatusOptions.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
          <label className="size-picker">系统类型<Input aria-label="按系统类型筛选" maxLength={80} value={systemType} onChange={e => { setSystemType(e.target.value); setFirstPage() }}/></label>
          <label className="size-picker">设备类别<Input aria-label="按设备类别筛选" maxLength={80} value={deviceCategory} onChange={e => { setDeviceCategory(e.target.value); setFirstPage() }}/></label>
          <Button variant="ghost" size="sm" type="button" onClick={resetSearch}>重置</Button>
          </div>}>
            <Button variant="ghost" size="sm"><Filter/>条件筛选{(field || placeId || tenantFilter || connectivity || physicalState || deviceCategory || customerStatus) && ' · 已启用'}</Button>
          </Popover>
          {!monitoring && <Popover trigger="click" placement="bottomRight" open={columnOpen} onOpenChange={setColumnOpen} destroyOnHidden content={
            <div className="column-picker" role="group" aria-label="设备列设置"><div className="column-picker-head"><strong>显示列</strong><span>{columns.length} / {assetColumnOptions.length}</span></div><div className="column-picker-grid">{assetColumnOptions.map(column => <label key={column.key}><input type="checkbox" checked={columns.includes(column.key)} onChange={e => toggleColumn(column.key, e.target.checked)}/>{column.label}</label>)}</div><div className="column-picker-actions"><Button type="button" variant="ghost" size="sm" onClick={showAllColumns}>全部显示</Button><Button type="button" variant="ghost" size="sm" onClick={() => setColumnOpen(false)}>完成</Button></div></div>
          }><Button variant="ghost" size="sm" type="button" aria-expanded={columnOpen}><SlidersHorizontal/>列设置</Button></Popover>}
        </div>
      </div>
      <div className="asset-table-frame">
      {devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={refreshAssets}/> : devices.data ?
        <DeviceTable assetLayout showPhysicalState={monitoring} items={devices.data.items} visibleColumns={monitoring ? monitoringColumns : columns} sort={sort} onSort={sortBy}
          headerActions={{
            category: <AssetCategoryFilter value={deviceCategory} onChange={value => { setDeviceCategory(value); setFirstPage() }}/>,
            online: <AssetChoiceFilter label="筛选在线状态" value={connectivity} options={connectivityOptions} onChange={value => { setConnectivity(value); setFirstPage() }}/>,
            customer: <AssetChoiceFilter label="筛选设备状态" value={customerStatus} options={deviceStatusOptions} onChange={value => { setCustomerStatus(value); setFirstPage() }}/>,
          }}
          selectedIds={new Set(selectedDevices.keys())} onSelectionChange={!monitoring && (canWrite || canMigrate || permissions.includes('exports:write')) ? selectDevice : undefined} onPageSelectionChange={!monitoring && (canWrite || canMigrate || permissions.includes('exports:write')) ? selectPage : undefined} actions={device => <div className="identity-row-actions device-ledger-row-actions">
          <Button type="button" variant="ghost" size="sm" onClick={() => openDetail(device)}>查看详情</Button>
          {!monitoring && <RowActions label="更多设备操作" text={<Ellipsis aria-hidden="true" size={17}/>}>
            {canWrite && <><Button variant="ghost" size="sm" disabled={!Number.isSafeInteger(device.revision)} onClick={() => setEditor({ mode: 'edit', device })}><Pencil/>编辑资料</Button><Button variant="ghost" size="sm" disabled={!Number.isSafeInteger(device.revision)} onClick={() => setMovingDevice(device)}><MoveRight/>移动位置</Button></>}
            {management && permissions.includes('device-services:read') && <Button type="button" variant="ghost" size="sm" onClick={() => setServiceDevice({ id: device.id, tenantId: device.tenant_id, name: device.name })}><SlidersHorizontal/>服务与参数</Button>}
            {management && canWrite && <DeviceTransferAction device={device} onSaved={() => { setSelectedDevices(new Map()); devices.refresh(); setNotice('受控移交已提交；来源资产历史保留，目标资产需单独完成接入绑定。') }}/>}
            {management && canWrite && <DeviceArchiveAction device={device} onSaved={() => { setSelectedDevices(new Map()); devices.refresh(); setNotice('设备已归档；历史保留且不再参与日常资产查询。') }}/>}
            {canMigrate && <Button variant="ghost" size="sm" disabled={!Number.isSafeInteger(device.revision)} onClick={() => setEditor({ mode: 'migrate', device })}><GitCompareArrows/>迁移版本</Button>}
            <Button variant="ghost" size="sm" onClick={() => setEditor({ mode: 'history', device })}><History/>绑定历史</Button>
            <Button variant="ghost" size="sm" onClick={() => setEditor({ mode: 'place-history', device })}><History/>位置历史</Button>
          </RowActions>}
        </div>}/> : null}
      </div>
      {devices.data && !devices.loading && <div className="asset-pagination">
        <span>共 {devices.data.total} 条</span>
        {selectedDevices.size > 0 && <Button variant="ghost" size="sm" onClick={() => setSelectedDevices(new Map())}>已选 {selectedDevices.size} 条 · 清除</Button>}
        <select aria-label="每页设备条数" value={devices.data.size} onChange={e => { setSize(Number(e.target.value)); setFirstPage() }}>
          {[...new Set([devices.data.size, 20, 50, 100])].sort((a, b) => a - b).map(count => <option key={count} value={count}>{count} 条 / 页</option>)}
        </select>
        <Pagination current={devices.data.page} pageSize={devices.data.size} total={devices.data.total} showSizeChanger={false} size="small" onChange={setPage}/>
      </div>}
    </div>
    <Sheet open={batchTargets.length > 0} onOpenChange={open => { if (!open) setBatchTargets([]) }}><SheetContent className="review-sheet sm:max-w-xl"><SheetHeader className="review-header"><SheetTitle>已选设备批量操作</SheetTitle><SheetDescription>使用选中设备的实际归属，位置和版本变更须核对后提交。</SheetDescription></SheetHeader>
      <div className="review-body">{batchTargets.length > 0 && <DeviceBatchActions devices={batchTargets} canWrite={canWrite} canMigrate={canMigrate} onCancel={() => { setBatchTargets([]); setSelectedDevices(new Map()) }} onSaved={() => { setBatchTargets([]); setSelectedDevices(new Map()); refreshAssets(); useSession.getState().refreshScopeTree(); publishObjectChange('devices', 'organization'); setNotice('已完成所选设备的显式版本/位置变更。') }}/>}</div>
    </SheetContent></Sheet>
    <Sheet open={!!editor} onOpenChange={open => { if (!open) close() }}><SheetContent className="review-sheet sm:max-w-xl">{editor && <DeviceEditor key={`${editor.mode}:${editor.device?.id ?? 'new'}:${editor.device?.revision ?? ''}`} editor={editor} onRefresh={() => { close(); setSelectedDevices(new Map()); devices.refresh() }} onSaved={() => { close(); setSelectedDevices(new Map()); devices.refresh(); publishObjectChange('devices'); setNotice('设备操作已保存。开户不是上报、迁移不是恢复；请按服务端结果核对未知状态、待联调标记与固定版本。') }}/>}</SheetContent></Sheet>
    <Sheet open={!!movingDevice} onOpenChange={open => { if (!open) setMovingDevice(null) }}><SheetContent className="review-sheet"><SheetHeader className="review-header"><SheetTitle>移动设备位置 · {movingDevice?.name ?? movingDevice?.device_eui}</SheetTitle></SheetHeader><div className="review-body">{movingDevice && <DeviceBatchActions key={movingDevice.id} devices={[movingDevice]} canWrite={canWrite} canMigrate={false} onCancel={() => setMovingDevice(null)} onSaved={() => { setMovingDevice(null); setSelectedDevices(new Map()); devices.refresh(); useSession.getState().refreshScopeTree(); publishObjectChange('devices', 'organization'); setNotice('设备位置已更新，位置履历已保留；列表筛选与页码不变。') }}/>}</div></SheetContent></Sheet>
    <Sheet open={!!serviceDevice} onOpenChange={open => { if (!open) setServiceDevice(null) }}><SheetContent className="review-sheet sm:max-w-3xl">
      {serviceDevice && <><SheetHeader className="review-header"><SheetTitle>设备服务 · {serviceDevice.name ?? serviceDevice.id}</SheetTitle><SheetDescription>能力为缓存元数据；参数与命令只在显式操作后产生。厂商接受不等于物理执行。</SheetDescription></SheetHeader><DeviceServicesPanel deviceId={serviceDevice.id} tenantId={serviceDevice.tenantId}/></>}
    </SheetContent></Sheet>
  </section>
}
function DeviceBatchActions({ devices, canWrite, canMigrate, onCancel, onSaved }: { devices: Device[]; canWrite: boolean; canMigrate: boolean; onCancel: () => void; onSaved: () => void }) {
  const permissions = useSession(state => state.permissions)
  const audience = useSession(state => state.audience)
  const management = audience === 'management'
  const sameTenant = !!devices[0]?.tenant_id && devices.every(device => device.tenant_id === devices[0].tenant_id)
  const sameProduct = !!devices[0]?.product_id && devices.every(device => device.product_id === devices[0].product_id)
  const allPlaced = devices.every(device => !!device.place_id)
  const tenantId = sameTenant ? devices[0].tenant_id : undefined
  const [mode, setMode] = useState<'move' | 'migrate'>(canWrite ? 'move' : 'migrate')
  const [placeId, setPlaceId] = useState(''), [versionId, setVersionId] = useState(''), [reason, setReason] = useState('')
  const action = useMutationAction()
  const tree = useResource<{ id: string; name: string; places: PlaceNode[] }[]>(`device-batch-place-options:${tenantId ?? 'none'}:${audience}`, async signal => {
    const units = management ? await api.scopeTree(signal) : await customerAssetsApi.scopeTree(signal)
    return units.map(unit => ({ id: unit.id, name: unit.name, places: unit.places as PlaceNode[] }))
  }, !!tenantId && canWrite && permissions.includes('units:read'))
  const targetUnit = tree.data?.find(unit => unit.id === tenantId)
  const targetPlaces = targetUnit?.places ?? []
  const targetPlace = targetPlaces.find(place => place.id === placeId)
  const targetPlaceLabel = targetPlace ? `${placePath(targetPlace, targetPlaces)}（${targetPlace.id}）` : placeId || '未选择'
  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (!sameTenant || devices.length > 100 || devices.some(device => !Number.isSafeInteger(device.revision))) throw new Error('批量操作只接受1–100台同一归属单位、revision有效的设备。')
      if (mode === 'move' && (!canWrite || !targetPlaces.some((place: PlaceNode) => place.id === placeId))) throw new Error('请选择本单位中可访问的真实位置；历史位置未知不得自动补齐。')
      if (mode === 'migrate' && (!canMigrate || !sameProduct || !allPlaced || !versionId || !reason.trim())) throw new Error('版本迁移要求同一产品、已登记真实位置、明确选择发布版本并说明原因。')
    } catch (error) { action.setError(error); return }
    if (!action.confirming) { action.setConfirming(true); return }
    const targets: DeviceBatchTarget[] = devices.map(device => ({ device_id: device.id, expected_revision: device.revision as number }))
    void action.run(async (key, signal) => {
      if (!tenantId) throw new Error('设备归属单位缺失，禁止提交跨租户批量写入。')
      if (mode === 'move') await deviceBatchApi.move(targets, placeId, key, tenantId, signal)
      else await deviceBatchApi.migrate(targets, versionId, reason, key, tenantId, signal)
    }, onSaved)
  }
  if (!sameTenant) return <Card><CardContent><h2>批量设备操作</h2><p role="alert">所选设备归属不同单位或单位身份缺失。请清除选择后仅选同一单位设备；不会使用全局租户替代设备归属。</p><Button type="button" variant="outline" onClick={onCancel}>清除选择</Button></CardContent></Card>
  if (!management) return <Card><CardContent><h2><ListChecks size={18}/>{devices.length === 1 ? '变更设备位置' : `批量变更设备位置（${devices.length}台）`}</h2><p>只变更明确所选设备在本单位内的位置关系；不执行云端控制，不修改固定版本、用户授权或历史报警。</p>
    <CustomerWriteGate store={customerDeviceBatchMoveMutation} title="变更设备位置" submitLabel={devices.length === 1 ? '确认变更位置' : '确认批量移动'} warning="服务端会逐台按提交的 revision 重新校验；只写入位置关系和不可变历史，不执行设备参数或硬件命令。" summary={<><p>设备：{devices.map(device => device.name ?? device.device_eui ?? device.id).join('、')}</p><p>目标位置：{targetPlaceLabel}</p></>} buildRequest={() => {
      if (!tenantId) throw new Error('设备归属单位缺失，禁止提交跨租户移动')
      if (!placeId || !targetPlaces.some((place: PlaceNode) => place.id === placeId)) throw new Error('请选择本单位真实目标位置')
      if (devices.some(device => !Number.isSafeInteger(device.revision))) throw new Error('所选设备 revision 未确认，请刷新后重选')
      const targets: DeviceBatchTarget[] = devices.map(device => ({ device_id: device.id, expected_revision: device.revision as number }))
      return { path: '/devices/batch-move', method: 'POST', body: { devices: targets, place_id: placeId }, display: `变更 ${devices.length} 台设备位置` }
    }} onSaved={onSaved}>
      <label className="field">真实目标位置<select required value={placeId} onChange={event => setPlaceId(event.target.value)}><option value="">请选择位置</option>{targetPlaces.map((place: PlaceNode) => <option key={place.id} value={place.id}>{placePath(place, targetPlaces)}</option>)}</select>{tree.loading && <Loading/>}{tree.error != null && <ErrorNotice error={tree.error} retry={tree.refresh}/>}</label>
    </CustomerWriteGate>
    <Button type="button" variant="outline" onClick={onCancel}>{devices.length === 1 ? '取消' : '清除选择'}</Button>
  </CardContent></Card>
  return <Card><CardContent><h2><ListChecks size={18}/>{devices.length === 1 ? mode === 'move' ? '变更设备位置' : '迁移设备产品版本' : `批量设备操作（${devices.length}台）`}</h2><p>只变更明确所选设备的本地位置/版本关系；不执行云端控制，不修改既有用户授权或历史报警。</p>
    <form onSubmit={submit}>
      {!action.confirming ? <>
        {canWrite && canMigrate && <label className="field">操作<select aria-label="批量设备操作" value={mode} onChange={event => { setMode(event.target.value as 'move' | 'migrate'); setPlaceId(''); setVersionId(''); action.touch() }}><option value="move">变更归属位置</option><option value="migrate">迁移已发布版本</option></select></label>}
        {mode === 'move' && <label className="field">真实目标位置<select required value={placeId} onChange={event => { setPlaceId(event.target.value); action.touch() }}><option value="">请选择位置</option>{targetPlaces.map((place: PlaceNode) => <option key={place.id} value={place.id}>{placePath(place, targetPlaces)}</option>)}</select>{tree.loading && <Loading/>}{tree.error != null && <ErrorNotice error={tree.error} retry={tree.refresh}/>}</label>}
        {mode === 'migrate' && <><div className="field"><PublishedVersionPicker value={versionId} onChange={id => { setVersionId(id); action.touch() }} lockedProductId={sameProduct ? devices[0].product_id : null} excludedVersionId={devices.length === 1 ? devices[0].product_version_id : null}/></div>{!sameProduct && <p role="alert">批量迁移必须是同一产品；当前选择包含多个产品。</p>}{!allPlaced && <p role="alert">选择中有设备尚未登记真实位置。先逐台明确绑定位置，不能以迁移掩盖缺失归属。</p>}<label className="field">迁移原因<Input required maxLength={1000} value={reason} onChange={event => { setReason(event.target.value); action.touch() }}/></label></>}
        {action.error != null && <ErrorNotice error={action.error}/>}<div className="identity-form-actions"><Button type="button" variant="outline" onClick={onCancel}>{devices.length === 1 ? '取消' : '清除选择'}</Button><Button type="submit">{devices.length === 1 ? '核对设备变更' : '核对批量操作'}</Button></div>
      </> : <OperationConfirmation action={action} title={mode === 'move' ? '确认变更设备位置' : '确认迁移设备版本'} submitLabel={devices.length === 1 ? '确认设备变更' : '确认批量写入'} refresh={onCancel} warning="服务端会逐台按提交的revision重新校验。只写入登记位置/版本关系和不可变历史，不执行设备参数或硬件命令。"><p>归属单位：{devices[0].tenant_name ?? tenantId}</p><p>设备：{devices.map(device => device.name ?? device.device_eui ?? device.id).join('、')}</p><p>{mode === 'move' ? `目标位置：${targetPlaceLabel}` : `目标版本：${versionId} · 原因：${reason}`}</p></OperationConfirmation>}
    </form>
  </CardContent></Card>
}
