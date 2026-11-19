import type { EffectiveParameter, Paginated, ParameterChange, ParameterChain, ParameterDefinition, ParameterMutation, ParameterPolicy } from './types'
function pick<T>(value: Record<string, unknown>, keys: readonly string[]): T { return Object.fromEntries(keys.filter(key => Object.hasOwn(value, key)).map(key => [key, value[key]])) as T }
export function integerToken(text: string): number {
  if (typeof text !== 'string' || !/^-?(?:0|[1-9]\d*)$/.test(text)) throw new Error('请输入普通十进制整数字面量，不接受空值、空格、小数、字符串或科学计数法')
  const value = Number(text)
  if (!Number.isSafeInteger(value)) throw new Error('整数超出安全表示范围，已拒绝，不会舍入后提交')
  return value
}
export function revisionInteger(value: number): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('当前revision必须是非负安全整数，请刷新后重开'); return value }
export function definedInteger(text: string, definition: ParameterDefinition): number {
  const value = integerToken(text)
  if (definition.value_type !== 'INTEGER' || !Number.isSafeInteger(definition.min) || !Number.isSafeInteger(definition.max) || definition.min > definition.max) throw new Error('服务端整数定义未确认，禁止提交')
  if (value < definition.min || value > definition.max) throw new Error(`请输入 ${definition.min} 至 ${definition.max} ${definition.unit} 的整数`)
  return value
}
export function effectiveMetadata(value: Record<string, unknown>): EffectiveParameter { return pick<EffectiveParameter>(value, ['key', 'value', 'unit', 'source_scope', 'source_id', 'source_revision', 'evaluated_at']) }
function changeMetadata(value: Record<string, unknown>): ParameterChange { return pick<ParameterChange>(value, ['id', 'key', 'scope', 'scope_id', 'tenant_id', 'action', 'previous_value', 'value', 'revision_before', 'revision_after', 'actor_id', 'reason', 'created_at']) }
function policyMetadata(value: Record<string, unknown>): ParameterPolicy {
  const policy = pick<ParameterPolicy>(value, ['key', 'scope', 'scope_id', 'tenant_id', 'has_override', 'value', 'revision', 'consumer'])
  if (policy.consumer !== 'CONNECTIVITY_AND_OFFLINE_EVENTS') throw new Error('参数消费者契约未确认')
  policy.effective = effectiveMetadata(value.effective as Record<string, unknown>)
  policy.chain = (value.chain as Record<string, unknown>[]).map(entry => pick<ParameterChain>(entry, ['scope', 'scope_id', 'has_override', 'value', 'revision', 'is_effective']))
  return policy
}
export function definitionsResponse(text: string): { items: ParameterDefinition[] } { const parsed = JSON.parse(text), items = parsed.items.map((item: Record<string, unknown>) => pick<ParameterDefinition>(item, ['key', 'name', 'unit', 'min', 'max', 'value_type', 'allowed_scopes', 'consumer', 'effect', 'global_value', 'global_revision'])); if (items.some((item: ParameterDefinition) => item.key === 'offline_threshold_minutes' ? item.consumer !== 'CONNECTIVITY_AND_OFFLINE_EVENTS' || item.effect !== 'LOCAL_DERIVATION' : item.effect !== 'QUERY_ONLY')) throw new Error('参数消费者/效果契约未确认'); return { items } }
export function targetsResponse(text: string): Paginated<{ id: string; name: string; tenant_id: string }> { const parsed = JSON.parse(text); if (!parsed || !Array.isArray(parsed.items) || !Number.isSafeInteger(parsed.page) || !Number.isSafeInteger(parsed.size) || !Number.isSafeInteger(parsed.total)) throw new Error('参数目标目录响应未完整确认'); const items = parsed.items.map((item: Record<string, unknown>) => { if (!item || typeof item.id !== 'string' || !item.id || typeof item.name !== 'string' || typeof item.tenant_id !== 'string' || !item.tenant_id) throw new Error('参数目标缺少真实单位归属，已拒绝'); return pick<{ id: string; name: string; tenant_id: string }>(item, ['id', 'name', 'tenant_id']) }); return { items, page: parsed.page, size: parsed.size, total: parsed.total } }
export function policyResponse(text: string): ParameterPolicy { return policyMetadata(JSON.parse(text)) }
export function changeResponse(text: string): ParameterMutation { const parsed = JSON.parse(text); return { policy: policyMetadata(parsed.policy), change: changeMetadata(parsed.change) } }
export function changesResponse(text: string): Paginated<ParameterChange> { const parsed = JSON.parse(text); return { items: parsed.items.map(changeMetadata), page: parsed.page, size: parsed.size, total: parsed.total } }
export function effectiveResponse(text: string): { device_id: string; items: EffectiveParameter[]; consumer: 'CONNECTIVITY_AND_OFFLINE_EVENTS' } { const parsed = JSON.parse(text); if (parsed.consumer !== 'CONNECTIVITY_AND_OFFLINE_EVENTS') throw new Error('有效参数消费者契约未确认'); return { device_id: parsed.device_id, items: parsed.items.map(effectiveMetadata), consumer: parsed.consumer } }
