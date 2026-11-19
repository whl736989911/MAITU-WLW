import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationResourcesPage } from '@/pages/notification-resources'
import { useSession } from '@/lib/store'
import type { NotificationResourceAccount, PlaceNode } from '@/lib/types'
import { management, response } from './fixtures'

const unitA = { id: 'unit-a', name: '单位A' }, unitB = { id: 'unit-b', name: '单位B' }
const place = (id: string, name: string, parent_id: string | null, unit = unitA): PlaceNode => ({ id, name, parent_id, tenant_id: unit.id, tenant_name: unit.name, legacy_place_id: null, device_count: 0, level: parent_id ? 2 : 1, revision: 0 })
const directory = [place('east', '东楼', null), place('east-room', '205室', 'east'), place('west', '西楼', null), place('west-room', '205室', 'west'), place('foreign-room', '另一单位位置', null, unitB)]
const account = (owner: string, unit = unitA): NotificationResourceAccount => ({ id: `account-${owner}`, tenant_id: unit.id, tenant_name: unit.name, owner_type: 'PLACE', owner_id: owner, owner_name: '205室', sms_total: 10, sms_used: 0, sms_remaining: 10, voice_total: 1, voice_used: 0, voice_remaining: 1, enabled: true, revision: 1, updated_at: null })
beforeEach(() => {
  useSession.getState().clear(); localStorage.clear(); sessionStorage.clear()
  useSession.getState().login({ ...management, permissions: ['notification-resources:read', 'notification-resources:write', 'tenants:read'] }, 'management')
  useSession.getState().setScope([unitA.id, unitB.id], [], true)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => { vi.restoreAllMocks() })
function setup() {
  const state = { accounts: new Map([['east-room', account('east-room')], ['west-room', account('west-room')], ['foreign-room', account('foreign-room', unitB)]]), commits: 0, orders: 0, loseNext: false }
  const receipts = new Map<string, Record<string, unknown>>()
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://local').pathname, tenant = new Headers(init?.headers).get('X-Tenant-Id')
    if (path.endsWith('/management/tenants')) return response({ items: [unitA, unitB] })
    if (path.endsWith('/notification-resources/recharge')) {
      const body = JSON.parse(String(init?.body)), current = state.accounts.get(body.owner_id)
      if (!current || current.tenant_id !== tenant || body.owner_type !== 'PLACE') return response({ code: 'WRONG_FINANCIAL_TARGET' }, 400)
      const key = new Headers(init?.headers).get('Idempotency-Key')!
      if (receipts.has(key)) return response(receipts.get(key))
      const sms = body.channel === 'SMS', updated = { ...current, sms_total: current.sms_total + (sms ? body.quantity : 0), sms_remaining: current.sms_remaining + (sms ? body.quantity : 0), voice_total: current.voice_total + (sms ? 0 : body.quantity), voice_remaining: current.voice_remaining + (sms ? 0 : body.quantity), revision: current.revision + 1 }
      state.accounts.set(body.owner_id, updated);state.commits++
      const wire = Object.fromEntries(Object.entries(updated).filter(([key]) => key !== 'tenant_id' && key !== 'tenant_name'))
      receipts.set(key, wire)
      if (state.loseNext) { state.loseNext = false;return Promise.reject(new TypeError('QA committed place credit response lost')) }
      return response(wire)
    }
    if (path.endsWith('/orders') && init?.method === 'POST') { state.orders++;return response({ code: 'PAYMENT_NOT_EXERCISED' }, 400) }
    if (path.endsWith('/notification-resources')) return response([...state.accounts.values()])
    if (path.endsWith('/pricing')) return response({ sms_price_cents: 10, voice_price_cents: 20, revision: 0, updated_at: null })
    if (path.endsWith('/places')) return response(directory)
    if (path.endsWith('/orders') || path.endsWith('/provider-attempts')) return response({ items: [], total: 0, page: 1, size: 20 })
    throw new Error(`Unexpected place recharge request: ${url}`)
  }))
  render(<NotificationResourcesPage />)
  return state
}
async function openPlace() {
  await userEvent.click(await screen.findByText('单价与充值操作'))
  await userEvent.click(screen.getByLabelText('目标单位'))
  await userEvent.click(await screen.findByText('单位A · unit-a'))
  await userEvent.selectOptions(screen.getByLabelText('归属类型'), 'PLACE')
  await screen.findAllByRole('option', { name: /205室/ })
}
describe('位置资源充值的明确目标', () => {
  it('不自动选首个位置，未明确选择时不入账或建支付订单', async () => {
    const state = setup();await openPlace()
    expect(screen.getByLabelText('位置')).toHaveValue('')
    expect(screen.queryByRole('option', { name: /另一单位位置/ })).not.toBeInTheDocument()
   ;await userEvent.click(screen.getByRole('button', { name: '后台直接入账（不扫码）' }));await screen.findAllByRole('alert')
    await userEvent.click(screen.getByRole('button', { name: '生成微信二维码' }))
    expect(state.commits).toBe(0);expect(state.orders).toBe(0)
    expect(state.accounts.get('east-room')!.sms_remaining).toBe(10)
  })
  it('同名位置按完整路径选择，短信和语音只增加指定位置', async () => {
    const state = setup();await openPlace()
    expect(screen.getByRole('option', { name: '东楼 / 205室 · east-room' })).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('位置'), 'west-room')
    expect(screen.getByText('充值位置：单位A / 西楼 / 205室')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('短信条数'), { target: { value: '7' } })
    await userEvent.click(screen.getByRole('button', { name: '后台直接入账（不扫码）' }));await waitFor(() => expect(state.commits).toBe(1))
    await userEvent.selectOptions(screen.getByLabelText('资源类型'), 'VOICE')
    fireEvent.change(screen.getByLabelText('语音次数'), { target: { value: '3' } })
    await userEvent.click(screen.getByRole('button', { name: '后台直接入账（不扫码）' }));await waitFor(() => expect(state.commits).toBe(2))
    expect(state.accounts.get('west-room')!.sms_remaining).toBe(17);expect(state.accounts.get('west-room')!.voice_remaining).toBe(4)
    expect(state.accounts.get('east-room')).toEqual(account('east-room'));expect(state.accounts.get('foreign-room')).toEqual(account('foreign-room', unitB))
  })
  it('操作单位变更后清除旧位置，不带入其他单位的位置', async () => {
    setup();await openPlace();await userEvent.selectOptions(screen.getByLabelText('位置'), 'west-room')
    await userEvent.click(screen.getByLabelText('目标单位'))
    await userEvent.click(await screen.findByText('单位B · unit-b'))
    await screen.findByRole('option', { name: '另一单位位置 · foreign-room' })
    expect(screen.getByLabelText('位置')).toHaveValue('')
    expect(screen.queryByRole('option', { name: /205室/ })).not.toBeInTheDocument()
  })
  it('清空新单位的位置草稿，不清除未确认入账的原单位、完整路径和幂等编号', async () => {
    const state = setup();state.loseNext = true;await openPlace()
    await userEvent.selectOptions(screen.getByLabelText('位置'), 'west-room')
    fireEvent.change(screen.getByLabelText('短信条数'), { target: { value: '7' } })
    await userEvent.click(screen.getByRole('button', { name: '后台直接入账（不扫码）' }));await screen.findAllByRole('alert')
    await userEvent.click(screen.getByLabelText('目标单位'))
    await userEvent.click(await screen.findByText('单位B · unit-b'))
    expect(await screen.findByText(/原操作：单位A · 西楼 \/ 205室 · 短信 7 条/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '重试原入账' }))
    await userEvent.click(screen.getByRole('button', { name: '确认重试原入账' }))
    await screen.findAllByText(/后台入账已确认/)
    expect(state.commits).toBe(1);expect(state.accounts.get('west-room')!.sms_remaining).toBe(17)
    expect(state.accounts.get('foreign-room')).toEqual(account('foreign-room', unitB))
  })
})
