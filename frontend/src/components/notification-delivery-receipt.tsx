import { useState, type FormEvent } from 'react'
import { RefreshCw } from 'lucide-react'
import { notificationDeliveryApi, type DeliveryDetail, type DeliveryObservation, type ReceiptConfig, type ReconcileResult } from '@/lib/notification-delivery'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Empty, ErrorNotice, Loading } from '@/components/common'
import { Badge } from '@/components/ui/badge'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { dateTime } from '@/lib/format'

const receiptLabels: Record<string, string> = {
  UNCONFIRMED: '未确认', WAITING: '等待回执', DELIVERED: '已送达', FAILED: '回执失败', ACCEPTED: '厂商已受理',
  HEARD_FULL: '已听完语音', CONNECTED_PARTIAL: '接通未听完', NOT_CONNECTED: '未接通',
}
const reasonLabels: Record<string, string> = {
  ATTEMPT_NOT_ACCEPTED: '厂商尚未受理该次发送，不能查询送达', PROVIDER_ID_UNAVAILABLE: '缺少厂商回执 ID，无法证明对应记录',
  DESTINATION_SNAPSHOT_UNAVAILABLE: '历史尝试未保存当时目标号码，不可猜测', ACCOUNT_SNAPSHOT_UNAVAILABLE: '历史尝试未保存厂商账户凭据',
  PROVIDER_NOT_CONFIGURED: '当前未配置可查询的厂商账户', ACCOUNT_MISMATCH: '当前厂商账户与发送时不一致，避免错绑',
  SEND_DATE_UNAVAILABLE: '缺少发送日期', SEND_DATE_INVALID: '发送日期异常', QUERY_WINDOW_EXHAUSTED: '已超出厂商可查询时间窗口',
DESTINATION_UNAVAILABLE: '无法解密当时目标号码', DESTINATION_SNAPSHOT_TAMPERED: '目标号码快照完整性校验失败',
  SEND_WAITING: '运营商等待回执', SEND_FAILED: '厂商发送失败', CARRIER_REJECTED: '运营商回执失败', RECEIPT_NOT_FOUND: '尚未查到该回执记录', PAGE_INCOMPLETE: '厂商分页未返回完整记录',
  MULTIPLE_MATCHES: '同一回执 ID 命中多条记录', DESTINATION_MISMATCH: '厂商号码与发送快照不一致',
  OUT_ID_MISMATCH: '厂商 OutId 与本地尝试不一致', CORRELATION_MISMATCH: '厂商通话标识与本地 CallId 不一致',
  UNKNOWN_SEND_STATUS: '厂商返回未知发送状态', MISSING_ERR_CODE: '厂商未返回送达错误码',
  UNKNOWN_PROVIDER_STATUS: '厂商返回未知通话状态', INCONSISTENT_EVIDENCE: '通话状态与时长/接通时间不一致',
  MISSING_PROVIDER_STATUS: '厂商未返回通话状态', NOT_SYNCHRONIZED: '厂商详情尚未同步',
  PROVIDER_UNAVAILABLE: '厂商查询暂不可用', QUERY_REJECTED: '厂商拒绝查询请求',
  WECHAT_CALLBACK_ONLY: '公众号送达只由微信服务器完成事件证明，不支持主动查询',
  USER_BLOCKED: '用户拒收公众号消息', SYSTEM_FAILED: '微信系统发送失败', OPENID_MISMATCH: '回调 OpenID 与发送快照不一致',
  WECHAT_RECIPIENT_UNSUBSCRIBED: '接收人已取消关注公众号', WECHAT_BINDING_REVOKED: '微信绑定已解除',
}
const sourceLabels: Record<string, string> = { MANUAL: '人工查询', SCHEDULED: '定时查询', SEND_RESULT: '发送受理结果', PROVIDER_CALLBACK: '微信完成事件回调' }
export const receiptReason = (reason: string | null) => reason == null ? '—' : reasonLabels[reason] ?? reason

