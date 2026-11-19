import { lazy, Suspense, useState, type ReactNode } from 'react'
import { BellRing, ChevronRight, Radio, Search, Unplug } from 'lucide-react'
import { AlarmReview } from '@/components/alarm-review'
import { Empty, ErrorNotice, Loading, PageHeading, RefreshButton, Status } from '@/components/common'
import { DeviceStatusBadge } from '@/components/data-tables'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { alarmTimeText } from '@/lib/alarm-time'
import { emptyAlarmFilters, validateAlarmWorkbench } from '@/lib/alarm-workbench'
import { api } from '@/lib/api'
import { reported } from '@/lib/format'
import { publishObjectChange, useObjectRevision } from '@/lib/object-events'
import { useSession } from '@/lib/store'
import { allowedPage, type Alarm, type AlarmWorkbenchFilters, type PageName } from '@/lib/types'
import { useResource } from '@/lib/use-resource'

const AlarmDetail = lazy(() => import('@/components/alarm-detail').then(m => ({ default: m.AlarmDetail })))
const DeviceDetail = lazy(() => import('@/components/device-detail').then(m => ({ default: m.DeviceDetail })))
const previewSize = 4
const pendingFilters: AlarmWorkbenchFilters = { ...emptyAlarmFilters, response_state: 'UNHANDLED' }

type Selected = { kind: 'alarm'; row: Alarm } | { kind: 'device'; id: string; tenantId: string }

