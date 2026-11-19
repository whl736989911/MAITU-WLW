import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useSession } from '@/lib/store'
import { management } from './fixtures'
import { legacyApi } from '@/lib/legacy-api'
import type { LegacyMessageDetail, LegacyMessagePage } from '@/lib/legacy-api'
import { LegacyIngestPage } from '@/pages/legacy/ingest'

vi.mock('@/lib/legacy-api', () => ({ legacyApi: { messages: vi.fn(), message: vi.fn(), differences: vi.fn() } }))
const row = { id: 'message-1', tenant_id: 'tenant-a', ingress: 'aep', vendor_platform: 'aep_turbo', vendor_device_id: 'device-1', device_id: null, payload_raw: '{"temperature":21}', received_at: '2026-10-30T06:44:16Z', state: 'FAILED' as const, failure_code: 'UNKNOWN_DEVICE', events: [] }
const detail: LegacyMessageDetail = { summary: row, payload_raw: '{"temperature":21}', events: [{ id: 'event-1', kind: 'HEARTBEAT', method: 'thing.event.heartbeat', params_json: { battery: 0 }, observed_at: '2026-10-30T06:44:16Z' }] }
const difference = { id: 'diff-1', run_id: 'run-1', kind: 'DUPLICATE_MODEL', legacy_id: 'legacy-1', new_id: null, detail: '型号重复：TBS-N101', created_at: '2026-10-30T06:44:16Z' }
const page: LegacyMessagePage = { items: [row, { ...row, id: 'message-2', vendor_platform: 'lorawan_turbo', state: 'NORMALIZED', failure_code: null }], total: 2, page: 1, size: 100 }
const mockApi = vi.mocked(legacyApi)
beforeEach(() => {
  vi.clearAllMocks(); useSession.getState().clear(); useSession.getState().login(management, 'management'); useSession.getState().setScope(['tenant-a'], [], true)
  mockApi.messages.mockImplementation(async (pageNumber = page.page, size = page.size, state) => {
    const items = state ? page.items.filter(item => item.state === state) : page.items
    return { items, total: items.length, page: pageNumber, size }
  })
  mockApi.message.mockResolvedValue(detail); mockApi.differences.mockResolvedValue({ items: [difference], total: 1, page: 1, size: 100 })
})

describe('接入镜像', () => {
  it('显示状态统计、报文列表并按状态筛选', async () => {
    render(<LegacyIngestPage />)
    const devices = await screen.findAllByText('device-1')
    expect(devices).toHaveLength(2)
    expect(screen.getAllByText('已标准化')[0].closest('[data-slot="card"]')).toHaveTextContent('1')
    expect(screen.getAllByText('重复报文')[0].closest('[data-slot="card"]')).toHaveTextContent('0')
    expect(screen.getAllByText('待解码')[0].closest('[data-slot="card"]')).toHaveTextContent('0')
    expect(screen.getByText(/未识别到设备，报文已记录/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('按状态筛选'), { target: { value: 'FAILED' } })
    await waitFor(() => expect(screen.getAllByText('device-1')).toHaveLength(1))
  })
  it('展开详情显示原始报文和事件', async () => {
    render(<LegacyIngestPage />)
    fireEvent.click((await screen.findAllByText('device-1'))[0])
    expect(await screen.findByText('未识别到设备（UNKNOWN_DEVICE）：报文已记录，但未关联设备。')).toBeInTheDocument()
    fireEvent.click(screen.getByText('原始报文'))
    expect(screen.getByText('{"temperature":21}')).toBeInTheDocument()
    expect(screen.getByText('HEARTBEAT · thing.event.heartbeat')).toBeInTheDocument()
  })
  it('差异页签呈现批次对账记录', async () => {
    render(<LegacyIngestPage />)
    fireEvent.click(screen.getByRole('button', { name: '对账差异' }))
    expect(await screen.findByText('型号重复：TBS-N101')).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getByText('run-1')).toBeInTheDocument()
  })
})
