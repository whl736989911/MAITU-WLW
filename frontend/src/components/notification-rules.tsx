import { useEffect, useId, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import type { Paginated, ScopeUnit } from '@/lib/types'
import { notificationApi, notificationRuleScopeTypes, notificationRuleWriteUncertain, type NotificationChannel, type NotificationContact, type NotificationEffectiveQuery, type NotificationEffectiveReceivers, type NotificationEventScope, type NotificationRule, type NotificationRuleInput, type NotificationRuleReceiver, type NotificationRuleScope, type NotificationRuleScopeType } from '@/lib/notifications'
import type { NotificationRuleTargets } from '@/lib/notifications'
import { customerAssetsApi } from '@/lib/customer-assets'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { dateTime } from '@/lib/format'
import { Label } from './ui/label'
import { Card, CardContent } from './ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from './common'
import { OperationConfirmation, useMutationAction } from './identity-common'
import { api } from '@/lib/api'
import { PageHelp } from './page-tools'
import { wechatApi, type WechatChannel, type WechatRecipient } from '@/lib/wechat-official-account'

type Selection = { rule: NotificationRule } | { create: true } | null
type ReceiverChoice = NotificationRuleReceiver
interface UnitDirectory { data?: ScopeUnit[]; loading: boolean; error?: unknown; refresh: () => void }
const channelNames: Record<NotificationChannel, string> = { SMS: '短信', VOICE: '语音', WECHAT: '公众号' }
const scopeNames: Record<NotificationRuleScopeType, string> = { UNIT: '单位 UNIT', PLACE: '位置 PLACE', DEVICE: '设备 DEVICE' }

export type RuleScopeDraft = { type: NotificationRuleScopeType | ''; id: string }
export function ruleScopeFromDraft({ type, id }: RuleScopeDraft, tenantId: string): NotificationRuleScope | undefined {
  const targetId = type === 'UNIT' ? tenantId : id.trim()
  return type && tenantId && targetId ? { scope_type: type, scope_id: targetId } : undefined
}
export function ruleScopeLabel({ scope_type, scope_id }: NotificationRuleScope) {
  return `${scopeNames[scope_type]} · ${scope_id}`
}

function UnitSelect({ label, tenantId, units, optional = false, disabled = false, onChange }: { label: string; tenantId: string; units: ScopeUnit[]; optional?: boolean; disabled?: boolean; onChange: (value: string) => void }) {
  const id = useId()
  return <div className="field"><Label htmlFor={id}>{label}</Label><select id={id} className="native-select" required={!optional} value={tenantId} disabled={disabled} onChange={event => onChange(event.target.value)}>
    <option value="">{optional ? '左侧读取范围内全部单位' : '请明确选择单位'}</option>
    {tenantId && !units.some(unit => unit.id === tenantId) && <option value={tenantId}>{tenantId}</option>}
    {units.map(unit => <option key={unit.id} value={unit.id}>{unit.name} · {unit.id}</option>)}
  </select></div>
}

function TargetIdField({ type, tenantId, value, unit, disabled = false, onChange }: { type: 'PLACE' | 'DEVICE'; tenantId: string; value: string; unit?: ScopeUnit; disabled?: boolean; onChange: (value: string) => void }) {
  const id = useId()
  const session = useSession()
  const customer = session.audience === 'customer'
  const [keyword, setKeyword] = useState(''), [page, setPage] = useState(1)
  const places = useResource(`notification-rule-places:${tenantId}:${session.scopeTreeRevision}`, signal => customerAssetsApi.places(signal), !!tenantId && customer && type === 'PLACE' && session.permissions.includes('units:read'))
  const devices = useResource(`notification-rule-devices:${tenantId}:${page}:${keyword}`, signal => api.devices(page, keyword.trim(), 20, signal, undefined, undefined, customer ? undefined : tenantId), !!tenantId && type === 'DEVICE' && session.permissions.includes('device:read'))
  const directory = type === 'PLACE' ? places : devices
  const entries = type === 'PLACE' ? (customer ? places.data : unit?.places) : devices.data?.items
  const options = entries?.filter(entry => entry.tenant_id === tenantId) ?? []
  const deviceDirectory = type === 'DEVICE' && !!tenantId && session.permissions.includes('device:read')
  return <>
    <div className="field"><Label htmlFor={id}>{type === 'PLACE' ? '位置 ID' : '设备 ID'}</Label><Input id={id} required value={value} disabled={disabled} onChange={event => onChange(event.target.value)} placeholder="选择下方目录记录或输入准确 ID"/></div>
    {deviceDirectory && <div className="field"><Label htmlFor={`${id}-search`}>搜索设备目录</Label><Input id={`${id}-search`} value={keyword} disabled={disabled} onChange={event => { setKeyword(event.target.value); setPage(1) }} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }} placeholder="按名称、设备编号或地址查询"/></div>}
    {directory.loading ? <small role="status" className="muted-note">正在读取目录…</small> : directory.error != null ? <div className="muted-note">目录读取失败；仍可填写已知的准确 ID。 <Button type="button" variant="outline" size="sm" onClick={directory.refresh}>重试目录</Button></div> : options.length > 0 && <div className="field">
      <Label htmlFor={`${id}-directory`}>从{type === 'PLACE' ? '位置' : '设备'}目录选择</Label>
      <select id={`${id}-directory`} className="native-select" value={options.some(entry => entry.id === value) ? value : ''} disabled={disabled} onChange={event => { if (event.target.value) onChange(event.target.value) }}>
        <option value="">请选择本单位记录</option>
        {options.map(entry => <option value={entry.id} key={entry.id}>{entry.name || '未命名'} · {entry.id}</option>)}
      </select>
    </div>}
    {type === 'DEVICE' && !devices.loading && devices.data && devices.data.total > devices.data.size && <Pager page={page} size={devices.data.size} total={devices.data.total} onChange={setPage}/>}
  </>
}

