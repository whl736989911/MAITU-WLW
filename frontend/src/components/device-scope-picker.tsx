import { useEffect, useMemo, useState, type KeyboardEvent } from 'react'
import { api, errorMessage } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import type { Device, ScopeUnit } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { ErrorNotice, Loading } from './common'

const PAGE_SIZE = 20

type Props = { tenantId: string; value: string[]; onChange: (ids: string[]) => void; disabled?: boolean }

export function DeviceScopePicker({ tenantId, value, onChange, disabled = false }: Props) {
  const { audience, permissions, generation } = useSession()
  const canReadDevices = permissions.includes('device:read')
  const canReadPlaces = audience === 'management' && permissions.includes('units:read')
  const [keyword, setKeyword] = useState('')
  const [draft, setDraft] = useState('')
  const [page, setPage] = useState(1)
  const [selectedOnly, setSelectedOnly] = useState(false)
  const [placeId, setPlaceId] = useState('')
  const [observed, setObserved] = useState<Map<string, Device>>(() => new Map())
  const places = useResource(`device-scope-picker-places:${generation}:${tenantId}`, signal => api.scopeTree(signal), !!tenantId && canReadPlaces)
  const devices = useResource(`device-scope-picker:${generation}:${tenantId}:${page}:${keyword}:${placeId}`, signal => api.devices(page, keyword, PAGE_SIZE, signal, undefined, placeId || undefined, tenantId), !!tenantId && canReadDevices && !selectedOnly)

  useEffect(() => {
    setKeyword('')
    setDraft('')
    setPage(1)
    setSelectedOnly(false)
    setObserved(new Map())
  }, [tenantId, generation])
  useEffect(() => {
    if (!devices.data) return
    setObserved(current => {
      const next = new Map(current)
      for (const device of devices.data!.items) next.set(device.id, device)
      return next
    })
  }, [devices.data])

  const selected = useMemo(() => [...new Set(value)], [value])
  const selectedSet = useMemo(() => new Set(selected), [selected])
  const targetPlaces = (places.data ?? []).find((unit: ScopeUnit) => unit.id === tenantId)?.places ?? []
  useEffect(() => { setPlaceId(''); setPage(1) }, [tenantId, generation])

  const pageRows = devices.data?.items ?? []
  const allPageSelected = pageRows.length > 0 && pageRows.every(device => selectedSet.has(device.id))
  const somePageSelected = pageRows.some(device => selectedSet.has(device.id))
  const updateSelection = (next: string[]) => { if (!disabled) onChange([...new Set(next)]) }
  const toggleDevice = (id: string, checked: boolean) => updateSelection(checked ? [...selected, id] : selected.filter(value => value !== id))
  const togglePage = (checked: boolean) => {
    const next = new Set(selected)
    for (const device of pageRows) checked ? next.add(device.id) : next.delete(device.id)
    updateSelection([...next])
  }
  const applySearch = () => { setKeyword(draft.trim()); setPage(1) }
  const handleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter') return
    event.preventDefault()
    event.stopPropagation()
    applySearch()
  }
  const changePlace = (next: string) => { setPlaceId(next); setPage(1) }
  const formatDevice = (id: string) => {
    const device = observed.get(id)
    if (!device) return id
    const name = device.name?.trim() || device.device_eui?.trim() || id
    return `${name}${device.device_eui && device.name?.trim() ? ` · ${device.device_eui}` : ''}`
  }
  const placeLabel = (place: ScopeUnit['places'][number]) => {
    const byId = new Map(targetPlaces.map(item => [item.id, item]))
    const names = [place.name]
    const seen = new Set([place.id])
    let parentId = place.parent_id
    while (parentId && !seen.has(parentId)) {
      seen.add(parentId)
      const parent = byId.get(parentId)
      if (!parent) {
        const contextName = place.parent_context?.name
        if (contextName && names.at(-1) !== contextName) names.push(contextName)
        break
      }
      names.push(parent.name)
      parentId = parent.parent_id
    }
    return names.reverse().join(' / ')
  }

  return <section aria-label="设备范围选择" className="device-scope-picker">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div><strong>设备范围</strong><p className="text-sm text-muted-foreground">已选 {selected.length} 个；设备查询仅用于浏览和选择，不会提交授权变更。</p></div>
      {selected.length > 0 && <Button type="button" variant="outline" disabled={disabled} onClick={() => updateSelection([])}>清空全部已选</Button>}
    </div>

    {selected.length > 0 && <section aria-label="已选设备" className="mt-3 rounded-md border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2"><strong>已选设备（{selected.length}）</strong><label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={selectedOnly} onChange={event => { setSelectedOnly(event.target.checked); setPage(1) }}/>仅查看已选</label></div>
      <ul className="mt-2 grid gap-2 sm:grid-cols-2">
        {selected.map(id => <li key={id} className="flex min-w-0 items-center justify-between gap-2 rounded border px-2 py-1.5"><span className="min-w-0 break-all" title={id}>{formatDevice(id)}</span><Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={() => toggleDevice(id, false)} aria-label={`移除 ${formatDevice(id)}`}>移除</Button></li>)}
      </ul>
      <p className="mt-2 text-xs text-muted-foreground">未在已加载设备中观察到的条目以稳定 ID 显示；只有明确移除或清空才会从选择中删除。</p>
    </section>}

    {!canReadDevices ? <p role="status" className="mt-3 rounded-md border p-3 text-sm">当前会话没有设备读取权限，无法在此浏览设备。已有设备范围仍可保留、移除或清空；此处不会推断或授予设备读取权限。</p> : <>
      <div className="mt-3 flex flex-wrap items-end gap-2">
        <div className="min-w-[min(100%,16rem)] flex-1"><label htmlFor="device-scope-search" className="mb-1 block text-sm font-medium">搜索设备名称、序列号或地址</label><Input id="device-scope-search" value={draft} onChange={event => setDraft(event.target.value)} onKeyDown={handleSearchKeyDown} placeholder="输入设备关键字" /></div>
        <Button type="button" variant="outline" onClick={applySearch}>搜索</Button>
        {canReadPlaces && <div className="min-w-48"><label htmlFor="device-scope-place" className="mb-1 block text-sm font-medium">位置</label><select id="device-scope-place" className="select-control w-full" value={placeId} onChange={event => changePlace(event.target.value)}><option value="">目标单位全部位置</option>{targetPlaces.map(place => <option key={place.id} value={place.id}>{placeLabel(place)}</option>)}</select></div>}
      </div>
      {canReadPlaces && places.loading && <p role="status" className="mt-2 text-sm">正在读取目标单位位置…</p>}
      {canReadPlaces && places.error != null && <div role="alert" className="mt-2 text-sm">位置筛选读取失败：{errorMessage(places.error)} <Button type="button" variant="ghost" size="sm" onClick={places.refresh}>重试位置</Button></div>}
      {canReadPlaces && places.data && targetPlaces.length === 0 && <p className="mt-2 text-sm text-muted-foreground">该目标单位当前没有可选位置。</p>}
      {selectedOnly ? <p className="mt-3 text-sm">仅查看受控选择中的 {selected.length} 个条目。未加载的 ID 仍保留，可在上方移除。</p> : <>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
          <label className="flex items-center gap-2"><input type="checkbox" checked={allPageSelected} ref={element => { if (element) element.indeterminate = somePageSelected && !allPageSelected }} disabled={disabled || pageRows.length === 0 || devices.loading} onChange={event => togglePage(event.target.checked)}/>选择当前页（{pageRows.length} 条）</label>
        <p className="mt-1 text-xs text-muted-foreground">勾选只会把当前页设备加入已有选择；取消勾选只移除当前页对应设备，不影响其他页或未加载 ID。</p>
          <span className="text-sm text-muted-foreground">当前页 {pageRows.length} 条 · 选择合计 {selected.length}</span>
        </div>
        {devices.loading ? <p role="status" className="mt-3">正在读取目标单位设备…</p> : devices.error != null ? <div role="alert" className="mt-3">设备读取失败：{errorMessage(devices.error)} <Button type="button" variant="ghost" size="sm" onClick={devices.refresh}>重试</Button></div> : devices.data && devices.data.items.length === 0 ? <p className="mt-3 rounded border p-4 text-sm">{keyword ? '没有匹配的设备。' : '目标单位暂无可浏览设备。'}</p> : devices.data && <>
          <div className="mt-2 overflow-x-auto rounded-md border"><table className="w-full min-w-[36rem] text-left text-sm"><thead><tr className="border-b"><th scope="col" className="p-2">选择</th><th scope="col" className="p-2">设备</th><th scope="col" className="p-2">序列号</th><th scope="col" className="p-2">位置 / 地址</th></tr></thead><tbody>{pageRows.map(device => <tr key={device.id} className="border-b last:border-0"><td className="p-2"><input type="checkbox" aria-label={`选择 ${device.name || device.device_eui || device.id}`} checked={selectedSet.has(device.id)} disabled={disabled} onChange={event => toggleDevice(device.id, event.target.checked)}/></td><td className="p-2">{device.name?.trim() || '未命名设备'}</td><td className="p-2">{device.device_eui || '—'}</td><td className="p-2">{[device.place_name, device.address].filter(Boolean).join(' · ') || '—'}</td></tr>)}</tbody></table></div>
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2"><span className="text-sm">共 {devices.data.total} 条 · 第 {devices.data.page} 页 / {Math.max(1, Math.ceil(devices.data.total / devices.data.size))} 页</span><div className="flex gap-2"><Button type="button" variant="outline" disabled={devices.data.page <= 1 || devices.loading} onClick={() => setPage(current => Math.max(1, current - 1))}>上一页</Button><Button type="button" variant="outline" disabled={devices.data.page * devices.data.size >= devices.data.total || devices.loading} onClick={() => setPage(current => current + 1)}>下一页</Button></div></div>
        </>}
      </>}
    </>}
  </section>
}

