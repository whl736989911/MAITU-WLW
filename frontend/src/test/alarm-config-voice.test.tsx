import { beforeEach, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AlarmConfigVoice } from '@/components/alarm-config-voice'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

class Utterance {
  lang = ''
  onend: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor(readonly text: string) {}
}
const spoken: Utterance[] = []
const current = { tenantId: 'tenant-owner', sourceId: 'alarm-a', deviceId: 'device-a', eventType: 'FIRE' as const, occurredAt: '2026-11-09T00:00:00Z' }
const reader = { ...management, permissions: [...management.permissions, 'alarm-base:read'] }
const fetchVoice = vi.fn((url: string, init?: RequestInit) => {
  const { pathname, searchParams } = new URL(url, 'http://local')
  const sourceId = pathname.split('/').at(-1)
  if (pathname.includes('/outcomes/')) {
    expect(new Headers(init?.headers).get('X-Tenant-Id')).toBe(current.tenantId)
    const eventType = sourceId?.startsWith('fault') ? 'FAULT' : 'FIRE'
    return Promise.resolve(response([{
      id: 'voice-outcome', tenant_id: current.tenantId, alarm_id: eventType === 'FIRE' ? sourceId : null, event_type: eventType,
      configuration_revision: 1, outcome_kind: 'VOICE_BROADCAST', outcome: 'LOCAL_CONTENT_COMPOSED_NOT_SENT',
      detail_json: JSON.stringify({ content: { position: '楼梯间', zone: '不应读出的区域', eventType: eventType === 'FIRE' ? 'FIRE' : 'fault-code', fields: ['position', 'eventType'] } }), occurred_at: current.occurredAt,
    }]))
  }
  if (!pathname.includes('/scopes/DEVICE/')) throw new Error(`Unexpected voice request: ${url}`)
  const eventKind = pathname.includes('/FAULT/') ? 'FAULT' : 'FIRE'
  const device = sourceId ?? ''
  return Promise.resolve(response({
    tenant_id: searchParams.get('tenant_id'), scope: 'DEVICE', scope_id: device, event_kind: eventKind,
    effective: { source_scope: 'DEVICE', source_id: device, source_revision: 3, value: {
      voice_enabled: device !== 'disabled-device', voice_fields: ['position', 'zone'], voice_types: [eventKind === 'FIRE' ? 'FIRE' : 'fault-code'],
      voice_continuous: device === 'device-a', silence_minutes: 5,
    } },
  }))
})
beforeEach(() => {
  localStorage.clear()
  spoken.length = 0
  fetchVoice.mockClear()
  useSession.getState().login(reader, 'management')
  useSession.getState().setScope(['selected-root'], ['selected-place'], true)
  vi.stubGlobal('SpeechSynthesisUtterance', Utterance)
  vi.stubGlobal('speechSynthesis', { speak: (item: Utterance) => spoken.push(item), cancel: vi.fn() })
  vi.stubGlobal('fetch', fetchVoice)
})

it('keeps repeating until stopped, and retired speech callbacks cannot restart playback', async () => {
  const view = render(<AlarmConfigVoice {...current}/>)
  await screen.findByText('详细位置：楼梯间')
  expect(fetchVoice.mock.calls.map(([url]) => url)).toEqual([
    '/api/management/alarm-base-configuration/FIRE/scopes/DEVICE/device-a?tenant_id=tenant-owner',
    '/api/management/alarm-base-configuration/outcomes/alarm-a',
  ])
  expect(spoken).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: '持续重复播报' }))
  expect(spoken).toHaveLength(1)
  act(() => spoken[0].onend?.())
  act(() => spoken[1].onend?.())
  act(() => spoken[2].onend?.())
  expect(spoken).toHaveLength(4)
  fireEvent.click(screen.getByRole('button', { name: '静音 5 分钟' }))
  expect(screen.getByRole('button', { name: '点击朗读' })).toBeDisabled()
  act(() => spoken[3].onend?.())
  expect(spoken).toHaveLength(4)
  fireEvent.click(screen.getByRole('button', { name: '提前取消静音' }))
  fireEvent.click(screen.getByRole('button', { name: '点击朗读' }))
  act(() => spoken[3].onend?.())
  expect(spoken).toHaveLength(5)
  expect(spoken[4].text).toBe('详细位置：楼梯间')
  act(() => spoken[4].onend?.())
  expect(spoken).toHaveLength(5)
  view.unmount()
  act(() => spoken[0].onend?.())
  expect(spoken).toHaveLength(5)
})

it.each([['FIRE', ['alarm:read']], ['FIRE', ['alarm-base:read']], ['FAULT', ['alarm-base:read', 'alarm:read']]] as const)(
  '%s needs both its event permission and the policy read permission', (eventType, permissions) => {
    useSession.getState().login({ ...reader, permissions: [...permissions] }, 'management')
    render(<AlarmConfigVoice {...current} eventType={eventType}/>)
    expect(screen.queryByRole('region', { name: '本地语音播报' })).not.toBeInTheDocument()
    expect(fetchVoice).not.toHaveBeenCalled()
    expect(spoken).toHaveLength(0)
  },
)

it('changing device and event category retires repeating audio and does not carry silence into other devices', async () => {
  const view = render(<AlarmConfigVoice {...current}/>)
  fireEvent.click(await screen.findByRole('button', { name: '持续重复播报' }))
  fireEvent.click(screen.getByRole('button', { name: '静音 5 分钟' }))
  view.rerender(<AlarmConfigVoice {...current} sourceId="fault-a" deviceId="device-b" eventType="FAULT"/>)
  await waitFor(() => expect(fetchVoice).toHaveBeenCalledWith('/api/management/alarm-base-configuration/FAULT/scopes/DEVICE/device-b?tenant_id=tenant-owner', expect.anything()))
  expect(await screen.findByRole('button', { name: '点击朗读' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: '持续重复播报' })).not.toBeInTheDocument()
  act(() => spoken[0].onend?.())
  expect(spoken).toHaveLength(1)
  view.rerender(<AlarmConfigVoice {...current} deviceId="disabled-device"/>)
  await screen.findByText(/已关闭本地语音/)
  expect(screen.queryByRole('button', { name: '点击朗读' })).not.toBeInTheDocument()
  expect(fetchVoice.mock.calls.filter(([url]) => url.endsWith('/outcomes/alarm-a'))).toHaveLength(1)
})
