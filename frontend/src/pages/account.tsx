import { DataTransferActions } from '@/components/data-transfer-actions'
import { useState, type FormEvent, type ReactNode } from 'react'
import { ArrowLeft, BellRing, Mail, UserRound } from 'lucide-react'
import { ApiError, api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice, Loading, PageHeading, RefreshButton } from '@/components/common'
import { useMutationAction } from '@/components/identity-common'
import { WechatSelfBinding } from '@/components/wechat-binding'
import type { MyMessage, MyProfile } from '@/lib/types'
import '../customer/customer.css'

const names: Record<MyMessage['status'], string> = { UNREAD: '未读', READ: '已读', HANDLED: '已处理' }

export function AccountPage() { return <AccountGate><AccountContent/></AccountGate> }

function AccountGate({ children }: { children: ReactNode }) {
  const { user, permissions } = useSession()
  if (!user || user.must_change_password || !permissions.includes('inbox:read')) return <Empty title="当前账户无个人中心权限"/>
  return <>{children}</>
}

function AccountContent() {
  const { audience, navigate } = useSession()
  return <div className="customer-hub">
    <PageHeading eyebrow="账号与消息" title="消息与资料">
      {audience === 'customer' && <Button type="button" variant="outline" onClick={() => navigate('my-profile')}><ArrowLeft/>返回我的</Button>}
    </PageHeading>
    <div className="customer-account-layout">
      <MessageInbox/>
      <div className="customer-account-stack">
        <ProfileDetails/>
        <LoginSessions/>
        {audience === 'management' && <WechatSelfBinding/>}
      </div>
    </div>
  </div>
}

function MessageInbox() {
  const canHandle = useSession(s => s.permissions.includes('inbox:handle'))
  const [page, setPage] = useState(1)
  const messages = useResource(`me-messages:${page}`, signal => api.myMessages(page, signal))
  const action = useMutationAction()
  function refresh() { action.touch(); messages.refresh() }
  function act(message: MyMessage, handle: boolean) {
    void action.run((_, signal) => handle ? api.handleMessage(message.id, message.revision, signal) : api.readMessage(message.id, message.revision, signal), refresh)
  }
  return <Card><CardContent>
    <div className="customer-section-head">
      <h2>站内消息</h2>
      <div className="customer-controls"><RefreshButton onClick={refresh}/><DataTransferActions kind="MY_NOTIFICATIONS" label="导出我的消息"/></div>
    </div>
    {action.error != null && <ErrorNotice error={action.error}/>}
    {messages.loading ? <Loading/> : messages.error != null ? <ErrorNotice error={messages.error} retry={refresh}/> : messages.data && <>
      {messages.data.items.length ? <ul className="customer-records">
        {messages.data.items.map(message => {
          const Icon = message.kind === 'ALARM' ? BellRing : Mail
          return <li key={message.id} className="customer-message">
            <span className="customer-record-icon" aria-hidden="true"><Icon size={17}/></span>
            <article>
              <div className="customer-message-title"><h3>{message.title}</h3><Badge variant={message.status === 'UNREAD' ? 'secondary' : 'outline'}>{names[message.status]}</Badge></div>
              <time dateTime={message.created_at}>{dateTime(message.created_at)}</time>
              <p>{message.body}</p>
              {message.status !== 'HANDLED' && <div className="customer-controls">
                {message.status === 'UNREAD' && <Button type="button" variant="outline" onClick={() => act(message, false)} disabled={action.busy || action.error != null}>标记已读</Button>}
                {canHandle && <Button type="button" variant="outline" onClick={() => act(message, true)} disabled={action.busy || action.error != null}>标记已处理</Button>}
              </div>}
            </article>
          </li>
        })}
      </ul> : <Empty title="暂无站内消息"/>}
      {messages.data.total > messages.data.size && <nav className="customer-inbox-pager" aria-label="站内消息分页">
        <Button type="button" variant="outline" disabled={page <= 1 || action.busy} onClick={() => { action.touch(); setPage(page - 1) }}>上一页</Button>
        <span>第 {page} / {Math.ceil(messages.data.total / messages.data.size)} 页</span>
        <Button type="button" variant="outline" disabled={page * messages.data.size >= messages.data.total || action.busy} onClick={() => { action.touch(); setPage(page + 1) }}>下一页</Button>
      </nav>}
    </>}
  </CardContent></Card>
}

