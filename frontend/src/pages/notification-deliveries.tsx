import { DataTransferActions } from '@/components/data-transfer-actions'
import { useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { notificationDeliveryApi, type Delivery } from '@/lib/notification-delivery'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { Badge } from '@/components/ui/badge'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { NotificationDeliveryReceiptPanel, NotificationReceiptPolicyPanel, receiptReason } from '@/components/notification-delivery-receipt'
import { dateTime } from '@/lib/format'

const deliveryLabels: Record<string, string> = { SENT: '厂商已受理', ACCEPTED: '厂商已受理', UNKNOWN: '受理情况未知', FAILED: '发送失败', SKIPPED: '未发送', PENDING: '发送处理中', SUPPRESSED: '已抑制', RESOURCE_UNAVAILABLE: '资源不可用' }
const receiptLabels: Record<string, string> = { UNCONFIRMED: '未确认', WAITING: '等待回执', DELIVERED: '已送达', FAILED: '回执失败', ACCEPTED: '厂商已受理', HEARD_FULL: '已听完语音', CONNECTED_PARTIAL: '接通未听完', NOT_CONNECTED: '未接通' }

export function NotificationDeliveriesPage() {
  const { user, audience, permissions } = useSession()
  if (!user || audience !== 'management' || !permissions.includes('notification-resources:read')) return <Empty title="当前账户无真实发送记录权限"/>
  return <DeliveriesContent canReconcile={permissions.includes('notification-delivery:reconcile')} canConfigure={permissions.includes('notification-config:write')}/>
}

function DeliveriesContent({ canReconcile, canConfigure }: { canReconcile: boolean; canConfigure: boolean }) {
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [policyOpen, setPolicyOpen] = useState(false)
  const [notice, setNotice] = useState('')
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeKey = useSession(s => `${s.scopeTenantIds.join(',')}|${s.scopePlaceIds.join(',')}|${s.scopeInitialized}`)
  const deliveries = useResource(`notification-deliveries:${scopeKey}:${page}`, signal => notificationDeliveryApi.list(page, scopeTenantIds, scopePlaceIds, signal))
  return <><PageHeading eyebrow="NOTIFICATION · 发送记录" title="通知记录" description="查询火警、故障的短信、语音与公众号发送尝试及厂商回执。"><Button variant="outline" onClick={() => setPolicyOpen(true)}>回执查询策略</Button><Button variant="outline" onClick={deliveries.refresh}><RefreshCw/>刷新</Button><DataTransferActions kind="NOTIFICATION_ATTEMPTS" label="导出发送尝试" filters={{ unit_ids: scopeTenantIds.join(','), place_ids: scopePlaceIds.join(',') }}/><PageHelp title="受理、送达与接听"><p>“厂商已受理”只表示短信或语音服务商接受发送请求，不是运营商送达、用户接听或已经处置。</p><p>短信送达、语音听完/接通未听完/未接通、公众号完成事件分别展示；公众号 errcode 0 只表示受理，只有认证回调 success/failed 才写送达或失败，未收到回调保持未确认，不盲目重发。</p><p>查询回执只调用厂商只读详情接口，不重发通知、不清除报警或故障、不改变资源余额与设备状态。</p><p>历史尝试若缺少发送时的号码、账户、BizId/CallId 或日期快照，将保持未确认并显示具体原因。</p></PageHelp></PageHeading>
    <Card className="list-card"><CardContent>{deliveries.loading ? <Loading/> : deliveries.error != null ? <ErrorNotice error={deliveries.error} retry={deliveries.refresh}/> : deliveries.data?.items.length ? <><Table className="notification-delivery-table"><TableHeader><TableRow><TableHead>时间</TableHead><TableHead>事件 / 来源</TableHead><TableHead>设备 / 联系人</TableHead><TableHead>渠道</TableHead><TableHead>尝试</TableHead><TableHead>发送受理</TableHead><TableHead>送达 / 接听回执</TableHead><TableHead>最近回执</TableHead><TableHead>回执 ID</TableHead><TableHead>详情</TableHead></TableRow></TableHeader><TableBody>{deliveries.data.items.map(item => <DeliveryRow key={item.id} item={item} onOpen={() => { setSelectedId(item.id); setNotice('') }}/>)}</TableBody></Table><Pager page={deliveries.data.page} size={deliveries.data.size} total={deliveries.data.total} onChange={setPage}/></> : <Empty title="暂无真实发送记录"/>}</CardContent></Card>
    {notice && <div role="status" className="notice">{notice}</div>}
    <Sheet open={policyOpen} onOpenChange={setPolicyOpen}><SheetContent className="review-sheet sm:max-w-3xl">{policyOpen && <NotificationReceiptPolicyPanel canConfigure={canConfigure} onSaved={() => setNotice('已重新读取回执查询策略；实际生效值按环境安全上限展示，保存失败或 revision 冲突时不会写入。')}/>}</SheetContent></Sheet>
    <Sheet open={selectedId !== null} onOpenChange={open => { if (!open) setSelectedId(null) }}><SheetContent className="review-sheet sm:max-w-4xl">{selectedId && <NotificationDeliveryReceiptPanel id={selectedId} canReconcile={canReconcile} onChanged={() => { deliveries.refresh(); setNotice('已重新读取该发送尝试的回执状态与观测证据。') }}/>}</SheetContent></Sheet>
  </>
}

function DeliveryRow({ item, onOpen }: { item: Delivery; onOpen: () => void }) {
  const receipt = item.receipt
  const variant = receipt == null ? 'outline' : receipt.state === 'DELIVERED' || receipt.state === 'HEARD_FULL' ? 'default' : receipt.state === 'FAILED' || receipt.state === 'NOT_CONNECTED' ? 'destructive' : 'outline'
  return <TableRow><TableCell className="muted">{dateTime(item.created_at)}</TableCell><TableCell><span>{item.event_kind === 'FIRE' ? '火警' : item.event_kind === 'FAULT' ? '故障' : item.event_kind}</span><div className="muted table-sub">{item.source_kind} · <span className="mono">{item.source_id}</span></div></TableCell><TableCell className="mono">{item.device_id}<div className="muted table-sub">{item.contact_id}</div></TableCell><TableCell>{item.channel === 'SMS' ? '短信' : item.channel === 'VOICE' ? '语音' : '公众号'}</TableCell><TableCell>{item.attempt_no}</TableCell><TableCell><Badge variant={item.status === 'FAILED' ? 'destructive' : 'outline'}>{deliveryLabels[item.status] ?? item.status}</Badge><div className="muted table-sub">{item.provider_code ?? '—'}</div></TableCell><TableCell>{receipt ? <><Badge variant={variant}>{receiptLabels[receipt.state] ?? receipt.state}</Badge><div className="muted table-sub">{receiptReason(receipt.reason)}</div></> : <><Badge variant="outline">未确认</Badge><div className="muted table-sub">{item.eligibility.queryable ? '尚未查询厂商回执' : receiptReason(item.eligibility.reason)}</div></>}</TableCell><TableCell className="muted">{receipt ? dateTime(receipt.last_observed_at) : '—'}</TableCell><TableCell className="mono">{item.provider_id ?? '—'}</TableCell><TableCell><Button variant="outline" onClick={onOpen}>回执详情</Button></TableCell></TableRow>
}
