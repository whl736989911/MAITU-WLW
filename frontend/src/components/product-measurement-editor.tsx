import { useEffect, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  emptyMapping, emptyMeasurement, eventTypeLabels, isNumberLexeme, mappingRawKindLabels,
  parseSchemaDraft, valueTypeLabels, type BatteryDraft, type MappingDraft, type MeasurementDraft,
  type ProductEventType, type SchemaDraft,
} from '@/lib/product-schema'
import type { Capabilities } from '@/lib/types'

/** Decimal text input that never hands an incomplete or exponent token to the document serializer. */
export function LexemeInput({ id, label, value, onCommit, allowEmpty = true }: { id?: string; label: string; value: string; onCommit: (value: string) => void; allowEmpty?: boolean }) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  function commit() {
    const next = text.trim()
    if (next === value) return
    if ((next === '' && !allowEmpty) || (next !== '' && !isNumberLexeme(next))) { setText(value); return }
    onCommit(next)
  }
  return <Input id={id} aria-label={label} inputMode="decimal" value={text} onChange={event => setText(event.target.value)} onBlur={commit} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); commit() } }}/>
}

function eventsSupported(capabilities: Capabilities): ProductEventType[] {
  return (['FIRE', 'RECOVERY', 'FAULT', 'HEARTBEAT'] as const).filter(type => {
    const key = type === 'FIRE' ? 'fire' : type === 'RECOVERY' ? 'recovery' : type === 'FAULT' ? 'fault' : 'heartbeat'
    return capabilities[key] === 'SUPPORTED'
  })
}

