import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationRechargeSettingsPage } from '@/pages/notification-recharge-settings'
import { useSession } from '@/lib/store'
import type { ManagedUser, NotificationRechargeGrant, NotificationRechargeMode, PlaceNode } from '@/lib/types'
import { management, response } from './fixtures'

const unitA = { id: 'unit-a', name: '单位A' }, unitB = { id: 'unit-b', name: '单位B' }
const customer = (id: string, name: string, unit = unitA): ManagedUser => ({ id, name, username: id, user_type: 'CUSTOMER', tenant_id: unit.id, tenant_name: unit.name, enabled: true, must_change_password: false, revision: 0, is_protected: false, permissions: ['notification-recharge:read'], tenant_ids: [] })
const customers = [customer('foreign', '外单位客户', unitB), customer('first', '客户甲'), customer('second', '客户乙'), customer('third', '客户丙')]
const place = (id: string, name: string, parent_id: string | null, unit = unitA): PlaceNode => ({ id, name, parent_id, tenant_id: unit.id, tenant_name: unit.name, legacy_place_id: null, device_count: 0, level: parent_id ? 2 : 1, revision: 0 })
const places = [place('east', '东楼', null), place('east-room', '205室', 'east'), place('west', '西楼', null), place('west-room', '205室', 'west'), place('foreign-place', '外单位位置', null, unitB)]
const existing: NotificationRechargeGrant = { user_id: 'first', user_name: '客户甲', username: 'first', owner_type: 'PLACE', owner_id: 'west-room', owner_name: '205室', created_at: '2026-11-13T00:00:00Z' }
const rights = ['notification-resources:read', 'notification-resources:write', 'users:read', 'tenants:read']
beforeEach(() => {
  useSession.getState().clear(); localStorage.clear(); sessionStorage.clear()
  useSession.getState().login({ ...management, permissions: rights }, 'management')
  useSession.getState().setScope([unitA.id, unitB.id], [], true)
})
afterEach(() => { vi.restoreAllMocks() })
function setup(options: { existing?: boolean; readonly?: boolean; noUserRead?: boolean; confirmed?: boolean } = {}) {
  vi.spyOn(window, 'confirm').mockReturnValue(options.confirmed ?? true)
  if (options.readonly) useSession.setState({ permissions: ['notification-resources:read', 'users:read', 'tenants:read'] })
  if (options.noUserRead) useSession.setState({ permissions: ['notification-resources:read', 'notification-resources:write', 'tenants:read'] })
  const policies: Record<string, { mode: NotificationRechargeMode; revision: number; updated_at: string | null }> = { 'unit-a': { mode: 'ORGANIZATION', revision: 0, updated_at: null }, 'unit-b': { mode: 'ORGANIZATION', revision: 0, updated_at: null } }
  const state = { grants: options.existing ? [existing] : [] as NotificationRechargeGrant[], adds: 0, removes: 0, policies }
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const parsed = new URL(url, 'http://local'), path = parsed.pathname, tenant = new Headers(init?.headers).get('X-Tenant-Id'), page = Number(parsed.searchParams.get('page') ?? 1)
    if (path.endsWith('/management/tenants')) return response({ items: [unitA, unitB] })
    if (path.endsWith('/users')) {
      if (options.noUserRead) return response({ code: 'PERMISSION_DENIED' }, 403)
      const target = parsed.searchParams.get('scope_tenant_ids'), keyword = parsed.searchParams.get('keyword') ?? ''
      const matches = customers.filter(item => (!target || item.tenant_id === target) && (!keyword || item.name.includes(keyword) || item.username.includes(keyword)))
      return response({ items: matches.slice((page - 1) * 2, page * 2), total: matches.length, page, size: 2 })
    }
    if (path.endsWith('/places')) return response(places)
    if (path.endsWith('/policies')) return response({ items: Object.entries(state.policies).map(([tenant_id, policy]) => ({ ...policy, tenant_id, tenant_name: tenant_id === unitA.id ? unitA.name : unitB.name })), total: 2, page: 1, size: 20 })
    if (path.endsWith('/grants/overview')) return response({ items: state.grants.map(grant => ({ ...grant, tenant_id: unitA.id, tenant_name: unitA.name })), total: state.grants.length, page: 1, size: 20 })
    if (path.endsWith('/policy')) {
      if (tenant !== unitA.id && tenant !== unitB.id) return response({ code: 'UNIT_REQUIRED' }, 400)
      if (init?.method === 'PUT') { const body = JSON.parse(String(init.body));if (body.expected_revision !== state.policies[tenant].revision) return response({ code: 'REVISION_CONFLICT' }, 409);state.policies[tenant] = { mode: body.mode, revision: state.policies[tenant].revision + 1, updated_at: null } }
      return response(state.policies[tenant])
    }
    if (path.endsWith('/grants/revoke')) {
      if (tenant !== unitA.id) return response({ code: 'WRONG_UNIT' }, 400)
      const before = state.grants.length;state.grants = state.grants.filter(grant => grant.user_id !== parsed.searchParams.get('userId') || grant.owner_id !== parsed.searchParams.get('ownerId') || grant.owner_type !== parsed.searchParams.get('ownerType'))
      if (before === state.grants.length) return response({ code: 'MISSING' }, 404)
      state.removes++;return new Response(null, { status: 204 })
    }
    if (path.endsWith('/grants')) {
      if (init?.method === 'POST') {
        const body = JSON.parse(String(init.body)), user = customers.find(item => item.id === body.user_id), target = places.find(item => item.id === body.owner_id)
        if (!user || user.tenant_id !== tenant || (body.owner_type === 'PLACE' ? target?.tenant_id !== tenant : body.owner_id !== tenant)) return response({ code: 'WRONG_GRANT_TARGET' }, 400)
        const grant: NotificationRechargeGrant = { user_id: user.id, user_name: user.name, username: user.username, owner_type: body.owner_type, owner_id: body.owner_id, owner_name: target?.name ?? unitA.name, created_at: existing.created_at }
        state.grants.push(grant);state.adds++;return response(grant)
      }
      return response(tenant === unitA.id ? state.grants : [])
    }
    throw new Error(`Unexpected recharge grant request: ${url}`)
  }))
  render(<NotificationRechargeSettingsPage />)
  return state
}
async function targetUnit() { await userEvent.click(await screen.findByLabelText('目标单位')); await userEvent.click(await screen.findByText('单位A · unit-a')) }
async function ready() { await targetUnit(); await screen.findByRole('option', { name: '客户甲（first）' }) }
async function choosePlace() { await userEvent.selectOptions(screen.getByLabelText('充值账户类型'), 'PLACE');await userEvent.selectOptions(screen.getByLabelText('位置'), 'west-room') }
describe('客户充值账户授权选择与撤销', () => {
  it('不默认客户或位置，只提供当前单位对象', async () => {
    const state = setup();await ready()
    expect(screen.getByLabelText('客户用户')).toHaveValue('');expect(screen.queryByRole('option', { name: /外单位客户/ })).not.toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('充值账户类型'), 'PLACE')
    expect(screen.getByLabelText('位置')).toHaveValue('');expect(screen.queryByRole('option', { name: /外单位位置/ })).not.toBeInTheDocument()
   ;await userEvent.click(screen.getByRole('button', { name: '新增授权' }));await screen.findByRole('alert');expect(state.adds).toBe(0)
  })
  it('第二页客户可授权到指定同名位置，并可撤销该记录', async () => {
    const state = setup();await ready();const picker = within(screen.getByRole('region', { name: '选择客户用户' }))
    await userEvent.click(picker.getByRole('button', { name: 'Next Page' }));await screen.findByRole('option', { name: '客户丙（third）' })
    expect(screen.getByLabelText('客户用户')).toHaveValue('');await userEvent.selectOptions(screen.getByLabelText('客户用户'), 'third');await choosePlace()
    expect(screen.getByRole('option', { name: '东楼 / 205室 · east-room' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '新增授权' }));await screen.findByRole('article', { name: '客户丙的西楼 / 205室充值授权' })
    expect(state.grants.map(grant => [grant.user_id, grant.owner_type, grant.owner_id])).toEqual([['third', 'PLACE', 'west-room']])
   ;await userEvent.click(screen.getByRole('button', { name: '撤销客户丙的西楼 / 205室充值授权' }))
    await waitFor(() => expect(screen.queryByRole('article', { name: '客户丙的西楼 / 205室充值授权' })).not.toBeInTheDocument())
    expect(state.grants).toEqual([]);expect(state.removes).toBe(1)
  })
  it('回车搜索客户不提交授权，并清除旧客户选择', async () => {
    const state = setup();await ready();await userEvent.selectOptions(screen.getByLabelText('客户用户'), 'first')
    await userEvent.type(screen.getByLabelText('搜索本单位客户'), '客户丙{Enter}');await screen.findByRole('option', { name: '客户丙（third）' })
    expect(screen.getByLabelText('客户用户')).toHaveValue('');expect(screen.queryByRole('alert')).not.toBeInTheDocument();expect(state.adds).toBe(0)
  })
  it('实际操作单位改变时清除授权草稿，不把其他单位对象留给当前单位', async () => {
    const state = setup();await ready();await userEvent.selectOptions(screen.getByLabelText('客户用户'), 'first');await choosePlace()
    await userEvent.click(screen.getByLabelText('目标单位'))
    await userEvent.click(await screen.findByText('单位B · unit-b'))
    expect(screen.getByLabelText('客户用户')).toHaveValue('');expect(screen.getByLabelText('充值账户类型')).toHaveValue('ORGANIZATION');expect(state.adds).toBe(0)
  })
  it('只读账户保留完整授权归属但不能新增或撤销', async () => {
    const state = setup({ existing: true, readonly: true });await targetUnit();await screen.findByRole('article', { name: '客户甲的西楼 / 205室充值授权' })
    expect(screen.getByRole('button', { name: '新增授权' })).toBeDisabled();expect(screen.getByRole('button', { name: '撤销客户甲的西楼 / 205室充值授权' })).toBeDisabled();expect(screen.getByRole('button', { name: '保存充值模式' })).toBeDisabled();expect(state.adds + state.removes).toBe(0)
  })
  it('修改模式只作用于当前单位', async () => {
    const state = setup();await targetUnit();await screen.findByLabelText('模式');await userEvent.selectOptions(screen.getByLabelText('模式'), 'GRANTS')
    await userEvent.click(screen.getByRole('button', { name: '保存充值模式' }))
    await waitFor(() => expect(state.policies[unitA.id].mode).toBe('GRANTS'));expect(state.policies[unitA.id].revision).toBe(1);expect(state.policies[unitB.id].mode).toBe('ORGANIZATION')
  })
  it('未通过二次确认时不写入授权记录', async () => {
    const state = setup({ existing: true, confirmed: false });await ready()
    await userEvent.selectOptions(screen.getByLabelText('客户用户'), 'second');await choosePlace()
    await userEvent.click(screen.getByRole('button', { name: '新增授权' }))
    await userEvent.click(screen.getByRole('button', { name: '撤销客户甲的西楼 / 205室充值授权' }))
    expect(state.adds + state.removes).toBe(0)
    expect(state.grants).toHaveLength(1)
  })
  it('没有用户读取权限时不冒充可选择客户或丢失原会话', async () => {
    setup({ noUserRead: true });await targetUnit();await screen.findByText('选择客户需要客户用户读取权限，请联系管理员。')
    expect(screen.getByLabelText('客户用户')).toBeDisabled();expect(screen.getByRole('button', { name: '新增授权' })).toBeDisabled();expect(useSession.getState().user).not.toBeNull()
  })
})
