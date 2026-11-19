import type { ReactNode } from 'react'
import { useSession } from '@/lib/store'
import type { PageName } from '@/lib/types'
import { Loading } from './common'

// Personal data and explicit-target import/export commands do not depend on a browse selection.
const scopedPages: Partial<Record<PageName, true>> = {
  wall: true, devices: true, alarms: true, profiles: true, 'device-groups': true, distribution: true,
  places: true, relations: true, 'notification-deliveries': true, 'notification-resources': true,
  'notification-recharge-settings': true, 'notification-contacts': true,
  suppressions: true, users: true,
  'audit-records': true, sources: true, 'legacy-ingest': true, messages: true, tasks: true, parameters: true,
  'duty-overview': true, 'parameter-monitoring': true, 'device-monitoring': true,
  'alarm-history': true, 'fault-history': true, 'operations-overview': true, 'device-faults': true,
  'resource-linkage': true, 'alarm-base-config': true,
}
export function AuthorizedReadScope({ children }: { children: ReactNode }) {
  const { audience, page, scopeInitialized } = useSession()
  if (audience !== 'management' || !scopedPages[page]) return <>{children}</>
  if (!scopeInitialized) return <Loading/>
  // Empty browse selections belong to each reader; they must not destroy explicit-target drafts.
  return <section className="authorized-read-content">{children}</section>
}
