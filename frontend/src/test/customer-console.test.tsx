import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import App from '@/App'
import { useSession } from '@/lib/store'
import { customer, response } from './fixtures'

beforeEach(() => useSession.getState().clear())

it('可切换设备地图与点位图，未配置时不显示虚构点位', async () => {
  history.replaceState(null, '', '/customer')
  useSession.getState().login({ ...customer, permissions: ['summary:read'] }, 'customer')
  const requests = vi.fn((url: string) => {
    if (url === '/api/health') return response({ status: 'UP', storage_mode: 'h2-dev', roles: [] })
    if (url === '/api/customer/summary') return response({ device_total: 3, offline: 1, unactivated: 0 })
    throw new Error(`Unexpected endpoint: ${url}`)
  })
  vi.stubGlobal('fetch', requests)
  render(<App/>)
  const map = await screen.findByRole('region', { name: '设备地图' })
  expect(map).toBeVisible()
  await waitFor(() => expect(screen.getByText('3')).toBeVisible())
  fireEvent.click(screen.getByRole('button', { name: '点位图' }))
  expect(screen.getByText('暂无点位图')).toBeVisible()
  expect(screen.queryByRole('region', { name: '设备地图' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '设备', pressed: false }))
  expect(screen.getByRole('region', { name: '设备地图' })).toBeVisible()
  expect(requests.mock.calls.some(([url]) => url.startsWith('/api/management/'))).toBe(false)
})
