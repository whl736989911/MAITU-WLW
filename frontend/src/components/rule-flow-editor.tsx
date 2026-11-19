import { useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, History, Link2, Plus, RefreshCw, ShieldAlert, Trash2, Zap } from 'lucide-react'
import { ApiError } from '@/lib/api'
import { beginWriteIntent, clearWriteIntent, getWriteIntent, markWriteIntentUnknown, useWriteIntent } from '@/lib/write-intent'
import {
  ruleFlowApiFor,
  type RuleFlow, type RuleFlowCatalog, type RuleFlowCompiledView, type RuleFlowDefinition, type RuleFlowDetail,
  type RuleFlowEdge, type RuleFlowIssue, type RuleFlowNode, type RuleFlowPreview,
} from '@/lib/rule-flows'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice } from '@/components/common'

const CARD_WIDTH = 208
const CANVAS_MIN_HEIGHT = 460

type Props = {
  tenantId: string
  flow: RuleFlow
  definition: RuleFlowDefinition
  catalog: RuleFlowCatalog
  canWrite: boolean
  canPublish: boolean
  onSaved: (detail: RuleFlowDetail) => void
}

function text(value: unknown, fallback = ''): string { return typeof value === 'string' ? value : fallback }
function flag(value: unknown, fallback = false): boolean { return typeof value === 'boolean' ? value : fallback }
function count(value: unknown, fallback = 0): number { return typeof value === 'number' ? value : fallback }
function names(value: unknown): string[] { return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [] }
function rows(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object' && !Array.isArray(item)) : []
}
const clamp = (value: number) => Math.max(0, Math.min(5600, Math.round(value)))

