import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import App from '@/App'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { customer, fixedLoginChallenge, management, response } from './fixtures'

beforeEach(() => useSession.getState().clear())

it('只在用户端提交客户受众，过期后回到独立的用户端登录地址', async () => {
  history.replaceState(null, '', '/customer/login')
  const signIn = vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/health') return response({ status: 'UP', storage_mode: 'h2-dev', roles: [] })
    if (url === '/api/auth/login') return response({ ...customer, permissions: [] })
    if (url === '/api/auth/me') return response({ code: 'UNAUTHORIZED', message: '会话失效' }, 401)
    throw new Error(`Unexpected endpoint: ${url} ${init?.method}`)
  })
  vi.stubGlobal('fetch', signIn)
  const challenge = fixedLoginChallenge()
  render(<App/>)
  fireEvent.change(screen.getByLabelText('账户'), { target: { value: 'fixture-customer' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'SyntheticEntryOnly123!' } })
  fireEvent.change(screen.getByLabelText('验证码'), { target: { value: challenge } })
  fireEvent.click(screen.getByRole('button', { name: '登录' }))
  await screen.findByRole('navigation', { name: '主导航' })
  expect(location.pathname).toBe('/customer')
  const call = signIn.mock.calls.find(([url]) => url === '/api/auth/login')
  expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ username: 'fixture-customer', audience: 'customer' })
  await act(async () => { await expect(api.me()).rejects.toMatchObject({ status: 401 }) })
  await screen.findByRole('button', { name: '登录' })
  expect(location.pathname).toBe('/customer/login')
  expect(screen.queryByRole('navigation', { name: '主导航' })).not.toBeInTheDocument()
})

it('管理端和用户端之间不复用已打开的会话或显示另一端表单', async () => {
  history.replaceState(null, '', '/customer/login')
  useSession.getState().login(management, 'management')
  const request = vi.fn((url: string) => {
    if (url === '/api/health') return response({ status: 'UP', storage_mode: 'h2-dev', roles: [] })
    throw new Error(`Unexpected endpoint: ${url}`)
  })
  vi.stubGlobal('fetch', request)
  const view = render(<App/>)
  expect(screen.getByRole('button', { name: '登录' })).toBeInTheDocument()
  expect(screen.getByLabelText('验证码')).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /用户端|客户端|管理端/ })).not.toBeInTheDocument()
  await waitFor(() => expect(useSession.getState().token).toBeNull())
  expect(request.mock.calls.every(([url]) => url === '/api/health')).toBe(true)
  view.unmount()
  history.replaceState(null, '', '/management/login')
  render(<App/>)
  expect(screen.getByRole('button', { name: '登录' })).toBeInTheDocument()
  expect(screen.queryByLabelText('验证码')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /用户端|客户端|管理端/ })).not.toBeInTheDocument()
})

it('验证码输错或点图刷新后，不会把凭据提交给服务端', async () => {
  history.replaceState(null, '', '/customer/login')
  const request = vi.fn((url: string) => {
    if (url === '/api/health') return response({ status: 'UP', storage_mode: 'h2-dev', roles: [] })
    throw new Error(`Unexpected endpoint: ${url}`)
  })
  vi.stubGlobal('fetch', request)
  let number = 1
  vi.spyOn(crypto, 'getRandomValues').mockImplementation(array => {
    if (array instanceof Uint8Array) array.fill(number++)
    return array
  })
  render(<App/>)
  fireEvent.change(screen.getByLabelText('账户'), { target: { value: 'fixture-customer' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'SyntheticEntryOnly123!' } })
  fireEvent.change(screen.getByLabelText('验证码'), { target: { value: '2222' } })
  fireEvent.click(screen.getByRole('button', { name: '点击刷新验证码' }))
  expect(screen.getByLabelText('验证码')).toHaveValue('')
  fireEvent.change(screen.getByLabelText('验证码'), { target: { value: '2222' } })
  fireEvent.click(screen.getByRole('button', { name: '登录' }))
  expect(await screen.findByText('验证码错误')).toBeVisible()
  fireEvent.change(screen.getByLabelText('验证码'), { target: { value: '0000' } })
  fireEvent.click(screen.getByRole('button', { name: '登录' }))
  expect(await screen.findByText('验证码错误')).toBeVisible()
  expect(request.mock.calls.map(([url]) => url)).not.toContain('/api/auth/login')
})

it('用户端刷新时用本标签的会话标记恢复客户登录，不显示登录页', async () => {
  const tab = '57e20e46-7b8a-4a98-8c05-ccea5a587686'
  history.replaceState({ customerResumeTag: tab }, '', '/customer')
  const requests = vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/health') return response({ status: 'UP' })
    if (url === '/api/auth/customer-session') {
      expect(init?.credentials).toBe('same-origin')
      expect(new Headers(init?.headers).get('X-Customer-Session')).toBe(tab)
      return response({ ...customer, access_token: 'a'.repeat(43), permissions: [] })
    }
    throw new Error(`Unexpected endpoint: ${url}`)
  })
  vi.stubGlobal('fetch', requests)
  render(<App/>)
  expect(screen.queryByRole('button', { name: '登录' })).not.toBeInTheDocument()
  await screen.findByRole('navigation', { name: '主导航' })
  expect(location.pathname).toBe('/customer')
  expect(useSession.getState().user?.id).toBe(customer.user.id)
})

it('用户端会话已过期则返回登录，不再保留刷新标记', async () => {
  history.replaceState({ customerResumeTag: '57e20e46-7b8a-4a98-8c05-ccea5a587686' }, '', '/customer')
  vi.stubGlobal('fetch', (url: string) => url === '/api/health' ? response({ status: 'UP' }) :
    response({ code: 'UNAUTHENTICATED', message: '请重新登录' }, 401))
  render(<App/>)
  await screen.findByLabelText('验证码')
  expect(location.pathname).toBe('/customer/login')
  expect(history.state?.customerResumeTag).toBeUndefined()
})

it('管理端默认收起单位/位置栏，可展开且不清空已选范围', async () => {
  history.replaceState(null, '', '/management')
  useSession.getState().login({ ...management, permissions: [] }, 'management')
  useSession.getState().setScope(['tenant-a'], ['p1'])
  vi.stubGlobal('fetch', async (url: string) => url.includes('/scope-tree') ?
    response({ items: [{ id: 'tenant-a', name: '测试单位', places: [{ id: 'p1', name: '测试位置', parent_id: null, device_count: 1 }] }] }) :
    response({ status: 'UP' }))
  render(<App/>)
  const toggle = screen.getByRole('button', { name: '展开单位/位置筛选栏' })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(toggle)
  expect(screen.getByRole('button', { name: '收起单位/位置筛选栏' })).toHaveAttribute('aria-expanded', 'true')
  expect(useSession.getState().scopePlaceIds).toEqual(['p1'])
})