export function RuleScopeFields({ value, tenantId, unit, label = '规则作用域', optional = false, disabled = false, onChange }: { value: RuleScopeDraft; tenantId: string; unit?: ScopeUnit; label?: string; optional?: boolean; disabled?: boolean; onChange: (value: RuleScopeDraft) => void }) {
  const id = useId()
  return <>
    <div className="field"><Label htmlFor={id}>{label}</Label><select id={id} className="native-select" value={value.type} disabled={disabled || !tenantId} onChange={event => onChange({ type: event.target.value as RuleScopeDraft['type'], id: '' })}>
      {optional && <option value="">不限作用域</option>}
      {notificationRuleScopeTypes.map(type => <option key={type} value={type}>{scopeNames[type]}</option>)}
    </select></div>
    {value.type === 'UNIT' && tenantId && <div className="muted-note">目标单位 ID：{tenantId}</div>}
    {(value.type === 'PLACE' || value.type === 'DEVICE') && <TargetIdField key={`${tenantId}:${value.type}`} type={value.type} tenantId={tenantId} value={value.id} unit={unit} disabled={disabled} onChange={id => onChange({ type: value.type, id })}/>}
  </>
}

type TargetBrowse = { keyword: string; page: number }
const firstTargetPage: TargetBrowse = { keyword: '', page: 1 }

function useRuleTargets(tenantId: string, type: NotificationRuleScopeType, browse: TargetBrowse, enabled: boolean, targetId?: string, revision = 0) {
  const keyword = browse.keyword.trim()
  return useResource(JSON.stringify(['notification-rule-targets', tenantId, type, browse.page, keyword, targetId, revision]),
    signal => notificationApi.targets(tenantId, type, browse.page, keyword, targetId, signal), enabled && !!tenantId)
}

type RuleTargetsResource = { data?: NotificationRuleTargets; loading: boolean; error?: unknown; refresh: () => void }

function RuleTargetStatus({ directory }: { directory: RuleTargetsResource }) {
  if (directory.loading) return <small role="status" className="muted-note">正在确认可用目标…</small>
  if (directory.error != null) return <ErrorNotice error={directory.error} retry={directory.refresh}/>
  if (directory.data?.access === 'NONE') return <p className="notice" role="status">当前单位无可用设备范围，此操作不可用。</p>
  return null
}

function ManagementTargetPicker({ type, directory, browse, setBrowse, value, onChange, disabled = false }: {
  type: 'PLACE' | 'DEVICE'; directory: RuleTargetsResource; browse: TargetBrowse; setBrowse: (value: TargetBrowse) => void;
  value: string; onChange: (value: string) => void; disabled?: boolean
}) {
  const id = useId()
  const kind = type === 'PLACE' ? '位置' : '设备'
  const data = directory.loading ? undefined : directory.data
  const items = data?.items ?? []
  return <>
    <div className="field"><Label htmlFor={`${id}-search`}>搜索{kind}目标</Label>
      <Input id={`${id}-search`} value={browse.keyword} disabled={disabled} placeholder="按名称或 ID 查询"
        onChange={event => setBrowse({ keyword: event.target.value, page: 1 })}
        onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }}/></div>
    <RuleTargetStatus directory={directory}/>
    {data && data.access !== 'NONE' && <>
      <div className="field"><Label htmlFor={id}>{kind} ID</Label>
        <select id={id} required className="native-select" value={value} disabled={disabled || (!items.length && !value)} onChange={event => onChange(event.target.value)}>
          <option value="">{items.length ? `请选择${kind}` : `无匹配的${kind}`}</option>
          {value && !items.some(item => item.id === value) && <option value={value}>已选 · {value}</option>}
          {items.map(item => <option key={item.id} value={item.id}>{item.name || '未命名'} · {item.id}</option>)}
        </select></div>
      {data.total > data.size && <Pager page={browse.page} size={data.size} total={data.total} onChange={page => setBrowse({ ...browse, page })}/>}
    </>}
  </>
}

function ManagementRuleScopeFields({ value, tenantId, available, disabled, onChange }: {
  value: RuleScopeDraft; tenantId: string; available: readonly NotificationRuleScopeType[]; disabled: boolean;
  onChange: (value: RuleScopeDraft) => void
}) {
  const id = useId()
  return <div className="field"><Label htmlFor={id}>规则作用域</Label>
    <select id={id} className="native-select" required value={value.type && available.includes(value.type) ? value.type : ''} disabled={disabled || !available.length} onChange={event => onChange({ type: event.target.value as RuleScopeDraft['type'], id: '' })}>
      <option value="">请选择作用域</option>
      {available.map(type => <option key={type} value={type}>{scopeNames[type]}</option>)}
    </select>
    {value.type === 'UNIT' && available.includes('UNIT') && <small className="muted-note">目标单位 ID：{tenantId}</small>}
  </div>
}

