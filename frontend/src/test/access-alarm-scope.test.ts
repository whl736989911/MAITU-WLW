import { expect, it } from 'vitest'
import { readAlarmChanges } from '@/lib/alarm-changes'

const stream = '11111111-1111-4111-8111-111111111111'
function cursor(sequence: string) {
  return btoa(JSON.stringify({ v: 1, tenant_id: 'tenant-a', audience: 'management', session: 'a'.repeat(64), stream_id: stream, sequence })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
it('accepts consecutive visible-device publication ranks without interpreting them as the tenant-wide event count', () => {
  const query = { cursor: cursor('0'), size: 2 }
  const payload = {
    mode: 'CHANGES', as_of: '2026-10-30T03:00:00Z', timezone: 'Asia/Shanghai',
    scope: { tenant_id: 'tenant-a', audience: 'management', stream_id: stream }, size: 2,
    items: [1, 2].map(sequence => ({ delivery_id: `00000000-0000-4000-8000-00000000000${sequence}`, sequence: String(sequence), type: 'ALARM_CHANGED' })),
    next_cursor: cursor('2'), through_cursor: cursor('2'), has_more: false, pending_count: '0',
    semantics: { kind: 'INVALIDATION_ONLY', order: 'PER_ACTOR_VISIBLE_PUBLICATION_RANK', publisher_liveness: 'UNCONFIRMED', baseline: 'HEAD_BEFORE_SEPARATE_WORKBENCH_READ', historical_coverage: 'APPLICATION_TRACES_FROM_V11' },
  }
  const parsed = readAlarmChanges(JSON.stringify(payload), query, 'tenant-a', 'management')
  expect(parsed.items.map(item => item.sequence)).toEqual(['1', '2'])
  expect(parsed.has_more).toBe(false)
  for (const order of ['PER_DEVICE_TOTAL', '', null, 1]) {
    expect(() => readAlarmChanges(JSON.stringify({ ...payload, semantics: { ...payload.semantics, order } }), query, 'tenant-a', 'management')).toThrow()
  }
})
