import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ruleFlowApiFor } from '@/lib/rule-flows'
import { RuleFlowEditor } from '@/components/rule-flow-editor'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

const admin = { ...management, permissions: [...management.permissions, 'rules:read', 'rules:write', 'rules:publish'] }
const hash = (char: string) => char.repeat(64)

// Every fixture below is the exact shape Spring writes: global SNAKE_CASE for records, while map payloads
// (node config, base configuration, receivers, consumer detail) keep their owning consumer's key grammar.
const definitionWire = {
  nodes: [
    { id: 'mqtt-1', type: 'mqtt', config: { applicationId: 'app-live' }, x: 20, y: 20 },
    { id: 'source', type: 'device.message', config: { scope: { mode: 'ALL', placeIds: [], deviceIds: [] }, systemTypes: [] }, x: 300, y: 20 },
    { id: 'persist', type: 'persist', config: {}, x: 560, y: 20 },
  ],
  edges: [{ from: 'source', to: 'persist' }, { from: 'persist', to: 'mqtt-1' }],
}
const flowWire = {
  id: 'flow-1', tenant_id: 'tenant-a', name: '夜间通知', description: '真实蛇形响应', status: 'PUBLISHED',
  revision: 4, published_revision: 3, enabled: false, last_compile_ok: true,
  last_compile_issues: [{ node_id: 'mqtt-1', severity: 'NOTE', code: 'REFERENCE_OK', message: '引用核对通过' }],
  last_compile_at: '2026-11-12T01:00:00Z', created_by: 'u1', created_at: '2026-11-10T01:00:00Z',
  updated_by: 'u1', updated_at: '2026-11-12T01:00:00Z', archived_at: null, archived_reason: null,
  definition_sha256: hash('a'),
}
const baseConfiguration = {
  unitService: { pushRules: { enabled: true, period_hours: 24 } },
  subscriptions: { exclude: ['FAULT'] },
  voiceBroadcast: { fields: ['zone', 'position'] },
}
const planWire = {
  definition_sha256: hash('b'), draft_revision: 4, ok: true,
  issues: [{ node_id: null, severity: 'NOTE', code: 'PLAN_READY', message: '计划已计算' }],
  base_configuration: baseConfiguration, owned_fields: ['unitService'],
  merge: { enabled: true, window_seconds: 120 },
  notification_rules: [{
    event_scope: 'FIRE',
    channels: [{ channel: 'SMS', escalation_minutes: null, receivers: [{ contactId: 'c-1', level: 1 }] }],
  }],
  references: [{ node_id: 'mqtt-1', consumer_kind: 'OUTPUT_APPLICATION', consumer_id: 'app-live', consumer_name: '已发布MQTT应用', consumer_revision: 9, effect: '只读引用' }],
  dependencies: [
    { consumer_kind: 'OUTPUT_APPLICATION', scope_key: 'application:app-live', consumer_id: 'app-live', revision: 9 },
    { consumer_kind: 'NOTIFICATION_RULE', scope_key: 'FIRE', consumer_id: null, revision: null },
  ],
  diffs: [{ consumer_kind: 'ALARM_MERGE_POLICY', scope_key: 'unit', field: 'enabled+window', before: 'false/null', after: 'true/120' }],
  plan_hash: hash('f'),
}
const bindingsWire = [{ consumer_kind: 'OUTPUT_APPLICATION', scope_key: 'application:app-live', consumer_id: 'app-live', consumer_revision: 9, owned_fields: ['name'], updated_at: '2026-11-12T01:00:00Z' }]
const catalogWire = {
  node_types: [
    { type: 'mqtt', label: '历史数据输出引用（已退役）', category: 'REFERENCE', port: 'REFERENCE', fixed: false, min_count: 0, max_count: 4, supported: false, rationale: '已退役', description: '历史记录' },
    { type: 'device.message', label: '设备消息', category: 'SOURCE', port: 'START', fixed: true, min_count: 1, max_count: 1, supported: true, rationale: '入口', description: '真实事实入口' },
    { type: 'persist', label: '持久化', category: 'NATIVE', port: 'FIXED', fixed: true, min_count: 1, max_count: 1, supported: true, rationale: '固定', description: '固定阶段' },
  ],
  channels: [{ channel: 'SMS', supported: true, rationale: '' }, { channel: 'VOICE', supported: true, rationale: '' }, { channel: 'WECHAT', supported: false, rationale: '通道未接通' }],
  system_types: [{ id: 'JTY-GD', name: 'JTY-GD' }],
  places: [{ id: 'place-a', name: '一号楼', path: '单位A / 一号楼' }],
  devices: [{ id: 'device-1', name: '烟感', model: 'JTY-GD', place_path: '单位A / 一号楼', in_current_scope: true }],
  contacts: [{ id: 'c-1', name: '值班员', phone_masked: '138****0000', enabled: true }],
  linkage_rules: [{ id: 'link-1', name: '排烟联动', kind: 'CUSTOM', enabled: true, has_controllers: true }],
  applications: [
    { id: 'app-live', name: 'MQTT上送', status: 'ACTIVE', enabled_outputs: 2, mqtt_enabled_outputs: 2 },
    { id: 'app-idle', name: '仅HTTPS', status: 'ACTIVE', enabled_outputs: 1, mqtt_enabled_outputs: 0 },
  ],
  event_codes: ['FIRE', '200002'], linkage_actions: ['start'], channel_support_known: true,
}
const detailWire = { flow: flowWire, definition: definitionWire, published_draft_drift: 3 }
const previewWire = { plan: planWire, bindings: bindingsWire }
const revisionsWire = [{ revision: 3, definition_sha256: hash('c'), plan_hash: hash('e'), reason: '首次发布', published_by: '发布人甲', published_at: '2026-11-11T00:00:00Z' }]
const compiledWire = {
  flow_id: 'flow-1', published_revision: 3, enabled: false,
  consumers: [{ consumer_kind: 'OUTPUT_APPLICATION', scope_key: 'application:app-live', consumer_id: 'app-live', consumer_revision: 8, drift_detected: true, effect: '只读引用（规则流不启停外发）', detail: { name: 'MQTT上送', status: 'ACTIVE' } }],
}

