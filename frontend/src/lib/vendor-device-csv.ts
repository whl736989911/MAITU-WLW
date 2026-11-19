import type { PlaceNode } from './types'
import { placePath } from './place-tree'
import type { VendorDeviceSource } from './vendor-devices'

const englishHeaders: Record<VendorDeviceSource, string[]> = {
  LEGACY_REALTIME: ['name', 'serial', 'place_id', 'place_path', 'address', 'legacy_profile_id', 'upstream_device_id'],
  HIKVISION_FIRECLOUD: ['name', 'serial', 'place_id', 'place_path', 'address'],
}
export const vendorDeviceCsvHeaders: Record<VendorDeviceSource, string[]> = {
  LEGACY_REALTIME: ['设备名称', '设备标识', '位置ID', '完整位置路径', '地址', '旧型号配置ID', '上报标识'],
  HIKVISION_FIRECLOUD: ['设备名称', '设备标识', '位置ID', '完整位置路径', '地址'],
}
export interface VendorDeviceImportRow {
  name: string
  serial: string
  place_id: string
  address?: string
  legacy_profile_id?: string
  upstream_device_id?: string
  source_row?: number
}

export function parseVendorDeviceCsv(text: string): string[][] {
  text = text.replace(/^\uFEFF/, '')
  const rows: string[][] = []
  let row: string[] = [], cell = '', quoted = false, closedQuote = false
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') { cell += '"'; index++ }
      else if (char === '"') { quoted = false; closedQuote = true }
      else cell += char
      continue
    }
    if (closedQuote && char !== ',' && char !== '\r' && char !== '\n') throw new Error('CSV 闭合引号后只能跟逗号或换行')
    if (char === '"') {
      if (cell.length || closedQuote) throw new Error('CSV 引号只能出现在单元格开头')
      quoted = true
      continue
    }
    if (char === ',') { row.push(cell); cell = ''; closedQuote = false; continue }
    if (char === '\r' || char === '\n') {
      if (char === '\r' && text[index + 1] === '\n') index++
      row.push(cell)
      if (row.some(value => value.length > 0)) rows.push(row)
      row = []; cell = ''; closedQuote = false
      continue
    }
    cell += char
  }
  if (quoted) throw new Error('CSV 存在未闭合引号')
  row.push(cell)
  if (row.some(value => value.length > 0)) rows.push(row)
  return rows
}

export function parseVendorDeviceImport(text: string, source: VendorDeviceSource, places: PlaceNode[], commonProfileId: string): VendorDeviceImportRow[] {
  return vendorDeviceRows(parseVendorDeviceCsv(text), source, places, commonProfileId)
}

/** Shared preflight for CSV text and XLSX cells; the first row must be the exact template header. */
export function parseVendorDeviceGrid(grid: string[][], source: VendorDeviceSource, places: PlaceNode[], commonProfileId: string): VendorDeviceImportRow[] {
  if (!grid.length) throw new Error('表格为空')
  return vendorDeviceRows(grid, source, places, commonProfileId)
}

function vendorDeviceRows(parsed: string[][], source: VendorDeviceSource, places: PlaceNode[], commonProfileId: string): VendorDeviceImportRow[] {
  if (!parsed.length) throw new Error('CSV 文件为空')
  const actualHeaders = parsed[0].map(value => value.trim())
  const localHeaders = vendorDeviceCsvHeaders[source], apiHeaders = englishHeaders[source]
  const matchesLocal = actualHeaders.length === localHeaders.length && localHeaders.every((header, index) => actualHeaders[index] === header)
  const matchesApi = actualHeaders.length === apiHeaders.length && apiHeaders.every((header, index) => actualHeaders[index] === header)
  if (!matchesLocal && !matchesApi) throw new Error(`表格表头必须严格为：${localHeaders.join(',')}`)
  const mapHeaders = apiHeaders
  if (parsed.length - 1 > 200) throw new Error('单次最多导入200行')
  const claimed = new Map<string, number>(), rows: VendorDeviceImportRow[] = []
  for (const [index, values] of parsed.slice(1).entries()) {
    const rowNo = index + 2
    if (values.every(value => !value.trim())) continue
    if (values.length !== mapHeaders.length) throw new Error(`第 ${rowNo} 行字段数不正确`)
    const fields = Object.fromEntries(mapHeaders.map((header, fieldIndex) => [header, values[fieldIndex]])) as Record<string, string>
    const name = fields.name.trim(), serial = fields.serial.trim(), placeId = fields.place_id.trim(), locationPath = fields.place_path.trim()
    if (!name || name.length > 200) throw new Error(`第 ${rowNo} 行名称必填且最多200字符`)
    if (!/^[\x21-\x7e]{1,64}$/.test(serial)) throw new Error(`第 ${rowNo} 行 EUI/SN 须为1–64位安全ASCII字符`)
    if (claimed.has(serial)) throw new Error(`第 ${rowNo} 行设备标识或上报标识与文件内第 ${claimed.get(serial)} 行重复：${serial}`)
    claimed.set(serial, rowNo)
    if (fields.address.length > 500) throw new Error(`第 ${rowNo} 行地址最多500字符`)
    if (!!placeId === !!locationPath) throw new Error(`第 ${rowNo} 行必须且只能填写位置ID或完整位置路径`)
    const matches = placeId
      ? places.filter(place => place.id === placeId)
      : places.filter(place => placePath(place, places) === locationPath)
    if (matches.length !== 1) throw new Error(`第 ${rowNo} 行位置${matches.length ? '路径不唯一' : '无法在当前单位中精确匹配'}`)
    const output: VendorDeviceImportRow = { name, serial, place_id: matches[0].id }
    if (fields.address.trim()) output.address = fields.address.trim()
    if (source === 'LEGACY_REALTIME') {
      const rowProfileId = fields.legacy_profile_id.trim() || commonProfileId
      if (!rowProfileId || rowProfileId.length > 64) throw new Error(`第 ${rowNo} 行须选择共用旧型号或填写有效的旧型号配置ID（最多64字符）`)
      if (fields.upstream_device_id.trim() && !/^[\x21-\x7e]{1,128}$/.test(fields.upstream_device_id.trim())) throw new Error(`第 ${rowNo} 行上报标识须为1–128位安全ASCII字符`)
      const upstream = fields.upstream_device_id.trim()
      if (upstream && upstream !== serial) {
        if (claimed.has(upstream)) throw new Error(`第 ${rowNo} 行上报标识与文件内第 ${claimed.get(upstream)} 行设备标识或上报标识重复：${upstream}`)
        claimed.set(upstream, rowNo)
      }
      output.legacy_profile_id = rowProfileId
      if (upstream) output.upstream_device_id = upstream
    }
    rows.push(output)
  }
  if (!rows.length) throw new Error('表格中没有设备数据行')
  return rows
}

export function vendorDeviceCsvTemplate(source: VendorDeviceSource): string {
  return `\uFEFF${vendorDeviceCsvHeaders[source].join(',')}\r\n`
}
