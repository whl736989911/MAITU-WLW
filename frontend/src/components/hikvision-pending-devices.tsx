import { useState } from 'react'
import { hikvisionApi, type HikvisionDevice, type HikvisionStatus } from '@/lib/hikvision'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import type { PlaceNode, ScopeUnit } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Card, CardContent } from './ui/card'
import { ErrorNotice, Loading, Pager } from './common'

const PAGE_SIZE = 20
export function HikvisionPendingDevices({ openDevice }: { openDevice: (device: { id: string; tenant_id?: string }) => void }) {
  const permissions = useSession(state => state.permissions)
  const canRead = permissions.includes('integrations:read'), canWrite = permissions.includes('integrations:write')
  const status = useResource('hikvision-status', signal => hikvisionApi.status(signal), canRead)
  const [page, setPage] = useState(1), [refreshing, setRefreshing] = useState(false), [error, setError] = useState<unknown>()
  const devices = useResource(`hikvision-devices:${page}`, signal => hikvisionApi.devices(page, PAGE_SIZE, signal), canRead)
  const refresh = () => { status.refresh(); devices.refresh() }
  async function synchronize() {
    if (refreshing) return
    if (!window.confirm('将从海康云只读拉取设备与事件并重建本地来源映射；不会创建单位、不会自动归属设备，也不会改写本地报警。继续？')) return
    setRefreshing(true); setError(undefined)
    try { await hikvisionApi.sync(); refresh() }
    catch (cause) { setError(cause) }
    finally { setRefreshing(false) }
  }
  if (!canRead) return null
  return <Card className="list-card"><CardContent>
    <div className="list-toolbar"><div><h2>海康待分配设备</h2><p className="muted-note">仅显示真实海康开放 API 观察到的设备；同步不会创建本地单位，归属必须由有权限的操作者明确指定。</p></div>{canWrite && <div className="identity-row-actions"><Button type="button" disabled={refreshing || !status.data?.configured || !status.data?.enabled} onClick={() => void synchronize()}>{refreshing ? '同步中…' : '同步海康设备'}</Button></div>}</div>
    {status.loading && !status.data ? <Loading/> : status.error != null ? <ErrorNotice error={status.error} retry={status.refresh}/> : status.data && <HikvisionStatusView status={status.data}/>}
    {error != null && <ErrorNotice error={error}/>}
    {devices.loading && !devices.data ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : devices.data ? <>
      {devices.data.items.length === 0 ? <p className="muted-note">当前没有已观察到的海康设备。</p> : <div className="table-wrap"><table><thead><tr><th>来源设备</th><th>来源状态</th><th>观测</th><th>传感器</th><th>本地归属</th><th>操作</th></tr></thead><tbody>{devices.data.items.map(device => <HikvisionDeviceRow key={device.external_id} device={device} canWrite={canWrite} onAssigned={refresh} onOpen={openDevice}/>)}</tbody></table></div>}
      <Pager page={devices.data.page} size={devices.data.size} total={devices.data.total} onChange={next => setPage(next)}/>
    </> : null}
  </CardContent></Card>
}
function HikvisionStatusView({ status }: { status: HikvisionStatus }) {
  return <div className="table-note">
    <p>连接配置：{status.configured ? '已配置' : '未配置'} · 接入状态：{status.enabled ? '启用' : '停用'} · 轮询间隔：{status.poll_interval_seconds} 秒</p>
    <p>海康设备：{status.devices_total} · 待分配：{status.unassigned_total} · 已关联：{status.linked_total} · 待分配事件：{status.unassigned_events}</p>
    {status.quarantined_events > 0 && <p>归属时间隔离：{status.quarantined_events} 条。早于接管时间或来源时间不明的记录保留原文，不作为当前归属的新报警。</p>}
    {status.mapping_errors > 0 && <p role="alert">映射错误：{status.mapping_errors} 条；同步拉取成功不代表数据已完成映射或应用。</p>}
    <p>上次同步：{status.last_sync_at ?? '暂无记录'} · 上次成功：{status.last_success_at ?? '暂无记录'}</p>
    {status.last_error && <p role="status">最近错误：{status.last_error}</p>}
    {status.streams.map((stream, index) => <p key={`${stream.kind}:${index}`}>流 {stream.kind}：{stream.total} · 最近成功 {stream.last_success_at ?? '暂无记录'}{stream.last_error ? ` · 错误 ${stream.last_error}` : ''}</p>)}
  </div>
}
function HikvisionDeviceRow({ device, canWrite, onAssigned, onOpen }: { device: HikvisionDevice; canWrite: boolean; onAssigned: () => void; onOpen: (device: { id: string; tenant_id?: string }) => void }) {
  const [tenantId, setTenantId] = useState(''), [placeId, setPlaceId] = useState(''), [existingDeviceId, setExistingDeviceId] = useState(''), [saving, setSaving] = useState(false), [error, setError] = useState<unknown>()
  const scope = useResource(`hikvision-assignment-scope:${device.external_id}`, signal => api.scopeTree(signal), canWrite && device.assignment_state === 'UNASSIGNED')
  const units: ScopeUnit[] = scope.data ?? [], places: PlaceNode[] = units.find(unit => unit.id === tenantId)?.places ?? []
  async function assign() {
    if (!canWrite || !tenantId || saving) return
    if (!window.confirm(`将海康设备 ${device.name}（${device.external_id}）归属到所选本地单位${existingDeviceId.trim() ? `并关联已有设备 ${existingDeviceId.trim()}` : '并创建本地设备'}；不会创建或删除云端记录。继续？`)) return
    setSaving(true); setError(undefined)
    try { await hikvisionApi.assign(device.external_id, { expected_revision: device.revision, tenant_id: tenantId, place_id: existingDeviceId.trim() ? null : placeId || null, existing_device_id: existingDeviceId.trim() || null }); onAssigned() }
    catch (cause) { setError(cause) }
    finally { setSaving(false) }
  }
  return <tr><td>{device.name}<div className="table-sub mono">来源 ID：{device.external_id}</div><div className="table-sub">序列号：{device.serial ?? '未提供'} · 型号：{device.model ?? '未提供'}</div><div className="table-sub">类型：{device.source_device_type ?? '未提供'} · 来源更新时间：{device.source_updated_at ?? '未提供'}</div></td><td>{device.source_status ?? '未知'} · {device.online === null ? '在线状态未知' : device.online ? '在线' : '离线'}</td><td>{device.last_observed_at ?? '尚无观测'}</td><td>{device.sensor_count}</td><td>{device.assignment_state === 'LINKED' ? <><div>本地设备：{device.local_device_id ?? '未知'}</div><div className="table-sub">单位：{device.local_tenant_id ?? '未知'} · 位置：{device.local_place_id ?? '未指定'}</div></> : '待分配'}</td><td>{device.assignment_state === 'LINKED' ? device.local_device_id && <Button type="button" variant="outline" size="sm" onClick={() => onOpen({ id: device.local_device_id!, tenant_id: device.local_tenant_id ?? undefined })}>查看本地设备</Button> : canWrite ? <div className="form-stack"><label className="field">明确选择本地单位<select aria-label={`为${device.name}选择本地单位`} className="native-select" value={tenantId} onChange={event => { setTenantId(event.target.value); setPlaceId('') }}><option value="">请选择单位</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.name}</option>)}</select></label>{scope.loading && <small>读取可管理单位与位置…</small>}{scope.error != null && <ErrorNotice error={scope.error} retry={scope.refresh}/>}<label className="field">位置（可选）<select aria-label={`为${device.name}选择位置`} className="native-select" value={placeId} onChange={event => setPlaceId(event.target.value)}><option value="">不指定位置</option>{places.map(place => <option key={place.id} value={place.id}>{place.name}</option>)}</select></label><label className="field">已存在本地设备 ID（可选；留空会创建真实设备）<Input aria-label={`为${device.name}填写已有设备 ID`} value={existingDeviceId} onChange={event => setExistingDeviceId(event.target.value)} /></label><Button type="button" disabled={!tenantId || saving || scope.loading || !units.length} onClick={() => void assign()}>{saving ? '分配中…' : '确认分配'}</Button>{error != null && <ErrorNotice error={error}/>}</div> : '无分配权限'}</td></tr>
}
