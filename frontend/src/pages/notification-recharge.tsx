import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Coins, ListChecks, QrCode, RefreshCw } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { api, ApiError, errorMessage } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import type { NotificationCustomerLedger, NotificationCustomerOrder, Paginated } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { dateTime } from '@/lib/format'
import './notification-resources.css'
import './notification-recharge.css'

const accountKey = (item: { owner_type: string; owner_id: string }) => `${item.owner_type}:${item.owner_id}`
const kindNames: Record<string, string> = { RECHARGE: '充值', ADJUST: '调整', CONSUME: '扣减', REFUND: '退回' }
const paymentStateNames: Record<string, string> = { CREATED: '已创建（待扫码）', IN_FLIGHT: '支付进行中', ACCEPTED: '支付已受理', REJECTED: '支付被拒绝', UNKNOWN: '支付状态未知', PAID: '已支付', CLOSED: '已关闭' }
function confirmedOrder(order: NotificationCustomerOrder, id: string) {
  if (!order || order.id !== id || !['SMS', 'VOICE'].includes(order.channel) ||
    !['PENDING', 'PAID', 'CANCELLED', 'FAILED'].includes(order.status) || typeof order.payment_state !== 'string' ||
    !Number.isSafeInteger(order.quantity) || order.quantity <= 0 || !Number.isSafeInteger(order.amount_cents) || order.amount_cents < 0 ||
    order.code_url !== null && typeof order.code_url !== 'string' || order.expires_at !== null && typeof order.expires_at !== 'string') throw new Error('订单详情未确认，请重新读取原订单')
  return order
}
function payable(order: NotificationCustomerOrder) {
  return order.status === 'PENDING' && !!order.code_url && ['CREATED', 'ACCEPTED'].includes(order.payment_state) &&
    (!order.expires_at || Date.parse(order.expires_at) > Date.now())
}
const closeNeedsVerification = (order: NotificationCustomerOrder) =>
  order.status === 'PENDING' && ['IN_FLIGHT', 'UNKNOWN', 'PAID_UNCONFIRMED'].includes(order.close_state ?? '')
function pendingPaymentMessage(order: NotificationCustomerOrder) {
  const state = paymentStateNames[order.payment_state] ?? order.payment_state
  if (order.payment_state === 'UNKNOWN') return `支付结果未确认（${state}），请稍后刷新订单核对，不要重复支付。`
  if (order.payment_state === 'REJECTED') return `微信支付下单被拒绝（${state}），请联系平台管理员。`
  if (order.payment_state === 'CLOSED') return `支付已关闭（${state}），不能继续支付。`
  if (order.payment_state === 'PAID') return '支付已确认，到账状态仍需核对，请勿再次支付。'
  if (order.expires_at && !Number.isFinite(Date.parse(order.expires_at))) return '订单有效期未确认，请刷新订单核对，暂不展示付款二维码。'
  if (order.expires_at && Date.parse(order.expires_at) <= Date.now()) return '原付款二维码已到期，请核对订单状态，不能继续使用旧二维码。'
  if (!['CREATED', 'ACCEPTED'].includes(order.payment_state)) return `当前支付状态（${state}）不允许使用旧二维码，请刷新原订单核对。`
  return `微信支付暂未返回二维码（${state}），请稍后刷新订单。`
}

