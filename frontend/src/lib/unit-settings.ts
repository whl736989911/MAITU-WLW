import { ApiError, request } from './api'
import { useSession } from './store'
import { jsonArrayItems, jsonFieldText, parseJsonDocument } from './json-document'
import { integerToken, policyResponse } from './parameter-json'
import type { ParameterDefinition, ParameterPolicy } from './types'

export interface UnitParameterSettings { definition: ParameterDefinition; policy: ParameterPolicy; allowed_maximum: number }
export interface UnitParameterChange { id: string; key: string; action: 'SET' | 'CLEAR'; previous_value: number | null; value: number | null; revision_before: number; revision_after: number; created_at: string }
const parameterKey = 'offline_threshold_minutes'
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('单位设置响应结构未确认')
  return value as Record<string, unknown>
}
function integerFields(raw: string, value: unknown): void {
  if (typeof value === 'number') { integerToken(raw); return }
  if (Array.isArray(value)) { const items = jsonArrayItems(raw); value.forEach((item, i) => integerFields(items[i], item)); return }
  if (value && typeof value === 'object') for (const [key, item] of Object.entries(value)) integerFields(jsonFieldText(raw, key)!, item)
}
function policy(raw: string, tenant: string): ParameterPolicy {
  const data = record(parseJsonDocument(raw)); integerFields(raw, data)
  const result = policyResponse(raw)
  if (result.key !== parameterKey || result.scope !== 'TENANT' || result.tenant_id !== tenant || result.scope_id !== tenant || typeof result.has_override !== 'boolean' || !Number.isSafeInteger(result.revision) || result.revision < 0 || result.consumer !== 'CONNECTIVITY_AND_OFFLINE_EVENTS' || !Array.isArray(result.chain)) throw new Error('单位参数范围或消费者未确认')
  if ((result.has_override && (!Number.isSafeInteger(result.value) || result.value! < 1)) || (!result.has_override && result.value !== null)) throw new Error('单位参数覆盖状态未确认')
  if (result.effective.key !== parameterKey || !Number.isSafeInteger(result.effective.value) || result.effective.value < 1 || !['GLOBAL', 'TENANT'].includes(result.effective.source_scope) || !Number.isSafeInteger(result.effective.source_revision) || result.effective.source_revision < 0 || typeof result.effective.evaluated_at !== 'string') throw new Error('单位参数最终生效值未确认')
  return result
}
function ownTenant(): string {
  const session = useSession.getState()
  if (session.audience !== 'customer' || !session.user?.tenant_id) throw new ApiError(403, 'CUSTOMER_UNIT_REQUIRED', '此入口只维护当前客户单位')
  return session.user.tenant_id
}
function settingsResponse(text: string, tenant: string): UnitParameterSettings {
  const data = record(parseJsonDocument(text)); integerFields(text, data)
  const definition = record(data.definition)
  if (Object.keys(data).sort().join(',') !== 'allowed_maximum,definition,policy' || definition.key !== parameterKey || definition.value_type !== 'INTEGER' || definition.effect !== 'LOCAL_DERIVATION' || definition.consumer !== 'CONNECTIVITY_AND_OFFLINE_EVENTS' || typeof definition.name !== 'string' || typeof definition.unit !== 'string' || !Number.isSafeInteger(definition.min) || !Number.isSafeInteger(definition.max) || !Number.isSafeInteger(definition.global_value) || !Number.isSafeInteger(definition.global_revision) || !Array.isArray(definition.allowed_scopes)) throw new Error('单位参数定义未完整确认')
  const allowed = data.allowed_maximum
  if (typeof allowed !== 'number' || allowed < Number(definition.min) || allowed > Number(definition.max) || allowed > Number(definition.global_value)) throw new Error('单位参数允许范围未确认')
  return { definition: definition as unknown as ParameterDefinition, policy: policy(jsonFieldText(text, 'policy')!, tenant), allowed_maximum: allowed }
}
export const unitSettingsApi = {
  parameters(signal?: AbortSignal) {
    const tenant = ownTenant()
    return request<UnitParameterSettings>('/customer/unit-parameters', { signal, decode: text => settingsResponse(text, tenant) })
  },
  async change(action: 'SET' | 'CLEAR', value: string, revision: number, reason: string, key: string, signal?: AbortSignal) {
    const tenant = ownTenant()
    if (!Number.isSafeInteger(revision) || revision < 0) throw new Error('当前版本未确认，请刷新')
    const body = { ...(action === 'SET' ? { value: integerToken(value) } : {}), expected_revision: revision, reason }
    return request<ParameterPolicy>(`/customer/unit-parameters/${parameterKey}${action === 'CLEAR' ? '/clear' : ''}`, { method: action === 'SET' ? 'PUT' : 'POST', body: JSON.stringify(body), headers: { 'Idempotency-Key': key }, signal, decode: text => { parseJsonDocument(text); return policy(jsonFieldText(text, 'policy')!, tenant) } })
  },
  history(page: number, signal?: AbortSignal) {
    ownTenant()
    return request<{ items: UnitParameterChange[]; total: number; page: number; size: number }>(`/customer/unit-parameters/${parameterKey}/history?page=${page}`, { signal, decode: text => {
      const data = record(parseJsonDocument(text)); integerFields(text, data)
      if (!Array.isArray(data.items) || !Number.isSafeInteger(data.total) || Number(data.total) < 0 || !Number.isSafeInteger(data.page) || Number(data.page) < 1 || !Number.isSafeInteger(data.size) || Number(data.size) < 1) throw new Error('单位参数历史未确认')
      const items = data.items.map(value => { const row = record(value); if (row.key !== parameterKey || typeof row.id !== 'string' || !['SET', 'CLEAR'].includes(String(row.action)) || typeof row.created_at !== 'string' || Object.hasOwn(row, 'actor_id')) throw new Error('单位参数历史字段未确认'); return row as unknown as UnitParameterChange })
      return { items, total: Number(data.total), page: Number(data.page), size: Number(data.size) }
    } })
  },
}
