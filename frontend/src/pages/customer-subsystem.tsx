import { useState } from 'react'
import { Radio } from 'lucide-react'
import { AlarmDetail } from '@/components/alarm-detail'
import { CustomerDeviceCard, CustomerDeviceStatus } from '@/components/customer-device-ledger'
import CustomerDeviceDetail from '@/components/customer-device-detail'
import { CustomerRecordPager, CustomerRecordTable } from '@/components/customer-records'
import { Empty, ErrorNotice, Loading } from '@/components/common'
import { LegacyCustomerMap } from '@/components/legacy-customer-map'
import { useLegacyUnits } from '@/components/customer-workspace'
import { VideoResourcesPanel } from '@/components/video-resources-panel'
import { alarmTimeText } from '@/lib/alarm-time'
import { emptyAlarmFilters, validateAlarmWorkbench } from '@/lib/alarm-workbench'
import { api } from '@/lib/api'
import { statusLabel } from '@/lib/format'
import { useObjectRevision } from '@/lib/object-events'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import type { Alarm } from '@/lib/types'
import './customer-console.css'

export function CustomerSubsystemPage({ system }: { system: string }) {
  const { user, permissions, generation } = useSession()
  if (!user?.tenant_id) return <Empty title="当前账号未绑定单位"/>
  if (system === '视频监控系统') {
    if (!permissions.includes('video:read')) return <Empty title="当前账号无视频读取权限"/>
    return <section className="legacy-record-page" aria-label={system}>
      <div className="legacy-subsystem-head"><div className="legacy-subsystem-map"><LegacyCustomerMap/></div><VideoLocation/></div>
      <VideoResourcesPanel tenantId={user.tenant_id} canWrite={false} canPlay={permissions.includes('video:play')}
        canReadDevices={permissions.includes('device:read')} canReadPlaces={permissions.includes('units:read')}/>
    </section>
  }
  if (!permissions.includes('device:read')) return <Empty title="当前账号无设备读取权限"/>
  return <DeviceSubsystem key={`${generation}:${system}`} system={system} tenantId={user.tenant_id} canReadAlarms={permissions.includes('alarm:read')}/>
}

function VideoLocation() {
  const unit = useLegacyUnits()
  return <CustomerDeviceCard title="设备位置">{unit?.loading ? <Loading/> : unit?.error != null ? <ErrorNotice error={unit.error} retry={unit.refresh}/> : unit?.data?.[0]?.places.length ?
    <ul>{unit.data[0].places.map(place => <li key={place.id}>{place.name}</li>)}</ul> : <p>暂无可见位置</p>}</CustomerDeviceCard>
}

function DeviceSubsystem({ system, tenantId, canReadAlarms }: { system: string; tenantId: string; canReadAlarms: boolean }) {
  const [page, setPage] = useState(1), [selectedId, setSelectedId] = useState(''), [open, setOpen] = useState(false)
  const revisions = `${useObjectRevision('devices')}:${useObjectRevision('events')}`
  const devices = useResource(`legacy-subsystem-devices:${tenantId}:${system}:${page}:${revisions}`, signal => api.devices(page, '', 20, signal, 'eui', undefined, undefined, undefined, undefined, { system_type: system }).then(result => {
    if (result.items.some(device => device.tenant_id !== tenantId || device.system_type !== system)) throw new Error('子系统设备范围未确认')
    return result
  }))
  const visible = devices.data?.items ?? []
  const selected = visible.find(device => device.id === selectedId)
  if (open && selected) return <CustomerDeviceDetail deviceId={selected.id} tenantId={tenantId} close={() => setOpen(false)}/>
  return <section className="legacy-record-page legacy-subsystem" aria-label={system}>
    <div className="legacy-subsystem-head">
      <div className="legacy-subsystem-map"><LegacyCustomerMap/></div>
      <aside className="legacy-subsystem-info" aria-label="设备信息">
        <div className="legacy-subsystem-badge"><Radio size={36} aria-hidden="true"/>{selected ? <CustomerDeviceStatus code={selected.status_display?.customer_status}/> : '未选择设备'}</div>
        {devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : visible.length ? <>
          <select aria-label="选择本系统设备" value={selectedId} onChange={event => setSelectedId(event.target.value)}><option value="">选择设备</option>
            {visible.map(device => <option key={device.id} value={device.id}>{device.name ?? device.device_eui ?? device.id}</option>)}</select>
          {devices.data && devices.data.total > devices.data.size && <CustomerRecordPager page={page} size={devices.data.size} total={devices.data.total} onPage={next => { setPage(next); setSelectedId('') }}/>}
        </> : <p>暂无归入本系统的设备</p>}
        <dl>{[['设备名称', selected?.name], ['设备类型', selected?.device_category], ['设备型号', selected?.model], ['设备位置', selected?.address ?? selected?.place_name]].map(([label, value]) => <div key={label}><dt>{label}：</dt><dd>{value ?? '—'}</dd></div>)}</dl>
        {selected && <button type="button" className="legacy-device-link" onClick={() => setOpen(true)}>设备详情</button>}
      </aside>
    </div>
    {canReadAlarms && <SubsystemAlarms system={system} tenantId={tenantId}/>}
  </section>
}

function SubsystemAlarms({ system, tenantId }: { system: string; tenantId: string }) {
  const [page, setPage] = useState(1), [alarm, setAlarm] = useState<Alarm | null>(null)
  const revision = useObjectRevision('events')
  const records = useResource(`subsystem-alarms:${tenantId}:${system}:${page}:${revision}`, signal =>
    api.alarmWorkbench(emptyAlarmFilters, page, 10, signal, [tenantId], undefined, system).then(result => {
      const invalid = validateAlarmWorkbench(result, emptyAlarmFilters, page, 10)
      if (invalid || result.items.some(item => item.tenant_id !== tenantId)) throw new Error(invalid ?? '报警归属未确认')
      return result
    }))
  return <>
    <CustomerRecordTable name="子系统报警" columns={['报警设备名称', '警情点位置', '警情类型', '物理状态', '人工处置', '警情时间', '操作']}
      loading={records.loading} error={records.error} retry={records.refresh} rows={(records.data?.items ?? []).map(item => ({
        key: item.id, cells: [item.device_name, item.address, statusLabel(item.event_type), statusLabel(item.physical_state), statusLabel(item.response_state), alarmTimeText(item),
          <button type="button" className="legacy-device-link" onClick={() => setAlarm(item)}>详情</button>],
      }))}/>
    {records.data && <CustomerRecordPager page={records.data.page} size={records.data.size} total={records.data.total} onPage={setPage}/>}
    <AlarmDetail alarmId={alarm?.id ?? null} tenantId={alarm?.tenant_id} onClose={() => setAlarm(null)} onChanged={records.refresh}/>
  </>
}
