import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { ArrowLeft, BellRing, MapPin, SlidersHorizontal } from 'lucide-react'
import { Alert, Card, Descriptions, Table, Tabs } from 'antd'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { unitSettingsApi, type UnitParameterSettings, type UnitParameterChange } from '@/lib/unit-settings'
import { integerToken } from '@/lib/parameter-json'
import { dateTime } from '@/lib/format'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { CustomerPlaceManager } from '@/components/customer-place-manager'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { offlineThresholdPath, useUnitParameterWrite } from '@/customer/unit-parameter-write'
import '../customer/customer.css'

export function UnitSettingsPage({ notifications }: { notifications: ReactNode }) {
  const { audience, permissions, user, navigate } = useSession()
  if (audience !== 'customer' || !user?.tenant_id) return <Empty title="此入口只维护当前客户单位"/>
  const items: { key: string; label: ReactNode; children: ReactNode }[] = []
  if (permissions.includes('units:read')) items.push({ key: 'places', label: <><MapPin size={16}/>位置</>, children: <CustomerPlaceManager/> })
  if (permissions.includes('unit-parameters:read')) items.push({ key: 'parameters', label: <><SlidersHorizontal size={16}/>业务参数</>, children: <ParameterSettings tenantId={user.tenant_id}/> })
  if (permissions.includes('unit-notifications:read')) items.push({ key: 'notifications', label: <><BellRing size={16}/>通知策略</>, children: notifications })
  return <section className="customer-hub unit-settings-grid">
    <PageHeading eyebrow="单位维护" title="本单位设置"><Button type="button" variant="outline" onClick={() => navigate('my-profile')}><ArrowLeft/>返回我的</Button></PageHeading>
    {items.length ? <Tabs className="customer-unit-tabs" destroyOnHidden items={items}/> : <Empty title="当前账号未获单位设置权限"/>}
  </section>
}

