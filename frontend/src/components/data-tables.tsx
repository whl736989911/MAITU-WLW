import { ArrowUpRight, Battery, MapPin, Radio } from 'lucide-react'
import { Fragment, type ReactNode } from 'react'
import type { Alarm, Device, DeviceStatusCode, DeviceStatusDisplay } from '@/lib/types'
import { useSession } from '@/lib/store'
import { battery, dateTime, reported, statusLabel } from '@/lib/format'
import { alarmTimeText } from '@/lib/alarm-time'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Button } from './ui/button'
import { Empty, Status } from './common'
import { RowActions } from './page-tools'
import { Badge } from './ui/badge'

export function AlarmTable({ items, onSelect, onDetailSelect, compact = false, actions }: { items: Alarm[]; onSelect?: (alarm: Alarm) => void; onDetailSelect?: (alarm: Alarm) => void; compact?: boolean; actions?: (alarm: Alarm) => ReactNode }) {
  if (!items.length) return <Empty title="暂无报警记录">当前租户没有报警记录。</Empty>
  return <Table className="alarm-table"><TableHeader><TableRow><TableHead>报警设备 / 位置</TableHead>{!compact && <TableHead>事件类型</TableHead>}<TableHead>物理状态</TableHead><TableHead>人工处置</TableHead><TableHead>来源发生时间</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader><TableBody>{items.map(alarm => <TableRow key={alarm.id} data-alarm-id={alarm.id}><TableCell><div className="table-primary"><span className="device-symbol"><Radio size={17}/></span><div><strong>{reported(alarm.device_name)}</strong><div className="table-sub"><MapPin size={12}/>{reported(alarm.address)}</div></div></div></TableCell>{!compact && <TableCell>{statusLabel(alarm.event_type)}</TableCell>}<TableCell><Status value={alarm.physical_state} dimension="physical"/></TableCell><TableCell><Status value={alarm.response_state} dimension="response"/></TableCell><TableCell className="text-muted-foreground text-xs">{alarmTimeText(alarm)}</TableCell><TableCell className="text-right"><div className="row-actions">{onSelect && <Button type="button" variant="outline" size="sm" onClick={() => onSelect(alarm)}>查看<ArrowUpRight size={14}/></Button>}{onDetailSelect && <Button type="button" variant="ghost" size="sm" onClick={() => onDetailSelect(alarm)}>查看详情</Button>}{actions?.(alarm)}</div></TableCell></TableRow>)}</TableBody></Table>}
export type DeviceColumnKey = 'eui' | 'name' | 'model' | 'product' | 'version' | 'unit' | 'address' | 'placement' | 'system' | 'category' | 'online' | 'business' | 'customer' | 'battery' | 'last'
export const deviceColumnDefinitions: { key: DeviceColumnKey; label: string }[] = [
  { key: 'name', label: '设备名称' },
  { key: 'customer', label: '用户端状态' },
  { key: 'online', label: '在线状态' },
  { key: 'business', label: '业务状态' },
  { key: 'eui', label: '设备编号' },
  { key: 'model', label: '设备型号' },
  { key: 'product', label: '产品' },
  { key: 'version', label: '固定版本' },
  { key: 'unit', label: '归属单位' },
  { key: 'address', label: '安装地址' },
  { key: 'placement', label: '归属位置' },
  { key: 'system', label: '所属系统' },
  { key: 'category', label: '设备类别' },
  { key: 'battery', label: '电池电量' },
  { key: 'last', label: '最后上报' },
]
export const defaultDeviceColumns: DeviceColumnKey[] = ['eui', 'name', 'placement', 'battery', 'last']
export const configurableDeviceColumns = deviceColumnDefinitions.filter(({ key }) => key !== 'customer' && key !== 'online' && key !== 'business')
const statusText: Record<DeviceStatusCode,string> = { FIRE: '报警', OFFLINE: '离线', DISABLED: '禁用', SHIELDED: '屏蔽', FAULT: '故障', ONLINE: '在线', UNACTIVATED: '未激活', UNKNOWN: '状态未确认' }

export function DeviceStatusBadge({ code, label }: { code?: DeviceStatusCode; label?: string }) {
  const tone = code === 'FIRE' ? 'danger' : code === 'ONLINE' ? 'success' : code === 'FAULT' ? 'warning' : 'neutral'
  return <Badge variant="outline" className={`status status-${tone}`}><span className="status-dot"/>{label ?? (code ? statusText[code] : '—')}</Badge>
}

