import { useState, type FormEvent } from 'react'
import { TreeSelect } from 'antd'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { alarmBaseApi, type AlarmConfigurationScope, type AlarmEventKind, type AlarmOverrideScope, type AlarmScopedConfiguration, type AlarmScopedValue } from '@/lib/alarm-configuration'
import type { Device, Paginated, PlaceNode, ScopeUnit } from '@/lib/types'
import { placeTreeOptions } from '@/lib/place-tree'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Empty, ErrorNotice, Loading, Pager } from '@/components/common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'

const eventKinds: readonly AlarmEventKind[] = ['FIRE', 'FAULT']
const scopes: readonly AlarmOverrideScope[] = ['PLACE', 'DEVICE', 'PLATFORM']
const voiceFields = [['zone', '区域'], ['position', '详细位置'], ['eventSource', '报警源/故障源'], ['time', '发生时间'], ['eventType', '报警/故障类型']] as const
const eventNames: Record<AlarmEventKind, string> = { FIRE: '火警', FAULT: '故障' }
const scopeNames: Record<AlarmConfigurationScope, string> = { UNIT: '单位', PLACE: '位置', DEVICE: '设备', PLATFORM: '平台' }
function matchesScopeTarget(config: AlarmScopedConfiguration, event: AlarmEventKind, scope: AlarmOverrideScope, targetId: string, tenantId: string) {
  if (config.event_kind !== event || config.scope !== scope || config.scope_id !== targetId) return false
  return scope === 'PLATFORM' ? config.tenant_id === null : config.tenant_id === tenantId
}
function parseSubscriptions(text: string): Record<string, string[]> {
  const map: Record<string, string[]> = {}
  for (const line of text.split('\n').filter(Boolean)) {
    const [system, codes = ''] = line.split(':', 2)
    if (system?.trim()) map[system.trim()] = codes.split(',').map(code => code.trim()).filter(Boolean)
  }
  return map
}

