import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from '@/App'
import { AlarmReview } from '@/components/alarm-review'
import { DeviceTable } from '@/components/data-tables'
import { api } from '@/lib/api'
import { battery, dateTime, reported, statusLabel } from '@/lib/format'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { alarm, customer, management, response } from './fixtures'

beforeEach(() => useSession.getState().clear())
describe('中文展示与页面权限', () => {
  it.each([
    { online: 'OFFLINE', customerStatus: 'OFFLINE', label: '离线', business: [{ code: 'FAULT', label: '防拆故障' }] },
    { online: 'UNKNOWN', customerStatus: 'UNKNOWN', label: '状态未确认', business: [] },
    { online: 'UNKNOWN', customerStatus: 'FIRE', label: '报警', business: [{ code: 'FIRE', label: '报警' }, { code: 'FAULT', label: '防拆故障' }] },
  ])('客户设备列表显示 $label，不展示业务明细', async ({ online, customerStatus, label, business }) => {
    useSession.getState().login(customer, 'customer')
    const row = { id: 'd1', name: '烟感', device_eui: null, model: null, address: null, connectivity: 'UNKNOWN', physical_state: 'FIRE', battery_percent: null, last_message_at: '2026-10-01T00:00:00Z',
      battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'NOT_CONFIGURED', comparator: null, threshold: null, source_scope: 'NONE', source_id: null, source_revision: 0, evaluated_at: '2026-10-30T00:00:00Z' },
      status_display: { customer_status: customerStatus, online_status: online, business_statuses: business } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ items: [row], total: 1, page: 1, size: 20 })))
    render(<DeviceTable items={(await api.devices(1)).items}/>)
    expect(screen.getAllByText(label)).toHaveLength(1)
    if (customerStatus !== 'FIRE') expect(screen.queryByText('报警')).not.toBeInTheDocument()
    expect(screen.queryByText('防拆故障')).not.toBeInTheDocument()
  })
  it('null显示未上报，真实0%保留为0%', () => {
    expect(reported(null)).toBe('未上报'); expect(reported(0)).toBe('0'); expect(battery(null)).toBe('未上报'); expect(battery(0)).toBe('0%'); expect(dateTime(null)).toBe('未上报'); expect(statusLabel('UNKNOWN')).toBe('状态未确认')
  })
  it('客户只有四个快捷入口且无权页面不会读取业务API', async () => {
    useSession.getState().login({ ...customer, permissions: [] }, 'customer'); useSession.getState().navigate('settings')
    const fetcher = vi.fn().mockResolvedValue(response({ status: 'ok', storage_mode: 'h2-dev', roles: [] })); vi.stubGlobal('fetch', fetcher)
    render(<App/>)
    expect(screen.queryByRole('button', { name: '管理设置' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: '用户端快捷导航' })).getAllByRole('button').map(button => button.textContent)).toEqual(['首页', '设备', '报警', '我的'])
    expect(fetcher.mock.calls.every(call => call[0] === '/api/health')).toBe(true)
  })
  it('首次改密仅显示改密表单，不触发业务读取', async () => {
    useSession.getState().login({ ...management, user: { ...management.user, must_change_password: true } }, 'management')
    const fetcher = vi.fn().mockResolvedValue(response({ status: 'ok', storage_mode: 'h2-dev', roles: [] })); vi.stubGlobal('fetch', fetcher)
    render(<App/>); expect(screen.getByText('更新您的初始密码')).toBeInTheDocument()
    await waitFor(() => expect(fetcher).toHaveBeenCalled())
    expect(fetcher.mock.calls.every(call => call[0] === '/api/health')).toBe(true)
  })
})
describe('报警复核', () => {
  it('结论无默认值，提交必须显式选择；成功后仍不把火警改为正常', async () => {
    useSession.getState().login(customer, 'customer')
    const fetcher = vi.fn().mockResolvedValue(response({ ...alarm, response_state: 'HANDLED', physical_state: 'FIRE' })); vi.stubGlobal('fetch', fetcher)
    const close = vi.fn(), refresh = vi.fn()
    render(<AlarmReview alarm={alarm} onClose={close} onReviewed={refresh}/>)
    expect(screen.getByLabelText('复核结论')).toHaveValue('')
    const user = userEvent.setup(); await user.selectOptions(screen.getByLabelText('复核结论'), 'FALSE_ALARM'); await user.type(screen.getByLabelText('现场情况 / 处置备注'), '已现场核查为误报'); await user.click(screen.getByRole('button', { name: '提交复核结论' }))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    expect(JSON.parse(fetcher.mock.calls[0][1].body).conclusion).toBe('FALSE_ALARM')
    expect(screen.getAllByText('火警').length).toBeGreaterThan(0); expect(close).toHaveBeenCalled()
  })
  it('409提示刷新且禁用旧版本提交，错误包含请求编号', async () => {
    useSession.getState().login(customer, 'customer')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ code: 'REVISION_CONFLICT', message: '版本冲突', request_id: 'request-409' }, 409)))
    render(<AlarmReview alarm={alarm} onClose={() => {}} onReviewed={() => {}}/>)
    fireEvent.change(screen.getByLabelText('复核结论'), { target: { value: 'TEST' } }); fireEvent.change(screen.getByLabelText('现场情况 / 处置备注'), { target: { value: '测试' } }); fireEvent.submit(screen.getByRole('button', { name: '提交复核结论' }).closest('form')!)
    expect(await screen.findByText(/request-409/)).toBeInTheDocument(); expect(screen.getByRole('button', { name: '提交复核结论' })).toBeDisabled(); expect(screen.getByRole('button', { name: '刷新最新版本' })).toBeInTheDocument()
  })
})
it('单位/位置筛选变化后立即隐藏旧范围数据，迟到响应不串入新范围', async () => {
  useSession.getState().login(management, 'management'); useSession.getState().setScope(['tenant-a'], [])
  const pendingA = Promise.withResolvers<string>(), pendingB = Promise.withResolvers<string>()
  function Probe() {
    const tenantIds = useSession(state => state.scopeTenantIds)
    const data = useResource(`probe:${tenantIds[0]}`, () => tenantIds[0] === 'tenant-a' ? pendingA.promise : pendingB.promise)
    return <div>{data.data ?? '无数据'}</div>
  }
  render(<Probe/>)
  act(() => useSession.getState().setScope(['tenant-b'], []))
  await act(async () => pendingA.resolve('单位A的私密数据'))
  expect(screen.queryByText('单位A的私密数据')).not.toBeInTheDocument()
  await act(async () => pendingB.resolve('单位B数据'))
  expect(screen.getByText('单位B数据')).toBeInTheDocument()
})

