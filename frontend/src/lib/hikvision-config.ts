import { request } from './api'

export interface HikvisionConfiguration {
  configured: boolean
  enabled: boolean
  endpoint: string | null
  app_key_masked: string | null
  secret_configured: boolean
  poll_interval_seconds: number
  model_scope: string
  revision: number
  configuration_source: 'DATABASE' | 'ENVIRONMENT'
  secret_storage_ready: boolean
}
export const hikvisionConfigApi = {
  get: (signal?: AbortSignal) => request<HikvisionConfiguration>('/management/hikvision/configuration', { signal }),
  save: (body: { expected_revision: number; endpoint: string; app_key: string; app_secret?: string; enabled: boolean; poll_interval_seconds: number; model_scope: string }, idempotencyKey: string, signal?: AbortSignal) => request<HikvisionConfiguration>('/management/hikvision/configuration', { method: 'PUT', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body), signal }),
  validateReadOnly: (signal?: AbortSignal) => request<{ result: 'READ_ONLY_OK' }>('/management/hikvision/configuration/validate', { method: 'POST', signal }),
}
