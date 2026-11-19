import { ApiError, request } from './api'

export type RegistrationMode = 'NEW_VENDOR' | 'MATCH_EXISTING' | 'PRODUCT_MODEL'
export type RegistrationStatus = 'DRAFT' | 'CLOUD_PENDING' | 'CLOUD_REGISTERED' | 'COMMISSIONED' | 'FAILED' | 'UNKNOWN' | 'ABANDONED'
export type CloudState = 'NOT_REQUIRED' | 'NOT_ATTEMPTED' | 'IN_FLIGHT' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export type LocalState = 'NOT_READY' | 'READY' | 'SUCCEEDED' | 'FAILED'

export interface RegistrationSummary {
  id: string
  driver_key: string
  mode: RegistrationMode
  status: RegistrationStatus
  revision: number
  tenant_id: string | null
  place_id: string | null
  product_version_id: string | null
  device_id: string | null
  external_device_id: string | null
  external_serial: string
  device_name: string | null
  device_model: string | null
  cloud_state: CloudState
  local_state: LocalState
  created_at: string
  updated_at: string
  commissioned_at: string | null
}
export interface RegistrationEvent {
  id: string
  step: string
  outcome: string
  detail: unknown
  actor_id: string
  occurred_at: string
}
export interface RegistrationDetail {
  registration: RegistrationSummary
  device_type: number | null
  communication_type: number | null
  access_mode: number | null
  producter_id: number | null
  product_model_id: number | null
  vendor_unit_id: string | null
  verification_code_stored: boolean
  vendor_payload_stored: boolean
  vendor_payload_fields: string[]
  location: string | null
  coordinates: Record<string, unknown>
  auto_add_channel: boolean | null
  account_fingerprint: string | null
  cloud_result: unknown
  acceptance_note: string | null
  created_by: string
  events: RegistrationEvent[]
}
export interface CreateRegistrationBody {
  mode: RegistrationMode
  driver_key: string
  external_serial: string
  device_name?: string
  device_model?: string
  device_type?: number
  communication_type?: number
  access_mode?: number
  producter_id?: number
  product_model_id?: number
  vendor_unit_id?: string
  verification_code?: string
  location?: string
  latitude?: number
  longitude?: number
  auto_add_channel?: boolean
  external_device_id?: string
  vendor_payload?: Record<string, unknown>
  tenant_id?: string
  place_id?: string
  product_version_id?: string
}
export interface CommissionBody {
  tenant_id: string
  place_id: string
  product_version_id?: string
  existing_device_id?: string
  existing_device_revision?: number
  acceptance_note: string
  device_name?: string
  address?: string
  system_type?: string
  device_category?: string
}

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function bad(): never { throw new ApiError(200, 'REGISTRATION_RESPONSE_UNCONFIRMED', '登记响应未确认；不以本地推测替代') }
function text(value: unknown, nullable = false): string | null {
  if (value === null) { if (nullable) return null; bad() }
  if (typeof value !== 'string') bad()
  return value
}
function integer(value: unknown, nullable = false): number | null {
  if (value === null) { if (nullable) return null; bad() }
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) bad()
  return value
}
function summary(raw: unknown): RegistrationSummary {
  if (!object(raw)) return bad()
  return {
    id: text(raw.id) as string,
    driver_key: text(raw.driver_key) as string,
    mode: raw.mode as RegistrationMode,
    status: raw.status as RegistrationStatus,
    revision: integer(raw.revision) as number,
    tenant_id: text(raw.tenant_id, true),
    place_id: text(raw.place_id, true),
    product_version_id: text(raw.product_version_id, true),
    device_id: text(raw.device_id, true),
    external_device_id: text(raw.external_device_id, true),
    external_serial: text(raw.external_serial) as string,
    device_name: text(raw.device_name, true),
    device_model: text(raw.device_model, true),
    cloud_state: raw.cloud_state as CloudState,
    local_state: raw.local_state as LocalState,
    created_at: text(raw.created_at) as string,
    updated_at: text(raw.updated_at) as string,
    commissioned_at: text(raw.commissioned_at, true),
  }
}
function event(raw: unknown): RegistrationEvent {
  if (!object(raw)) return bad()
  return {
    id: text(raw.id) as string,
    step: text(raw.step) as string,
    outcome: text(raw.outcome) as string,
    detail: raw.detail,
    actor_id: text(raw.actor_id) as string,
    occurred_at: text(raw.occurred_at) as string,
  }
}
export function detailResponse(raw: string): RegistrationDetail {
  const parsed: unknown = JSON.parse(raw)
  const events = object(parsed) ? parsed.events : undefined
  const payloadFields = object(parsed) ? parsed.vendor_payload_fields : undefined
  if (!object(parsed) || !Array.isArray(events) || !object(parsed.coordinates)) return bad()
  if (!Array.isArray(payloadFields) || payloadFields.some(field => typeof field !== 'string')) return bad()
  return {
    registration: summary(parsed.registration),
    device_type: integer(parsed.device_type, true),
    communication_type: integer(parsed.communication_type, true),
    access_mode: integer(parsed.access_mode, true),
    producter_id: integer(parsed.producter_id, true),
    product_model_id: integer(parsed.product_model_id, true),
    vendor_unit_id: text(parsed.vendor_unit_id, true),
    verification_code_stored: typeof parsed.verification_code_stored === 'boolean' ? parsed.verification_code_stored : bad(),
    vendor_payload_stored: typeof parsed.vendor_payload_stored === 'boolean' ? parsed.vendor_payload_stored : bad(),
    vendor_payload_fields: payloadFields as string[],
    location: text(parsed.location, true),
    coordinates: parsed.coordinates,
    auto_add_channel: parsed.auto_add_channel === null ? null : typeof parsed.auto_add_channel === 'boolean' ? parsed.auto_add_channel : bad(),
    account_fingerprint: text(parsed.account_fingerprint, true),
    cloud_result: parsed.cloud_result,
    acceptance_note: text(parsed.acceptance_note, true),
    created_by: text(parsed.created_by) as string,
    events: events.map(event),
  }
}
export function pageResponse(raw: string): { items: RegistrationSummary[]; total: number; page: number; size: number } {
  const parsed: unknown = JSON.parse(raw)
  const items = object(parsed) ? parsed.items : undefined
  if (!object(parsed) || !Array.isArray(items)) return bad()
  return { items: items.map(summary), total: integer(parsed.total) as number, page: integer(parsed.page) as number, size: integer(parsed.size) as number }
}

