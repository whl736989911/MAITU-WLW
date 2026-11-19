import { ApiError, request } from './api'

/**
 * Rule-flow transport. The graph is bounded and reviewed server-side; this module only carries it and
 * refuses responses whose shape was not agreed, so a partial or substituted payload never renders as a plan.
 *
 * The server uses the global SNAKE_CASE Jackson strategy, so every method below decodes the exact wire
 * spelling once, here, into the camelCase types the UI consumes. Maps are deliberately not rewritten: node
 * config, the alarm base configuration projection, consumer readback detail and notification receiver rows
 * keep the key grammar their owning consumer stores and reviews.
 */
export type RuleFlowStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type IssueSeverity = 'ERROR' | 'NOTE'

export interface RuleFlowNode { id: string; type: string; config: Record<string, unknown>; x: number; y: number }
export interface RuleFlowEdge { from: string; to: string }
export interface RuleFlowDefinition { nodes: RuleFlowNode[]; edges: RuleFlowEdge[] }

export interface RuleFlowIssue { nodeId: string | null; severity: IssueSeverity; code: string; message: string }
export interface RuleFlowDependency { consumerKind: string; scopeKey: string; consumerId: string | null; revision: number | null }
export interface RuleFlowDiff { consumerKind: string; scopeKey: string; field: string; before: string; after: string }
export interface RuleFlowReceiver { contactId: string; level: number; channel?: string }
export interface RuleFlowChannelTarget { channel: string; receivers: RuleFlowReceiver[]; escalationMinutes: number | null }
export interface RuleFlowRuleTarget { eventScope: string; channels: RuleFlowChannelTarget[] }
export interface RuleFlowMergeTarget { enabled: boolean; windowSeconds: number | null }
export interface RuleFlowReference { nodeId: string; consumerKind: string; consumerId: string; consumerName: string; consumerRevision: number; effect: string }

export interface RuleFlowPlan {
  definitionSha256: string
  draftRevision: number
  ok: boolean
  issues: RuleFlowIssue[]
  baseConfiguration: Record<string, unknown>
  ownedFields: string[]
  merge: RuleFlowMergeTarget | null
  notificationRules: RuleFlowRuleTarget[]
  references: RuleFlowReference[]
  dependencies: RuleFlowDependency[]
  diffs: RuleFlowDiff[]
  planHash: string
}

export interface RuleFlow {
  id: string
  tenantId: string
  name: string
  description: string
  status: RuleFlowStatus
  revision: number
  publishedRevision: number | null
  enabled: boolean
  lastCompileOk: boolean | null
  lastCompileIssues: RuleFlowIssue[]
  lastCompileAt: string | null
  createdBy: string
  createdAt: string
  updatedBy: string
  updatedAt: string
  archivedAt: string | null
  archivedReason: string | null
  definitionSha256: string
}
export interface RuleFlowDetail { flow: RuleFlow; definition: RuleFlowDefinition; publishedDraftDrift: number | null }
export interface RuleFlowBinding { consumerKind: string; scopeKey: string; consumerId: string; consumerRevision: number; ownedFields: string[]; updatedAt: string }
export interface RuleFlowRevision { revision: number; definitionSha256: string; planHash: string; reason: string; publishedBy: string; publishedAt: string }
export interface RuleFlowRevisionDetail { revision: number; definition: RuleFlowDefinition; plan: RuleFlowPlan; reason: string; publishedBy: string; publishedAt: string }
export interface RuleFlowCompileReport { id: string; draftRevision: number; planHash: string; ok: boolean; issues: RuleFlowIssue[]; createdBy: string; createdAt: string }
export interface RuleFlowEvent { id: string; action: string; revisionBefore: number | null; revisionAfter: number; actorId: string; reason: string; detailJson: string; createdAt: string }
export interface RuleFlowConsumerReadback { consumerKind: string; scopeKey: string; consumerId: string; consumerRevision: number; driftDetected: boolean; effect: string; detail: Record<string, unknown> }
export interface RuleFlowCompiledView { flowId: string; publishedRevision: number; enabled: boolean; consumers: RuleFlowConsumerReadback[] }

