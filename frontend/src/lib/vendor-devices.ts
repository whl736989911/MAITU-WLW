import { request } from './api'
import { detailResponse, type CreateRegistrationBody, type RegistrationDetail } from './device-registrations'
import type { Device, Paginated } from './types'
import type { VendorDeviceImportRow } from './vendor-device-csv'

export type VendorDeviceSource = 'LEGACY_REALTIME' | 'HIKVISION_FIRECLOUD'
export interface LegacyRealtimeProfile {
  id: string
  name: string
  manufacturer: string
  model: string
  legacy_product_id: string
  decoder_ready: boolean
}
export interface AepOnboarding { revision: number; imei: string | null; upstream_ids: string[] }
export interface ConfirmAepBody { expected_revision: number; upstream_device_id: string }
export interface CreateVendorDeviceBody {
  source: VendorDeviceSource
  name: string
  serial: string
  place_id: string
  address?: string
  legacy_profile_id?: string
  upstream_device_id?: string
}
export interface ImportVendorDevicesBody {
  source: VendorDeviceSource
  rows: VendorDeviceImportRow[]
}
export interface VendorSpreadsheetTemplate {
  filename: string
  media_type: string
  content_base64: string
  content_sha256: string
}
function templateValue(raw: string): VendorSpreadsheetTemplate {
  const value = JSON.parse(raw) as Record<string, unknown>
  const filename = value.filename, mediaType = value.media_type, content = value.content_base64, digest = value.content_sha256
  if (typeof filename !== 'string' || !filename || /[\\/\u0000-\u001f]/.test(filename)) throw new Error('模板文件名无效')
  if (mediaType !== 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet') throw new Error('模板文件类型无效')
  if (typeof content !== 'string' || !content || content.length % 4 !== 0) throw new Error('模板文件内容无效')
  if (typeof digest !== 'string' || !/^[0-9a-f]{64}$/.test(digest)) throw new Error('模板文件摘要无效')
  return { filename, media_type: mediaType, content_base64: content, content_sha256: digest }
}
function gridValue(raw: string): { rows: string[][]; modelLabels: Record<string, string>; unit: { id: string; name: string } | null } {
  const value = JSON.parse(raw) as Record<string, unknown>
  if (!Array.isArray(value.rows) || value.rows.length < 1 || value.rows.length > 201) throw new Error('XLSX解析结果无效')
  const rows = value.rows.map(row => {
    if (!Array.isArray(row) || row.length < 1 || row.length > 16 || row.some(cell => typeof cell !== 'string')) throw new Error('XLSX解析行无效')
    return row as string[]
  })
  if (rows.some(row => row.length !== rows[0].length)) throw new Error('XLSX列数不一致')
  const labels = value.model_labels
  if (labels != null && (typeof labels !== 'object' || Array.isArray(labels) || Object.values(labels).some(label => typeof label !== 'string'))) throw new Error('XLSX型号选项无效')
  const unit = value.unit
  if (unit != null && (typeof unit !== 'object' || !('id' in unit) || !('name' in unit) || typeof unit.id !== 'string' || !unit.id || typeof unit.name !== 'string' || !unit.name)) throw new Error('XLSX归属单位无效')
  return { rows, modelLabels: (labels ?? {}) as Record<string, string>, unit: unit as { id: string; name: string } | null ?? null }
}

export const vendorDevicesApi = {
  legacyProfiles: (page: number, size: number, keyword: string, signal?: AbortSignal) =>
    request<Paginated<LegacyRealtimeProfile>>(`/management/vendor-devices/legacy-profiles?page=${page}&size=${size}&keyword=${encodeURIComponent(keyword)}`, { signal }),
  create: (body: CreateVendorDeviceBody, idempotencyKey: string, tenantId: string, signal?: AbortSignal) =>
    request<Device>('/management/vendor-devices', {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body),
      tenantScoped: true, tenantId, signal,
    }),
  aepOnboarding: (deviceId: string, tenantId: string, signal?: AbortSignal) =>
    request<AepOnboarding>(`/management/vendor-devices/${encodeURIComponent(deviceId)}/aep-onboarding`, { tenantScoped: true, tenantId, signal }),
  confirmAep: (deviceId: string, body: ConfirmAepBody, idempotencyKey: string, tenantId: string) =>
    request<AepOnboarding>(`/management/vendor-devices/${encodeURIComponent(deviceId)}/aep-onboarding`, {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body),
      tenantScoped: true, tenantId,
    }),
  createHikvision: (body: CreateRegistrationBody, idempotencyKey: string, tenantId: string, signal?: AbortSignal) =>
    request<RegistrationDetail>('/management/vendor-devices/hikvision-create', {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body),
      tenantScoped: true, tenantId, signal, decode: detailResponse,
    }),
  importRows: (body: ImportVendorDevicesBody, idempotencyKey: string, tenantId: string, signal?: AbortSignal) =>
    request<{ items: Device[] }>('/management/vendor-devices/import', {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body),
      tenantScoped: true, tenantId, signal,
    }),
  importTemplate: (source: VendorDeviceSource) =>
    request<VendorSpreadsheetTemplate>(`/management/vendor-devices/import/template?source=${encodeURIComponent(source)}${source === 'LEGACY_REALTIME' ? '&guided=true' : ''}`, { decode: templateValue }),
  previewSpreadsheet: (source: VendorDeviceSource, contentBase64: string) =>
    request<ReturnType<typeof gridValue>>('/management/vendor-devices/import/spreadsheet', {
      method: 'POST', body: JSON.stringify({ source, content_base64: contentBase64 }), decode: gridValue,
    }),
  previewLegacyTerminal: (contentBase64: string, tenantId: string, profileId?: string) =>
    request<{ rows: VendorDeviceImportRow[] }>('/management/vendor-devices/import/legacy-terminal/preview', {
      method: 'POST', body: JSON.stringify({ content_base64: contentBase64, legacy_profile_id: profileId }),
      tenantScoped: true, tenantId,
    }),
}
