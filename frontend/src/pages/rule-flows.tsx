import { useMemo, useState } from 'react'
import { Plus } from 'lucide-react'
import { ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { ruleFlowApiFor, starterDefinition, type RuleFlowDetail } from '@/lib/rule-flows'
import { beginWriteIntent, clearWriteIntent, getWriteIntent, markWriteIntentUnknown } from '@/lib/write-intent'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { ConfigurationUnitTarget } from '@/components/configuration-unit-target'
import { RuleFlowEditor } from '@/components/rule-flow-editor'
import './rule-flows.css'

export function RuleFlowsPage() {
  const permissions = useSession(s => s.permissions)
  if (!permissions.includes('rules:read'))
    return <><PageHeading eyebrow="RULES · 规则与通知" title="业务规则" description="规则流把受审节点编译为既有通知、报警与归并消费者。"/><ErrorNotice error={new Error('当前账户没有规则流读取权限')}/></>
  return <ConfigurationUnitTarget>{tenantId => <RuleFlowsContent key={tenantId} tenantId={tenantId} canWrite={permissions.includes('rules:write')} canPublish={permissions.includes('rules:publish')}/>}</ConfigurationUnitTarget>
}

function RuleFlowsContent({ tenantId, canWrite, canPublish }: { tenantId: string; canWrite: boolean; canPublish: boolean }) {
  const api = useMemo(() => ruleFlowApiFor(tenantId), [tenantId])
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState('')
  const [query, setQuery] = useState({ keyword: '', status: '' })
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [createName, setCreateName] = useState('')
  const [createDescription, setCreateDescription] = useState('')
  const [createReason, setCreateReason] = useState('')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<unknown>()
  const catalog = useResource(`rule-flow-catalog:${tenantId}`, signal => api.catalog(signal))
  const list = useResource(`rule-flows:${tenantId}:${page}:${query.keyword}:${query.status}`, signal => api.list(page, 20, query.keyword, query.status, signal))
  const detail = useResource(`rule-flow-detail:${selectedId ?? 'none'}`, signal => selectedId ? api.detail(selectedId, signal) : Promise.resolve(null as RuleFlowDetail | null), selectedId != null)

  function apply(detailResult: RuleFlowDetail) {
    detail.refresh()
    list.refresh()
    setSelectedId(detailResult.flow.id)
  }

  async function createFlow() {
    if (creating) return
    if (!createName.trim()) { setCreateError(new Error('请填写规则流名称')); return }
    if (!createReason.trim()) { setCreateError(new Error('请填写创建原因')); return }
    const payload = { name: createName.trim(), description: createDescription, definition: starterDefinition(), reason: createReason.trim() }
    const scope = `rule-flow-create:${tenantId}`
    let intent = getWriteIntent<unknown>(scope)
    if (intent && intent.state === 'UNKNOWN') { setCreateError(new Error('上一条创建结果未确认；请先刷新列表核对，不能以不同内容新建第二条命令。')); return }
    if (!intent) intent = beginWriteIntent(scope, payload, '创建规则流草稿')
    setCreating(true); setCreateError(undefined)
    try {
      const created = await api.create({ ...payload }, intent.key)
      clearWriteIntent(scope)
      setCreateName(''); setCreateDescription(''); setCreateReason('')
      setSelectedId(created.flow.id)
      list.refresh()
    } catch (failure) {
      setCreateError(failure)
      if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) clearWriteIntent(scope)
      else markWriteIntentUnknown(scope, intent.key)
    } finally {
      setCreating(false)
    }
  }

  const flows = list.data?.items ?? []
  return <>
    <PageHeading eyebrow="RULES · 规则与通知" title="业务规则" description="用受审节点编排报警、故障、通知、归并与引用；发布后须显式启用，才由既有消费者生效。">
      <PageHelp title="规则流如何生效">
        <div className="form-stack">
          <p>草稿只保存图形与属性；编译预览只计算将要写入的既有消费者目标与差异，不启用、不外发。</p>
          <p>发布在一次事务内写入报警基础配置、报警归并策略与真实通知规则，并记录消费者真实ID与修订号；任一失败或依赖已漂移则全部回滚。</p>
          <p>发布后消费者处于未启用状态；点击“启用消费者”才会真正打开通知与归并。停用会关闭本规则流负责的那部分策略。</p>
          <p>来源、持久化与创建报警是固定原生阶段，不能被关闭或重排；火灾事件不能被规则流排除通知。</p>
        </div>
      </PageHelp>
    </PageHeading>

    <div className="list-toolbar">
      <form className="search-form" onSubmit={event => { event.preventDefault(); setPage(1); setQuery({ keyword: keyword.trim(), status }) }}>
        <div className="input-icon"><Input aria-label="按名称查询规则流" value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="按名称或ID查询（字面匹配）"/></div>
        <select aria-label="规则流状态" className="native-select" value={status} onChange={event => { setStatus(event.target.value); setPage(1); setQuery(current => ({ ...current, status: event.target.value })) }}>
          <option value="">全部状态</option><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="ARCHIVED">已归档</option>
        </select>
        <Button type="submit" variant="outline">查询</Button>
      </form>
      <RefreshButton onClick={() => { list.refresh(); catalog.refresh() }}/>
    </div>

    {catalog.error != null && <ErrorNotice error={catalog.error} retry={catalog.refresh}/>}
    {list.error != null && <ErrorNotice error={list.error} retry={list.refresh}/>}

    <div className="rule-flow-page">
      <Card><CardContent>
        <h3>规则流清单 {list.data ? <Badge>{list.data.total}</Badge> : null}</h3>
        {list.loading && flows.length === 0 ? <Loading/> : flows.length === 0 ? <Empty title="还没有规则流">新建一条规则流，从设备消息 → 持久化 → 创建报警三个固定阶段开始。</Empty> :
          <div className="table-wrap"><table className="data-table"><thead><tr><th>名称</th><th>状态</th><th>草稿修订</th><th>发布修订</th><th>更新时间</th></tr></thead>
            <tbody>{flows.map(item => <tr key={item.id} className={selectedId === item.id ? 'selected' : ''}>
              <td><Button type="button" variant="ghost" size="sm" onClick={() => setSelectedId(item.id)}>{item.name}</Button></td>
              <td>{item.status === 'ARCHIVED' ? <Badge>已归档</Badge> : item.enabled ? <Badge>已启用</Badge> : item.status === 'PUBLISHED' ? <Badge>已发布 · 未启用</Badge> : <Badge>草稿</Badge>}</td>
              <td>{item.revision}</td>
              <td>{item.publishedRevision ?? '—'}</td>
              <td>{item.updatedAt}</td>
            </tr>)}</tbody></table></div>}
        {list.data && <Pager page={page} size={20} total={list.data.total} onChange={setPage}/>}
        {canWrite && <section className="form-stack">
          <h4><Plus size={14}/> 新建规则流</h4>
          <div className="field"><Label htmlFor="rule-flow-create-name">名称</Label><Input id="rule-flow-create-name" value={createName} onChange={event => setCreateName(event.target.value)} maxLength={120}/></div>
          <div className="field"><Label htmlFor="rule-flow-create-description">说明</Label><Input id="rule-flow-create-description" value={createDescription} onChange={event => setCreateDescription(event.target.value)} maxLength={1000}/></div>
          <div className="field"><Label htmlFor="rule-flow-create-reason">创建原因</Label><Input id="rule-flow-create-reason" value={createReason} onChange={event => setCreateReason(event.target.value)} maxLength={1000}/></div>
          {createError != null && <ErrorNotice error={createError}/>}
          <Button type="button" disabled={creating} onClick={() => void createFlow()}>创建草稿</Button>
          <p className="muted-note">创建后为草稿状态，不会启用任何通知或控制。</p>
        </section>}
      </CardContent></Card>

      <div className="rule-flow-detail">
        {detail.error != null && <ErrorNotice error={detail.error} retry={detail.refresh}/>}
        {selectedId == null ? <Empty title="未选择规则流">从左侧清单选择一条规则流开始编辑。</Empty> :
          detail.loading && !detail.data ? <Loading/> :
          detail.data ? (catalog.data ? <RuleFlowEditor tenantId={tenantId} flow={detail.data.flow} definition={detail.data.definition}
            catalog={catalog.data}
            canWrite={canWrite && detail.data.flow.status !== 'ARCHIVED'} canPublish={canPublish && detail.data.flow.status !== 'ARCHIVED'}
            onSaved={apply}/> : catalog.error != null ? <ErrorNotice error={catalog.error} retry={catalog.refresh}/> : <Loading/>) : <Empty title="规则流未加载"/>}
      </div>
    </div>
  </>
}
