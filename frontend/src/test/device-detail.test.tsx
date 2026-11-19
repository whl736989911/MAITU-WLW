import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DeviceDetail as DeviceDetailComponent } from '@/components/device-detail'
import type { ComponentProps } from 'react'
import { deviceDetailApi, readDeviceDetail, readDeviceAlarms } from '@/lib/device-detail'
import { emptyAlarmFilters, expectedMetricDefinitions } from '@/lib/alarm-workbench'
import { useSession } from '@/lib/store'
import { management, customer, response } from './fixtures'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { queryDeviceDetailFaultEvents } from '@/lib/duty-api'
const time = '2026-10-25T04:20:00.123456Z', epochPolicy = { key: 'offline_threshold_minutes', value: 5, unit: '分钟', source_scope: 'GLOBAL', source_id: null, source_revision: 0, evaluated_at: time }
const device = { id: 'd1', tenant_id: 'tenant-a', name: '当前详情名称', device_eui: 'synthetic-eui', model: '保存旧型号', address: '当前安装地址', connectivity: 'UNACTIVATED', physical_state: 'UNKNOWN', last_message_at: null, battery_percent: null, product_id: 'product-one', product_version_id: 'version-one', product_name: '保存旧产品名', version_number: 1, revision: 0, integration_state: 'PENDING_INTEGRATION', tenant_name: null, place_id: null, place_name: null, system_type: null, device_category: null, offline_policy: epochPolicy, battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'NOT_CONFIGURED', comparator: null, threshold: null, source_scope: 'NONE', source_id: null, source_revision: 0, evaluated_at: time } }
const detail = (change = {}) => ({ as_of: time, timezone: 'Asia/Shanghai', consumer: 'CONNECTIVITY_AND_OFFLINE_EVENTS', device: { ...device, status_display: { online_status: 'UNACTIVATED', customer_status: 'UNACTIVATED', business_statuses: [] }, ...change } })
const alarm = { id: 'alarm-one', tenant_id: 'tenant-a', device_id: 'd1', device_name: '报警固有旧名称', address: '报警固有旧地址', event_type: 'FIRE', occurred_at: '2020-01-01T00:00:00.123456Z', physical_state: 'FIRE', response_state: 'UNHANDLED', conclusion: null, revision: 0 }
const workbench = (change = {}) => ({ device_id: 'd1', items: [alarm], total: 1, page: 1, size: 3, as_of: '2026-10-25T04:20:01Z', timezone: 'Asia/Shanghai', filters: { ...emptyAlarmFilters }, metrics: { alarm_total: 1, unhandled: 1, handled: 0, unrecovered: 1, recovered: 0, true_fire: 0 }, metric_definitions: expectedMetricDefinitions, ...change })
const raw = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
function DeviceDetail(props: ComponentProps<typeof DeviceDetailComponent>) { return <ConfigProvider locale={zhCN}><DeviceDetailComponent {...props} tenantId={props.tenantId ?? 'tenant-a'}/></ConfigProvider> }
function login(permissions = ['device:read', 'alarm:read'], client = false) { useSession.getState().login({ ...(client ? customer : management), permissions }, client ? 'customer' : 'management'); if (!client) useSession.getState().setScope(['tenant-a'], [], true) }
function mock(override?: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined, permissions = ['device:read', 'alarm:read'], client = false) {
  login(permissions, client)
  const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    const custom = override?.(url, init); if (custom !== undefined) return custom
    
    if (/\/devices\/d[12]$/.test(url)) return raw(detail({ id: url.endsWith('d2') ? 'd2' : 'd1' }))
    if (/\/devices\/d[12]\/alarms\?/.test(url)) return response(workbench())
    throw new Error(`Unexpected device-detail endpoint ${url}`)
  }); vi.stubGlobal('fetch', fetcher); return fetcher
}
const detailCalls = (fetcher: ReturnType<typeof vi.fn>) => fetcher.mock.calls.filter(call => /\/devices\/d[12]$/.test(call[0]))
const alarmCalls = (fetcher: ReturnType<typeof vi.fn>) => fetcher.mock.calls.filter(call => /\/devices\/d[12]\/alarms\?/.test(call[0]))
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
async function relatedAlarmButton() {
  await userEvent.setup().click(await screen.findByRole('tab', { name: '关联报警' }))
  return screen.findByRole('button', { name: '读取关联报警' })
}
beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })

describe('P8新lazy lossless设备/关联报警wire', () => {
  it('四字段/深层safe whitelist、requested id/offset/asof同策略瞬间与binding，不从最新产品取名', () => { const result = readDeviceDetail(JSON.stringify({ ...detail({ schema: { password: 'private-schema' }, actor_id: 'private-actor', offline_policy: { ...epochPolicy, reason: 'private-reason' } }), extra: 'private-root' }), 'd1', 'tenant-a'); expect(Object.keys(result)).toEqual(['as_of', 'timezone', 'consumer', 'device']); expect(JSON.stringify(result)).not.toContain('private'); expect(result.device.product_name).toBe('保存旧产品名'); expect(result.device.revision).toBe(0); expect(readDeviceDetail(JSON.stringify(detail({ offline_policy: { ...epochPolicy, evaluated_at: '2026-10-25T12:20:00.123456+08:00' } })), 'd1', 'tenant-a').device.offline_policy.value).toBe(5) })
  it('detail revision/version/offline value/revision按原始整数拒绝near-int/指数/小数/string/bool/unsafe', () => { const source = JSON.stringify(detail()); for (const [field, original] of [['revision', '0'], ['version_number', '1'], ['value', '5'], ['source_revision', '0']]) for (const invalid of ['0.9999999999999999999999', '1e0', '1.0', '"1"', 'true', '9007199254740993']) expect(() => readDeviceDetail(source.replace(`"${field}":${original}`, `"${field}":${invalid}`), 'd1', 'tenant-a')).toThrow() })
  it('JSON重复键（含escaped/深层/未知字段）与nonfinite在旧JSON.parse前拒绝', () => { const source = JSON.stringify(detail()); for (const invalid of [source.replace('"revision":0', '"revision":0,"revi\\u0073ion":1'), source.replace('"value":5', '"value":5,"value":6'), source.replace('"battery_percent":null', '"battery_percent":1e309'), source.replace('"device":{', '"unread":{"x":1,"x":2},"device":{')]) expect(() => readDeviceDetail(invalid, 'd1', 'tenant-a')).toThrow() })
  it('battery null/真实0/极小数/BigDecimal保持原词法，不将100.000…001舍100而接受', () => { const source = JSON.stringify(detail()); for (const token of ['0', '0.10000000000000000000000001', '1e-400', '100.0000000000000000000000']) expect(readDeviceDetail(source.replace('"battery_percent":null', `"battery_percent":${token}`), 'd1', 'tenant-a').device.battery_percent_json).toBe(token); expect(readDeviceDetail(source, 'd1', 'tenant-a').device.battery_percent_json).toBe('null'); for (const token of ['100.0000000000000000000001', '-1e-400', '"0"', 'true']) expect(() => readDeviceDetail(source.replace('"battery_percent":null', `"battery_percent":${token}`), 'd1', 'tenant-a')).toThrow() })
  it('扁平10字段关联保持旧9字段语义、无nested WB，expected device和每row唯一精确匹配', () => { expect(readDeviceAlarms(JSON.stringify(workbench()), 'd1', 'tenant-a', emptyAlarmFilters, 1).device_id).toBe('d1'); for (const change of [{ device_id: 'd2' }, { items: [{ ...alarm, device_id: 'd2' }] }, { items: [{ ...alarm, tenant_id: 'tenant-b' }] }, { items: [alarm, alarm] }, { total: 0 }, { timezone: 'UTC' }, { metrics: { ...workbench().metrics, handled: 1 } }, { filters: { ...emptyAlarmFilters, keyword: 'other' } }]) expect(() => readDeviceAlarms(JSON.stringify(workbench(change)), 'd1', 'tenant-a', emptyAlarmFilters, 1)).toThrow() })
  it('报警3分页/6metrics/每row revision原始span strict，无近整数舍入或重复键洗白', () => { const source = JSON.stringify(workbench()); for (const field of ['total', 'page', 'size', 'alarm_total', 'unhandled', 'handled', 'unrecovered', 'recovered', 'true_fire', 'revision']) { const found = new RegExp(`"${field}":(\\d+)`).exec(source)!; for (const invalid of ['0.999999999999999999999', '1e0', '1.0', 'true', '"1"']) expect(() => readDeviceAlarms(source.replace(found[0], `"${field}":${invalid}`), 'd1', 'tenant-a', emptyAlarmFilters, 1)).toThrow() }; expect(() => readDeviceAlarms(source.replace('"device_id":"d1"', '"device_id":"d1","device_id":"d2"'), 'd1', 'tenant-a', emptyAlarmFilters, 1)).toThrow() })
  it('新GET精准path/scoped header，customer不带tenant，detail无query、不读effective/产品/参数旧目录', async () => { const fetcher = mock(url => url.includes('/alarms?') ? response(workbench({ filters: { ...emptyAlarmFilters, keyword: '%_literal' }, items: [], total: 0, metrics: { alarm_total: 0, unhandled: 0, handled: 0, unrecovered: 0, recovered: 0, true_fire: 0 } })) : undefined, ['device:read']); await deviceDetailApi.detail('d1', undefined, 'tenant-a'); expect(detailCalls(fetcher)[0][0]).toBe('/api/management/devices/d1'); expect(detailCalls(fetcher)[0][1].headers.get('X-Tenant-Id')).toBe('tenant-a'); login(['device:read', 'alarm:read'], true); await deviceDetailApi.detail('d1', undefined, 'tenant-a'); await deviceDetailApi.alarms('d1', { ...emptyAlarmFilters, keyword: '%_literal' }, 1, undefined, undefined, 'tenant-a'); expect(detailCalls(fetcher).at(-1)![0]).toBe('/api/customer/devices/d1'); expect(detailCalls(fetcher).at(-1)![1].headers.has('X-Tenant-Id')).toBe(false); const query = new URL(alarmCalls(fetcher)[0][0], 'http://local').searchParams; expect(query.get('keyword')).toBe('%_literal'); expect(query.has('device_id') || query.has('size')).toBe(false); expect(fetcher.mock.calls.every(call => String(call[0]).includes('/devices/d1'))).toBe(true) })
})
describe('P8 record-owner device detail', () => {
  it('opens device and related alarms under record owner with no global tenant, not previous unit', async () => {
    const fetcher = mock(undefined)
    expect(useSession.getState().scopeTenantIds).toEqual(['tenant-a'])
    render(<DeviceDetailComponent deviceId="d1" tenantId="tenant-a" close={() => {}}/>)
    await screen.findByText('当前详情名称', { selector: 'h2' })
    await userEvent.setup().click(await relatedAlarmButton())
    await screen.findByText('报警固有旧名称')
    expect(detailCalls(fetcher)[0][1].headers.get('X-Tenant-Id')).toBe('tenant-a')
    expect(alarmCalls(fetcher)[0][1].headers.get('X-Tenant-Id')).toBe('tenant-a')
  })
})

