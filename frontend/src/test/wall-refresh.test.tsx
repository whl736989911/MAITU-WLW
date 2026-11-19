import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { WallPage } from '@/pages/wall'
import { useSession } from '@/lib/store'
import { emptyAlarmFilters, expectedMetricDefinitions } from '@/lib/alarm-workbench'
import type { AlarmWorkbenchFilters } from '@/lib/types'
import { alarm, management, response } from './fixtures'

const start = '2026-11-12T08:00:00Z'
const speak = vi.fn()
function analytics(days: number) {
  const to = new Date(), from = new Date(to.getTime() - days * 86400000)
  const points = Array.from({ length: days }, (_, index) => ({ date: new Date(from.getTime() + index * 86400000).toISOString().slice(0, 10), count: 0 }))
  return response({ as_of: to.toISOString(), from: from.toISOString(), to: to.toISOString(), timezone: 'UTC', days, alarms: 0, faults: 0, untimed_fault_events: 0, reported_offline_events: 0, alarms_by_day: points, faults_by_day: points, reported_offline_events_by_day: points, fault_types: [], reported_offline_devices: [], offline_semantics: 'ONLY_REPORTED_OFFLINE_EVENTS', fault_semantics: 'ONLY_LEGACY_INGEST_EVENTS_KIND_FAULT' })
}
function mountWall(alarmRead = true, policyRead = true) {
  const scenario = { alarmId: alarm.id, deviceId: alarm.device_id, voiceEnabled: true, hasVoiceEvidence: true, allPending: false, analytics: (days: number): Response | Promise<Response> => analytics(days) }
  useSession.getState().login({ ...management, permissions: ['summary:read', ...(alarmRead ? ['alarm:read'] : []), ...(policyRead ? ['alarm-base:read'] : [])] }, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (scenario.allPending) return new Promise<Response>(() => {})
    const parsed = new URL(url, 'http://local'), q = parsed.searchParams
    if (parsed.pathname.endsWith('/summary')) return response({ device_total: 1, online: 1, offline: 0, unactivated: 0, today_alarms: 0, unhandled_alarms: 1, low_battery: 0 })
    if (parsed.pathname.endsWith('/duty/analytics')) return scenario.analytics(Number(q.get('days')))
    if (parsed.pathname.endsWith('/alarm-workbench')) {
      const filters: AlarmWorkbenchFilters = { ...emptyAlarmFilters, physical_state: q.get('physical_state') as 'FIRE' | 'NORMAL' | null, response_state: q.get('response_state') as 'HANDLED' | 'UNHANDLED' | null }
      const matches = (!filters.physical_state || filters.physical_state === 'FIRE') && (!filters.response_state || filters.response_state === 'UNHANDLED')
      const count = matches ? 1 : 0
      return response({ items: matches ? [{ ...alarm, id: scenario.alarmId, device_id: scenario.deviceId }] : [], total: count, page: Number(q.get('page')), size: Number(q.get('size')), filters, metrics: { alarm_total: count, unhandled: count, handled: 0, unrecovered: count, recovered: 0, true_fire: 0 }, metric_definitions: expectedMetricDefinitions, as_of: new Date().toISOString(), timezone: 'Asia/Shanghai' })
    }
    if (parsed.pathname.includes('/scopes/DEVICE/')) {
      expect(q.get('tenant_id')).toBe(alarm.tenant_id)
      expect(parsed.pathname).toContain(`/FIRE/scopes/DEVICE/${scenario.deviceId}`)
      return response({ tenant_id: alarm.tenant_id, scope: 'DEVICE', scope_id: scenario.deviceId, event_kind: 'FIRE', effective: {
        source_scope: 'DEVICE', source_id: scenario.deviceId, source_revision: 1, value: { voice_enabled: scenario.voiceEnabled, voice_fields: ['position'], voice_types: ['FIRE'], voice_continuous: false, silence_minutes: 0 },
      } })
    }
    if (parsed.pathname.includes('/outcomes/')) return response(scenario.hasVoiceEvidence ? [{
      id: 'voice-outcome', tenant_id: alarm.tenant_id, alarm_id: scenario.alarmId, event_type: 'FIRE',
      configuration_revision: 1, outcome_kind: 'VOICE_BROADCAST', outcome: 'LOCAL_CONTENT_COMPOSED_NOT_SENT',
      detail_json: JSON.stringify({ content: { position: '楼梯口', eventType: 'FIRE', fields: ['position'] } }), occurred_at: start,
    }] : [])
    throw new Error(`Unexpected wall request: ${url}`)
  }))
  render(<WallPage />)
  return scenario
}
function stamp() { return document.querySelector('.wall-toolbar [role="status"]')?.textContent ?? '' }
async function loaded() { await waitFor(() => { expect(stamp()).not.toBe(''); expect(stamp()).not.toContain('尚未加载') }) }
beforeEach(() => {
  useSession.getState().clear();localStorage.clear();sessionStorage.clear();speak.mockClear()
  vi.useFakeTimers({ toFake: ['Date'] });vi.setSystemTime(start)
  localStorage.setItem('fire-platform.wall.preferences.v1', JSON.stringify({ sound: true, refreshSeconds: 120 }))
  vi.stubGlobal('speechSynthesis', { speak, cancel: vi.fn() })
  vi.stubGlobal('SpeechSynthesisUtterance', class { lang = ''; constructor(public text: string) {} })
})
afterEach(() => { vi.useRealTimers() })

