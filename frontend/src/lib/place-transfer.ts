import { create } from 'zustand'
import { ApiError, request } from './api'
import { useSession } from './store'
import type { PlaceNode } from './types'

export interface PlaceTransferPreview {
  source_place_id: string; source_tenant_id: string; target_tenant_id: string; target_parent_id: string | null
  revision: number; places_count: number; devices_count: number; active_fire_count: number; bound_source_count: number
  snapshot: string; warnings: string[]
}
export interface PlaceTransferResult {
  transfer_id: string; source_place_id: string; target_place_id: string; target_tenant_id: string
  places_count: number; devices_count: number
}
export async function previewPlaceTransfer(place: PlaceNode, tenant: string, parent: string, signal?: AbortSignal) {
  const query = new URLSearchParams({ target_tenant_id: tenant })
  if (parent) query.set('target_parent_id', parent)
  const preview = await request<PlaceTransferPreview>(`/management/places/${encodeURIComponent(place.id)}/transfer-preview?${query}`, { tenantScoped: true, tenantId: place.tenant_id, signal })
  if (preview.source_place_id !== place.id || preview.source_tenant_id !== place.tenant_id || preview.target_tenant_id !== tenant || (preview.target_parent_id ?? '') !== parent || !preview.snapshot || !Number.isSafeInteger(preview.revision) || !Number.isSafeInteger(preview.places_count) || preview.places_count < 1 || !Number.isSafeInteger(preview.devices_count) || preview.devices_count < 0 || !Array.isArray(preview.warnings)) throw new ApiError(200, 'INVALID_RESPONSE', '移交范围未确认，请重新预览')
  return preview
}
type Intent = { place: PlaceNode; preview: PlaceTransferPreview; reason: string; confirm: boolean }
type Phase = 'EDITING' | 'CONFIRMING' | 'PENDING' | 'UNCERTAIN' | 'CONFLICT'
interface TransferWrite {
  generation: number; intent: Intent | null; key: string | null; phase: Phase; error: unknown
  completion: { serial: number; result: PlaceTransferResult } | null
  prepare: (place: PlaceNode, preview: PlaceTransferPreview, reason: string, confirm: boolean) => void
  edit: () => boolean; reload: () => boolean; submit: () => Promise<void>
}
let controller: AbortController | null = null, serial = 0
const empty = () => ({ generation: useSession.getState().generation, intent: null, key: null, phase: 'EDITING' as const, error: undefined, completion: null })
export const usePlaceTransfer = create<TransferWrite>((set, get) => ({
  ...empty(),
  prepare: (place, preview, reason, confirm) => {
    if (get().intent || !reason.trim() || get().generation !== useSession.getState().generation) return
    set({ intent: { place, preview, reason: reason.trim(), confirm }, key: crypto.randomUUID(), phase: 'CONFIRMING', error: undefined, completion: null })
  },
  edit: () => {
    if (!['EDITING', 'CONFIRMING'].includes(get().phase)) return false
    set({ intent: null, key: null, phase: 'EDITING', error: undefined }); return true
  },
  reload: () => {
    if (get().phase !== 'CONFLICT') return false
    set({ intent: null, key: null, phase: 'EDITING', error: undefined }); return true
  },
  submit: async () => {
    const { intent, key, phase, generation } = get()
    if (!intent || !key || !['CONFIRMING', 'UNCERTAIN'].includes(phase)) return
    const pending = new AbortController(), wasUncertain = phase === 'UNCERTAIN'
    controller = pending; set({ phase: 'PENDING', error: undefined })
    try {
      const { preview, reason, confirm } = intent
      const result = await request<PlaceTransferResult>(`/management/places/${encodeURIComponent(preview.source_place_id)}/transfer`, {
        tenantScoped: true, tenantId: preview.source_tenant_id, method: 'POST', signal: pending.signal,
        headers: { 'Idempotency-Key': key },
        body: JSON.stringify({ target_tenant_id: preview.target_tenant_id, target_parent_id: preview.target_parent_id, expected_revision: preview.revision, snapshot: preview.snapshot, reason, confirm_active_risks: confirm }),
      })
      if (!result.transfer_id || !result.target_place_id || result.source_place_id !== preview.source_place_id || result.target_tenant_id !== preview.target_tenant_id || result.places_count !== preview.places_count || result.devices_count !== preview.devices_count) throw new ApiError(200, 'INVALID_RESPONSE', '移交结果未确认，请沿原操作回查')
      if (get().generation !== generation || useSession.getState().generation !== generation || get().key !== key) return
      set({ intent: null, key: null, phase: 'EDITING', error: undefined, completion: { serial: ++serial, result } })
    } catch (error) {
      if (get().generation !== generation || useSession.getState().generation !== generation || get().key !== key) return
      const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.code !== 'INVALID_RESPONSE'
      set({ error, phase: wasUncertain || !rejected ? 'UNCERTAIN' : error instanceof ApiError && error.status === 409 ? 'CONFLICT' : 'CONFIRMING' })
    } finally { if (controller === pending) controller = null }
  },
}))
useSession.subscribe((current, previous) => {
  if (current.generation === previous.generation) return
  controller?.abort(); controller = null; usePlaceTransfer.setState(empty())
})
