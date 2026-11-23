import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react'
import { Search, SlidersHorizontal } from 'lucide-react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { compileDeviceDraft, emptyDeviceDraft, emptyDeviceFilters, getDeviceWorkbench, deviceFilterEnums, type DeviceDraft, type DeviceFilters, type DeviceMetrics } from '@/lib/device-workbench'
import { statusLabel } from '@/lib/format'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Card, CardContent } from './ui/card'
import { DeviceTable } from './data-tables'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from './common'
import { DataTransferActions } from './data-transfer-actions'
import { useObjectRevision } from '@/lib/object-events'

const DeviceDetail = lazy(() => import('./device-detail').then(m => ({ default: m.DeviceDetail })))
const metricLabels: [keyof DeviceMetrics, string][] = [['device_total', '匹配设备总数'], ['online', '近期有上报'], ['offline', '有心跳依据的超时'], ['unactivated', '未激活（未上报）'], ['unknown_connectivity', '上报超时，链路未确认'], ['fire', '来源火情：火警'], ['normal', '来源火情：正常'], ['unknown', '来源火情未确认'], ['battery_low', '电量低'], ['battery_normal', '电量正常'], ['battery_not_configured', '未配置电量阈值'], ['battery_no_data', '无电量数据']]

export function DeviceWorkbench({ back }: { back: () => void }) {
  const { user, audience, permissions, generation, scopeTenantIds, scopePlaceIds, scopeInitialized } = useSession()
  if (!user || user.must_change_password || !permissions.includes('device:read') || (audience !== 'management' && !user.tenant_id)) return <Empty title="当前无设备工作台读取范围"/>
  if (!scopeInitialized) return <Loading/>
  if (scopeTenantIds.length === 0 && scopePlaceIds.length === 0) return <Empty title="当前单位/位置范围未选择"/>
  return <Content key={`${generation}:${permissions.join('|')}`} back={back}/>
}