export function RuleFlowEditor({ tenantId, flow, definition, catalog, canWrite, canPublish, onSaved }: Props) {
  const api = useMemo(() => ruleFlowApiFor(tenantId), [tenantId])
  const [nodes, setNodes] = useState<RuleFlowNode[]>(definition.nodes)
  const [edges, setEdges] = useState<RuleFlowEdge[]>(definition.edges)
  const [name, setName] = useState(flow.name)
  const [description, setDescription] = useState(flow.description)
  const [selected, setSelected] = useState<string | null>(definition.nodes[0]?.id ?? null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState<RuleFlowPreview | null>(null)
  const [previewedRevision, setPreviewedRevision] = useState<number | null>(null)
  const [outcome, setOutcome] = useState('')
  const [showHistory, setShowHistory] = useState(false)
  const [history, setHistory] = useState<Array<{ revision: number; planHash: string; reason: string; publishedBy: string; publishedAt: string }>>([])
  const [historyError, setHistoryError] = useState<unknown>()
  const [readback, setReadback] = useState<RuleFlowCompiledView | null>(null)
  const drag = useRef<{ id: string; startX: number; startY: number; originX: number; originY: number } | null>(null)
  const intentScope = `rule-flow:${flow.id}`
  const pendingIntent = useWriteIntent<unknown>(intentScope)

  useEffect(() => {
    setNodes(definition.nodes); setEdges(definition.edges); setName(flow.name); setDescription(flow.description)
    setPreview(null); setPreviewedRevision(null); setOutcome('')
    setSelected(current => definition.nodes.some(node => node.id === current) ? current : definition.nodes[0]?.id ?? null)
  }, [flow.revision, flow.name, flow.description, definition])

  const types = useMemo(() => new Map(catalog.nodeTypes.map(item => [item.type, item])), [catalog.nodeTypes])
  const issues: RuleFlowIssue[] = preview?.plan.issues ?? flow.lastCompileIssues
  const errors = issues.filter(issue => issue.severity === 'ERROR')
  const issuesFor = (nodeId: string) => issues.filter(issue => issue.nodeId === nodeId)
  const node = nodes.find(item => item.id === selected) ?? null
  const dirty = useMemo(() => {
    const current = JSON.stringify({ nodes, edges })
    const saved = JSON.stringify({ nodes: definition.nodes, edges: definition.edges })
    return current !== saved || name !== flow.name || description !== flow.description
  }, [nodes, edges, definition, name, description, flow.name, flow.description])

  const canvas = useMemo(() => {
    const width = Math.max(760, ...nodes.map(item => item.x + CARD_WIDTH + 120))
    const height = Math.max(CANVAS_MIN_HEIGHT, ...nodes.map(item => item.y + 190))
    return { width, height }
  }, [nodes])

  function updateNode(id: string, patch: Partial<RuleFlowNode>) {
    setNodes(current => current.map(item => item.id === id ? { ...item, ...patch } : item))
  }
  function updateConfig(id: string, key: string, value: unknown) {
    setNodes(current => current.map(item => item.id === id ? { ...item, config: { ...item.config, [key]: value } } : item))
  }
  function addNode(type: string) {
    const definitionOf = types.get(type)
    if (!definitionOf) return
    const used = nodes.filter(item => item.type === type).length
    if (used >= definitionOf.maxCount) { setError(new Error(`${definitionOf.label} 最多 ${definitionOf.maxCount} 个`)); return }
    const id = `${type.split('.').pop() ?? type}-${Math.random().toString(36).slice(2, 7)}`
    const index = nodes.length
    const anchor = nodes[nodes.length - 1]
    const next: RuleFlowNode = { id, type, config: type === 'device.message' ? { scope: { mode: 'ALL', placeIds: [], deviceIds: [] }, systemTypes: [] } : {}, x: clamp((anchor?.x ?? -180) + 260), y: clamp((anchor?.y ?? 60) + (index % 2 === 0 ? 130 : 0)) }
    setNodes(current => [...current, next])
    if (anchor) setEdges(current => [...current, { from: anchor.id, to: id }])
    setSelected(id)
    setError(undefined)
  }
  function removeNode(id: string) {
    const target = nodes.find(item => item.id === id)
    const definitionOf = target ? types.get(target.type) : null
    if (!target || !definitionOf) return
    if (definitionOf.fixed || nodes.filter(item => item.type === id || item.type === target.type).length <= definitionOf.minCount) {
      setError(new Error(`${definitionOf.label} 是固定阶段，不能删除`))
      return
    }
    const remaining = nodes.filter(item => item.id !== id)
    setNodes(remaining)
    setEdges(current => current.filter(edge => edge.from !== id && edge.to !== id))
    if (selected === id) setSelected(remaining[0]?.id ?? null)
  }
  function setOutgoing(id: string, targets: string[]) {
    setEdges(current => [...current.filter(edge => edge.from !== id), ...targets.map(to => ({ from: id, to }))])
  }

  async function run(scope: string, payload: unknown, label: string, action: (key: string) => Promise<void>) {
    if (busy) return
    if (!canWrite && !canPublish) { setError(new Error('当前账户没有规则流写入权限')); return }
    if (!reason.trim()) { setError(new Error('必须填写本次操作原因')); return }
    let intent = getWriteIntent<unknown>(scope)
    if (intent && intent.state === 'UNKNOWN') {
      if (intent.digest !== JSON.stringify(payload)) {
        setError(new Error('存在未确认的写入意图；请沿原操作确认结果或先刷新核对，不能以不同内容新建第二条命令。'))
        return
      }
    } else if (!intent) {
      intent = beginWriteIntent<unknown>(scope, payload, label)
    }
    const key = intent.key
    setBusy(true); setError(undefined)
    try {
      await action(key)
      clearWriteIntent(scope)
      setOutcome(`${label}已完成`)
    } catch (failure) {
      if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) clearWriteIntent(scope)
      else markWriteIntentUnknown(scope, key)
      setError(failure)
    } finally {
      setBusy(false)
    }
  }

  function saveDraft(thenPreview = false) {
    const payload = { name: name.trim(), description, definition: { nodes, edges }, reason: reason.trim() }
    void run(`${intentScope}:save`, payload, '保存草稿', async key => {
      setPreview(null); setPreviewedRevision(null)
      const detail = await api.save(flow.id, { ...payload, expected_revision: flow.revision }, key)
      onSaved(detail)
      if (thenPreview) {
        try {
          const computed = await api.preview(detail.flow.id, detail.flow.revision)
          setPreview(computed); setPreviewedRevision(detail.flow.revision)
          setOutcome('已完成编译预览；预览不会启用任何消费者')
        } catch (failure) { setError(failure) }
      }
    })
  }

  function doPreview() {
    if (dirty) { saveDraft(true); return }
    setBusy(true); setError(undefined)
    api.preview(flow.id, flow.revision).then(computed => {
      setPreview(computed); setPreviewedRevision(flow.revision)
      setOutcome('已完成编译预览；预览不会启用任何消费者')
    }, failure => setError(failure)).finally(() => setBusy(false))
  }

  function doPublish() {
    if (!preview || previewedRevision !== flow.revision) { setError(new Error('请先对当前草稿版本完成编译预览，再发布')); return }
    if (!window.confirm(`发布「${name.trim() || flow.name}」的当前编译版本（revision ${flow.revision}）。发布后消费者会写入但不会自动启用。继续？`)) return
    const payload = { expected_revision: flow.revision, preview_hash: preview.plan.planHash, reason: reason.trim() }
    void run(`${intentScope}:publish`, payload, '发布', async key => {
      const result = await api.publish(flow.id, { ...payload }, key)
      if (!result.published) {
        setPreview({ plan: result.plan, bindings: result.bindings })
        setError(new Error(`编译未通过：${result.issues.filter(issue => issue.severity === 'ERROR').map(issue => issue.message).join('；') || '请查看节点错误'}`))
        return
      }
      setOutcome(`已发布修订 ${result.revision}；消费者已写入但尚未启用`)
      onSaved({ flow: result.flow, definition: { nodes, edges }, publishedDraftDrift: null })
    })
  }

  function doToggle() {
    const target = !flow.enabled
    if (!window.confirm(`${target ? '启用' : '停用'}「${name.trim() || flow.name}」已编译的消费者（revision ${flow.revision}）。${target ? '启用后会真实执行该规则流。' : ''}继续？`)) return
    const payload = { expected_revision: flow.revision, enabled: target, reason: reason.trim() }
    void run(`${intentScope}:enable`, payload, target ? '启用' : '停用', async key => {
      const result = await api.toggle(flow.id, { ...payload }, key)
      setOutcome(target ? '已启用编译后的消费者' : '已停用编译后的消费者')
      onSaved({ flow: result.flow, definition: { nodes, edges }, publishedDraftDrift: null })
      loadReadback(result.flow.id)
    })
  }

  function doArchive() {
    if (!window.confirm(`归档「${name.trim() || flow.name}」（revision ${flow.revision}）；已绑定的消费者不会再被该规则流启用，历史保留。继续？`)) return
    const payload = { expected_revision: flow.revision, reason: reason.trim() }
    void run(`${intentScope}:archive`, payload, '归档', async key => {
      const detail = await api.archive(flow.id, { ...payload }, key)
      setOutcome('规则流已归档；已绑定的消费者不会再被该规则流启用')
      onSaved(detail)
    })
  }

  function loadReadback(id: string) {
    api.compiled(id).then(setReadback, failure => setError(failure))
  }

  function loadHistory() {
    setShowHistory(true); setHistoryError(undefined)
    api.revisions(flow.id).then(setHistory, failure => setHistoryError(failure))
  }

  return <div className="rule-flow-editor">
    <div className="list-toolbar">
      <div className="field"><Label htmlFor="rule-flow-name">规则流名称</Label><Input id="rule-flow-name" value={name} disabled={!canWrite} onChange={event => setName(event.target.value)} maxLength={120}/></div>
      <div className="field"><Label htmlFor="rule-flow-description">说明</Label><Input id="rule-flow-description" value={description} disabled={!canWrite} onChange={event => setDescription(event.target.value)} maxLength={1000}/></div>
      <Badge>{flow.status === 'PUBLISHED' ? (flow.enabled ? '已发布 · 已启用' : '已发布 · 未启用') : flow.status === 'ARCHIVED' ? '已归档' : '草稿'}</Badge>
      {flow.publishedRevision != null && <Badge>发布修订 {flow.publishedRevision}</Badge>}
      {dirty && <Badge title="草稿与已发布版本不一致">有未发布改动</Badge>}
    </div>

    <div className="rule-flow-layout">
      <aside className="rule-flow-palette" aria-label="节点类型">
        <h3>受审节点</h3>
        <p className="muted-note">只允许目录内节点；不可写入宿主代码、循环或任意 HTTP。</p>
        {['SOURCE', 'NATIVE', 'POLICY', 'NOTIFICATION', 'REFERENCE'].map(category => {
          const items = catalog.nodeTypes.filter(item => item.category === category)
          if (items.length === 0) return null
          return <section key={category} className="rule-flow-palette-group">
            <h4>{categoryLabel(category)}</h4>
            {items.map(item => {
              const used = nodes.filter(nodeItem => nodeItem.type === item.type).length
              const full = used >= item.maxCount
              const blocked = !item.supported || !canWrite || full
              const channelBlocked = ['sms', 'voice', 'wechat'].includes(item.type) && !catalog.channels.find(channel => channel.channel === item.type.toUpperCase())?.supported
              return <button key={item.type} type="button" className="rule-flow-palette-item" disabled={blocked || channelBlocked}
                title={item.supported ? (full ? '已达上限' : item.description) : item.rationale}
                onClick={() => addNode(item.type)}>
                <Plus size={13}/><span>{item.label}</span>{item.fixed && <Badge>固定</Badge>}
              </button>
            })}
          </section>
        })}
      </aside>

      <div className="rule-flow-canvas" role="group" aria-label="规则流画布" style={{ minHeight: CANVAS_MIN_HEIGHT }}>
        <svg className="rule-flow-edges" width={canvas.width} height={canvas.height} aria-hidden>
          {edges.map(edge => {
            const from = nodes.find(item => item.id === edge.from), to = nodes.find(item => item.id === edge.to)
            if (!from || !to) return null
            const x1 = from.x + CARD_WIDTH, y1 = from.y + 46, x2 = to.x, y2 = to.y + 46
            const mid = Math.max(40, (x2 - x1) / 2)
            return <path key={`${edge.from}->${edge.to}`} d={`M ${x1} ${y1} C ${x1 + mid} ${y1}, ${x2 - mid} ${y2}, ${x2} ${y2}`} fill="none" stroke="var(--border, #cfd6e4)" strokeWidth={2}/>
          })}
        </svg>
        {nodes.map(item => {
          const definitionOf = types.get(item.type)
          const nodeIssues = issuesFor(item.id)
          const failing = nodeIssues.some(issue => issue.severity === 'ERROR')
          return <div key={item.id} className={`rule-flow-node${selected === item.id ? ' selected' : ''}${failing ? ' invalid' : ''}`}
            style={{ left: item.x, top: item.y, width: CARD_WIDTH }} role="button" tabIndex={0}
            aria-pressed={selected === item.id}
            onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelected(item.id) } if (canWrite && (event.key === 'ArrowRight' || event.key === 'ArrowLeft' || event.key === 'ArrowUp' || event.key === 'ArrowDown')) { event.preventDefault(); const step = event.shiftKey ? 5 : 20; updateNode(item.id, { x: clamp(item.x + (event.key === 'ArrowRight' ? step : event.key === 'ArrowLeft' ? -step : 0)), y: clamp(item.y + (event.key === 'ArrowDown' ? step : event.key === 'ArrowUp' ? -step : 0)) }) } }}
            onClick={() => setSelected(item.id)}
            onPointerDown={event => {
              if (!canWrite || event.button !== 0) return
              ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
              drag.current = { id: item.id, startX: event.clientX, startY: event.clientY, originX: item.x, originY: item.y }
            }}
            onPointerMove={event => {
              const active = drag.current
              if (!active || active.id !== item.id) return
              updateNode(item.id, { x: clamp(active.originX + event.clientX - active.startX), y: clamp(active.originY + event.clientY - active.startY) })
            }}
            onPointerUp={() => { drag.current = null }}
            onPointerCancel={() => { drag.current = null }}>
            <header>{definitionOf?.label ?? item.type}{definitionOf?.fixed && <span className="rule-flow-fixed">固定</span>}</header>
            <p className="rule-flow-node-id">{item.id}</p>
            {failing && <p className="rule-flow-node-warning"><AlertTriangle size={12}/> 需要处理</p>}
          </div>
        })}
        {nodes.length === 0 && <Empty title="画布为空">把受审节点加入画布并连接成一条从设备消息开始的路径。</Empty>}
      </div>

      <aside className="rule-flow-properties" aria-label="节点属性">
        {node == null ? <Empty title="未选择节点">选择一个节点后可编辑它的业务属性。</Empty> : <>
          <h3>{types.get(node.type)?.label ?? node.type}</h3>
          <p className="muted-note">{types.get(node.type)?.description}</p>
          <NodeForm node={node} catalog={catalog} canWrite={canWrite} nodes={nodes} edges={edges}
            onConfig={updateConfig} onOutgoing={setOutgoing} onRemove={removeNode} types={types}/>
        </>}
      </aside>
    </div>

    <Card><CardContent>
      <h3>变更原因与确认</h3>
      <p className="muted-note">草稿保存、发布与启停写审计；预览只核对影响，不会生效。发布与启停会再次校验当前会话、权限、单位授权与消费方修订号。</p>
      <div className="form-stack">
        <div className="field"><Label htmlFor="rule-flow-reason">变更原因</Label><Input id="rule-flow-reason" value={reason} onChange={event => setReason(event.target.value)} maxLength={1000}/></div>
      </div>
      {pendingIntent?.state === 'UNKNOWN' && <div className="notice" role="alert"><strong>存在结果未确认的写入</strong><p>请沿原请求确认结果或先刷新核对；系统不会自动换幂等键重发。</p></div>}
      {error != null && <ErrorNotice error={error}/>}
      {outcome && <p className="muted-note" role="status">{outcome}</p>}
      <div className="list-toolbar">
        <Button type="button" disabled={!canWrite || busy} onClick={() => saveDraft(false)}>保存草稿</Button>
        <Button type="button" variant="outline" disabled={!canPublish || busy} onClick={doPreview}>编译预览</Button>
        <Button type="button" disabled={!canPublish || busy || !preview || previewedRevision !== flow.revision} onClick={doPublish}>发布修订</Button>
        <Button type="button" variant={flow.enabled ? 'outline' : 'default'} disabled={!canPublish || busy || flow.publishedRevision === null} onClick={doToggle}>{flow.enabled ? '停用消费者' : '启用消费者'}</Button>
        <Button type="button" variant="ghost" disabled={busy} onClick={loadHistory}><History size={14}/> 发布历史</Button>
        <Button type="button" variant="ghost" disabled={!canWrite || busy || flow.enabled || flow.status === 'ARCHIVED'} onClick={doArchive}><Trash2 size={14}/> 归档</Button>
        <Button type="button" variant="ghost" disabled={busy} onClick={() => loadReadback(flow.id)}><RefreshCw size={14}/> 刷新消费回读</Button>
      </div>
    </CardContent></Card>

    {preview && <PreviewPanel preview={preview} revision={previewedRevision} flowRevision={flow.revision}/>}
    {errors.length > 0 && <Card><CardContent><h3><ShieldAlert size={16}/> 节点错误（{errors.length}）</h3>
      <ul className="rule-flow-issues">{issues.map((issue, index) => <li key={`${issue.code}-${index}`} className={issue.severity === 'ERROR' ? 'error' : 'note'}>
        <strong>{issue.severity === 'ERROR' ? '错误' : '提示'}</strong> · {issue.nodeId ?? '整体'} · {issue.code}：{issue.message}
      </li>)}</ul></CardContent></Card>}

    {readback && <Card><CardContent><h3><Link2 size={16}/> 消费者回读</h3>
      <p className="muted-note">“已漂移”表示绑定的消费方修订与当前修订不一致，即该策略已在规则流之外被修改；发布或启停会被拒绝，不会静默覆盖。</p>
      <div className="table-wrap"><table className="data-table"><thead><tr><th>消费者</th><th>范围</th><th>ID</th><th>当前修订</th><th>状态</th></tr></thead>
        <tbody>{readback.consumers.map(item => <tr key={`${item.consumerKind}-${item.consumerId}`}>
          <td>{item.consumerKind}</td><td>{item.scopeKey}</td><td>{item.consumerId}</td>
          <td>{item.consumerRevision}</td>
          <td>{item.driftDetected ? <Badge>已漂移</Badge> : <Badge>{item.effect}</Badge>}</td>
        </tr>)}</tbody></table></div>
      {readback.consumers.length === 0 && <p className="muted-note">尚未发布，没有消费者绑定。</p>}
    </CardContent></Card>}

    {showHistory && <Card><CardContent><h3><History size={16}/> 已发布修订</h3>
      {historyError != null ? <ErrorNotice error={historyError}/> : history.length === 0 ? <p className="muted-note">还没有发布修订。</p> : <div className="table-wrap"><table className="data-table">
        <thead><tr><th>修订</th><th>计划哈希</th><th>原因</th><th>发布人</th><th>时间</th></tr></thead>
        <tbody>{history.map(item => <tr key={item.revision}><td>{item.revision}</td><td><code>{item.planHash.slice(0, 16)}…</code></td><td>{item.reason}</td><td>{item.publishedBy}</td><td>{item.publishedAt}</td></tr>)}</tbody>
      </table></div>}
    </CardContent></Card>}
  </div>
}

