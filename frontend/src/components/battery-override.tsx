import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from 'react'
import { batteryApi } from '@/lib/battery-api'
import { beginBatteryIntent, getBatteryIntent, releaseBatteryConflict, retryBatteryIntent, subscribeBatteryIntent } from '@/lib/battery-intent'
import { validThreshold } from '@/lib/battery-threshold'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Card, CardContent, CardHeader, CardTitle } from './ui/card'
import { ErrorNotice, Loading } from './common'

export function BatteryOverride({ deviceId, tenantId }: { deviceId: string; tenantId: string }) {
  const permissions = useSession(s => s.permissions)
  const canWrite = permissions.includes('parameters:write')
  const definitions = useResource('battery-metric-definitions', signal => batteryApi.definitions(signal))
  const current = useResource(`battery-override:${tenantId}:${deviceId}`, signal => batteryApi.view(deviceId, tenantId, signal))
  const [historyPage, setHistoryPage] = useState(1)
  const history = useResource(`battery-override-history:${tenantId}:${deviceId}:${historyPage}`, signal => batteryApi.history(deviceId, tenantId, historyPage, signal))
  const persisted = useSyncExternalStore(subscribeBatteryIntent, getBatteryIntent, getBatteryIntent)
  const intent = persisted?.deviceId === deviceId ? persisted : null
  const blockedByOther = !!persisted && persisted.deviceId !== deviceId
  const [thresholdValue, setThresholdValue] = useState('')
  const [reason, setReason] = useState('')
  const [actionKind, setActionKind] = useState<'SET' | 'CLEAR'>('SET')
  const [comparator, setComparator] = useState<'LT' | 'LTE' | ''>('')
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<unknown>()
  const [refreshRequested, setRefreshRequested] = useState(false)
  const [conflictRefreshed, setConflictRefreshed] = useState(false)
  const lastIntent = useRef(persisted)
  useEffect(() => {
    if (lastIntent.current && !persisted) { current.refresh(); history.refresh(); setActionKind('SET'); setComparator(''); setThresholdValue(''); setReason(''); setConfirming(false) }
    lastIntent.current = persisted
  }, [persisted])
  useEffect(() => { if (refreshRequested && !current.loading && current.data) { setConflictRefreshed(true); setRefreshRequested(false) } }, [refreshRequested, current.loading, current.data, current.loadedAt])
  function refresh() { current.refresh(); history.refresh(); if (intent?.state === 'CONFLICT') { setRefreshRequested(true); setConflictRefreshed(false) } }
  function submit(event: FormEvent) {
    event.preventDefault(); setError(undefined)
    if (intent) {
      if (intent.state !== 'PENDING') { if (!retryBatteryIntent()) setError(new Error('原操作尚未进入可重试状态')) }
      return
    }
    if (!current.data) return
    if (actionKind === 'SET' && (comparator === '' || !validThreshold(thresholdValue, Number(thresholdValue)))) { setError(new Error('请明确选择LT/LTE，并填写0至100、最多36位小数的阈值')); return }
    if (actionKind === 'SET' && current.data.product_version && current.data.product_version.battery_capability !== 'SUPPORTED') { setError(new Error('固定产品版本电量能力不是SUPPORTED，不能设置设备覆盖')); return }
    if (!reason.trim()) { setError(new Error('请填写变更原因')); return }
    if (!confirming) { setConfirming(true); return }
    const threshold = actionKind === 'SET' ? { parameter_key: 'battery_low_threshold_percent' as const, metric_key: 'battery_percent' as const, unit: '%' as const, comparator: comparator as 'LT' | 'LTE', threshold: Number(thresholdValue), threshold_json: thresholdValue } : null
    const body = { expected_revision: current.data.override.revision, battery_low_threshold: threshold, battery_low_threshold_json: actionKind === 'SET' ? thresholdValue : null, reason: reason.trim() }
    setConfirming(false)
    if (!beginBatteryIntent(deviceId, tenantId, body)) setError(new Error('已有未解决的电量覆盖操作；请先使用原内容和幂等键重试'))
  }
  const definition = definitions.data?.items[0]
  return <Card className="battery-override">
    <CardHeader><CardTitle>电量低阈值覆盖</CardTitle></CardHeader>
    <CardContent>
      {definition && <div className="parameter-definition"><strong>电量低阈值参数定义 · {definition.key}</strong><p>指标 {definition.metric_key} · 单位 {definition.unit} · 范围 {definition.min}–{definition.max} · 精度 {definition.scale} 位 · LT / LTE</p><p>消费者 {definition.consumer} · {definition.effect}</p></div>}
      {definitions.loading && <Loading/>}{definitions.error != null && <ErrorNotice error={definitions.error} retry={definitions.refresh}/>}
      {current.loading ? <Loading/> : current.error != null ? <ErrorNotice error={current.error} retry={current.refresh}/> : current.data && <>
        <div className="notice">仅影响查询时的电量低阈值观察；不是告警、通知、命令或硬件控制。未绑定产品的旧设备可设置单设备覆盖。已绑定设备的支持声明与有效来源如下。</div>
        <dl className="detail-list"><div><dt>当前有效阈值配置与来源</dt><dd>{current.data.effective.configured ? `${current.data.effective.threshold_json}${current.data.effective.comparator}` : '未配置'} · {current.data.effective.source_scope} · revision {current.data.effective.source_revision}</dd></div><div><dt>固定产品版本 / 电量能力</dt><dd>{current.data.product_version ? `${current.data.product_version.product_version_id} · v${current.data.product_version.version_number} · ${current.data.product_version.battery_capability}` : '未绑定版本；仅设备覆盖可生效'}</dd></div><div><dt>设备覆盖</dt><dd>{current.data.override.has_value ? `${current.data.override.threshold_json}${current.data.override.comparator}` : '未设置覆盖'} · revision {current.data.override.revision}</dd></div></dl>
        {blockedByOther && <div role="alert" className="notice">另一个设备存在尚未确认的电量覆盖操作，禁止更换对象或创建新操作。请返回原设备，以原操作内容和幂等键重试。</div>}
        {intent && <section role="status" className="notice"><strong>{intent.state === 'PENDING' ? '原操作正在等待服务端回执' : intent.state === 'CONFLICT' ? '原操作发生revision/幂等冲突' : '原操作结果未确认'}</strong><p>设备 {intent.deviceId} · expected revision {intent.body.expected_revision} · {intent.body.battery_low_threshold ? `${intent.body.battery_low_threshold.threshold_json}${intent.body.battery_low_threshold.comparator}` : 'CLEAR'} · {intent.body.reason}</p>{intent.state === 'CONFLICT' && <><p>刷新当前配置和历史后，只有确认revision已变化，才能显式放弃冲突操作并重新编辑。</p><Button type="button" variant="outline" onClick={refresh}>刷新覆盖与历史</Button>{conflictRefreshed && current.data.override.revision !== intent.body.expected_revision && <Button type="button" variant="outline" onClick={() => { if (releaseBatteryConflict(deviceId, current.data!.override.revision)) { setConfirming(false); setReason(''); setThresholdValue(''); setComparator('') } }}>已刷新并确认冲突，放弃原操作后重新设置</Button>}</>}{intent.state !== 'PENDING' && <form onSubmit={submit}><Button type="submit">{intent.state === 'CONFLICT' ? '使用原内容重试核对冲突' : '使用原操作内容和幂等键重试'}</Button></form>}</section>}
        {!intent && !blockedByOther && canWrite && definition && <form className="form-stack" onSubmit={submit}>
          <div className="field"><Label htmlFor="battery-override-action">操作</Label><select id="battery-override-action" className="native-select" value={actionKind} disabled={confirming} onChange={event => setActionKind(event.target.value as 'SET' | 'CLEAR')}><option value="SET">SET · 设置覆盖</option><option value="CLEAR">CLEAR · 清除覆盖并继承</option></select></div>
          {actionKind === 'SET' && <><div className="field"><Label htmlFor="battery-override-comparator">电量比较方式</Label><select id="battery-override-comparator" className="native-select" value={comparator} disabled={confirming} onChange={event => setComparator(event.target.value as 'LT' | 'LTE' | '')}><option value="">请选择LT或LTE比较方式</option><option value="LT">LT · 小于</option><option value="LTE">LTE · 小于或等于</option></select></div><div className="field"><Label htmlFor="battery-override-threshold">电量低阈值（%）</Label><Input id="battery-override-threshold" inputMode="decimal" value={thresholdValue} disabled={confirming} onChange={event => setThresholdValue(event.target.value)}/></div></>}
          <div className="field"><Label htmlFor="battery-override-reason">变更原因</Label><Input id="battery-override-reason" value={reason} disabled={confirming} onChange={event => setReason(event.target.value)} required/></div>
          {confirming && <div className="notice"><strong>确认目标</strong><p>{actionKind === 'SET' ? `将设备 ${deviceId} 的电量低覆盖设为 ${thresholdValue}% (${comparator})` : `清除设备 ${deviceId} 的电量低覆盖并继承上级`} · expected revision {current.data.override.revision} · 原因 {reason}</p><p>首次请求将冻结操作、阈值、原因、expected revision和幂等键；离开页面不会丢弃未知结果。</p></div>}
          {error != null && <ErrorNotice error={error}/>}<Button type="submit" disabled={actionKind === 'SET' && (!thresholdValue || !comparator)}>{confirming ? '确认并提交' : '核对并继续'}</Button>
        </form>}
        {!canWrite && <p className="muted-note">当前账户只有读权限；不会展示写入表单。</p>}
        <h3>设备覆盖历史</h3>{history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : <ol>{history.data?.items.map(item => <li key={item.id}>{item.action} · revision {item.revision_before}→{item.revision_after} · {item.previous ? `${item.previous.threshold_json}${item.previous.comparator}` : '无'} → {item.new ? `${item.new.threshold_json}${item.new.comparator}` : '无'} · {item.reason} · {item.created_at}</li>)}</ol>}
        <div className="version-actions"><Button type="button" variant="outline" disabled={historyPage <= 1} onClick={() => setHistoryPage(historyPage - 1)}>上一页覆盖历史</Button><span>{historyPage} / {Math.max(1, Math.ceil((history.data?.total ?? 0) / (history.data?.size ?? 1)))}</span><Button type="button" variant="outline" disabled={!history.data || historyPage >= Math.ceil(history.data.total / history.data.size)} onClick={() => setHistoryPage(historyPage + 1)}>下一页覆盖历史</Button></div>
        <Button type="button" variant="outline" onClick={refresh}>刷新覆盖与历史</Button>
      </>}
    </CardContent>
  </Card>
}
