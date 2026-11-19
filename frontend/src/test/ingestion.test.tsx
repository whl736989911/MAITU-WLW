import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { api } from '@/lib/api'
import { previewResponse } from '@/lib/ingestion-json'
import { useSession } from '@/lib/store'
import type { Device, IngestPreview, IngestSource, RawMessage, SourceBinding } from '@/lib/types'
import { SourcesPage } from '@/pages/sources'
import { MessagesPage, PreviewResult } from '@/pages/messages'
import { AuthorizedReadScope } from '@/components/authorized-read-scope'
import { ReadingValues } from '@/components/reading-values'
import { Pager } from '@/components/common'
import { customer, management, response } from './fixtures'
const perms = ['sources:read', 'sources:write', 'sources:credentials', 'sources:bind', 'messages:read', 'messages:raw', 'messages:preview', 'device:read', 'device:write']
const admin = { ...management, permissions: [...management.permissions, ...perms] }
const token = 'ONE_TIME_SYNTHETIC_CREDENTIAL_NOT_PRODUCTION'
const source: IngestSource = { id: 'source-one', tenant_id: 'tenant-a', name: '通用测试来源', protocol: 'GENERIC_HTTP_JSON_V1', enabled: true, revision: 4, created_at: '2026-10-23T00:00:00Z', last_received_at: null, message_count: 2, duplicate_count: 0 }
const device: Device = { id: 'device-one', tenant_id: 'tenant-a', name: '匹配真实设备', device_eui: 'eui:unchanged', address: '单位A', model: 'model', connectivity: 'UNACTIVATED', physical_state: 'UNKNOWN', battery_percent: null, last_message_at: null, product_id: 'product-one', product_version_id: 'captured-version', version_number: 1, revision: 0, integration_state: 'PENDING_INTEGRATION', battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'NOT_CONFIGURED', comparator: null, threshold: null, source_scope: 'NONE', source_id: null, source_revision: 0, evaluated_at: '2026-10-23T00:00:00Z' } }
const binding: SourceBinding = { id: 'binding-one', source_id: source.id, device_id: device.id, vendor_device_id: 'opaque/vendor:device', is_active: true, created_at: '2026-10-23T00:00:00Z', ended_at: null }
const message: RawMessage = { id: 'raw-one', tenant_id: 'tenant-a', source_id: source.id, source_name: source.name, source_message_id: 'stable-message-1', vendor_device_id: binding.vendor_device_id, device_id: device.id, device_name: device.name, binding_id: binding.id, product_version_id: 'captured-version', received_at: '2026-10-23T00:00:00Z', reported_at: 'invalid-device-time', occurred_at: null, occurred_at_raw: 'invalid-device-time', payload_bytes: 53, payload_sha256: 'stored-sha-only', binding_state: 'BOUND', duplicate_count: 1, revision: 3, latest_preview_id: 'preview-old', parse_status: 'PREVIEWED', business_status: 'NOT_APPLIED', application_state: 'REJECTED', applied_at: null, apply_error: 'SOURCE_TIME_INVALID', capture_origin: 'AUTHENTICATED_SOURCE' }
const preview: IngestPreview = { id: 'preview-old', raw_message_id: message.id, product_version_id: 'captured-version', event_type: 'UNKNOWN', battery_percent: null, readings: [], diagnostics: ['未映射事件，不归正常'], reported_at: null, time_quality: 'INVALID', raw_event: 'unmapped-value', created_at: '2026-10-23T00:01:00Z', actor_id: 'actor-one', reason: '历史人工诊断', business_status: 'NOT_APPLIED' }
const rawPayload = '  { "event" : "unmapped-value", "n":9007199254740993 }\n'
const page = (items: unknown[]) => ({ items, total: items.length, page: 1, size: 20 })
function login(permissions = admin.permissions) { useSession.getState().login({ ...admin, permissions }, 'management'); useSession.getState().setScope(['tenant-a'], [], true) }
function changeActor() { useSession.getState().login({ ...admin, access_token: 'different-synthetic-session', user: { ...admin.user, id: 'different-actor' } }, 'management'); useSession.getState().setScope(['tenant-b'], [], true) }
function basicFetch(url: string, _init?: RequestInit): Response {
  if (url.endsWith('/scope-tree')) return response([{ id: 'tenant-a', name: '单位A', places: [] }, { id: 'tenant-b', name: '单位B', places: [] }])
  if (url.includes('/sources?')) return response(page([source]))
  if (url.endsWith('/bindings')) return response({ items: [] })
  if (url.includes('/devices?')) return response(page([device, { ...device, id: 'legacy', name: '未绑定模板真实', product_version_id: null, product_id: null, product_name: null, version_number: null }]))
  if (url.includes('/messages?')) return response(page([message]))
  if (url === `/api/management/messages/${message.id}`) return response({ message, previews: [preview] })
  if (url.endsWith('/raw')) return response({ id: message.id, payload: rawPayload, payload_bytes: 53, payload_sha256: 'stored-sha-only' })
  if (url.includes('/previews/')) return response(preview)
  throw new Error(`Unexpected synthetic test endpoint: ${url}`)
}
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }) }
async function selectCreateTarget(user: ReturnType<typeof userEvent.setup>) { await user.selectOptions(await screen.findByLabelText('创建目标单位'), 'tenant-a') }
async function startCreate(user: ReturnType<typeof userEvent.setup>) { await user.click(screen.getByRole('button', { name: '创建接入来源' })); await selectCreateTarget(user) }
function scoped(page: 'sources' | 'messages', children: React.ReactNode) { useSession.getState().navigate(page); return <AuthorizedReadScope>{children}</AuthorizedReadScope> }
async function confirm(user: ReturnType<typeof userEvent.setup>, button: string) { await user.click(screen.getByRole('button', { name: '核对并继续' })); await user.click(screen.getByRole('button', { name: button })) }
async function openSource(user: ReturnType<typeof userEvent.setup>) { await user.click(await screen.findByRole('button', { name: '绑定与历史' })); await screen.findByText('暂无绑定历史') }
async function openMessage(user: ReturnType<typeof userEvent.setup>) { await user.click(await screen.findByRole('button', { name: '查看追踪' })); await screen.findByText('原始来源时间文本') }
beforeEach(() => { vi.restoreAllMocks(); useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })

describe('P4aAPI、独立原文与精确数据', () => {
  it('来源管理请求保留管理Bearer且接入凭据不参与管理API认证', async () => {
    login(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/rotate-token') ? response({ source, one_time_token: token, credential_recovery_required: false }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher)
    await api.sources(1, '%literal_'); await api.messages(1, 'opaque%_', source.id); const credential = await api.rotateSourceToken(source.id, 4, 'rotation-key', undefined, 'tenant-a')
    expect(credential.one_time_token).toBe(token)
    expect(fetcher.mock.calls.every(call => (call[1]!.headers as Headers).get('Authorization') === `Bearer ${admin.access_token}`)).toBe(true)
    expect(new URL(fetcher.mock.calls[1][0], 'http://local').searchParams.get('source_id')).toBe(source.id)
    expect((fetcher.mock.calls[2][1]!.headers as Headers).get('Idempotency-Key')).toBe('rotation-key')
    expect(JSON.parse(String(fetcher.mock.calls[2][1]!.body))).toEqual({ expected_revision: 4})
  })
  it('无raw权限API层也拦/raw，403不退出；列表/详情/预览防御丢弃意外原文字段', async () => {
    login(admin.permissions.filter(value => value !== 'messages:raw'))
    const fetcher = vi.fn().mockImplementation((url: string) => url.includes('/messages?') ? response(page([{ ...message, payload: rawPayload, raw_event: 'unexpected-raw' }])) : url.includes('/previews/') ? response({ ...preview, payload: rawPayload }) : response({ message: { ...message, payload: rawPayload }, previews: [preview] })); vi.stubGlobal('fetch', fetcher)
    await expect(api.messageRaw(message.id)).rejects.toMatchObject({ status: 403, code: 'RAW_PERMISSION_REQUIRED' }); expect(fetcher).not.toHaveBeenCalled(); expect(useSession.getState().token).toBe(admin.access_token)
    const list = await api.messages(1); const detail = await api.messageDetail(message.id, undefined, 'tenant-a'); const attempt = await api.messagePreview(message.id, preview.id, undefined, 'tenant-a')
    expect(list.items[0]).not.toHaveProperty('payload'); expect(list.items[0]).not.toHaveProperty('raw_event'); expect(detail.message).not.toHaveProperty('payload'); expect(detail.previews[0]).not.toHaveProperty('raw_event'); expect(attempt).not.toHaveProperty('raw_event'); expect(attempt).not.toHaveProperty('raw_event_json'); expect(attempt).not.toHaveProperty('payload')
  })
  it('精确电量/raw_event保留原数字片段，无权限时raw_event不是null而是缺省', () => {
    const text = JSON.stringify(preview).replace('"battery_percent":null', '"battery_percent":0.123456789012345678901').replace('"raw_event":"unmapped-value"', '"raw_event":9007199254740993')
    const allowed = previewResponse(text, true), denied = previewResponse(text, false)
    expect(allowed.raw_event_json).toBe('9007199254740993'); expect(allowed.battery_percent_json).toBe('0.123456789012345678901'); expect(Object.hasOwn(denied, 'raw_event')).toBe(false)
    render(<PreviewResult preview={allowed} canRaw/>); expect(screen.getByText('0.123456789012345678901%')).toBeInTheDocument(); expect(screen.getByText('9007199254740993')).toBeInTheDocument(); expect(screen.getByText('UNKNOWN')).toBeInTheDocument()
  })
  it('属性读数保留超安全整数的小数精度与false，且不携带额外原文字段', () => {
    const text = JSON.stringify({ ...preview, readings: [
      { key: 'water_level', label: '水位', value_type: 'NUMBER', value: 0, unit: 'm', payload: 'FORBIDDEN_EXTRA_RAW' },
      { key: 'tamper', label: '拆卸', value_type: 'BOOLEAN', value: false, unit: null },
    ] }).replace('"value":0', '"value":9007199254740993.001')
    const parsed = previewResponse(text, false)
    expect(parsed.readings[0]).not.toHaveProperty('payload')
    expect(parsed.readings_json).not.toContain('FORBIDDEN_EXTRA_RAW')
    expect(parsed).not.toHaveProperty('raw_event')
    render(<ReadingValues readings={parsed.readings} exactJson={parsed.readings_json}/>)
    expect(screen.getByText('9007199254740993.001')).toBeInTheDocument()
    expect(screen.getByText('否（false）')).toBeInTheDocument()
  })
  it('原文直接保留字符串，预览POST只发reason与expected_revision且不附新产品版本', async () => {
    login(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/preview') ? response({ message: { ...message, revision: 4 }, preview }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher)
    expect((await api.messageRaw(message.id, undefined, 'tenant-a')).payload).toBe(rawPayload)
    const result = await api.previewMessage(message.id, { expected_revision: 3, reason: '明确人工预览'}, 'preview-key', undefined, 'tenant-a')
    expect(result.preview.product_version_id).toBe('captured-version'); expect(result.preview.business_status).toBe('NOT_APPLIED')
    expect(JSON.parse(String(fetcher.mock.calls[1][1]!.body))).toEqual({ expected_revision: 3, reason: '明确人工预览'}); expect(fetcher.mock.calls.every(call => call[1]!.cache === 'no-store')).toBe(true)
    expect(fetcher.mock.calls.some(call => /\/devices|\/alarms|\/events/.test(call[0]))).toBe(false)
  })
})

describe('P4a来源、主源绑定与一次性秘密', () => {
  it('客户伪带权限、无来源读取权限或未改密时两页不读API', () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    useSession.getState().login({ ...customer, permissions: admin.permissions }, 'customer'); const first = render(<><SourcesPage/><MessagesPage/></>); expect(fetcher).not.toHaveBeenCalled(); first.unmount()
    useSession.getState().login({ ...admin, permissions: [] }, 'management'); const second = render(<><SourcesPage/><MessagesPage/></>); expect(fetcher).not.toHaveBeenCalled(); second.unmount()
    useSession.getState().login({ ...admin, user: { ...admin.user, must_change_password: true } }, 'management'); useSession.getState().setScope(['tenant-a'], [], true); render(<><SourcesPage/><MessagesPage/></>); expect(fetcher).not.toHaveBeenCalled()
  })
  it('创建默认真实，成功进入独立隐藏凭据抽屉，不自动复制/持久化/放示例；关闭销毁', async () => {
    login(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url === '/api/management/sources' && init?.method === 'POST' ? response({ source, one_time_token: token, credential_recovery_required: false }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher)
    const user = userEvent.setup(), copy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined)
    render(<SourcesPage health={{ status: 'UP', storage_mode: 'h2-dev', generic_ingest_enabled: false, roles: ['api'] }}/>); await screen.findByText(source.name); await startCreate(user); fill('来源名称', '正式通用来源'); await confirm(user, '确认创建接入来源')
    await screen.findByRole('button', { name: '显示一次性令牌' }); expect(copy).not.toHaveBeenCalled(); expect(screen.queryByText(token)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '显示一次性令牌' })); expect(screen.getByLabelText('当前一次性令牌')).toHaveTextContent(token); expect(screen.getByText(/Authorization: Bearer <接入令牌占位符>/)).not.toHaveTextContent(token)
    act(() => useSession.getState().setScope(['tenant-b'], [], true)); expect(screen.getByLabelText('当前一次性令牌')).toHaveTextContent(token)
    await user.click(screen.getByRole('button', { name: '复制一次性令牌' })); expect(copy).toHaveBeenCalledExactlyOnceWith(token); expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0); expect(JSON.stringify(useSession.getState())).not.toContain(token); expect(window.location.href).not.toContain(token)
    await user.click(within(screen.getByRole('dialog', { name: '一次性接入凭据' })).getByRole('button', { name: /^close$|^关闭$/i })); expect(screen.queryByText(token)).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '显示一次性令牌' })).not.toBeInTheDocument()
    const call = fetcher.mock.calls.find(call => call[1]?.method === 'POST')!; expect(JSON.parse(String(call[1]!.body))).toMatchObject({ name: '正式通用来源' }); expect((call[1]!.headers as Headers).get('X-Tenant-Id')).toBe('tenant-a'); expect(fetcher.mock.calls.some(call => call[0].startsWith('/api/ingest'))).toBe(false)
  }, 20_000)
  it('首次响应丢失后同key重试只提示显式轮换，不找回/自动轮换/创建第二源', async () => {
    login(); let writes = 0
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => { if (url === '/api/management/sources' && init?.method === 'POST') { writes++; if (writes === 1) return Promise.reject(new Error('synthetic lost response')); return response({ source, one_time_token: null, credential_recovery_required: true }) } return basicFetch(url, init) }); vi.stubGlobal('fetch', fetcher)
    render(<SourcesPage/>); const user = userEvent.setup(); await startCreate(user); fill('来源名称', '丢首次响应的来源'); await confirm(user, '确认创建接入来源'); await screen.findByText(/NETWORK_ERROR/); await user.click(screen.getByRole('button', { name: '确认创建接入来源' })); await screen.findByText(/首次响应可能丢失，无法找回，请显式轮换/)
    expect(screen.queryByRole('button', { name: '显示一次性令牌' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '复制一次性令牌' })).not.toBeInTheDocument(); expect(fetcher.mock.calls.some(call => call[0].endsWith('/rotate-token'))).toBe(false)
    const calls = fetcher.mock.calls.filter(call => call[0] === '/api/management/sources'); expect(calls).toHaveLength(2); expect((calls[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((calls[1][1]!.headers as Headers).get('Idempotency-Key')); expect(useSession.getState().token).toBe(admin.access_token)
  }, 20_000)
  it('显式轮换带当前source revision，权限独立，旧令牌失效有明确确认', async () => {
    login(admin.permissions.filter(value => !['sources:write', 'sources:bind', 'device:write'].includes(value)))
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/rotate-token') ? response({ source: { ...source, revision: 5 }, one_time_token: token, credential_recovery_required: false }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(<SourcesPage/>); const user = userEvent.setup(); await screen.findByText(source.name); expect(screen.queryByRole('button', { name: '创建接入来源' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '来源配置' })).not.toBeInTheDocument(); await user.click(screen.getByRole('button', { name: '轮换凭据' })); await confirm(user, '确认轮换接入凭据'); await screen.findByRole('button', { name: '显示一次性令牌' }); const call = fetcher.mock.calls.find(call => call[0].endsWith('/rotate-token'))!; expect(JSON.parse(String(call[1]!.body)).expected_revision).toBe(4)
  }, 20_000)
  it('真实已绑定模板设备需要显式选择、原因与二次确认；不根据EUI自动绑定', async () => {
    login(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/bindings') && init?.method === 'POST' ? response({ source: { ...source, revision: 5 }, binding }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(<SourcesPage/>); const user = userEvent.setup(); await openSource(user); await user.click(screen.getByRole('button', { name: '绑定设备' })); const select = await screen.findByLabelText('当前单位设备'); await screen.findByRole('option', { name: /匹配真实设备/ }); expect(select).toHaveValue(''); expect(screen.getByRole('option', { name: /未绑定模板真实/ })).toBeDisabled(); expect(screen.getByLabelText('来源设备标识（不透明值）')).toHaveValue('')
    await user.selectOptions(select, device.id); fill('来源设备标识（不透明值）', binding.vendor_device_id); fill('绑定操作原因', '显式接入当前单位'); await confirm(user, '确认绑定当前主源'); await screen.findByText(/来源配置操作已保存/); const call = fetcher.mock.calls.find(call => call[1]?.method === 'POST')!; expect(JSON.parse(String(call[1]!.body))).toEqual({ device_id: device.id, vendor_device_id: binding.vendor_device_id, reason: '显式接入当前单位', expected_revision: 4}); expect(fetcher.mock.calls.some(call => /migrate-version/.test(call[0]))).toBe(false)
  }, 20_000)
  it('显式解绑保留历史理由，源绑定冲突409仅刷新重开不抢占', async () => {
    login(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/bindings') ? response({ items: [binding] }) : url.endsWith('/detach') ? response({ code: 'BINDING_CONFLICT', message: '绑定版本已变化', request_id: 'binding-409' }, 409) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(<SourcesPage/>); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '绑定与历史' })); await user.click(await screen.findByRole('button', { name: '显式解绑' })); fill('绑定操作原因', '明确结束主源绑定'); await confirm(user, '确认显式解绑主源'); await screen.findByText(/binding-409/); expect(screen.getByRole('button', { name: '返回修改' })).toBeDisabled(); expect(screen.getByRole('button', { name: '确认显式解绑主源' })).toBeDisabled(); const call = fetcher.mock.calls.find(call => call[0].endsWith('/detach'))!; expect(JSON.parse(String(call[1]!.body))).toEqual({ expected_revision: 4, reason: '明确结束主源绑定'}); await user.click(screen.getByRole('button', { name: '刷新后重新打开' })); expect(screen.queryByRole('button', { name: '确认显式解绑主源' })).not.toBeInTheDocument()
  }, 20_000)
  it('切身份取消创建，迟到响应不得在新会话打开一次性令牌', async () => {
    login(); let resolve!: (response: Response) => void
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url === '/api/management/sources' && init?.method === 'POST' ? new Promise<Response>(r => { resolve = r }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(scoped('sources', <SourcesPage/>)); const user = userEvent.setup(); await startCreate(user); fill('来源名称', '迟到凭据来源'); await confirm(user, '确认创建接入来源'); act(changeActor); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); const call = fetcher.mock.calls.find(call => call[1]?.method === 'POST')!; expect(call[1]!.signal!.aborted).toBe(true); await act(async () => resolve(response({ source, one_time_token: token, credential_recovery_required: false }))); expect(screen.queryByRole('button', { name: '显示一次性令牌' })).not.toBeInTheDocument(); expect(screen.queryByText(token)).not.toBeInTheDocument(); expect(JSON.stringify(useSession.getState())).not.toContain(token)
  }, 20_000)
  it('已经显示的令牌在切身份或登出后销毁，不能从新会话重新打开', async () => {
    login(); const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/management/sources' && init?.method === 'POST') return response({ source, one_time_token: token, credential_recovery_required: false })
      return basicFetch(url, init)
    }); vi.stubGlobal('fetch', fetcher); render(scoped('sources', <SourcesPage/>)); const user = userEvent.setup()
    for (const boundary of ['identity', 'logout']) {
      if (boundary === 'logout') act(() => login())
      await startCreate(user); fill('来源名称', '边界销毁验证'); await confirm(user, '确认创建接入来源'); await user.click(await screen.findByRole('button', { name: '显示一次性令牌' })); expect(screen.getByLabelText('当前一次性令牌')).toHaveTextContent(token)
      act(() => { if (boundary === 'identity') changeActor(); else useSession.getState().clear() })
      expect(screen.queryByLabelText('当前一次性令牌')).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '显示一次性令牌' })).not.toBeInTheDocument(); expect(JSON.stringify(useSession.getState())).not.toContain(token)
    }
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
  }, 20_000)
  it('复用分页按钮在绑定表单中不是submit，不意外进入敏感确认', async () => {
    const submit = vi.fn((event: React.FormEvent) => event.preventDefault()); render(<form onSubmit={submit}><Pager page={1} size={20} total={40} onChange={() => undefined}/></form>); await userEvent.click(screen.getByRole('button', { name: 'Next Page' })); expect(submit).not.toHaveBeenCalled()
  })
})

