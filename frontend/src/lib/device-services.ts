import { ApiError, request } from './api'
import { useSession } from './store'

export type ServiceCategory = 'QUERY' | 'CONFIGURATION' | 'CONTROL'
export type RiskLevel = 'NORMAL' | 'ENGINEERING'
export type SupportState = 'VERIFIED' | 'UNVERIFIED'
export type CommandState = 'QUEUED' | 'IN_FLIGHT' | 'ACCEPTED' | 'REJECTED' | 'UNKNOWN' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type DriverMode = 'PRODUCT_MODEL' | 'VENDOR_PLATFORM' | 'NONE'

export interface ServiceField {
  key: string
  label: string
  type: 'string' | 'integer' | 'number' | 'boolean'
  required: boolean
  sensitive: boolean
  minimum: number | null
  maximum: number | null
  maxLength: number | null
  enum: string[]
}
export interface ServiceDefinition {
  id: string | null
  source: 'DRIVER' | 'PRODUCT_VERSION'
  product_version_id: string | null
  driver_key: string | null
  service_key: string
  display_name: string
  category: ServiceCategory
  risk_level: RiskLevel
  result_kind: 'PARAMETERS' | 'COMMAND' | 'STATUS'
  support_state: SupportState
  dispatch_ready: boolean
  dispatch_blocked_code: string | null
  dispatch_blocked_reason: string | null
  input_schema: { fields: ServiceField[] }
  applicability: unknown
  evidence: unknown
  status: string
  revision: number
  published_at: string | null
}
export interface SourceBinding {
  id: string
  driver_key: string
  external_id: string
  serial: string | null
  account_fingerprint: string | null
  generation: number
  state: 'ACTIVE' | 'RELEASED'
  bound_at: string
  bound_by: string | null
  released_at: string | null
  released_by: string | null
  release_reason: string | null
  pending_risk: unknown
  pending_raw_records: number
}
export interface ServiceCapabilities {
  device_id: string
  tenant_id: string
  device_revision: number
  mode: DriverMode
  driver_key: string | null
  driver_configured: boolean
  driver_configuration_code: string
  source_bound: boolean
  binding: SourceBinding | null
  parameter_snapshot_present: boolean
  parameter_observed_at: string | null
  services: ServiceDefinition[]
  notes: string[]
}
export interface ParameterSnapshot {
  id: string
  device_id: string
  driver_key: string
  source_generation: number | null
  schema: Record<string, unknown>
  values: unknown
  observed_at: string
  command_id: string | null
}
export interface ParameterRead { device_id: string; snapshot: ParameterSnapshot | null; note: string }
export interface CommandSummary {
  id: string
  tenant_id: string
  device_id: string
  driver_key: string
  driver_mode: DriverMode
  service_key: string
  risk_level: RiskLevel
  result_kind: 'PARAMETERS' | 'COMMAND' | 'STATUS'
  state: CommandState
  status_version: number
  accepted_reference: string | null
  vendor_code: string | null
  next_action: string | null
  accepted_at: string | null
  created_at: string
  finished_at: string | null
}
export interface CommandAttempt {
  id: string
  command_id: string
  attempt_no: number
  phase: 'SUBMIT' | 'QUERY'
  outcome: string
  vendor_code: string | null
  detail: unknown
  started_at: string
  finished_at: string | null
}
export interface CommandDetail {
  command: CommandSummary
  arguments: Record<string, unknown>
  snapshot: Record<string, unknown>
  result: unknown
  actor_id: string | null
  reason: string
  payload_sha256: string
  started_at: string | null
  updated_at: string
  attempts: CommandAttempt[]
}
export interface Driver {
  key: string
  mode: DriverMode
  display_name: string
  configured: boolean
  enabled: boolean
  configuration_code: string
  endpoint: string | null
  token_masked: string | null
  account_fingerprint: string | null
  revision: number
  updated_at: string | null
  configurable_origin_account: boolean
  services: ServiceDefinition[]
}