function badgeVariant(state: string): 'default' | 'destructive' | 'outline' {
  if (state === 'DELIVERED' || state === 'HEARD_FULL') return 'default'
  if (state === 'FAILED' || state === 'NOT_CONNECTED') return 'destructive'
  return 'outline'
}
const channelLabels: Record<string, string> = { SMS: '短信', VOICE: '语音', WECHAT: '公众号' }

export function NotificationDeliveryReceiptPanel({ id, canReconcile, onChanged }: { id: string; canReconcile: boolean; onChanged: () => void }) {
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds)
  const scopeKey = useSession(s => `${s.scopeTenantIds.join(',')}|${s.scopePlaceIds.join(',')}|${s.scopeInitialized}`)
  const detail = useResource(`notification-delivery-detail:${scopeKey}:${id}`, signal => notificationDeliveryApi.detail(id, scopeTenantIds, scopePlaceIds, signal))
  if (detail.loading) return <div className="review-body"><Loading/></div>
  if (detail.error != null) return <div className="review-body"><ErrorNotice error={detail.error} retry={detail.refresh}/></div>
  if (!detail.data) return <div className="review-body"><Empty title="未找到该发送记录"/></div>
  return <DeliveryDetailBody detail={detail.data} canReconcile={canReconcile} refresh={detail.refresh} onChanged={onChanged}/>
}

