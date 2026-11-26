import { Popover } from 'antd'
import { ChevronLeft, Filter } from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { ErrorNotice, Loading } from './common'
import { dateTime } from '@/lib/format'
import type { DeviceStatusCode, Paginated } from '@/lib/types'
import { CustomerRecordPager } from './customer-records'
import { TableViewport } from './ui/table'

const statuses: Partial<Record<DeviceStatusCode, string>> = {
  ONLINE: '正常', FIRE: '警报', FAULT: '故障', OFFLINE: '离线', UNACTIVATED: '未激活', SHIELDED: '屏蔽', DISABLED: '禁用',
}
const filterStatuses = ['ONLINE', 'FIRE', 'FAULT', 'OFFLINE', 'UNACTIVATED', 'SHIELDED'] as const

export function CustomerDeviceStatusFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const [draft, setDraft] = useState<string[]>([])
  const [open, setOpen] = useState(false)
  function apply(next: string[]) { onChange(next.join(',')); setOpen(false) }
  return <Popover open={open} onOpenChange={next => { if (next) setDraft(value ? value.split(',') : []); setOpen(next) }}
    placement="bottomLeft" trigger="click" content={<form className="legacy-device-status-filter" onSubmit={event => { event.preventDefault(); apply(draft) }}>
      {filterStatuses.map(code => <label key={code}><input type="checkbox" checked={draft.includes(code)}
        onChange={event => setDraft(current => event.target.checked ? [...current, code] : current.filter(item => item !== code))}/>{statuses[code]}</label>)}
      <div><button type="submit" className="legacy-device-link">筛选</button>
        <button type="button" className="legacy-device-link" aria-label="重置设备状态" onClick={() => apply([])}>重置</button></div>
    </form>}>
    <button type="button" className="legacy-device-link legacy-device-filter-mark" aria-label="按设备状态筛选" aria-expanded={open}
      data-active={!!value}><Filter size={12} aria-hidden="true"/></button>
  </Popover>
}

export function CustomerDeviceStatus({ code }: { code?: DeviceStatusCode }) {
  return code && statuses[code] ? <span className="legacy-device-status" data-status={code}>{statuses[code]}</span> : <>—</>
}


