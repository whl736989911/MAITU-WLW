import { create } from 'zustand'
import { ApiError, request } from './api'
import { useSession } from './store'
import type { PlaceNode } from './types'

export interface PlaceMovePreview {
  place: PlaceNode
  target_parent: PlaceNode | null
  places_count: number
  devices_count: number
  snapshot: string
}
export interface PlaceMoveResult {
  place: PlaceNode
  places_count: number
  devices_count: number
  changed: boolean
}
export async function previewPlaceMove(place: PlaceNode, parentId: string, signal?: AbortSignal): Promise<PlaceMovePreview> {
  const query = new URLSearchParams()
  if (parentId) query.set('target_parent_id', parentId)
  const audience = useSession.getState().audience
  const result = await request<PlaceMovePreview>(`/${audience}/places/${encodeURIComponent(place.id)}/move-preview?${query}`, { tenantScoped: true, tenantId: place.tenant_id, signal })
  if (result.place?.id !== place.id || result.place.tenant_id !== place.tenant_id || (result.target_parent?.id ?? '') !== parentId || !result.snapshot || !Number.isSafeInteger(result.places_count) || result.places_count < 1 || !Number.isSafeInteger(result.devices_count) || result.devices_count < 0) throw new ApiError(200, 'INVALID_RESPONSE', '移动范围预览未确认，请重新读取')
  return result
}

type Intent = { preview: PlaceMovePreview; reason: string }
type Phase = 'EDITING' | 'CONFIRMING' | 'PENDING' | 'UNCERTAIN' | 'CONFLICT'
interface MoveWrite {
  generation: number
  intent: Intent | null
  key: string | null
  phase: Phase
  error: unknown
  completion: { serial: number; result: PlaceMoveResult } | null
  prepare: (preview: PlaceMovePreview, reason: string) => void
  edit: () => boolean
  reloadConflict: () => boolean
  dismissCompletion: () => void
  submit: () => Promise<void>
}
let controller: AbortController | null = null
let serial = 0
const empty = () => ({ generation: useSession.getState().generation, intent: null, key: null, phase: 'EDITING' as const, error: undefined, completion: null })
// Like authorization writes: navigation retains the canonical in-memory intent.
export const usePlaceMove = create<MoveWrite>((set, get) => ({
  ...empty(),
  prepare: (preview, reason) => {
    if (get().intent || !reason.trim() || get().generation !== useSession.getState().generation) return
    set({ intent: { preview, reason: reason.trim() }, key: crypto.randomUUID(), phase: 'CONFIRMING', error: undefined, completion: null })
  },
  edit: () => {
    if (get().phase !== 'CONFIRMING' && get().phase !== 'EDITING') return false
    set({ intent: null, key: null, phase: 'EDITING', error: undefined })
    return true
  },
  reloadConflict: () => {
    if (get().phase !== 'CONFLICT') return false
    set({ intent: null, key: null, phase: 'EDITING', error: undefined })
    return true
  },
  dismissCompletion: () => set({ completion: null }),
  submit: async () => {
    const { intent, key, phase, generation } = get()
    if (!intent || !key || (phase !== 'CONFIRMING' && phase !== 'UNCERTAIN')) return
    const pending = new AbortController(), wasUncertain = phase === 'UNCERTAIN'
    controller = pending
    set({ phase: 'PENDING', error: undefined })
    try {
      const { preview, reason } = intent
      const result = await request<PlaceMoveResult>(`/${useSession.getState().audience}/places/${encodeURIComponent(preview.place.id)}/move`, {
        tenantScoped: true, tenantId: preview.place.tenant_id, method: 'POST', signal: pending.signal,
        headers: { 'Idempotency-Key': key },
        body: JSON.stringify({ target_parent_id: preview.target_parent?.id ?? null, expected_revision: preview.place.revision, snapshot: preview.snapshot, reason }),
      })
      if (result.place?.id !== preview.place.id || result.place.tenant_id !== preview.place.tenant_id || (result.place.parent_id ?? null) !== (preview.target_parent?.id ?? null) || result.places_count !== preview.places_count || result.devices_count !== preview.devices_count || typeof result.changed !== 'boolean') throw new ApiError(200, 'INVALID_RESPONSE', '移动结果未确认，请沿原操作回查')
      if (get().generation !== generation || useSession.getState().generation !== generation || get().key !== key) return
      set({ intent: null, key: null, phase: 'EDITING', error: undefined, completion: { serial: ++serial, result } })
    } catch (error) {
      if (get().generation !== generation || useSession.getState().generation !== generation || get().key !== key) return
      const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.code !== 'INVALID_RESPONSE'
      set({ error, phase: wasUncertain || !rejected ? 'UNCERTAIN' : error instanceof ApiError && error.status === 409 ? 'CONFLICT' : 'CONFIRMING' })
    } finally {
      if (controller === pending) controller = null
    }
  },
}))
useSession.subscribe((current, previous) => {
  if (current.generation === previous.generation) return
  controller?.abort(); controller = null
  usePlaceMove.setState(empty())
})