export function SchemaEditor({ draft, schemaText, capabilities, onChangeDraft, onChangeText }: {
  draft: SchemaDraft | null; schemaText: string; capabilities: Capabilities
  onChangeDraft: (draft: SchemaDraft) => void; onChangeText: (text: string) => void
}) {
  function update(next: SchemaDraft) { onChangeDraft(next) }
  return <>
    <section className="typed-schema-editor" aria-label="结构化Schema编辑">
      <h3>事件映射</h3>
      {draft === null ? <div className="notice" role="status">当前Schema不是可表单化的 JSON_POINTER_V1 对象，或含有无法识别的结构。请使用下方高级JSON修正；保存仍以该JSON原文为准。</div> : <>
        {draft.eventMappings.length > 0 && <div className="field"><Label htmlFor="event-pointer">事件路径（JSON Pointer）</Label><Input id="event-pointer" value={draft.eventPointer} placeholder="/event" onChange={event => update({ ...draft, eventPointer: event.target.value })}/></div>}
        {draft.eventMappings.map((mapping, index) => <div className="mapping-row" key={index}>
          <div className="field"><Label htmlFor={`mapping-kind-${index}`}>原始值类型</Label><select id={`mapping-kind-${index}`} className="native-select" value={mapping.rawKind} onChange={event => {
            const kind = event.target.value as MappingDraft['rawKind']
            update({ ...draft, eventMappings: draft.eventMappings.map((item, itemIndex) => itemIndex === index ? { ...item, rawKind: kind, rawText: kind === 'STRING' ? '' : kind === 'NUMBER' ? '0' : 'true' } : item) })
          }}>{(['STRING', 'NUMBER', 'BOOLEAN'] as const).map(kind => <option key={kind} value={kind}>{mappingRawKindLabels[kind]}</option>)}</select></div>
          <div className="field"><Label htmlFor={`mapping-value-${index}`}>原始值</Label>{mapping.rawKind === 'BOOLEAN'
            ? <select id={`mapping-value-${index}`} className="native-select" value={mapping.rawText} onChange={event => update({ ...draft, eventMappings: draft.eventMappings.map((item, itemIndex) => itemIndex === index ? { ...item, rawText: event.target.value } : item) })}><option value="true">true</option><option value="false">false</option></select>
            : mapping.rawKind === 'NUMBER'
              ? <LexemeInput id={`mapping-value-${index}`} label={`映射 ${index + 1} 原始数字`} value={mapping.rawText} onCommit={value => update({ ...draft, eventMappings: draft.eventMappings.map((item, itemIndex) => itemIndex === index ? { ...item, rawText: value } : item) })} allowEmpty={false}/>
              : <Input id={`mapping-value-${index}`} value={mapping.rawText} onChange={event => update({ ...draft, eventMappings: draft.eventMappings.map((item, itemIndex) => itemIndex === index ? { ...item, rawText: event.target.value } : item) })}/>}</div>
          <div className="field"><Label htmlFor={`mapping-event-${index}`}>映射事件</Label><select id={`mapping-event-${index}`} className="native-select" value={mapping.eventType} onChange={event => update({ ...draft, eventMappings: draft.eventMappings.map((item, itemIndex) => itemIndex === index ? { ...item, eventType: event.target.value as ProductEventType } : item) })}>{(['FIRE', 'RECOVERY', 'FAULT', 'HEARTBEAT', 'PARAM', 'OTHER'] as const).map(type => <option key={type} value={type}>{eventTypeLabels[type]}</option>)}</select></div>
          <Button type="button" variant="ghost" size="sm" aria-label={`删除映射 ${index + 1}`} onClick={() => update({ ...draft, eventMappings: draft.eventMappings.filter((_, itemIndex) => itemIndex !== index) })}><Trash2/>删除</Button>
        </div>)}
        <div className="identity-form-actions">
          <Button type="button" variant="outline" onClick={() => update({ ...draft, eventMappings: [...draft.eventMappings, emptyMapping()] })}><Plus/>添加事件映射</Button>
          {draft.eventMappings.length > 0 && <Button type="button" variant="outline" onClick={() => update({ ...draft, eventPointer: '', eventMappings: [] })}>清除事件映射（仅读数）</Button>}
        </div>
        <p className="muted-note">纯温湿度/心跳等读数产品可以没有事件映射。已声明为支持的事件应提供对应映射；服务端在发布时校验一致性。未知或未映射事件不会被猜成火警或恢复。</p>
        <h3>监测读数声明</h3>
        <p className="muted-note">最多64条；标识为稳定字段名。NUMBER可配置缩放/偏移/范围，数值按十进制字面量保存，不经过浏览器浮点数。STRING/BOOLEAN不允许数值变换。读取值缺失或类型不符时显示缺失/无效，不补0。</p>
        {draft.measurements.map((measurement, index) => {
          const patch = (next: Partial<MeasurementDraft>) => update({ ...draft, measurements: draft.measurements.map((item, itemIndex) => itemIndex === index ? { ...item, ...next } : item) })
          return <div className="measurement-row" key={index}>
            <div className="field"><Label htmlFor={`measurement-key-${index}`}>标识</Label><Input id={`measurement-key-${index}`} value={measurement.key} placeholder="temperature" onChange={event => patch({ key: event.target.value })}/></div>
            <div className="field"><Label htmlFor={`measurement-label-${index}`}>显示名称</Label><Input id={`measurement-label-${index}`} value={measurement.label} placeholder="温度" onChange={event => patch({ label: event.target.value })}/></div>
            <div className="field"><Label htmlFor={`measurement-pointer-${index}`}>路径（JSON Pointer）</Label><Input id={`measurement-pointer-${index}`} value={measurement.pointer} placeholder="/temperature" onChange={event => patch({ pointer: event.target.value })}/></div>
            <div className="field"><Label htmlFor={`measurement-type-${index}`}>值类型</Label><select id={`measurement-type-${index}`} className="native-select" value={measurement.valueType} onChange={event => {
              const valueType = event.target.value as MeasurementDraft['valueType']
              patch(valueType === 'NUMBER' ? { valueType } : { valueType, scale: '', offset: '', min: '', max: '' })
            }}>{(['NUMBER', 'STRING', 'BOOLEAN'] as const).map(type => <option key={type} value={type}>{valueTypeLabels[type]}</option>)}</select></div>
            <div className="field"><Label htmlFor={`measurement-unit-${index}`}>单位</Label><Input id={`measurement-unit-${index}`} value={measurement.unit} placeholder="℃" onChange={event => patch({ unit: event.target.value })}/></div>
            {measurement.valueType === 'NUMBER' && <>
              <div className="field"><Label htmlFor={`measurement-scale-${index}`}>缩放</Label><LexemeInput id={`measurement-scale-${index}`} label={`读数 ${index + 1} 缩放`} value={measurement.scale} onCommit={value => patch({ scale: value })}/></div>
              <div className="field"><Label htmlFor={`measurement-offset-${index}`}>偏移</Label><LexemeInput id={`measurement-offset-${index}`} label={`读数 ${index + 1} 偏移`} value={measurement.offset} onCommit={value => patch({ offset: value })}/></div>
              <div className="field"><Label htmlFor={`measurement-min-${index}`}>最小值</Label><LexemeInput id={`measurement-min-${index}`} label={`读数 ${index + 1} 最小值`} value={measurement.min} onCommit={value => patch({ min: value })}/></div>
              <div className="field"><Label htmlFor={`measurement-max-${index}`}>最大值</Label><LexemeInput id={`measurement-max-${index}`} label={`读数 ${index + 1} 最大值`} value={measurement.max} onCommit={value => patch({ max: value })}/></div>
            </>}
            <div className="field"><Label htmlFor={`measurement-required-${index}`}>是否必需</Label><select id={`measurement-required-${index}`} className="native-select" value={measurement.required ? 'true' : 'false'} onChange={event => patch({ required: event.target.value === 'true' })}><option value="false">可选</option><option value="true">必需</option></select></div>
            <Button type="button" variant="ghost" size="sm" aria-label={`删除读数 ${index + 1}`} onClick={() => update({ ...draft, measurements: draft.measurements.filter((_, itemIndex) => itemIndex !== index) })}><Trash2/>删除</Button>
          </div>
        })}
        <Button type="button" variant="outline" onClick={() => update({ ...draft, measurements: [...draft.measurements, emptyMeasurement()] })}><Plus/>添加监测读数</Button>
        <h3>电量字段映射（schema）</h3>
        {draft.battery === null ? <><p className="muted-note">未声明电量字段。电量低阈值只在声明电量能力且配置映射后可用。</p><Button type="button" variant="outline" onClick={() => update({ ...draft, battery: { pointer: '/battery', scale: '1', offset: '0', min: '0', max: '100' } })}>添加电量映射</Button></>
          : <div className="measurement-row"><BatteryFields battery={draft.battery} onChange={battery => update({ ...draft, battery })}/><Button type="button" variant="ghost" size="sm" onClick={() => update({ ...draft, battery: null })}><Trash2/>清除</Button></div>}
        <p className="muted-note">能力声明支持的事件：{eventsSupported(capabilities).map(type => eventTypeLabels[type]).join('、') || '未声明支持的事件'}。模板声明不等于硬件已验证。</p>
      </>}
    </section>
    <details className="secondary-controls" open={draft === null}>
      <summary>高级 JSON（与结构化表单表达同一 JSON_POINTER_V1）</summary>
      <div className="field"><Label htmlFor="schema-json">Schema JSON</Label><textarea id="schema-json" className="json-editor" rows={16} spellCheck={false} value={schemaText} onChange={event => onChangeText(event.target.value)}/><p className="muted-note">只允许JSON_POINTER_V1；不支持脚本、通配符或递归。重复JSON键/非有限数值不会被静默覆盖或归零。</p></div>
    </details>
  </>
}

