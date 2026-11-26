import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ConfigProvider } from 'antd'
import App from '@/App'
import { useSession } from '@/lib/store'
import { emptyAlarmFilters, expectedMetricDefinitions } from '@/lib/alarm-workbench'
import { dateTime } from '@/lib/format'
import { CustomerAlarmMonitor } from '@/components/customer-alarm-monitor'
import { publishObjectChange } from '@/lib/object-events'
import { alarm, customer, response } from './fixtures'

afterEach(() => { useSession.getState().clear(); vi.unstubAllGlobals() })
it('combines system, manual handling state and Beijing time in the customer alarm query', async () => {
  history.replaceState(null, '', '/customer')
  useSession.getState().login({ ...customer, permissions: ['alarm:read', 'device:read', 'maintenance:read'] }, 'customer')
  useSession.getState().navigate('alarms')
  const fetcher = vi.fn((url: string) => {
    if (url === '/api/health') return response({ status: 'UP', storage_mode: 'h2-dev', roles: [] })
    if (!url.startsWith('/api/customer/alarm-workbench?')) throw new Error(`Unexpected ${url}`)
    const params = new URL(url, 'http://local').searchParams
    const from = params.get('occurred_from')
    return response({ items: [], total: 0, page: 1, size: Number(params.get('size') ?? 20), as_of: '2026-11-24T03:00:00Z', timezone: 'Asia/Shanghai', filters: { ...emptyAlarmFilters, response_state: params.get('response_state'), occurred_from: from && new Date(from).toISOString() }, metric_definitions: expectedMetricDefinitions, metrics: { alarm_total: 0, unhandled: 0, handled: 0, unrecovered: 0, recovered: 0, true_fire: 0 } })
  })
  vi.stubGlobal('fetch', fetcher)
  render(<App/>)
  const systems = within(await screen.findByRole('navigation', { name: '所属系统' }))
  const sidebar = within(screen.getByRole('navigation', { name: '栏目菜单' }))
  for (const name of ['报警归并', '报警历史', '故障历史', '维保工单']) expect(sidebar.queryByRole('button', { name })).not.toBeInTheDocument()
  for (const name of ['报警监控', '故障监控']) expect(sidebar.getByRole('button', { name })).toBeInTheDocument()
  fireEvent.click(systems.getByRole('button', { name: '消防用水监测报警系统' }))
  await waitFor(() => expect(new URL(fetcher.mock.lastCall![0], 'http://local').searchParams.get('system_type')).toBe('消防用水监测报警系统'))
  fireEvent.change(screen.getByLabelText('人工处置筛选'), { target: { value: 'UNHANDLED' } })
  fireEvent.change(screen.getByLabelText('开始时间（北京时间）'), { target: { value: '2026-11-24T01:30' } })
  fireEvent.click(screen.getByRole('button', { name: '搜索' }))
  await waitFor(() => {
    const sent = new URL(fetcher.mock.lastCall![0], 'http://local').searchParams
    expect(sent.get('occurred_from')).toMatch(/^2026-11-24T01:30(?::00)?\+08:00$/)
    expect(sent.get('system_type')).toBe('消防用水监测报警系统')
    expect(sent.get('response_state')).toBe('UNHANDLED')
  })
})

