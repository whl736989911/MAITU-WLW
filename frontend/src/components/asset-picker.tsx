import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Select, TreeSelect } from 'antd'
import { api } from '@/lib/api'
import { customerAssetsApi } from '@/lib/customer-assets'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { placePath, placeTreeOptions } from '@/lib/place-tree'
import { scopeTargetTenantId } from '@/lib/operation-target'
import type { Device } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Empty, ErrorNotice, Loading } from './common'

export interface AssetReference {
  id: string
  name?: string | null
  eui?: string | null
  location?: string | null
  tenantId?: string | null
}

const PAGE_SIZE = 20

/** Explicit target unit. Management selects a granted unit; customer is fixed to the session unit. */
export function TargetUnitSelect({ audience, permissions, value, onChange, disabled = false }: {
  audience: 'management' | 'customer'; permissions: string[]
  value: string; onChange: (unit: string) => void; disabled?: boolean
}) {
  const canReadUnits = audience === 'management' && permissions.includes('tenants:read')
  const units = useResource('asset-target-units', signal => api.tenants(signal), canReadUnits)
  const sessionTenant = useSession(s => s.user?.tenant_id ?? '')
  const { scopeInitialized, scopeTenantIds, scopePlaceIds, scopeTreeRevision } = useSession()
  const tree = useResource(`asset-target-scope:${scopeTreeRevision}`, signal => api.scopeTree(signal), audience === 'management' && scopeInitialized && scopePlaceIds.length > 0)
  const initialized = useRef(false)
  const inferred = scopeInitialized
    ? scopePlaceIds.length ? scopeTargetTenantId(tree.data ?? [], scopeTenantIds, scopePlaceIds)
      : scopeTenantIds.length === 1 && (!canReadUnits || units.data?.items.some(unit => unit.id === scopeTenantIds[0])) ? scopeTenantIds[0] : undefined
    : undefined
  useEffect(() => {
    if (value) initialized.current = true
    if (audience !== 'management' || disabled || initialized.current || !inferred) return
    initialized.current = true
    onChange(inferred)
  }, [audience, disabled, inferred, value, onChange])
  if (audience === 'customer') return <div className="field"><Label htmlFor="asset-target-unit">目标单位（会话固定）</Label><p id="asset-target-unit" className="muted-note">{sessionTenant}</p></div>
  if (canReadUnits) return <div className="field"><Label htmlFor="asset-target-unit">目标单位</Label>
    <Select id="asset-target-unit" aria-label="目标单位" showSearch optionFilterProp="label" disabled={disabled} loading={units.loading}
      value={value || undefined} placeholder="明确选择本次写入的已授权单位"
      options={(units.data?.items ?? []).map(unit => ({ value: unit.id, label: `${unit.name} · ${unit.id}` }))}
      onChange={onChange}/>
    {units.error != null && <ErrorNotice error={units.error} retry={units.refresh}/>}
  </div>
  return <div className="field"><Label htmlFor="asset-target-unit">目标单位（高级：单位ID）</Label>
    <Input id="asset-target-unit" value={value} maxLength={64} disabled={disabled} onChange={event => onChange(event.target.value)} placeholder="输入已授权单位ID；服务端会核验grant"/>
    <p className="muted-note">当前账户没有单位目录读取权限，可输入已授权单位ID；服务端仍会核验本次目标。不会代入其他操作的单位。</p>
  </div>
}

