import { create } from 'zustand'
import { ApiError } from './api'
import { accessApi, type AccessRole } from './access-roles'
import { useSession } from './store'
import type { ManagedUser, PageName } from './types'

export type RoleEditor = { kind: 'create' } | { kind: 'edit' | 'delete'; role: AccessRole }
export type RoleValues = { code: string; name: string; user_type: 'PLATFORM' | 'CUSTOMER'; enabled: boolean; permissions: string[]; reason: string }
export type AuthorizationIntent =
  | { kind: 'ROLE'; editor: RoleEditor; form: RoleValues }
  | { kind: 'ACCOUNT_ROLES'; platform: boolean; target: ManagedUser; tenantId: string | null; revision: number; roleIds: string[]; reason: string }
  | { kind: 'DEVICE_SCOPE'; platform: boolean; target: ManagedUser; tenantId: string; revision: number; mode: 'ALL_TENANT' | 'DEVICE_SET' | 'PLACE_SUBTREE'; deviceIds: string[]; placeIds: string[]; reason: string }
type Phase = 'EDITING' | 'CONFIRMING' | 'PENDING' | 'UNCERTAIN' | 'CONFLICT'
type Completion = { serial: number; kind: AuthorizationIntent['kind']; platform?: boolean; targetId?: string; tenantId?: string | null }
interface AuthorizationWrite {
  generation: number
  phase: Phase
  intent: AuthorizationIntent | null
  ownerKey: string | null
  key: string | null
  error: unknown
  completion: Completion | null
  prepare: (intent: AuthorizationIntent) => boolean
  edit: () => boolean
  reloadConflict: () => boolean
  submit: () => Promise<void>
}
let controller: AbortController | null = null
let serial = 0
const ownerKey = (intent: AuthorizationIntent) => intent.kind === 'ROLE' ? 'ROLE' : `${intent.kind}:${intent.platform}:${intent.target.id}:${intent.tenantId ?? ''}`
const empty = () => ({ generation: useSession.getState().generation, phase: 'EDITING' as const, intent: null, key: null, ownerKey: null, error: undefined, completion: null })
export const useAuthorizationWrite = create<AuthorizationWrite>((set, get) => ({
  ...empty(),
  prepare: intent => {
    if (get().intent || get().generation !== useSession.getState().generation) return false
    if (intent.kind === 'ACCOUNT_ROLES' && (intent.platform !== (intent.target.user_type === 'PLATFORM') || intent.tenantId !== (intent.platform ? null : intent.target.tenant_id))) return false
    if (intent.kind === 'DEVICE_SCOPE' && (intent.platform !== (intent.target.user_type === 'PLATFORM') || !intent.tenantId || !intent.platform && intent.tenantId !== intent.target.tenant_id)) return false
    set({ intent, key: crypto.randomUUID(), ownerKey: ownerKey(intent), phase: 'CONFIRMING', error: undefined, completion: null })
    return true
  },
  edit: () => {
    if (get().phase !== 'CONFIRMING' && get().phase !== 'EDITING') return false
    set({ intent: null, key: null, ownerKey: null, phase: 'EDITING', error: undefined })
    return true
  },
  reloadConflict: () => {
    if (get().phase !== 'CONFLICT') return false
    set({ intent: null, key: null, ownerKey: null, phase: 'EDITING', error: undefined })
    return true
  },
  submit: async () => {
    const { intent, key, generation, phase, ownerKey: owner } = get()
    if (!intent || !key || (phase !== 'CONFIRMING' && phase !== 'UNCERTAIN') || owner !== ownerKey(intent)) return
    const pending = new AbortController(), wasUncertain = phase === 'UNCERTAIN'
    controller = pending
    set({ phase: 'PENDING', error: undefined })
    try {
      if (intent.kind === 'ROLE') {
        const { editor, form } = intent
        if (editor.kind === 'create') await accessApi.createRole({ ...form }, key, pending.signal)
        else if (editor.kind === 'edit') await accessApi.updateRole(editor.role.id, { name: form.name, enabled: form.enabled, permissions: form.permissions, reason: form.reason, expected_revision: editor.role.revision }, key, pending.signal)
        else await accessApi.deleteRole(editor.role.id, { expected_revision: editor.role.revision, reason: form.reason }, key, pending.signal)
      } else if (intent.kind === 'ACCOUNT_ROLES') {
        await accessApi.assignRoles(intent.platform, intent.target.id, { role_ids: intent.roleIds, expected_revision: intent.revision, reason: intent.reason }, key, pending.signal, intent.platform ? undefined : intent.tenantId ?? undefined)
      } else {
        await accessApi.updateUserScope(intent.platform, intent.target.id, { mode: intent.mode, device_ids: intent.mode === 'DEVICE_SET' ? intent.deviceIds : [], place_ids: intent.mode === 'PLACE_SUBTREE' ? intent.placeIds : [], expected_revision: intent.revision, reason: intent.reason }, key, pending.signal, intent.tenantId)
      }
      if (get().generation !== generation || useSession.getState().generation !== generation || get().key !== key || get().ownerKey !== owner) return
      const completion: Completion = intent.kind === 'ROLE' ? { serial: ++serial, kind: intent.kind } : { serial: ++serial, kind: intent.kind, platform: intent.platform, targetId: intent.target.id, tenantId: intent.tenantId }
      set({ phase: 'EDITING', intent: null, key: null, ownerKey: null, error: undefined, completion })
    } catch (error) {
      if (get().generation !== generation || useSession.getState().generation !== generation || get().key !== key || get().ownerKey !== owner) return
      // After any unconfirmed attempt, even a later 4xx cannot prove that the original attempt did not commit.
      const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.code !== 'INVALID_RESPONSE'
      set({ error, phase: wasUncertain || !rejected ? 'UNCERTAIN' : error instanceof ApiError && error.status === 409 ? 'CONFLICT' : 'CONFIRMING' })
    } finally {
      if (controller === pending) controller = null
    }
  },
}))
// Navigation alone retains the in-memory intent. A changed authorization/unit owner destroys it, never a late reply's replacement owner.
useSession.subscribe((current, previous) => {
  if (current.generation === previous.generation) return
  controller?.abort(); controller = null
  useAuthorizationWrite.setState(empty())
})
export function authorizationPage(intent: AuthorizationIntent): PageName { return intent.kind === 'ROLE' ? 'roles' : intent.platform ? 'platform-users' : 'users' }
export function authorizationCloseAllowed(): boolean {
  const current = useAuthorizationWrite.getState()
  return current.phase === 'EDITING' || current.phase === 'CONFIRMING'
}