export interface RuleFlowNodeType { type: string; label: string; category: string; port: string; fixed: boolean; minCount: number; maxCount: number; supported: boolean; rationale: string; description: string }
export interface RuleFlowChannelSupport { channel: string; supported: boolean; rationale: string }
export interface RuleFlowOption { id: string; name: string }
export interface RuleFlowPlaceOption { id: string; name: string; path: string }
export interface RuleFlowDeviceOption { id: string; name: string | null; model: string | null; placePath: string | null; inCurrentScope: boolean }
export interface RuleFlowContactOption { id: string; name: string; phoneMasked: string; enabled: boolean }
export interface RuleFlowLinkageOption { id: string; name: string; kind: string; enabled: boolean; hasControllers: boolean }
export interface RuleFlowApplicationOption { id: string; name: string; status: string; enabledOutputs: number; mqttEnabledOutputs: number }
export interface RuleFlowCatalog {
  nodeTypes: RuleFlowNodeType[]
  channels: RuleFlowChannelSupport[]
  systemTypes: RuleFlowOption[]
  places: RuleFlowPlaceOption[]
  devices: RuleFlowDeviceOption[]
  contacts: RuleFlowContactOption[]
  linkageRules: RuleFlowLinkageOption[]
  applications: RuleFlowApplicationOption[]
  eventCodes: string[]
  linkageActions: string[]
  channelSupportKnown: boolean
}

export interface RuleFlowPreview { plan: RuleFlowPlan; bindings: RuleFlowBinding[] }
export interface RuleFlowPublish { published: boolean; flow: RuleFlow; revision: number | null; plan: RuleFlowPlan; bindings: RuleFlowBinding[]; issues: RuleFlowIssue[] }

type Wire = Record<string, unknown>

