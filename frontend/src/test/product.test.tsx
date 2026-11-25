import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { schemaDocument, sampleDocument, syntheticSamples } from '@/lib/product-json'
import { parseJsonDocument } from '@/lib/json-document'
import type { Device, PlaceNode, Product, ProductVersion, ParserTestResult } from '@/lib/types'
import { ProductsPage } from '@/pages/products'
import { DevicesPage } from '@/pages/devices'
import { DeviceTable } from '@/components/data-tables'
import { DeviceEditor } from '@/components/device-editor'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { ScopeTreePanel } from '@/components/scope-tree-panel'
import { ParserResults, PublishedVersionPicker } from '@/components/product-common'
import { customer, management, response } from './fixtures'
const admin = { ...management, permissions: [...management.permissions, 'products:read', 'products:write', 'products:publish', 'device:write', 'device:migrate'] }
const product: Product = { id: 'product1', name: '合成测试产品', manufacturer: 'synthetic-maker', model: 'json-model', description: '只用于合成映射测试', revision: 0, state: 'ACTIVE', archived_at: null }
const schema = { format: 'JSON_POINTER_V1', event_pointer: '/event', event_mappings: [{ raw_value: 'FIRE', event_type: 'FIRE' }, { raw_value: 'RECOVERY', event_type: 'RECOVERY' }], battery: { pointer: '/battery', scale: 1, offset: 0, min: 0, max: 100 } }
const draft: ProductVersion = { id: 'version-draft', product_id: product.id, version_number: 2, status: 'DRAFT', revision: 2, schema, capabilities: { fire: 'PENDING', recovery: 'PENDING', battery: 'PENDING' }, battery_low_threshold: null, parser_tested: false, parser_test_summary: null, created_at: '2026-10-22T00:00:00Z', published_at: null }
const published: ProductVersion = { ...draft, id: 'version-pub', version_number: 1, status: 'PUBLISHED', revision: 8, capabilities: { fire: 'SUPPORTED', recovery: 'SUPPORTED', battery: 'SUPPORTED' }, battery_low_threshold: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', comparator: 'LTE', threshold: 18, threshold_json: '18' }, parser_tested: true, published_at: '2026-10-22T01:00:00Z', parser_test_summary: { passed: true, tested_revision: 7, schema_fingerprint: 'synthetic-fingerprint', samples: syntheticSamples, results: [] } }
const device: Device = { id: 'real-device', tenant_id: 'tenant-a', name: '正式未联调烟感', device_eui: 'vendor:device-001', model: product.model, address: '测试楼1层', place_id: 'place-a', connectivity: 'UNACTIVATED', physical_state: 'UNKNOWN', last_message_at: null, battery_percent: null, product_id: product.id, product_version_id: published.id, product_name: product.name, version_number: 1, revision: 0, integration_state: 'PENDING_INTEGRATION', battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'NO_DATA', comparator: 'LTE', threshold: 18, source_scope: 'PRODUCT_VERSION', source_id: published.id, source_revision: published.revision, evaluated_at: '2026-10-22T00:00:00Z' } }
const page = (items: unknown[]) => ({ items, total: items.length, page: 1, size: 20 })
function login() { useSession.getState().login(admin, 'management'); useSession.getState().setScope(['tenant-a'], [], true) }
function basicFetch(url: string, _init?: RequestInit): Response {
  if (url.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places: [{ id: 'place-a', tenant_id: 'tenant-a', tenant_name: '单位A', name: '开户位置A', parent_id: null, legacy_place_id: null, device_count: 0, level: 1, revision: 0 }] }])
  if (url.includes('/products?')) return response(page([product]))
  if (url.endsWith('/versions')) return response({ items: [published, draft] })
  if (url.includes('/devices?')) return response(page([device]))
  if (url.endsWith('/version-history')) return response({ items: [] })
  return response(device)
}
function setValue(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }) }
async function confirmActor(user: ReturnType<typeof userEvent.setup>, button: string) { const review = screen.queryByRole('button', { name: '核对并继续' }); if (review) await user.click(review); await user.click(screen.getByRole('button', { name: button })) }
async function deviceAction(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: '更多设备操作' }))
  await user.click(screen.getByRole('button', { name }))
}
beforeEach(() => useSession.getState().clear())

