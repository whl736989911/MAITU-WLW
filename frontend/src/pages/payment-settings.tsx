import { useEffect, useRef, useState, type FormEvent } from 'react'
import { AlertTriangle, FileSearch, RefreshCw, Save, ShieldCheck } from 'lucide-react'
import { errorMessage } from '@/lib/api'
import { paymentApi, type PaymentConfigView, type PaymentDifference, type PaymentRun } from '@/lib/payments'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'

const sourceLabels: Record<string, string> = {
  CONFIG: '页面配置', ENV: '部署环境变量（兼容，只读）', NONE: '尚未配置',
}
const runStatusLabels: Record<string, string> = {
  RUNNING: '处理中（结果未确认）', COMPLETED: '已完成', FAILED: '失败',
}
const differenceLabels: Record<string, string> = {
  MALFORMED_ROW: '无法解析的账单行',
  DUPLICATE_BILL_ORDER: '账单商户订单号重复',
  DUPLICATE_TRANSACTION: '账单微信交易号重复',
  MERCHANT_MISMATCH: '账单商户号与当前配置不一致',
  CURRENCY_MISMATCH: '币种不是 CNY',
  AMOUNT_MISMATCH: '本地金额与账单金额不一致',
  TRANSACTION_MISMATCH: '本地交易号与账单不一致',
  STATE_MISMATCH: '本地已支付但账单状态不是成功',
  BILL_PAID_LOCAL_UNPAID: '账单已支付但本地未入账（需显式查单）',
  MISSING_BILL_ROW: '本地已支付但账单缺少该订单',
}

type Form = {
  enabled: boolean
  mchId: string
  appId: string
  apiKey: string
  notifyUrl: string
  expiry: string
  autoLifecycle: boolean
  autoReconcile: boolean
}

export function PaymentSettingsPage() {
  const { user, permissions } = useSession()
  if (!user) return <Empty title="请先登录"/>
  if (!permissions.includes('payments:read')) return <Empty title="当前账户无支付配置权限">需要 payments:read 权限。</Empty>
  return <PaymentSettingsContent canWrite={permissions.includes('payments:write')}/>
}

