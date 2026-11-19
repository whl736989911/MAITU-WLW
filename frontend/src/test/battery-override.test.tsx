import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSession } from '@/lib/store'
import { BatteryOverride } from '@/components/battery-override'

const admin = { access_token: 'synthetic-battery-token', user: { id: 'battery-actor', username: 'battery-actor', name: '电量测试操作者', user_type: 'PLATFORM' as const, tenant_id: null, must_change_password: false }, permissions: ['parameters:read', 'parameters:write'] }
const effective = { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', configured: false, comparator: null, threshold: null, source_scope: 'NONE', source_id: null, source_revision: 0, evaluated_at: '2026-10-30T00:00:00Z' }
function view(revision = 4, hasValue = false) { return { device_id: 'device-a', tenant_id: 'tenant-a', parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', override: { has_value: hasValue, comparator: hasValue ? 'LT' : null, threshold: hasValue ? 10 : null, revision }, effective: hasValue ? { ...effective, configured: true, comparator: 'LT', threshold: 10, source_scope: 'DEVICE', source_id: 'device-a', source_revision: revision } : effective, product_version: null, consumer: 'BATTERY_OBSERVATION_ONLY' } }
const definitions = { items: [{ key: 'battery_low_threshold_percent', metric_key: 'battery_percent', name: '电量低阈值', unit: '%', min: 0, max: 100, scale: 36, comparators: ['LT', 'LTE'], allowed_scopes: ['PRODUCT_VERSION', 'DEVICE'], consumer: 'BATTERY_OBSERVATION_ONLY', effect: 'QUERY_ONLY' }] }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
function owner() { useSession.getState().login(admin, 'management') }
beforeEach(() => { useSession.getState().clear(); owner() })

describe('电量覆盖待确认操作', () => {
  it('离开页面后保留原业务体/key/revision并沿原键重试，旧owner切换销毁未知意图', async () => {
    let putCount = 0
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith('/metric-parameter-definitions')) return json(definitions)
      if (url.endsWith('/battery-low-threshold/history?page=1')) return json({ items: [], total: 0, page: 1, size: 20 })
      if (url.endsWith('/battery-low-threshold') && init?.method === 'PUT') {
        putCount++
        if (putCount === 1) return json({ message: '响应未确认' }, 503)
        const change = { id: 'change-a', action: 'SET', previous: null, new: { comparator: 'LT', threshold: 10 }, revision_before: 4, revision_after: 5, actor_id: admin.user.id, reason: '保留同一操作', created_at: '2026-10-30T00:00:01Z' }
        return json({ view: view(5, true), change })
      }
      if (url.endsWith('/battery-low-threshold')) return json(view())
      throw new Error(`unexpected request ${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    const user = userEvent.setup()
    const first = render(<BatteryOverride deviceId="device-a" tenantId="tenant-a"/>)
    await screen.findByText(/当前有效阈值配置与来源/)
    await user.selectOptions(screen.getByLabelText('电量比较方式'), 'LT')
    fireEvent.change(screen.getByLabelText('电量低阈值（%）'), { target: { value: '10.000000000000000000000000000000000001' } })
    await user.type(screen.getByLabelText('变更原因'), '保留同一操作')
    await user.click(screen.getByRole('button', { name: '核对并继续' }))

    await user.click(screen.getByRole('button', { name: '确认并提交' }))
    await screen.findByText('原操作结果未确认')
    const firstCall = fetcher.mock.calls.find(call => call[1]?.method === 'PUT')!
    const firstBody = JSON.parse(String(firstCall[1].body))
    const firstKey = new Headers(firstCall[1].headers).get('Idempotency-Key')
    expect(firstBody).toMatchObject({ expected_revision: 4, reason: '保留同一操作', battery_low_threshold: { comparator: 'LT', threshold: 10 } })
    expect(firstBody.battery_low_threshold.threshold).toBe(10)
    expect(String(firstCall[1].body)).toContain('"threshold":10.000000000000000000000000000000000001')
    first.unmount()

    const resumed = render(<BatteryOverride deviceId="device-a" tenantId="tenant-a"/>)
    await screen.findByText('原操作结果未确认')
    expect(screen.getByText(/expected revision 4/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '使用原操作内容和幂等键重试' }))
    await waitFor(() => expect(putCount).toBe(2))
    const secondCall = fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')[1]
    const secondBody = JSON.parse(String(secondCall[1].body))
    expect(secondBody).toEqual({ ...firstBody})
    expect(secondBody.expected_revision).toBe(4)
    expect(new Headers(secondCall[1].headers).get('Idempotency-Key')).toBe(firstKey)
    resumed.unmount()

    useSession.getState().clear(); owner()
    render(<BatteryOverride deviceId="device-a" tenantId="tenant-a"/>)
    await screen.findByText(/当前有效阈值配置与来源/)
    expect(screen.queryByText('原操作结果未确认')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '核对并继续' })).toBeInTheDocument()
  })
  it('旧owner的迟到成功回执不能写入新owner的覆盖操作', async () => {
    let resolvePut!: (value: Response) => void
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith('/metric-parameter-definitions')) return json(definitions)
      if (url.endsWith('/battery-low-threshold/history?page=1')) return json({ items: [], total: 0, page: 1, size: 20 })
      if (url.endsWith('/battery-low-threshold') && init?.method === 'PUT') return new Promise((resolve: (value: Response) => void) => { resolvePut = resolve })
      if (url.endsWith('/battery-low-threshold')) return json(view())
      throw new Error(`unexpected request ${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    const user = userEvent.setup(), first = render(<BatteryOverride deviceId="device-a" tenantId="tenant-a"/>)
    await screen.findByText(/当前有效阈值配置与来源/)
    await user.selectOptions(screen.getByLabelText('电量比较方式'), 'LT')
    fireEvent.change(screen.getByLabelText('电量低阈值（%）'), { target: { value: '10' } })
    await user.type(screen.getByLabelText('变更原因'), '旧owner迟到响应')
    await user.click(screen.getByRole('button', { name: '核对并继续' }))

    await user.click(screen.getByRole('button', { name: '确认并提交' }))
    await waitFor(() => expect(fetcher.mock.calls.some(call => call[1]?.method === 'PUT')).toBe(true))
    first.unmount()
    useSession.getState().clear(); owner()
    resolvePut(json({ view: view(5, true), change: { id: 'late', action: 'SET', previous: null, new: { comparator: 'LT', threshold: 10 }, revision_before: 4, revision_after: 5, actor_id: admin.user.id, reason: '旧owner迟到响应', created_at: '2026-10-30T00:00:01Z' } }))
    render(<BatteryOverride deviceId="device-a" tenantId="tenant-a"/>)
    await screen.findByText(/当前有效阈值配置与来源/)
    expect(screen.queryByText('原操作结果未确认')).not.toBeInTheDocument()
    expect(screen.queryByText(/旧owner迟到响应/)).not.toBeInTheDocument()
  })
})