function ProfileDetails() {
  const profile = useResource('me-profile', signal => api.myProfile(signal))
  return <Card><CardContent>
    <div className="customer-section-head"><h2>个人资料</h2><UserRound size={18} aria-hidden="true"/></div>
    {profile.loading ? <Loading/> : profile.error != null ? <ErrorNotice error={profile.error} retry={profile.refresh}/> : profile.data && <RenameProfile profile={profile.data}/>}
  </CardContent></Card>
}

function RenameProfile({ profile }: { profile: MyProfile }) {
  const [savedName, setSavedName] = useState(profile.name)
  const [name, setName] = useState(profile.name)
  const [notice, setNotice] = useState('')
  const action = useMutationAction()
  function save(event: FormEvent) {
    event.preventDefault()
    setNotice('')
    if (!name.trim() || name.trim() === savedName) return
    let confirmedName = ''
    void action.run(async (_, signal) => {
      const updated = await api.renameMe(name.trim(), signal)
      if (updated.id !== profile.id || typeof updated.name !== 'string' || !updated.name) throw new Error('服务端未确认姓名修改结果，请重新读取资料')
      confirmedName = updated.name
    }, () => {
      setSavedName(confirmedName)
      setName(confirmedName)
      useSession.setState(state => state.user?.id === profile.id ? { user: { ...state.user, name: confirmedName } } : state)
      setNotice('姓名已保存。')
    })
  }
  return <form className="form-stack" onSubmit={save}>
    <dl className="detail-list">
      <div><dt>登录账号</dt><dd>{profile.username}</dd></div>
      <div><dt>账号类型</dt><dd>{profile.user_type === 'CUSTOMER' ? '客户账户' : '平台账户'}</dd></div>
    </dl>
    <div className="field"><Label htmlFor="me-name">姓名</Label><Input id="me-name" required maxLength={200} value={name} disabled={action.busy} onChange={e => { setName(e.target.value); setNotice(''); action.touch() }}/></div>
    {notice && <p role="status" className="notice">{notice}</p>}
    {action.error != null && <ErrorNotice error={action.error}/>}
    <div className="identity-form-actions"><Button type="submit" disabled={action.busy || !name.trim() || name.trim() === savedName}>{action.busy ? '保存中…' : '保存资料'}</Button></div>
  </form>
}

function LoginSessions() {
  const sessions = useResource('me-sessions', signal => api.mySessions(signal))
  const [notice, setNotice] = useState('')
  const action = useMutationAction()
  const otherSessions = sessions.data?.filter(session => !session.current).length ?? 0
  const unconfirmed = action.error != null && (!(action.error instanceof ApiError) || action.error.status === 0 || action.error.status >= 500 || action.error.code === 'INVALID_RESPONSE')
  function refresh() { action.touch(); sessions.refresh() }
  function revoke(event: FormEvent) {
    event.preventDefault()
    setNotice('')
    if (!action.confirming || unconfirmed) return
    let revoked = 0
    void action.run(async (_, signal) => { ({ revoked } = await api.revokeOtherSessions(signal)) }, () => {
      setNotice(`已撤销 ${revoked} 个其他会话。`)
      refresh()
    })
  }
  return <Card><CardContent>
    <div className="customer-section-head"><h2>登录会话</h2><RefreshButton onClick={refresh}/></div>
    {notice && <p role="status" className="notice">{notice}</p>}
    {sessions.loading ? <Loading/> : sessions.error != null ? <ErrorNotice error={sessions.error} retry={refresh}/> : sessions.data?.length ? <>
      <ul className="customer-records">
        {sessions.data.map((session, index) => <li key={index} className="customer-session">
          <strong>{session.current ? '当前会话' : '其他会话'}</strong>
          <div>登录：{session.created_at ? dateTime(session.created_at) : '未记录'}</div>
          <div>到期：{dateTime(session.expires_at)}</div>
        </li>)}
      </ul>
      {action.error != null && <ErrorNotice error={action.error}/>}
      {unconfirmed ? <p role="status">撤销结果未确认。请刷新会话列表核对，不会自动重试。</p> : action.confirming ? <form className="sensitive-confirmation" onSubmit={revoke}>
        <p>撤销本账号全部其他登录会话；当前会话保留。</p>
        <div className="customer-controls"><Button type="button" variant="outline" disabled={action.busy} onClick={action.touch}>取消</Button><Button type="submit" variant="destructive" disabled={action.busy}>确认撤销</Button></div>
      </form> : otherSessions > 0 && <Button type="button" variant="outline" onClick={() => action.setConfirming(true)}>撤销其他会话</Button>}
    </> : <Empty title="没有有效会话"/>}
  </CardContent></Card>
}
