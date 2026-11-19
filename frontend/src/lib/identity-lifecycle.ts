import { request } from './api'
export type IdentityDeletionResult = { id: string; outcome: 'DELETED'; reason: string }
export const identityLifecycleApi = {
  deleteUser: (platform: boolean, id: string, body: { expected_revision: number; reason: string }, key: string, tenantId?: string, signal?: AbortSignal) => request<IdentityDeletionResult>(`${platform ? '/management/platform-users' : '/management/users'}/${encodeURIComponent(id)}`, { method: 'DELETE', tenantScoped: !platform, tenantId, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
}
