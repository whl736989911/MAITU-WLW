import { useEffect, useState, type FormEvent } from 'react'
import { request } from '@/lib/api'
import { useDomainWrite, type DomainWrite } from '@/lib/domain-write'
import { useResource } from '@/lib/use-resource'
import {
  ACTION_LABELS, CONTROLLER_ACTIONS, controllerApi, type ControllerResource, type ServiceField,
} from '@/lib/controllers'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { DeviceSelector, type AssetReference } from '@/components/asset-picker'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice, Loading, Pager, RefreshButton } from '@/components/common'

function WriteFields({ write, label }: { write: DomainWrite; label: string }) {
  return <>
    <div className="field"><Label htmlFor={`${label}-reason`}>变更原因</Label><Input id={`${label}-reason`} value={write.reason} maxLength={1000} onChange={event => write.setReason(event.target.value)}/></div>
    {write.error != null && <ErrorNotice error={write.error} retry={() => write.setError(undefined)}/>}
  </>
}

function fieldValue(field: ServiceField, value: string | undefined): unknown {
  if (value === undefined || value === '') {
    if (field.required) throw new Error(`请填写 ${field.label}`)
    return undefined
  }
  if (field.type === 'integer') {
    if (!/^-?\d{1,18}$/.test(value)) throw new Error(`${field.label} 必须是整数`)
    return Number(value)
  }
  if (field.type === 'number') {
    const parsed = Number(value)
    if (!Number.isFinite(parsed)) throw new Error(`${field.label} 必须是数字`)
    return parsed
  }
  if (field.type === 'boolean') {
    if (value !== 'true' && value !== 'false') throw new Error(`${field.label} 必须明确为 true/false`)
    return value === 'true'
  }
  return value
}

