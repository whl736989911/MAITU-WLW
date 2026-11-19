import { useEffect, useRef, useState } from 'react'
import { SlidersHorizontal } from 'lucide-react'
import { ApiError } from '@/lib/api'
import { getAlarmChanges, readChangeCoordinate, type AlarmChanges, type ChangeCoordinate } from '@/lib/alarm-changes'
import { useSession } from '@/lib/store'
import { AlarmChangeSocket } from '@/lib/alarm-change-socket'
import { AlarmChangeReconnect, initialReconnectView } from '@/lib/alarm-change-reconnect'
import { Button } from './ui/button'
import { ErrorNotice, Loading } from './common'
export interface AlarmChangesPanelProps { refreshWorkbench: (signal: AbortSignal) => Promise<void>; onHint: () => void; close: () => void; blocked: boolean; authorityEpoch: number; onAuthorityLost: (capturedEpoch: number) => void }
export function AlarmChangesPanel(props: AlarmChangesPanelProps) {
  const streamTenant = useSession(s => s.audience === 'management' ? s.scopeInitialized && s.scopeTenantIds.length === 1 && s.scopePlaceIds.length === 0 ? s.scopeTenantIds[0] : undefined : s.user?.tenant_id ?? undefined)
  const audience = useSession(s => s.audience), generation = useSession(s => s.generation), token = useSession(s => s.token)
  const permissions = useSession(s => s.permissions.join('|')), userId = useSession(s => s.user?.id), mustChangePassword = useSession(s => s.user?.must_change_password)
  const ownerKey = JSON.stringify([audience, generation, token, permissions, userId, mustChangePassword, streamTenant])
  return <AlarmChangesPanelOwner key={ownerKey} {...props} streamTenant={streamTenant}/>
}
function AlarmChangesPanelOwner({ refreshWorkbench, onHint, close, blocked, authorityEpoch, onAuthorityLost, streamTenant }: AlarmChangesPanelProps & { streamTenant: string | undefined }) {
  const [cursor, setCursor] = useState<string>(), [through, setThrough] = useState<string>(), [view, setView] = useState<AlarmChanges>(), [busy, setBusy] = useState(''), [error, setError] = useState<unknown>(), [status, setStatus] = useState('未建立检查基线；初始化不重放旧发布提示。')
  const [channelStatus, setChannelStatus] = useState('信号通道未连接；仅在初始化确认后可显式连接。'), [reconnectView, setReconnectView] = useState({ ...initialReconnectView })
  const reconnect = useRef<AlarmChangeReconnect | null>(null), checkedCursor = useRef<string | undefined>(undefined), baselineEpoch = useRef(0), authority = useRef(authorityEpoch); authority.current = authorityEpoch
  const operation = useRef(0), controller = useRef<AbortController | null>(null), callbacks = useRef({ refreshWorkbench, onHint, onAuthorityLost }), observation = useRef<ChangeCoordinate | null>(null), channel = useRef<AlarmChangeSocket | null>(null)
  callbacks.current = { refreshWorkbench, onHint, onAuthorityLost }
  function observe(head: ChangeCoordinate) {
    const old = observation.current
    if (!old || old.tenant_id !== head.tenant_id || old.audience !== head.audience || old.session !== head.session || old.stream_id !== null && head.stream_id !== null && old.stream_id !== head.stream_id) throw new Error('观察范围或epoch未确认')
    const epoch = head.stream_id ?? old.stream_id, newer = BigInt(head.sequence) > BigInt(old.sequence)
    observation.current = { ...(newer ? head : old), stream_id: epoch }
    if (newer) callbacks.current.onHint()
  }
  if (!channel.current) channel.current = new AlarmChangeSocket({ state: setChannelStatus, head: observe, lifecycle: event => reconnect.current?.lifecycle(event), authorityLost: captured => { reconnect.current?.stop(); baselineEpoch.current++; checkedCursor.current = undefined; operation.current++; controller.current?.abort(); observation.current = null; setCursor(undefined); setThrough(undefined); setView(undefined); callbacks.current.onAuthorityLost(captured) } })
  if (!reconnect.current) reconnect.current = new AlarmChangeReconnect(channel.current, () => checkedCursor.current ? { cursor: checkedCursor.current, authorityEpoch: authority.current, baselineEpoch: baselineEpoch.current } : null, setReconnectView)
  useEffect(() => { reconnect.current?.activate(); return () => { operation.current++; controller.current?.abort(); baselineEpoch.current++; checkedCursor.current = undefined; reconnect.current?.dispose(); channel.current?.disconnect(false) } }, [])
  function begin(phase: string) { controller.current?.abort(); const id = ++operation.current, signal = new AbortController(); controller.current = signal; setBusy(phase); setError(undefined); setView(undefined); return { signal: signal.signal, live: () => operation.current === id && !signal.signal.aborted } }
  function failed(error: unknown) { setError(error); setStatus('检查未完成；未推进位置，已有待刷新状态不会因此清除。'); if (error instanceof ApiError && (error.status === 403 || error.status === 400 || error.status === 409)) { reconnect.current?.stop(); channel.current?.disconnect(); baselineEpoch.current++; checkedCursor.current = undefined; observation.current = null; setCursor(undefined); setThrough(undefined) } }
  async function initialize() {
    if (blocked) return
    reconnect.current?.stop(); channel.current?.disconnect(); baselineEpoch.current++; checkedCursor.current = undefined; observation.current = null
    const job = begin('正在获取发布基线'); setCursor(undefined); setThrough(undefined)
    try { const data = await getAlarmChanges({}, job.signal); if (!job.live()) return; setBusy('等待本次工作台真实重读确认'); await callbacks.current.refreshWorkbench(job.signal); if (!job.live()) return; observation.current = readChangeCoordinate(data.next_cursor); checkedCursor.current = data.next_cursor; setCursor(data.next_cursor); setThrough(undefined); setView(data); setStatus('本次工作台重读已确认；尚未检查该基线之后的已发布范围。') }
    catch (error) { if (job.live()) failed(error) } finally { if (job.live()) setBusy('') }
  }
  async function check() {
    if (!cursor || busy) return
    const job = begin('正在检查已发布提示页')
    try { const data = await getAlarmChanges({ cursor, ...(through === undefined ? {} : { through }) }, job.signal); if (!job.live()) return; observe(readChangeCoordinate(data.next_cursor)); checkedCursor.current = data.next_cursor; setCursor(data.next_cursor); setThrough(data.has_more ? data.through_cursor : undefined); setView(data); setStatus(data.has_more ? '本次固定发布范围尚有提示页，请明确检查下一页。' : '已检查该已发布范围；不保证所有当前业务提交都已发布。') }
    catch (error) { if (job.live()) failed(error) } finally { if (job.live()) setBusy('') }
  }
  return <section className="alarm-change-panel" aria-label="报警变更检查" data-alarm-change-mode={view?.mode} data-alarm-change-as-of={view?.as_of} data-alarm-change-position={view ? readChangeCoordinate(view.next_cursor).sequence : undefined} data-alarm-change-through={view ? readChangeCoordinate(view.through_cursor).sequence : undefined}>
    <h2>报警变更检查（手动）</h2><p>{reconnectView.active ? '仅本轮明确同意的有限预算内可自动恢复失效信号连接；不读实体、不代表已同步、发布器在线或实时值守，不自动轮询HTTP、弹窗、声音、排序或修改统计。' : '仅V11后APPLICATION轨迹的失效提示，不是报警实体、报警数量或实时值守；不自动轮询、重连、弹窗、声音、排序或修改统计。'}</p>
    {!streamTenant && <p className="notice">变更检查使用单单位游标，请在左侧仅选择一个完整单位后初始化。此限制只针对变更检查，不是浏览全部授权报警的前置条件。</p>}
    <div className="alarm-change-actions"><Button type="button" variant="outline" disabled={!streamTenant || !!busy || blocked} onClick={initialize}><SlidersHorizontal/>初始化报警变更检查</Button><Button type="button" variant="outline" disabled={!cursor || !!busy} onClick={check}>{through === undefined ? '检查已发布报警变更' : '检查下一页报警变更'}</Button><Button type="button" variant="outline" disabled={!streamTenant || !!busy || blocked} onClick={initialize}>刷新工作台并重建检查基线</Button><Button type="button" variant="ghost" onClick={() => { reconnect.current?.stop(); channel.current?.disconnect(false); operation.current++; controller.current?.abort(); close() }}>关闭报警变更检查</Button></div>
    <div className="alarm-change-actions" aria-label="显式变更信号"><Button type="button" variant="outline" disabled={!cursor || !!busy || blocked} onClick={() => { reconnect.current?.stop(); if (cursor) void channel.current?.connect(cursor, authorityEpoch) }}>连接变更信号</Button><Button type="button" variant="outline" onClick={() => { reconnect.current?.stop(); channel.current?.disconnect() }}>断开变更信号</Button><Button type="button" variant="outline" disabled={!cursor || !!busy || blocked} onClick={() => { reconnect.current?.stop(); if (cursor) void channel.current?.connect(cursor, authorityEpoch) }}>手动重连变更信号</Button></div><p role="status" data-alarm-channel-status>{channelStatus}</p><p>{reconnectView.active ? 'WS仍只观察已发布head；本轮仅有界恢复信号连接，不推进人工HTTP位置或固定目标，不自动补拉、刷新或提交。重复传输检查不代表发布器在线。' : 'WS只观察已发布head，不推进人工HTTP位置或固定目标，不自动补拉、刷新、重连或提交。重复传输检查不代表发布器在线。'}</p>
    <div className="alarm-change-actions" aria-label="有界自动重连"><Button type="button" variant="outline" disabled={!!busy || blocked || !reconnect.current?.canEnable()} onClick={() => { void reconnect.current?.enable() }}>启用有界自动重连</Button><Button type="button" variant="outline" disabled={!reconnectView.active} onClick={() => reconnect.current?.stop()}>停止自动重连</Button></div><p role="status" data-alarm-reconnect-status={reconnectView.phase} data-alarm-reconnect-used={reconnectView.used} data-alarm-reconnect-max={reconnectView.max ?? undefined} data-alarm-reconnect-delay-ms={reconnectView.delayMs ?? undefined} data-alarm-reconnect-window-ms={reconnectView.windowMs ?? undefined}>{reconnectView.message}{reconnectView.delayMs === null ? '' : ` · 固定恢复间隔 ${reconnectView.delayMs} 毫秒；本轮总窗口 ${reconnectView.windowMs} 毫秒`} · 本轮已用恢复尝试 {reconnectView.used}{reconnectView.max === null ? '' : ` / ${reconnectView.max}，最多还可尝试 ${Math.max(0, reconnectView.max - reconnectView.used)} 次`}；{reconnectView.active ? '窗口从本次点击计起，成功不续期；到期或次数用尽即停止计划。' : '未运行自动计划；已确认通道可保持，后续连接需手动或再次明确选择。'}</p>
    {blocked && <p className="notice">当前详情或复核已打开，请先关闭再初始化/重建；检查提示不更改复核输入。</p>}
    {busy && <Loading/>}<p role="status" data-alarm-change-status>{busy || status}</p>{error != null && <ErrorNotice error={error}/>}
    {view && <><p data-alarm-change-pending={view.pending_count}>当前设备访问范围内已提交未发布积压任务：{view.pending_count}（不是报警数）。发布器活性未确认，积压0不表示无报警或发布器在线。</p><p>提示检查求值 {view.as_of} · {view.timezone}；与工作台是独立HTTP快照，不是提交水位。当前单位提示可能在当前历史筛选之外，请按需要重读已应用条件。</p>{view.items.map(item => <p key={item.delivery_id} data-alarm-change-sequence={item.sequence}>ALARM_CHANGED · 发布位置 {item.sequence}：当前工作台可能需刷新（不代表一条新报警）。</p>)}</>}
  </section>
}
