import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { ApiError } from '@/lib/api'
import { vendorDevicesApi, type ConfirmAepBody } from '@/lib/vendor-devices'
import { beginWriteIntent, clearWriteIntent, markWriteIntentUnknown, useWriteIntent } from '@/lib/write-intent'
import { deviceDetailApi, type DeviceEventRecord, type DeviceEventFilters } from '@/lib/device-detail'
import { emptyAlarmDraft, emptyAlarmFilters, compileAlarmDraft, type AlarmFilterDraft } from '@/lib/alarm-workbench'
import type { AlarmWorkbenchFilters, AlarmWorkbenchMetrics, Device } from '@/lib/types'
import { battery, batteryPolicyLabel, dateTime, reported, conclusions } from '@/lib/format'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from './ui/sheet'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Table, TableHeader, TableHead, TableBody, TableRow, TableCell } from './ui/table'
import { DeviceBusinessStatuses, DeviceStatusBadge } from './data-tables'
import { Empty, ErrorNotice, Loading, Pager, Status } from './common'
import { OperationConfirmation, useMutationAction } from './identity-common'
import { parameterScopeNames } from './offline-policy'
import { PageHelp } from './page-tools'
import { DeviceServicesPanel } from './device-services-panel'
import { alarmTimeText } from '@/lib/alarm-time'
import { DeviceTelemetry } from './device-telemetry'
import { DeviceFaultRecords } from './device-fault-records'
import { DataTransferActions } from './data-transfer-actions'
import { useObjectRevision } from '@/lib/object-events'
const metrics: [keyof AlarmWorkbenchMetrics, string][] = [['alarm_total', '本设备匹配报警总数'], ['unhandled', '人工未处理'], ['handled', '人工已处理'], ['unrecovered', '物理未恢复（FIRE）'], ['recovered', '物理已恢复（NORMAL）'], ['true_fire', '当前复核为真实火警']]
export function DeviceDetail({ deviceId, tenantId, close, onEdit }: { deviceId: string | null; tenantId?: string; close: () => void; onEdit?: (device: Device) => void }) {
  const { user, audience, permissions, generation } = useSession(), owner = audience === 'management' ? tenantId : user?.tenant_id, allowed = !!user && !user.must_change_password && permissions.includes('device:read') && !!owner
  return <Sheet open={!!deviceId && allowed} onOpenChange={open => { if (!open) close() }}><SheetContent className="review-sheet device-detail-sheet">{deviceId && allowed && <Inspector key={`${generation}:${deviceId}:${owner}:${permissions.join('|')}`} deviceId={deviceId} tenantId={owner!} onEdit={onEdit}/>}</SheetContent></Sheet>
}
function Inspector({ deviceId, tenantId, onEdit }: { deviceId: string; tenantId: string; onEdit?: (device: Device) => void }) {
  const canAlarms = useSession(s => s.permissions.includes('alarm:read')), canWrite = useSession(s => s.permissions.includes('device:write'))
  const management = useSession(s => s.audience === 'management')
  const [readAlarms, setReadAlarms] = useState(false), [activeTab, setActiveTab] = useState<'overview'|'telemetry'|'alarms'|'faults'|'events'|'services'>('overview')
  const canServices = useSession(s => s.permissions.includes('device-services:read'))
  const canConfirmAep = useSession(s => s.audience === 'management' && s.permissions.includes('device:write') && s.permissions.includes('integrations:write'))
  const deviceRevision = useObjectRevision('devices'), eventRevision = useObjectRevision('events'), notificationRevision = useObjectRevision('notifications')
  const detail = useResource(`device-current:${tenantId}:${deviceId}:${deviceRevision}:${eventRevision}:${notificationRevision}`, signal => deviceDetailApi.detail(deviceId, signal, tenantId)), current = detail.data
  function refresh() { setReadAlarms(false); setActiveTab('overview'); detail.refresh() }
  return <>
    <SheetHeader className="review-header"><SheetTitle>{onEdit && canWrite ? '设备详情' : '设备只读详情'}</SheetTitle><SheetDescription>{onEdit && canWrite ? '设备资料、状态与关联记录分开展示。修改资料需明确确认，不改变设备上报、旧报警或云端配置。' : '只读当前设备、手工登记分类、固定绑定元信息、有效策略及本设备关联报警；不提供无真实执行适配器的设备命令。'}</SheetDescription></SheetHeader>
    <div className="review-body device-detail-body">
      <div className="version-actions"><Button type="button" variant="outline" onClick={refresh}>刷新设备详情</Button>{onEdit && canWrite && current && !detail.loading && detail.error == null && <Button type="button" onClick={() => onEdit(current.device)}>编辑设备资料</Button>}</div>
      {detail.loading && !current ? <Loading/> : detail.error != null ? <><p className="notice">本次设备读取未确认，旧设备及关联报警已清除；不会用列表快照替代，不退出仍合法会话。</p><ErrorNotice error={detail.error} retry={refresh}/></> : !current ? <Empty title="设备当前详情未确认"/> : <>
        <section data-device-detail-id={current.device.id} data-device-detail-revision={current.device.revision}>
          <div className="alarm-detail-title"><h2>{reported(current.device.name)}</h2></div>
          <details className="device-evaluation-info"><summary>快照求值说明</summary><p className="muted-note">本次设备求值 {current.as_of} · 展示口径 {current.timezone} · {current.consumer}；ISO offset原样保留，不用浏览器时钟推算连接状态。这不是设备上报时间。</p></details>
          <div className="device-snapshot">
            <span>{management ? '用户端状态' : '状态'} <DeviceStatusBadge code={current.device.status_display?.customer_status}/></span>
            {management && <>
              <span>在线状态 <DeviceStatusBadge code={current.device.status_display?.online_status}/></span>
              <span>业务状态 <DeviceBusinessStatuses display={current.device.status_display}/></span>
            </>}
          </div>
          <div role="tablist" aria-label="设备详情分类" className="version-actions">
            <Button role="tab" aria-selected={activeTab==='overview'} type="button" variant={activeTab==='overview'?'default':'outline'} onClick={() => setActiveTab('overview')}>设备信息</Button>
            <Button role="tab" aria-selected={activeTab==='telemetry'} type="button" variant={activeTab==='telemetry'?'default':'outline'} onClick={() => setActiveTab('telemetry')}>监测数据</Button>
            <Button role="tab" aria-selected={activeTab==='alarms'} type="button" variant={activeTab==='alarms'?'default':'outline'} onClick={() => setActiveTab('alarms')}>关联报警</Button>
            <Button role="tab" aria-selected={activeTab==='faults'} type="button" variant={activeTab==='faults'?'default':'outline'} onClick={() => setActiveTab('faults')}>故障记录</Button>
            <Button role="tab" aria-selected={activeTab==='events'} type="button" variant={activeTab==='events'?'default':'outline'} onClick={() => setActiveTab('events')}>事件记录</Button>
            {canServices && <Button role="tab" aria-selected={activeTab==='services'} type="button" variant={activeTab==='services'?'default':'outline'} onClick={() => setActiveTab('services')}>服务与参数</Button>}
          </div>
          {activeTab==='overview' && <>
            <section className="offline-policy"><strong>低阈值观察：{batteryPolicyLabel(current.device.battery_policy.status)}</strong><span>{current.device.battery_policy.threshold_json ?? '未配置阈值'}{current.device.battery_policy.comparator ?? ''} · 来源 {current.device.battery_policy.source_scope} · revision {current.device.battery_policy.source_revision}</span><span>评估时刻 {current.device.battery_policy.evaluated_at}（数据库读取快照，不是上报时间）</span></section>
            <dl className="detail-list">
              <div><dt>设备ID / revision</dt><dd className="mono">{current.device.id} · {current.device.revision}</dd></div>
              <div><dt>归属单位</dt><dd>{current.device.tenant_name ?? current.device.tenant_id ?? '归属单位未知'}</dd></div>
              <div><dt>当前设备名称</dt><dd>{reported(current.device.name)}</dd></div>
              <div><dt>EUI / 标识</dt><dd className="mono">{reported(current.device.device_eui)}</dd></div>
              <div><dt>设备所存型号</dt><dd>{reported(current.device.model)}</dd></div>
              <div><dt>所属系统（人工登记）</dt><dd>{current.device.system_type ?? '尚未人工指定'}</dd></div>
              <div><dt>设备类别（人工登记）</dt><dd>{current.device.device_category ?? '尚未人工指定'}</dd></div>
              <div><dt>所属位置</dt><dd className={current.device.place_id ? 'mono' : 'text-amber-700'}>{current.device.place_name ? `${current.device.place_name} · ${current.device.place_id}` : current.device.place_id ?? '需指定实际位置'}</dd></div>
              <div><dt>当前安装地址</dt><dd>{reported(current.device.address)}</dd></div>
              <div><dt>最后有效上报</dt><dd>{current.device.last_message_at ? dateTime(current.device.last_message_at) : '未上报'}</dd></div>
              <div><dt>电量</dt><dd>{battery(current.device.battery_percent, current.device.battery_percent_json)}</dd></div>
              <div><dt>接入状态</dt><dd>{current.device.integration_state === 'CONNECTED' ? '已接入' : current.device.integration_state === 'PENDING_INTEGRATION' ? '待接入联调' : current.device.integration_state === 'LEGACY_UNVERIFIED' ? '历史接入未核验' : current.device.integration_state === 'RETIRED_SIMULATION' ? '历史模拟记录（只读溯源，不参与当前设备状态）' : '接入状态未确认'}{current.device.legacy_integration_state ? ` · 历史状态 ${current.device.legacy_integration_state}` : ''}</dd></div>
            </dl>
            {canConfirmAep && <AepOnboardingPanel deviceId={deviceId} tenantId={tenantId} name={current.device.name} refreshDevice={detail.refresh}/>}
            <h3>固定版本（绑定时保存）</h3>
            {current.device.product_version_id === null ? <p className="notice">未绑定模板，不冒充正式产品或默认最新版本。</p> : <dl className="detail-list"><div><dt>绑定时保存的产品名 / 型号</dt><dd>{reported(current.device.product_name)} / {reported(current.device.model)}</dd></div><div><dt>固定产品ID</dt><dd className="mono">{current.device.product_id}</dd></div><div><dt>固定版本</dt><dd className="mono">{current.device.product_version_id} · v{current.device.version_number}</dd></div></dl>}
            <p className="muted-note">不读取产品schema、样例、凭据、参数目录或历史审计。没有支持声明/地图/硬件接通证据，不画成功。固定绑定不会因发布新版本漂移。</p>
            <h3>有效策略</h3>
            <div className="offline-policy"><strong>有效上报超时阈值 {current.device.offline_policy.value} {current.device.offline_policy.unit}</strong><span>来源：{parameterScopeNames[current.device.offline_policy.source_scope]}（{current.device.offline_policy.source_scope}） · revision {current.device.offline_policy.source_revision}</span><span className="mono">来源ID：{current.device.offline_policy.source_id ?? '全局基础项，无scope ID'}</span></div>
            <p className="muted-note" data-device-policy-evaluated-at={current.device.offline_policy.evaluated_at}>实际评估原时刻：{current.device.offline_policy.evaluated_at}，与本次as_of同一instant；是策略求值而非设备上报。仅读时连接状态，不配业务数字、不扫描/下发/通知。</p>
          </>}
          {activeTab==='telemetry' && <DeviceTelemetry deviceId={deviceId} tenantId={tenantId}/>}
          {activeTab==='alarms' && (!canAlarms ? <p className="notice">未读取：缺独立alarm:read权限，不代表0条或无报警。</p> : !readAlarms ? <><p className="muted-note">仅设备已确认后可显式读取；报警固有名称/地址与当前设备元数据独立，不共享跨请求全局快照。</p><Button type="button" onClick={() => setReadAlarms(true)}>读取关联报警</Button></> : <RelatedAlarms key={`${tenantId}:${deviceId}`} deviceId={deviceId} tenantId={tenantId}/>)}
          {activeTab==='faults' && <DeviceFaultRecords key={`${tenantId}:${deviceId}`} deviceId={deviceId} tenantId={tenantId}/>}
          {activeTab==='events' && <RelatedEvents key={`${tenantId}:${deviceId}`} deviceId={deviceId} tenantId={tenantId}/>}
          {activeTab==='services' && <DeviceServicesPanel deviceId={deviceId} tenantId={tenantId} compact/>}
        </section>
      </>}
    </div>
  </>
}

