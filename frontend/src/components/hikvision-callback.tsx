import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Checkbox, Input, Pagination, Space, Switch, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ApiError, errorMessage } from '@/lib/api'
import { hikvisionCallbackApi, type HikvisionCallbackReceipt, type HikvisionCallbackStatus, type HikvisionCallbackSubscription } from '@/lib/hikvision-callback'
import { useSession } from '@/lib/store'
import './hikvision-callback.css'
import { HikvisionSubscriptions } from './hikvision-subscriptions'

const PAGE_SIZE = 20
const statusLabel: Record<HikvisionCallbackReceipt['state'], string> = { PENDING: '待处理', PROCESSED: '已交解码器（不代表已归属）', PARTIAL: '部分待关注', REJECTED: '拒绝处理' }
const time = (value: string | null) => value ? new Date(value).toLocaleString() : '暂无'
function canonicalPublicOrigin(input: string): string | null {
  try {
    const url = new URL(input.trim())
    if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) return null
    const hostname = url.hostname.toLowerCase()
    if (!hostname || hostname === 'localhost' || hostname.endsWith('.localhost') || hostname.endsWith('.local')) return null
    const ipv4 = hostname.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/)
    if (ipv4) {
      const octets = ipv4.slice(1).map(Number)
      if (octets.some(value => value > 255) || octets[0] === 0 || octets[0] === 10 || octets[0] === 127 || octets[0] === 169 && octets[1] === 254 || octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31 || octets[0] === 192 && octets[1] === 168 || octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127 || octets[0] === 192 && octets[1] === 0 || octets[0] === 198 && (octets[1] === 18 || octets[1] === 19 || octets[1] === 51 && octets[2] === 100) || octets[0] === 203 && octets[1] === 0 && octets[2] === 113 || octets[0] >= 224) return null
    }
    if (hostname === '[::1]' || hostname === '[::]' || hostname.startsWith('[::ffff:') || hostname.startsWith('[fc') || hostname.startsWith('[fd') || hostname.startsWith('[fe8') || hostname.startsWith('[fe9') || hostname.startsWith('[fea') || hostname.startsWith('[feb')) return null
    return url.origin
  } catch { return null }
}

