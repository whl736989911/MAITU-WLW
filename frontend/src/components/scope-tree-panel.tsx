import { useEffect, useMemo, useState } from 'react'
import { Checkbox, Tree, type TreeDataNode } from 'antd'
import { Building2, MapPin, RefreshCw, Search } from 'lucide-react'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import type { ScopePlaceNode, ScopeUnit } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'

type TreeNode = { id: string; name: string; children: TreeNode[]; place: ScopePlaceNode | null }
type TreeIssue = { node: TreeNode; reason: string }
type PlaceTree = { roots: TreeNode[]; issues: TreeIssue[] }

function placeTree(places: ScopePlaceNode[]): PlaceTree {
  const nodes = new Map(places.map(place => [place.id, { id: place.id, name: place.name, children: [] as TreeNode[], place }]))
  const contexts = new Map<string, TreeNode>()
  const roots: TreeNode[] = []
  for (const node of nodes.values()) {
    const parent = node.place.parent_id ? nodes.get(node.place.parent_id) : undefined
    if (parent) parent.children.push(node)
    else if (node.place.parent_id) continue
    else if (node.place.parent_context) {
      const context = node.place.parent_context
      const key = context.id ? `source-parent:${context.id}` : `unavailable-parent:${node.id}`
      let contextNode = contexts.get(key)
      if (!contextNode) {
        contextNode = { id: key, name: context.name, children: [], place: null }
        contexts.set(key, contextNode)
        roots.push(contextNode)
      }
      contextNode.children.push(node)
    } else roots.push(node)
  }
  const sort = (items: TreeNode[]) => {
    items.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN') || a.id.localeCompare(b.id))
    items.forEach(item => sort(item.children))
  }
  sort(roots)
  const reachable = new Set<string>()
  const visit = (node: TreeNode) => {
    if (reachable.has(node.id)) return
    reachable.add(node.id)
    node.children.forEach(visit)
  }
  roots.forEach(visit)
  const issues = [...nodes.values()].filter(node => !reachable.has(node.id)).map(node => ({
    node,
    reason: node.place?.parent_id && !nodes.has(node.place.parent_id) ? '上级位置不在当前授权列表中' : '位置层级存在循环引用',
  }))
  return { roots, issues }
}