function AepOnboardingPanel({ deviceId, tenantId, name, refreshDevice }: { deviceId: string; tenantId: string; name?: string | null; refreshDevice: () => void }) {
  const scope = `aep-onboarding:${tenantId}:${deviceId}`
  const resource = useResource(scope, signal => vendorDevicesApi.aepOnboarding(deviceId, tenantId, signal))
  const intent = useWriteIntent<ConfirmAepBody>(scope)
  const [upstream, setUpstream] = useState('')
  const [draft, setDraft] = useState<ConfirmAepBody | null>(null)
  const [notice, setNotice] = useState('')
  const action = useMutationAction()

  function reset() { setDraft(null); action.touch(); resource.refresh(); refreshDevice() }
  function send(payload: ConfirmAepBody, key: string) {
    void action.run(async () => {
      try {
        await vendorDevicesApi.confirmAep(deviceId, payload, key, tenantId)
        clearWriteIntent(scope, key)
      } catch (error) {
        if (error instanceof ApiError && error.status > 0 && error.status < 500) clearWriteIntent(scope, key)
        else markWriteIntentUnknown(scope, key)
        throw error
      }
    }, () => {
      reset(); setUpstream(''); setNotice('关联已登记。请让设备再次上报，再核对最后上报时间与事件记录。')
    })
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (intent || action.busy || !resource.data?.imei) return
    if (!action.confirming) {
      if (!/^[\x21-\x7e]{1,128}$/.test(upstream.trim())) { action.setError(new Error('请输入运营商提供的 deviceId')); return }
      setDraft({ upstream_device_id: upstream.trim(), expected_revision: resource.data.revision })
      action.setError(undefined); action.setConfirming(true)
      return
    }
    if (!draft) return
    try {
      const pending = beginWriteIntent(scope, draft, draft.upstream_device_id)
      send(pending.payload, pending.key)
    } catch (error) { action.setError(error) }
  }
  if (intent) return <section aria-label="AEP 上报关联待核对">
    <p role="status" className="notice">AEP 上报关联未确认：{intent.label}。可以用原请求核对，不会重复登记。</p>
    {action.error != null && <ErrorNotice error={action.error}/>}
    <Button type="button" disabled={action.busy} onClick={() => send(intent.payload, intent.key)}>核对原关联请求</Button>
  </section>
  if (resource.error != null) return <ErrorNotice error={resource.error} retry={resource.refresh}/>
  if (!resource.data?.imei) return null
  return <details className="device-filter-drawer" open={action.confirming || notice ? true : undefined}>
    <summary>AEP 上报关联{resource.data.upstream_ids.length > 0 ? `（已核对 ${resource.data.upstream_ids.length} 项）` : ''}</summary>
    <form className="form-stack" onSubmit={submit}>
      {notice && <p role="status" className="notice">{notice}</p>}
      <p>设备 IMEI：<span className="mono">{resource.data.imei}</span></p>
      {resource.data.upstream_ids.length > 0 && <ul>{resource.data.upstream_ids.map(id => <li className="mono" key={id}>{id}</li>)}</ul>}
      {action.confirming && draft
        ? <OperationConfirmation action={action} title="二次确认 · 关联 AEP 上报" submitLabel="确认关联" refresh={reset}
          warning="只接收关联后的新报文，不回放旧报警，不修改设备归属。">
          <p>设备：{name ?? deviceId} · IMEI {resource.data.imei}</p><p>AEP deviceId：{draft.upstream_device_id}</p>
        </OperationConfirmation>
        : <>
          <p className="muted-note">先让设备自检上报。平台会核对最近 15 分钟内未关联报文的 IMEI；不会在运营商侧开户。</p>
          <div className="field"><Label htmlFor="aep-upstream-device">运营商 AEP deviceId</Label><Input id="aep-upstream-device" value={upstream} maxLength={128} required onChange={event => { setUpstream(event.target.value); setDraft(null); action.touch() }}/></div>
          {action.error != null && <ErrorNotice error={action.error}/>}
          <Button type="submit">核对上报关联</Button>
        </>}
    </form>
  </details>
}
const eventKindLabels: Record<string, string> = { HEARTBEAT: '心跳', ALARM: '报警', FAULT: '故障', OFFLINE: '离线报告', PARAM: '监测上报', OTHER: '其他记录' }
function eventName(event: DeviceEventRecord) { return event.event_label ?? (event.method === 'HIKVISION_CURRENT_MONITOR' ? '监测快照' : event.method === 'GENERIC_HTTP_JSON_V1' ? '接入报文' : eventKindLabels[event.kind] ?? event.kind) }