function categoryLabel(category: string) {
  switch (category) {
    case 'SOURCE': return '来源'
    case 'NATIVE': return '原生阶段'
    case 'POLICY': return '本地策略'
    case 'NOTIFICATION': return '通知通道'
    case 'REFERENCE': return '只读引用'
    default: return category
  }
}

function PreviewPanel({ preview, revision, flowRevision }: { preview: RuleFlowPreview; revision: number | null; flowRevision: number }) {
  const stale = revision !== flowRevision
  return <Card><CardContent>
    <h3><Zap size={16}/> 编译预览 {stale && <Badge>草稿已变化，请重新预览</Badge>}</h3>
    <p className="muted-note">预览只计算将要写入的既有消费者目标与差异，不启用、不外发、不改变任何报警事实。计划哈希 <code>{preview.plan.planHash.slice(0, 16)}…</code></p>
    <h4>将写入的消费者</h4>
    {preview.plan.diffs.length === 0 ? <p className="muted-note">与当前消费者值一致，没有差异。</p> : <div className="table-wrap"><table className="data-table">
      <thead><tr><th>消费者</th><th>范围</th><th>字段</th><th>当前</th><th>发布后（未启用）</th></tr></thead>
      <tbody>{preview.plan.diffs.map((item, index) => <tr key={`${item.consumerKind}-${item.field}-${index}`}>
        <td>{item.consumerKind}</td><td>{item.scopeKey}</td><td>{item.field}</td>
        <td><code className="rule-flow-diff">{truncate(item.before)}</code></td>
        <td><code className="rule-flow-diff">{truncate(item.after)}</code></td>
      </tr>)}</tbody></table></div>}
    <h4>依赖修订（发布时按此校验）</h4>
    <div className="table-wrap"><table className="data-table"><thead><tr><th>消费者</th><th>范围</th><th>当前 ID</th><th>修订</th></tr></thead>
      <tbody>{preview.plan.dependencies.map((item, index) => <tr key={`${item.consumerKind}-${item.scopeKey}-${index}`}>
        <td>{item.consumerKind}</td><td>{item.scopeKey}</td><td>{item.consumerId ?? '（将新建）'}</td><td>{item.revision ?? '—'}</td>
      </tr>)}</tbody></table></div>
    {preview.plan.references.length > 0 && <><h4>只读引用</h4><ul className="rule-flow-issues">
      {preview.plan.references.map(item => <li key={`${item.consumerKind}-${item.consumerId}`} className="note"><strong>{item.consumerName}</strong>（修订 {item.consumerRevision}）：{item.effect}</li>)}
    </ul></>}
    {preview.bindings.length > 0 && <><h4>当前已绑定消费者</h4><ul className="rule-flow-issues">
      {preview.bindings.map(item => <li key={`${item.consumerKind}-${item.consumerId}`} className="note">{item.consumerKind} · {item.scopeKey} · 修订 {item.consumerRevision}</li>)}
    </ul></>}
  </CardContent></Card>
}