function PaymentSettingsContent({ canWrite }: { canWrite: boolean }) {
  const config = useResource('payment-config', signal => paymentApi.config(signal))
  const status = useResource('payment-config-status', signal => paymentApi.status(signal))
  const [form, setForm] = useState<Form>({ enabled: false, mchId: '', appId: '', apiKey: '', notifyUrl: '', expiry: '', autoLifecycle: false, autoReconcile: false })
  const [revision, setRevision] = useState(0)
  const [notice, setNotice] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [runsPage, setRunsPage] = useState(1)
  const runs = useResource(`payment-reconciliation-runs:${runsPage}`, signal => paymentApi.runs(runsPage, signal))
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null)
  const detail = useResource(`payment-reconciliation-run:${selectedRunId ?? 'none'}`, signal => paymentApi.run(selectedRunId!, signal), !!selectedRunId)
  const [billDate, setBillDate] = useState(yesterdayBillDate())
  const [reconcileBusy, setReconcileBusy] = useState(false)
  const retryKey = useRef<{ billDate: string; key: string } | null>(null)

  useEffect(() => { if (config.data) hydrate(config.data) }, [config.data])

  function hydrate(value: PaymentConfigView) {
    setForm({
      enabled: value.enabled, mchId: '', appId: '', apiKey: '', notifyUrl: value.notify_url ?? '',
      expiry: value.order_expiry_minutes === null ? '' : String(value.order_expiry_minutes),
      autoLifecycle: value.auto_lifecycle_enabled, autoReconcile: value.auto_reconcile_enabled,
    })
    setRevision(value.revision)
  }
  function field<K extends keyof Form>(key: K, value: Form[K]) { setForm(previous => ({ ...previous, [key]: value })); setNotice(''); setError('') }

  async function save(event: FormEvent) {
    event.preventDefault(); setError(''); setNotice('')
    if (!canWrite) { setError('当前账户没有支付配置写入权限'); return }
    const expiry = form.expiry.trim()
    const minutes = expiry === '' ? null : Number(expiry)
    if (minutes !== null && (!Number.isInteger(minutes) || minutes < 1 || minutes > 21600)) { setError('业务有效期必须是 1 至 21600 分钟，或留空表示不设置'); return }
    if (form.apiKey && form.apiKey.length !== 32) { setError('APIv2 密钥必须为 32 位字符'); return }
    setBusy(true)
    try {
      const saved = await paymentApi.saveConfig({
        enabled: form.enabled, mch_id: form.mchId || null, app_id: form.appId || null,
        api_v2_key: form.apiKey || null, notify_url: form.notifyUrl || null,
        order_expiry_minutes: minutes, auto_lifecycle_enabled: form.autoLifecycle,
        auto_reconcile_enabled: form.autoReconcile, expected_revision: revision,
      }, crypto.randomUUID())
      hydrate(saved); setNotice('支付配置已保存。密钥只写不读；密钥变更会保留旧版本用于历史订单核验。')
      config.refresh(); status.refresh()
    } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }

  async function runReconciliation(event: FormEvent) {
    event.preventDefault(); setError(''); setNotice('')
    if (!canWrite) { setError('当前账户没有支付对账权限'); return }
    if (!/^\d{8}$/.test(billDate)) { setError('账单日期必须为 yyyyMMdd'); return }
    const requestKey = retryKey.current && retryKey.current.billDate === billDate
      ? retryKey.current.key
      : (retryKey.current = { billDate, key: crypto.randomUUID() }).key
    setReconcileBusy(true)
    try {
      const result = await paymentApi.reconcile({ bill_date: billDate }, requestKey)
      retryKey.current = null
      setSelectedRunId(result.run.id); runs.refresh(); status.refresh()
      setNotice(`对账${runStatusLabels[result.run.status] ?? result.run.status}：${result.run.difference_count} 条差异；对账不会自动入账或退款。`)
    } catch (e) { setError(errorMessage(e)) } finally { setReconcileBusy(false) }
  }

  const current = config.data
  return <>
    <PageHeading eyebrow="PAYMENT · 微信支付" title="支付配置与对账" description="管理微信支付 APIv2 商户配置、订单生命周期与每日对账单。">
      <Button type="button" variant="outline" onClick={() => { config.refresh(); status.refresh(); runs.refresh() }}><RefreshCw/>刷新</Button>
      <PageHelp title="支付配置与对账">
        <p>商户号、AppID 与 APIv2 密钥由页面保存，密钥加密存储且只写不读；输入留空表示保持现有值。保存需要当前会话与版本校验。</p>
        <p>业务有效期只有在明确填写后才生效，并随下单请求提交给微信；留空表示不设置，不推断默认到期时间。微信官方要求订单创建 5 分钟后才能关单。</p>
        <p>自动查单/关单与每日对账默认关闭，必须在此页面显式开启并保存。系统只会调用查单、关单与下载对账单，不会重新下单、自动入账或退款。</p>
        <p>对账单只对本地平台订单比对；外部商户订单只计数，不导入、不展示。差异需要通过订单查单核实后处理。</p>
      </PageHelp>
    </PageHeading>
    {status.error != null && <ErrorNotice error={status.error} retry={status.refresh}/>}
    {status.data && <div className="notice provider-live-status" role="status">
      <ShieldCheck size={15}/>配置来源：{sourceLabels[status.data.source] ?? status.data.source}
      · 新订单支付：{status.data.ready_for_new_orders ? '可发起' : '不可发起'}
      · 密钥存储：{status.data.secret_storage_ready ? '可用' : '不可用'}
      · 待支付订单 {status.data.pending_orders}（已过本地有效期 {status.data.expired_pending_orders}）
      {status.data.last_reconciliation_at && <> · 最近对账 {dateTime(status.data.last_reconciliation_at)}（{runStatusLabels[status.data.last_reconciliation_status ?? ''] ?? status.data.last_reconciliation_status}）</>}
    </div>}
    {!canWrite && <div role="status" className="notice">当前账户只有读取权限，不能保存配置或发起对账。</div>}
    {config.loading ? <Loading/> : config.error != null ? <ErrorNotice error={config.error} retry={config.refresh}/> : current && <>
      {current.source === 'ENV' && <div className="notice" role="status">当前使用部署环境变量中的微信支付配置（兼容模式，只读展示）。首次保存页面配置后将改为页面配置；启用时必须重新填写 APIv2 密钥。</div>}
      <form className="form-stack" onSubmit={save}>
        <fieldset className="provider-fieldset form-stack" disabled={!canWrite || busy}>
          <div className="provider-config-grid">
            <Card><CardContent><h2 className="section-heading"><ShieldCheck size={18}/>商户身份</h2><div className="form-stack">
              <label className="inline-check"><input type="checkbox" checked={form.enabled} onChange={e => field('enabled', e.target.checked)}/>启用微信支付（新订单可发起支付）</label>
              <div className="field"><Label htmlFor="payment-mch">商户号</Label><Input id="payment-mch" value={form.mchId} onChange={e => field('mchId', e.target.value)} placeholder={current.mch_id_masked ?? '未配置，留空保持不变'} autoComplete="off"/></div>
              <div className="field"><Label htmlFor="payment-appid">AppID</Label><Input id="payment-appid" value={form.appId} onChange={e => field('appId', e.target.value)} placeholder={current.app_id_masked ?? '未配置，留空保持不变'} autoComplete="off"/></div>
              <div className="field"><Label htmlFor="payment-key">APIv2 密钥</Label><Input id="payment-key" type="password" value={form.apiKey} onChange={e => field('apiKey', e.target.value)} placeholder={current.api_key_configured ? '已配置，留空保持不变（32 位）' : '只写不读（32 位）'} autoComplete="new-password" maxLength={32}/></div>
            </div></CardContent></Card>
            <Card><CardContent><h2 className="section-heading"><FileSearch size={18}/>通知与生命周期</h2><div className="form-stack">
              <div className="field"><Label htmlFor="payment-notify">支付结果通知地址</Label><Input id="payment-notify" value={form.notifyUrl} onChange={e => field('notifyUrl', e.target.value)} placeholder="https://…，不能带查询参数" autoComplete="off"/></div>
              <div className="field"><Label htmlFor="payment-expiry">订单业务有效期（分钟，留空不设置）</Label><Input id="payment-expiry" type="number" min={1} max={21600} value={form.expiry} onChange={e => field('expiry', e.target.value)} placeholder="留空表示不推断到期时间"/></div>
              <label className="inline-check"><input type="checkbox" checked={form.autoLifecycle} onChange={e => field('autoLifecycle', e.target.checked)}/>启用自动查单与到期关单</label>
              <label className="inline-check"><input type="checkbox" checked={form.autoReconcile} onChange={e => field('autoReconcile', e.target.checked)}/>启用每日对账（昨日账单，10:00 后执行）</label>
              {form.autoLifecycle && <div className="notice" role="status">自动任务只会在本地订单到达有效期后调用微信关单，并对未确认订单查单；结果未知时保持 UNKNOWN 并有限次重试。</div>}
            </div></CardContent></Card>
          </div>
          <div className="provider-save-bar">
            <Button type="submit" disabled={!canWrite || busy}><Save/>{busy ? '保存中…' : '保存支付配置'}</Button>
            <span className="muted-note">版本 {revision} · {current.updated_at ? dateTime(current.updated_at) : '尚未保存'} · 密钥版本 {current.credentials_revision}</span>
          </div>
        </fieldset>
      </form>
    </>}
    <Card className="list-card management-list-panel"><CardContent>
      <div className="management-list-bar">
        <h2 className="section-heading"><AlertTriangle size={16}/>每日对账</h2>
        <div className="list-toolbar management-list-actions"><Button variant="outline" size="sm" onClick={runs.refresh}>刷新</Button></div>
      </div>
      <p className="management-list-context">对账只下载当日「成功交易」账单并记录差异，不会自动入账、退款或关闭订单。日期为微信账单日期（yyyyMMdd），可查询约三个月内账单。</p>
      <form className="filter-row management-named-filters" onSubmit={runReconciliation}>
        <div className="field"><Label htmlFor="payment-bill-date">账单日期</Label><Input id="payment-bill-date" value={billDate} onChange={e => setBillDate(e.target.value.trim())} placeholder="yyyyMMdd" maxLength={8} disabled={!canWrite || reconcileBusy}/></div>
        <div style={{ display: 'flex', alignItems: 'flex-end' }}><Button type="submit" disabled={!canWrite || reconcileBusy}><FileSearch/>{reconcileBusy ? '下载对账中…' : '下载并比对昨日账单'}</Button></div>
      </form>
      {runs.loading ? <Loading/> : runs.error != null ? <ErrorNotice error={runs.error} retry={runs.refresh}/> : runs.data?.items.length ? <>
        <Table><TableHeader><TableRow><TableHead>账单日期</TableHead><TableHead>状态</TableHead><TableHead>本地订单</TableHead><TableHead>账单行</TableHead><TableHead>匹配</TableHead><TableHead>外部行</TableHead><TableHead>差异</TableHead><TableHead>格式异常</TableHead><TableHead>创建时间</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
          <TableBody>{runs.data.items.map((run: PaymentRun) => <TableRow key={run.id}>
            <TableCell className="mono">{run.bill_date}</TableCell>
            <TableCell>{runStatusLabels[run.status] ?? run.status}{run.error_code && <div className="table-sub mono">{run.error_code}</div>}</TableCell>
            <TableCell>{run.local_orders}</TableCell><TableCell>{run.bill_rows}</TableCell><TableCell>{run.matched_rows}</TableCell>
            <TableCell>{run.external_rows}</TableCell><TableCell>{run.difference_count}</TableCell><TableCell>{run.malformed_rows}</TableCell>
            <TableCell className="muted">{dateTime(run.created_at)}</TableCell>
            <TableCell><Button type="button" size="sm" variant="outline" onClick={() => setSelectedRunId(run.id)}>查看差异</Button></TableCell>
          </TableRow>)}</TableBody>
        </Table>
        <Pager page={runs.data.page} size={runs.data.size} total={runs.data.total} onChange={setRunsPage}/>
      </> : <Empty title="暂无对账记录">对账默认关闭，需要在此页面显式开启或手动下载。</Empty>}
      {selectedRunId && <div style={{ marginTop: 16 }}>
        {detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : detail.data && <>
          <div className="notice" role="status">
            账单 {detail.data.run.bill_date} · {runStatusLabels[detail.data.run.status] ?? detail.data.run.status}
            {detail.data.run.error_message && <> · {detail.data.run.error_message}</>}
            {detail.data.run.bill_sha256 && <div className="table-sub mono">SHA-256 {detail.data.run.bill_sha256}</div>}
          </div>
          {detail.data.differences.length ? <Table><TableHeader><TableRow><TableHead>类型</TableHead><TableHead>订单</TableHead><TableHead>商户订单号</TableHead><TableHead>微信交易号</TableHead><TableHead>本地状态</TableHead><TableHead>本地金额</TableHead><TableHead>账单金额</TableHead><TableHead>账单状态</TableHead><TableHead>说明</TableHead></TableRow></TableHeader>
            <TableBody>{detail.data.differences.map((item: PaymentDifference, index: number) => <TableRow key={`${item.kind}:${item.out_trade_no ?? index}`}>
              <TableCell><strong>{differenceLabels[item.kind] ?? item.kind}</strong></TableCell>
              <TableCell className="mono">{item.order_id ?? '—'}</TableCell><TableCell className="mono">{item.out_trade_no ?? '—'}</TableCell>
              <TableCell className="mono">{item.transaction_id_masked ?? '—'}</TableCell><TableCell>{item.local_status ?? '—'}</TableCell>
              <TableCell>{item.local_amount_cents === null ? '—' : (item.local_amount_cents / 100).toFixed(2) + ' 元'}</TableCell><TableCell>{item.bill_amount_cents === null ? '—' : (item.bill_amount_cents / 100).toFixed(2) + ' 元'}</TableCell>
              <TableCell>{item.bill_trade_state ?? '—'}</TableCell><TableCell>{item.detail ?? '—'}</TableCell>
            </TableRow>)}</TableBody></Table>
            : <Empty title="本次对账没有差异"/>}
        </>}
      </div>}
    </CardContent></Card>
    {error && <div role="alert" className="notice danger-note">{error}</div>}
    {notice && <div role="status" className="notice success-note">{notice}</div>}
  </>
}

function yesterdayBillDate() {
  const date = new Date()
  date.setDate(date.getDate() - 1)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}${month}${day}`
}
