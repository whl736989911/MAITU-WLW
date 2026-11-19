import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AlarmsPage } from '@/pages/alarms'
import { useSession } from '@/lib/store'
import { emptyAlarmFilters, expectedMetricDefinitions } from '@/lib/alarm-workbench'
import { changeSemantics, readChangeCoordinate } from '@/lib/alarm-changes'
import { channelOwner, channelSemantics } from '@/lib/alarm-change-channel'
import { AlarmChangeSocket } from '@/lib/alarm-change-socket'
import { AlarmChangeReconnect, fetchReconnectPolicy, readReconnectPolicy, type ReconnectContext } from '@/lib/alarm-change-reconnect'
import { alarm, management, customer, response } from './fixtures'
const stream = '11111111-1111-4111-8111-111111111111', otherStream = '22222222-2222-4222-8222-222222222222', fingerprint = 'a'.repeat(64), at = '2026-10-26T01:40:00.123456Z'
function cursor(sequence = '0', epoch: string | null = null, tenant = 'tenant-a', audience = 'management') { return btoa(JSON.stringify({ v: 1, tenant_id: tenant, audience, session: fingerprint, stream_id: epoch, sequence })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') }
const frame = (value = cursor()) => JSON.stringify({ v: 1, type: 'ALARM_CHANGE_SIGNAL', head_cursor: value, semantics: { ...channelSemantics } })
function policy(max_retries = 2, delay_ms = 100, window_ms = 5000, audience = 'management') { return { v: 1, scope: { tenant_id: 'tenant-a', audience, session: fingerprint }, policy: { max_retries, delay_ms, window_ms } } }
function bootstrap(ticket = 'T'.repeat(43), audience = 'management', connect_timeout_ms = 1000, silence_timeout_ms = 15000) { return { v: 1, scope: { tenant_id: 'tenant-a', audience, session: fingerprint }, ticket, connect_timeout_ms, silence_timeout_ms } }
class NativeMock {
  static instances: NativeMock[] = []
  readyState = 0; protocol = ''; closes = 0
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  constructor(public url: string, public protocols: string[]) { NativeMock.instances.push(this) }
  open(protocol = 'alarm-changes.v1') { this.readyState = 1; this.protocol = protocol; this.onopen?.(new Event('open')) }
  message(data: unknown) { this.onmessage?.({ data } as MessageEvent) }
  ended(code = 1006, reason = '') { this.readyState = 3; this.onclose?.({ code, reason } as CloseEvent) }
  close() { this.readyState = 3; this.closes++ }
  send(): never { throw new Error('No product application/auth WS frame is permitted') }
}
function login(client = false) { useSession.getState().login({ ...(client ? customer : management), permissions: ['alarm:read', 'alarm:review'] }, client ? 'customer' : 'management'); if (!client) useSession.getState().setScope(['tenant-a'], [], true) }
const latest = () => NativeMock.instances.at(-1)!
const fakeClock = () => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date', 'performance'] })
const flush = () => vi.advanceTimersByTimeAsync(0)
const ticketCalls = (fetcher: ReturnType<typeof vi.fn>) => fetcher.mock.calls.filter(c => c[0].endsWith('/alarm-change-channel'))
const policyCalls = (fetcher: ReturnType<typeof vi.fn>) => fetcher.mock.calls.filter(c => c[0].endsWith('/alarm-change-reconnect-policy'))
type Override = (url: string, init?: RequestInit) => Response | Promise<Response> | undefined
const cleanup: (() => void)[] = []
function rig(override?: Override, envelope = policy(), client = false, connectMs = 1000) {
  login(client); let issued = 0
  const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => { const special = override?.(url, init); if (special !== undefined) return special; if (url.endsWith('/alarm-change-reconnect-policy')) return response(envelope); if (url.endsWith('/alarm-change-channel')) return response(bootstrap(String(++issued).padStart(43, 'T'), client ? 'customer' : 'management', connectMs)); throw new Error('Unexpected test endpoint') }); vi.stubGlobal('fetch', fetcher)
  let context: ReconnectContext | null = { cursor: cursor('0', null, 'tenant-a', client ? 'customer' : 'management'), authorityEpoch: 0, baselineEpoch: 1 }
  const views = vi.fn(), hooks = { state: vi.fn(), head: vi.fn(), authorityLost: vi.fn() }; let reconnect!: AlarmChangeReconnect
  const channel = new AlarmChangeSocket({ ...hooks, lifecycle: event => reconnect.lifecycle(event) }); reconnect = new AlarmChangeReconnect(channel, () => context, views)
  cleanup.push(() => { reconnect.dispose(); channel.disconnect(false) })
  return { channel, reconnect, fetcher, hooks, views, setContext(value: ReconnectContext | null) { context = value }, context: () => context, view: () => views.mock.calls.at(-1)?.[0], async ready(value = context!.cursor) { await channel.connect(context!.cursor, context!.authorityEpoch); latest().open(); latest().message(frame(value)) } }
}
beforeEach(() => { useSession.getState().clear(); NativeMock.instances = []; vi.stubGlobal('WebSocket', NativeMock); localStorage.clear(); sessionStorage.clear() })
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.useRealTimers() })
describe('P8b3 policy原字面/闭集与独立fresh GET', () => {
  it('准确3top/3scope/3policy，接受冻结技术边界且不含票或URL', () => {
    const a = readChangeCoordinate(cursor()); expect(readReconnectPolicy(JSON.stringify(policy()), a)).toEqual(policy().policy)
    expect(readReconnectPolicy(JSON.stringify(policy(1, 100, 1000)), a)).toEqual(policy(1, 100, 1000).policy)
    expect(readReconnectPolicy(JSON.stringify(policy(10, 60000, 600000)), a)).toEqual(policy(10, 60000, 600000).policy)
  })
  it('nested原span不parse/stringify：指数/近整数/重复escaped/额外字段/空/bind皆拒', () => {
    const good = JSON.stringify(policy()), a = readChangeCoordinate(cursor())
    for (const raw of ['', '{}', good.replace('"v":1', '"v":1e0'), good.replace('"max_retries":2', '"max_retries":2.0000000000000000001'), good.replace('"delay_ms":100', '"delay_ms":1e2'), good.replace('"window_ms":5000', '"window_ms":4999.9999999999999999999'), good.replace('"policy":{', '"policy":{"\\u0064elay_ms":100,'), good.replace('"scope":{', '"scope":{"session":"duplicate",'), good.replace('"policy":{', '"ticket":"private","policy":{'), good.replace('"window_ms":5000', '"window_ms":5000,"unknown":1'), JSON.stringify({ ...policy(), scope: { ...policy().scope, session: 'b'.repeat(64) } }), JSON.stringify(policy(0)), JSON.stringify(policy(11)), JSON.stringify(policy(1, 99)), JSON.stringify(policy(1, 60001, 600000)), JSON.stringify(policy(1, 100, 999)), JSON.stringify(policy(1, 100, 600001)), JSON.stringify(policy(1, 1000, 1000))]) expect(() => readReconnectPolicy(raw, a)).toThrow()
  })
  it('最小双端GET无body/Origin/header伪造，omit/no-store/redirect error/current scope', async () => {
    for (const client of [false, true]) {
      const r = rig(undefined, policy(2, 100, 5000, client ? 'customer' : 'management'), client); await r.ready(); await r.reconnect.enable()
      const [url, init] = policyCalls(r.fetcher)[0], h = new Headers(init.headers)
      expect(url).toBe(`/api/${client ? 'customer' : 'management'}/alarm-change-reconnect-policy`)
      expect(init).toMatchObject({ method: 'GET', credentials: 'omit', cache: 'no-store', redirect: 'error' }); expect(init.body).toBeUndefined(); expect(h.has('Origin')).toBe(false); expect(h.get('Authorization')).toBe(`Bearer ${client ? customer.access_token : management.access_token}`); expect(h.get('X-Tenant-Id')).toBe(client ? null : 'tenant-a')
      r.reconnect.dispose(); r.channel.disconnect(false)
    }
  })
  it('真正current policy401按原notice清session，403/503/空200只终止计划保healthy', async () => {
    for (const status of [403, 503, 200, 401]) {
      const r = rig(url => url.endsWith('/alarm-change-reconnect-policy') ? status === 200 ? new Response(null, { status }) : response({ code: 'DENIED' }, status) : undefined); await r.ready(); const socket = latest(); await r.reconnect.enable()
      expect(policyCalls(r.fetcher)).toHaveLength(1); expect(ticketCalls(r.fetcher)).toHaveLength(1); expect(socket.closes).toBe(0)
      if (status === 401) { expect(useSession.getState().token).toBeNull(); expect(useSession.getState().notice).toBe('登录已失效，请重新登录。') } else { expect(useSession.getState().token).not.toBeNull(); expect(r.view().active).toBe(false) }
      r.reconnect.dispose(); r.channel.disconnect(false)
    }
  })
  it('旧GET401/status与异步body先完整owner guard，不能清同token新generation', async () => {
    let resolve!: (value: Response) => void; const r = rig(url => url.endsWith('/alarm-change-reconnect-policy') ? new Promise<Response>(done => { resolve = done }) : undefined); await r.ready(); const owner = channelOwner(), signal = new AbortController().signal
    const pending = fetchReconnectPolicy(readChangeCoordinate(cursor()), owner, signal, () => true); login(); resolve(response({ private: 'not-read' }, 401)); await expect(pending).rejects.toMatchObject({ name: 'AbortError' }); expect(useSession.getState().token).toBe(management.access_token)
    let body!: (value: string) => void; const nativeResponse = new Response('{}'); vi.spyOn(nativeResponse, 'text').mockImplementation(() => new Promise<string>(done => { body = done })); r.fetcher.mockImplementation(() => nativeResponse)
    const pendingBody = fetchReconnectPolicy(readChangeCoordinate(cursor()), channelOwner(), signal, () => true); await Promise.resolve(); await Promise.resolve(); useSession.getState().setScope(['tenant-b'], [], true); body('must-not-parse'); await expect(pendingBody).rejects.toMatchObject({ name: 'AbortError' })
  })
})
describe('P8b3 monotonic有限预算与deadline（新fake clock，不改旧timeout）', () => {
  it('默认与onopen无首valid都不能opt-in，valid后仍0policy直到显式click，重复enable不续杯', async () => {
    fakeClock(); const r = rig(); await r.reconnect.enable(); expect(policyCalls(r.fetcher)).toHaveLength(0)
    await r.channel.connect(cursor(), 0); latest().open(); expect(r.reconnect.canEnable()).toBe(false); await r.reconnect.enable(); expect(policyCalls(r.fetcher)).toHaveLength(0)
    latest().message(frame()); expect(r.reconnect.canEnable()).toBe(true); expect(policyCalls(r.fetcher)).toHaveLength(0)
    await r.reconnect.enable(); await r.reconnect.enable(); expect(policyCalls(r.fetcher)).toHaveLength(1); expect(r.view()).toMatchObject({ active: true, used: 0, max: 2 })
  })
  it('clickT0固定窗口：held policy耗时计入，不从response或首断重新给满窗', async () => {
    fakeClock(); let resolve!: (value: Response) => void; const r = rig(url => url.endsWith('/alarm-change-reconnect-policy') ? new Promise<Response>(done => { resolve = done }) : undefined)
    await r.ready(); const pending = r.reconnect.enable(); await vi.advanceTimersByTimeAsync(400); resolve(response(policy(2, 100, 1000))); await pending
    expect(r.view().remainingMs).toBe(600); await vi.advanceTimersByTimeAsync(599); expect(r.view().active).toBe(true); await vi.advanceTimersByTimeAsync(1); expect(r.view().phase).toBe('exhausted'); expect(latest().closes).toBe(0)
    latest().ended(); await vi.advanceTimersByTimeAsync(5000); expect(ticketCalls(r.fetcher)).toHaveLength(1)
  })
  it('policy到达已过click窗则不arm；准备GET仍有已知connect timeout而非UI默认', async () => {
    fakeClock(); let resolve!: (value: Response) => void; const r = rig(url => url.endsWith('/alarm-change-reconnect-policy') ? new Promise<Response>(done => { resolve = done }) : undefined, policy(), false, 10000)
    await r.ready(); const pending = r.reconnect.enable(); await vi.advanceTimersByTimeAsync(1500); resolve(response(policy(2, 100, 1000))); await pending; expect(r.view().phase).toBe('exhausted'); expect(latest().closes).toBe(0); expect(ticketCalls(r.fetcher)).toHaveLength(1)
  })
  it('policy headers已回但body悬挂也在已知server期限abort，晚body不arm', async () => {
    fakeClock(); let resolve!: (value: string) => void, policySignal!: AbortSignal; const body = new Response('{}'); vi.spyOn(body, 'text').mockImplementation(() => new Promise<string>(done => { resolve = done }))
    const r = rig((url, init) => { if (url.endsWith('/alarm-change-reconnect-policy')) { policySignal = init!.signal!; return body } }); await r.ready(); const pending = r.reconnect.enable(); await flush(); await vi.advanceTimersByTimeAsync(1000); expect(policySignal.aborted).toBe(true); expect(r.view().phase).toBe('terminal'); resolve(JSON.stringify(policy())); await pending; expect(r.view().active).toBe(false); expect(latest().closes).toBe(0)
  })
  it('loading原连接断即cancel policy，late200/401/403无一arm或clear现owner', async () => {
    fakeClock(); for (const status of [200, 401, 403]) { let resolve!: (value: Response) => void, signal!: AbortSignal; const r = rig((url, init) => url.endsWith('/alarm-change-reconnect-policy') ? new Promise<Response>(done => { resolve = done; signal = init!.signal! }) : undefined); await r.ready(); const pending = r.reconnect.enable(); latest().ended(); expect(signal.aborted).toBe(true); resolve(status === 200 ? response(policy()) : response({ private: 'not-read' }, status)); await pending; expect(r.view().active).toBe(false); expect(useSession.getState().token).toBe(management.access_token); await vi.advanceTimersByTimeAsync(1000); expect(ticketCalls(r.fetcher)).toHaveLength(1); r.reconnect.dispose(); r.channel.disconnect(false) }
  })
  it('跨两次成功仍耗尽N，fresh票不同，N成功后healthy仍收HEAD和真正AUTH控制', async () => {
    fakeClock(); const r = rig(); await r.ready(); await r.reconnect.enable(); const firstTicket = latest().protocols[1]
    latest().ended(); await vi.advanceTimersByTimeAsync(100); expect(r.view().used).toBe(1); latest().open(); latest().message(frame(cursor('1', stream))); expect(r.view()).toMatchObject({ active: true, used: 1 }); const secondTicket = latest().protocols[1]
    latest().ended(); await vi.advanceTimersByTimeAsync(100); latest().open(); latest().message(frame(cursor('2', stream))); expect(r.view()).toMatchObject({ active: false, phase: 'exhausted', used: 2 }); expect(latest().closes).toBe(0); expect(new Set([firstTicket, secondTicket, latest().protocols[1]]).size).toBe(3)
    latest().message(frame(cursor('3', stream))); expect(r.hooks.head).toHaveBeenCalledTimes(4); latest().ended(1008, 'AUTHORIZATION_CHANGED'); expect(r.hooks.authorityLost).toHaveBeenCalledExactlyOnceWith(0); await vi.advanceTimersByTimeAsync(10000); expect(ticketCalls(r.fetcher)).toHaveLength(3)
  })
  it('第N次POST已扣不立即cancel，body pending可在剩余窗结算首valid', async () => {
    fakeClock(); let resolve!: (value: Response) => void, signal!: AbortSignal, count = 0; const r = rig((url, init) => url.endsWith('/alarm-change-channel') && ++count === 2 ? new Promise<Response>(done => { resolve = done; signal = init!.signal! }) : undefined, policy(1))
    await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100); expect(r.view()).toMatchObject({ active: true, used: 1 }); expect(signal.aborted).toBe(false)
    resolve(response(bootstrap())); await flush(); expect(NativeMock.instances).toHaveLength(2); latest().open(); expect(r.view().active).toBe(true); latest().message(frame(cursor('1', stream))); expect(r.view()).toMatchObject({ active: false, phase: 'exhausted', used: 1 }); expect(latest().closes).toBe(0)
  })
  it('成功不推迟expiry，window到保当前valid通道并关未来计划', async () => {
    fakeClock(); const r = rig(undefined, policy(10, 100, 1000)); await r.ready(); await r.reconnect.enable(); await vi.advanceTimersByTimeAsync(300); latest().ended(); await vi.advanceTimersByTimeAsync(100); latest().open(); latest().message(frame(cursor('1', stream))); expect(r.view().used).toBe(1)
    await vi.advanceTimersByTimeAsync(600); expect(r.view()).toMatchObject({ active: false, phase: 'exhausted', used: 1 }); expect(latest().closes).toBe(0); latest().ended(); await vi.advanceTimersByTimeAsync(500); expect(ticketCalls(r.fetcher)).toHaveLength(2)
  })
  it('stop healthy保通道；明确新click才新policy新窗，active heartbeat不能续预算', async () => {
    fakeClock(); const r = rig(); await r.ready(); await r.reconnect.enable(); r.reconnect.stop(); expect(latest().closes).toBe(0); expect(r.reconnect.canEnable()).toBe(true); await r.reconnect.enable(); expect(policyCalls(r.fetcher)).toHaveLength(2); latest().message(frame()); latest().message(frame()); await vi.advanceTimersByTimeAsync(5000); expect(r.view().phase).toBe('exhausted'); expect(ticketCalls(r.fetcher)).toHaveLength(1)
  })
  it('stop backoff无后续票；stop未valid WS取消候选而旧callbacks不污染新manual', async () => {
    fakeClock(); const r = rig(); await r.ready(); await r.reconnect.enable(); latest().ended(); r.reconnect.stop(); await vi.advanceTimersByTimeAsync(1000); expect(ticketCalls(r.fetcher)).toHaveLength(1)
    await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100); const old = latest(), oldOpen = old.onopen!, oldMessage = old.onmessage!, oldClose = old.onclose!; r.reconnect.stop(); expect(old.closes).toBe(1)
    await r.ready(); const now = latest(); oldOpen(new Event('open')); oldMessage({ data: frame(cursor('8', stream)) } as MessageEvent); oldClose({ code: 1008, reason: 'AUTHORIZATION_CHANGED' } as CloseEvent); expect(r.channel.getReady()).not.toBeNull(); expect(now.closes).toBe(0); expect(r.hooks.authorityLost).not.toHaveBeenCalled(); expect(r.view().active).toBe(false); await vi.advanceTimersByTimeAsync(5000); expect(now.closes).toBe(0); expect(r.channel.getReady()).not.toBeNull()
  })
  it('HTTP200 headers已到但真实text流悬挂：body截止terminal且late body不得新POST或WS', async () => {
    fakeClock()
    let count = 0, signal!: AbortSignal, body!: ReadableStreamDefaultController<Uint8Array>, released = false
    const reply = new Response(new ReadableStream<Uint8Array>({ start(controller) { body = controller } }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    const text = vi.spyOn(reply, 'text') // Observe the real native Response.text(), do not replace it.
    const r = rig((url, init) => { if (url.endsWith('/alarm-change-channel') && ++count === 2) { signal = init!.signal!; return reply } })
    const lifecycle = vi.spyOn(r.reconnect, 'lifecycle')
    const release = () => { if (!released) { released = true; body.enqueue(new TextEncoder().encode(JSON.stringify(bootstrap()))); body.close() } }
    try {
      await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100)
      expect(reply.status).toBe(200); expect(text).toHaveBeenCalledTimes(1); expect(signal.aborted).toBe(false); expect(ticketCalls(r.fetcher)).toHaveLength(2)
      await vi.advanceTimersByTimeAsync(1000); expect(signal.aborted).toBe(true)
      await vi.advanceTimersByTimeAsync(100)
      expect(ticketCalls(r.fetcher)).toHaveLength(2)
      expect(r.view()).toMatchObject({ active: false, phase: 'terminal', used: 1 })
      expect(lifecycle.mock.calls.some(([event]) => event.type === 'ended' && event.reason === 'body-timeout' && event.retryable === false)).toBe(true)
      useSession.setState({ token: 'late-body-new-owner' }); release(); await flush()
      expect(useSession.getState().token).toBe('late-body-new-owner'); expect(NativeMock.instances).toHaveLength(1); expect(ticketCalls(r.fetcher)).toHaveLength(2); expect(r.hooks.authorityLost).not.toHaveBeenCalled()
    } finally { release(); await flush() }
    // Busy JS: monotonic deadline passed, but the timer task has not dispatched.
    let lateCount = 0, deliver!: (value: Response) => void
    const late = rig(url => url.endsWith('/alarm-change-channel') && ++lateCount === 2 ? new Promise<Response>(done => { deliver = done }) : undefined)
    const lateEvents = vi.spyOn(late.reconnect, 'lifecycle')
    await late.ready(); await late.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100)
    const clock = vi.spyOn(performance, 'now').mockReturnValue(performance.now() + 1001)
    try {
      deliver(new Response(null, { status: 401 })); await flush()
      expect(useSession.getState().token).toBe(management.access_token)
      expect(late.view()).toMatchObject({ active: true, phase: 'backoff', used: 1 })
      expect(lateEvents.mock.calls.some(([event]) => event.type === 'ended' && event.reason === 'bootstrap-timeout' && event.retryable === true)).toBe(true)
      expect(ticketCalls(late.fetcher)).toHaveLength(2); expect(late.hooks.authorityLost).not.toHaveBeenCalled()
    } finally { late.reconnect.dispose(); late.channel.disconnect(false); clock.mockRestore() }
  })
  it('auto POST预响应也按既知connect timeout，deadline消耗一次且不重读policy', async () => {
    fakeClock(); let count = 0, signal!: AbortSignal; const r = rig((url, init) => url.endsWith('/alarm-change-channel') && ++count >= 2 ? (signal = init!.signal!, new Promise<Response>(() => {})) : undefined)
    await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100); expect(signal.aborted).toBe(false); await vi.advanceTimersByTimeAsync(1000); expect(signal.aborted).toBe(true); expect(r.view().used).toBe(1); await vi.advanceTimersByTimeAsync(100); expect(r.view().used).toBe(2); expect(policyCalls(r.fetcher)).toHaveLength(1)
  })
  it('auto候选无首valid的connect deadline可重试，不能由onopen假成功', async () => {
    fakeClock(); const r = rig(); await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100); const candidate = latest(); candidate.open(); expect(r.view()).toMatchObject({ phase: 'retrying', used: 1 }); await vi.advanceTimersByTimeAsync(1000); expect(candidate.closes).toBe(1); expect(r.view().phase).toBe('backoff'); await vi.advanceTimersByTimeAsync(100); expect(r.view().used).toBe(2); expect(ticketCalls(r.fetcher)).toHaveLength(3)
  })
  it('valid连接静默deadline也是有限恢复；窗口结束后旧B2 watchdog不能重启计划', async () => {
    fakeClock(); const r = rig(undefined, policy(2, 100, 20000)); await r.ready(); await r.reconnect.enable(); const original = latest(); await vi.advanceTimersByTimeAsync(15000); expect(original.closes).toBe(1); expect(r.view().phase).toBe('backoff'); await vi.advanceTimersByTimeAsync(100); latest().open(); latest().message(frame(cursor('1', stream))); await vi.advanceTimersByTimeAsync(4900); expect(r.view()).toMatchObject({ active: false, phase: 'exhausted', used: 1 }); expect(latest().closes).toBe(0); await vi.advanceTimersByTimeAsync(15000); expect(ticketCalls(r.fetcher)).toHaveLength(2)
  })
  it('剩余window比已知connect deadline短则先window取消，晚POST成功不构造WS', async () => {
    fakeClock(); let count = 0, resolve!: (value: Response) => void, signal!: AbortSignal; const r = rig((url, init) => url.endsWith('/alarm-change-channel') && ++count === 2 ? new Promise<Response>(done => { resolve = done; signal = init!.signal! }) : undefined, policy(2, 100, 1000))
    await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100); await vi.advanceTimersByTimeAsync(900); expect(signal.aborted).toBe(true); expect(r.view().phase).toBe('exhausted'); resolve(response(bootstrap())); await flush(); expect(NativeMock.instances).toHaveLength(1); expect(ticketCalls(r.fetcher)).toHaveLength(2)
  })
  it('后台时钟过期而timer尚未执行，before-dispatch/after-body guard仍禁止新票与WS', async () => {
    fakeClock(); const r = rig(undefined, policy(2, 100, 1000)); await r.ready(); await r.reconnect.enable(); latest().ended(); const clock = vi.spyOn(performance, 'now').mockReturnValue(50000); await vi.advanceTimersByTimeAsync(100); expect(ticketCalls(r.fetcher)).toHaveLength(1); expect(r.view().phase).toBe('exhausted'); clock.mockRestore()
  })
  it('stop policy与auto在途的晚status/body不会重新arm或清新scope/session', async () => {
    fakeClock(); let resolve!: (value: Response) => void, count = 0; const r = rig(url => url.endsWith('/alarm-change-channel') && ++count === 2 ? new Promise<Response>(done => { resolve = done }) : undefined)
    await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100); r.reconnect.stop(); useSession.getState().setScope(['tenant-b'], [], true); resolve(response({ private: 'not-read' }, 401)); await flush(); expect(useSession.getState().token).toBe(management.access_token); expect(NativeMock.instances).toHaveLength(1); expect(r.view().active).toBe(false)
  })
})
describe('P8b3可重试白名单与单attempt一次结束', () => {
  it('native仅1006/1001/正确版本1011 CHANNEL_UNAVAILABLE可自动恢复', async () => {
    fakeClock(); for (const [code, reason, retryable] of [[1006, '', true], [1001, '', true], [1011, 'CHANNEL_UNAVAILABLE', true], [1000, '', false], [1011, 'other', false], [1008, 'PROTOCOL_INVALID', false], [1009, '', false], [4000, '', false]] as const) {
      const r = rig(); await r.ready(); await r.reconnect.enable(); latest().ended(code, reason); await vi.advanceTimersByTimeAsync(100); expect(ticketCalls(r.fetcher)).toHaveLength(retryable ? 2 : 1); expect(r.view().active).toBe(retryable); r.reconnect.dispose(); r.channel.disconnect(false)
    }
  })
  it('onerror+真实close+旧callback重复只排一次，不从人类state猜', async () => {
    fakeClock(); const r = rig(); await r.ready(); await r.reconnect.enable(); const socket = latest(), oldClose = socket.onclose!, oldMessage = socket.onmessage!; socket.onerror?.(new Event('error')); await vi.advanceTimersByTimeAsync(100); expect(ticketCalls(r.fetcher)).toHaveLength(1)
    socket.ended(); oldClose({ code: 1006, reason: '' } as CloseEvent); oldMessage({ data: frame(cursor('9', stream)) } as MessageEvent); await vi.advanceTimersByTimeAsync(100); expect(ticketCalls(r.fetcher)).toHaveLength(2); expect(r.view().used).toBe(1)
  })
  it('auto ticket仅429/503与无HTTP网络异常可重试，其余HTTP terminal且消耗尝试', async () => {
    fakeClock(); for (const status of [429, 503, 403, 400, 409, 500, 502, 504, 200]) { let count = 0; const r = rig(url => url.endsWith('/alarm-change-channel') && ++count > 1 ? status === 200 ? new Response(null, { status }) : response({ private: 'not-read' }, status) : undefined)
      await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(100); expect(r.view().used).toBe(1); const retries = status === 429 || status === 503; expect(r.view().active).toBe(retries); await vi.advanceTimersByTimeAsync(100); expect(ticketCalls(r.fetcher)).toHaveLength(retries ? 3 : 2); r.reconnect.dispose(); r.channel.disconnect(false)
    }
    let count = 0; const r = rig(url => url.endsWith('/alarm-change-channel') && ++count > 1 ? Promise.reject(new TypeError('synthetic network unavailable')) : undefined); await r.ready(); await r.reconnect.enable(); latest().ended(); await vi.advanceTimersByTimeAsync(200); expect(ticketCalls(r.fetcher)).toHaveLength(3); expect(r.view().used).toBe(2)
  })
  it('错误echo/binary/raw/epoch不可自动重试，准确AUTH先停计划且仍走原authority hook', async () => {
    fakeClock(); for (const kind of ['echo', 'binary', 'raw', 'epoch', 'auth']) { const r = rig(); await r.ready(cursor('1', stream)); await r.reconnect.enable(); const socket = latest()
      if (kind === 'echo') { socket.protocol = 'wrong'; socket.message(frame(cursor('2', stream))) } else if (kind === 'binary') socket.message(new Blob(['x'])); else if (kind === 'raw') socket.message('{"v":1,"v":1}'); else if (kind === 'epoch') socket.message(frame(cursor('2', otherStream))); else socket.ended(1008, 'AUTHORIZATION_CHANGED')
      expect(r.view().active).toBe(false); await vi.advanceTimersByTimeAsync(500); expect(ticketCalls(r.fetcher)).toHaveLength(1); if (kind === 'auth') expect(r.hooks.authorityLost).toHaveBeenCalledExactlyOnceWith(0); else expect(r.hooks.authorityLost).not.toHaveBeenCalled(); r.reconnect.dispose(); r.channel.disconnect(false)
    }
  })
  it('普通HTTP C推进后下一attempt取最新C，不固定arm旧C也不拿WS W', async () => {
    fakeClock(); const r = rig(); await r.ready(cursor('9', stream)); await r.reconnect.enable(); r.setContext({ cursor: cursor('2', stream), authorityEpoch: 0, baselineEpoch: 1 }); latest().ended(); await vi.advanceTimersByTimeAsync(100); latest().open(); latest().message(frame(cursor('2', stream))); expect(r.channel.getReady()).not.toBeNull(); expect(r.view()).toMatchObject({ active: true, used: 1 }); expect(policyCalls(r.fetcher)).toHaveLength(1)
  })
  it('baseline/authority变化不续计划，旧timer不能在新context发票', async () => {
    fakeClock(); for (const mode of ['baseline', 'authority']) { const r = rig(); await r.ready(); await r.reconnect.enable(); latest().ended(); r.setContext({ cursor: cursor(), authorityEpoch: mode === 'authority' ? 1 : 0, baselineEpoch: mode === 'baseline' ? 2 : 1 }); await vi.advanceTimersByTimeAsync(100); expect(ticketCalls(r.fetcher)).toHaveLength(1); expect(r.reconnect.canEnable()).toBe(false); r.reconnect.dispose(); r.channel.disconnect(false) }
  })
})
function wb(url: string) { const q = new URL(url, 'http://local').searchParams; return { items: [alarm], page: Number(q.get('page') ?? 1), size: Number(q.get('size') ?? 2), total: 1, filters: { ...emptyAlarmFilters }, as_of: at, timezone: 'Asia/Shanghai', metrics: { alarm_total: 1, unhandled: 1, handled: 0, unrecovered: 1, recovered: 0, true_fire: 0 }, metric_definitions: { ...expectedMetricDefinitions } } }
function feed(url: string, head: bigint, epoch: string | null) { const q = new URL(url, 'http://local').searchParams, start = q.has('cursor') ? BigInt(readChangeCoordinate(q.get('cursor')!).sequence) : head, target = q.has('through') ? BigInt(readChangeCoordinate(q.get('through')!).sequence) : head, count = q.has('cursor') ? Number(target - start < 2n ? target - start : 2n) : 0, position = start + BigInt(count); return { mode: q.has('cursor') ? 'CHANGES' : 'BASELINE', as_of: at, timezone: 'Asia/Shanghai', scope: { tenant_id: 'tenant-a', audience: 'management', stream_id: epoch }, size: 2, items: Array.from({ length: count }, (_, i) => ({ delivery_id: `00000000-0000-4000-8000-${String(start + BigInt(i) + 1n).padStart(12, '0')}`, sequence: String(start + BigInt(i) + 1n), type: 'ALARM_CHANGED' })), next_cursor: cursor(String(position), epoch), through_cursor: cursor(String(target), epoch), has_more: position < target, pending_count: '0', semantics: { ...changeSemantics } } }
function ui(override?: Override) { login(); let tickets = 0; const state = { head: 0n, epoch: null as string | null }; const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => { const special = override?.(url, init); if (special !== undefined) return special; if (url.includes('/alarm-workbench?')) return response(wb(url)); if (url.includes('/alarm-changes')) return response(feed(url, state.head, state.epoch)); if (url.endsWith('/alarm-change-channel')) return response(bootstrap(String(++tickets).padStart(43, 'T'), 'management', 10000)); if (url.endsWith('/alarm-change-reconnect-policy')) return response(policy(2, 100, 10000)); throw new Error('Unexpected UI test endpoint') }); vi.stubGlobal('fetch', fetcher); return { fetcher, state } }
const panel = () => screen.getByRole('region', { name: '报警变更检查' })
const autoStatus = () => panel().querySelector('[data-alarm-reconnect-status]')!
const dirty = () => document.querySelector('[data-alarm-change-dirty]')
async function boot(user: ReturnType<typeof userEvent.setup>) { await waitFor(() => expect(document.querySelector('[data-workbench-metric="alarm_total"]')).toHaveTextContent('1')); await user.click(screen.getByRole('button', { name: '打开报警变更检查' })); await screen.findByText('报警变更检查（手动）'); await user.click(within(panel()).getByRole('button', { name: '初始化报警变更检查' })); await waitFor(() => expect(within(panel()).getByRole('button', { name: '连接变更信号' })).toBeEnabled()) }
async function manual(user: ReturnType<typeof userEvent.setup>) { await user.click(within(panel()).getByRole('button', { name: '连接变更信号' })); await waitFor(() => expect(latest()).toBeDefined()); act(() => { latest().open(); latest().message(frame()) }) }
describe('P8b3真实Panel/原WB authority/草稿和manual隔离', () => {
  it('默认0policy/auto；H-WB ready也不启，首manual valid后按钮才可click', async () => {
    const { fetcher } = ui(); render(<AlarmsPage/>); const user = userEvent.setup(); await boot(user); expect(within(panel()).getByRole('button', { name: '启用有界自动重连' })).toBeDisabled(); expect(policyCalls(fetcher)).toHaveLength(0)
    await user.click(within(panel()).getByRole('button', { name: '连接变更信号' })); await waitFor(() => expect(latest()).toBeDefined()); act(() => latest().open()); expect(within(panel()).getByRole('button', { name: '启用有界自动重连' })).toBeDisabled(); act(() => latest().message(frame())); expect(within(panel()).getByRole('button', { name: '启用有界自动重连' })).toBeEnabled(); expect(policyCalls(fetcher)).toHaveLength(0)
    await user.click(within(panel()).getByRole('button', { name: '启用有界自动重连' })); await waitFor(() => expect(autoStatus()).toHaveAttribute('data-alarm-reconnect-status', 'healthy')); expect(policyCalls(fetcher)).toHaveLength(1); expect(ticketCalls(fetcher)).toHaveLength(1); expect(panel()).toHaveAttribute('data-alarm-change-position', '0')
  })
  it('auto连接保持固定HTTP T/高位去重，普通check与WB refresh不重读policy', async () => {
    const { fetcher, state } = ui(); render(<AlarmsPage/>); const user = userEvent.setup(); await boot(user); await manual(user); await user.click(within(panel()).getByRole('button', { name: '启用有界自动重连' })); await waitFor(() => expect(autoStatus()).toHaveAttribute('data-alarm-reconnect-status', 'healthy'))
    state.head = 3n; state.epoch = stream; await user.click(within(panel()).getByRole('button', { name: '检查已发布报警变更' })); await waitFor(() => expect(panel()).toHaveAttribute('data-alarm-change-position', '2')); expect(panel()).toHaveAttribute('data-alarm-change-through', '3')
    const original = latest(); act(() => original.ended()); await waitFor(() => expect(NativeMock.instances).toHaveLength(2)); state.head = 5n; act(() => { latest().open(); latest().message(frame(cursor('5', stream))) }); await user.click(screen.getByRole('button', { name: '刷新数据' })); await waitFor(() => expect(dirty()).toBeNull()); act(() => latest().message(frame(cursor('5', stream)))); expect(dirty()).toBeNull(); expect(panel()).toHaveAttribute('data-alarm-change-position', '2'); expect(panel()).toHaveAttribute('data-alarm-change-through', '3'); expect(policyCalls(fetcher)).toHaveLength(1)
    expect(fetcher.mock.calls.filter(c => c[1]?.method === 'POST' && !c[0].endsWith('/alarm-change-channel'))).toHaveLength(0)
  })
  it('已arm后开复核Sheet不续期或停计划，真实auto firstvalid保备注且无WB/补拉/业务写', async () => {
    const { fetcher, state } = ui(); render(<AlarmsPage/>); const user = userEvent.setup(); await boot(user); await manual(user); await user.click(within(panel()).getByRole('button', { name: '启用有界自动重连' })); await waitFor(() => expect(autoStatus()).toHaveAttribute('data-alarm-reconnect-status', 'healthy')); const checkedPanel = panel()
    await user.click(within(document.querySelector('[data-alarm-id="a1"]') as HTMLElement).getByRole('button', { name: '查看' })); fireEvent.change(screen.getByLabelText('现场情况 / 处置备注'), { target: { value: '自动信号不能动的备注' } }); const calls = fetcher.mock.calls.length; act(() => latest().ended()); await waitFor(() => expect(NativeMock.instances).toHaveLength(2)); state.head = 1n; state.epoch = stream; act(() => { latest().open(); latest().message(frame(cursor('1', stream))) }); expect(screen.getByLabelText('现场情况 / 处置备注')).toHaveValue('自动信号不能动的备注'); expect(fetcher.mock.calls).toHaveLength(calls + 1); expect(policyCalls(fetcher)).toHaveLength(1); expect(checkedPanel).toHaveAttribute('data-alarm-change-position', '0'); expect(checkedPanel).toHaveAttribute('data-alarm-change-through', '0'); expect(fetcher.mock.calls.filter(c => c[1]?.method === 'POST' && !c[0].endsWith('/alarm-change-channel'))).toHaveLength(0)
  })
  it('AUTHORITY优先停plan/清安全Sheet和缓存，唯一WB恢复后无旧计划', async () => {
    const { fetcher } = ui(); render(<AlarmsPage/>); const user = userEvent.setup(); await boot(user); await manual(user); await user.click(within(panel()).getByRole('button', { name: '启用有界自动重连' })); await waitFor(() => expect(autoStatus()).toHaveAttribute('data-alarm-reconnect-status', 'healthy')); const count = fetcher.mock.calls.length
    act(() => latest().ended(1008, 'AUTHORIZATION_CHANGED')); await screen.findByRole('button', { name: '重新读取当前工作台确认权限' }); expect(document.querySelector('[data-workbench-metric], [data-alarm-change-position]')).toBeNull(); expect(fetcher.mock.calls).toHaveLength(count); expect(useSession.getState().token).toBe(management.access_token)
    await user.click(screen.getByRole('button', { name: '重新读取当前工作台确认权限' })); await waitFor(() => expect(document.querySelector('[data-workbench-metric="alarm_total"]')).toHaveTextContent('1')); expect(screen.queryByRole('region', { name: '报警变更检查' })).not.toBeInTheDocument(); expect(policyCalls(fetcher)).toHaveLength(1)
  })
  it.each(['disconnect', 'connect', 'reconnect', 'initialize', 'close', 'scope', 'token', 'permissions', 'logout'])('manual/context %s 先停plan，旧close不派新工作', async action => {
      const { fetcher } = ui(); const view = render(<AlarmsPage/>), user = userEvent.setup(); await boot(user); await manual(user); await user.click(within(panel()).getByRole('button', { name: '启用有界自动重连' })); await waitFor(() => expect(autoStatus()).toHaveAttribute('data-alarm-reconnect-status', 'healthy')); const old = latest(), oldClose = old.onclose!
      if (action === 'scope') act(() => useSession.getState().setScope(['tenant-b'], [], true)); else if (action === 'token') act(() => useSession.setState({ token: 'replacement-owner' })); else if (action === 'permissions') act(() => useSession.setState({ permissions: [] })); else if (action === 'logout') act(() => useSession.getState().clear()); else await user.click(within(panel()).getByRole('button', { name: action === 'disconnect' ? '断开变更信号' : action === 'connect' ? '连接变更信号' : action === 'reconnect' ? '手动重连变更信号' : action === 'initialize' ? '刷新工作台并重建检查基线' : '关闭报警变更检查' }))
      act(() => oldClose({ code: 1006, reason: '' } as CloseEvent)); expect(policyCalls(fetcher)).toHaveLength(1); expect(document.querySelector('[data-alarm-authority-blocked]')).toBeNull(); if (screen.queryByRole('region', { name: '报警变更检查' })) expect(autoStatus()).not.toHaveAttribute('data-alarm-reconnect-status', 'backoff'); view.unmount(); NativeMock.instances = []
  })
  it('390启停/预算语义可见而无票/凭据/storage，不伪称pixel或自动实体同步', async () => {
    vi.stubGlobal('innerWidth', 390); ui(); render(<AlarmsPage/>); const user = userEvent.setup(); await boot(user); await manual(user); await user.click(within(panel()).getByRole('button', { name: '启用有界自动重连' })); await waitFor(() => expect(autoStatus()).toHaveAttribute('data-alarm-reconnect-status', 'healthy')); expect(within(panel()).getByRole('button', { name: '停止自动重连' })).toBeVisible(); expect(autoStatus()).toHaveTextContent('本轮已用恢复尝试 0 / 2'); expect(autoStatus()).toHaveTextContent('成功不续期'); expect(autoStatus()).toHaveTextContent('固定恢复间隔 100 毫秒'); expect(autoStatus()).toHaveTextContent('本轮总窗口 10000 毫秒'); await user.click(within(panel()).getByRole('button', { name: '停止自动重连' })); expect(latest().closes).toBe(0); expect(document.body.innerHTML).not.toContain(management.access_token); expect(document.body.innerHTML).not.toContain(latest().protocols[1]); expect(localStorage.length + sessionStorage.length).toBe(0)
  })
})
