import { useRef, useState, type FormEvent } from 'react'
import { api, ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import type { ProfileSnapshot } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice, Loading, PageHeading } from '@/components/common'
import { useMutationAction } from '@/components/identity-common'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { OperationalPortraitPanel } from '@/components/operational-portrait'
import { PageHelp } from '@/components/page-tools'

const statusNames: Record<ProfileSnapshot['status'], string> = { OK: '样本充足', INSUFFICIENT: '样本不足', MISSING: '窗口内未上报', UNSUPPORTED: '产品未支持该指标' }

export function ProfilesPage() { return <ProfilesGate><ProfileContent/></ProfilesGate> }

function ProfilesGate({ children }: { children: React.ReactNode }) {
  const { user, audience, permissions } = useSession()
  if (!user || user.must_change_password || audience !== 'management' || !permissions.includes('profiles:read')) return <Empty title="当前账户无画像权限"/>
  return <>{children}</>
}

function ProfileContent() {
  const session = useSession(), permissions = session.permissions
  const canWrite = permissions.includes('profiles:write')
  const tenantIds = session.scopeTenantIds, placeIds = session.scopePlaceIds, initialized = session.scopeInitialized
  const scopeEmpty = initialized && tenantIds.length === 0 && placeIds.length === 0
  const scopeKey = `${tenantIds.join(',')}:${placeIds.join(',')}:${initialized}`
  const units = useResource('profile-target-units', signal => api.scopeTree(signal), true)
  const targets = initialized ? (units.data ?? []).filter(unit => tenantIds.includes(unit.id) || unit.places.some(place => placeIds.includes(place.id))) : []
  const rules = useResource(`profile-rules:${scopeKey}`, signal => api.profileRules(signal, tenantIds, placeIds), initialized && !scopeEmpty)
  const [tenantId, setTenantId] = useState(''), [deviceId, setDeviceId] = useState(''), [snapshot, setSnapshot] = useState<ProfileSnapshot | null>(null), [history, setHistory] = useState<ProfileSnapshot[]>([])
  const [configuring, setConfiguring] = useState(false), [reason, setReason] = useState(''), [tab, setTab] = useState<'operations' | 'battery'>('operations')
  const [profileLoading, setProfileLoading] = useState(false), [profileError, setProfileError] = useState<unknown>()
  const loadSequence = useRef(0)
  const action = useMutationAction()
  async function load(device: string) {
    if (!tenantId) { setProfileError(new Error('请从已选树范围明确选择设备所属单位')); return }
    const sequence = ++loadSequence.current
    setSnapshot(null); setHistory([]); setProfileError(undefined); setProfileLoading(true)
    try {
      let nextSnapshot: ProfileSnapshot | null
      try { nextSnapshot = await api.deviceProfile(device, undefined, tenantId, tenantIds, placeIds) }
      catch (error) { if (error instanceof ApiError && error.status === 404) nextSnapshot = null; else throw error }
      const nextHistory = await api.profileHistory(device, undefined, tenantId, tenantIds, placeIds)
      if (sequence === loadSequence.current) { setSnapshot(nextSnapshot); setHistory(nextHistory) }
    } catch (error) {
      if (sequence === loadSequence.current) setProfileError(error)
    } finally {
      if (sequence === loadSequence.current) setProfileLoading(false)
    }
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!tenantId || !reason.trim() || !deviceId.trim()) { action.setError(new Error('必须明确选择目标单位，并填写设备ID与原因')); return }
    void action.run(async () => { await api.recomputeProfile(deviceId.trim(), { reason: reason.trim() }, undefined, tenantId); await load(deviceId.trim()) }, action.touch)
  }
  return <><PageHeading eyebrow="PROFILE · 可解释画像" title="设备画像"><PageHelp title="画像数据说明">基于授权单位 / 位置范围及已持久化证据生成运维标签；电量观测保持独立版本化快照。规则变更生成新版本，不修改报警或设备状态。</PageHelp></PageHeading>
    <Card><CardContent><div className="field"><Label htmlFor="profile-unit">设备所属单位（读取 / 配置目标）</Label><select id="profile-unit" className="native-select" value={tenantId} onChange={e => { ++loadSequence.current; setTenantId(e.target.value); setSnapshot(null); setHistory([]); setProfileError(undefined); action.touch() }}><option value="">请明确选择单位</option>{targets.map(unit => <option key={unit.id} value={unit.id}>{unit.name} · {unit.id}</option>)}</select></div>
      <div className="list-toolbar" role="tablist" aria-label="画像类型"><Button type="button" role="tab" aria-selected={tab === 'operations'} onClick={() => setTab('operations')}>设备运维画像（6类标签）</Button><Button type="button" role="tab" aria-selected={tab === 'battery'} onClick={() => setTab('battery')}>电量观测快照</Button></div></CardContent></Card>
    {tab === 'operations' ? <OperationalPortraitPanel tenantId={tenantId} tenantIds={tenantIds} placeIds={placeIds} canWrite={canWrite}/> : <>
    <Card><CardContent><div className="list-toolbar"><h2>授权范围画像规则</h2>{canWrite && <Button type="button" onClick={() => setConfiguring(true)} disabled={!tenantId}>配置电量规则（先选择目标单位）</Button>}</div>
      {scopeEmpty ? <Empty title="当前范围未选择">请在左侧单位 / 位置树选择范围；清空后画像规则为零条。</Empty> : rules.loading ? <Loading/> : rules.error != null ? <ErrorNotice error={rules.error} retry={rules.refresh}/> : rules.data?.items.length ? <Table><TableHeader><TableRow><TableHead>单位 ID</TableHead><TableHead>版本</TableHead><TableHead>窗口</TableHead><TableHead>最小样本</TableHead><TableHead>状态</TableHead><TableHead>变更原因</TableHead></TableRow></TableHeader><TableBody>{rules.data.items.map(rule => <TableRow key={rule.tenant_id}><TableCell className="mono">{rule.tenant_id}</TableCell><TableCell>v{rule.version}</TableCell><TableCell>{rule.window_days} 天</TableCell><TableCell>{rule.min_samples}</TableCell><TableCell>{rule.enabled ? '启用' : '停用'}</TableCell><TableCell>{rule.reason}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="所选授权范围尚无画像规则">未配置时不会生成任何画像结论。</Empty>}
    </CardContent></Card>
    <Card><CardContent><form className="form-stack" onSubmit={submit}><div className="list-toolbar"><h2>查看与重算</h2><Button type="button" variant="outline" onClick={() => void load(deviceId.trim())} disabled={!tenantId || !deviceId.trim()}>读取当前画像</Button></div>
      
      <div className="field"><Label htmlFor="profile-device">设备ID</Label><Input id="profile-device" value={deviceId} onChange={e => setDeviceId(e.target.value)}/></div>
      {profileLoading ? <Loading/> : profileError != null ? <ErrorNotice error={profileError} retry={() => void load(deviceId.trim())}/> : snapshot && <dl className="detail-list"><div><dt>状态</dt><dd><Badge variant="outline">{statusNames[snapshot.status]}</Badge></dd></div><div><dt>样本数</dt><dd>{snapshot.sample_count}</dd></div><div><dt>最新 / 最小 / 最大 / 均值</dt><dd>{[snapshot.latest_percent, snapshot.min_percent, snapshot.max_percent, snapshot.mean_percent].map(v => v ?? '未计算').join(' / ')}</dd></div><div><dt>窗口</dt><dd>{dateTime(snapshot.window_start)} → {dateTime(snapshot.window_end)}</dd></div><div><dt>规则版本</dt><dd>v{snapshot.rule_version}</dd></div></dl>}
      {canWrite && <> <div className="field"><Label htmlFor="profile-reason">重算原因</Label><Input id="profile-reason" value={reason} onChange={e => { setReason(e.target.value); action.touch() }}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit" disabled={action.busy || !tenantId}>重算并保存快照</Button></>}</form>
    </CardContent></Card>
    {history.length > 0 && <Card><CardContent><h2>历史快照</h2><Table><TableHeader><TableRow><TableHead>计算时间</TableHead><TableHead>规则</TableHead><TableHead>状态</TableHead><TableHead>样本</TableHead><TableHead>均值</TableHead></TableRow></TableHeader><TableBody>{history.map(item => <TableRow key={item.id}><TableCell>{dateTime(item.computed_at)}</TableCell><TableCell>v{item.rule_version} · {item.window_days}天</TableCell><TableCell>{item.status}</TableCell><TableCell>{item.sample_count}</TableCell><TableCell>{item.mean_percent ?? '未计算'}</TableCell></TableRow>)}</TableBody></Table></CardContent></Card>}
    <Sheet open={configuring} onOpenChange={setConfiguring}><SheetContent className="review-sheet sm:max-w-xl">{configuring && <RuleForm tenantId={tenantId} onClose={() => setConfiguring(false)} saved={() => { setConfiguring(false); rules.refresh() }}/>}</SheetContent></Sheet>
    </>}
  </>
}

function RuleForm({ tenantId, onClose, saved }: { tenantId: string; onClose: () => void; saved: () => void }) {
  const [windowDays, setWindowDays] = useState('30'), [minSamples, setMinSamples] = useState('3'), [enabled, setEnabled] = useState(true), [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!tenantId || !reason.trim()) { action.setError(new Error('必须明确选择单位并填写变更原因')); return }
    void action.run(async () => { await api.configureProfileRule({ window_days: Number(windowDays), min_samples: Number(minSamples), enabled, reason: reason.trim() }, undefined, tenantId) }, saved)
  }
  return <Card><CardContent><form className="form-stack" onSubmit={submit}><h2>配置画像规则（生成新版本）</h2><p>目标单位：{tenantId}</p><div className="field"><Label htmlFor="rule-window">窗口天数</Label><Input id="rule-window" value={windowDays} onChange={e => setWindowDays(e.target.value)}/></div><div className="field"><Label htmlFor="rule-min">最小样本数</Label><Input id="rule-min" value={minSamples} onChange={e => setMinSamples(e.target.value)}/></div><label className="notification-checkbox"><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)}/>启用该规则</label><div className="field"><Label htmlFor="rule-reason">变更原因</Label><Input id="rule-reason" value={reason} onChange={e => setReason(e.target.value)}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<div className="identity-form-actions"><Button type="button" variant="outline" onClick={onClose}>取消</Button><Button type="submit">核对并继续</Button></div></form></CardContent></Card>
}
