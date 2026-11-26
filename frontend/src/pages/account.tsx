import { DataTransferActions } from '@/components/data-transfer-actions'
import { useEffect, useState, type FormEvent } from 'react'
import { api, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, Loading, PageHeading } from '@/components/common'
import { WechatSelfBinding } from '@/components/wechat-binding'
import { CustomerDateRange, CustomerRecordPager, CustomerRecordsSearch, CustomerRecordTable } from '@/components/customer-records'
import { compileAlarmDraft, emptyAlarmDraft, emptyAlarmFilters } from '@/lib/alarm-workbench'
import type { MyMessage, Paginated } from '@/lib/types'

const names: Record<string, string> = { UNREAD: '未读', READ: '已读', HANDLED: '已处理' }

export function AccountPage() { return <AccountGate><AccountContent/></AccountGate> }

function AccountGate({ children }: { children: React.ReactNode }) {
  const { user, permissions } = useSession()
  if (!user || user.must_change_password || !permissions.includes('inbox:read')) return <Empty title="当前账户无个人中心权限"/>
  return <>{children}</>
}

function AccountContent() {
  const permissions = useSession(s => s.permissions)
  const legacy = useSession(s => s.audience === 'customer')
  const [draft, setDraft] = useState(emptyAlarmDraft), [applied, setApplied] = useState(emptyAlarmFilters)
  const [page, setPage] = useState(1), [size, setSize] = useState(10)
  const [moving, setMoving] = useState(false)
  const [name, setName] = useState(''), [notice, setNotice] = useState(''), [error, setError] = useState<unknown>(null)
  const profile = useResource('me-profile', signal => api.myProfile(signal))
  const sessions = useResource('me-sessions', signal => api.mySessions(signal))
  const messages = useResource(`me-messages:${page}:${size}:${JSON.stringify(applied)}`, signal => {
    if (!legacy) return api.myMessages(1, signal)
    const query = new URLSearchParams({ page: String(page), size: String(size), keyword: applied.keyword })
    if (applied.occurred_from) query.set('from', applied.occurred_from)
    if (applied.occurred_to) query.set('to', applied.occurred_to)
    return request<Paginated<MyMessage>>(`/me/messages?${query}`, { signal })
  })
  const [refreshKey, setRefreshKey] = useState(0)
  useEffect(() => { if (profile.data) setName(profile.data.name) }, [profile.data])

  async function save(event: FormEvent) {
    event.preventDefault(); setError(undefined); setNotice('')
    try { const updated = await api.renameMe(name.trim()); setNotice(`已保存为 ${updated.name}`); profile.refresh() } catch (e) { setError(e) }
  }
  async function revokeOthers() {
    setError(undefined)
    try { const result = await api.revokeOtherSessions(); setNotice(`已撤销 ${result.revoked} 个其它会话`); sessions.refresh() } catch (e) { setError(e) }
  }
  async function act(id: string, revision: number, handle: boolean) {
    if (moving) return
    setError(undefined); setMoving(true)
    try { if (handle) await api.handleMessage(id, revision); else await api.readMessage(id, revision); messages.refresh(); setRefreshKey(v => v + 1) } catch (e) { setError(e) }
    finally { setMoving(false) }
  }
  const unread = messages.data?.items.filter(message => message.status === 'UNREAD') ?? []
  async function readPage() {
    if (moving || !unread.length || !window.confirm(`将本页 ${unread.length} 条未读消息标为已读？`)) return
    setMoving(true); setError(undefined); setNotice('')
    const results = await Promise.allSettled(unread.map(message => api.readMessage(message.id, message.revision)))
    const confirmed = results.filter(result => result.status === 'fulfilled').length
    setNotice(confirmed === unread.length ? `已核对 ${confirmed} 条消息的已读状态` : `已核对 ${confirmed} 条，另外 ${unread.length - confirmed} 条结果未确认，请查看刷新后的列表。`)
    messages.refresh(); setMoving(false)
  }
  if (legacy) return <section className="legacy-record-page" aria-label="通知消息">
    <CustomerRecordsSearch field="通知内容" keyword={draft.keyword} onKeyword={keyword => setDraft(current => ({ ...current, keyword }))}
      onSearch={event => { event.preventDefault(); try { setApplied(compileAlarmDraft(draft)); setPage(1); setError(undefined) } catch (e) { setError(e) } }}
      onReset={() => { setDraft(emptyAlarmDraft); setApplied(emptyAlarmFilters); setPage(1); setError(undefined) }}
      actions={<>
        <button type="button" className="legacy-device-button" disabled={moving || messages.loading || !unread.length} onClick={() => void readPage()}>本页全部已读</button>
        <DataTransferActions kind="MY_NOTIFICATIONS" label="导出全部消息"/>
      </>}>
      <CustomerDateRange from={draft.occurred_from} to={draft.occurred_to} onFrom={occurred_from => setDraft(current => ({ ...current, occurred_from }))}
        onTo={occurred_to => setDraft(current => ({ ...current, occurred_to }))}/>
    </CustomerRecordsSearch>
    {error != null && <ErrorNotice error={error}/>}
    {notice && <p role="status">{notice}</p>}
    <CustomerRecordTable name="通知消息" columns={['消息类型', '通知方式', '通知内容', '通知时间', '状态']}
      loading={messages.loading} error={messages.error} retry={messages.refresh} rows={(messages.data?.items ?? []).map(message => ({
        key: message.id, cells: [message.kind === 'ALARM' ? '报警' : '系统', '站内消息',
          <details className="legacy-message-body"><summary>{message.title}</summary><p>{message.body}</p></details>, dateTime(message.created_at),
          <div className="legacy-record-actions"><span>{names[message.status]}</span>
            {message.status === 'UNREAD' && <button type="button" className="legacy-device-link" disabled={moving} onClick={() => void act(message.id, message.revision, false)}>标为已读</button>}
            {message.status !== 'HANDLED' && permissions.includes('inbox:handle') && <button type="button" className="legacy-device-link" disabled={moving} onClick={() => void act(message.id, message.revision, true)}>标为已处理</button>}
          </div>],
      }))}/>
    {messages.data && <CustomerRecordPager page={messages.data.page} size={messages.data.size} total={messages.data.total}
      onPage={setPage} onSize={next => { setSize(next); setPage(1) }}/>}
    <details className="secondary-controls"><summary>个人资料和会话</summary>
      {profile.loading ? <Loading/> : profile.error != null ? <ErrorNotice error={profile.error} retry={profile.refresh}/> : profile.data && <form className="legacy-record-form" onSubmit={save}>
        <label>昵称<input value={name} onChange={event => setName(event.target.value)}/></label><button type="submit" className="legacy-device-button">保存</button>
      </form>}
      <div className="legacy-record-actions"><button type="button" className="legacy-device-button" disabled={sessions.loading}
        onClick={() => { if (window.confirm('撤销其它设备上的登录会话？')) void revokeOthers() }}>撤销其它会话</button></div>
      {sessions.error != null && <ErrorNotice error={sessions.error} retry={sessions.refresh}/>}
      {sessions.data && <ul>{sessions.data.map((item, index) => <li key={index}>{dateTime(item.created_at)} · 到期 {dateTime(item.expires_at)}{item.current && ' · 当前会话'}</li>)}</ul>}
      {notice && <p role="status">{notice}</p>}
    </details>
  </section>
  return <>
    <PageHeading eyebrow="ACCOUNT · 个人中心" title="我的消息与账号" description="处理站内消息、维护个人资料与登录会话。"><PageHelp title="个人中心说明"><div className="form-stack"><p>消息按当前用户独立记录；已读和已处理是不同状态。</p><p>撤销其它会话只撤销当前账户在其它设备上的登录，不退出当前会话。</p></div></PageHelp></PageHeading>
    <Card className="list-card management-list-panel"><CardContent>
      <div className="management-list-bar"><h2>我的消息</h2><div className="list-toolbar management-list-actions"><DataTransferActions kind="MY_NOTIFICATIONS" label="导出我的消息"/></div></div>
      {messages.loading ? <Loading/> : messages.error != null ? <ErrorNotice error={messages.error} retry={messages.refresh}/> : messages.data && messages.data.items.length ? <Table key={refreshKey}><TableHeader><TableRow><TableHead>标题</TableHead><TableHead>状态</TableHead><TableHead>时间</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{messages.data.items.map(message => <TableRow key={message.id}><TableCell><strong>{message.title}</strong><div className="table-sub">{message.body}</div></TableCell><TableCell><Badge variant="outline">{names[message.status]}</Badge></TableCell><TableCell>{dateTime(message.created_at)}</TableCell><TableCell>{message.status === 'UNREAD' && <Button variant="ghost" size="sm" onClick={() => void act(message.id, message.revision, false)}>标记已读</Button>}{message.status !== 'HANDLED' && permissions.includes('inbox:handle') && <Button variant="ghost" size="sm" onClick={() => void act(message.id, message.revision, true)}>标记已处理</Button>}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="暂无站内消息"/>}
    </CardContent></Card>
    <Card className="list-card management-list-panel"><CardContent><div className="management-list-bar"><h2>我的资料</h2></div>
      {profile.loading ? <Loading/> : profile.error != null ? <ErrorNotice error={profile.error} retry={profile.refresh}/> : profile.data && <form className="form-stack" onSubmit={save}><dl className="detail-list"><div><dt>账号</dt><dd>{profile.data.username}</dd></div><div><dt>类型</dt><dd>{profile.data.user_type}</dd></div><div><dt>单位</dt><dd>{profile.data.tenant_id ?? '平台'}</dd></div></dl><div className="field"><Label htmlFor="me-name">姓名</Label><Input id="me-name" value={name} onChange={e => setName(e.target.value)}/></div><div className="identity-form-actions"><Button type="submit">保存资料</Button></div></form>}
      {notice && <p role="status" className="notice">{notice}</p>}
      {error != null && <ErrorNotice error={error}/>}
    </CardContent></Card>
    <Card className="list-card management-list-panel"><CardContent><div className="management-list-bar"><h2>我的会话</h2><div className="list-toolbar management-list-actions"><Button type="button" variant="outline" onClick={() => void revokeOthers()}>撤销其它会话</Button></div></div>
      {sessions.loading ? <Loading/> : sessions.error != null ? <ErrorNotice error={sessions.error} retry={sessions.refresh}/> : sessions.data && sessions.data.length ? <Table><TableHeader><TableRow><TableHead>登录时间</TableHead><TableHead>过期时间</TableHead><TableHead>当前</TableHead></TableRow></TableHeader><TableBody>{sessions.data.map((session, index) => <TableRow key={index}><TableCell>{session.created_at ? dateTime(session.created_at) : '未记录'}</TableCell><TableCell>{dateTime(session.expires_at)}</TableCell><TableCell>{session.current ? '当前会话' : ''}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="没有有效会话"/>}
    </CardContent></Card>
    <WechatSelfBinding/>
  </>
}
