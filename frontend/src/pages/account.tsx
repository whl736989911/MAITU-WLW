import { DataTransferActions } from '@/components/data-transfer-actions'
import { useEffect, useState, type FormEvent } from 'react'
import { api } from '@/lib/api'
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

const names: Record<string, string> = { UNREAD: '未读', READ: '已读', HANDLED: '已处理' }

export function AccountPage() { return <AccountGate><AccountContent/></AccountGate> }

function AccountGate({ children }: { children: React.ReactNode }) {
  const { user, permissions } = useSession()
  if (!user || user.must_change_password || !permissions.includes('inbox:read')) return <Empty title="当前账户无个人中心权限"/>
  return <>{children}</>
}

function AccountContent() {
  const permissions = useSession(s => s.permissions)
  const [name, setName] = useState(''), [notice, setNotice] = useState(''), [error, setError] = useState<unknown>(null)
  const profile = useResource('me-profile', signal => api.myProfile(signal))
  const sessions = useResource('me-sessions', signal => api.mySessions(signal))
  const messages = useResource('me-messages', signal => api.myMessages(1, signal))
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
    setError(undefined)
    try { if (handle) await api.handleMessage(id, revision); else await api.readMessage(id, revision); messages.refresh(); setRefreshKey(v => v + 1) } catch (e) { setError(e) }
  }
  return <><PageHeading eyebrow="ACCOUNT · 个人中心" title="我的消息与账号" description="处理站内消息、维护个人资料与登录会话。"><PageHelp title="个人中心说明"><div className="form-stack"><p>消息按当前用户独立记录；已读和已处理是不同状态。</p><p>撤销其它会话只撤销当前账户在其它设备上的登录，不退出当前会话。</p></div></PageHelp><DataTransferActions kind="MY_NOTIFICATIONS" label="导出我的消息"/></PageHeading>
    <Card><CardContent><h2>我的消息</h2>
      {messages.loading ? <Loading/> : messages.error != null ? <ErrorNotice error={messages.error} retry={messages.refresh}/> : messages.data && messages.data.items.length ? <Table key={refreshKey}><TableHeader><TableRow><TableHead>标题</TableHead><TableHead>状态</TableHead><TableHead>时间</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{messages.data.items.map(message => <TableRow key={message.id}><TableCell><strong>{message.title}</strong><div className="table-sub">{message.body}</div></TableCell><TableCell><Badge variant="outline">{names[message.status]}</Badge></TableCell><TableCell>{dateTime(message.created_at)}</TableCell><TableCell>{message.status === 'UNREAD' && <Button variant="ghost" size="sm" onClick={() => void act(message.id, message.revision, false)}>标记已读</Button>}{message.status !== 'HANDLED' && permissions.includes('inbox:handle') && <Button variant="ghost" size="sm" onClick={() => void act(message.id, message.revision, true)}>标记已处理</Button>}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="暂无站内消息"/>}
    </CardContent></Card>
    <Card><CardContent><h2>我的资料</h2>
      {profile.loading ? <Loading/> : profile.error != null ? <ErrorNotice error={profile.error} retry={profile.refresh}/> : profile.data && <form className="form-stack" onSubmit={save}><dl className="detail-list"><div><dt>账号</dt><dd>{profile.data.username}</dd></div><div><dt>类型</dt><dd>{profile.data.user_type}</dd></div><div><dt>单位</dt><dd>{profile.data.tenant_id ?? '平台'}</dd></div></dl><div className="field"><Label htmlFor="me-name">姓名</Label><Input id="me-name" value={name} onChange={e => setName(e.target.value)}/></div><div className="identity-form-actions"><Button type="submit">保存资料</Button></div></form>}
      {notice && <p role="status" className="notice">{notice}</p>}
      {error != null && <ErrorNotice error={error}/>}
    </CardContent></Card>
    <Card><CardContent><div className="list-toolbar"><h2>我的会话</h2><Button type="button" variant="outline" onClick={() => void revokeOthers()}>撤销其它会话</Button></div>
      {sessions.loading ? <Loading/> : sessions.error != null ? <ErrorNotice error={sessions.error} retry={sessions.refresh}/> : sessions.data && sessions.data.length ? <Table><TableHeader><TableRow><TableHead>登录时间</TableHead><TableHead>过期时间</TableHead><TableHead>当前</TableHead></TableRow></TableHeader><TableBody>{sessions.data.map((session, index) => <TableRow key={index}><TableCell>{session.created_at ? dateTime(session.created_at) : '未记录'}</TableCell><TableCell>{dateTime(session.expires_at)}</TableCell><TableCell>{session.current ? '当前会话' : ''}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="没有有效会话"/>}
    </CardContent></Card>
    <WechatSelfBinding/>
  </>
}
