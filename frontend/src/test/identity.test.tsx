import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from '@/App'
import { UsersPage } from '@/pages/users'
import { AuthScreen } from '@/components/auth'
import { UnitsPage } from '@/pages/units'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { ManagedUser, PermissionCatalog, Unit } from '@/lib/types'
import { customer, management, response } from './fixtures'

const p2Permissions = ['units:read', 'units:write', 'users:read', 'users:write', 'users:reset-password', 'platform-users:read', 'platform-users:write', 'platform-users:reset-password']
const admin = { ...management, permissions: [...management.permissions, ...p2Permissions] }
const unit: Unit = { id: 'unit-one', name: '正式测试单位', enabled: true, revision: 3 }
const target: ManagedUser = { id: 'managed-one', username: 'unit-reader', name: '单位读者', user_type: 'CUSTOMER', tenant_id: unit.id, tenant_name: unit.name, enabled: true, must_change_password: false, revision: 7, is_protected: false, permissions: ['device:read'], tenant_ids: [] }
const catalog: PermissionCatalog = { items: [
  { key: 'device:read', label: '设备查看', user_types: ['CUSTOMER', 'PLATFORM'], delegable: true },
  { key: 'alarm:review', label: '报警复核（不可委派）', user_types: ['CUSTOMER', 'PLATFORM'], delegable: false },
  { key: 'units:write', label: '平台单位创建权限', user_types: ['PLATFORM'], delegable: true },
], password_policy: { min_length: 10, max_length: 64 } }
const listedUser = (name: string) => screen.findByRole('row', { name: new RegExp(name) })
const page = (items: unknown[]) => ({ items, total: items.length, page: 1, size: 20 })
const actorSecret = 'ActorTestOnly123!'
const targetSecret = 'TargetTestOnly123!'
function loginAdmin() { useSession.getState().login(admin, 'management'); useSession.getState().setScope([unit.id], [], true) }
async function chooseCreateUnit(user: ReturnType<typeof userEvent.setup>) { await user.click(await screen.findByLabelText('目标单位')); await user.click(await screen.findByRole('option', { name: /正式测试单位/ })) }
function defaultFetch(url: string, init?: RequestInit) {
  if (url === '/api/auth/permission-catalog') return response(catalog)
  if (url.includes('/health')) return response({ status: 'UP', storage_mode: 'h2-dev', roles: [] })
  if (url === '/api/management/tenants') return response({ items: [unit] })
  if (url.includes('/units?')) return response(page([unit]))
  if (url.includes('/users?') || url.includes('/platform-users?')) return response(page([target]))
  if (init?.method) return response({ ...target, revision: 8 })
  throw new Error(`Unexpected test endpoint: ${url}`)
}
beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })

describe('P2真实API合同', () => {
  it('普通客户开户不接受伪造的跨单位字段', async () => {
    useSession.getState().login({ ...customer, permissions: [...customer.permissions, 'users:read', 'users:write'] }, 'customer')
    const fetcher = vi.fn().mockImplementation(async (_url: string, _init: RequestInit) => response(target)); vi.stubGlobal('fetch', fetcher)
    await api.createUser(false, { username: 'new-person', name: '新人', initial_password: targetSecret, permissions: ['device:read'], tenant_ids: ['forged-grant'] }, 'create-customer')
    const ordinary = JSON.parse(String(fetcher.mock.calls[0][1].body))
    expect(ordinary).not.toHaveProperty('tenant_ids'); expect(ordinary).not.toHaveProperty('tenant_id'); expect(ordinary).not.toHaveProperty('user_type')
  })
  it('权限拒绝403只提示requestId，不清有效登录', async () => {
    loginAdmin(); vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ code: 'FORBIDDEN', message: '当前账户无此写入权限', request_id: 'forbidden-403' }, 403)))
    await expect(api.createUnit({ name: '单位'}, 'key')).rejects.toMatchObject({ status: 403, requestId: 'forbidden-403' })
    expect(useSession.getState().token).toBe(admin.access_token)
  })
})

