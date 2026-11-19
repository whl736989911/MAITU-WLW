import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { legacyDevicesApi } from '@/lib/legacy-devices'
import { LegacyDevicesPage } from '@/pages/legacy/devices'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { management } from './fixtures'

vi.mock('@/lib/legacy-devices', () => ({ legacyDevicesApi: { list: vi.fn() } }))
const device = { id: 'device-1', name: '试点烟感', device_eui: 'eui-311-01', model: 'TBS-N101', tenant_id: 'tenant-311', place_name: '一号楼', product_version_id: 'version-1', legacy_product_id: 'legacy-product-1', physical_state: 'NORMAL', last_message_at: '2026-10-30T06:44:16Z' }
const mockList = vi.mocked(legacyDevicesApi.list)
const result = (items = [device], total = items.length, page = 1) => ({ items, total, page, size: 20 })

beforeEach(() => {
  vi.clearAllMocks()
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: ['legacy-ingest:read'] }, 'management')
  useSession.getState().setScope(['tenant-311'], [])
  vi.spyOn(api, 'scopeTree').mockResolvedValue([{ id: 'tenant-311', name: '归属单位', places: [] }])
  mockList.mockResolvedValue(result())
})

describe('试点设备列表', () => {
  it('渲染设备数据及状态标记', async () => {
    render(<LegacyDevicesPage/> )
    expect(await screen.findByText('试点烟感')).toBeInTheDocument()
    expect(screen.getByText('eui-311-01')).toBeInTheDocument()
    expect(screen.getByText('TBS-N101')).toBeInTheDocument()
    expect(screen.getByText('一号楼')).toBeInTheDocument()
    expect(screen.getByText('legacy-product-1')).toBeInTheDocument()
    expect(screen.getByText('正常')).toBeInTheDocument()
  })

  it('applies a new search to the displayed device records', async () => {
    mockList.mockImplementation(async (_page, _size, keyword) => result(keyword ? [{ ...device, id: 'matched', name: '匹配烟感' }] : [device]))
    render(<LegacyDevicesPage/>)
    await screen.findByText('试点烟感')
    fireEvent.change(screen.getByLabelText('搜索设备名称或 EUI'), { target: { value: ' eui-311 ' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    expect(await screen.findByText('匹配烟感')).toBeInTheDocument()
    expect(screen.queryByText('试点烟感')).not.toBeInTheDocument()
  })


  it('读取失败显示错误态并可重试', async () => {
    mockList.mockRejectedValueOnce(new Error('服务不可用')).mockResolvedValue(result())
    render(<LegacyDevicesPage/> )
    expect(await screen.findByRole('alert')).toHaveTextContent('服务不可用')
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('试点烟感')).toBeInTheDocument()
  })

  it('moves from the first twenty records to the remaining device', async () => {
    mockList.mockImplementation(async page => ({
      items: page === 1 ? Array.from({ length: 20 }, (_, index) => ({ ...device, id: `first-${index}`, name: `首批烟感-${index}` })) : [{ ...device, id: 'last', name: '第二页烟感' }],
      total: 21, page: page ?? 1, size: 20,
    }))
    render(<LegacyDevicesPage/>)
    await screen.findByText('首批烟感-0')
    fireEvent.click(screen.getByRole('button', { name: 'Next Page' }))
    expect(await screen.findByText('第二页烟感')).toBeInTheDocument()
    expect(screen.queryByText('首批烟感-0')).not.toBeInTheDocument()
  })
})
