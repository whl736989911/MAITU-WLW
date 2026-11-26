import type { EffectiveParameter } from '@/lib/types'
import { dateTime, reported } from '@/lib/format'
export const parameterScopeNames = { GLOBAL: '全局', TENANT: '单位', PLACE: '位置', GROUP: '设备分组', DEVICE: '单设备' } as const
export function validOfflinePolicy(policy?: EffectiveParameter | null): policy is EffectiveParameter {
  return !!policy && policy.key === 'offline_threshold_minutes' && Number.isSafeInteger(policy.value) && policy.value > 0 && typeof policy.unit === 'string' && !!policy.unit && Object.hasOwn(parameterScopeNames, policy.source_scope) && Number.isSafeInteger(policy.source_revision) && policy.source_revision >= 0 && (policy.source_scope === 'GLOBAL' ? policy.source_id === null : typeof policy.source_id === 'string' && !!policy.source_id) && typeof policy.evaluated_at === 'string' && Number.isFinite(Date.parse(policy.evaluated_at))
}
export function OfflinePolicy({ policy, detailed = false }: { policy?: EffectiveParameter | null; detailed?: boolean }) {
  if (!validOfflinePolicy(policy)) return <div className="offline-policy unknown">离线策略未确认 · 不推定默认或0</div>
  return <div className="offline-policy"><strong>有效离线阈值 {policy.value} {policy.unit}</strong><span>来源：{parameterScopeNames[policy.source_scope]}（{policy.source_scope}） · revision {policy.source_revision}</span>{detailed && <><span className="mono">来源ID：{policy.source_id ?? '全局基础项，无scope ID'}</span><span>策略评估时间：{dateTime(policy.evaluated_at)}（非设备上报）</span></>}{!detailed && <span title={policy.evaluated_at}>评估于 {dateTime(policy.evaluated_at)}</span>}</div>
}
export function OverrideValue({ value }: { value: number | null }) { return <>{value === null ? '无覆盖 · 继承' : reported(value)}</> }
