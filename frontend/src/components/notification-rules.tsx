import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import type { Paginated, ScopeUnit } from '@/lib/types'
import { notificationApi, type NotificationChannel, type NotificationContact, type NotificationRule, type NotificationRuleInput, type NotificationRuleReceiver } from '@/lib/notifications'
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

type Selection = { id: string; tenantId: string } | { create: true } | null
type ReceiverChoice = NotificationRuleReceiver
const channelNames: Record<NotificationChannel, string> = { SMS: '短信', VOICE: '语音', WECHAT: '公众号' }

export function NotificationRules({ canRead, canWrite }: { canRead: boolean; canWrite: boolean }) {
  const session = useSession()
  const scopeKey = `${session.generation}:${session.scopeInitialized}:${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}`
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [draft, setDraft] = useState(''), [selection, setSelection] = useState<Selection>(null)
  const rows = useResource<Paginated<NotificationRule>>(`notification-rules:${scopeKey}:${page}:${keyword}`, signal => notificationApi.rules(page, keyword, signal), canRead && session.scopeInitialized)
  useEffect(() => { setPage(1) }, [scopeKey])
  const refresh = () => { setSelection(null); rows.refresh() }
  if (!canRead) return <Empty title="当前账户无真实通知规则读取权限"/>
  return <section aria-label="报警与故障收件规则">
    <PageHeading eyebrow="NOTIFICATION · 通知规则" title="报警与故障通知规则" description="分别配置短信、语音联系人和升级间隔。">
      <RefreshButton onClick={rows.refresh}/>{canWrite && <Button type="button" onClick={() => setSelection({ create: true })}>新建收件规则</Button>}
      <PageHelp title="通知规则与事件状态"><p>火警与故障分别配置并显式启用，保存规则不会立即发送消息。短信和语音正文由真实通道模板决定，本页不会测试供应商或发起发送。</p><p>规则只控制软件的外部通知出口，不删除故障历史、不改变设备物理恢复状态。故障规则没有启用时，不会自动套用火警规则。</p></PageHelp>
    </PageHeading>
    <Card className="list-card"><CardContent>
      <form className="list-toolbar" onSubmit={event => { event.preventDefault(); setKeyword(draft.trim()); setPage(1) }}>
        <div className="field"><Label htmlFor="notification-rule-search">规则名称 / ID</Label><Input id="notification-rule-search" value={draft} onChange={event => setDraft(event.target.value)} placeholder="按名称或ID查询"/></div>
        <Button type="submit" variant="outline">查询</Button>
      </form>
      {rows.loading ? <Loading/> : rows.error != null ? <ErrorNotice error={rows.error} retry={rows.refresh}/> : rows.data?.items.length ? <>
        <Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>类型 / 规则</TableHead><TableHead>短信收件级别</TableHead><TableHead>语音收件级别</TableHead><TableHead>公众号收件级别</TableHead><TableHead>状态 / 版本</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
          <TableBody>{rows.data.items.map(rule => <TableRow key={rule.id}>
            <TableCell>{rule.tenant_name}<div className="table-sub mono">{rule.tenant_id}</div></TableCell>
            <TableCell>{rule.event_scope === 'FIRE' ? '火警 FIRE' : '故障 FAULT'} · {rule.name}<div className="table-sub mono">{rule.id}</div></TableCell>
            <TableCell>{receiverSummary(rule, 'SMS')}</TableCell><TableCell>{receiverSummary(rule, 'VOICE')}</TableCell><TableCell>{receiverSummary(rule, 'WECHAT')}</TableCell>
            <TableCell>{rule.enabled ? '已启用' : '已停用'} · revision {rule.revision}</TableCell>
            <TableCell><Button type="button" variant="outline" size="sm" onClick={() => setSelection({ id: rule.id, tenantId: rule.tenant_id })}>{canWrite ? '编辑规则' : '查看规则'}</Button></TableCell>
          </TableRow>)}</TableBody>
        </Table>
        <Pager page={rows.data.page} size={rows.data.size} total={rows.data.total} onChange={setPage}/>
      </> : <Empty title="暂无真实报警收件规则">创建规则时需明确选择单位，并为短信和语音分别选择已启用的本地联系人。</Empty>}
    </CardContent></Card>
    <Sheet open={selection !== null} onOpenChange={open => { if (!open) setSelection(null) }}><SheetContent className="review-sheet notification-sheet">{selection && <RuleEditor key={'create' in selection ? 'create' : `${selection.tenantId}:${selection.id}`} selection={selection} canWrite={canWrite} close={() => setSelection(null)} saved={refresh}/>}</SheetContent></Sheet>
  </section>
}