export function AlarmScopedRules({ canWrite }: { canWrite: boolean }) {
  const units = useResource<ScopeUnit[]>('alarm-scoped-rule-units', signal => api.scopeTree(signal))
  const [eventKind, setEventKind] = useState<AlarmEventKind>('FIRE')
  const [scope, setScope] = useState<AlarmOverrideScope>('PLACE')
  const [tenantId, setTenantId] = useState('')
  const [placeId, setPlaceId] = useState('')
  const [deviceFilterPlace, setDeviceFilterPlace] = useState('')
  const [deviceId, setDeviceId] = useState('')
  const [devicePage, setDevicePage] = useState(1)
  const [deviceSearch, setDeviceSearch] = useState('')
  const [deviceKeyword, setDeviceKeyword] = useState('')
  const places = useResource<PlaceNode[]>(`alarm-scope-places:${tenantId}`, signal => api.places(signal, [tenantId], [], true), scope !== 'PLATFORM' && !!tenantId)
  const devices = useResource<Paginated<Device>>(`alarm-scope-devices:${tenantId}:${deviceFilterPlace}:${deviceKeyword}:${devicePage}`, signal => api.devices(devicePage, deviceKeyword, 20, signal, undefined, deviceFilterPlace || undefined, tenantId), scope === 'DEVICE' && !!tenantId)
  const targetId = scope === 'PLATFORM' ? 'platform' : scope === 'PLACE' ? placeId : deviceId
  const targetReady = scope === 'PLATFORM' || !!tenantId && !!targetId
  const configuration = useResource<AlarmScopedConfiguration>(`alarm-scope-rule:${eventKind}:${scope}:${tenantId}:${targetId}`, signal => alarmBaseApi.getScoped(eventKind, scope, targetId, signal, tenantId || undefined), targetReady)
  const selectedPlace = places.data?.find(place => place.id === placeId)
  const selectedDevice = devices.data?.items.find(device => device.id === deviceId)
  const selectedTarget = scope === 'PLATFORM' ? '平台' : scope === 'PLACE' ? selectedPlace?.name ?? '位置未选择' : selectedDevice?.name ?? selectedDevice?.device_eui ?? '设备未选择'
  const responseMatchesTarget = !configuration.data || matchesScopeTarget(configuration.data, eventKind, scope, targetId, tenantId)

  function changeScope(next: AlarmOverrideScope) {
    setScope(next)
    setPlaceId('')
    setDeviceFilterPlace('')
    setDeviceId('')
    setDevicePage(1)
  }
  function changeTenant(next: string) {
    setTenantId(next)
    setPlaceId('')
    setDeviceFilterPlace('')
    setDeviceId('')
    setDevicePage(1)
  }

  return <section className="alarm-scoped-rules" aria-label="FIRE/FAULT 分层规则">
    <div className="alarm-scope-controls">
      <div className="field"><Label htmlFor="alarm-rule-event">事件类型</Label><select id="alarm-rule-event" className="native-select" value={eventKind} onChange={event => setEventKind(event.target.value as AlarmEventKind)}>{eventKinds.map(kind => <option value={kind} key={kind}>{eventNames[kind]} · {kind}</option>)}</select></div>
      <div className="field"><Label htmlFor="alarm-rule-scope">覆盖范围</Label><select id="alarm-rule-scope" className="native-select" value={scope} onChange={event => changeScope(event.target.value as AlarmOverrideScope)}>{scopes.map(level => <option value={level} key={level}>{scopeNames[level]}</option>)}</select></div>
      {scope !== 'PLATFORM' && <div className="field"><Label htmlFor="alarm-rule-unit">所属单位</Label><select id="alarm-rule-unit" className="native-select" value={tenantId} onChange={event => changeTenant(event.target.value)}><option value="">请明确选择授权单位</option>{units.data?.map(unit => <option value={unit.id} key={unit.id}>{unit.name} · {unit.id}</option>)}</select>{units.loading && <Loading/>}{units.error != null && <ErrorNotice error={units.error} retry={units.refresh}/>}</div>}
      {scope === 'PLACE' && tenantId && <div className="field"><Label htmlFor="alarm-rule-place">目标位置</Label>{places.loading ? <Loading/> : places.error != null ? <ErrorNotice error={places.error} retry={places.refresh}/> : <TreeSelect id="alarm-rule-place" className="alarm-scope-place-select" aria-label="目标位置" treeData={placeTreeOptions(places.data ?? [])} value={placeId || undefined} onChange={(value: string | undefined) => setPlaceId(value ?? '')} treeDefaultExpandAll allowClear placeholder="按真实层级选择位置"/>}</div>}
      {scope === 'DEVICE' && tenantId && <>
        <div className="field"><Label htmlFor="alarm-device-place-filter">位置筛选（可选）</Label>{places.loading ? <Loading/> : places.error != null ? <ErrorNotice error={places.error} retry={places.refresh}/> : <TreeSelect id="alarm-device-place-filter" className="alarm-scope-place-select" aria-label="位置筛选（可选）" treeData={placeTreeOptions(places.data ?? [])} value={deviceFilterPlace || undefined} onChange={(value: string | undefined) => { setDeviceFilterPlace(value ?? ''); setDeviceId(''); setDevicePage(1) }} treeDefaultExpandAll allowClear placeholder="不选位置时搜索本单位设备"/>}</div>
        <form className="field alarm-device-search" onSubmit={event => { event.preventDefault(); setDeviceKeyword(deviceSearch.trim()); setDevicePage(1); setDeviceId('') }}><Label htmlFor="alarm-device-search">搜索目标设备</Label><div><Input id="alarm-device-search" value={deviceSearch} onChange={event => setDeviceSearch(event.target.value)} placeholder="设备名称或编号"/><Button type="submit" variant="outline">查询</Button></div></form>
        <div className="field"><Label htmlFor="alarm-rule-device">目标设备</Label>{devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : <select id="alarm-rule-device" className="native-select" value={deviceId} onChange={event => setDeviceId(event.target.value)}><option value="">请明确选择一台设备</option>{devices.data?.items.map(device => <option value={device.id} key={device.id}>{device.name || '未命名设备'} · {device.device_eui ?? device.id}</option>)}</select>}{devices.data && <Pager page={devices.data.page} size={devices.data.size} total={devices.data.total} onChange={page => { setDevicePage(page); setDeviceId('') }}/>}</div>
      </>}
    </div>
    {scope === 'PLATFORM' || tenantId ? targetReady ? configuration.loading ? <Loading/> : configuration.error != null ? <ErrorNotice error={configuration.error} retry={configuration.refresh}/> : configuration.data ? responseMatchesTarget ? <AlarmScopedRuleEditor key={`${eventKind}:${scope}:${targetId}:${configuration.data.revision}`} config={configuration.data} eventKind={eventKind} scope={scope} tenantId={tenantId || undefined} targetName={selectedTarget} canWrite={canWrite} refresh={configuration.refresh}/> : <ErrorNotice error={new Error('服务端返回的事件、范围或归属与当前选择不一致，已停止编辑，请刷新核对。')} retry={configuration.refresh}/> : null : <Empty title="选择目标后读取当前规则">事件类型、覆盖范围和实际目标必须明确；读取筛选不会改变写入对象。</Empty> : null}
    <p className="muted-note">设备范围与位置范围分别保存；位置子树授权由服务端核验。平台规则不依赖当前单位筛选。FIRE/FAULT 规则只影响通知策略，不创建或改写报警事实。</p>
  </section>
}

