import { beforeEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AlarmBaseConfigurationPage } from '@/pages/alarm-base-config'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

const config = {
  event_kind: 'FIRE',
  scope: 'PLATFORM',
  scope_id: 'platform',
  tenant_id: null,
  revision: 3,
  value: null,
  effective: {
    source_scope: 'UNIT',
    source_id: 'tenant-a',
    source_revision: 2,
    value: {
      subscriptions: {}, receive_alarm: true, push_message: true, push_period_hours: 24,
      voice_enabled: false, voice_types: [], voice_fields: ['position'], voice_continuous: false, silence_minutes: 0,
    },
    unit_receive_alarm: true,
    unit_push_message: true,
  },
}

beforeEach(() => {
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: ['alarm-base:read', 'alarm-base:write'] }, 'management')
  vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(url.endsWith('/management/scope-tree') ? response([]) : response(config))))
})

it('shows inherited platform FIRE rules and requires confirmation before writing an override', async () => {
  const user = userEvent.setup()
  const fetcher = vi.mocked(fetch)
  render(<AlarmBaseConfigurationPage/>)
  await user.click(await screen.findByRole('tab', { name: /FIRE\/FAULT分层规则/ }))
  await user.selectOptions(await screen.findByLabelText('覆盖范围'), 'PLATFORM')
  expect(await screen.findByText(/本级未覆盖，正在继承单位规则/)).toBeInTheDocument()
  expect(screen.getByText(/火警 · 平台 · 平台/)).toBeInTheDocument()
  await user.type(screen.getByLabelText('变更原因'), '平台通知策略调整')
  await user.click(screen.getByRole('button', { name: '保存本级规则' }))
  expect(await screen.findByRole('heading', { name: '确认保存本级规则' })).toBeInTheDocument()
  expect(fetcher.mock.calls.some(([url, init]) => String(url).includes('/scopes/PLATFORM/platform') && init?.method === 'PUT')).toBe(false)
  await user.click(screen.getByRole('button', { name: '确认保存' }))
  await waitFor(() => expect(fetcher.mock.calls.some(([url, init]) => String(url).includes('/scopes/PLATFORM/platform') && init?.method === 'PUT')).toBe(true))
})
