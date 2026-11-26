import { beforeEach, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
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
beforeEach(() => {
  localStorage.clear()
  spoken.length = 0
  useSession.getState().login(management, 'management')
  vi.stubGlobal('SpeechSynthesisUtterance', Utterance)
  vi.stubGlobal('speechSynthesis', { speak: (item: Utterance) => spoken.push(item), cancel: vi.fn() })
  vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(response(url.includes('/outcomes/') ? [{
    id: 'voice-outcome', tenant_id: 'tenant-a', alarm_id: 'alarm-a', event_type: 'FIRE',
    configuration_revision: 1, outcome_kind: 'VOICE_BROADCAST', outcome: 'LOCAL_CONTENT_COMPOSED_NOT_SENT',
    detail_json: JSON.stringify({ content: { position: '楼梯间', eventType: 'FIRE' } }), occurred_at: '2026-11-09T00:00:00Z',
  }] : {
    tenant_id: 'tenant-a', revision: 1, updated_at: null,
    value: { voiceBroadcast: { alarmEnabled: true, faultEnabled: false, alarmFields: ['position', 'eventType'], continuous: false }, silenceIntervals: { alarmMinutes: 5, faultMinutes: 0 } },
  }))))
})

it('keeps repeating until stopped, and retired speech callbacks cannot restart playback', async () => {
  const view = render(<AlarmConfigVoice tenantId="tenant-a" sourceId="alarm-a" eventType="FIRE" occurredAt="2026-11-09T00:00:00Z"/>)
  await screen.findByText('详细位置：楼梯间。事件类型：FIRE')
  fireEvent.click(screen.getByRole('checkbox', { name: '持续重复播报' }))
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
  expect(spoken[4].text).toBe('详细位置：楼梯间。事件类型：FIRE')
  act(() => spoken[4].onend?.())
  expect(spoken).toHaveLength(5)
  view.unmount()
  act(() => spoken[0].onend?.())
  expect(spoken).toHaveLength(5)
})
