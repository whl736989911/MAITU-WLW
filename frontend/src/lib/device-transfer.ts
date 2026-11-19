import { request } from './api'
import { parseJsonDocument } from './json-document'

export type DeviceTransferResult = { source: unknown; target: unknown; transfer_id: string }
function decode(text: string): DeviceTransferResult {
  let value: unknown
  try { value = parseJsonDocument(text) } catch { throw new Error('设备移交服务响应格式无效') }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('设备移交服务响应格式无效')
  const row = value as Record<string, unknown>
  if (Object.keys(row).length !== 3 || !Object.hasOwn(row, 'source') || !Object.hasOwn(row, 'target') || typeof row.transfer_id !== 'string') throw new Error('设备移交服务响应格式无效')
  return row as DeviceTransferResult
}
export const deviceTransferApi = {
  transfer: (deviceId: string, body: { target_tenant_id: string; target_place_id: string; reason: string; expected_revision: number }, key: string, sourceTenant: string, signal?: AbortSignal) =>
    request<DeviceTransferResult>(`/management/devices/${encodeURIComponent(deviceId)}/transfer`, { method: 'POST', tenantScoped: true, tenantId: sourceTenant, headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode }),
}
