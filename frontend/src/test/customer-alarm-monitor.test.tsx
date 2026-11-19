import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CustomerAlarmMonitor } from '@/components/customer-alarm-monitor'
import { useSession } from '@/lib/store'
import { emptyAlarmFilters, expectedMetricDefinitions } from '@/lib/alarm-workbench'
import { changeSemantics } from '@/lib/alarm-changes'
import { channelSemantics } from '@/lib/alarm-change-channel'
import type { Alarm } from '@/lib/types'
import { dateTime } from '@/lib/format'
import { alarm, customer, management, response } from './fixtures'
import { publishObjectChange } from '@/lib/object-events'

const stream = '11111111-1111-4111-8111-111111111111', session = 'a'.repeat(64), at = '2026-11-16T07:00:00Z'
function cursor(sequence = '0') { return btoa(JSON.stringify({ v: 1, tenant_id: 'tenant-a', audience: 'customer', session, stream_id: stream, sequence })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') }
function frame(sequence: string) { return JSON.stringify({ v: 1, type: 'ALARM_CHANGE_SIGNAL', head_cursor: cursor(sequence), semantics: channelSemantics }) }
class Socket {
  static instances: Socket[] = []
  readyState = 0; protocol = ''
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  constructor() { Socket.instances.push(this) }
  open() { this.readyState = 1; this.protocol = 'alarm-changes.v1'; this.onopen?.(new Event('open')); this.message('0') }
  message(sequence: string) { this.onmessage?.({ data: frame(sequence) } as MessageEvent) }
  revoke() { this.readyState = 3; this.onclose?.({ code: 1008, reason: 'AUTHORIZATION_CHANGED' } as CloseEvent) }
  close() { this.readyState = 3 }
}
function snapshot(rows: Alarm[], url: string) {
  const query = new URL(url, 'http://local').searchParams, page = Number(query.get('page')), size = Number(query.get('size')), total = rows.length
  return { items: rows.slice((page - 1) * size, page * size), page, size, total, filters: { ...emptyAlarmFilters, physical_state: 'FIRE', response_state: 'UNHANDLED' }, as_of: at, timezone: 'Asia/Shanghai', metrics: { alarm_total: total, unhandled: total, handled: 0, unrecovered: total, recovered: 0, true_fire: 0 }, metric_definitions: expectedMetricDefinitions }
}
function setup(initial: Alarm[] = []) {
  useSession.getState().login(customer, 'customer')
  const state = { rows: initial, hold: null as null | ((url: string, signal?: AbortSignal) => Promise<Response>) }
  const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    if (url.includes('/alarm-workbench?')) return state.hold ? state.hold(url, init?.signal ?? undefined) : response(snapshot(state.rows, url))
    if (url.includes('/alarm-changes')) return response({ mode: 'BASELINE', as_of: at, timezone: 'Asia/Shanghai', scope: { tenant_id: 'tenant-a', audience: 'customer', stream_id: stream }, size: 20, items: [], next_cursor: cursor(), through_cursor: cursor(), has_more: false, pending_count: '0', semantics: { ...changeSemantics, order: 'PER_ACTOR_VISIBLE_PUBLICATION_RANK' } })
    if (url.endsWith('/alarm-change-channel')) return response({ v: 1, scope: { tenant_id: 'tenant-a', audience: 'customer', session }, ticket: 'T'.repeat(43), connect_timeout_ms: 10000, silence_timeout_ms: 15000 })
    if (url.includes('/alarms/')) { const row = state.rows.find(item => url.endsWith('/' + item.id)); if (row) return response(row) }
    throw new Error('Unexpected monitor endpoint: ' + url)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<CustomerAlarmMonitor/>)
  return { state, fetcher }
}
async function connect() { await waitFor(() => expect(Socket.instances).toHaveLength(1)); act(() => Socket.instances[0].open()); return Socket.instances[0] }
beforeEach(() => { useSession.getState().clear(); Socket.instances = []; vi.stubGlobal('WebSocket', Socket) })
afterEach(() => { vi.useRealTimers() })

describe('客户全局报警即时提醒', () => {
  it('不等待5秒定时器即可从真实协议head信号弹出两来源报警，稍后处理不写处置', async () => {
    const { state, fetcher } = setup(), socket = await connect()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    state.rows = [{ ...alarm, id: 'legacy-fire', device_name: '旧来源烟感' }, { ...alarm, id: 'hik-fire', device_name: '海康来源烟感' }]
    act(() => socket.message('1'))
    expect(await screen.findByRole('heading', { name: '旧来源烟感' })).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: '稍后处理' }))
    expect(await screen.findByRole('heading', { name: '海康来源烟感' })).toBeVisible()
    expect(fetcher.mock.calls.some(([url]) => String(url).endsWith('/review'))).toBe(false)
  })
  it('即时通道保持ready但没有新head时仍补查并显示真实新报警', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const { state, fetcher } = setup([{ ...alarm, id: 'initial', device_name: '初始已读取报警' }])
      expect(await screen.findByRole('heading', { name: '初始已读取报警' })).toBeVisible()
      await connect()
      expect(fetcher.mock.calls.filter(([url]) => String(url).includes('/alarm-workbench?')).length).toBeGreaterThan(0)
      fireEvent.click(screen.getByRole('button', { name: '稍后处理' }))
      state.rows = [{ ...alarm, id: 'fire-without-head', device_name: '没有即时信号的真实报警' }]
      const reads = fetcher.mock.calls.filter(([url]) => String(url).includes('/alarm-workbench?')).length
      await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
      expect(await screen.findByRole('heading', { name: '没有即时信号的真实报警' })).toBeVisible()
      expect(fetcher.mock.calls.filter(([url]) => String(url).includes('/alarm-workbench?')).length).toBeGreaterThan(reads)
    } finally { vi.useRealTimers() }
  })
  it('另一台设备的新报警事件重读HTTP事实且不打断处理中复核草稿', async () => {
    const { state, fetcher } = setup([{ ...alarm, device_name: '正在核查的烟感' }]); await connect(); const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '立即处理' }))
    await user.click(await screen.findByRole('button', { name: '开始人工复核' }))
    fireEvent.change(screen.getByLabelText('现场情况 / 处置备注'), { target: { value: '现场核查尚未提交' } })
    state.rows = [...state.rows, { ...alarm, id: 'second-fire', device_name: '另一台烟感' }]
    act(() => publishObjectChange('events'))
    await waitFor(() => expect(fetcher.mock.calls.filter(([url]) => String(url).includes('/alarm-workbench?')).length).toBeGreaterThan(1))
    expect(screen.queryByRole('heading', { name: '火灾报警提醒' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('现场情况 / 处置备注')).toHaveValue('现场核查尚未提交')
  }, 20_000)
  it.each(['SOURCE_REPORTED', 'SOURCE_TIME_MISSING'] as const)('保留平台登记时间并按%s区分来源时间，不拿平台时间补猜', async quality => {
    const sourceTime = '2026-11-16T07:00:00Z', recorded = '2026-11-16T07:01:30Z'
    setup([{ ...alarm, time_quality: quality, occurred_at: quality === 'SOURCE_REPORTED' ? sourceTime : null, recorded_at: recorded }])
    await connect()
    await userEvent.click(await screen.findByRole('button', { name: '立即处理' }))
    await screen.findByText('平台登记时间')
    expect(screen.getByText('平台登记时间').nextElementSibling).toHaveTextContent(dateTime(recorded))
    expect(screen.getByText('来源发生时间').nextElementSibling).toHaveTextContent(quality === 'SOURCE_REPORTED' ? dateTime(sourceTime) : '来源发生时间未知')
    expect(screen.queryByText(/历史时间来源未核验/)).not.toBeInTheDocument()
  }, 20_000)
  it('服务端失权关闭后，旧读取的晚成功不能重新弹出原可见范围的报警', async () => {
    const { state } = setup(), socket = await connect()
    let resolve!: (value: Response) => void, heldUrl = '', signal: AbortSignal | undefined
    state.hold = (url, currentSignal) => { heldUrl = url; signal = currentSignal; return new Promise<Response>(done => { resolve = done }) }
    act(() => socket.message('1'))
    await waitFor(() => expect(resolve).toBeTypeOf('function'))
    act(() => socket.revoke())
    expect(signal?.aborted).toBe(true)
    state.hold = null
    await act(async () => { resolve(response(snapshot([{ ...alarm, device_name: '已撤销范围的报警' }], heldUrl))) })
    expect(screen.queryByRole('heading', { name: '已撤销范围的报警' })).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
  it('管理端或没有报警读取权限的客户不启动客户报警数据读取', () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    useSession.getState().login(management, 'management')
    const view = render(<CustomerAlarmMonitor/>)
    act(() => useSession.getState().login({ ...customer, permissions: ['device:read'] }, 'customer'))
    view.rerender(<CustomerAlarmMonitor/>)
    expect(fetcher).not.toHaveBeenCalled()
  })
})
