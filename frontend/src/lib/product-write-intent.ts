import { useSession } from './store'
import { api, ApiError } from './api'
import type { Capabilities, ParserTestResult, ProductVersion } from './types'
import type { BatteryLowThreshold } from './types'

export type ProductWriteCommand =
  | { mode: 'new'; product_id: string; base_version_id: null }
  | { mode: 'clone'; product_id: string; base_version_id: string }
  | { mode: 'edit'; version_id: string; body: { expected_revision: number; schema_json: string; capabilities: Capabilities; battery_low_threshold: BatteryLowThreshold | null } }
  | { mode: 'test'; version_id: string; body: { expected_revision: number; samples_json: string } }
  | { mode: 'publish'; version_id: string; expected_revision: number }
export interface ProductWriteIntent { owner: string; productId: string; command: ProductWriteCommand; key: string; state: 'PENDING' | 'UNKNOWN' | 'CONFLICT' }
export interface ProductWriteCompletion { productId: string; result: ProductVersion | ParserTestResult }
let intent: ProductWriteIntent | null = null, completion: ProductWriteCompletion | null = null, controller: AbortController | null = null
let snapshot: { intent: ProductWriteIntent | null; completion: ProductWriteCompletion | null } = { intent: null, completion: null }
const listeners = new Set<() => void>()
const ownerKey = () => { const s = useSession.getState(); return JSON.stringify([s.token, s.user, s.generation, s.permissions, s.audience]) }
let owner = ownerKey()
function emit() { snapshot = { intent: intent?.owner === ownerKey() ? intent : null, completion }; for (const listener of listeners) listener() }
function clear() { controller?.abort(); controller = null; intent = null; completion = null; emit() }
useSession.subscribe(() => { const next = ownerKey(); if (next !== owner) { owner = next; clear() } })
export function subscribeProductWrite(listener: () => void) { listeners.add(listener); return () => listeners.delete(listener) }
export function getProductWriteSnapshot() { return snapshot }
export function consumeProductWriteCompletion(productId: string) { if (completion?.productId === productId) { const result = completion.result; completion = null; emit(); return result } }
export function beginProductWrite(productId: string, command: ProductWriteCommand) {
  if (intent || !useSession.getState().token) return false
  const next: ProductWriteIntent = { owner: ownerKey(), productId, command: structuredClone(command), key: crypto.randomUUID(), state: 'PENDING' }
  intent = next; completion = null; emit(); void send(next); return true
}
export function retryProductWrite() {
  if (!intent || intent.owner !== ownerKey() || intent.state === 'PENDING' || controller) return false
  const next: ProductWriteIntent = { ...intent, state: 'PENDING' }; intent = next; emit(); void send(next); return true
}
async function send(current: ProductWriteIntent) {
  const c = new AbortController(); controller = c
  try {
    let result: ProductVersion | ParserTestResult
    const command = current.command
    if (command.mode === 'new' || command.mode === 'clone') result = await api.createVersion(command.product_id, command.base_version_id, current.key, c.signal)
    else if (command.mode === 'edit') result = await api.updateVersion(command.version_id, command.body, current.key, c.signal)
    else if (command.mode === 'test') result = await api.testVersion(command.version_id, command.body, current.key, c.signal)
    else result = await api.publishVersion(command.version_id, command.expected_revision, current.key, c.signal)
    if (intent !== current || current.owner !== ownerKey() || c.signal.aborted) return
    intent = null; completion = { productId: current.productId, result }; emit()
  } catch (error) {
    if (intent !== current || current.owner !== ownerKey() || c.signal.aborted) return
    intent = { ...current, state: error instanceof ApiError && error.status === 409 ? 'CONFLICT' : 'UNKNOWN' }; emit()
  } finally { if (controller === c) controller = null }
}
export function releaseProductConflict(productId: string) {
  if (!intent || intent.owner !== ownerKey() || intent.productId !== productId || intent.state !== 'CONFLICT') return false
  intent = null; emit(); return true
}
