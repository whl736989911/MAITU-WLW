import { useSyncExternalStore } from 'react'

export type BusinessObject = 'devices' | 'events' | 'organization' | 'notifications'

const revisions: Record<BusinessObject, number> = { devices: 0, events: 0, organization: 0, notifications: 0 }
const listeners: Record<BusinessObject, Set<() => void>> = {
  devices: new Set(), events: new Set(), organization: new Set(), notifications: new Set(),
}

export function publishObjectChange(...kinds: BusinessObject[]): void {
  for (const kind of new Set(kinds)) {
    revisions[kind]++
    for (const listener of listeners[kind]) listener()
  }
}

export function useObjectRevision(kind: BusinessObject): number {
  return useSyncExternalStore(
    listener => { listeners[kind].add(listener); return () => listeners[kind].delete(listener) },
    () => revisions[kind],
    () => 0,
  )
}