describe('P3声明式JSON与API边界', () => {
  it('拒绝重复键、转义后相同键、非有限数；不执行脚本或归零', () => {
    expect(() => parseJsonDocument('{"a":1,"a":2}')).toThrow('重复键')
    expect(() => parseJsonDocument('{"a":1,"\\u0061":2}')).toThrow('重复键')
    expect(() => parseJsonDocument('{"x":[1e999]}')).toThrow('非有限')
    expect(() => schemaDocument('[]')).toThrow('对象')
    expect(parseJsonDocument('{"a":{"x":0},"b":{"x":false}}')).toEqual({ a: { x: 0 }, b: { x: false } })
    expect(() => parseJsonDocument('(()=>alert(1))()')).toThrow()
    expect(() => parseJsonDocument('private-payload-test-only')).toThrow('JSON语法无效')
    try { parseJsonDocument('private-payload-test-only') } catch (error) { expect((error as Error).message).not.toContain('private-payload-test-only') }
  })
  it('合成样例保留原payload字符串，MALFORMED不在前端变成空对象', () => {
    expect(sampleDocument(JSON.stringify(syntheticSamples))).toEqual(syntheticSamples)
    expect(sampleDocument(JSON.stringify(syntheticSamples))[3].payload).toBe('{invalid-json')
    expect(() => sampleDocument('[{"name":"bad","payload":{},"case_kind":"FIRE"}]')).toThrow('payload')
  })
  it('产品全局API无tenant头、schema原始数字不被JS重序列化，测试/发布附实际revision与幂等', async () => {
    useSession.getState().login(admin, 'management')
    const fetcher = vi.fn().mockImplementation(async (url: string, _init: RequestInit) => response(url.endsWith('/test') ? { version_id: draft.id, revision: 10, passed: false, results: [] } : draft)); vi.stubGlobal('fetch', fetcher)
    await api.products(1, '%literal_'); await api.createVersion(product.id, null, 'new-key'); await api.createVersion(product.id, published.id, 'clone-key')
    const raw = '{"format":"JSON_POINTER_V1","raw_value":9007199254740993}'
    await api.updateVersion(draft.id, { schema_json: raw, capabilities: draft.capabilities, battery_low_threshold: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', comparator: 'LTE', threshold: Number('19.123456789012345678901234567890123456'), threshold_json: '19.123456789012345678901234567890123456' }, expected_revision: 2}, 'edit-key')
    expect(String(fetcher.mock.calls[3][1].body)).toContain('"battery_low_threshold":{"parameter_key":"battery_low_threshold_percent","metric_key":"battery_percent","unit":"%","comparator":"LTE","threshold":19.123456789012345678901234567890123456}')
    expect(JSON.parse(String(fetcher.mock.calls[1][1].body)).base_version_id).toBeNull(); expect(JSON.parse(String(fetcher.mock.calls[2][1].body)).base_version_id).toBe(published.id)
    await api.testVersion(draft.id, { expected_revision: 9, samples_json: '[{"name":"precision","case_kind":"UNKNOWN","payload":"{}","expected_event":"UNKNOWN","expected_battery_percent":0.123456789012345678901}]'}, 'test-key'); await api.publishVersion(draft.id, 10, 'publish-key')
    expect(String(fetcher.mock.calls[4][1].body)).toContain('0.123456789012345678901')
    expect(JSON.parse(String(fetcher.mock.calls[5][1].body)).expected_revision).toBe(10)
    await expect(Promise.resolve().then(() => api.updateVersion(draft.id, { schema_json: '{"a":1,"a":2}', expected_revision: 2, capabilities: draft.capabilities, battery_low_threshold: null}, 'bad-schema'))).rejects.toThrow('重复键')
    await expect(Promise.resolve().then(() => api.testVersion(draft.id, { samples_json: '[{"name":"a","name":"b"}]', expected_revision: 2}, 'bad-samples'))).rejects.toThrow('重复键')
    expect(fetcher).toHaveBeenCalledTimes(6)
    expect((fetcher.mock.calls[5][1].headers as Headers).get('Idempotency-Key')).toBe('publish-key')
    expect(fetcher.mock.calls.every(call => !(call[1].headers as Headers).has('X-Tenant-Id'))).toBe(true)
  })
  it('GET高精度schema不改内容再PUT保留原数值字面量，原始事件诊断也无损', async () => {
    login()
    const rawSchema = '{"format":"JSON_POINTER_V1","event_pointer":"/event","event_mappings":[{"raw_value":9007199254740993,"event_type":"FIRE"},{"raw_value":"RECOVERY","event_type":"RECOVERY"}],"battery":null}'
    const versionText = JSON.stringify({ ...draft, schema: 'RAW_SCHEMA' }).replace('"RAW_SCHEMA"', rawSchema)
    const testText = '{"version_id":"version-draft","revision":3,"passed":true,"results":[{"name":"precision","passed":true,"event_type":"FIRE","battery_percent":null,"raw_event":9007199254740993,"diagnostics":[]}]}'
    const fetcher = vi.fn().mockImplementation((url: string, _init: RequestInit) => url.endsWith('/versions') ? new Response(` {"items":[${versionText}]} `) : url.endsWith('/test') ? new Response(testText) : response(draft)); vi.stubGlobal('fetch', fetcher)
    const versions = await api.versions(product.id)
    expect(versions.items[0].schema_json).toBe(rawSchema)
    await api.updateVersion(draft.id, { schema_json: versions.items[0].schema_json!, capabilities: draft.capabilities, battery_low_threshold: null, expected_revision: 2}, 'roundtrip')
    expect(String(fetcher.mock.calls[1][1].body)).toContain(rawSchema)
    const result = await api.testVersion(draft.id, { samples_json: JSON.stringify(syntheticSamples), expected_revision: 2}, 'raw-event')
    expect(result.results[0].raw_event_json).toBe('9007199254740993')
    render(<ParserResults passed={result.passed} results={result.results}/>); expect(screen.getByText('9007199254740993')).toBeInTheDocument()
  })
  it('设备写入强制显式tenant；元数据与迁移不附带物理状态等禁止字段', async () => {
    useSession.getState().login(admin, 'management'); vi.stubGlobal('fetch', vi.fn())
    await expect(api.createDevice({ name: '设备', address: '', place_id: 'place-a', device_eui: 'vendor-1', product_version_id: published.id}, 'key')).rejects.toMatchObject({ code: 'TENANT_REQUIRED' })
    login(); const fetcher = vi.fn().mockImplementation(async (_url: string, _init: RequestInit) => response(device)); vi.stubGlobal('fetch', fetcher)
    await api.updateDevice(device.id, { name: '改名', address: '改地址', expected_revision: 0}, 'meta-key', undefined, 'tenant-a')
    await api.migrateDevice(device.id, { product_version_id: 'next-pub', expected_revision: 0, reason: '显式升级'}, 'migration-key', undefined, 'tenant-a')
    expect((fetcher.mock.calls[0][1].headers as Headers).get('X-Tenant-Id')).toBe('tenant-a')
    const metadata = JSON.parse(String(fetcher.mock.calls[0][1].body)); expect(Object.keys(metadata).sort()).toEqual(['address', 'expected_revision', 'name'])
    const migration = JSON.parse(String(fetcher.mock.calls[1][1].body)); expect(migration.expected_revision).toBe(0); expect(migration).not.toHaveProperty('physical_state'); expect(migration).not.toHaveProperty('battery_percent')
  })
})

describe('P3状态和发布/绑定门禁', () => {
  it('正式新设备未知/未激活不显示正常；版本/接入信息只在详情显示', () => {
    const pending = { ...device, status_display: { online_status: 'UNACTIVATED' as const, customer_status: 'UNACTIVATED' as const, business_statuses: [] } }
    render(<DeviceTable items={[pending, { ...pending, id: 'legacy', name: '旧接入设备', product_version_id: null, product_id: null, product_name: null, version_number: null, integration_state: 'LEGACY_UNVERIFIED' }]}/>)
    expect(screen.getAllByText('未激活')).toHaveLength(4); expect(screen.queryByText('待接入联调')).not.toBeInTheDocument(); expect(screen.queryByText('未绑定模板')).not.toBeInTheDocument(); expect(screen.queryByText('正常')).not.toBeInTheDocument(); expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })
  it('原始未知事件和null电量不归正常，真实0%保留', () => {
    render(<ParserResults passed={false} results={[{ name: '未知', passed: true, event_type: 'UNKNOWN', battery_percent: null, readings: [], raw_event: 'unexpected', diagnostics: ['未映射事件'] }, { name: '零电量', passed: true, event_type: 'FIRE', battery_percent: 0, readings: [], raw_event: 'FIRE', diagnostics: [] }]}/>)
    expect(screen.getByText('UNKNOWN')).toBeInTheDocument(); expect(screen.getByText('0%')).toBeInTheDocument(); expect(screen.getByText('未上报')).toBeInTheDocument(); expect(screen.getByText('"unexpected"')).toBeInTheDocument(); expect(screen.getByRole('status')).toHaveTextContent('整体未通过或覆盖不足'); expect(screen.queryByText('正常')).not.toBeInTheDocument()
  })
  it('客户即使带产品/设备写权限也无产品API和开户/迁移/管理历史动作', async () => {
    useSession.getState().login({ ...customer, permissions: admin.permissions }, 'customer')
    const fetcher = vi.fn().mockImplementation(basicFetch); vi.stubGlobal('fetch', fetcher)
    render(<><ProductsPage/><DevicesPage/></>); await screen.findByText(device.name!)
    expect(screen.queryByRole('button', { name: '正式设备开户' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '迁移版本' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '绑定历史' })).not.toBeInTheDocument(); expect(fetcher.mock.calls.every(call => call[0].startsWith('/api/customer/devices?'))).toBe(true)
  })
  it('绑定选择只含已发布具体版本，初始化不选最新，伪选草稿不能形成目标', async () => {
    login(); vi.stubGlobal('fetch', vi.fn().mockImplementation(basicFetch)); const change = vi.fn()
    render(<PublishedVersionPicker value="" onChange={change}/>); const user = userEvent.setup(); await screen.findByRole('option', { name: /合成测试产品/ }); await user.selectOptions(screen.getByLabelText('产品'), product.id)
    const picker = await screen.findByLabelText('具体已发布版本'); await screen.findByRole('option', { name: /v1 · version-pub/ }); expect(picker).toHaveValue(''); expect(screen.queryByRole('option', { name: /version-draft/ })).not.toBeInTheDocument()
    fireEvent.change(picker, { target: { value: draft.id } }); expect(change).toHaveBeenLastCalledWith('', null)
    await user.selectOptions(picker, published.id); expect(change).toHaveBeenLastCalledWith(published.id, expect.objectContaining(published))
  })
  it('只有草稿时不能继续，发布后刷新可显式选版且保留已填设备资料', async () => {
    login()
    let versionPublished = false
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) =>
      url.endsWith('/versions') ? response({ items: versionPublished ? [draft, published] : [draft] }) : basicFetch(url, init)))
    render(<Sheet open onOpenChange={() => {}}><SheetContent><DeviceEditor editor={{ mode: 'create', device: null }} onSaved={() => {}} onRefresh={() => {}}/></SheetContent></Sheet>)
    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('开户归属单位（必选）')).toHaveValue('tenant-a'))
    act(() => useSession.getState().setScope(['tenant-b'], [], true))
    expect(screen.getByLabelText('开户归属单位（必选）')).toHaveValue('tenant-a')
    setValue('设备名称', '保留设备资料')
    setValue('设备EUI / 标识', 'registration-empty-version')
    await screen.findByRole('option', { name: /合成测试产品/ })
    await user.selectOptions(screen.getByLabelText('产品'), product.id)
    await screen.findByText(/该产品尚无已发布版本；/)
    expect(screen.getByLabelText('具体已发布版本')).toBeDisabled()
    expect(screen.getByRole('button', { name: '创建设备开户' })).toBeDisabled()
    versionPublished = true
    await user.click(screen.getByRole('button', { name: '刷新已发布版本' }))
    await screen.findByRole('option', { name: /v1 · version-pub/ })
    expect(screen.getByLabelText('具体已发布版本')).toHaveValue('')
    expect(screen.getByRole('button', { name: '创建设备开户' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('具体已发布版本'), published.id)
    expect(screen.getByRole('button', { name: '创建设备开户' })).toBeEnabled()
    expect(screen.getByLabelText('设备名称')).toHaveValue('保留设备资料')
    expect(screen.getByLabelText('设备EUI / 标识')).toHaveValue('registration-empty-version')
  })
  it('已发布版本只读不可编辑/测试/再次发布，原证据仍可查看', async () => {
    login(); vi.stubGlobal('fetch', vi.fn().mockImplementation(basicFetch)); render(<ProductsPage/>); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '查看版本' })); await user.click(await screen.findByRole('button', { name: /^v1/ }))
    expect(screen.getByRole('button', { name: '编辑草稿' })).toBeDisabled(); expect(screen.getByRole('button', { name: '运行合成样例测试' })).toBeDisabled(); expect(screen.getByRole('button', { name: '发布此版本' })).toBeDisabled(); expect(screen.getByText(/电量低阈值 · 版本配置（只读）/)).toBeInTheDocument(); expect(screen.getByText('18LTE · battery_low_threshold_percent · battery_percent · %')).toBeInTheDocument(); expect(screen.getByText(/存档测试证据/)).toBeInTheDocument(); expect(screen.getByRole('button', { name: '复制成新草稿' })).toBeEnabled()
  })
  it('草稿保存未知后导航离开仍冻结原版本revision/body/key，重返产品只可重试原操作', async () => {
    login(); let puts = 0
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/products?')) return response(page([product]))
      if (url.endsWith('/versions')) return response({ items: [published, draft] })
      if (url.endsWith('/product-versions/version-draft') && init?.method === 'PUT') {
        puts++
        return puts === 1 ? response({ message: '未确认' }, 503) : response({ ...draft, revision: 3, schema: { ...schema, event_pointer: '/frozen' } })
      }
      return basicFetch(url, init)
    })
    vi.stubGlobal('fetch', fetcher); const user = userEvent.setup()
    const first = render(<ProductsPage/>)
    await user.click(await screen.findByRole('button', { name: '查看版本' })); await user.click(await screen.findByRole('button', { name: /^v2/ }))
    await user.click(screen.getByRole('button', { name: '编辑草稿' })); setValue('Schema JSON', JSON.stringify({ ...schema, event_pointer: '/frozen' }))
    await user.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('产品版本操作结果未确认')
    const firstCall = fetcher.mock.calls.find(call => call[1]?.method === 'PUT')!, firstBody = JSON.parse(String(firstCall[1].body)), key = new Headers(firstCall[1].headers).get('Idempotency-Key')
    expect(firstBody).toMatchObject({ expected_revision: 2, schema: { event_pointer: '/frozen' }, battery_low_threshold: null })
    first.unmount()
    const resumed = render(<ProductsPage/>)
    await user.click(await screen.findByRole('button', { name: '查看版本' })); await user.click(await screen.findByRole('button', { name: /^v2/ }))
    await screen.findByText('产品版本操作结果未确认')
    await user.click(screen.getByRole('button', { name: '使用原操作内容和幂等键重试' }))
    await waitFor(() => expect(puts).toBe(2))
    const secondCall = fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')[1], secondBody = JSON.parse(String(secondCall[1].body))
    expect(secondBody).toEqual({ ...firstBody})
    expect(new Headers(secondCall[1].headers).get('Idempotency-Key')).toBe(key)
    resumed.unmount()
  })
  it('草稿能力PENDING不画支持，无证据不可发布；重复键编辑被拒绝不调用PUT', async () => {
    login(); vi.stubGlobal('fetch', vi.fn().mockImplementation(basicFetch)); render(<ProductsPage/>); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '查看版本' })); await user.click(await screen.findByRole('button', { name: /^v2/ }))
    expect(screen.getByRole('button', { name: '发布此版本' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '编辑草稿' })); setValue('Schema JSON', '{"event_pointer":"/a","event_pointer":"/b"}'); await user.click(screen.getByRole('button', { name: '保存草稿' })); expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
  })
  it('草稿保存后清除旧通过证据，不能继续使用旧测试发布', async () => {
    login(); let current: ProductVersion = { ...draft, parser_tested: true, parser_test_summary: { passed: true, tested_revision: 2, results: [] } }
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith('/versions')) return response({ items: [current] })
      if (init?.method === 'PUT') { current = { ...current, revision: 3, parser_tested: false, parser_test_summary: null }; return response(current) }
      return basicFetch(url, init)
    }); vi.stubGlobal('fetch', fetcher); render(<ProductsPage/>); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '查看版本' })); await user.click(await screen.findByRole('button', { name: /^v2/ })); expect(screen.getByRole('button', { name: '发布此版本' })).toBeEnabled(); await user.click(screen.getByRole('button', { name: '编辑草稿' })); setValue('Schema JSON', JSON.stringify({ ...schema, event_pointer: '/new-event' })); await user.click(screen.getByRole('button', { name: '保存草稿' })); await waitFor(() => expect(screen.getByRole('button', { name: '发布此版本' })).toBeDisabled()); expect(screen.getByText(/当前草稿没有测试证据/)).toBeInTheDocument()
    const call = fetcher.mock.calls.find(call => call[1]?.method === 'PUT')!; expect(JSON.parse(String(call[1]!.body)).expected_revision).toBe(2)
  }, 20_000)
  it('样例单行通过但整体覆盖失败仍不发布，失败测试的新revision可用于后续编辑', async () => {
    login(); let current: ProductVersion = { ...draft }
    const result: ParserTestResult = { version_id: draft.id, revision: 3, passed: false, results: [{ name: '单个样例', passed: true, event_type: 'FIRE', battery_percent: null, readings: [], raw_event: 'FIRE', diagnostics: [] }] }
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith('/versions')) return response({ items: [current] })
      if (url.endsWith('/test')) { current = { ...current, revision: 3, parser_tested: false, parser_test_summary: { passed: false, tested_revision: 3, results: result.results } }; return response(result) }
      return basicFetch(url, init)
    }); vi.stubGlobal('fetch', fetcher); render(<ProductsPage/>); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '查看版本' })); await user.click(await screen.findByRole('button', { name: /^v2/ })); await user.click(screen.getByRole('button', { name: '运行合成样例测试' })); await user.click(screen.getByRole('button', { name: '填入四类合成示例（不会自动测试）' })); await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: '保存测试结果' })); await screen.findByText(/测试已保存，服务端当前 revision 3/); await waitFor(() => expect(screen.getByRole('button', { name: '编辑草稿' })).toBeEnabled()); expect(screen.getByRole('button', { name: '发布此版本' })).toBeDisabled(); expect(screen.getAllByText(/整体未通过或覆盖不足/).length).toBeGreaterThan(0); await user.click(screen.getByRole('button', { name: '编辑草稿' })); expect(within(screen.getByRole('dialog')).getByText(/revision 3/)).toBeInTheDocument()
  }, 20_000)
  it('显式四类测试后读取新revision再发布，发布需二次确认且不写设备API', async () => {
    login(); let current = { ...draft }
    const testResult: ParserTestResult = { version_id: draft.id, revision: 3, passed: true, results: [{ name: '合成零电量', passed: true, event_type: 'RECOVERY', battery_percent: 0, readings: [], raw_event: 'RECOVERY', diagnostics: [] }] }
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith('/versions')) return response({ items: [current] })
      if (url.endsWith('/test') && init?.method === 'POST') { current = { ...current, revision: 3, parser_tested: true, parser_test_summary: { passed: true, tested_revision: 3, schema_fingerprint: 'new-synthetic', samples: syntheticSamples, results: testResult.results } }; return response(testResult) }
      if (url.endsWith('/publish')) { current = { ...current, status: 'PUBLISHED', revision: 4 }; return response(current) }
      return basicFetch(url, init)
    }); vi.stubGlobal('fetch', fetcher); render(<ProductsPage/>); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '查看版本' })); await user.click(await screen.findByRole('button', { name: /^v2/ })); await user.click(screen.getByRole('button', { name: '运行合成样例测试' }))
    expect(screen.getByLabelText('合成样例 JSON 数组')).toHaveValue('[]'); await user.click(screen.getByRole('button', { name: '填入四类合成示例（不会自动测试）' })); expect(fetcher.mock.calls.some(call => call[0].endsWith('/test'))).toBe(false); await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: '保存测试结果' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '发布此版本' })).toBeEnabled()); await user.click(screen.getByRole('button', { name: '发布此版本' })); expect(fetcher.mock.calls.some(call => call[0].endsWith('/publish'))).toBe(false); await confirmActor(user, '确认发布此版本')
    const publishCall = fetcher.mock.calls.find(call => call[0].endsWith('/publish'))!; expect(JSON.parse(String(publishCall[1]!.body)).expected_revision).toBe(3); expect(fetcher.mock.calls.some(call => call[0].includes('/devices'))).toBe(false)
    await waitFor(() => expect(screen.getByRole('button', { name: '编辑草稿' })).toBeDisabled())
  }, 20_000)
})