describe('P8双端设备当前详情与独立关联', () => {
  it('无有效上报显示未激活；未绑定型号与未知电量保持明确', async () => {
    mock(() => raw(detail({ product_id: null, product_version_id: null, product_name: null, version_number: null })), ['device:read'])
    render(<DeviceDetail deviceId="d1" close={() => {}}/>)
    await screen.findByText(/未绑定模板，不冒充/)
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getAllByText('未激活')).toHaveLength(2)
    expect(dialog.queryByText('0%', { exact: true })).not.toBeInTheDocument()
  })
  it('详情保留电量精度和原绑定型号，不请求最新产品', async () => {
    const fetcher = mock(url => /\/devices\/d1$/.test(url) ? new Response(JSON.stringify(detail()).replace('"battery_percent":null', '"battery_percent":0.10000000000000000001')) : undefined, ['device:read'])
    render(<DeviceDetail deviceId="d1" close={() => {}}/>)
    await screen.findByText('0.10000000000000000001%')
    expect(screen.getByText(/保存旧产品名 \/ 保存旧型号/)).toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('alarm权仍须显式读取，device当前名称/地址/NORMAL不覆盖报警固有旧快照FIRE', async () => { const fetcher = mock(url => /\/devices\/d1$/.test(url) ? raw(detail({ physical_state: 'NORMAL', connectivity: 'ONLINE', last_message_at: time })) : undefined); render(<DeviceDetail deviceId="d1" close={() => {}}/>); const user = userEvent.setup(); await screen.findByText('当前详情名称', { selector: 'h2' }); expect(alarmCalls(fetcher)).toHaveLength(0); expect(screen.getAllByText('当前安装地址', { selector: 'dd' })).toHaveLength(1); await user.click(await relatedAlarmButton()); await screen.findByText('报警固有旧名称'); expect(screen.getByText('报警固有旧地址')).toBeInTheDocument(); expect(document.querySelector('[data-device-alarm-metric="alarm_total"]')).toHaveTextContent('1'); expect(screen.queryByRole('button', { name: /复核|history|查看详情|排队|领取/ })).not.toBeInTheDocument(); expect(fetcher.mock.calls.every(call => !call[1].method || call[1].method === 'GET')).toBe(true) })
  it('关联真实分页/所有页统计/沿applied，keyword百分号字面不拿当前设备名过滤', async () => { let delayed = false, resolveLate!: (value: Response) => void; const fetcher = mock(url => { if (!url.includes('/alarms?')) return undefined; const q = new URL(url, 'http://local').searchParams, p = Number(q.get('page')); if (p === 2 && delayed) return new Promise<Response>(resolve => { resolveLate = resolve }); return response(workbench({ page: p, size: 1, total: 2, items: [{ ...alarm, id: `alarm-page-${p}` }], filters: { ...emptyAlarmFilters, keyword: q.get('keyword') ?? '' }, metrics: { ...workbench().metrics, alarm_total: 2, unhandled: 2, unrecovered: 2 } })) }); render(<DeviceDetail deviceId="d1" close={() => {}}/>); const user = userEvent.setup(); await user.click(await relatedAlarmButton()); await waitFor(() => expect(document.querySelector('[data-device-alarm-id="alarm-page-1"]')).not.toBeNull()); expect(document.querySelector('[data-device-alarm-metric="alarm_total"]')).toHaveTextContent('2'); change('关联报警关键字', '%_字面'); expect(alarmCalls(fetcher)).toHaveLength(1); await user.click(screen.getByRole('button', { name: '下一页' })); await waitFor(() => expect(document.querySelector('[data-device-alarm-id="alarm-page-2"]')).not.toBeNull()); expect(new URL(alarmCalls(fetcher).at(-1)![0], 'http://local').searchParams.get('keyword')).toBeNull(); await user.click(screen.getByRole('button', { name: '查询关联报警' })); await waitFor(() => expect(new URL(alarmCalls(fetcher).at(-1)![0], 'http://local').searchParams.get('keyword')).toBe('%_字面')); expect(new URL(alarmCalls(fetcher).at(-1)![0], 'http://local').searchParams.has('size')).toBe(false); await screen.findByRole('button', { name: '下一页' }); delayed = true; await user.click(screen.getByRole('button', { name: '下一页' })); await waitFor(() => expect(resolveLate).toBeTypeOf('function')); const old = alarmCalls(fetcher).at(-1)!; change('关联报警关键字', '新的已应用条件'); await user.click(screen.getByRole('button', { name: '查询关联报警' })); await waitFor(() => expect(document.querySelector('[data-device-alarm-id="alarm-page-1"]')).not.toBeNull()); expect(old[1].signal.aborted).toBe(true); await act(async () => resolveLate(response(workbench({ page: 2, size: 1, total: 2, filters: { ...emptyAlarmFilters, keyword: '%_字面' }, items: [{ ...alarm, id: 'late-page-2', device_name: '迟到页不出现' }], metrics: { ...workbench().metrics, alarm_total: 2, unhandled: 2, unrecovered: 2 } })))); expect(screen.queryByText('迟到页不出现')).not.toBeInTheDocument() })
  it('筛选北京时间显式+08微秒[from,to)，坏草稿/伪enum不请求也不偷换applied', async () => { const fetcher = mock(url => { if (!url.includes('/alarms?')) return undefined; const q = new URL(url, 'http://local').searchParams; if (!q.has('occurred_from')) return response(workbench()); return response(workbench({ filters: { ...emptyAlarmFilters, occurred_from: '2020-01-01T00:00:00.123456Z', occurred_to: '2020-01-01T00:00:00.123457Z' } })) }); render(<DeviceDetail deviceId="d1" close={() => {}}/>); const user = userEvent.setup(); await user.click(await relatedAlarmButton()); await screen.findByText('报警固有旧名称'); change('关联报警开始时间（北京时间）', '2020-01-01T08:00:00.123456'); change('关联报警结束时间（北京时间）', '2020-01-01T08:00:00.123457'); await user.click(screen.getByRole('button', { name: '查询关联报警' })); await waitFor(() => expect(alarmCalls(fetcher)).toHaveLength(2)); const q = new URL(alarmCalls(fetcher).at(-1)![0], 'http://local').searchParams; expect(q.get('occurred_from')).toBe('2020-01-01T08:00:00.123456+08:00'); expect(q.get('occurred_to')).toBe('2020-01-01T08:00:00.123457+08:00'); await screen.findByText((text, node) => node?.tagName === 'P' && text.startsWith('服务端实际应用条件') && text.includes('2020-01-01T00:00:00.123456Z')); change('关联报警结束时间（北京时间）', '2020-01-01T08:00:00.123455'); await user.click(screen.getByRole('button', { name: '查询关联报警' })); await screen.findByText(/开始时间必须早于结束时间/); expect(alarmCalls(fetcher)).toHaveLength(2); change('关联报警开始时间（北京时间）', ''); change('关联报警结束时间（北京时间）', ''); const select = screen.getByLabelText('关联报警物理状态') as HTMLSelectElement; select.add(new Option('伪造状态', 'UNKNOWN')); fireEvent.change(select, { target: { value: 'UNKNOWN' } }); await user.click(screen.getByRole('button', { name: '查询关联报警' })); await screen.findByText(/筛选枚举未确认/); expect(alarmCalls(fetcher)).toHaveLength(2) })
  it('explicit size与clear重置page1/空时间，不默认日期或size，筛选不是嵌套写form', async () => { const fetcher = mock(url => url.includes('/alarms?') ? response(workbench({ size: Number(new URL(url, 'http://local').searchParams.get('size') ?? '3') })) : undefined); render(<DeviceDetail deviceId="d1" close={() => {}}/>); const user = userEvent.setup(); await user.click(await relatedAlarmButton()); await screen.findByText('报警固有旧名称'); expect(screen.getByLabelText('关联报警开始时间（北京时间）')).toHaveValue(''); expect(screen.getByLabelText('每页关联报警条数')).toHaveValue(''); await user.selectOptions(screen.getByLabelText('每页关联报警条数'), '20'); await waitFor(() => expect(new URL(alarmCalls(fetcher).at(-1)![0], 'http://local').searchParams.get('size')).toBe('20')); await user.selectOptions(screen.getByLabelText('每页关联报警条数'), ''); await waitFor(() => expect(new URL(alarmCalls(fetcher).at(-1)![0], 'http://local').searchParams.has('size')).toBe(false)); await user.click(screen.getByRole('button', { name: '清空关联报警条件' })); expect(document.querySelector('form form')).toBeNull(); expect(fetcher.mock.calls.every(call => !call[1].method || call[1].method === 'GET')).toBe(true) })
})

describe('设备资料编辑入口的确认与授权边界', () => {
  it.each([false, true])('读取中、失败及撤销写权限不允许从旧快照进入编辑（客户=%s）', async client => {
    let calls = 0, resolveRefresh!: (value: Response) => void
    mock(url => /\/devices\/d1$/.test(url) && ++calls === 2 ? new Promise<Response>(resolve => { resolveRefresh = resolve }) : undefined, ['device:read', 'device:write'], client)
    render(<DeviceDetail deviceId="d1" close={() => {}} onEdit={() => {}}/>)
    const user = userEvent.setup()
    await screen.findByRole('button', { name: '编辑设备资料' })
    await user.click(screen.getByRole('button', { name: '刷新设备详情' }))
    await waitFor(() => expect(resolveRefresh).toBeTypeOf('function'))
    expect(screen.queryByRole('button', { name: '编辑设备资料' })).not.toBeInTheDocument()
    await act(async () => resolveRefresh(response({ code: 'FORBIDDEN', message: '当前设备读取已拒绝' }, 403)))
    await screen.findByText(/旧设备及关联报警已清除/)
    expect(screen.queryByRole('button', { name: '编辑设备资料' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '刷新设备详情' }))
    await screen.findByRole('button', { name: '编辑设备资料' })
    act(() => {
      const { token, authGeneration, user: account } = useSession.getState()
      useSession.getState().revalidate(token!, authGeneration, account!, ['device:read'])
    })
    await screen.findByText('当前详情名称', { selector: 'h2' })
    expect(screen.queryByRole('button', { name: '编辑设备资料' })).not.toBeInTheDocument()
  })
})

describe('P8只读生命周期/坏响应，不复用旧行或扩权', () => {
  it('刷新当前详情销毁关联结果/草稿/请求，重新确认后须再次明确读取', async () => { const fetcher = mock(); render(<DeviceDetail deviceId="d1" close={() => {}}/>); const user = userEvent.setup(); await user.click(await relatedAlarmButton()); await screen.findByText('报警固有旧名称'); change('关联报警关键字', '不保留草稿'); await user.click(screen.getByRole('button', { name: '刷新设备详情' })); expect(screen.queryByLabelText('关联报警关键字')).not.toBeInTheDocument(); await user.click(await relatedAlarmButton()); expect(screen.getByLabelText('关联报警关键字')).toHaveValue(''); expect(detailCalls(fetcher)).toHaveLength(2); expect(alarmCalls(fetcher)).toHaveLength(2) })
  it('撤alarm读立即清子树/统计、重新授予不恢复隐藏结果或请求', async () => { const fetcher = mock(); render(<DeviceDetail deviceId="d1" close={() => {}}/>); const user = userEvent.setup(); await user.click(await relatedAlarmButton()); await screen.findByText('报警固有旧名称'); act(() => useSession.setState({ permissions: ['device:read'] })); expect(screen.queryByText('报警固有旧名称')).not.toBeInTheDocument(); expect(document.querySelector('[data-device-alarm-metric]')).toBeNull(); await user.click(await screen.findByRole('tab', { name: '关联报警' })); await screen.findByText(/缺独立alarm:read/); act(() => useSession.setState({ permissions: ['device:read', 'alarm:read'] })); await relatedAlarmButton(); expect(alarmCalls(fetcher)).toHaveLength(1); expect(document.querySelector('[data-device-alarm-metric]')).toBeNull() })
  it('device-only缺alarm不读，只有alarm缺device连详情也不读', () => { const fetcher = mock(undefined, ['alarm:read']); render(<DeviceDetail deviceId="d1" close={() => {}}/>); expect(fetcher).not.toHaveBeenCalled(); expect(screen.queryByRole('dialog')).not.toBeInTheDocument() })
  it('对象A→B取消晚A，关闭清结果，晚GET不把A画进B', async () => { let resolve!: (value: Response) => void; const fetcher = mock(url => /\/devices\/d1$/.test(url) ? new Promise<Response>(r => { resolve = r }) : undefined); const view = render(<DeviceDetail deviceId="d1" close={() => {}}/>); await waitFor(() => expect(resolve).toBeTypeOf('function')); const old = detailCalls(fetcher)[0]; view.rerender(<DeviceDetail deviceId="d2" close={() => {}}/>); await waitFor(() => expect(document.querySelector('[data-device-detail-id="d2"]')).not.toBeNull()); expect(old[1].signal.aborted).toBe(true); await act(async () => resolve(raw(detail({ name: '迟到A不显示' })))); expect(screen.queryByText('迟到A不显示')).not.toBeInTheDocument(); view.rerender(<DeviceDetail deviceId={null} close={() => {}}/>); expect(document.querySelector('[data-device-detail-id]')).toBeNull() })
  it('切换单位筛选不改写已打开记录的归属；撤设备读才销毁当前与晚数据，不落浏览器cache', async () => { let resolve!: (value: Response) => void; const fetcher = mock(url => /\/devices\/d1$/.test(url) ? new Promise<Response>(r => { resolve = r }) : undefined); render(<DeviceDetail deviceId="d1" close={() => {}}/>); await waitFor(() => expect(resolve).toBeTypeOf('function')); const old = detailCalls(fetcher)[0]; act(() => useSession.getState().setScope(['tenant-b'], [], true)); expect(old[1].signal.aborted).toBe(false); expect((old[1].headers as Headers).get('X-Tenant-Id')).toBe('tenant-a'); expect(detailCalls(fetcher)).toHaveLength(1); act(() => useSession.setState({ permissions: [] })); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(old[1].signal.aborted).toBe(true); act(() => useSession.getState().clear()); await act(async () => resolve(raw(detail({ name: 'scope旧对象' })))); expect(screen.queryByText('scope旧对象')).not.toBeInTheDocument(); expect(localStorage.length + sessionStorage.length).toBe(0) })
  it('detail403/404保会话、当前/关联清除，不拿列表当成功；detail刷新后坏200无正常/0', async () => { for (const code of [403, 404, 200]) { const fetcher = mock(url => /\/devices\/d1$/.test(url) ? code === 200 ? raw({ ...detail(), device: { ...device, id: 'other-object' } }) : response({ code: 'DENIED_OR_MISSING', message: '本次读取未确认', request_id: `device-${code}` }, code) : undefined); const view = render(<DeviceDetail deviceId="d1" close={() => {}}/>); await screen.findByText(/旧设备及关联报警已清除/); expect(document.querySelector('[data-device-detail-id]')).toBeNull(); expect(screen.queryByRole('button', { name: '读取关联报警' })).not.toBeInTheDocument(); expect(useSession.getState().token).not.toBeNull(); expect(alarmCalls(fetcher)).toHaveLength(0); view.unmount() } })
  it('报警刷新错误/错设备/近整数metadata不补0/旧stats，当前设备独立保留', async () => { let calls = 0; const fetcher = mock(url => { if (!url.includes('/alarms?')) return undefined; return ++calls === 1 ? response(workbench()) : calls === 2 ? new Response(JSON.stringify(workbench()).replace('"unhandled":1', '"unhandled":0.9999999999999999999999')) : response({ code: 'FORBIDDEN', message: '关联读取已拒绝', request_id: 'related-403' }, 403) }); render(<DeviceDetail deviceId="d1" close={() => {}}/>); const user = userEvent.setup(); await user.click(await relatedAlarmButton()); await screen.findByText('报警固有旧名称'); await user.click(screen.getByRole('button', { name: '刷新关联报警' })); await screen.findByText(/DEVICE_DETAIL_UNCONFIRMED/); expect(screen.queryByText('报警固有旧名称')).not.toBeInTheDocument(); expect(document.querySelector('[data-device-alarm-metric]')).toBeNull(); expect(document.querySelector('[data-device-detail-id="d1"]')).not.toBeNull(); const token = useSession.getState().token; await user.click(screen.getByRole('button', { name: '刷新关联报警' })); await screen.findByText(/related-403/); expect(document.querySelector('[data-device-alarm-metric]')).toBeNull(); expect(screen.queryByText('报警固有旧名称')).not.toBeInTheDocument(); expect(useSession.getState().token).toBe(token); expect(fetcher.mock.calls.some(call => /history|review|notifications|source|parameters|products/.test(call[0]))).toBe(false) })
})

describe('本设备故障记录', () => {
  const fault = { id: 'fault-one', tenant_id: 'tenant-a', device_id: 'd1', kind: 'FAULT', method: 'thing.event.faults', fault_code: 'LOW_BATTERY', fault_label: '电量过低', observed_at: null, received_at: null, created_at: time, source_message_at: null, source_time_field: null, source_fault_id: null, source_recovered: true, source_recovered_at: null, device_name: '当前详情名称', device_address: null, place_id: null, handling: { handled: false, note: '', revision: 0, updated_by: 'actor', updated_at: time } }
  it('来源恢复、人工处置与未知时间分别展示，刷新失败清除旧故障', async () => {
    let calls = 0
    mock(url => url.includes('/duty/device-faults/d1/events?') ? ++calls === 1 ? response({ items: [fault], total: 1, page: 1, size: 20 }) : response({ code: 'SERVICE_UNAVAILABLE', message: '本次故障查询失败' }, 503) : undefined, ['device:read'])
    render(<DeviceDetail deviceId="d1" close={() => {}}/>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: '故障记录' }))
    const panel = await screen.findByRole('region', { name: '本设备故障记录' })
    await within(panel).findByText('电量过低')
    expect(within(panel).getByText('来源已修复，恢复时间未提供')).toBeInTheDocument()
    expect(within(panel).getByRole('cell', { name: '未处置' })).toBeInTheDocument()
    expect(within(panel).getByText('观察时间未知')).toBeInTheDocument()
    expect(within(panel).getByText('接收时间未知')).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: '刷新本设备故障' }))
    await within(panel).findByText(/本次故障查询失败/)
    expect(within(panel).queryByText('电量过低')).not.toBeInTheDocument()
    expect(within(panel).queryByText('本设备暂无故障记录')).not.toBeInTheDocument()
  })
  it('拒绝另一设备、另一单位、重复记录和非布尔恢复状态，不把坏响应显示为本设备故障', async () => {
    mock(undefined, ['device:read'])
    for (const items of [[{ ...fault, device_id: 'd2' }], [{ ...fault, tenant_id: 'tenant-b' }], [fault, fault], [{ ...fault, source_recovered: 'false' }]]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ items, total: items.length, page: 1, size: 20 })))
      await expect(queryDeviceDetailFaultEvents('d1', 'tenant-a', 1, '', '', '')).rejects.toThrow()
    }
  })
  it('故障时间草稿不改变分页条件，清空回到第一页并恢复未知时间记录', async () => {
    mock(url => {
      if (!url.includes('/duty/device-faults/d1/events?')) return undefined
      const q = new URL(url, 'http://local').searchParams, page = Number(q.get('page')), bounded = q.has('from')
      return response({ items: [{ ...fault, id: bounded ? `bounded-${page}` : 'untimed-fault', observed_at: bounded ? '2026-10-25T04:20:00Z' : null }], total: 2, page, size: 1 })
    }, ['device:read'])
    render(<DeviceDetail deviceId="d1" close={() => {}}/>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: '故障记录' }))
    const panel = await screen.findByRole('region', { name: '本设备故障记录' })
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="untimed-fault"]')).not.toBeNull())
    change('故障开始时间（北京时间，含）', '2026-10-25T12:00:00')
    change('故障结束时间（北京时间，不含）', '2026-10-25T13:00:00')
    await user.click(within(panel).getByRole('button', { name: '查询故障记录' }))
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="bounded-1"]')).not.toBeNull())
    change('故障开始时间（北京时间，含）', '2027-10-25T12:00:00')
    await user.click(within(panel).getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="bounded-2"]')).not.toBeNull())
    await user.click(within(panel).getByRole('button', { name: '清空故障条件' }))
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="untimed-fault"]')).not.toBeNull())
    expect(within(panel).getByRole('button', { name: '上一页' })).toBeDisabled()
  })
  it('有界故障查询拒绝未知来源时间及半开区间外的成功响应', async () => {
    mock(undefined, ['device:read'])
    const from = '2026-10-25T04:20:00.123456Z', to = '2026-10-25T04:20:00.123457Z'
    for (const observed_at of [null, '2026-10-25T04:20:00.123455Z', to]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ items: [{ ...fault, observed_at }], total: 1, page: 1, size: 20 })))
      await expect(queryDeviceDetailFaultEvents('d1', 'tenant-a', 1, from, to, '')).rejects.toThrow()
    }
  })
  it('处置草稿不影响翻页，应用后重回第一页且清空恢复全部状态', async () => {
    mock(url => {
      if (!url.includes('/duty/device-faults/d1/events?')) return undefined
      const q = new URL(url, 'http://local').searchParams, page = Number(q.get('page')), state = q.get('handling_state')
      const handling = state === 'UNRECORDED' ? null : { ...fault.handling, handled: state === 'HANDLED' }
      return response({ items: [{ ...fault, id: `${state || 'ALL'}-${page}`, handling }], total: 2, page, size: 1 })
    }, ['device:read'])
    render(<DeviceDetail deviceId="d1" close={() => {}}/>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: '故障记录' }))
    const panel = await screen.findByRole('region', { name: '本设备故障记录' })
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="ALL-1"]')).not.toBeNull())
    await user.selectOptions(within(panel).getByLabelText('故障本地处置状态'), 'HANDLED')
    await user.click(within(panel).getByRole('button', { name: '查询故障记录' }))
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="HANDLED-1"]')).not.toBeNull())
    await user.selectOptions(within(panel).getByLabelText('故障本地处置状态'), 'UNRECORDED')
    await user.click(within(panel).getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="HANDLED-2"]')).not.toBeNull())
    await user.click(within(panel).getByRole('button', { name: '查询故障记录' }))
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="UNRECORDED-1"]')).not.toBeNull())
    expect(within(panel).getByRole('button', { name: '上一页' })).toBeDisabled()
    await user.click(within(panel).getByRole('button', { name: '清空故障条件' }))
    await waitFor(() => expect(panel.querySelector('[data-device-fault-id="ALL-1"]')).not.toBeNull())
    expect(within(panel).getByLabelText('故障本地处置状态')).toHaveValue('')
  })
  it('处置筛选拒绝状态不匹配的成功响应，不把未记录当未处置', async () => {
    mock(undefined, ['device:read'])
    for (const [state, handling] of [['UNRECORDED', fault.handling], ['UNHANDLED', null], ['HANDLED', fault.handling]] as const) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ items: [{ ...fault, handling }], total: 1, page: 1, size: 20 })))
      await expect(queryDeviceDetailFaultEvents('d1', 'tenant-a', 1, '', '', state)).rejects.toThrow()
    }
  })
})