function truncate(value: string) {
  return value.length > 220 ? `${value.slice(0, 220)}…` : value
}

type NodeFormProps = {
  node: RuleFlowNode
  catalog: RuleFlowCatalog
  canWrite: boolean
  nodes: RuleFlowNode[]
  edges: RuleFlowEdge[]
  types: Map<string, RuleFlowCatalog['nodeTypes'][number]>
  onConfig: (id: string, key: string, value: unknown) => void
  onOutgoing: (id: string, targets: string[]) => void
  onRemove: (id: string) => void
}

function NodeForm({ node, catalog, canWrite, nodes, edges, types, onConfig, onOutgoing, onRemove }: NodeFormProps) {
  const definitionOf = types.get(node.type)
  const outgoing = edges.filter(edge => edge.from === node.id).map(edge => edge.to)
  const others = nodes.filter(item => item.id !== node.id)
  const config = node.config
  return <>
    <div className="field"><Label htmlFor="rule-flow-next">后续节点</Label>
      <select id="rule-flow-next" className="native-select" multiple size={Math.min(6, Math.max(2, others.length))} disabled={!canWrite}
        value={outgoing} onChange={event => onOutgoing(node.id, Array.from(event.target.selectedOptions).map(option => option.value))}>
        {others.map(item => <option key={item.id} value={item.id}>{types.get(item.type)?.label ?? item.type} · {item.id}</option>)}
      </select>
      <p className="muted-note">连线决定执行顺序；系统会拒绝环路、不可达节点和超过 8 层的图。</p>
    </div>
    {node.type === 'device.message' && <SourceForm node={node} catalog={catalog} canWrite={canWrite} onConfig={onConfig}/>}
    {node.type === 'eventtype' && <EventTypeForm node={node} canWrite={canWrite} onConfig={onConfig}/>}
    {node.type === 'fault' && <FaultForm node={node} canWrite={canWrite} onConfig={onConfig}/>}
    {node.type === 'annotate' && <AnnotateForm node={node} canWrite={canWrite} onConfig={onConfig}/>}
    {node.type === 'pushtype' && <PushForm node={node} canWrite={canWrite} onConfig={onConfig}/>}
    {(node.type === 'sms' || node.type === 'voice' || node.type === 'wechat') && <NotificationForm node={node} catalog={catalog} canWrite={canWrite} onConfig={onConfig}/>}
    {node.type === 'merge' && <MergeForm node={node} canWrite={canWrite} onConfig={onConfig}/>}
    {node.type === 'linkage' && <LinkageForm node={node} catalog={catalog} canWrite={canWrite} onConfig={onConfig}/>}
    {node.type === 'mqtt' && <OutputForm node={node} catalog={catalog} canWrite={canWrite} onConfig={onConfig}/>}
    {(node.type === 'persist' || node.type === 'createalarm') && <div className="notice"><strong>原生阶段</strong><p>该阶段由既有接入与报警链路固定执行，规则流不能关闭、重排或改写。</p></div>}
    {canWrite && !definitionOf?.fixed && !(definitionOf && nodes.filter(item => item.type === node.type).length <= definitionOf.minCount) &&
      <Button type="button" variant="outline" onClick={() => onRemove(node.id)}><Trash2 size={14}/> 删除节点</Button>}
    {definitionOf?.fixed && <p className="muted-note">固定阶段不能删除。</p>}
  </>
}

