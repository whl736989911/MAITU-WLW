import { useState, type FormEvent } from 'react'
import { Eye, FileText, Play } from 'lucide-react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime, reported, displayDecimal } from '@/lib/format'
import type { CaptureOrigin, Health, IngestPreview, MessageApplicationState, MessageApplyResult, RawMessage, ReparseDetail } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { IdentitySearch, OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { IngestionBoundary, IngestionGate } from '@/components/ingestion-common'
import { ReadingValues } from '@/components/reading-values'
export function MessagesPage({ health }: { health?: Health }) { return <IngestionGate page="messages"><MessagesContent health={health}/></IngestionGate> }
function MessagesContent({ health }: { health?: Health }) {
  const canSources = useSession(s => s.permissions.includes('sources:read'))
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeInitialized = useSession(s => s.scopeInitialized)
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [sourceId, setSourceId] = useState(''), [selectedId, setSelectedId] = useState('')
  const scopeKey = `${scopeInitialized}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`
  const messages = useResource(`messages:${page}:${keyword}:${sourceId}:${scopeKey}`, signal => api.messages(page, keyword, sourceId, signal, scopeTenantIds, scopePlaceIds), scopeInitialized)
  const selectedTenantId = messages.data?.items.find(message => message.id === selectedId)?.tenant_id ?? ''
  return <>
    <PageHeading eyebrow="MESSAGES · 消息追踪" title="通用 HTTP 消息追踪" description="查看通用接入报文的接收、设备绑定、解析与实际应用结果。"><PageHelp title="各类报文去哪里查"><div className="form-stack"><p>这里不是全部设备的报文总表。旧设备到「接入镜像」查看原始接收记录，海康设备到「海康云接入」查看回调和拉取记录。</p><p>列表时间为平台接收时间；来源时间原文不等同于确认的事件发生时间。</p><p>原文访问需独立权限。人工预览不会应用数据；真实重解析或应用始终使用捕获时固定版本，保留服务端冲突与错误状态。</p><p>未绑定的历史消息不会因当前设备目录变化而回填绑定。</p></div></PageHelp></PageHeading>
    <IngestionBoundary health={health}/>
    <Card className="list-card management-list-panel"><CardContent>
      <div className="management-list-bar"><h2>接收记录</h2><div className="list-toolbar management-list-actions"><RefreshButton onClick={messages.refresh}/></div></div>
      <IdentitySearch aria-label="搜索接收消息" placeholder="按消息标识、来源设备标识查询（字面匹配）" onSearch={value => { setPage(1); setKeyword(value) }}>{canSources && <SourceFilter value={sourceId} onChange={id => { setSourceId(id); setPage(1) }}/>}</IdentitySearch>
      {messages.loading ? <Loading/> : messages.error != null ? <ErrorNotice error={messages.error} retry={messages.refresh}/> : messages.data?.items.length ? <>
        <Table><TableHeader><TableRow><TableHead>消息 / 来源</TableHead><TableHead>绑定 / 捕获版本</TableHead><TableHead>平台接收 / 来源时间原文</TableHead><TableHead>原文字节 / 重复</TableHead><TableHead>解析 / 真实处理状态</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
          <TableBody>{messages.data.items.map(message => <TableRow key={message.id}>
            <TableCell><strong>{message.source_message_id}</strong><div className="table-sub">{message.source_name}</div><div className="table-sub mono">来源设备：{message.vendor_device_id}</div><div className="table-sub mono">单位：{message.tenant_id}</div><div className="table-sub">{captureOriginLabel(message.capture_origin)}</div></TableCell>
            <TableCell>{message.binding_state === 'BOUND' ? reported(message.device_name) : '未绑定 · 待绑定（历史消息不回填）'}<div className="table-sub mono">{message.product_version_id ?? '无捕获版本'}</div></TableCell>
            <TableCell>{dateTime(message.received_at)}<div className="table-sub mono">来源时间原文：{reported(message.occurred_at_raw)}</div></TableCell>
            <TableCell>{message.payload_bytes} bytes<div className="table-sub">重复 {message.duplicate_count}</div></TableCell>
            <TableCell><div className="identity-status"><Badge variant="outline">{message.parse_status === 'PREVIEWED' ? '已有人工预览' : '尚未预览'}</Badge><Badge variant="outline">{applicationStateLabel(message.application_state, message.binding_state)}</Badge></div>{message.apply_error && <div className="table-sub" role="alert">处理错误：{message.apply_error}</div>}</TableCell>
            <TableCell><Button variant="ghost" size="sm" onClick={() => setSelectedId(message.id)}><FileText/>查看追踪</Button></TableCell>
          </TableRow>)}</TableBody>
        </Table><Pager page={messages.data.page} size={messages.data.size} total={messages.data.total} onChange={setPage}/>
      </> : <Empty title="当前范围暂无通用 HTTP 报文">接入来源仅登记、尚无外部发送时，这里不会生成记录。旧设备的「接入镜像」和海康的回调／拉取记录不计入本页。</Empty>}
    </CardContent></Card>
    <Sheet open={!!selectedId} onOpenChange={open => { if (!open) setSelectedId('') }}><SheetContent className="review-sheet sm:max-w-xl">{selectedId && <MessageInspector key={selectedId} id={selectedId} tenantId={selectedTenantId} changed={messages.refresh}/>}</SheetContent></Sheet>
  </>
}
function captureOriginLabel(origin: CaptureOrigin): string {
  return origin === 'AUTHENTICATED_SOURCE' ? '认证接入来源捕获' : '历史未认证来源'
}
function applicationStateLabel(state: MessageApplicationState, binding: RawMessage['binding_state']): string {
  if (state === 'NOT_APPLIED') return binding === 'UNBOUND' ? '未绑定 · 尚未应用' : '尚未应用'
  if (state === 'APPLIED') return '已应用'
  if (state === 'STALE') return 'STALE · 服务端标记'
  return 'REJECTED · 服务端拒绝'
}
function MessageApplicationStatus({ message }: { message: RawMessage }) {
  return <section className="preview-result" aria-label="真实消息应用状态">
    <h3>真实处理状态</h3>
    <dl className="detail-list">
      <div><dt>服务端应用状态</dt><dd>{applicationStateLabel(message.application_state, message.binding_state)} · {message.application_state}</dd></div>
      <div><dt>捕获来源</dt><dd>{captureOriginLabel(message.capture_origin)} · {message.capture_origin}</dd></div>
      <div><dt>来源时间（解析）</dt><dd>{message.occurred_at ? dateTime(message.occurred_at) : '未解析'}</dd></div>
      <div><dt>应用时间</dt><dd>{message.applied_at ? dateTime(message.applied_at) : '尚无应用时间'}</dd></div>
      {message.apply_error && <div><dt>处理错误码</dt><dd role="alert">{message.apply_error}</dd></div>}
    </dl>
    {message.application_state === 'NOT_APPLIED' && message.capture_origin === 'AUTHENTICATED_SOURCE' && <p className="muted-note">认证来源捕获由服务端真实处理器处理；事务提交前状态保持 NOT_APPLIED。此状态不表示已成功应用。</p>}
    {message.application_state === 'NOT_APPLIED' && message.capture_origin === 'LEGACY_UNVERIFIED' && <p className="muted-note">历史未认证来源不由真实捕获处理器自动应用；详情以服务端返回状态为准。</p>}
  </section>
}
function SourceFilter({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeInitialized = useSession(s => s.scopeInitialized)
  const [page, setPage] = useState(1)
  const sources = useResource(`message-source-filter:${page}:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}`, signal => api.sources(page, '', signal, scopeTenantIds, scopePlaceIds), scopeInitialized)
  return <div className="source-filter">{sources.loading ? <span>读取授权范围来源…</span> : sources.error != null ? <ErrorNotice error={sources.error} retry={sources.refresh}/> : <><select className="native-select" aria-label="按授权范围来源筛选" value={value} onChange={e => { const id = e.target.value; if (!id || sources.data?.items.some(source => source.id === id)) onChange(id) }}><option value="">全部所选范围来源</option>{sources.data?.items.map(source => <option key={source.id} value={source.id}>{source.name} · {source.id}</option>)}</select>{sources.data && <Pager page={sources.data.page} size={sources.data.size} total={sources.data.total} onChange={next => { setPage(next); onChange('') }}/>}</>}</div>
}
function MessageInspector({ id, tenantId, changed }: { id: string; tenantId: string; changed: () => void }) {
  const permissions = useSession(s => s.permissions), canRaw = permissions.includes('messages:raw'), canPreview = permissions.includes('messages:preview')
  const detail = useResource(`message-detail:${id}:${tenantId}`, signal => api.messageDetail(id, signal, tenantId))
  const [showRaw, setShowRaw] = useState(false), [previewId, setPreviewId] = useState(''), [editing, setEditing] = useState(false), [notice, setNotice] = useState('')
  const message = detail.data?.message
  if (editing && message) return <PreviewForm message={message} cancel={() => setEditing(false)} refresh={() => { setEditing(false); detail.refresh(); changed() }} saved={id => { setEditing(false); setPreviewId(id); setNotice('预览尝试已保存 · NOT_APPLIED。没有推进设备、事件、报警、通知或在线时间。'); detail.refresh(); changed() }}/>
  const fields: [string, string | number | null | undefined][] = message ? [['消息ID', message.id], ['来源快照', `${message.source_name} · ${message.source_id}`], ['稳定消息身份', message.source_message_id], ['来源设备标识', message.vendor_device_id], ['捕获来源', captureOriginLabel(message.capture_origin)], ['设备快照', message.device_id], ['设备名称快照', message.device_name], ['绑定快照', message.binding_id], ['捕获时产品版本', message.product_version_id], ['平台接收时间', dateTime(message.received_at)], ['解析后来源时间', message.occurred_at ? dateTime(message.occurred_at) : null], ['原始来源时间文本', message.occurred_at_raw], ['reported_at字段', message.reported_at], ['原文字节数', message.payload_bytes], ['原文SHA256', message.payload_sha256], ['当前revision', message.revision]] : []
  return <><SheetHeader className="review-header"><SheetTitle>消息追踪详情</SheetTitle><SheetDescription>展示服务端真实应用状态。人工预览只产生解析历史，不等于应用；原文与原始事件受独立权限控制。</SheetDescription></SheetHeader><div className="review-body">{notice && <div role="status" className="notice">{notice}</div>}{detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : message && <><dl className="detail-list">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{reported(value)}</dd></div>)}</dl><MessageApplicationStatus message={message}/><MessageApplyPanel message={message} refresh={() => { detail.refresh(); changed() }}/><ReparsePanel message={message}/>{message.binding_state === 'UNBOUND' && <div className="notice">消息捕获时未绑定设备，后续绑定不会回填此消息；当前不具备应用条件。</div>}<div className="version-actions">{canRaw && <Button variant="outline" onClick={() => setShowRaw(value => !value)}><Eye/>{showRaw ? '关闭原文并销毁' : '查看独立原文'}</Button>}{canPreview && <Button disabled={message.binding_state !== 'BOUND' || !message.product_version_id} onClick={() => setEditing(true)}><Play/>创建无副作用预览</Button>}<RefreshButton onClick={detail.refresh}/></div>{!canRaw && <div className="notice">当前没有独立原文权限：不请求原文端点，不保留或展示raw_event；仍可查看安全诊断与时间质量。</div>}{showRaw && canRaw && <AuthorizedRaw key={`${id}:raw`} id={id} tenantId={tenantId}/>}<h3 className="mt-5 mb-3">不可变历史预览尝试</h3>{detail.data?.previews.length ? <ol className="binding-history">{detail.data.previews.map(preview => <li key={preview.id}><strong>{preview.event_type} · {preview.business_status}</strong><span>{dateTime(preview.created_at)} · {preview.id}</span><Button type="button" variant="outline" size="sm" onClick={() => setPreviewId(preview.id)}>查看此历史尝试</Button></li>)}</ol> : <Empty title="尚无预览尝试"/>}{previewId && <PreviewReader key={`${previewId}:${canRaw}`} messageId={id} previewId={previewId} tenantId={tenantId}/>}</>}</div></>
}
function AuthorizedRaw({ id, tenantId }: { id: string; tenantId: string }) {
  const authorized = useSession(s => s.audience === 'management' && s.permissions.includes('messages:read') && s.permissions.includes('messages:raw'))
  return authorized ? <RawText id={id} tenantId={tenantId}/> : null
}
function RawText({ id, tenantId }: { id: string; tenantId: string }) {
  const raw = useResource(`raw-message:${id}:${tenantId}`, signal => api.messageRaw(id, signal, tenantId))
  return <section className="raw-panel"><h3>独立原文（关闭即销毁）</h3><p className="muted-note">原始字符串直接显示，不JSON.parse再序列化、不做截断或替换。请勿复制到不受控日志。</p>{raw.loading ? <Loading/> : raw.error != null ? <ErrorNotice error={raw.error} retry={raw.refresh}/> : raw.data && <><div className="muted-note mono">{raw.data.payload_bytes} bytes · SHA256 {raw.data.payload_sha256}</div><pre className="raw-payload" aria-label="原始报文全文">{raw.data.payload}</pre></>}</section>
}
function PreviewReader({ messageId, previewId, tenantId }: { messageId: string; previewId: string; tenantId: string }) {
  const canRaw = useSession(s => s.permissions.includes('messages:raw'))
  const preview = useResource(`preview-attempt:${messageId}:${previewId}:${tenantId}:${canRaw}`, signal => api.messagePreview(messageId, previewId, signal, tenantId))
  return preview.loading ? <Loading/> : preview.error != null ? <ErrorNotice error={preview.error} retry={preview.refresh}/> : preview.data ? <><PreviewResult preview={preview.data} canRaw={canRaw}/><ReadingValues readings={preview.data.readings} exactJson={preview.data.readings_json}/></> : null
}
export function PreviewResult({ preview, canRaw }: { preview: IngestPreview; canRaw: boolean }) {
  const percent = displayDecimal(preview.battery_percent_json ?? (preview.battery_percent === null ? 'null' : String(preview.battery_percent)))
  return <section className="preview-result"><h3>历史解析结果 · 仅预览</h3><div className="notice">{preview.business_status} · 未应用到设备/事件/报警/通知。{preview.event_type === 'UNKNOWN' ? 'UNKNOWN为未知，不是正常或恢复。' : '解析事件不等于已发生业务事件。'}</div><dl className="detail-list"><div><dt>捕获版本（不随迁移改变）</dt><dd className="mono">{preview.product_version_id}</dd></div><div><dt>事件类型</dt><dd>{preview.event_type}</dd></div><div><dt>精确电量</dt><dd>{percent === 'null' ? '未上报' : `${percent}%`}</dd></div><div><dt>设备报告时间</dt><dd>{preview.reported_at ? dateTime(preview.reported_at) : '未提供（不回退接收时间）'}</dd></div><div><dt>时间质量</dt><dd>{preview.time_quality} · {({ REPORTED: '来源声明（未校准，不作可信排序）', MISSING: '缺失报告时间', INVALID: '无效报告时间' })[preview.time_quality]}</dd></div><div><dt>尝试时间 / 操作人</dt><dd>{dateTime(preview.created_at)} · {preview.actor_id}</dd></div><div><dt>原因</dt><dd>{preview.reason}</dd></div></dl>{preview.diagnostics.length ? <ul className="preview-diagnostics">{preview.diagnostics.map((item, index) => <li key={index}>{item}</li>)}</ul> : <p className="muted-note">服务端未提供诊断。</p>}{canRaw && Object.hasOwn(preview, 'raw_event') && <div><h4>受原文权限控制的原始事件</h4><pre className="json-preview" aria-label="原始事件诊断">{preview.raw_event_json ?? JSON.stringify(preview.raw_event)}</pre></div>}</section>
}
function ReparsePanel({ message }: { message: RawMessage }) {
  const permissions = useSession(s => s.permissions)
  const [reason, setReason] = useState(''), [editing, setEditing] = useState(false), [job, setJob] = useState<ReparseDetail | null>(null)
  const [jobRefreshing, setJobRefreshing] = useState(false), [jobError, setJobError] = useState<unknown>()
  const action = useMutationAction()
  async function refreshJob() {
    if (!job || jobRefreshing) return
    setJobRefreshing(true); setJobError(undefined)
    try { setJob(await api.reparseJob(job.job.id, undefined, message.tenant_id)) }
    catch (error) { setJobError(error) }
    finally { setJobRefreshing(false) }
  }
  if (!permissions.includes('messages:reparse')) return null
  if (message.binding_state !== 'BOUND') return null
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('必须说明重解析原因')); return }
    void action.run(async (key, signal) => {
      const created = await api.reparseMessage(message.id, { reason: reason.trim() }, key, signal, message.tenant_id)
      let detail: ReparseDetail = { job: created, attempts: [] }
      for (let i = 0; i < 20 && (detail.job.status === 'PENDING' || detail.job.status === 'RUNNING'); i++) {
        await new Promise(resolve => setTimeout(resolve, 400))
        detail = await api.reparseJob(created.id, signal, message.tenant_id)
      }
      setJob(detail)
    }, () => setEditing(false))
  }
  if (!editing) return <section className="preview-result">
    <h3>重解析（N07 · 无副作用）</h3>
    <div className="notice">只用捕获时版本解析原文。结果只落任务与尝试记录，不写设备、事件、报警、通知。</div>
    {job && <>
      <dl className="detail-list">
        <div><dt>任务状态</dt><dd>{job.job.status}</dd></div>
        <div><dt>尝试次数</dt><dd>{job.job.attempt_count}/{job.job.max_attempts}</dd></div>
        <div><dt>解析结果</dt><dd className="mono">{job.job.result_json ?? '无'}</dd></div>
        <div><dt>尝试轨迹</dt><dd>{job.attempts.length ? job.attempts.map(a => `#${a.attempt_no} ${a.outcome}${a.error_code ? `(${a.error_code})` : ''}`).join(' · ') : '尚未执行'}</dd></div>
      </dl>
      {jobError != null && <ErrorNotice error={jobError} />}
      {['PENDING', 'RUNNING'].includes(job.job.status) && <Button type="button" variant="outline" disabled={jobRefreshing} onClick={() => void refreshJob()}>{jobRefreshing ? '刷新中…' : '刷新重解析任务'}</Button>}
    </>}
    <div className="identity-form-actions"><Button type="button" onClick={() => { setEditing(true); action.touch() }}>创建重解析任务</Button></div>
  </section>
  return <section className="preview-result"><h3>重解析（N07 · 无副作用）</h3><form className="form-stack" onSubmit={submit}><div className="field"><Label htmlFor="reparse-reason">重解析原因</Label><Input id="reparse-reason" required value={reason} onChange={e => { setReason(e.target.value); action.touch() }}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<div className="identity-form-actions"><Button type="button" variant="outline" onClick={() => setEditing(false)}>取消</Button><Button type="submit" disabled={action.busy}>{action.busy ? '正在提交…' : '提交'}</Button></div></form></section>
}

