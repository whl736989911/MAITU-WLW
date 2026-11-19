import { jsonArrayItems, jsonFieldText } from './json-document'
import { effectiveMetadata } from './parameter-json'
import { readDeviceStatus } from './device-status-json'
import type { BatteryPolicy, Device, Paginated, ProcessingAttempt, ProcessingDetail, ProcessingTask } from './types'
const deviceKeys = ['id', 'tenant_id', 'tenant_name', 'name', 'device_eui', 'model', 'address', 'connectivity', 'physical_state', 'last_message_at', 'battery_percent', 'is_simulated', 'product_id', 'product_version_id', 'product_name', 'version_number', 'revision', 'place_id', 'place_name', 'integration_state', 'system_type', 'device_category'] as const
const taskKeys = ['id', 'kind', 'tenant_id', 'raw_message_id', 'source_id', 'source_message_id', 'device_id', 'device_name', 'product_version_id', 'actor_id', 'expected_device_revision', 'recovery_alarm_id', 'reason', 'status', 'revision', 'attempt_count', 'max_attempts', 'available_at', 'lease_owner', 'lease_until', 'lease_token', 'created_at', 'finished_at', 'last_error_code'] as const
const attemptKeys = ['id', 'attempt_no', 'lease_token', 'worker_id', 'started_at', 'finished_at', 'outcome', 'error_code'] as const
function pick<T>(value: Record<string, unknown>, keys: readonly string[]): T { return Object.fromEntries(keys.filter(key => Object.hasOwn(value, key)).map(key => [key, value[key]])) as T }
export function taskResponse(text: string): ProcessingTask {
  const parsed = JSON.parse(text), task = pick<ProcessingTask>(parsed, taskKeys)
  if (!Object.hasOwn(parsed, 'result') || (parsed.result !== null && typeof parsed.result !== 'string')) throw new Error('历史任务结果格式未确认')
  task.result = parsed.result
  return task
}
export function tasksResponse(text: string): Paginated<ProcessingTask> {
  const parsed = JSON.parse(text)
  return { items: jsonArrayItems(jsonFieldText(text, 'items')!).map(taskResponse), total: parsed.total, page: parsed.page, size: parsed.size }
}
export function taskDetailResponse(text: string): ProcessingDetail {
  const parsed = JSON.parse(text)
  return { task: taskResponse(jsonFieldText(text, 'task')!), attempts: parsed.attempts.map((item: Record<string, unknown>) => pick<ProcessingAttempt>(item, attemptKeys)) }
}
export function devicesResponse(text: string, decodeBatteryPolicy: (raw: string, row: Record<string, unknown>) => BatteryPolicy): Paginated<Device> {
  const parsed = JSON.parse(text), items = jsonArrayItems(jsonFieldText(text, 'items')!)
  return { items: parsed.items.map((device: Record<string, unknown>, index: number) => {
    const row = { ...pick<Device>(device, deviceKeys), battery_percent_json: jsonFieldText(items[index], 'battery_percent'), battery_policy: decodeBatteryPolicy(items[index], device) }
    if (Object.hasOwn(device, 'status_display')) row.status_display = readDeviceStatus(device.status_display)
    if (Object.hasOwn(device, 'offline_policy')) row.offline_policy = device.offline_policy === null ? null : effectiveMetadata(device.offline_policy as Record<string, unknown>)
    return row
  }), total: parsed.total, page: parsed.page, size: parsed.size }
}