describe('P3正式开户与显式迁移', () => {
  it('统一产品模型登记缺少具体发布版本或实际位置时不提交', async () => {
    login()
    const driver = { key: 'MODEL', mode: 'PRODUCT_MODEL', display_name: '本地模型', configured: false, enabled: false, configuration_code: 'NOT_CONFIGURED', endpoint: null, token_masked: null, account_fingerprint: null, revision: 0, updated_at: null, configurable_origin_account: false, services: [] }
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/integration-drivers') ? response({ items: [driver] }) : basicFetch(url, init))
    vi.stubGlobal('fetch', fetcher)
    render(<DevicesPage/>)
    const user = userEvent.setup()
    await screen.findByText(device.name!)
    await user.click(screen.getByRole('button', { name: '添加设备' }))
    await user.click(await screen.findByRole('menuitem', { name: '高级接入登记' }))
    await user.selectOptions(await screen.findByLabelText('接入驱动'), driver.key)
    setValue('本地设备名称', '新开户设备'); setValue('精确序列号 / 本地设备标识', 'manufacturer:register-2')
    expect(screen.getByRole('button', { name: '创建来源登记草稿' })).toBeDisabled()
    await screen.findByRole('option', { name: /合成测试产品/ }); await user.selectOptions(screen.getByLabelText('产品'), product.id)
    await screen.findByRole('option', { name: /v1 · version-pub/ })
    expect(screen.getByLabelText('具体已发布版本')).toHaveValue('')
    await user.selectOptions(screen.getByLabelText('具体已发布版本'), published.id)
    expect(screen.getByLabelText('实际安装位置（必选）')).toHaveValue('')
    await user.click(screen.getByRole('button', { name: '创建来源登记草稿' }))
    expect(fetcher.mock.calls.some(call => call[1]?.method === 'POST')).toBe(false)
  }, 20_000)
  it('行操作菜单关闭后删除确认仍可见，取消不会提交归档', async () => {
    login()
    const fetcher = vi.fn().mockImplementation(basicFetch)
    vi.stubGlobal('fetch', fetcher)
    render(<DevicesPage/>)
    const user = userEvent.setup()
    await screen.findByText(device.name!)
    await deviceAction(user, '删除设备（保留历史）')
    const dialog = await screen.findByRole('dialog', { name: '删除设备（保留历史）' })
    expect(dialog).toBeVisible()
    expect(dialog).toHaveTextContent(device.device_eui!)
    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(fetcher.mock.calls.some(call => call[1]?.method === 'POST' && call[0].endsWith('/archive'))).toBe(false)
  })
  it('行菜单关闭后跨单位移交仍可见，取消不改变设备归属', async () => {
    useSession.getState().login({ ...admin, permissions: [...admin.permissions, 'units:read'] }, 'management')
    useSession.getState().setScope(['tenant-a'], [], true)
    const fetcher = vi.fn().mockImplementation(basicFetch)
    vi.stubGlobal('fetch', fetcher)
    render(<DevicesPage/>)
    const user = userEvent.setup()
    await screen.findByText(device.name!)
    await deviceAction(user, '跨单位移交')
    const dialog = await screen.findByRole('dialog', { name: '跨单位移交设备' })
    expect(dialog).toBeVisible()
    expect(within(dialog).getByLabelText('目标单位')).toBeVisible()
    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(fetcher.mock.calls.some(call => call[1]?.method === 'POST' && call[0].endsWith('/transfer'))).toBe(false)
  })
  it('迁移锁同产品、当前版本禁选、目标具体新版本+原因+expected_revision且不恢复物理状态', async () => {
    login(); const next = { ...published, id: 'version-next', version_number: 3 }; const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/versions') ? response({ items: [published, draft, next] }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(<DevicesPage/>); const user = userEvent.setup(); await screen.findByText(device.name!); await deviceAction(user, '迁移版本'); expect(screen.getByText(`固定同产品：${product.id}`)).toBeInTheDocument(); expect(screen.queryByLabelText('产品')).not.toBeInTheDocument(); await screen.findByRole('option', { name: /version-next/ }); const versionSelect = screen.getByLabelText('具体已发布版本'); expect(within(versionSelect).getByRole('option', { name: /当前绑定，不可重复迁移/ })).toBeDisabled(); expect(within(versionSelect).queryByRole('option', { name: /version-draft/ })).not.toBeInTheDocument(); await user.selectOptions(versionSelect, next.id); setValue('版本迁移原因', '经确认显式升级'); await user.click(screen.getByRole('button', { name: '核对并迁移版本' })); await user.click(screen.getByRole('button', { name: '确认显式迁移产品版本' }))
    const call = fetcher.mock.calls.find(call => call[0].endsWith('/migrate-version'))!; expect(JSON.parse(String(call[1]!.body))).toEqual({ product_version_id: next.id, expected_revision: 0, reason: '经确认显式升级'}); await screen.findByText(/设备操作已保存/)
  }, 20_000)
  it('设备整行revision冲突必须重新读取后再保存，不自行加减版本也不冒充成功', async () => {
    login(); let current: Device = { ...device }
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') { current = { ...device, revision: 5 }; return response({ code: 'REVISION_CONFLICT', message: '设备状态已变化', request_id: 'device-rev-409' }, 409) }
      if (url.includes('/devices?')) return response(page([current]))
      return basicFetch(url, init)
    }); vi.stubGlobal('fetch', fetcher); render(<DevicesPage/>); const user = userEvent.setup(); await screen.findByText(device.name!); await deviceAction(user, '编辑资料'); setValue('设备名称', '冲突后改名'); await user.click(screen.getByRole('button', { name: '保存设备元数据' })); await screen.findByText(/device-rev-409/); expect(screen.queryByText(/设备操作已保存/)).not.toBeInTheDocument(); const first = fetcher.mock.calls.filter(call => call[1]?.method === 'PUT'); expect(first).toHaveLength(1); expect(JSON.parse(String(first[0][1]!.body))).toMatchObject({ name: '冲突后改名', expected_revision: 0 }); await user.click(within(screen.getByRole('dialog')).getByLabelText('关闭')); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); await user.click(screen.getByRole('button', { name: '刷新数据' })); await waitFor(() => expect(fetcher.mock.calls.filter(call => call[0].includes('/devices?') && call[1]?.method === undefined).length).toBeGreaterThan(1)); await deviceAction(user, '编辑资料'); expect(await within(screen.getByRole('dialog')).findByText(/revision 5/)).toBeInTheDocument(); await user.click(screen.getByRole('button', { name: '保存设备元数据' })); await waitFor(() => expect(fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')).toHaveLength(2)); const second = fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')[1]; expect(JSON.parse(String(second[1]!.body)).expected_revision).toBe(5)
  }, 20_000)
  it('设备编辑按记录归属写入；筛选单位变化不改写目标或取消已打开的操作', async () => {
    login(); let resolve!: (value: Response) => void
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => init?.method === 'PUT' ? new Promise<Response>(r => { resolve = r }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(<DevicesPage/>); const user = userEvent.setup(); await screen.findByText(device.name!); await deviceAction(user, '绑定历史'); expect(await screen.findByText('暂无绑定历史')).toBeInTheDocument(); expect(screen.queryByText('正式开户绑定')).not.toBeInTheDocument(); await user.click(within(screen.getByRole('dialog')).getByLabelText('关闭')); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); await deviceAction(user, '编辑资料'); setValue('设备名称', '改名后'); await user.click(screen.getByRole('button', { name: '保存设备元数据' })); const call = fetcher.mock.calls.find(call => call[1]?.method === 'PUT')!; act(() => useSession.getState().setScope(['tenant-b'], [], true)); expect(screen.getByRole('dialog')).toBeInTheDocument(); expect((call[1]!.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a'); expect(JSON.parse(String(call[1]!.body))).toMatchObject({ name: '改名后', expected_revision: 0 }); await act(async () => resolve(response(device))); expect(await screen.findByText(/设备操作已保存/)).toBeInTheDocument()
  }, 20_000)
})

