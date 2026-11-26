import { Pagination } from 'antd'
import { useId, type FormEventHandler, type ReactNode } from 'react'
import { ErrorNotice, Loading } from './common'
import { TableViewport } from './ui/table'
import './customer-device-ledger.css'
import './customer-records.css'

export const legacySystems = [
  '烟温火灾探测报警系统', '电气火灾监控预警系统', '消防用水监测报警系统',
  '占道违章侦测报警系统', '泛安全感知探测报警系统', '传统消防接入报警系统',
] as const

export function CustomerSystemTabs({ system = '', onSystem }: { system?: string; onSystem?: (system: string) => void }) {
  return <nav className="legacy-device-systems" aria-label="所属系统">
    {['', ...legacySystems].map(item => <button key={item} type="button" aria-pressed={item === system} disabled={!!item && !onSystem}
      title={item && !onSystem ? '此台账未提供按系统筛选' : undefined} onClick={() => onSystem?.(item)}>{item || '全部'}</button>)}
  </nav>
}

export function CustomerRecordsSearch({ field, searchLabel = field, keyword, onKeyword, onSearch, onReset, children, actions }: {
  field: string; searchLabel?: string; keyword: string; onKeyword: (value: string) => void; onSearch: FormEventHandler<HTMLFormElement>; onReset: () => void;
  children?: ReactNode; actions?: ReactNode
}) {
  return <div className="legacy-record-toolbar">
    <form className="legacy-record-form" aria-label="搜索" onSubmit={onSearch}>
      <span className="legacy-record-field" title={field}>{field}</span>
      <input aria-label={searchLabel} maxLength={100} placeholder="请输入关键词" value={keyword} onChange={e => onKeyword(e.target.value)}/>
      {children}
      <div className="legacy-record-actions">
        <button type="submit" className="legacy-device-button">搜索</button>
        <button type="button" className="legacy-device-button" onClick={onReset}>重置</button>
      </div>
    </form>
    {actions && <div className="legacy-record-actions">{actions}</div>}
  </div>
}

export function CustomerDateRange({ from, to, onFrom, onTo }: {
  from: string; to: string; onFrom: (value: string) => void; onTo: (value: string) => void
}) {
  return <div className="legacy-record-dates" role="group" aria-label="开始和结束时间（北京时间）">
    <label><span>开始时间</span><input aria-label="开始时间（北京时间）" title="开始时间（北京时间，含）" type="datetime-local" value={from} onChange={e => onFrom(e.target.value)}/></label>
    <label><span>结束时间</span><input aria-label="结束时间（北京时间）" title="结束时间（北京时间，不含）" type="datetime-local" value={to} onChange={e => onTo(e.target.value)}/></label>
  </div>
}

type Row = { key: string; cells: ReactNode[] }
export function CustomerRecordTable({ name, columns, rows, loading, error, retry, emptyText = '暂无数据' }: {
  name: string; columns: readonly string[]; rows: Row[]; loading?: boolean; error?: unknown; retry?: () => void; emptyText?: string
}) {
  return <TableViewport className="legacy-record-table-wrap" label={`${name} · 表格区域`}><table className="legacy-record-table" aria-label={name} aria-busy={!!loading}>
    <thead><tr>{columns.map(column => <th scope="col" key={column}>{column}</th>)}</tr></thead><tbody>
      {loading || error != null || rows.length === 0 ? <tr><td colSpan={columns.length} className="legacy-record-empty">
        {loading ? <Loading/> : error != null ? <ErrorNotice error={error} retry={retry}/> : <><span aria-hidden="true"/>{emptyText}</>}
      </td></tr> : rows.map(row => <tr key={row.key}>{row.cells.map((cell, index) => <td key={columns[index]} title={typeof cell === 'string' ? cell : undefined}>{cell ?? '—'}</td>)}</tr>)}
    </tbody>
  </table></TableViewport>
}

export function CustomerRecordPager({ page, size, total, onPage, onSize }: {
  page: number; size: number; total: number; onPage: (page: number) => void; onSize?: (size: number) => void
}) {
  const id = useId(), pages = Math.max(1, Math.ceil(total / size))
  return <nav className="legacy-device-pager" aria-label="结果分页"><span>共 {total} 条</span>
    <Pagination current={page} pageSize={size} total={total} showSizeChanger={!!onSize} pageSizeOptions={[10, 20, 30, 50, 100]} responsive
      onChange={(next, nextSize) => onSize && nextSize !== size ? onSize(nextSize) : onPage(next)}/>
    <form className="legacy-device-jump" onSubmit={event => {
      event.preventDefault()
      const form = event.currentTarget, next = Number(new FormData(form).get('page'))
      if (total > 0 && Number.isSafeInteger(next) && next >= 1 && next <= pages && next !== page) onPage(next)
      form.reset()
    }}><label htmlFor={id}>跳至 <input id={id} name="page" type="number" min={1} max={pages} required disabled={pages === 1} aria-label="跳至页码"/> 页</label></form>
  </nav>
}
