import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSession } from '@/lib/store'
import { ParameterMonitoringPage } from '@/pages/parameter-monitoring'
import { management, response } from './fixtures'

const sample = { event_id: 'telemetry-1', tenant_id: 'tenant-a', device_id: 'device-1', device_name: '海康温湿度探测器', address: '三层机房', observed_at: '2026-11-01T09:00:00Z', received_at: '2026-11-01T09:00:02Z', recorded_at: '2026-11-01T09:00:03Z', readings: [{ metric: 'hik_400033', label: '海康温度', value: '21.5', unit: null, unit_status: 'UNKNOWN' }, { metric: 'humidity', label: '相对湿度', value: '46', unit: '%', unit_status: 'DECLARED' }] }
const page = { items: [sample], total: 1, page: 1, size: 20 }
type Reply = () => Response | Promise<Response>
function mountMonitoring(overrides: { current?: Reply; history?: Reply; metrics?: Reply } = {}) {
  useSession.getState().login({ ...management, permissions: ['device:read'] }, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/duty/telemetry/metrics')) return overrides.metrics?.() ?? response([{ key: 'battery_percent', label: '电量' }, { key: 'hik_400033', label: '厂商扩展指标 400033' }, { key: 'humidity', label: '湿度' }])
    if (url.includes('/duty/parameters/current?')) return overrides.current?.() ?? response(page)
    if (url.includes('/duty/parameters?')) return overrides.history?.() ?? response(page)
    throw new Error(`Unexpected telemetry request: ${url}`)
  }))
  render(<ParameterMonitoringPage/>)
}
beforeEach(() => { useSession.getState().clear(); vi.restoreAllMocks() })

describe('动态设备监测指标', () => {
  it('未知单位不按字段名称猜温标或百分比，已声明单位仍显示', async () => {
    mountMonitoring()
    const unknown = await screen.findByRole('cell', { name: '海康温度 21.5 单位未知' })
    expect(unknown).not.toHaveTextContent(/%|℃|°C/)
    expect(screen.getByRole('cell', { name: '相对湿度 46 %' })).toBeInTheDocument()
  })
  it.each(['pending', 'failed'])('历史查询%s时切回最新读数仍可使用', async state => {
    mountMonitoring({ history: () => state === 'pending' ? new Promise<Response>(() => {}) : response({ code: 'QA_HISTORY_UNAVAILABLE' }, 503) })
    expect(await screen.findByRole('cell', { name: '海康温度 21.5 单位未知' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: '历史记录' }))
    if (state === 'failed') expect(await screen.findByRole('alert')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: '最新读数' }))
    expect(await screen.findByRole('cell', { name: '海康温度 21.5 单位未知' })).toBeInTheDocument()
  })

  it('当前值读取失败时仍可切换到历史样本', async () => {
    mountMonitoring({ current: () => response({ code: 'QA_CURRENT_UNAVAILABLE' }, 503) })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: '历史记录' }))
    const history = await screen.findByRole('heading', { name: '历史样本筛选结果' })
    const panel = within(history.closest('[id]')!)
    expect(await panel.findByRole('cell', { name: /海康温度 21.5 单位未知/ })).toBeInTheDocument()
  })

  it('手动刷新重新读取当前值，而不是继续显示旧快照', async () => {
    let value = '21.5'
    mountMonitoring({ current: () => response({ ...page, items: [{ ...sample, readings: [{ ...sample.readings[0], value }] }] }) })
    expect(await screen.findByRole('cell', { name: '海康温度 21.5 单位未知' })).toBeInTheDocument()
    value = '22.75'
    await userEvent.click(screen.getByRole('button', { name: '刷新监测数据' }))
    expect(await screen.findByRole('cell', { name: '海康温度 22.75 单位未知' })).toBeInTheDocument()
    expect(screen.queryByRole('cell', { name: '海康温度 21.5 单位未知' })).not.toBeInTheDocument()
  })

  it('指标目录失败时保留来源指标编码和已返回读数', async () => {
    mountMonitoring({ metrics: () => response({ code: 'QA_METRICS_UNAVAILABLE' }, 503) })
    expect(await screen.findByRole('cell', { name: '海康温度 21.5 单位未知' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'hik_400033' })).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