describe('值守大屏完整刷新与播报去重', () => {
  it.each(['filter', 'trend'])('仅更新 %s 不冒充全屏完整刷新', async change => {
    mountWall();await loaded();const before = stamp()
    vi.setSystemTime('2026-11-12T08:01:00Z')
    if (change === 'filter') { await userEvent.selectOptions(screen.getByLabelText('处置状态'), 'HANDLED');await screen.findByText('当前状态筛选暂无报警') }
    else { await userEvent.click(screen.getByRole('button', { name: '近30天' }));await screen.findByRole('img', { name: '30天带历史发生时间的报警趋势 UTC' }) }
    expect(stamp()).toBe(before)
    vi.setSystemTime('2026-11-12T08:02:00Z')
    await userEvent.click(screen.getByRole('button', { name: '立即刷新' }))
    await waitFor(() => expect(stamp()).not.toBe(before))
  })

  it('部分失败保留上次完整确认时间，恢复后才能推进', async () => {
    const scenario = mountWall();await loaded();const before = stamp()
    scenario.analytics = () => response({ code: 'QA_WALL_ANALYTICS_UNAVAILABLE' }, 503)
    vi.setSystemTime('2026-11-12T08:01:00Z')
    await userEvent.click(screen.getByRole('button', { name: '立即刷新' }))
    await screen.findByRole('alert')
    expect(stamp()).toBe(before)
    expect(screen.getByText('部分数据读取失败')).toBeInTheDocument()
    scenario.analytics = analytics
    vi.setSystemTime('2026-11-12T08:02:00Z')
    await userEvent.click(screen.getByRole('button', { name: '立即刷新' }))
    await waitFor(() => expect(stamp()).not.toBe(before))
  })

  it('慢查询未完成时不推进完整确认时间', async () => {
    const scenario = mountWall();await loaded();const before = stamp()
    let resolve!: (value: Response) => void
    scenario.analytics = () => new Promise<Response>(done => { resolve = done })
    vi.setSystemTime('2026-11-12T08:01:00Z')
    await userEvent.click(screen.getByRole('button', { name: '立即刷新' }))
    expect(stamp()).toBe(before)
    expect(screen.getByText('正在刷新')).toBeInTheDocument()
    await act(async () => resolve(analytics(7)))
    await waitFor(() => expect(stamp()).not.toBe(before))
  })

  it('手动刷新不重播已播火警，新事件仍播报一次', async () => {
    const scenario = mountWall();await loaded()
    expect(speak).not.toHaveBeenCalled()
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('/scopes/DEVICE/'))).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: '启用语音播报' }))
    await waitFor(() => expect(speak).toHaveBeenCalledTimes(1))
    expect(speak.mock.calls[0][0].text).toBe('详细位置：楼梯口')
    vi.setSystemTime('2026-11-12T08:01:00Z')
    const before = stamp();await userEvent.click(screen.getByRole('button', { name: '立即刷新' }))
    await waitFor(() => expect(stamp()).not.toBe(before))
    expect(speak).toHaveBeenCalledTimes(1)
    scenario.alarmId = 'new-alarm'
    vi.setSystemTime('2026-11-12T08:02:00Z')
    await userEvent.click(screen.getByRole('button', { name: '立即刷新' }))
    await waitFor(() => expect(speak).toHaveBeenCalledTimes(2))
  })

  it.each([[false, true], [true, false]])('missing event or policy permission prevents policy reads and speech', async (alarmRead, policyRead) => {
    mountWall(alarmRead, policyRead); await loaded()
    expect(screen.queryByRole('button', { name: '启用语音播报' })).not.toBeInTheDocument()
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('/alarm-base-configuration'))).toBe(false)
    expect(speak).not.toHaveBeenCalled()
  })

  it('requires fresh opt-in after a scope change and skips disabled or unevidenced devices', async () => {
    const scenario = mountWall()
    scenario.voiceEnabled = false
    await loaded()
    await userEvent.click(screen.getByRole('button', { name: '启用语音播报' }))
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('/scopes/DEVICE/'))).toBe(true))
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('/outcomes/'))).toBe(false)
    expect(speak).not.toHaveBeenCalled()
    scenario.voiceEnabled = true
    scenario.hasVoiceEvidence = false
    scenario.deviceId = 'other-device'
    await userEvent.click(screen.getByRole('button', { name: '立即刷新' }))
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('/outcomes/'))).toBe(true))
    expect(speak).not.toHaveBeenCalled()
    scenario.hasVoiceEvidence = true
    act(() => useSession.getState().setScope(['tenant-a'], ['different-place'], true))
    await loaded()
    expect(screen.getByRole('button', { name: '启用语音播报' })).toBeEnabled()
    expect(speak).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '启用语音播报' }))
    await waitFor(() => expect(speak).toHaveBeenCalledTimes(1))
  })

  it('范围变化后不沿用上一范围的完整刷新确认', async () => {
    const scenario = mountWall();await loaded();const before = stamp();scenario.allPending = true
    act(() => useSession.getState().setScope(['tenant-b'], [], true))
    expect(stamp()).not.toBe(before)
    expect(stamp()).toContain('尚未加载')
  })

  it('无报警读取权限时只要求可读数据完整确认', async () => {
    mountWall(false);await loaded()
    expect(screen.getByText('无报警查看权限')).toBeInTheDocument()
  })
})
