import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useResource } from '@/lib/use-resource'
import { api } from '@/lib/api'
import type { NotificationChannelPolicy, NotificationChannelPolicySource } from '@/lib/types'
import { useSession } from '@/lib/store'
import { placePath } from '@/lib/place-tree'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Card, CardContent } from './ui/card'
import { Empty, ErrorNotice, Loading } from './common'
import { ConfigurationUnitTarget } from './configuration-unit-target'
import { OperationConfirmation, useMutationAction } from './identity-common'

type Tri = 'INHERIT' | 'ENABLED' | 'DISABLED'
const tri = (value: boolean | null): Tri => value == null ? 'INHERIT' : value ? 'ENABLED' : 'DISABLED'
const triName: Record<Tri, string> = { INHERIT: '继承上级/单位（不写显式值）', ENABLED: '显式启用', DISABLED: '显式停用' }
const triWire = (value: Tri): boolean | null => value === 'INHERIT' ? null : value === 'ENABLED'

/** Explicit channel settings and their effective UNIT/PLACE inheritance sources. */
export function NotificationChannelPolicyView({ canRead, canWrite }: { canRead: boolean; canWrite: boolean }) {
  const session = useSession()
  const scopeKey = session.generation
  if (!canRead) return <Empty title="当前账户无通知通道策略读取权限"/>
  return <ConfigurationUnitTarget key={scopeKey}>{tenantId => <ChannelPolicyContent key={tenantId} tenantId={tenantId} canWrite={canWrite}/>}</ConfigurationUnitTarget>
}
function ChannelPolicyContent({ tenantId, canWrite }: { tenantId: string; canWrite: boolean }) {
  const [scopeType, setScopeType] = useState<'UNIT' | 'PLACE'>('UNIT'), [placeId, setPlaceId] = useState('')
  const tree = useResource('channel-policy-scope-tree', signal => api.scopeTree(signal))
  const unitName = tree.data?.find(unit => unit.id === tenantId)?.name ?? tenantId
  const placeLabels = useMemo(() => {
    const places = tree.data?.find(unit => unit.id === tenantId)?.places ?? []
    return new Map(places.map(place => [place.id, placePath(place, places)] as const).sort((a, b) => a[1].localeCompare(b[1])))
  }, [tree.data, tenantId])
  const policy = useResource<NotificationChannelPolicy>(`notification-channel-policy:${tenantId}:${scopeType}:${placeId}`, signal => api.notificationChannelPolicy(scopeType, placeId || null, signal, tenantId), scopeType === 'UNIT' || !!placeId)
  const scopeLabel = scopeType === 'UNIT' ? unitName : `${unitName} / ${placeLabels.get(placeId) ?? placeId}`
  return <Card><CardContent>
    <h2 className="section-heading">短信 / 语音 / 公众号通道策略</h2>
    <p className="muted-note">策略按位置逐级继承：位置自身 → 上级位置 → 单位。未设置（继承）不会写入显式值；两层都选择继承会删除该层设置并回退到上级或单位策略，最终无显式策略时为默认停用。</p>
    <div className="provider-grid">
      <div className="field"><Label htmlFor="channel-policy-scope">策略层级</Label><select id="channel-policy-scope" className="native-select" value={scopeType} onChange={event => { setScopeType(event.target.value as 'UNIT' | 'PLACE'); setPlaceId('') }}><option value="UNIT">单位策略（影响全单位）</option><option value="PLACE">位置策略（可覆盖上级）</option></select></div>
      {scopeType === 'PLACE' && <div className="field"><Label htmlFor="channel-policy-place">目标位置</Label>{tree.loading ? <Loading/> : tree.error != null ? <ErrorNotice error={tree.error} retry={tree.refresh}/> : <select id="channel-policy-place" className="native-select" value={placeId} onChange={event => setPlaceId(event.target.value)}><option value="">请明确选择位置，不自动默认</option>{Array.from(placeLabels, ([id, label]) => <option key={id} value={id}>{label} · {id}</option>)}</select>}</div>}
    </div>
    {scopeType === 'PLACE' && !placeId ? <Empty title="请选择目标位置">位置策略必须明确指定位置，服务端会按当前授权范围再次核验。</Empty>
      : policy.loading ? <Loading/> : policy.error != null ? <ErrorNotice error={policy.error} retry={policy.refresh}/>
      : policy.data && (policy.data.tenant_id !== tenantId || policy.data.scope_type !== scopeType || policy.data.place_id !== (scopeType === 'UNIT' ? null : placeId)) ? <ErrorNotice error={new Error('通道策略所属单位或位置与当前选择不一致，请重新读取。')} retry={policy.refresh}/>
      : policy.data && <PolicyEditor key={`${tenantId}:${scopeType}:${placeId}`} policy={policy.data} scopeLabel={scopeLabel} placeLabels={placeLabels} tenantId={tenantId} canWrite={canWrite} refresh={policy.refresh}/>}
  </CardContent></Card>
}
function PolicyEditor({ policy, scopeLabel, placeLabels, tenantId, canWrite, refresh }: { policy: NotificationChannelPolicy; scopeLabel: string; placeLabels: ReadonlyMap<string, string>; tenantId: string; canWrite: boolean; refresh: () => void }) {
  const [sms, setSms] = useState<Tri>(() => tri(policy.sms_enabled)), [voice, setVoice] = useState<Tri>(() => tri(policy.voice_enabled)), [wechat, setWechat] = useState<Tri>(() => tri(policy.wechat_enabled)), [reason, setReason] = useState(''), [notice, setNotice] = useState('')
  const action = useMutationAction()
  useEffect(() => { setSms(tri(policy.sms_enabled)); setVoice(tri(policy.voice_enabled)); setWechat(tri(policy.wechat_enabled)) }, [policy])
  const rows: { channel: string; explicit: boolean | null; effective: boolean; source: NotificationChannelPolicySource | null; value: Tri; set: (value: Tri) => void }[] = [
    { channel: '短信', explicit: policy.sms_enabled, effective: policy.effective_sms, source: policy.sms_source, value: sms, set: setSms },
    { channel: '语音', explicit: policy.voice_enabled, effective: policy.effective_voice, source: policy.voice_source, value: voice, set: setVoice },
    { channel: '公众号', explicit: policy.wechat_enabled, effective: policy.effective_wechat, source: policy.wechat_source, value: wechat, set: setWechat },
  ]
  function submit(event: FormEvent) {
    event.preventDefault(); if (!canWrite) return
    if (!reason.trim()) { action.setError(new Error('必须填写本次策略变更原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    const body = { scope_type: policy.scope_type, place_id: policy.place_id, sms_enabled: triWire(sms), voice_enabled: triWire(voice), wechat_enabled: triWire(wechat), expected_revision: policy.revision, reason: reason.trim() }
    void action.run((key, signal) => api.saveNotificationChannelPolicy(body, key, signal, tenantId), () => { setNotice('通道策略已保存并重新读取生效值。'); refresh() })
  }
  return <form className="form-stack" onSubmit={submit}>
    <div className="notice">{policy.scope_type === 'PLACE' ? `当前目标：位置「${scopeLabel}」` : `当前目标：单位「${scopeLabel}」`} · 配置版本 revision {policy.revision} · {policy.configured ? '存在显式设置' : '没有显式设置，全部继承'}</div>
    <div className="channel-policy-status">
      {rows.map(row => <section className="channel-policy-status-item" aria-label={`${row.channel}通道状态`} key={row.channel}>
        <div className="channel-policy-status-heading"><h3>{row.channel}</h3><strong>{row.effective ? '当前启用' : '当前停用'}</strong></div>
        <dl><div><dt>本层设置</dt><dd>{row.explicit == null ? '未设置（继承）' : row.explicit ? '启用' : '停用'}</dd></div>
          <div><dt>生效来源</dt><dd>{row.source ? `${row.source.scope_type === 'UNIT' ? '单位' : '位置'}「${row.source.scope_type === 'PLACE' ? placeLabels.get(row.source.scope_id) ?? row.source.name : row.source.name}」${row.source.scope_type === 'UNIT' ? '（单位级）' : row.source.scope_id === policy.place_id ? '（当前层级）' : '（上级位置）'}` : '无显式策略，默认停用'}</dd></div></dl>
      </section>)}
    </div>
    {canWrite ? <><div className="provider-grid">
      {rows.map(row => { const id = row.channel === '短信' ? 'sms' : row.channel === '语音' ? 'voice' : 'wechat'; return <div className="field" key={row.channel}><Label htmlFor={`channel-policy-${id}`}>{row.channel}显式值</Label><select id={`channel-policy-${id}`} className="native-select" value={row.value} disabled={action.confirming || action.busy} onChange={event => { action.touch(); row.set(event.target.value as Tri) }}>{(['INHERIT', 'ENABLED', 'DISABLED'] as Tri[]).map(value => <option key={value} value={value}>{triName[value]}</option>)}</select></div> })}
    </div>
    <div className="field"><Label htmlFor="channel-policy-reason">本次操作原因</Label><Input id="channel-policy-reason" maxLength={1000} required value={reason} disabled={action.confirming || action.busy} onChange={event => { action.touch(); setReason(event.target.value) }}/></div>
    {action.error != null && !action.confirming && <ErrorNotice error={action.error}/>}
    {!action.confirming && <div className="identity-form-actions"><Button type="submit" disabled={action.busy}>核对策略变更</Button></div>}
    {action.confirming && <OperationConfirmation action={action} title="确认变更通知通道策略" submitLabel={action.busy ? '正在保存…' : '确认保存策略'} refresh={refresh} warning="策略决定后续真实通知是否允许该通道；本页只保存策略，不发送测试消息、不调用供应商。公众号还要求平台配置启用且联系人已绑定。"><p>{scopeLabel} · 短信 {triName[sms]} · 语音 {triName[voice]} · 公众号 {triName[wechat]}</p><p>配置版本：{policy.revision} · 原因：{reason}</p></OperationConfirmation>}
    </> : <div className="notice">当前为只读查看；无法修改通道策略。</div>}
    {notice && <div role="status" className="notice success-note">{notice}</div>}
  </form>
}