function RelatedEvents({ deviceId, tenantId }: { deviceId: string; tenantId: string }) {
  const [page, setPage] = useState(1)
  const [draft, setDraft] = useState<DeviceEventFilters>({ kind: '', from: '', to: '' }), [applied, setApplied] = useState<DeviceEventFilters>({ kind: '', from: '', to: '' })
  const [error, setError] = useState<unknown>()
  const filters: DeviceEventFilters = { kind: applied.kind, from: applied.from ? `${applied.from}+08:00` : '', to: applied.to ? `${applied.to}+08:00` : '' }
  const events = useResource(`device-events:${tenantId}:${deviceId}:${page}:${JSON.stringify(filters)}`, signal => deviceDetailApi.events(deviceId, filters, page, undefined, signal, tenantId))
  const eventRevision = useObjectRevision('events'), previousEventRevision = useRef(eventRevision)
  useEffect(() => {
    if (previousEventRevision.current === eventRevision) return
    previousEventRevision.current = eventRevision
    events.refresh()
  }, [eventRevision, events.refresh])
  function apply(event: FormEvent) {
    event.preventDefault()
    if (draft.from && draft.to && Date.parse(`${draft.from}+08:00`) >= Date.parse(`${draft.to}+08:00`)) { setError(new Error('结束时间必须晚于开始时间')); return }
    setError(undefined); setApplied({ ...draft }); setPage(1)
  }
  function clear() { setDraft({ kind: '', from: '', to: '' }); setApplied({ kind: '', from: '', to: '' }); setPage(1); setError(undefined) }
  return <section className="device-events-panel" aria-label="本设备事件记录">
    <div className="section-header"><h3>事件记录</h3><div className="notification-actions"><Button type="button" variant="outline" size="sm" onClick={events.refresh}>刷新事件记录</Button><PageHelp title="事件记录的证据边界"><p>这里展示本设备已保存的事件，不表示本平台曾下发对应命令。复位、静音等事件本身也不等于报警恢复或人工处置。</p><p>事件时间是来源提供的观察时间，服务接收时间单独展示；没有来源时间时保留未知，不用接收时间补造。未映射类型保留原始事件码。</p><p>原始报文不随本页返回；“记录状态”不是设备物理状态或通知送达状态。</p></PageHelp></div></div>
    <form onSubmit={apply}>
      <div className="device-detail-grid">
        <div className="field"><Label htmlFor="device-event-kind">事件类型</Label><select id="device-event-kind" className="native-select" value={draft.kind} onChange={event => setDraft(old => ({ ...old, kind: event.target.value as DeviceEventFilters['kind'] }))}><option value="">全部事件</option>{Object.entries(eventKindLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
        <div className="field"><Label htmlFor="device-event-from">事件开始时间（北京时间，含）</Label><Input id="device-event-from" type="datetime-local" step="1" value={draft.from} onChange={event => setDraft(old => ({ ...old, from: event.target.value }))}/></div>
        <div className="field"><Label htmlFor="device-event-to">事件结束时间（北京时间，不含）</Label><Input id="device-event-to" type="datetime-local" step="1" value={draft.to} onChange={event => setDraft(old => ({ ...old, to: event.target.value }))}/></div>
      </div>
      <div className="version-actions"><Button type="submit">查询事件记录</Button><Button type="button" variant="outline" onClick={clear}>清空事件条件</Button></div>
    </form>
    <div className="version-actions"><DataTransferActions kind="EVENTS" label="导出本设备事件记录" filters={{ unit_ids: tenantId, device_id: deviceId, kind: filters.kind, occurred_from: filters.from, occurred_to: filters.to }}/></div>
    {error != null && <ErrorNotice error={error}/>}
    {(draft.kind !== applied.kind || draft.from !== applied.from || draft.to !== applied.to) && <p className="muted-note">事件条件尚未应用，翻页和导出仍沿用上次查询条件。</p>}
    {events.loading ? <Loading/> : events.error != null ? <ErrorNotice error={events.error} retry={events.refresh}/> : events.data ? <>
      {events.data.items.length ? <Table><TableHeader><TableRow><TableHead>事件时间</TableHead><TableHead>事件</TableHead><TableHead>服务接收时间</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>
        {events.data.items.map(event => <TableRow key={event.id} data-device-event-id={event.id}>
          <TableCell title={event.observed_at ?? undefined}>{event.observed_at ? dateTime(event.observed_at) : '未知（来源未提供）'}</TableCell>
          <TableCell>{eventName(event)}{event.event_code !== null && <div className="table-sub mono">事件码 {event.event_code}</div>}</TableCell>
          <TableCell title={event.received_at ?? undefined}>{event.received_at ? dateTime(event.received_at) : '未知'}</TableCell>
          <TableCell><PageHelp title="事件记录详情" label="记录详情"><dl className="detail-list"><div><dt>事件</dt><dd>{eventName(event)}</dd></div><div><dt>事件码</dt><dd>{event.event_code ?? '未提供'}</dd></div><div><dt>记录类别</dt><dd>{eventKindLabels[event.kind] ?? event.kind}</dd></div><div><dt>记录状态</dt><dd>{event.status ?? '未提供'}</dd></div><div><dt>来源方法</dt><dd className="mono">{event.method ?? '未提供'}</dd></div><div><dt>事件时间</dt><dd>{event.observed_at ?? '未知（来源未提供）'}</dd></div><div><dt>服务接收时间</dt><dd>{event.received_at ?? '未知'}</dd></div><div><dt>入库时间</dt><dd>{event.created_at}</dd></div><div><dt>记录编号</dt><dd className="mono">{event.id}</dd></div></dl></PageHelp></TableCell>
        </TableRow>)}
      </TableBody></Table> : <Empty title="暂无符合条件的设备事件"/>}
      <Pager page={events.data.page} size={events.data.size} total={events.data.total} onChange={setPage}/>
    </> : null}
  </section>
}

function RelatedAlarms({ deviceId, tenantId }: { deviceId: string; tenantId: string }) {
  const [page, setPage] = useState(1), [size, setSize] = useState<number | undefined>(), [draft, setDraft] = useState<AlarmFilterDraft>({ ...emptyAlarmDraft }), [appliedDraft, setAppliedDraft] = useState({ ...emptyAlarmDraft }), [applied, setApplied] = useState<AlarmWorkbenchFilters>({ ...emptyAlarmFilters }), [error, setError] = useState<unknown>()
  const alarms = useResource(`device-alarms:${tenantId}:${deviceId}:${JSON.stringify([applied, page, size])}`, signal => deviceDetailApi.alarms(deviceId, applied, page, size, signal, tenantId))
  const eventRevision = useObjectRevision('events'), previousEventRevision = useRef(eventRevision)
  useEffect(() => {
    if (previousEventRevision.current === eventRevision) return
    previousEventRevision.current = eventRevision
    alarms.refresh()
  }, [eventRevision, alarms.refresh])
  const shown = alarms.data?.filters ?? applied
  function update(field: keyof AlarmFilterDraft, value: string) { setDraft(old => ({ ...old, [field]: value })); setError(undefined) }
  function apply(event: FormEvent) { event.preventDefault(); try { setApplied(compileAlarmDraft(draft)); setAppliedDraft({ ...draft }); setPage(1); setError(undefined); alarms.refresh() } catch (error) { setError(error) } }
  function clear() { setDraft({ ...emptyAlarmDraft }); setAppliedDraft({ ...emptyAlarmDraft }); setApplied({ ...emptyAlarmFilters }); setPage(1); setError(undefined); alarms.refresh() }
  return <div className="device-related-alarms">
    <div className="version-actions"><Button type="button" variant="outline" onClick={alarms.refresh}>刷新关联报警</Button>{!alarms.loading && alarms.error == null && alarms.data && <DataTransferActions kind="ALARMS" label="导出本设备关联报警" filters={{ unit_ids: tenantId, device_id: deviceId, ...applied }}/>}</div>
    <p className="notice">关联范围仅路径设备ID；以下快照/统计是本次独立报警查询，不是当前设备状态。复核、恢复、历史、领取与通知均不在本只读详情操作。</p>
    <form onSubmit={apply} className="device-alarm-filter"><div className="device-detail-grid"><div className="field"><Label htmlFor="device-alarm-keyword">关联报警关键字</Label><Input id="device-alarm-keyword" value={draft.keyword} onChange={e => update('keyword', e.target.value)} placeholder="报警ID/固有名称/地址字面查询"/></div>{([['physical_state', '关联报警物理状态', [['FIRE', '未恢复（FIRE）'], ['NORMAL', '已恢复（NORMAL）']]], ['response_state', '关联报警人工状态', [['UNHANDLED', '未处理（UNHANDLED）'], ['HANDLED', '已处理（HANDLED）']]], ['conclusion', '关联报警复核结论', [['UNREVIEWED', '未复核（结论NULL）'], ...conclusions]]] as const).map(([key, label, options]) => <div className="field" key={key}><Label htmlFor={`device-alarm-${key}`}>{label}</Label><select className="native-select" id={`device-alarm-${key}`} value={draft[key]} onChange={e => update(key, e.target.value)}><option value="">全部</option>{options.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>)}{([['occurred_from', '关联报警开始时间（北京时间）'], ['occurred_to', '关联报警结束时间（北京时间）']] as const).map(([key, label]) => <div className="field" key={key}><Label htmlFor={`device-alarm-${key}`}>{label}</Label><Input id={`device-alarm-${key}`} type="text" autoComplete="off" placeholder="YYYY-MM-DDTHH:mm:ss.ffffff" value={draft[key]} onChange={e => update(key, e.target.value)}/></div>)}</div><p className="muted-note">北京时间精确文本YYYY-MM-DDTHH:mm[:ss.ffffff]，校验后明确+08:00（不受原生日期控件毫秒限制）：[开始含，结束不含)，无默认日期，所有条件AND。</p><div className="version-actions"><Button type="submit">查询关联报警</Button><Button type="button" variant="outline" onClick={clear}>清空关联报警条件</Button><label>每页关联报警条数<select aria-label="每页关联报警条数" className="native-select" value={size ?? ''} onChange={e => { setSize(e.target.value ? Number(e.target.value) : undefined); setPage(1) }}><option value="">服务端默认条数</option>{[20, 50, 100].map(n => <option key={n} value={n}>{n}</option>)}</select></label></div></form>
    {JSON.stringify(draft) !== JSON.stringify(appliedDraft) && <p className="notice">关联草稿未应用，翻页和导出仍沿已应用条件。</p>}
    {error != null && <ErrorNotice error={error}/>}
    <p className="muted-note">{alarms.data ? '服务端实际应用条件' : '当前请求条件（等待确认）'}：关键字 {shown.keyword || '无条件'} · 物理 {shown.physical_state ?? '全部'} · 人工 {shown.response_state ?? '全部'} · 结论 {shown.conclusion ?? '全部'} · 开始含 {shown.occurred_from ?? '无'} · 结束不含 {shown.occurred_to ?? '无'}</p>{alarms.loading ? <Loading/> : alarms.error != null ? <ErrorNotice error={alarms.error} retry={alarms.refresh}/> : !alarms.data ? <Empty title="关联报警未确认"/> : <><p className="muted-note">独立报警快照 {alarms.data.as_of} · {alarms.data.timezone} · FILTERED_ALL_PAGES（本设备所有匹配页，不是当前页累计）</p><div className="device-detail-metrics">{metrics.map(([key, label]) => <div key={key}><span>{label}</span><strong data-device-alarm-metric={key}>{alarms.data!.metrics[key]}</strong></div>)}</div>{alarms.data.items.length ? <Table><TableHeader><TableRow><TableHead>报警ID / 固有名称与地址快照</TableHead><TableHead>物理 / 人工状态</TableHead><TableHead>发生时间 / revision</TableHead></TableRow></TableHeader><TableBody>{alarms.data.items.map(row => <TableRow key={row.id} data-device-alarm-id={row.id}><TableCell className="device-alarm-snapshot"><strong className="mono">{row.id}</strong><p>{reported(row.device_name)}</p><p>{reported(row.address)}</p></TableCell><TableCell><Status value={row.physical_state} dimension="physical"/><Status value={row.response_state} dimension="response"/><p>{row.conclusion ?? '未复核（结论NULL）'}</p></TableCell><TableCell>{alarmTimeText(row)}<p>revision {row.revision}</p></TableCell></TableRow>)}</TableBody></Table> : <Empty title={alarms.data.total ? '当前报警页为空，匹配总量非0' : '本设备无匹配报警'}/>}<Pager page={alarms.data.page} size={alarms.data.size} total={alarms.data.total} onChange={setPage}/></>}
  </div>
}
