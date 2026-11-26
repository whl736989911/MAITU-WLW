import { lazy, Suspense, useState, type ReactNode } from 'react'
import { deviceDetailApi } from '@/lib/device-detail'
import { dateTime } from '@/lib/format'
import { useObjectRevision } from '@/lib/object-events'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import type { Device } from '@/lib/types'
import { CustomerDeviceBack, CustomerDeviceCard, CustomerDeviceStatus } from './customer-device-ledger'
import { ErrorNotice, Loading } from './common'
import { DeviceControlPanel } from './device-detail-actions'

const DeviceRecords = lazy(() => import('./device-detail').then(module => ({ default: module.DeviceDetail })))

function Fields({ rows }: { rows: ReadonlyArray<readonly [string, ReactNode]> }) {
  return <dl className="legacy-device-fields">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}</dl>
}

export default function CustomerDeviceDetail({ deviceId, tenantId, close, onEdit }: {
  deviceId: string; tenantId: string; close: () => void; onEdit?: (device: Device) => void
}) {
  const [showRecords, setShowRecords] = useState(false)
  const assets = useObjectRevision('devices'), events = useObjectRevision('events'), notifications = useObjectRevision('notifications')
  const canReadServices = useSession(s => s.permissions.includes('device-services:read'))
  const detail = useResource(`legacy-device:${tenantId}:${deviceId}:${assets}:${events}:${notifications}`,
    signal => deviceDetailApi.detail(deviceId, signal, tenantId))
  const device = detail.data?.device
  const edit = device && onEdit && Number.isSafeInteger(device.revision)
    ? <button type="button" className="legacy-device-button" onClick={() => onEdit(device)}>编辑</button> : undefined
  if (showRecords) return <Suspense fallback={<Loading/>}><DeviceRecords deviceId={deviceId} tenantId={tenantId}
    close={() => setShowRecords(false)} onEdit={onEdit}/></Suspense>
  return <div className="legacy-device-detail legacy-terminal-detail">
    <CustomerDeviceBack onClick={close}/>
    {detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : device && <>
      <CustomerDeviceCard title="设备信息" action={canReadServices ? <>
        <DeviceControlPanel deviceId={deviceId} tenantId={tenantId} deviceName={device.name} appearance="legacy"/>{edit}
      </> : edit}>
        <Fields rows={[
          ['设备名称', device.name], ['设备型号', device.model], ['设备类型', device.device_category],
          ['设备状态', <CustomerDeviceStatus code={device.status_display?.customer_status}/>],
          ['所属系统', device.system_type], ['所属位置', device.place_name], ['注册时间', null],
          ['最后一次消息时间', device.last_message_at ? dateTime(device.last_message_at) : null],
          ['屏蔽此设备', device.status_display?.business_statuses.some(status => status.code === 'SHIELDED') ? '已屏蔽' : null],
          ['是否推送', null], ['设备编号', device.device_eui], ['入网模式', null], ['网关MAC', null],
        ]}/>
        <h3>设备属性（固定值）</h3>
        <Fields rows={[
          ['产品名称', device.product_name], ['产品型号', device.model], ['绑定版本', device.version_number == null ? null : `v${device.version_number}`],
        ]}/>
        <button type="button" className="legacy-device-link" onClick={() => setShowRecords(true)}>设备检测值与记录</button>
      </CustomerDeviceCard>
      <CustomerDeviceCard title="安装信息" action={edit}>
        <Fields rows={[
          ['安装人', null], ['联系电话', null], ['安装位置', device.address ?? device.place_name], ['安装图片', null], ['GPS坐标', null],
        ]}/>
      </CustomerDeviceCard>
      <CustomerDeviceCard title="联系人信息"><p className="legacy-device-empty-section">未提供此设备的专属联系人</p></CustomerDeviceCard>
      <CustomerDeviceCard title="设备联动">
        <p className="legacy-device-empty-section">未提供此设备的联动配置</p>
      </CustomerDeviceCard>
    </>}
  </div>
}
