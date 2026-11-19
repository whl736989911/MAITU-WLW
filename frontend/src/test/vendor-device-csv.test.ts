import { describe, expect, it } from 'vitest'
import { parseVendorDeviceGrid, parseVendorDeviceImport, vendorDeviceCsvHeaders, vendorDeviceCsvTemplate } from '@/lib/vendor-device-csv'
import type { PlaceNode } from '@/lib/types'

const place = (id: string, name = id): PlaceNode => ({ id, name, tenant_id: 'tenant-a', tenant_name: 'A', parent_id: null, legacy_place_id: null, device_count: 0, level: 0, revision: 0 })
const places = [place('room-a', '一层')]
const legacy = (rows: string) => vendorDeviceCsvTemplate('LEGACY_REALTIME') + rows
const hik = (rows: string) => vendorDeviceCsvTemplate('HIKVISION_FIRECLOUD') + rows

describe('来源设备CSV导入的输入与归属边界', () => {
  it('两套下载模板均能填写导入，海康不携带旧型号或旧标识', () => {
    expect(parseVendorDeviceImport(legacy('烟感A,EUI-A,room-a,,楼内,,UP-A\r\n'), 'LEGACY_REALTIME', places, 'legacy-model')).toEqual([{ name: '烟感A', serial: 'EUI-A', place_id: 'room-a', address: '楼内', legacy_profile_id: 'legacy-model', upstream_device_id: 'UP-A' }])
    expect(parseVendorDeviceImport(hik('海康A,SN-A,room-a,,楼内\r\n'), 'HIKVISION_FIRECLOUD', places, 'ignored-legacy-model')).toEqual([{ name: '海康A', serial: 'SN-A', place_id: 'room-a', address: '楼内' }])
    expect(() => parseVendorDeviceImport(hik('海康A,SN-A,room-a,,楼内'), 'LEGACY_REALTIME', places, 'legacy-model')).toThrow()
  })
  it('接受Excel BOM和完整引用表头，以及名称和地址中的逗号、双引号、换行', () => {
    const csv = '\uFEFF"设备名称","设备标识","位置ID","完整位置路径","地址"\r\n"烟感,一号",SN-A,room-a,,"一层\r\n门牌""A"""\r\n'
    expect(parseVendorDeviceImport(csv, 'HIKVISION_FIRECLOUD', places, '')).toEqual([{ name: '烟感,一号', serial: 'SN-A', place_id: 'room-a', address: '一层\r\n门牌"A"' }])
  })
  it('位置只能是当前单位的唯一ID或唯一完整路径，不能混填或按重名猜测', () => {
    expect(() => parseVendorDeviceImport(hik('A,SN-A,foreign-room,,'), 'HIKVISION_FIRECLOUD', places, '')).toThrow()
    expect(() => parseVendorDeviceImport(hik('A,SN-A,room-a,一层,'), 'HIKVISION_FIRECLOUD', places, '')).toThrow()
    expect(() => parseVendorDeviceImport(hik('A,SN-A,,一层,'), 'HIKVISION_FIRECLOUD', [...places, place('room-b', '一层')], '')).toThrow()
    expect(parseVendorDeviceImport(hik('A,SN-A,,一层,'), 'HIKVISION_FIRECLOUD', places, '')[0].place_id).toBe('room-a')
  })
  it('整文件拒绝第二行重复标识、坏列、未闭合引号或超过批次上限，不返回部分数据', () => {
    for (const rows of ['A,SN-A,room-a,,\r\nB,SN-A,room-a,,', 'A,SN-A,room-a,,\r\nB,SN-B', '"unfinished,SN-A,room-a,,', Array.from({ length: 201 }, (_, i) => `A${i},SN-${i},room-a,,`).join('\r\n')])
      expect(() => parseVendorDeviceImport(hik(rows), 'HIKVISION_FIRECLOUD', places, '')).toThrow()
  })
  it('旧型号和上报标识遵守真实接口长度与字符约束，行级型号优先于共用型号', () => {
    expect(parseVendorDeviceImport(legacy('A,EUI-A,room-a,,,row-model,UP-A'), 'LEGACY_REALTIME', places, 'shared-model')[0].legacy_profile_id).toBe('row-model')
    for (const row of ['A,EUI-A,room-a,,,,', 'A,EUI-A,room-a,,,' + 'x'.repeat(65) + ',UP-A', 'A,EUI-A,room-a,,,model,非法标识', 'A,EUI-A,room-a,,,model,' + 'x'.repeat(129)])
      expect(() => parseVendorDeviceImport(legacy(row), 'LEGACY_REALTIME', places, '')).toThrow()
  })
  it('文件内设备标识与上报标识不得互相占用，错误指向后出现的一行', () => {
    const shared = 'UP-' + 'x'.repeat(10)
    expect(() => parseVendorDeviceImport(legacy(`A,EUI-A,room-a,,,model,${shared}\r\nB,EUI-B,room-a,,,model,${shared}`), 'LEGACY_REALTIME', places, '')).toThrow(/第 3 行.*重复/)
    expect(() => parseVendorDeviceImport(legacy(`A,EUI-A,room-a,,,model,${shared}\r\nB,${shared},room-a,,,model,`), 'LEGACY_REALTIME', places, '')).toThrow(/第 3 行.*重复/)
  })
  it('XLSX 单元格网格复用 CSV 的严格表头、位置和行校验', () => {
    const headers = [...vendorDeviceCsvHeaders.LEGACY_REALTIME]
    expect(parseVendorDeviceGrid([headers, ['烟感A', 'EUI-A', 'room-a', '', '', 'model', 'UP-A']], 'LEGACY_REALTIME', places, '')).toEqual([{ name: '烟感A', serial: 'EUI-A', place_id: 'room-a', legacy_profile_id: 'model', upstream_device_id: 'UP-A' }])
    expect(() => parseVendorDeviceGrid([headers, ['A', 'EUI-A', 'foreign-room', '', '', 'model', '']], 'LEGACY_REALTIME', places, '')).toThrow(/第 2 行位置/)
    expect(() => parseVendorDeviceGrid([[...headers, '多余列'], ['A', 'EUI-A', 'room-a', '', '', 'model', '', '']], 'LEGACY_REALTIME', places, '')).toThrow(/表头/)
  })
})
