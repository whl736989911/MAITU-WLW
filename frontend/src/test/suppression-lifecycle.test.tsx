import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '@/lib/store'
import { SuppressionsPage } from '@/pages/suppressions'
import { management, response } from './fixtures'

const moment = '2030-04-01T10:00:00.000Z'
const page = (items: unknown[], total = items.length) => ({ items, total, page: 1, size: 20 })
const record = { id: 'sup-a', tenant_id: 'tenant-a', kind: 'NOTIFICATION', scope: 'DEVICES', fire_confirmed: false, reason: '设备维护', starts_at: '2030-04-01T10:00:00Z', ends_at: '2030-04-02T10:00:00Z', status: 'ACTIVE', revision: 7, enabled: true, deleted: false, effective_state: 'ACTIVE', created_at: moment, cancelled_at: null, cancel_reason: null }
const channel = { id: 'channel-a', tenant_id: 'tenant-a', device_id: 'device-a', device_name: '一号烟感', channel_key: 'smoke-1', name: '厨房烟感', channel_type: 'SMOKE', enabled: true, revision: 2, device_simulated: false, created_at: moment, updated_at: moment }
function login() {
  useSession.getState().login({ ...management, permissions: ['suppressions:read', 'suppressions:write', 'device:read', 'device:write'] }, 'management')
  useSession.getState().setScope(['tenant-a'], [])
}
function setup(fetcher: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  login()
  const fetchMock = vi.fn(fetcher)
  vi.stubGlobal('fetch', fetchMock)
  render(<SuppressionsPage />)
  return fetchMock
}
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
async function sensitiveConfirm(user: ReturnType<typeof userEvent.setup>, first: string, final: string) {
  await user.click(screen.getByRole('button', { name: first }))

  await user.click(screen.getByRole('button', { name: final }))
}

beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })

