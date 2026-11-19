import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSession } from '@/lib/store'
import { NotificationRechargePage } from '@/pages/notification-recharge'
import { NotificationProviderPage } from '@/pages/notification-provider'
import { customer, management, response } from './fixtures'

const createdAt = '2026-11-01T00:00:00.000Z'
const order = (status: 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED' = 'PENDING') => ({ id: 'order-1', channel: 'SMS' as const, quantity: 100, amount_cents: 1000, status, payment_state: status === 'PENDING' ? 'CREATED' as const : status === 'PAID' ? 'PAID' as const : 'CLOSED' as const, code_url: 'https://qr.invalid/order-1', expires_at: null, created_at: createdAt, paid_at: status === 'PAID' ? createdAt : null })
const destinations = [{ owner_type: 'ORGANIZATION' as const, owner_id: 'tenant-a', owner_name: '单位A' }, { owner_type: 'PLACE' as const, owner_id: 'place-b', owner_name: '位置B' }]
const options = (items = destinations) => ({ policy: { mode: 'ORGANIZATION', revision: 0, updated_at: null }, destinations: items, pricing: { sms_price_cents: 10, voice_price_cents: 20, revision: 0, updated_at: null } })
function loginCustomer() { useSession.getState().login({ ...customer, permissions: ['notification-recharge:read', 'notification-recharge:write'] }, 'customer') }
function loginReadOnlyProvider() { useSession.getState().login({ ...management, permissions: ['notification-config:read'] }, 'management') }
function setupRecharge(handler: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined) {
  loginCustomer()
  const fetcher = vi.fn((url: string, init?: RequestInit) => handler(url, init) ?? response({}))
  vi.stubGlobal('fetch', fetcher)
  render(<NotificationRechargePage />)
  return fetcher
}
const endpoint = (url: string, suffix: string) => new URL(url, 'http://local').pathname.endsWith(suffix)

afterEach(() => { vi.useRealTimers() })
beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear(); vi.useRealTimers() })

