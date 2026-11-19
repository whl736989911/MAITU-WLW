import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { UnitSettingsPage } from '@/pages/unit-settings'
import { useSession } from '@/lib/store'
import { customer, response } from '@/test/fixtures'

const key = 'offline_threshold_minutes'
const consumer = 'CONNECTIVITY_AND_OFFLINE_EVENTS'
const threshold = '/api/customer/unit-parameters'

it('核对单位覆盖前不写入；结果未确认时仅用同一版本、原因和值重试', async () => {
  useSession.getState().login({ ...customer, permissions: ['unit-parameters:read', 'unit-parameters:write'] }, 'customer')
  const policy = {
    key, scope: 'TENANT', scope_id: 'tenant-a', tenant_id: 'tenant-a', has_override: true, value: 30, revision: 4, consumer, chain: [],
    effective: { key, unit: '分钟', value: 30, source_scope: 'TENANT', source_id: 'tenant-a', source_revision: 4, evaluated_at: '2026-11-20T00:00:00Z' },
  }
  const definition = { key, name: '通信阈值', unit: '分钟', value_type: 'INTEGER', effect: 'LOCAL_DERIVATION', consumer, min: 1, max: 240, global_value: 60, global_revision: 8, allowed_scopes: ['GLOBAL', 'TENANT'] }
  const writes: { idempotency: string | null; body?: BodyInit | null }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit) => {
    if (url.startsWith(`${threshold}/${key}/history`)) return response({ items: [], total: 0, page: 1, size: 20 })
    if (url === threshold) return response({ definition, policy, allowed_maximum: 60 })
    if (url === `${threshold}/${key}` && options.method === 'PUT') {
      writes.push({ idempotency: new Headers(options.headers).get('Idempotency-Key'), body: options.body })
      if (writes.length === 1) throw new TypeError('连接在响应前中断')
      return response({ policy: { ...policy, value: 20, revision: 5 } })
    }
    throw new Error(`Unexpected request: ${url}`)
  }))
  const user = userEvent.setup()
  render(<UnitSettingsPage notifications={null}/>)
  const value = await screen.findByLabelText('本单位覆盖值（分钟）')
  await user.clear(value)
  await user.type(value, '20')
  await user.type(screen.getByLabelText('变更原因'), '本单位规则调整')
  await user.click(screen.getByRole('button', { name: '预览修改' }))
  expect(writes).toHaveLength(0)

  await user.click(screen.getByRole('button', { name: '确认保存覆盖' }))
  const retry = await screen.findByRole('button', { name: '用原请求确认结果' })
  expect(screen.queryByRole('button', { name: '返回修改' })).not.toBeInTheDocument()
  await user.click(retry)
  expect(writes[0].idempotency).toBeTruthy()
  expect(writes).toEqual([writes[0], writes[0]])
  expect(JSON.parse(String(writes[0].body))).toEqual({ value: 20, expected_revision: 4, reason: '本单位规则调整' })
})
