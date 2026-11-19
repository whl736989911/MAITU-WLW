import { request } from './api'

export interface HikvisionCallbackStatus {
  enabled: boolean
  public_origin: string | null
  credential_configured: boolean
  secret_storage_ready: boolean
  revision: number
  receiver_path: string
  worker_enabled: boolean
  pending_count: number
  processed_count: number
  attention_count: number
  last_received_at: string | null
  enabled_message_types: string[]
  supported_message_types: string[]
}
export interface HikvisionCallbackReceipt {
  id: string
  received_at: string
  last_received_at: string
  duplicate_count: number
  state: 'PENDING' | 'PROCESSED' | 'PARTIAL' | 'REJECTED'
  message_types: string[]
  submitted_records: number
  unsupported_messages: number
  error_code: string | null
  revision: number
  reparse_count: number
  last_reparse_at: string | null
}
export interface HikvisionCallbackSubscription {
  message_type: string
  destination_origin: string | null
  matches_receiver: boolean
  updated_at: string | null
}
export const hikvisionCallbackApi = {
  get: (signal?: AbortSignal) => request<HikvisionCallbackStatus>('/management/hikvision/callback', { signal }),
  receipts: (page: number, size: number, signal?: AbortSignal) => request<{ page: number; size: number; total: number; items: HikvisionCallbackReceipt[] }>(`/management/hikvision/callback/receipts?page=${page}&size=${size}`, { signal }),
  save: (body: { expected_revision: number; enabled: boolean; public_origin: string; enabled_message_types: string[]; credential?: string }, key: string, signal?: AbortSignal) => request<HikvisionCallbackStatus>('/management/hikvision/callback', { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
  reparse: (id: string, body: { expected_revision: number }, key: string, signal?: AbortSignal) => request<HikvisionCallbackReceipt>(`/management/hikvision/callback/receipts/${encodeURIComponent(id)}/reparse`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
  subscriptions: (signal?: AbortSignal) => request<{ checked_at: string; items: HikvisionCallbackSubscription[] }>('/management/hikvision/callback/subscriptions', { method: 'POST', signal }),
}
