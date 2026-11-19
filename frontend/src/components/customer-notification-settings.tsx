import { DataTransferActions } from '@/components/data-transfer-actions'
import { useEffect, useId, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { customerNotificationApi, type CustomerContactInput, type CustomerRuleInput } from '@/lib/customer-notifications'
import { notificationPhone, type NotificationContact, type NotificationRule } from '@/lib/notifications'
import type { Paginated } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Card, CardContent } from './ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { PageHelp } from './page-tools'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from './common'
import { useMutationAction } from './identity-common'
import { WechatRecipientSelect } from './wechat-binding'
import { wechatApi, type WechatChannel, type WechatRecipient } from '@/lib/wechat-official-account'

type Editor = { kind: 'contact'; item?: NotificationContact } | { kind: 'rule'; item?: NotificationRule } | null
export function CustomerNotificationSettings() {
  const session = useSession(), allowed = session.audience === 'customer' && session.permissions.includes('unit-notifications:read')
  const [editor, setEditor] = useState<Editor>(null), [generation, setGeneration] = useState(0)
  const [contactPage, setContactPage] = useState(1), [contactKeyword, setContactKeyword] = useState(''), [contactDraft, setContactDraft] = useState('')
  const [rulePage, setRulePage] = useState(1), [ruleKeyword, setRuleKeyword] = useState(''), [ruleDraft, setRuleDraft] = useState('')
  const contacts = useResource<Paginated<NotificationContact>>(`customer-notification-contacts:${session.generation}:${generation}:${contactPage}:${contactKeyword}`, signal => customerNotificationApi.contacts(contactPage, contactKeyword, signal), allowed)
  const rules = useResource<Paginated<NotificationRule>>(`customer-notification-rules:${session.generation}:${generation}:${rulePage}:${ruleKeyword}`, signal => customerNotificationApi.rules(rulePage, ruleKeyword, signal), allowed)
  const canWrite = session.audience === 'customer' && session.permissions.includes('unit-notifications:write')
  const refresh = () => { setEditor(null); setGeneration(value => value + 1); contacts.refresh(); rules.refresh() }
  if (!allowed) return <Empty title="当前账户无本单位通知配置读取权限"/>
  return <section aria-label="本单位通知设置"><PageHeading eyebrow="UNIT · NOTIFICATION SETTINGS" title="本单位联系人与报警通知" description="维护联系人，分别设置火警与故障的收件规则。"><RefreshButton onClick={refresh}/>{canWrite && <><Button onClick={() => setEditor({ kind: 'contact' })}>新增联系人</Button><Button variant="outline" onClick={() => setEditor({ kind: 'rule' })}>新增收件规则</Button></>}<DataTransferActions kind="UNIT_CONTACTS" label="联系人数据" tenantId={session.user?.tenant_id ?? undefined} onCompleted={refresh}/><DataTransferActions kind="UNIT_NOTIFICATION_RULES" label="通知规则草稿" tenantId={session.user?.tenant_id ?? undefined} onCompleted={refresh}/><PageHelp title="通知设置与生效范围"><p>仅维护本单位联系人和事件收件规则；供应商密钥、模板、通道测试与全局门禁由管理端配置。</p><p>故障推送必须明确选择并启用故障规则；设置不创建、删除、处置故障或改变设备物理恢复。保存规则不会发送测试通知。</p><p>SENT 仅表示供应商受理，不等于已送达。</p></PageHelp></PageHeading>
    <div className="form-stack">
      <Card><CardContent><div className="demo-side-head"><h2 style={{ margin: 0 }}>联系人</h2><span className="muted">每页 {contacts.data?.size ?? 20} 条，可搜索或翻页</span></div>
        <form className="list-toolbar" onSubmit={event => { event.preventDefault(); setContactKeyword(contactDraft.trim()); setContactPage(1) }}><Input aria-label="搜索本单位联系人" value={contactDraft} onChange={event => setContactDraft(event.target.value)} placeholder="按名称或ID查询，不搜索号码"/><Button type="submit" variant="outline">查询</Button></form>
        {contacts.loading ? <Loading/> : contacts.error != null ? <ErrorNotice error={contacts.error} retry={contacts.refresh}/> : contacts.data?.items.length ? contacts.data.items.map(item => <div className="field" key={item.id}><strong>{item.name}</strong> · {item.phone_masked} · {item.enabled ? '启用' : '停用'}<div className="table-sub">{item.contact_type} · revision {item.revision}</div>{canWrite && <Button type="button" variant="outline" onClick={() => setEditor({ kind: 'contact', item })}>编辑</Button>}</div>) : <Empty title="尚无联系人">新增联系人时号码仅写入并加密保存，之后只返回掩码。</Empty>}
        {!contacts.loading && contacts.error == null && contacts.data && contacts.data.total > contacts.data.size && <Pager page={contacts.data.page} size={contacts.data.size} total={contacts.data.total} onChange={setContactPage}/>}
      </CardContent></Card>
      <Card><CardContent><div className="demo-side-head"><h2 style={{ margin: 0 }}>收件规则</h2><span className="muted">每页 {rules.data?.size ?? 20} 条，可搜索或翻页</span></div>
        <form className="list-toolbar" onSubmit={event => { event.preventDefault(); setRuleKeyword(ruleDraft.trim()); setRulePage(1) }}><Input aria-label="搜索收件规则" value={ruleDraft} onChange={event => setRuleDraft(event.target.value)} placeholder="按名称或ID查询"/><Button type="submit" variant="outline">查询</Button></form>
        {rules.loading ? <Loading/> : rules.error != null ? <ErrorNotice error={rules.error} retry={rules.refresh}/> : rules.data?.items.length ? rules.data.items.map(item => <div className="field" key={item.id}><strong>{item.event_scope === 'FIRE' ? '火警 FIRE' : '故障 FAULT'} · {item.name}</strong> · {item.enabled ? '启用' : '停用'} · revision {item.revision}<div className="table-sub">短信 {item.receivers.filter(receiver => receiver.channel === 'SMS').map(receiver => `${receiver.level}级 ${receiver.contact_name} ${receiver.phone_masked}`).join('、')}；语音 {item.receivers.filter(receiver => receiver.channel === 'VOICE').map(receiver => `${receiver.level}级 ${receiver.contact_name} ${receiver.phone_masked}`).join('、')}</div>{canWrite && <Button type="button" variant="outline" onClick={() => setEditor({ kind: 'rule', item })}>编辑</Button>}</div>) : <Empty title="尚无收件规则">火警与故障分别配置；FAULT 规则需显式选择并启用。</Empty>}
        {!rules.loading && rules.error == null && rules.data && rules.data.total > rules.data.size && <Pager page={rules.data.page} size={rules.data.size} total={rules.data.total} onChange={setRulePage}/>}
      </CardContent></Card>
    </div>
    <Sheet open={editor !== null && canWrite} onOpenChange={open => { if (!open) setEditor(null) }}><SheetContent className="review-sheet notification-sheet">
      {editor?.kind === 'contact' && <ContactEditor item={editor.item} close={() => setEditor(null)} saved={refresh}/>}
      {editor?.kind === 'rule' && <RuleEditor item={editor.item} close={() => setEditor(null)} saved={refresh}/>}
    </SheetContent></Sheet>
  </section>
}
function ContactEditor({ item, close, saved }: { item?: NotificationContact; close: () => void; saved: () => void }) {
  const canWrite = useSession.getState().permissions.includes('unit-notifications:write'), [name, setName] = useState(item?.name ?? ''), [type, setType] = useState(item?.contact_type ?? 'DUTY_OFFICER'), [phone, setPhone] = useState(''), [enabled, setEnabled] = useState(item?.enabled ?? false), [reason, setReason] = useState('')
  const [bindingChoice, setBindingChoice] = useState(item ? 'KEEP' : '')
  const tenantId = useSession.getState().user?.tenant_id ?? ''
  const action = useMutationAction()
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!canWrite || action.busy) return
    try {
      const input: CustomerContactInput = { name: name.trim(), contact_type: type, phone: notificationPhone(phone, !item), ...(bindingChoice === 'KEEP' ? {} : { wechat_binding_id: bindingChoice }), enabled, reason: reason.trim(), ...(item ? { expected_revision: item.revision } : {}) }
      if (!input.reason) throw new Error('必须填写配置原因')
      const importantChange = enabled !== (item?.enabled ?? false) || !!item?.enabled && (!!phone.trim() || bindingChoice !== 'KEEP')
      if (importantChange && !window.confirm(`确认保存本单位联系人“${input.name}”的接收变更？启用状态或已启用的联系方式变更可能影响生效中通知的接收人；本操作不发送测试通知。`)) return
      void action.run((key, signal) => customerNotificationApi.saveContact(item?.id ?? null, input, key, signal), saved)
    } catch (cause) { action.setError(cause) }
  }
  const disabled = action.busy
  return <><SheetHeader className="review-header"><SheetTitle>{item ? '编辑联系人' : '新增联系人'}</SheetTitle><SheetDescription>只维护本单位联系人；电话号码加密存储，编辑时留空保留原号码。</SheetDescription></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}>
    <div className="field"><Label htmlFor="customer-contact-name">姓名</Label><Input id="customer-contact-name" required maxLength={200} value={name} disabled={disabled} onChange={event => { action.touch(); setName(event.target.value) }}/></div>
    <div className="field"><Label htmlFor="customer-contact-type">联系人类型</Label><select id="customer-contact-type" className="native-select" value={type} disabled={disabled} onChange={event => { action.touch(); setType(event.target.value) }}>{['FIRE_RESPONSIBLE','LEGAL_REPRESENTATIVE','CONTROL_ROOM','DUTY_OFFICER','OTHER'].map(value => <option key={value}>{value}</option>)}</select></div>
    <div className="field"><Label htmlFor="customer-contact-phone">电话号码 {item ? '（留空不变）' : ''}</Label><Input id="customer-contact-phone" value={phone} disabled={disabled} onChange={event => { action.touch(); setPhone(event.target.value) }} autoComplete="off"/></div>
    <WechatRecipientSelect value={bindingChoice} onChange={value => { setBindingChoice(value); action.touch() }} disabled={disabled} tenantId={tenantId} keep={!!item}/>
    <label className="field"><span><input type="checkbox" checked={enabled} disabled={disabled} onChange={event => { action.touch(); setEnabled(event.target.checked) }}/> 启用联系人</span></label>
    <Field label="变更原因" value={reason} set={value => { action.touch(); setReason(value) }} disabled={disabled}/>
    {action.error != null && <ErrorNotice error={action.error}/>}
    <div className="identity-form-actions"><Button type="submit" disabled={action.busy}>{action.busy ? '正在保存…' : item ? '保存联系人' : '创建联系人'}</Button><Button type="button" variant="outline" onClick={close}>取消</Button></div>
  </form></div></>
}
function RuleEditor({ item, close, saved }: { item?: NotificationRule; close: () => void; saved: () => void }) {
  const canWrite = useSession.getState().permissions.includes('unit-notifications:write')
  const [name, setName] = useState(item?.name ?? ''), [scope, setScope] = useState<'FIRE'|'FAULT'>(item?.event_scope ?? 'FIRE'), [enabled, setEnabled] = useState(item?.enabled ?? false)
  const [sms, setSms] = useState(() => item?.receivers.filter(r => r.channel === 'SMS').sort((a,b) => a.level-b.level).map(r => r.contact_id) ?? [''])
  const [voice, setVoice] = useState(() => item?.receivers.filter(r => r.channel === 'VOICE').sort((a,b) => a.level-b.level).map(r => r.contact_id) ?? [''])
  const [wechat, setWechat] = useState(() => item?.receivers.filter(r => r.channel === 'WECHAT').sort((a,b) => a.level-b.level).map(r => r.contact_id) ?? [])
  const [smsInterval, setSmsInterval] = useState(item?.sms_escalation_interval_minutes == null ? '' : String(item.sms_escalation_interval_minutes)), [voiceInterval, setVoiceInterval] = useState(item?.voice_escalation_interval_minutes == null ? '' : String(item.voice_escalation_interval_minutes)), [wechatInterval, setWechatInterval] = useState(item?.wechat_escalation_interval_minutes == null ? '' : String(item.wechat_escalation_interval_minutes))
  const [reason, setReason] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [contactPage, setContactPage] = useState(1), [contactKeyword, setContactKeyword] = useState(''), [contactDraft, setContactDraft] = useState('')
  // Labels of every contact seen on any loaded page (or already saved on the rule), so a selection made on
  // one page keeps its name/mask when the picker moves to another page instead of silently losing the ID.
  const [labels, setLabels] = useState<Record<string, { name: string; phone_masked: string }>>(() => Object.fromEntries((item?.receivers ?? []).map(receiver => [receiver.contact_id, { name: receiver.contact_name, phone_masked: receiver.phone_masked }] as const)))
  const known = useResource<Paginated<NotificationContact>>(`customer-rule-contacts:${contactPage}:${contactKeyword}`, signal => customerNotificationApi.contacts(contactPage, contactKeyword, signal), canWrite)
  const wechatRecipients = useResource<WechatRecipient[]>('customer-rule-wechat-recipients', signal => wechatApi.recipients(signal), canWrite)
  const wechatChannel = useResource<WechatChannel>('customer-rule-wechat-channel', signal => wechatApi.channel(signal), canWrite)
  useEffect(() => {
    const pageItems = known.data?.items
    if (!pageItems) return
    setLabels(previous => { const next = { ...previous }; for (const contact of pageItems) next[contact.id] = { name: contact.name, phone_masked: contact.phone_masked }; return next })
  }, [known.data])
  const activeContacts = known.data?.items.filter(contact => contact.enabled) ?? []
  const activeWechatContacts = activeContacts.filter(contact => !!contact.wechat_binding_id)
  function wechatLabel(contactId: string): string | undefined {
    const contact = activeWechatContacts.find(item => item.id === contactId)
    const recipient = contact?.wechat_binding_id ? wechatRecipients.data?.find(item => item.bindingId === contact.wechat_binding_id) : undefined
    return recipient ? `${recipient.userName} · ${recipient.openidMasked}` : undefined
  }
  function searchContacts() { setContactKeyword(contactDraft.trim()); setContactPage(1) }
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!canWrite || busy) return
    try {
      if (!reason.trim()) throw new Error('请填写变更原因')
      for (const [channel, selected] of [['SMS', sms], ['VOICE', voice]] as const) if (!selected.length || selected.length > 5 || selected.some(id => !id) || new Set(selected).size !== selected.length) throw new Error(`${channel === 'SMS' ? '短信' : '语音'}收件人必须从一级开始连续配置且不得重复`)
      if (wechat.length > 5 || wechat.some(id => !id) || new Set(wechat).size !== wechat.length) throw new Error('公众号收件人最多5级、不得重复且每级必须选择已绑定联系人')
      if (wechat.length && activeWechatContacts.length === 0 && !wechat.every(id => labels[id])) throw new Error('公众号收件人必须来自本单位已绑定用户')
      const interval = (value: string, count: number) => { if (!count) return null; if (count === 1 && !value.trim()) return null; if (!/^\d+$/.test(value) || Number(value) > 60) throw new Error('升级间隔必须是0至60分钟整数'); if (count === 1 && Number(value) !== 0) throw new Error('单级规则不消费升级间隔，只能留空或为0'); return Number(value) }
      const input: CustomerRuleInput = { name: name.trim(), enabled, event_scope: scope, receivers: [...sms.map((contact_id, index) => ({ channel: 'SMS' as const, contact_id, level: index + 1 })), ...voice.map((contact_id, index) => ({ channel: 'VOICE' as const, contact_id, level: index + 1 })), ...wechat.map((contact_id, index) => ({ channel: 'WECHAT' as const, contact_id, level: index + 1 }))], sms_escalation_interval_minutes: interval(smsInterval, sms.length), voice_escalation_interval_minutes: interval(voiceInterval, voice.length), wechat_escalation_interval_minutes: interval(wechatInterval, wechat.length), reason: reason.trim(), ...(item ? { expected_revision: item.revision } : {}) }
      const recipients = input.receivers.map(receiver => `${receiver.channel === 'SMS' ? '短信' : receiver.channel === 'VOICE' ? '语音' : '公众号'}第${receiver.level}级：${labels[receiver.contact_id]?.name ?? receiver.contact_id}`).join('\n')
      if (!window.confirm(`确认保存本单位${scope === 'FIRE' ? '火警' : '故障'}收件规则“${input.name}”（${enabled ? '启用' : '停用'}）？\n${recipients}\n该配置可能影响真实报警接收及通知费用，不会立即发送测试通知。`)) return
      setBusy(true); await customerNotificationApi.saveRule(item?.id ?? null, input, crypto.randomUUID()); saved()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '保存失败') } finally { setBusy(false) }
  }
  function channelEditor(channel: 'SMS'|'VOICE'|'WECHAT', selected: string[], setSelected: (value: string[]) => void, interval: string, setInterval: (value: string) => void) {
    const options = channel === 'WECHAT' ? activeWechatContacts : activeContacts
    const label = channel === 'SMS' ? '短信' : channel === 'VOICE' ? '语音' : '公众号'
    return <section className="form-stack" key={channel} aria-label={`${label}收件人`}><h3>{label}收件人及升级{channel === 'WECHAT' ? '（可选）' : ''}</h3>{channel === 'WECHAT' && (wechatChannel.loading ? <Loading/> : wechatChannel.error != null ? <ErrorNotice error={wechatChannel.error} retry={wechatChannel.refresh}/> : wechatChannel.data && <div className={wechatChannel.data.ready ? 'notice' : 'notice danger-note'} role="status">公众号能力：{wechatChannel.data.ready ? '就绪' : '未就绪'} · 可用收件人 {wechatChannel.data.active_recipients} · {wechatChannel.data.message}</div>)}
      {selected.map((contactId, index) => <div className="field" key={`${channel}-${index}`}><Label htmlFor={`customer-rule-${channel}-${index}`}>{index + 1}级联系人</Label><select id={`customer-rule-${channel}-${index}`} className="native-select" required value={contactId} disabled={!canWrite} onChange={event => setSelected(selected.map((id, i) => i === index ? event.target.value : id))}><option value="">{channel === 'WECHAT' ? '选择已关联公众号绑定的联系人' : '选择本单位已启用联系人'}</option>{contactId && !options.some(contact => contact.id === contactId) && <option value={contactId}>{wechatLabel(contactId) ?? labels[contactId]?.name ?? contactId} · {channel === 'WECHAT' ? '当前保存值' : labels[contactId]?.phone_masked ?? '当前保存值（不在当前页）'}</option>}{options.map(contact => <option key={contact.id} value={contact.id}>{channel === 'WECHAT' ? `${wechatLabel(contact.id) ?? contact.name} · 已绑定` : `${contact.name} · ${contact.phone_masked}`}</option>)}</select></div>)}
      <div className="identity-form-actions"><Button type="button" variant="outline" disabled={!canWrite || selected.length >= 5} onClick={() => setSelected([...selected, ''])}>增加级别</Button><Button type="button" variant="outline" disabled={!canWrite || selected.length <= (channel === 'WECHAT' ? 0 : 1)} onClick={() => setSelected(selected.slice(0, -1))}>移除末级</Button></div>
      <div className="field"><Label htmlFor={`customer-rule-${channel}-interval`}>升级间隔（分钟）</Label><Input id={`customer-rule-${channel}-interval`} type="number" min="0" max="60" step="1" disabled={!canWrite || selected.length <= 1} value={interval} onChange={event => setInterval(event.target.value)} placeholder={selected.length <= 1 ? '单级无需间隔' : '0至60分钟'}/></div>
      {channel === 'WECHAT' && !selected.length && <p className="muted-note">未添加公众号收件人；公众号不参与此规则。需要时从已绑定用户中按姓名添加。</p>}
    </section>
  }
  return <><SheetHeader className="review-header"><SheetTitle>{item ? '编辑收件规则' : '新增收件规则'}</SheetTitle><SheetDescription>火警与故障分别配置；短信和语音独立设置收件人及升级间隔，公众号可选。</SheetDescription></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}><div className="field"><Label htmlFor="customer-rule-name">规则名称</Label><Input id="customer-rule-name" required maxLength={200} value={name} onChange={event => setName(event.target.value)}/></div><div className="field"><Label htmlFor="customer-rule-scope">事件范围</Label><select id="customer-rule-scope" className="native-select" value={scope} onChange={event => setScope(event.target.value as 'FIRE'|'FAULT')}><option value="FIRE">火警 FIRE</option><option value="FAULT">故障 FAULT</option></select></div>
    <section className="form-stack" aria-label="联系人目录"><h3>联系人目录</h3><div className="list-toolbar"><Input aria-label="搜索可收件联系人" value={contactDraft} onChange={event => setContactDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); searchContacts() } }} placeholder="按名称或ID查询，不搜索号码"/><Button type="button" variant="outline" onClick={searchContacts}>查询</Button></div><p className="muted-note">逐页加载本单位联系人，每页 {known.data?.size ?? 20} 个；可搜索或翻页选择更多联系人。已选联系人即使不在当前页也会保留并继续提交其ID。</p>
      {known.loading ? <Loading/> : known.error != null ? <ErrorNotice error={known.error} retry={known.refresh}/> : <>{known.data && <div className="muted-note">第 {known.data.page} 页 · 共 {known.data.total} 个联系人 · 当前页已启用 {activeContacts.length} 个</div>}{known.data && known.data.total > known.data.size && <Pager page={known.data.page} size={known.data.size} total={known.data.total} onChange={setContactPage}/>}</>}
    </section>
    {channelEditor('SMS',sms,setSms,smsInterval,setSmsInterval)}{channelEditor('VOICE',voice,setVoice,voiceInterval,setVoiceInterval)}{channelEditor('WECHAT',wechat,setWechat,wechatInterval,setWechatInterval)}<label className="field"><span><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)}/> 启用此事件通知</span></label><Field label="变更原因" value={reason} set={setReason}/><p className="muted-note">订阅和屏蔽只影响外部通知，不修改故障事实或设备物理状态；保存不发送测试通知。公众号收件人只引用本单位已绑定用户，已取消关注或未启用单位通道策略时不会发送。</p><Buttons busy={busy} error={error} close={close}/></form></div></>
}
function Field({ label, value, set, disabled = false }: { label: string; value: string; set: (value: string) => void; disabled?: boolean }) { const id = useId(); return <div className="field"><Label htmlFor={id}>{label}</Label><Input id={id} required value={value} disabled={disabled} onChange={event => set(event.target.value)} autoComplete="off"/></div> }
function Buttons({ busy, error, close }: { busy: boolean; error: string; close: () => void }) { return <><div className="identity-form-actions"><Button type="submit" disabled={busy}>{busy ? '保存中…' : '保存配置'}</Button><Button type="button" variant="outline" onClick={close}>取消</Button></div>{error && <p role="alert">{error}</p>}</> }
