import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '@/lib/store'
import { compileDeviceDraft, deviceMetricDefinitions, emptyDeviceDraft, emptyDeviceFilters, getDeviceWorkbench, readDeviceWorkbench } from '@/lib/device-workbench'
import { management, response } from './fixtures'

const asOf = '2026-10-25T05:30:00.123456Z'
const device = { id: 'real-device', tenant_id: 'tenant-a', tenant_name: '单位A', name: '真实烟感', device_eui: 'eui-1', model: '型号', address: '一层', connectivity: 'ONLINE', physical_state: 'FIRE', last_message_at: asOf, battery_percent: 0, product_id: null, product_version_id: null, product_name: null, version_number: null, revision: 1, place_id: 'place-a', place_name: '一层', integration_state: 'CONNECTED', system_type: null, device_category: null, offline_policy: { key: 'offline_threshold_minutes', value: 5, unit: '分钟', source_scope: 'GLOBAL', source_id: null, source_revision: 0, evaluated_at: asOf }, battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'LOW', comparator: 'LTE', threshold: 18, source_scope: 'DEVICE', source_id: 'real-device', source_revision: 1, evaluated_at: asOf } }
const metrics = { device_total: 1, online: 1, offline: 0, unactivated: 0, unknown_connectivity: 0, fire: 1, normal: 0, unknown: 0, battery_low: 1, battery_normal: 0, battery_not_configured: 0, battery_no_data: 0 }
const page = { items: [device], total: 1, page: 1, size: 20, as_of: asOf, timezone: 'Asia/Shanghai', filters: emptyDeviceFilters, metrics, metric_definitions: deviceMetricDefinitions }
const json = (value: unknown) => JSON.stringify(value)
function login() { useSession.getState().login({ ...management, permissions: ['device:read'] }, 'management'); useSession.getState().setScope(['tenant-a'], [], true) }
beforeEach(() => { useSession.getState().clear(); vi.restoreAllMocks() })

describe('真实设备工作台合同', () => {
  it('只暴露真实状态筛选；关键字、设备状态和类别按规范序列化', () => {
    expect(Object.keys(emptyDeviceFilters)).toEqual(['keyword', 'connectivity', 'physical_state', 'system_type', 'device_category'])
    expect(compileDeviceDraft({ ...emptyDeviceDraft, keyword: '  一层  ', connectivity: 'ONLINE', physical_state: 'FIRE', system_type: '烟感', device_category: '室内' })).toEqual({ keyword: '一层', connectivity: 'ONLINE', physical_state: 'FIRE', system_type: '烟感', device_category: '室内' })
    expect(() => compileDeviceDraft({ ...emptyDeviceDraft, connectivity: 'CONNECTED' })).toThrow()
  })
  it('未知连接不映射在线/未激活，按UNKNOWN指标合计并支持显式筛选', () => {
    const row = { ...device, connectivity: 'UNKNOWN' }, filters = { ...emptyDeviceFilters, connectivity: 'UNKNOWN' }
    const unknownPage = { ...page, items: [row], filters, metrics: { ...metrics, online: 0, unknown_connectivity: 1 } }
    expect(compileDeviceDraft({ ...emptyDeviceDraft, connectivity: 'UNKNOWN' }).connectivity).toBe('UNKNOWN')
    expect(readDeviceWorkbench(json(unknownPage), filters, 1, undefined, 'tenant-a').metrics.unknown_connectivity).toBe(1)
    expect(() => readDeviceWorkbench(json({ ...unknownPage, metrics: { ...unknownPage.metrics, online: 1, unknown_connectivity: 0 } }), filters, 1, undefined, 'tenant-a')).toThrow()
  })
  it('保留真实设备的0电量、原始报警状态及服务端字段白名单', () => {
    const result = readDeviceWorkbench(json({ ...page, private_field: 'must not escape', items: [{ ...device, private_field: 'must not escape' }] }), emptyDeviceFilters, 1, undefined, 'tenant-a')
    expect(result.items[0].battery_percent).toBe(0)
    expect(result.items[0].physical_state).toBe('FIRE')
    expect(json(result)).not.toContain('private_field')
    expect(result.metrics.device_total).toBe(1)
  })
  it('拒绝跨单位、分页错配和无效输入', () => {
    expect(() => readDeviceWorkbench(json({ ...page, items: [{ ...device, tenant_id: 'tenant-b' }] }), emptyDeviceFilters, 1, undefined, 'tenant-a')).toThrow()
    expect(() => readDeviceWorkbench(json(page), emptyDeviceFilters, 2, undefined, 'tenant-a')).toThrow()
    expect(() => compileDeviceDraft({ ...emptyDeviceDraft, keyword: 'bad\0' })).toThrow()
  })
  it('读取授权范围设备工作台并发送真实筛选', async () => {
    login(); const filters = { ...emptyDeviceFilters, connectivity: 'ONLINE', physical_state: 'FIRE' }; const fetcher = vi.fn().mockResolvedValue(response({ ...page, filters })); vi.stubGlobal('fetch', fetcher)
    await getDeviceWorkbench(filters, 1)
    const [url] = fetcher.mock.calls[0]; const query = new URL(url, 'http://local').searchParams
    expect(url).toContain('/api/management/device-workbench?')
    expect(query.get('connectivity')).toBe('ONLINE'); expect(query.get('physical_state')).toBe('FIRE')
  })
})