function MessageApplyPanel({ message, refresh }: { message: RawMessage; refresh: () => void }) {
  const permissions = useSession(s => s.permissions)
  const [reason, setReason] = useState(''), [editing, setEditing] = useState(false), [result, setResult] = useState<MessageApplyResult | null>(null)
  const action = useMutationAction()
  if (!permissions.includes('messages:apply')) return null
  const applicationState = result?.application_state ?? message.application_state
  if (message.binding_state !== 'BOUND') return <section className="preview-result"><h3>人工应用</h3><div className="notice">消息捕获时未绑定设备，不能人工应用；后续绑定不会回填此消息。</div></section>
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('必须说明应用原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run(async (key, signal) => { const applied = await api.applyMessage(message.id, { reason: reason.trim(), expected_revision: message.revision }, key, signal, message.tenant_id); setResult(applied); refresh() }, () => setEditing(false))
  }
  const stateText = result === null ? '' : result.application_state === 'APPLIED' ? '服务端返回 APPLIED；实际应用结果以设备与报警状态为准。'
    : result.application_state === 'STALE' ? '服务端返回 STALE；未推断设备状态已经更新。'
    : '服务端返回 REJECTED；请依据消息状态和错误信息核查。'
  if (!editing) return <section className="preview-result"><h3>真实消息应用</h3>
    <div className="notice">新认证来源捕获由真实处理器自动处理。此处保留经授权的人工应用能力；请求需明确二次确认，状态以服务端结果为准。</div>
    {result && <><dl className="detail-list"><div><dt>应用状态</dt><dd>{result.application_state}</dd></div><div><dt>说明</dt><dd>{stateText}</dd></div><div><dt>事件类型</dt><dd>{result.event_type ?? '无'}</dd></div><div><dt>报警</dt><dd>{result.alarm_id ?? '无'}</dd></div><div><dt>outbox事件</dt><dd>{result.outbox_event_type ?? '无'}</dd></div></dl>
      <ReadingValues readings={result.readings} exactJson={result.readings_json}/></>}
    <div className="identity-form-actions">{applicationState === 'NOT_APPLIED' && <Button type="button" onClick={() => { setEditing(true); action.touch() }}>人工应用捕获消息</Button>}<Button type="button" variant="outline" onClick={refresh}>刷新详情</Button></div>
  </section>
  return <section className="preview-result"><h3>真实消息应用</h3><form className="form-stack" onSubmit={submit}>{!action.confirming ? <><div className="identity-readonly mono">消息 {message.id} · revision {message.revision}</div><div className="notice">人工应用会写入真实设备/报警事实及 outbox。服务端可能返回 APPLIED、STALE 或 REJECTED。</div><div className="field"><Label htmlFor="apply-reason">应用原因</Label><Input id="apply-reason" required value={reason} onChange={e => { setReason(e.target.value); action.touch() }}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<div className="identity-form-actions"><Button type="button" variant="outline" onClick={() => setEditing(false)}>取消</Button><Button type="submit" disabled={action.busy}>{action.busy ? '正在提交…' : '提交'}</Button></div></> : <OperationConfirmation action={action} title="确认人工应用捕获消息" submitLabel="确认应用消息" refresh={() => setEditing(false)} warning="此操作请求真实业务处理；结果以服务端确认状态为准。不触发短信、电话、支付或硬件动作。"><p>消息 {message.id}</p><p>原因：{reason}</p></OperationConfirmation>}</form></section>
}

