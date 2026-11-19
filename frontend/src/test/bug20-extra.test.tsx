import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSession } from '@/lib/store'
import type { ProfileRule, ProfileSnapshot, RawMessage, ReparseJob } from '@/lib/types'
import { MessagesPage } from '@/pages/messages'
import { ProfilesPage } from '@/pages/profiles'
import { management, response } from './fixtures'

const actor = 'SyntheticExtraActor!'
const profilePermissions = ['profiles:read']
const messagePermissions = ['messages:read', 'messages:reparse']
const rule: ProfileRule = { id: 'rule-extra', tenant_id: 'tenant-a', version: 1, window_days: 7, min_samples: 2, enabled: true, reason: 'synthetic rule', created_at: '2026-11-01T00:00:00Z' }
const message: RawMessage = { id: 'raw-extra', tenant_id: 'tenant-a', source_id: 'source-extra', source_name: 'synthetic', source_message_id: 'stable-extra', vendor_device_id: 'opaque-extra', device_id: 'device-extra', device_name: 'synthetic device', binding_id: 'binding-extra', product_version_id: 'version-extra', received_at: '2026-11-01T00:00:00Z', reported_at: null, occurred_at: null, occurred_at_raw: null, payload_bytes: 1, payload_sha256: 'synthetic-sha', binding_state: 'BOUND', duplicate_count: 0, revision: 0, latest_preview_id: null, parse_status: 'NOT_PREVIEWED', business_status: 'NOT_APPLIED', application_state: 'NOT_APPLIED', applied_at: null, apply_error: null, capture_origin: 'LEGACY_UNVERIFIED' }
const page = (items: unknown[]) => ({ items, page: 1, size: 20, total: items.length })
function profile(id: string, samples: number): ProfileSnapshot & { tenant_id: string } { return { id: `snapshot-${id}`, tenant_id: 'tenant-a', device_id: id, rule_id: rule.id, rule_version: 1, window_days: 7, min_samples: 2, status: 'OK', sample_count: samples, latest_percent: '50', min_percent: '40', max_percent: '60', mean_percent: '50', window_start: '2026-10-25T00:00:00Z', window_end: '2026-11-01T00:00:00Z', computed_at: `2026-11-01T00:00:${String(samples).padStart(2, '0')}Z`, reason: `synthetic-${id}` } }
function reparseJob(status: ReparseJob['status']): ReparseJob { return { id: 'reparse-extra', tenant_id: 'tenant-a', raw_message_id: message.id, product_version_id: 'version-extra', status, attempt_count: status === 'SUCCEEDED' ? 1 : 0, max_attempts: 3, revision: status === 'SUCCEEDED' ? 1 : 0, created_at: '2026-11-01T00:00:00Z', started_at: null, finished_at: status === 'SUCCEEDED' ? '2026-11-01T00:00:01Z' : null, last_error_code: null, result_json: status === 'SUCCEEDED' ? '{"event_type":"UNKNOWN"}' : null } }
function login(permissions: string[]) { useSession.getState().login({ ...management, permissions }, 'management'); useSession.getState().setScope(['tenant-a'], [], true) }
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }) }
beforeEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })
afterEach(() => vi.useRealTimers())

describe('extra functional regressions', () => {
  it('profile read for an older device cannot replace a newer device result', async () => {
    login(profilePermissions)
    let resolveOld!: (value: Response) => void
    const old = profile('device-old', 11), current = profile('device-current', 22)
    const fetcher = vi.fn((url: string) => {
      if (url === '/api/management/scope-tree') return Promise.resolve(response([{ id: 'tenant-a', name: 'Synthetic tenant', places: [] }]))
      if (url.includes('/profile-rules?')) return Promise.resolve(response({ items: [rule] }))
      if (url.includes('/devices/device-old/profile?')) return new Promise<Response>(resolve => { resolveOld = resolve })
      if (url.includes('/devices/device-current/profile?')) return Promise.resolve(response(current))
      if (url.includes('/devices/device-current/profile/history?')) return Promise.resolve(response([]))
      if (url.includes('/devices/device-old/profile/history?')) return Promise.resolve(response([old]))
      throw new Error(`Unexpected synthetic profile endpoint: ${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    render(<ProfilesPage/>)
    await userEvent.setup().click(screen.getByRole('tab', { name: '电量观测快照' }))
    await screen.findByText('v1')
    fill('设备所属单位（读取 / 配置目标）', 'tenant-a')
    fill('设备ID', 'device-old'); await userEvent.setup().click(screen.getByRole('button', { name: '读取当前画像' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([url]) => String(url).includes('/devices/device-old/profile?'))).toBe(true))
    fill('设备ID', 'device-current'); await userEvent.setup().click(screen.getByRole('button', { name: '读取当前画像' }))
    await screen.findByText('22')
    await act(async () => { resolveOld(response(old)); await Promise.resolve(); await Promise.resolve() })
    expect(screen.getByText('22')).toBeInTheDocument()
    expect(screen.queryByText('11')).not.toBeInTheDocument()
  })

  it('profile history read failures are shown and do not leave a stale successful snapshot', async () => {
    login(profilePermissions)
    const fetcher = vi.fn((url: string) => {
      if (url === '/api/management/scope-tree') return Promise.resolve(response([{ id: 'tenant-a', name: 'Synthetic tenant', places: [] }]))
      if (url.includes('/profile-rules?')) return Promise.resolve(response({ items: [rule] }))
      if (url.includes('/devices/device-error/profile?')) return Promise.resolve(response(profile('device-error', 7)))
      if (url.includes('/devices/device-error/profile/history?')) return Promise.resolve(response({ code: 'SYNTHETIC_FAILURE' }, 503))
      throw new Error(`Unexpected synthetic profile endpoint: ${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    render(<ProfilesPage/>)
    await userEvent.setup().click(screen.getByRole('tab', { name: '电量观测快照' }))
    await screen.findByText('v1')
    fill('设备所属单位（读取 / 配置目标）', 'tenant-a')
    fill('设备ID', 'device-error'); await userEvent.setup().click(screen.getByRole('button', { name: '读取当前画像' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('SYNTHETIC_FAILURE')
    expect(screen.queryByText('7')).not.toBeInTheDocument()
  })

  it('pending reparse status can be refreshed after the bounded automatic polling window', async () => {
    login(messagePermissions)
    const user = userEvent.setup()
    let statusReads = 0
    const fetcher = vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('/messages?')) return Promise.resolve(response(page([message])))
      if (url === `/api/management/messages/${message.id}`) return Promise.resolve(response({ message, previews: [] }))
      if (url.endsWith('/reparse') && init?.method === 'POST') return Promise.resolve(response(reparseJob('PENDING')))
      if (url.endsWith('/reparse-jobs/reparse-extra')) {
        statusReads += 1
        const status = statusReads <= 20 ? 'PENDING' : 'SUCCEEDED'
        return Promise.resolve(response({ job: reparseJob(status), attempts: [] }))
      }
      throw new Error(`Unexpected synthetic reparse endpoint: ${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    render(<MessagesPage/>)
    await user.click(await screen.findByRole('button', { name: '查看追踪' }))
    await user.click(await screen.findByRole('button', { name: '创建重解析任务' }))
    fill('重解析原因', '等待synthetic worker')

    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('button', { name: '提交' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(20 * 400) })
    expect(statusReads).toBe(20)
    expect(screen.getByRole('button', { name: '刷新重解析任务' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '刷新重解析任务' }))
    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument()
    expect(statusReads).toBe(21)
  }, 20_000)
})
