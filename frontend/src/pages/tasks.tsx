import { useState } from 'react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import type { ProcessingTask, TaskStatus } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { taskStatusNames } from '@/components/processing-common'

export function TasksPage() {
  const { permissions, scopeTenantIds, scopePlaceIds, scopeInitialized } = useSession()
  const scopeKey = `${scopeInitialized}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [draft, setDraft] = useState(''), [status, setStatus] = useState<TaskStatus | ''>(''), [selected, setSelected] = useState<ProcessingTask | null>(null)
  const tasks = useResource(`processing-tasks:${page}:${keyword}:${status}:${scopeKey}`, signal => api.processingTasks(page, keyword, status, signal, scopeTenantIds, scopePlaceIds), scopeInitialized && permissions.includes('tasks:read'))
  if (!permissions.includes('tasks:read')) return <Empty title="当前账户无处理任务读取权限"/>
  return <><PageHeading eyebrow="PROCESSING · HISTORY" title="历史处理记录" description="查看已保存的任务与执行尝试。"><PageHelp title="任务历史说明"><div className="form-stack"><p>本页只读，不提交、取消、重试或重放任务。</p><p>任务及尝试是历史记录，不代表当前设备状态或报文应用状态；当前应用结果请在消息追踪中查看。</p></div></PageHelp><RefreshButton onClick={tasks.refresh}/></PageHeading><Card className="list-card"><CardContent><form className="list-toolbar" onSubmit={event => { event.preventDefault(); setKeyword(draft.trim()); setPage(1) }}><div className="field"><Label htmlFor="task-keyword">任务 / 来源消息 / 设备</Label><Input id="task-keyword" value={draft} onChange={event => setDraft(event.target.value)}/></div><div className="field"><Label htmlFor="task-status">任务状态</Label><select id="task-status" className="native-select" value={status} onChange={event => { setStatus(event.target.value as TaskStatus | ''); setPage(1) }}><option value="">全部</option>{Object.entries(taskStatusNames).map(([value, label]) => <option key={value} value={value}>{value} · {label}</option>)}</select></div><Button type="submit" variant="outline">查询</Button></form>{tasks.loading ? <Loading/> : tasks.error != null ? <ErrorNotice error={tasks.error} retry={tasks.refresh}/> : tasks.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>任务ID</TableHead><TableHead>类型</TableHead><TableHead>来源消息</TableHead><TableHead>设备</TableHead><TableHead>状态</TableHead><TableHead>创建时间</TableHead></TableRow></TableHeader><TableBody>{tasks.data.items.map(task => <TableRow key={task.id}><TableCell><Button variant="link" onClick={() => setSelected(task)}>{task.id}</Button></TableCell><TableCell>{task.kind}</TableCell><TableCell>{task.source_message_id}</TableCell><TableCell>{task.device_name ?? task.device_id}</TableCell><TableCell>{task.status}</TableCell><TableCell>{task.created_at}</TableCell></TableRow>)}</TableBody></Table><Pager page={tasks.data.page} size={tasks.data.size} total={tasks.data.total} onChange={next => setPage(next)}/></> : <Empty title="暂无历史处理记录"/>}</CardContent></Card>{selected && <TaskHistory id={selected.id} tenantId={selected.tenant_id} close={() => setSelected(null)}/>}</>
}

function TaskHistory({ id, tenantId, close }: { id: string; tenantId: string; close: () => void }) {
  const detail = useResource(`processing-task:${id}:${tenantId}`, signal => api.processingTask(id, signal, tenantId))
  return <Sheet open onOpenChange={open => { if (!open) close() }}><SheetContent><SheetHeader><SheetTitle>处理任务详情</SheetTitle><SheetDescription>任务与尝试为只读记录；不提供重试、取消或设备命令执行。</SheetDescription></SheetHeader>{detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : detail.data && <div className="form-stack"><p className="notice">{detail.data.task.kind} · {detail.data.task.status} · revision {detail.data.task.revision}{detail.data.task.kind === 'SIMULATED_MESSAGE_APPLY' ? ' · 历史退役模拟处理，仅用于溯源' : ' · 后台处理状态以本记录为准，不代表额外硬件动作'}</p><dl className="detail-list">{([['任务ID', detail.data.task.id], ['来源', detail.data.task.source_id], ['消息身份', detail.data.task.source_message_id], ['设备ID', detail.data.task.device_id], ['设备名称快照', detail.data.task.device_name], ['提交原因', detail.data.task.reason], ['创建时间', detail.data.task.created_at], ['结束时间', detail.data.task.finished_at], ['错误码', detail.data.task.last_error_code]] as [string, unknown][]).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value == null ? '未提供' : String(value)}</dd></div>)}</dl><h3>尝试历史</h3>{detail.data.attempts.map(attempt => <p className="mono" key={attempt.id}>{attempt.attempt_no} · {attempt.outcome} · {attempt.started_at} · {attempt.error_code ?? '无错误码'}</p>)}</div>}</SheetContent></Sheet>
}
