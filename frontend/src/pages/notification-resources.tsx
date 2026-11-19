import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { BellRing, Coins, RefreshCw } from 'lucide-react'
import { api, errorMessage } from '@/lib/api'
import { paymentApi, type PaymentActionResult } from '@/lib/payments'
import { dateTime } from '@/lib/format'
import { placePath } from '@/lib/place-tree'
import { createMutationIntent } from '@/lib/mutation-intent'
import { publishObjectChange } from '@/lib/object-events'
import './notification-resources.css'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { allowedPage, type NotificationResourceAccount, type NotificationResourceOrder } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { TargetUnitSelect } from '@/components/asset-picker'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'

type ManagedOrder = NotificationResourceOrder & { expires_at: string | null; close_state: string | null; query_state: string | null }
const paymentStateLabels: Record<string, string> = {
  CREATED: '未发起支付', IN_FLIGHT: '下单请求进行中', ACCEPTED: '微信已受理（待支付）', REJECTED: '微信未受理',
  UNKNOWN: '结果未确认', PAID: '已支付', CLOSED: '已关闭',
}

const useManualRecharge = createMutationIntent<NotificationResourceAccount>(raw => {
  const value = JSON.parse(raw) as NotificationResourceAccount | null
  if (!value || typeof value.id !== 'string' || typeof value.owner_id !== 'string' || typeof value.owner_name !== 'string' ||
    !['ORGANIZATION', 'PLACE'].includes(value.owner_type) || typeof value.enabled !== 'boolean' ||
    ![value.sms_total, value.sms_used, value.sms_remaining, value.voice_total, value.voice_used, value.voice_remaining, value.revision].every(Number.isSafeInteger)) {
    throw new Error('后台入账回执未确认，请沿原请求重试')
  }
  return value
})