function PreviewForm({ message, cancel, saved, refresh }: { message: RawMessage; cancel: () => void; saved: (id: string) => void; refresh: () => void }) {
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim() || !message.product_version_id || message.binding_state !== 'BOUND') { action.setError(new Error('必须说明预览原因，且消息在捕获时已有固定已发布版本；不会后补版本')); return }
    let previewId = ''
    void action.run(async (key, signal) => { const result = await api.previewMessage(message.id, { reason: reason.trim(), expected_revision: message.revision }, key, signal, message.tenant_id); previewId = result.preview.id }, () => saved(previewId))
  }
  return <><SheetHeader className="review-header"><SheetTitle>创建无副作用预览</SheetTitle><SheetDescription>只用捕获时已发布版本。即使源已停用或旧绑定已解绑，历史预览也不回写业务。</SheetDescription></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}><div className="identity-readonly mono">消息 {message.id} · 捕获版本 {message.product_version_id} · revision {message.revision}</div><div className="notice">结果始终NOT_APPLIED，不更新设备状态/在线时间/电量，不创建事件、报警、通知。UNKNOWN或畸形结果不是正常/恢复。</div><div className="field"><Label htmlFor="preview-reason">预览原因</Label><Input id="preview-reason" required value={reason} onChange={e => { setReason(e.target.value); action.touch() }}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<div className="identity-form-actions"><Button type="button" variant="outline" onClick={cancel}>取消预览</Button><Button type="submit" disabled={action.busy}>{action.busy ? '正在提交…' : '提交'}</Button></div></form></div></>
}