export function EffectiveNotificationContacts({ units, fixedTenantId, revision, load }: { units?: ScopeUnit[]; fixedTenantId?: string; revision: number; load: (query: NotificationEffectiveQuery, signal: AbortSignal) => Promise<NotificationEffectiveReceivers> }) {
  const eventId = useId()
  const [tenant, setTenant] = useState(''), [deviceId, setDeviceId] = useState(''), [eventScope, setEventScope] = useState<NotificationEventScope>('FIRE')
  const [query, setQuery] = useState<NotificationEffectiveQuery | null>(null)
  const tenantId = fixedTenantId ?? tenant
  const management = fixedTenantId === undefined
  const [browse, setBrowse] = useState(firstTargetPage)
  const directory = useRuleTargets(tenantId, 'DEVICE', browse, management, undefined, revision)
  const previewAllowed = !management || (!directory.loading && directory.data !== undefined && directory.data.access !== 'NONE')
  const effective = useResource(`notification-effective:${revision}:${JSON.stringify(query)}`, signal => load(query!, signal), query !== null)
  return <section aria-label="设备生效联系人"><Card className="list-card"><CardContent>
    <h2>设备生效联系人</h2>
    <p className="muted-note">按设备和事件查看服务端计算结果。不同作用域可同时贡献联系人；收件顺序和延时以返回结果为准。查询不会发送通知，也不代表已送达。</p>
    <form className="list-toolbar" onSubmit={event => {
      event.preventDefault()
      const target = deviceId.trim()
      if (!tenantId || !target || !previewAllowed) return
      if (query?.tenantId === tenantId && query.deviceId === target && query.eventScope === eventScope) effective.refresh()
      else setQuery({ tenantId, deviceId: target, eventScope })
    }}>
      {management ? <>
        <UnitSelect label="查询单位" units={units ?? []} tenantId={tenantId} onChange={value => { setTenant(value); setBrowse(firstTargetPage); setDeviceId(''); setQuery(null) }}/>
        {tenantId && <ManagementTargetPicker type="DEVICE" directory={directory} browse={browse} setBrowse={setBrowse} value={deviceId} onChange={value => { setDeviceId(value); setQuery(null) }}/>}
      </> : <TargetIdField key={tenantId} type="DEVICE" tenantId={tenantId} value={deviceId} disabled={!tenantId} onChange={value => { setDeviceId(value); setQuery(null) }}/>}
      <div className="field"><Label htmlFor={eventId}>事件类型</Label><select id={eventId} className="native-select" value={eventScope} onChange={event => { setEventScope(event.target.value as NotificationEventScope); setQuery(null) }}><option value="FIRE">火警 FIRE</option><option value="FAULT">故障 FAULT</option></select></div>
      <Button type="submit" variant="outline" disabled={!tenantId || !deviceId.trim() || !previewAllowed}>查看生效联系人</Button>
    </form>
    {previewAllowed && (effective.loading ? <Loading/> : effective.error != null ? <ErrorNotice error={effective.error} retry={effective.refresh}/> : effective.data && <>
      <p className="muted-note">单位 {effective.data.tenant_id} · 设备 {effective.data.device_id} · {effective.data.event_scope === 'FIRE' ? '火警 FIRE' : '故障 FAULT'}</p>
      {effective.data.receivers.length === 0 ? <Empty title="该设备无生效联系人"/> : <Table>
        <TableHeader><TableRow><TableHead>联系人</TableHead><TableHead>渠道 / 级别</TableHead><TableHead>延时</TableHead><TableHead>来源作用域 / 规则</TableHead></TableRow></TableHeader>
        <TableBody>{effective.data.receivers.map(receiver => <TableRow key={JSON.stringify([receiver.channel, receiver.contact_id, receiver.source_rule_id, receiver.level])}>
          <TableCell>{receiver.contact_name}<div className="table-sub">{receiver.phone_masked}</div></TableCell>
          <TableCell>{channelNames[receiver.channel]} · {receiver.level}级</TableCell><TableCell>{receiver.delay_seconds} 秒</TableCell>
          <TableCell>{ruleScopeLabel({ scope_type: receiver.source_scope_type, scope_id: receiver.source_scope_id })}<div className="table-sub">规则 {receiver.source_rule_id} · revision {receiver.source_revision}</div></TableCell>
        </TableRow>)}</TableBody>
      </Table>}
    </>)}
  </CardContent></Card></section>
}

export function NotificationRules(props: { canRead: boolean; canWrite: boolean }) {
  const generation = useSession(session => session.generation)
  return <ManagementNotificationRules key={generation} {...props}/>
}

