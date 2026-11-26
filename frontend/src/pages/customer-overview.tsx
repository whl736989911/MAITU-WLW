import { lazy, Suspense, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { BellRing, Cctv, Layers, MapPin, Radio, Search, SlidersHorizontal } from 'lucide-react'
import { AlarmReview } from '@/components/alarm-review'
import { ErrorNotice, Loading } from '@/components/common'
import { useLegacyUnits } from '@/components/customer-workspace'
import { legacySystems } from '@/components/customer-records'
import { LegacyCustomerMap } from '@/components/legacy-customer-map'
import { alarmTimeText } from '@/lib/alarm-time'
import { api } from '@/lib/api'
import { emptyDeviceFilters, getDeviceWorkbench } from '@/lib/device-workbench'
import { queryFaults } from '@/lib/duty-api'
import { dateTime, reported } from '@/lib/format'
import { publishObjectChange, useObjectRevision } from '@/lib/object-events'
import { placeTreeOptions, type PlaceTreeOption } from '@/lib/place-tree'
import { useSession } from '@/lib/store'
import { allowedPage, type Alarm, type Device } from '@/lib/types'
import { useResource } from '@/lib/use-resource'
import './customer-console.css'

const DeviceDetail = lazy(() => import('@/components/device-detail').then(module => ({ default: module.DeviceDetail })))
const initialSearch = { keyword: '', state: '', product: '', system: '' }

function ConsolePanel({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return <section className="legacy-console-panel" aria-label={title}><header><h2>{title}</h2>{actions}</header>{children}</section>
}

function LocationList({ items, current, select }: { items: PlaceTreeOption[]; current: string; select: (id: string) => void }) {
  return <ul className="legacy-location-tree">{items.map(item => <li key={item.key}>{item.children.length
    ? <details open><summary>{item.title}</summary><button type="button" aria-pressed={current === item.key} onClick={() => select(item.key)}>查看 {item.title}</button><LocationList items={item.children} current={current} select={select}/></details>
    : <button type="button" aria-pressed={current === item.key} onClick={() => select(item.key)}>{item.title}</button>}</li>)}</ul>
}

export function CustomerOverviewPage({ openSystem }: { openSystem?: (system: string) => void } = {}) {
  const { scopeInitialized, scopeTenantIds, scopePlaceIds, user, permissions, navigate } = useSession()
  const units = useLegacyUnits()
  const eventsRevision = useObjectRevision('events'), devicesRevision = useObjectRevision('devices'), unitRevision = useObjectRevision('organization')
  const scope = JSON.stringify([scopeTenantIds, scopePlaceIds, unitRevision])
  const ready = scopeInitialized && (scopeTenantIds.length > 0 || scopePlaceIds.length > 0)
  const readAlarms = allowedPage('alarms', 'customer', permissions)
  const readDevices = allowedPage('devices', 'customer', permissions)
  const summary = useResource(`legacy-summary:${scope}:${eventsRevision}:${devicesRevision}`, signal => api.summary(signal, scopeTenantIds, scopePlaceIds), ready && !readDevices)
  const deviceStatus = useResource(`legacy-metrics:${scope}:${eventsRevision}:${devicesRevision}`, signal => getDeviceWorkbench(emptyDeviceFilters, 1, 1, signal), ready && readDevices)
  const alarms = useResource(`legacy-alarm-feed:${scope}:${eventsRevision}`, signal => api.alarms(1, 10, signal, scopeTenantIds, scopePlaceIds), ready && readAlarms)
  const faults = useResource(`legacy-fault-feed:${scope}:${eventsRevision}`, signal => queryFaults(1, '', '', '', signal), ready && readDevices)
  const [view, setView] = useState<'devices' | 'plan'>('devices')
  const [activeAlarm, setActiveAlarm] = useState<Alarm | null>(null)
  const [activeDevice, setActiveDevice] = useState<Device | null>(null)
  const [form, setForm] = useState(initialSearch)
  const [submitted, setSubmitted] = useState<typeof initialSearch | null>(null)
  const [position, setPosition] = useState('')
  const matches = useResource(`legacy-map-search:${scope}:${devicesRevision}:${position}:${JSON.stringify(submitted)}`, signal => {
    const query = submitted!
    return api.devices(1, query.keyword, 10, signal, query.keyword ? 'eui' : undefined, position || undefined, undefined, scopeTenantIds, scopePlaceIds, {
      connectivity: ['OFFLINE', 'UNACTIVATED', 'UNKNOWN', 'ONLINE'].includes(query.state) ? query.state : undefined,
      physical_state: ['NORMAL', 'FIRE'].includes(query.state) ? query.state : undefined,
      device_category: query.product,
      system_type: query.system,
    })
  }, ready && readDevices && submitted != null)
  const places = units?.data?.[0]?.places
  const tree = useMemo(() => placeTreeOptions(places ?? []), [places])
  const metrics = deviceStatus.data?.metrics
  const counts = [
    ['总数', metrics?.device_total ?? summary.data?.device_total, '已授权范围内的设备'],
    ['正常', metrics?.normal, '来源物理状态为正常，不代表设备在线'],
    ['警报', metrics?.fire, '来源物理状态为火警，不代表人工未复核'],
    ['故障', null, '历史故障不等于当前故障设备数'],
    ['离线', metrics?.offline ?? summary.data?.offline, '按实际离线策略计算'],
    ['屏蔽', null, '现有汇总未提供屏蔽设备数量'],
    ['未激活', metrics?.unactivated ?? summary.data?.unactivated, '未收到来源上报'],
  ] as const
  const recent = [
    ...(alarms.data?.items ?? []).map(event => ({ key: event.id, name: event.device_name, date: alarmTimeText(event), time: event.occurred_at, status: event.response_state === 'HANDLED' ? '已复核' : event.response_state === 'UNHANDLED' ? '未复核' : '复核状态未确认', label: '报警', open: () => setActiveAlarm(event) })),
    ...(faults.data?.items ?? []).map(event => ({ key: event.id, name: event.device_name, date: event.observed_at ? dateTime(event.observed_at) : '来源时间未提供', time: event.observed_at, status: event.handling?.handled ? '已复核' : '未复核', label: '故障', open: () => navigate('device-faults') })),
  ].sort((left, right) => (right.time ? Date.parse(right.time) : -Infinity) - (left.time ? Date.parse(left.time) : -Infinity)).slice(0, 10)
  const availableSystems = [
    ...(readDevices ? legacySystems : []),
    ...(permissions.includes('video:read') ? ['视频监控系统'] : []),
  ]

  function choosePlace(id: string) {
    setPosition(id)
    if (readDevices) setSubmitted(submitted ?? initialSearch)
  }

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted({ ...form, keyword: form.keyword.trim(), product: form.product.trim(), system: form.system.trim() })
    matches.refresh()
  }

  if (activeDevice) return <div className="legacy-home-detail"><Suspense fallback={<Loading/>}><DeviceDetail deviceId={activeDevice.id} tenantId={activeDevice.tenant_id} close={() => setActiveDevice(null)}/></Suspense></div>

  return <section className="legacy-console" aria-label="控制台">
    {view === 'devices' ? <LegacyCustomerMap/> : <div className="legacy-plan" role="region" aria-label="点位图画布"><Layers size={32}/><p>暂无点位图</p></div>}
    <div className="legacy-console-left">
      <dl className="legacy-console-stats" aria-label="设备统计" aria-busy={summary.loading || deviceStatus.loading}>
        {counts.map(([label, value, meaning]) => <div key={label} title={meaning}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}
      </dl>
      {summary.error != null && <ErrorNotice error={summary.error} retry={summary.refresh}/>}
      {deviceStatus.error != null && <ErrorNotice error={deviceStatus.error} retry={deviceStatus.refresh}/>}
      {availableSystems.length > 0 && <div className="legacy-console-systems" aria-label="子系统">{availableSystems.map(title => {
        const Icon = title === '视频监控系统' ? Cctv : Radio
        return <button key={title} type="button" onClick={() => openSystem ? openSystem(title) : navigate('customer-subsystem')}><Icon size={22}/>{title}</button>
      })}</div>}
    </div>
    <div className="legacy-console-right">
      {readDevices && <form className="legacy-console-search" onSubmit={search}>
        <div className="legacy-searchbar"><label><span className="sr-only">设备编号</span><input name="device-number" placeholder="请输入设备编号" value={form.keyword} onChange={event => setForm(old => ({ ...old, keyword: event.target.value }))}/></label><button type="submit" aria-label="搜索设备" title="搜索设备"><Search size={17}/></button></div>
        <div className="legacy-console-filters"><select aria-label="设备状态" value={form.state} onChange={event => setForm(old => ({ ...old, state: event.target.value }))}><option value="">设备状态</option><option value="NORMAL">正常</option><option value="FIRE">警报</option><option value="ONLINE">在线</option><option value="OFFLINE">离线</option><option value="UNACTIVATED">未激活</option><option value="UNKNOWN">链路未确认</option></select><input aria-label="产品类型" placeholder="产品类型" maxLength={80} value={form.product} onChange={event => setForm(old => ({ ...old, product: event.target.value }))}/><input aria-label="所属系统" placeholder="所属系统" maxLength={80} value={form.system} onChange={event => setForm(old => ({ ...old, system: event.target.value }))}/><button type="submit" aria-label="应用设备筛选" title="应用设备筛选"><SlidersHorizontal size={14}/></button></div>
        {submitted && <div className="legacy-console-results">
          {matches.loading ? <Loading/> : matches.error != null ? <ErrorNotice error={matches.error} retry={matches.refresh}/> : matches.data?.items.length ? <ul>{matches.data.items.map(device => <li key={device.id}><span><strong>{reported(device.name)}</strong><small>{reported(device.device_eui)} · {reported(device.address)}</small></span><button type="button" onClick={() => setActiveDevice(device)}>设备详情</button></li>)}</ul> : <p>暂无匹配设备</p>}
          <button type="button" onClick={() => { setSubmitted(null); setForm(initialSearch) }}>清除搜索</button>
        </div>}
      </form>}
      <ConsolePanel title="设备位置" actions={units && user?.tenant_id && permissions.includes('units:read') ? <button type="button" onClick={units.refresh}>更新位置</button> : undefined}>
        {units?.loading ? <Loading/> : units?.error != null ? <ErrorNotice error={units.error} retry={units.refresh}/> : tree.length ? <><button type="button" aria-pressed={!position} onClick={() => choosePlace('')}><MapPin size={14}/>所有位置</button><LocationList items={tree} current={position} select={choosePlace}/></> : <p className="legacy-console-empty">{permissions.includes('units:read') ? '暂无位置' : '没有位置读取权限'}</p>}
      </ConsolePanel>
      {(readAlarms || readDevices) && <ConsolePanel title="报警、故障处置情况" actions={<button type="button" onClick={() => { if (readAlarms) alarms.refresh(); if (readDevices) faults.refresh() }}>更新</button>}>
        {alarms.error != null && <ErrorNotice error={alarms.error} retry={alarms.refresh}/>}
        {faults.error != null && <ErrorNotice error={faults.error} retry={faults.refresh}/>}
        {recent.length ? <ol className="legacy-console-events">{recent.map(event => <li key={`${event.label}:${event.key}`}><button type="button" onClick={event.open}><BellRing size={13}/><span>{reported(event.name)} · {event.label} · {event.status}<small>{event.date}</small></span></button></li>)}</ol> : alarms.loading || faults.loading ? <Loading/> : alarms.error == null && faults.error == null ? <p className="legacy-console-empty">暂无数据</p> : null}
      </ConsolePanel>}
    </div>
    <div className="legacy-mode-switch" aria-label="地图视图"><button type="button" aria-pressed={view === 'devices'} onClick={() => setView('devices')}><Radio size={23}/>设备</button><button type="button" aria-pressed={view === 'plan'} onClick={() => setView('plan')}><Layers size={23}/>点位图</button></div>
    <AlarmReview alarm={activeAlarm} onClose={() => setActiveAlarm(null)} onReviewed={() => publishObjectChange('events')}/>
  </section>
}
