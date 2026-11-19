import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationResourcesPage } from '@/pages/notification-resources'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'
import type { NotificationResourceAccount } from '@/lib/types'

const tenant = { id: 'tenant-a', name: '验收单位A' }
const initial: NotificationResourceAccount = { id: 'resource-a', tenant_id: tenant.id, tenant_name: tenant.name, owner_type: 'ORGANIZATION', owner_id: tenant.id, owner_name: tenant.name, sms_total: 30, sms_used: 4, sms_remaining: 26, voice_total: 9, voice_used: 2, voice_remaining: 7, enabled: true, revision: 1, updated_at: null }
beforeEach(() => {
  useSession.getState().clear();localStorage.clear();sessionStorage.clear()
  useSession.getState().login({ ...management, permissions: ['notification-resources:read', 'notification-resources:write', 'tenants:read'] }, 'management')
  useSession.getState().setScope([tenant.id], [], true)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => { vi.restoreAllMocks() })
function setup() {
  const state = { account: { ...initial }, commits: 0, loseNext: true, bodyless: false, keys: [] as string[] }
  const receipts = new Map<string, NotificationResourceAccount>()
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://local').pathname
    if (path.endsWith('/management/tenants')) return response({ items: [tenant, { id: 'tenant-b', name: '验收单位B' }] })
    if (init?.method === 'POST' && path.endsWith('/notification-resources/recharge')) {
      const key = new Headers(init.headers).get('Idempotency-Key')!, body = JSON.parse(String(init.body))
      state.keys.push(key)
      if (new Headers(init.headers).get('X-Tenant-Id') !== tenant.id || body.owner_id !== tenant.id) return response({ code: 'WRONG_RECHARGE_TARGET' }, 403)
      if (!receipts.has(key)) {
        state.commits++
        const prefix = body.channel === 'SMS' ? 'sms' : 'voice'
        state.account = { ...state.account, [`${prefix}_total`]: state.account[`${prefix}_total`] + body.quantity, [`${prefix}_remaining`]: state.account[`${prefix}_remaining`] + body.quantity, revision: state.account.revision + 1 }
        receipts.set(key, { ...state.account })
      }
      if (state.loseNext) { state.loseNext = false;return state.bodyless ? new Response(null, { status: 204 }) : Promise.reject(new TypeError('QA committed response lost')) }
      return response(receipts.get(key))
    }
    if (path.endsWith('/notification-resources')) return response([state.account])
    if (path.endsWith('/notification-resources/pricing')) return response({ sms_price_cents: 10, voice_price_cents: 20, revision: 0, updated_at: null })
    if (path.endsWith('/places')) return response([])
    if (path.endsWith('/orders') || path.endsWith('/provider-attempts')) return response({ items: [], total: 0, page: 1, size: 20 })
    throw new Error(`Unexpected resource request: ${url}`)
  }))
  return state
}
async function prepare() {
  const mounted = render(<NotificationResourcesPage />), user = userEvent.setup()
  await screen.findByText('账户余额')
  await user.click(screen.getByText('单价与充值操作'))
  await user.click(screen.getByLabelText('目标单位'))
  await user.click(await screen.findByRole('option', { name: /验收单位A/ }))
  return { user, mounted }
}
describe('后台直接入账的幂等与目标固定', () => {
  it('结果未确认时不另起一笔，离开再返回仍沿原请求与幂等键确认', async () => {
    const state = setup(), { user, mounted } = await prepare()
    await user.click(screen.getByRole('button', { name: '后台直接入账（不扫码）' }));await screen.findAllByRole('alert')
    expect(screen.getByLabelText('短信条数')).toBeDisabled()
    expect(state.commits).toBe(1)
    mounted.unmount()
    render(<NotificationResourcesPage />)
    await user.click(await screen.findByRole('button', { name: '重试原入账' }))
    await user.click(screen.getByRole('button', { name: '确认重试原入账' }))
    await screen.findAllByText(/后台入账已确认/)
    expect(state.commits).toBe(1)
    expect(state.keys).toHaveLength(2)
    expect(state.keys[1]).toBe(state.keys[0])
    expect(localStorage.length).toBe(0);expect(sessionStorage.length).toBe(0)
  })
  it('没有入账回执的204不是成功，必须沿原请求确认', async () => {
    const state = setup();state.bodyless = true
    const { user } = await prepare()
    await user.click(screen.getByRole('button', { name: '后台直接入账（不扫码）' }));await screen.findAllByRole('alert')
    expect(screen.getByRole('button', { name: '重试原入账' })).toBeInTheDocument()
    expect(screen.getByLabelText('短信条数')).toBeDisabled()
    expect(state.commits).toBe(1)
  })
  it('切换操作单位后重试仍指向原单位，不会给新单位充值', async () => {
    const state = setup()
    const { user } = await prepare()
    await user.click(screen.getByRole('button', { name: '后台直接入账（不扫码）' }));await screen.findAllByRole('alert')
    await user.click(screen.getByLabelText('目标单位'))
    await user.click(await screen.findByRole('option', { name: /验收单位B/ }))
    await user.click(screen.getByRole('button', { name: '重试原入账' }))
    await user.click(screen.getByRole('button', { name: '确认重试原入账' }))
    await screen.findAllByText(/后台入账已确认/)
    expect(state.commits).toBe(1)
    expect(state.account.owner_id).toBe(tenant.id)
    expect(screen.getAllByText(/后台入账已确认/)[0]).toHaveTextContent(tenant.name)
  })
})
