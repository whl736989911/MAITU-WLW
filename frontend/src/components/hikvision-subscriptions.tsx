import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Checkbox, Input, Pagination, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ApiError, errorMessage } from '@/lib/api'
import {
  hikvisionSubscriptionsApi,
  type SubscriptionInspect, type SubscriptionOperation, type SubscriptionOperationSummary,
  type SubscriptionPreview,
} from '@/lib/hikvision-subscriptions'
import { useSession } from '@/lib/store'
import { beginWriteIntent, clearWriteIntent, markWriteIntentUnknown, useWriteIntent } from '@/lib/write-intent'

const PAGE_SIZE = 10
const MESSAGE_TYPES = [
  { value: '980002', label: '报警事件（980002）' },
  { value: '980004', label: '故障事件（980004）' },
  { value: '980008', label: '监测数据（980008）' },
  { value: '980016', label: '原生设备事件（980016）' },
]
const stateLabel: Record<string, string> = {
  PREVIEW: '预览（未发送）', IN_FLIGHT: '已持久化发送中', PENDING_CONFIRMATION: '待回读确认（非失败）',
  CONFIRMED: '回读已确认', REJECTED: '厂商拒绝/未发送', UNKNOWN: '结果未知（仅核对）', CANCELLED: '已取消（未发送）',
}
const terminalUnsafe = new Set(['IN_FLIGHT', 'PENDING_CONFIRMATION', 'UNKNOWN'])
interface PreviewPayload { operation: 'ADD' | 'UPDATE' | 'DELETE'; message_type: string; expected_receiver_revision: number; expected_hikvision_revision: number; reason: string }
interface ExecutePayload { operation_id: string; expected_remote_digest: string; overwrite_confirmed: boolean }

export function HikvisionSubscriptions() {
  const session = useSession()
  if (!session.permissions.includes('integrations:read')) return <Alert type="warning" showIcon message="当前账户无云端订阅读取权限"/>
  return <SubscriptionsPanel canWrite={session.permissions.includes('integrations:write')} generation={session.authGeneration}/>
}

