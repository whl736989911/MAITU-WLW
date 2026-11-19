import type { DeviceStatusDisplay } from './types'

function invalid(): never { throw new Error('设备状态响应未确认') }
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  return value as Record<string, unknown>
}

export function readDeviceStatus(value: unknown): DeviceStatusDisplay | undefined {
  if (value === undefined) return undefined
  const { online_status, customer_status, business_statuses } = record(value)
  if (online_status !== 'ONLINE' && online_status !== 'OFFLINE' && online_status !== 'UNACTIVATED' && online_status !== 'UNKNOWN') return invalid()
  if (customer_status !== 'ONLINE' && customer_status !== 'OFFLINE' && customer_status !== 'UNACTIVATED' && customer_status !== 'UNKNOWN'
    && customer_status !== 'FIRE' && customer_status !== 'DISABLED' && customer_status !== 'SHIELDED' && customer_status !== 'FAULT') return invalid()
  if (!Array.isArray(business_statuses)) return invalid()
  return { online_status, customer_status, business_statuses: business_statuses.map(item => {
    const { code, label } = record(item)
    if ((code !== 'FIRE' && code !== 'DISABLED' && code !== 'SHIELDED' && code !== 'FAULT') || typeof label !== 'string' || !label.trim()) return invalid()
    return { code, label }
  }) }
}
