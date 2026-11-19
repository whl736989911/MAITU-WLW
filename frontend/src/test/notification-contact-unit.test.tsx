import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationConfiguration } from '@/components/notification-settings'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

const detail = { id: 'contact-b', tenant_id: 'tenant-b', tenant_name: '单位B', name: '单位B值班人', contact_type: 'DUTY_OFFICER', phone_masked: '138****0000', wechat_binding_id: null, enabled: true, revision: 7, created_at: '2026-11-12T00:00:00Z' }
beforeEach(() => {
  useSession.getState().clear();localStorage.clear();sessionStorage.clear()
  useSession.getState().login({ ...management, permissions: ['notification-config:read','notification-config:write'] }, 'management')
  useSession.getState().setScope(['tenant-a','tenant-b'], [], true)
})
function setup(options: { missingUnit?: boolean; wrongDetail?: boolean; hold?: boolean; readonly?: boolean } = {}) {
  if (options.readonly) useSession.setState({ permissions: ['notification-config:read'] })
  const state = { contact: { ...detail }, phone: '13800000000', writes: 0, reads: 0, created: false, createdEnabled: undefined as boolean | undefined, resolve: undefined as ((reply: Response) => void) | undefined }
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://local').pathname, tenant = new Headers(init?.headers).get('X-Tenant-Id')
    if (path.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places: [] }, { id: 'tenant-b', name: '单位B', places: [] }])
    if (path.endsWith('/notification-wechat/recipients')) return response([])
    if (path.endsWith('/notification-contacts') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));if (tenant !== 'tenant-a') return response({ code: 'WRONG_UNIT' }, 404)
      state.writes++;state.created = true;state.createdEnabled = body.enabled
      return response({ ...detail, id: 'new-contact-a', tenant_id: 'tenant-a', tenant_name: '单位A', name: body.name, contact_type: body.contact_type, enabled: body.enabled, revision: 0 }, 201)
    }
    if (path.endsWith('/notification-contacts')) {
      const items = [options.missingUnit ? Object.fromEntries(Object.entries(state.contact).filter(([key]) => key !== 'tenant_id' && key !== 'tenant_name')) : { ...state.contact }]
      if (state.created) items.push({ ...detail, id: 'new-contact-a', name: '新增单位A联系人', enabled: false, tenant_id: 'tenant-a', tenant_name: '单位A' })
      return response({ items, total: items.length, page: 1, size: 20 })
    }
    if (path.endsWith('/notification-contacts/contact-b')) {
      if (tenant !== 'tenant-b') return response({ code: 'WRONG_UNIT' }, 404)
      if (init?.method === 'PUT') {
        const body = JSON.parse(String(init.body));if (body.expected_revision !== state.contact.revision) return response({ code: 'REVISION_CONFLICT' }, 409)
        state.writes++;state.contact = { ...state.contact, name: body.name, enabled: body.enabled, revision: state.contact.revision + 1 }
        if (body.phone !== null) state.phone = body.phone
        return response(state.contact)
      }
      state.reads++
      if (options.hold) return new Promise<Response>(resolve => { state.resolve = resolve })
      return response({ ...state.contact, ...(options.wrongDetail ? { tenant_id: 'tenant-c', tenant_name: '单位C' } : {}) })
    }
    throw new Error(`Unexpected contact request: ${url}`)
  }))
  render(<NotificationConfiguration />)
  return state
}
async function openContact(readonly = false) {
  const row = (await screen.findByText(detail.name)).closest('tr')!
  await userEvent.click(within(row).getByRole('button', { name: readonly ? '查看配置' : '编辑联系人' }))
}
function change(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }) }
async function confirm() {
  await userEvent.click(screen.getByRole('button', { name: /保存联系人|创建联系人/ }))
}
describe('联系人真实详情不含单位字段', () => {
  it('重命名固定到列表原单位，普通资料直接保存且不更换号码', async () => {
    const state = setup();await openContact();await screen.findByLabelText('替换号码（留空保留）')
    change('联系人名称', '原单位B改名')
    change('操作原因', '仅变更原联系人，不更换号码');await confirm()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(state.writes).toBe(1)
    expect(state.phone).toBe('13800000000')
    expect(state.contact.name).toBe('原单位B改名')
    expect(state.contact.enabled).toBe(true)
  })
  it('停用已启用联系人固定到列表原单位，核对后才提交', async () => {
    const state = setup();await openContact();await screen.findByLabelText('替换号码（留空保留）')
    await userEvent.click(screen.getByRole('checkbox', { name: /启用联系人/ }))
    change('操作原因', '仅停用原联系人，不更换号码')
    await userEvent.click(screen.getByRole('button', { name: '核对联系人变更' }))
    expect(state.writes).toBe(0)
    await userEvent.click(screen.getByRole('button', { name: '确认保存联系人' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(state.writes).toBe(1)
    expect(state.phone).toBe('13800000000')
    expect(state.contact.name).toBe(detail.name)
    expect(state.contact.enabled).toBe(false)
  })
  it('新增仍使用明确选择的单位，且默认不启用', async () => {
    const state = setup();await userEvent.click(await screen.findByRole('button', { name: '创建通知联系人' }))
    await screen.findByRole('option', { name: '单位A' })
    expect(screen.getByLabelText('联系人所属单位')).toHaveValue('')
    expect(screen.getByRole('button', { name: '创建联系人' })).toBeEnabled()
    expect(state.writes).toBe(0)
    await userEvent.selectOptions(screen.getByLabelText('联系人所属单位'), 'tenant-a')
    change('联系人名称', '新增单位A联系人')
    await userEvent.selectOptions(screen.getByLabelText('联系人类型'), 'OTHER');change('联系电话','13900000000');change('操作原因','隔离资料维护')
    await confirm()
    await screen.findByText('新增单位A联系人');expect(state.created).toBe(true);expect(state.createdEnabled).toBe(false)
  })
  it('详情返回另一个单位时阻止提交，而不是切换目标', async () => {
    const state = setup({ wrongDetail: true });await openContact();await screen.findByRole('alert')
    expect(screen.queryByRole('button', { name: '核对联系人变更' })).not.toBeInTheDocument();expect(state.writes).toBe(0)
  })
  it('缺失单位归属的联系人列表无法确认，也不读取模糊详情', async () => {
    const state = setup({ missingUnit: true });await screen.findByRole('alert')
    expect(screen.queryByText(detail.name)).not.toBeInTheDocument();expect(state.reads).toBe(0)
  })
  it('读取范围变化不改写已打开联系人的记录归属', async () => {
    const state = setup({ hold: true });await openContact();await waitFor(() => expect(state.resolve).toBeTypeOf('function'))
    act(() => useSession.getState().setScope(['tenant-a'], [], true))
    await act(async () => state.resolve!(response(detail)))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('所属单位：单位B');expect(within(dialog).getByLabelText('替换号码（留空保留）')).toBeInTheDocument()
    change('联系人名称', '范围变化后仍改原单位');change('操作原因', '读取范围变化不改变记录归属');await confirm()
    await waitFor(() => expect(state.writes).toBe(1))
    expect(state.contact.name).toBe('范围变化后仍改原单位')
  })
  it('只读查看仍展示联系人原单位且不提供保存入口', async () => {
    setup({ readonly: true });await openContact(true);await within(await screen.findByRole('dialog')).findByText('138****0000')
    expect(screen.getByRole('dialog')).toHaveTextContent('所属单位：单位B')
    expect(screen.queryByRole('button', { name: '核对联系人变更' })).not.toBeInTheDocument()
  })
})
