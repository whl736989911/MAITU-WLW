import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CustomerPlaceManager } from '@/components/customer-place-manager'
import { customerAssetsApi } from '@/lib/customer-assets'
import { useSession } from '@/lib/store'
import type { PlaceNode } from '@/lib/types'
import { customer, response } from './fixtures'

const places: PlaceNode[] = [
  { id: 'west', tenant_id: 'tenant-a', tenant_name: '本单位', name: '西楼', parent_id: null, legacy_place_id: null, device_count: 0, level: 1, revision: 1 },
  { id: 'west-205', tenant_id: 'tenant-a', tenant_name: '本单位', name: '205室', parent_id: 'west', legacy_place_id: null, device_count: 3, level: 2, revision: 2 },
  { id: 'east', tenant_id: 'tenant-a', tenant_name: '本单位', name: '东楼', parent_id: null, legacy_place_id: null, device_count: 0, level: 1, revision: 3 },
  { id: 'east-205', tenant_id: 'tenant-a', tenant_name: '本单位', name: '205室', parent_id: 'east', legacy_place_id: null, device_count: 0, level: 2, revision: 4 },
]
beforeEach(() => {
  useSession.getState().clear(); localStorage.clear(); sessionStorage.clear()
  useSession.getState().login({ ...customer, permissions: ['units:read', 'units:write'] }, 'customer')
  vi.spyOn(customerAssetsApi, 'places').mockResolvedValue(places)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (new URL(url, 'http://local').pathname.endsWith('/data-transfer/kinds')) return response({ kinds: [], protected_storage_ready: true })
    throw new Error(`Unexpected location request: ${url}`)
  }))
})
async function openEditor(path: string) {
  await userEvent.click(await screen.findByRole('button', { name: `位置操作 · ${path}` }))
  const actions = within(await screen.findByRole('group', { name: `位置操作 · ${path}` }))
  await userEvent.click(actions.getByRole('button', { name: /编\s*辑/ }))
  return within(await screen.findByRole('dialog'))
}
describe('本单位位置树与同名节点隔离', () => {
  it('按完整路径搜索下级时保留其真实上级，排除另一楼栋的同名位置', async () => {
    render(<CustomerPlaceManager/>)
    await screen.findByRole('button', { name: '位置操作 · 西楼 / 205室' })
    await userEvent.type(screen.getByRole('textbox', { name: '搜索位置名称或完整路径' }), '西楼 / 205室{Enter}')
    expect(await screen.findByText('西楼', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.getAllByText('205室', { selector: 'strong' })).toHaveLength(1)
    expect(screen.queryByText('东楼', { selector: 'strong' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '位置操作 · 东楼 / 205室' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '位置操作 · 西楼 / 205室' })).toBeInTheDocument()
  })
  it('关闭西楼草稿后改名东楼同名节点，仍保留东楼实际父位置', async () => {
    const previousFetch = globalThis.fetch
    const writes: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
      if (url === '/api/customer/places/east-205' && options?.method === 'PUT') {
        writes.push(JSON.parse(String(options.body)))
        return response({ ...places[3], name: '东楼会议室', revision: 5 })
      }
      return previousFetch(url, options)
    }))
    render(<CustomerPlaceManager/>)
    const west = await openEditor('西楼 / 205室')
    expect(west.queryByRole('combobox', { name: '上级位置' })).not.toBeInTheDocument()
    await userEvent.clear(west.getByLabelText('位置名称'))
    await userEvent.type(west.getByLabelText('位置名称'), '尚未保存的西楼草稿')
    await userEvent.click(west.getByRole('button', { name: /取\s*消/ }))
    const east = await openEditor('东楼 / 205室')
    expect(east.getByLabelText('位置名称')).toHaveValue('205室')
    expect(east.queryByRole('combobox', { name: '上级位置' })).not.toBeInTheDocument()
    await userEvent.clear(east.getByLabelText('位置名称'))
    await userEvent.type(east.getByLabelText('位置名称'), '东楼会议室')
    await userEvent.click(east.getByRole('button', { name: '保存名称' }))
    expect(writes).toEqual([{ name: '东楼会议室', parent_place_id: 'east', expected_revision: 4 }])
    await userEvent.click(await screen.findByRole('button', { name: '位置操作 · 东楼 / 205室' }))
    await userEvent.click(within(screen.getByRole('group', { name: '位置操作 · 东楼 / 205室' })).getByRole('button', { name: '移动整枝' }))
    expect(await screen.findByRole('combobox', { name: '目标上级位置' })).toBeInTheDocument()
  })
})