describe('管理端设备类别维护', () => {
  it('修改已有类别后直接保存，再次清空不会保留旧类别或按型号推断', async () => {
    login()
    const fetcher = vi.fn().mockImplementation(basicFetch)
    vi.stubGlobal('fetch', fetcher)
    const value = { ...device, tenant_id: 'tenant-a', system_type: '消防报警', device_category: '感烟探测器' }
    render(<Sheet open onOpenChange={() => {}}><SheetContent><DeviceEditor editor={{ mode: 'edit', device: value }} onSaved={() => {}} onRefresh={() => {}}/></SheetContent></Sheet>)
    const user = userEvent.setup()
    expect(screen.getByLabelText('设备类别（人工登记）')).toHaveValue('感烟探测器')
    setValue('设备类别（人工登记）', '手动报警按钮')
    await user.click(screen.getByRole('button', { name: '保存设备元数据' }))
    await waitFor(() => expect(fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')).toHaveLength(1))
    expect(JSON.parse(String(fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')[0][1].body))).toMatchObject({ device_category: '手动报警按钮', system_type: '消防报警', expected_revision: 0 })
  })
  it('清空类别并提交显式空值，不回填旧类别', async () => {
    login()
    const fetcher = vi.fn().mockImplementation(basicFetch)
    vi.stubGlobal('fetch', fetcher)
    const value = { ...device, tenant_id: 'tenant-a', system_type: '消防报警', device_category: '感烟探测器' }
    render(<Sheet open onOpenChange={() => {}}><SheetContent><DeviceEditor editor={{ mode: 'edit', device: value }} onSaved={() => {}} onRefresh={() => {}}/></SheetContent></Sheet>)
    const user = userEvent.setup()
    await user.clear(screen.getByLabelText('设备类别（人工登记）'))
    await user.click(screen.getByRole('button', { name: '保存设备元数据' }))
    await waitFor(() => expect(fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')).toHaveLength(1))
    expect(JSON.parse(String(fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')[0][1].body))).toMatchObject({ device_category: '', system_type: '消防报警', expected_revision: 0 })
  })
})

describe('设备位置完整路径', () => {
  it.each(['management', 'customer'] as const)('%s 区分同名房间，直接提交真实位置ID，缺失上级不冒充根节点', async audience => {
    const node = (id: string, name: string, parent_id: string | null): PlaceNode => ({ id, name, parent_id, tenant_id: 'tenant-a', tenant_name: '单位A', legacy_place_id: null, device_count: 0, level: parent_id ? 2 : 1, revision: 0 })
    const places = [node('west', '西楼', null), node('west-205', '205室', 'west'), node('east', '东楼', null), node('east-205', '205室', 'east'), node('partial-205', '205室', 'outside-scope')]
    useSession.getState().login({ ...(audience === 'customer' ? customer : management), permissions: ['device:read', 'device:write', 'units:read'] }, audience)
    const fetcher = vi.fn((url: string, init?: RequestInit) => url.endsWith('/scope-tree') ? response([{ id: 'tenant-a', name: '单位A', places }]) : basicFetch(url, init))
    vi.stubGlobal('fetch', fetcher)
    render(<Sheet open onOpenChange={() => {}}><SheetContent><DeviceEditor editor={{ mode: 'edit', device: { ...device, tenant_id: 'tenant-a', place_id: 'west-205' } }} onSaved={() => {}} onRefresh={() => {}}/></SheetContent></Sheet>)
    const user = userEvent.setup()
    await screen.findByRole('option', { name: '西楼 / 205室' })
    expect(screen.getByRole('option', { name: '东楼 / 205室' })).toBeInTheDocument()
    const select = screen.getByLabelText('所属位置（必选）')
    expect(select).toHaveValue('west-205')
    await user.selectOptions(select, 'east-205')
    if (audience === 'customer') expect(document.querySelector('.confirmation-summary')).toHaveTextContent('东楼 / 205室（east-205）')
    await user.selectOptions(screen.getByLabelText('所属位置（必选）'), 'partial-205')
    expect(screen.getByRole('option', { name: '上级不在当前范围 / 205室' })).toBeInTheDocument()
    if (audience === 'customer') {
      expect(document.querySelector('.confirmation-summary')).toHaveTextContent('上级不在当前范围 / 205室（partial-205）')
      await user.click(screen.getByRole('button', { name: '确认保存资料' }))
    } else await user.click(screen.getByRole('button', { name: '保存设备元数据' }))
    await waitFor(() => expect(fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')).toHaveLength(1))
    expect(JSON.parse(String(fetcher.mock.calls.filter(call => call[1]?.method === 'PUT')[0][1]!.body)).place_id).toBe('partial-205')
  })
})

describe('设备批量移动位置确认消歧', () => {
  it.each(['management', 'customer'] as const)('%s 批量选择后确认完整目标，返回切换同名房间不残留旧路径', async audience => {
    const node = (id: string, name: string, parent_id: string | null): PlaceNode => ({ id, name, parent_id, tenant_id: 'tenant-a', tenant_name: '单位A', legacy_place_id: null, device_count: 0, level: parent_id ? 2 : 1, revision: 0 })
    const places = [node('west', '西楼', null), node('west-205', '205室', 'west'), node('east', '东楼', null), node('east-205', '205室', 'east')]
    const selectedDevices = [{ ...device, tenant_id: 'tenant-a' }, { ...device, id: 'second-device', device_eui: 'vendor:device-002', name: '第二台设备', tenant_id: 'tenant-a', revision: 2 }]
    useSession.getState().login({ ...(audience === 'customer' ? customer : management), permissions: ['device:read', 'device:write', 'units:read'] }, audience)
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places }])
      if (url.includes('/devices?')) return response(page(selectedDevices))
      if (url.endsWith('/data-transfer/kinds')) return response({ kinds: [], protected_storage_ready: true })
      return basicFetch(url, init)
    }))
    render(<DevicesPage/>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('checkbox', { name: '选择当前页全部设备' }))
    await screen.findByRole('option', { name: '西楼 / 205室' })
    await user.selectOptions(screen.getByLabelText('真实目标位置'), 'west-205')
    if (audience === 'management') await user.click(screen.getByRole('button', { name: '核对批量操作' }))
    const west = document.querySelector('.confirmation-summary')!
    expect(west).toHaveTextContent('西楼 / 205室（west-205）')
    expect(west).toHaveTextContent(device.name!)
    expect(west).toHaveTextContent('第二台设备')
    if (audience === 'management') await user.click(screen.getByRole('button', { name: '返回修改' }))
    await user.selectOptions(screen.getByLabelText('真实目标位置'), 'east-205')
    if (audience === 'management') await user.click(screen.getByRole('button', { name: '核对批量操作' }))
    const east = document.querySelector('.confirmation-summary')!
    expect(east).toHaveTextContent('东楼 / 205室（east-205）')
    expect(east).not.toHaveTextContent('西楼 / 205室')
  })
})

describe('批量移动保存后范围刷新', () => {
  it.each(['management', 'customer'] as const)('%s 成功后显示新位置并保留筛选，管理端同步源和目标计数', async audience => {
    const rows = [{ ...device, tenant_id: 'tenant-a' }, { ...device, id: 'count-device-b', device_eui: 'count-device-b', name: '正式未联调B', tenant_id: 'tenant-a' }]
    let committed = false, finishSave!: () => void
    const currentDevices = () => rows.map(row => ({ ...row, tenant_name: '单位A', place_id: committed ? 'count-target' : 'count-source', place_name: committed ? '目标位置' : '源位置', revision: committed ? 1 : 0 }))
    const places = () => [
      { id: 'count-source', tenant_id: 'tenant-a', tenant_name: '单位A', name: '源位置', parent_id: null, legacy_place_id: null, device_count: committed ? 0 : 2, level: 1, revision: 0 },
      { id: 'count-target', tenant_id: 'tenant-a', tenant_name: '单位A', name: '目标位置', parent_id: null, legacy_place_id: null, device_count: committed ? 2 : 0, level: 1, revision: 0 },
    ]
    useSession.getState().login({ ...(audience === 'customer' ? customer : management), permissions: ['device:read', 'device:write', 'units:read'] }, audience)
    useSession.getState().setScope(['tenant-a'], [], true)
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/devices/batch-move') && init?.method === 'POST') return new Promise<Response>(resolve => {
        finishSave = () => { committed = true; resolve(response({ items: currentDevices() })) }
      })
      if (url.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places: places() }])
      if (url.includes('/devices?')) return response(page(currentDevices()))
      if (url.endsWith('/data-transfer/kinds')) return response({ kinds: [], protected_storage_ready: true })
      return basicFetch(url, init)
    }))
    render(<><ScopeTreePanel/><DevicesPage/></>)
    const user = userEvent.setup()
    const counter = (name: string) => screen.getByText(name, { selector: '.scope-tree-panel span' }).closest('label')!
    if (audience === 'management') {
      const tree = await screen.findByRole('tree')
      act(() => tree.focus())
      await user.keyboard('{ArrowDown}{ArrowRight}')
      await waitFor(() => expect(counter('源位置')).toHaveTextContent('源位置2'))
      expect(counter('目标位置')).toHaveTextContent('目标位置0')
    }
    setValue('搜索设备', '正式')
    await user.click(screen.getByRole('button', { name: /^查\s*询$/ }))
    await user.click(await screen.findByRole('checkbox', { name: '选择当前页全部设备' }))
    await screen.findByRole('option', { name: '目标位置' })
    await user.selectOptions(screen.getByLabelText('真实目标位置'), 'count-target')
    if (audience === 'management') await user.click(screen.getByRole('button', { name: '核对批量操作' }))

    await user.click(screen.getByRole('button', { name: audience === 'management' ? '确认批量写入' : '确认批量移动' }))
    await waitFor(() => expect(finishSave).toBeTypeOf('function'))
    if (audience === 'management') expect(counter('源位置')).toHaveTextContent('源位置2')
    await act(async () => finishSave())
    await waitFor(() => {
      for (const row of rows) expect(document.querySelector(`[data-device-id="${row.id}"]`)).toHaveTextContent('目标位置')
      if (audience === 'management') {
        expect(counter('源位置')).toHaveTextContent('源位置0')
        expect(counter('目标位置')).toHaveTextContent('目标位置2')
      }
    })
    expect(screen.getByRole('textbox', { name: '搜索设备' })).toHaveValue('正式')
    expect(useSession.getState().scopeTenantIds).toEqual(['tenant-a'])
    expect(useSession.getState().scopePlaceIds).toEqual([])
    expect(screen.queryByLabelText('真实目标位置')).not.toBeInTheDocument()
  })
})
