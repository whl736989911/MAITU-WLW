import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { api } from '@/lib/api'
import { DataTransferDownload } from '@/components/data-transfer-download'
import { DevicesPage } from '@/pages/devices'
import { DistributionPage } from '@/pages/distribution'
import { PlacesPage } from '@/pages/places'
import type { Device } from '@/lib/types'
import { management } from './fixtures'
import { useSession } from '@/lib/store'

const basePermissions = ['device:read', 'units:read', 'imports:read', 'imports:write', 'exports:read', 'exports:write']
function enterWorkspace(permissions = basePermissions) {
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions }, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
}
const device = (id: string): Device => ({
  id, tenant_id: 'tenant-a', name: `设备-${id}`, device_eui: `eui-${id}`, model: 'model-a', address: '地址', place_id: 'place-a',
  connectivity: 'UNACTIVATED', physical_state: 'UNKNOWN', last_message_at: null, battery_percent: null,
  product_id: null, product_version_id: null, product_name: null, version_number: null, revision: 0,
  integration_state: 'PENDING_INTEGRATION',
  battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'NOT_CONFIGURED', comparator: null, threshold: null, source_scope: 'NONE', source_id: null, source_revision: 0, evaluated_at: '2026-11-01T00:00:00Z' },
})
const paged = (items: unknown[], page: number, size: number, total: number) => ({ items, page, size, total })

beforeEach(() => {
  vi.restoreAllMocks()
  enterWorkspace()
})

describe('frontend core regression behavior', () => {

  it('starts a new device search-field query at page one', async () => {
    const devices = vi.spyOn(api, 'devices').mockImplementation(async (page: number) => paged([device(`p${page}`)], page, 20, 40) as never)
    vi.spyOn(api, 'tenants').mockResolvedValue({ items: [], total: 0, page: 1, size: 20 } as never)
    vi.spyOn(api, 'places').mockResolvedValue([])
    render(<DevicesPage/>)
    await screen.findByText('设备-p1')
    fireEvent.click(screen.getByRole('button', { name: 'Next Page' }))
    await screen.findByText('设备-p2')
    fireEvent.change(screen.getByLabelText('搜索字段'), { target: { value: 'eui' } })
    await waitFor(() => expect(devices.mock.calls.at(-1)?.[0]).toBe(1))
    expect(screen.getByText('设备-p1')).toBeInTheDocument()
  })

  it('rejects blank and out-of-range distribution coordinates before writing', async () => {
    const selectedDevice = device('d1')
    const setLocation = vi.spyOn(api, 'setDeviceLocation').mockResolvedValue(selectedDevice as never)
    vi.spyOn(api, 'deviceDistribution').mockResolvedValue({ points: [], with_coordinates: 0, without_coordinates: 1, semantics: 'test' } as never)
    vi.spyOn(api, 'devices').mockResolvedValue(paged([selectedDevice], 1, 100, 1) as never)
    enterWorkspace([...basePermissions, 'device:write'])
    render(<DistributionPage/>)
    fireEvent.click(screen.getByRole('button', { name: '维护坐标' }))
    await screen.findByRole('option', { name: /设备-d1/ })
    fireEvent.change(screen.getByLabelText('设备'), { target: { value: 'd1' } })

    fireEvent.click(screen.getByRole('button', { name: '保存坐标' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(setLocation).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('纬度（-90..90）'), { target: { value: '91' } })
    fireEvent.change(screen.getByLabelText('经度（-180..180）'), { target: { value: '181' } })
    fireEvent.click(screen.getByRole('button', { name: '保存坐标' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('纬度（-90..90）'), { target: { value: '90' } })
    fireEvent.change(screen.getByLabelText('经度（-180..180）'), { target: { value: '180' } })
    fireEvent.click(screen.getByRole('button', { name: '保存坐标' }))
    await waitFor(() => expect(setLocation).toHaveBeenCalledTimes(1))
    expect(setLocation.mock.calls[0][1]).toMatchObject({ latitude: 90, longitude: 180 })

  })
  it('allows navigating beyond the first hundred devices at a place', async () => {
    const devices = vi.spyOn(api, 'devices').mockImplementation(async (page: number) => paged(Array.from({ length: page === 1 ? 100 : 25 }, (_, index) => device(`p${page}-${index}`)), page, 100, 125) as never)
    vi.spyOn(api, 'places').mockResolvedValue([{ id: 'place-a', tenant_id: 'tenant-a', tenant_name: '单位A', name: '位置A', parent_id: null, legacy_place_id: null, device_count: 125, level: 1, revision: 0 }])
    render(<PlacesPage/>)
    await screen.findByText('位置A')
    fireEvent.click(screen.getByText('位置A'))
    await screen.findByText('设备-p1-0')
    fireEvent.click(screen.getByRole('button', { name: 'Next Page' }))
    await screen.findByText('设备-p2-0')
    expect(devices.mock.calls.at(-1)?.[0]).toBe(2)
  })

  it('shows download failures and allows an explicit retry without reporting a saved file', async () => {
    const download = vi.fn().mockRejectedValue(new Error('download failed'))
    render(<DataTransferDownload load={download}>下载数据文件</DataTransferDownload>)
    fireEvent.click(screen.getByRole('button', { name: '下载数据文件' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('download failed')
    fireEvent.click(screen.getByRole('button', { name: '下载数据文件' }))
    await waitFor(() => expect(download).toHaveBeenCalledTimes(2))
  })
})