const post = <T,>(path: string, body: unknown, key: string, signal: AbortSignal | undefined, decode: (raw: string) => T, tenantId?: string) =>
  request<T>(path, { tenantScoped: !!tenantId, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode })

export const deviceRegistrationsApi = {
  list: (page: number, tenantId: string | undefined, signal?: AbortSignal) =>
    request<{ items: RegistrationSummary[]; total: number; page: number; size: number }>(`/management/device-registrations?page=${page}`, { tenantScoped: true, tenantOptional: true, tenantId, signal, decode: pageResponse }),
  create: (body: CreateRegistrationBody, key: string, tenantId: string | undefined, signal?: AbortSignal) =>
    post<RegistrationDetail>('/management/device-registrations', body, key, signal, detailResponse, tenantId),
  detail: (id: string, signal?: AbortSignal) =>
    request<RegistrationDetail>(`/management/device-registrations/${encodeURIComponent(id)}`, { signal, decode: detailResponse }),
  resume: (id: string, key: string, signal?: AbortSignal) =>
    post<RegistrationDetail>(`/management/device-registrations/${encodeURIComponent(id)}/resume`, {}, key, signal, detailResponse),
  reconcile: (id: string, key: string, signal?: AbortSignal) =>
    post<RegistrationDetail>(`/management/device-registrations/${encodeURIComponent(id)}/reconcile`, {}, key, signal, detailResponse),
  commission: (id: string, body: CommissionBody, key: string, signal?: AbortSignal) =>
    post<RegistrationDetail>(`/management/device-registrations/${encodeURIComponent(id)}/commission`, body, key, signal, detailResponse),
  abandon: (id: string, reason: string, key: string, signal?: AbortSignal) =>
    post<RegistrationDetail>(`/management/device-registrations/${encodeURIComponent(id)}/abandon`, { reason }, key, signal, detailResponse),
}

// ---- old TTL/TSF ledger registration fields ----
// The typed fields mirror the exact add bodies the legacy drivers validate and send. No free-form JSON
// document is accepted: every field is either a checked integer, checked text, checked hex credential,
// or absent. Credential values are never echoed by the API (the detail only reports field names).

export type LegacyVendorFamily = 'LEGACY_TTL' | 'LEGACY_TSF'

export interface TtlVendorFields {
  productId: string
  applicationId: string
  platformId: string
  networkModel: string
  parentId: string
  node: string
  productCategory: string
  describe: string
  profile: string
  appKey: string
  appEui: string
  devAddr: string
  nwkSKey: string
  appSKey: string
}
export interface TsfVendorFields {
  productId: string
  applicationId: string
  platformId: string
  networkModel: string
  placeId: string
  node: string
  parentId: string
  companyId: string
  address: string
  gps: string
  childSystem: string
  installMsg: string
  show: '' | 'true' | 'false'
  push: '' | 'true' | 'false'
  contactIds: string
}
export interface LegacyVendorDraft {
  family: LegacyVendorFamily
  ttl: TtlVendorFields
  tsf: TsfVendorFields
}

