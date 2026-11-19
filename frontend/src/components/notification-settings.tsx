import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { Paginated, ScopeUnit } from '@/lib/types'
import { useResource } from '@/lib/use-resource'
import { notificationApi, notificationPhone, type NotificationContact } from '@/lib/notifications'
import { publishObjectChange } from '@/lib/object-events'
import { dateTime } from '@/lib/format'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Card, CardContent } from './ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Empty, ErrorNotice, PageHeading, Pager, RefreshButton } from './common'
import { PageHelp } from './page-tools'
import { IdentitySearch, OperationConfirmation, useMutationAction } from './identity-common'
import { WechatRecipientSelect } from './wechat-binding'
const typeNames: Record<string, string> = { FIRE_RESPONSIBLE: '消防责任人', LEGAL_REPRESENTATIVE: '法定代表人', CONTROL_ROOM: '消控室', DUTY_OFFICER: '值班人员', OTHER: '其他' }
const contactTypes = Object.keys(typeNames)
type ContactSelection = { id: string | null; tenantId?: string; tenantName?: string }
export function NotificationConfiguration() {
  const permissions = useSession(s => s.permissions)
  const canWrite = permissions.includes('notification-config:write')
  const scopeKey = useSession(s => `${s.scopeTenantIds.join(',')}|${s.scopePlaceIds.join(',')}|${s.scopeInitialized}`)
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [editor, setEditor] = useState<ContactSelection | null>(null)
  const rows = useResource<Paginated<NotificationContact>>(`notification-contacts:${scopeKey}:${page}:${keyword}`, signal => notificationApi.contacts(page, keyword, signal))
  useEffect(() => { setPage(1) }, [scopeKey])
  const refresh = () => { setEditor(null); rows.refresh(); publishObjectChange('notifications') }
  return <>
    <PageHeading eyebrow="NOTIFICATION · 联系人" title="通知联系人" description="维护短信、语音通知的联系人及启用状态。">
      <RefreshButton onClick={rows.refresh}/>
      {canWrite && <Button onClick={() => setEditor({ id: null })}>创建通知联系人</Button>}
      <PageHelp title="联系人与通知规则"><p>这里维护联系人资料；实际短信、语音收件人和升级间隔在“报警与故障通知规则”页签配置。保存联系人不代表已发送通知。</p><p>号码加密保存，仅展示服务端返回的遮罩，不支持按完整号码搜索。编辑留空保留原号码，不回填明文。</p><p>授权范围栏只缩小读取范围，具体编辑仍绑定一个明确单位。联系方式格式合法不表示号码有效、能接通或通知已送达。</p></PageHelp>
    </PageHeading>
    <Card className="list-card"><CardContent>
      <IdentitySearch aria-label="搜索通知联系人" placeholder="按名称或ID查询" onSearch={value => { setPage(1); setKeyword(value) }}/>
      {rows.loading ? <p>正在读取联系人…</p> : rows.error != null ? <ErrorNotice error={rows.error} retry={rows.refresh}/> : rows.data ? <>
        {rows.data.items.length ? <Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>名称 / ID</TableHead><TableHead>类型 / 号码</TableHead><TableHead>状态</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>
          {rows.data.items.map(item => <TableRow key={item.id} data-contact-id={item.id}>
            <TableCell>{item.tenant_name ?? '单位未提供'}<div className="table-sub mono">{item.tenant_id ?? '单位ID未提供'}</div></TableCell>
            <TableCell>{item.name}<div className="table-sub mono">{item.id}</div></TableCell>
            <TableCell>{typeNames[item.contact_type] ?? item.contact_type}<div className="table-sub">{item.phone_masked}</div></TableCell>
            <TableCell title={`版本 ${item.revision}`}>{item.enabled ? '已启用' : '未启用'}</TableCell>
            <TableCell><Button variant="outline" size="sm" disabled={!item.tenant_id} title={!item.tenant_id ? '联系人所属单位未明确，不能读取或编辑' : undefined} onClick={() => setEditor({ id: item.id, tenantId: item.tenant_id, tenantName: item.tenant_name })}>{canWrite ? '编辑联系人' : '查看配置'}</Button></TableCell>
          </TableRow>)}
        </TableBody></Table> : <Empty title="暂无通知联系人"/>}
        <Pager page={rows.data.page} size={rows.data.size} total={rows.data.total} onChange={setPage}/>
      </> : null}
    </CardContent></Card>
    <Sheet open={!!editor} onOpenChange={open => { if (!open) setEditor(null) }}><SheetContent className="review-sheet notification-sheet">{editor && <ContactEditor key={`${editor.id ?? 'create'}:${editor.tenantId ?? ''}:${canWrite}`} editor={editor} canWrite={canWrite} saved={refresh}/>}</SheetContent></Sheet>
  </>
}
function ContactEditor({ editor, canWrite, saved }: { editor: ContactSelection; canWrite: boolean; saved: () => void }) {
  const detail = useResource(`notification-contact:${editor.tenantId ?? ''}:${editor.id}`, signal => notificationApi.contact(editor.id!, signal, editor.tenantId), !!editor.id && !!editor.tenantId)
  const targetError = editor.id && !editor.tenantId ? '联系人所属单位未明确，请重新读取列表后操作' : detail.data && (detail.data.id !== editor.id || detail.data.tenant_id !== undefined && detail.data.tenant_id !== editor.tenantId) ? '联系人详情与原选择不一致，已停止编辑，请重新读取列表核对' : null
  if (targetError) return <><SheetHeader className="review-header"><SheetTitle>联系人详情未确认</SheetTitle><SheetDescription>记录缺少或不一致的原单位归属，不会改用其他目标。</SheetDescription></SheetHeader><ErrorNotice error={new Error(targetError)}/></>
  return editor.id ? detail.loading ? <><SheetHeader className="review-header"><SheetTitle>通知联系人</SheetTitle><SheetDescription>正在读取服务端联系人记录。</SheetDescription></SheetHeader></> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : detail.data ? <ContactForm key={`${detail.data.id}:${detail.data.revision}`} item={detail.data} tenantId={editor.tenantId} tenantName={editor.tenantName} canWrite={canWrite} saved={saved}/> : null : <ContactForm tenantId={editor.tenantId} tenantName={editor.tenantName} canWrite={canWrite} saved={saved}/>
}
function ContactForm({ item, tenantId: initialTenantId, tenantName: initialTenantName, canWrite, saved }: { item?: NotificationContact; tenantId?: string; tenantName?: string; canWrite: boolean; saved: () => void }) {
  const [name, setName] = useState(item?.name ?? ''), [enabled, setEnabled] = useState(item?.enabled ?? false), [phone, setPhone] = useState(''), [type, setType] = useState(item?.contact_type ?? ''), [reason, setReason] = useState('')
  const [bindingChoice, setBindingChoice] = useState(item ? 'KEEP' : '')
  const [tenantId, setTenantId] = useState(initialTenantId ?? item?.tenant_id ?? '')
  const units = useResource<ScopeUnit[]>('notification-contact-units', signal => api.scopeTree(signal), !item && canWrite)
  const unitName = item ? initialTenantName ?? item.tenant_name ?? tenantId : units.data?.find(unit => unit.id === tenantId)?.name ?? (tenantId || '待选择')
  useEffect(() => { if (!item && !tenantId && units.data?.length === 1) setTenantId(units.data[0].id) }, [item, tenantId, units.data])
  const action = useMutationAction()
  const importantChange = enabled !== (item?.enabled ?? false) || !!item?.enabled && (!!phone.trim() || bindingChoice !== 'KEEP')
  useEffect(() => { if (action.error instanceof ApiError && action.error.status === 403) { setPhone(''); action.setConfirming(false) } }, [action.error])
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canWrite) return
    try {
      if (!tenantId) throw new Error('请明确选择联系人所属单位')
      if (!item && !units.data?.some(unit => unit.id === tenantId)) throw new Error('所属单位未通过当前授权目录核验，请刷新后重试')
      if (!name.trim()) throw new Error('请填写联系人名称')
      if (!contactTypes.includes(type)) throw new Error('请选择联系人类型')
      if (!reason.trim()) throw new Error('请填写本次操作原因')
      const suppliedPhone = notificationPhone(phone, !item)
      if (importantChange && !action.confirming) { action.setError(undefined); action.setConfirming(true); return }
      void action.run((key, signal) => notificationApi.saveContact(item?.id ?? null, { name: name.trim(), enabled, contact_type: type, phone: suppliedPhone, ...(item ? (bindingChoice === 'KEEP' ? {} : { wechat_binding_id: bindingChoice }) : (bindingChoice ? { wechat_binding_id: bindingChoice } : {})), reason: reason.trim(), expected_revision: item?.revision }, key, signal, tenantId), () => { setPhone(''); saved() })
    } catch (error) { action.setError(error) }
  }
  return <><SheetHeader className="review-header"><SheetTitle>{item ? canWrite ? '编辑联系人' : '查看联系人' : '创建通知联系人'}</SheetTitle><SheetDescription>所属单位：{unitName}。人工维护的联系方式资料；号码只写不读，编辑留空保留，不回填原号码。格式不代表号码真实有效、可接通或通知已送达。</SheetDescription></SheetHeader><div className="review-body form-stack">{item && <p className="mono">{item.id} · revision {item.revision} · {dateTime(item.created_at)}</p>}{!canWrite ? <dl className="detail-list"><div><dt>名称</dt><dd>{item?.name}</dd></div><div><dt>类型</dt><dd>{item && (typeNames[item.contact_type] ?? item.contact_type)}</dd></div><div><dt>号码遮罩</dt><dd>{item?.phone_masked}</dd></div><div><dt>状态</dt><dd>{item?.enabled ? '已启用' : '未启用'}</dd></div><div><dt>微信绑定</dt><dd>{item?.wechat_binding_id ? '已关联公众号绑定' : '未关联'}</dd></div></dl> : <form className="form-stack" onSubmit={submit}>{!action.confirming ? <fieldset className="form-stack" disabled={action.busy}>
    {!item && <div className="field"><label htmlFor="notification-contact-unit">联系人所属单位</label><select id="notification-contact-unit" className="native-select" value={tenantId} required disabled={units.loading || units.error != null} onChange={event => { setTenantId(event.target.value); setBindingChoice(''); action.touch() }}><option value="">请选择单位</option>{units.data?.map(unit => <option key={unit.id} value={unit.id}>{unit.name}</option>)}</select>{units.loading && <p>正在读取授权单位…</p>}{units.error != null && <ErrorNotice error={units.error} retry={units.refresh}/>}</div>}
    <div className="field"><label htmlFor="notification-contact-name">联系人名称</label><Input id="notification-contact-name" value={name} onChange={event => { setName(event.target.value); action.touch() }} required/></div><div className="field"><label htmlFor="notification-contact-type">联系人类型</label><select id="notification-contact-type" className="native-select" value={type} required onChange={event => { setType(event.target.value); action.touch() }}><option value="">请显式选择联系人类型</option>{contactTypes.map(value => <option key={value} value={value}>{typeNames[value]} · {value}</option>)}</select></div>{item && <p>已存号码遮罩：{item.phone_masked}</p>}<div className="field"><label htmlFor="notification-contact-phone">{item ? '替换号码（留空保留）' : '联系电话'}</label><Input id="notification-contact-phone" type="tel" autoComplete="off" value={phone} required={!item} onChange={event => { setPhone(event.target.value); action.touch() }}/></div><WechatRecipientSelect value={bindingChoice} onChange={value => { setBindingChoice(value); action.touch() }} disabled={action.busy || !tenantId} tenantId={tenantId} keep={!!item}/><label className="notification-checkbox"><input type="checkbox" checked={enabled} onChange={event => { setEnabled(event.target.checked); action.touch() }}/>启用联系人（创建默认未启用）</label><div className="field"><label htmlFor="notification-contact-reason">操作原因</label><textarea id="notification-contact-reason" value={reason} required rows={3} onChange={event => { setReason(event.target.value); action.touch() }}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit" disabled={action.busy}>{action.busy ? '正在保存…' : importantChange ? '核对联系人变更' : item ? '保存联系人' : '创建联系人'}</Button></fieldset> : <OperationConfirmation action={action} title="确认联系人变更" submitLabel={item ? '确认保存联系人' : '确认创建联系人'} refresh={saved} warning="启用状态或已启用联系人的号码、微信绑定变更可能影响生效中通知规则的接收人；只有规则实际命中才发送通知。"><p>所属单位：{unitName}</p><p>联系人：{name.trim()}</p><p>启用状态：{enabled ? '启用' : '停用'}</p><p>电话号码：{phone.trim() ? '已填写新号码（不回显）' : '保留原号码'}</p><p>微信绑定：{bindingChoice === 'KEEP' ? '保持原绑定' : bindingChoice ? '改为所选绑定' : '不绑定'}</p><p>操作原因：{reason.trim()}</p></OperationConfirmation>}</form>}</div></>
}
