import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationRechargePage } from '@/pages/notification-recharge'
import { useSession } from '@/lib/store'
import { customer, response } from './fixtures'
import type { NotificationCustomerAccount, NotificationCustomerOrder } from '@/lib/types'

const order: NotificationCustomerOrder = { id: 'history-order', channel: 'VOICE', quantity: 5, amount_cents: 125, status: 'PENDING', payment_state: 'ACCEPTED', code_url: 'weixin://qa-only-no-live-payment', expires_at: null, created_at: '2026-11-12T01:00:00Z', paid_at: null }
beforeEach(() => { useSession.getState().clear();localStorage.clear();sessionStorage.clear();useSession.getState().login({ ...customer, permissions: ['notification-recharge:read'] }, 'customer') })
afterEach(() => { vi.useRealTimers() })
function setup(accounts: NotificationCustomerAccount[] = []) {
  const state = { current: { ...order }, fail: false, hold: false, resolve: undefined as ((reply: Response) => void) | undefined }
  const fetcher = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method === 'POST') throw new Error('History viewing must never create an order or a payment request')
    const path = new URL(url, 'http://local').pathname
    if (path.endsWith('/options')) return response({ policy: { mode: 'ORGANIZATION', revision: 0, updated_at: null }, destinations: accounts.map(account => ({ owner_type: account.owner_type, owner_id: account.owner_id, owner_name: account.owner_name })), pricing: { sms_price_cents: 10, voice_price_cents: 25, revision: 0, updated_at: null } })
    if (path.endsWith('/accounts')) return response(accounts)
    if (path.endsWith('/accounts/wire-account/ledger')) return response({ items: [{ id: 'entry-voice', account_id: 'wire-account', channel: 'VOICE', kind: 'RECHARGE', quantity: 3, balance_after: 5, created_at: '2026-11-12T02:00:00Z' }], total: 1, page: 1, size: 20 })
    if (path.endsWith('/orders')) return response({ items: [order], total: 1, page: 1, size: 20 })
    if (path.endsWith('/orders/history-order')) {
      if (state.hold) return new Promise<Response>(resolve => { state.resolve = resolve })
      return state.fail ? response({ code: 'QA_ORDER_UNAVAILABLE', message: '订单暂不可读取' }, 503) : response(state.current)
    }
    throw new Error(`Unexpected history request: ${url}`)
  })
  vi.stubGlobal('fetch', fetcher);render(<NotificationRechargePage />)
  return { state, fetcher }
}
async function open() { await userEvent.click(await screen.findByRole('button', { name: '查看订单 history-order' })) }
describe('客户端已有充值订单', () => {
  it('按真实 account_id 合同读取余额，并打开对应账户流水', async () => {
    setup([{ account_id: 'wire-account', owner_type: 'ORGANIZATION', owner_id: 'tenant-a', owner_name: '单位A', sms_total: 30, sms_used: 9, sms_remaining: 21, voice_total: 8, voice_used: 3, voice_remaining: 5, enabled: true, updated_at: null }])
    await userEvent.click(await screen.findByRole('button', { name: '查看明细' }))
    await screen.findByRole('region', { name: '账户资源明细' })
    expect(screen.getByRole('region', { name: '账户资源明细' })).toHaveTextContent('语音')
    expect(screen.getByRole('region', { name: '账户资源明细' })).toHaveTextContent('充值')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
  it('只读客户能重新打开自己的待支付订单，不创建新订单', async () => {
    const { fetcher } = setup();await open()
    expect(await screen.findByRole('img', { name: '微信支付二维码' })).toBeInTheDocument()
    expect(screen.getByText(/订单号 history-order/)).toHaveTextContent('语音 5 次')
    expect(fetcher.mock.calls.some(([,init]) => init?.method === 'POST')).toBe(false)
  })
  it('列表仍待支付但详情已经支付时不再给出付款二维码', async () => {
    const { state } = setup();state.current = { ...order, status: 'PAID', payment_state: 'PAID' };await open()
    await screen.findByText(/订单号 history-order/)
    expect(screen.queryByRole('img', { name: '微信支付二维码' })).not.toBeInTheDocument()
    expect(screen.getByText(/订单号 history-order/)).toHaveTextContent('状态 PAID')
  })
  it.each(['CLOSED','UNKNOWN','EXPIRED'] as const)('%s 的旧二维码不能继续展示为可支付', async kind => {
    const { state } = setup()
    if (kind === 'EXPIRED') state.current.expires_at = '2000-01-01T00:00:00Z'
    else state.current.payment_state = kind
    await open();await screen.findByText(/订单号 history-order/)
    expect(screen.queryByRole('img', { name: '微信支付二维码' })).not.toBeInTheDocument()
  })
  it('订单查询失败可重试，不能保留旧二维码', async () => {
    const { state } = setup();state.fail = true;await open()
    await screen.findByRole('alert');expect(screen.queryByRole('img', { name: '微信支付二维码' })).not.toBeInTheDocument()
    state.fail = false;await userEvent.click(screen.getByRole('button', { name: '重试' }))
    await screen.findByRole('img', { name: '微信支付二维码' })
  })
  it('关闭后忽略迟到的订单详情', async () => {
    const { state } = setup();state.hold = true;await open()
    await waitFor(() => expect(state.resolve).toBeTypeOf('function'))
    await userEvent.click(screen.getByRole('button', { name: '关闭详情' }))
    await act(async () => state.resolve!(response(order)))
    expect(screen.queryByText(/订单号 history-order/)).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: '微信支付二维码' })).not.toBeInTheDocument()
  })
  it('自动读取失败暂停旧二维码，恢复读取后才恢复展示', async () => {
    vi.useFakeTimers({ toFake: ['setInterval','clearInterval'] })
    const { state } = setup();await open();await screen.findByRole('img', { name: '微信支付二维码' })
    state.fail = true;await act(async () => vi.advanceTimersByTimeAsync(3000))
    await screen.findByRole('alert');expect(screen.queryByRole('img', { name: '微信支付二维码' })).not.toBeInTheDocument()
    state.fail = false;await act(async () => vi.advanceTimersByTimeAsync(3000))
    await screen.findByRole('img', { name: '微信支付二维码' })
  })
})