function DeliveryDetailBody({ detail, canReconcile, refresh, onChanged }: { detail: DeliveryDetail; canReconcile: boolean; refresh: () => void; onChanged: () => void }) {
  const { delivery, observations, config } = detail
  const receipt = delivery.receipt
  const [reason, setReason] = useState('')
  const [result, setResult] = useState<ReconcileResult | null>(null)
  const action = useMutationAction()
  const expectedRevision = receipt?.revision ?? 0
  const blocked = delivery.eligibility.queryable ? null : delivery.eligibility.reason
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canReconcile || blocked != null || !reason.trim()) { action.setError(new Error('请填写本次查询理由')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run(async (key, signal) => {
      const outcome = await notificationDeliveryApi.reconcile(delivery.id, { expected_revision: expectedRevision, reason: reason.trim() }, key, scopeTenantIds, scopePlaceIds, signal)
      setResult(outcome)
      onChanged()
      refresh()
    }, () => { setReason(''); action.touch() })
  }
  return <>
    <SheetHeader className="review-header"><SheetTitle>发送回执 · {channelLabels[delivery.channel] ?? delivery.channel}</SheetTitle><SheetDescription>发送受理、厂商送达/接听回执和未确认状态彼此独立；短信/语音查询回执不重发、不清除报警、不改变计费或设备状态。公众号送达只由微信认证完成事件写入，未收到回调保持未确认。</SheetDescription></SheetHeader>
    <div className="review-body">
      <dl className="detail-list">
        <div><dt>尝试编号</dt><dd className="mono">{delivery.id}</dd></div>
        <div><dt>事件来源</dt><dd>{delivery.event_kind === 'FIRE' ? '火警' : '故障'} · <span className="mono">{delivery.source_id}</span></dd></div>
        <div><dt>设备 / 联系人</dt><dd className="mono">{delivery.device_id} · {delivery.contact_id}</dd></div>
        <div><dt>发送受理状态</dt><dd><Badge variant={delivery.status === 'FAILED' ? 'destructive' : 'outline'}>{delivery.status}</Badge> <span className="muted">厂商码 {delivery.provider_code ?? '—'} · 回执 ID {delivery.provider_id ?? '—'}</span></dd></div>
        <div><dt>{delivery.channel === 'WECHAT' ? '发送时接收 OpenID' : '发送时目标号码'}</dt><dd className="mono">{delivery.snapshot.destination_masked ?? '未保存（不可猜测）'}</dd></div>
        <div><dt>快照绑定</dt><dd>{delivery.channel === 'WECHAT' ? 'OpenID' : '目标号码'} {delivery.snapshot.destination_bound ? '已保存' : '未保存'} · 厂商账户 {delivery.snapshot.account_bound ? '已绑定' : '未绑定'} · 发送日期 {delivery.snapshot.send_date ?? '—'} · 关联 ID {delivery.provider_id ?? '—'}{delivery.template_id ? ` · 模板 ${delivery.template_id}` : ''}</dd></div>
        <div><dt>回执状态</dt><dd>{receipt ? <><Badge variant={badgeVariant(receipt.state)}>{receiptLabels[receipt.state] ?? receipt.state}</Badge> <span className="muted">原因 {receiptReason(receipt.reason)} · revision {receipt.revision}</span></> : <Badge variant="outline">未确认</Badge>}</dd></div>
        <div><dt>厂商原始状态</dt><dd className="mono">{receipt?.provider_status ?? '—'} / {receipt?.provider_code ?? '—'}</dd></div>
        <div><dt>最近回执时间</dt><dd className="muted">{dateTime(receipt?.last_observed_at ?? null)}</dd></div>
      </dl>
      {result && <div role="status" className="notice">本次{result.network_queried ? '已调用厂商查询' : '未调用厂商（本地不可证明）'}：{receiptLabels[result.state] ?? result.state}（{receiptReason(result.reason)}） · revision {result.revision}。</div>}
      {canReconcile ? <form className="form-stack" onSubmit={submit}>
        {blocked != null && <div className="notice">当前不可证明：{receiptReason(blocked)}。系统不会用当前联系人、当前厂商账户或猜测号码替代历史证据。</div>}
        {!config.effective.provider_ready && <div className="notice">当前部署未配置可查询的厂商 AccessKey，查询只能返回未确认原因。</div>}
        <div className="field"><Label htmlFor="receipt-reason">本次查询理由</Label><Input id="receipt-reason" value={reason} maxLength={1000} required disabled={action.busy} onChange={event => { setReason(event.target.value); action.touch() }}/></div>
        {action.confirming && <OperationConfirmation action={action} title="确认向厂商查询回执" submitLabel={action.busy ? '查询中…' : '查询回执'} refresh={() => { action.touch(); refresh() }} warning="只执行厂商只读详情查询；不会重发短信/语音，不会更改或冲正余额，也不会改变报警或故障状态。">已读取 revision {expectedRevision}；{blocked != null ? `本地判定不可查询（${receiptReason(blocked)}）` : '将在提交后校验当前会话、权限、单位与设备范围。'}</OperationConfirmation>}
        {action.error != null && <ErrorNotice error={action.error}/>}
        {!action.confirming && <Button type="submit" disabled={blocked != null || !reason.trim() || action.busy}><RefreshCw/>查询回执</Button>}
        {action.conflict && <div className="notice">回执状态已在其他请求中更新。请刷新详情后按新的 revision 重新确认。</div>}
      </form> : <div className="notice">当前账户没有“查询厂商回执”权限，只能查看已保存证据。</div>}
      <h3>不可变回执观测</h3>
      {observations.length ? <div className="table-wrap"><table className="data-table"><thead><tr><th>时间</th><th>来源</th><th>调用厂商</th><th>状态</th><th>原因</th><th>厂商状态 / 码</th><th>号码（掩码）</th><th>时长</th><th>请求编号</th><th>证据摘要</th></tr></thead><tbody>
        {observations.map((observation: DeliveryObservation) => <tr key={observation.id}><td className="muted">{dateTime(observation.observed_at)}</td><td>{sourceLabels[observation.source] ?? observation.source}</td><td>{observation.network_queried ? '是' : '否'}</td><td><Badge variant={badgeVariant(observation.state)}>{receiptLabels[observation.state] ?? observation.state}</Badge></td><td>{receiptReason(observation.reason)}</td><td className="mono">{observation.provider_status ?? '—'} / {observation.provider_code ?? '—'}</td><td className="mono">{observation.provider_phone_masked ?? '—'}</td><td>{observation.duration_seconds ?? '—'}</td><td className="mono">{observation.query_request_id ?? '—'}</td><td className="mono">{observation.evidence_digest.slice(0, 12)}</td></tr>)}
      </tbody></table></div> : <Empty title="暂无回执观测"/>}
      <QueryConfig config={config}/>
    </div>
  </>
}

