import { render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { useSession } from '@/lib/store'
import { alarm, customer, response } from '@/test/fixtures'
import { CustomerHome } from './home'

it('首页明确区分缺失来源时间与已处理状态，不从平台登记时间推断恢复', async () => {
  useSession.getState().login(customer, 'customer')
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/customer/summary') return response({ unhandled_alarms: 0, unknown_connectivity: 3 })
    if (url === '/api/customer/alarms?page=1&size=6') return response({ items: [{ ...alarm, physical_state: 'FIRE', response_state: 'HANDLED', occurred_at: null, recorded_at: '2026-11-20T17:30:00Z', time_quality: 'SOURCE_TIME_MISSING' }], total: 1, page: 1, size: 6 })
    throw new Error(`Unexpected request: ${url}`)
  }))
  render(<CustomerHome/>)
  const row = within(await screen.findByRole('button', { name: /测试烟感/ }))
  expect(row.getByText(/来源发生时间未知/)).toBeInTheDocument()
  expect(row.getByText('火警')).toBeInTheDocument()
  expect(row.getByText('已处理')).toBeInTheDocument()
})

it('无报警权限时仍读取汇总，但不请求报警列表或提供报警入口', async () => {
  useSession.getState().login({ ...customer, permissions: ['summary:read', 'device:read'] }, 'customer')
  const fetcher = vi.fn(async (url: string) => {
    if (url === '/api/customer/summary') return response({ unknown_connectivity: 3 })
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<CustomerHome/>)
  expect(await screen.findByText('3', { exact: true })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '全部报警' })).not.toBeInTheDocument()
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual(['/api/customer/summary'])
})