function Content({ back }: { back: () => void }) {
  const { scopeTenantIds, scopePlaceIds } = useSession()
  const [selectedDetail, setSelectedDetail] = useState<{ id: string; tenantId?: string } | null>(null)
  function openDetail(device: { id: string; tenant_id?: string }) { setSelectedDetail({ id: device.id, tenantId: device.tenant_id }) }
  function closeDetail() { setSelectedDetail(null) }
  const [draft, setDraft] = useState<DeviceDraft>({ ...emptyDeviceDraft }), [appliedDraft, setAppliedDraft] = useState({ ...emptyDeviceDraft }), [applied, setApplied] = useState<DeviceFilters>({ ...emptyDeviceFilters })
  const [page, setPage] = useState(1), [size, setSize] = useState<number | undefined>(), [error, setError] = useState<unknown>()
  const devices = useResource(`device-workbench:${JSON.stringify([scopeTenantIds, scopePlaceIds, applied, page, size])}`, signal => getDeviceWorkbench(applied, page, size, signal)), data = devices.data, shown = data?.filters ?? applied
  const deviceRevision = useObjectRevision('devices'), organizationRevision = useObjectRevision('organization'), previous = useRef({ deviceRevision, organizationRevision })
  useEffect(() => {
    if (previous.current.deviceRevision !== deviceRevision || previous.current.organizationRevision !== organizationRevision) devices.refresh()
    previous.current = { deviceRevision, organizationRevision }
  }, [deviceRevision, organizationRevision, devices.refresh])
  function update(key: keyof DeviceDraft, value: string) { setDraft(old => ({ ...old, [key]: value })); setError(undefined) }
  function refresh() { closeDetail(); devices.refresh() }
  function apply(event: FormEvent) { event.preventDefault(); closeDetail(); try { setApplied(compileDeviceDraft(draft)); setAppliedDraft({ ...draft }); setPage(1); setError(undefined); devices.refresh() } catch (error) { setError(error) } }
  function clear() { closeDetail(); setDraft({ ...emptyDeviceDraft }); setAppliedDraft({ ...emptyDeviceDraft }); setApplied({ ...emptyDeviceFilters }); setPage(1); setError(undefined); devices.refresh() }
  if (selectedDetail) return <Suspense fallback={<Loading/>}><DeviceDetail deviceId={selectedDetail.id} tenantId={selectedDetail.tenantId} close={closeDetail}/></Suspense>
  return <section className="device-workbench" data-device-workbench-as-of={data?.as_of}>
    <PageHeading eyebrow="DEVICES · 明确筛选" title="设备筛选工作台" description="按链路诊断、来源火情及登记属性筛选；列表另显示用户端状态。">
      <Button type="button" variant="outline" onClick={() => { closeDetail(); back() }}><SlidersHorizontal/>返回基础设备列表</Button>
      <DataTransferActions kind="DEVICES" filters={{ unit_ids: scopeTenantIds.join(','), place_ids: scopePlaceIds.join(','), ...applied }} onCompleted={refresh}/>
      <Button type="button" variant="outline" onClick={refresh}><Search/>刷新设备工作台</Button>
    </PageHeading>
    <Card><CardContent>
      <form onSubmit={apply} className="device-workbench-filter">
        <div className="device-workbench-grid">
          <div className="field"><Label htmlFor="device-wb-keyword">设备关键字</Label><Input id="device-wb-keyword" placeholder="保存的名称、EUI或地址字面包含" value={draft.keyword} onChange={event => update('keyword', event.target.value)}/></div>
          {([['connectivity', '链路诊断筛选'], ['physical_state', '来源火情筛选']] as const).map(([key, label]) => <div className="field" key={key}><Label htmlFor={`device-wb-${key}`}>{label}</Label><select id={`device-wb-${key}`} className="native-select" value={draft[key]} onChange={event => update(key, event.target.value)}><option value="">全部</option>{deviceFilterEnums[key].map(value => <option key={value} value={value}>{statusLabel(value)}（{value}）</option>)}</select></div>)}
          <div className="field"><Label htmlFor="device-wb-system-type">所属系统</Label><Input id="device-wb-system-type" value={draft.system_type} onChange={event => update('system_type', event.target.value)}/></div>
          <div className="field"><Label htmlFor="device-wb-device-category">设备类别</Label><Input id="device-wb-device-category" value={draft.device_category} onChange={event => update('device_category', event.target.value)}/></div>
        </div>
        <p className="muted-note">链路诊断需要心跳依据；列表在线状态仅按有效上报间隔计算。来源火情不等于人工处置状态。</p>
        <div className="version-actions"><Button type="submit"><Search/>应用设备筛选</Button><Button type="button" variant="outline" onClick={clear}>清空设备条件</Button><label>每页设备工作台条数<select aria-label="每页设备工作台条数" className="native-select" value={size ?? ''} onChange={event => { closeDetail(); setSize(event.target.value ? Number(event.target.value) : undefined); setPage(1) }}><option value="">服务端默认条数</option>{[20, 50, 100].map(value => <option key={value} value={value}>{value}</option>)}</select></label></div>
      </form>
      {JSON.stringify(draft) !== JSON.stringify(appliedDraft) && <p className="notice">设备筛选草稿未应用，刷新/翻页仍用已应用条件。</p>}
      {error != null && <ErrorNotice error={error}/>}
      <p className="device-workbench-applied" data-device-workbench-filters={data ? 'confirmed' : undefined}>{data ? '服务端实际设备条件' : '当前设备请求条件（等待确认）'}：关键字 {shown.keyword || '无条件'} · 连接 {shown.connectivity ?? '全部'} · 物理 {shown.physical_state ?? '全部'} · 系统 {shown.system_type ?? '全部'} · 类别 {shown.device_category ?? '全部'}</p>
      {devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={refresh}/> : !data ? <Empty title="设备工作台尚未确认"/> : <>
        <p className="muted-note">本次工作台求值 {data.as_of} · {data.timezone} · FILTERED_ALL_PAGES · CONNECTIVITY_AND_OFFLINE_EVENTS；非最后上报或提交水位。随后详情是独立求值，不拿本行替代当前详情。</p>
        <div className="device-workbench-metrics">{metricLabels.map(([key, label]) => <div key={key}><span>{label}</span><strong data-device-workbench-metric={key}>{data.metrics[key]}</strong></div>)}</div>
        <div className="device-workbench-table">{data.items.length ? <DeviceTable items={data.items} actions={device => <Button type="button" variant="ghost" size="sm" onClick={() => openDetail(device)}>查看设备详情</Button>}/> : <Empty title={data.total ? '当前设备页为空，匹配总量非0' : '当前设备条件无匹配'}/>}</div>
        <Pager page={data.page} size={data.size} total={data.total} onChange={next => { closeDetail(); setPage(next) }}/>
      </>}
      <p className="muted-note">高级仅只读设备与独立详情；编辑、迁移、绑定历史和开户请返回基础设备列表按已有权限操作。退出高级清空本次条件。</p>
    </CardContent></Card>
  </section>
}
