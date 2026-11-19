import { jsonArrayItems, jsonFieldText } from '@/lib/json-document'
import { displayDecimal } from '@/lib/format'
import type { ProductReading } from '@/lib/types'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

export function ReadingValues({ readings, exactJson }: { readings: ProductReading[]; exactJson?: string }) {
  if (readings.length === 0) return null
  const spans = exactJson === undefined ? [] : jsonArrayItems(exactJson)
  return <section><h4>解析后的属性读数</h4><Table><TableHeader><TableRow><TableHead>属性</TableHead><TableHead>数值</TableHead><TableHead>类型 / 单位</TableHead></TableRow></TableHeader><TableBody>{readings.map((reading, index) => {
    const raw = spans[index] === undefined ? undefined : jsonFieldText(spans[index], 'value')
    const value = reading.value_type === 'NUMBER' ? raw === undefined ? '精确数值未提供' : displayDecimal(raw)
      : reading.value_type === 'BOOLEAN' ? reading.value ? '是（true）' : '否（false）' : String(reading.value)
    return <TableRow key={`${reading.key}:${index}`}><TableCell>{reading.label}<span className="table-sub mono">{reading.key}</span></TableCell><TableCell className="mono">{value}</TableCell><TableCell>{reading.value_type === 'NUMBER' ? '数值' : reading.value_type === 'BOOLEAN' ? '布尔' : '文本'}{reading.unit ? ` / ${reading.unit}` : ''}</TableCell></TableRow>
  })}</TableBody></Table></section>
}
