import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within, act, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RolesPage } from '@/pages/roles'
import { UsersPage } from '@/pages/users'
import { accessApi } from '@/lib/access-roles'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

const catalog = { items: [{ key: 'device:read', label: '设备读取', user_types: ['PLATFORM', 'CUSTOMER'], delegable: true }], password_policy: { min_length: 12, max_length: 128 } }
const granted = ['roles:read', 'roles:write', 'platform-users:read', 'platform-users:assign-roles', 'platform-users:assign-scope']
const role = { id: 'role-1', code: 'selected', name: '范围内读取', user_type: 'PLATFORM', enabled: true, deleted: false, revision: 0, permissions: ['device:read'] }
const target = { id: 'target-1', username: 'target-reader', name: '授权目标', user_type: 'PLATFORM', tenant_id: null, enabled: true, must_change_password: false, is_protected: false, revision: 1, permissions: ['device:read'], tenant_ids: ['tenant-a'] }
beforeEach(() => {
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: [...management.permissions, ...granted] }, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); useSession.getState().clear() })

async function prepareRole() {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: '创建角色模板' }))
  fireEvent.change(await screen.findByLabelText('不可变代码'), { target: { value: 'retry_only' } })
  fireEvent.change(screen.getByLabelText('模板名称'), { target: { value: '待确认模板' } })
  fireEvent.change(screen.getByLabelText('操作理由'), { target: { value: '保持原始业务意图' } })
  await user.click(screen.getByRole('button', { name: '继续核对' }))

  return user
}

it('keeps an unconfirmed committed role creation open and retries its original intent/key unchanged', async () => {
  const writes: { key: string | null; body: Record<string, unknown> }[] = []
  let commits = 0
  let saved: typeof role | undefined
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/permission-catalog')) return response(catalog)
    if (init?.method === 'POST' && path === '/api/management/roles') {
      const body = JSON.parse(String(init.body)), key = new Headers(init.headers).get('Idempotency-Key')
      writes.push({ key, body })
      if (!saved) { commits++; saved = { ...role, code: body.code, name: body.name, user_type: body.user_type, permissions: body.permissions }; return new Response('{', { status: 200 }) }
      expect(key).toBe(writes[0].key)
      return response(saved)
    }
    if (path.startsWith('/api/management/roles?')) return response({ items: saved ? [saved] : [], total: saved ? 1 : 0, page: 1, size: 20 })
    throw new Error('Unexpected authorization fixture route')
  }))
  const mounted = render(<RolesPage/>)
  const user = await prepareRole()
  await user.click(screen.getByRole('button', { name: '确认提交' }))
  await screen.findByRole('alert')
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }))
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '返回修改' })).toBeDisabled()
  mounted.unmount()
  render(<RolesPage/>)

  await user.click(await screen.findByRole('button', { name: '确认提交' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(commits).toBe(1)
  expect(writes).toHaveLength(2)
  expect(writes[0].key).toBeTruthy()
  expect(writes[1]).toEqual(writes[0])
})

it.each([204, 205])('never confirms a bodyless %s response for a role mutation', async status => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(undefined, { status })))
  await expect(accessApi.createRole({ code: 'nonempty', name: '有效模板', user_type: 'PLATFORM', enabled: true, permissions: [], reason: '明确创建'}, 'original-key'))
    .rejects.toMatchObject({ status, code: 'INVALID_RESPONSE' })
})

it('freezes selected devices while scope submission is pending and does not lose the intent on ordinary close', async () => {
  let deliver!: () => void
  let scope = { user_id: target.id, tenant_id: 'tenant-a', configured: true, mode: 'DEVICE_SET', revision: 1, device_ids: ['device-a'], place_ids: [] as string[] }
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/permission-catalog')) return response(catalog)
    if (path.includes('/platform-users?')) return response({ items: [target], total: 1, page: 1, size: 20 })
    if (path.includes('/roles?')) return response({ items: [role], total: 1, page: 1, size: 100 })
    if (path.endsWith('/access')) return response({ user_id: target.id, user_type: 'PLATFORM', revision: 1, direct_permissions: ['device:read'], effective_permissions: ['device:read'], roles: [] })
    if (path.includes('/devices?')) return response({ items: [], total: 0, page: 1, size: 20 })
    if (path.endsWith('/scope') && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)); scope = { ...scope, revision: 2, device_ids: body.device_ids, place_ids: body.place_ids }
      return new Response(new ReadableStream<Uint8Array>({ start(controller) { deliver = () => { controller.enqueue(new TextEncoder().encode(JSON.stringify(scope))); controller.close() } } }), { status: 200 })
    }
    if (path.endsWith('/scope')) return response(scope)
    throw new Error('Unexpected scope fixture route')
  }))
  render(<UsersPage platform/>)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: '角色 / 设备范围' }))
  fireEvent.change(await screen.findByLabelText('目标单位（来自目标账号的授权）'), { target: { value: 'tenant-a' } })
  await user.click(await screen.findByRole('button', { name: '明确读取目标单位范围' }))
  await user.click(await screen.findByRole('button', { name: '移除 device-a' }))
  fireEvent.change(screen.getByLabelText('设备范围变更理由'), { target: { value: '明确限定设备' } })
  await user.click(screen.getByRole('button', { name: '核对设备范围' }))

  await user.click(screen.getByRole('button', { name: '确认设备范围' }))
  await waitFor(() => expect(deliver).toBeTypeOf('function'))
  expect(screen.getByRole('checkbox', { name: /范围内读取/ })).toBeDisabled()
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }))
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  await act(async () => { deliver(); await Promise.resolve() })
})
