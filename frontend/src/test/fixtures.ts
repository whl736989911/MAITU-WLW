import { vi } from 'vitest'
import type { Alarm, AuthResponse } from '@/lib/types'
export const management: AuthResponse = { access_token: 'test-only-token', user: { id: 'u1', username: 'test-admin', name: '测试管理员', user_type: 'PLATFORM', tenant_id: null, must_change_password: false }, permissions: ['summary:read', 'device:read', 'alarm:read', 'alarm:review', 'tenants:read', 'settings:read', 'settings:write'] }
export const customer: AuthResponse = { access_token: 'test-customer-token', user: { id: 'c1', username: 'test-customer', name: '测试客户', user_type: 'CUSTOMER', tenant_id: 'tenant-a', must_change_password: false }, permissions: ['summary:read', 'device:read', 'alarm:read', 'alarm:review'] }
export const alarm: Alarm = { id: 'a1', tenant_id: 'tenant-a', device_id: 'd1', device_name: '测试烟感', address: '一层', event_type: 'FIRE', occurred_at: '2026-10-22T01:00:00Z', physical_state: 'FIRE', response_state: 'UNHANDLED', conclusion: null, revision: 1 }
export const response = (value: unknown, status = 200) => new Response(value === undefined ? undefined : JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })

export function fixedLoginChallenge() {
  vi.spyOn(crypto, 'getRandomValues').mockImplementation(array => {
    if (array instanceof Uint8Array) array.fill(1)
    return array
  })
  return '2222'
}