export function legacyVendorFamily(driverKey: string): LegacyVendorFamily | null {
  if (driverKey === 'LEGACY_TTL') return 'LEGACY_TTL'
  if (driverKey === 'LEGACY_TSF') return 'LEGACY_TSF'
  return null
}

export function emptyLegacyVendorDraft(driverKey: string): LegacyVendorDraft | null {
  const family = legacyVendorFamily(driverKey)
  if (!family) return null
  return {
    family,
    ttl: { productId: '', applicationId: '', platformId: '', networkModel: '', parentId: '', node: '', productCategory: '', describe: '', profile: '', appKey: '', appEui: '', devAddr: '', nwkSKey: '', appSKey: '' },
    tsf: { productId: '', applicationId: '', platformId: '', networkModel: '', placeId: '', node: '', parentId: '', companyId: '', address: '', gps: '', childSystem: '', installMsg: '', show: '', push: '', contactIds: '' },
  }
}

/** 1-200 safe ASCII for TSF, 5-32 for the TTL dEui contract. */
export function validateLegacySerial(family: LegacyVendorFamily, serial: string): string | null {
  const value = serial.trim()
  if (family === 'LEGACY_TTL') return /^[\x21-\x7e]{5,32}$/.test(value) ? null : '旧TTL设备编号(dEui)必须是5-32位安全ASCII'
  return /^[\x21-\x7e]{1,200}$/.test(value) ? null : '旧TSF设备编号(dEui)必须是1-200位安全ASCII'
}

/** TSF dName is capped at 20 characters by the audited add contract; TTL only requires a non-blank name. */
export function validateLegacyName(family: LegacyVendorFamily, name: string): string | null {
  const value = name.trim()
  if (!value) return '旧台账新增登记必须填写设备名称'
  if (family === 'LEGACY_TSF' && value.length > 20) return '旧TSF设备名称(dName)必须为1-20字符'
  return null
}

function integerText(value: string, label: string, minimum: number): number {
  const raw = value.trim()
  if (!/^\d+$/.test(raw)) throw new Error(`${label}必须是整数`)
  const parsed = Number(raw)
  if (!Number.isSafeInteger(parsed) || parsed < minimum) throw new Error(`${label}必须是不小于${minimum}的整数`)
  return parsed
}
function optionalIntegerText(value: string, label: string, minimum: number): number | undefined {
  return value.trim() === '' ? undefined : integerText(value, label, minimum)
}
function requiredText(value: string, label: string, maxLength: number): string {
  const text = value.trim()
  if (!text) throw new Error(`${label}不能为空`)
  if (text.length > maxLength) throw new Error(`${label}不能超过${maxLength}字符`)
  return text
}
function optionalText(value: string, label: string, maxLength: number): string | undefined {
  const text = value.trim()
  if (!text) return undefined
  if (text.length > maxLength) throw new Error(`${label}不能超过${maxLength}字符`)
  return text
}
function hexCredential(value: string, label: string, length: number): string | undefined {
  const text = value.trim()
  if (!text) return undefined
  if (!new RegExp(`^[0-9a-fA-F]{${length}}$`).test(text)) throw new Error(`${label}必须是${length}位十六进制`)
  return text
}
function contactIdList(value: string): number[] | undefined {
  const text = value.trim()
  if (!text) return undefined
  const parts = text.split(',').map(part => part.trim())
  if (parts.some(part => !/^\d+$/.test(part))) throw new Error('联系人ID必须是以逗号分隔的整数')
  const ids = parts.map(part => Number(part))
  if (ids.some(id => !Number.isSafeInteger(id))) throw new Error('联系人ID超出安全整数范围')
  return ids
}

