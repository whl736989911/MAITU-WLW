type ScopeUnit = { id: string; places: readonly { id: string }[] }

/** A form default only: every selected node must resolve to the same known unit. */
export function scopeTargetTenantId(units: readonly ScopeUnit[], tenantIds: readonly string[], placeIds: readonly string[]): string | undefined {
  let target: ScopeUnit | undefined
  for (const unit of units) {
    if (!tenantIds.includes(unit.id) && !unit.places.some(place => placeIds.includes(place.id))) continue
    if (target) return undefined
    target = unit
  }
  if (!target) return undefined
  const owner = target
  if (tenantIds.some(id => id !== owner.id) || placeIds.some(id => !owner.places.some(place => place.id === id))) return undefined
  return owner.id
}