function ManagementNotificationRules({ canRead, canWrite }: { canRead: boolean; canWrite: boolean }) {
  const session = useSession()
  const scopeKey = `${session.generation}:${session.scopeInitialized}:${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}`
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [draft, setDraft] = useState(''), [selection, setSelection] = useState<Selection>(null)
  const [filterTenant, setFilterTenant] = useState(''), [filterDraft, setFilterDraft] = useState<RuleScopeDraft>({ type: '', id: '' })
  const [filter, setFilter] = useState<{ tenantId?: string; scope?: NotificationRuleScope }>({})
  const [previewRevision, setPreviewRevision] = useState(0)
  const tree = useResource(`notification-rule-target-units:${session.scopeTreeRevision}`, signal => api.scopeTree(signal), canRead)
  const units = tree.data ?? []
  const rows = useResource<Paginated<NotificationRule>>(`notification-rules:${scopeKey}:${page}:${keyword}:${JSON.stringify(filter)}`, signal => notificationApi.rules(page, keyword, signal, filter.scope, filter.tenantId), canRead && session.scopeInitialized)
  useEffect(() => { setPage(1) }, [scopeKey])
  const refresh = () => { rows.refresh(); setPreviewRevision(value => value + 1) }
  const saved = () => { setSelection(null); refresh() }
  if (!canRead) return <Empty title="当前账户无真实通知规则读取权限"/>
  return <section aria-label="报警与故障收件规则">
    <PageHeading eyebrow="NOTIFICATION · 通知规则" title="报警与故障通知规则" description="按单位、位置或设备配置火警与故障的收件人和升级间隔。">
      <RefreshButton onClick={refresh}/>{canWrite && <Button type="button" onClick={() => setSelection({ create: true })}>新建收件规则</Button>}
      <PageHelp title="通知规则与事件状态"><p>火警与故障分别配置并显式启用。这里的作用域指定规则匹配目标，不代表权限；收件级别只代表同一规则内的渠道升级顺序。</p><p>规则只控制软件的外部通知出口，不删除故障历史、不改变设备物理恢复状态。故障规则不会套用火警规则；保存或查询都不会发送测试通知。</p></PageHelp>
    </PageHeading>
    <Card className="list-card"><CardContent>
      <form aria-label="筛选收件规则" className="list-toolbar" onSubmit={event => {
        event.preventDefault()
        const scope = ruleScopeFromDraft(filterDraft, filterTenant)
        if (filterDraft.type && !scope) return
        setFilter({ tenantId: filterTenant || undefined, scope }); setKeyword(draft.trim()); setPage(1)
      }}>
        <div className="field"><Label htmlFor="notification-rule-search">规则名称 / ID</Label><Input id="notification-rule-search" value={draft} onChange={event => setDraft(event.target.value)} placeholder="按名称或ID查询"/></div>
        <UnitSelect label="筛选单位" units={units} tenantId={filterTenant} optional onChange={value => { setFilterTenant(value); setFilterDraft({ type: '', id: '' }) }}/>
        <RuleScopeFields label="筛选规则作用域" value={filterDraft} tenantId={filterTenant} unit={units.find(unit => unit.id === filterTenant)} optional onChange={setFilterDraft}/>
        <Button type="submit" variant="outline">查询</Button>
      </form>
      {rows.loading ? <Loading/> : rows.error != null ? <ErrorNotice error={rows.error} retry={rows.refresh}/> : rows.data?.items.length ? <>
        <Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>类型 / 规则</TableHead><TableHead>作用域 / 目标</TableHead><TableHead>短信收件级别</TableHead><TableHead>语音收件级别</TableHead><TableHead>公众号收件级别</TableHead><TableHead>状态 / 版本</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
          <TableBody>{rows.data.items.map(rule => <TableRow key={rule.id}>
            <TableCell>{rule.tenant_name}<div className="table-sub mono">{rule.tenant_id}</div></TableCell>
            <TableCell>{rule.event_scope === 'FIRE' ? '火警 FIRE' : '故障 FAULT'} · {rule.name}<div className="table-sub mono">{rule.id}</div></TableCell>
            <TableCell>{ruleScopeLabel(rule)}</TableCell>
            <TableCell>{receiverSummary(rule, 'SMS')}</TableCell><TableCell>{receiverSummary(rule, 'VOICE')}</TableCell><TableCell>{receiverSummary(rule, 'WECHAT')}</TableCell>
            <TableCell>{rule.enabled ? '已启用' : '已停用'} · revision {rule.revision}</TableCell>
            <TableCell><Button type="button" variant="outline" size="sm" onClick={() => setSelection({ rule })}>{canWrite ? '编辑规则' : '查看规则'}</Button></TableCell>
          </TableRow>)}</TableBody>
        </Table>
        <Pager page={rows.data.page} size={rows.data.size} total={rows.data.total} onChange={setPage}/>
      </> : <Empty title="暂无真实报警收件规则">只列出读取范围和筛选条件匹配的规则；新建时在表单中另选写入目标。</Empty>}
    </CardContent></Card>
    <EffectiveNotificationContacts units={units} revision={previewRevision} load={notificationApi.effective}/>
    <Sheet open={selection !== null} onOpenChange={open => { if (!open) setSelection(null) }}><SheetContent className="review-sheet notification-sheet">{selection && <RuleEditor key={'create' in selection ? 'create' : `${selection.rule.tenant_id}:${selection.rule.id}`} selection={selection} tree={tree} canWrite={canWrite} close={() => setSelection(null)} saved={saved}/>}</SheetContent></Sheet>
  </section>
}

function receiverSummary(rule: NotificationRule, channel: NotificationChannel) {
  const receivers = rule.receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level)
  if (!receivers.length) return channel === 'WECHAT' ? <span className="muted">未配置</span> : null
  return <div className="form-stack">{receivers.map(receiver => <div key={`${receiver.channel}:${receiver.level}`}><strong>{receiver.level}级</strong> · {receiver.contact_name}<div className="table-sub">{channel === 'WECHAT' ? receiver.wechat_openid_masked ?? 'OpenID 遮罩未提供' : receiver.phone_masked}</div></div>)}{receivers.length > 1 && <small className="muted-note">升级间隔：{rule[channel === 'SMS' ? 'sms_escalation_interval_minutes' : channel === 'VOICE' ? 'voice_escalation_interval_minutes' : 'wechat_escalation_interval_minutes']} 分钟</small>}</div>
}

