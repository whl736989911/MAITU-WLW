import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { DevicesPage } from '@/pages/devices'
import { useSession } from '@/lib/store'
import { customer, response } from './fixtures'

beforeEach(() => {
  useSession.getState().clear()
  useSession.getState().login({ ...customer, permissions: ['device:read'] }, 'customer')
})

it('submits the chosen legacy search field, switches systems and resets without requiring write access', async () => {
  const list = vi.fn().mockImplementation((url: string) => {
    if (!url.startsWith('/api/customer/devices?')) throw new Error(`Unexpected endpoint: ${url}`)
    const query = new URLSearchParams(url.split('?')[1])
    return response({ page: Number(query.get('page')), size: Number(query.get('size') || 20), items: [], total: 0 })
  })
  vi.stubGlobal('fetch', list)
  render(<DevicesPage/>)
  await screen.findByText('共 0 条')
  expect(screen.getByRole('combobox', { name: '搜索字段' })).toHaveValue('eui')
  fireEvent.change(screen.getByRole('combobox', { name: '搜索字段' }), { target: { value: 'model' } })
  fireEvent.change(screen.getByRole('textbox', { name: '搜索设备' }), { target: { value: '  MODEL-12  ' } })
  expect(list).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByRole('button', { name: '搜索' }))
  await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.stringContaining('field=model'), expect.anything()))
  expect(list.mock.lastCall?.[0]).toContain('keyword=MODEL-12')
  fireEvent.click(screen.getByRole('button', { name: '烟温火灾探测报警系统' }))
  await waitFor(() => expect(list.mock.lastCall?.[0]).toContain('system_type='))
  fireEvent.click(screen.getByRole('button', { name: '按设备状态筛选' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: '警报' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '故障' }))
  fireEvent.click(screen.getByRole('button', { name: '筛选' }))
  await waitFor(() => expect(new URLSearchParams(list.mock.lastCall?.[0].split('?')[1]).get('customer_status')).toBe('FIRE,FAULT'))
  fireEvent.click(screen.getByRole('button', { name: '重置' }))
  await waitFor(() => expect(list.mock.lastCall?.[0]).not.toContain('keyword=MODEL-12'))
  expect(list.mock.lastCall?.[0]).not.toContain('customer_status=')
  expect(screen.getByRole('button', { name: '批量删除' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '添加无线终端' })).toBeDisabled()
})
