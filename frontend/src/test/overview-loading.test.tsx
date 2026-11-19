import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DutyOverviewPage } from '@/pages/duty-overview'
import { OperationsOverviewPage } from '@/pages/operations-overview'
import { useSession } from '@/lib/store'
import type { DutyAnalytics } from '@/lib/duty-api'
import type { OpsOverview, Summary } from '@/lib/types'
import { management, response } from './fixtures'

const summary: Summary = { device_total: 17, online: 8, offline: 5, unactivated: 4, unknown_connectivity: 0, today_alarms: 5, unhandled_alarms: 6, low_battery: 0 }
const daily = [4, 4, 4, 4, 5, 5, 5].map((count, index) => ({ date: `2026-11-0${index + 1}`, count }))
const analytics: DutyAnalytics = {
  as_of: '2026-11-08T00:00:00Z', from: '2026-11-01T00:00:00Z', to: '2026-11-08T00:00:00Z', timezone: 'UTC', days: 7,
  alarms: 31, faults: 2, untimed_fault_events: 3, reported_offline_events: 0, alarms_by_day: daily,
  faults_by_day: daily.map((point, index) => ({ ...point, count: index === 0 || index === 6 ? 1 : 0 })),
  reported_offline_events_by_day: daily.map(point => ({ ...point, count: 0 })),
  fault_types: [{ code: '1002003', label: '防拆报警', count: 2 }], reported_offline_devices: [],
  offline_semantics: 'ONLY_REPORTED_OFFLINE_EVENTS', fault_semantics: 'ONLY_LEGACY_INGEST_EVENTS_KIND_FAULT',
}
const ops: OpsOverview = { computed_at: analytics.as_of, alarms: { unhandled: 6, fire: 4, oldest_unhandled_at: analytics.from }, channel: { head: 42, pending: 2, last_recorded_at: analytics.as_of }, tasks: { raw_message_pending: 0, reparse_pending: 0 }, sources: { enabled: 1, last_received_at: analytics.as_of }, semantics: '隔离测试快照' }
const pages = [
  { name: '值守概览', Component: DutyOverviewPage, region: '值守状态', deviceLabel: '设备总数', chart: '7天实际报警逐日趋势，UTC', refresh: '刷新概览' },
  { name: '运维概览', Component: OperationsOverviewPage, region: '运维关键指标', deviceLabel: '设备总数 · 当前状态', chart: '7天报警逐日趋势，UTC', refresh: '刷新运维概览' },
]
type Reply = () => Response | Promise<Response>
function mountPage(Component: typeof DutyOverviewPage, overrides: { summary?: Reply; analytics?: Reply; ops?: Reply } = {}) {
  useSession.getState().login({ ...management, permissions: ['summary:read'] }, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const path = new URL(url, 'http://local').pathname
    if (path.endsWith('/summary')) return overrides.summary?.() ?? response(summary)
    if (path.endsWith('/ops-overview')) return overrides.ops?.() ?? response(ops)
    if (path.endsWith('/duty/analytics')) return overrides.analytics?.() ?? response(analytics)
    throw new Error(`Unexpected overview request: ${url}`)
  }))
  render(<Component />)
}
const metric = (region: string, label: string) => within(screen.getByRole('region', { name: region })).getByText(label).nextElementSibling
beforeEach(() => { useSession.getState().clear(); vi.restoreAllMocks() })

describe.each(pages)('$name 独立加载', page => {
  it.each(['pending', 'failed'])('趋势查询 %s 时保留成功的设备摘要', async state => {
    mountPage(page.Component, { analytics: () => state === 'pending' ? new Promise<Response>(() => {}) : response({ code: 'QA_ANALYTICS_UNAVAILABLE' }, 503) })
    await waitFor(() => expect(metric(page.region, page.deviceLabel)).toHaveTextContent(/^17$/))
    if (state === 'failed') expect(await screen.findByRole('alert')).toBeInTheDocument()
    if (page.Component === OperationsOverviewPage) expect(metric(page.region, '7天报警')).toHaveTextContent('未知')
  })
  it('设备摘要失败时仍显示已确认的历史趋势，不补零', async () => {
    mountPage(page.Component, { summary: () => response({ code: 'QA_SUMMARY_UNAVAILABLE' }, 503) })
    const chart = await screen.findByRole('img', { name: page.chart })
    expect(within(chart).getByText('2026-11-07: 5 条')).toBeInTheDocument()
    expect(metric(page.region, page.deviceLabel)).toHaveTextContent('未知')
  })
  it('范围授权被拒绝时不继续展示其他接口缓存的统计或趋势', async () => {
    mountPage(page.Component, { analytics: () => response({ code: 'QA_SCOPE_DENIED' }, 403) })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: page.region })).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: page.chart })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '近30天' }))
    expect(screen.queryByRole('region', { name: page.region })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '近30天' })).toBeDisabled()
  })
})

it('管理端接入摘要失败不遮挡值守状态，并沿用独立确认的未处理报警数', async () => {
  mountPage(DutyOverviewPage, { ops: () => response({ code: 'QA_OPS_UNAVAILABLE' }, 503) })
  await waitFor(() => expect(metric('值守状态', '设备总数')).toHaveTextContent(/^17$/))
  expect(metric('值守状态', '报警未处理')).toHaveTextContent(/^6$/)
  expect(metric('值守状态', '待发布变更')).toHaveTextContent('未知')
  expect(await screen.findByRole('img', { name: pages[0].chart })).toBeInTheDocument()
})

it('刷新中的旧设备摘要不冒充最新确认值，也不遮挡已重读的趋势', async () => {
  let reads = 0, resolve!: (value: Response) => void
  mountPage(OperationsOverviewPage, { summary: () => ++reads === 1 ? response(summary) : new Promise<Response>(done => { resolve = done }) })
  await waitFor(() => expect(metric(pages[1].region, pages[1].deviceLabel)).toHaveTextContent(/^17$/))
  await userEvent.click(screen.getByRole('button', { name: pages[1].refresh }))
  expect(metric(pages[1].region, pages[1].deviceLabel)).toHaveTextContent('未知')
  expect(await screen.findByRole('img', { name: pages[1].chart })).toBeInTheDocument()
  await act(async () => resolve(response({ ...summary, device_total: 19, online: 10 })))
  await waitFor(() => expect(metric(pages[1].region, pages[1].deviceLabel)).toHaveTextContent(/^19$/))
})
