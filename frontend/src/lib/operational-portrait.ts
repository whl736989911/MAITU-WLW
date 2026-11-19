import { request, scopeQuery } from './api'

export const portraitLabels = [
  { id: 'FREQUENT_FALSE_ALARM', name: '频繁误报' },
  { id: 'FREQUENT_FAULT', name: '频繁故障' },
  { id: 'FREQUENT_OFFLINE', name: '频繁离线' },
  { id: 'WEAK_SIGNAL', name: '频繁信号弱' },
  { id: 'LOW_BATTERY', name: '低电量' },
  { id: 'LONG_OFFLINE', name: '长期离线' },
] as const
export type PortraitLabel = typeof portraitLabels[number]['id']
export interface PortraitRule {
  id: string; tenant_id: string; version: number; window_days: number; false_alarm_count: number; fault_count: number; offline_count: number
  weak_signal_percent: string | number; weak_signal_metric: 'rssi' | 'rsrp' | null; weak_signal_threshold: string | number | null; low_battery_percent: string | number; long_offline_days: number
  selected_labels: PortraitLabel[]; enabled: boolean; reason: string; created_at: string
}
export interface PortraitResult { label: PortraitLabel; status: 'OK' | 'MISSING' | 'UNSUPPORTED' | 'NOT_SELECTED'; matched: boolean; sample_count: number; value: string | null; evidence: string; latest_at: string | null }
export interface PortraitSnapshot { id: string; tenant_id: string; device_id: string; rule_id: string; rule_version: number; window_start: string; window_end: string; evaluated_at: string; labels: PortraitResult[]; reason: string }
export interface PortraitDevice { id: string; tenant_id: string; name: string; address: string; place_id: string | null; last_message_at: string | null; product_version_id: string | null }
export interface PortraitPage { items: PortraitDevice[]; total: number; page: number; size: number; label_counts: Partial<Record<PortraitLabel, number>> }
export interface PortraitRuleInput {
  window_days: number; false_alarm_count: number; fault_count: number; offline_count: number; weak_signal_percent: string
  weak_signal_metric: 'rssi' | 'rsrp' | null; weak_signal_threshold: string | null; low_battery_percent: string
  long_offline_days: number; selected_labels: PortraitLabel[]; enabled: boolean; reason: string; expected_rule_version: number
}
  
export const operationalPortrait = {
  rules(signal?: AbortSignal, tenantIds?: string[], placeIds?: string[]) {
    return request<{ items: PortraitRule[] }>(scopeQuery('/management/portrait-rules', tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, signal })
  },
  configure(body: PortraitRuleInput, key: string, signal?: AbortSignal, tenantId?: string) {
    const decimals = ['weak_signal_percent', 'low_battery_percent', 'weak_signal_threshold'] as const
    const tokens = new Map<string, string>()
    for (const key of decimals) {
      const value = body[key]
      if (value === null) continue
      if (typeof value !== 'string' || !/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(value)) throw new Error('画像阈值必须是精确十进制数值')
      tokens.set(key, value)
    }
    const serialized = JSON.stringify({ ...body, weak_signal_percent: null, low_battery_percent: null, weak_signal_threshold: null })
    let payload = serialized
    for (const key of decimals) payload = payload.replace(`\"${key}\":null`, `\"${key}\":${tokens.get(key) ?? 'null'}`)
    return request<PortraitRule>('/management/portrait-rules', { method: 'POST', headers: { 'Idempotency-Key': key }, body: payload, tenantScoped: true, tenantId, signal })
  },
  devices(page: number, label: PortraitLabel | '', keyword: string, signal?: AbortSignal, tenantIds?: string[], placeIds?: string[], tenantId?: string) {
    const query = new URLSearchParams({ page: String(page), size: '50' })
    if (label) query.set('label', label)
    if (keyword.trim()) query.set('keyword', keyword.trim())
    return request<PortraitPage>(scopeQuery(`/management/device-portraits?${query}`, tenantIds, placeIds), { tenantScoped: true, tenantOptional: true, tenantId, signal })
  },
  evaluate(deviceId: string, body: { reason: string; expected_rule_version: number }, key: string, signal?: AbortSignal, tenantId?: string, tenantIds?: string[], placeIds?: string[]) {
    return request<PortraitSnapshot>(scopeQuery(`/management/devices/${encodeURIComponent(deviceId)}/portrait/evaluate`, tenantIds, placeIds), { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), tenantScoped: true, tenantId, signal })
  },
  history(deviceId: string, signal?: AbortSignal, tenantId?: string, tenantIds?: string[], placeIds?: string[]) {
    return request<PortraitSnapshot[]>(scopeQuery(`/management/devices/${encodeURIComponent(deviceId)}/portrait/history?limit=50`, tenantIds, placeIds), { tenantScoped: true, tenantId, signal })
  },
}
