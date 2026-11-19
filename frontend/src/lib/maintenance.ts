import { ApiError, request } from './api'
import { useSession } from './store'
import { parseJsonDocument } from './json-document'
import type { Paginated } from './types'

export const WORK_ORDER_STATUSES = ['DRAFT', 'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'PENDING_REVIEW', 'CLOSED', 'CANCELLED'] as const
export const WORK_ORDER_PRIORITIES = ['LOW', 'NORMAL', 'HIGH', 'URGENT'] as const
export const WORK_ORDER_RESULTS = ['REPAIRED', 'NOT_REPAIRED', 'REPLACED', 'OTHER'] as const
export type WorkOrderStatus = typeof WORK_ORDER_STATUSES[number]
export type WorkOrderPriority = typeof WORK_ORDER_PRIORITIES[number]
export type WorkOrderResult = typeof WORK_ORDER_RESULTS[number]

export interface WorkOrderSummary {
  id: string; tenant_id: string; device_id: string; device_name: string | null; device_address: string | null
  fault_event_id: string | null; title: string; description: string; priority: WorkOrderPriority | null
  due_at: string | null; status: WorkOrderStatus; assignee_user_id: string | null; assignee_name: string | null
  revision: number; created_at: string; updated_at: string; closed_at: string | null; cancelled_at: string | null
}
export interface WorkOrderTransition {
  id: string; action: string; from_status: string | null; to_status: string; actor_id: string; actor_name: string | null
  reason: string; assignee_user_id: string | null; revision_before: number | null; revision_after: number
  work_log_id: string | null; created_at: string
}
export interface WorkOrderLog {
  id: string; actor_id: string; actor_name: string | null; note: string; result: WorkOrderResult
  replacement_device_id: string | null; replacement_device_name: string | null; created_at: string
}
export interface WorkOrderReplacement {
  id: string; work_log_id: string; source_device_id: string; replacement_device_id: string
  replacement_device_name: string | null; created_by: string; created_at: string
}
export interface WorkOrderDetail {
  order: WorkOrderSummary; timeline: WorkOrderTransition[]; work_logs: WorkOrderLog[]; replacements: WorkOrderReplacement[]
}
export interface Assignee { user_id: string; username: string; name: string }
export type WorkOrderPage = Paginated<WorkOrderSummary>

function fail(): never { throw new ApiError(200, 'WORK_ORDER_RESPONSE_UNCONFIRMED', '工单响应格式未确认；不推断空值或成功。') }
function obj(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail()
  const record = value as Record<string, unknown>
  for (const key of keys) if (!Object.hasOwn(record, key)) fail()
  return record
}
function text(value: unknown): string { if (typeof value !== 'string') fail(); return value }
function nullableText(value: unknown): string | null { return value === null ? null : text(value) }
function integer(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) fail()
  return value
}
function status(value: unknown): WorkOrderStatus {
  if (typeof value !== 'string' || !(WORK_ORDER_STATUSES as readonly string[]).includes(value)) fail()
  return value as WorkOrderStatus
}
function priority(value: unknown): WorkOrderPriority | null {
  if (value === null) return null
  if (typeof value !== 'string' || !(WORK_ORDER_PRIORITIES as readonly string[]).includes(value)) fail()
  return value as WorkOrderPriority
}
function result(value: unknown): WorkOrderResult {
  if (typeof value !== 'string' || !(WORK_ORDER_RESULTS as readonly string[]).includes(value)) fail()
  return value as WorkOrderResult
}
function array(value: unknown, mapper: (item: unknown) => unknown): unknown[] {
  if (!Array.isArray(value)) fail()
  return value.map(mapper)
}
function summary(raw: unknown): WorkOrderSummary {
  const row = obj(raw, ['id', 'tenant_id', 'device_id', 'device_name', 'device_address', 'fault_event_id', 'title',
    'description', 'priority', 'due_at', 'status', 'assignee_user_id', 'assignee_name', 'revision', 'created_at',
    'updated_at', 'closed_at', 'cancelled_at'])
  return {
    id: text(row.id), tenant_id: text(row.tenant_id), device_id: text(row.device_id),
    device_name: nullableText(row.device_name), device_address: nullableText(row.device_address),
    fault_event_id: nullableText(row.fault_event_id), title: text(row.title), description: text(row.description),
    priority: priority(row.priority), due_at: nullableText(row.due_at), status: status(row.status),
    assignee_user_id: nullableText(row.assignee_user_id), assignee_name: nullableText(row.assignee_name),
    revision: integer(row.revision), created_at: text(row.created_at), updated_at: text(row.updated_at),
    closed_at: nullableText(row.closed_at), cancelled_at: nullableText(row.cancelled_at),
  }
}
function transition(raw: unknown): WorkOrderTransition {
  const row = obj(raw, ['id', 'action', 'from_status', 'to_status', 'actor_id', 'actor_name', 'reason',
    'assignee_user_id', 'revision_before', 'revision_after', 'work_log_id', 'created_at'])
  return { id: text(row.id), action: text(row.action), from_status: nullableText(row.from_status),
    to_status: text(row.to_status), actor_id: text(row.actor_id), actor_name: nullableText(row.actor_name),
    reason: text(row.reason), assignee_user_id: nullableText(row.assignee_user_id),
    revision_before: row.revision_before === null ? null : integer(row.revision_before),
    revision_after: integer(row.revision_after), work_log_id: nullableText(row.work_log_id),
    created_at: text(row.created_at) }
}
function workLog(raw: unknown): WorkOrderLog {
  const row = obj(raw, ['id', 'actor_id', 'actor_name', 'note', 'result', 'replacement_device_id',
    'replacement_device_name', 'created_at'])
  return { id: text(row.id), actor_id: text(row.actor_id), actor_name: nullableText(row.actor_name),
    note: text(row.note), result: result(row.result), replacement_device_id: nullableText(row.replacement_device_id),
    replacement_device_name: nullableText(row.replacement_device_name), created_at: text(row.created_at) }
}
function replacement(raw: unknown): WorkOrderReplacement {
  const row = obj(raw, ['id', 'work_log_id', 'source_device_id', 'replacement_device_id',
    'replacement_device_name', 'created_by', 'created_at'])
  return { id: text(row.id), work_log_id: text(row.work_log_id), source_device_id: text(row.source_device_id),
    replacement_device_id: text(row.replacement_device_id),
    replacement_device_name: nullableText(row.replacement_device_name), created_by: text(row.created_by),
    created_at: text(row.created_at) }
}
function detail(raw: unknown): WorkOrderDetail {
  const row = obj(raw, ['order', 'timeline', 'work_logs', 'replacements'])
  return { order: summary(row.order), timeline: array(row.timeline, transition) as WorkOrderTransition[],
    work_logs: array(row.work_logs, workLog) as WorkOrderLog[],
    replacements: array(row.replacements, replacement) as WorkOrderReplacement[] }
}
function page(raw: unknown): WorkOrderPage {
  const row = obj(raw, ['items', 'total', 'page', 'size'])
  return { items: array(row.items, summary) as WorkOrderSummary[], total: integer(row.total),
    page: integer(row.page, 1), size: integer(row.size, 1) }
}
export function decodeWorkOrderDetail(raw: string): WorkOrderDetail {
  return detail(parseJsonDocument(raw))
}
export function decodeWorkOrderPage(raw: string): WorkOrderPage {
  const value = page(parseJsonDocument(raw))
  if (value.items.length > value.size) fail()
  return value
}
export function decodeAssignees(raw: string): Assignee[] {
  return array(parseJsonDocument(raw), item => {
    const row = obj(item, ['user_id', 'username', 'name'])
    return { user_id: text(row.user_id), username: text(row.username), name: text(row.name) }
  }) as Assignee[]
}