export function CustomerDeviceSearch({ fields, initialField, draft, onDraft, onSearch, onReset, children }: {
  fields: ReadonlyArray<readonly [string, string]>; initialField?: string; draft: string; onDraft: (value: string) => void
  onSearch: (field: string, keyword: string) => void; onReset: () => void; children?: ReactNode
}) {
  const [field, setField] = useState(initialField ?? fields[0][0])
  function submit(event: FormEvent) { event.preventDefault(); onSearch(field, draft.trim()) }
  return <div className="legacy-device-toolbar">
    <form className="legacy-device-search" onSubmit={submit}>
      <select aria-label="搜索字段" value={field} onChange={event => setField(event.target.value)}>{fields.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
      <input type="text" aria-label="搜索设备" placeholder="请输入关键词" maxLength={100} value={draft} onChange={event => onDraft(event.target.value)}/>
      <button type="submit" className="legacy-device-button">搜索</button>
      <button type="button" className="legacy-device-button" onClick={() => { setField(fields[0][0]); onReset() }}>重置</button>
    </form>
    {children && <div className="legacy-device-tools">{children}</div>}
  </div>
}

export function CustomerDeviceTools({ kind, create, transfer, children }: {
  kind: '无线终端' | '网关设备' | '视频设备'; create?: () => void; transfer?: (mode: 'export' | 'import') => ReactNode; children?: ReactNode
}) {
  return <>
    {transfer?.('export') ?? <button type="button" className="legacy-device-button" disabled>批量导出</button>}
    <button type="button" className="legacy-device-button" onClick={create} disabled={!create}>添加{kind}</button>
    {transfer?.('import') ?? <button type="button" className="legacy-device-button" disabled>批量添加</button>}
    <button type="button" className="legacy-device-button legacy-device-danger" disabled title="当前没有批量删除权限">批量删除</button>
    {children}
  </>
}

type LedgerRow = { id: string; eui?: string | null; name: string | null; model?: string | null; address?: string | null; status?: ReactNode; last?: string | null }
type Selection = { has: (id: string) => boolean; set: (id: string, value: boolean) => void; setPage: (value: boolean) => void }
export function CustomerDeviceTable<T extends LedgerRow>({ page, size, list, gateway, open, edit, selection, setPage, setSize, statusFilter }: {
  page: number; size: number; list: { data?: Paginated<T>; loading: boolean; error?: unknown; refresh: () => void }
  gateway?: boolean; open: (row: T) => void; edit?: (row: T) => void; selection?: Selection
  setPage: (value: number) => void; setSize?: (value: number) => void
  statusFilter?: ReactNode
}) {
  const rows = list.data?.items ?? []
  return <>
    <TableViewport className="legacy-device-table-wrap" label={gateway ? '网关设备 · 表格区域' : '设备信息 · 表格区域'}>
      <table className="legacy-device-table" aria-label={gateway ? '网关设备' : '设备信息'} aria-busy={list.loading}>
      <thead><tr><th scope="col"><input type="checkbox" aria-label="选择当前页全部设备" disabled={!selection || !rows.length}
        checked={!!selection && !!rows.length && rows.every(row => selection.has(row.id))} onChange={event => selection?.setPage(event.target.checked)}/></th>
        <th scope="col">{gateway ? '网关MAC' : '设备编号'}</th><th scope="col">{gateway ? '网关名称' : '设备名称'}</th>
        <th scope="col">{gateway ? '网关型号' : '设备型号'}</th><th scope="col">安装位置</th>
        <th scope="col">设备状态{statusFilter ?? <Filter size={12} className="legacy-device-filter-mark" aria-hidden="true"/>}</th>
        <th scope="col">最后一次消息时间</th><th scope="col">操作</th></tr></thead>
      <tbody>{list.loading || list.error != null || !rows.length ? <tr><td colSpan={8} className="legacy-device-empty">
        {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : <><span aria-hidden="true"/>暂无数据</>}
      </td></tr> : rows.map(row => <tr key={row.id}>
        <td><input type="checkbox" aria-label={`选择设备 ${row.name ?? row.eui ?? row.id}`} disabled={!selection}
          checked={selection?.has(row.id) ?? false} onChange={event => selection?.set(row.id, event.target.checked)}/></td>
        <td>{row.eui ? <button type="button" className="legacy-device-link" onClick={() => open(row)} title={row.eui}>{row.eui}</button> : '—'}</td>
        <td title={row.name ?? undefined}>{row.name ?? '—'}</td><td title={row.model ?? undefined}>{row.model ?? '—'}</td>
        <td title={row.address ?? undefined}>{row.address ?? '—'}</td><td>{row.status ?? '—'}</td><td>{row.last ? dateTime(row.last) : '—'}</td>
        <td><div className="legacy-device-row-actions"><button type="button" className="legacy-device-link" onClick={() => open(row)}>详情</button>
          <button type="button" className="legacy-device-link" onClick={() => edit?.(row)} disabled={!edit}>编辑</button>
          <button type="button" className="legacy-device-link legacy-device-delete" disabled title="当前没有删除权限">删除</button></div></td>
      </tr>)}</tbody>
    </table></TableViewport>
    {list.data && <CustomerRecordPager page={page} size={list.data.size || size} total={list.data.total} onPage={setPage} onSize={setSize}/>}
  </>
}

export function CustomerDeviceCard({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return <section className="legacy-device-card"><header><h2>{title}</h2>{action && <div className="legacy-device-tools">{action}</div>}</header><div className="legacy-device-card-content">{children}</div></section>
}

export function CustomerDeviceBack({ onClick }: { onClick: () => void }) {
  return <button className="legacy-device-link legacy-device-back" type="button" onClick={onClick}><ChevronLeft size={16}/>返回</button>
}