it('filters the customer fault records themselves by system and resets to the first page', async () => {
  history.replaceState(null, '', '/customer')
  useSession.getState().login({ ...customer, permissions: ['device:read'] }, 'customer')
  useSession.getState().navigate('device-faults')
  const fetcher = vi.fn((url: string) => {
    if (url === '/api/health') return response({ status: 'UP', storage_mode: 'h2-dev', roles: [] })
    if (url.startsWith('/api/customer/duty/faults?')) return response({ items: [], total: 0, page: 1, size: 10 })
    throw new Error(`Unexpected ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<App/>)
  await screen.findByRole('table', { name: '故障监控' })
  fireEvent.click(within(screen.getByRole('navigation', { name: '所属系统' })).getByRole('button', { name: '电气火灾监控预警系统' }))
  await waitFor(() => {
    const sent = fetcher.mock.calls.map(([url]) => new URL(url, 'http://local')).find(url => url.searchParams.get('system_type') === '电气火灾监控预警系统')
    expect(sent?.pathname).toBe('/api/customer/duty/faults')
    expect(sent?.searchParams.get('page')).toBe('1')
  })
})

it('shows only source time, advances only the reminder, and opens review for the next alarm', async () => {
  const recorded = '2026-11-24T03:02:00Z'
  const occurred = '2026-11-24T02:59:00Z'
  const incidents = [
    { ...alarm, id: 'unknown-source', device_eui: 'TEST-001', occurred_at: null, time_quality: 'SOURCE_TIME_MISSING', recorded_at: recorded },
    { ...alarm, id: 'review-target', device_eui: 'TEST-002', device_name: '第二个测试烟感', occurred_at: occurred, time_quality: 'SOURCE_REPORTED', recorded_at: recorded },
  ]
  const fetcher = vi.fn((url: string, _init?: RequestInit) => {
    if (url.endsWith('/alarms/review-target')) return response(incidents[1])
    if (url.includes('/alarm-workbench?')) return response({
      items: incidents, page: 1, size: 100, total: 2, as_of: '2026-11-24T03:03:00Z', timezone: 'Asia/Shanghai',
      filters: { ...emptyAlarmFilters, physical_state: 'FIRE', response_state: 'UNHANDLED' },
      metrics: { alarm_total: 2, unhandled: 2, handled: 0, unrecovered: 2, recovered: 0, true_fire: 0 },
      metric_definitions: expectedMetricDefinitions,
    })
    return response({ code: 'ALARM_CHANGES_DISABLED' }, 503)
  })
  vi.stubGlobal('fetch', fetcher)
  useSession.getState().login(customer, 'customer')
  render(<CustomerAlarmMonitor/>)
  const popup = within(await screen.findByRole('dialog', { name: /火灾报警 -/ }))
  expect(popup.getByText('TEST-001')).toBeInTheDocument()
  expect(popup.getByText('来源发生时间未知')).toBeInTheDocument()
  expect(popup.queryByText(dateTime(recorded))).not.toBeInTheDocument()
  fireEvent.click(popup.getByRole('button', { name: '关闭' }))
  await popup.findByText('TEST-002')
  expect(popup.queryByText('TEST-001')).not.toBeInTheDocument()
  expect(popup.getByText(dateTime(occurred))).toBeInTheDocument()
  expect(popup.queryByText(dateTime(recorded))).not.toBeInTheDocument()
  fireEvent.click(popup.getByRole('button', { name: '复核' }))
  await screen.findByLabelText('现场情况 / 处置备注')
  expect(fetcher.mock.calls.some(([url]) => url.endsWith('/alarms/review-target'))).toBe(true)
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})

it('closes only alarms currently queued and presents a newly received alarm without writing alarm state', async () => {
  let incidents = [
    { ...alarm, id: 'first-fire', device_name: '第一台烟感' },
    { ...alarm, id: 'second-fire', device_name: '第二台烟感' },
  ]
  const fetcher = vi.fn((url: string, _init?: RequestInit) => {
    if (!url.includes('/alarm-workbench?')) return response({ code: 'ALARM_CHANGES_DISABLED' }, 503)
    const count = incidents.length
    return response({
      items: incidents, page: 1, size: 100, total: count, as_of: '2026-11-24T03:03:00Z', timezone: 'Asia/Shanghai',
      filters: { ...emptyAlarmFilters, physical_state: 'FIRE', response_state: 'UNHANDLED' },
      metrics: { alarm_total: count, unhandled: count, handled: 0, unrecovered: count, recovered: 0, true_fire: 0 },
      metric_definitions: expectedMetricDefinitions,
    })
  })
  vi.stubGlobal('fetch', fetcher)
  useSession.getState().login({ ...customer, permissions: ['alarm:read'] }, 'customer')
  render(<ConfigProvider theme={{ token: { motion: false } }}><CustomerAlarmMonitor/></ConfigProvider>)
  const popup = within(await screen.findByRole('dialog'))
  expect(popup.getByText('第一台烟感')).toBeInTheDocument()
  expect(popup.queryByRole('button', { name: '复核' })).not.toBeInTheDocument()
  expect(popup.getByRole('button', { name: '消音' })).toBeDisabled()
  fireEvent.click(popup.getByRole('button', { name: '关闭所有' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  incidents = [...incidents, { ...alarm, id: 'third-fire', device_name: '第三台烟感' }]
  act(() => publishObjectChange('events'))
  expect(within(await screen.findByRole('dialog')).getByText('第三台烟感')).toBeInTheDocument()
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
