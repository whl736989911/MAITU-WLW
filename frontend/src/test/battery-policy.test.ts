import { describe, expect, it } from 'vitest'
import { batteryRawForRow } from '@/lib/battery-json'
import { devicesResponse } from '@/lib/processing-json'
import type { BatteryPolicy } from '@/lib/types'
const observed = (battery: string | null, comparator: 'LT' | 'LTE', threshold: string, status: BatteryPolicy['status']) => {
  const row = { battery_percent: battery === null ? null : Number(battery), battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status, comparator, threshold: Number(threshold), source_scope: 'DEVICE', source_id: 'device-a', source_revision: 2, evaluated_at: '2026-10-30T00:00:00Z' } }
  const json = JSON.stringify(row).replace(`\"battery_percent\":${battery === null ? 'null' : String(Number(battery))}`, `\"battery_percent\":${battery ?? 'null'}`).replace(`\"threshold\":${String(Number(threshold))}`, `\"threshold\":${threshold}`)
  return batteryRawForRow(json, row)
}

describe('电量阈值观察严格读取', () => {
  it('null不是0，明确零值使用LT/LTE边界语义', () => {
    expect(observed(null, 'LT', '0', 'NO_DATA').status).toBe('NO_DATA')
    expect(observed('0', 'LT', '0', 'NORMAL').status).toBe('NORMAL')
    expect(observed('0', 'LTE', '0', 'LOW').status).toBe('LOW')
  })
  it('以36位原始十进制词法比较，不把数值舍入后误过边界', () => {
    expect(observed('0.100000000000000000000000000000000000', 'LT', '0.100000000000000000000000000000000001', 'LOW').status).toBe('LOW')
    expect(() => observed('0.100000000000000000000000000000000001', 'LTE', '0.100000000000000000000000000000000000', 'LOW')).toThrow()
    expect(observed('0.100000000000000000000000000000000001', 'LTE', '0.100000000000000000000000000000000000', 'NORMAL').status).toBe('NORMAL')
    expect(observed('0', 'LT', '0E-36', 'NORMAL').threshold_json).toBe('0E-36')
    expect(() => observed('0', 'LT', '100.000000000000000000000000000000000001', 'NORMAL')).toThrow()
  })
  it('未配置必须没有阈值来源，configured source and comparator remain paired', () => {
    const row = { battery_percent: 0, battery_policy: { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', status: 'NOT_CONFIGURED', comparator: null, threshold: null, source_scope: 'NONE', source_id: null, source_revision: 0, evaluated_at: '2026-10-30T00:00:00Z' } }
    expect(batteryRawForRow(JSON.stringify(row), row).status).toBe('NOT_CONFIGURED')
    expect(() => batteryRawForRow(JSON.stringify({ ...row, battery_policy: { ...row.battery_policy, status: 'NORMAL' } }), { ...row, battery_policy: { ...row.battery_policy, status: 'NORMAL' } })).toThrow()
  })
  it('设备列表解码保留原始值词法并拒绝缺失的新观察字段', () => {
    const value = '10.000000000000000000000000000000000000', threshold = '10.000000000000000000000000000000000001'
    const wire = `{"items":[{"id":"d","battery_percent":${value},"battery_policy":{"parameter_key":"battery_low_threshold_percent","metric_key":"battery_percent","unit":"%","status":"LOW","comparator":"LTE","threshold":${threshold},"source_scope":"DEVICE","source_id":"d","source_revision":2,"evaluated_at":"2026-10-30T00:00:00Z"}}],"total":1,"page":1,"size":20}`
    const decoded = devicesResponse(wire, batteryRawForRow)
    expect(decoded.items[0].battery_percent_json).toBe(value)
    expect(decoded.items[0].battery_policy.threshold_json).toBe(threshold)
    expect(() => devicesResponse(wire.replace(/,"battery_policy":\{[^}]+\}/, ''), batteryRawForRow)).toThrow()
  })
})