const policyReasonLabels: Record<string, string> = {
  BATCH_SIZE_CAPPED: '批量受环境上限收紧', MIN_AGE_CAPPED: '最小等待受环境上限收紧', RETRY_CAPPED: '重试间隔受环境上限收紧',
  MAX_QUERIES_CAPPED: '单次查询上限受环境收紧', ENV_QUERY_DISABLED: '部署环境总开关禁止定时查询', PROVIDER_NOT_CONFIGURED: '厂商查询凭据未配置',
}

function QueryConfig({ config }: { config: ReceiptConfig }) {
  const { desired, effective, technical } = config
  return <><dl className="detail-list">
    <div><dt>定时查询（期望）</dt><dd>{desired.enabled ? '页面已启用' : '未启用（默认关闭）'} · revision {desired.revision} · 更新 {dateTime(desired.updated_at)}</dd></div>
    <div><dt>定时查询（实际）</dt><dd>{effective.enabled ? '实际生效' : '当前不执行'} · 环境总开关 {effective.env_allowed ? '允许' : '关闭'} · 厂商凭据 {effective.provider_ready ? '已配置' : '未配置'}</dd></div>
    <div><dt>期望参数</dt><dd>{desired.min_age_seconds} 秒后查询 · 重试 {desired.retry_seconds} 秒 · 单次发送最多 {desired.max_queries_per_attempt} 次 · 批大小 {desired.batch_size}</dd></div>
    <div><dt>实际参数</dt><dd>{effective.min_age_seconds} 秒后查询 · 重试 {effective.retry_seconds} 秒 · 单次发送最多 {effective.max_queries_per_attempt} 次 · 批大小 {effective.batch_size}</dd></div>
    {effective.clamped_reasons.length > 0 && <div><dt>收紧原因</dt><dd>{effective.clamped_reasons.map(reason => policyReasonLabels[reason] ?? reason).join('；')}</dd></div>}
    <div><dt>查询窗口</dt><dd>短信最近 {technical.sms_lookback_days} 天 · 语音最近 {technical.voice_lookback_months} 个月（QueryDate 为原始呼叫日期） · ProdId {technical.voice_prod_id}</dd></div>
    <div><dt>环境安全上限</dt><dd>批 ≤ {technical.max_batch_size} · 最小等待 ≤ {technical.max_min_age_seconds} 秒 · 重试 ≤ {technical.max_retry_seconds} 秒 · 单次查询 ≤ {technical.max_queries_per_attempt} 次</dd></div>
  </dl><p className="muted-note">可在页面顶部“回执查询策略”中修改期望参数。环境值只作为技术安全上限，实际值会被收紧并在此明确展示。</p></>
}

export function NotificationReceiptPolicyPanel({ canConfigure, onSaved }: { canConfigure: boolean; onSaved: () => void }) {
  const policy = useResource('notification-receipt-policy', signal => notificationDeliveryApi.config(signal))
  return <><SheetHeader className="review-header"><SheetTitle>回执查询策略</SheetTitle><SheetDescription>控制可选的定时厂商回执查询。默认关闭；开启后只执行只读详情查询，不发送通知、不改变报警、故障、设备或资源余额。环境技术上限可能收紧实际值。</SheetDescription></SheetHeader>
    <div className="review-body">{policy.loading ? <Loading/> : policy.error != null ? <ErrorNotice error={policy.error} retry={policy.refresh}/> : policy.data ? <><QueryConfig config={policy.data}/>{canConfigure ? <PolicyForm key={policy.data.desired.revision} desired={policy.data.desired} technical={policy.data.technical} onSaved={() => { policy.refresh(); onSaved() }}/> : <div className="notice">当前账户没有维护通知配置的权限（notification-config:write），只能查看期望值与实际生效值。</div>}</> : <Empty title="查询策略不可用"/>}</div>
  </>
}

