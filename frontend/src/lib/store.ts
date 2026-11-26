import { create } from 'zustand'
import { allowedPage, pagePermission, type Audience, type AuthResponse, type PageName } from './types'
import { setCustomerResumeTag } from './portal-entry'

const landingPages = Object.keys(pagePermission) as PageName[]

// The live bearer, identity and permissions stay in memory; no Web Storage.
interface Session {
  token: string | null
  user: AuthResponse['user'] | null
  permissions: string[]
  audience: Audience
  page: PageName
  generation: number
  authGeneration: number
  tenantListRevision: number
  scopeTreeRevision: number
  scopeTenantIds: string[]
  scopePlaceIds: string[]
  scopeInitialized: boolean
  refreshTenants: () => void
  refreshScopeTree: () => void
  setScope: (tenantIds: string[], placeIds: string[], initialized?: boolean) => void
  resetScope: () => void
  notice: string | null
  login: (auth: AuthResponse, audience: Audience) => void
  clear: (notice?: string) => void
  navigate: (page: PageName) => void
  revalidate: (token: string, authGeneration: number, user: AuthResponse['user'], permissions: string[]) => boolean
}
export const useSession = create<Session>((set, get) => ({
  token: null, user: null, permissions: [], audience: 'management', page: 'summary', generation: 0, authGeneration: 0, tenantListRevision: 0, scopeTreeRevision: 0, scopeTenantIds: [], scopePlaceIds: [], scopeInitialized: false, notice: null,
  refreshTenants: () => set(s => ({ tenantListRevision: s.tenantListRevision + 1 })),
  refreshScopeTree: () => set(s => ({ scopeTreeRevision: s.scopeTreeRevision + 1 })),
  setScope: (tenantIds, placeIds, initialized = true) => set({ scopeTenantIds: [...tenantIds], scopePlaceIds: [...placeIds], scopeInitialized: initialized }),
  resetScope: () => set({ scopeTenantIds: [], scopePlaceIds: [], scopeInitialized: false }),
  login: (auth, audience) => set(s => ({ token: auth.access_token, user: auth.user, permissions: auth.permissions, audience, page: landingPages.find(page => allowedPage(page, audience, auth.permissions)) ?? 'summary', generation: s.generation + 1, authGeneration: s.authGeneration + 1, scopeTenantIds: audience === 'customer' && auth.user.tenant_id ? [auth.user.tenant_id] : [], scopePlaceIds: [], scopeInitialized: audience === 'customer' && !!auth.user.tenant_id, notice: null })),
  clear: (notice) => {
    if (get().audience === 'customer') setCustomerResumeTag(null)
    set(s => ({ token: null, user: null, permissions: [], page: 'summary', generation: s.generation + 1, authGeneration: s.authGeneration + 1, scopeTenantIds: [], scopePlaceIds: [], scopeInitialized: false, notice: notice ?? null }))
  },
  navigate: (page) => set({ page }),
  revalidate: (token, authGeneration, user, permissions) => {
    const current = get()
    if (current.token !== token || current.authGeneration !== authGeneration) return false
    const changed = JSON.stringify(current.user) !== JSON.stringify(user) || JSON.stringify(current.permissions) !== JSON.stringify(permissions)
    if (changed) set(s => s.token !== token || s.authGeneration !== authGeneration ? s : { user, permissions, generation: s.generation + 1, authGeneration: s.authGeneration + 1, scopeTenantIds: s.audience === 'customer' && user.tenant_id ? [user.tenant_id] : [], scopePlaceIds: [], scopeInitialized: s.audience === 'customer' && !!user.tenant_id })
    return changed
  },
}))