export function NotificationRechargePage() {
  const { user, audience, permissions, authGeneration } = useSession()
  if (!user || audience !== 'customer' || !permissions.includes('notification-recharge:read')) return <Empty title="当前账户无通知充值权限"/>
  return <RechargeContent key={authGeneration} canWrite={permissions.includes('notification-recharge:write')}/>
}
function RechargeContent({ canWrite }: { canWrite: boolean }) {
  const options = useResource('customer-recharge-options', signal => api.customerRechargeOptions(signal))
  const balances = useResource('customer-recharge-accounts', signal => api.customerRechargeAccounts(signal))
  const [page, setPage] = useState(1)
  const orders = useResource(`customer-recharge-orders:${page}`, signal => api.customerRechargeOrders(page, signal))
  const [owner, setOwner] = useState(''), [channel, setChannel] = useState<'SMS'|'VOICE'>('SMS'), [quantity, setQuantity] = useState('100')
  const [notice, setNotice] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [created, setCreated] = useState<NotificationCustomerOrder | null>(null), [reviewing, setReviewing] = useState(false)
  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null), [orderReadError, setOrderReadError] = useState<unknown>()
  const closingOrder = useRef<string | null>(null)
  const [closing, setClosing] = useState(false), [verifyId, setVerifyId] = useState<string | null>(null)
  const checkingClose = created?.status === 'PENDING' && (verifyId === created.id || closeNeedsVerification(created))
  const selectedOrder = useResource(`customer-recharge-order:${selectedOrderId}`, async signal => confirmedOrder(await api.customerRechargeOrder(selectedOrderId!, signal), selectedOrderId!), !!selectedOrderId)
  useEffect(() => {
    if (!selectedOrder.data) return
    setCreated(selectedOrder.data); setSelectedOrderId(null); setOrderReadError(undefined)
    if (selectedOrder.data.status !== 'PENDING') orders.refresh()
    if (selectedOrder.data.status === 'PAID') { options.refresh(); balances.refresh() }
  }, [selectedOrder.data, orders.refresh, options.refresh, balances.refresh])
  useEffect(() => {
    if (selectedOrderId || created) document.getElementById('customer-recharge-order')?.scrollIntoView?.({ block: 'start' })
  }, [selectedOrderId, created?.id])
  const [ledgerOwner, setLedgerOwner] = useState(''), [ledgerPage, setLedgerPage] = useState(1)
  const retry = useRef<{ intent: string; key: string } | null>(null)
  const destinations = options.data?.destinations
  const selectedOwner = owner
  const destination = destinations?.find(item => accountKey(item) === selectedOwner)
  const accountsByOwner = new Map((balances.data ?? []).map(account => [accountKey(account), account] as const))
  const ledgerAccount = ledgerOwner ? accountsByOwner.get(ledgerOwner) : undefined
  const ledger = useResource<Paginated<NotificationCustomerLedger>>(`customer-recharge-ledger:${ledgerOwner}:${ledgerPage}`, signal => ledgerAccount ? api.customerRechargeLedger(ledgerAccount.account_id, ledgerPage, signal) : Promise.resolve<Paginated<NotificationCustomerLedger>>({ items: [], total: 0, page: 1, size: 20 }), !!ledgerAccount)
  useEffect(() => {
    if (ledgerOwner) document.getElementById('customer-recharge-ledger')?.scrollIntoView?.({ block: 'start' })
  }, [ledgerOwner])
  const unit = channel === 'SMS' ? options.data?.pricing.sms_price_cents : options.data?.pricing.voice_price_cents
  const amount = unit == null ? null : unit * (Number(quantity) || 0)
  useEffect(() => {
    const id = created?.id, status = created?.status
    if (!id || status !== 'PENDING' || closing) return
    const controller = new AbortController()
    let inFlight = false
    const timer = setInterval(() => {
      if (inFlight) return
      inFlight = true
      void api.customerRechargeOrder(id, controller.signal).then(result => {
        if (controller.signal.aborted || closingOrder.current) return
        const order = confirmedOrder(result, id)
        setCreated(order); setOrderReadError(undefined)
        if (order.status !== status) {
          if (order.status === 'PAID') { setNotice('支付成功，资源已到账。'); orders.refresh(); options.refresh(); balances.refresh() }
          else setNotice(`订单状态已更新：${order.status}`)
        }
      }).catch(error => { if (!controller.signal.aborted) setOrderReadError(error) }).finally(() => { inFlight = false })
    }, 3000)
    return () => { clearInterval(timer); controller.abort() }
  }, [created?.id, created?.status, closing, orders.refresh, options.refresh, balances.refresh])
  function viewOrder(id: string) { if (closingOrder.current) return; setCreated(null); setOrderReadError(undefined); setSelectedOrderId(id) }
  function closeOrder() { if (closingOrder.current) return; setCreated(null); setSelectedOrderId(null); setOrderReadError(undefined) }
  async function cancelPayment(order: NotificationCustomerOrder) {
    const { authGeneration, user } = useSession.getState()
    if (!canWrite || !user?.tenant_id || closingOrder.current || busy || order.status !== 'PENDING') return
    const verify = verifyId === order.id || closeNeedsVerification(order)
    if (!verify && !window.confirm(`确认取消订单 ${order.id}（${(order.amount_cents / 100).toFixed(2)} 元）的支付？已支付的订单不能取消。`)) return
    closingOrder.current = order.id
    setClosing(true); setNotice(''); setError('')
    try {
      const result = confirmedOrder(await api.closeCustomerRechargeOrder(order.id, user.tenant_id, verify), order.id)
      if (useSession.getState().authGeneration !== authGeneration) return
      setCreated(result); setOrderReadError(undefined); orders.refresh()
      if (result.status === 'PAID') { setNotice('微信已确认支付，资源已到账。'); options.refresh(); balances.refresh() }
      if (result.status !== 'PENDING') setVerifyId(null)
      else if (result.close_state !== 'REJECTED') setVerifyId(order.id)
      else setError('微信未关闭订单，请刷新订单核对。')
    } catch (e) {
      if (useSession.getState().authGeneration !== authGeneration) return
      if (!(e instanceof ApiError) || e.status < 400 || e.status >= 500 || e.code === 'ORDER_ALREADY_PAID') setVerifyId(order.id)
      setError(errorMessage(e))
    } finally {
      closingOrder.current = null
      setClosing(false)
    }
  }
  function refresh() { setReviewing(false); options.refresh(); orders.refresh(); balances.refresh(); if (created) viewOrder(created.id); else if (selectedOrderId) selectedOrder.refresh() }
  function showLedger(key: string) { setLedgerOwner(key); setLedgerPage(1) }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || closingOrder.current) return
    setError('')
    setNotice('')
    if (!destination) { setError(owner ? '所选充值账户已不可用，请重新选择账户' : '请明确选择充值账户'); return }
    const count = Number(quantity)
    if (!Number.isInteger(count) || count <= 0 || count > 1_000_000) { setError('数量必须是 1 至 1,000,000 的整数'); return }
    if (amount == null) { setError('资源单价未配置，请联系平台管理员'); return }
    if (!reviewing) { setReviewing(true); return }
    setReviewing(false)
    closeOrder()
    const intent = JSON.stringify([destination.owner_type, destination.owner_id, channel, count])
    if (!retry.current || retry.current.intent !== intent) retry.current = { intent, key: crypto.randomUUID() }
    const key = retry.current.key
    setBusy(true)
    try {
      const order = await api.createCustomerRechargeOrder({ owner_type: destination.owner_type, owner_id: destination.owner_id, channel, quantity: count }, key)
      retry.current = null
      setCreated(order); orders.refresh(); setNotice(order.code_url ? `订单已创建：${(order.amount_cents / 100).toFixed(2)} 元，请使用微信扫码支付。` : `订单已创建：${(order.amount_cents / 100).toFixed(2)} 元。${pendingPaymentMessage(order)}`)
    } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <><PageHeading eyebrow="NOTIFICATION · 充值" title="短信与语音充值" description={canWrite ? '查看授权账户的短信/语音余额与明细，并生成充值订单。' : '当前账户只读：可查看授权账户余额、明细和本人充值订单。'}><Button variant="outline" onClick={refresh}><RefreshCw/>刷新</Button><PageHelp title="充值与余额说明"><p>短信和语音分别计量、计价及入账，不互相抵扣。剩余数量、总量和已用数量以当前账户实际记录为准；没有记录时不显示虚构余额。</p><p>充值金额按当前平台单价计算。支付配置接入前不能完成在线支付；订单创建、支付受理与数量到账是不同状态，请按原订单刷新核对，不重复支付。</p><p>只读账户可以查看授权余额、明细和自己的历史订单，不能创建新订单。</p></PageHelp></PageHeading>
    <Card><CardContent>
      <div className="demo-side-head"><h2 className="section-heading" style={{ margin: 0 }}>资源余额</h2><span className="muted">短信与语音分别统计，只显示当前授权的充值账户</span></div>
      {options.loading || balances.loading ? <Loading/> : options.error != null ? <ErrorNotice error={options.error} retry={options.refresh}/> : balances.error != null ? <div role="status" className="notice danger-note">账户余额读取失败，可稍后刷新。<Button type="button" variant="outline" onClick={() => balances.refresh()}>重试</Button></div> : !destinations?.length ? <Empty title="当前没有可查看的资源账户">请联系平台管理员配置充值授权。</Empty> : <div className="notification-account-grid">{destinations.map(item => {
        const account = accountsByOwner.get(accountKey(item))
        return <article className="notification-account-card" key={accountKey(item)} aria-label={`${item.owner_name} · ${item.owner_type === 'ORGANIZATION' ? '单位账户' : '位置账户'}`}>
          <header><h3>{item.owner_name}</h3><p className="muted">{item.owner_type === 'ORGANIZATION' ? '单位账户' : '位置账户'}{account && !account.enabled ? ' · 已停用' : ''}</p></header>
          {account ? <dl className="notification-account-balances">
            <div><dt>短信剩余（条）</dt><dd>{account.sms_remaining}</dd><small>总量 {account.sms_total} · 已用 {account.sms_used}</small></div>
            <div><dt>语音剩余（次）</dt><dd>{account.voice_remaining}</dd><small>总量 {account.voice_total} · 已用 {account.voice_used}</small></div>
          </dl> : <p className="muted">暂无账户记录</p>}
          <footer className="notification-account-footer"><details><summary>账户信息</summary><p className="mono">{item.owner_id}</p></details>{account && <Button type="button" variant="outline" size="sm" onClick={() => showLedger(accountKey(item))}><ListChecks/>查看明细</Button>}</footer>
        </article>
      })}</div>}
      {ledgerOwner && !ledgerAccount && <div role="status" className="notice danger-note">所选账户已不在当前授权范围，已停止显示明细，不会自动切换到其他账户。<Button type="button" variant="outline" onClick={() => setLedgerOwner('')}>关闭</Button></div>}
      {ledgerAccount && <section id="customer-recharge-ledger" aria-label="账户资源明细" className="form-stack" style={{ scrollMarginTop: 80 }}>
        <div className="demo-side-head"><h3 style={{ margin: 0 }}>{ledgerAccount.owner_name} · {ledgerAccount.owner_type === 'ORGANIZATION' ? '单位账户' : '位置账户'} · 明细</h3><Button type="button" variant="outline" onClick={() => setLedgerOwner('')}>关闭明细</Button></div>
        {ledger.loading ? <Loading/> : ledger.error != null ? <div role="status" className="notice danger-note">账户明细读取失败，可稍后重试。<Button type="button" variant="outline" onClick={() => ledger.refresh()}>重试</Button></div> : ledger.data?.items.length ? <><div className="notification-account-grid">{ledger.data.items.map(entry => <article className="notification-account-card" key={entry.id}><header><h3>{entry.channel === 'SMS' ? '短信' : '语音'} · {kindNames[entry.kind] ?? entry.kind}</h3><p className="muted">{dateTime(entry.created_at)}</p></header><dl className="notification-account-balances"><div><dt>本次变动（{entry.channel === 'SMS' ? '条' : '次'}）</dt><dd>{entry.quantity}</dd></div><div><dt>变动后余量</dt><dd>{entry.balance_after}</dd></div></dl></article>)}</div><Pager page={ledger.data.page} size={ledger.data.size} total={ledger.data.total} onChange={setLedgerPage}/></> : <Empty title="暂无账户变动记录"/>}
      </section>}
    </CardContent></Card>
    {canWrite ? <><div className="notice"><Coins size={15}/>充值金额按平台当前单价计算。微信支付参数接入前，订单只能创建，不能完成在线支付。</div>{options.loading ? <Loading/> : options.error != null ? <ErrorNotice error={options.error} retry={options.refresh}/> : options.data && <>
      <Card className="customer-recharge-order-form"><CardContent><h2 className="section-heading">新建充值订单</h2>{!destinations?.length ? <Empty title="当前没有可充值账户">请联系平台管理员配置充值权限。</Empty> : <form className="customer-recharge-form" onSubmit={submit}>
        <div className="customer-recharge-fields">
          <div className="field"><Label htmlFor="recharge-owner">充值账户</Label><select id="recharge-owner" className="native-select" value={selectedOwner} required onChange={e => { setOwner(e.target.value); setReviewing(false) }}><option value="">请明确选择充值账户</option>{owner && !destination && <option value={owner}>所选账户已不可用，请重新选择</option>}{destinations.map(item => <option key={accountKey(item)} value={accountKey(item)}>{item.owner_name}（{item.owner_type === 'ORGANIZATION' ? '单位账户' : '位置账户'}）</option>)}</select><p className="muted-note">充值仅针对明确选中的单位或位置账户。</p></div>
          <div className="field"><Label htmlFor="recharge-channel">资源类型</Label><select id="recharge-channel" className="native-select" value={channel} onChange={e => { setChannel(e.target.value as 'SMS'|'VOICE'); setReviewing(false) }}><option value="SMS">短信</option><option value="VOICE">语音（次）</option></select></div>
          <div className="field"><Label htmlFor="recharge-quantity">{channel === 'SMS' ? '短信条数' : '语音次数'}</Label><Input id="recharge-quantity" type="number" min={1} max={1000000} value={quantity} onChange={e => { setQuantity(e.target.value); setReviewing(false) }}/></div>
        </div>
        <div className="customer-recharge-checkout">
          <dl className="detail-list"><div><dt>单价</dt><dd>{unit == null ? '未配置' : `${unit} 分/${channel === 'SMS' ? '条' : '次'}`}</dd></div><div><dt>应付金额</dt><dd>{amount == null ? '—' : `${(amount / 100).toFixed(2)} 元`}</dd></div></dl>
          {reviewing ? <section className="customer-recharge-review" role="status" aria-label="订单确认">
            <h3>请确认充值订单</h3>
            <dl><div><dt>充值账户</dt><dd>{destination!.owner_name} · {destination!.owner_type === 'ORGANIZATION' ? '单位账户' : '位置账户'}</dd></div><div><dt>充值内容</dt><dd>{channel === 'SMS' ? `短信 ${quantity} 条` : `语音 ${quantity} 次`}</dd></div><div><dt>应付金额</dt><dd>{(amount! / 100).toFixed(2)} 元</dd></div></dl>
            <p>确认后仅创建充值订单，不会自动完成支付或保证资源到账。</p>
            <div className="customer-recharge-actions"><Button type="button" variant="outline" disabled={busy} onClick={() => setReviewing(false)}>返回修改</Button><Button type="submit" disabled={busy}>{busy ? '创建中…' : '确认创建订单'}</Button></div>
          </section> : <Button type="submit" disabled={busy || amount == null}>{busy ? '创建中…' : '审核充值订单'}</Button>}
        </div>
      </form>}</CardContent></Card>
    </>}</> : null}
    {(selectedOrderId || created) && <section id="customer-recharge-order" style={{ scrollMarginTop: 80 }}><Card><CardContent>
      <div className="demo-side-head"><h2 className="section-heading"><QrCode size={18}/>{created?.status === 'PENDING' ? '待支付订单' : '充值订单'}</h2><div className="notification-actions">
        {created && <Button type="button" variant="outline" disabled={closing} onClick={() => viewOrder(created.id)}>刷新订单</Button>}
        {created?.status === 'PENDING' && canWrite && (checkingClose || ['ACCEPTED', 'UNKNOWN'].includes(created.payment_state)) &&
          <Button type="button" variant="outline" disabled={busy || closing} onClick={() => void cancelPayment(created)}>
            {checkingClose ? '核对取消结果' : '取消支付'}
          </Button>}
        <Button type="button" variant="outline" disabled={closing} title="只关闭当前详情，不取消订单或支付" onClick={closeOrder}>关闭详情</Button>
      </div></div>
      {selectedOrderId ? selectedOrder.loading ? <Loading/> : selectedOrder.error != null ? <ErrorNotice error={selectedOrder.error} retry={selectedOrder.refresh}/> : null : created && <>
        <p>订单号 {created.id} · {created.channel === 'SMS' ? `短信 ${created.quantity} 条` : `语音 ${created.quantity} 次`} · 金额 {(created.amount_cents / 100).toFixed(2)} 元 · 状态 {created.status}</p>
        <p className="muted-note">支付：{paymentStateNames[created.payment_state] ?? created.payment_state}{created.expires_at ? ` · 有效至 ${created.expires_at}` : ''}</p>
        {closing ? <p role="status">正在核对原订单，请勿支付。</p> :
          checkingClose ? <p role="status">取消结果未确认，请核对原订单，勿重复付款。</p> :
          orderReadError != null ? <ErrorNotice error={orderReadError} retry={() => viewOrder(created.id)}/> :
          payable(created) ? <div className="payment-qr"><QRCodeSVG value={created.code_url!} size={220} role="img" aria-label="微信支付二维码"/><p>请使用微信扫码支付，支付成功后会自动刷新。</p></div> :
          <div className="notice">{created.status === 'PENDING' ? pendingPaymentMessage(created) : created.status === 'CANCELLED' ? '支付已取消，此订单不能继续支付。' : '此订单已结束，不能继续支付。'}</div>}
      </>}
    </CardContent></Card></section>}
    <Card><CardContent><div className="demo-side-head"><h2 className="section-heading" style={{ margin: 0 }}>我的充值订单</h2><span className="muted">仅显示本人订单；查看原订单不会重复下单</span></div>{orders.loading ? <Loading/> : orders.error != null ? <ErrorNotice error={orders.error} retry={orders.refresh}/> : orders.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>订单 / 操作</TableHead><TableHead>状态</TableHead><TableHead>支付</TableHead></TableRow></TableHeader><TableBody>{orders.data.items.map(order => <TableRow key={order.id}><TableCell><strong>{order.channel === 'SMS' ? '短信' : '语音'} {order.quantity} {order.channel === 'SMS' ? '条' : '次'} · {(order.amount_cents / 100).toFixed(2)} 元</strong><div className="table-sub">{order.created_at}</div><Button type="button" variant="outline" size="sm" aria-label={`查看订单 ${order.id}`} onClick={() => viewOrder(order.id)}>{order.status === 'PENDING' ? '查看 / 继续支付' : '查看订单'}</Button></TableCell><TableCell>{order.status}</TableCell><TableCell>{paymentStateNames[order.payment_state] ?? order.payment_state}{order.status === 'PENDING' && order.expires_at && <div className="table-sub">有效至 {order.expires_at}</div>}</TableCell></TableRow>)}</TableBody></Table><Pager page={orders.data.page} size={orders.data.size} total={orders.data.total} onChange={setPage}/></> : <Empty title="暂无充值订单"/>}</CardContent></Card>
    {notice && <div role="status" className="notice success-note">{notice}</div>}{error && <div role="alert" className="notice danger-note">{error}</div>}
  </>
}
