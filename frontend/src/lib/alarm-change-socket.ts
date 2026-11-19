import { ApiError } from './api'
import { useSession } from './store'
import { readChangeCoordinate, type ChangeCoordinate } from './alarm-changes'
import { channelOwner, sameChannelOwner, readChannelBootstrap, readChannelFrame, type ChannelOwner } from './alarm-change-channel'
function cancelled(): never { throw new DOMException('变更信号连接操作已取消', 'AbortError') }
export async function fetchChannelBootstrap(anchor: ChangeCoordinate, owner: ChannelOwner, signal: AbortSignal, live: () => boolean, onResponse?: () => void) {
  const current = () => !signal.aborted && sameChannelOwner(owner) && live()
  if (!current()) return cancelled()
  if (!owner.token || !owner.tenant || owner.tenant !== anchor.tenant_id || owner.audience !== anchor.audience) throw new Error('信号范围未确认')
  const headers = new Headers({ Accept: 'application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${owner.token}` }); if (owner.audience === 'management') headers.set('X-Tenant-Id', owner.tenant)
  let result: Response
  try { result = await fetch(`/api/${owner.audience}/alarm-change-channel`, { method: 'POST', body: '{}', headers, credentials: 'omit', cache: 'no-store', redirect: 'error', signal }) }
  catch (error) { if (error instanceof DOMException && error.name === 'AbortError') throw error; throw new ApiError(0, 'CHANNEL_NETWORK_UNCONFIRMED', '信号网络未确认') }
  if (!current()) return cancelled()
  onResponse?.() // Current Response/headers observed; no status, payload or ticket escapes.
  if (result.status === 401) { useSession.getState().clear('登录已失效，请重新登录。'); throw new ApiError(401, 'CHANNEL_AUTHENTICATION_REQUIRED', '登录已失效，请重新登录。') }
  if (!result.ok) throw new ApiError(result.status, 'CHANNEL_CONNECT_UNCONFIRMED', '信号连接未确认，请手动重连或显式重读工作台')
  const raw = await result.text(); if (!current()) return cancelled()
  return readChannelBootstrap(raw, anchor)
}
export interface ChannelReady { connectionId: number; authorityEpoch: number; connectTimeoutMs: number }
export type ChannelEndReason = 'cancelled' | 'network' | 'bootstrap-timeout' | 'body-timeout' | 'bootstrap-status' | 'protocol' | 'connect-timeout' | 'silence-timeout' | 'native-close' | 'authority-changed'
export type ChannelLifecycle = { type: 'ready'; connection: ChannelReady; autoAttempt?: number } | { type: 'ended'; connectionId: number; reason: ChannelEndReason; retryable: boolean; autoAttempt?: number }
export interface AutoChannelAttempt { id: number; timeoutMs: number; deadline: number; live: () => boolean }
export interface ChannelHooks { state: (message: string) => void; head: (head: ChangeCoordinate) => void; authorityLost: (authorityEpoch: number) => void; lifecycle?: (event: ChannelLifecycle) => void }
/** Default B2 stays manual. The optional supervisor never receives credentials. */
export class AlarmChangeSocket {
  private epoch = 0
  private socket: WebSocket | null = null
  private request: AbortController | null = null
  private connectTimer: ReturnType<typeof setTimeout> | undefined
  private silenceTimer: ReturnType<typeof setTimeout> | undefined
  private bootstrapTimer: ReturnType<typeof setTimeout> | undefined
  private readyInfo: ChannelReady | null = null
  private readyOwner: ChannelOwner | null = null
  private auto: AutoChannelAttempt | undefined
  private finish: ((reason: ChannelEndReason, retryable: boolean) => void) | null = null
  constructor(private hooks: ChannelHooks) {}
  getReady(): ChannelReady | null { return this.readyInfo && this.socket?.readyState === 1 && this.readyOwner && sameChannelOwner(this.readyOwner) ? this.readyInfo : null }
  cancelAuto(attempt: AutoChannelAttempt) { if (this.auto === attempt && !this.readyInfo) this.disconnect(false) }
  private teardown() {
    ++this.epoch; this.request?.abort(); this.request = null; clearTimeout(this.connectTimer); clearTimeout(this.silenceTimer); clearTimeout(this.bootstrapTimer)
    const socket = this.socket; this.socket = null; this.readyInfo = null; this.readyOwner = null; this.auto = undefined; this.finish = null
    if (socket) { socket.onopen = null; socket.onmessage = null; socket.onerror = null; socket.onclose = null; if (socket.readyState < 2) { try { socket.close(1000, 'CLIENT_DISCONNECT') } catch { /* already closing */ } } }
  }
  disconnect(notify = true) {
    const finish = this.finish; this.teardown(); finish?.('cancelled', false)
    if (notify) this.hooks.state('信号通道已断开；人工检查位置保留，请按需要手动重连。')
  }
  async connect(anchorCursor: string, authorityEpoch: number, auto?: AutoChannelAttempt) {
    this.disconnect(false)
    const epoch = this.epoch, owner = channelOwner(), controller = new AbortController(), lifecycle = this.hooks.lifecycle; this.request = controller; this.auto = auto
    let established = false, ended = false, responded = false, bootstrapDone = false
    let bootstrapDeadline: number | undefined
    const finish = (reason: ChannelEndReason, retryable: boolean) => { if (ended) return; ended = true; lifecycle?.({ type: 'ended', connectionId: epoch, reason, retryable, autoAttempt: auto?.id }) }; this.finish = finish
    const live = () => this.epoch === epoch && !controller.signal.aborted && sameChannelOwner(owner) && (established || !auto || auto.live() && performance.now() < auto.deadline)
    const bootstrapLive = () => live() && (bootstrapDeadline === undefined || performance.now() < bootstrapDeadline)
    const fail = (reason: ChannelEndReason, retryable: boolean) => { if (!live()) return; this.teardown(); this.hooks.state('信号通道未确认；人工检查位置未改变，请手动重连。'); finish(reason, retryable) }
    const bootstrapTimeout = () => fail(responded ? 'body-timeout' : 'bootstrap-timeout', !responded)
    this.hooks.state('正在申请一次性信号票据…')
    try {
      if (!live()) { this.teardown(); finish('cancelled', false); return }
      bootstrapDeadline = auto ? Math.min(performance.now() + auto.timeoutMs, auto.deadline) : undefined
      if (bootstrapDeadline !== undefined) this.bootstrapTimer = setTimeout(bootstrapTimeout, Math.max(0, bootstrapDeadline - performance.now()))
      const anchor = readChangeCoordinate(anchorCursor), bootstrap = await fetchChannelBootstrap(anchor, owner, controller.signal, bootstrapLive, auto ? () => { responded = true } : undefined)
      if (!live()) return
      if (bootstrapDeadline !== undefined && performance.now() >= bootstrapDeadline) { bootstrapTimeout(); return }
      bootstrapDone = true; clearTimeout(this.bootstrapTimer)
      const connectMs = bootstrap.connect_timeout_ms, silenceMs = bootstrap.silence_timeout_ms
      this.hooks.state('正在连接，等待首个有效授权信号…')
      const location = window.location, socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/api/${owner.audience}/alerts`, ['alarm-changes.v1', `ticket.${bootstrap.ticket}`]); this.socket = socket
      const current = () => live() && this.socket === socket
      let previous: ChangeCoordinate | undefined, adoptedStream = anchor.stream_id
      this.connectTimer = setTimeout(() => fail('connect-timeout', true), auto ? Math.min(connectMs, auto.deadline - performance.now()) : connectMs)
      socket.onopen = () => { if (!current()) return; if (socket.protocol !== 'alarm-changes.v1') return fail('protocol', false); this.hooks.state('已升级，等待首个有效授权信号…') }
      socket.onmessage = event => {
        if (!current()) return
        try {
          if (socket.protocol !== 'alarm-changes.v1') return fail('protocol', false)
          const head = readChannelFrame(event.data, anchor)
          if (adoptedStream !== null && head.stream_id !== adoptedStream || BigInt(head.sequence) < BigInt(previous?.sequence ?? anchor.sequence)) throw new Error('信号epoch/位置未确认')
          if (adoptedStream === null && head.stream_id !== null) adoptedStream = head.stream_id
          previous = head
          this.hooks.head(head)
          if (!current()) return
          clearTimeout(this.connectTimer); clearTimeout(this.silenceTimer); this.silenceTimer = setTimeout(() => fail('silence-timeout', true), silenceMs)
          this.hooks.state('信号通道已建立；仅传输检查，发布器活性未确认，人工HTTP范围未必检查。')
          if (!established) { established = true; this.readyInfo = { connectionId: epoch, authorityEpoch, connectTimeoutMs: connectMs }; this.readyOwner = owner; lifecycle?.({ type: 'ready', connection: this.readyInfo, autoAttempt: auto?.id }) }
        } catch { fail('protocol', false) }
      }
      socket.onerror = () => { if (current()) this.hooks.state('信号连接未确认；等待关闭或技术deadline，不猜测HTTP鉴权结果。') }
      socket.onclose = event => {
        if (!current()) return
        const revoked = socket.protocol === 'alarm-changes.v1' && event.code === 1008 && event.reason === 'AUTHORIZATION_CHANGED'
        const retryable = event.code === 1006 || event.code === 1001 || socket.protocol === 'alarm-changes.v1' && event.code === 1011 && event.reason === 'CHANNEL_UNAVAILABLE'
        this.teardown()
        if (revoked) { finish('authority-changed', false); this.hooks.state('授权已改变，当前范围数据已隐藏。'); this.hooks.authorityLost(authorityEpoch) }
        else { this.hooks.state('信号通道已断开或未确认；人工检查位置保留，请手动重连。'); finish('native-close', retryable) }
      }
    } catch (error) {
      if (live()) {
        if (!bootstrapDone && bootstrapDeadline !== undefined && performance.now() >= bootstrapDeadline) { bootstrapTimeout(); return }
        this.teardown(); this.hooks.state('信号连接未确认；请手动重连或显式重读工作台。')
        finish(error instanceof ApiError && error.status === 0 ? 'network' : error instanceof ApiError && error.status !== 200 ? 'bootstrap-status' : 'protocol', error instanceof ApiError && (error.status === 0 || error.status === 429 || error.status === 503))
      } else if (this.epoch === epoch) { this.teardown(); finish('cancelled', false) }
    }
  }
}
