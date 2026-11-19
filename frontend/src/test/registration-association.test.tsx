import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RegistrationCreator, RegistrationDetailView } from '@/components/device-registration-workspace'
import { VendorDeviceCreator } from '@/components/vendor-device-creator'
import { CustomerDeviceRegistrationWorkspace } from '@/components/customer-device-registration'
import { useSession } from '@/lib/store'
import { vendorDeviceCsvHeaders } from '@/lib/vendor-device-csv'
import { customer, management, response } from './fixtures'
import { Sheet, SheetContent } from '@/components/ui/sheet'

const time = '2026-11-13T00:00:00Z'
const places = [
  { id: 'east', name: '东楼', parent_id: null, level: 1 },
  { id: 'east-room', name: '205室', parent_id: 'east', level: 2 },
  { id: 'west', name: '西楼', parent_id: null, level: 1 },
  { id: 'west-room', name: '205室', parent_id: 'west', level: 2 },
].map(place => ({ ...place, tenant_id: 'tenant-a', tenant_name: '单位A', device_count: 0, legacy_place_id: null, revision: 0 }))
const unit = { id: 'tenant-a', name: '单位A', places }
function device(index: number) {
  return { id: `device-${index}`, tenant_id: unit.id, tenant_name: unit.name, name: `匹配设备${index}`, device_eui: 'MATCH-EUI', model: '导入型号', address: '安装地址', connectivity: 'UNACTIVATED', physical_state: 'UNKNOWN', last_message_at: null, battery_percent: null, product_id: null, product_version_id: null, product_name: null, version_number: null, revision: index, integration_state: 'LEGACY_UNVERIFIED', place_id: 'east-room', place_name: '205室', system_type: null, device_category: null, offline_policy: { key: 'offline_threshold_minutes', value: 5, unit: '分钟', source_scope: 'GLOBAL', source_id: null, source_revision: 0, evaluated_at: time }, battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'NOT_CONFIGURED', comparator: null, threshold: null, source_scope: 'NONE', source_id: null, source_revision: 0, evaluated_at: time } }
}
function setup(client: boolean) {
  useSession.getState().login({ ...(client ? customer : management), permissions: ['device:read', 'device:write', 'units:read'] }, client ? 'customer' : 'management')
  const registration = { id: 'registration-one', driver_key: 'LEGACY_TTL', mode: 'MATCH_EXISTING', status: 'CLOUD_REGISTERED', revision: 2, tenant_id: unit.id, place_id: null, product_version_id: null, device_id: null as string | null, external_device_id: 'ledger-one', external_serial: 'MATCH-EUI', device_name: null, device_model: null, cloud_state: 'SUCCEEDED', local_state: 'READY', created_at: time, updated_at: time, commissioned_at: null as string | null }
  const detail = () => ({ registration, device_model: null, device_type: null, communication_type: null, access_mode: null, producter_id: null, product_model_id: null, vendor_unit_id: null, verification_code_stored: false, vendor_payload_stored: false, vendor_payload_fields: [], location: null, coordinates: {}, auto_add_channel: null, account_fingerprint: null, cloud_result: { result: { remote_lookup: true, identity_verified: true, local_match_state: 'FOUND' } }, acceptance_note: null, created_by: 'u1', events: [] })
  const state = { commissions: 0 }
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const parsed = new URL(url, 'http://local'), path = parsed.pathname
    if (path.endsWith('/scope-tree')) return response([unit])
    if (path.endsWith('/device-registrations/drivers')) return response({ items: [] })
    if (path.endsWith('/device-registrations')) return response({ items: [registration], total: 1, page: 1, size: 20 })
    if (path.endsWith('/device-registrations/registration-one/commission')) {
      const body = JSON.parse(String(init?.body))
      if (body.tenant_id !== unit.id || body.existing_device_id !== 'device-21' || body.existing_device_revision !== 21 || body.place_id !== 'west-room') return response({ code: 'WRONG_ASSOCIATION', message: '本地验收目标或版本不符' }, 409)
      state.commissions++;registration.status = 'COMMISSIONED';registration.local_state = 'SUCCEEDED';registration.device_id = 'device-21';registration.commissioned_at = time;registration.revision++
      return response(detail())
    }
    if (path.endsWith('/device-registrations/registration-one')) return response(detail())
    if (path.endsWith('/devices')) {
      const page = Number(parsed.searchParams.get('page') ?? 1), keyword = parsed.searchParams.get('keyword') ?? ''
      const rows = Array.from({ length: 21 }, (_, index) => device(index + 1)).filter(row => keyword === 'MATCH-EUI' || row.name.includes(keyword))
      return response({ items: rows.slice((page - 1) * 20, page * 20), total: rows.length, page, size: 20 })
    }
    throw new Error(`Unexpected registration association request: ${url}`)
  }))
  render(client ? <CustomerDeviceRegistrationWorkspace/> : <RegistrationDetailView registrationId="registration-one"/>)
  return state
}
async function ready(client: boolean) {
  if (client) await userEvent.click(await screen.findByRole('button', { name: '打开' }))
  return screen.findByLabelText(client ? '已有设备（显式选择）' : '已有本地设备（显式选择）')
}
beforeEach(() => { useSession.getState().clear();localStorage.clear();sessionStorage.clear() })
it('来源登记在唯一授权单位下预填目标，但筛选变化不静默改写本次草稿', async () => {
  useSession.getState().login({ ...management, permissions: ['device:read', 'device:write', 'device-services:read', 'units:read'] }, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
  const driver = { key: 'MODEL', mode: 'PRODUCT_MODEL', display_name: '本地模型', configured: false, enabled: false, configuration_code: 'NOT_CONFIGURED', endpoint: null, token_masked: null, account_fingerprint: null, revision: 0, updated_at: null, configurable_origin_account: false, services: [] }
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const path = new URL(url, 'http://local').pathname
    if (path.endsWith('/scope-tree')) return response([unit])
    if (path.endsWith('/integration-drivers')) return response({ items: [driver] })
    throw new Error(`Unexpected registration creator request: ${url}`)
  }))
  render(<Sheet open onOpenChange={() => {}}><SheetContent><RegistrationCreator onCreated={() => {}}/></SheetContent></Sheet>)
  await waitFor(() => expect(screen.getByLabelText('目标归属单位（必选）')).toHaveValue(unit.id))
  act(() => useSession.getState().setScope(['tenant-b'], [], true))
  expect(screen.getByLabelText('目标归属单位（必选）')).toHaveValue(unit.id)
})
it('实时来源建档在唯一授权单位下预填，但筛选变化不静默改写本次草稿', async () => {
  useSession.getState().login({ ...management, permissions: ['device:read', 'device:write', 'units:read'] }, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const path = new URL(url, 'http://local').pathname
    if (path.endsWith('/scope-tree')) return response([unit])
    if (path.endsWith('/legacy-profiles')) return response({ items: [], total: 0, page: 1, size: 20 })
    throw new Error(`Unexpected vendor device creator request: ${url}`)
  }))
  render(<VendorDeviceCreator onCreated={() => {}} onPending={() => {}} onBatchCreated={() => {}}/>)
  await waitFor(() => expect(screen.getByLabelText('归属单位')).toHaveValue(unit.id))
  act(() => useSession.getState().setScope(['tenant-b'], [], true))
  expect(screen.getByLabelText('归属单位')).toHaveValue(unit.id)
})
for (const client of [false, true]) describe(client ? '客户端已有设备登记验收' : '管理端已有设备登记验收', () => {
  it('第二页明确选择设备并按其版本关联到正确同名位置', async () => {
    const state = setup(client);await ready(client)
    const picker = screen.getByRole('region', { name: '选择已有本地设备' })
    await userEvent.click(within(picker).getByRole('button', { name: '下一页' }))
    await screen.findByRole('option', { name: /匹配设备21/ })
    await userEvent.selectOptions(screen.getByLabelText(client ? '已有设备（显式选择）' : '已有本地设备（显式选择）'), 'device-21')
    await userEvent.selectOptions(screen.getByLabelText(client ? '本单位位置' : '位置'), 'west-room')
    expect(screen.getByRole('option', { name: '西楼 / 205室' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('验收说明'), { target: { value: '核对实际安装位置' } })
    if (!client) await userEvent.click(screen.getByRole('button', { name: '核对并验收' }))
    const form = screen.getByRole('button', { name: '确认验收' }).closest('form')!
    await userEvent.click(screen.getByRole('button', { name: '确认验收' }))
    await screen.findByText(/本地验收完成/)
    expect(state.commissions).toBe(1)
    expect(screen.queryByLabelText('验收说明')).not.toBeInTheDocument()
  })
  it('回车应用搜索时清除旧设备，不能把查询误提交为验收', async () => {
    const state = setup(client), selection = await ready(client)
    await userEvent.selectOptions(selection, 'device-1')
    const input = screen.getByLabelText('查找本单位导入设备（建议按登记EUI）')
    fireEvent.change(input, { target: { value: '匹配设备21' } })
    await userEvent.type(input, '{Enter}')
    await screen.findByRole('option', { name: /匹配设备21/ })
    await waitFor(() => expect(screen.getByLabelText(client ? '已有设备（显式选择）' : '已有本地设备（显式选择）')).toHaveValue(''))
    expect(screen.queryByRole('option', { name: /匹配设备1 ·/ })).not.toBeInTheDocument()
    expect(state.commissions).toBe(0)
    expect(screen.queryByText('必须选择单位、位置并填写验收说明')).not.toBeInTheDocument()
  })
})
describe('平台本地新增与海康云新增', () => {
  const profile = { id: 'decoder-one', name: '直连烟感', manufacturer: '厂商', model: 'M1', legacy_product_id: 'legacy-m1', decoder_ready: true }
  function baseFetch(handler: (path: string, init?: RequestInit) => Response) {
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      const path = new URL(url, 'http://local').pathname
      if (path.endsWith('/scope-tree')) return response([unit])
      if (path.endsWith('/legacy-profiles')) return response({ items: [profile], total: 1, page: 1, size: 20 })
      return handler(path, init)
    }))
  }
  async function commonFields() {
    await screen.findByLabelText('设备名称')
    fireEvent.change(screen.getByLabelText('设备名称'), { target: { value: '新增烟感' } })
    fireEvent.change(screen.getByLabelText('设备序列号 / EUI'), { target: { value: 'SERIAL-90' } })
    fireEvent.change(screen.getByLabelText('实际位置'), { target: { value: 'east-room' } })
  }
  beforeEach(() => useSession.getState().login({ ...management, permissions: ['device:read', 'device:write', 'integrations:write', 'units:read'] }, 'management'))
  it('缺项时可触发校验，搜索型号不能代替显式选型，换单位后须重选位置', async () => {
    const user = userEvent.setup()
    baseFetch(path => { throw new Error(`Unexpected write during validation: ${path}`) })
    render(<VendorDeviceCreator onCreated={() => {}} onPending={() => {}} onBatchCreated={() => {}}/>)
    const review = screen.getByRole('button', { name: '保存设备' })
    expect(review).toBeEnabled()
    await screen.findByRole('option', { name: `${unit.name} · ${unit.id}` })
    await user.selectOptions(screen.getByLabelText('归属单位'), unit.id)
    await commonFields()
    await user.type(screen.getByLabelText('搜索型号（可选）'), 'M1')
    await user.click(screen.getByRole('button', { name: '查询型号' }))
    const model = await screen.findByLabelText('设备型号（必选）')
    expect(model).toHaveValue('')
    await user.click(review)
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    await user.selectOptions(model, profile.id)
    await user.selectOptions(screen.getByLabelText('归属单位'), '')
    expect(screen.getByLabelText('实际位置')).toHaveValue('')
    await user.click(review)
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
  it('批量导入模式默认本地新建旧烟感，XLSX复用同一预检并原子提交', async () => {
    const user = userEvent.setup()
    let preview: Record<string, unknown> | null = null, submitted: Record<string, unknown> | null = null
    const created = device(1)
    baseFetch((path, init) => {
      if (path.endsWith('/management/vendor-devices/import/spreadsheet')) { preview = JSON.parse(String(init?.body)); return response({ rows: [[...vendorDeviceCsvHeaders.LEGACY_REALTIME], ['烟感A', 'EUI-A', 'east-room', '', '楼内', profile.id, 'UP-A']] }) }
      if (path.endsWith('/management/vendor-devices/import')) { submitted = JSON.parse(String(init?.body)); return response({ items: [created] }) }
      throw new Error(`Unexpected batch request: ${path}`)
    })
    const onBatchCreated = vi.fn()
    render(<VendorDeviceCreator initialMode="import" onCreated={() => {}} onPending={() => {}} onBatchCreated={onBatchCreated}/>)
    await user.click(screen.getByText('旧模板与 CSV 选项'))
    await screen.findByRole('option', { name: unit.name })
    await user.selectOptions(screen.getByLabelText('归属单位'), unit.id)
    expect(screen.getByLabelText('批量处理方式')).toHaveValue('LEGACY_REALTIME')
    await user.upload(screen.getByLabelText(/选择.*XLSX 文件/), new File(['PK\u0003\u0004'], 'devices.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
    await screen.findByText(/已预检 1 行/)
    expect(preview).toMatchObject({ source: 'LEGACY_REALTIME' })
    await user.click(screen.getByRole('button', { name: '核对并确认批量新建 1 行' }))
    await user.click(screen.getByRole('button', { name: '确认原子新建 1 行' }))
    await waitFor(() => expect(onBatchCreated).toHaveBeenCalledWith([created]))
    expect(submitted).toMatchObject({ source: 'LEGACY_REALTIME', rows: [{ name: '烟感A', serial: 'EUI-A', place_id: 'east-room', address: '楼内', legacy_profile_id: profile.id, upstream_device_id: 'UP-A' }] })
    expect(vi.mocked(fetch)).not.toHaveBeenCalledWith(expect.stringContaining('/hikvision/sync'), expect.anything())
  })
  it('海康新增调用云端原生登记接口并保留UNKNOWN，不依赖发现/同步', async () => {
    const user = userEvent.setup()
    const detail = {
      registration: { id: 'registration-unknown', driver_key: 'HIKVISION_FIRECLOUD', mode: 'NEW_VENDOR', status: 'UNKNOWN', revision: 1, tenant_id: unit.id, place_id: 'east-room', product_version_id: null, device_id: null, external_device_id: null, external_serial: 'SERIAL-90', device_name: '新增烟感', device_model: 'HK-M1', cloud_state: 'UNKNOWN', local_state: 'NOT_READY', created_at: time, updated_at: time, commissioned_at: null },
      device_type: null, communication_type: null, access_mode: null, producter_id: null, product_model_id: null, vendor_unit_id: null, verification_code_stored: true, vendor_payload_stored: false, vendor_payload_fields: [], location: null, coordinates: {}, auto_add_channel: null, account_fingerprint: null, cloud_result: null, acceptance_note: null, created_by: 'u1', events: [],
    }
    let saved: Record<string, unknown> | null = null
    baseFetch((path, init) => {
      if (path.endsWith('/management/vendor-devices/hikvision-create')) { saved = JSON.parse(String(init?.body)); return response(detail) }
      throw new Error(`Unexpected Hikvision request: ${path}`)
    })
    const onCreated = vi.fn(), onPending = vi.fn()
    render(<VendorDeviceCreator onCreated={onCreated} onPending={onPending} onBatchCreated={() => {}}/>)
    await screen.findByRole('option', { name: `${unit.name} · ${unit.id}` })
    await user.selectOptions(screen.getByLabelText('归属单位'), unit.id)
    await user.selectOptions(screen.getByLabelText('新增方式'), 'HIKVISION_FIRECLOUD')
    await commonFields()
    await user.type(screen.getByLabelText('海康设备型号'), 'HK-M1')
    await user.type(screen.getByLabelText('海康设备类型代码（官方字段）'), '600002')
    await user.type(screen.getByLabelText('通信方式代码（官方字段）'), '1')
    await user.type(screen.getByLabelText('海康云单位 / 组织标识'), 'cloud-unit')
    await user.type(screen.getByLabelText('海康校验码（可选）'), 'vendor-secret')
    await user.click(screen.getByRole('button', { name: '核对新增设备' }))

    await user.click(screen.getByRole('button', { name: '确认新增' }))
    await waitFor(() => expect(onPending).toHaveBeenCalledWith(expect.objectContaining({ registration: expect.objectContaining({ id: 'registration-unknown', status: 'UNKNOWN' }) })))
    expect(onCreated).not.toHaveBeenCalled()
    expect(saved).toMatchObject({ mode: 'NEW_VENDOR', driver_key: 'HIKVISION_FIRECLOUD', external_serial: 'SERIAL-90', device_model: 'HK-M1', verification_code: 'vendor-secret', tenant_id: unit.id, place_id: 'east-room'})
    expect(vi.mocked(fetch)).not.toHaveBeenCalledWith(expect.stringContaining('/hikvision/sync'), expect.anything())
  })
})
it('海康云原生登记的待完成验收不要求发布通用产品版本', async () => {
  useSession.getState().login({ ...management, permissions: ['device:read', 'device:write', 'units:read'] }, 'management')
  const detail = {
    registration: { id: 'native-hik-pending', driver_key: 'HIKVISION_FIRECLOUD', mode: 'NEW_VENDOR', status: 'CLOUD_REGISTERED', revision: 1, tenant_id: unit.id, place_id: 'east-room', product_version_id: null, device_id: null, external_device_id: 'hk-remote', external_serial: 'HK-SERIAL', device_name: '海康烟感', device_model: 'HK-M1', cloud_state: 'SUCCEEDED', local_state: 'READY', created_at: time, updated_at: time, commissioned_at: null },
    device_type: null, communication_type: null, access_mode: null, producter_id: null, product_model_id: null, vendor_unit_id: 'cloud-unit', verification_code_stored: false, vendor_payload_stored: false, vendor_payload_fields: [], location: null, coordinates: {}, auto_add_channel: null, account_fingerprint: null, cloud_result: null, acceptance_note: null, created_by: 'u1', events: [],
  }
  let commissionBody: Record<string, unknown> | null = null
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://local').pathname
    if (path.endsWith('/scope-tree')) return response([unit])
    if (path.endsWith('/integration-drivers')) return response({ items: [] })
    if (path.endsWith('/device-registrations/native-hik-pending')) return response(detail)
    if (path.endsWith('/device-registrations/native-hik-pending/commission')) {
      commissionBody = JSON.parse(String(init?.body))
      return response(detail)
    }
    throw new Error(`Unexpected native Hikvision commission request: ${path}`)
  }))
  const user = userEvent.setup()
  render(<RegistrationDetailView registrationId="native-hik-pending"/>)
  await screen.findByText(/海康原生设备，无需通用产品版本/)
  expect(screen.getByRole('button', { name: '核对并验收' })).toBeEnabled()
  await user.type(screen.getByLabelText('验收说明'), '云端登记后确认本地安装')
  await user.click(screen.getByRole('button', { name: '核对并验收' }))

  await user.click(screen.getByRole('button', { name: '确认验收' }))
  await waitFor(() => expect(commissionBody).toMatchObject({ tenant_id: unit.id, place_id: 'east-room', acceptance_note: '云端登记后确认本地安装'}))
  expect(commissionBody).not.toHaveProperty('product_version_id')
})