function ttlDeviceAttr(ttl: TtlVendorFields, networkModel: number): Record<string, unknown> | undefined {
  const ot = [ttl.appKey, ttl.appEui].some(value => value.trim() !== '')
  const abp = [ttl.devAddr, ttl.nwkSKey, ttl.appSKey].some(value => value.trim() !== '')
  const hasLoRaMaterial = ot || abp || ttl.profile.trim() !== ''
  if (hasLoRaMaterial && networkModel !== 1 && networkModel !== 2)
    throw new Error('LoRa凭据/Profile只适用于网络模式1(OTAA)或2(ABP)')
  if (ot && networkModel !== 1) throw new Error('AppKey/AppEUI是OTAA凭据，网络模式必须为1(OTAA)')
  if (abp && networkModel !== 2) throw new Error('DevAddr/NwkSKey/AppSKey是ABP凭据，网络模式必须为2(ABP)')
  const attr: Record<string, unknown> = {}
  if (networkModel === 1) {
    const appKey = hexCredential(ttl.appKey, 'OTAA AppKey', 32)
    const appEui = hexCredential(ttl.appEui, 'OTAA AppEUI', 16)
    if (appKey) attr.AppKey = appKey
    if (appEui) attr.AppEUI = appEui
  }
  if (networkModel === 2) {
    const devAddr = hexCredential(ttl.devAddr, 'ABP DevAddr', 8)
    const nwkSKey = hexCredential(ttl.nwkSKey, 'ABP NwkSKey', 32)
    const appSKey = hexCredential(ttl.appSKey, 'ABP AppSKey', 32)
    if (devAddr) attr.DevAddr = devAddr
    if (nwkSKey) attr.NwkSKey = nwkSKey
    if (appSKey) attr.AppSKey = appSKey
  }
  const profile = optionalText(ttl.profile, 'LoRa Profile', 100)
  if (profile) attr.Profile = profile
  return Object.keys(attr).length ? attr : undefined
}

/** Builds the exact vendor payload accepted by the legacy TTL/TSF drivers. Throws on invalid input. */
export function buildLegacyVendorPayload(draft: LegacyVendorDraft): Record<string, unknown> {
  if (draft.family === 'LEGACY_TTL') {
    const ttl = draft.ttl
    const networkModel = integerText(ttl.networkModel, '旧TTL网络模式(dNetworkModel)', 0)
    if (![0, 1, 2, 101].includes(networkModel)) throw new Error('旧TTL网络模式只能是0(NB-IoT)/1(OTAA)/2(ABP)/101(其他)')
    const payload: Record<string, unknown> = {
      productId: integerText(ttl.productId, '旧TTL产品ID(dPid)', 0),
      applicationId: integerText(ttl.applicationId, '旧TTL应用ID(dAid)', 0),
      platformId: integerText(ttl.platformId, '旧TTL平台ID(dPlatform)', 0),
      networkModel,
    }
    const parentId = optionalIntegerText(ttl.parentId, '旧TTL父设备ID(dParentId)', 0)
    const node = optionalIntegerText(ttl.node, '旧TTL节点号(dNode)', 0)
    const productCategory = optionalIntegerText(ttl.productCategory, '旧TTL产品类别(dProductCategory)', 0)
    if (parentId !== undefined) payload.parentId = parentId
    if (node !== undefined) payload.node = node
    if (productCategory !== undefined) payload.productCategory = productCategory
    const describe = optionalText(ttl.describe, '旧TTL说明(dDescribe)', 500)
    if (describe) payload.describe = describe
    const deviceAttr = ttlDeviceAttr(ttl, networkModel)
    if (deviceAttr) payload.deviceAttr = deviceAttr
    return payload
  }
  const tsf = draft.tsf
  const payload: Record<string, unknown> = {
    productId: integerText(tsf.productId, '旧TSF产品ID(dProductId)', 0),
    applicationId: integerText(tsf.applicationId, '旧TSF应用ID(dAid)', 0),
    platformId: integerText(tsf.platformId, '旧TSF平台ID(dPlatform)', 0),
    networkModel: integerText(tsf.networkModel, '旧TSF网络模式(dNetworkModel)', 0),
    placeId: integerText(tsf.placeId, '旧TSF场所ID(dPid)', 0),
    address: requiredText(tsf.address, '旧TSF安装地址(dAddress)', 500),
    gps: requiredText(tsf.gps, '旧TSF GPS坐标(dGps)', 200),
    childSystem: requiredText(tsf.childSystem, '旧TSF所属系统(dChildSystem)', 100),
  }
  const node = optionalIntegerText(tsf.node, '旧TSF节点号(dNode)', 0)
  const parentId = optionalIntegerText(tsf.parentId, '旧TSF父设备ID(dParentId)', 0)
  if (node === undefined && parentId === undefined) throw new Error('旧TSF新增登记必须提供节点号(dNode)或父设备ID(dParentId)')
  if (node !== undefined) payload.node = node
  if (parentId !== undefined) payload.parentId = parentId
  const companyId = optionalIntegerText(tsf.companyId, '旧TSF单位ID(dCompanyId)', 0)
  if (companyId !== undefined) payload.companyId = companyId
  const installMsg = optionalText(tsf.installMsg, '旧TSF安装信息(dInstallMsg)', 1000)
  if (installMsg) payload.installMsg = installMsg
  if (tsf.show !== '') payload.show = tsf.show === 'true'
  if (tsf.push !== '') payload.push = tsf.push === 'true'
  const contactIds = contactIdList(tsf.contactIds)
  if (contactIds) payload.contactIds = contactIds
  return payload
}
