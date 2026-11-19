import { useState, type FormEvent } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { QrCode, RefreshCw, ShieldCheck, Unlink } from 'lucide-react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import { wechatApi, type WechatBindingStatus, type WechatRecipient, type WechatStartResult } from '@/lib/wechat-official-account'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Empty, ErrorNotice, Loading, Pager } from '@/components/common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'

/** Self-service binding. Never polls automatically and never binds without the original user's password. */
export function WechatSelfBinding() {
  const user = useSession(s => s.user)
  const [started, setStarted] = useState<WechatStartResult | null>(null)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const status = useResource<WechatBindingStatus>(`wechat-binding-status:${user?.id ?? ''}:${user?.tenant_id ?? ''}`, signal => wechatApi.status(signal), !!user)
  async function start() {
    setError(null); setNotice(''); setBusy(true)
    try {
      const result = await wechatApi.start()
      setStarted(result)
      setNotice('授权链接已生成。请在微信中扫码或打开授权页；完成后回到本页点击刷新。')
      status.refresh()
    } catch (cause) { setError(cause) } finally { setBusy(false) }
  }
  async function reject() {
    if (!status.data?.pending) return
    setError(null); setBusy(true)
    try { await wechatApi.reject(status.data.pending.id); setStarted(null); setNotice('已取消本次待确认授权。'); status.refresh() }
    catch (cause) { setError(cause) } finally { setBusy(false) }
  }
  if (!user) return null
  return <Card><CardContent><div className="list-toolbar"><h2 className="section-heading" style={{ margin: 0 }}><ShieldCheck size={18}/>微信通知绑定</h2><Button type="button" variant="outline" disabled={busy} onClick={() => { status.refresh(); setNotice('已重新读取绑定状态；不会自动确认。') }}><RefreshCw/>刷新状态</Button></div>
    {status.loading ? <Loading/> : status.error != null ? <ErrorNotice error={status.error} retry={status.refresh}/> : status.data && <>
      <div className={status.data.canStart ? 'notice success-note' : 'notice'} role="status">{status.data.message}{status.data.appIdMasked && ` · 公众号 ${status.data.appIdMasked}`}</div>
      {status.data.binding ? <dl className="detail-list">
        <div><dt>绑定状态</dt><dd>{status.data.binding.status === 'ACTIVE' ? '已绑定' : status.data.binding.status}{status.data.binding.stale ? '（旧公众号，需重新绑定）' : ''}</dd></div>
        <div><dt>OpenID（遮罩）</dt><dd className="mono">{status.data.binding.openidMasked}</dd></div>
        <div><dt>关注状态</dt><dd>{status.data.binding.followState === 'SUBSCRIBED' ? '已关注' : status.data.binding.followState === 'UNSUBSCRIBED' ? '已取消关注（不会发送）' : '未收到关注事件，按实际发送结果为准'}</dd></div>
        <div><dt>绑定时间</dt><dd>{dateTime(status.data.binding.boundAt)}</dd></div>
        <div><dt>最近关注事件</dt><dd>{dateTime(status.data.binding.lastSeenAt)}</dd></div>
      </dl> : <p className="muted-note">当前账号尚未绑定。绑定后仍需单位联系人引用、单位通道策略启用和通知规则命中，才会发送模板消息。</p>}
      {status.data.pending && <div className="notice" role="status"><strong>{status.data.pending.status === 'CALLBACK_RECEIVED' ? '授权已完成，待本人确认' : '等待微信授权'}</strong> · 有效至 {dateTime(status.data.pending.expiresAt)}{status.data.pending.openidMasked ? ` · ${status.data.pending.openidMasked}` : ''}{status.data.pending.status === 'CALLBACK_RECEIVED' && <ConfirmBinding pendingId={status.data.pending.id} revision={status.data.pending.revision} refresh={() => { setStarted(null); status.refresh() }}/>} {status.data.pending.status === 'PENDING' && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void reject()}>取消待确认授权</Button>}</div>}
      {started && <div className="payment-qr"><QRCodeSVG value={started.qrPayload} size={190}/><p>请使用微信扫描实际授权链接的二维码。二维码内容就是本次一次性 OAuth 地址，不是占位图。</p><Button type="button" variant="outline" onClick={() => window.open(started.authorizeUrl, '_blank', 'noopener,noreferrer')}><QrCode/>打开微信授权页</Button><p className="muted-note">授权链接有效至 {dateTime(started.expiresAt)} · 固定回调 {started.redirectUri}</p></div>}
      {!status.data.binding?.stale && !started && <div className="identity-form-actions"><Button type="button" disabled={!status.data.canStart || busy} onClick={() => void start()}>{status.data.canStart ? '开始微信绑定' : '公众号通道未就绪'}</Button></div>}
      {status.data.canStart && !started && status.data.binding?.stale && <div className="identity-form-actions"><Button type="button" disabled={busy} onClick={() => void start()}><ShieldCheck/>重新绑定当前公众号</Button></div>}
      {status.data.binding && <UnbindBinding bindingRevision={status.data.binding.revision} refresh={() => { setStarted(null); status.refresh() }}/>}
      <History/>
    </>}
    {error != null && <ErrorNotice error={error}/>}
    {notice && <div role="status" className="notice success-note">{notice}</div>}
  </CardContent></Card>
}

