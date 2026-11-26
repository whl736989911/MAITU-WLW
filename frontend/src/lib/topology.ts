import { ApiError, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { Paginated } from '@/lib/types'

export interface TopologyGateway {
  id: string; tenant_id: string; address: string; name: string; protocol: 'LEGACY_TURMASS_MQTT' | 'HIKVISION' | 'MANUAL'
  device_id: string | null; device_name: string | null; state: 'ACTIVE' | 'RETIRED'; revision: number
  created_at: string; updated_at: string; visible_children: number
}
export interface TopologyLink {
  id: string; tenant_id: string; gateway_id: string; gateway_address: string; gateway_name: string
  child_kind: 'DEVICE' | 'MEASUREMENT'; device_id: string; device_name: string | null; device_eui: string | null
  place_id: string | null; place_name: string | null; channel_id: string | null; channel_name: string | null
  channel_type: string | null; channel_enabled: boolean; device_archived_at: string | null
  provenance: 'OBSERVED' | 'MANUAL'; observation_source: string | null; observation_event_id: string | null
  observed_at: string | null; verified: boolean; verified_by: string | null; verified_at: string | null
  verification_note: string | null; state: 'ACTIVE' | 'REVOKED'; revoked_by: string | null
  revoked_at: string | null; revoke_reason: string | null; revision: number; created_by: string
  created_at: string; updated_at: string
}
export interface GatewayPage extends Paginated<TopologyGateway> { scope_limited: boolean }
export interface LinkPage extends Paginated<TopologyLink> { scope_limited: boolean }
export interface GatewayTree { gateway: TopologyGateway; links: TopologyLink[]; scope_limited: boolean; truncated: boolean; note: string | null }

export interface MutationSpec { path: string; method: 'POST' | 'PUT'; body: Record<string, unknown>; display: string }

const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(200, 'TOPOLOGY_RESPONSE_UNCONFIRMED', '拓扑响应结构未确认')
  return value as Record<string, unknown>
}
const pageOf = (raw: string, key: 'items'): Record<string, unknown> => {
  const value = object(JSON.parse(raw))
  if (!Array.isArray(value[key]) || typeof value.total !== 'number' || typeof value.page !== 'number' || typeof value.size !== 'number') throw new ApiError(200, 'TOPOLOGY_RESPONSE_UNCONFIRMED', '拓扑分页响应结构未确认')
  return value
}

export const topologyApi = {
  gateways(tenantId: string, page: number, keyword: string, signal?: AbortSignal, size?: number) {
    const params = new URLSearchParams({ page: String(page) })
    if (size !== undefined) params.set('size', String(size))
    if (keyword) params.set('keyword', keyword)
    return request<GatewayPage>(`/${useSession.getState().audience}/topology/gateways?${params}`, {
      tenantScoped: true, tenantId, signal,
      decode: raw => pageOf(raw, 'items') as unknown as GatewayPage,
    })
  },
  links(tenantId: string, page: number, filters: { gateway_id?: string; keyword?: string; child_kind?: string; state?: string }, signal?: AbortSignal) {
    const params = new URLSearchParams({ page: String(page) })
    for (const [key, value] of Object.entries(filters)) if (value) params.set(key, value)
    return request<LinkPage>(`/${useSession.getState().audience}/topology/links?${params}`, {
      tenantScoped: true, tenantId, signal,
      decode: raw => pageOf(raw, 'items') as unknown as LinkPage,
    })
  },
  tree(tenantId: string, gatewayId: string, signal?: AbortSignal) {
    return request<GatewayTree>(`/${useSession.getState().audience}/topology/gateways/${encodeURIComponent(gatewayId)}/tree`, {
      tenantScoped: true, tenantId, signal, decode: raw => object(JSON.parse(raw)) as unknown as GatewayTree,
    })
  },
}

export const topologyPaths = {
  createGateway: (body: Record<string, unknown>): MutationSpec => ({ path: '/management/topology/gateways', method: 'POST', body, display: '登记网关' }),
  updateGateway: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/topology/gateways/${encodeURIComponent(id)}`, method: 'PUT', body, display: '修改网关' }),
  retireGateway: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/topology/gateways/${encodeURIComponent(id)}/retire`, method: 'POST', body, display: '停用网关' }),
  createLink: (body: Record<string, unknown>): MutationSpec => ({ path: '/management/topology/links', method: 'POST', body, display: '登记拓扑关系' }),
  verifyLink: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/topology/links/${encodeURIComponent(id)}/verify`, method: 'POST', body, display: '核验拓扑关系' }),
  revokeLink: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/topology/links/${encodeURIComponent(id)}/revoke`, method: 'POST', body, display: '撤销拓扑关系' }),
}

export const GATEWAY_PROTOCOLS = ['LEGACY_TURMASS_MQTT', 'HIKVISION', 'MANUAL'] as const
export const PROVENANCE_LABELS: Record<string, string> = { OBSERVED: '已观察（旧MQTT事件）', MANUAL: '人工声明' }