function AlarmScopedRuleEditor({ config, eventKind, scope, tenantId, targetName, canWrite, refresh }: { config: AlarmScopedConfiguration; eventKind: AlarmEventKind; scope: AlarmOverrideScope; tenantId?: string; targetName: string; canWrite: boolean; refresh: () => void }) {
  const action = useMutationAction()
  const [draft, setDraft] = useState<AlarmScopedValue>(config.value ?? config.effective.value)
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState<'save' | 'clear' | null>(null)
  const hasOverride = config.value != null
  function changed(next: AlarmScopedValue) { setDraft(next); setPending(null); action.touch() }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('请填写本次变更原因')); return }
    if (!Number.isInteger(draft.push_period_hours) || draft.push_period_hours < 1 || draft.push_period_hours > 720 || !Number.isInteger(draft.silence_minutes) || draft.silence_minutes < 0 || draft.silence_minutes > 1440) { action.setError(new Error('推送周期需为 1–720 小时，静音间隔需为 0–1440 分钟')); return }
    if (!pending) { setPending('save'); action.setConfirming(true); return }
    const mode = pending
    void action.run((key, signal) => mode === 'clear'
      ? alarmBaseApi.clearScoped(eventKind, scope, config.scope_id, { expected_revision: config.revision, reason: reason.trim() }, key, signal, tenantId)
      : alarmBaseApi.saveScoped(eventKind, scope, config.scope_id, { expected_revision: config.revision, reason: reason.trim(), value: draft }, key, signal, tenantId),
    () => { setReason(''); setPending(null); action.setConfirming(false); refresh() })
  }
  function clearOverride() {
    if (!hasOverride) return
    if (!reason.trim()) { action.setError(new Error('请填写清除本级覆盖的原因')); return }
    action.touch()
    setPending('clear')
    action.setConfirming(true)
  }
  const effective = config.effective
  return <Card className="alarm-scope-rule-card"><CardContent>
    <div className="alarm-scope-effective" role="status"><strong>{hasOverride ? '本级已显式覆盖' : `本级未覆盖，正在继承${scopeNames[effective.source_scope]}规则`}</strong><span>{eventNames[eventKind]} · {scopeNames[scope]} · {targetName} · 本级版本 {config.revision} · 生效来源 {scopeNames[effective.source_scope]} {effective.source_id} / v{effective.source_revision}</span><p>上级单位通知接收：{effective.unit_receive_alarm ? '开启' : '关闭'} · 上级通知派发：{effective.unit_push_message ? '开启' : '关闭'}。上级关闭时，本级规则不会越权恢复通知。</p></div>
    <form className="alarm-scoped-rule-form" onSubmit={submit}>
      <fieldset disabled={!canWrite || action.busy}>
        <section className="alarm-scope-form-section"><h2>订阅与通知资格</h2><div className="alarm-scope-checks"><label className="notification-checkbox"><input type="checkbox" checked={draft.receive_alarm} onChange={event => changed({ ...draft, receive_alarm: event.target.checked, push_message: event.target.checked && draft.push_message })}/>接收{eventNames[eventKind]}</label><label className="notification-checkbox"><input type="checkbox" checked={draft.push_message} disabled={!draft.receive_alarm} onChange={event => changed({ ...draft, push_message: event.target.checked })}/>允许通知派发</label></div><p className="muted-note">继承或开启通知不会创建报警事实；接收和派发资格仍由服务端逐次核验。</p><div className="field"><Label htmlFor="alarm-scope-subscriptions">排除事件代码（每行：设备系统型号:代码1,代码2）</Label><textarea id="alarm-scope-subscriptions" rows={4} value={Object.entries(draft.subscriptions).map(([system, codes]) => `${system}:${codes.join(',')}`).join('\n')} onChange={event => changed({ ...draft, subscriptions: parseSubscriptions(event.target.value) })} placeholder="device-model:200040,200070"/></div><div className="field"><Label htmlFor="alarm-scope-period">渐进推送周期（小时）</Label><Input id="alarm-scope-period" type="number" min={1} max={720} value={draft.push_period_hours} onChange={event => changed({ ...draft, push_period_hours: Number(event.target.value) })}/></div></section>
        <section className="alarm-scope-form-section"><h2>语音内容与静音</h2><label className="notification-checkbox"><input type="checkbox" checked={draft.voice_enabled} onChange={event => changed({ ...draft, voice_enabled: event.target.checked })}/>启用本地语音内容</label><div className="field"><Label htmlFor="alarm-scope-voice-types">语音事件代码（逗号分隔）</Label><Input id="alarm-scope-voice-types" value={draft.voice_types.join(',')} onChange={event => changed({ ...draft, voice_types: event.target.value.split(',').map(item => item.trim()).filter(Boolean) })}/></div><fieldset className="field alarm-scope-field-options"><legend>播报字段</legend>{voiceFields.map(([field, label]) => <label className="notification-checkbox" key={field}><input type="checkbox" checked={draft.voice_fields.includes(field)} onChange={event => { const next = draft.voice_fields.filter(item => item !== field); if (event.target.checked) next.push(field); changed({ ...draft, voice_fields: next }) }}/>{label}</label>)}</fieldset><label className="notification-checkbox"><input type="checkbox" checked={draft.voice_continuous} onChange={event => changed({ ...draft, voice_continuous: event.target.checked })}/>连续朗读模式（用户点击后生效）</label><div className="field"><Label htmlFor="alarm-scope-silence">静音间隔（分钟）</Label><Input id="alarm-scope-silence" type="number" min={0} max={1440} value={draft.silence_minutes} onChange={event => changed({ ...draft, silence_minutes: Number(event.target.value) })}/></div></section>
      </fieldset>
      <div className="alarm-scope-save">
        <div className="field"><Label htmlFor="alarm-scope-reason">变更原因</Label><Input id="alarm-scope-reason" value={reason} onChange={event => { setReason(event.target.value); setPending(null); action.touch() }}/></div>
        {action.error != null && <ErrorNotice error={action.error}/>}
        <div className="alarm-scope-buttons"><Button type="submit" disabled={!canWrite || action.busy}>保存本级规则</Button>{hasOverride && <Button type="button" variant="outline" disabled={!canWrite || action.busy} onClick={clearOverride}>清除本级覆盖</Button>}</div>
      </div>
      {action.confirming && pending && <OperationConfirmation action={action} title={pending === 'clear' ? '确认清除本级覆盖' : '确认保存本级规则'} submitLabel={pending === 'clear' ? '确认清除' : '确认保存'} refresh={refresh} warning={pending === 'clear' ? '清除后将恢复继承上级有效规则，不删除历史修订。' : '保存会影响该事件类型在所选范围内的后续通知策略，不修改报警事实。'}><p>{eventNames[eventKind]} · {scopeNames[scope]} · {targetName}</p><p>本级版本：{config.revision} · 生效来源：{scopeNames[effective.source_scope]} {effective.source_id} / v{effective.source_revision}</p><p>变更原因：{reason}</p></OperationConfirmation>}
    </form>
  </CardContent></Card>
}
