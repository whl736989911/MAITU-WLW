import { useState, type FormEvent } from 'react'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { alarmSessionsApi, type AlarmSession } from '@/lib/alarm-sessions'
import { dateTime, reported } from '@/lib/format'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { AlarmDetail } from '@/components/alarm-detail'
import { DataTransferActions } from '@/components/data-transfer-actions'

export function AlarmSessionsPage() {
  const { user, permissions, generation } = useSession()
  if (!user || user.must_change_password || !permissions.includes('alarm:read')) return <><PageHeading eyebrow="ALARMS · 逻辑会合组" title="报警会合组"/><Empty title="当前账户无报警查看权限"/></>
  return <AlarmSessionsContent key={generation}/>
}

function AlarmSessionsContent() {
  const { scopeTenantIds, scopePlaceIds, scopeInitialized } = useSession()
  const scopeEmpty = scopeInitialized && scopeTenantIds.length === 0 && scopePlaceIds.length === 0
  const [page, setPage] = useState(1)
  const size = undefined
  const [deviceId, setDeviceId] = useState(''), [appliedDeviceId, setAppliedDeviceId] = useState('')
  const [eventType, setEventType] = useState(''), [appliedEventType, setAppliedEventType] = useState('')
  const [selected, setSelected] = useState<AlarmSession | null>(null), [memberPage, setMemberPage] = useState(1)
  const [alarmDetail, setAlarmDetail] = useState<{ id: string; tenantId?: string } | null>(null)
  const scopeKey = `${scopeTenantIds.join(',')}|${scopePlaceIds.join(',')}`
  const listKey = `${scopeKey}:${page}:${size ?? ''}:${appliedDeviceId}:${appliedEventType}`
  const sessions = useResource(`alarm-sessions:${listKey}`, signal => alarmSessionsApi.list(page, size, signal, scopeTenantIds, scopePlaceIds, appliedDeviceId, appliedEventType), scopeInitialized && !scopeEmpty)
  const detail = useResource(`alarm-session:${selected?.id ?? 'none'}:${selected?.revision ?? 0}`, signal => selected ? alarmSessionsApi.detail(selected.id, signal, selected.tenant_id) : Promise.resolve(undefined), !!selected)
  const members = useResource(`alarm-session-members:${selected?.id ?? 'none'}:${memberPage}`, signal => selected ? alarmSessionsApi.members(selected.id, memberPage, size, signal, selected.tenant_id) : Promise.resolve(undefined), !!selected)
  function apply(event: FormEvent) {
    event.preventDefault()
    setAppliedDeviceId(deviceId.trim()); setAppliedEventType(eventType); setPage(1); setSelected(null)
  }
  function choose(next: AlarmSession | null) { setSelected(next); setMemberPage(1); setAlarmDetail(null) }
  function refreshAll() { sessions.refresh(); detail.refresh(); members.refresh() }

  if (!scopeInitialized) return <><PageHeading eyebrow="ALARMS · 逻辑会合组" title="报警会合组"/><Loading/></>
  if (scopeEmpty) return <><PageHeading eyebrow="ALARMS · 逻辑会合组" title="报警会合组"/><Empty title="当前范围未选择">会合组只会在授权的单位/位置范围内读取；请在左侧范围树选择至少一个单位或位置。未选择不会自动放宽到全部授权范围。</Empty></>
  return <>
    <PageHeading eyebrow="ALARMS · 归并记录" title="报警归并" description="按授权范围查看归并记录与独立的真实报警。"><DataTransferActions kind="ALARM_SESSIONS" label="导出归并记录" filters={{ unit_ids: scopeTenantIds.join(','), place_ids: scopePlaceIds.join(','), device_id: appliedDeviceId, event_type: appliedEventType }}/><RefreshButton onClick={refreshAll}/></PageHeading>
    <div className="notice" role="status"><strong>会合组不改变报警事实</strong><p>组内每位成员仍保留自己的物理状态、复核结论、通知发送尝试与历史。打开成员进入原报警详情/复核；这里没有批量复核、伪造恢复或删除入口。会合组关闭或策略变化都不会清除成员。</p></div>
    <Card><CardContent>
      <form className="list-toolbar" onSubmit={apply}>
        <div className="field"><Label htmlFor="session-device">设备ID（精确）</Label><Input id="session-device" value={deviceId} maxLength={64} placeholder="留空表示当前范围全部设备" onChange={e => setDeviceId(e.target.value)}/></div>
        <div className="field"><Label htmlFor="session-event">事件类型</Label><select id="session-event" className="native-select" value={eventType} onChange={e => setEventType(e.target.value)}><option value="">全部（当前仅FIRE）</option><option value="FIRE">FIRE</option></select></div>
        <Button type="submit">应用筛选</Button>
      </form>
      {sessions.loading ? <Loading/> : sessions.error != null ? <ErrorNotice error={sessions.error} retry={sessions.refresh}/> : sessions.data && sessions.data.items.length ? <>
        <div className="table-wrap"><Table><TableHeader><TableRow><TableHead>会话 / 策略</TableHead><TableHead>单位与设备</TableHead><TableHead>源时间窗口</TableHead><TableHead>成员 / 活动 / 未复核</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader><TableBody>{sessions.data.items.map(session => <TableRow key={session.id}><TableCell><strong className="mono">{session.id}</strong><div className="table-sub">捕获策略 revision {session.policy_revision} · {session.policy_state === 'CURRENT' ? '当前策略' : session.policy_state === 'STALE' ? '策略已变化（仅随新报警生效）' : '策略记录不可确认'} · 会话 revision {session.revision}</div></TableCell><TableCell>{session.device_name}<div className="table-sub">{session.address}</div><div className="table-sub mono">{session.tenant_id} · {session.device_id}</div></TableCell><TableCell>{dateTime(session.first_source_at)} → {dateTime(session.last_source_at)}<div className="table-sub">窗口 {session.window_seconds} 秒 · {session.open ? <Badge variant="outline">收集中</Badge> : <Badge variant="outline">窗口已关闭</Badge>}</div>{session.window_ends_at && <div className="table-sub">窗口理论截止 {dateTime(session.window_ends_at)}</div>}</TableCell><TableCell>{session.member_count} 条源报警<div className="table-sub">活动物理成员 {session.active_member_count} · 未复核 {session.unhandled_member_count}</div></TableCell><TableCell className="text-right"><Button type="button" variant={selected?.id === session.id ? 'default' : 'outline'} onClick={() => choose(selected?.id === session.id ? null : session)}>{selected?.id === session.id ? '收起成员' : '查看成员'}</Button></TableCell></TableRow>)}</TableBody></Table></div>
        <Pager page={sessions.data.page} size={sessions.data.size} total={sessions.data.total} onChange={setPage}/>
        <div className="table-sub">会合组按源上报事件的新建物理报警建立；源时间去重后更新同一物理报警的既有计数不产生新成员。策略关闭或窗口变更只影响此后新建报警。</div>
      </> : <Empty title="当前条件下没有会合组">只有显式启用报警会合组策略并产生新的FIRE物理报警后，才会出现逻辑会话；默认关闭。</Empty>}
    </CardContent></Card>
    {selected && <Card><CardContent>
      <h2>会话成员</h2>
      <p className="muted-note">成员计数来自真实报警行；按源时间读取，不改变报警。已恢复成员仍保留在会话中，只有活动物理成员会参与通知代表选择。</p>
      {detail.data && <div className="detail-states"><div><span>逻辑会话</span><strong className="mono">{detail.data.session.id}</strong></div><div><span>通知认领</span><span>总计 {detail.data.claims.total} · 已发送 {detail.data.claims.sent} · 未知 {detail.data.claims.unknown} · 失败 {detail.data.claims.failed} · 处理中 {detail.data.claims.claimed}</span></div></div>}
      {detail.error != null && <ErrorNotice error={detail.error} retry={detail.refresh}/>}
      {members.loading ? <Loading/> : members.error != null ? <ErrorNotice error={members.error} retry={members.refresh}/> : members.data && members.data.items.length ? <>
        <div className="table-wrap"><Table><TableHeader><TableRow><TableHead>成员报警</TableHead><TableHead>源时间 / 加入时间</TableHead><TableHead>物理 / 人工</TableHead><TableHead>发生次数 / revision</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader><TableBody>{members.data.items.map(member => <TableRow key={member.alarm_id}><TableCell><strong className="mono">{member.alarm_id}</strong><div className="table-sub">{member.device_name} · {member.address}</div></TableCell><TableCell>{dateTime(member.source_at)}<div className="table-sub">加入 {dateTime(member.added_at)}</div></TableCell><TableCell><Badge variant="outline">{member.physical_state === 'FIRE' ? '物理报警中' : '物理已恢复'}</Badge><div className="table-sub">人工 {member.response_state === 'UNHANDLED' ? '未处理' : '已处理'} · {reported(member.conclusion)}</div></TableCell><TableCell>{member.occurrence_count} 次<div className="table-sub">revision {member.revision}</div></TableCell><TableCell className="text-right"><Button type="button" variant="outline" onClick={() => setAlarmDetail({ id: member.alarm_id, tenantId: member.tenant_id })}>打开原报警详情 / 复核</Button></TableCell></TableRow>)}</TableBody></Table></div>
        <Pager page={members.data.page} size={members.data.size} total={members.data.total} onChange={setMemberPage}/>
      </> : <Empty title="尚未加载成员"/>}
      <Button type="button" variant="outline" onClick={() => choose(null)}>关闭会话详情</Button>
    </CardContent></Card>}
    <AlarmDetail alarmId={alarmDetail?.id ?? null} tenantId={alarmDetail?.tenantId} onClose={() => setAlarmDetail(null)} onChanged={refreshAll}/>
  </>
}