export function HikvisionCallback() {
  const session = useSession(), owner = `${session.authGeneration}:${session.generation}:${session.permissions.join('|')}`
  if (!session.permissions.includes('integrations:read')) return <Alert type="warning" showIcon message="当前账户无海康事件推送读取权限" />
  return <CallbackPanel key={owner} canWrite={session.permissions.includes('integrations:write')} />
}
function CallbackPanel({ canWrite }: { canWrite: boolean }) {
  const generation = useSession(s => s.authGeneration)
  const [status, setStatus] = useState<HikvisionCallbackStatus | null>(null), [receipts, setReceipts] = useState<HikvisionCallbackReceipt[]>([])
  const [page, setPage] = useState(1), [total, setTotal] = useState(0), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [editing, setEditing] = useState(false), [origin, setOrigin] = useState(''), [enabled, setEnabled] = useState(false), [enabledTypes, setEnabledTypes] = useState<string[]>([]), [credential, setCredential] = useState(''), [saving, setSaving] = useState(false), [uncertain, setUncertain] = useState(false)
  const [reparseReceipt, setReparseReceipt] = useState<HikvisionCallbackReceipt | null>(null), [reparsing, setReparsing] = useState(false), [reparseUncertain, setReparseUncertain] = useState(false), [reparseError, setReparseError] = useState('')
  const [queuedNotice, setQueuedNotice] = useState<string | null>(null)
  const [subscriptions, setSubscriptions] = useState<{ checked_at: string; items: HikvisionCallbackSubscription[] } | null>(null), [checking, setChecking] = useState(false), [checkError, setCheckError] = useState('')
  const saveKey = useRef<string | null>(null), reparseKey = useRef<string | null>(null), readController = useRef<AbortController | null>(null), saveController = useRef<AbortController | null>(null), subscriptionController = useRef<AbortController | null>(null), reparseController = useRef<AbortController | null>(null), readSequence = useRef(0), pageRef = useRef(page)
  pageRef.current = page
  const load = async (signal: AbortSignal, resetUnknown = false, requestedPage = pageRef.current) => {
    const sequence = ++readSequence.current
    const ownsResponse = () => !signal.aborted && sequence === readSequence.current && requestedPage === pageRef.current && useSession.getState().authGeneration === generation
    try {
      const [config, result] = await Promise.all([hikvisionCallbackApi.get(signal), hikvisionCallbackApi.receipts(requestedPage, PAGE_SIZE, signal)])
      if (!ownsResponse()) return
      setStatus(config); setReceipts(result.items); setTotal(result.total); setError('')
      if (resetUnknown) {
        saveKey.current = null; setUncertain(false)
        reparseKey.current = null; setReparseUncertain(false); setReparseReceipt(null); setReparseError(''); setQueuedNotice(null)
      }
    } catch (cause) { if (ownsResponse()) setError(errorMessage(cause)) }
    finally { if (ownsResponse()) setLoading(false) }
  }
  useEffect(() => {
    readController.current?.abort()
    const abort = new AbortController(); readController.current = abort; setLoading(true); void load(abort.signal)
    return () => { abort.abort(); if (readController.current === abort) readController.current = null }
  }, [generation, page])
  useEffect(() => () => {
    readController.current?.abort(); saveController.current?.abort(); subscriptionController.current?.abort(); reparseController.current?.abort()
    if (useSession.getState().authGeneration === generation) { setCredential('') }
  }, [generation])
  useEffect(() => { if (!canWrite) { setEditing(false); setCredential(''); setReparseReceipt(null) } }, [canWrite])
  function openEditor() { if (!status || !canWrite) return; setOrigin(status.public_origin ?? ''); setEnabled(status.enabled); setEnabledTypes([...status.enabled_message_types]); setCredential(''); setEditing(value => !value) }
  function generate() {
    if (!globalThis.crypto?.getRandomValues) { setError('浏览器不支持安全随机数，未生成凭据。'); return }
    const bytes = crypto.getRandomValues(new Uint8Array(32)); setCredential(Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join(''))
  }
  async function copyUrl() {
    const normalizedOrigin = canonicalPublicOrigin(origin)
    if (!credential || !status || !normalizedOrigin) { setError('请先生成凭据，并输入合法的HTTPS公网源站（仅源站，不含路径、查询或凭据）。'); return }
    const receiver = `${normalizedOrigin}${status.receiver_path.replace('{credential}', credential)}`
    try { await navigator.clipboard.writeText(receiver); if (useSession.getState().authGeneration === generation) message.success('完整接收地址已复制；请妥善保管。') }
    catch { if (useSession.getState().authGeneration === generation) setError('无法访问剪贴板；请确认页面处于安全上下文后重试。') }
  }
  async function save() {
    if (!status || !canWrite || saving || checking || uncertain || reparseReceipt || queuedNotice) return
    if (!window.confirm(`保存接收端配置：源站 ${origin.trim() || '未填写'} · ${enabled ? '启用' : '停用'} · 类型 ${enabledTypes.join('、') || '无'}${credential ? ' · 轮换接收凭据（旧URL立即失效）' : ''}。启用报警类型可能创建真实报警并发送通知。继续？`)) return
    setSaving(true); setError('')
    const body = { expected_revision: status.revision, enabled, enabled_message_types: enabledTypes, public_origin: origin.trim(), ...(credential ? { credential } : {}) }
    if (!saveKey.current) saveKey.current = crypto.randomUUID()
    const abort = new AbortController(); saveController.current?.abort(); saveController.current = abort
    try {
      const updated = await hikvisionCallbackApi.save(body, saveKey.current, abort.signal)
      if (abort.signal.aborted || useSession.getState().authGeneration !== generation) return
      saveKey.current = null; setUncertain(false); setStatus(updated); setEditing(false); setCredential(''); setError(''); setSubscriptions(null)
      readController.current?.abort()
      const read = new AbortController(); readController.current = read; setLoading(true); await load(read.signal)
      if (!abort.signal.aborted && useSession.getState().authGeneration === generation) message.success('事件推送配置已保存。')
    } catch (cause) {
      if (!abort.signal.aborted && useSession.getState().authGeneration === generation) {
        if (!(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500) { setUncertain(true); setError(`${errorMessage(cause)}。结果可能未知；不会自动重试。请先显式刷新核对配置后再操作。`) }
        else { saveKey.current = null; setError(errorMessage(cause)) }
      }
    } finally { if (useSession.getState().authGeneration === generation) { if (!abort.signal.aborted) setSaving(false) }; if (saveController.current === abort) saveController.current = null }
  }
  function refresh() {
    if (reparsing || saving || checking) return
    setEditing(false); setCredential(''); setSubscriptions(null); setLoading(true)
    readController.current?.abort()
    const read = new AbortController(); readController.current = read; void load(read.signal, true)
  }
  function beginReparse(receipt: HikvisionCallbackReceipt) {
    if (!canWrite || reparseReceipt || queuedNotice || saving || checking || !['PARTIAL', 'REJECTED'].includes(receipt.state) || !status?.enabled || !status.credential_configured) return
    setReparseReceipt(receipt); setReparseError(''); setReparseUncertain(false); reparseKey.current = crypto.randomUUID()
  }
  async function submitReparse() {
    const receipt = reparseReceipt
    if (!receipt || reparsing || !reparseKey.current) return
    if (!window.confirm(`将重新解析收据 ${receipt.id}（修订 ${receipt.revision}）保存的原始报文；不会重新获取数据，但可能创建真实报警并发送通知。继续？`)) return
    const key = reparseKey.current, body = { expected_revision: receipt.revision }
    const abort = new AbortController(); reparseController.current?.abort(); reparseController.current = abort; setReparsing(true); setReparseError('')
    try {
      const queued = await hikvisionCallbackApi.reparse(receipt.id, body, key, abort.signal)
      if (abort.signal.aborted || useSession.getState().authGeneration !== generation) return
      setQueuedNotice(queued.id); setReparseReceipt(null); setReparseUncertain(false); reparseKey.current = null
      message.success('已排队重新解析；请显式刷新以查看实际处理结果。')
    } catch (cause) {
      if (!abort.signal.aborted && useSession.getState().authGeneration === generation) {
        if (!(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500) { setReparseUncertain(true); setReparseError(`${errorMessage(cause)}。结果可能未知；请使用相同意图与幂等键重试，或显式刷新后再开始新意图。`) }
        else { setReparseError(cause.status === 409 ? `${errorMessage(cause)}。请显式刷新，核对最新修订和状态后再操作。` : errorMessage(cause)) }
      }
    } finally { if (useSession.getState().authGeneration === generation) { if (!abort.signal.aborted) setReparsing(false) }; if (reparseController.current === abort) reparseController.current = null }
  }
  async function checkSubscriptions() {
    if (checking || saving) return
    const abort = new AbortController(); subscriptionController.current?.abort(); subscriptionController.current = abort; setChecking(true); setCheckError(''); setSubscriptions(null)
    try { const result = await hikvisionCallbackApi.subscriptions(abort.signal); if (!abort.signal.aborted && useSession.getState().authGeneration === generation) setSubscriptions(result) }
    catch (cause) { if (!abort.signal.aborted && useSession.getState().authGeneration === generation) setCheckError(errorMessage(cause)) }
    finally { if (useSession.getState().authGeneration === generation) { if (!abort.signal.aborted) setChecking(false) }; if (subscriptionController.current === abort) subscriptionController.current = null }
  }
  const labels: Record<string, string> = { '980002': '报警事件', '980004': '故障事件', '980008': '监测数据', '980016': '原生设备事件' }
  const columns: ColumnsType<HikvisionCallbackReceipt> = [
    { title: '接收时间', dataIndex: 'received_at', width: 160, render: (v: string) => time(v) },
    { title: '最近重复', dataIndex: 'last_received_at', width: 160, render: (v: string) => time(v) },
    { title: '状态', dataIndex: 'state', width: 190, render: (v: HikvisionCallbackReceipt['state']) => <Tag>{statusLabel[v]}</Tag> },
    { title: '消息类型', dataIndex: 'message_types', width: 155, render: (values: string[]) => <span className="hik-callback-ellipsis" title={values.join(', ')}>{values.join(', ') || '—'}</span> },
    { title: '分派解析项', dataIndex: 'submitted_records', width: 90 }, { title: '未启用或未支持', dataIndex: 'unsupported_messages', width: 125 },
    { title: '重复次数', dataIndex: 'duplicate_count', width: 80 }, { title: '处理代码', dataIndex: 'error_code', width: 130, render: (v: string | null) => v ?? '—' },
    { title: '修订', dataIndex: 'revision', width: 70 }, { title: '重新解析次数', dataIndex: 'reparse_count', width: 110 },
    { title: '最近重新解析', dataIndex: 'last_reparse_at', width: 160, render: (v: string | null) => time(v) },
    { title: '重解析', key: 'reparse', width: 120, fixed: 'right', render: (_, receipt) => ['PARTIAL', 'REJECTED'].includes(receipt.state) && <Button size="small" disabled={!canWrite || !status?.enabled || !status.credential_configured || Boolean(reparseReceipt) || Boolean(queuedNotice) || saving || checking || reparsing} onClick={() => beginReparse(receipt)}>重新解析</Button> },
  ]
  return <div className="hik-callback">
    <Card data-testid="hik-callback-status" title="海康事件与监测推送" extra={<Button onClick={refresh} loading={loading} disabled={saving || checking || reparsing}>刷新</Button>}>
      {error && <Alert type="error" showIcon message={error} />}
      {loading && !status ? <p>正在读取真实状态…</p> : status && <>
        <div className="hik-callback-metrics"><div><small>接收端</small><strong>{status.enabled ? '已启用' : '已停用'}</strong></div><div><small>待处理</small><strong>{status.pending_count}</strong></div><div><small>已交解码器</small><strong>{status.processed_count}</strong></div><div><small>待关注</small><strong>{status.attention_count}</strong></div><div><small>最近接收</small><strong className="hik-callback-time" title={time(status.last_received_at)}>{time(status.last_received_at)}</strong></div><div><small>本节点处理worker</small><strong>{status.worker_enabled ? '已启用' : '未启用'}</strong></div></div>
        <p className="hik-callback-note">当前接收：{status.enabled_message_types.map(code => labels[code] ?? code).join('、') || '未选择类型'}</p>
        <details className="hik-callback-note"><summary>接入边界与认证说明</summary><p>支持消息类型：{status.supported_message_types.map(code => `${labels[code] ?? code}（${code}）`).join('、')}。收据保留已接收的有效报文；未启用类型会标记待关注。服务端收据计数不能证明报文来自真实厂商或反映实时事件。HTTPS共享地址凭据用于接收端访问，不是海康厂商签名。HTTP 200 仅表示原始报文已持久化；交给解码器不代表已完成设备归属或业务处理。</p></details>
        {canWrite && <Button data-testid="hik-callback-configure" onClick={openEditor} disabled={Boolean(reparseReceipt) || Boolean(queuedNotice) || reparsing || checking}>{editing ? '收起配置' : '配置接收端'}</Button>}
        {!status.credential_configured && <p className="hik-callback-note">接收地址模板：{status.public_origin ?? '未配置HTTPS源站'}{status.receiver_path.replace('{credential}', '〔凭据仅在保存前显示〕')}</p>}
        {editing && canWrite && <section className="hik-callback-editor" data-testid="hik-callback-editor" aria-label="事件推送接收端配置">
          <label>HTTPS公网源站<Input aria-label="HTTPS公网源站" value={origin} onChange={e => setOrigin(e.target.value)} placeholder="https://example.com" /></label>
          <label className="hik-callback-switch">启用接收端 <Switch checked={enabled} onChange={setEnabled}/></label>
          <label>启用的消息类型<Checkbox.Group data-testid="hik-callback-enabled-message-types" aria-label="启用的消息类型" options={status.supported_message_types.map(code => ({ label: labels[code] ?? code, value: code }))} value={enabledTypes} onChange={values => setEnabledTypes(values as string[])}/></label>
          <Alert type="warning" showIcon message="启用报警类型可能创建真实报警并按现有通知规则发送通知；请勿用生产接收端进行付费通知测试。"/>
          <div><Button onClick={generate}>在浏览器生成新凭据</Button>{credential && <><Typography.Text code>{credential}</Typography.Text><Button onClick={() => void copyUrl()}>复制完整接收URL</Button></>}</div>
          {credential && <Alert type="warning" showIcon message="保存后明文凭据不会再次显示。请先复制并安全保存完整回调URL；轮换会立即使旧URL失效，云订阅不会自动更新。"/>}
          <p>留空凭据表示保留现有值。禁用接收端不会删除已接收收据。启用接收端时至少选择一种消息类型。</p>
          <Button data-testid="hik-callback-save" type="primary" disabled={saving || checking || uncertain || enabled && !enabledTypes.length || !status.secret_storage_ready && enabled} loading={saving} onClick={() => void save()}>保存配置</Button>
          {!status.secret_storage_ready && <Alert type="warning" message="加密密钥存储不可用；无法启用接收端。"/>}
        </section>}
      </>}
    </Card>
    {canWrite && reparseReceipt && <Card data-testid="hik-callback-reparse-confirm" className="hik-callback-reparse" aria-label="重新解析确认" title="重新解析收据">
      <p>将重新提交此收据保存的原始报文，不会重新获取数据；解析后可能创建真实报警，并按现有通知规则发送通知。</p>
      <p>收据 {reparseReceipt.id} · 修订 {reparseReceipt.revision} · 已重新解析 {reparseReceipt.reparse_count} 次 · 最近排队 {time(reparseReceipt.last_reparse_at)}</p>
      {reparseError && <Alert type="error" showIcon message={reparseError}/>}
      {reparseUncertain && <Alert type="warning" showIcon message="操作结果未知。请以相同收据、修订和幂等键重试，或先显式刷新查看真实状态；刷新后才可开始新意图。"/>}
      <Space wrap><Button type="primary" disabled={reparsing || !status?.enabled} loading={reparsing} onClick={() => void submitReparse()}>{reparseUncertain ? '使用原幂等键重试' : '确认排队重新解析'}</Button><Button disabled={reparsing || reparseUncertain} onClick={() => { setReparseReceipt(null); reparseKey.current = null }}>取消</Button></Space>
    </Card>}
    {queuedNotice && <Alert type="info" showIcon title="重新解析已排队，尚未确认处理结果" description="列表和计数仍为上次读取结果；请点击顶部“刷新”查看服务器的实际状态。"/>}
    <Card data-testid="hik-callback-receipts" title="接收收据"><div className="hik-callback-table"><Table rowKey="id" size="small" loading={loading} pagination={false} scroll={{ x: 1530 }} dataSource={receipts} columns={columns}/></div><Pagination size="small" current={page} pageSize={PAGE_SIZE} total={total} showSizeChanger={false} onChange={setPage}/></Card>
    <details className="hik-callback-secondary"><summary>只读核对云端订阅</summary><Card data-testid="hik-callback-subscriptions">
      <p className="hik-callback-note">仅在点击后调用只读订阅列表接口，不会注册、修改或删除云端订阅。</p>
      {canWrite ? <Space wrap><Button disabled={checking || saving} loading={checking} onClick={() => void checkSubscriptions()}>只读核对云端订阅</Button></Space> : <p>需要 integrations:write 权限。</p>}
      {checkError && <Alert type="error" message={checkError}/>}
      {subscriptions && <><p className="hik-callback-note">核对时间：{time(subscriptions.checked_at)}</p><div className="hik-callback-table"><Table rowKey="message_type" size="small" pagination={false} scroll={{ x: 600 }} dataSource={subscriptions.items} columns={[{ title: '消息类型', dataIndex: 'message_type', width: 130 }, { title: '目标源站', dataIndex: 'destination_origin', width: 250, render: (v: string | null) => v ?? '无法安全识别' }, { title: '匹配接收端', dataIndex: 'matches_receiver', width: 120, render: (v: boolean) => v ? '匹配' : '不匹配' }, { title: '云端更新时间', dataIndex: 'updated_at', width: 170, render: (v: string | null) => time(v) }]}/></div></>}
    </Card></details>
    <HikvisionSubscriptions/>
  </div>
}