describe('frontend notification regressions', () => {
  it('requires a recharge destination and confirms the full amount before creating an order', async () => {
    const fetcher = setupRecharge((url) => {
      if (endpoint(url, '/customer/notification-recharge/options')) return response(options())
      if (endpoint(url, '/customer/notification-recharge/orders')) return response({ items: [], total: 0, page: 1, size: 20 })
      return undefined
    })
    const user = userEvent.setup()
    await screen.findByText('新建充值订单')
    const destination = screen.getByLabelText('充值账户')
    expect(destination).toHaveValue('')
    await user.click(screen.getByRole('button', { name: '审核充值订单' }))
    expect(fetcher.mock.calls.some(call => endpoint(call[0], '/customer/notification-recharge/orders') && call[1]?.method === 'POST')).toBe(false)
    await user.selectOptions(destination, 'ORGANIZATION:tenant-a')
    await user.click(screen.getByRole('button', { name: '审核充值订单' }))
    const confirmation = await screen.findByRole('status', { name: '订单确认' })
    expect(confirmation).toHaveTextContent('单位A')
    expect(confirmation).toHaveTextContent('短信 100 条')
    expect(confirmation).toHaveTextContent('10.00 元')
    expect(fetcher.mock.calls.some(call => endpoint(call[0], '/customer/notification-recharge/orders') && call[1]?.method === 'POST')).toBe(false)
    await user.click(screen.getByRole('button', { name: '返回修改' }))
    expect(screen.queryByRole('status', { name: '订单确认' })).not.toBeInTheDocument()
    expect(fetcher.mock.calls.some(call => endpoint(call[0], '/customer/notification-recharge/orders') && call[1]?.method === 'POST')).toBe(false)
  })

  it('stale destination cannot silently become the first current destination', async () => {
    let refreshed = false
    const fetcher = setupRecharge((url) => {
      if (endpoint(url, '/customer/notification-recharge/options')) return response(options(refreshed ? [destinations[1]] : destinations))
      if (endpoint(url, '/customer/notification-recharge/orders')) return response({ items: [], total: 0, page: 1, size: 20 })
      return undefined
    })
    const user = userEvent.setup()
    await screen.findByText('新建充值订单')
    const ownerSelect = screen.getAllByRole('combobox')[0]
    await user.selectOptions(ownerSelect, 'ORGANIZATION:tenant-a')
    refreshed = true
    await user.click(screen.getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(fetcher.mock.calls.filter(call => endpoint(call[0], '/customer/notification-recharge/options'))).toHaveLength(2))
    await user.click(screen.getByRole('button', { name: '审核充值订单' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('所选充值账户已不可用')
    expect(fetcher.mock.calls.some(call => endpoint(call[0], '/customer/notification-recharge/orders') && call[1]?.method === 'POST')).toBe(false)
  })

  it('retries the same customer order intent with the same idempotency key', async () => {
    let first = true
    const fetcher = setupRecharge((url, init) => {
      if (endpoint(url, '/customer/notification-recharge/options')) return response(options())
      if (endpoint(url, '/customer/notification-recharge/orders') && init?.method === 'POST') {
        if (first) { first = false; return Promise.reject(new TypeError('synthetic dropped response')) as unknown as Response }
        return response(order())
      }
      if (endpoint(url, '/customer/notification-recharge/orders')) return response({ items: [], total: 0, page: 1, size: 20 })
      return undefined
    })
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('充值账户'), 'ORGANIZATION:tenant-a')
    await user.click(await screen.findByRole('button', { name: '审核充值订单' }))
    await user.click(screen.getByRole('button', { name: '确认创建订单' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: '审核充值订单' }))
    await user.click(screen.getByRole('button', { name: '确认创建订单' }))
    await screen.findByText(/订单号 order-1/)
    const writes = fetcher.mock.calls.filter(call => endpoint(call[0], '/customer/notification-recharge/orders') && call[1]?.method === 'POST')
    expect(writes).toHaveLength(2)
    expect((writes[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((writes[1][1]!.headers as Headers).get('Idempotency-Key'))
  })

  it('updates a pending customer order to a terminal status and stops polling', async () => {
    let detailCalls = 0
    const fetcher = setupRecharge((url, init) => {
      if (endpoint(url, '/customer/notification-recharge/options')) return response(options())
      if (endpoint(url, '/customer/notification-recharge/orders') && init?.method === 'POST') return response(order())
      if (endpoint(url, '/customer/notification-recharge/orders/order-1')) { detailCalls++; return response(order('CANCELLED')) }
      if (endpoint(url, '/customer/notification-recharge/orders')) return response({ items: [], total: 0, page: 1, size: 20 })
      return undefined
    })
    const user = userEvent.setup()
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    await user.selectOptions(await screen.findByLabelText('充值账户'), 'ORGANIZATION:tenant-a')
    await user.click(await screen.findByRole('button', { name: '审核充值订单' }))
    await user.click(screen.getByRole('button', { name: '确认创建订单' }))
    await screen.findByText('待支付订单')
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(screen.getByText('订单状态已更新：CANCELLED')).toBeInTheDocument()
    expect(screen.getByText(/状态 CANCELLED/)).toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(6000) })
    expect(detailCalls).toBe(1)
    expect(fetcher.mock.calls.some(call => endpoint(call[0], '/customer/notification-recharge/orders/order-1'))).toBe(true)
  })

  it('does not present a paid order as awaiting payment', async () => {
    const fetcher = setupRecharge((url, init) => {
      if (endpoint(url, '/customer/notification-recharge/options')) return response(options())
      if (endpoint(url, '/customer/notification-recharge/orders') && init?.method === 'POST') return response(order())
      if (endpoint(url, '/customer/notification-recharge/orders/order-1')) return response(order('PAID'))
      if (endpoint(url, '/customer/notification-recharge/orders')) return response({ items: [], total: 0, page: 1, size: 20 })
      return undefined
    })
    const user = userEvent.setup()
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    await user.selectOptions(await screen.findByLabelText('充值账户'), 'ORGANIZATION:tenant-a')
    await user.click(await screen.findByRole('button', { name: '审核充值订单' }))
    await user.click(screen.getByRole('button', { name: '确认创建订单' }))
    await screen.findByText('待支付订单')
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(screen.getByRole('heading', { name: '充值订单' })).toBeInTheDocument()
    expect(screen.getByText(/状态 PAID/)).toBeInTheDocument()
    expect(screen.queryByText('请使用微信扫码支付，支付成功后会自动刷新。')).not.toBeInTheDocument()
    expect(document.querySelector('.payment-qr')).toBeNull()
  })

  it('read-only provider users cannot submit configuration or test actions', async () => {
    loginReadOnlyProvider()
    const fetcher = vi.fn((url: string, _init?: RequestInit) => {
      const path = new URL(url, 'http://local').pathname
      if (path.endsWith('/management/notification-provider')) return response({ sms_enabled: false, voice_enabled: false, sms_sign_name: null, sms_template_code: null, tts_code: null, tts_called_show_number: null, sms_endpoint: 'https://sms.invalid/', voice_endpoint: 'https://voice.invalid/', access_key_id_masked: null, secret_configured: false, test_mode_enabled: false, test_phone_masked: null, revision: 0, updated_at: null })
      if (path.endsWith('/management/notification-provider/status')) return response({ real_enabled: false, sms_ready: false, voice_ready: false, secret_storage_ready: false, sms_enabled: false, voice_enabled: false, test_mode_enabled: false, test_phone_masked: null, revision: 0 })
      return response({})
    })
    vi.stubGlobal('fetch', fetcher)
    render(<NotificationProviderPage />)
    const sign = await screen.findByLabelText('短信签名')
    expect(sign).toBeDisabled()
    expect(screen.getByLabelText('AccessKey Secret')).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存全部通道配置' })).toBeDisabled()
    fireEvent.submit(sign.closest('form')!)
    expect(await screen.findByText(/没有通知通道写入权限/)).toHaveAttribute('role', 'alert')
    const testButton = screen.getByRole('button', { name: '发送真实测试通知' })
    expect(testButton).toBeDisabled()
    fireEvent.click(testButton)
    expect(fetcher.mock.calls.some(call => ['PUT', 'POST'].includes(call[1]?.method ?? ''))).toBe(false)
  })
})