describe('P2自助改密入口', () => {
  it('正常登录含根账户可自助改密；取消返回工作台且销毁密码', async () => {
    useSession.getState().login({ ...admin, user: { ...admin.user, username: 'bootstrap-admin' } }, 'management')
    const fetcher = vi.fn().mockImplementation(defaultFetch); vi.stubGlobal('fetch', fetcher)
    render(<App/>); const user = userEvent.setup(); await user.click(screen.getByRole('button', { name: '修改自己的密码' }))
    expect(screen.getByRole('heading', { name: '修改自己的密码' })).toBeInTheDocument(); expect(screen.getByLabelText('当前密码')).toHaveValue('')
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: actorSecret } }); fireEvent.change(screen.getByLabelText('新密码'), { target: { value: targetSecret } })
    await user.click(screen.getByRole('button', { name: '取消并返回工作空间' })); expect(useSession.getState().token).toBe(admin.access_token); expect(screen.queryByLabelText('当前密码')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '修改自己的密码' })); expect(screen.getByLabelText('当前密码')).toHaveValue(''); expect(screen.getByLabelText('新密码')).toHaveValue(''); expect(fetcher.mock.calls.some(call => call[0] === '/api/auth/change-password')).toBe(false)
  })
  it('自助新密码必须确认一致，204成功清会话并要求重新登录', async () => {
    loginAdmin(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url === '/api/auth/change-password' ? new Response(null, { status: 204 }) : defaultFetch(url, init)); vi.stubGlobal('fetch', fetcher)
    render(<App/>); const user = userEvent.setup(); await user.click(screen.getByRole('button', { name: '修改自己的密码' }))
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: actorSecret } }); fireEvent.change(screen.getByLabelText('新密码'), { target: { value: targetSecret } }); fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'different-test-only' } }); await user.click(screen.getByRole('button', { name: '更新密码并重新登录' })); expect(screen.getByRole('alert')).toHaveTextContent('两次新密码不一致')
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: targetSecret } }); await user.click(screen.getByRole('button', { name: '更新密码并重新登录' })); await screen.findByText('密码已更新，旧会话已撤销。请使用新密码重新登录。')
    expect(useSession.getState().token).toBeNull(); expect(screen.getByLabelText('密码')).toHaveValue(''); expect(JSON.stringify(useSession.getState())).not.toContain(actorSecret)
  })
  it('客户完成初始改密后仍从客户入口重新登录', async () => {
    useSession.getState().login({ ...customer, user: { ...customer.user, must_change_password: true } }, 'customer')
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/auth/change-password') return Promise.resolve(new Response(null, { status: 204 }))
      if (url === '/api/auth/login') {
        const input = JSON.parse(String(init?.body)) as { audience?: string }
        return Promise.resolve(input.audience === 'customer'
          ? response({ ...customer, user: { ...customer.user, must_change_password: false } })
          : response({ code: 'INVALID_CREDENTIALS', message: '账号与入口不匹配' }, 401))
      }
      throw new Error(`Unexpected account lifecycle endpoint: ${url}`)
    }))
    render(<AuthScreen/>)
    const user = userEvent.setup()
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: actorSecret } })
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: targetSecret } })
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: targetSecret } })
    await user.click(screen.getByRole('button', { name: '更新密码并重新登录' }))
    await waitFor(() => expect(useSession.getState().token).toBeNull())
    fireEvent.change(screen.getByLabelText('账户'), { target: { value: customer.user.username } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: targetSecret } })
    await user.click(screen.getByRole('button', { name: '登录客户端' }))
    await waitFor(() => expect(useSession.getState().token).toBe(customer.access_token))
    expect(useSession.getState().audience).toBe('customer')
    expect(useSession.getState().user?.tenant_id).toBe(customer.user.tenant_id)
  })
})
describe('P2页面边界与敏感表单', () => {
  it('客户即使伪带平台权限，直接访问单位/平台用户页也不发请求', () => {
    useSession.getState().login({ ...customer, permissions: [...customer.permissions, ...p2Permissions] }, 'customer')
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    render(<><UnitsPage/><UsersPage platform/></>)
    expect(fetcher).not.toHaveBeenCalled(); expect(screen.queryByRole('button', { name: '创建单位' })).not.toBeInTheDocument()
  })
  it('管理端单位用户未选tenant也读取授权目录；客户仍用会话并仅显示适用目录', async () => {
    useSession.getState().login(admin, 'management'); const fetcher = vi.fn().mockImplementation(defaultFetch); vi.stubGlobal('fetch', fetcher)
    const managementView = render(<UsersPage platform={false}/>); await listedUser('单位读者')
    const userRead = fetcher.mock.calls.find(call => call[0].includes('/management/users?'))!
    expect((userRead[1].headers as Headers).has('X-Tenant-Id')).toBe(false)
    expect(new URL(userRead[0], 'http://local').searchParams.get('scope_selected')).toBe('true')
    managementView.unmount()
    useSession.getState().login({ ...customer, permissions: [...customer.permissions, 'users:read', 'users:write'] }, 'customer')
    render(<UsersPage platform={false}/>); await listedUser('单位读者')
    await userEvent.click(screen.getByRole('button', { name: '创建单位用户' }))
    expect(screen.getByLabelText('初始密码')).toHaveValue(''); expect(screen.getByLabelText('确认初始密码')).toHaveValue('')
    expect(screen.queryByText('平台单位创建权限')).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /报警复核（不可委派）/ })).toBeDisabled()
    expect(fetcher.mock.calls.every(call => !(call[1].headers as Headers).has('X-Tenant-Id'))).toBe(true)
  })
  it('自我与保护账号只读，其他账号才有编辑/重置动作', async () => {
    loginAdmin(); const records = [target, { ...target, id: admin.user.id, username: 'myself', name: '当前本人' }, { ...target, id: 'root', username: 'protected-root', name: '受保护根', is_protected: true }]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => url.includes('/users?') ? response(page(records)) : defaultFetch(url, init)))
    render(<UsersPage platform={false}/>); await listedUser('受保护根')
    for (const name of ['当前本人', '受保护根']) { const row = screen.getAllByRole('row').find(item => item.textContent?.includes(name)); expect(row).toBeDefined(); expect(within(row!).queryByRole('button', { name: /编辑|重置密码/ })).not.toBeInTheDocument() }
    const writableRow = await listedUser('单位读者'); expect(within(writableRow).getByRole('button', { name: /^编辑/ })).toBeInTheDocument(); expect(within(writableRow).getByRole('button', { name: '重置密码' })).toBeInTheDocument()
  })
  it('创建用户需二次确认；未知结果沿原key重试，目标密码变化换key，成功销毁秘密', async () => {
    loginAdmin(); let writes = 0
    const fetcher = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === '/api/management/users' && init?.method === 'POST') { writes++; return writes <= 2 ? response({ code: 'SERVICE_UNAVAILABLE', message: '结果未确认', request_id: 'create-503' }, 503) : response(target) }
      return defaultFetch(url, init)
    }); vi.stubGlobal('fetch', fetcher)
    render(<UsersPage platform={false}/>); await listedUser('单位读者'); const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '创建单位用户' })); await chooseCreateUnit(user); await user.type(screen.getByLabelText('用户名'), 'new-customer'); await user.type(screen.getByLabelText('姓名'), '真实新客户'); await user.type(screen.getByLabelText('初始密码'), targetSecret); await user.type(screen.getByLabelText('确认初始密码'), targetSecret); await user.click(screen.getByRole('checkbox', { name: /设备查看/ })); await user.click(screen.getByRole('button', { name: '核对并继续' }))
    expect(screen.queryByDisplayValue(targetSecret)).not.toBeInTheDocument(); expect(writes).toBe(0)
    await user.click(screen.getByRole('button', { name: '确认创建账号' })); await waitFor(() => expect(writes).toBe(1)); await screen.findByText(/create-503/)
    expect(screen.queryByText(/账号已创建。初始密码不在响应中返回/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认创建账号' })); await waitFor(() => expect(writes).toBe(2))
    const firstCalls = fetcher.mock.calls.filter(call => call[0] === '/api/management/users' && call[1]?.method === 'POST')
    expect((firstCalls[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((firstCalls[1][1]!.headers as Headers).get('Idempotency-Key'))
    await user.click(screen.getByRole('button', { name: '返回修改' })); await user.clear(screen.getByLabelText('初始密码')); await user.type(screen.getByLabelText('初始密码'), `${targetSecret}changed`); await user.clear(screen.getByLabelText('确认初始密码')); await user.type(screen.getByLabelText('确认初始密码'), `${targetSecret}changed`); await user.click(screen.getByRole('button', { name: '核对并继续' })); await user.click(screen.getByRole('button', { name: '确认创建账号' }))
    await screen.findByText(/账号已创建。初始密码不在响应中返回/)
    const calls = fetcher.mock.calls.filter(call => call[0] === '/api/management/users' && call[1]?.method === 'POST')
    expect((calls[2][1]!.headers as Headers).get('Idempotency-Key')).not.toBe((calls[0][1]!.headers as Headers).get('Idempotency-Key'))
    expect(JSON.stringify(useSession.getState())).not.toContain(actorSecret); expect(JSON.stringify(useSession.getState())).not.toContain(targetSecret); expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
  }, 20_000)
  it('409必须刷新重开，不能返回修改并覆盖旧版本', async () => {
    loginAdmin(); vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => init?.method === 'PUT' ? response({ code: 'REVISION_CONFLICT', message: '版本冲突', request_id: 'user-409' }, 409) : defaultFetch(url, init)))
    render(<UsersPage platform={false}/>); await listedUser('单位读者'); const user = userEvent.setup()
    const row = await listedUser('单位读者'); await user.click(within(row).getByRole('button', { name: /^编辑/ })); await user.click(screen.getByRole('button', { name: '核对并继续' })); await user.click(screen.getByRole('button', { name: '确认保存并撤销会话' })); await screen.findByText(/user-409/)
    expect(screen.getByRole('button', { name: '返回修改' })).toBeDisabled(); expect(screen.getByRole('button', { name: '确认保存并撤销会话' })).toBeDisabled(); await user.click(screen.getByRole('button', { name: '刷新后重新打开' }))
  })
  it('密码重置在关闭和成功提交后清除秘密', async () => {
    loginAdmin(); const fetcher = vi.fn().mockImplementation(defaultFetch); vi.stubGlobal('fetch', fetcher)
    render(<UsersPage platform={false}/>); await listedUser('单位读者'); const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '重置密码' })); expect(screen.getByLabelText('新密码')).toHaveValue(''); await user.type(screen.getByLabelText('新密码'), targetSecret); await user.type(screen.getByLabelText('确认新密码'), targetSecret); await user.keyboard('{Escape}'); await waitFor(() => expect(screen.queryByLabelText('新密码')).not.toBeInTheDocument()); await user.click(screen.getByRole('button', { name: '重置密码' })); expect(screen.getByLabelText('新密码')).toHaveValue(''); expect(screen.getByLabelText('确认新密码')).toHaveValue('')
    await user.type(screen.getByLabelText('新密码'), targetSecret); await user.type(screen.getByLabelText('确认新密码'), targetSecret); await user.click(screen.getByRole('button', { name: '核对并继续' })); await user.click(screen.getByRole('button', { name: '确认重置并撤销会话' })); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); expect(screen.queryByDisplayValue(targetSecret)).not.toBeInTheDocument()
  }, 20_000)
  it('平台授权候选只来自已授权units目录，普通单位用户表单没有grant选项', async () => {
    loginAdmin(); vi.stubGlobal('fetch', vi.fn().mockImplementation(defaultFetch))
    const view = render(<UsersPage platform/>); await listedUser('单位读者'); await userEvent.click(screen.getByRole('button', { name: '创建平台用户' })); expect(await screen.findByRole('checkbox', { name: new RegExp(unit.name) })).toBeInTheDocument(); expect(screen.getByText('平台单位创建权限')).toBeInTheDocument(); expect(screen.queryByRole('checkbox', { name: /未授权单位/ })).not.toBeInTheDocument(); view.unmount()
    render(<UsersPage platform={false}/>); await listedUser('单位读者'); await userEvent.click(screen.getByRole('button', { name: '创建单位用户' })); expect(screen.queryByText('平台单位访问授权')).not.toBeInTheDocument()
  })
  it('范围筛选变化不改变已打开操作的明确目标，迟到写响应仍只作用于目标账号', async () => {
    loginAdmin(); let resolveWrite!: (value: Response) => void
    const fetcher = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => url.endsWith('/reset-password') ? new Promise<Response>(r => { resolveWrite = r }) : defaultFetch(url, init)); vi.stubGlobal('fetch', fetcher)
    render(<UsersPage platform={false}/>); await listedUser('单位读者'); const user = userEvent.setup(); await user.click(screen.getByRole('button', { name: '重置密码' })); await user.type(screen.getByLabelText('新密码'), targetSecret); await user.type(screen.getByLabelText('确认新密码'), targetSecret); await user.click(screen.getByRole('button', { name: '核对并继续' })); await user.click(screen.getByRole('button', { name: '确认重置并撤销会话' }))
    const pending = fetcher.mock.calls.find(call => call[0].endsWith('/reset-password'))!; expect((pending[1]!.headers as Headers).get('X-Tenant-Id')).toBe(unit.id)
    act(() => useSession.getState().setScope(['other-unit'], [], true))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await act(async () => resolveWrite(response({ ...target, revision: 8 })))
    await screen.findByText(/变更已保存/); expect(JSON.stringify(useSession.getState())).not.toContain(actorSecret)
  })
  it('停用选中单位后保留有效登录并仍可访问全局单位页', async () => {
    loginAdmin(); useSession.getState().setScope([unit.id], [], true); useSession.getState().navigate('units'); let enabled = true
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `/api/management/units/${unit.id}` && init?.method === 'PUT') {
        enabled = JSON.parse(String(init.body)).enabled
        return response({ ...unit, enabled, revision: unit.revision + 1 })
      }
      if (url === '/api/management/scope-tree') return response(enabled ? [{ id: unit.id, name: unit.name, places: [] }] : [])
      if (url === '/api/management/tenants') return response({ items: enabled ? [unit] : [] })
      if (url.includes('/units?')) return response(page([{ ...unit, enabled }]))
      return defaultFetch(url, init)
    }))
    render(<App/>); const row = await screen.findByRole('row', { name: /正式测试单位/ }); const user = userEvent.setup(); await user.click(within(row).getByRole('button', { name: '停用' })); await user.click(screen.getByRole('button', { name: '确认停用' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '新增单位' })).toBeEnabled()); expect(useSession.getState().token).toBe(admin.access_token); expect(useSession.getState().page).toBe('units'); expect(screen.getByRole('button', { name: '新增单位' })).toBeEnabled()
  })
})
