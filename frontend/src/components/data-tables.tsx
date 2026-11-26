import { ArrowDown, ArrowUp, ArrowUpRight, Battery, ChevronsUpDown, MapPin, Radio } from 'lucide-react'
import { Fragment, type ReactNode } from 'react'
import type { Alarm, Device, DeviceStatusCode, DeviceStatusDisplay } from '@/lib/types'
import type { DeviceColumnKey, DeviceSort } from '@/lib/device-sort'
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
export const deviceColumnDefinitions: { key: DeviceColumnKey; label: string }[] = [
  { key: 'name', label: '设备名称' },
  { key: 'customer', label: '设备显示状态' },
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

export function DeviceTable({ items, actions, visibleColumns = defaultDeviceColumns, selectedIds, onSelectionChange, onPageSelectionChange, sort, onSort, headerActions, assetLayout = false, showPhysicalState = false }: { items: Device[]; actions?: (device: Device) => ReactNode; visibleColumns?: DeviceColumnKey[]; selectedIds?: ReadonlySet<string>; onSelectionChange?: (device: Device, selected: boolean) => void; onPageSelectionChange?: (items: Device[], selected: boolean) => void; sort?: DeviceSort; onSort?: (column: DeviceColumnKey) => void; headerActions?: Partial<Record<DeviceColumnKey, ReactNode>>; assetLayout?: boolean; showPhysicalState?: boolean }) {
  const management = useSession(s => s.audience === 'management')
  if (!items.length && !assetLayout) return <Empty title="暂无设备记录">请尝试其他搜索条件，或联系管理员检查租户设备授权。</Empty>
  const assetOrder: DeviceColumnKey[] = ['name', 'category', 'eui', 'unit', 'online', 'customer', 'business']
  const assetLabels: Partial<Record<DeviceColumnKey, string>> = { category: '设备类型', eui: '序列号', unit: '所属单位', customer: '设备状态' }
  const columns = deviceColumnDefinitions.filter(({ key }) => key === 'customer' ||
    (key === 'online' ? management : key === 'business' ? management && (!assetLayout || visibleColumns.includes(key)) : visibleColumns.includes(key)))
  if (assetLayout) {
    const rank = (key: DeviceColumnKey) => assetOrder.includes(key) ? assetOrder.indexOf(key) : assetOrder.length
    columns.sort((left, right) => rank(left.key) - rank(right.key))
  }
  const cells: Record<DeviceColumnKey, (device: Device) => ReactNode> = {
    eui: device => <TableCell className="mono">{reported(device.device_eui)}</TableCell>,
    name: device => <TableCell className={assetLayout ? 'asset-name-cell' : undefined}>{assetLayout
      ? <span className="device-cell-text" title={reported(device.name)}>{reported(device.name)}</span>
      : <div className="table-primary"><span className="device-symbol"><Radio size={19}/></span><strong className="device-cell-text" title={reported(device.name)}>{reported(device.name)}</strong></div>}</TableCell>,
    model: device => <TableCell>{reported(device.model)}</TableCell>,
    product: device => <TableCell>{reported(device.product_name)}</TableCell>,
    version: device => <TableCell className="mono">{device.product_version_id ? `v${device.version_number ?? '未知'} · ${device.product_version_id}` : '未绑定'}</TableCell>,
    unit: device => <TableCell>{device.tenant_name ?? device.tenant_id ?? '归属单位未知'}</TableCell>,
    address: device => <TableCell><span className="device-cell-text" title={reported(device.address)}>{reported(device.address)}</span></TableCell>,
    placement: device => <TableCell className={device.place_id ? 'mono' : 'text-amber-700'}><span className="device-cell-text" title={device.place_name ?? device.place_id ?? '需指定实际位置'}>{device.place_name ?? device.place_id ?? '需指定实际位置'}</span></TableCell>,
    system: device => <TableCell>{device.system_type ?? '未人工指定'}</TableCell>,
    category: device => <TableCell>{device.device_category ?? '未人工指定'}</TableCell>,
    customer: device => <TableCell><DeviceStatusBadge code={device.status_display?.customer_status}/></TableCell>,
    online: device => <TableCell><DeviceStatusBadge code={assetLayout ? assetConnectivity(device.connectivity) : device.status_display?.online_status}/></TableCell>,
    business: device => <TableCell><DeviceBusinessStatuses display={device.status_display}/></TableCell>,
    battery: device => <TableCell><span className="battery"><Battery size={15}/>{battery(device.battery_percent, device.battery_percent_json)}</span></TableCell>,
    last: device => <TableCell className="text-muted-foreground text-xs">{dateTime(device.last_message_at)}</TableCell>,
  }
  const allSelected = !!selectedIds && items.length > 0 && items.every(device => selectedIds.has(device.id))
  return <Table className={assetLayout ? 'device-table asset-device-table' : 'device-table'} aria-label={assetLayout ? '设备资产列表' : undefined}>
    <TableHeader><TableRow>
      {onPageSelectionChange && <TableHead className={assetLayout ? 'asset-select-cell' : undefined}><input type="checkbox" aria-label="选择当前页全部设备" disabled={!items.length} checked={allSelected} onChange={event => onPageSelectionChange(items, event.target.checked)}/></TableHead>}
      {columns.map(({ key, label }) => {
        const heading = (assetLayout && assetLabels[key]) || (key === 'customer' && !management ? '状态' : label)
        const direction = sort?.column === key ? sort.direction : undefined
        const Icon = direction === 'asc' ? ArrowUp : direction === 'desc' ? ArrowDown : ChevronsUpDown
        const sortable = !!onSort && !(assetLayout && key === 'online')
        return <TableHead key={key} className={assetLayout && key === 'name' ? 'asset-name-cell' : undefined} aria-sort={sortable && direction ? direction === 'asc' ? 'ascending' : 'descending' : undefined}>
          <div className={assetLayout ? 'asset-column-heading' : undefined}>{sortable ?
            <Button type="button" variant="ghost" size="sm" className="-ml-2 px-2" onClick={() => onSort?.(key)}>
              {heading}<Icon aria-hidden="true" className={direction ? 'text-primary' : 'text-muted-foreground'}/>
            </Button> : <span>{heading}</span>}
            {headerActions?.[key]}
          </div>
        </TableHead>
      })}
      {showPhysicalState && <TableHead>来源火情</TableHead>}
      {actions && <TableHead className={assetLayout ? 'asset-action-cell text-right' : 'text-right'}>操作</TableHead>}
    </TableRow></TableHeader>
    <TableBody>{items.map(device => <TableRow key={device.id} data-device-id={device.id} data-device-placement-required={!device.place_id || undefined}>
      {onSelectionChange && <TableCell className={assetLayout ? 'asset-select-cell' : undefined}><input type="checkbox" aria-label={`选择设备 ${device.name ?? device.id}`} checked={selectedIds?.has(device.id) ?? false} onChange={event => onSelectionChange(device, event.target.checked)}/></TableCell>}
      {columns.map(({ key }) => <Fragment key={key}>{cells[key](device)}</Fragment>)}
      {showPhysicalState && <TableCell><Status value={device.physical_state} dimension="physical"/></TableCell>}
      {actions && <TableCell className={assetLayout ? 'asset-action-cell text-right' : 'text-right'}>{actions(device)}</TableCell>}
    </TableRow>)}{assetLayout && !items.length && <TableRow><TableCell colSpan={columns.length + Number(!!onSelectionChange) + Number(!!actions) + Number(showPhysicalState)}>
      <Empty title="暂无设备记录">请调整范围、系统或搜索条件。</Empty>
    </TableCell></TableRow>}</TableBody>
  </Table>
}

function assetConnectivity(value: string | null): DeviceStatusCode | undefined {
  switch (value) {
    case 'ONLINE': case 'OFFLINE': case 'UNACTIVATED': case 'UNKNOWN': return value
    default: return undefined
  }
}
