import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useState } from 'react'
import { parseSampleDrafts, sampleCaseLabels, sampleStateLabels, serializeSampleDrafts, syntheticSampleDrafts, type SampleDraft, type SampleEvent, type SampleMeasurementDraft, type SampleMeasurementState, type SampleCaseKind } from '@/lib/product-samples'
import { valueTypeLabels, type MeasurementDraft, type SchemaDraft } from '@/lib/product-schema'
import type { Capabilities } from '@/lib/types'
import { LexemeInput } from './product-measurement-editor'
import { ErrorNotice } from './common'

const caseKinds: SampleCaseKind[] = ['FIRE', 'RECOVERY', 'FAULT', 'HEARTBEAT', 'PARAM', 'OTHER', 'UNKNOWN', 'MALFORMED']
const sampleEvents: SampleEvent[] = ['FIRE', 'RECOVERY', 'FAULT', 'HEARTBEAT', 'PARAM', 'OTHER', 'UNKNOWN']
const states: SampleMeasurementState[] = ['VALID', 'MISSING', 'INVALID']

function defaultValue(declaration: MeasurementDraft): string { return declaration.valueType === 'BOOLEAN' ? 'true' : declaration.valueType === 'STRING' ? '' : '0' }

export function SampleEditor({ text, schema, capabilities, onChange }: { text: string; schema: SchemaDraft | null; capabilities: Capabilities; onChange: (text: string) => void }) {
  const drafts = parseSampleDrafts(text)
  const declarations = schema?.measurements ?? []
  const [fillError, setFillError] = useState<unknown>()
  function apply(next: SampleDraft[]) { try { onChange(serializeSampleDrafts(next)) } catch { /* LexemeInput only commits valid tokens; keep the last valid document. */ } }
  function addSample() {
    if (drafts === null) return
    const index = drafts.length + 1
    const measured = declarations.length > 0
    apply([...drafts, { name: `合成样例 ${index}`, caseKind: measured ? 'PARAM' : 'UNKNOWN', payload: '{}', expectedEvent: measured ? 'PARAM' : 'UNKNOWN', expectedBattery: '', expectedMeasurements: [] }])
  }
  return <section className="sample-editor" aria-label="合成样例编辑">
    <div className="notice">样例只保存在版本测试证据中，是明确合成的JSON输入；不是厂商协议，不连接设备，不产生事件/报警/通知。请勿粘贴生产密钥、token、密码或个人资料。</div>
    <div className="identity-form-actions">
      <Button type="button" variant="outline" disabled={schema === null} onClick={() => { if (schema === null) return; try { setFillError(undefined); apply(syntheticSampleDrafts(schema, capabilities)) } catch (error) { setFillError(error) } }}>填入四类合成示例（不会自动测试）</Button>
      <Button type="button" variant="outline" disabled={drafts === null} onClick={addSample}><Plus/>添加空样例</Button>
    </div>
    {fillError != null && <ErrorNotice error={fillError}/>}
    <p className="muted-note">“填入”会按当前能力与读数声明生成：只为SUPPORTED事件生成映射样例；纯读数产品不制造火警/恢复；不声明读数期望的样例表示该报文不应产生读数。电量经过非恒等缩放/偏移时不会自动编造期望电量，请手工补充有效电量样例。</p>
    {drafts === null
      ? <div className="notice" role="status">当前样例JSON无法用结构化表单表达（字段或类型不符合合同）。请使用下方高级JSON修正；测试提交仍以该JSON原文为准。</div>
      : drafts.length === 0 ? <p className="muted-note">尚无样例。可手工添加，或使用“填入”生成合成集合。</p>
        : drafts.map((draft, index) => {
          const patch = (next: Partial<SampleDraft>) => apply(drafts.map((item, itemIndex) => itemIndex === index ? { ...item, ...next } : item))
          return <div className="sample-row" key={index}>
            <div className="sample-row-header"><strong>样例 {index + 1}</strong><Button type="button" variant="ghost" size="sm" aria-label={`删除样例 ${index + 1}`} onClick={() => apply(drafts.filter((_, itemIndex) => itemIndex !== index))}><Trash2/>删除</Button></div>
            <div className="field"><Label htmlFor={`sample-name-${index}`}>名称</Label><Input id={`sample-name-${index}`} value={draft.name} onChange={event => patch({ name: event.target.value })}/></div>
            <div className="field"><Label htmlFor={`sample-kind-${index}`}>样例类别</Label><select id={`sample-kind-${index}`} className="native-select" value={draft.caseKind} onChange={event => {
              const caseKind = event.target.value as SampleCaseKind
              patch({ caseKind, ...(caseKind === 'MALFORMED' ? { expectedEvent: 'UNKNOWN' as const } : caseKind !== 'UNKNOWN' ? { expectedEvent: caseKind } : {}) })
            }}>{caseKinds.map(kind => <option key={kind} value={kind}>{sampleCaseLabels[kind]}</option>)}</select></div>
            <div className="field"><Label htmlFor={`sample-event-${index}`}>期望事件</Label><select id={`sample-event-${index}`} className="native-select" value={draft.expectedEvent} onChange={event => patch({ expectedEvent: event.target.value as SampleEvent })}>{sampleEvents.map(event => <option key={event} value={event}>{sampleCaseLabels[event]}</option>)}</select></div>
            <div className="field"><Label htmlFor={`sample-battery-${index}`}>期望电量（%，留空为null）</Label><LexemeInput id={`sample-battery-${index}`} label={`样例 ${index + 1} 期望电量`} value={draft.expectedBattery} onCommit={value => patch({ expectedBattery: value })}/></div>
            <div className="field"><Label htmlFor={`sample-payload-${index}`}>合成报文JSON（字符串内容）</Label><textarea id={`sample-payload-${index}`} className="json-editor" rows={3} spellCheck={false} value={draft.payload} onChange={event => patch({ payload: event.target.value })}/></div>
            {declarations.length > 0 && <div className="sample-measurements">
              <p className="muted-note">NUMBER的期望值是按声明缩放/偏移变换后的读数；报文payload中的原始值可以不同。MISSING/INVALID表示该字段无有效读数。</p>
              <div className="sample-row-header"><span>期望读数</span><Button type="button" variant="ghost" size="sm" onClick={() => {
                const used = new Set(draft.expectedMeasurements.map(measurement => measurement.key))
                const declaration = declarations.find(measurement => !used.has(measurement.key))
                if (!declaration) return
                patch({ expectedMeasurements: [...draft.expectedMeasurements, { key: declaration.key, state: 'VALID', valueText: defaultValue(declaration), valueKind: declaration.valueType }] })
              }}><Plus/>添加读数期望</Button></div>
              {draft.expectedMeasurements.map((measurement, measurementIndex) => {
                const declaration = declarations.find(item => item.key === measurement.key) ?? null
                const patchMeasurement = (next: Partial<SampleMeasurementDraft>) => patch({ expectedMeasurements: draft.expectedMeasurements.map((item, itemIndex) => itemIndex === measurementIndex ? { ...item, ...next } : item) })
                return <div className="sample-measurement-row" key={measurement.key}>
                  <div className="field"><Label htmlFor={`sample-measurement-key-${index}-${measurementIndex}`}>读数</Label><select id={`sample-measurement-key-${index}-${measurementIndex}`} className="native-select" value={measurement.key} onChange={event => {
                    const next = declarations.find(item => item.key === event.target.value)
                    patchMeasurement({ key: event.target.value, valueKind: next?.valueType ?? 'NUMBER', valueText: next && measurement.state === 'VALID' ? defaultValue(next) : '' })
                  }}>{declarations.map(item => <option key={item.key} value={item.key}>{item.label} · {item.key}</option>)}</select></div>
                  <div className="field"><Label htmlFor={`sample-measurement-state-${index}-${measurementIndex}`}>期望状态</Label><select id={`sample-measurement-state-${index}-${measurementIndex}`} className="native-select" value={measurement.state} onChange={event => {
                    const state = event.target.value as SampleMeasurementState
                    patchMeasurement({ state, ...(state === 'VALID' && declaration ? { valueText: defaultValue(declaration), valueKind: declaration.valueType } : { valueText: '' }) })
                  }}>{states.map(state => <option key={state} value={state}>{sampleStateLabels[state]}</option>)}</select></div>
                  {measurement.state === 'VALID' && declaration !== null && <div className="field"><Label htmlFor={`sample-measurement-value-${index}-${measurementIndex}`}>期望值{`（${valueTypeLabels[declaration.valueType]}）`}</Label>{declaration.valueType === 'BOOLEAN'
                    ? <select id={`sample-measurement-value-${index}-${measurementIndex}`} className="native-select" value={measurement.valueText} onChange={event => patchMeasurement({ valueText: event.target.value })}><option value="true">true</option><option value="false">false</option></select>
                    : declaration.valueType === 'NUMBER'
                      ? <LexemeInput id={`sample-measurement-value-${index}-${measurementIndex}`} label={`样例 ${index + 1} 读数 ${measurement.key} 期望值`} value={measurement.valueText} onCommit={value => patchMeasurement({ valueText: value })} allowEmpty={false}/>
                      : <Input id={`sample-measurement-value-${index}-${measurementIndex}`} value={measurement.valueText} onChange={event => patchMeasurement({ valueText: event.target.value })}/>}</div>}
                  <Button type="button" variant="ghost" size="sm" aria-label={`删除样例 ${index + 1} 读数 ${measurement.key}`} onClick={() => patch({ expectedMeasurements: draft.expectedMeasurements.filter((_, itemIndex) => itemIndex !== measurementIndex) })}><Trash2/></Button>
                </div>
              })}
            </div>}
          </div>
        })}
    <details className="secondary-controls" open={drafts === null}>
      <summary>高级 JSON（合成样例数组）</summary>
      <div className="field"><Label htmlFor="parser-samples">合成样例 JSON 数组</Label><textarea id="parser-samples" className="json-editor" rows={18} spellCheck={false} value={text} onChange={event => onChange(event.target.value)}/></div>
    </details>
  </section>
}