function unconfirmed(label: string): never {
  throw new ApiError(200, 'RULE_FLOW_RESPONSE_UNCONFIRMED', `${label}响应格式未确认`)
}
function record(value: unknown, label: string): Wire {
  if (!value || typeof value !== 'object' || Array.isArray(value)) unconfirmed(label)
  return value as Wire
}
/** A required string: an absent ID, hash or actor is never replaced with an empty value. */
function text(source: Wire, key: string, label: string): string {
  const value = source[key]
  if (typeof value !== 'string') unconfirmed(`${label}字段 ${key}`)
  return value
}
/** A declared-nullable string. Present but wrong-typed values still fail. */
function textOrNull(source: Wire, key: string, label: string): string | null {
  const value = source[key]
  if (value === null || value === undefined) return null
  if (typeof value !== 'string') unconfirmed(`${label}字段 ${key}`)
  return value
}
/** A required number, kept as the server's JSON number rather than coerced or defaulted to 0. */
function total(source: Wire, key: string, label: string): number {
  const value = source[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) unconfirmed(`${label}字段 ${key}`)
  return value
}
function totalOrNull(source: Wire, key: string, label: string): number | null {
  const value = source[key]
  if (value === null || value === undefined) return null
  if (typeof value !== 'number' || !Number.isFinite(value)) unconfirmed(`${label}字段 ${key}`)
  return value
}
function flag(source: Wire, key: string, label: string): boolean {
  const value = source[key]
  if (typeof value !== 'boolean') unconfirmed(`${label}字段 ${key}`)
  return value
}
function flagOrNull(source: Wire, key: string, label: string): boolean | null {
  const value = source[key]
  if (value === null || value === undefined) return null
  if (typeof value !== 'boolean') unconfirmed(`${label}字段 ${key}`)
  return value
}
function list(source: Wire, key: string, label: string): unknown[] {
  const value = source[key]
  if (!Array.isArray(value)) unconfirmed(`${label}字段 ${key}`)
  return value
}
function texts(source: Wire, key: string, label: string): string[] {
  const value = list(source, key, label)
  if (value.some(item => typeof item !== 'string')) unconfirmed(`${label}字段 ${key}`)
  return [...value] as string[]
}
/** Map-valued projections are carried verbatim: their keys belong to the owning consumer, not this decoder. */
function mapOf(source: Wire, key: string, label: string): Record<string, unknown> {
  return record(source[key], `${label}字段 ${key}`)
}
function issue(source: unknown): RuleFlowIssue {
  const row = record(source, '编译问题')
  const severity = text(row, 'severity', '编译问题')
  if (severity !== 'ERROR' && severity !== 'NOTE') unconfirmed('编译问题字段 severity')
  return {
    nodeId: textOrNull(row, 'node_id', '编译问题'),
    severity,
    code: text(row, 'code', '编译问题'),
    message: text(row, 'message', '编译问题'),
  }
}
function issues(source: unknown): RuleFlowIssue[] {
  if (!Array.isArray(source)) unconfirmed('编译问题列表')
  return source.map(issue)
}
function definition(source: unknown): RuleFlowDefinition {
  const value = record(source, '规则流定义')
  const nodes = list(value, 'nodes', '规则流定义').map(item => {
    const node = record(item, '节点')
    if (typeof node.config !== 'object' || node.config === null || Array.isArray(node.config)) unconfirmed('节点字段 config')
    return { id: text(node, 'id', '节点'), type: text(node, 'type', '节点'), config: node.config as Record<string, unknown>, x: total(node, 'x', '节点'), y: total(node, 'y', '节点') }
  })
  const edges = list(value, 'edges', '规则流定义').map(item => {
    const edge = record(item, '连线')
    return { from: text(edge, 'from', '连线'), to: text(edge, 'to', '连线') }
  })
  return { nodes, edges }
}
function mergeTarget(source: unknown): RuleFlowMergeTarget {
  const value = record(source, '归并目标')
  return { enabled: flag(value, 'enabled', '归并目标'), windowSeconds: totalOrNull(value, 'window_seconds', '归并目标') }
}
function receiver(source: unknown): RuleFlowReceiver {
  // Receiver rows are Map payloads written by the compiler with camelCase keys; they are not records.
  const value = record(source, '接收人')
  const channel = textOrNull(value, 'channel', '接收人')
  return { contactId: text(value, 'contactId', '接收人'), level: total(value, 'level', '接收人'), ...(channel === null ? {} : { channel }) }
}
function channelTarget(source: unknown): RuleFlowChannelTarget {
  const value = record(source, '通道目标')
  return { channel: text(value, 'channel', '通道目标'), receivers: list(value, 'receivers', '通道目标').map(receiver), escalationMinutes: totalOrNull(value, 'escalation_minutes', '通道目标') }
}
function ruleTarget(source: unknown): RuleFlowRuleTarget {
  const value = record(source, '通知目标')
  return { eventScope: text(value, 'event_scope', '通知目标'), channels: list(value, 'channels', '通知目标').map(channelTarget) }
}
function reference(source: unknown): RuleFlowReference {
  const value = record(source, '引用')
  return { nodeId: text(value, 'node_id', '引用'), consumerKind: text(value, 'consumer_kind', '引用'), consumerId: text(value, 'consumer_id', '引用'), consumerName: text(value, 'consumer_name', '引用'), consumerRevision: total(value, 'consumer_revision', '引用'), effect: text(value, 'effect', '引用') }
}
function dependency(source: unknown): RuleFlowDependency {
  const value = record(source, '依赖')
  return { consumerKind: text(value, 'consumer_kind', '依赖'), scopeKey: text(value, 'scope_key', '依赖'), consumerId: textOrNull(value, 'consumer_id', '依赖'), revision: totalOrNull(value, 'revision', '依赖') }
}
function diff(source: unknown): RuleFlowDiff {
  const value = record(source, '影响预览')
  return { consumerKind: text(value, 'consumer_kind', '影响预览'), scopeKey: text(value, 'scope_key', '影响预览'), field: text(value, 'field', '影响预览'), before: text(value, 'before', '影响预览'), after: text(value, 'after', '影响预览') }
}
function plan(source: unknown): RuleFlowPlan {
  const value = record(source, '编译计划')
  return {
    definitionSha256: text(value, 'definition_sha256', '编译计划'),
    draftRevision: total(value, 'draft_revision', '编译计划'),
    ok: flag(value, 'ok', '编译计划'),
    issues: issues(value.issues),
    baseConfiguration: mapOf(value, 'base_configuration', '编译计划'),
    ownedFields: texts(value, 'owned_fields', '编译计划'),
    merge: value.merge == null ? null : mergeTarget(value.merge),
    notificationRules: list(value, 'notification_rules', '编译计划').map(ruleTarget),
    references: list(value, 'references', '编译计划').map(reference),
    dependencies: list(value, 'dependencies', '编译计划').map(dependency),
    diffs: list(value, 'diffs', '编译计划').map(diff),
    planHash: text(value, 'plan_hash', '编译计划'),
  }
}
function flow(source: unknown): RuleFlow {
  const value = record(source, '规则流')
  const status = text(value, 'status', '规则流')
  if (status !== 'DRAFT' && status !== 'PUBLISHED' && status !== 'ARCHIVED') unconfirmed('规则流字段 status')
  return {
    id: text(value, 'id', '规则流'),
    tenantId: text(value, 'tenant_id', '规则流'),
    name: text(value, 'name', '规则流'),
    description: text(value, 'description', '规则流'),
    status,
    revision: total(value, 'revision', '规则流'),
    publishedRevision: totalOrNull(value, 'published_revision', '规则流'),
    enabled: flag(value, 'enabled', '规则流'),
    lastCompileOk: flagOrNull(value, 'last_compile_ok', '规则流'),
    lastCompileIssues: issues(value.last_compile_issues),
    lastCompileAt: textOrNull(value, 'last_compile_at', '规则流'),
    createdBy: text(value, 'created_by', '规则流'),
    createdAt: text(value, 'created_at', '规则流'),
    updatedBy: text(value, 'updated_by', '规则流'),
    updatedAt: text(value, 'updated_at', '规则流'),
    archivedAt: textOrNull(value, 'archived_at', '规则流'),
    archivedReason: textOrNull(value, 'archived_reason', '规则流'),
    definitionSha256: text(value, 'definition_sha256', '规则流'),
  }
}
function bindings(source: unknown): RuleFlowBinding[] {
  if (!Array.isArray(source)) unconfirmed('消费者绑定')
  return source.map(item => {
    const row = record(item, '消费者绑定')
    return {
      consumerKind: text(row, 'consumer_kind', '消费者绑定'),
      scopeKey: text(row, 'scope_key', '消费者绑定'),
      consumerId: text(row, 'consumer_id', '消费者绑定'),
      consumerRevision: total(row, 'consumer_revision', '消费者绑定'),
      ownedFields: texts(row, 'owned_fields', '消费者绑定'),
      updatedAt: text(row, 'updated_at', '消费者绑定'),
    }
  })
}
function revision(source: unknown): RuleFlowRevision {
  const value = record(source, '发布修订')
  return {
    revision: total(value, 'revision', '发布修订'),
    definitionSha256: text(value, 'definition_sha256', '发布修订'),
    planHash: text(value, 'plan_hash', '发布修订'),
    reason: text(value, 'reason', '发布修订'),
    publishedBy: text(value, 'published_by', '发布修订'),
    publishedAt: text(value, 'published_at', '发布修订'),
  }
}
function revisionDetail(source: unknown): RuleFlowRevisionDetail {
  const value = record(source, '修订详情')
  return {
    revision: total(value, 'revision', '修订详情'),
    definition: definition(value.definition),
    plan: plan(value.plan),
    reason: text(value, 'reason', '修订详情'),
    publishedBy: text(value, 'published_by', '修订详情'),
    publishedAt: text(value, 'published_at', '修订详情'),
  }
}
function compileReport(source: unknown): RuleFlowCompileReport {
  const value = record(source, '编译报告')
  return {
    id: text(value, 'id', '编译报告'),
    draftRevision: total(value, 'draft_revision', '编译报告'),
    planHash: text(value, 'plan_hash', '编译报告'),
    ok: flag(value, 'ok', '编译报告'),
    issues: issues(value.issues),
    createdBy: text(value, 'created_by', '编译报告'),
    createdAt: text(value, 'created_at', '编译报告'),
  }
}
function event(source: unknown): RuleFlowEvent {
  const value = record(source, '规则流事件')
  return {
    id: text(value, 'id', '规则流事件'),
    action: text(value, 'action', '规则流事件'),
    revisionBefore: totalOrNull(value, 'revision_before', '规则流事件'),
    revisionAfter: total(value, 'revision_after', '规则流事件'),
    actorId: text(value, 'actor_id', '规则流事件'),
    reason: text(value, 'reason', '规则流事件'),
    detailJson: text(value, 'detail_json', '规则流事件'),
    createdAt: text(value, 'created_at', '规则流事件'),
  }
}
function consumerReadback(source: unknown): RuleFlowConsumerReadback {
  const value = record(source, '消费者回读')
  return {
    consumerKind: text(value, 'consumer_kind', '消费者回读'),
    scopeKey: text(value, 'scope_key', '消费者回读'),
    consumerId: text(value, 'consumer_id', '消费者回读'),
    consumerRevision: total(value, 'consumer_revision', '消费者回读'),
    driftDetected: flag(value, 'drift_detected', '消费者回读'),
    effect: text(value, 'effect', '消费者回读'),
    detail: mapOf(value, 'detail', '消费者回读'),
  }
}
function flowDetail(textOfBody: string): RuleFlowDetail {
  const value = record(JSON.parse(textOfBody), '规则流详情')
  return { flow: flow(value.flow), definition: definition(value.definition), publishedDraftDrift: totalOrNull(value, 'published_draft_drift', '规则流详情') }
}
function previewResponse(textOfBody: string): RuleFlowPreview {
  const value = record(JSON.parse(textOfBody), '预览')
  return { plan: plan(value.plan), bindings: bindings(value.bindings) }
}
function publishResponse(textOfBody: string): RuleFlowPublish {
  const value = record(JSON.parse(textOfBody), '发布')
  return {
    published: flag(value, 'published', '发布'),
    flow: flow(value.flow),
    revision: totalOrNull(value, 'revision', '发布'),
    plan: plan(value.plan),
    bindings: bindings(value.bindings),
    issues: issues(value.issues),
  }
}
function catalogResponse(textOfBody: string): RuleFlowCatalog {
  const value = record(JSON.parse(textOfBody), '节点目录')
  const option = (item: unknown, label: string) => record(item, label)
  return {
    nodeTypes: list(value, 'node_types', '节点目录').map(item => {
      const row = record(item, '节点类型')
      return {
        type: text(row, 'type', '节点类型'),
        label: text(row, 'label', '节点类型'),
        category: text(row, 'category', '节点类型'),
        port: text(row, 'port', '节点类型'),
        fixed: flag(row, 'fixed', '节点类型'),
        minCount: total(row, 'min_count', '节点类型'),
        maxCount: total(row, 'max_count', '节点类型'),
        supported: flag(row, 'supported', '节点类型'),
        rationale: text(row, 'rationale', '节点类型'),
        description: text(row, 'description', '节点类型'),
      }
    }),
    channels: list(value, 'channels', '节点目录').map(item => {
      const row = record(item, '通道能力')
      return { channel: text(row, 'channel', '通道能力'), supported: flag(row, 'supported', '通道能力'), rationale: text(row, 'rationale', '通道能力') }
    }),
    systemTypes: list(value, 'system_types', '节点目录').map(item => {
      const row = option(item, '系统型号')
      return { id: text(row, 'id', '系统型号'), name: text(row, 'name', '系统型号') }
    }),
    places: list(value, 'places', '节点目录').map(item => {
      const row = option(item, '位置')
      return { id: text(row, 'id', '位置'), name: text(row, 'name', '位置'), path: text(row, 'path', '位置') }
    }),
    devices: list(value, 'devices', '节点目录').map(item => {
      const row = option(item, '设备')
      return { id: text(row, 'id', '设备'), name: textOrNull(row, 'name', '设备'), model: textOrNull(row, 'model', '设备'), placePath: textOrNull(row, 'place_path', '设备'), inCurrentScope: flag(row, 'in_current_scope', '设备') }
    }),
    contacts: list(value, 'contacts', '节点目录').map(item => {
      const row = option(item, '联系人')
      return { id: text(row, 'id', '联系人'), name: text(row, 'name', '联系人'), phoneMasked: text(row, 'phone_masked', '联系人'), enabled: flag(row, 'enabled', '联系人') }
    }),
    linkageRules: list(value, 'linkage_rules', '节点目录').map(item => {
      const row = option(item, '联动规则')
      return { id: text(row, 'id', '联动规则'), name: text(row, 'name', '联动规则'), kind: text(row, 'kind', '联动规则'), enabled: flag(row, 'enabled', '联动规则'), hasControllers: flag(row, 'has_controllers', '联动规则') }
    }),
    applications: list(value, 'applications', '节点目录').map(item => {
      const row = option(item, '数据输出应用')
      return { id: text(row, 'id', '数据输出应用'), name: text(row, 'name', '数据输出应用'), status: text(row, 'status', '数据输出应用'), enabledOutputs: total(row, 'enabled_outputs', '数据输出应用'), mqttEnabledOutputs: total(row, 'mqtt_enabled_outputs', '数据输出应用') }
    }),
    eventCodes: texts(value, 'event_codes', '节点目录'),
    linkageActions: texts(value, 'linkage_actions', '节点目录'),
    channelSupportKnown: flag(value, 'channel_support_known', '节点目录'),
  }
}
function pageResponse(textOfBody: string): RuleFlowPage {
  const value = record(JSON.parse(textOfBody), '规则流分页')
  return { items: list(value, 'items', '规则流分页').map(flow), total: total(value, 'total', '规则流分页'), page: total(value, 'page', '规则流分页'), size: total(value, 'size', '规则流分页') }
}
function revisionsResponse(textOfBody: string): RuleFlowRevision[] {
  const value: unknown = JSON.parse(textOfBody)
  if (!Array.isArray(value)) unconfirmed('发布修订列表')
  return value.map(revision)
}
function compiledResponse(textOfBody: string): RuleFlowCompiledView {
  const value = record(JSON.parse(textOfBody), '消费者回读视图')
  return { flowId: text(value, 'flow_id', '消费者回读视图'), publishedRevision: total(value, 'published_revision', '消费者回读视图'), enabled: flag(value, 'enabled', '消费者回读视图'), consumers: list(value, 'consumers', '消费者回读视图').map(consumerReadback) }
}
function reportsResponse(textOfBody: string): RuleFlowCompileReport[] {
  const value: unknown = JSON.parse(textOfBody)
  if (!Array.isArray(value)) unconfirmed('编译报告列表')
  return value.map(compileReport)
}
function eventsResponse(textOfBody: string): RuleFlowEvent[] {
  const value: unknown = JSON.parse(textOfBody)
  if (!Array.isArray(value)) unconfirmed('规则流事件列表')
  return value.map(event)
}

