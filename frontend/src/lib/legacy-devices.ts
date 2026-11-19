import { request } from './api'

export type LegacyDevice = {
  id: string
  name: string
  device_eui: string | null
  model: string | null
  tenant_id: string
  place_name: string | null
  product_version_id: string | null
  legacy_product_id: string | null
  physical_state: string | null
  last_message_at: string | null
}

export type LegacyDevicePage = { items: LegacyDevice[]; total: number; page: number; size: number }

export const legacyDevicesApi = {
  list: (page = 1, size = 20, keyword = '', signal?: AbortSignal, tenantId?: string) => {
    const query = new URLSearchParams({ page: String(page), size: String(size), keyword })
    return request<LegacyDevicePage>(`/management/legacy-ingest/devices?${query}`, { tenantScoped: true, tenantId, signal })
  },
}