function route(url: string): Response {
  if (url.endsWith('/rule-flows/catalog')) return response(catalogWire)
  if (url.endsWith('/rule-flows/flow-1/preview')) return response(previewWire)
  if (url.endsWith('/rule-flows/flow-1/revisions')) return response(revisionsWire)
  if (url.endsWith('/rule-flows/flow-1/compiled')) return response(compiledWire)
  if (url.endsWith('/rule-flows/flow-1')) return response(detailWire)
  throw new Error(`unexpected rule-flow request: ${url}`)
}

function login() {
  useSession.getState().login(admin, 'management')
  useSession.getState().setScope(['tenant-a'], [])
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(route(url))))
}

beforeEach(() => useSession.getState().clear())

describe('规则流真实蛇形响应边界', () => {
  it('保留已退役输出节点的历史引用和消费回读，不允许重新配置', async () => {
    login()
    const api = ruleFlowApiFor('tenant-a')
    const catalog = await api.catalog()
    const detail = await api.detail('flow-1')
    render(<RuleFlowEditor tenantId="tenant-a" flow={detail.flow} definition={detail.definition} catalog={catalog}
      canWrite={false} canPublish onSaved={() => {}}/>)

    expect(screen.getByText(/第三方输出已退役。历史应用编号：app-live/)).toBeInTheDocument()
    expect(screen.queryByLabelText('已发布数据输出应用')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '编译预览' }))
    expect(await screen.findByText('true/120')).toBeInTheDocument()
    expect(screen.getByText('false/null')).toBeInTheDocument()
    expect(screen.getByText('ffffffffffffffff…')).toBeInTheDocument()
    expect(screen.getByText('已发布MQTT应用')).toBeInTheDocument()
    expect(screen.getAllByText(/修订 9/).length).toBeGreaterThan(0)
    expect(screen.getByText('（将新建）')).toBeInTheDocument()
    expect(screen.getAllByText('application:app-live').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('button', { name: /发布历史/ }))
    expect(await screen.findByText('首次发布')).toBeInTheDocument()
    expect(screen.getByText('发布人甲')).toBeInTheDocument()
    expect(screen.getByText('eeeeeeeeeeeeeeee…')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /刷新消费回读/ }))
    expect(await screen.findByText('已漂移')).toBeInTheDocument()
  })

  it('缺少mqtt_enabled_outputs时拒绝目录，不把能力计数默认为0', async () => {
    login()
    const { mqtt_enabled_outputs: _omitted, ...withoutMqtt } = catalogWire.applications[0]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...catalogWire, applications: [withoutMqtt] })))
    await expect(ruleFlowApiFor('tenant-a').catalog()).rejects.toThrow()
  })
})