/** Server-paged device search; no first-row selection and no bulk preload. */
export function DeviceSelector({ audience, tenantId, value, current, onChange, canReadDevices, canReadPlaces, disabled = false }: {
  audience: 'management' | 'customer'; tenantId?: string; value: string; current?: AssetReference
  onChange: (id: string, reference?: AssetReference) => void; canReadDevices: boolean; canReadPlaces: boolean; disabled?: boolean
}) {
  const session = useSession()
  const [draft, setDraft] = useState(''), [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1), [placeId, setPlaceId] = useState('')
  useEffect(() => { setPage(1); setKeyword(''); setDraft(''); setPlaceId('') }, [tenantId, audience])
  const places = useResource(`asset-device-places:${audience}:${tenantId ?? 'session'}`,
    signal => audience === 'customer' ? customerAssetsApi.places(signal) : api.places(signal, [tenantId!], [], true),
    canReadDevices && canReadPlaces && (audience === 'customer' || !!tenantId))
  const enabled = canReadDevices && (audience === 'customer' || !!tenantId)
  const devices = useResource(`asset-devices:${tenantId ?? 'session'}:${page}:${keyword}:${placeId}`,
    signal => api.devices(page, keyword, PAGE_SIZE, signal, undefined, placeId || undefined,
      audience === 'management' ? tenantId : undefined, undefined, undefined), enabled)
  const rows = (devices.data?.items ?? []).filter(device => !device.tenant_id || !tenantId || device.tenant_id === tenantId)
  const selected = rows.find(device => device.id === value) ?? null
  const reference: AssetReference | undefined = selected ? referenceOf(selected) : (value ? (current?.id === value ? current : { id: value }) : undefined)
  function search() { setKeyword(draft.trim()); setPage(1) }
  function onKey(event: KeyboardEvent<HTMLInputElement>) { if (event.key !== 'Enter') return; event.preventDefault(); event.stopPropagation(); search() }
  if (!canReadDevices) return <div className="field"><Label htmlFor="asset-device-id">设备（高级：设备ID）</Label>
    <Input id="asset-device-id" value={value} maxLength={64} disabled={disabled} onChange={event => onChange(event.target.value)} placeholder="没有device:read，仅可输入已授权设备ID"/>
    {current?.id === value && <p className="muted-note">当前关联：{current.name ?? value}{current.eui ? ` · ${current.eui}` : ''}（{value}）</p>}
    <p className="muted-note">当前账户没有设备读取权限，不能浏览设备名称/位置；服务端会核验设备、单位和当前范围。{value && <Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={() => onChange('')}>清除</Button>}</p>
  </div>
  return <section aria-label="设备选择" className="form-stack" style={{ minWidth: 0 }}>
    {reference && <div className="notice" style={{ overflowWrap: 'anywhere' }}>
      <strong>已选设备</strong>
      <p>{reference.name?.trim() || '未命名设备'}{reference.eui ? ` · ${reference.eui}` : ''}</p>
      <p>{reference.location?.trim() || (reference.id === value ? '未在当前页加载的位置' : '')} · {reference.id}{reference.tenantId ? ` · 单位 ${reference.tenantId}` : ''}</p>
      <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onChange('')}>清除选择</Button>
    </div>}
    {audience === 'management' && !tenantId ? <Empty title="请先选择目标单位">设备列表只查询明确目标单位的授权设备，不会预加载全部单位。</Empty> : <>
      <div className="search-form" style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Input aria-label="搜索设备" value={draft} maxLength={100} disabled={disabled} onChange={event => setDraft(event.target.value)} onKeyDown={onKey} placeholder="设备名称 / EUI / 地址"/>
        <Button type="button" variant="outline" disabled={disabled || !enabled} onClick={search}>查询设备</Button>
        {canReadPlaces && <div style={{ minWidth: 220 }}>
          {places.loading ? <Loading/> : places.error != null ? <ErrorNotice error={places.error} retry={places.refresh}/> : <TreeSelect aria-label="按位置筛选设备" style={{ width: '100%' }} allowClear showSearch treeNodeFilterProp="title"
            value={placeId || undefined} placeholder="按位置筛选（可空）" treeData={placeTreeOptions(places.data ?? [])}
            onChange={next => { setPlaceId(typeof next === 'string' ? next : ''); setPage(1) }}/>}
        </div>}
      </div>
      {!enabled ? <Empty title="无法浏览设备">当前目标单位未确认，或没有设备读取权限。</Empty> : devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : devices.data ? <>
        <p className="muted-note">共 {devices.data.total} 条 · 当前页 {rows.length} 条 · 每页 {devices.data.size} 条；不会自动选第一行。</p>
        {rows.length === 0 ? <Empty title="没有匹配设备"/> : <div style={{ display: 'grid', gap: 8 }}>
          {rows.map(device => <article key={device.id} style={{ border: '1px solid #e3e8db', borderRadius: 8, padding: 10, overflowWrap: 'anywhere' }}>
            <p><strong>{device.name?.trim() || '未命名设备'}</strong>{device.device_eui ? ` · ${device.device_eui}` : ''}</p>
            <p>{device.place_name?.trim() || device.address?.trim() || '位置未提供'} · {device.id}</p>
            <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onChange(device.id, referenceOf(device))}>选择此设备</Button>
          </article>)}
        </div>}
        <div className="version-actions"><Button type="button" variant="outline" size="sm" disabled={disabled || devices.data.page <= 1} onClick={() => setPage(current => Math.max(1, current - 1))}>上一页</Button><Button type="button" variant="outline" size="sm" disabled={disabled || devices.data.page * devices.data.size >= devices.data.total} onClick={() => setPage(current => current + 1)}>下一页</Button></div>
      </> : null}
    </>}
    <p className="muted-note">查询仅浏览当前授权设备；选择不授予任何新权限。{session.audience === 'customer' ? '客户范围由服务端会话单位固定。' : ''}</p>
  </section>
}

