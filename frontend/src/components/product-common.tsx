import { useState } from 'react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { battery, reported } from '@/lib/format'
import type { Capabilities, CapabilityState, ParserTestRow, ProductReading, ProductVersion } from '@/lib/types'
import { capabilityLabels as capabilityNames, declaredCapabilityKeys, isNumberLexeme, productCapabilities, valueTypeLabels, type MeasurementValueType } from '@/lib/product-schema'
import { jsonArrayItems, jsonFieldText } from '@/lib/json-document'
import { Badge } from './ui/badge'
import { Label } from './ui/label'
import { ErrorNotice, Loading, Pager } from './common'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Button } from './ui/button'
export const capabilityLabels: Record<CapabilityState, string> = { SUPPORTED: '声明支持', UNSUPPORTED: '声明不支持', PENDING: '待确认' }
export function CapabilityBadges({ capabilities }: { capabilities: Capabilities }) {
  const typed = productCapabilities(capabilities)
  return <div className="capability-badges">{declaredCapabilityKeys(capabilities).map(key => <Badge key={key} variant="outline" className={typed[key] === 'PENDING' ? 'status status-warning' : 'status status-neutral'}>{capabilityNames[key]} · {capabilityLabels[typed[key] ?? 'PENDING'] ?? '未知声明'}</Badge>)}</div>
}

type ReadingEvidence = { kind: 'absent' } | { kind: 'invalid' } | { kind: 'ok'; items: ProductReading[] }
const measurementValueTypes: Record<MeasurementValueType, true> = { NUMBER: true, STRING: true, BOOLEAN: true }
function decodeStringRaw(raw: string | undefined): string | null | undefined {
  if (raw === undefined || raw === 'null') return null
  if (!raw.startsWith('"')) return undefined
  const value: unknown = JSON.parse(raw)
  return typeof value === 'string' ? value : undefined
}
/** Backend can return exact reading lexemes; never run a stored decimal through a host Number. */
function evidenceFromRaw(raw: string): ReadingEvidence {
  try {
    const values: unknown = JSON.parse(raw)
    if (!Array.isArray(values)) return { kind: 'invalid' }
    const spans = jsonArrayItems(raw)
    const items = values.map((entry, index): ProductReading => {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new Error('读数证据无效')
      const record = entry as Record<string, unknown>
      if (typeof record.key !== 'string' || typeof record.label !== 'string') throw new Error('读数证据无效')
      const valueType = record.value_type
      if (typeof valueType !== 'string' || !Object.hasOwn(measurementValueTypes, valueType)) throw new Error('读数证据无效')
      const unitRaw = jsonFieldText(spans[index], 'unit'), valueRaw = jsonFieldText(spans[index], 'value'), unit = decodeStringRaw(unitRaw)
      if (unit === undefined || valueRaw === undefined) throw new Error('读数证据无效')
      const value = valueRaw.startsWith('"') ? (JSON.parse(valueRaw) as unknown) : valueRaw
      if (typeof value !== 'string') throw new Error('读数证据无效')
      return { key: record.key, label: record.label, value, value_type: valueType as MeasurementValueType, unit }
    })
    return { kind: 'ok', items }
  } catch { return { kind: 'invalid' } }
}
function rowReadings(row: ParserTestRow): ReadingEvidence {
  const record = row as unknown as Record<string, unknown>
  if (typeof record.readings_json === 'string') return evidenceFromRaw(record.readings_json)
  const readings = record.readings
  if (readings === undefined) return { kind: 'absent' }
  if (!Array.isArray(readings)) return { kind: 'invalid' }
  try {
    return { kind: 'ok', items: readings.map(entry => {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new Error('读数证据无效')
      const reading = entry as Record<string, unknown>
      if (typeof reading.key !== 'string' || typeof reading.label !== 'string' || typeof reading.value_type !== 'string' || !Object.hasOwn(measurementValueTypes, reading.value_type)) throw new Error('读数证据无效')
      if ((reading.unit !== null && typeof reading.unit !== 'string') || (typeof reading.value !== 'string' && typeof reading.value !== 'number' && typeof reading.value !== 'boolean')) throw new Error('读数证据无效')
      return { key: reading.key, label: reading.label, value: String(reading.value), value_type: reading.value_type as MeasurementValueType, unit: reading.unit as string | null }
    }) }
  } catch { return { kind: 'invalid' } }
}