it('同一查询刷新时保留已加载内容，成功后替换为新快照', async () => {
  const pending: ((value: string) => void)[] = []
  function Probe() {
    const resource = useResource('refresh-probe', () => new Promise<string>(resolve => pending.push(resolve)))
    return <><button onClick={resource.refresh}>刷新快照</button><span>{resource.loading ? '刷新中' : '刷新完成'}</span><div>{resource.data ?? '无数据'}</div></>
  }
  render(<Probe />)
  await act(async () => pending.shift()!('旧设备快照'))
  fireEvent.click(screen.getByRole('button', { name: '刷新快照' }))
  expect(screen.getByText('刷新中')).toBeInTheDocument()
  expect(screen.getByText('旧设备快照')).toBeInTheDocument()
  await act(async () => pending.shift()!('新设备快照'))
  expect(screen.queryByText('旧设备快照')).not.toBeInTheDocument()
  expect(screen.getByText('新设备快照')).toBeInTheDocument()
  expect(screen.getByText('刷新完成')).toBeInTheDocument()
})

it('保留刷新快照不能跨单位或查询条件，旧刷新晚回也不能覆盖新范围', async () => {
  useSession.getState().login(management, 'management')
  useSession.getState().setScope(['tenant-a'], [])
  const pending: ((value: string) => void)[] = []
  function Probe({ queryKey }: { queryKey: string }) {
    const tenantIds = useSession(state => state.scopeTenantIds)
    const resource = useResource(`${tenantIds[0]}:${queryKey}`, () => new Promise<string>(resolve => pending.push(resolve)))
    return <><button onClick={resource.refresh}>刷新快照</button><div>{resource.data ?? '无数据'}</div></>
  }
  const view = render(<Probe queryKey="七天" />)
  await act(async () => pending.shift()!('单位A已有快照'))
  fireEvent.click(screen.getByRole('button', { name: '刷新快照' }))
  const lateA = pending.shift()!
  act(() => useSession.getState().setScope(['tenant-b'], []))
  expect(screen.queryByText('单位A已有快照')).not.toBeInTheDocument()
  await act(async () => lateA('单位A迟到快照'))
  expect(screen.queryByText('单位A迟到快照')).not.toBeInTheDocument()
  await act(async () => pending.shift()!('单位B七天快照'))
  view.rerender(<Probe queryKey="三十天" />)
  expect(screen.queryByText('单位B七天快照')).not.toBeInTheDocument()
  await act(async () => pending.shift()!('单位B三十天快照'))
  expect(screen.getByText('单位B三十天快照')).toBeInTheDocument()
})
