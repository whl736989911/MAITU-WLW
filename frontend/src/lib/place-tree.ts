import type { PlaceNode } from './types'

export interface PlaceTreeOption { key: string; value: string; title: string; children: PlaceTreeOption[] }
export function placeBranchIds(places: PlaceNode[], rootId: string): Set<string> {
  const children = new Map<string, string[]>()
  for (const place of places) if (place.parent_id) {
    const branch = children.get(place.parent_id) ?? []
    branch.push(place.id); children.set(place.parent_id, branch)
  }
  const ids = new Set<string>(), pending = [rootId]
  while (pending.length) {
    const id = pending.pop()!
    if (ids.has(id)) continue
    ids.add(id)
    for (const child of children.get(id) ?? []) pending.push(child)
  }
  return ids
}
export function placePath(place: PlaceNode, places: PlaceNode[]): string {
  const byId = new Map(places.map(item => [item.id, item])), names = [place.name], seen = new Set([place.id])
  let parent = place.parent_id
  while (parent && !seen.has(parent)) {
    seen.add(parent)
    const node = byId.get(parent)
    if (!node || node.tenant_id !== place.tenant_id) { names.unshift('上级不在当前范围'); break }
    names.unshift(node.name); parent = node.parent_id
  }
  return names.join(' / ')
}
export function placeTreeOptions(places: PlaceNode[], excluded = new Set<string>()): PlaceTreeOption[] {
  const nodes = new Map<string, PlaceTreeOption>()
  for (const place of places) if (!excluded.has(place.id) && place.level > 0) nodes.set(place.id, { key: place.id, value: place.id, title: place.name, children: [] })
  const roots: PlaceTreeOption[] = []
  for (const place of places) {
    const node = nodes.get(place.id)
    if (!node) continue
    const parent = place.parent_id ? nodes.get(place.parent_id) : undefined
    if (parent) parent.children.push(node)
    else { if (place.parent_id) node.title += '（上级不在当前范围）'; roots.push(node) }
  }
  return roots
}