function RuleEditor({ selection, tree, canWrite, close, saved }: { selection: Exclude<Selection, null>; tree: UnitDirectory; canWrite: boolean; close: () => void; saved: () => void }) {
  const selected = 'rule' in selection ? selection.rule : undefined
  const detail = useResource(`notification-rule:${selected?.tenant_id}:${selected?.id}`, signal => notificationApi.rule(selected!.id, signal, selected!.tenant_id), !!selected)
  if (!selected) return <RuleForm tree={tree} canWrite={canWrite} close={close} saved={saved}/>
  if (detail.loading) return <><SheetHeader className="review-header"><SheetTitle>通知规则</SheetTitle><SheetDescription>正在读取规则和当前revision。</SheetDescription></SheetHeader><Loading/></>
  if (detail.error != null) return <><SheetHeader className="review-header"><SheetTitle>通知规则</SheetTitle><SheetDescription>规则读取失败。</SheetDescription></SheetHeader><ErrorNotice error={detail.error} retry={detail.refresh}/></>
  if (detail.data && (detail.data.scope_type !== selected.scope_type || detail.data.scope_id !== selected.scope_id)) return <><SheetHeader className="review-header"><SheetTitle>通知规则</SheetTitle><SheetDescription>规则目标未确认。</SheetDescription></SheetHeader><ErrorNotice error={new Error('规则详情与原选择的作用域或目标不一致，请关闭后刷新列表重试。')}/></>
  return detail.data ? <RuleForm key={`${detail.data.id}:${detail.data.revision}`} rule={detail.data} tree={tree} canWrite={canWrite} close={close} saved={saved}/> : null
}