export function ScopeTreePanel({ searchable = false }: { searchable?: boolean }) {
  const audience = useSession(s => s.audience)
  const initialized = useSession(s => s.scopeInitialized)
  const tenantIds = useSession(s => s.scopeTenantIds)
  const placeIds = useSession(s => s.scopePlaceIds)
  const setScope = useSession(s => s.setScope)
  const enabled = audience === 'management'
  const scopeTreeRevision = useSession(s => s.scopeTreeRevision)
  const tree = useResource(`scope-tree:${scopeTreeRevision}`, signal => api.scopeTree(signal), enabled)
  const [expanded, setExpanded] = useState<string[]>([])
  const [search, setSearch] = useState('')
  const [searchExpansion, setSearchExpansion] = useState<string[] | null>(null)
  const units = tree.data ?? []
  const selectedTenants = useMemo(() => new Set(tenantIds), [tenantIds])
  const selectedPlaces = useMemo(() => new Set(placeIds), [placeIds])
  const hierarchies = useMemo(() => new Map((tree.data ?? []).map(unit => [unit.id, placeTree(unit.places)])), [tree.data])

  useEffect(() => {
    if (!tree.data || initialized) return
    setScope(tree.data.map((unit: ScopeUnit) => unit.id), [])
  }, [tree.data, initialized, setScope])

  if (!enabled) return null
  if (tree.loading) return <section className="scope-tree-panel"><div className="scope-tree-title">单位 / 位置</div><p className="scope-tree-hint">正在读取授权范围…</p></section>
  if (tree.error != null) return <section className="scope-tree-panel"><div className="scope-tree-title">单位 / 位置</div><p className="scope-tree-hint">范围读取未完成</p><Button variant="ghost" size="sm" onClick={tree.refresh}>重试</Button></section>
  const allSelected = units.length > 0 && units.every(unit => selectedTenants.has(unit.id)) && placeIds.length === 0
  function toggleUnit(unit: ScopeUnit) {
    const nextTenants = selectedTenants.has(unit.id) ? tenantIds.filter(id => id !== unit.id) : [...tenantIds, unit.id]
    const nextPlaces = selectedTenants.has(unit.id) ? placeIds.filter(id => !unit.places.some(place => place.id === id)) : placeIds
    setScope(nextTenants, nextPlaces)
  }
  function togglePlace(unit: ScopeUnit, place: ScopePlaceNode) {
    if (selectedTenants.has(unit.id)) return
    const nextPlaces = selectedPlaces.has(place.id) ? placeIds.filter(id => id !== place.id) : [...placeIds, place.id]
    setScope(tenantIds, nextPlaces)
  }
  const query = searchable ? search.trim().toLocaleLowerCase() : ''
  const matches = (name: string) => name.toLocaleLowerCase().includes(query)
  const searchExpanded: string[] = []
  function renderPlaces(unit: ScopeUnit, nodes: TreeNode[], ancestorMatches: boolean): TreeDataNode[] {
    return nodes.flatMap(node => {
      const key = JSON.stringify(['place', unit.id, node.id])
      const ownMatch = matches(node.name)
      const children = renderPlaces(unit, node.children, ancestorMatches || ownMatch)
      if (!ancestorMatches && !ownMatch && !children.length) return []
      if (query && children.length) searchExpanded.push(key)
      return [{
        key,
        title: node.place
          ? <Checkbox checked={selectedTenants.has(unit.id) || selectedPlaces.has(node.place.id)} disabled={selectedTenants.has(unit.id)} onChange={() => togglePlace(unit, node.place!)}><MapPin size={12}/><span>{node.name}</span><small>{node.place.device_count}</small></Checkbox>
          : <span className="scope-tree-context" aria-label="只读上级位置"><MapPin size={12}/><span>{node.name}</span><small>仅显示层级</small></span>,
        children,
        selectable: false,
      }]
    })
  }
  const treeData = units.flatMap(unit => {
    const key = JSON.stringify(['unit', unit.id]), ownMatch = matches(unit.name)
    const children = renderPlaces(unit, hierarchies.get(unit.id)?.roots ?? [], ownMatch)
    if (!ownMatch && !children.length) return []
    if (query && children.length) searchExpanded.push(key)
    return [{ key, children, title: <Checkbox checked={selectedTenants.has(unit.id)} indeterminate={!selectedTenants.has(unit.id) && unit.places.some(place => selectedPlaces.has(place.id))} onChange={() => toggleUnit(unit)}><Building2 size={13}/><span title={unit.name}>{unit.name}</span></Checkbox> }]
  })
  return <section className="scope-tree-panel" aria-label="单位位置范围">
    {searchable && <div className="asset-scope-search"><Input type="search" aria-label="搜索单位或位置" placeholder="输入关键字查询" value={search} onChange={event => { setSearch(event.target.value); setSearchExpansion(null) }}/><Search size={15} aria-hidden="true"/></div>}
    <div className="scope-tree-head"><div className="scope-tree-title">单位 / 位置</div><div className="scope-tree-actions"><Button type="button" variant="ghost" size="sm" onClick={tree.refresh} aria-label="刷新单位位置范围" title="刷新单位位置范围"><RefreshCw size={13}/></Button><Button type="button" variant="ghost" size="sm" onClick={() => setScope(units.map(unit => unit.id), [])} disabled={allSelected}>全选</Button><Button type="button" variant="ghost" size="sm" onClick={() => setScope([], [])}>清空</Button></div></div>
    <p className="scope-tree-hint">{scopeSummary(units, selectedTenants, selectedPlaces)}</p>
    <div className="scope-tree-list">
      {units.length === 0 ? <p className="scope-tree-hint">当前账户没有可用的单位授权。</p> :
        treeData.length ? <Tree blockNode selectable={false} expandedKeys={query ? searchExpansion ?? searchExpanded : expanded}
          onExpand={keys => (query ? setSearchExpansion : setExpanded)(keys.map(String))} treeData={treeData}/> :
        <p className="scope-tree-hint">没有匹配的单位或位置。</p>}
      {units.map(unit => {
        const issues = hierarchies.get(unit.id)?.issues ?? []
        return issues.length > 0 && <div key={unit.id} className="scope-tree-issues" role="alert"><strong>{unit.name} · 层级待确认</strong>{issues.map(({ node, reason }) => node.place && <Checkbox key={node.id} checked={selectedTenants.has(unit.id) || selectedPlaces.has(node.id)} disabled={selectedTenants.has(unit.id)} onChange={() => togglePlace(unit, node.place!)}><span>{node.name}</span><small>{reason}</small></Checkbox>)}</div>
      })}
    </div>
  </section>
}

function scopeSummary(units: ScopeUnit[], tenants: Set<string>, places: Set<string>) {
  if (units.length === 0) return '无可用单位'
  if (tenants.size === 0 && places.size === 0) return '未选择范围'
  if (tenants.size === units.length && places.size === 0) return '全部授权范围'
  return `${tenants.size} 个单位 · ${places.size} 个位置`
}
