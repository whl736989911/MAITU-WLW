import { useState, type FormEvent } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { errorMessage } from '@/lib/api'
import { deviceServicesApi, type RiskLevel, type ServiceCategory, type ServiceDefinition, type SupportState } from '@/lib/device-services'
import type { ProductVersion } from '@/lib/types'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Badge } from './ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from './ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Empty, ErrorNotice, Loading } from './common'
import { OperationConfirmation, useMutationAction } from './identity-common'

type Editor = { mode: 'create' } | { mode: 'edit'; definition: ServiceDefinition }

const emptySchema = '{"fields":[]}'
const emptyObject = '{}'

export function ProductServiceDefinitions({ version, onChanged }: { version: ProductVersion; onChanged: () => void }) {
  const canWrite = useSession(s => s.permissions.includes('products:write'))
  const list = useResource(`service-definitions:${version.id}:${version.revision}`, signal => deviceServicesApi.definitions(version.id, signal))
  const [editor, setEditor] = useState<Editor | null>(null)
  return <Card className="product-versions"><CardHeader className="section-header"><div><CardTitle>服务操作定义</CardTitle><p>随产品版本发布冻结；声明能力不等于厂商已接通，未验证操作不可下发。</p></div>{canWrite && version.status === 'DRAFT' && <Button variant="outline" onClick={() => setEditor({ mode: 'create' })}><Plus/>登记服务操作</Button>}</CardHeader><CardContent>
    {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : list.data && (list.data.length
      ? <Table><TableHeader><TableRow><TableHead>操作</TableHead><TableHead>类别 / 风险</TableHead><TableHead>结果类型</TableHead><TableHead>能力证据</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>
        {list.data.map(definition => <TableRow key={definition.id ?? definition.service_key}>
          <TableCell><strong>{definition.display_name}</strong><div className="mono muted-note">{definition.service_key} · revision {definition.revision}</div></TableCell>
          <TableCell>{definition.category} · {definition.risk_level === 'ENGINEERING' ? '工程高风险' : '常规'}</TableCell>
          <TableCell>{definition.result_kind}</TableCell>
          <TableCell><Badge variant="outline">{definition.support_state}</Badge>{!definition.dispatch_ready && <div className="muted-note">{definition.dispatch_blocked_code}: {definition.dispatch_blocked_reason}</div>}</TableCell>
          <TableCell>{canWrite && version.status === 'DRAFT' && definition.id && <div className="identity-row-actions"><Button variant="ghost" size="sm" onClick={() => setEditor({ mode: 'edit', definition })}>编辑</Button><DeleteDefinition definition={definition} onDone={() => { list.refresh(); onChanged() }}/></div>}</TableCell>
        </TableRow>)}
      </TableBody></Table>
      : <Empty title="该版本尚未登记服务操作">已发布版本没有服务定义时，设备只能查看能力元数据，不能执行任何服务操作。</Empty>)}
    {editor && <DefinitionForm key={editor.mode === 'edit' ? editor.definition.id ?? '' : 'new'} version={version} editor={editor} onCancel={() => setEditor(null)} onSaved={() => { setEditor(null); list.refresh(); onChanged() }}/>}
  </CardContent></Card>
}

