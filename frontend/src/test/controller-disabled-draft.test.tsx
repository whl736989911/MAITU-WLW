import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ControllerResourcesPanel } from '@/components/controller-resources-panel'
import { request } from '@/lib/api'
import type * as ApiModule from '@/lib/api'

vi.mock('@/lib/api', async importOriginal => {
  const actual = await importOriginal<typeof ApiModule>()
  return { ...actual, request: vi.fn(() => new Promise(() => {})) }
})
vi.mock('@/components/data-transfer-actions', () => ({ DataTransferActions: () => null }))
vi.mock('@/components/asset-picker', () => ({
  DeviceSelector: ({ onChange }: { onChange: (id: string, reference: { id: string; name: string }) => void }) =>
    <button type="button" onClick={() => onChange('isolated-device', { id: 'isolated-device', name: '隔离验收设备' })}>选择隔离验收设备</button>,
}))
vi.mock('@/lib/use-resource', () => ({
  useResource: (key: string) => ({
    data: key.startsWith('controller-form-caps:') ? {
      services: [{ service_key: 'unverified-query', display_name: '未验证只读能力', category: 'QUERY',
        support_state: 'UNVERIFIED', risk_level: 'NORMAL', result_kind: 'STATUS', status: 'PUBLISHED',
        dispatch_ready: false, dispatch_blocked_code: 'DRIVER_NOT_CONFIGURED',
        dispatch_blocked_reason: '驱动未配置', input_schema: { fields: [] } }], notes: [],
    } : { items: [], page: 1, size: 20, total: 0 },
    loading: false, error: undefined, refresh: () => {},
  }),
}))

afterEach(cleanup)

it('不可执行能力仍允许登记停用草稿，且提交内容只声明真实设备、服务与原因', async () => {
  const write = vi.mocked(request)
  render(<ControllerResourcesPanel tenantId="isolated-tenant" canWrite canReadDevices canReadPlaces/>)
  fireEvent.click(screen.getByRole('button', { name: '登记控制器关联' }))
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: '隔离验收停用草稿' } })
  fireEvent.click(screen.getByRole('button', { name: '选择隔离验收设备' }))
  fireEvent.change(screen.getByLabelText('真实服务能力'), { target: { value: 'unverified-query' } })
  fireEvent.change(screen.getByLabelText('变更原因'), { target: { value: '准备停用关联，不能执行' } })
  expect(write).not.toHaveBeenCalled()
  const submit = screen.getByRole('button', { name: '登记关联' }) as HTMLButtonElement
  fireEvent.click(submit)
  await waitFor(() => expect(write).toHaveBeenCalledTimes(1))
  const [path, init] = write.mock.calls[0]
  expect(path).toBe('/management/controller-resources')
  expect(init).toMatchObject({ method: 'POST', tenantScoped: true, tenantId: 'isolated-tenant' })
  expect(JSON.parse(String(init!.body))).toMatchObject({
    name: '隔离验收停用草稿', device_id: 'isolated-device', action: 'start',
    service_key: 'unverified-query', arguments_json: '{}', reason: '准备停用关联，不能执行',
  })
  await waitFor(() => expect(submit.disabled).toBe(true))
  expect(screen.getByText('当前能力不可用于自动联动')).toBeTruthy()
  expect(screen.queryByText(/当前服务不可执行/)).toBeNull()
})