export function ParserResults({ passed, results }: { passed: boolean; results: ParserTestRow[] }) {
  const rows = results.map(result => ({ result, evidence: rowReadings(result) }))
  const showReadings = rows.some(row => row.evidence.kind !== 'absent')
  return <div className="parser-results"><div className={`notice ${passed ? '' : 'parser-failed'}`} role="status">{passed ? '合成样例整体通过（不代表厂商协议已验证）' : '合成样例整体未通过或覆盖不足，不可据此发布'}</div><Table><TableHeader><TableRow><TableHead>合成样例</TableHead><TableHead>结果 / 事件</TableHead><TableHead>电量</TableHead>{showReadings && <TableHead>声明读数证据</TableHead>}<TableHead>原始事件与诊断</TableHead></TableRow></TableHeader><TableBody>{rows.map(({ result, evidence }, index) => <TableRow key={`${result.name}:${index}`}><TableCell>{result.name}</TableCell><TableCell>{result.passed ? '样例通过' : '样例未通过'}<div className="table-sub mono">{result.event_type}</div></TableCell><TableCell>{battery(result.battery_percent)}</TableCell>{showReadings && <TableCell>{evidence.kind === 'invalid' ? <span className="dashboard-unknown">读数证据格式未确认</span> : evidence.kind === 'ok' ? evidence.items.length === 0 ? <span className="muted-note">无读数</span> : evidence.items.map(reading => <span className="monitoring-reading" key={reading.key}>{reading.label} {reading.value}{reading.unit ? ` ${reading.unit}` : ''}{reading.value_type !== 'NUMBER' && <Badge variant="outline">{valueTypeLabels[reading.value_type]}</Badge>}</span>) : <span className="muted-note">未提供</span>}</TableCell>}<TableCell><code className="mono">{result.raw_event == null ? 'null（未映射 / 缺失）' : result.raw_event_json ?? JSON.stringify(result.raw_event)}</code>{result.diagnostics.map((diagnostic, i) => <p className="muted-note" key={i}>{diagnostic}</p>)}</TableCell></TableRow>)}</TableBody></Table></div>
}
export function PublishedVersionPicker({ value, onChange, lockedProductId, excludedVersionId }: { value: string; onChange: (id: string, version: ProductVersion | null) => void; lockedProductId?: string | null; excludedVersionId?: string | null }) {
  const allowed = useSession(s => s.audience === 'management' && s.permissions.includes('products:read'))
  const [page, setPage] = useState(1)
  const [productId, setProductId] = useState(lockedProductId ?? '')
  const products = useResource(`pick-products:${page}`, signal => api.products(page, '', signal), allowed && !lockedProductId)
  const versions = useResource(`pick-versions:${productId}`, signal => api.versions(productId, signal), allowed && !!productId)
  const publishedVersions = versions.data?.items.filter(item => item.status === 'PUBLISHED') ?? []
  const hasSelectableVersion = publishedVersions.some(item => item.id !== excludedVersionId)
  if (!allowed) return <div className="notice">需要产品目录读取权限才能明确选择已发布版本；不会使用默认最新版本或伪造候选。</div>
  return <div className="form-stack">
    <div className="field">
      <Label htmlFor="binding-product">产品</Label>
      {lockedProductId ? <div className="identity-readonly mono">固定同产品：{lockedProductId}</div>
        : products.loading ? <Loading/>
        : products.error != null ? <ErrorNotice error={products.error} retry={products.refresh}/>
        : <>
          <select className="native-select" id="binding-product" value={productId} required onChange={e => { setProductId(e.target.value); onChange('', null) }}>
            <option value="">请显式选择产品</option>
            {products.data?.items.map(item => <option value={item.id} key={item.id}>{item.name} · {item.manufacturer} / {item.model}</option>)}
          </select>
          {products.data && <Pager page={products.data.page} size={products.data.size} total={products.data.total} onChange={setPage}/>}
        </>}
    </div>
    {productId && <div className="field">
      <div className="section-header">
        <Label htmlFor="binding-version">具体已发布版本</Label>
        <Button type="button" variant="outline" size="sm" disabled={versions.loading} onClick={versions.refresh}>刷新已发布版本</Button>
      </div>
      {versions.loading ? <Loading/>
        : versions.error != null ? <ErrorNotice error={versions.error} retry={versions.refresh}/>
        : <>
          <select className="native-select" id="binding-version" value={value} required={hasSelectableVersion} disabled={!hasSelectableVersion} onChange={e => {
            const chosen = publishedVersions.find(item => item.id === e.target.value && item.id !== excludedVersionId)
            onChange(chosen?.id ?? '', chosen ?? null)
          }}>
            <option value="">{hasSelectableVersion ? '请选择具体版本（不会默认最新）' : '暂无可选的已发布版本'}</option>
            {publishedVersions.map(item => <option key={item.id} value={item.id} disabled={item.id === excludedVersionId}>v{item.version_number} · {item.id}{item.id === excludedVersionId ? '（当前绑定，不可重复迁移）' : ''}</option>)}
          </select>
          {!hasSelectableVersion && <div className="notice" role="status">
            <strong>{publishedVersions.length ? '该产品暂无其他已发布版本可用于迁移。' : '该产品尚无已发布版本；草稿不能用于设备开户或产品模型登记。'}</strong>
            <p>产品模型接入：请在“设备与产品 → 产品模板 → 查看版本”完成协议配置、样例校验和发布，再返回此处点击“刷新已发布版本”，无需重填当前表单。系统不会自动发布草稿。</p>
            <p>已有厂商设备：海康云设备请使用“接入与开放 → 海康云接入 → 发现与归属”；已导入的旧平台设备请在“设备管理”查找并调整归属，不要为关联这些设备重复创建产品模型。</p>
          </div>}
        </>}
    </div>}
    <p className="muted-note">绑定版本固定不漂移。新版本发布不会自动替换任何设备；只有明确提交迁移才会改变绑定。{reported(excludedVersionId) !== '未上报' ? ` 当前绑定：${excludedVersionId}` : ''}</p>
  </div>
}
