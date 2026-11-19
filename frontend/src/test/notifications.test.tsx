import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSession } from '@/lib/store'
import { allowedPage } from '@/lib/types'
import { notificationContact, notificationPhone, notificationRule } from '@/lib/notifications'
import { NotificationContactsPage } from '@/pages/notification-contacts'
import { management, customer, response } from './fixtures'
const grants = ['notification-config:read', 'notification-config:write']
const phone = '13812341234', clock = '2026-10-23T07:00:00.123456Z'
const contact = { id: 'nc-one', tenant_id: 'tenant-a', tenant_name: '单位A', name: '合成值班联系人', contact_type: 'DUTY_OFFICER', phone_masked: '138****1234', wechat_binding_id: null, enabled: true, revision: 0, created_at: clock }
const otherContact = { ...contact, id: 'nc-two', name: '合成第二联系人', phone_masked: '139****9876' }
const thirdContact = { ...contact, id: 'nc-three', name: '合成第三联系人', phone_masked: '137****0000' }
const fourthContact = { ...contact, id: 'nc-four', name: '合成第四联系人', phone_masked: '136****1111' }
const fifthContact = { ...contact, id: 'nc-five', name: '合成第五联系人', phone_masked: '135****2222' }
const sixthContact = { ...contact, id: 'nc-six', name: '合成第六联系人', phone_masked: '134****3333' }
const ruleFixture = { id: 'rule-one', tenant_id: 'tenant-a', tenant_name: '单位A', scope_type: 'UNIT' as const, scope_id: 'tenant-a', name: '合成规则', event_scope: 'FIRE' as const, enabled: true, revision: 2, created_at: clock, receivers: [{ channel: 'SMS' as const, contact_id: contact.id, contact_name: contact.name, phone_masked: contact.phone_masked, level: 1 }, { channel: 'VOICE' as const, contact_id: otherContact.id, contact_name: otherContact.name, phone_masked: otherContact.phone_masked, level: 1 }], sms_escalation_interval_minutes: null, voice_escalation_interval_minutes: null, wechat_escalation_interval_minutes: null }
const page = (items: unknown[], p = 1, total = items.length, size = 5) => ({ items, total, page: p, size })
function login(permissions = grants, audience: 'management' | 'customer' = 'management') { useSession.getState().login({ ...(audience === 'management' ? management : customer), permissions }, audience); if (audience === 'management') useSession.getState().setScope(['tenant-a'], [], true) }
function server(url: string, init?: RequestInit): Response {
  const parsed = new URL(url, 'http://local'), body = init?.body ? JSON.parse(String(init.body)) : null, pageNumber = Number(parsed.searchParams.get('page') ?? 1)
  const contacts = [contact, otherContact, thirdContact, fourthContact, fifthContact, sixthContact]
  if (parsed.pathname.endsWith('/scope-tree')) return response(useSession.getState().scopeTenantIds.map(id => ({ id, name: id === 'tenant-a' ? '单位A' : '单位B', places: [] })))
  if (parsed.pathname.endsWith('/notification-wechat/recipients')) return response([])
  if (parsed.pathname.endsWith('/notification-contacts')) return body ? response({ ...contact, name: body.name, enabled: body.enabled, contact_type: body.contact_type }) : response(page(contacts.slice((pageNumber - 1) * 2, pageNumber * 2), pageNumber, contacts.length, 2))
  if (parsed.pathname.endsWith(`/notification-contacts/${contact.id}`)) return response(body ? { ...contact, name: body.name, enabled: body.enabled, contact_type: body.contact_type } : contact)
  if (parsed.pathname.endsWith('/notification-rules/targets')) return response({ ...page([], pageNumber, 0, 20), tenant_id: new Headers(init?.headers).get('X-Tenant-Id'), scope_type: parsed.searchParams.get('scopeType'), access: 'ALL' })
  if (parsed.pathname.endsWith('/notification-rules')) {
    if (!body) return response(page([ruleFixture], pageNumber, 1, 20))
    const lookup = new Map(contacts.map(item => [item.id, item]))
    return response({ id: 'rule-one', tenant_id: 'tenant-a', tenant_name: '单位A', scope_type: body.scope_type, scope_id: body.scope_id, name: body.name, event_scope: body.event_scope, enabled: body.enabled, revision: body.expected_revision ?? 0, created_at: clock, receivers: body.receivers.map((receiver: { channel: 'SMS' | 'VOICE'; contact_id: string; level: number }) => ({ ...receiver, contact_name: lookup.get(receiver.contact_id)?.name ?? '', phone_masked: lookup.get(receiver.contact_id)?.phone_masked ?? '', wechat_binding_id: null })), sms_escalation_interval_minutes: body.sms_escalation_interval_minutes, voice_escalation_interval_minutes: body.voice_escalation_interval_minutes, wechat_escalation_interval_minutes: body.wechat_escalation_interval_minutes })
  }
  if (parsed.pathname.endsWith('/notification-rules/rule-one')) return response(ruleFixture)
  throw new Error(`Unexpected notification endpoint ${url}`)
}
function setup(override?: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined, permissions = grants) { login(permissions); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => override?.(url, init) ?? server(url, init)); vi.stubGlobal('fetch', fetcher); render(<NotificationContactsPage/>); return fetcher }
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const writes = (fetcher: ReturnType<typeof vi.fn>) => fetcher.mock.calls.filter(call => ['POST', 'PUT'].includes(call[1]?.method))
async function confirm(user: ReturnType<typeof userEvent.setup>, first: string, final: string) { await user.click(screen.getByRole('button', { name: first })); await user.click(screen.getByRole('button', { name: final })) }
beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })
async function openRules() { await userEvent.click(screen.getByRole('tab', { name: '报警与故障通知规则' })) }