describe('notification suppression lifecycle', () => {
  it('creates sensor suppression for explicitly selected channel IDs, never device IDs', async () => {
    const fetcher = setup((url, init) => {
      const path = new URL(url, 'http://local').pathname
      if (path.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places: [] }])
      if (path.endsWith('/device-channels')) return response(page([channel]))
      if (path.endsWith('/devices')) return response(page([{ id: 'device-a', name: '一号烟感', device_eui: 'eui-a' }]))
      if (path.endsWith('/suppressions') && init?.method === 'POST') return response({ ...record, scope: 'SENSORS' })
      if (path.endsWith('/suppressions')) return response(page([]))
      throw new Error(`Unexpected request ${url}`)
    })
    const user = userEvent.setup()
    await screen.findByText('暂无屏蔽记录')
    await user.click(screen.getByRole('tab', { name: '传感器资源' }))
    await user.click(screen.getByRole('button', { name: '创建屏蔽' }))
    await user.selectOptions(await screen.findByLabelText('配置目标单位'), 'tenant-a')
    await user.selectOptions(await screen.findByLabelText('屏蔽范围'), 'SENSORS')
    await screen.findByText(/厨房烟感/)
    await user.click(screen.getByRole('checkbox', { name: /厨房烟感/ }))
    change('开始时间', '2030-04-01T10:00')
    change('结束时间', '2030-04-02T10:00')
    change('原因', '通道检修')
    await sensitiveConfirm(user, '核对并继续', '确认创建')
    await waitFor(() => expect(fetcher.mock.calls.some(call => call[1]?.method === 'POST' && call[0].endsWith('/suppressions'))).toBe(true))
    const [url, init] = fetcher.mock.calls.find(call => call[1]?.method === 'POST' && call[0].endsWith('/suppressions'))!
    const body = JSON.parse(String(init?.body))
    expect(body).toMatchObject({ scope: 'SENSORS', channel_ids: ['channel-a'], starts_at: new Date('2030-04-01T10:00').toISOString(), ends_at: new Date('2030-04-02T10:00').toISOString() })
    expect(body).not.toHaveProperty('device_ids')
    expect((init?.headers as Headers).get('Idempotency-Key')).toBeTruthy()
    expect((init?.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a')
    expect(new URL(url, 'http://local').search).toBe('')
  })

  it('ignores a late response from an earlier browse scope', async () => {
    let resolveOld!: (value: Response) => void
    const fetcher = setup((url) => {
      const query = new URL(url, 'http://local').searchParams
      if (!url.includes('/suppressions?')) throw new Error(`Unexpected request ${url}`)
      if (query.get('scope_tenant_ids') === 'tenant-a') return new Promise<Response>(resolve => { resolveOld = resolve })
      return response(page([{ ...record, id: 'scope-b-record', tenant_id: 'tenant-b', reason: '新范围记录' }]))
    })
    await act(async () => { useSession.getState().setScope(['tenant-b'], []) })
    await screen.findByText('新范围记录')
    await act(async () => { resolveOld(response(page([{ ...record, id: 'scope-a-record', reason: '旧范围记录' }]))) })
    expect(screen.getByText('新范围记录')).toBeInTheDocument()
    expect(screen.queryByText('旧范围记录')).not.toBeInTheDocument()
    expect(fetcher.mock.calls.some(call => call[1]?.signal?.aborted)).toBe(true)
  })

  it('adopts the persisted disabled state under its record owner within an aggregate scope', async () => {
    let persisted = { ...record, scope: 'TENANT' }
    const fetcher = setup((url, init) => {
      if (url.endsWith('/suppressions/sup-a/enabled')) { persisted = { ...persisted, enabled: false, effective_state: 'DISABLED', revision: 8 }; return response(persisted) }
      if (url.endsWith('/sup-a/history') || url.endsWith('/sup-a/targets')) return response([])
      if (url.includes('/suppressions?')) return response(page([persisted]))
      throw new Error(`Unexpected request ${url}`)
    })
    useSession.getState().setScope(['tenant-a', 'tenant-b'], [])
    const user = userEvent.setup()
    await screen.findByText('设备维护')
    await user.click(screen.getByRole('button', { name: '停用' }))
    change('变更原因', '维护暂停派发')
    await sensitiveConfirm(user, '核对并继续', '确认操作')
    await screen.findByRole('button', { name: '启用' })
    expect(screen.getByText('DISABLED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '停用' })).not.toBeInTheDocument()
    const [, init] = fetcher.mock.calls.find(call => call[1]?.method === 'POST' && call[0].endsWith('/sup-a/enabled'))!
    expect((init?.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a')
  })

  it('refreshes the persisted state after a conflict before reopening a mutation', async () => {
    let persisted = { ...record, scope: 'TENANT' }
    let attempts = 0
    setup((url, init) => {
      if (url.endsWith('/sup-a/enabled')) {
        if (++attempts === 1) {
          persisted = { ...persisted, enabled: false, effective_state: 'DISABLED', revision: 8 }
          return response({ code: 'REVISION_CONFLICT', message: '记录已被其他操作更新' }, 409)
        }
        const body = JSON.parse(String(init?.body))
        if (body.expected_revision !== persisted.revision) return response({ code: 'REVISION_CONFLICT' }, 409)
        persisted = { ...persisted, enabled: true, effective_state: 'ACTIVE', revision: 9 }
        return response(persisted)
      }
      if (url.endsWith('/sup-a/history') || url.endsWith('/sup-a/targets')) return response([])
      if (url.includes('/suppressions?')) return response(page([persisted]))
      throw new Error(`Unexpected request ${url}`)
    })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '停用' }))
    change('变更原因', '暂停维护')
    await sensitiveConfirm(user, '核对并继续', '确认操作')
    await user.click(await screen.findByRole('button', { name: '刷新后重新打开' }))
    await user.click(await screen.findByRole('button', { name: '启用' }))
    change('变更原因', '恢复维护屏蔽')
    await sensitiveConfirm(user, '核对并继续', '确认操作')
    await screen.findByRole('button', { name: '停用' })
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
    expect(screen.queryByText('DISABLED')).not.toBeInTheDocument()
  })

  it('shows local edit times and preserves untouched precise instants outside UTC', async () => {
    const offset = vi.spyOn(Date.prototype, 'getTimezoneOffset').mockReturnValue(-480)
    const precise = { ...record, scope: 'TENANT', starts_at: '2030-04-01T10:00:12.123456Z', ends_at: '2030-04-02T10:00:45.654321Z' }
    let persisted = precise
    try {
      const fetcher = setup((url, init) => {
        if (url.endsWith('/sup-a') && init?.method === 'PUT') { persisted = { ...precise, revision: 8 }; return response(persisted) }
        if (url.endsWith('/sup-a/history') || url.endsWith('/sup-a/targets')) return response([])
        if (url.includes('/suppressions?')) return response(page([persisted]))
        throw new Error(`Unexpected request ${url}`)
      })
      const user = userEvent.setup()
      await screen.findByText('设备维护')
      await user.click(screen.getByRole('button', { name: '编辑期限' }))
      expect(screen.getByLabelText('开始时间')).toHaveValue('2030-04-01T18:00')
      expect(screen.getByLabelText('结束时间')).toHaveValue('2030-04-02T18:00')
      change('变更原因', '保留原有精确时段')
      await sensitiveConfirm(user, '核对并继续', '确认操作')
      await waitFor(() => expect(screen.queryByRole('heading', { name: '编辑屏蔽期限' })).not.toBeInTheDocument())
      const [, init] = fetcher.mock.calls.find(call => call[1]?.method === 'PUT')!
      const submitted = JSON.parse(String(init?.body))
      expect(submitted.starts_at).toBe(precise.starts_at)
      expect(submitted.ends_at).toBe(precise.ends_at)
    } finally { offset.mockRestore() }
  })
})