export interface RuleFlowPage { items: RuleFlow[]; total: number; page: number; size: number }
export interface RuleFlowCreateBody { name: string; description: string; definition: RuleFlowDefinition; reason: string }
export interface RuleFlowSaveBody { expected_revision: number; name: string; description: string; definition: RuleFlowDefinition; reason: string }
export interface RuleFlowArchiveBody { expected_revision: number; reason: string }
export interface RuleFlowPublishBody { expected_revision: number; preview_hash: string; reason: string }
export interface RuleFlowToggleBody { expected_revision: number; enabled: boolean; reason: string }

export interface RuleFlowApi {
  list: (page: number, size: number | undefined, keyword: string, status: string, signal?: AbortSignal) => Promise<RuleFlowPage>
  catalog: (signal?: AbortSignal) => Promise<RuleFlowCatalog>
  detail: (id: string, signal?: AbortSignal) => Promise<RuleFlowDetail>
  revisions: (id: string, signal?: AbortSignal) => Promise<RuleFlowRevision[]>
  revision: (id: string, revision: number, signal?: AbortSignal) => Promise<RuleFlowRevisionDetail>
  compiled: (id: string, signal?: AbortSignal) => Promise<RuleFlowCompiledView>
  reports: (id: string, signal?: AbortSignal) => Promise<RuleFlowCompileReport[]>
  events: (id: string, signal?: AbortSignal) => Promise<RuleFlowEvent[]>
  bindings: (id: string, signal?: AbortSignal) => Promise<RuleFlowBinding[]>
  create: (body: RuleFlowCreateBody, key: string, signal?: AbortSignal) => Promise<RuleFlowDetail>
  save: (id: string, body: RuleFlowSaveBody, key: string, signal?: AbortSignal) => Promise<RuleFlowDetail>
  archive: (id: string, body: RuleFlowArchiveBody, key: string, signal?: AbortSignal) => Promise<RuleFlowDetail>
  preview: (id: string, expectedRevision: number, signal?: AbortSignal) => Promise<RuleFlowPreview>
  publish: (id: string, body: RuleFlowPublishBody, key: string, signal?: AbortSignal) => Promise<RuleFlowPublish>
  toggle: (id: string, body: RuleFlowToggleBody, key: string, signal?: AbortSignal) => Promise<RuleFlowPublish>
}