function scopedQuery(params: URLSearchParams): string {
  const session = useSession.getState()
  if (session.audience === 'management') {
    params.set('scope_selected', 'true')
    params.set('scope_tenant_ids', session.scopeTenantIds.join(','))
    params.set('scope_place_ids', session.scopePlaceIds.join(','))
  }
  return `?${params}`
}
export function workOrderListQuery(input: { page: number; status: string; keyword: string }) {
  const params = new URLSearchParams({ page: String(input.page) })
  if (input.status) params.set('status', input.status)
  if (input.keyword) params.set('keyword', input.keyword)
  return scopedQuery(params)
}
export function getWorkOrders(input: { page: number; status: string; keyword: string }, signal: AbortSignal) {
  const session = useSession.getState()
  return request<WorkOrderPage>(`/${session.audience}/maintenance/work-orders${workOrderListQuery(input)}`,
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeWorkOrderPage })
}
export function getWorkOrder(id: string, signal: AbortSignal, tenantId?: string) {
  const session = useSession.getState()
  return request<WorkOrderDetail>(`/${session.audience}/maintenance/work-orders/${encodeURIComponent(id)}${scopedQuery(new URLSearchParams())}`,
    { tenantScoped: true, tenantOptional: true, tenantId, signal, decode: decodeWorkOrderDetail })
}
export function getAssignees(id: string, signal: AbortSignal, keyword = '') {
  const session = useSession.getState()
  const params = new URLSearchParams()
  if (keyword) params.set('keyword', keyword)
  return request<Assignee[]>(`/${session.audience}/maintenance/work-orders/${encodeURIComponent(id)}/assignee-candidates${scopedQuery(params)}`,
    { tenantScoped: true, tenantOptional: true, signal, decode: decodeAssignees })
}


