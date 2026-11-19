import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { publishObjectChange } from '@/lib/object-events'
import { dateTime } from '@/lib/format'
import { listDeviceChannels, type DeviceChannel } from '@/lib/device-channels'
import { cancelSuppression, createSuppression, deleteSuppression, getSuppressionHistory, getSuppressionTargets, listSuppressions, setSuppressionEnabled, updateSuppression, type Suppression, type SuppressionCreate, type SuppressionHistoryEvent, type SuppressionScope } from '@/lib/suppressions'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading } from '@/components/common'
import { NotificationGate } from '@/components/notification-common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { ConfigurationUnitTarget } from '@/components/configuration-unit-target'
import { DeviceChannelRegistry } from '@/components/device-channel-registry'

export function SuppressionsPage() { return <NotificationGate page="suppressions"><SuppressionContent/></NotificationGate> }

function SuppressionContent() {
  const session = useSession(), canWrite = session.permissions.includes('suppressions:write')
  const scopeKey = JSON.stringify([session.generation, session.scopeTenantIds, session.scopePlaceIds, session.scopeInitialized])
  const [tab, setTab] = useState<SuppressionScope>('TENANT'), [page, setPage] = useState(1), [status, setStatus] = useState(''), [creating, setCreating] = useState(false)
  const [active, setActive] = useState<Suppression | null>(null), [operation, setOperation] = useState<'edit' | 'enable' | 'disable' | 'cancel' | 'delete' | null>(null)
  const list = useResource(`suppressions:${scopeKey}:${page}:${tab}:${status}`, signal => listSuppressions({ page, size: 20, scope: tab, status, scope_tenant_ids: session.scopeTenantIds, scope_place_ids: session.scopePlaceIds }, signal), session.scopeInitialized)
  useEffect(() => { setCreating(false); setActive(null); setOperation(null) }, [session.generation])
  useEffect(() => { setPage(1) }, [scopeKey])
  const rows = list.data?.items ?? []
  function start(item: Suppression, next: typeof operation) { setActive(item); setOperation(next) }
  function closeAction() { setActive(null); setOperation(null) }
  function saved() { closeAction(); list.refresh(); publishObjectChange('notifications') }
  return <><PageHeading eyebrow="SUPPRESSION · 有期限屏蔽" title="通知屏蔽" description="查看与管理通知派发屏蔽规则。"><PageHelp title="屏蔽范围说明"><div className="form-stack"><p>屏蔽只影响通知派发；设备连接、物理状态和人工处置独立，报警事实仍正常记录。</p><p>火警默认穿透屏蔽；若规则明确包含火警，需要二次确认。登记的传感器资源是人工台账，不代表物理传感器证据。</p></div></PageHelp></PageHeading>
    <Card className="list-card"><CardContent><div className="list-toolbar"><h2>屏蔽记录</h2>{canWrite && <Button type="button" onClick={() => setCreating(true)}>创建屏蔽</Button>}</div>
      <div className="list-toolbar"><div role="tablist" aria-label="屏蔽资源范围">{(['TENANT', 'DEVICES', 'SENSORS'] as const).map(value => <Button key={value} type="button" variant={tab === value ? 'default' : 'outline'} role="tab" aria-selected={tab === value} onClick={() => { setTab(value); setPage(1); setCreating(false); setActive(null); setOperation(null) }}>{value === 'TENANT' ? '屏蔽单位' : value === 'DEVICES' ? '屏蔽设备' : '传感器资源'}</Button>)}</div><label>记录状态<select className="native-select" value={status} onChange={event => { setStatus(event.target.value); setPage(1) }}><option value="">全部未删除</option><option value="ACTIVE">生效中</option><option value="SCHEDULED">待生效</option><option value="DISABLED">已停用</option><option value="EXPIRED">已到期</option><option value="CANCELLED">已取消</option><option value="DELETED">已删除（历史）</option></select></label></div>
      {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : rows.length ? <><div className="form-stack">{rows.map(item => <SuppressionCard key={item.id} item={item} canWrite={canWrite} onAction={start} onHistory={record => start(record, null)}/>)}</div><div className="identity-form-actions"><Button type="button" variant="outline" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</Button><span aria-live="polite">第 {page} 页 · 共 {list.data?.total ?? 0} 条</span><Button type="button" variant="outline" disabled={!list.data || page * list.data.size >= list.data.total} onClick={() => setPage(page + 1)}>下一页</Button></div></> : <Empty title="暂无屏蔽记录">当前范围与筛选条件下没有记录。</Empty>}
    </CardContent></Card>
    {creating && <ConfigurationUnitTarget>{tenantId => <CreateForm key={`${tenantId}:${session.generation}`} tenantId={tenantId} initialScope={tab} onClose={() => setCreating(false)} saved={() => { setCreating(false); saved() }}/>}</ConfigurationUnitTarget>}
    {active && <SuppressionDetail key={`${active.id}:${operation ?? 'detail'}:${session.generation}`} record={active} operation={operation} canWrite={canWrite} onClose={closeAction} saved={saved}/>}
    {tab === 'SENSORS' && !creating && !active && <section className="form-stack" aria-label="人工登记传感器通道"><h2>人工传感器台账</h2><p className="muted-note">只选择服务端登记的通道；每条通道保留所属设备，不把设备当作传感器。</p><ConfigurationUnitTarget>{tenantId => <DeviceChannelRegistry key={`${tenantId}:${session.generation}`} tenantId={tenantId}/>}</ConfigurationUnitTarget></section>}
  </>
}