function receiverSummary(rule: NotificationRule, channel: NotificationChannel) {
  const receivers = rule.receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level)
  if (!receivers.length) return channel === 'WECHAT' ? <span className="muted">未配置</span> : null
  return <div className="form-stack">{receivers.map(receiver => <div key={`${receiver.channel}:${receiver.level}`}><strong>{receiver.level}级</strong> · {receiver.contact_name}<div className="table-sub">{channel === 'WECHAT' ? receiver.wechat_openid_masked ?? 'OpenID 遮罩未提供' : receiver.phone_masked}</div></div>)}{receivers.length > 1 && <small className="muted-note">升级间隔：{rule[channel === 'SMS' ? 'sms_escalation_interval_minutes' : channel === 'VOICE' ? 'voice_escalation_interval_minutes' : 'wechat_escalation_interval_minutes']} 分钟</small>}</div>
}

function RuleEditor({ selection, canWrite, close, saved }: { selection: Exclude<Selection, null>; canWrite: boolean; close: () => void; saved: () => void }) {
  const creating = 'create' in selection
  const selectionId = creating ? '' : selection.id
  const selectionTenantId = creating ? undefined : selection.tenantId
  const detail = useResource(`notification-rule:${creating ? 'create' : `${selectionTenantId}:${selectionId}`}`, signal => notificationApi.rule(selectionId, signal, selectionTenantId), !creating)
  if (creating) return <RuleForm canWrite={canWrite} close={close} saved={saved}/>
  if (detail.loading) return <><SheetHeader className="review-header"><SheetTitle>通知规则</SheetTitle><SheetDescription>正在读取规则和当前revision。</SheetDescription></SheetHeader><Loading/></>
  if (detail.error != null) return <><SheetHeader className="review-header"><SheetTitle>通知规则</SheetTitle><SheetDescription>规则读取失败。</SheetDescription></SheetHeader><ErrorNotice error={detail.error} retry={detail.refresh}/></>
  if (detail.data && (detail.data.id !== selectionId || detail.data.tenant_id !== selectionTenantId)) return <><SheetHeader className="review-header"><SheetTitle>通知规则</SheetTitle><SheetDescription>规则归属未确认。</SheetDescription></SheetHeader><ErrorNotice error={new Error('规则详情与原选择的单位或记录不一致，请关闭后刷新列表重试。')}/></>
  return detail.data ? <RuleForm key={`${detail.data.id}:${detail.data.revision}`} rule={detail.data} canWrite={canWrite} close={close} saved={saved}/> : null
}

