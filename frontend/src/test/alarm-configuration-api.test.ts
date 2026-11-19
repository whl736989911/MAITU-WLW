import { beforeEach, expect, it, vi } from 'vitest'
import { alarmBaseApi, type AlarmScopedValue } from '@/lib/alarm-configuration'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

const value: AlarmScopedValue = {
  subscriptions: { 'detector-x': ['E1'] },
  receive_alarm: true,
  push_message: true,
  push_period_hours: 24,
  voice_enabled: false,
  voice_types: [],
  voice_fields: ['position'],
  voice_continuous: false,
  silence_minutes: 0,
}
const configuration = {
  event_kind: 'FIRE',
  scope: 'PLACE',
  scope_id: 'place-a',
  tenant_id: 'tenant-a',
  revision: 4,
  value: null,
  effective: {
    source_scope: 'UNIT',
    source_id: 'tenant-a',
    source_revision: 2,
    value,
    unit_receive_alarm: true,
    unit_push_message: true,
  },
}

beforeEach(() => {
  useSession.getState().clear()
  useSession.getState().login(management, 'management')
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response(configuration))))
})

it('reads a place override with its explicit unit query and tenant header', async () => {
  await alarmBaseApi.getScoped('FIRE', 'PLACE', 'place-a', undefined, 'tenant-a')
  const [url, init] = vi.mocked(fetch).mock.calls[0]
  expect(url).toBe('/api/management/alarm-base-configuration/FIRE/scopes/PLACE/place-a?tenant_id=tenant-a')
  expect(new Headers(init?.headers).get('X-Tenant-Id')).toBe('tenant-a')
  expect(init?.method).toBeUndefined()
})

it('writes a scoped override with the expected revision and idempotency key', async () => {
  await alarmBaseApi.saveScoped('FAULT', 'DEVICE', 'device/a', { expected_revision: 4, reason: '安装策略调整', value }, 'request-1', undefined, 'tenant-a')
  const [url, init] = vi.mocked(fetch).mock.calls[0]
  expect(url).toBe('/api/management/alarm-base-configuration/FAULT/scopes/DEVICE/device%2Fa?tenant_id=tenant-a')
  expect(init?.method).toBe('PUT')
  expect(new Headers(init?.headers).get('Idempotency-Key')).toBe('request-1')
  expect(JSON.parse(String(init?.body))).toEqual({ expected_revision: 4, reason: '安装策略调整', value })
})

it('clears a place override without removing the tenant target', async () => {
  await alarmBaseApi.clearScoped('FIRE', 'PLACE', 'place-a', { expected_revision: 4, reason: '恢复继承' }, 'request-2', undefined, 'tenant-a')
  const [url, init] = vi.mocked(fetch).mock.calls[0]
  expect(url).toBe('/api/management/alarm-base-configuration/FIRE/scopes/PLACE/place-a/clear?tenant_id=tenant-a')
  expect(init?.method).toBe('POST')
  expect(new Headers(init?.headers).get('Idempotency-Key')).toBe('request-2')
  expect(JSON.parse(String(init?.body))).toEqual({ expected_revision: 4, reason: '恢复继承' })
})

it('reads and writes platform rules without sending a tenant target', async () => {
  await alarmBaseApi.getScoped('FAULT', 'PLATFORM', 'platform')
  await alarmBaseApi.saveScoped('FAULT', 'PLATFORM', 'platform', { expected_revision: 4, reason: '平台规则变更', value }, 'request-3')
  expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/management/alarm-base-configuration/FAULT/scopes/PLATFORM/platform')
  expect(vi.mocked(fetch).mock.calls[1][0]).toBe('/api/management/alarm-base-configuration/FAULT/scopes/PLATFORM/platform')
  expect(new Headers(vi.mocked(fetch).mock.calls[1][1]?.headers).has('X-Tenant-Id')).toBe(false)
})
