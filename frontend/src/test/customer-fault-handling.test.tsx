import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setFaultHandling, type FaultEvent } from '@/lib/duty-api'
import { useSession } from '@/lib/store'
import { customer } from './fixtures'

const event: FaultEvent = {
  id: 'fault-customer-1', tenant_id: 'tenant-a', device_id: 'device-a', kind: 'FAULT', method: 'thing.event.fault',
  fault_code: '1002003', fault_label: '防拆报警', observed_at: '2026-11-12T01:00:00Z', source_message_at: null,
  source_time_field: null, source_fault_id: null, source_recovered: null, source_recovered_at: null,
  received_at: '2026-11-12T01:00:01Z', created_at: '2026-11-12T01:00:01Z', device_name: '客户设备',
  device_address: '客户地址', place_id: 'place-a', handling: null,
}
const body = { expected_revision: 0, handled: true, note: '客户本地确认'}

beforeEach(() => { useSession.getState().clear() })

describe('客户本地故障处置边界', () => {
  it('使用客户入口和会话固定单位，不发送X-Tenant-Id，也不依赖management', async () => {
    useSession.getState().login({ ...customer, permissions: ['device:read', 'faults:handle'] }, 'customer')
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ tenant_id: 'tenant-a', event_id: event.id, handled: true, note: body.note, revision: 1, updated_by: customer.user.id, updated_at: '2026-11-12T01:00:02Z' }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetcher)
    await expect(setFaultHandling(event, body, 'customer-fault-once')).resolves.toMatchObject({ revision: 1, handled: true })
    const [path, init] = fetcher.mock.calls[0]
    expect(path).toBe('/api/customer/duty/faults/fault-customer-1/handling')
    expect((init.headers as Headers).has('X-Tenant-Id')).toBe(false)
    expect((init.headers as Headers).get('Idempotency-Key')).toBe('customer-fault-once')
    expect(JSON.parse(String(init.body))).toEqual(body)
  })

  it('缺少独立faults:handle时不发请求', async () => {
    useSession.getState().login({ ...customer, permissions: ['device:read'] }, 'customer')
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    await expect(setFaultHandling(event, body, 'customer-fault-denied')).rejects.toMatchObject({ status: 403, code: 'FAULT_HANDLE_REQUIRED' })
    expect(fetcher).not.toHaveBeenCalled()
  })
})
