import type { ReactNode } from 'react'
import { Alert, Empty as AntEmpty, Pagination, Spin } from 'antd'
import { RefreshCw } from 'lucide-react'
import { Badge } from './ui/badge'
import { Button } from './ui/button'
import { errorMessage } from '@/lib/api'
import { reported, statusLabel } from '@/lib/format'

export function Status({ value, dimension }: { value: string | null | undefined; dimension: 'connectivity' | 'physical' | 'response' }) {
  const tone = dimension === 'physical' && value === 'FIRE' ? 'danger' : dimension === 'response' && (value === 'PENDING' || value === 'UNHANDLED') ? 'warning' : (dimension === 'connectivity' && value === 'ONLINE') || (dimension === 'physical' && value === 'NORMAL') || (dimension === 'response' && value === 'HANDLED') ? 'success' : 'neutral'
  return <Badge variant="outline" className={`status status-${tone}`}><span className="status-dot" />{dimension === 'connectivity' && value === 'UNKNOWN' ? '上报超时，离线未确认' : statusLabel(value)}</Badge>
}
export function ErrorNotice({ error, retry }: { error: unknown; retry?: () => void }) {
  return <Alert role="alert" type="error" showIcon className="error-notice" title="操作未完成" description={errorMessage(error)} action={retry && <Button variant="outline" size="sm" onClick={retry}><RefreshCw/>重试</Button>}/>
}
export function Loading() { return <div role="status" className="empty-state"><Spin/><p>正在读取服务端数据…</p></div> }
export function Empty({ title = '暂无记录', children }: { title?: string; children?: ReactNode }) { return <AntEmpty className="empty-state" image={AntEmpty.PRESENTED_IMAGE_SIMPLE} description={<><strong>{title}</strong><p>{children ?? '服务端尚无可显示的记录。'}</p></>}/> }
export function PageHeading({ eyebrow, title, description, children }: { eyebrow: string; title: string; description?: string; children?: ReactNode }) {
  return <div className="page-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1>{description && <p>{description}</p>}</div><div className="heading-actions">{children}</div></div>
}
export function Pager({ page, size, total, onChange }: { page: number; size: number; total: number; onChange: (page: number) => void }) {
  return <div className="pager"><span>共 {reported(total)} 条 · 第 {page} 页</span><Pagination current={page} pageSize={size} total={total} showSizeChanger={false} responsive onChange={onChange}/></div>
}
export function RefreshButton({ onClick }: { onClick: () => void }) { return <Button variant="outline" onClick={onClick}><RefreshCw/>刷新数据</Button> }