function SourceForm({ node, catalog, canWrite, onConfig }: { node: RuleFlowNode; catalog: RuleFlowCatalog; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  const scope = (node.config.scope && typeof node.config.scope === 'object' && !Array.isArray(node.config.scope) ? node.config.scope : {}) as Record<string, unknown>
  const mode = text(scope.mode, 'ALL')
  const placeIds = names(scope.placeIds)
  const deviceIds = names(scope.deviceIds)
  const systemTypes = names(node.config.systemTypes)
  function setScope(patch: Record<string, unknown>) { onConfig(node.id, 'scope', { mode, placeIds, deviceIds, ...patch }) }
  return <>
    <div className="field"><Label htmlFor="rule-flow-scope-mode">入口范围</Label>
      <select id="rule-flow-scope-mode" className="native-select" value={mode} disabled={!canWrite}
        onChange={event => setScope({ mode: event.target.value, placeIds: [], deviceIds: [] })}>
        <option value="ALL">整个单位</option>
        <option value="PLACES">指定位置子树</option>
        <option value="DEVICES">指定设备</option>
      </select>
      <p className="muted-note">通知与报警策略消费者按单位生效；只有仅含联动/输出引用的规则流可以使用更窄范围。</p>
    </div>
    {mode === 'PLACES' && <MultiPicker id="rule-flow-places" label="位置" hint="按名称路径选择，不使用ID作为主要输入" disabled={!canWrite}
      selected={placeIds} onChange={next => setScope({ placeIds: next })}
      options={catalog.places.map(item => ({ id: item.id, label: item.path || item.name, detail: item.name }))}/>}
    {mode === 'DEVICES' && <MultiPicker id="rule-flow-devices" label="设备" hint="只列出当前单位设备；不在当前授权设备范围的设备会被服务端拒绝" disabled={!canWrite}
      selected={deviceIds} onChange={next => setScope({ deviceIds: next })}
      options={catalog.devices.map(item => ({ id: item.id, label: item.name ?? item.id, detail: `${item.model ?? '型号未知'} · ${item.placePath ?? '未设置位置'}${item.inCurrentScope ? '' : ' · 不在当前范围'}` }))}/>}
    <MultiPicker id="rule-flow-system-types" label="系统型号" hint="用于订阅排除项定位；留空表示不改动分型号排除" disabled={!canWrite}
      selected={systemTypes} onChange={next => onConfig(node.id, 'systemTypes', next)}
      options={catalog.systemTypes.map(item => ({ id: item.id, label: item.name }))}/>
  </>
}

function EventTypeForm({ node, canWrite, onConfig }: { node: RuleFlowNode; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  const fault = (node.config.fault && typeof node.config.fault === 'object' ? node.config.fault : {}) as Record<string, unknown>
  const codes = names(fault.excludeCodes)
  return <>
    <div className="notice"><strong>火灾通知不能被排除</strong><p>火灾事件属于物理风险，规则流不能把它从通知中剔除；仅允许配置故障事件的排除代码。</p></div>
    <div className="field"><Label htmlFor="rule-flow-fault-excludes">故障排除事件代码</Label>
      <Input id="rule-flow-fault-excludes" disabled={!canWrite} value={codes.join(',')}
        onChange={event => onConfig(node.id, 'fault', { excludeCodes: event.target.value.split(',').map(item => item.trim()).filter(Boolean).slice(0, 64) })}/>
      <p className="muted-note">逗号分隔，最多 64 个；这些代码会写入报警基础配置的故障订阅排除项。</p>
    </div>
  </>
}

function FaultForm({ node, canWrite, onConfig }: { node: RuleFlowNode; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  const types = names(node.config.voiceTypes)
  return <>
    <label className="notification-checkbox"><input type="checkbox" disabled={!canWrite} checked={flag(node.config.notify)} onChange={event => onConfig(node.id, 'notify', event.target.checked)}/>生成故障通知规则</label>
    <label className="notification-checkbox"><input type="checkbox" disabled={!canWrite} checked={flag(node.config.voiceEnabled)} onChange={event => onConfig(node.id, 'voiceEnabled', event.target.checked)}/>启用故障本地语音播报</label>
    <div className="field"><Label htmlFor="rule-flow-fault-silence">故障语音静音间隔（分钟）</Label>
      <Input id="rule-flow-fault-silence" type="number" min={0} max={1440} disabled={!canWrite} value={count(node.config.silenceMinutes)}
        onChange={event => onConfig(node.id, 'silenceMinutes', Number(event.target.value))}/>
    </div>
    <div className="field"><Label htmlFor="rule-flow-fault-types">播报故障类型</Label>
      <Input id="rule-flow-fault-types" disabled={!canWrite} value={types.join(',')}
        onChange={event => onConfig(node.id, 'voiceTypes', event.target.value.split(',').map(item => item.trim()).filter(Boolean).slice(0, 32))}/>
      <p className="muted-note">真实故障事实的代码；留空表示不限制具体代码。</p>
    </div>
  </>
}

const ANNOTATION_FIELDS = [
  ['zone', '区域'], ['position', '安装位置'], ['eventSource', '事件来源'], ['time', '时间'], ['eventType', '事件类型'],
] as const

function AnnotateForm({ node, canWrite, onConfig }: { node: RuleFlowNode; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  const selected = names(node.config.fields)
  return <fieldset className="form-stack"><legend>播报字段</legend>
    {ANNOTATION_FIELDS.map(([key, label]) => <label key={key} className="notification-checkbox"><input type="checkbox" disabled={!canWrite} checked={selected.includes(key)}
      onChange={event => onConfig(node.id, 'fields', event.target.checked ? [...selected.filter(item => item !== key), key] : selected.filter(item => item !== key))}/>{label}</label>)}
  </fieldset>
}

function PushForm({ node, canWrite, onConfig }: { node: RuleFlowNode; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  return <>
    <label className="notification-checkbox"><input type="checkbox" disabled={!canWrite} checked={flag(node.config.pushMessage, true)} onChange={event => onConfig(node.id, 'pushMessage', event.target.checked)}/>允许消息派发</label>
    <div className="field"><Label htmlFor="rule-flow-period">未恢复提醒周期（小时）</Label>
      <Input id="rule-flow-period" type="number" min={1} max={720} disabled={!canWrite} value={count(node.config.periodHours, 24)}
        onChange={event => onConfig(node.id, 'periodHours', Number(event.target.value))}/>
    </div>
  </>
}

function NotificationForm({ node, catalog, canWrite, onConfig }: { node: RuleFlowNode; catalog: RuleFlowCatalog; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  const channel = node.type.toUpperCase()
  const support = catalog.channels.find(item => item.channel === channel)
  const scopes = names(node.config.eventScopes)
  const receivers = rows(node.config.receivers)
  function setReceivers(next: Record<string, unknown>[]) { onConfig(node.id, 'receivers', next) }
  return <>
    {support && !support.supported && <div className="notice" role="alert"><strong>通道未接通</strong><p>{support.rationale}</p></div>}
    <fieldset className="form-stack"><legend>适用事件</legend>
      {[['FIRE', '火灾报警'], ['FAULT', '设备故障']].map(([key, label]) => <label key={key} className="notification-checkbox"><input type="checkbox" disabled={!canWrite}
        checked={scopes.includes(key)} onChange={event => onConfig(node.id, 'eventScopes', event.target.checked ? [...scopes, key] : scopes.filter(item => item !== key))}/>{label}</label>)}
    </fieldset>
    <div className="field"><Label>接收人</Label>
      {receivers.map((receiver, index) => <div className="rule-flow-receiver" key={`${text(receiver.contactId)}-${index}`}>
        <select aria-label={`接收人 ${index + 1}`} className="native-select" disabled={!canWrite} value={text(receiver.contactId)}
          onChange={event => setReceivers(receivers.map((item, position) => position === index ? { ...item, contactId: event.target.value } : item))}>
          <option value="">选择联系人</option>
          {catalog.contacts.filter(contact => contact.enabled).map(contact => <option key={contact.id} value={contact.id}>{contact.name} · {contact.phoneMasked}</option>)}
        </select>
        <select aria-label={`接收等级 ${index + 1}`} className="native-select" disabled={!canWrite} value={count(receiver.level, 1)}
          onChange={event => setReceivers(receivers.map((item, position) => position === index ? { ...item, level: Number(event.target.value) } : item))}>
          {[1, 2, 3, 4, 5].map(level => <option key={level} value={level}>第 {level} 级</option>)}
        </select>
        <Button type="button" variant="ghost" size="sm" disabled={!canWrite} onClick={() => setReceivers(receivers.filter((_, position) => position !== index))}>移除</Button>
      </div>)}
      <Button type="button" variant="outline" disabled={!canWrite || receivers.length >= 50}
        onClick={() => setReceivers([...receivers, { contactId: '', level: receivers.length + 1 }])}><Plus size={14}/> 添加接收人</Button>
      <p className="muted-note">等级必须从 1 开始连续；同一通道不能重复同一联系人。现有通知规则消费者要求同一事件范围同时具备短信与语音接收人；微信通道只有第 1 级且无升级间隔。联系人不是登录账号。</p>
    </div>
    <div className="field"><Label htmlFor="rule-flow-escalation">升级间隔（分钟）</Label>
      <Input id="rule-flow-escalation" type="number" min={0} max={60} disabled={!canWrite} value={count(node.config.escalationMinutes)}
        onChange={event => onConfig(node.id, 'escalationMinutes', Number(event.target.value))}/>
      <p className="muted-note">只有存在第 2 级及以上接收人时才有消费者；单等级规则必须为 0。微信通道没有独立升级间隔消费者。</p>
    </div>
  </>
}

function MergeForm({ node, canWrite, onConfig }: { node: RuleFlowNode; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  return <>
    <label className="notification-checkbox"><input type="checkbox" disabled={!canWrite} checked={flag(node.config.enabled)} onChange={event => onConfig(node.id, 'enabled', event.target.checked)}/>启用报警归并窗口</label>
    <div className="field"><Label htmlFor="rule-flow-window">归并窗口（秒）</Label>
      <Input id="rule-flow-window" type="number" min={1} max={604800} disabled={!canWrite} value={count(node.config.windowSeconds)}
        onChange={event => onConfig(node.id, 'windowSeconds', Number(event.target.value))}/>
      <p className="muted-note">窗口变化会按既有规则关闭当前已捕获会话；成员与物理报警不受影响。首报仍立即保留。</p>
    </div>
  </>
}

function LinkageForm({ node, catalog, canWrite, onConfig }: { node: RuleFlowNode; catalog: RuleFlowCatalog; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  const selected = text(node.config.ruleId)
  return <>
    <div className="field"><Label htmlFor="rule-flow-linkage">资源联动规则</Label>
      <select id="rule-flow-linkage" className="native-select" disabled={!canWrite} value={selected} onChange={event => onConfig(node.id, 'ruleId', event.target.value)}>
        <option value="">选择已存在规则</option>
        {catalog.linkageRules.filter(item => item.kind === 'CUSTOM').map(item => <option key={item.id} value={item.id}>{item.name}{item.hasControllers ? ' · 已配置控制器' : ''}{item.enabled ? ' · 已启用' : ' · 未启用'}</option>)}
      </select>
      <p className="muted-note">规则流只登记并核对引用，不会改写或启停联动规则；硬件动作仍由有权人员在资源联动页面单独维护。</p>
    </div>
  </>
}

function OutputForm({ node, catalog, canWrite, onConfig }: { node: RuleFlowNode; catalog: RuleFlowCatalog; canWrite: boolean; onConfig: NodeFormProps['onConfig'] }) {
  const selected = text(node.config.applicationId)
  return <>
    <div className="field"><Label htmlFor="rule-flow-application">已发布数据输出应用</Label>
      <select id="rule-flow-application" className="native-select" disabled={!canWrite} value={selected} onChange={event => onConfig(node.id, 'applicationId', event.target.value)}>
        <option value="">选择应用</option>
        {catalog.applications.map(item => {
          const unavailable = item.mqttEnabledOutputs === 0
          return <option key={item.id} value={item.id} disabled={unavailable}>{item.name} · MQTT已启用 {item.mqttEnabledOutputs} · 全部已启用 {item.enabledOutputs}{unavailable ? ' · 无已启用MQTT输出，不可引用' : ''}</option>
        })}
      </select>
      {catalog.applications.some(item => item.mqttEnabledOutputs === 0) && <p className="muted-note" role="note">未启用任何 MQTT 输出的应用不能作为引用；规则流不会代建或代发外发。</p>}
      <p className="muted-note">只做引用核对：选择的开放应用必须已有启用的MQTT输出；外发由该应用的数据输出链路承载，规则流不新建端点、不代发、不启停外发。</p>
    </div>
  </>
}

type PickerOption = { id: string; label: string; detail?: string }

function MultiPicker({ id, label, hint, options, selected, onChange, disabled }: { id: string; label: string; hint?: string; options: PickerOption[]; selected: string[]; onChange: (next: string[]) => void; disabled: boolean }) {
  const [query, setQuery] = useState('')
  const normalized = query.trim().toLowerCase()
  const matches = options.filter(option => !selected.includes(option.id) &&
    (normalized === '' || `${option.label} ${option.detail ?? ''}`.toLowerCase().includes(normalized))).slice(0, 20)
  const labelOf = (value: string) => options.find(option => option.id === value)
  return <div className="field">
    <Label htmlFor={id}>{label}</Label>
    {hint && <p className="muted-note">{hint}</p>}
    <div className="rule-flow-chips">{selected.map(value => <span key={value} className="rule-flow-chip">
      {labelOf(value)?.label ?? `${value}（不在当前选项）`}
      <button type="button" aria-label={`移除 ${labelOf(value)?.label ?? value}`} disabled={disabled} onClick={() => onChange(selected.filter(item => item !== value))}>×</button>
    </span>)}{selected.length === 0 && <span className="muted-note">未选择</span>}</div>
    <Input id={id} disabled={disabled} value={query} onChange={event => setQuery(event.target.value)} placeholder="按名称搜索后点选"/>
    <div className="rule-flow-options" role="listbox" aria-label={`${label}候选`}>
      {matches.map(option => <button key={option.id} type="button" role="option" aria-selected={false} disabled={disabled} onClick={() => { onChange([...selected, option.id]); setQuery('') }}>
        <span>{option.label}</span>{option.detail && <small>{option.detail}</small>}
      </button>)}
      {matches.length === 0 && <p className="muted-note">没有匹配项。</p>}
    </div>
  </div>
}