export interface LegacySourceConfig {
  driver_key: string
  display_name: string
  origin: string | null
  username_masked: string | null
  credentials_stored: boolean
  allow_insecure_http: boolean
  enabled: boolean
  configured: boolean
  account_fingerprint: string | null
  revision: number
  updated_at: string | null
}

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function bad(code: string, message: string): never { throw new ApiError(200, code, message) }
function text(value: unknown, name: string, nullable = false): string | null {
  if (value === null) { if (nullable) return null; bad('SERVICE_RESPONSE_UNCONFIRMED', `${name}未确认`) }
  if (typeof value !== 'string') bad('SERVICE_RESPONSE_UNCONFIRMED', `${name}未确认`)
  return value
}
function number(value: unknown, name: string, nullable = false): number | null {
  if (value === null) { if (nullable) return null; bad('SERVICE_RESPONSE_UNCONFIRMED', `${name}未确认`) }
  if (typeof value !== 'number' || !Number.isFinite(value)) bad('SERVICE_RESPONSE_UNCONFIRMED', `${name}未确认`)
  return value
}
function integer(value: unknown, name: string, nullable = false): number | null {
  const result = number(value, name, nullable)
  if (result !== null && !Number.isSafeInteger(result)) bad('SERVICE_RESPONSE_UNCONFIRMED', `${name}未确认`)
  return result
}
function boolean(value: unknown, name: string): boolean {
  if (typeof value !== 'boolean') bad('SERVICE_RESPONSE_UNCONFIRMED', `${name}未确认`)
  return value
}
const strings = (value: unknown, name: string): string[] => {
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) bad('SERVICE_RESPONSE_UNCONFIRMED', `${name}未确认`)
  return value as string[]
}
function field(raw: unknown): ServiceField {
  if (!object(raw)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务字段未确认')
  const type = raw.type
  if (type !== 'string' && type !== 'integer' && type !== 'number' && type !== 'boolean') return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务字段类型未确认')
  return {
    key: text(raw.key, '字段key') as string,
    label: text(raw.label, '字段名称') as string,
    type,
    required: boolean(raw.required, '字段必填'),
    sensitive: boolean(raw.sensitive, '字段敏感性'),
    minimum: number(raw.minimum === undefined ? null : raw.minimum, '最小值', true),
    maximum: number(raw.maximum === undefined ? null : raw.maximum, '最大值', true),
    maxLength: integer(raw.maxLength === undefined ? null : raw.maxLength, '最大长度', true),
    enum: raw.enum === undefined ? [] : strings(raw.enum, '枚举值'),
  }
}
function definition(raw: unknown): ServiceDefinition {
  if (!object(raw)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务定义未确认')
  const category = raw.category, risk = raw.risk_level, support = raw.support_state, result = raw.result_kind, source = raw.source
  if (!['QUERY', 'CONFIGURATION', 'CONTROL'].includes(String(category))) return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务类别未确认')
  if (risk !== 'NORMAL' && risk !== 'ENGINEERING') return bad('SERVICE_RESPONSE_UNCONFIRMED', '风险等级未确认')
  if (support !== 'VERIFIED' && support !== 'UNVERIFIED') return bad('SERVICE_RESPONSE_UNCONFIRMED', '能力证据状态未确认')
  if (!['PARAMETERS', 'COMMAND', 'STATUS'].includes(String(result))) return bad('SERVICE_RESPONSE_UNCONFIRMED', '结果类型未确认')
  if (source !== 'DRIVER' && source !== 'PRODUCT_VERSION') return bad('SERVICE_RESPONSE_UNCONFIRMED', '定义来源未确认')
  const schema = raw.input_schema
  const fields = object(schema) ? schema.fields : undefined
  if (!object(schema) || !Array.isArray(fields)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务输入schema未确认')
  return {
    id: text(raw.id, '定义ID', true),
    source,
    product_version_id: text(raw.product_version_id, '产品版本', true),
    driver_key: text(raw.driver_key, '驱动', true),
    service_key: text(raw.service_key, '服务key') as string,
    display_name: text(raw.display_name, '服务名称') as string,
    category: category as ServiceCategory,
    risk_level: risk,
    result_kind: result as ServiceDefinition['result_kind'],
    support_state: support,
    dispatch_ready: boolean(raw.dispatch_ready, '可下发状态'),
    dispatch_blocked_code: text(raw.dispatch_blocked_code, '阻断码', true),
    dispatch_blocked_reason: text(raw.dispatch_blocked_reason, '阻断原因', true),
    input_schema: { fields: fields.map(field) },
    applicability: raw.applicability,
    evidence: raw.evidence,
    status: text(raw.status, '定义状态') as string,
    revision: integer(raw.revision, '定义revision') as number,
    published_at: text(raw.published_at, '发布时间', true),
  }
}
function binding(raw: unknown): SourceBinding {
  if (!object(raw)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '来源关联未确认')
  const state = raw.state
  if (state !== 'ACTIVE' && state !== 'RELEASED') return bad('SERVICE_RESPONSE_UNCONFIRMED', '来源关联状态未确认')
  return {
    id: text(raw.id, '关联ID') as string,
    driver_key: text(raw.driver_key, '驱动') as string,
    external_id: text(raw.external_id, '来源标识') as string,
    serial: text(raw.serial, '序列号', true),
    account_fingerprint: text(raw.account_fingerprint, '账号指纹', true),
    generation: integer(raw.generation, '关联代次') as number,
    state,
    bound_at: text(raw.bound_at, '绑定时间') as string,
    bound_by: text(raw.bound_by, '绑定人', true),
    released_at: text(raw.released_at, '解绑时间', true),
    released_by: text(raw.released_by, '解绑人', true),
    release_reason: text(raw.release_reason, '解绑原因', true),
    pending_risk: raw.pending_risk,
    pending_raw_records: integer(raw.pending_raw_records, '待处理记录') as number,
  }
}
function capabilitiesResponse(raw: string): ServiceCapabilities {
  const parsed: unknown = JSON.parse(raw)
  if (!object(parsed)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务能力响应未确认')
  const mode = parsed.mode
  if (mode !== 'PRODUCT_MODEL' && mode !== 'VENDOR_PLATFORM' && mode !== 'NONE') return bad('SERVICE_RESPONSE_UNCONFIRMED', '驱动模式未确认')
  const serviceRows = parsed.services, noteRows = parsed.notes
  if (!Array.isArray(serviceRows) || !Array.isArray(noteRows)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务能力列表未确认')
  return {
    device_id: text(parsed.device_id, '设备') as string,
    tenant_id: text(parsed.tenant_id, '单位') as string,
    device_revision: integer(parsed.device_revision, '设备revision') as number,
    mode,
    driver_key: text(parsed.driver_key, '驱动', true),
    driver_configured: boolean(parsed.driver_configured, '驱动可用状态'),
    driver_configuration_code: text(parsed.driver_configuration_code, '驱动配置状态') as string,
    source_bound: boolean(parsed.source_bound, '来源关联状态'),
    binding: parsed.binding === null ? null : binding(parsed.binding),
    parameter_snapshot_present: boolean(parsed.parameter_snapshot_present, '参数快照状态'),
    parameter_observed_at: text(parsed.parameter_observed_at, '参数快照时间', true),
    services: serviceRows.map(definition),
    notes: strings(noteRows, '说明'),
  }
}
function snapshot(raw: unknown): ParameterSnapshot | null {
  if (raw === null) return null
  if (!object(raw)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '参数快照未确认')
  if (!object(raw.schema)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '参数schema未确认')
  return {
    id: text(raw.id, '快照ID') as string,
    device_id: text(raw.device_id, '设备') as string,
    driver_key: text(raw.driver_key, '驱动') as string,
    source_generation: integer(raw.source_generation, '来源代次', true),
    schema: raw.schema,
    values: raw.values,
    observed_at: text(raw.observed_at, '观察时间') as string,
    command_id: text(raw.command_id, '命令ID', true),
  }
}
function parameterResponse(raw: string): ParameterRead {
  const parsed: unknown = JSON.parse(raw)
  if (!object(parsed)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '参数读取响应未确认')
  return { device_id: text(parsed.device_id, '设备') as string, snapshot: snapshot(parsed.snapshot), note: text(parsed.note, '说明') as string }
}
const commandStates = ['QUEUED', 'IN_FLIGHT', 'ACCEPTED', 'REJECTED', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED']
function summary(raw: unknown): CommandSummary {
  if (!object(raw) || !commandStates.includes(String(raw.state))) return bad('SERVICE_RESPONSE_UNCONFIRMED', '命令状态未确认')
  if (raw.risk_level !== 'NORMAL' && raw.risk_level !== 'ENGINEERING') return bad('SERVICE_RESPONSE_UNCONFIRMED', '命令风险未确认')
  return {
    id: text(raw.id, '命令ID') as string,
    tenant_id: text(raw.tenant_id, '单位') as string,
    device_id: text(raw.device_id, '设备') as string,
    driver_key: text(raw.driver_key, '驱动') as string,
    driver_mode: raw.driver_mode as DriverMode,
    service_key: text(raw.service_key, '服务') as string,
    risk_level: raw.risk_level,
    result_kind: raw.result_kind as CommandSummary['result_kind'],
    state: raw.state as CommandState,
    status_version: integer(raw.status_version, '命令版本') as number,
    accepted_reference: text(raw.accepted_reference, '厂商引用', true),
    vendor_code: text(raw.vendor_code, '厂商码', true),
    next_action: text(raw.next_action, '后续动作', true),
    accepted_at: text(raw.accepted_at, '接受时间', true),
    created_at: text(raw.created_at, '创建时间') as string,
    finished_at: text(raw.finished_at, '结束时间', true),
  }
}
function attempt(raw: unknown): CommandAttempt {
  if (!object(raw)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '命令尝试未确认')
  return {
    id: text(raw.id, '尝试ID') as string,
    command_id: text(raw.command_id, '命令') as string,
    attempt_no: integer(raw.attempt_no, '尝试序号') as number,
    phase: raw.phase === 'QUERY' ? 'QUERY' : 'SUBMIT',
    outcome: text(raw.outcome, '尝试结果') as string,
    vendor_code: text(raw.vendor_code, '厂商码', true),
    detail: raw.detail,
    started_at: text(raw.started_at, '开始时间') as string,
    finished_at: text(raw.finished_at, '结束时间', true),
  }
}
function commandDetailResponse(raw: string): CommandDetail {
  const parsed: unknown = JSON.parse(raw)
  const attempts = object(parsed) ? parsed.attempts : undefined
  if (!object(parsed) || !Array.isArray(attempts) || !object(parsed.arguments) || !object(parsed.snapshot))
    return bad('SERVICE_RESPONSE_UNCONFIRMED', '命令详情未确认')
  return {
    command: summary(parsed.command),
    arguments: parsed.arguments,
    snapshot: parsed.snapshot,
    result: parsed.result,
    actor_id: text(parsed.actor_id, '操作者', true),
    reason: text(parsed.reason, '原因') as string,
    payload_sha256: text(parsed.payload_sha256, '载荷指纹') as string,
    started_at: text(parsed.started_at, '开始时间', true),
    updated_at: text(parsed.updated_at, '更新时间') as string,
    attempts: attempts.map(attempt),
  }
}
function commandsPageResponse(raw: string): { items: CommandSummary[]; total: number; page: number; size: number } {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  if (!object(parsed) || !Array.isArray(items)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '命令列表未确认')
  return { items: items.map(summary), total: integer(parsed.total, '总数') as number, page: integer(parsed.page, '页码') as number, size: integer(parsed.size, '页大小') as number }
}
function bindingsResponse(raw: string): SourceBinding[] {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  if (!object(parsed) || !Array.isArray(items)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '来源关联列表未确认')
  return items.map(binding)
}
function driversResponse(raw: string): Driver[] {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  if (!object(parsed) || !Array.isArray(items)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '驱动列表未确认')
  return items.map(item => {
    const serviceRows = object(item) ? item.services : undefined
    if (!object(item) || !Array.isArray(serviceRows)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '驱动配置未确认')
    return {
      key: text(item.key, '驱动') as string,
      mode: item.mode as DriverMode,
      display_name: text(item.display_name, '驱动名称') as string,
      configured: boolean(item.configured, '配置状态'),
      enabled: boolean(item.enabled, '启用状态'),
      configuration_code: text(item.configuration_code, '配置码') as string,
      endpoint: text(item.endpoint, 'endpoint', true),
      token_masked: text(item.token_masked, '令牌', true),
      account_fingerprint: text(item.account_fingerprint, '账号指纹', true),
      revision: integer(item.revision, '配置版本') as number,
      updated_at: text(item.updated_at, '更新时间', true),
      configurable_origin_account: boolean(item.configurable_origin_account, '来源账号配置能力'),
      services: serviceRows.map(definition),
    }
  })
}
function legacySourcesResponse(raw: string): LegacySourceConfig[] {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  if (!object(parsed) || !Array.isArray(items)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '旧平台来源配置列表未确认')
  return items.map(rawItem => {
    if (!object(rawItem)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '旧平台来源配置未确认')
    return {
      driver_key: text(rawItem.driver_key, '驱动') as string,
      display_name: text(rawItem.display_name, '驱动名称') as string,
      origin: text(rawItem.origin, '来源地址', true),
      username_masked: text(rawItem.username_masked, '账号', true),
      credentials_stored: boolean(rawItem.credentials_stored, '凭据保存状态'),
      allow_insecure_http: boolean(rawItem.allow_insecure_http, 'HTTP风险确认'),
      enabled: boolean(rawItem.enabled, '启用状态'),
      configured: boolean(rawItem.configured, '配置状态'),
      account_fingerprint: text(rawItem.account_fingerprint, '账号指纹', true),
      revision: integer(rawItem.revision, '配置版本') as number,
      updated_at: text(rawItem.updated_at, '更新时间', true),
    }
  })
}
function definitionsResponse(raw: string): ServiceDefinition[] {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  if (!object(parsed) || !Array.isArray(items)) return bad('SERVICE_RESPONSE_UNCONFIRMED', '服务定义列表未确认')
  return items.map(definition)
}

const audiencePrefix = () => `/${useSession.getState().audience}`
const get = <T,>(path: string, tenantId: string | undefined, signal: AbortSignal | undefined, decode: (raw: string) => T, tenantOptional = false) =>
  request<T>(path, { tenantScoped: true, tenantOptional, tenantId, signal, decode })

export const deviceServicesApi = {
  capabilities: (deviceId: string, tenantId: string | undefined, signal?: AbortSignal) =>
    get<ServiceCapabilities>(`${audiencePrefix()}/devices/${encodeURIComponent(deviceId)}/service-capabilities`, tenantId, signal, capabilitiesResponse),
  parameters: (deviceId: string, tenantId: string | undefined, signal?: AbortSignal) =>
    get<ParameterRead>(`${audiencePrefix()}/devices/${encodeURIComponent(deviceId)}/service-parameters`, tenantId, signal, parameterResponse),
  sourceBindings: (deviceId: string, tenantId: string | undefined, signal?: AbortSignal) =>
    get<SourceBinding[]>(`${audiencePrefix()}/devices/${encodeURIComponent(deviceId)}/source-bindings`, tenantId, signal, bindingsResponse),
  commands: (deviceId: string, page: number, tenantId: string | undefined, signal?: AbortSignal, serviceKey?: string) => {
    const query = new URLSearchParams({ page: String(page) })
    if (serviceKey) query.set('serviceKey', serviceKey)
    return get<{ items: CommandSummary[]; total: number; page: number; size: number }>(`${audiencePrefix()}/devices/${encodeURIComponent(deviceId)}/service-commands?${query}`, tenantId, signal, commandsPageResponse)
  },
  command: (id: string, tenantId: string | undefined, signal?: AbortSignal) =>
    get<CommandDetail>(`${audiencePrefix()}/device-service-commands/${encodeURIComponent(id)}`, tenantId, signal, commandDetailResponse),
  createCommand: (deviceId: string, body: { service_key: string; arguments: Record<string, unknown>; expected_device_revision: number; capability_revision?: number; source_binding_id?: string; reason: string; risk_confirmed: boolean }, key: string, tenantId: string | undefined, signal?: AbortSignal) =>
    request<CommandDetail>(`${audiencePrefix()}/devices/${encodeURIComponent(deviceId)}/service-commands`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: commandDetailResponse }),
  cancel: (id: string, body: { reason: string }, key: string, tenantId: string | undefined, signal?: AbortSignal) =>
    request<CommandDetail>(`${audiencePrefix()}/device-service-commands/${encodeURIComponent(id)}/cancel`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: commandDetailResponse }),
  reconcile: (id: string, body: { reason: string }, key: string, tenantId: string | undefined, signal?: AbortSignal) =>
    request<CommandDetail>(`${audiencePrefix()}/device-service-commands/${encodeURIComponent(id)}/reconcile`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: commandDetailResponse }),
  unbind: (deviceId: string, body: { expected_device_revision: number; binding_id?: string; reason: string }, key: string, tenantId: string | undefined, signal?: AbortSignal) =>
    request<SourceBinding>(`/management/devices/${encodeURIComponent(deviceId)}/unbind`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => binding(JSON.parse(text)) }),
  cloudDelete: (deviceId: string, body: { expected_device_revision: number; external_device_id?: string; external_serial?: string; reason: string; risk_confirmed: boolean }, key: string, tenantId: string | undefined, signal?: AbortSignal) =>
    request<CommandDetail>(`/management/devices/${encodeURIComponent(deviceId)}/cloud-delete`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: commandDetailResponse }),
  drivers: (signal?: AbortSignal) => request<Driver[]>('/management/integration-drivers', { signal, decode: driversResponse }),
  legacySources: (signal?: AbortSignal) => request<LegacySourceConfig[]>('/management/legacy-sources', { signal, decode: legacySourcesResponse }),
  saveLegacySource: (key: string, body: { origin: string; username: string; password: string; allow_insecure_http: boolean; enabled: boolean; expected_revision: number }, requestKey: string, signal?: AbortSignal) =>
    request<LegacySourceConfig>(`/management/legacy-sources/${encodeURIComponent(key)}`, { method: 'PUT', headers: { 'Idempotency-Key': requestKey }, body: JSON.stringify(body), signal, decode: raw => legacySourcesResponse(JSON.stringify({ items: [JSON.parse(raw)] }))[0]! }),
  saveDriverConfig: (key: string, body: { endpoint: string; token: string; enabled: boolean; expected_revision: number }, requestKey: string, signal?: AbortSignal) =>
    request<{ driver_key: string; endpoint: string; token_masked: string | null; enabled: boolean; configured: boolean; account_fingerprint: string | null; revision: number; updated_at: string | null }>(`/management/integration-drivers/${encodeURIComponent(key)}/configuration`, { method: 'PUT', headers: { 'Idempotency-Key': requestKey }, body: JSON.stringify(body), signal }),
  addCapabilityEvidence: (key: string, body: { model: string; service_key: string; evidence: string }, requestKey: string, signal?: AbortSignal) =>
    request<{ driver_key: string; model: string; service_key: string; evidence: string; verified_by: string; verified_at: string }>(`/management/integration-drivers/${encodeURIComponent(key)}/capability-evidence`, { method: 'POST', headers: { 'Idempotency-Key': requestKey }, body: JSON.stringify(body), signal }),
  definitions: (versionId: string, signal?: AbortSignal) =>
    request<ServiceDefinition[]>(`/management/product-versions/${encodeURIComponent(versionId)}/service-definitions`, { signal, decode: definitionsResponse }),
  createDefinition: (versionId: string, body: { operation_key: string; display_name: string; category: ServiceCategory; risk_level: RiskLevel; result_kind: string; input_schema: unknown; applicability: unknown; evidence: unknown; support_state: SupportState; expected_version_revision: number }, key: string, signal?: AbortSignal) =>
    request<ServiceDefinition>(`/management/product-versions/${encodeURIComponent(versionId)}/service-definitions`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => definition(JSON.parse(text)) }),
  updateDefinition: (id: string, body: { display_name: string; category: ServiceCategory; risk_level: RiskLevel; result_kind: string; input_schema: unknown; applicability: unknown; evidence: unknown; support_state: SupportState; expected_revision: number }, key: string, signal?: AbortSignal) =>
    request<ServiceDefinition>(`/management/product-service-definitions/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: text => definition(JSON.parse(text)) }),
  deleteDefinition: (id: string, body: { expected_revision: number }, key: string, signal?: AbortSignal) =>
    request<{ id: string }>(`/management/product-service-definitions/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
}

export function emptyArguments(fields: ServiceField[]): Record<string, boolean> {
  const result: Record<string, boolean> = {}
  for (const field of fields) if (field.type === 'boolean') result[field.key] = false
  return result
}

/** Converts raw form text into the declared JSON type without inventing defaults. */
export function fieldValue(field: ServiceField, raw: string, checked: boolean): unknown {
  if (field.type === 'boolean') return checked
  if (raw === '') return undefined
  if (field.type === 'integer') return /^-?\d+$/.test(raw) ? Number(raw) : raw
  if (field.type === 'number') return /^-?\d+(\.\d+)?$/.test(raw) ? Number(raw) : raw
  return raw
}