describe('P4a消息元数据、原文与NOT_APPLIED历史预览', () => {
  it('真实消息状态与来源错误码来自服务端应用字段', async () => {
    login(); vi.stubGlobal('fetch', vi.fn().mockImplementation(basicFetch)); render(<MessagesPage/>)
    expect(await screen.findByText('REJECTED · 服务端拒绝')).toBeInTheDocument()
    expect(screen.getByText('处理错误：SOURCE_TIME_INVALID')).toBeInTheDocument()
    const user = userEvent.setup(); await openMessage(user)
    expect(screen.getByText('REJECTED · 服务端拒绝 · REJECTED')).toBeInTheDocument()
    expect(screen.getByText('SOURCE_TIME_INVALID')).toBeInTheDocument()
    expect(screen.getByText('认证接入来源捕获 · AUTHENTICATED_SOURCE')).toBeInTheDocument()
  })
  it('无raw权限可看安全历史诊断，无raw按钮/端点/原始事件，不混成null授权', async () => {
    login(admin.permissions.filter(value => !['messages:raw', 'messages:preview', 'sources:read'].includes(value)))
    const fetcher = vi.fn().mockImplementation(basicFetch); vi.stubGlobal('fetch', fetcher); render(<MessagesPage/>); const user = userEvent.setup(); await openMessage(user); expect(screen.queryByRole('button', { name: '查看独立原文' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '创建无副作用预览' })).not.toBeInTheDocument(); await user.click(screen.getByRole('button', { name: '查看此历史尝试' })); await screen.findByText('UNKNOWN'); expect(screen.getByText('未映射事件，不归正常')).toBeInTheDocument(); expect(screen.queryByText('unmapped-value')).not.toBeInTheDocument(); expect(screen.queryByLabelText('原始事件诊断')).not.toBeInTheDocument(); expect(fetcher.mock.calls.some(call => call[0].endsWith('/raw'))).toBe(false); expect(fetcher.mock.calls.some(call => call[0].includes('/sources'))).toBe(false)
  }, 20_000)
  it('原文按明确请求读取；关闭或切身份销毁，不进入会话存储', async () => {
    login(); const fetcher = vi.fn().mockImplementation(basicFetch); vi.stubGlobal('fetch', fetcher); render(scoped('messages', <MessagesPage/>)); const user = userEvent.setup(); await openMessage(user); expect(fetcher.mock.calls.some(call => call[0].endsWith('/raw'))).toBe(false); await user.click(screen.getByRole('button', { name: '查看独立原文' })); const raw = await screen.findByLabelText('原始报文全文'); expect(raw.textContent).toBe(rawPayload); expect(JSON.stringify(useSession.getState())).not.toContain(rawPayload); await user.click(screen.getByRole('button', { name: '关闭原文并销毁' })); expect(screen.queryByLabelText('原始报文全文')).not.toBeInTheDocument(); await user.click(screen.getByRole('button', { name: '查看独立原文' })); await screen.findByLabelText('原始报文全文'); act(changeActor); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(screen.queryByLabelText('原始报文全文')).not.toBeInTheDocument()
  }, 20_000)
  it('历史预览需要原因和当前raw revision，只用捕获版本，响应不推进任何业务API', async () => {
    login(admin.permissions.filter(value => value !== 'messages:raw')); let current = { ...message }
    const nextPreview = { ...preview, id: 'preview-new', reason: '明确诊断历史报文' }
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith('/preview') && init?.method === 'POST') { current = { ...current, revision: 4, latest_preview_id: nextPreview.id }; return response({ message: current, preview: nextPreview }) }
      if (url === `/api/management/messages/${message.id}`) return response({ message: current, previews: [preview, nextPreview] })
      if (url.endsWith('/previews/preview-new')) return response(nextPreview)
      if (url.includes('/sources?')) return response(page([{ ...source, enabled: false }]))
      return basicFetch(url, init)
    }); vi.stubGlobal('fetch', fetcher); render(<MessagesPage/>); const user = userEvent.setup(); await openMessage(user); await user.click(screen.getByRole('button', { name: '创建无副作用预览' })); fill('预览原因', '明确诊断历史报文'); await user.click(screen.getByRole('button', { name: '提交' })); await screen.findByText(/预览尝试已保存 · NOT_APPLIED/); await screen.findByText('历史解析结果 · 仅预览'); expect(screen.getByText('UNKNOWN')).toBeInTheDocument(); expect(screen.getByText('未提供（不回退接收时间）')).toBeInTheDocument(); expect(screen.queryByText('0%')).not.toBeInTheDocument(); expect(screen.queryByText('正常')).not.toBeInTheDocument(); expect(screen.queryByLabelText('原始事件诊断')).not.toBeInTheDocument()
    const call = fetcher.mock.calls.find(call => call[1]?.method === 'POST')!; expect(JSON.parse(String(call[1]!.body))).toEqual({ expected_revision: 3, reason: '明确诊断历史报文'}); expect(fetcher.mock.calls.some(call => /\/devices|\/alarms|\/events|\/versions|\/raw$/.test(call[0]))).toBe(false)
  }, 20_000)
  it('无绑定旧消息不可预览，不自动使用后来绑定或最新产品补处理', async () => {
    login(); const unbound = { ...message, binding_state: 'UNBOUND', product_version_id: null, binding_id: null, device_id: null, device_name: null, parse_status: 'NOT_PREVIEWED', latest_preview_id: null }
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.includes('/messages?') ? response(page([unbound])) : url === `/api/management/messages/${message.id}` ? response({ message: unbound, previews: [] }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(<MessagesPage/>); const user = userEvent.setup(); await openMessage(user); expect(screen.getByRole('button', { name: '创建无副作用预览' })).toBeDisabled(); expect(screen.getByText(/后续绑定不会回填此消息/)).toBeInTheDocument(); expect(screen.getByText('尚无预览尝试')).toBeInTheDocument(); expect(fetcher.mock.calls.some(call => call[1]?.method === 'POST' || call[0].includes('/versions'))).toBe(false)
  })
  it('仅显示层去普通十进制小数尾零，保留有效精度/科学计数/原始事件和输入对象', () => {
    const view = render(<PreviewResult preview={preview} canRaw/>)
    for (const [literal, display] of [
      [`80.${'0'.repeat(36)}`, '80'], ['0.10000000000000001000', '0.10000000000000001'], ['0.000', '0'],
      ['80', '80'], ['80.01000', '80.01'], ['0.00120', '0.0012'], ['-0.000', '-0'],
      ['8.000e+1', '8.000e+1'], ['1.2300E-7', '1.2300E-7'], ['01.000', '01.000'],
    ]) {
      const unchanged = Object.freeze({ ...preview, battery_percent_json: literal, raw_event_json: literal })
      view.rerender(<PreviewResult preview={unchanged} canRaw/>)
      expect(screen.getByText(`${display}%`)).toBeInTheDocument()
      expect(screen.getByLabelText('原始事件诊断').textContent).toBe(literal)
      expect(unchanged.battery_percent_json).toBe(literal); expect(unchanged.raw_event_json).toBe(literal)
    }
  })
  it('REPORTED仅来源声明未校准，真0电量保留，缺失电量不冒充0', () => {
    const view = render(<PreviewResult preview={{ ...preview, time_quality: 'REPORTED', reported_at: '2026-10-23T00:00:00Z', battery_percent: 0, battery_percent_json: '0' }} canRaw={false}/>); expect(screen.getByText('0%')).toBeInTheDocument(); expect(screen.getByText(/来源声明（未校准，不作可信排序）/)).toBeInTheDocument(); view.unmount(); render(<PreviewResult preview={preview} canRaw={false}/>); expect(screen.getByText('未上报')).toBeInTheDocument(); expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })
  it('原文/预览读取迟到响应在切单位后不展示，旧actor/报文不写入会话', async () => {
    login(); let resolve!: (response: Response) => void
    const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.endsWith('/raw') ? new Promise<Response>(r => { resolve = r }) : basicFetch(url, init)); vi.stubGlobal('fetch', fetcher); render(scoped('messages', <MessagesPage/>)); const user = userEvent.setup(); await openMessage(user); await user.click(screen.getByRole('button', { name: '查看独立原文' })); const call = fetcher.mock.calls.find(call => call[0].endsWith('/raw'))!; act(() => useSession.getState().setScope(['tenant-b'], [], true)); expect(call[1]!.signal!.aborted).toBe(true); await act(async () => resolve(response({ id: message.id, payload: rawPayload, payload_bytes: 53, payload_sha256: 'stored-sha-only' }))); expect(screen.queryByLabelText('原始报文全文')).not.toBeInTheDocument(); expect(JSON.stringify(useSession.getState())).not.toContain(rawPayload)
  }, 20_000)
})
