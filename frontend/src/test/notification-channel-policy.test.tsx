import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationChannelPolicyView } from '@/components/notification-channel-policy'
import { useSession } from '@/lib/store'
import type { NotificationChannelPolicy, ScopePlaceNode } from '@/lib/types'
import { management, response } from './fixtures'

const place = (id: string, name: string, parent_id: string | null): ScopePlaceNode => ({ id, name, parent_id, tenant_id: 'tenant-a', tenant_name: '单位A', legacy_place_id: null, device_count: 0, level: parent_id ? 2 : 1, revision: 0, parent_context: null })
const directory = [place('east', '1号楼', null), place('west', '2号楼', null), place('east-room', '205室', 'east'), place('west-room', '205室', 'west')]
function policy(placeId: string | null): NotificationChannelPolicy {
  return { tenant_id: 'tenant-a', scope_type: placeId ? 'PLACE' : 'UNIT', place_id: placeId, configured: !placeId, revision: placeId ? 0 : 2, sms_enabled: placeId ? null : true, voice_enabled: placeId ? null : false, wechat_enabled: null, effective_sms: true, effective_voice: false, effective_wechat: false, sms_source: placeId ? { scope_type: 'PLACE', scope_id: placeId === 'east-room' ? 'east' : 'west', name: placeId === 'east-room' ? '1号楼' : '2号楼' } : { scope_type: 'UNIT', scope_id: 'tenant-a', name: '单位A' }, voice_source: { scope_type: 'UNIT', scope_id: 'tenant-a', name: '单位A' }, wechat_source: null }
}
beforeEach(() => {
  useSession.getState().clear(); localStorage.clear(); sessionStorage.clear()
  useSession.getState().login({ ...management, permissions: ['notification-config:read', 'notification-config:write'] }, 'management')
  useSession.getState().setScope(['tenant-a', 'tenant-b'], [], true)
})
function setup(options: { holdPlace?: boolean; wrongTarget?: 'unit' | 'place'; missingParent?: boolean; readonly?: boolean } = {}) {
  const state = { writes: 0, resolve: undefined as ((reply: Response) => void) | undefined }
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const parsed = new URL(url, 'http://local'), tenant = new Headers(init?.headers).get('X-Tenant-Id')
    if (parsed.pathname.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places: options.missingParent ? directory.filter(item => item.id === 'east-room') : directory }, { id: 'tenant-b', name: '单位B', places: [] }])
    if (parsed.pathname.endsWith('/notification-channel-policy')) {
      if (tenant !== 'tenant-a') return response({ code: 'WRONG_UNIT' }, 404)
      if (init?.method === 'PUT') {
        state.writes++; return response({ code: 'UNEXPECTED_WRITE' }, 400)
      }
      const placeId = parsed.searchParams.get('placeId')
      if (placeId && options.holdPlace) return new Promise<Response>(resolve => { state.resolve = resolve })
      return response({ ...policy(placeId), ...(placeId && options.wrongTarget === 'unit' ? { tenant_id: 'tenant-b' } : placeId && options.wrongTarget === 'place' ? { place_id: 'different-room' } : {}) })
    }
    throw new Error(`Unexpected channel policy request: ${url}`)
  }))
  render(<NotificationChannelPolicyView canRead canWrite={!options.readonly} />)
  return state
}
async function chooseUnit() {
  await screen.findByRole('option', { name: '单位A · tenant-a' })
  await userEvent.selectOptions(screen.getByLabelText('配置目标单位'), 'tenant-a')
  await screen.findByLabelText('策略层级')
}
async function choosePlace(id = 'east-room') {
  await chooseUnit();await userEvent.selectOptions(screen.getByLabelText('策略层级'), 'PLACE')
  await screen.findByRole('option', { name: /205室.*east-room/ })
  await userEvent.selectOptions(screen.getByLabelText('目标位置'), id)
}
describe('位置通道策略路径和配置上下文', () => {
  it('同名二级位置显示完整路径，确认时保留所选楼栋与单位', async () => {
    const state = setup();await choosePlace('west-room')
    expect(screen.getByRole('option', { name: '1号楼 / 205室 · east-room' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '2号楼 / 205室 · west-room' })).toBeInTheDocument()
    await screen.findByLabelText('短信显式值')
    expect(screen.getByText(/当前目标：位置「单位A \/ 2号楼 \/ 205室」/)).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('短信显式值'), 'DISABLED')
    fireEvent.change(screen.getByLabelText('本次操作原因'), { target: { value: '只停用2号楼205室的短信' } })
    await userEvent.click(screen.getByRole('button', { name: '核对策略变更' }))
    const confirmation = screen.getByText(/单位A \/ 2号楼 \/ 205室 · 短信 显式停用/)
    expect(confirmation).toBeInTheDocument();expect(state.writes).toBe(0)
  })
  it('实际配置目标改变时清除旧确认与草稿', async () => {
    const state = setup();await choosePlace();await screen.findByLabelText('短信显式值')
    fireEvent.change(screen.getByLabelText('本次操作原因'), { target: { value: '目标改变前的草稿' } })
    await userEvent.click(screen.getByRole('button', { name: '核对策略变更' }))

    await userEvent.selectOptions(screen.getByLabelText('配置目标单位'), '')
    await waitFor(() => expect(screen.queryByRole('button', { name: '确认保存策略' })).not.toBeInTheDocument())
    expect(screen.queryByLabelText('短信显式值')).not.toBeInTheDocument();expect(state.writes).toBe(0)
  })
  it('迟到策略响应不能恢复已取消的明确目标', async () => {
    const state = setup({ holdPlace: true });await choosePlace();await waitFor(() => expect(state.resolve).toBeTypeOf('function'))
    await userEvent.selectOptions(screen.getByLabelText('配置目标单位'), '')
    await act(async () => state.resolve!(response(policy('east-room'))))
    expect(screen.queryByLabelText('短信显式值')).not.toBeInTheDocument();expect(state.writes).toBe(0)
  })
  it('目录刷新但真实目标不变时保留未提交的策略确认草稿', async () => {
    const state = setup(); await choosePlace(); await screen.findByLabelText('短信显式值')
    fireEvent.change(screen.getByLabelText('本次操作原因'), { target: { value: '目录刷新前尚未提交的策略' } })
    await userEvent.click(screen.getByRole('button', { name: '核对策略变更' }))

    await act(async () => useSession.getState().refreshScopeTree())
    expect(screen.getByLabelText('配置目标单位')).toHaveValue('tenant-a')
    expect(screen.getByRole('button', { name: '确认保存策略' })).toBeInTheDocument()
    expect(state.writes).toBe(0)
  })
  it.each(['unit', 'place'] as const)('返回其他 %s 的策略时不开放编辑', async wrongTarget => {
    const state = setup({ wrongTarget });await choosePlace();await screen.findByRole('alert')
    expect(screen.queryByLabelText('短信显式值')).not.toBeInTheDocument();expect(state.writes).toBe(0)
  })
  it('上级不在授权目录时明确标识，不把二级位置显示为一级', async () => {
    setup({ missingParent: true, readonly: true });await choosePlace()
    expect(screen.getByRole('option', { name: '上级不在当前范围 / 205室 · east-room' })).toBeInTheDocument()
    await screen.findByText(/当前目标：位置「单位A \/ 上级不在当前范围 \/ 205室」/)
    expect(screen.queryByRole('button', { name: '核对策略变更' })).not.toBeInTheDocument()
  })
})
