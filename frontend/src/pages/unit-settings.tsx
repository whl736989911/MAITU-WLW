import { useState, type FormEvent, type ReactNode } from 'react'
import { Alert, Card, Descriptions, Table, Tabs } from 'antd'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { unitSettingsApi, type UnitParameterSettings, type UnitParameterChange } from '@/lib/unit-settings'
import { integerToken } from '@/lib/parameter-json'
import { dateTime } from '@/lib/format'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { useMutationAction } from '@/components/identity-common'
import { CustomerPlaceManager } from '@/components/customer-place-manager'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export function UnitSettingsPage({ notifications }: { notifications: ReactNode }) {
  const { audience, permissions, user } = useSession()
  if (audience !== 'customer' || !user?.tenant_id) return <Empty title="此入口只维护当前客户单位"/>
  const items = []
  if (permissions.includes('units:read')) items.push({ key: 'places', label: '本单位位置', children: <CustomerPlaceManager/> })
  if (permissions.includes('unit-parameters:read')) items.push({ key: 'parameters', label: '单位业务参数', children: <ParameterSettings/> })
  if (permissions.includes('unit-notifications:read')) items.push({ key: 'notifications', label: '联系人与收件策略', children: notifications })
  return <section className="unit-settings-grid"><PageHeading eyebrow="UNIT · 单位设置" title="本单位设置" description="管理本单位位置、业务参数与通知；各项操作按当前授权开放。"/>{items.length ? <Tabs destroyOnHidden items={items}/> : <Empty title="当前账号未获单位设置权限"/>}</section>
}

function ParameterSettings() {
  const { user, permissions } = useSession()
  const [notice, setNotice] = useState('')
  const [page, setPage] = useState(1)
  const current = useResource(`customer-unit-parameters:${user?.tenant_id}`, signal => unitSettingsApi.parameters(signal))
  const history = useResource(`customer-unit-parameter-history:${user?.tenant_id}:${page}`, signal => unitSettingsApi.history(page, signal))
  function saved() { setNotice('单位覆盖已保存，查询消费者将读取当前有效策略；未下发任何设备参数或通知。'); current.refresh(); history.refresh() }
  return <section className="unit-settings-grid">
    <Alert type="info" showIcon title="业务规则与设备内部参数是两回事" description="当前开放的是已有设备列表/概况查询消费者的通信阈值。不会修改海康或设备内部探测算法，不代表取得原始心跳，也不自动发送离线通知。"/>
    {notice && <Alert type="success" showIcon title={notice}/>}
    {current.loading ? <Loading/> : current.error != null ? <ErrorNotice error={current.error} retry={current.refresh}/> : current.data && <ParameterEditor key={`${user?.tenant_id}:${current.data.policy.revision}`} settings={current.data} canWrite={permissions.includes('unit-parameters:write')} saved={saved} refresh={current.refresh}/>}
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
function ParameterEditor({ settings, canWrite, saved, refresh }: { settings: UnitParameterSettings; canWrite: boolean; saved: () => void; refresh: () => void }) {
  const { definition, policy, allowed_maximum: maximum } = settings
  const [value, setValue] = useState(String(policy.has_override ? policy.value : policy.effective.value))
  const [reason, setReason] = useState('')
  const [kind, setKind] = useState<'SET' | 'CLEAR'>('SET')
  const [validation, setValidation] = useState<unknown>()
  const action = useMutationAction()
  function save(next: 'SET' | 'CLEAR') {
    setValidation(undefined)
    try {
      if (next === 'SET') {
        const parsed = integerToken(value)
        if (parsed < definition.min || parsed > maximum) throw new Error(`本单位允许 ${definition.min} 至 ${maximum} ${definition.unit}；不能突破平台当前上限`)
      }
      if (!reason.trim()) throw new Error(next === 'SET' ? '请填写变更原因' : '请先填写恢复继承的原因')
      setKind(next)
      void action.run((key, signal) => unitSettingsApi.change(next, value, policy.revision, reason.trim(), key, signal), saved)
    } catch (error) { setValidation(error) }
  }
  return <Card title={definition.name} extra={<span>版本 {policy.revision}</span>}>
    <Descriptions column={1} size="small" items={[
      { key: 'inherited', label: '平台继承值', children: `${definition.global_value} ${definition.unit}` },
      { key: 'override', label: '本单位覆盖', children: policy.has_override ? `${policy.value} ${definition.unit}` : '无，使用继承值' },
      { key: 'effective', label: '当前最终值', children: `${policy.effective.value} ${definition.unit} · ${policy.effective.source_scope === 'TENANT' ? '本单位' : '平台'}版本 ${policy.effective.source_revision}` },
      { key: 'bounds', label: '单位允许范围', children: `${definition.min}—${maximum} ${definition.unit}` },
      { key: 'consumer', label: '生效方式', children: '后续查询实时读取，不修改上报事实或已发生结果' },
    ]}/>
    {canWrite ? <form className="form-stack" onSubmit={event => { event.preventDefault(); save('SET') }}>
      <div className="field"><Label htmlFor="unit-offline-value">本单位覆盖值（{definition.unit}）</Label><Input id="unit-offline-value" inputMode="numeric" value={value} disabled={action.busy} onChange={event => { action.touch(); setValue(event.target.value); setValidation(undefined) }}/></div>
      <div className="field"><Label htmlFor="unit-parameter-reason">变更原因</Label><Input id="unit-parameter-reason" value={reason} maxLength={1000} disabled={action.busy} onChange={event => { action.touch(); setReason(event.target.value); setValidation(undefined) }}/></div>
      {validation != null && <ErrorNotice error={validation}/>}
      <div className="identity-form-actions"><Button type="submit" disabled={action.busy}>预览修改</Button><Button type="button" variant="outline" disabled={action.busy || !policy.has_override} onClick={() => save('CLEAR')}>恢复继承</Button></div>
    </form> : <Alert type="info" title="当前为只读；没有本单位参数写入权限"/>}
    
  </Card>
}