function RuleForm({ rule, tree, canWrite, close, saved }: { rule?: NotificationRule; tree: UnitDirectory; canWrite: boolean; close: () => void; saved: () => void }) {
  const units = tree.data ?? []
  const [tenantId, setTenantId] = useState(rule?.tenant_id ?? ''), [name, setName] = useState(rule?.name ?? ''), [eventScope, setEventScope] = useState<'FIRE' | 'FAULT'>(rule?.event_scope ?? 'FIRE'), [enabled, setEnabled] = useState(rule?.enabled ?? false)
  const [scopeDraft, setScopeDraft] = useState<RuleScopeDraft>({ type: 'UNIT', id: '' })
  const scope = rule ?? ruleScopeFromDraft(scopeDraft, tenantId)
  const unit = units.find(unit => unit.id === tenantId)
  const [receivers, setReceivers] = useState<ReceiverChoice[]>(() => rule ? rule.receivers.map(item => ({ ...item })).sort((a, b) => a.channel.localeCompare(b.channel) || a.level - b.level) : initialReceivers())
  const [smsInterval, setSmsInterval] = useState(rule?.sms_escalation_interval_minutes == null ? '' : String(rule.sms_escalation_interval_minutes)), [voiceInterval, setVoiceInterval] = useState(rule?.voice_escalation_interval_minutes == null ? '' : String(rule.voice_escalation_interval_minutes)), [wechatInterval, setWechatInterval] = useState(rule?.wechat_escalation_interval_minutes == null ? '' : String(rule.wechat_escalation_interval_minutes))
  const [contactPage, setContactPage] = useState(1), [contactKeyword, setContactKeyword] = useState(''), [reason, setReason] = useState('')
  const action = useMutationAction()
  const uncertain = notificationRuleWriteUncertain(action.error)
  const [browse, setBrowse] = useState(firstTargetPage)
  const access = useRuleTargets(tenantId, rule?.scope_type ?? 'UNIT', firstTargetPage, canWrite, rule?.scope_type === 'UNIT' ? undefined : rule?.scope_id)
  const accessData = access.loading ? undefined : access.data
  const available: readonly NotificationRuleScopeType[] = accessData?.access === 'ALL' ? notificationRuleScopeTypes
    : accessData?.access === 'DEVICE_ONLY' ? ['DEVICE'] : []
  const targetType = !rule && scopeDraft.type && available.includes(scopeDraft.type) ? scopeDraft.type : undefined
  const browseTargets = useRuleTargets(tenantId, targetType || 'UNIT', browse, targetType === 'PLACE' || targetType === 'DEVICE')
  const browseData = browseTargets.loading ? undefined : browseTargets.data
  const targetAllowed = scope !== undefined && available.includes(scope.scope_type) && (
    rule ? (rule.scope_type === 'UNIT' || accessData?.items[0]?.id === rule.scope_id)
      : (scope.scope_type === 'UNIT' || (scope.scope_type === 'PLACE' ? browseData?.access === 'ALL' : browseData?.access === 'ALL' || browseData?.access === 'DEVICE_ONLY'))
  )
  const canEdit = canWrite && (!rule || targetAllowed)
  const needsBothChannels = scope?.scope_type === 'UNIT'
  const contacts = useResource<Paginated<NotificationContact>>(`notification-rule-contacts:${tenantId}:${contactPage}:${contactKeyword}`, signal => notificationApi.contacts(contactPage, contactKeyword, signal, tenantId, true), !!tenantId)
  const wechatRecipients = useResource<WechatRecipient[]>(`notification-rule-wechat-recipients:${tenantId}`, signal => wechatApi.recipients(signal, tenantId), !!tenantId)
  const wechatChannel = useResource<WechatChannel>(`notification-rule-wechat-channel:${tenantId}`, signal => wechatApi.channel(signal, tenantId), !!tenantId)
  const activeContacts = contacts.data?.items.filter(contact => contact.enabled) ?? []
  const activeWechatContacts = activeContacts.filter(contact => !!contact.wechat_binding_id)
  function wechatRecipientFor(contact?: NotificationContact) {
    if (!contact?.wechat_binding_id) return undefined
    return wechatRecipients.data?.find(item => item.bindingId === contact.wechat_binding_id)
  }
  function mutate(change: () => void) { action.touch(); change() }
  function updateReceiver(channel: NotificationChannel, level: number, contactId: string) {
    const selected = activeContacts.find(contact => contact.id === contactId)
    const recipient = wechatRecipientFor(selected)
    mutate(() => setReceivers(current => current.map(receiver => receiver.channel === channel && receiver.level === level ? { channel, level, contact_id: contactId, contact_name: recipient?.userName ?? selected?.name ?? '', phone_masked: selected?.phone_masked ?? '', wechat_binding_id: selected?.wechat_binding_id ?? null, wechat_openid_masked: recipient?.openidMasked ?? null } : receiver)))
  }
  function addLevel(channel: NotificationChannel) {
    const count = receivers.filter(receiver => receiver.channel === channel).length
    if (count >= 5) return
    mutate(() => {
      setReceivers(current => [...current, { channel, level: count + 1, contact_id: '', contact_name: '', phone_masked: '' }])
      if (channel === 'SMS' && !smsInterval) setSmsInterval('0')
      if (channel === 'VOICE' && !voiceInterval) setVoiceInterval('0')
      if (channel === 'WECHAT' && count >= 1 && !wechatInterval) setWechatInterval('0')
    })
  }
  function removeLevel(channel: NotificationChannel) {
    const count = receivers.filter(receiver => receiver.channel === channel).length
    if (count <= (needsBothChannels && channel !== 'WECHAT' ? 1 : 0)) return
    mutate(() => { setReceivers(current => current.filter(receiver => receiver.channel !== channel || receiver.level !== count)); if (count <= 2 && channel === 'SMS') setSmsInterval(''); if (count <= 2 && channel === 'VOICE') setVoiceInterval(''); if (count <= 2 && channel === 'WECHAT') setWechatInterval('') })
  }
  function setUnit(value: string) {
    mutate(() => { setTenantId(value); setScopeDraft({ type: 'UNIT', id: '' }); setBrowse(firstTargetPage); setContactPage(1); setContactKeyword(''); setReceivers(initialReceivers()); setSmsInterval(''); setVoiceInterval(''); setWechatInterval('') })
  }
  function intervalValue(channel: NotificationChannel): number | null {
    const raw = (channel === 'SMS' ? smsInterval : channel === 'VOICE' ? voiceInterval : wechatInterval).trim()
    const count = receivers.filter(receiver => receiver.channel === channel).length
    if (!count) return null
    if (!raw) { if (count > 1) throw new Error(`${channelNames[channel]}多级升级必须填写0至60分钟间隔`); return null }
    if (!/^\d+$/.test(raw) || Number(raw) > 60) throw new Error(`${channelNames[channel]}升级间隔只能为0至60分钟整数`)
    const value = Number(raw)
    if (count === 1 && value !== 0) throw new Error(`${channelNames[channel]}单级规则不消费升级间隔，只能留空或为0`)
    return value
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || (!targetAllowed && !uncertain) || action.busy || action.conflict) return
    try {
      if (!tenantId || !scope) throw new Error('必须明确选择规则所属单位和作用域目标')
      if (!rule && !unit && !uncertain) throw new Error('所属单位不在已授权单位目录中，请刷新后重试')
      if (!name.trim() || name.trim().length > 200) throw new Error('规则名称必须为1至200字符')
      if (!reason.trim()) throw new Error('必须填写本次规则变更原因')
      if (!receivers.length || receivers.length > 15) throw new Error('至少配置一名收件人；短信、语音和公众号最多各配置5级')
      for (const channel of ['SMS', 'VOICE'] as const) {
        const ordered = receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level)
        if ((needsBothChannels && !ordered.length) || ordered.length > 5 || ordered.some((receiver, index) => receiver.level !== index + 1 || !receiver.contact_id)) throw new Error(`${channelNames[channel]}收件级别必须从1连续且每级选择已启用联系人`)
        if (new Set(ordered.map(receiver => receiver.contact_id)).size !== ordered.length) throw new Error(`${channelNames[channel]}同一联系人不能重复设置多个级别`)
        if (ordered.some(receiver => !receiver.contact_name || !receiver.phone_masked)) throw new Error(`${channelNames[channel]}收件人选择状态不完整，请从联系人列表重新选择`)
      }
      const wechatOrdered = receivers.filter(receiver => receiver.channel === 'WECHAT').sort((a, b) => a.level - b.level)
      if (wechatOrdered.length > 5 || wechatOrdered.some((receiver, index) => receiver.level !== index + 1 || !receiver.contact_id || !receiver.wechat_binding_id)) throw new Error('公众号收件级别必须从1连续，且每级选择已关联公众号绑定的联系人')
      if (new Set(wechatOrdered.map(receiver => receiver.contact_id)).size !== wechatOrdered.length) throw new Error('公众号同一联系人不能重复设置多个级别')
      const input: NotificationRuleInput = {
        name: name.trim(), enabled, event_scope: eventScope, scope_type: scope.scope_type, scope_id: scope.scope_id,
        receivers: receivers.map(({ channel, level, contact_id }) => ({ channel, level, contact_id })).sort((a, b) => a.channel.localeCompare(b.channel) || a.level - b.level),
        sms_escalation_interval_minutes: intervalValue('SMS'),
        voice_escalation_interval_minutes: intervalValue('VOICE'),
        wechat_escalation_interval_minutes: intervalValue('WECHAT'),
        reason: reason.trim(),
        ...(rule ? { expected_revision: rule.revision } : {}),
      }
      if (!action.confirming) { action.setConfirming(true); return }
      void action.run((key, signal) => notificationApi.saveRule(rule?.id ?? null, input, key, signal, tenantId), saved)
    } catch (error) { action.setError(error) }
  }
  function receiverField(channel: NotificationChannel) {
    const selected = receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level)
    const wechat = channel === 'WECHAT'
    const options = wechat ? activeWechatContacts : activeContacts
    const interval = channel === 'SMS' ? smsInterval : channel === 'VOICE' ? voiceInterval : wechatInterval
    const setInterval = channel === 'SMS' ? setSmsInterval : channel === 'VOICE' ? setVoiceInterval : setWechatInterval
    return <section className="form-stack" aria-label={`${channelNames[channel]}收件人`}>
      <h3>{channelNames[channel]}收件人{(wechat || !needsBothChannels) && '（可选）'}</h3>
      {wechat && (wechatChannel.loading ? <Loading/> : wechatChannel.error != null ? <ErrorNotice error={wechatChannel.error} retry={wechatChannel.refresh}/> : wechatChannel.data && <div className={wechatChannel.data.ready ? 'notice' : 'notice danger-note'} role="status">公众号能力：{wechatChannel.data.ready ? '就绪' : '未就绪'} · 可用收件人 {wechatChannel.data.active_recipients} · {wechatChannel.data.message}</div>)}
      {selected.map(receiver => {
        const pageHasSelection = options.some(contact => contact.id === receiver.contact_id)
        return <div className="field" key={`${channel}:${receiver.level}`}>
          <Label htmlFor={`rule-${channel}-${receiver.level}`}>{receiver.level}级联系人</Label>
          <select id={`rule-${channel}-${receiver.level}`} className="native-select" value={receiver.contact_id} disabled={!canEdit || !tenantId || contacts.loading} required onChange={event => updateReceiver(channel, receiver.level, event.target.value)}>
            <option value="">{wechat ? '请选择已关联公众号绑定的联系人' : '请选择本单位已启用联系人'}</option>
            {receiver.contact_id && !pageHasSelection && <option value={receiver.contact_id}>{receiver.contact_name || receiver.contact_id} · {wechat ? receiver.wechat_openid_masked ?? '当前保存值' : receiver.phone_masked || '当前保存值，刷新联系人页后确认'}</option>}
            {options.map(contact => { const recipient = wechatRecipientFor(contact); return <option key={contact.id} value={contact.id}>{wechat ? `${recipient?.userName ?? contact.name} · ${recipient?.openidMasked ?? '绑定状态待确认'}` : `${contact.name} · ${contact.phone_masked}`}</option> })}
          </select>
          {receiver.contact_id && <small className="muted-note">已选 {receiver.contact_name || receiver.contact_id} · {wechat ? `OpenID 遮罩 ${receiver.wechat_openid_masked ?? '未确认'}` : `服务端遮罩 ${receiver.phone_masked}`}；只提交联系人ID，不提交 OpenID 或遮罩号码。</small>}
        </div>
      })}
      {canEdit && <div className="identity-form-actions"><Button type="button" variant="outline" disabled={selected.length >= 5} onClick={() => addLevel(channel)}>增加{channelNames[channel]}级别</Button><Button type="button" variant="outline" disabled={selected.length <= (needsBothChannels && !wechat ? 1 : 0)} onClick={() => removeLevel(channel)}>移除末级</Button></div>}
      <div className="field"><Label htmlFor={`rule-${channel}-interval`}>多级升级间隔（分钟）</Label><Input id={`rule-${channel}-interval`} type="number" min="0" max="60" step="1" value={interval} disabled={!canEdit || selected.length <= 1} placeholder={selected.length <= 1 ? '单级不需要（留空或0）' : '0至60分钟'} onChange={event => mutate(() => setInterval(event.target.value))}/><small className="muted-note">{selected.length > 1 ? '多级规则为0至60分钟整数。' : '当前单级规则不消费升级间隔，可留空或为0。'}</small></div>
      {contacts.loading ? <Loading/> : contacts.error != null ? <ErrorNotice error={contacts.error} retry={contacts.refresh}/> : !tenantId ? <p className="muted-note">先明确选择规则所属单位，再选择该单位的已启用联系人。</p> : <><div className="field"><Label htmlFor={`rule-${channel}-contact-search`}>搜索本单位联系人</Label><Input id={`rule-${channel}-contact-search`} value={contactKeyword} onChange={event => { setContactKeyword(event.target.value); setContactPage(1) }} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }} placeholder="按名称或ID查询，不搜索号码"/></div><div className="muted-note">联系人选择第 {contacts.data?.page ?? contactPage} 页 · {wechat ? `仅显示当前页已启用且已关联公众号绑定的联系人（${activeWechatContacts.length} 个）` : '仅显示当前页已启用联系人'}。已选项会保留在各级选择框，翻页不会清除。</div>{contacts.data && contacts.data.total > contacts.data.size && <Pager page={contacts.data.page} size={contacts.data.size} total={contacts.data.total} onChange={setContactPage}/>}</>}
      {!contacts.loading && contacts.error == null && contacts.data && options.length === 0 && <p className="muted-note">{wechat ? '当前分页没有已关联公众号绑定的已启用联系人；需用户先在“我的”完成绑定。' : '当前分页没有已启用联系人；可搜索或翻页选择其他本单位联系人。'}</p>}
    </section>
  }
  const title = rule ? canEdit ? '编辑真实报警收件规则' : '查看真实报警收件规则' : '新建真实报警收件规则'
  const form = <form className="form-stack" onSubmit={submit}>
    {rule && <p className="mono">{rule.id} · revision {rule.revision} · {rule.tenant_name} · {rule.tenant_id} · {dateTime(rule.created_at)}</p>}
    <RuleTargetStatus directory={access}/>
    {rule && accessData && accessData.access !== 'NONE' && !targetAllowed && <p className="notice" role="status">该规则的固定目标当前不可编辑。单位和位置规则需要全单位设备范围；设备规则需要有权访问且设备未归档。</p>}
    {rule ? <p className="notice">固定作用域：{ruleScopeLabel(rule)}。编辑不会移动规则。</p> : <>
      {tree.loading ? <Loading/> : tree.error != null ? <ErrorNotice error={tree.error} retry={tree.refresh}/> : <UnitSelect label="规则所属单位" units={units} tenantId={tenantId} disabled={!canWrite || action.confirming} onChange={setUnit}/>}
      <ManagementRuleScopeFields value={scopeDraft} tenantId={tenantId} available={available} disabled={!canWrite || action.confirming}
        onChange={value => mutate(() => { setScopeDraft(value); setBrowse(firstTargetPage) })}/>
      {(targetType === 'PLACE' || targetType === 'DEVICE') && <ManagementTargetPicker type={targetType} directory={browseTargets}
        browse={browse} setBrowse={setBrowse} value={scopeDraft.id} disabled={action.confirming}
        onChange={id => mutate(() => setScopeDraft({ type: targetType, id }))}/>}
    </>}
    <div className="field"><Label htmlFor="notification-rule-name">规则名称</Label><Input id="notification-rule-name" maxLength={200} required value={name} disabled={!canEdit || action.confirming} onChange={event => mutate(() => setName(event.target.value))}/></div>
    <div className="field"><Label htmlFor="notification-rule-event-scope">报警事件类型</Label><select id="notification-rule-event-scope" className="native-select" value={eventScope} disabled={!canEdit || action.confirming} onChange={event => mutate(() => setEventScope(event.target.value as 'FIRE' | 'FAULT'))}><option value="FIRE">火警 FIRE</option><option value="FAULT">故障 FAULT</option></select></div>
    <label className="field"><span>状态</span><span><input type="checkbox" checked={enabled} disabled={!canEdit || action.confirming} onChange={event => mutate(() => setEnabled(event.target.checked))}/> 启用真实{eventScope === 'FIRE' ? '火警' : '故障'}通知规则</span></label>
    <div className="notice">规则仅路由所选事件类型。FAULT 仅在明确选择并启用后产生短信/语音/公众号通知，不创建或关闭故障事实；配置不发送测试消息。公众号收件人可选，未绑定、取消关注、通道未启用或策略关闭时不会伪造送达。</div>
    <fieldset className="form-stack" disabled={action.confirming}>{receiverField('SMS')}{receiverField('VOICE')}{receiverField('WECHAT')}</fieldset>
    {canEdit && <div className="field"><Label htmlFor="notification-rule-reason">本次操作原因</Label><Input id="notification-rule-reason" maxLength={1000} required value={reason} disabled={action.confirming} onChange={event => mutate(() => setReason(event.target.value))}/></div>}
    {action.error != null && !action.confirming && <ErrorNotice error={action.error}/>}
    {canEdit && !action.confirming && <div className="identity-form-actions"><Button type="submit" disabled={!targetAllowed}>核对规则变更</Button><Button type="button" variant="outline" onClick={close}>关闭</Button></div>}
    {canWrite && action.confirming && (uncertain ? <section className="sensitive-confirmation">
      <ErrorNotice error={action.error}/>
      <p>保存结果未知。只能按原作用域、内容和幂等键重试；请勿另建规则。</p>
      <Button type="submit">按原操作重试</Button>
    </section> : <OperationConfirmation action={action} title={rule ? '确认更新真实报警收件规则' : '确认创建真实报警收件规则'} submitLabel={action.busy ? '正在保存…' : '确认保存规则'} refresh={saved} warning="启用或修改规则会改变后续真实报警的收件人与升级安排。此操作不会直接发送通知。"><p>{name.trim()} · {enabled ? '启用' : '停用'} · 单位 {rule?.tenant_name ?? unit?.name ?? tenantId} · {scope && ruleScopeLabel(scope)}</p><p>短信：{summaryText(receivers, 'SMS')} · 升级间隔 {smsInterval || '无'}</p><p>语音：{summaryText(receivers, 'VOICE')} · 升级间隔 {voiceInterval || '无'}</p><p>公众号：{summaryText(receivers, 'WECHAT') || '未配置'} · 升级间隔 {wechatInterval || '无'}</p><p>原因：{reason}</p>{action.conflict && <p>revision / 幂等冲突；请刷新列表并重新打开规则核对当前revision。</p>}</OperationConfirmation>)}
  </form>
  return <><SheetHeader className="review-header"><SheetTitle>{title}</SheetTitle><SheetDescription>单位规则必须配置短信和语音；位置和设备规则至少配置一个渠道。每个渠道最多5级，联系人不可重复，级别必须连续。公众号需要已绑定联系人。真实模板由通知通道配置决定，不在此编辑或测试。</SheetDescription></SheetHeader><div className="review-body">{!canWrite && <div className="notice">当前为只读查看；无法修改规则或选择联系人。</div>}{form}</div></>
}

function initialReceivers(): ReceiverChoice[] { return (['SMS', 'VOICE'] as const).map(channel => ({ channel, level: 1, contact_id: '', contact_name: '', phone_masked: '' })) }
function summaryText(receivers: ReceiverChoice[], channel: NotificationChannel) { return receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level).map(receiver => { const detail = channel === 'WECHAT' ? receiver.wechat_openid_masked : receiver.phone_masked; return `${receiver.level}级 ${receiver.contact_name || '未选择'}${detail ? ` (${detail})` : ''}` }).join('、') }