/**
 * Every call is bound to the explicitly selected operation unit, never to the read-scope tree.
 * The selected unit is a write target; it never changes what the left scope tree shows.
 */
export function ruleFlowApiFor(tenantId: string): RuleFlowApi {
  const scoped = <T,>(path: string, options: RequestInit & { decode?: (text: string) => unknown } = {}) =>
    request<T>(path, { ...options, tenantScoped: true, tenantId })
  return {
    list: (page: number, size: number | undefined, keyword: string, status: string, signal?: AbortSignal) => {
      const params = new URLSearchParams({ page: String(page) })
      if (size !== undefined) params.set('size', String(size))
      if (keyword) params.set('keyword', keyword)
      if (status) params.set('status', status)
      return scoped<RuleFlowPage>(`/management/rule-flows?${params}`, { signal, decode: pageResponse })
    },
    catalog: (signal?: AbortSignal) => scoped<RuleFlowCatalog>('/management/rule-flows/catalog', { signal, decode: catalogResponse }),
    detail: (id: string, signal?: AbortSignal) => scoped<RuleFlowDetail>(`/management/rule-flows/${encodeURIComponent(id)}`, { signal, decode: flowDetail }),
    revisions: (id: string, signal?: AbortSignal) => scoped<RuleFlowRevision[]>(`/management/rule-flows/${encodeURIComponent(id)}/revisions`, { signal, decode: revisionsResponse }),
    revision: (id: string, revision: number, signal?: AbortSignal) => scoped<RuleFlowRevisionDetail>(`/management/rule-flows/${encodeURIComponent(id)}/revisions/${revision}`, { signal, decode: raw => revisionDetail(JSON.parse(raw)) }),
    compiled: (id: string, signal?: AbortSignal) => scoped<RuleFlowCompiledView>(`/management/rule-flows/${encodeURIComponent(id)}/compiled`, { signal, decode: compiledResponse }),
    reports: (id: string, signal?: AbortSignal) => scoped<RuleFlowCompileReport[]>(`/management/rule-flows/${encodeURIComponent(id)}/reports`, { signal, decode: reportsResponse }),
    events: (id: string, signal?: AbortSignal) => scoped<RuleFlowEvent[]>(`/management/rule-flows/${encodeURIComponent(id)}/events`, { signal, decode: eventsResponse }),
    bindings: (id: string, signal?: AbortSignal) => scoped<RuleFlowBinding[]>(`/management/rule-flows/${encodeURIComponent(id)}/bindings`, { signal, decode: raw => bindings(JSON.parse(raw)) }),
    create: (body: RuleFlowCreateBody, key: string, signal?: AbortSignal) =>
      scoped<RuleFlowDetail>('/management/rule-flows', { headers: { 'Idempotency-Key': key }, method: 'POST', body: JSON.stringify(body), signal, decode: flowDetail }),
    save: (id: string, body: RuleFlowSaveBody, key: string, signal?: AbortSignal) =>
      scoped<RuleFlowDetail>(`/management/rule-flows/${encodeURIComponent(id)}`, { headers: { 'Idempotency-Key': key }, method: 'PUT', body: JSON.stringify(body), signal, decode: flowDetail }),
    archive: (id: string, body: RuleFlowArchiveBody, key: string, signal?: AbortSignal) =>
      scoped<RuleFlowDetail>(`/management/rule-flows/${encodeURIComponent(id)}`, { headers: { 'Idempotency-Key': key }, method: 'DELETE', body: JSON.stringify(body), signal, decode: flowDetail }),
    preview: (id: string, expectedRevision: number, signal?: AbortSignal) =>
      scoped<RuleFlowPreview>(`/management/rule-flows/${encodeURIComponent(id)}/preview`, { method: 'POST', body: JSON.stringify({ expected_revision: expectedRevision }), signal, decode: previewResponse }),
    publish: (id: string, body: RuleFlowPublishBody, key: string, signal?: AbortSignal) =>
      scoped<RuleFlowPublish>(`/management/rule-flows/${encodeURIComponent(id)}/publish`, { headers: { 'Idempotency-Key': key }, method: 'POST', body: JSON.stringify(body), signal, decode: publishResponse }),
    toggle: (id: string, body: RuleFlowToggleBody, key: string, signal?: AbortSignal) =>
      scoped<RuleFlowPublish>(`/management/rule-flows/${encodeURIComponent(id)}/enabled`, { headers: { 'Idempotency-Key': key }, method: 'POST', body: JSON.stringify(body), signal, decode: publishResponse }),
  }
}
/** Fixed native stages always exist and always start wired in this order. */
export function starterDefinition(): RuleFlowDefinition {
  return {
    nodes: [
      { id: 'source', type: 'device.message', config: { scope: { mode: 'ALL', placeIds: [], deviceIds: [] }, systemTypes: [] }, x: 40, y: 60 },
      { id: 'persist', type: 'persist', config: {}, x: 300, y: 60 },
      { id: 'alarm', type: 'createalarm', config: {}, x: 560, y: 60 },
    ],
    edges: [{ from: 'source', to: 'persist' }, { from: 'persist', to: 'alarm' }],
  }
}
