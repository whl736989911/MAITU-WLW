import { ApiError } from './api'
import { useSession } from './store'
import { parseJsonDocument, jsonFieldText } from './json-document'
import { integerToken } from './parameter-json'
import { readChangeCoordinate, type ChangeCoordinate } from './alarm-changes'
import { channelOwner, sameChannelOwner, type ChannelOwner } from './alarm-change-channel'
import { AlarmChangeSocket, type AutoChannelAttempt, type ChannelLifecycle } from './alarm-change-socket'
export interface ReconnectPolicy { max_retries: number; delay_ms: number; window_ms: number }
function bad(): never { throw new Error('自动重连技术策略未确认；仍保持手动模式') }
function fields(value: unknown, keys: string[]): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value, k))) return bad(); return value as Record<string, unknown> }
export function readReconnectPolicy(raw: string, anchor: ChangeCoordinate): ReconnectPolicy {
  const data = fields(parseJsonDocument(raw), ['v', 'scope', 'policy']), scope = fields(data.scope, ['tenant_id', 'audience', 'session'])
  if (data.v !== 1 || jsonFieldText(raw, 'v') !== '1' || scope.tenant_id !== anchor.tenant_id || scope.audience !== anchor.audience || scope.session !== anchor.session) return bad()
  fields(data.policy, ['max_retries', 'delay_ms', 'window_ms'])
  const span = jsonFieldText(raw, 'policy'); if (span === undefined) return bad()
  const number = (key: string) => { const token = jsonFieldText(span, key); if (token === undefined) return bad(); return integerToken(token) }
  const policy = { max_retries: number('max_retries'), delay_ms: number('delay_ms'), window_ms: number('window_ms') }
  if (policy.max_retries < 1 || policy.max_retries > 10 || policy.delay_ms < 100 || policy.delay_ms > 60000 || policy.window_ms < 1000 || policy.window_ms > 600000 || policy.window_ms <= policy.delay_ms) return bad()
  return policy
}
export async function fetchReconnectPolicy(anchor: ChangeCoordinate, owner: ChannelOwner, signal: AbortSignal, live: () => boolean): Promise<ReconnectPolicy> {
  const current = () => !signal.aborted && sameChannelOwner(owner) && live()
  const cancelled = () => new DOMException('本次自动重连策略读取已取消', 'AbortError')
  if (!current()) throw cancelled()
  if (!owner.token || owner.tenant !== anchor.tenant_id || owner.audience !== anchor.audience) return bad()
  const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${owner.token}` }); if (owner.audience === 'management') headers.set('X-Tenant-Id', owner.tenant!)
  const response = await fetch(`/api/${owner.audience}/alarm-change-reconnect-policy`, { method: 'GET', headers, credentials: 'omit', cache: 'no-store', redirect: 'error', signal })
  if (!current()) throw cancelled()
  if (response.status === 401) { useSession.getState().clear('登录已失效，请重新登录。'); throw new ApiError(401, 'RECONNECT_POLICY_AUTHENTICATION_REQUIRED', '登录已失效，请重新登录。') }
  if (!response.ok) throw new ApiError(response.status, 'RECONNECT_POLICY_UNCONFIRMED', '自动重连策略未确认；未启用自动计划')
  const raw = await response.text(); if (!current()) throw cancelled()
  return readReconnectPolicy(raw, anchor)
}
export interface ReconnectContext { cursor: string; authorityEpoch: number; baselineEpoch: number }
export interface ReconnectView { active: boolean; phase: 'off' | 'preparing' | 'healthy' | 'backoff' | 'retrying' | 'exhausted' | 'terminal'; used: number; max: number | null; delayMs: number | null; windowMs: number | null; remainingMs: number | null; message: string }
interface Plan { id: number; owner: ChannelOwner; context: ReconnectContext; connectionId: number; connectMs: number; start: number; expiry: number; used: number; phase: ReconnectView['phase']; policy: ReconnectPolicy | null; controller: AbortController; pending?: AutoChannelAttempt; stageTimer?: ReturnType<typeof setTimeout>; expiryTimer?: ReturnType<typeof setTimeout> }
export const initialReconnectView: ReconnectView = { active: false, phase: 'off', used: 0, max: null, delayMs: null, windowMs: null, remainingMs: null, message: '未启用自动重连；原手动连接首个有效信号后才可显式选择。' }
/** One explicit click, one immutable window, one monotonic retry budget. No business reads. */
export class AlarmChangeReconnect {
  private plan: Plan | null = null
  private serial = 0
  private attemptSerial = 0
  private disposed = false
  private view: ReconnectView = { ...initialReconnectView }
  constructor(private socket: AlarmChangeSocket, private context: () => ReconnectContext | null, private update: (view: ReconnectView) => void) {}
  canEnable() { const ready = this.socket.getReady(), context = this.context(); return !this.disposed && !this.plan && !!ready && !!context && ready.authorityEpoch === context.authorityEpoch }
  private current(plan: Plan) { const context = this.context(); return !this.disposed && this.plan === plan && sameChannelOwner(plan.owner) && !!context && context.authorityEpoch === plan.context.authorityEpoch && context.baselineEpoch === plan.context.baselineEpoch }
  private preparing(plan: Plan) { return this.current(plan) && plan.phase === 'preparing' && this.socket.getReady()?.connectionId === plan.connectionId }
  private publish(message: string) { const p = this.plan; this.view = { active: !!p, phase: p?.phase ?? this.view.phase, used: p?.used ?? this.view.used, max: p?.policy?.max_retries ?? (p ? null : this.view.max), delayMs: p?.policy?.delay_ms ?? (p ? null : this.view.delayMs), windowMs: p?.policy?.window_ms ?? (p ? null : this.view.windowMs), remainingMs: p?.policy ? Math.max(0, p.expiry - performance.now()) : null, message }; if (!this.disposed) this.update({ ...this.view }) }
  private end(message: string, phase: ReconnectView['phase'], notify = true) {
    const p = this.plan; if (!p) return
    this.plan = null; ++this.serial; clearTimeout(p.stageTimer); clearTimeout(p.expiryTimer); p.controller.abort(); if (p.pending) this.socket.cancelAuto(p.pending)
    this.view = { active: false, phase, used: p.used, max: p.policy?.max_retries ?? null, delayMs: p.policy?.delay_ms ?? null, windowMs: p.policy?.window_ms ?? null, remainingMs: p.policy ? Math.max(0, p.expiry - performance.now()) : null, message }; if (notify && !this.disposed) this.update({ ...this.view })
  }
  stop(notify = true) { if (this.plan) this.end('已停止自动重连；已确认的现有通道保留，未确认候选已取消。', 'off', notify) }
  activate() { this.disposed = false }
  dispose() { this.disposed = true; this.stop(false) }
  async enable() {
    if (!this.canEnable()) return
    const ready = this.socket.getReady()!, context = this.context()!, owner = channelOwner()
    const p: Plan = { id: ++this.serial, owner, context: { ...context }, connectionId: ready.connectionId, connectMs: ready.connectTimeoutMs, start: performance.now(), expiry: Infinity, used: 0, phase: 'preparing', policy: null, controller: new AbortController() }; this.plan = p
    this.publish('正在读取本次技术策略；尚未启用，点击起点不会因晚响应重新计时。')
    p.stageTimer = setTimeout(() => { if (this.plan === p) this.end('策略读取超过已知服务端技术期限；仍为手动模式。', 'terminal', this.current(p)) }, ready.connectTimeoutMs)
    try {
      const policy = await fetchReconnectPolicy(readChangeCoordinate(context.cursor), owner, p.controller.signal, () => this.preparing(p))
      if (!this.preparing(p)) { if (this.plan === p) this.end('原连接或范围已改变；本次计划未启用。', 'terminal', this.current(p)); return }
      clearTimeout(p.stageTimer); p.policy = policy; p.expiry = p.start + policy.window_ms
      if (performance.now() >= p.expiry) { this.end('本次点击窗口已到期，未启动自动恢复；现有通道保留。', 'exhausted'); return }
      p.phase = 'healthy'; p.expiryTimer = setTimeout(() => { if (this.plan === p) this.end('本次窗口到期；现有已确认通道保留，后续断开须手动。', 'exhausted', this.current(p)) }, p.expiry - performance.now())
      this.publish('本次有限自动重连已启用；成功或重复信号不续期，不自动检查HTTP范围。')
    } catch { if (this.plan === p) this.end('自动重连策略未确认或原连接已失效；未启用，不自动重试策略。', 'terminal', this.current(p)) }
  }
  lifecycle(event: ChannelLifecycle) {
    if (this.disposed) return
    const p = this.plan
    if (!p) { this.update({ ...this.view }); return }
    if (!this.current(p)) { this.end('范围或身份已改变；自动计划已销毁。', 'terminal', false); return }
    if (p.policy && performance.now() >= p.expiry) { this.end('本次窗口到期；现有已确认通道保留，不再恢复。', 'exhausted'); return }
    if (p.phase === 'preparing') { if (event.type === 'ended' && event.connectionId === p.connectionId || event.type === 'ready' && event.connection.connectionId !== p.connectionId) this.end('加载策略期间原连接失效或切换；晚策略不会启用。', 'terminal'); return }
    if (event.type === 'ready') {
      if (p.phase !== 'retrying' || !p.pending || event.autoAttempt !== p.pending.id) return
      p.pending = undefined; p.connectionId = event.connection.connectionId
      if (performance.now() >= p.expiry || p.used >= p.policy!.max_retries) { this.end('本次自动次数或窗口已用尽；已确认通道保留，不再恢复。', 'exhausted'); return }
      p.phase = 'healthy'; this.publish('信号恢复已确认；已用次数保留，窗口不续期，人工HTTP范围未必检查。'); return
    }
    if (p.phase === 'backoff' || (p.pending ? event.autoAttempt !== p.pending.id : event.connectionId !== p.connectionId)) return
    p.pending = undefined
    if (!event.retryable) { this.end('本次连接遇到不可自动重试的结束；计划停止，请显式核验。', 'terminal'); return }
    if (p.used >= p.policy!.max_retries || performance.now() >= p.expiry) { this.end('本次自动次数或窗口已用尽；后续须手动连接。', 'exhausted'); return }
    p.phase = 'backoff'; this.publish('信号已断开；仅在本次剩余预算内等待恢复，不读取报警实体。')
    clearTimeout(p.stageTimer)
    if (performance.now() + p.policy!.delay_ms < p.expiry) p.stageTimer = setTimeout(() => this.retry(p), p.policy!.delay_ms)
  }
  private retry(p: Plan) {
    if (!this.current(p)) { if (this.plan === p) this.end('范围已改变；旧自动计划已销毁。', 'terminal', false); return }
    if (performance.now() >= p.expiry || p.used >= p.policy!.max_retries) { this.end('本次自动次数或窗口已用尽；不再申请票据。', 'exhausted'); return }
    const context = this.context()!
    try { const before = readChangeCoordinate(p.context.cursor), now = readChangeCoordinate(context.cursor); if (before.tenant_id !== now.tenant_id || before.audience !== now.audience || before.session !== now.session) return this.end('人工检查范围已改变；自动计划停止。', 'terminal') } catch { this.end('人工检查位置未确认；自动计划停止。', 'terminal'); return }
    ++p.used
    const auto: AutoChannelAttempt = { id: ++this.attemptSerial, timeoutMs: p.connectMs, deadline: p.expiry, live: () => this.current(p) && p.pending === auto }; p.pending = auto; p.phase = 'retrying'
    this.publish('正在本次有限预算内申请新票并等待首个有效信号；不是已同步。')
    void this.socket.connect(context.cursor, context.authorityEpoch, auto)
  }
}