describe('本设备事件筛选', () => {
  const event = { id: 'all-event', tenant_id: 'tenant-a', device_id: 'd1', kind: 'OTHER', method: 'fixture.event', status: 'NORMALIZED', event_code: null, event_label: null, observed_at: time, received_at: null, created_at: time }
  it('类型草稿不改变已应用条件，分页沿已应用类型且清空返回第一页', async () => {
    mock(url => {
      if (!url.includes('/devices/d1/events?')) return undefined
      const query = new URL(url, 'http://local').searchParams, page = Number(query.get('page')), kind = query.get('kind')
      return response({ items: [{ ...event, id: kind === 'FAULT' ? `fault-page-${page}` : 'all-event', kind: kind || 'OTHER' }], total: 2, page, size: 1 })
    }, ['device:read'])
    render(<DeviceDetail deviceId="d1" close={() => {}}/>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: '事件记录' }))
    const panel = await screen.findByRole('region', { name: '本设备事件记录' })
    await waitFor(() => expect(panel.querySelector('[data-device-event-id="all-event"]')).not.toBeNull())
    await user.selectOptions(within(panel).getByLabelText('事件类型'), 'FAULT')
    await user.click(within(panel).getByRole('button', { name: '查询事件记录' }))
    await waitFor(() => expect(panel.querySelector('[data-device-event-id="fault-page-1"]')).not.toBeNull())
    await user.selectOptions(within(panel).getByLabelText('事件类型'), 'OTHER')
    await user.click(within(panel).getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(panel.querySelector('[data-device-event-id="fault-page-2"]')).not.toBeNull())
    expect(panel.querySelector('[data-device-event-id="all-event"]')).toBeNull()
    await user.click(within(panel).getByRole('button', { name: '清空事件条件' }))
    await waitFor(() => expect(panel.querySelector('[data-device-event-id="all-event"]')).not.toBeNull())
    expect(within(panel).getByRole('button', { name: '上一页' })).toBeDisabled()
  })
  it('拒绝与已应用类型不符的成功响应，不把其他类型冒充筛选结果', async () => {
    mock(url => url.includes('/devices/d1/events?') ? response({ items: [event], total: 1, page: 1, size: 20 }) : undefined, ['device:read'])
    await expect(deviceDetailApi.events('d1', { kind: 'FAULT', from: '', to: '' }, 1, undefined, undefined, 'tenant-a')).rejects.toThrow()
  })
})