describe('真实联系人资料与权限', () => {
  it('客户无管理联系资料权限，号码校验拒绝不完整信息', () => { expect(allowedPage('notification-contacts', 'customer', grants)).toBe(false); expect(() => notificationPhone('01012345678', true)).toThrow() })
  it('只解析有真实单位归属的掩码联系人，不返回手机号或额外敏感字段', () => { const safe = notificationContact({ ...contact, phone, ciphertext: 'private-cipher'}); expect(JSON.stringify(safe)).not.toContain(phone); expect(JSON.stringify(safe)).not.toContain('private-'); expect(() => notificationContact({ ...contact, tenant_id: undefined, tenant_name: undefined })).toThrow() })
  it('新建联系人默认停用，直接保存人工资料且不回显电话', async () => { const fetcher = setup(); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '创建通知联系人' })); expect(screen.getByLabelText('联系人类型')).toHaveValue(''); expect(screen.getByLabelText('联系电话')).toHaveValue(''); expect(screen.getByLabelText('启用联系人（创建默认未启用）')).not.toBeChecked(); change('联系人名称', '新合成联系人'); await user.selectOptions(screen.getByLabelText('联系人类型'), 'DUTY_OFFICER'); change('联系电话', phone); change('操作原因', '人工资料维护'); await user.click(screen.getByRole('button', { name: '创建联系人' })); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); const [call] = writes(fetcher), body = JSON.parse(call[1]!.body as string); expect(body).toEqual({ name: '新合成联系人', contact_type: 'DUTY_OFFICER', phone, enabled: false, reason: '人工资料维护'})
  })
  it('新建即启用联系人必须核对后才创建，核对前不写入', async () => { const fetcher = setup(); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '创建通知联系人' })); await screen.findByRole('option', { name: '单位A' }); await user.selectOptions(screen.getByLabelText('联系人所属单位'), 'tenant-a'); change('联系人名称', '启用新联系人'); await user.selectOptions(screen.getByLabelText('联系人类型'), 'OTHER'); change('联系电话', phone); change('操作原因', '新增即启用'); await user.click(screen.getByRole('checkbox', { name: /启用联系人/ })); await user.click(screen.getByRole('button', { name: '核对联系人变更' })); expect(writes(fetcher)).toHaveLength(0); expect(screen.getByText('启用状态：启用')).toBeInTheDocument(); await user.click(screen.getByRole('button', { name: '确认创建联系人' })); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); expect(writes(fetcher)).toHaveLength(1); expect(JSON.parse(writes(fetcher)[0][1]!.body as string)).toMatchObject({ phone, enabled: true, reason: '新增即启用' }); expect(JSON.parse(writes(fetcher)[0][1]!.body as string)).not.toHaveProperty('expected_revision') })
  it('多单位筛选不猜写入目标，联系人提交使用明确归属', async () => {
    const fetcher = setup(), user = userEvent.setup()
    act(() => useSession.getState().setScope(['tenant-a', 'tenant-b'], []))
    await user.click(screen.getByRole('button', { name: '创建通知联系人' }))
    const unit = await screen.findByLabelText('联系人所属单位')
    expect(unit).toHaveValue('')
    await screen.findByRole('option', { name: '单位A' })
    await user.selectOptions(unit, 'tenant-a')
    change('联系人名称', '明确归属的联系人')
    await user.selectOptions(screen.getByLabelText('联系人类型'), 'DUTY_OFFICER')
    change('联系电话', phone)
    change('操作原因', '明确本次联系人归属')
    await user.click(screen.getByRole('button', { name: '创建联系人' }))
    await waitFor(() => expect(writes(fetcher)).toHaveLength(1))
    expect((writes(fetcher)[0][1]!.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a')
  })
  it('编辑从服务端读revision，留空号码保留不发送遮罩值', async () => { const fetcher = setup(); const user = userEvent.setup(); const row = await screen.findByText(contact.name); await user.click(within(row.closest('tr')!).getByRole('button', { name: '编辑联系人' })); await screen.findByLabelText('替换号码（留空保留）'); change('联系人名称', '新显示名称'); change('操作原因', '只改名称'); await user.click(screen.getByRole('button', { name: '保存联系人' })); await waitFor(() => expect(writes(fetcher)).toHaveLength(1)); const body = JSON.parse(writes(fetcher)[0][1]!.body as string); expect(body.phone).toBeNull(); expect(body.expected_revision).toBe(0); expect(JSON.stringify(body)).not.toContain(contact.phone_masked) })
  it('为已启用联系人更换号码必须核对，核对前不写入且不回显新号码', async () => { const fetcher = setup(); const user = userEvent.setup(); const row = await screen.findByText(contact.name); await user.click(within(row.closest('tr')!).getByRole('button', { name: '编辑联系人' })); await screen.findByLabelText('替换号码（留空保留）'); change('替换号码（留空保留）', phone); change('操作原因', '值班人换号'); await user.click(screen.getByRole('button', { name: '核对联系人变更' })); expect(writes(fetcher)).toHaveLength(0); expect(screen.getByText('电话号码：已填写新号码（不回显）')).toBeInTheDocument(); expect(document.body.textContent).not.toContain(phone); await user.click(screen.getByRole('button', { name: '确认保存联系人' })); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); expect(writes(fetcher)).toHaveLength(1); const body = JSON.parse(writes(fetcher)[0][1]!.body as string); expect(body).toMatchObject({ phone, enabled: true, expected_revision: 0 }); expect((writes(fetcher)[0][1]!.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a') })
  it('停用已启用联系人先核对再提交，普通名称修改不触发核对', async () => { const fetcher = setup(); const user = userEvent.setup(); const row = await screen.findByText(contact.name); await user.click(within(row.closest('tr')!).getByRole('button', { name: '编辑联系人' })); await screen.findByLabelText('替换号码（留空保留）'); change('操作原因', '停用改由他人值班'); await user.click(screen.getByRole('checkbox', { name: /启用联系人/ })); await user.click(screen.getByRole('button', { name: '核对联系人变更' })); expect(writes(fetcher)).toHaveLength(0); expect(screen.getByText('启用状态：停用')).toBeInTheDocument(); await user.click(screen.getByRole('button', { name: '确认保存联系人' })); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); const body = JSON.parse(writes(fetcher)[0][1]!.body as string); expect(body).toMatchObject({ enabled: false, phone: null }); expect(body).not.toHaveProperty('wechat_binding_id') })
  it('只读管理权限可检查规则但不显示写入入口', async () => {
    const fetcher = setup(undefined, ['notification-config:read']), user = userEvent.setup(); await openRules(); const row = await screen.findByText(/合成规则/)
    expect(screen.queryByRole('button', { name: '新建收件规则' })).not.toBeInTheDocument()
    await user.click(within(row.closest('tr')!).getByRole('button', { name: '查看规则' }))
    const name = await screen.findByLabelText('规则名称')
    expect(name).toBeDisabled()
    expect(screen.queryByRole('button', { name: '核对规则变更' })).not.toBeInTheDocument()
    expect(writes(fetcher)).toHaveLength(0)
  })
  it('编辑读取记录所属单位并以详情revision执行CAS', async () => {
    const fetcher = setup(), user = userEvent.setup(); await openRules(); const row = await screen.findByText(/合成规则/)
    await user.click(within(row.closest('tr')!).getByRole('button', { name: '编辑规则' }))
    await waitFor(() => expect(screen.getByLabelText('规则名称')).toBeEnabled())
    change('规则名称', '编辑后的真实规则')
    change('本次操作原因', '明确更新真实收件策略')
    await confirm(user, '核对规则变更', '确认保存规则')
    await waitFor(() => expect(writes(fetcher)).toHaveLength(1))
    const [call] = writes(fetcher), body = JSON.parse(call[1]!.body as string)
    expect(call[1]!.method).toBe('PUT')
    expect(new URL(call[0], 'http://local').pathname).toBe('/api/management/notification-rules/rule-one')
    expect((call[1]!.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a')
    expect(body).toMatchObject({ name: '编辑后的真实规则', expected_revision: 2, reason: '明确更新真实收件策略' })
    expect(body.receivers).toEqual([{ channel: 'SMS', contact_id: contact.id, level: 1 }, { channel: 'VOICE', contact_id: otherContact.id, level: 1 }])
    expect(JSON.stringify(body)).not.toContain(contact.phone_masked)
    const detail = fetcher.mock.calls.find(([url, init]) => String(url).endsWith('/notification-rules/rule-one') && init?.method !== 'PUT')
    expect(detail).toBeDefined()
  })
  it('创建真实收件规则明确选单位/启用联系人，翻页保留各级选择且只提交contact_id', async () => {
    const fetcher = setup(), user = userEvent.setup(); await openRules()
    await user.click(await screen.findByRole('button', { name: '新建收件规则' }))
    const unit = await screen.findByLabelText('规则所属单位')
    expect(unit).toHaveValue('')
    await user.selectOptions(unit, 'tenant-a')
    const sms = screen.getByRole('region', { name: '短信收件人' }), voice = screen.getByRole('region', { name: '语音收件人' })
    await within(within(sms).getByLabelText('1级联系人')).findByRole('option', { name: /合成值班联系人/ })
    await user.selectOptions(within(sms).getByLabelText('1级联系人'), contact.id)
    await user.click(within(sms).getByRole('button', { name: '增加短信级别' }))
    await user.selectOptions(within(sms).getByLabelText('2级联系人'), otherContact.id)
    await user.selectOptions(within(voice).getByLabelText('1级联系人'), contact.id)
    await user.click(within(sms).getByRole('button', { name: 'Next Page' }))
    await within(within(sms).getByLabelText('1级联系人')).findByRole('option', { name: /合成第三联系人/ })
    expect(within(sms).getByLabelText('1级联系人')).toHaveValue(contact.id)
    expect(within(sms).getByLabelText('2级联系人')).toHaveValue(otherContact.id)
    expect(within(voice).getByLabelText('1级联系人')).toHaveValue(contact.id)
    change('规则名称', '真实报警分级收件')
    await user.click(screen.getByRole('checkbox', { name: /启用真实火警通知规则/ }))
    change('本次操作原因', '明确配置真实报警收件人')
    await confirm(user, '核对规则变更', '确认保存规则')
    await waitFor(() => expect(writes(fetcher)).toHaveLength(1))
    const [call] = writes(fetcher), body = JSON.parse(call[1]!.body as string)
    expect(new URL(call[0], 'http://local').pathname).toBe('/api/management/notification-rules')
    expect(body).toMatchObject({ name: '真实报警分级收件', scope_type: 'UNIT', scope_id: 'tenant-a', enabled: true, sms_escalation_interval_minutes: 0, voice_escalation_interval_minutes: null, reason: '明确配置真实报警收件人'})
    expect(body.receivers).toEqual([{ channel: 'SMS', level: 1, contact_id: contact.id }, { channel: 'SMS', level: 2, contact_id: otherContact.id }, { channel: 'VOICE', level: 1, contact_id: contact.id }])
    expect(body).not.toHaveProperty('expected_revision')
    const serialized = JSON.stringify(body)
    expect(serialized).not.toMatch(/phone_masked|contact_name|sms_template|max_attempts|retry_delay/)
    expect(serialized).not.toContain(contact.phone_masked)
    expect(serialized).not.toContain(otherContact.phone_masked)
    expect(fetcher.mock.calls.some(([url]) => /notification-provider.*test|notification.*send/.test(String(url)))).toBe(false)
  })
  it('规则响应校验真实双渠道连续级别并忽略已退役配置字段', () => {
    const dto = notificationRule({ ...ruleFixture, id: 'rule-read', name: '真实规则', revision: 3, mode: 'SIMULATION', sms_template_id: 'retired' })
    expect(dto).not.toHaveProperty('mode')
    expect(() => notificationRule({ ...dto, tenant_id: undefined, tenant_name: undefined })).toThrow()
    expect(() => notificationRule({ ...dto, receivers: [{ ...dto.receivers[0], level: 2 }, dto.receivers[1]] })).toThrow()
    expect(() => notificationRule({ ...dto, scope_type: undefined })).toThrow()
    expect(() => notificationRule({ ...dto, scope_id: 'other-unit' })).toThrow()
  })
  it('权限被拒绝时不保留电话草稿，也不显示为已保存', async () => { setup((url, init) => init?.method === 'POST' && url.endsWith('/notification-contacts') ? response({ code: 'FORBIDDEN', message: '没有此操作权限', request_id: 'notif403' }, 403) : undefined); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '创建通知联系人' })); change('联系人名称', '合成联系'); await user.selectOptions(screen.getByLabelText('联系人类型'), 'OTHER'); change('联系电话', phone); change('操作原因', '资料维护'); await user.click(screen.getByRole('button', { name: '创建联系人' })); expect(await screen.findByRole('alert')).toHaveTextContent('没有此操作权限'); expect(screen.getByLabelText('联系电话')).toHaveValue(''); expect(screen.getByRole('dialog')).toBeInTheDocument() })
  it('授权读取范围切换中止旧联系人列表请求', async () => { let resolve!: (value: Response) => void; const fetcher = setup(url => url.includes('/notification-contacts?') ? new Promise<Response>(done => { resolve = done }) : undefined); act(() => useSession.getState().setScope(['tenant-b'], [], true)); expect(fetcher.mock.calls.some(call => call[1]?.signal?.aborted)).toBe(true); await act(async () => resolve(response(page([otherContact])))) })
})