function SuppressionCard({ item, canWrite, onAction, onHistory }: { item: Suppression; canWrite: boolean; onAction: (item: Suppression, action: 'edit' | 'enable' | 'disable' | 'cancel' | 'delete') => void; onHistory: (item: Suppression) => void }) {
  const mutable = item.effective_state !== 'EXPIRED' && item.effective_state !== 'CANCELLED' && item.effective_state !== 'DELETED'
  const deletable = item.effective_state === 'EXPIRED' || item.effective_state === 'CANCELLED'
  return <article className="notice"><div className="list-toolbar"><strong>{item.scope === 'TENANT' ? '单位全部设备' : item.scope === 'DEVICES' ? '指定设备' : '指定人工登记通道'}</strong><Badge variant="outline">{item.effective_state}</Badge></div><p className="table-sub mono">{item.id} · {item.tenant_id} · revision {item.revision}</p><p>{dateTime(item.starts_at)} → {dateTime(item.ends_at)}</p><p>{item.fire_confirmed ? '火警一并屏蔽（已确认）' : '火警穿透，仅屏蔽非火警'}</p><p>{item.reason}</p><div className="identity-form-actions"><Button type="button" variant="outline" onClick={() => onHistory(item)}>详情 / 历史</Button>{canWrite && mutable && <><Button type="button" variant="outline" onClick={() => onAction(item, 'edit')}>编辑期限</Button><Button type="button" variant="outline" onClick={() => onAction(item, item.enabled ? 'disable' : 'enable')}>{item.enabled ? '停用' : '启用'}</Button><Button type="button" variant="outline" onClick={() => onAction(item, 'cancel')}>取消</Button></>}{canWrite && deletable && <Button type="button" variant="destructive" onClick={() => onAction(item, 'delete')}>删除过期记录</Button>}</div></article>
}

function SuppressionDetail({ record, operation, canWrite, onClose, saved }: { record: Suppression; operation: 'edit' | 'enable' | 'disable' | 'cancel' | 'delete' | null; canWrite: boolean; onClose: () => void; saved: () => void }) {
  const targets = useResource(`suppression-targets:${record.id}:${record.tenant_id}`, signal => getSuppressionTargets(record.id, record.tenant_id, signal))
  const history = useResource(`suppression-history:${record.id}:${record.tenant_id}`, signal => getSuppressionHistory(record.id, record.tenant_id, signal))
  const targetRows = targets.data ?? []
  return <Card><CardContent><div className="list-toolbar"><h2>{operation ? '屏蔽变更' : '屏蔽详情与历史'}</h2><Button type="button" variant="outline" onClick={onClose}>关闭</Button></div>
    <p>{record.scope} · {record.effective_state} · {record.tenant_id} · revision {record.revision}</p><p>{dateTime(record.starts_at)} → {dateTime(record.ends_at)}</p><p>{record.reason}</p>
    {record.scope !== 'TENANT' && <section><h3>目标快照</h3>{targets.loading ? <Loading/> : targets.error != null ? <ErrorNotice error={targets.error} retry={targets.refresh}/> : targetRows.length ? <div className="form-stack">{targetRows.map((target, index) => <article className="notice" key={`${target.channel_id}:${index}`}><strong>{target.channel_name}</strong><p>设备：{target.device_name} · {target.device_id}</p><p>通道：{target.channel_key} · {target.channel_type}</p></article>)}</div> : <Empty title="没有目标快照">服务端没有返回目标快照。</Empty>}</section>}
    <section><h3>不可变更历史</h3>{history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data?.length ? <div className="form-stack">{history.data.map(event => <HistoryItem key={event.id} event={event}/>)}</div> : <Empty title="暂无历史事件">没有历史事件。</Empty>}</section>
    {operation && canWrite && <MutationForm record={record} operation={operation} onClose={onClose} saved={saved}/>}
  </CardContent></Card>
}
function HistoryItem({ event }: { event: SuppressionHistoryEvent }) {
  return <article className="notice"><strong>{event.action} · {event.outcome}</strong><p>{dateTime(event.occurred_at)} · 操作者 {event.actor_id}</p><p>{event.reason ?? '—'}</p><p className="mono">请求编号：{event.request_id}</p>{event.snapshot_json && <details><summary>变更快照</summary><pre className="mono">{event.snapshot_json}</pre></details>}</article>
}

