import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { DeviceTelemetry } from '@/components/device-telemetry'
import { publishObjectChange } from '@/lib/object-events'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

const telemetry = (value: string) => ({ items: [{ event_id: 'sample-1', tenant_id: 'tenant-a', device_id: 'device-a', device_name: '烟感', address: null, observed_at: null, received_at: '2026-11-17T01:02:00Z', recorded_at: '2026-11-17T01:02:01Z', readings: [{ metric: 'temperature', label: '温度', value, unit: null, unit_status: 'UNKNOWN', observed_at: null, received_at: null, recorded_at: '2026-11-17T01:02:01Z' }] }], total: 1, page: 1, size: 1 })

beforeEach(() => {
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: ['device:read'] }, 'management')
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => response(telemetry('21.5'))))
})

describe('本设备真实监测值刷新', () => {
  it('设备对象变化触发有权限的HTTP重读，不伪造读数来源时间', async () => {
    const fetcher = vi.mocked(fetch)
    let value = '21.5'
    fetcher.mockImplementation(async () => response(telemetry(value)))
    render(<DeviceTelemetry deviceId="device-a" tenantId="tenant-a"/>)
    const cells = within(await screen.findByRole('row', { name: /温度.*21\.5.*单位未知/ })).getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('温度')
    expect(cells[1]).toHaveTextContent('21.5')
    expect(cells[1]).toHaveTextContent('单位未知')
    expect(cells[2]).toHaveTextContent(/^未提供$/)
    expect(cells[2]).not.toHaveTextContent('2026')
    value = '22.75'
    const before = fetcher.mock.calls.length
    act(() => publishObjectChange('devices'))
    const updated = within(await screen.findByRole('row', { name: /温度.*22\.75.*单位未知/ })).getAllByRole('cell')
    expect(updated[1]).toHaveTextContent('22.75')
    expect(updated[2]).toHaveTextContent(/^未提供$/)
    await waitFor(() => expect(fetcher.mock.calls.length).toBeGreaterThan(before))
  })
})