export function ManagementHome({ onNavigate, overview }: { onNavigate: (page: PageName) => void; overview: ReactNode }) {
  const { permissions, scopeInitialized, scopeTenantIds, scopePlaceIds } = useSession()
  const activeScope = scopeInitialized && (scopeTenantIds.length > 0 || scopePlaceIds.length > 0)
  const scopeKey = JSON.stringify([scopeTenantIds, scopePlaceIds])
  const canReadAlarms = allowedPage('alarms', 'management', permissions)
  const canReview = permissions.includes('alarm:review')
  const canReadDevices = allowedPage('devices', 'management', permissions)
  const [selected, setSelected] = useState<Selected | null>(null)
  const [draft, setDraft] = useState('')
  const [keyword, setKeyword] = useState('')
  const [overviewOpen, setOverviewOpen] = useState(false)
  const eventRevision = useObjectRevision('events')
  const deviceRevision = useObjectRevision('devices')
  const notificationRevision = useObjectRevision('notifications')
  const organizationRevision = useObjectRevision('organization')
  const alarms = useResource(`management-home:pending:${scopeKey}:${eventRevision}:${organizationRevision}`, signal => api.alarmWorkbench(pendingFilters, 1, previewSize, signal, scopeTenantIds, scopePlaceIds), activeScope && canReadAlarms)
  const devices = useResource(`management-home:devices:${scopeKey}:${keyword}:${deviceRevision}:${eventRevision}:${notificationRevision}:${organizationRevision}`, signal => api.devices(1, keyword, previewSize, signal, undefined, undefined, undefined, scopeTenantIds, scopePlaceIds), activeScope && canReadDevices)
  const invalidQueue = alarms.data && !alarms.loading && validateAlarmWorkbench(alarms.data, pendingFilters, 1, previewSize)
  const queue = alarms.loading || alarms.error != null || invalidQueue ? undefined : alarms.data
  const devicePage = devices.loading || devices.error != null ? undefined : devices.data
  const selectedAlarm = selected?.kind === 'alarm' && canReadAlarms ? selected.row : null
  const selectedDevice = selected?.kind === 'device' && canReadDevices ? selected : null
  const reviewLabel = canReview ? '复核警情' : '查看警情'

  return <>
    <PageHeading eyebrow="管理工作空间 / 一线操作" title="处置工作台" description="处理待复核警情，查询设备和故障。左侧单位与位置仅筛选读取范围。">
      {activeScope && (canReadAlarms || canReadDevices) && <RefreshButton onClick={() => { alarms.refresh(); devices.refresh() }}/>}
    </PageHeading>
    {!scopeInitialized ? <Loading/> : !activeScope ? <Empty title="当前范围未选择">请从单位 / 位置筛选栏选择读取范围。</Empty> : !canReadAlarms && !canReadDevices ? <Empty title="当前账户没有警情或设备查看权限"/> :
      <div className={`grid items-start gap-4 ${canReadAlarms && canReadDevices ? 'xl:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]' : ''}`}>
        {canReadAlarms && <section aria-labelledby="pending-alarms" className="min-w-0 overflow-hidden rounded-xl border border-border bg-card">
          <header className="flex flex-wrap items-start justify-between gap-3 border-b border-border p-4">
            <div className="min-w-0">
              <h2 id="pending-alarms" className="flex items-center gap-2"><BellRing size={18} className="text-destructive" aria-hidden="true"/>待处置警情</h2>
              <p className="mt-1 text-xs text-slate-600">人工未处理；物理状态单独列示</p>
            </div>
            <Button type="button" variant="outline" size="sm" onClick={() => onNavigate('alarms')}>报警信息<ChevronRight size={14}/></Button>
          </header>
          <div className="flex items-baseline justify-between gap-2 bg-slate-50 px-4 py-2 text-xs text-slate-600" aria-live="polite">
            <span>待复核 <strong className="ml-1 text-lg text-slate-900">{queue?.total ?? '—'}</strong> 条</span>
            {queue && queue.items.length > 0 && <span>最近 {queue.items.length} 条</span>}
          </div>
          {alarms.loading ? <Loading/> : alarms.error != null ? <ErrorNotice error={alarms.error} retry={alarms.refresh}/> : invalidQueue ? <ErrorNotice error={new Error(invalidQueue)} retry={alarms.refresh}/> : queue?.items.length ?
            <ul className="m-0 list-none divide-y divide-border p-0">{queue.items.map(row => {
              const name = row.device_name || row.device_id
              return <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
                <div className="min-w-0 flex-1 basis-40 break-words">
                  <div className="flex flex-wrap items-center gap-2"><strong>{name}</strong><Status value={row.physical_state} dimension="physical"/></div>
                  <p className="mt-1 text-xs text-slate-600">{reported(row.address)} · {alarmTimeText(row)}</p>
                  {scopeTenantIds.length !== 1 || scopePlaceIds.length > 0 ? <p className="text-xs text-slate-600">单位：{row.tenant_id}</p> : null}
                </div>
                <Button type="button" variant="outline" size="sm" aria-label={`${reviewLabel}：${name}`} onClick={() => setSelected({ kind: 'alarm', row })}>{reviewLabel}</Button>
              </li>
            })}</ul> : <Empty title="当前范围没有待复核警情">人工队列为空不代表设备已恢复。</Empty>}
        </section>}
        {canReadDevices && <section aria-labelledby="device-lookup" className="min-w-0 overflow-hidden rounded-xl border border-border bg-card">
          <header className="flex flex-wrap items-start justify-between gap-3 border-b border-border p-4">
            <div className="min-w-0">
              <h2 id="device-lookup" className="flex items-center gap-2"><Radio size={18} className="text-primary" aria-hidden="true"/>设备查询</h2>
              <p className="mt-1 text-xs text-slate-600">查看资产、监测数据与关联记录</p>
            </div>
            <Button type="button" variant="outline" size="sm" onClick={() => onNavigate('devices')}>设备资产<ChevronRight size={14}/></Button>
          </header>
          <form role="search" aria-label="当前范围设备查询" className="flex flex-wrap items-center gap-2 border-b border-border bg-slate-50 px-4 py-2" onSubmit={event => { event.preventDefault(); setKeyword(draft.trim()) }}>
            <Input type="search" className="min-w-0 flex-1 basis-40" aria-label="首页搜索设备" placeholder="设备名称、编号、型号或地址" value={draft} onChange={event => setDraft(event.target.value)}/>
            <Button type="submit" variant="outline" size="sm"><Search size={15}/>查询</Button>
            {(draft || keyword) && <Button type="button" variant="ghost" size="sm" onClick={() => { setDraft(''); setKeyword('') }}>清空设备查询</Button>}
          </form>
          {devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : devicePage?.items.length ? <>
            <p className="px-4 pt-3 text-xs text-slate-600" role="status">{keyword ? '查询匹配' : '当前范围'} {devicePage.total} 台 · 显示 {devicePage.items.length} 台</p>
            <ul className="m-0 list-none divide-y divide-border p-0">{devicePage.items.map(device => {
              const name = device.name || device.device_eui || device.id
              const tenantId = device.tenant_id
              return <li key={device.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
                <div className="min-w-0 flex-1 basis-40 break-words">
                  <div className="flex flex-wrap items-center gap-2"><strong>{name}</strong><DeviceStatusBadge code={device.status_display?.online_status} label={device.status_display?.online_status ? undefined : '连接未确认'}/></div>
                  <p className="mt-1 text-xs text-slate-600">{device.tenant_name || tenantId || '归属单位未知'} · {device.place_name || device.address || '安装位置未知'}</p>
                </div>
                {tenantId && <Button type="button" variant="outline" size="sm" aria-label={`查看设备：${name}`} onClick={() => setSelected({ kind: 'device', id: device.id, tenantId })}>查看设备</Button>}
              </li>
            })}</ul>
          </> : <Empty title={keyword ? '当前条件没有匹配设备' : '当前范围无设备'}/>}
          <div className="border-t border-border px-4 py-2">
            <Button type="button" variant="ghost" size="sm" onClick={() => onNavigate('device-faults')}><Unplug size={15}/>查看设备故障<ChevronRight size={14}/></Button>
          </div>
        </section>}
      </div>}
    {activeScope && <section className="mt-4">
      <Button type="button" variant="outline" size="sm" aria-expanded={overviewOpen} onClick={() => setOverviewOpen(open => !open)}>{overviewOpen ? '收起运行总览' : '展开运行总览'}</Button>
      {overviewOpen && <div className="mt-4"><Suspense fallback={<Loading/>}>{overview}</Suspense></div>}
    </section>}
    {selectedAlarm && canReview && <AlarmReview alarm={selectedAlarm} onClose={() => setSelected(null)} onReviewed={() => publishObjectChange('events')}/>}
    {selectedAlarm && !canReview && <Suspense fallback={<Loading/>}><AlarmDetail alarmId={selectedAlarm.id} tenantId={selectedAlarm.tenant_id} onClose={() => setSelected(null)} onChanged={alarms.refresh}/></Suspense>}
    {selectedDevice && <Suspense fallback={<Loading/>}><DeviceDetail deviceId={selectedDevice.id} tenantId={selectedDevice.tenantId} close={() => setSelected(null)}/></Suspense>}
  </>
}
