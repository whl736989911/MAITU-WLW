import { request } from './api'
import type { Device } from './types'

export interface DeviceBatchTarget { device_id: string; expected_revision: number }
export interface DeviceBatchResult { items: Device[] }

function verify(targets: DeviceBatchTarget[], tenantId?: string): void {
  if (!tenantId || targets.length < 1 || targets.length > 100 || targets.some(target => !target.device_id || !Number.isSafeInteger(target.expected_revision) || target.expected_revision < 0) || new Set(targets.map(target => target.device_id)).size !== targets.length) {
    throw new Error('批量设备目标未确认；请选择同一单位内且revision有效的1–100台设备。')
  }
}

export const deviceBatchApi = {
  move: (targets: DeviceBatchTarget[], placeId: string, key: string, tenantId?: string, signal?: AbortSignal) => {
    verify(targets, tenantId)
    if (!placeId || !key) throw new Error('批量移动目标位置或幂等键未确认。')
    return request<DeviceBatchResult>('/management/devices/batch-move', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ devices: targets, place_id: placeId }), signal })
  },
  migrate: (targets: DeviceBatchTarget[], versionId: string, reason: string, key: string, tenantId?: string, signal?: AbortSignal) => {
    verify(targets, tenantId)
    if (!versionId || !reason.trim() || !key) throw new Error('批量版本迁移请求未确认。')
    return request<DeviceBatchResult>('/management/devices/batch-migrate-version', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ devices: targets, product_version_id: versionId, reason: reason.trim() }), signal })
  },
}
