import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { api } from '@/lib/api'
import { alarmHistoryResponse, alarmResponse } from '@/lib/alarm-json'
import { useSession } from '@/lib/store'
import { emptyAlarmFilters, expectedMetricDefinitions } from '@/lib/alarm-workbench'
import type { Alarm, AlarmCoverage, AlarmHistory, AlarmTraceEntry } from '@/lib/types'
import { AlarmDetail as OwnerAlarmDetail } from '@/components/alarm-detail'
import { CoverageView, TraceState, validCoverage } from '@/components/alarm-trace'
import { AlarmsPage } from '@/pages/alarms'
import { CustomerOverviewPage } from '@/pages/customer-overview'
import { alarm, customer, management, response } from './fixtures'
const grants = ['alarm:read', 'alarm:review', 'alarm:history']
const current: Alarm & { tenant_id: string } = { ...alarm, tenant_id: 'tenant-a', id: 'alarm-trace-one', device_id: 'device-frozen', device_name: '报警时名称快照', address: '报警时地址快照', revision: 0, physical_state: 'FIRE', response_state: 'UNHANDLED', conclusion: null }
const reportedEui = 'fixture-alarm-device-eui'
function AlarmDetail(props: { alarmId: string | null; tenantId?: string; onClose: () => void; onChanged: () => void }) { return <OwnerAlarmDetail {...props} tenantId={props.tenantId ?? current.tenant_id}/> }
const physical = { physical_state: 'FIRE' as const, response_state: 'UNHANDLED' as const, conclusion: null }
const created: AlarmTraceEntry = { id: 'trace-created', alarm_id: current.id, action: 'FIRE_CREATED', revision_before: null, revision_after: 0, before: null, after: physical, occurred_at: '2026-10-23T04:00:00Z', recorded_at: '2026-10-23T04:00:01Z', actor_kind: 'SYSTEM', actor_label: '系统处理', is_self: false, remark: null, origin: 'APPLICATION', actor_id: null, actor_name: null }
const reviewed: AlarmTraceEntry = { ...created, id: 'trace-reviewed', action: 'REVIEWED', revision_before: 0, revision_after: 1, before: physical, after: { ...physical, response_state: 'HANDLED', conclusion: 'TEST' }, actor_kind: 'PLATFORM', actor_label: '当时平台快照名', actor_id: 'manager-id-private', actor_name: '当时平台快照名', remark: '该次明确复核正文', occurred_at: null }
const recovered: AlarmTraceEntry = { ...created, id: 'trace-recovered', action: 'ALARM_RECOVERED', revision_before: 1, revision_after: 2, before: reviewed.after, after: { ...reviewed.after, physical_state: 'NORMAL' } }
const coverage: AlarmCoverage = { kind: 'FROM_CREATION', first_revision: 0, journal_through_revision: 0, current_revision: 0, entry_total: 1, baseline_recorded_at: null }
const history = (items: AlarmTraceEntry[] = [created], extra: Partial<AlarmHistory> = {}): AlarmHistory => ({ items, total: items.length, page: 1, size: 20, coverage: { ...coverage, entry_total: items.length, current_revision: items.length - 1, journal_through_revision: items.length - 1 }, ...extra })
function login(permissions = grants, audience: 'management' | 'customer' = 'management') { useSession.getState().login({ ...(audience === 'management' ? management : customer), permissions }, audience); if (audience === 'management') useSession.getState().setScope(['tenant-a'], [], true) }
const page = (items: unknown[]) => ({ items, total: items.length, page: 1, size: 20 })
function baseFetch(url: string): Response {
  if (url.includes('/alarm-workbench?')) {
    const requestedSize = new URL(url, 'http://local').searchParams.get('size')
    return response({ items: [current], total: 1, page: 1, size: requestedSize ? Number(requestedSize) : 20, as_of: created.recorded_at, timezone: 'Asia/Shanghai', filters: { ...emptyAlarmFilters }, metrics: { alarm_total: 1, unhandled: 1, handled: 0, unrecovered: 1, recovered: 0, true_fire: 0 }, metric_definitions: { ...expectedMetricDefinitions } })
  }
  if (url.includes('/history?')) return response(history())
  if (url.includes('/alarms?')) return response(page([current]))
  if (url.includes('/summary')) return response({ device_total: 0, online: 0, offline: 0, unactivated: 0, today_alarms: 1, unhandled_alarms: 1 })
  if (url.endsWith(`/alarms/${current.id}`)) return response(current)
  throw new Error(`Unexpected synthetic alarm endpoint: ${url}`)
}
async function openHistory(user: ReturnType<typeof userEvent.setup>) { await user.click(await screen.findByRole('button', { name: '查看业务轨迹' })); await within(screen.getByRole('dialog')).findByRole('button', { name: '刷新业务轨迹' }) }
async function beginReview(user: ReturnType<typeof userEvent.setup>, remark = '明确现场复核') { await user.click(await screen.findByRole('button', { name: '开始人工复核' })); expect(screen.getByLabelText('复核结论')).toHaveValue(''); await user.selectOptions(screen.getByLabelText('复核结论'), 'TEST'); fireEvent.change(screen.getByLabelText('现场情况 / 处置备注'), { target: { value: remark } }) }
function stateOfCurrent() { return within(screen.getByRole('dialog')) }
function currentState() { return stateOfCurrent().getByRole('group', { name: '当前报警状态' }) }
async function waitForCurrentState() { await screen.findByRole('heading', { name: current.device_name!, level: 2 }) }
beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })

describe('P6a1当前与历史安全DTO/独立权限', () => {
  it('AlarmRow安全投影保留record owner；history管理快照保留、客户两管理key和nested秘密彻底不保留', () => {
    const poisoned = { ...reviewed, payload: 'forbidden-payload', raw_event: 'forbidden-event', request_id: 'forbidden-audit', before: { ...physical, actor_id: 'forbidden-nested' }, after: { ...reviewed.after, raw_event: 'forbidden-after' } }
    const row = alarmResponse(JSON.stringify({ ...current, device_eui: reportedEui, remark: 'forbidden-remark', actor_name: 'forbidden-name', payload: 'forbidden' }))
    expect(row).toEqual({ ...current, device_eui: reportedEui })
    const manager = alarmHistoryResponse(JSON.stringify(history([poisoned])), 'management'); expect(manager.items[0].actor_name).toBe('当时平台快照名'); expect(manager.items[0].actor_id).toBe('manager-id-private'); expect(JSON.stringify(manager)).not.toContain('forbidden')
    const client = alarmHistoryResponse(JSON.stringify(history([{ ...poisoned, actor_label: '平台人员' }])), 'customer'); expect(Object.hasOwn(client.items[0], 'actor_id')).toBe(false); expect(Object.hasOwn(client.items[0], 'actor_name')).toBe(false); expect(JSON.stringify(client)).not.toMatch(/当时平台快照名|manager-id-private|forbidden/); expect(client.items[0].actor_label).toBe('平台人员')
  })
  it('客户label为封闭安全投影，恶意姓名/伪本人或SYSTEM冒充本人不保留', () => {
    for (const entry of [{ ...reviewed, actor_label: 'secret@example.test' }, { ...reviewed, actor_label: '本人', is_self: false }, { ...created, actor_label: '本人', is_self: true }]) { const result = alarmHistoryResponse(JSON.stringify(history([entry])), 'customer'); expect(result.items[0].actor_label).toBe('身份标签未确认'); expect(JSON.stringify(result)).not.toContain('secret@example.test') }
    const own = alarmHistoryResponse(JSON.stringify(history([{ ...reviewed, actor_kind: 'CUSTOMER', actor_label: '本人', is_self: true }])), 'customer'); expect(own.items[0].actor_label).toBe('本人'); expect(own.items[0].is_self).toBe(true)
  })
  it('新GET双端正确tenant头/默认分页，复核仍旧三字段+key并支持abort', async () => {
    login(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => init?.method === 'POST' ? response({ ...current, response_state: 'HANDLED' }) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); await api.alarm(current.id, undefined, current.tenant_id); await api.alarmHistory(current.id, 2, undefined, current.tenant_id); const controller = new AbortController(); await api.review({ ...current, actor_id: 'forged', remark: 'forged' } as Alarm, 'TEST', '旧兼容正文', 'stable-review-key', controller.signal)
    for (const [, init] of fetcher.mock.calls) expect((init!.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a'); expect(fetcher.mock.calls[1][0]).toMatch(/\/history\?page=2$/); expect(fetcher.mock.calls[1][0]).not.toContain('size='); const write = fetcher.mock.calls[2][1]!; expect(JSON.parse(String(write.body))).toEqual({ expected_revision: 0, conclusion: 'TEST', remark: '旧兼容正文' }); expect((write.headers as Headers).get('Idempotency-Key')).toBe('stable-review-key'); expect(write.signal).toBe(controller.signal)
    useSession.getState().login({ ...customer, permissions: grants }, 'customer'); await api.alarm(current.id); await api.alarmHistory(current.id, 1); for (const [url, init] of fetcher.mock.calls.slice(3)) { expect(url).toMatch(/^\/api\/customer\/alarms\//); expect((init!.headers as Headers).has('X-Tenant-Id')).toBe(false) }
  })
  it('缺alarm:read/无单位/未改密不能请求新详情；review/history都不替代read', () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher); login(['alarm:review', 'alarm:history']); const one = render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); expect(fetcher).not.toHaveBeenCalled(); one.unmount(); useSession.getState().login({ ...management, permissions: grants }, 'management'); const two = render(<OwnerAlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); expect(fetcher).not.toHaveBeenCalled(); two.unmount(); useSession.getState().login({ ...customer, permissions: grants, user: { ...customer.user, must_change_password: true } }, 'customer'); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); expect(fetcher).not.toHaveBeenCalled()
  })
  it('management detail拒绝响应中与被打开记录不匹配的owner', async () => {
    login(); const fetcher = vi.fn().mockResolvedValue(response({ ...current, tenant_id: 'tenant-b' })); vi.stubGlobal('fetch', fetcher); render(<OwnerAlarmDetail alarmId={current.id} tenantId="tenant-a" onClose={() => {}} onChanged={() => {}}/>); expect(await screen.findByText('当前报警身份未确认')).toBeInTheDocument(); expect(screen.queryByRole('button', { name: '开始人工复核' })).not.toBeInTheDocument(); expect((fetcher.mock.calls[0][1]!.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a')
  })
  it('无新history权限只读当前并可复核，打开详情本身不写、不查设备/产品/原文', async () => {
    login(['alarm:read', 'alarm:review']); const fetcher = vi.fn().mockImplementation(baseFetch); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); expect(await screen.findByRole('heading', { name: current.device_name!, level: 2 })).toBeInTheDocument(); expect(screen.getByRole('button', { name: '开始人工复核' })).toBeEnabled(); expect(screen.queryByLabelText('复核结论')).not.toBeInTheDocument(); expect(fetcher.mock.calls).toHaveLength(1); expect(fetcher.mock.calls[0][0]).toBe(`/api/management/alarms/${current.id}`); expect(new Headers(fetcher.mock.calls[0][1]!.headers).get('X-Tenant-Id')).toBe(current.tenant_id)
  })
  it('管理报警表打开只读详情走新GET，并保留workbench列表请求', async () => {
    login(); const fetcher = vi.fn().mockImplementation(baseFetch); vi.stubGlobal('fetch', fetcher); render(<AlarmsPage/>); const user = userEvent.setup(); const button = await screen.findByRole('button', { name: '查看详情' }); await user.click(button); const dialog = await screen.findByRole('dialog'); expect(await within(dialog).findByRole('heading', { name: current.device_name!, level: 2 })).toBeInTheDocument(); expect(fetcher.mock.calls.some(call => call[0] === `/api/management/alarms/${current.id}`)).toBe(true); expect(fetcher.mock.calls.some(call => call[0].includes('/alarm-workbench?'))).toBe(true)
  })
  it('客户报警页只读详情与总览的实际报警行保持当前/轨迹权限边界', async () => {
    login(['alarm:read'], 'customer')
    useSession.getState().setScope(['tenant-a'], [], true)
    const fetcher = vi.fn<(url: string, init?: RequestInit) => Response>(baseFetch)
    vi.stubGlobal('fetch', fetcher)
    const user = userEvent.setup()
    const first = render(<AlarmsPage/>)
    await user.click(await screen.findByRole('button', { name: '查看详情' }))
    expect(await screen.findByRole('heading', { name: current.device_name!, level: 2 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '开始人工复核' })).not.toBeInTheDocument()
    first.unmount()
    login(['alarm:read', 'summary:read'], 'customer')
    useSession.getState().setScope(['tenant-a'], [], true)
    render(<CustomerOverviewPage/>)
    await user.click(await screen.findByRole('button', { name: /报警时名称快照.*未复核/ }))
    expect(await screen.findByRole('heading', { name: current.device_name!, level: 2 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '开始人工复核' })).not.toBeInTheDocument()
    expect(fetcher.mock.calls.some(call => call[1]?.method === 'POST')).toBe(false)
    expect(fetcher.mock.calls.some(call => call[0].includes('/history'))).toBe(false)
  })
})

describe('P6a1不可变轨迹、coverage与安全文本', () => {
  it('管理按历史快照名显示而非当前用户；系统不猜人，备注/名称作为React文本', async () => {
    login(); const named = { ...reviewed, actor_label: '<img src=x onerror=alert(1)>', actor_name: '<img src=x onerror=alert(1)>', remark: '<script>not executed</script>' }; const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/history?') ? response(history([named, created], { coverage: { ...coverage, journal_through_revision: 1, current_revision: 1, entry_total: 2 } })) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); expect(await screen.findByText('<img src=x onerror=alert(1)>（当时快照）')).toBeInTheDocument(); expect(screen.getByText('<script>not executed</script>')).toBeInTheDocument(); expect(screen.getByText('系统处理 · 无用户身份上下文，不猜某个人')).toBeInTheDocument(); expect(document.querySelector('img')).toBeNull(); expect(document.querySelector('script')).toBeNull(); expect(screen.getByText(/无发生时刻（null），不根据记录时间补猜/)).toBeInTheDocument()
  })
  it('客户只显示本人/保守actor标签，不在DOM/DTO中保留管理id和真实快照名', async () => {
    login(grants, 'customer'); const safe = { ...reviewed, actor_label: '平台人员' }; const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/history?') ? response(history([safe], { coverage: { ...coverage, kind: 'PARTIAL', first_revision: 1, journal_through_revision: 1, current_revision: 1 } })) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); expect(await screen.findByText('平台人员')).toBeInTheDocument(); expect(screen.queryByText(/当时平台快照名|manager-id-private/)).not.toBeInTheDocument(); expect(screen.getByText('该次明确复核正文')).toBeInTheDocument(); expect(fetcher.mock.calls.every(call => !(call[1]!.headers as Headers).has('X-Tenant-Id'))).toBe(true)
  })
  it('coverage从全部聚合而非当前页推断，真实翻页持续显示FROM_CREATION、不硬编码size', async () => {
    login(); const items = [recovered, reviewed, created], aggregate = { ...coverage, current_revision: 2, journal_through_revision: 2, entry_total: 3 }
    const fetcher = vi.fn().mockImplementation((url: string) => { if (url.includes('/history?')) { const p = Number(new URL(url, 'http://local').searchParams.get('page')); return response(history([items[p - 1]], { total: 3, page: p, size: 1, coverage: aggregate })) } return response({ ...current, revision: 2, physical_state: 'NORMAL', response_state: 'HANDLED', conclusion: 'TEST' }) }); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); await screen.findByText('从创建开始连续记录（FROM_CREATION）'); await user.click(screen.getByRole('button', { name: 'Page 2' })); await screen.findByText(/REVIEWED · trace-reviewed/); expect(screen.getByText('从创建开始连续记录（FROM_CREATION）')).toBeInTheDocument(); expect(screen.getByText(/实际轨迹行数：3/)).toBeInTheDocument(); expect(fetcher.mock.calls.some(call => call[0].endsWith('/history?page=2'))).toBe(true); expect(fetcher.mock.calls.every(call => !call[0].includes('size='))).toBe(true); expect(stateOfCurrent().getByText('正常')).toBeInTheDocument()
  })
  it('迁移基线即使revision0也仅迁移以来，不补过去、先态正常或操作者', async () => {
    login(); const baseline = { ...created, id: 'baseline-old', action: 'MIGRATION_BASELINE' as const, actor_kind: 'LEGACY_UNKNOWN' as const, actor_label: '历史未知', remark: '迁移时已有最后备注', origin: 'MIGRATION_SNAPSHOT' as const, occurred_at: null }
    const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/history?') ? response(history([baseline], { coverage: { ...coverage, kind: 'SINCE_MIGRATION', baseline_recorded_at: baseline.recorded_at } })) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); expect(await screen.findByText('仅迁移以来记录（SINCE_MIGRATION）')).toBeInTheDocument(); expect(screen.queryByText('从创建开始连续记录（FROM_CREATION）')).not.toBeInTheDocument(); expect(screen.getByText('迁移时末次备注（非过去过程）')).toBeInTheDocument(); expect(screen.getByText('未提供先态（创建/基线），不代表先前正常')).toBeInTheDocument(); expect(screen.getByText('历史未知 · 没有过去操作者证据')).toBeInTheDocument()
  })
  it('空历史/内部缺口保持PARTIAL，无数据不伪造完整或旧创建记录', async () => {
    login(); const partial = { ...coverage, kind: 'PARTIAL' as const, first_revision: null, journal_through_revision: null, current_revision: 9, entry_total: 0 }
    const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/history?') ? response(history([], { coverage: partial })) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); expect(await screen.findByText('轨迹不完整（PARTIAL）')).toBeInTheDocument(); expect(screen.getByText('暂无业务轨迹')).toBeInTheDocument(); expect(screen.queryByText(/FIRE_CREATED ·/)).not.toBeInTheDocument(); expect(validCoverage({ ...coverage, kind: 'PARTIAL', journal_through_revision: 4, current_revision: 4, entry_total: 2 }, 2)).toBe(true)
  })
  it('未知Coverage/坏revision/不一致总量不画完整或补0，未知状态枚举不显示正常', () => {
    for (const patch of [{ kind: 'UNKNOWN' }, { current_revision: -1 }, { first_revision: null }, { entry_total: 0 }, { journal_through_revision: null }]) { const view = render(<CoverageView coverage={{ ...coverage, ...patch } as AlarmCoverage} total={1}/>); expect(screen.getByText('覆盖信息未确认，不认定完整')).toBeInTheDocument(); expect(screen.queryByText('从创建开始连续记录（FROM_CREATION）')).not.toBeInTheDocument(); view.unmount() }
    const view = render(<TraceState state={{ physical_state: 'ONLINE', response_state: 'NORMAL', conclusion: 'NORMAL' }}/>); expect(screen.getByText('物理状态未确认', { exact: false })).toBeInTheDocument(); expect(screen.getByText('结论：结论未确认（未知枚举）')).toBeInTheDocument(); expect(screen.queryByText('正常')).not.toBeInTheDocument(); view.unmount(); render(<CoverageView total={0}/>); expect(screen.getByText('覆盖信息未确认，不认定完整')).toBeInTheDocument(); expect(screen.queryByText(/实际轨迹行数：0/)).not.toBeInTheDocument()
  })
  it('history403刷新清旧备注/actor，当前详情仍可读且有效登录保留', async () => {
    login(); let deny = false
    const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/history?') ? deny ? response({ code: 'PERMISSION_DENIED', message: '轨迹授权已撤回', request_id: 'history-403' }, 403) : response(history([{ ...created, remark: '旧历史敏感备注' }])) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); await screen.findByText('旧历史敏感备注'); deny = true; await user.click(screen.getByRole('button', { name: '刷新业务轨迹' })); await screen.findByText(/history-403/); expect(screen.queryByText('旧历史敏感备注')).not.toBeInTheDocument(); expect(screen.getByText(/旧轨迹已清除/)).toBeInTheDocument(); expect(screen.getByRole('heading', { name: current.device_name!, level: 2 })).toBeInTheDocument(); expect(useSession.getState().token).toBe(management.access_token)
  })
  it('本地history撤权立即清旧内容/取消晚读，恢复权限不自动重开轨迹', async () => {
    login(); let resolve!: (value: Response) => void
    const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/history?') ? new Promise<Response>(r => { resolve = r }) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); const call = fetcher.mock.calls.find(call => call[0].includes('/history?'))!; act(() => useSession.setState({ permissions: ['alarm:read', 'alarm:review'] })); expect(call[1]!.signal!.aborted).toBe(true); expect(screen.queryByText('不可变业务轨迹')).not.toBeInTheDocument(); await act(async () => resolve(response(history([{ ...created, remark: '迟到撤权备注' }])))); expect(screen.queryByText('迟到撤权备注')).not.toBeInTheDocument(); act(() => useSession.setState({ permissions: grants })); expect(await screen.findByRole('button', { name: '查看业务轨迹' })).toBeInTheDocument(); expect(screen.queryByText('不可变业务轨迹')).not.toBeInTheDocument(); expect(fetcher.mock.calls.filter(call => call[0].includes('/history?'))).toHaveLength(1)
  })
})