export function ControllerResourcesPanel({ tenantId, canWrite, canReadDevices, canReadPlaces }: {
  tenantId: string; canWrite: boolean; canReadDevices: boolean; canReadPlaces: boolean
}) {
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [draft, setDraft] = useState(''), [includeRetired, setIncludeRetired] = useState(false)
  const list = useResource(`controllers:${tenantId}:${page}:${keyword}:${includeRetired}`,
    signal => controllerApi.list(tenantId, page, { keyword, include_retired: includeRetired }, signal))
  const [selectedId, setSelectedId] = useState('')
  const detail = useResource(`controller-detail:${tenantId}:${selectedId}`, signal => controllerApi.detail(tenantId, selectedId, signal), !!selectedId)
  const write = useDomainWrite()
  const [formOpen, setFormOpen] = useState(false), [editing, setEditing] = useState<ControllerResource | null>(null)
  const [name, setName] = useState(''), [action, setAction] = useState('start'), [serviceKey, setServiceKey] = useState('')
  const [device, setDevice] = useState<AssetReference | undefined>()
  const [argumentsView, setArgumentsView] = useState<Record<string, string>>({})
  const capabilities = useResource(`controller-form-caps:${tenantId}:${device?.id ?? ''}`,
    signal => controllerApi.capabilities(tenantId, device!.id, signal), !!device && formOpen)
  const selectedService = capabilities.data?.services.find(service => service.service_key === serviceKey)
  const fields = selectedService?.input_schema.fields ?? []

  useEffect(() => { setPage(1) }, [tenantId, keyword, includeRetired])
  useEffect(() => {
    if (!list.data) return
    if (selectedId && !list.data.items.some(item => item.id === selectedId)) setSelectedId('')
  }, [list.data, selectedId])
  useEffect(() => {
    setArgumentsView(previous => {
      const next = { ...previous }
      for (const field of fields) if (field.type === 'boolean' && next[field.key] === undefined) next[field.key] = 'false'
      return next
    })
  }, [serviceKey])

  const reload = () => { list.refresh(); if (selectedId) detail.refresh() }
  function search(event: FormEvent) { event.preventDefault(); setKeyword(draft.trim()); setPage(1) }
  const rows = list.data?.items ?? []
  const record = detail.data?.resource ?? null

  function startCreate() {
    setEditing(null); setName(''); setAction('start'); setServiceKey(''); setDevice(undefined); setArgumentsView({}); setFormOpen(true); write.reset()
  }
  function startEdit(item: ControllerResource) {
    setEditing(item); setName(item.name); setAction(item.action); setServiceKey(item.service_key); setDevice({ id: item.device_id, name: item.device_name, eui: item.device_eui })
    const parsed: Record<string, string> = {}
    try {
      const value: unknown = JSON.parse(item.arguments_json || '{}')
      if (value && typeof value === 'object' && !Array.isArray(value))
        for (const [key, raw] of Object.entries(value as Record<string, unknown>)) parsed[key] = String(raw)
    } catch { /* stored arguments remain unreadable in the form; save without changing them is blocked below */ }
    setArgumentsView(parsed); setFormOpen(true); write.reset()
  }
  function buildArguments(): string {
    const payload: Record<string, unknown> = {}
    for (const field of fields) {
      if (field.sensitive) continue
      const value = fieldValue(field, argumentsView[field.key])
      if (value !== undefined) payload[field.key] = value
    }
    return JSON.stringify(payload)
  }
  function submitForm(event: FormEvent) {
    event.preventDefault()
    let argumentsJson: string
    try { argumentsJson = buildArguments() } catch (failure) { write.setError(failure); return }
    if (editing) {
      const body = { expected_revision: editing.revision, name: name.trim(), service_key: serviceKey, arguments_json: argumentsJson, reason: write.reason.trim() }
      write.submit(['controller.update', editing.id, body], key => request(`/management/controller-resources/${encodeURIComponent(editing.id)}`, { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
        () => { setFormOpen(false); reload() })
      return
    }
    if (!device) { write.setError(new Error('请选择控制器所属真实设备')); return }
    const body = { name: name.trim(), device_id: device.id, action, service_key: serviceKey, arguments_json: argumentsJson, reason: write.reason.trim() }
    write.submit(['controller.create', body], key => request('/management/controller-resources', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setFormOpen(false); reload() })
  }
  function toggleEnabled(item: ControllerResource) {
    const body = { expected_revision: item.revision, enabled: !item.enabled, reason: write.reason.trim() }
    write.submit(['controller.enabled', item.id, body], key => request(`/management/controller-resources/${encodeURIComponent(item.id)}/enabled`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }), reload)
  }
  function retire(item: ControllerResource) {
    const body = { expected_revision: item.revision, reason: write.reason.trim() }
    write.submit(['controller.retire', item.id, body], key => request(`/management/controller-resources/${encodeURIComponent(item.id)}/retire`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }), reload)
  }

  return <>
    <div className="list-toolbar"><h2>联动控制器</h2><div className="row-actions">
      <RefreshButton onClick={reload}/>
      {canWrite && <DataTransferActions kind="CONTROLLER_RESOURCES" tenantId={tenantId} filters={{ unit_ids: tenantId, place_ids: '' }}/>}
    </div></div>
    <form className="search-form" onSubmit={search}>
      <Input aria-label="搜索控制器" value={draft} maxLength={100} onChange={event => setDraft(event.target.value)} placeholder="名称 / 服务 / 设备"/>
      <Button type="submit" variant="outline">查询</Button>
      {canWrite && <label className="notification-checkbox"><input type="checkbox" checked={includeRetired} onChange={event => setIncludeRetired(event.target.checked)}/>含已停用</label>}
      {canWrite && <Button type="button" onClick={startCreate}>登记控制器关联</Button>}
    </form>
    {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : rows.length === 0 ? <Empty title="没有控制器关联">控制器关联必须指向真实设备上已发布的服务能力；不会根据名称推断继电器。</Empty> : <>
      <div className="table-wrap"><table><thead><tr><th>名称</th><th>设备</th><th>动作/服务</th><th>能力</th><th>启用</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{rows.map(item => <tr key={item.id}>
          <td>{item.name}<div className="table-sub mono">{item.id}</div></td>
          <td>{item.device_name ?? item.device_id}<div className="table-sub mono">{item.device_eui ?? item.device_id}</div></td>
          <td>{ACTION_LABELS[item.action] ?? item.action}<div className="table-sub mono">{item.service_key} · {item.driver_mode}</div></td>
          <td>{item.support_state} / {item.risk_level} / {item.result_kind}</td>
          <td>{item.enabled ? '已启用' : '未启用'}</td><td>{item.state === 'ACTIVE' ? '有效' : '已停用'}</td>
          <td><div className="row-actions"><Button type="button" size="sm" variant="outline" onClick={() => { setSelectedId(item.id); write.reset() }}>详情</Button>
            {canWrite && item.state === 'ACTIVE' && <><Button type="button" size="sm" variant="outline" onClick={() => startEdit(item)}>编辑</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => toggleEnabled(item)}>{item.enabled ? '停用' : '启用'}</Button>
              {!item.enabled && <Button type="button" size="sm" variant="outline" onClick={() => retire(item)}>停用关联</Button>}</>}</div></td>
        </tr>)}</tbody></table></div>
      <Pager page={list.data?.page ?? 1} size={list.data?.size ?? 20} total={list.data?.total ?? 0} onChange={setPage}/>
      {canWrite && <WriteFields write={write} label="controller-inline"/>}
    </>}

    {formOpen && <Card><CardContent><form onSubmit={submitForm}>
      <h2>{editing ? '编辑控制器关联' : '登记控制器关联'}</h2>
      <div className="field"><Label htmlFor="controller-name">名称</Label><Input id="controller-name" value={name} maxLength={200} onChange={event => setName(event.target.value)}/></div>
      {!editing && <>
        <div className="field"><Label htmlFor="controller-action">联动动作</Label><select id="controller-action" className="native-select" value={action} onChange={event => setAction(event.target.value)}>{CONTROLLER_ACTIONS.map(value => <option key={value} value={value}>{ACTION_LABELS[value] ?? value} · {value}</option>)}</select></div>
        <DeviceSelector audience="management" tenantId={tenantId} value={device?.id ?? ''} current={device}
          onChange={(_id, reference) => { setDevice(reference); setServiceKey(''); setArgumentsView({}) }} canReadDevices={canReadDevices} canReadPlaces={canReadPlaces}/>
      </>}
      {editing && <p className="muted-note">设备与动作不可在此修改；如需更正请停用关联后重新登记。</p>}
      {device && <div className="field"><Label htmlFor="controller-service">真实服务能力</Label>
        {capabilities.loading ? <Loading/> : capabilities.error != null ? <ErrorNotice error={capabilities.error} retry={capabilities.refresh}/> :
          <select id="controller-service" className="native-select" value={serviceKey} onChange={event => setServiceKey(event.target.value)}>
            <option value="">请选择当前设备已发布能力</option>
            {(capabilities.data?.services ?? []).map(service => <option key={service.service_key} value={service.service_key}>{service.display_name} · {service.service_key}{service.dispatch_ready ? '' : '（不可执行）'}</option>)}
          </select>}
        {capabilities.data && capabilities.data.notes.map(note => <p className="muted-note" key={note}>{note}</p>)}
      </div>}
      {selectedService && !selectedService.dispatch_ready && <div className="notice"><strong>当前能力不可用于自动联动</strong><p>{selectedService.dispatch_blocked_reason ?? selectedService.dispatch_blocked_code}</p></div>}
      {fields.length > 0 && <section aria-label="控制器参数">
        <h3>声明式参数</h3>
        {fields.map(field => <div className="field" key={field.key}>
          <Label htmlFor={`controller-arg-${field.key}`}>{field.label}{field.required ? '（必填）' : ''}{field.sensitive ? '（敏感字段禁止自动保存）' : ''}</Label>
          {field.type === 'boolean'
            ? <select id={`controller-arg-${field.key}`} className="native-select" disabled={field.sensitive} value={argumentsView[field.key] ?? 'false'} onChange={event => setArgumentsView(previous => ({ ...previous, [field.key]: event.target.value }))}><option value="false">false</option><option value="true">true</option></select>
            : <Input id={`controller-arg-${field.key}`} disabled={field.sensitive} value={argumentsView[field.key] ?? ''} onChange={event => setArgumentsView(previous => ({ ...previous, [field.key]: event.target.value }))} placeholder={field.sensitive ? '敏感字段不能保存到联动参数' : field.type}/>}
          <p className="muted-note mono">{field.key}{field.enum?.length ? ` · 可选：${field.enum.join(' / ')}` : ''}</p>
        </div>)}
      </section>}
      <p className="muted-note">只有已发布、已验证的 NORMAL 类型化 COMMAND 服务才能启用自动联动；工程类操作、只读查询和未验证能力会在启用时被具体拒绝。登记本身不会向设备下发任何命令。</p>
      <WriteFields write={write} label="controller-form"/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>{write.saving ? '处理中…' : editing ? '保存修改' : '登记关联'}</Button><Button type="button" variant="outline" onClick={() => { setFormOpen(false); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {record && detail.data && <Card><CardContent>
      <div className="list-toolbar"><h2>控制器关联详情 · {record.name}</h2><Button type="button" variant="outline" onClick={() => setSelectedId('')}>关闭</Button></div>
      <div className="detail-grid">
        <div><strong>设备</strong><p>{record.device_name ?? record.device_id}<br/><span className="mono">{record.device_id}</span></p></div>
        <div><strong>动作 / 服务</strong><p>{ACTION_LABELS[record.action] ?? record.action}<br/><span className="mono">{record.service_key}</span></p></div>
        <div><strong>驱动</strong><p>{record.driver_mode} · {record.driver_key}</p></div>
        <div><strong>存储能力</strong><p>{record.support_state} / {record.risk_level} / {record.result_kind}</p></div>
        <div><strong>启用</strong><p>{record.enabled ? '已启用' : '未启用'}</p></div>
      </div>
      <p className="muted-note">{detail.data.note}</p>
      <h3>当前设备能力</h3>
      {detail.data.capabilities.notes.map(note => <p className="muted-note" key={note}>{note}</p>)}
      {detail.data.capabilities.services.length === 0 ? <Empty title="当前无法读取能力"/> :
        <div className="table-wrap"><table><thead><tr><th>服务</th><th>类别/风险</th><th>支持</th><th>可执行</th><th>阻塞原因</th></tr></thead>
          <tbody>{detail.data.capabilities.services.map(service => <tr key={service.service_key}>
            <td>{service.display_name}<div className="table-sub mono">{service.service_key}</div></td>
            <td>{service.category} / {service.risk_level} / {service.result_kind}</td><td>{service.support_state}</td>
            <td>{service.dispatch_ready ? '是' : '否'}</td><td>{service.dispatch_blocked_reason ?? service.dispatch_blocked_code ?? ''}</td>
          </tr>)}</tbody></table></div>}
      <h3>最近设备命令</h3>
      {detail.data.commands.items.length === 0 ? <Empty title="没有命令记录">尚无入队命令；入队不等于设备已执行。</Empty> :
        <div className="table-wrap"><table><thead><tr><th>命令</th><th>服务</th><th>状态</th><th>风险/结果</th><th>创建</th><th>厂商码</th></tr></thead>
          <tbody>{detail.data.commands.items.map(command => <tr key={command.id}>
            <td className="mono">{command.id}</td><td>{command.service_key}</td><td>{command.state}</td>
            <td>{command.risk_level} / {command.result_kind}</td><td>{command.created_at}</td><td>{command.vendor_code ?? ''}</td>
          </tr>)}</tbody></table></div>}
      {canWrite && record.state === 'ACTIVE' && <><WriteFields write={write} label="controller-detail-actions"/><div className="row-actions">
        <Button type="button" variant="outline" onClick={() => toggleEnabled(record)}>{record.enabled ? '停用自动联动' : '启用自动联动'}</Button>
        {!record.enabled && <Button type="button" variant="outline" onClick={() => retire(record)}>停用关联</Button>}
      </div></>}
    </CardContent></Card>}
  </>
}