function PolicyForm({ desired, technical, onSaved }: { desired: ReceiptConfig['desired']; technical: ReceiptConfig['technical']; onSaved: () => void }) {
  const [enabled, setEnabled] = useState(desired.enabled)
  const [minAge, setMinAge] = useState(String(desired.min_age_seconds))
  const [retry, setRetry] = useState(String(desired.retry_seconds))
  const [maxQueries, setMaxQueries] = useState(String(desired.max_queries_per_attempt))
  const [batch, setBatch] = useState(String(desired.batch_size))
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function invalid(): string | null {
    if ([minAge, retry, maxQueries, batch].some(value => value.trim() === '')) return '请填写所有数值'
    const values = [minAge, retry, maxQueries, batch].map(value => Number(value))
    if (!values.every(value => Number.isSafeInteger(value))) return '所有数值必须是整数'
    if (values[0] < 0 || values[0] > 86400) return '最小等待必须为 0–86400 秒'
    if (values[1] < 60 || values[1] > 604800) return '重试间隔必须为 60–604800 秒'
    if (values[2] < 1 || values[2] > 500) return '单次查询上限必须为 1–500'
    if (values[3] < 1 || values[3] > 100) return '批大小必须为 1–100'
    if (!reason.trim()) return '请填写本次变更理由'
    return null
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    const problem = invalid()
    if (problem) { action.setError(new Error(problem)); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run(async (key, signal) => {
      await notificationDeliveryApi.updateConfig({ expected_revision: desired.revision, periodic_query_enabled: enabled, min_age_seconds: Number(minAge), retry_seconds: Number(retry), max_queries_per_attempt: Number(maxQueries), batch_size: Number(batch), reason: reason.trim() }, key, signal)
    }, () => { setReason(''); action.touch(); onSaved() })
  }
  return <form className="form-stack" onSubmit={submit}>
    <h3>编辑期望策略</h3>
    <label className="field"><span><input type="checkbox" checked={enabled} disabled={action.busy} onChange={event => { setEnabled(event.target.checked); action.touch() }}/> 启用定时回执查询</span></label>
    <div className="form-stack"><label className="field">最小等待（秒）<Input type="number" required min={0} value={minAge} disabled={action.busy} onChange={event => { setMinAge(event.target.value); action.touch() }}/></label><label className="field">重试间隔（秒）<Input type="number" required min={60} value={retry} disabled={action.busy} onChange={event => { setRetry(event.target.value); action.touch() }}/></label><label className="field">单次发送最多查询次数<Input type="number" required min={1} value={maxQueries} disabled={action.busy} onChange={event => { setMaxQueries(event.target.value); action.touch() }}/></label><label className="field">每轮批大小<Input type="number" required min={1} value={batch} disabled={action.busy} onChange={event => { setBatch(event.target.value); action.touch() }}/></label></div>
    <label className="field">变更理由<Input required maxLength={1000} value={reason} disabled={action.busy} onChange={event => { setReason(event.target.value); action.touch() }}/></label>
    <p className="muted-note">环境技术上限：批 ≤ {technical.max_batch_size}、最小等待 ≤ {technical.max_min_age_seconds} 秒、重试 ≤ {technical.max_retry_seconds} 秒、单次查询 ≤ {technical.max_queries_per_attempt} 次。保存的是期望值，实际值会被收紧并公开显示。</p>
    {action.confirming && <OperationConfirmation action={action} title="确认保存回执查询策略" submitLabel={action.busy ? '保存中…' : '保存策略'} refresh={() => { action.touch(); onSaved() }} warning="只保存定时查询策略；不会立即向厂商查询、不会发送通知，也不会改变报警、故障、设备或资源余额。">将 revision {desired.revision} 更新为：{enabled ? '启用' : '停用'} · 最小等待 {minAge} 秒 · 重试 {retry} 秒 · 单次最多 {maxQueries} 次 · 批大小 {batch}。</OperationConfirmation>}
    {action.error != null && <ErrorNotice error={action.error}/>}
    {action.conflict && <div className="notice">策略已变化。请刷新后按新的 revision 重新确认。</div>}
    {!action.confirming && <Button type="submit" disabled={action.busy}>保存查询策略</Button>}
  </form>
}