function ParameterSettings({ tenantId }: { tenantId: string }) {
  const canWrite = useSession(s => s.permissions.includes('unit-parameters:write'))
  const [notice, setNotice] = useState('')
  const [page, setPage] = useState(1)
  const current = useResource(`customer-unit-parameters:${tenantId}`, signal => unitSettingsApi.parameters(signal))
  const history = useResource(`customer-unit-parameter-history:${tenantId}:${page}`, signal => unitSettingsApi.history(page, signal))
  const completion = useUnitParameterWrite(s => s.completion)
  useEffect(() => {
    if (!completion || !canWrite || completion.result.tenant_id !== tenantId) return
    useUnitParameterWrite.getState().dismissCompletion()
    setNotice('单位规则已保存。后续查询使用当前有效规则，未修改设备参数，也未发送通知。')
    current.refresh()
    history.refresh()
  }, [completion, canWrite, tenantId, current.refresh, history.refresh])
  return <section className="unit-settings-grid">
    <Alert type="info" showIcon title="通信阈值只影响平台判断" description="阈值用于平台判断通信状态与派生离线事件。它不会改写原始上报，不会修改设备或厂商参数；上报超时也不等于已确认离线。"/>
    {notice && <Alert type="success" showIcon title={notice}/>}
    {current.loading ? <Loading/> : current.error != null ? <ErrorNotice error={current.error} retry={current.refresh}/> : current.data && <ParameterEditor key={`${tenantId}:${current.data.policy.revision}`} settings={current.data} canWrite={canWrite} refresh={current.refresh}/>}
    <Card title="单位参数变更记录">
      {history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data && <><Table<UnitParameterChange> rowKey="id" dataSource={history.data.items} pagination={false} scroll={{ x: 600 }} columns={[
        { title: '时间', dataIndex: 'created_at', render: value => dateTime(value) },
        { title: '操作', dataIndex: 'action', render: value => value === 'CLEAR' ? '恢复继承' : '设置覆盖' },
        { title: '原值', dataIndex: 'previous_value', render: value => value === null ? '继承' : `${value} 分钟` },
        { title: '新值', dataIndex: 'value', render: value => value === null ? '继承' : `${value} 分钟` },
        { title: '版本', dataIndex: 'revision_after' },
      ]}/><Pager page={history.data.page} size={history.data.size} total={history.data.total} onChange={setPage}/></>}
    </Card>
  </section>
}
function ParameterEditor({ settings, canWrite, refresh }: { settings: UnitParameterSettings; canWrite: boolean; refresh: () => void }) {
  const { definition, policy, allowed_maximum: maximum } = settings
  const [value, setValue] = useState(String(policy.has_override ? policy.value : policy.effective.value))
  const [reason, setReason] = useState('')
  const [proposal, setProposal] = useState<{ kind: 'SET' | 'CLEAR'; value: number | null; reason: string } | null>(null)
  const [validation, setValidation] = useState<unknown>()
  const write = useUnitParameterWrite()
  const resolving = write.phase === 'PENDING' || write.phase === 'UNCERTAIN' || write.phase === 'CONFLICT'
  function preview(kind: 'SET' | 'CLEAR') {
    setValidation(undefined)
    try {
      const next = kind === 'SET' ? integerToken(value) : null
      if (next != null && (next < definition.min || next > maximum)) throw new Error(`本单位允许 ${definition.min} 至 ${maximum} ${definition.unit}`)
      if (!reason.trim()) throw new Error('请填写变更原因')
      setProposal({ kind, value: next, reason: reason.trim() })
    } catch (error) { setValidation(error) }
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!proposal || resolving) return
    const clear = proposal.kind === 'CLEAR'
    void write.perform({
      path: `${offlineThresholdPath}${clear ? '/clear' : ''}`,
      method: clear ? 'POST' : 'PUT',
      tenant: policy.tenant_id,
      body: { ...(clear ? {} : { value: proposal.value }), expected_revision: policy.revision, reason: proposal.reason },
      display: `本单位 · ${clear ? '恢复平台继承' : `覆盖为 ${proposal.value} ${definition.unit}`} · 原因：${proposal.reason}`,
    })
  }
  return <Card title={definition.name} extra={<span>版本 {policy.revision}</span>}>
    <Descriptions column={1} size="small" items={[
      { key: 'inherited', label: '平台继承值', children: `${definition.global_value} ${definition.unit}` },
      { key: 'override', label: '本单位覆盖', children: policy.has_override ? `${policy.value} ${definition.unit}` : '无，使用继承值' },
      { key: 'effective', label: '当前最终值', children: `${policy.effective.value} ${definition.unit} · ${policy.effective.source_scope === 'TENANT' ? '本单位' : '平台'}版本 ${policy.effective.source_revision}` },
      { key: 'bounds', label: '单位允许范围', children: `${definition.min}—${maximum} ${definition.unit}` },
      { key: 'consumer', label: '生效方式', children: '后续查询实时读取，不修改上报事实或已发生结果' },
    ]}/>
    {!canWrite ? <Alert type="info" title="当前为只读；没有本单位参数写入权限"/> : resolving ? <section className="sensitive-confirmation" aria-live="polite">
      <h3>{write.phase === 'CONFLICT' ? '参数版本已变更' : write.phase === 'PENDING' ? '正在等待服务端确认' : '写入结果未确认'}</h3>
      <p>{write.intent?.display}</p>
      {write.error != null && <ErrorNotice error={write.error}/>}
      {write.phase === 'CONFLICT' ? <Button type="button" variant="outline" onClick={() => { write.reload(); refresh() }}>重新读取参数</Button> : <Button type="button" onClick={() => void write.retry()} disabled={write.phase === 'PENDING'}>用原请求确认结果</Button>}
    </section> : proposal ? <form className="sensitive-confirmation" onSubmit={submit}>
      <h3>{proposal.kind === 'CLEAR' ? '核对恢复继承' : '核对单位参数覆盖'}</h3>
      <p>当前：{policy.effective.value} {definition.unit} · {proposal.kind === 'CLEAR' ? `恢复平台继承（当前平台值 ${definition.global_value} ${definition.unit}）` : `改为 ${proposal.value} ${definition.unit}`}</p>
      <p>原因：{proposal.reason}</p>
      {write.error != null && <ErrorNotice error={write.error}/>}
      <div className="customer-controls">
        <Button type="button" variant="outline" onClick={() => { write.edit(); setProposal(null) }}>返回修改</Button>
        <Button type="submit">{proposal.kind === 'CLEAR' ? '确认恢复继承' : '确认保存覆盖'}</Button>
      </div>
    </form> : <form className="form-stack" onSubmit={event => { event.preventDefault(); preview('SET') }}>
      <div className="field"><Label htmlFor="unit-offline-value">本单位覆盖值（{definition.unit}）</Label><Input id="unit-offline-value" inputMode="numeric" value={value} onChange={event => { setValue(event.target.value); setValidation(undefined) }}/></div>
      <div className="field"><Label htmlFor="unit-parameter-reason">变更原因</Label><Input id="unit-parameter-reason" value={reason} maxLength={1000} onChange={event => { setReason(event.target.value); setValidation(undefined) }}/></div>
      {validation != null && <ErrorNotice error={validation}/>}
      <div className="customer-controls"><Button type="submit">预览修改</Button><Button type="button" variant="outline" disabled={!policy.has_override} onClick={() => preview('CLEAR')}>恢复继承</Button></div>
    </form>}
  </Card>
}