function SubscriptionsPanel({ canWrite, generation }: { canWrite: boolean; generation: number }) {
  const [inspect, setInspect] = useState<SubscriptionInspect | null>(null)
  const [inspecting, setInspecting] = useState(false), [inspectError, setInspectError] = useState('')
  const [operation, setOperation] = useState<'ADD' | 'UPDATE' | 'DELETE'>('ADD')
  const [messageType, setMessageType] = useState('980016')
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState<SubscriptionPreview | null>(null)
  const [previewing, setPreviewing] = useState(false), [previewError, setPreviewError] = useState(''), [previewUncertain, setPreviewUncertain] = useState(false)
  const [overwriteConfirmed, setOverwriteConfirmed] = useState(false)
  const [executing, setExecuting] = useState(false), [executeError, setExecuteError] = useState(''), [executeUncertain, setExecuteUncertain] = useState(false)
  const [result, setResult] = useState<SubscriptionOperation | null>(null)
  const [items, setItems] = useState<SubscriptionOperationSummary[]>([])
  const [page, setPage] = useState(1), [total, setTotal] = useState(0), [loading, setLoading] = useState(true), [listError, setListError] = useState('')
  const [selected, setSelected] = useState<SubscriptionOperation | null>(null), [detailError, setDetailError] = useState('')
  const [actionReason, setActionReason] = useState(''), [acting, setActing] = useState(false), [actionError, setActionError] = useState('')
  const previewIntent = useWriteIntent<PreviewPayload>('subscription-preview')
  const executeIntent = useWriteIntent<ExecutePayload>('subscription-execute')
  const actionKey = useRef<string | null>(null)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [generation])
  useEffect(() => { void loadOperations(page) }, [page, generation])
  async function loadOperations(requestedPage: number) {
    setLoading(true); setListError('')
    try {
      const pageItems = await hikvisionSubscriptionsApi.operations(requestedPage, PAGE_SIZE)
      if (!alive.current) return
      setItems(pageItems.items); setTotal(pageItems.total); setPage(pageItems.page)
    } catch (cause) { if (alive.current) setListError(errorMessage(cause)) }
    finally { if (alive.current) setLoading(false) }
  }
  async function runInspect() {
    if (inspecting) return
    setInspecting(true); setInspectError(''); setInspect(null); setPreview(null); setPreviewUncertain(false)
    try {
      const value = await hikvisionSubscriptionsApi.inspect()
      if (!alive.current) return
      setInspect(value)
    } catch (cause) { if (alive.current) setInspectError(errorMessage(cause)) }
    finally { if (alive.current) setInspecting(false) }
  }
  async function runPreview() {
    if (previewIntent) { setPreviewError('存在未确认的预览意图；请使用原幂等键重试或刷新，不能新建不同预览'); return }
    if (!inspect || previewing) return
    if (!reason.trim()) { setPreviewError('请填写操作原因'); return }
        setPreviewing(true); setPreviewError(''); setPreview(null); setResult(null); setExecuteUncertain(false); setExecuteError('')
    let intentKey: string | null = null
    try {
      const created = beginWriteIntent<PreviewPayload>('subscription-preview', {
        operation, message_type: messageType,
        expected_receiver_revision: inspect.receiver_revision,
        expected_hikvision_revision: inspect.hikvision_revision,
        reason: reason.trim(),
      }, `订阅预览 ${operation}/${messageType}`)
      intentKey = created.key
      const value = await hikvisionSubscriptionsApi.preview({ ...created.payload }, created.key)
      if (!alive.current) return
      clearWriteIntent('subscription-preview', created.key)
      setPreview(value); setPreviewUncertain(false)
      setOverwriteConfirmed(false)
    } catch (cause) {
      if (alive.current) {
        if (cause instanceof ApiError && cause.status !== 0 && cause.status < 500) { clearWriteIntent('subscription-preview'); setPreviewError(errorMessage(cause)) }
        else { if (intentKey) markWriteIntentUnknown('subscription-preview', intentKey); setPreviewUncertain(true); setPreviewError(`${errorMessage(cause)}。预览结果未确认；使用同一幂等键重试不会创建第二个意图。`) }
      }
    } finally { if (alive.current) setPreviewing(false) }
  }
  async function runExecute() {
    if (executeIntent) { setExecuteError('存在未确认的执行意图；请使用原幂等键重试或只读核对，不能新建写入'); return }
    if (!preview?.operation_id || executing) return
    if (preview.overwrite_required && !overwriteConfirmed) { setExecuteError('必须显式确认覆盖现有云端订阅地址'); return }
    setExecuting(true); setExecuteError(''); setExecuteUncertain(false)
    let intentKey: string | null = null
    try {
      const created = beginWriteIntent<ExecutePayload>('subscription-execute', {
        operation_id: preview.operation_id, expected_remote_digest: preview.remote_digest, overwrite_confirmed: overwriteConfirmed,
      }, `订阅执行 ${preview.operation}/${preview.message_type}`)
      intentKey = created.key
      const value = await hikvisionSubscriptionsApi.execute(preview.operation_id, { ...created.payload }, created.key)
      if (!alive.current) return
      clearWriteIntent('subscription-execute', created.key)
      setResult(value); setPreview(null); setOverwriteConfirmed(false)
      setSelected(value); setPreviewUncertain(false)
      message.success(`厂商写入已返回；当前状态：${stateLabel[value.state] ?? value.state}`)
      void loadOperations(1)
    } catch (cause) {
      if (alive.current) {
        if (cause instanceof ApiError && cause.status !== 0 && cause.status < 500) { clearWriteIntent('subscription-execute'); setExecuteError(errorMessage(cause)) }
        else { if (intentKey) markWriteIntentUnknown('subscription-execute', intentKey); setExecuteUncertain(true); setExecuteError(`${errorMessage(cause)}。写入结果未确认；不得重新发起，请使用同一幂等键重试或只读核对。`) }
      }
    } finally { if (alive.current) setExecuting(false) }
  }
  async function retryPreview() {
    const intent = previewIntent
    if (!intent) return
    setPreviewing(true); setPreviewError(''); setPreviewUncertain(false)
    try {
      const value = await hikvisionSubscriptionsApi.preview({ ...intent.payload }, intent.key)
      if (!alive.current) return
      clearWriteIntent('subscription-preview', intent.key)
      setPreview(value); setOverwriteConfirmed(false); setPreviewUncertain(false)
    } catch (cause) {
      if (alive.current) {
        if (cause instanceof ApiError && cause.status !== 0 && cause.status < 500) { clearWriteIntent('subscription-preview', intent.key); setPreviewError(errorMessage(cause)) }
        else { markWriteIntentUnknown('subscription-preview', intent.key); setPreviewUncertain(true); setPreviewError(`${errorMessage(cause)}。预览仍未确认；不会自动重试。`) }
      }
    } finally { if (alive.current) setPreviewing(false) }
  }
  async function retryExecute() {
    const intent = executeIntent
    if (!intent) return
    setExecuting(true); setExecuteError(''); setExecuteUncertain(false)
    try {
      const value = await hikvisionSubscriptionsApi.execute(intent.payload.operation_id, { expected_remote_digest: intent.payload.expected_remote_digest, overwrite_confirmed: intent.payload.overwrite_confirmed }, intent.key)
      if (!alive.current) return
      clearWriteIntent('subscription-execute', intent.key)
      setResult(value); setPreview(null); setOverwriteConfirmed(false); setSelected(value); setExecuteUncertain(false)
      void loadOperations(1)
    } catch (cause) {
      if (alive.current) {
        if (cause instanceof ApiError && cause.status !== 0 && cause.status < 500) { clearWriteIntent('subscription-execute', intent.key); setExecuteError(errorMessage(cause)) }
        else { markWriteIntentUnknown('subscription-execute', intent.key); setExecuteUncertain(true); setExecuteError(`${errorMessage(cause)}。执行结果仍未确认；不得重新发起，请只读核对。`) }
      }
    } finally { if (alive.current) setExecuting(false) }
  }
  async function openDetail(id: string) {
    setDetailError(''); setActionError(''); setSelected(null)
    try { const value = await hikvisionSubscriptionsApi.operation(id); if (alive.current) setSelected(value) }
    catch (cause) { if (alive.current) setDetailError(errorMessage(cause)) }
  }
  async function reconcile(id: string) {
    if (acting) return
    if (!actionKey.current) actionKey.current = crypto.randomUUID()
    setActing(true); setActionError('')
    try {
      const value = await hikvisionSubscriptionsApi.reconcile(id, actionKey.current)
      if (!alive.current) return
      setSelected(value); actionKey.current = null
      message.success(`只读核对完成：${stateLabel[value.state] ?? value.state}`)
      void loadOperations(page)
    } catch (cause) { if (alive.current) { setActionError(errorMessage(cause)); if (!(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500) actionKey.current = null } }
    finally { if (alive.current) setActing(false) }
  }
  async function cancelPreview(id: string) {
    if (!actionReason.trim() || acting) return
    if (!actionKey.current) actionKey.current = crypto.randomUUID()
    setActing(true); setActionError('')
    try {
      const value = await hikvisionSubscriptionsApi.cancel(id, { reason: actionReason.trim() }, actionKey.current)
      if (!alive.current) return
      setSelected(value); setActionReason(''); actionKey.current = null
      message.success('预览已取消，未发起任何厂商写入。')
      void loadOperations(page)
    } catch (cause) { if (alive.current) setActionError(errorMessage(cause)) }
    finally { if (alive.current) setActing(false) }
  }
  const columns: ColumnsType<SubscriptionOperationSummary> = [
    { title: '意图', dataIndex: 'id', width: 240, render: (v: string, row) => <div><Typography.Text code>{v.slice(0, 8)}…</Typography.Text><div className="hik-callback-note">{row.operation} · {row.message_type}</div></div> },
    { title: '状态', dataIndex: 'state', width: 190, render: (v: string) => <Tag>{stateLabel[v] ?? v}</Tag> },
    { title: '目标/原地址源站', width: 240, render: (_, row) => <div className="mono">{row.intended_origin ?? '删除/无目标'}<div className="hik-callback-note">原：{row.previous_origin ?? '无'}</div></div> },
    { title: '覆盖确认', dataIndex: 'overwrite_required', width: 95, render: (v: boolean) => v ? '需要' : '无' },
    { title: '厂商码', dataIndex: 'vendor_code', width: 130, render: (v: string | null) => v ?? '—' },
    { title: '创建/结束', width: 190, render: (_, row) => <div className="hik-callback-note">{row.created_at}<br/>{row.finished_at ?? '未结束'}</div> },
    { title: '操作', width: 150, render: (_, row) => <Space wrap><Button size="small" onClick={() => void openDetail(row.id)}>查看</Button>{terminalUnsafe.has(row.state) && canWrite && <Button size="small" onClick={() => void openDetail(row.id)}>核对</Button>}{row.state === 'PREVIEW' && canWrite && <Button size="small" onClick={() => void openDetail(row.id)}>取消</Button>}</Space> },
  ]
  return <Card data-testid="hik-subscriptions" title="云端订阅管理（显式）" extra={<Button onClick={() => void loadOperations(page)} loading={loading}>刷新列表</Button>}>
    <Alert type="info" showIcon message="只读检查 → 预览 → 二次确认与幂等键 → 持久化 IN_FLIGHT → 厂商写入 → 只读回读确认。默认不修改云端；任何检查/核对都不会写入厂商。" />
    <div className="hik-callback-metrics">
      <div><small>接收端源站</small><strong>{inspect?.receiver_origin ?? '未检查'}</strong></div>
      <div><small>接收端配置修订</small><strong>{inspect?.receiver_revision ?? '—'}</strong></div>
      <div><small>海康配置修订</small><strong>{inspect?.hikvision_revision ?? '—'}</strong></div>
      <div><small>接收端就绪</small><strong>{inspect ? inspect.receiver_ready ? '是' : '否' : '—'}</strong></div>
      <div><small>账号指纹</small><strong className="mono">{inspect?.account_fingerprint_masked ?? '—'}</strong></div>
    </div>
    {inspect?.limitations && <Alert type="warning" showIcon message="厂商能力限制" description={inspect.limitations.note}/>}
    {canWrite ? <Space wrap>
      <Button onClick={() => void runInspect()} loading={inspecting} disabled={inspecting}>只读检查云端订阅</Button>
    </Space> : <p className="hik-callback-note">需要 integrations:write 权限及当前账户密码。</p>}
    {inspectError && <Alert type="error" showIcon message={inspectError}/>}
    {previewIntent && <Alert type="warning" showIcon style={{ marginTop: 8 }} message="存在未确认的订阅预览意图（导航后仍保留）。使用原幂等键重试不会创建第二个意图。"
      action={<Space><Button size="small" loading={previewing} disabled={previewing} onClick={() => void retryPreview()}>使用原键重试</Button></Space>}/>}
    {executeIntent && <Alert type="warning" showIcon style={{ marginTop: 8 }} message="存在未确认的订阅执行意图（导航后仍保留）。只能使用原幂等键重试或只读核对，不能重新发起写入。"
      action={<Space><Button size="small" loading={executing} disabled={executing} onClick={() => void retryExecute()}>使用原键重试</Button></Space>}/>}
    {inspect && <div className="hik-callback-table"><Table rowKey={row => `${row.message_type}:${row.url_sha256 ?? 'absent'}`} size="small" pagination={false} scroll={{ x: 760 }} dataSource={inspect.items} columns={[
      { title: '消息类型', dataIndex: 'message_type', width: 120 }, { title: '目标源站', dataIndex: 'destination_origin', width: 220, render: (v: string | null) => v ?? '无法安全识别' },
      { title: '匹配接收端', dataIndex: 'matches_receiver', width: 110, render: (v: boolean) => v ? '匹配' : '不匹配' },
      { title: 'URL摘要', dataIndex: 'url_sha256', width: 150, render: (v: string | null) => v ? `${v.slice(0, 12)}…` : '无' },
      { title: '支持', dataIndex: 'supported', width: 80, render: (v: boolean) => v ? '是' : '否' },
      { title: '重复', dataIndex: 'duplicate', width: 80, render: (v: boolean) => v ? '多条' : '—' },
    ]}/><p className="hik-callback-note">不返回任何原始URL；比较与摘要由服务端完成。</p></div>}
    {canWrite && inspect && <section aria-label="订阅预览与执行">
      <h3>生成预览</h3>
      {previewIntent && <Alert type="warning" showIcon message="存在未确认的预览意图；重试使用原幂等键与原内容，不会创建第二个意图。" />}
      {executeIntent && preview && <Alert type="warning" showIcon message="存在未确认的执行意图；只能使用原幂等键重试或只读核对，不能重新发起写入。" />}
      <Space wrap>
        <Select aria-label="订阅操作" value={operation} onChange={(value: 'ADD' | 'UPDATE' | 'DELETE') => { setOperation(value); setPreview(null) }} options={[{ value: 'ADD', label: '新增' }, { value: 'UPDATE', label: '更新（覆盖现有地址）' }, { value: 'DELETE', label: '删除' }]}/>
        <Select aria-label="消息类型" value={messageType} onChange={value => { setMessageType(value); setPreview(null) }} options={MESSAGE_TYPES}/>
        <Input aria-label="订阅操作原因" value={reason} onChange={event => setReason(event.target.value)} placeholder="操作原因（审计）" style={{ width: 260 }}/>
        {previewIntent ? <Button loading={previewing} disabled={previewing} onClick={() => void retryPreview()}>使用原幂等键重试未确认预览</Button> : <Button onClick={() => void runPreview()} loading={previewing} disabled={previewing || !reason.trim()}>{previewUncertain ? '使用原幂等键重试预览' : '生成预览（不写入）'}</Button>}
      </Space>
      {previewError && <Alert type="error" showIcon message={previewError}/>}
      {preview && <div className="hik-callback-preview">
        <p>操作：{preview.operation} · {preview.message_type} · 状态 {stateLabel[preview.state] ?? preview.state}</p>
        <p>当前源站：{preview.current_origin ?? '无'} → 目标源站：{preview.intended_origin ?? '删除/无目标'}</p>
        <p>远端摘要：<Typography.Text code>{preview.remote_digest.slice(0, 16)}…</Typography.Text> · 远端状态：{preview.remote_state} · 过期：{preview.expires_at}</p>
        {preview.no_write_required ? <Alert type="success" showIcon message="云端状态已与目标一致；无需写入，未生成可执行意图。"/>
          : <>
            {preview.overwrite_required && <Alert type="warning" showIcon message="该操作会覆盖该消息类型现有云端地址（可能是旧平台URL）。请确认后才可发送。"/>}
            <Alert type="warning" showIcon message="厂商无CAS/幂等/重试契约；读与写之间存在并发覆盖窗口。code0只代表厂商接受，必须回读确认。"/>
            <Space wrap>
              {preview.overwrite_required && <Checkbox checked={overwriteConfirmed} onChange={event => setOverwriteConfirmed(event.target.checked)}>我确认覆盖现有订阅地址</Checkbox>}
              {executeIntent ? <Button type="primary" danger={preview.overwrite_required} loading={executing} disabled={executing} onClick={() => void retryExecute()}>使用原幂等键重试未确认执行</Button> : <Button type="primary" danger={preview.overwrite_required} loading={executing} disabled={executing || preview.overwrite_required && !overwriteConfirmed} onClick={() => void runExecute()}>{executeUncertain ? '使用原幂等键重试执行' : '确认执行厂商写入'}</Button>}
            </Space>
          </>}
        {executeError && <Alert type="error" showIcon message={executeError}/>}
      </div>}
    </section>}
    {result && <Alert style={{ marginTop: 12 }} type={result.state === 'CONFIRMED' ? 'success' : result.state === 'REJECTED' ? 'error' : 'warning'} showIcon
      message={`意图 ${result.id.slice(0, 8)}… 当前状态：${stateLabel[result.state] ?? result.state}`}
      description={result.state === 'UNKNOWN' || result.state === 'PENDING_CONFIRMATION' || result.state === 'IN_FLIGHT'
        ? '结果未终态；请使用下方“只读核对”，不要重新发起写入。待确认不是失败。' : '终态以只读回读证据为准；不推断终端投递。'}/>}
    {listError && <Alert type="error" showIcon message={listError}/>}
    <h3 style={{ marginTop: 16 }}>订阅意图历史</h3>
    <div className="hik-callback-table"><Table rowKey="id" size="small" loading={loading} pagination={false} scroll={{ x: 1200 }} dataSource={items} columns={columns}/></div>
    <Pagination size="small" current={page} pageSize={PAGE_SIZE} total={total} showSizeChanger={false} onChange={setPage}/>
    {detailError && <Alert type="error" showIcon message={detailError}/>}
    {selected && <Card size="small" style={{ marginTop: 12 }} title={`意图 ${selected.id}`}>
      <p>状态：{stateLabel[selected.state] ?? selected.state} · revision {selected.revision} · 厂商码 {selected.vendor_code ?? '—'}</p>
      <p>目标源站：{selected.intended_origin ?? '删除/无目标'} · 原源站：{selected.previous_origin ?? '无'} · 覆盖确认：{selected.overwrite_confirmed ? '是' : '否'}</p>
      <p>远端摘要：<Typography.Text code>{selected.remote_digest.slice(0, 16)}…</Typography.Text> · 原因：{selected.reason}</p>
      <p className="hik-callback-note">原始订阅URL与凭据不返回；下方观测仅含源站与摘要。</p>
      {terminalUnsafe.has(selected.state) && canWrite && <Space wrap>
        <Button loading={acting} disabled={acting} onClick={() => void reconcile(selected.id)}>只读核对</Button>
      </Space>}
      {selected.state === 'PREVIEW' && canWrite && <Space wrap>
        <Input aria-label="取消原因" value={actionReason} onChange={event => setActionReason(event.target.value)} placeholder="取消原因"/>
        <Button loading={acting} disabled={acting || !actionReason.trim()} onClick={() => void cancelPreview(selected.id)}>取消未发送意图</Button>
      </Space>}
      {actionError && <Alert type="error" showIcon message={actionError}/>}
      <h4>尝试记录</h4>
      <ol className="binding-history">{selected.attempts.map(item => <li key={item.id}><strong>#{item.attempt_no} · {item.phase} · {item.outcome}</strong><div className="mono">{item.vendor_code ?? '无厂商码'} · {item.started_at} → {item.finished_at ?? '未结束'}</div>{item.detail != null && <pre className="json-preview">{JSON.stringify(item.detail, null, 2)}</pre>}</li>)}</ol>
      <h4>只读观测（不可变）</h4>
      <ol className="binding-history">{selected.observations.map(item => <li key={item.id}><strong>{item.trigger} · {item.observed_at}</strong><div className="mono">list {item.list_digest.slice(0, 16)}…</div><pre className="json-preview">{JSON.stringify(item.snapshot, null, 2)}</pre></li>)}</ol>
    </Card>}
  </Card>
}