const batteryFieldLabels: Record<'scale' | 'offset' | 'min' | 'max', string> = { scale: '缩放', offset: '偏移', min: '最小值', max: '最大值' }
function BatteryFields({ battery, onChange }: { battery: BatteryDraft; onChange: (battery: BatteryDraft) => void }) {
  return <>
    <div className="field"><Label htmlFor="battery-schema-pointer">电量路径（JSON Pointer）</Label><Input id="battery-schema-pointer" value={battery.pointer} onChange={event => onChange({ ...battery, pointer: event.target.value })}/></div>
    {(['scale', 'offset', 'min', 'max'] as const).map(key => <div className="field" key={key}><Label htmlFor={`battery-schema-${key}`}>{batteryFieldLabels[key]}</Label><LexemeInput id={`battery-schema-${key}`} label={`电量映射 ${batteryFieldLabels[key]}`} value={battery[key]} onCommit={value => { const next = { ...battery }; next[key] = value; onChange(next) }} allowEmpty={false}/></div>)}
  </>
}

/** Read-only structured projection; falls back to raw JSON when the document cannot be modelled. */
export function SchemaSummary({ schemaText }: { schemaText: string }) {
  const draft = parseSchemaDraft(schemaText)
  if (draft === null) return <pre className="json-preview">{schemaText}</pre>
  return <div className="schema-summary">
    {draft.eventMappings.length > 0
      ? <Table><TableHeader><TableRow><TableHead>事件路径</TableHead><TableHead>原始值</TableHead><TableHead>声明事件</TableHead></TableRow></TableHeader><TableBody><TableRow><TableCell className="mono">{draft.eventPointer}</TableCell><TableCell className="mono">{draft.eventMappings.map(mapping => `${mappingRawKindLabels[mapping.rawKind]} ${mapping.rawText}`).join('、')}</TableCell><TableCell>{draft.eventMappings.map(mapping => eventTypeLabels[mapping.eventType]).join('、')}</TableCell></TableRow></TableBody></Table>
      : <p className="muted-note">未声明事件映射（纯读数模板）。</p>}
    <Table><TableHeader><TableRow><TableHead>读数</TableHead><TableHead>路径</TableHead><TableHead>类型 / 单位</TableHead><TableHead>变换 / 范围</TableHead><TableHead>必需</TableHead></TableRow></TableHeader><TableBody>
      {draft.measurements.map(measurement => <TableRow key={measurement.key}><TableCell><strong>{measurement.label}</strong><div className="mono muted-note">{measurement.key}</div></TableCell><TableCell className="mono">{measurement.pointer}</TableCell><TableCell><Badge variant="outline">{valueTypeLabels[measurement.valueType]}</Badge> {measurement.unit || '未声明单位'}</TableCell><TableCell className="mono">{measurement.valueType === 'NUMBER' ? [measurement.scale && `×${measurement.scale}`, measurement.offset && `${measurement.offset}偏移`, measurement.min && `≥${measurement.min}`, measurement.max && `≤${measurement.max}`].filter(Boolean).join(' ') || '未声明变换' : '不适用'}</TableCell><TableCell>{measurement.required ? '必需' : '可选'}</TableCell></TableRow>)}
    </TableBody></Table>
    {draft.measurements.length === 0 && <p className="muted-note">未声明监测读数。</p>}
    {draft.battery && <p className="muted-note">电量字段：{draft.battery.pointer} · ×{draft.battery.scale} · 偏移 {draft.battery.offset} · 范围 {draft.battery.min}..{draft.battery.max}</p>}
    <details className="secondary-controls"><summary>高级 JSON（原始schema）</summary><pre className="json-preview">{schemaText}</pre></details>
  </div>
}