function RuleForm({ rule, canWrite, close, saved }: { rule?: NotificationRule; canWrite: boolean; close: () => void; saved: () => void }) {
  const session = useSession()
  const tree = useResource(`notification-rule-target-units:${session.scopeTreeRevision}`, signal => api.scopeTree(signal), canWrite && session.scopeInitialized)
  const eligibleUnits = useMemo(() => (tree.data ?? []).filter((unit: ScopeUnit) => session.scopeTenantIds.includes(unit.id) || unit.places.some(place => session.scopePlaceIds.includes(place.id))), [tree.data, session.scopeTenantIds, session.scopePlaceIds])
  const [tenantId, setTenantId] = useState(rule?.tenant_id ?? ''), [name, setName] = useState(rule?.name ?? ''), [eventScope, setEventScope] = useState<'FIRE' | 'FAULT'>(rule?.event_scope ?? 'FIRE'), [enabled, setEnabled] = useState(rule?.enabled ?? false)
  const [receivers, setReceivers] = useState<ReceiverChoice[]>(() => rule ? rule.receivers.map(item => ({ ...item })).sort((a, b) => a.channel.localeCompare(b.channel) || a.level - b.level) : initialReceivers())
  const [smsInterval, setSmsInterval] = useState(rule?.sms_escalation_interval_minutes == null ? '' : String(rule.sms_escalation_interval_minutes)), [voiceInterval, setVoiceInterval] = useState(rule?.voice_escalation_interval_minutes == null ? '' : String(rule.voice_escalation_interval_minutes)), [wechatInterval, setWechatInterval] = useState(rule?.wechat_escalation_interval_minutes == null ? '' : String(rule.wechat_escalation_interval_minutes))
  const [contactPage, setContactPage] = useState(1), [contactKeyword, setContactKeyword] = useState(''), [reason, setReason] = useState('')
  const action = useMutationAction()
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
    if (count <= (channel === 'WECHAT' ? 0 : 1)) return
    mutate(() => { setReceivers(current => current.filter(receiver => receiver.channel !== channel || receiver.level !== count)); if (count === 2 && channel === 'SMS') setSmsInterval(''); if (count === 2 && channel === 'VOICE') setVoiceInterval(''); if (count === 2 && channel === 'WECHAT') setWechatInterval('') })
  }
  function setUnit(value: string) {
    mutate(() => { setTenantId(value); setContactPage(1); setContactKeyword(''); setReceivers(initialReceivers()); setSmsInterval(''); setVoiceInterval(''); setWechatInterval('') })
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
    if (!canWrite) return
    try {
      if (!tenantId) throw new Error('必须明确选择通知规则所属单位')
      if (rule && rule.tenant_id !== tenantId) throw new Error('编辑规则不得更改其所属单位')
      if (!name.trim() || name.trim().length > 200) throw new Error('规则名称必须为1至200字符')
      if (!reason.trim()) throw new Error('必须填写本次规则变更原因')
      if (receivers.length > 15) throw new Error('短信、语音和公众号最多各配置5级收件人')
      if (!eligibleUnits.some(unit => unit.id === tenantId)) throw new Error('所属单位不在当前已授权单位目录中，请刷新范围后重试')
      for (const channel of ['SMS', 'VOICE'] as const) {
        const ordered = receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level)
        if (!ordered.length || ordered.length > 5 || ordered.some((receiver, index) => receiver.level !== index + 1 || !receiver.contact_id)) throw new Error(`${channelNames[channel]}收件级别必须从1连续且每级选择已启用联系人`)
        if (new Set(ordered.map(receiver => receiver.contact_id)).size !== ordered.length) throw new Error(`${channelNames[channel]}同一联系人不能重复设置多个级别`)
        if (ordered.some(receiver => !receiver.contact_name || !receiver.phone_masked)) throw new Error(`${channelNames[channel]}收件人选择状态不完整，请从联系人列表重新选择`)
      }
      const wechatOrdered = receivers.filter(receiver => receiver.channel === 'WECHAT').sort((a, b) => a.level - b.level)
      if (wechatOrdered.length > 5 || wechatOrdered.some((receiver, index) => receiver.level !== index + 1 || !receiver.contact_id || !receiver.wechat_binding_id)) throw new Error('公众号收件级别必须从1连续，且每级选择已关联公众号绑定的联系人')
      if (new Set(wechatOrdered.map(receiver => receiver.contact_id)).size !== wechatOrdered.length) throw new Error('公众号同一联系人不能重复设置多个级别')
      const input: NotificationRuleInput = {
        name: name.trim(), enabled, event_scope: eventScope,
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
      <h3>{channelNames[channel]}收件人{wechat ? '（可选）' : ''}</h3>
      {wechat && (wechatChannel.loading ? <Loading/> : wechatChannel.error != null ? <ErrorNotice error={wechatChannel.error} retry={wechatChannel.refresh}/> : wechatChannel.data && <div className={wechatChannel.data.ready ? 'notice' : 'notice danger-note'} role="status">公众号能力：{wechatChannel.data.ready ? '就绪' : '未就绪'} · 可用收件人 {wechatChannel.data.active_recipients} · {wechatChannel.data.message}</div>)}
      {selected.map(receiver => {
        const pageHasSelection = options.some(contact => contact.id === receiver.contact_id)
        return <div className="field" key={`${channel}:${receiver.level}`}>
          <Label htmlFor={`rule-${channel}-${receiver.level}`}>{receiver.level}级联系人</Label>
          <select id={`rule-${channel}-${receiver.level}`} className="native-select" value={receiver.contact_id} disabled={!canWrite || !tenantId || contacts.loading} required onChange={event => updateReceiver(channel, receiver.level, event.target.value)}>
            <option value="">{wechat ? '请选择已关联公众号绑定的联系人' : '请选择本单位已启用联系人'}</option>
            {receiver.contact_id && !pageHasSelection && <option value={receiver.contact_id}>{receiver.contact_name || receiver.contact_id} · {wechat ? receiver.wechat_openid_masked ?? '当前保存值' : receiver.phone_masked || '当前保存值，刷新联系人页后确认'}</option>}
            {options.map(contact => { const recipient = wechatRecipientFor(contact); return <option key={contact.id} value={contact.id}>{wechat ? `${recipient?.userName ?? contact.name} · ${recipient?.openidMasked ?? '绑定状态待确认'}` : `${contact.name} · ${contact.phone_masked}`}</option> })}
          </select>
          {receiver.contact_id && <small className="muted-note">已选 {receiver.contact_name || receiver.contact_id} · {wechat ? `OpenID 遮罩 ${receiver.wechat_openid_masked ?? '未确认'}` : `服务端遮罩 ${receiver.phone_masked}`}；只提交联系人ID，不提交 OpenID 或遮罩号码。</small>}
        </div>
      })}
      {canWrite && <div className="identity-form-actions"><Button type="button" variant="outline" disabled={selected.length >= 5} onClick={() => addLevel(channel)}>增加{channelNames[channel]}级别</Button><Button type="button" variant="outline" disabled={selected.length <= (wechat ? 0 : 1)} onClick={() => removeLevel(channel)}>移除末级</Button></div>}
      <div className="field"><Label htmlFor={`rule-${channel}-interval`}>多级升级间隔（分钟）</Label><Input id={`rule-${channel}-interval`} type="number" min="0" max="60" step="1" value={interval} disabled={!canWrite || selected.length <= 1} placeholder={selected.length <= 1 ? '单级不需要（留空或0）' : '0至60分钟'} onChange={event => mutate(() => setInterval(event.target.value))}/><small className="muted-note">{selected.length > 1 ? '多级规则为0至60分钟整数。' : '当前单级规则不消费升级间隔，可留空或为0。'}</small></div>
      {contacts.loading ? <Loading/> : contacts.error != null ? <ErrorNotice error={contacts.error} retry={contacts.refresh}/> : !tenantId ? <p className="muted-note">先明确选择规则所属单位，再选择该单位的已启用联系人。</p> : <><div className="field"><Label htmlFor={`rule-${channel}-contact-search`}>搜索本单位联系人</Label><Input id={`rule-${channel}-contact-search`} value={contactKeyword} onChange={event => { setContactKeyword(event.target.value); setContactPage(1) }} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }} placeholder="按名称或ID查询，不搜索号码"/></div><div className="muted-note">联系人选择第 {contacts.data?.page ?? contactPage} 页 · {wechat ? `仅显示当前页已启用且已关联公众号绑定的联系人（${activeWechatContacts.length} 个）` : '仅显示当前页已启用联系人'}。已选项会保留在各级选择框，翻页不会清除。</div>{contacts.data && contacts.data.total > contacts.data.size && <Pager page={contacts.data.page} size={contacts.data.size} total={contacts.data.total} onChange={setContactPage}/>}</>}
      {!contacts.loading && contacts.error == null && contacts.data && options.length === 0 && <p className="muted-note">{wechat ? '当前分页没有已关联公众号绑定的已启用联系人；需用户先在“我的”完成绑定。' : '当前分页没有已启用联系人；可搜索或翻页选择其他本单位联系人。'}</p>}
    </section>
  }
  const title = rule ? canWrite ? '编辑真实报警收件规则' : '查看真实报警收件规则' : '新建真实报警收件规则'
  const form = <form className="form-stack" onSubmit={submit}>
    {rule && <p className="mono">{rule.id} · revision {rule.revision} · {rule.tenant_name} · {rule.tenant_id} · {dateTime(rule.created_at)}</p>}
    {!rule && <div className="field"><Label htmlFor="notification-rule-unit">规则所属单位</Label>{tree.loading ? <Loading/> : tree.error != null ? <ErrorNotice error={tree.error} retry={tree.refresh}/> : <select id="notification-rule-unit" className="native-select" required value={tenantId} disabled={!canWrite} onChange={event => setUnit(event.target.value)}><option value="">请明确选择授权范围内单位，不自动默认</option>{eligibleUnits.map(unit => <option key={unit.id} value={unit.id}>{unit.name} · {unit.id}</option>)}</select>}</div>}
    <div className="field"><Label htmlFor="notification-rule-name">规则名称</Label><Input id="notification-rule-name" maxLength={200} required value={name} disabled={!canWrite || action.confirming} onChange={event => mutate(() => setName(event.target.value))}/></div>
    <div className="field"><Label htmlFor="notification-rule-event-scope">报警事件类型</Label><select id="notification-rule-event-scope" className="native-select" value={eventScope} disabled={!canWrite || action.confirming} onChange={event => mutate(() => setEventScope(event.target.value as 'FIRE' | 'FAULT'))}><option value="FIRE">火警 FIRE</option><option value="FAULT">故障 FAULT</option></select></div>
    <label className="field"><span>状态</span><span><input type="checkbox" checked={enabled} disabled={!canWrite || action.confirming} onChange={event => mutate(() => setEnabled(event.target.checked))}/> 启用真实{eventScope === 'FIRE' ? '火警' : '故障'}通知规则</span></label>
    <div className="notice">规则仅路由所选事件类型。FAULT 仅在明确选择并启用后产生短信/语音/公众号通知，不创建或关闭故障事实；配置不发送测试消息。公众号收件人可选，未绑定、取消关注、通道未启用或策略关闭时不会伪造送达。</div>
    {receiverField('SMS')}{receiverField('VOICE')}{receiverField('WECHAT')}
    {canWrite && <div className="field"><Label htmlFor="notification-rule-reason">本次操作原因</Label><Input id="notification-rule-reason" maxLength={1000} required value={reason} disabled={action.confirming} onChange={event => mutate(() => setReason(event.target.value))}/></div>}
    {action.error != null && !action.confirming && <ErrorNotice error={action.error}/>}
    {canWrite && !action.confirming && <div className="identity-form-actions"><Button type="submit">核对规则变更</Button><Button type="button" variant="outline" onClick={close}>关闭</Button></div>}
    {canWrite && action.confirming && <OperationConfirmation action={action} title={rule ? '确认更新真实报警收件规则' : '确认创建真实报警收件规则'} submitLabel={action.busy ? '正在保存…' : '确认保存规则'} refresh={saved} warning="启用或修改规则会改变后续真实报警的短信、语音和公众号收件人与升级安排。此操作只保存规则，不直接调用通道测试、供应商或发送接口。"><p>{name.trim()} · {enabled ? '启用' : '停用'} · 单位 {rule?.tenant_name ?? eligibleUnits.find(unit => unit.id === tenantId)?.name ?? tenantId}</p><p>短信：{summaryText(receivers, 'SMS')} · 升级间隔 {smsInterval || '无'}</p><p>语音：{summaryText(receivers, 'VOICE')} · 升级间隔 {voiceInterval || '无'}</p><p>公众号：{summaryText(receivers, 'WECHAT') || '未配置'} · 升级间隔 {wechatInterval || '无'}</p><p>原因：{reason}</p>{action.conflict && <p>revision / 幂等冲突；请刷新列表并重新打开规则核对当前revision。</p>}</OperationConfirmation>}
  </form>
  return <><SheetHeader className="review-header"><SheetTitle>{title}</SheetTitle><SheetDescription>短信和语音分别设置1至5级已启用本地联系人；公众号为可选的1至5级已绑定联系人。同渠道联系人不能重复，级别必须连续。真实模板由通知通道配置决定，不在此编辑或测试。</SheetDescription></SheetHeader><div className="review-body">{!canWrite ? <div className="notice">当前为只读查看；无法修改规则或选择联系人。</div> : null}{form}</div></>
}

function initialReceivers(): ReceiverChoice[] { return (['SMS', 'VOICE'] as const).map(channel => ({ channel, level: 1, contact_id: '', contact_name: '', phone_masked: '' })) }
function summaryText(receivers: ReceiverChoice[], channel: NotificationChannel) { return receivers.filter(receiver => receiver.channel === channel).sort((a, b) => a.level - b.level).map(receiver => { const detail = channel === 'WECHAT' ? receiver.wechat_openid_masked : receiver.phone_masked; return `${receiver.level}级 ${receiver.contact_name || '未选择'}${detail ? ` (${detail})` : ''}` }).join('、') }