function CreateForm({ tenantId, initialScope, onClose, saved }: { tenantId: string; initialScope: SuppressionScope; onClose: () => void; saved: () => void }) {
  const session = useSession(), [scope, setScope] = useState<SuppressionScope>(initialScope), [devicePage, setDevicePage] = useState(1), [channelPage, setChannelPage] = useState(1)
  const [deviceIds, setDeviceIds] = useState<string[]>([]), [channelIds, setChannelIds] = useState<string[]>([]), [deviceNames, setDeviceNames] = useState<Record<string, string>>({}), [selectedChannels, setSelectedChannels] = useState<Record<string, DeviceChannel>>({}), [fire, setFire] = useState(false), [reason, setReason] = useState(''), [startsAt, setStartsAt] = useState(''), [endsAt, setEndsAt] = useState('')
  const action = useMutationAction()
  const deviceList = useResource(`suppression-devices:${tenantId}:${session.generation}:${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${devicePage}`, signal => api.devices(devicePage, '', 20, signal, undefined, undefined, tenantId, session.scopeTenantIds, session.scopePlaceIds), session.scopeInitialized && scope === 'DEVICES')
  const channelList = useResource(`suppression-channels:${tenantId}:${session.generation}:${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}:${channelPage}`, signal => listDeviceChannels({ page: channelPage, size: 20, scope_tenant_ids: session.scopeTenantIds, scope_place_ids: session.scopePlaceIds }, signal, tenantId), session.scopeInitialized && scope === 'SENSORS')
  const devices = deviceList.data?.items ?? [], channels = (channelList.data?.items ?? []).filter(channel => channel.enabled)
  function changeScope(next: SuppressionScope) { setScope(next); setDeviceIds([]); setChannelIds([]); setDeviceNames({}); setSelectedChannels({}); action.touch() }
  function submit(event: FormEvent) {
    event.preventDefault()
    const startDate = new Date(startsAt), endDate = new Date(endsAt), duration = endDate.getTime() - startDate.getTime()
    if (!reason.trim() || !startsAt || !endsAt || !Number.isFinite(duration) || duration <= 0 || duration > 90 * 24 * 60 * 60 * 1000) { action.setError(new Error('请填写有效原因与时间范围；结束时间须晚于开始时间且间隔不超过90天。')); return }
    if (scope === 'DEVICES' && deviceIds.length === 0 || scope === 'SENSORS' && channelIds.length === 0) { action.setError(new Error('请选择至少一个目标。')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    const body: SuppressionCreate = { scope, ...(scope === 'DEVICES' ? { device_ids: deviceIds } : {}), ...(scope === 'SENSORS' ? { channel_ids: channelIds } : {}), fire_confirmed: fire, reason: reason.trim(), starts_at: startDate.toISOString(), ends_at: endDate.toISOString() }
    void action.run((key, signal) => createSuppression(body, key, tenantId, signal), saved)
  }
  return <Card><CardContent><form className="form-stack" onSubmit={submit}><h2>创建通知屏蔽 · {tenantId}</h2>{!action.confirming ? <>
    <div className="field"><Label htmlFor="suppression-scope">屏蔽范围</Label><select id="suppression-scope" className="native-select" value={scope} onChange={event => changeScope(event.target.value as SuppressionScope)}><option value="TENANT">单位全部设备</option><option value="DEVICES">指定设备</option><option value="SENSORS">指定人工登记通道</option></select></div>
    {scope === 'DEVICES' && <><SelectionPage label="设备目标" page={devicePage} total={deviceList.data?.total ?? 0} size={deviceList.data?.size ?? 20} loading={deviceList.loading} error={deviceList.error} retry={deviceList.refresh} previous={() => setDevicePage(devicePage - 1)} next={() => setDevicePage(devicePage + 1)}>{devices.length ? devices.map(device => <label className="notification-checkbox" key={device.id}><input type="checkbox" checked={deviceIds.includes(device.id)} aria-label={`选择 ${device.id}`} onChange={event => { setDeviceIds(event.target.checked ? [...deviceIds, device.id] : deviceIds.filter(id => id !== device.id)); setDeviceNames(event.target.checked ? { ...deviceNames, [device.id]: device.name || device.device_eui || device.id } : Object.fromEntries(Object.entries(deviceNames).filter(([id]) => id !== device.id))); action.touch() }}/>{device.name || device.device_eui || device.id} · {device.id}</label>) : !deviceList.loading && <p>当前页无设备</p>}</SelectionPage>{deviceIds.length > 0 && <div className="notice"><strong>已选择设备 ({deviceIds.length})</strong>{deviceIds.map(id => <p key={id}>{deviceNames[id] || id} · {id}</p>)}</div>}</>}
    {scope === 'SENSORS' && <><p className="notice">目标仅为人工登记并启用的通道，不代表物理传感器存在或接入。</p><SelectionPage label="通道目标" page={channelPage} total={channelList.data?.total ?? 0} size={channelList.data?.size ?? 20} loading={channelList.loading} error={channelList.error} retry={channelList.refresh} previous={() => setChannelPage(channelPage - 1)} next={() => setChannelPage(channelPage + 1)}>{channels.length ? channels.map(channel => <ChannelChoice key={channel.id} channel={channel} checked={channelIds.includes(channel.id)} toggle={on => { setChannelIds(on ? [...channelIds, channel.id] : channelIds.filter(id => id !== channel.id)); setSelectedChannels(on ? { ...selectedChannels, [channel.id]: channel } : Object.fromEntries(Object.entries(selectedChannels).filter(([id]) => id !== channel.id))); action.touch() }}/>) : !channelList.loading && <p>当前页没有启用的人工登记通道</p>}</SelectionPage>{channelIds.length > 0 && <div className="notice"><strong>已选择通道 ({channelIds.length})</strong>{channelIds.map(id => { const channel = selectedChannels[id]; return <p key={id}>{channel?.name ?? id} · {channel?.channel_key ?? id} · 设备 {channel?.device_name || channel?.device_id || '设备名称不可用'}</p> })}</div>}</>}
    <div className="field"><Label htmlFor="suppression-start">开始时间</Label><Input id="suppression-start" type="datetime-local" value={startsAt} onChange={event => { setStartsAt(event.target.value); action.touch() }} required/></div><div className="field"><Label htmlFor="suppression-end">结束时间</Label><Input id="suppression-end" type="datetime-local" value={endsAt} onChange={event => { setEndsAt(event.target.value); action.touch() }} required/></div>
    <label className="notification-checkbox"><input type="checkbox" checked={fire} onChange={event => { setFire(event.target.checked); action.touch() }}/>火警也屏蔽（显式二次确认）</label><div className="field"><Label htmlFor="suppression-reason">原因</Label><Input id="suppression-reason" maxLength={1000} value={reason} onChange={event => { setReason(event.target.value); action.touch() }} required/></div><div className="identity-form-actions"><Button type="button" variant="outline" onClick={onClose}>返回</Button><Button type="submit">核对并继续</Button></div>{action.error != null && <ErrorNotice error={action.error}/>}</> : <OperationConfirmation action={action} title="确认创建通知屏蔽" submitLabel="确认创建" refresh={onClose} warning="仅作用于所选通知目标；操作会写入审计记录。"><p>{scope} · {scope === 'DEVICES' ? `${deviceIds.length}台设备` : scope === 'SENSORS' ? `${channelIds.length}个人工通道` : tenantId}</p><p>{startsAt} → {endsAt}</p><p>原因：{reason}</p></OperationConfirmation>}</form></CardContent></Card>
}
function ChannelChoice({ channel, checked, toggle }: { channel: DeviceChannel; checked: boolean; toggle: (checked: boolean) => void }) {
  return <label className="notification-checkbox"><input type="checkbox" checked={checked} onChange={event => toggle(event.target.checked)}/>{channel.name} · {channel.channel_type} · {channel.channel_key}<span>设备 {channel.device_name || channel.device_id}</span></label>
}
function SelectionPage({ label, page, total, size, loading, error, retry, previous, next, children }: { label: string; page: number; total: number; size: number; loading: boolean; error?: unknown; retry: () => void; previous: () => void; next: () => void; children: ReactNode }) {
  return <section className="form-stack"><h3>{label} · 已选项保留跨页</h3>{loading ? <Loading/> : error != null ? <ErrorNotice error={error} retry={retry}/> : <div className="form-stack">{children}</div>}<div className="identity-form-actions"><Button type="button" variant="outline" disabled={page <= 1} onClick={previous}>上一页</Button><span>第 {page} 页 · 共 {total} 条</span><Button type="button" variant="outline" disabled={page * size >= total} onClick={next}>下一页</Button></div></section>
}

function inputTime(instant: string) {
  const date = new Date(instant)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
function MutationForm({ record, operation, onClose, saved }: { record: Suppression; operation: 'edit' | 'enable' | 'disable' | 'cancel' | 'delete'; onClose: () => void; saved: () => void }) {
  const [startsAt, setStartsAt] = useState(() => inputTime(record.starts_at)), [endsAt, setEndsAt] = useState(() => inputTime(record.ends_at)), [reason, setReason] = useState('')
  const action = useMutationAction()
  const labels = { edit: '编辑屏蔽期限', enable: '启用屏蔽', disable: '停用屏蔽', cancel: '取消屏蔽', delete: '删除过期记录' }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('必须填写变更原因')); return }
    const changedStart = startsAt !== inputTime(record.starts_at), changedEnd = endsAt !== inputTime(record.ends_at)
    const start = new Date(changedStart ? startsAt : record.starts_at), end = new Date(changedEnd ? endsAt : record.ends_at)
    if (operation === 'edit' && (!Number.isFinite(end.getTime() - start.getTime()) || end <= start || end.getTime() - start.getTime() > 90 * 86400000)) { action.setError(new Error('结束时间须晚于开始时间且间隔不超过90天。')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    const body = { expected_revision: record.revision, reason: reason.trim() }
    void action.run(async (key, signal) => {
      if (operation === 'edit') await updateSuppression(record.id, { ...body, starts_at: changedStart ? start.toISOString() : record.starts_at, ends_at: changedEnd ? end.toISOString() : record.ends_at }, key, record.tenant_id, signal)
      else if (operation === 'enable' || operation === 'disable') await setSuppressionEnabled(record.id, { ...body, enabled: operation === 'enable' }, key, record.tenant_id, signal)
      else if (operation === 'cancel') await cancelSuppression(record.id, body, key, record.tenant_id, signal)
      else await deleteSuppression(record.id, body, key, record.tenant_id, signal)
    }, saved)
  }
  return <form className="form-stack" onSubmit={submit}><h3>{labels[operation]}</h3>{!action.confirming ? <><p>目标记录：{record.id} · {record.tenant_id}</p>{operation === 'edit' && <><div className="field"><Label htmlFor="suppression-edit-start">开始时间</Label><Input id="suppression-edit-start" type="datetime-local" value={startsAt} onChange={event => { setStartsAt(event.target.value); action.touch() }}/></div><div className="field"><Label htmlFor="suppression-edit-end">结束时间</Label><Input id="suppression-edit-end" type="datetime-local" value={endsAt} onChange={event => { setEndsAt(event.target.value); action.touch() }}/></div></>}<div className="field"><Label htmlFor="suppression-mutation-reason">变更原因</Label><Input id="suppression-mutation-reason" value={reason} maxLength={1000} onChange={event => { setReason(event.target.value); action.touch() }} required/></div><div className="identity-form-actions"><Button type="button" variant="outline" onClick={onClose}>返回</Button><Button type="submit">核对并继续</Button></div>{action.error != null && <ErrorNotice error={action.error}/>}</> : <OperationConfirmation action={action} title={`确认${labels[operation]}`} submitLabel="确认操作" refresh={saved} warning="提交后服务端将校验当前版本、有效会话与操作者权限；请求使用稳定幂等键。"><p>{record.id} · {record.tenant_id} · revision {record.revision}</p><p>{reason}</p></OperationConfirmation>}</form>
}
