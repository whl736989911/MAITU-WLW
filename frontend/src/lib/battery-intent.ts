import { useSession } from './store'
import { batteryApi } from './battery-api'
import type { BatteryLowThreshold } from './types'
import { ApiError } from './api'

export interface BatteryOverrideBody { expected_revision: number; battery_low_threshold: BatteryLowThreshold | null; battery_low_threshold_json: string | null; reason: string }
export interface BatteryWriteIntent { owner: string; deviceId: string; tenantId: string; body: BatteryOverrideBody; key: string; state: 'PENDING' | 'UNKNOWN' | 'CONFLICT' }
let intent: BatteryWriteIntent | null = null, controller: AbortController | null = null
const listeners = new Set<() => void>()
const ownerKey = () => { const s = useSession.getState(); return JSON.stringify([s.token, s.user, s.generation, s.permissions, s.audience]) }
let owner = ownerKey()
function emit() { for (const listener of listeners) listener() }
function clear() { controller?.abort(); controller = null; intent = null; emit() }
useSession.subscribe(() => { const next = ownerKey(); if (next !== owner) { owner = next; clear() } })
export function subscribeBatteryIntent(listener: () => void) { listeners.add(listener); return () => listeners.delete(listener) }
export function getBatteryIntent() { return intent?.owner === ownerKey() ? intent : null }
export function beginBatteryIntent(deviceId: string, tenantId: string, body: BatteryOverrideBody) {
  if (intent || !useSession.getState().token || !tenantId) return false
  const next: BatteryWriteIntent = { owner: ownerKey(), deviceId, tenantId, body: structuredClone(body), key: crypto.randomUUID(), state: 'PENDING' }
  intent = next; emit(); void send(next)
  return true
}
export function retryBatteryIntent() {
  if (!intent || intent.owner !== ownerKey() || intent.state === 'PENDING' || controller) return false
  const current: BatteryWriteIntent = { ...intent, state: 'PENDING' }; intent = current; emit(); void send(current); return true
}
async function send(current: BatteryWriteIntent) {
  const c = new AbortController(); controller = c
  try {
    await batteryApi.update(current.deviceId, { ...current.body }, current.key, current.tenantId, c.signal)
    if (intent !== current || current.owner !== ownerKey() || c.signal.aborted) return
    intent = null; emit()
  } catch (error) {
    if (intent !== current || current.owner !== ownerKey() || c.signal.aborted) return
    intent = { ...current, state: error instanceof ApiError && error.status === 409 ? 'CONFLICT' : 'UNKNOWN' }
    emit()
  } finally { if (controller === c) controller = null }
}
export function releaseBatteryConflict(deviceId: string, confirmedRevision: number) {
  if (!intent || intent.owner !== ownerKey() || intent.deviceId !== deviceId || intent.state !== 'CONFLICT' || confirmedRevision === intent.body.expected_revision) return false
  intent = null; emit(); return true
}