export function DeviceBusinessStatuses({ display }: { display?: DeviceStatusDisplay }) {
  const statuses = display?.business_statuses
  const primary = statuses?.[0]
  if (!statuses || !primary) return <>—</>
  if (statuses.length === 1) return <DeviceStatusBadge code={primary.code} label={primary.label}/>
  return <RowActions label="查看全部业务状态" text={<><DeviceStatusBadge code={primary.code} label={primary.label}/><span>+{statuses.length - 1}</span></>}>
    <ul>{statuses.map(status => <li key={`${status.code}:${status.label}`}>{status.label}</li>)}</ul>
  </RowActions>
}

export function DeviceTable({ items, actions, visibleColumns = defaultDeviceColumns, selectedIds, onSelectionChange, onPageSelectionChange }: { items: Device[]; actions?: (device: Device) => ReactNode; visibleColumns?: DeviceColumnKey[]; selectedIds?: ReadonlySet<string>; onSelectionChange?: (device: Device, selected: boolean) => void; onPageSelectionChange?: (items: Device[], selected: boolean) => void }) {
  const management = useSession(s => s.audience === 'management')
  if (!items.length) return <Empty title="暂无设备记录">请尝试其他搜索条件，或联系管理员检查租户设备授权。</Empty>
  const columns = deviceColumnDefinitions.filter(({ key }) =>
    key === 'customer' || (key === 'online' || key === 'business' ? management : visibleColumns.includes(key)))
  const cells: Record<DeviceColumnKey, (device: Device) => ReactNode> = {
    eui: device => <TableCell className="mono">{reported(device.device_eui)}</TableCell>,
    name: device => <TableCell><div className="table-primary"><span className="device-symbol"><Radio size={19}/></span><strong className="device-cell-text" title={reported(device.name)}>{reported(device.name)}</strong></div></TableCell>,
    model: device => <TableCell>{reported(device.model)}</TableCell>,
    product: device => <TableCell>{reported(device.product_name)}</TableCell>,
    version: device => <TableCell className="mono">{device.product_version_id ? `v${device.version_number ?? '未知'} · ${device.product_version_id}` : '未绑定'}</TableCell>,
    unit: device => <TableCell>{device.tenant_name ?? device.tenant_id ?? '归属单位未知'}</TableCell>,
    address: device => <TableCell><span className="device-cell-text" title={reported(device.address)}>{reported(device.address)}</span></TableCell>,
    placement: device => <TableCell className={device.place_id ? 'mono' : 'text-amber-700'}><span className="device-cell-text" title={device.place_name ?? device.place_id ?? '需指定实际位置'}>{device.place_name ?? device.place_id ?? '需指定实际位置'}</span></TableCell>,
    system: device => <TableCell>{device.system_type ?? '未人工指定'}</TableCell>,
    category: device => <TableCell>{device.device_category ?? '未人工指定'}</TableCell>,
    customer: device => <TableCell><DeviceStatusBadge code={device.status_display?.customer_status}/></TableCell>,
    online: device => <TableCell><DeviceStatusBadge code={device.status_display?.online_status}/></TableCell>,
    business: device => <TableCell><DeviceBusinessStatuses display={device.status_display}/></TableCell>,
    battery: device => <TableCell><span className="battery"><Battery size={15}/>{battery(device.battery_percent, device.battery_percent_json)}</span></TableCell>,
    last: device => <TableCell className="text-muted-foreground text-xs">{dateTime(device.last_message_at)}</TableCell>,
  }
  const allSelected = !!selectedIds && items.every(device => selectedIds.has(device.id))
  return <Table className="device-table">
    <TableHeader><TableRow>
      {onPageSelectionChange && <TableHead><input type="checkbox" aria-label="选择当前页全部设备" checked={allSelected} onChange={event => onPageSelectionChange(items, event.target.checked)}/></TableHead>}
      {columns.map(({ key, label }) => <TableHead key={key}>{key === 'customer' && !management ? '状态' : label}</TableHead>)}
      {actions && <TableHead className="text-right">操作</TableHead>}
    </TableRow></TableHeader>
    <TableBody>{items.map(device => <TableRow key={device.id} data-device-id={device.id} data-device-placement-required={!device.place_id || undefined}>
      {onSelectionChange && <TableCell><input type="checkbox" aria-label={`选择设备 ${device.name ?? device.id}`} checked={selectedIds?.has(device.id) ?? false} onChange={event => onSelectionChange(device, event.target.checked)}/></TableCell>}
      {columns.map(({ key }) => <Fragment key={key}>{cells[key](device)}</Fragment>)}
      {actions && <TableCell className="text-right">{actions(device)}</TableCell>}
    </TableRow>)}</TableBody>
  </Table>
}
