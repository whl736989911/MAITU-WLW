import { request } from './api'
export const deviceArchiveApi = {
  archive: (id: string, body: { expected_revision: number; reason: string }, key: string, tenantId: string, signal?: AbortSignal) => request<unknown>(`/management/devices/${encodeURIComponent(id)}/archive`, { method: 'POST', tenantScoped: true, tenantId, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
}
