import { request } from './api'

export interface HikvisionStatus {
  configured: boolean
  enabled: boolean
  last_sync_at: string | null
  last_success_at: string | null
  last_error: string | null
  devices_total: number
  unassigned_total: number
  linked_total: number
  unassigned_events: number
  quarantined_events: number
  mapping_errors: number
  poll_interval_seconds: number
  streams: { kind: string; last_success_at: string | null; last_error: string | null; total: number }[]
}
export interface HikvisionDevice {
  external_id: string
  serial: string | null
  name: string
  model: string | null
  source_device_type: string | null
  source_status: string | null
  source_updated_at: string | null
  last_observed_at: string | null
  online: boolean | null
  sensor_count: number
  local_device_id: string | null
  local_tenant_id: string | null
  local_place_id: string | null
  assignment_state: 'UNASSIGNED' | 'LINKED'
  revision: number
}
export interface HikvisionPage { items: HikvisionDevice[]; total: number; page: number; size: number }
export const hikvisionApi = {
  status: (signal?: AbortSignal) => request<HikvisionStatus>('/management/hikvision/status', { signal }),
  devices: (page: number, size: number, signal?: AbortSignal) => request<HikvisionPage>(`/management/hikvision/devices?page=${page}&size=${size}`, { signal }),
  sync: (signal?: AbortSignal) => request<HikvisionStatus>('/management/hikvision/sync', { method: 'POST', signal }),
  assign: (externalId: string, body: { expected_revision: number; tenant_id: string; place_id: string | null; existing_device_id: string | null }) => request<HikvisionDevice>(`/management/hikvision/devices/${encodeURIComponent(externalId)}/assignment`, { method: 'POST', body: JSON.stringify(body) }),
}