type PlaceOption = { id: string; name: string; parent_id: string | null; depth: number; path: string }

/** Dynamic place roots: every selected place includes all descendants and future devices beneath it. */
export function PlaceSubtreePicker({ tenantId, value, onChange, disabled = false }: { tenantId: string; value: string[]; onChange: (ids: string[]) => void; disabled?: boolean }) {
  const { audience, permissions } = useSession()
  const canReadPlaces = audience === 'management' && permissions.includes('units:read')
  const tree = useResource(`place-subtree-picker:${tenantId}:${audience}`, signal => audience === 'customer'
    ? import('@/lib/customer-assets').then(({ customerAssetsApi }) => customerAssetsApi.scopeTree(signal).then(units => units.map(unit => ({ id: unit.id, name: unit.name, places: unit.places }))))
    : api.scopeTree(signal), !!tenantId && (audience === 'customer' || canReadPlaces))
  const units = (tree.data ?? []) as Array<{ id: string; name: string; places: Array<{ id: string; name: string; parent_id: string | null }> }>
  const raw = units.find(unit => unit.id === tenantId)?.places ?? []
  const byId = new Map(raw.map(place => [place.id, place]))
  const depthOf = (id: string) => {
    let depth = 0, current = byId.get(id)?.parent_id ?? null
    const seen = new Set<string>([id])
    while (current && !seen.has(current) && depth < 64) { seen.add(current); depth++; current = byId.get(current)?.parent_id ?? null }
    return depth
  }
  const pathOf = (id: string) => {
    const names: string[] = [], seen = new Set<string>()
    let current: string | null = id
    while (current && !seen.has(current) && names.length < 64) { seen.add(current); const node = byId.get(current); names.push(node?.name ?? current); current = node?.parent_id ?? null }
    return names.reverse().join(' / ')
  }
  const options: PlaceOption[] = [...raw].sort((left, right) => pathOf(left.id).localeCompare(pathOf(right.id)) || left.id.localeCompare(right.id))
    .map(place => ({ id: place.id, name: place.name, parent_id: place.parent_id, depth: depthOf(place.id), path: pathOf(place.id) }))
  const isDescendantOf = (candidate: string, ancestor: string) => {
    let current: string | null = byId.get(candidate)?.parent_id ?? null
    const seen = new Set<string>([candidate])
    while (current && !seen.add(current) && current !== ancestor) current = byId.get(current)?.parent_id ?? null
    return current === ancestor
  }
  const toggle = (id: string, checked: boolean) => {
    if (disabled) return
    if (!checked) { onChange(value.filter(root => root !== id)); return }
    if (value.some(root => root === id || isDescendantOf(id, root))) return
    onChange([...value.filter(root => !isDescendantOf(root, id)), id])
  }
  const [draft, setDraft] = useState('')
  const commitDraft = () => {
    if (disabled) return
    const ids = [...new Set(draft.split(/[\s,]+/).map(item => item.trim()).filter(Boolean))]
    if (ids.some(id => id.length > 64 || !/^[A-Za-z0-9_-]+$/.test(id))) return
    onChange(ids)
    setDraft('')
  }
  return <section aria-label="位置动态范围" className="form-stack">
    <p className="muted-note">选择的位置根会包含其全部后代位置；这些位置下未来新增的设备自动进入范围，设备移出该子树后自动失去当前可见性。只保存位置根，不缓存设备集合。</p>
    {audience === 'management' && !canReadPlaces ? <div className="notice">缺少 units:read，无法浏览位置树。可明确输入位置根 ID；服务端仍会核验单位、归档状态和操作者结构范围。已有根可移除。</div> : tree.loading ? <Loading/> : tree.error != null ? <ErrorNotice error={tree.error} retry={tree.refresh}/> : options.length === 0 ? <div className="notice">目标单位当前没有可选位置。</div> : <div className="permission-grid">{options.map(option => <label key={option.id} title={option.path} style={{ paddingLeft: option.depth ? `${option.depth * 18}px` : undefined }}><input type="checkbox" checked={value.includes(option.id)} disabled={disabled} onChange={event => toggle(option.id, event.target.checked)}/><span>{option.name}<small className="mono">{option.id}</small>{value.some(root => root !== option.id && isDescendantOf(option.id, root)) && <small>已被上级根覆盖</small>}</span></label>)}</div>}
    <div className="field"><label htmlFor="place-subtree-input">高级：位置根 ID（逗号或空格分隔）</label><Input id="place-subtree-input" value={draft} disabled={disabled} onChange={event => setDraft(event.target.value)} placeholder="仅在无法读取位置树时使用；必须是同单位在用位置" onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); commitDraft() } }}/><Button type="button" variant="outline" size="sm" disabled={disabled || !draft.trim()} onClick={commitDraft}>应用位置根</Button></div>
    {value.length > 0 && <div className="notice"><strong>当前位置根（{value.length}）</strong>{value.map(id => <div key={id}><span className="mono">{id}</span>{byId.has(id) && ` · ${pathOf(id)}`}<Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={() => toggle(id, false)}>移除</Button></div>)}</div>}
  </section>
}
