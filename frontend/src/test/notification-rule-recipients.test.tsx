import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationRules } from '@/components/notification-rules'
import { CustomerNotificationSettings } from '@/components/customer-notification-settings'
import { useSession } from '@/lib/store'
import type { NotificationRule, NotificationRuleInput } from '@/lib/notifications'
import { customer, management, response } from './fixtures'

const clock = '2026-11-12T00:00:00Z'
const active = ['一', '二'].map((suffix, index) => ({ id: `active-b-${index}`, tenant_id: 'tenant-b', tenant_name: '单位B', name: `单位B启用联系人${suffix}`, contact_type: 'DUTY_OFFICER', phone_masked: `138****000${index}`, wechat_binding_id: null, enabled: true, revision: 0, created_at: clock }))
const disabled = Array.from({ length: 20 }, (_, index) => ({ ...active[0], id: `disabled-${index}`, name: `较新停用联系人${index}`, enabled: false }))
const existing: NotificationRule = { id: 'rule-b', tenant_id: 'tenant-b', tenant_name: '单位B', scope_type: 'PLACE', scope_id: 'place-b', name: '原单位B故障规则', event_scope: 'FAULT', enabled: true, revision: 4, created_at: clock, receivers: [{ channel: 'SMS', level: 1, contact_id: active[0].id, contact_name: active[0].name, phone_masked: active[0].phone_masked }, { channel: 'VOICE', level: 1, contact_id: active[1].id, contact_name: active[1].name, phone_masked: active[1].phone_masked }], sms_escalation_interval_minutes: 0, voice_escalation_interval_minutes: 0, wechat_escalation_interval_minutes: null }
beforeEach(() => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  useSession.getState().clear(); localStorage.clear(); sessionStorage.clear()
  useSession.getState().login({ ...management, permissions: ['notification-config:read', 'notification-config:write'] }, 'management')
  useSession.getState().setScope(['tenant-a', 'tenant-b'], [], true)
})
function setup(options: { hold?: boolean; wrongDetail?: 'unit' | 'id' | 'scope'; access?: 'ALL' | 'DEVICE_ONLY' | 'NONE'; existingRule?: NotificationRule } = {}) {
  const state = { rule: structuredClone(options.existingRule ?? existing), writes: 0, unitAWrites: 0, previews: 0, submitted: undefined as NotificationRuleInput | undefined, resolve: undefined as ((reply: Response) => void) | undefined }
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const parsed = new URL(url, 'http://local'), path = parsed.pathname, tenant = new Headers(init?.headers).get('X-Tenant-Id')
    if (path.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places: [] }, { id: 'tenant-b', name: '单位B', places: [] }])
    if (path.endsWith('/notification-rules/targets')) {
      const access = tenant === 'tenant-b' ? options.access ?? 'ALL' : 'NONE'
      const type = parsed.searchParams.get('scopeType') ?? 'DEVICE'
      const candidate = type === 'PLACE' ? { id: 'place-b', name: '位置B' } : { id: 'device-b', name: '单位B设备' }
      const targetId = parsed.searchParams.get('targetId')
      const items = type !== 'UNIT' && access !== 'NONE' && (type !== 'PLACE' || access === 'ALL') && (!targetId || candidate.id === targetId) ? [candidate] : []
      return response({ tenant_id: tenant, scope_type: type, access, items, total: items.length, page: 1, size: 20 })
    }
    if (path.endsWith('/notification-wechat/recipients')) return response([])
    if (path.endsWith('/notification-wechat/channel')) return response({ configured: false, enabled: false, ready: false, fire_template_ready: false, fault_template_ready: false, active_recipients: 0, message: '未配置公众号，短信语音可独立设置' })
    if (path.endsWith('/notification-contacts')) {
      if (tenant !== 'tenant-b') return response({ items: [], total: 0, page: 1, size: 20 })
      const keyword = parsed.searchParams.get('keyword') ?? '', page = Number(parsed.searchParams.get('page') ?? 1)
      const candidates = [...disabled, ...active].filter(item => (parsed.searchParams.get('enabled') !== 'true' || item.enabled) && (!keyword || item.name.includes(keyword)))
      return response({ items: candidates.slice((page - 1) * 20, page * 20), total: candidates.length, page, size: 20 })
    }
    if ((path.endsWith('/notification-rules') && init?.method === 'POST') || (path.endsWith('/notification-rules/rule-b') && init?.method === 'PUT')) {
      if (tenant !== 'tenant-b') { state.unitAWrites++; return response({ code: 'WRONG_UNIT' }, 404) }
      const body = JSON.parse(String(init.body)) as typeof state.submitted & {}
      state.submitted = body
      if (init?.method === 'PUT' && (body.scope_type !== state.rule.scope_type || body.scope_id !== state.rule.scope_id)) return response({ code: 'RULE_SCOPE_IMMUTABLE' }, 400)
      if (init?.method === 'POST' && body.scope_type && body.scope_id) state.rule = { ...state.rule, scope_type: body.scope_type, scope_id: body.scope_id }
      if (init?.method === 'PUT' && body.expected_revision !== state.rule.revision) return response({ code: 'REVISION_CONFLICT' }, 409)
      if (body.receivers.some(receiver => !active.some(contact => contact.id === receiver.contact_id))) return response({ code: 'CONTACT_NOT_ENABLED' }, 400)
      state.rule = { ...state.rule, name: body.name, enabled: body.enabled, event_scope: body.event_scope, revision: init?.method === 'POST' ? 0 : state.rule.revision + 1, sms_escalation_interval_minutes: body.sms_escalation_interval_minutes, voice_escalation_interval_minutes: body.voice_escalation_interval_minutes, wechat_escalation_interval_minutes: body.wechat_escalation_interval_minutes, receivers: body.receivers.map(receiver => { const contact = active.find(item => item.id === receiver.contact_id)!; return { ...receiver, contact_name: contact.name, phone_masked: contact.phone_masked } }) }
      state.writes++; return response(state.rule, init?.method === 'POST' ? 201 : 200)
    }
    if (path.endsWith('/notification-rules/effective')) {
      state.previews++
      if (tenant !== 'tenant-b' || parsed.searchParams.get('deviceId') !== 'device-b' || parsed.searchParams.get('eventScope') !== 'FAULT') return response({ code: 'WRONG_TARGET' }, 400)
      const receiver = { contact_id: active[0].id, contact_name: active[0].name, phone_masked: active[0].phone_masked, channel: 'SMS', level: 2, delay_seconds: 180 }
      return response({ tenant_id: tenant, device_id: 'device-b', event_scope: 'FAULT', receivers: [{ ...receiver, source_scope_type: 'PLACE', source_scope_id: 'place-b', source_rule_id: 'rule-place', source_revision: 4 }, { ...receiver, contact_id: active[1].id, contact_name: active[1].name, channel: 'VOICE', level: 1, delay_seconds: 0, source_scope_type: 'UNIT', source_scope_id: 'tenant-b', source_rule_id: 'rule-unit', source_revision: 2 }] })
    }
    if (path.endsWith('/notification-rules')) {
      const type = parsed.searchParams.get('scopeType'), id = parsed.searchParams.get('scopeId')
      const items = type && (tenant !== 'tenant-b' || type !== state.rule.scope_type || id !== state.rule.scope_id) ? [] : [state.rule]
      return response({ items, total: items.length, page: 1, size: 20 })
    }
    if (path.endsWith('/notification-rules/rule-b')) {
      if (tenant !== 'tenant-b') return response({ code: 'WRONG_UNIT' }, 404)
      if (options.hold) return new Promise<Response>(resolve => { state.resolve = resolve })
      return response({ ...state.rule, ...(options.wrongDetail === 'unit' ? { tenant_id: 'tenant-c' } : options.wrongDetail === 'id' ? { id: 'other-rule' } : options.wrongDetail === 'scope' ? { scope_id: 'other-place' } : {}) })
    }
    throw new Error(`Unexpected rule request: ${url}`)
  }))
  render(<NotificationRules canRead canWrite />)
  return state
}
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
async function create() {
  await userEvent.click(await screen.findByRole('button', { name: '新建收件规则' }))
  const unit = await within(screen.getByRole('dialog')).findByLabelText('规则所属单位')
  await within(unit).findByRole('option', { name: '单位B · tenant-b' })
  await userEvent.selectOptions(unit, 'tenant-b')
}
async function edit() {
  const row = (await screen.findByText(/故障 FAULT · 原单位B故障规则/)).closest('tr')!
  await userEvent.click(within(row).getByRole('button', { name: '编辑规则' }))
}
async function confirm() {
  change('本次操作原因', '隔离验收规则资料，不发送通知')
  await userEvent.click(screen.getByRole('button', { name: '核对规则变更' }))

  await userEvent.click(screen.getByRole('button', { name: '确认保存规则' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
}
describe('报警故障规则启用联系人与目标隔离', () => {
  it('较新停用联系人占满一页时，仍能直接选到已启用联系人', async () => {
    setup(); await create()
    const sms = within(screen.getByRole('region', { name: '短信收件人' }))
    expect(await sms.findByRole('option', { name: `${active[0].name} · ${active[0].phone_masked}` })).toBeInTheDocument()
    expect(sms.queryByRole('option', { name: /较新停用联系人/ })).not.toBeInTheDocument()
  })
  it('显式新建故障规则，短信两级与语音一级各自保留且不要求公众号', async () => {
    const state = setup(); await create()
    await screen.findAllByRole('option', { name: `${active[0].name} · ${active[0].phone_masked}` })
    await within(screen.getByLabelText('规则作用域')).findByRole('option', { name: '设备 DEVICE' })
    change('规则名称', '单位B故障升级'); await userEvent.selectOptions(screen.getByLabelText('报警事件类型'), 'FAULT')
    await userEvent.selectOptions(screen.getByLabelText('规则作用域'), 'DEVICE')
    await userEvent.selectOptions(await within(screen.getByRole('dialog')).findByLabelText('设备 ID'), 'device-b')
    act(() => useSession.getState().setScope(['tenant-a'], [], true))
    const sms = within(screen.getByRole('region', { name: '短信收件人' })), voice = within(screen.getByRole('region', { name: '语音收件人' }))
    await userEvent.selectOptions(sms.getByLabelText('1级联系人'), active[0].id)
    await userEvent.click(sms.getByRole('button', { name: '增加短信级别' }))
    await userEvent.selectOptions(sms.getByLabelText('2级联系人'), active[1].id)
    fireEvent.change(sms.getByLabelText('多级升级间隔（分钟）'), { target: { value: '5' } })
    await userEvent.selectOptions(voice.getByLabelText('1级联系人'), active[1].id)
    await confirm()
    expect(state.rule.event_scope).toBe('FAULT'); expect(state.rule.enabled).toBe(false)
    expect(state.submitted).toMatchObject({ scope_type: 'DEVICE', scope_id: 'device-b' })
    expect(state.rule.receivers.map(({ channel, level, contact_id }) => ({ channel, level, contact_id }))).toEqual([{ channel: 'SMS', level: 1, contact_id: active[0].id }, { channel: 'SMS', level: 2, contact_id: active[1].id }, { channel: 'VOICE', level: 1, contact_id: active[1].id }])
    expect(state.rule.sms_escalation_interval_minutes).toBe(5); expect(state.rule.voice_escalation_interval_minutes).toBeNull(); expect(state.rule.wechat_escalation_interval_minutes).toBeNull()
    expect(state.unitAWrites).toBe(0)
  })
  it.each(['rename', 'disable'])('%s 使用原单位和当前版本，不改变故障类型或收件人', async operation => {
    const state = setup(); await edit(); await waitFor(() => expect(screen.getByLabelText('规则名称')).toBeEnabled())
    expect(within(screen.getByRole('dialog')).getByText(/位置 PLACE · place-b/)).toBeInTheDocument()
    expect(screen.queryByLabelText('规则作用域')).not.toBeInTheDocument()
    act(() => useSession.getState().setScope(['tenant-a'], [], true))
    if (operation === 'rename') change('规则名称', '仅修改单位B故障规则名称')
    else await userEvent.click(screen.getByRole('checkbox', { name: /启用真实故障通知规则/ }))
    await confirm()
    expect(state.rule.event_scope).toBe('FAULT'); expect(state.rule.revision).toBe(5)
    expect(state.submitted).toMatchObject({ scope_type: 'PLACE', scope_id: 'place-b', expected_revision: 4 })
    expect(state.rule.enabled).toBe(operation !== 'disable')
    expect(state.rule.name).toBe(operation === 'rename' ? '仅修改单位B故障规则名称' : existing.name)
    expect(state.rule.receivers).toEqual(existing.receivers); expect(state.unitAWrites).toBe(0)
  })
  it('联系人搜索回车不进入规则保存确认，明确确认后才保存', async () => {
    const state = setup(); await edit(); await waitFor(() => expect(screen.getByLabelText('规则名称')).toBeEnabled())
    change('本次操作原因', '只查询联系人，不提交规则')
    const search = within(screen.getByRole('region', { name: '短信收件人' })).getByLabelText('搜索本单位联系人')
    await userEvent.click(search); await userEvent.keyboard('{Enter}')
    expect(screen.queryByRole('button', { name: '确认保存规则' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('本次操作原因')).toHaveValue('只查询联系人，不提交规则')
    expect(state.writes).toBe(0)
    await confirm()
    expect(state.writes).toBe(1); expect(state.rule.revision).toBe(existing.revision + 1)
  })
  it('明确关闭规则详情后，迟到响应不能重新打开编辑', async () => {
    const state = setup({ hold: true }); await edit(); await waitFor(() => expect(state.resolve).toBeTypeOf('function'))
    await userEvent.click(screen.getByLabelText('关闭'))
    await act(async () => state.resolve!(response(existing)))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(state.writes).toBe(0)
  })
  it.each(['unit', 'id', 'scope'] as const)('详情的 %s 与原选择不匹配时不允许编辑', async wrongDetail => {
    const state = setup({ wrongDetail }); await edit(); await screen.findByRole('alert')
    expect(screen.queryByLabelText('规则名称')).not.toBeInTheDocument(); expect(state.writes).toBe(0)
  })
  it('精确过滤规则，并分别显示设备生效联系人实际来源和延时', async () => {
    setup()
    const filters = within(screen.getByRole('form', { name: '筛选收件规则' }))
    const unit = filters.getByLabelText('筛选单位')
    await within(unit).findByRole('option', { name: '单位B · tenant-b' })
    await userEvent.selectOptions(unit, 'tenant-b')
    await userEvent.selectOptions(filters.getByLabelText('筛选规则作用域'), 'PLACE')
    fireEvent.change(filters.getByLabelText('位置 ID'), { target: { value: 'other-place' } })
    await userEvent.click(filters.getByRole('button', { name: '查询' }))
    expect(await screen.findByText('暂无真实报警收件规则')).toBeInTheDocument()

    const preview = within(screen.getByRole('region', { name: '设备生效联系人' }))
    await userEvent.selectOptions(await preview.findByLabelText('查询单位'), 'tenant-b')
    await userEvent.selectOptions(await preview.findByLabelText('设备 ID'), 'device-b')
    await userEvent.selectOptions(preview.getByLabelText('事件类型'), 'FAULT')
    await userEvent.click(preview.getByRole('button', { name: '查看生效联系人' }))
    const sms = within(await preview.findByRole('row', { name: new RegExp(active[0].name) }))
    expect(sms.getByText('180 秒')).toBeInTheDocument()
    expect(sms.getByText(/位置 PLACE · place-b/)).toBeInTheDocument()
    expect(sms.getByText(/rule-place.*revision 4/)).toBeInTheDocument()
    const voice = within(preview.getByRole('row', { name: new RegExp(active[1].name) }))
    expect(voice.getByText('0 秒')).toBeInTheDocument()
    expect(voice.getByText(/单位 UNIT · tenant-b/)).toBeInTheDocument()
  })
  it('仅设备范围可选择已授权设备，不提供单位或位置写目标', async () => {
    setup({ access: 'DEVICE_ONLY' }); await create()
    const scopes = await screen.findByLabelText('规则作用域')
    await within(scopes).findByRole('option', { name: '设备 DEVICE' })
    expect(within(scopes).queryByRole('option', { name: '单位 UNIT' })).not.toBeInTheDocument()
    expect(within(scopes).queryByRole('option', { name: '位置 PLACE' })).not.toBeInTheDocument()
    await userEvent.selectOptions(scopes, 'DEVICE')
    const targets = await within(screen.getByRole('dialog')).findByLabelText('设备 ID')
    await userEvent.selectOptions(targets, await within(targets).findByRole('option', { name: '单位B设备 · device-b' }))
    expect(targets).toHaveValue('device-b')
  })

  it('已授权的设备规则可以保留只有短信的配置并修改名称', async () => {
    const rule = { ...existing, scope_type: 'DEVICE' as const, scope_id: 'device-b', receivers: [existing.receivers[0]] }
    const state = setup({ access: 'DEVICE_ONLY', existingRule: rule })
    await edit()
    await waitFor(() => expect(screen.getByLabelText('规则名称')).toBeEnabled())
    change('规则名称', '保留仅短信的设备规则')
    await confirm()
    expect(state.submitted).toMatchObject({ name: '保留仅短信的设备规则', scope_type: 'DEVICE', scope_id: 'device-b', expected_revision: 4,
      receivers: [{ channel: 'SMS', level: 1, contact_id: active[0].id }] })
  })

  it('无设备范围时不提交预览或新规则，仍可以查看已有单位规则', async () => {
    const state = setup({ access: 'NONE', existingRule: { ...existing, scope_type: 'UNIT', scope_id: 'tenant-b' } })
    const preview = within(screen.getByRole('region', { name: '设备生效联系人' }))
    const unit = await preview.findByLabelText('查询单位')
    await within(unit).findByRole('option', { name: '单位B · tenant-b' })
    await userEvent.selectOptions(unit, 'tenant-b')
    expect(await preview.findByText(/无可用设备范围/)).toBeInTheDocument()
    await userEvent.click(preview.getByRole('button', { name: '查看生效联系人' }))
    expect(state.previews).toBe(0)

    await create()
    const dialog = within(screen.getByRole('dialog'))
    expect(await dialog.findByText(/无可用设备范围/)).toBeInTheDocument()
    expect(dialog.getByRole('button', { name: '核对规则变更' })).toBeDisabled()
    await userEvent.click(dialog.getByLabelText('关闭'))
    await edit()
    expect(await within(screen.getByRole('dialog')).findByText(/无可用设备范围/)).toBeInTheDocument()
    expect(screen.getByLabelText('规则名称')).toBeDisabled()
    expect(state.writes).toBe(0)
  })
})

describe('客户本单位通知规则安全维护', () => {
  it('第二页搜索回车只查询并重置页码，保留跨页收件人与草稿，明确保存才生效', async () => {
    useSession.getState().login({ ...customer, permissions: ['unit-notifications:read', 'unit-notifications:write'] }, 'customer')
    const contacts = [...disabled, ...active].map(item => ({ ...item, tenant_id: 'tenant-a', tenant_name: '单位A', name: item.name.replace('单位B', '本单位') }))
    const state = { writes: 0, attempts: [] as { key: string; body: string }[], queries: [] as { page: number; keyword: string }[], rule: { ...structuredClone(existing), tenant_id: 'tenant-a', tenant_name: '单位A', scope_id: 'place-a' } }
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      const parsed = new URL(url, 'http://local'), path = parsed.pathname
      if (path.endsWith('/data-transfer/kinds')) return response({ kinds: [], protected_storage_ready: true })
      if (path.endsWith('/notification-wechat/recipients')) return response([])
      if (path.endsWith('/notification-wechat/channel')) return response({ configured: false, enabled: false, ready: false, fire_template_ready: false, fault_template_ready: false, active_recipients: 0, message: '未配置公众号' })
      if (path.endsWith('/notification-settings/contacts')) {
        const page = Number(parsed.searchParams.get('page') ?? 1), keyword = parsed.searchParams.get('keyword') ?? ''
        state.queries.push({ page, keyword })
        const matches = contacts.filter(item => !keyword || item.name.includes(keyword))
        return response({ items: matches.slice((page - 1) * 20, page * 20), total: matches.length, page, size: 20 })
      }
      if (path.endsWith('/notification-settings/rules/rule-b') && init?.method === 'PUT') {
        const input = JSON.parse(String(init.body)) as NotificationRuleInput
        expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull()
        const key = new Headers(init.headers).get('Idempotency-Key') ?? ''
        if (!key || !input.reason || input.scope_type !== 'PLACE' || input.scope_id !== 'place-a') return response({ code: 'WRONG_TARGET' }, 400)
        state.attempts.push({ key, body: String(init.body) })
        if (state.attempts.length === 1) return response({ code: 'UPSTREAM_UNAVAILABLE' }, 503)
        if (input.expected_revision !== state.rule.revision) return response({ code: 'REVISION_CONFLICT' }, 409)
        state.writes++
        state.rule = { ...state.rule, name: input.name, enabled: input.enabled, event_scope: input.event_scope, revision: state.rule.revision + 1, receivers: input.receivers.map(receiver => { const contact = contacts.find(item => item.id === receiver.contact_id)!; return { ...receiver, contact_name: contact.name, phone_masked: contact.phone_masked } }) }
        return response(state.rule)
      }
      if (path.endsWith('/notification-settings/rules')) return response({ items: [state.rule], total: 1, page: 1, size: 20 })
      throw new Error(`Unexpected customer notification request: ${url}`)
    }))
    render(<CustomerNotificationSettings/>)
    const row = (await screen.findByText(/故障 FAULT · 原单位B故障规则/)).closest<HTMLDivElement>('.field')!
    await userEvent.click(within(row).getByRole('button'))
    const dialog = within(await screen.findByRole('dialog', { name: '编辑收件规则' }))
    const directory = within(dialog.getByRole('region', { name: '联系人目录' }))
    await userEvent.click(await directory.findByRole('button', { name: /2/ }))
    const sms = within(dialog.getByRole('region', { name: '短信收件人' })), voice = within(dialog.getByRole('region', { name: '语音收件人' }))
    await sms.findByRole('option', { name: `${contacts[20].name} · ${contacts[20].phone_masked}` })
    await userEvent.selectOptions(sms.getByLabelText('1级联系人'), contacts[20].id)
    await userEvent.selectOptions(voice.getByLabelText('1级联系人'), contacts[21].id)
    fireEvent.change(dialog.getByLabelText('规则名称'), { target: { value: '客户故障规则草稿' } })
    fireEvent.change(dialog.getByLabelText('变更原因'), { target: { value: '隔离验收，保存不发送通知' } })
    await userEvent.type(directory.getByRole('textbox', { name: '搜索可收件联系人' }), `${contacts[20].name}{Enter}`)
    await waitFor(() => expect(state.queries.at(-1)).toEqual({ page: 1, keyword: contacts[20].name }))
    expect(state.writes).toBe(0); expect(state.rule.revision).toBe(existing.revision)
    expect(dialog.getByLabelText('规则名称')).toHaveValue('客户故障规则草稿')
    expect(voice.getByLabelText('1级联系人')).toHaveValue(contacts[21].id)
    vi.mocked(window.confirm).mockReturnValueOnce(false)
    await userEvent.click(dialog.getByRole('button', { name: '保存配置' }))
    expect(state.writes).toBe(0)
    expect(dialog.getByLabelText('规则名称')).toHaveValue('客户故障规则草稿')
    await userEvent.click(dialog.getByRole('button', { name: '保存配置' }))
    expect(await dialog.findByText(/保存结果未知/)).toBeInTheDocument()
    expect(dialog.getByLabelText('规则名称')).toBeDisabled()
    await userEvent.click(dialog.getByRole('button', { name: '按原操作重试' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(state.writes).toBe(1); expect(state.rule.name).toBe('客户故障规则草稿')
    expect(state.attempts).toEqual([state.attempts[0], state.attempts[0]])
    expect(state.rule.event_scope).toBe('FAULT')
    expect(state.rule.receivers.map(receiver => receiver.contact_id)).toEqual([contacts[20].id, contacts[21].id])
  })
  it('只读客户只能查询会话单位设备，结果的单位不匹配时不展示联系人', async () => {
    useSession.getState().login({ ...customer, permissions: ['unit-notifications:read'] }, 'customer')
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      const parsed = new URL(url, 'http://local')
      if (parsed.pathname.endsWith('/data-transfer/kinds')) return response({ kinds: [], protected_storage_ready: true })
      if (parsed.pathname.endsWith('/rules/effective')) {
        expect(new Headers(init?.headers).get('X-Tenant-Id')).toBeNull()
        expect(parsed.searchParams.get('eventScope')).toBe('FIRE')
        return response({ tenant_id: 'other-unit', device_id: 'device-a', event_scope: 'FIRE', receivers: [{ contact_id: 'not-mine', contact_name: '非本单位联系人', phone_masked: '138****0000', channel: 'SMS', level: 1, delay_seconds: 0, source_scope_type: 'UNIT', source_scope_id: 'other-unit', source_rule_id: 'rule-x', source_revision: 1 }] })
      }
      return response({ items: [], page: 1, total: 0, size: 20 })
    }))
    render(<CustomerNotificationSettings/>)
    expect(screen.queryByRole('button', { name: '新增收件规则' })).not.toBeInTheDocument()
    const preview = within(screen.getByRole('region', { name: '设备生效联系人' }))
    expect(preview.queryByLabelText('查询单位')).not.toBeInTheDocument()
    fireEvent.change(preview.getByLabelText('设备 ID'), { target: { value: 'device-a' } })
    await userEvent.click(preview.getByRole('button', { name: '查看生效联系人' }))
    expect(await preview.findByRole('alert')).toBeInTheDocument()
    expect(preview.queryByText('非本单位联系人')).not.toBeInTheDocument()
  })
})