export function NotificationResourcesPage() {
  const { user, audience, permissions, authGeneration } = useSession()
  if (!user || !allowedPage('notification-resources', audience, permissions)) return <Empty title="当前账户无通知资源权限"/>
  return <ResourcesContent key={authGeneration}/>
}
function ResourcesContent() {
  const recharge = useManualRecharge(), busy = recharge.phase === 'PENDING', rechargeLocked = recharge.intent !== null
  const [retryOpen, setRetryOpen] = useState(false)
  const permissions = useSession(s => s.permissions), audience = useSession(s => s.audience)
  const [targetUnit, setTargetUnit] = useState('')
  const canWrite = permissions.includes('notification-resources:write')
  const paymentWrite = permissions.includes('payments:write')
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeKey = useSession(s => `${s.scopeTenantIds.join(',')}|${s.scopePlaceIds.join(',')}|${s.scopeInitialized}`)
  const accounts = useResource(`notification-resources:${scopeKey}`, signal => api.notificationResources(signal, scopeTenantIds, scopePlaceIds))
  const places = useResource(`notification-resources:places:${targetUnit || 'none'}`, signal => api.places(signal, targetUnit ? [targetUnit] : [], []), !!targetUnit)
  const targetUnits = useResource('notification-resource-target-units', signal => api.tenants(signal), audience === 'management' && permissions.includes('tenants:read'))
  const targetUnitName = targetUnits.data?.items.find(unit => unit.id === targetUnit)?.name ?? places.data?.find(place => place.tenant_id === targetUnit)?.tenant_name ?? targetUnit
  const placeChoices = useMemo(() => {
    const current = (places.data ?? []).filter(place => place.tenant_id === targetUnit)
    return current.map(place => ({ id: place.id, label: placePath(place, current) })).sort((a, b) => a.label.localeCompare(b.label))
  }, [places.data, targetUnit])
  const [ownerType, setOwnerType] = useState<'ORGANIZATION'|'PLACE'>('ORGANIZATION')
  const [ownerId, setOwnerId] = useState('')
  const [channel, setChannel] = useState<'SMS'|'VOICE'>('SMS')
  const [quantity, setQuantity] = useState('100')
  const [reference, setReference] = useState('')
  const [notice, setNotice] = useState(''), [error, setError] = useState('')
  const [ledgerAccount, setLedgerAccount] = useState<NotificationResourceAccount | null>(null), [ledgerPage, setLedgerPage] = useState(1)
  const ledger = useResource(`notification-resource-ledger:${ledgerAccount?.tenant_id ?? 'none'}:${ledgerAccount?.id ?? 'none'}:${ledgerPage}`, signal => api.notificationResourceLedger(ledgerAccount!.id, ledgerPage, signal, ledgerAccount!.tenant_id), !!ledgerAccount)
  useEffect(() => {
    const completed = recharge.completion
    if (!completed) return
    const saved = completed.result
    setNotice(`后台入账已确认：${saved.owner_name} · 短信剩余 ${saved.sms_remaining} 条 · 语音剩余 ${saved.voice_remaining} 次`)
    accounts.refresh(); ledger.refresh(); publishObjectChange('notifications'); recharge.dismissCompletion()
  }, [recharge.completion, accounts.refresh, ledger.refresh, recharge.dismissCompletion])
  const pricing = useResource('notification-resource-pricing', signal => api.notificationResourcePricing(signal))
  const [ordersPage, setOrdersPage] = useState(1)
  const [attemptsPage, setAttemptsPage] = useState(1)
  const attempts = useResource(`notification-provider-attempts:${scopeKey}:${attemptsPage}`, signal => api.notificationProviderAttempts(attemptsPage, signal, scopeTenantIds, scopePlaceIds))
  const orders = useResource(`notification-resource-orders:${scopeKey}:${ordersPage}`, signal => api.notificationResourceOrders(ordersPage, signal, scopeTenantIds, scopePlaceIds))
  const [smsPrice, setSmsPrice] = useState(''), [voicePrice, setVoicePrice] = useState(''), [priceBusy, setPriceBusy] = useState(false)
  const [orderBusy, setOrderBusy] = useState(false), [createdOrder, setCreatedOrder] = useState<NotificationResourceOrder | null>(null), [confirming, setConfirming] = useState<NotificationResourceOrder | null>(null), [confirmReference, setConfirmReference] = useState('')
  const [actionOrder, setActionOrder] = useState<ManagedOrder | null>(null)
  const [actionKind, setActionKind] = useState<'query' | 'close'>('query')
  const [actionBusy, setActionBusy] = useState(false)
  const [lastAction, setLastAction] = useState<PaymentActionResult | null>(null)
  const owner = ownerType === 'ORGANIZATION' ? targetUnit : ownerId
  const selected = useMemo(() => accounts.data?.find(a => a.tenant_id === targetUnit && a.owner_type === ownerType && a.owner_id === owner) ?? null, [accounts.data, ownerType, owner, targetUnit])
  const chosenPlace = placeChoices.find(place => place.id === ownerId)
  const ownerReady = !!targetUnit && !!owner && (ownerType !== 'PLACE' || (!!chosenPlace && !places.loading && places.error == null))
  const ownerLabel = ownerType === 'PLACE' ? chosenPlace?.label ?? owner : selected?.owner_name ?? targetUnit
  const projected = selected ? (channel === 'SMS' ? selected.sms_remaining : selected.voice_remaining) + (Number(quantity) || 0) : Number(quantity) || 0
  useEffect(() => { if (pricing.data) { setSmsPrice(pricing.data.sms_price_cents === null ? '' : String(pricing.data.sms_price_cents)); setVoicePrice(pricing.data.voice_price_cents === null ? '' : String(pricing.data.voice_price_cents)) } }, [pricing.data])
  function preview() {
    setError(''); setNotice('')
    const amount = Number(quantity)
    if (!Number.isInteger(amount) || amount <= 0 || amount > 1_000_000) { setError('数量必须是 1 至 1,000,000 的整数'); return }
    if (!ownerReady) { setError('请明确选择本次操作单位及充值目标'); return }
    if (accounts.loading || accounts.error != null) { setError('账户余额尚未确认，请刷新后再核算'); return }
    setNotice(`测试预览：${channel === 'SMS' ? '短信' : '语音'}剩余 ${selected ? (channel === 'SMS' ? selected.sms_remaining : selected.voice_remaining) : 0} → ${projected}，未写入数据库。`)
  }
  async function submit(event: FormEvent) {
    event.preventDefault(); setError(''); setNotice('')
    if (!ownerReady) { setError('请明确选择本次操作单位及充值目标'); return }
    const amount = Number(quantity)
    if (!Number.isInteger(amount) || amount <= 0 || amount > 1_000_000) { setError('数量必须是 1 至 1,000,000 的整数'); return }
    if (!canWrite || !targetUnit) { setError('请选择目标单位并确认当前账户具有通知资源写入权限'); return }
    if (!window.confirm(`确认向 ${targetUnitName} · ${ownerLabel} 直接写入 ${channel === 'SMS' ? '短信' : '语音'} ${Number(quantity)} ${channel === 'SMS' ? '条' : '次'}？这会立即增加可用资源并写入流水，不经过微信支付。继续？`)) return
    await recharge.perform({
      path: '/notification-resources/recharge', method: 'POST', tenant: targetUnit,
      body: { owner_type: ownerType, owner_id: owner, channel, quantity: amount, reference: reference || null },
      display: `${targetUnitName} · ${ownerLabel} · ${channel === 'SMS' ? '短信' : '语音'} ${amount} ${channel === 'SMS' ? '条' : '次'}`,
    })
  }
  async function createOrder() {
    setError(''); setNotice('')
    if (!ownerReady) { setError('请明确选择本次操作单位及充值目标'); return }
    if (!targetUnit) { setError('请明确选择本次操作单位'); return }
    const amount = Number(quantity)
    if (!Number.isInteger(amount) || amount <= 0 || amount > 1_000_000) { setError('数量必须是 1 至 1,000,000 的整数'); return }
    setOrderBusy(true); setNotice('正在向微信申请二维码…')
    try { const order = await api.createNotificationResourceOrder({ owner_type: ownerType, owner_id: owner, channel, quantity: amount }, crypto.randomUUID(), undefined, targetUnit); setCreatedOrder(order); setNotice(order.code_url ? `已生成待支付订单 ${order.id}，金额 ${(order.amount_cents / 100).toFixed(2)} 元，请扫码支付。` : `已生成订单 ${order.id}，微信支付暂不可用。`); orders.refresh() }
    catch (e) { setError(errorMessage(e)) } finally { setOrderBusy(false) }
  }
  useEffect(() => {
    if (!createdOrder || createdOrder.status !== 'PENDING' || !createdOrder.code_url) return
    const timer = setInterval(() => {
      void api.notificationResourceOrder(createdOrder.id, undefined, createdOrder.tenant_id).then(order => {
        setCreatedOrder(order)
        if (order.status === 'PAID') { setNotice('微信支付成功，资源已到账。'); accounts.refresh(); orders.refresh(); publishObjectChange('notifications') }
      }).catch(() => {})
    }, 3000)
    return () => clearInterval(timer)
  }, [createdOrder, accounts, orders])
  useEffect(() => { if (createdOrder) document.getElementById('management-wechat-order')?.scrollIntoView({ behavior: 'smooth', block: 'center' }) }, [createdOrder])
  async function savePricing(event: FormEvent) {
    event.preventDefault(); setError(''); setNotice('')
    if (!targetUnit) { setError('请明确选择保存单价的目标单位'); return }
    setPriceBusy(true)
    try { await api.saveNotificationResourcePricing({ sms_price_cents: smsPrice === '' ? null : Number(smsPrice), voice_price_cents: voicePrice === '' ? null : Number(voicePrice), expected_revision: pricing.data?.revision ?? 0 }, undefined, targetUnit); setNotice('资源单价已保存'); pricing.refresh(); publishObjectChange('notifications') }
    catch (e) { setError(errorMessage(e)) } finally { setPriceBusy(false) }
  }
  async function runOrderAction(event: FormEvent) {
    event.preventDefault(); if (!actionOrder) return
    setError(''); setNotice(''); setLastAction(null)
    if (!paymentWrite) { setError('当前账户没有支付订单操作权限'); return }
    setActionBusy(true)
    try {
      const result = actionKind === 'query'
        ? await paymentApi.queryOrder(actionOrder.id, actionOrder.tenant_id)
        : await paymentApi.closeOrder(actionOrder.id, actionOrder.tenant_id)
      setLastAction(result); setNotice(result.message)
      orders.refresh(); accounts.refresh()
    } catch (e) { setError(errorMessage(e)) } finally { setActionBusy(false) }
  }
  async function confirmOrder(event: FormEvent) {
    event.preventDefault(); if (!confirming) return; setError(''); setNotice('')
    try { const paid = await api.confirmNotificationResourceOrder(confirming.id, { reference: confirmReference || null }, undefined, confirming.tenant_id); setNotice(`订单已确认到账：${paid.id}`); setConfirming(null); setConfirmReference(''); accounts.refresh(); orders.refresh(); publishObjectChange('notifications') }
    catch (e) { setError(errorMessage(e)) }
  }
  return <><PageHeading eyebrow="NOTIFICATION · 资源账户" title="短信与语音资源" description="查看各账户短信条数、语音次数及资源流水。"><Button variant="outline" onClick={accounts.refresh}><RefreshCw/>刷新</Button><PageHelp title="通知资源与充值"><p>短信按条、语音按次分别管理和扣减，账户归属为具体单位或位置。授权范围只缩小读取范围；写入时仍须明确单位和账户。</p><p>后台直接添加资源不经过微信支付，直接写入账户及资源流水；生成微信二维码属于另一个支付流程。两者不是同一笔充值的两个按钮。</p><p>厂商受理记录不代表用户已收到短信或接听电话。无法确认受理的尝试不会自动重发或退回资源。</p></PageHelp></PageHeading>
    {recharge.intent && <Card><CardContent><h2 className="section-heading">{busy ? '正在提交后台入账' : recharge.phase === 'UNCERTAIN' ? '后台入账结果未确认' : '后台入账未完成'}</h2>
      <p>原操作：{recharge.intent.display}</p>
      <p className="muted-note">未确认前不另起一笔。重试只确认原单位、原账户和原数量，不会因本页目标选择变化而改投其他账户。请勿通过刷新浏览器或重复新建规避核对。</p>
      {recharge.error != null && <div role="alert" className="notice danger-note">{errorMessage(recharge.error)}</div>}
      {!busy && recharge.phase !== 'CONFLICT' && !retryOpen && <Button type="button" onClick={() => setRetryOpen(true)}>重试原入账</Button>}
      {retryOpen && !busy && <form className="form-stack" onSubmit={async event => { event.preventDefault(); setRetryOpen(false); await recharge.retry() }}>
        
        <Button type="submit">确认重试原入账</Button>
      </form>}
      {recharge.phase === 'CONFIRMING' && <Button type="button" variant="outline" onClick={() => { recharge.edit(); setRetryOpen(false) }}>返回修改</Button>}
      {recharge.phase === 'CONFLICT' && <Button type="button" variant="outline" onClick={() => { recharge.reload(); accounts.refresh(); setRetryOpen(false) }}>刷新账户后重新填写</Button>}
    </CardContent></Card>}
    {accounts.loading ? <Loading/> : accounts.error != null ? <ErrorNotice error={accounts.error} retry={accounts.refresh}/> : <>
      <Card><CardContent><h2 className="section-heading"><BellRing size={18}/>账户余额</h2>{accounts.data?.length ? <section className="notification-account-grid" aria-label="通知资源账户余额">{accounts.data.map(account => <article className="notification-account-card" key={account.id}>
        <header><h3>{account.owner_name}</h3><p className="muted-note">{account.owner_type === 'ORGANIZATION' ? '单位账户' : '位置账户'} · {account.tenant_name ?? '单位名称未提供'}{!account.enabled && ' · 已停用'}</p></header>
        <dl className="notification-account-balances">
          <div><dt>短信剩余（条）</dt><dd>{account.sms_remaining ?? '未知'}</dd><small>总量 {account.sms_total ?? '未知'} · 已用 {account.sms_used ?? '未知'}</small></div>
          <div><dt>语音剩余（次）</dt><dd>{account.voice_remaining ?? '未知'}</dd><small>总量 {account.voice_total ?? '未知'} · 已用 {account.voice_used ?? '未知'}</small></div>
        </dl>
        <div className="notification-account-footer"><details><summary>账户标识与更新时间</summary><p className="table-sub mono">单位：{account.tenant_id ?? '未提供'}</p><p className="table-sub mono">归属：{account.owner_id}</p><p className="table-sub">更新：{account.updated_at ? dateTime(account.updated_at) : '未记录'}</p></details><Button type="button" variant="outline" size="sm" disabled={!account.tenant_id} onClick={() => { setLedgerAccount(account); setLedgerPage(1) }}>流水</Button></div>
      </article>)}</section> : <Empty title="当前读取范围暂无资源账户">在“单价与充值操作”中选择明确目标单位后，可查看并使用该单位的充值功能。</Empty>}</CardContent></Card>
      {ledgerAccount && <Card><CardContent><div style={{ display: 'flex', alignItems: 'center', gap: 10 }}><h2 className="section-heading" style={{ margin: 0 }}>资源流水 · {ledgerAccount.owner_name}</h2><Button variant="ghost" size="sm" style={{ marginLeft: 'auto' }} onClick={() => setLedgerAccount(null)}>关闭</Button></div>{ledger.loading ? <Loading/> : ledger.error != null ? <ErrorNotice error={ledger.error} retry={ledger.refresh}/> : ledger.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>时间</TableHead><TableHead>渠道</TableHead><TableHead>类型</TableHead><TableHead>数量</TableHead><TableHead>余额</TableHead><TableHead>来源</TableHead><TableHead>凭证/备注</TableHead><TableHead>操作人</TableHead></TableRow></TableHeader><TableBody>{ledger.data.items.map(item => <TableRow key={item.id}><TableCell className="muted">{item.created_at}</TableCell><TableCell>{item.channel === 'SMS' ? '短信' : '语音'}</TableCell><TableCell>{item.kind}</TableCell><TableCell>{item.quantity}</TableCell><TableCell>{item.balance_after}</TableCell><TableCell>{item.source}</TableCell><TableCell>{item.reference ?? '—'}</TableCell><TableCell className="mono">{item.operator ?? '—'}</TableCell></TableRow>)}</TableBody></Table><Pager page={ledger.data.page} size={ledger.data.size} total={ledger.data.total} onChange={setLedgerPage}/></> : <Empty title="暂无资源流水"/>}</CardContent></Card>}
      {canWrite && <details className="secondary-controls"><summary>单价与充值操作</summary><div className="secondary-controls-body">
      <TargetUnitSelect audience={audience} permissions={permissions} value={targetUnit} onChange={unit => { setTargetUnit(unit); setOwnerId('') }} disabled={busy || orderBusy || priceBusy}/>
      {canWrite && <Card><CardContent><h2 className="section-heading"><Coins size={18}/>资源单价</h2><form className="provider-grid" onSubmit={savePricing}><div className="form-stack"><div className="field"><Label>短信单价（分/条）</Label><Input type="number" min={0} value={smsPrice} onChange={e => setSmsPrice(e.target.value)} placeholder="未配置"/></div><div className="field"><Label>语音单价（分/次）</Label><Input type="number" min={0} value={voicePrice} onChange={e => setVoicePrice(e.target.value)} placeholder="未配置"/></div></div><div className="form-stack"><div className="table-sub">revision {pricing.data?.revision ?? 0} · 单价未配置时禁止生成订单</div><Button type="submit" disabled={priceBusy}>{priceBusy ? '保存中…' : '保存单价'}</Button></div></form></CardContent></Card>}
      <p className="muted-note">目标单位只影响本次操作，不改变左侧读取范围；已有订单查单、确认和资源流水仍使用记录原单位。</p>
      {canWrite && <Card><CardContent><h2 className="section-heading"><Coins size={18}/>后台直接添加 / 扫码充值</h2><form id="management-recharge-form" onSubmit={submit}><fieldset className="provider-grid" disabled={rechargeLocked} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}><div className="form-stack"><div className="field"><Label htmlFor="resource-owner-type">归属类型</Label><select id="resource-owner-type" className="native-select" value={ownerType} onChange={e => { const next = e.target.value as 'ORGANIZATION'|'PLACE'; setOwnerType(next); setOwnerId('') }}><option value="ORGANIZATION">组织</option><option value="PLACE">位置</option></select></div>{ownerType === 'PLACE' && <div className="field"><Label htmlFor="resource-place">位置</Label><><select id="resource-place" className="native-select" value={ownerId} disabled={!targetUnit || places.loading || places.error != null} onChange={e => setOwnerId(e.target.value)}><option value="">请明确选择当前操作单位的位置</option>{placeChoices.map(place => <option key={place.id} value={place.id}>{place.label} · {place.id}</option>)}</select><small className="muted-note">{chosenPlace ? `充值位置：${targetUnitName} / ${chosenPlace.label}` : places.loading ? '正在读取当前位置目录…' : '请明确选择目标，不自动默认。'}</small>{places.error != null && <ErrorNotice error={places.error} retry={places.refresh}/>}</></div>}<div className="field"><Label htmlFor="resource-channel">资源类型</Label><select id="resource-channel" className="native-select" value={channel} onChange={e => setChannel(e.target.value as 'SMS'|'VOICE')}><option value="SMS">短信</option><option value="VOICE">语音</option></select></div></div><div className="form-stack"><div className="field"><Label htmlFor="resource-quantity">{channel === 'SMS' ? '短信条数' : '语音次数'}</Label><Input id="resource-quantity" type="number" min={1} max={1000000} value={quantity} onChange={e => setQuantity(e.target.value)}/></div><div className="field"><Label htmlFor="resource-reference">凭证 / 备注</Label><Input id="resource-reference" value={reference} onChange={e => setReference(e.target.value)} maxLength={200}/></div>{error && <div role="alert" className="notice danger-note">{error}</div>}{notice && <div role="status" className="notice success-note">{notice}</div>}<div className="notification-actions"><Button type="button" variant="outline" disabled={busy || orderBusy || !ownerReady} onClick={preview}>核算预览</Button><Button type="button" variant="outline" disabled={busy || orderBusy || !ownerReady} onClick={createOrder}>{orderBusy ? '生成中…' : '生成微信二维码'}</Button><Button type="submit" disabled={busy || orderBusy}>{busy ? '提交中…' : '后台直接入账（不扫码）'}</Button></div></div></fieldset></form></CardContent></Card>}
      </div></details>}
      <details className="secondary-controls"><summary>扫码充值订单</summary><div className="secondary-controls-body">
      <Card><CardContent><div style={{ display: 'flex', alignItems: 'center', gap: 10 }}><h2 className="section-heading" style={{ margin: 0 }}>扫码充值订单</h2><Button variant="ghost" size="sm" style={{ marginLeft: 'auto' }} onClick={orders.refresh}>刷新</Button></div><div className="notice">微信支付回调成功后自动入账；也可在后台人工确认到账。</div>{orders.loading ? <Loading/> : orders.error != null ? <ErrorNotice error={orders.error} retry={orders.refresh}/> : orders.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>创建时间</TableHead><TableHead>渠道</TableHead><TableHead>数量</TableHead><TableHead>金额</TableHead><TableHead>订单状态</TableHead><TableHead>支付状态</TableHead><TableHead>到期 / 到账</TableHead><TableHead>订单 token</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{orders.data.items.map(order => { const managed = order as ManagedOrder; return <TableRow key={order.id}><TableCell className="muted">{order.created_at}</TableCell><TableCell>{order.channel === 'SMS' ? '短信' : '语音'}</TableCell><TableCell>{order.quantity}</TableCell><TableCell>{(order.amount_cents / 100).toFixed(2)} 元</TableCell><TableCell>{order.status}{managed.close_state && <div className="table-sub mono">关单 {managed.close_state}</div>}{managed.query_state && <div className="table-sub mono">查单 {managed.query_state}</div>}</TableCell><TableCell>{paymentStateLabels[managed.payment_state] ?? managed.payment_state}</TableCell><TableCell className="muted">{managed.expires_at ? <span>到期 {dateTime(managed.expires_at)}</span> : '未设业务有效期'}<div className="table-sub">{order.paid_at ? `到账 ${dateTime(order.paid_at)}` : '未到账'}</div></TableCell><TableCell className="mono">{order.token.slice(0, 12)}…</TableCell><TableCell><div className="notification-actions">{order.out_trade_no && <Button size="sm" variant="outline" disabled={!paymentWrite || actionBusy} onClick={() => { setActionOrder(managed); setActionKind('query'); setLastAction(null) }}>查单</Button>}{order.status === 'PENDING' && order.out_trade_no && <Button size="sm" variant="outline" disabled={!paymentWrite || actionBusy} onClick={() => { setActionOrder(managed); setActionKind('close'); setLastAction(null) }}>关单</Button>}{order.status === 'PENDING' && canWrite && <Button size="sm" onClick={() => setConfirming(order)}>确认到账</Button>}{!order.out_trade_no && <span className="muted">—</span>}</div></TableCell></TableRow> })}</TableBody></Table><Pager page={orders.data.page} size={orders.data.size} total={orders.data.total} onChange={setOrdersPage}/></> : <Empty title="暂无充值订单"/>}{confirming && <form className="form-stack" onSubmit={confirmOrder} style={{ marginTop: 16 }}><div className="notice">确认订单 {confirming.id} 已线下到账，确认后立即写入资源账户和流水。</div><div className="field"><Label>到账凭证/备注</Label><Input value={confirmReference} onChange={e => setConfirmReference(e.target.value)} maxLength={200}/></div><div style={{ display: 'flex', gap: 8 }}><Button type="button" variant="outline" onClick={() => setConfirming(null)}>取消</Button><Button type="submit">确认到账</Button></div></form>}{actionOrder && <form className="form-stack" onSubmit={runOrderAction} style={{ marginTop: 16 }}><div className="notice">对订单 {actionOrder.id} 执行{actionKind === 'query' ? '微信查单' : '微信关单'}。查单会校验微信响应签名、商户身份、商户订单号与金额后才可能入账；关单只关闭商户侧订单，不代表退款。结果未确认时订单保持待处理并记录 UNKNOWN。</div><div style={{ display: 'flex', gap: 8 }}><Button type="button" variant="outline" onClick={() => { setActionOrder(null); setLastAction(null) }}>取消</Button><Button type="submit" disabled={actionBusy}>{actionBusy ? '处理中…' : actionKind === 'query' ? '确认查单' : '确认关单'}</Button></div>{lastAction && <div className="notice success-note" role="status">{lastAction.outcome} · {lastAction.message}</div>}</form>}</CardContent></Card>
      </div></details>
      <details className="secondary-controls"><summary>厂商受理记录</summary><div className="secondary-controls-body">
      <Card><CardContent>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}><h2 className="section-heading" style={{ margin: 0 }}>真实厂商发送尝试</h2><Button variant="ghost" size="sm" style={{ marginLeft: 'auto' }} onClick={attempts.refresh}>刷新</Button></div>
        <div className="notice">SENT 仅表示厂商受理，不代表送达；UNKNOWN 不会自动重发或退回资源。</div>
        {attempts.loading ? <Loading/> : attempts.error != null ? <ErrorNotice error={attempts.error} retry={attempts.refresh}/> : attempts.data?.items.length ? <>
          <Table><TableHeader><TableRow><TableHead>时间</TableHead><TableHead>事件</TableHead><TableHead>来源记录</TableHead><TableHead>联系人</TableHead><TableHead>渠道</TableHead><TableHead>尝试</TableHead><TableHead>状态</TableHead><TableHead>厂商码</TableHead><TableHead>回执 ID</TableHead></TableRow></TableHeader>
            <TableBody>{attempts.data.items.map(item => <TableRow key={item.id}><TableCell className="muted">{item.created_at}</TableCell><TableCell>{item.event_kind === 'FAULT' ? '设备故障' : '火警'}</TableCell><TableCell><span>{item.source_kind === 'RAW_FAULT' ? '设备上报' : item.source_kind === 'LEGACY_FAULT' ? '旧平台故障' : '报警记录'}</span><div className="table-sub mono">{item.source_id}</div></TableCell><TableCell className="mono">{item.contact_id}</TableCell><TableCell>{item.channel === 'SMS' ? '短信' : '语音'}</TableCell><TableCell>{item.attempt_no}</TableCell><TableCell>{item.status}</TableCell><TableCell>{item.provider_code ?? '—'}</TableCell><TableCell className="mono">{item.provider_id ?? '—'}</TableCell></TableRow>)}</TableBody>
          </Table><Pager page={attempts.data.page} size={attempts.data.size} total={attempts.data.total} onChange={setAttemptsPage}/>
        </> : <Empty title="暂无真实发送尝试"/>}
      </CardContent></Card>
      </div></details>
      {createdOrder && <Card><CardContent><h2 className="section-heading" id="management-wechat-order">微信支付订单</h2>{createdOrder.code_url ? <div className="payment-qr"><QRCodeSVG value={createdOrder.code_url} size={220}/><p>订单号 {createdOrder.id} · 金额 {(createdOrder.amount_cents / 100).toFixed(2)} 元</p><p>客户使用微信扫码支付；支付成功后自动入账。</p></div> : <div className="notice">订单 {createdOrder.id} 已创建，但微信支付未配置或下单失败。</div>}</CardContent></Card>}
      {notice && <div role="status" className="notice success-note">{notice}</div>}{error && <div role="alert" className="notice danger-note">{error}</div>}
    </>}
  </>
}
