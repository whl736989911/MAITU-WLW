import { ApiError, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { Paginated } from '@/lib/types'

export type ControllerAction = 'start' | 'openGate' | 'closeGate' | 'output' | 'releaser' | 'reset' | 'io-start' | 'io-stop'

export interface ControllerResource {
  id: string; tenant_id: string; name: string; device_id: string; device_name: string | null; device_eui: string | null
  action: ControllerAction; service_key: string; driver_key: string; driver_mode: 'PRODUCT_MODEL' | 'VENDOR_PLATFORM'
  service_definition_id: string | null; capability_revision: number
  support_state: 'VERIFIED' | 'UNVERIFIED'; risk_level: 'NORMAL' | 'ENGINEERING'; result_kind: 'PARAMETERS' | 'COMMAND' | 'STATUS'
  arguments_json: string
  enabled: boolean; state: 'ACTIVE' | 'RETIRED'; revision: number; created_at: string; updated_at: string
}
export interface ServiceField { key: string; label: string; type: string; required: boolean; sensitive: boolean; min?: string | null; max?: string | null; enum?: string[] }
export interface ServiceDefinitionView {
  id: string | null; source: 'DRIVER' | 'PRODUCT_VERSION'; product_version_id: string | null; service_key: string
  display_name: string; category: 'QUERY' | 'CONFIGURATION' | 'CONTROL'; risk_level: 'NORMAL' | 'ENGINEERING'
  result_kind: 'PARAMETERS' | 'COMMAND' | 'STATUS'; support_state: 'VERIFIED' | 'UNVERIFIED'; dispatch_ready: boolean
  dispatch_blocked_code: string | null; dispatch_blocked_reason: string | null; input_schema: { fields?: ServiceField[] }
  status: 'DRAFT' | 'PUBLISHED'; revision: number
}
export interface DeviceCapabilities {
  device_id: string; tenant_id: string; device_revision: number; mode: string; driver_key: string | null
  driver_configured: boolean; driver_configuration_code: string; source_bound: boolean; services: ServiceDefinitionView[]
  notes: string[]
}
export interface CommandSummary {
  id: string; service_key: string; state: string; risk_level: string; result_kind: string; created_at: string
  accepted_at: string | null; vendor_code: string | null
}
export interface ControllerDetail {
  resource: ControllerResource; capabilities: DeviceCapabilities; commands: Paginated<CommandSummary>; note: string
}
export interface MutationSpec { path: string; method: 'POST' | 'PUT'; body: Record<string, unknown>; display: string }

const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(200, 'CONTROLLER_RESPONSE_UNCONFIRMED', '控制器响应结构未确认')
  return value as Record<string, unknown>
}

export const controllerApi = {
  list(tenantId: string, page: number, options: { keyword?: string; include_retired?: boolean; size?: number } = {}, signal?: AbortSignal) {
    const params = new URLSearchParams({ page: String(page) })
    if (options.size) params.set('size', String(options.size))
    if (options.keyword) params.set('keyword', options.keyword)
    if (options.include_retired) params.set('include_retired', 'true')
    return request<Paginated<ControllerResource>>(`/${useSession.getState().audience}/controller-resources?${params}`, {
      tenantScoped: true, tenantId, signal,
      decode: raw => {
        const value = object(JSON.parse(raw))
        if (!Array.isArray(value.items)) throw new ApiError(200, 'CONTROLLER_RESPONSE_UNCONFIRMED', '控制器分页响应结构未确认')
        return value as unknown as Paginated<ControllerResource>
      },
    })
  },
  capabilities(tenantId: string, deviceId: string, signal?: AbortSignal) {
    return request<DeviceCapabilities>(`/${useSession.getState().audience}/devices/${encodeURIComponent(deviceId)}/service-capabilities`, {
      tenantScoped: true, tenantId, signal, decode: raw => object(JSON.parse(raw)) as unknown as DeviceCapabilities,
    })
  },
  detail(tenantId: string, id: string, signal?: AbortSignal) {
    return request<ControllerDetail>(`/${useSession.getState().audience}/controller-resources/${encodeURIComponent(id)}`, {
      tenantScoped: true, tenantId, signal, decode: raw => object(JSON.parse(raw)) as unknown as ControllerDetail,
    })
  },
}

export const controllerPaths = {
  create: (body: Record<string, unknown>): MutationSpec => ({ path: '/management/controller-resources', method: 'POST', body, display: '登记控制器关联' }),
  update: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/${useSession.getState().audience}/controller-resources/${encodeURIComponent(id)}`, method: 'PUT', body, display: '修改控制器关联' }),
  enabled: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/controller-resources/${encodeURIComponent(id)}/enabled`, method: 'POST', body, display: '启用/停用控制器关联' }),
  retire: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/controller-resources/${encodeURIComponent(id)}/retire`, method: 'POST', body, display: '停用控制器关联' }),
}

export const CONTROLLER_ACTIONS: ControllerAction[] = ['start', 'openGate', 'closeGate', 'output', 'releaser', 'reset', 'io-start', 'io-stop']
export const ACTION_LABELS: Record<string, string> = {
  start: '启动', openGate: '合闸', closeGate: '分闸', output: 'I/O输出', releaser: '断电', reset: '复位', 'io-start': 'I/O输出-启动', 'io-stop': 'I/O输出-停止',
}