/** Real place tree when units:read exists; exact-ID path only when the directory is unavailable. */
export function PlaceSelector({ audience, tenantId, value, current, onChange, canReadPlaces, disabled = false }: {
  audience: 'management' | 'customer'; tenantId?: string; value: string; current?: AssetReference
  onChange: (id: string, reference?: AssetReference) => void; canReadPlaces: boolean; disabled?: boolean
}) {
  const enabled = canReadPlaces && (audience === 'customer' || !!tenantId)
  const places = useResource(`asset-places:${audience}:${tenantId ?? 'session'}`, signal => audience === 'customer' ? customerAssetsApi.places(signal) : api.places(signal, [tenantId!], [], true), enabled)
  const selected = (places.data ?? []).find(place => place.id === value)
  const label = selected ? placePath(selected, places.data ?? []) : current?.id === value ? current?.name ?? value : value
  if (audience === 'management' && canReadPlaces && !tenantId) return <div className="field"><Label htmlFor="asset-place-id">位置（可空）</Label><p className="muted-note">请先选择目标单位后再选择位置；不会预加载其他单位的位置树。</p></div>
  if (!enabled) return <div className="field"><Label htmlFor="asset-place-id">位置（高级：位置ID）</Label>
    <Input id="asset-place-id" value={value} maxLength={64} disabled={disabled} onChange={event => onChange(event.target.value)} placeholder="没有位置目录读取权限时输入已授权位置ID"/>
    {current?.id === value && <p className="muted-note">当前关联：{current.name ?? value}（{value}）</p>}
    <p className="muted-note">当前账户没有units:read，不能浏览位置树；服务端仍核验位置单位与授权。{value && <Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={() => onChange('')}>清除</Button>}</p>
  </div>
  return <div className="field"><Label htmlFor="asset-place-id">位置（可空）</Label>
    {places.loading ? <Loading/> : places.error != null ? <ErrorNotice error={places.error} retry={places.refresh}/> : <TreeSelect id="asset-place-id" aria-label="位置" style={{ width: '100%' }} allowClear showSearch treeNodeFilterProp="title"
      disabled={disabled} value={value || undefined} placeholder="选择目标单位内的真实位置" treeData={placeTreeOptions(places.data ?? [])}
      onChange={next => {
        const id = typeof next === 'string' ? next : ''
        const place = (places.data ?? []).find(item => item.id === id)
        onChange(id, place ? { id: place.id, name: placePath(place, places.data ?? []), tenantId: place.tenant_id } : undefined)
      }}/>}
    {value && <p className="muted-note">已选：{label}</p>}
  </div>
}

function referenceOf(device: Device): AssetReference {
  return { id: device.id, name: device.name, eui: device.device_eui,
    location: device.place_name ?? device.address, tenantId: device.tenant_id ?? null }
}
