import { request } from './api'
import { useSession } from './store'

export interface DeviceChannel {
  id: string
  tenant_id: string
  device_id: string
  device_name?: string
  tenant_name?: string
  channel_key: string
  name: string
  channel_type: string
  enabled: boolean
  revision: number
  created_at: string
  updated_at: string
}
export interface DeviceChannelPage { items: DeviceChannel[]; total: number; page: number; size: number }
export interface DeviceChannelQuery { page?: number; size?: number; device_id?: string; keyword?: string; scope_tenant_ids?: string[]; scope_place_ids?: string[] }
export interface CreateDeviceChannel { device_id: string; channel_key: string; name: string; channel_type: string; enabled: boolean; reason: string }
export interface UpdateDeviceChannel { name: string; channel_type: string; enabled: boolean; expected_revision: number; reason: string }

function params(query: DeviceChannelQuery) {
  const state = useSession.getState(), result = new URLSearchParams({ page: String(query.page ?? 1), size: String(query.size ?? 20), scope_selected: 'true', scope_tenant_ids: (query.scope_tenant_ids ?? state.scopeTenantIds).join(','), scope_place_ids: (query.scope_place_ids ?? state.scopePlaceIds).join(',') })
  if (query.device_id) result.set('device_id', query.device_id)
  if (query.keyword) result.set('keyword', query.keyword)
  return result
}
export function listDeviceChannels(query: DeviceChannelQuery = {}, signal?: AbortSignal, tenantId?: string): Promise<DeviceChannelPage> {
  return request(`/management/device-channels?${params(query)}`, { tenantScoped: true, tenantOptional: true, tenantId, signal })
}
export function createDeviceChannel(body: CreateDeviceChannel, key: string, tenantId: string, signal?: AbortSignal): Promise<DeviceChannel> {
  return request('/management/device-channels', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal })
}
export function updateDeviceChannel(id: string, body: UpdateDeviceChannel, key: string, tenantId: string, signal?: AbortSignal): Promise<DeviceChannel> {
  return request(`/management/device-channels/${encodeURIComponent(id)}`, { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal })
}
export const deviceChannelsApi = { list: listDeviceChannels, create: createDeviceChannel, update: updateDeviceChannel }