describe('P6a1复核兼容、重放不回滚与生命周期销毁', () => {
  it('丢复核响应同key重试返回旧FIRE快照，成功后重读实际NORMAL/HANDLED和真实轨迹', async () => {
    login(); let latest = { ...current }, writes = 0
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => { if (url.endsWith('/review')) { writes++; if (writes === 1) { latest = { ...current, revision: 2, physical_state: 'NORMAL', response_state: 'HANDLED', conclusion: 'TEST' }; return Promise.reject(new Error('synthetic lost review response')) } return response({ ...current, revision: 1, physical_state: 'FIRE', response_state: 'HANDLED', conclusion: 'TEST' }) } if (url.includes('/history?')) return response(history([recovered, reviewed, created])); if (url.endsWith(`/alarms/${current.id}`)) return response(latest); return baseFetch(url) }); vi.stubGlobal('fetch', fetcher); const changed = vi.fn(); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={changed}/>); const user = userEvent.setup(); await beginReview(user, '首轮正文同业务重传'); await user.click(screen.getByRole('button', { name: '提交复核结论' })); await screen.findByText(/NETWORK_ERROR/); await user.click(screen.getByRole('button', { name: '提交复核结论' })); await waitFor(() => expect(within(currentState()).getByText('正常')).toBeInTheDocument()); expect(within(currentState()).getByText('已处理')).toBeInTheDocument(); await screen.findByText(/ALARM_RECOVERED · trace-recovered/); expect(changed).toHaveBeenCalledTimes(1); const calls = fetcher.mock.calls.filter(call => call[0].endsWith('/review')); expect((calls[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((calls[1][1]!.headers as Headers).get('Idempotency-Key')); expect(JSON.parse(String(calls[1][1]!.body)).expected_revision).toBe(0); expect(fetcher.mock.calls.filter(call => call[0].endsWith(`/alarms/${current.id}`))).toHaveLength(2)
  }, 20_000)
  it('无history权限复核不被挡，成功仍FIRE/HANDLED，可在新详情显式再次复核', async () => {
    login(['alarm:read', 'alarm:review']); let latest = { ...current }
    const fetcher = vi.fn().mockImplementation((url: string) => { if (url.endsWith('/review')) { latest = { ...latest, revision: latest.revision + 1, response_state: 'HANDLED', conclusion: 'TEST' }; return response(latest) } if (url.endsWith(`/alarms/${current.id}`)) return response(latest); return baseFetch(url) }); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await beginReview(user); await user.click(screen.getByRole('button', { name: '提交复核结论' })); await screen.findByText('已处理'); expect(within(currentState()).getByText('火警')).toBeInTheDocument(); expect(within(currentState()).getByText('已处理')).toBeInTheDocument(); await beginReview(user, '已处置后明确再复核'); await user.click(screen.getByRole('button', { name: '提交复核结论' })); await screen.findByText('已处理'); const calls = fetcher.mock.calls.filter(call => call[0].endsWith('/review')); expect(calls).toHaveLength(2); expect(JSON.parse(String(calls[1][1]!.body)).expected_revision).toBe(1); expect(fetcher.mock.calls.some(call => call[0].includes('/history'))).toBe(false)
  }, 20_000)
  it('复核409禁旧提交，刷新重开用实际9revision、不+1、不复用旧备注', async () => {
    login(['alarm:read', 'alarm:review']); let latest = { ...current }, writes = 0
    const fetcher = vi.fn().mockImplementation((url: string) => { if (url.endsWith('/review')) { writes++; if (writes === 1) { latest = { ...current, revision: 9, physical_state: 'NORMAL' }; return response({ code: 'REVISION_CONFLICT', message: '复核/恢复并发版本冲突', request_id: 'review-detail-409' }, 409) } latest = { ...latest, revision: 10, response_state: 'HANDLED', conclusion: 'TEST' }; return response(latest) } return response(latest) }); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await beginReview(user, '旧版本正文'); await user.click(screen.getByRole('button', { name: '提交复核结论' })); await screen.findByText(/review-detail-409/); expect(screen.getByRole('button', { name: '提交复核结论' })).toBeDisabled(); await user.click(screen.getByRole('button', { name: '刷新最新版本' })); await screen.findByText('正常'); expect(stateOfCurrent().getByText('正常')).toBeInTheDocument(); await user.click(screen.getByRole('button', { name: '开始人工复核' })); expect(screen.getByLabelText('现场情况 / 处置备注')).toHaveValue(''); await user.selectOptions(screen.getByLabelText('复核结论'), 'TEST'); fireEvent.change(screen.getByLabelText('现场情况 / 处置备注'), { target: { value: '重读实际新版本复核' } }); await user.click(screen.getByRole('button', { name: '提交复核结论' })); await screen.findByText('已处理'); const calls = fetcher.mock.calls.filter(call => call[0].endsWith('/review')); expect(JSON.parse(String(calls[1][1]!.body)).expected_revision).toBe(9)
  }, 20_000)
  it('当前坏revision/未知状态禁复核，不用列表快照或未知结论充正常', async () => {
    login(); const fetcher = vi.fn().mockResolvedValue(response({ ...current, revision: -1, physical_state: 'UNAVAILABLE', response_state: 'CLOSED', conclusion: 'NORMAL' })); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); await screen.findByText(/物理状态未确认/); expect(screen.getByRole('button', { name: '开始人工复核' })).toBeDisabled(); expect(screen.getByText('版本未确认')).toBeInTheDocument(); expect(stateOfCurrent().queryByText('正常')).not.toBeInTheDocument(); expect(fetcher.mock.calls).toHaveLength(1)
  })
  it('关闭详情取消晚当前GET，不从列表补旧详情；通知/命令/领取无按钮/请求', async () => {
    login(); let resolve!: (value: Response) => void
    const fetcher = vi.fn().mockImplementation(() => new Promise<Response>(r => { resolve = r })); vi.stubGlobal('fetch', fetcher); function Harness() { const [id, setId] = useState<string | null>(current.id); return <AlarmDetail alarmId={id} onClose={() => setId(null)} onChanged={() => {}}/> } render(<Harness/>); const user = userEvent.setup(); await user.click(screen.getByRole('button', { name: 'Close' })); expect(fetcher.mock.calls[0][1]!.signal!.aborted).toBe(true); await act(async () => resolve(response(current))); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: /发送通知|领取|复位|消音|ACK/ })).not.toBeInTheDocument()
  })
  it('更换报警取消旧history与备注，迟到轨迹不混入新报警', async () => {
    login(); let resolve!: (value: Response) => void; const second = { ...current, id: 'alarm-trace-two', device_name: '另一报警固有快照' }
    const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/history?') ? new Promise<Response>(r => { resolve = r }) : response(url.endsWith(second.id) ? second : current)); vi.stubGlobal('fetch', fetcher); const view = render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); const old = fetcher.mock.calls.find(call => call[0].includes('/history?'))!; view.rerender(<AlarmDetail alarmId={second.id} onClose={() => {}} onChanged={() => {}}/>); expect(old[1]!.signal!.aborted).toBe(true); await screen.findByText('另一报警固有快照', { selector: 'h2' }); await act(async () => resolve(response(history([{ ...created, remark: '前一个报警的迟到备注' }])))); expect(screen.queryByText('前一个报警的迟到备注')).not.toBeInTheDocument(); expect(screen.queryByText('不可变业务轨迹')).not.toBeInTheDocument()
  })
  it('撤销复核权限取消在途写入并清除备注，迟到成功不刷新；登出不留全局状态', async () => {
    login(); let resolve!: (value: Response) => void
    const fetcher = vi.fn().mockImplementation((url: string) => url.endsWith('/review') ? new Promise<Response>(r => { resolve = r }) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); const changed = vi.fn(); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={changed}/>); const user = userEvent.setup(); await beginReview(user, '撤权后不可保留的旧现场备注'); await user.click(screen.getByRole('button', { name: '提交复核结论' })); const call = fetcher.mock.calls.find(call => call[0].endsWith('/review'))!; act(() => useSession.setState({ permissions: ['alarm:read'] })); expect(call[1]!.signal!.aborted).toBe(true); expect(screen.queryByDisplayValue('撤权后不可保留的旧现场备注')).not.toBeInTheDocument(); await act(async () => resolve(response({ ...current, response_state: 'HANDLED' }))); expect(changed).not.toHaveBeenCalled(); act(() => useSession.getState().clear()); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(JSON.stringify(useSession.getState())).not.toContain('撤权后不可保留'); expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
  }, 20_000)
  it('当前GET403清全部旧详情和history，不退出合法会话或留下旧记录', async () => {
    login(); let deny = false
    const fetcher = vi.fn().mockImplementation((url: string) => url.endsWith(`/alarms/${current.id}`) && deny ? response({ code: 'PERMISSION_DENIED', message: '当前授权撤销', request_id: 'current-403' }, 403) : url.includes('/history?') ? response(history([{ ...created, remark: '将被清除的旧轨迹' }])) : baseFetch(url)); vi.stubGlobal('fetch', fetcher); render(<AlarmDetail alarmId={current.id} onClose={() => {}} onChanged={() => {}}/>); const user = userEvent.setup(); await openHistory(user); await screen.findByText('将被清除的旧轨迹'); deny = true; await user.click(screen.getByRole('button', { name: '刷新当前详情' })); await screen.findByText(/current-403/); expect(screen.queryByText('将被清除的旧轨迹')).not.toBeInTheDocument(); expect(screen.queryByRole('heading', { name: current.device_name!, level: 2 })).not.toBeInTheDocument(); expect(useSession.getState().token).toBe(management.access_token)
  })
})