function ConfirmBinding({ pendingId, revision, refresh }: { pendingId: string; revision: number; refresh: () => void }) {
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('请填写本次绑定确认原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => wechatApi.confirm(pendingId, { expected_revision: revision, reason: reason.trim() }, key, signal), () => { setReason(''); refresh() })
  }
  return <form className="form-stack" onSubmit={submit}><h3>本人确认绑定</h3><p>微信授权已在其他页面完成；只有当前原登录会话本人经二次确认后才会写入绑定。扫码者不能代替此确认。</p><div className="field"><Label htmlFor={`wechat-confirm-${pendingId}`}>确认原因</Label><Input id={`wechat-confirm-${pendingId}`} required maxLength={1000} value={reason} disabled={action.busy} onChange={event => { action.touch(); setReason(event.target.value) }}/></div>
    {!action.confirming && <Button type="submit" disabled={action.busy}>核对并确认绑定</Button>}
    {action.confirming && <OperationConfirmation action={action} title="确认绑定当前微信" submitLabel={action.busy ? '正在确认…' : '确认绑定'} refresh={refresh} warning="只写入本账号的公众号 OpenID 绑定；不会发送测试通知，不会修改联系人、通道策略或通知规则。">待确认请求 revision {revision} · 原因：{reason}</OperationConfirmation>}
    {action.error != null && <ErrorNotice error={action.error}/>}
  </form>
}

function UnbindBinding({ bindingRevision, refresh }: { bindingRevision: number; refresh: () => void }) {
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('请填写解除绑定原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => wechatApi.unbind({ expected_revision: bindingRevision, reason: reason.trim() }, key, signal), () => { setReason(''); refresh() })
  }
  return <form className="form-stack" onSubmit={submit}><h3>解除绑定</h3><p className="muted-note">解除后立即停止向本人发送公众号模板；已存在的发送尝试与回执证据不会被删除或改写。</p><div className="field"><Label htmlFor="wechat-unbind-reason">解除原因</Label><Input id="wechat-unbind-reason" required maxLength={1000} value={reason} disabled={action.busy} onChange={event => { action.touch(); setReason(event.target.value) }}/></div>
    {!action.confirming && <Button type="submit" variant="outline" disabled={action.busy}><Unlink/>核对解除绑定</Button>}
    {action.confirming && <OperationConfirmation action={action} title="确认解除微信绑定" submitLabel={action.busy ? '正在解除…' : '确认解除'} refresh={refresh} warning="解除绑定不改变报警、故障、设备状态或余额，也不删除历史发送记录。">绑定 revision {bindingRevision} · 原因：{reason}</OperationConfirmation>}
    {action.error != null && <ErrorNotice error={action.error}/>}
  </form>
}

function History() {
  const [page, setPage] = useState(1)
  const history = useResource(`wechat-binding-history:${page}`, signal => wechatApi.history(page, signal))
  return <><h3>绑定历史</h3>{history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data?.items.length ? <><div className="table-wrap"><table className="data-table"><thead><tr><th>时间</th><th>事件</th><th>OpenID（遮罩）</th><th>操作者</th><th>原因码</th></tr></thead><tbody>{history.data.items.map(item => <tr key={item.id}><td>{dateTime(item.createdAt)}</td><td>{item.event === 'BOUND' ? '绑定' : item.event === 'REPLACED' ? '替换旧绑定' : '解除绑定'}</td><td className="mono">{item.maskedOpenid}</td><td>{item.actorName ?? '系统/未记录'}</td><td>{item.detailCode ?? '—'}</td></tr>)}</tbody></table></div><Pager page={history.data.page} size={history.data.size} total={history.data.total} onChange={setPage}/></> : <Empty title="暂无绑定历史"/>}</>
}

/** Real named selector; no free OpenID or phone-to-OpenID guessing. */
export function WechatRecipientSelect({ value, onChange, disabled, tenantId, keep }: { value: string; onChange: (bindingId: string) => void; disabled?: boolean; tenantId?: string; keep?: boolean }) {
  const generation = useSession(s => s.generation)
  const recipients = useResource<WechatRecipient[]>(`wechat-recipients:${tenantId ?? ''}:${generation}`, signal => wechatApi.recipients(signal, tenantId), !!tenantId)
  if (!tenantId) return <div className="field"><Label>微信绑定用户</Label><p className="muted-note">请先明确选择联系人所属单位，再选择已绑定用户。</p></div>
  if (recipients.loading) return <Loading/>
  if (recipients.error != null) return <ErrorNotice error={recipients.error} retry={recipients.refresh}/>
  const usable = (recipients.data ?? []).filter(item => item.usable)
  return <div className="field"><Label htmlFor={`wechat-recipient-select-${tenantId ?? 'self'}`}>微信绑定用户（按姓名选择）</Label><select id={`wechat-recipient-select-${tenantId ?? 'self'}`} className="native-select" value={value} disabled={disabled} onChange={event => onChange(event.target.value)}>
    {keep && <option value="KEEP">保留当前已保存绑定</option>}
    <option value="">{usable.length ? '不使用微信收件' : '本单位暂无可用的已绑定用户'}</option>
    {value && value !== 'KEEP' && !usable.some(item => item.bindingId === value) && <option value={value}>当前保存的绑定（不可用或已取消关注）</option>}
    {usable.map(item => <option key={item.bindingId} value={item.bindingId}>{item.userName} · {item.openidMasked}</option>)}
  </select>{!usable.length && <small className="muted-note">需由本单位用户先在“我的”完成微信绑定；联系人只引用绑定，不手填 OpenID。</small>}</div>
}
