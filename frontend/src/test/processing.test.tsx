import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSession } from '@/lib/store'
import type { ProcessingTask } from '@/lib/types'
import { TasksPage } from '@/pages/tasks'
import { management, response } from './fixtures'

const legacyResult = JSON.stringify({ event_id: 'old-event', alarm_id: 'old-alarm', event_type: 'FIRE', physical_state: 'FIRE', battery_percent: 0, business_status: 'APPLIED_SIMULATION', time_basis: 'PLATFORM_RECEIVED_SIMULATION' })
const task: ProcessingTask = { id: 'old-task', kind: 'SIMULATED_MESSAGE_APPLY', tenant_id: 'tenant-a', raw_message_id: 'old-raw', source_id: 'old-source', source_message_id: 'old-message', device_id: 'old-device', device_name: '历史设备快照', product_version_id: 'old-version', actor_id: 'old-actor', expected_device_revision: 0, recovery_alarm_id: null, reason: '历史模拟处理', status: 'SUCCEEDED', revision: 2, attempt_count: 1, max_attempts: 1, available_at: '2026-10-23T00:00:00Z', lease_owner: null, lease_until: null, lease_token: 1, created_at: '2026-10-23T00:00:00Z', finished_at: '2026-10-23T00:01:00Z', last_error_code: null, result: legacyResult }
const page = { items: [task], total: 1, page: 1, size: 20 }
function login() { useSession.getState().login({ ...management, permissions: ['tasks:read'] }, 'management'); useSession.getState().setScope(['tenant-a'], [], true) }
function responseFor(url: string): Response { if (url.includes('/processing-tasks?')) return response(page); if (url.endsWith(`/processing-tasks/${task.id}`)) return response({ task, attempts: [] }); throw new Error(`Unexpected task request ${url}`) }
beforeEach(() => { useSession.getState().clear(); vi.restoreAllMocks() })

describe('真实消息处理与任务历史', () => {
  it('退役模拟任务仍只读标注为历史来源，不提供执行操作', async () => {
    login(); const fetcher = vi.fn().mockImplementation(responseFor); vi.stubGlobal('fetch', fetcher); render(<TasksPage/>); const user = userEvent.setup()
    const row = await screen.findByText(task.id); await user.click(row)
    expect(await screen.findByText(/历史退役模拟处理，仅用于溯源/)).toBeInTheDocument()
    expect(screen.getByText(/SIMULATED_MESSAGE_APPLY · SUCCEEDED/)).toBeInTheDocument()
    expect(screen.queryByText(legacyResult)).not.toBeInTheDocument()
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByRole('button', { name: /取消任务|重试|重放|提交/ })).not.toBeInTheDocument()
    expect(fetcher.mock.calls.every(([, init]) => !['POST', 'PUT', 'DELETE'].includes(init?.method ?? 'GET'))).toBe(true)
  })
  it('任务列表仍保留原分页/状态查询契约且只读取', async () => {
    login(); const fetcher = vi.fn().mockImplementation(responseFor); vi.stubGlobal('fetch', fetcher); render(<TasksPage/>); const user = userEvent.setup()
    await screen.findByText(task.id); await user.selectOptions(screen.getByLabelText('任务状态'), 'SUCCEEDED'); await user.click(screen.getByRole('button', { name: '查询' }))
    await waitFor(() => expect(fetcher.mock.calls.length).toBeGreaterThan(1))
    expect(new URL(fetcher.mock.calls.at(-1)![0], 'http://local').searchParams.get('status')).toBe('SUCCEEDED')
    expect(fetcher.mock.calls.every(([, init]) => !['POST', 'PUT', 'DELETE'].includes(init?.method ?? 'GET'))).toBe(true)
  })
})