function DefinitionForm({ version, editor, onCancel, onSaved }: { version: ProductVersion; editor: Editor; onCancel: () => void; onSaved: () => void }) {
  const existing = editor.mode === 'edit' ? editor.definition : null
  const [operationKey, setOperationKey] = useState(existing?.service_key ?? '')
  const [displayName, setDisplayName] = useState(existing?.display_name ?? '')
  const [category, setCategory] = useState<ServiceCategory>(existing?.category ?? 'QUERY')
  const [risk, setRisk] = useState<RiskLevel>(existing?.risk_level ?? 'NORMAL')
  const [resultKind, setResultKind] = useState(existing?.result_kind ?? 'COMMAND')
  const [support, setSupport] = useState<SupportState>(existing?.support_state ?? 'UNVERIFIED')
  const [schemaText, setSchemaText] = useState(existing ? JSON.stringify(existing.input_schema, null, 2) : emptySchema)
  const [applicabilityText, setApplicabilityText] = useState(existing ? JSON.stringify(existing.applicability, null, 2) : emptyObject)
  const [evidenceText, setEvidenceText] = useState(existing ? JSON.stringify(existing.evidence, null, 2) : emptyObject)
  const action = useMutationAction()
  function parseObject(text: string, name: string): Record<string, unknown> {
    const value: unknown = JSON.parse(text)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${name}必须是JSON对象`)
    return value as Record<string, unknown>
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (!displayName.trim()) throw new Error('请填写服务名称')
      if (editor.mode === 'create' && !/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(operationKey.trim())) throw new Error('操作key格式无效')
      const schema = parseObject(schemaText, '输入schema')
      const applicability = parseObject(applicabilityText, '适用性')
      const evidence = parseObject(evidenceText, '证据')
      if (support === 'VERIFIED' && typeof evidence.basis !== 'string') throw new Error('声明VERIFIED必须提供evidence.basis')
      if (!action.confirming) { action.setError(undefined); action.setConfirming(true); return }
      if (editor.mode === 'create') {
        void action.run((key, signal) => deviceServicesApi.createDefinition(version.id, {
          operation_key: operationKey.trim(), display_name: displayName.trim(), category, risk_level: risk,
          result_kind: resultKind, input_schema: schema, applicability, evidence, support_state: support,
          expected_version_revision: version.revision,
        }, key, signal), onSaved)
      } else if (existing?.id) {
        void action.run((key, signal) => deviceServicesApi.updateDefinition(existing.id!, {
          display_name: displayName.trim(), category, risk_level: risk, result_kind: resultKind,
          input_schema: schema, applicability, evidence, support_state: support,
          expected_revision: existing.revision,
        }, key, signal), onSaved)
      }
    } catch (error) { action.setError(error) }
  }
  return <form className="form-stack service-definition-form" onSubmit={submit}>
    <h3>{editor.mode === 'create' ? '登记服务操作' : `编辑 ${existing?.service_key}`}</h3>
    <p className="muted-note">保存会递增产品版本 revision，并使既有解析测试证据失效（需重新测试才能发布）。</p>
    {!action.confirming ? <>
      {editor.mode === 'create' && <div className="field"><Label htmlFor="definition-key">操作key</Label><Input id="definition-key" required value={operationKey} onChange={event => setOperationKey(event.target.value)} placeholder="例如 parameters.read"/></div>}
      <div className="field"><Label htmlFor="definition-name">服务名称</Label><Input id="definition-name" required value={displayName} onChange={event => setDisplayName(event.target.value)}/></div>
      <div className="field"><Label htmlFor="definition-category">类别</Label><select id="definition-category" className="native-select" value={category} onChange={event => setCategory(event.target.value as ServiceCategory)}><option value="QUERY">读取</option><option value="CONFIGURATION">参数下发</option><option value="CONTROL">控制</option></select></div>
      <div className="field"><Label htmlFor="definition-risk">风险等级</Label><select id="definition-risk" className="native-select" value={risk} onChange={event => setRisk(event.target.value as RiskLevel)}><option value="NORMAL">常规</option><option value="ENGINEERING">工程高风险</option></select></div>
      <div className="field"><Label htmlFor="definition-result">结果类型</Label><select id="definition-result" className="native-select" value={resultKind} onChange={event => setResultKind(event.target.value as typeof resultKind)}><option value="PARAMETERS">参数快照</option><option value="COMMAND">命令回执</option><option value="STATUS">状态</option></select></div>
      <div className="field"><Label htmlFor="definition-support">能力证据状态</Label><select id="definition-support" className="native-select" value={support} onChange={event => setSupport(event.target.value as SupportState)}><option value="UNVERIFIED">未验证（不可下发）</option><option value="VERIFIED">已验证</option></select></div>
      <div className="field"><Label htmlFor="definition-schema">输入schema（字段列表JSON）</Label><textarea id="definition-schema" className="json-editor" rows={8} spellCheck={false} value={schemaText} onChange={event => setSchemaText(event.target.value)}/></div>
      <div className="field"><Label htmlFor="definition-applicability">适用性JSON</Label><textarea id="definition-applicability" className="json-editor" rows={4} spellCheck={false} value={applicabilityText} onChange={event => setApplicabilityText(event.target.value)}/></div>
      <div className="field"><Label htmlFor="definition-evidence">实现/验证证据JSON</Label><textarea id="definition-evidence" className="json-editor" rows={4} spellCheck={false} value={evidenceText} onChange={event => setEvidenceText(event.target.value)}/></div>
      {action.error != null && <ErrorNotice error={action.error}/>}
      <div className="identity-form-actions"><Button type="submit">核对并继续</Button><Button type="button" variant="outline" onClick={onCancel}>取消</Button></div>
    </> : <OperationConfirmation action={action} title="二次确认 · 服务定义" submitLabel="确认保存" refresh={onCancel} warning="定义随版本发布冻结；已验证声明需要真实证据依据，未验证操作不能下发。">
      <p>{editor.mode === 'create' ? `新增 ${operationKey}` : `编辑 ${existing?.service_key}`} · {displayName}</p><p>{category} · {risk} · {resultKind} · {support}</p><p>版本 revision {version.revision}；操作者：{useSession.getState().user?.name}</p>
    </OperationConfirmation>}
  </form>
}

function DeleteDefinition({ definition, onDone }: { definition: ServiceDefinition; onDone: () => void }) {
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!definition.id) return
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => deviceServicesApi.deleteDefinition(definition.id!, { expected_revision: definition.revision }, key, signal), onDone)
  }
  return <form onSubmit={submit} className="inline-action">
    {!action.confirming ? <Button type="submit" variant="ghost" size="sm"><Trash2/>删除</Button>
      : <OperationConfirmation action={action} title="二次确认 · 删除服务定义" submitLabel="确认删除" refresh={() => action.setConfirming(false)} warning="仅草稿可删除；发布后不可修改。"><p>{definition.service_key} · revision {definition.revision}</p></OperationConfirmation>}
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}
