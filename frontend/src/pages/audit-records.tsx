import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Download, FileSearch, RefreshCw, Search, X } from 'lucide-react'
import { useSession } from '@/lib/store'
import { auditActionDefinitions, auditActionLabels, auditOutcomeDefinitions, auditOutcomeLabels, auditModules, auditPlatforms, compileAuditDraft, compileAuditSearch, emptyAuditDraft, emptyAuditFilters, emptyAuditSearch, exportAuditRecords, getAuditRecords, type AuditDraft, type AuditFilters, type AuditLogSearch, type AuditRecord, type AuditRecords, type AuditReferenceState } from '@/lib/audit-records'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
function useAuditRead(key: string, filters: AuditFilters, search: AuditLogSearch, page: number) {
  const sequence = useRef(0), active = useRef<AbortController | null>(null), query = useRef({ filters, search, page }); query.current = { filters, search, page }
  const [state, setState] = useState<{ key: string; loading: boolean; data?: AuditRecords; error?: unknown; cancelled?: boolean }>({ key, loading: true })
  function invalidate() { ++sequence.current; active.current?.abort(); active.current = null }
  useEffect(() => {
    const id = ++sequence.current, controller = new AbortController(); active.current = controller
    const current = () => id === sequence.current && !controller.signal.aborted
    setState({ key, loading: true })
    void Promise.resolve().then(() => { if (!current()) throw new DOMException('已取消', 'AbortError'); return getAuditRecords(query.current.filters, query.current.page, undefined, controller.signal, current, query.current.search) }).then(data => { if (current()) setState({ key, loading: false, data }) }, error => { if (current() && !(error instanceof DOMException && error.name === 'AbortError')) setState({ key, loading: false, error }) })
    return () => { if (id === sequence.current) ++sequence.current; controller.abort() }
  }, [key])
  const visible = state.key === key ? state : { key, loading: true }
  return { ...visible, invalidate, cancel: () => { invalidate(); setState({ key, loading: false, cancelled: true }) } }
}
export function AuditRecordsPage() {
  const session = useSession(), metadata = JSON.stringify([session.user, session.generation, session.permissions, session.audience, session.scopeTenantIds, session.scopePlaceIds, session.scopeInitialized, session.page]), scope = useRef({ token: session.token, metadata, version: 0 })
  if (scope.current.token !== session.token || scope.current.metadata !== metadata) scope.current = { token: session.token, metadata, version: scope.current.version + 1 }
  if (!session.token || !session.user || session.user.user_type !== 'PLATFORM' || session.user.must_change_password || session.audience !== 'management' || !session.permissions.includes('audit:read') || session.page !== 'audit-records') return <Empty title="当前无授权单位审计读取权限"/>
  return <Content key={scope.current.version}/>
}
function Reference({ value, state }: { value: string | null; state: AuditReferenceState }) { return <span data-audit-reference-state={state} className="mono">{state === 'UUID' ? value : state === 'WITHHELD' ? '不公开' : '未记录'}</span> }
function Content() {
  const session = useSession()
  const [draft, setDraft] = useState<AuditDraft>({ ...emptyAuditDraft }), [applied, setApplied] = useState<AuditFilters>({ ...emptyAuditFilters })
  const [searchDraft, setSearchDraft] = useState<Record<keyof AuditLogSearch, string>>({ keyword: '', username: '', module: '', platform: '' }), [search, setSearch] = useState<AuditLogSearch>({ ...emptyAuditSearch })
  const [page, setPage] = useState(1), [revision, setRevision] = useState(0), [error, setError] = useState<unknown>(), [selected, setSelected] = useState<AuditRecord | null>(null), [exporting, setExporting] = useState(false), [notice, setNotice] = useState('')
  const exportRun = useRef<AbortController | null>(null)
  useEffect(() => () => exportRun.current?.abort(), [])
  const read = useAuditRead(JSON.stringify([applied, search, page, revision, session.scopeTenantIds, session.scopePlaceIds]), applied, search, page), data = read.data
  const canNames = session.permissions.includes('users:read') || session.permissions.includes('platform-users:read')
  function update(key: keyof AuditDraft, value: string) { setDraft(old => ({ ...old, [key]: value })); setError(undefined) }
  function updateSearch(key: keyof AuditLogSearch, value: string) { setSearchDraft(old => ({ ...old, [key]: value })); setError(undefined) }
  function query(filters: AuditFilters, nextSearch: AuditLogSearch, nextPage: number) { read.invalidate(); exportRun.current?.abort(); setExporting(false); setSelected(null); setApplied(filters); setSearch(nextSearch); setPage(nextPage); setRevision(value => value + 1) }
  function apply(event: FormEvent) { event.preventDefault(); try { const filters = compileAuditDraft(draft), next = compileAuditSearch(searchDraft); setError(undefined); query(filters, next, 1) } catch (failure) { setError(failure) } }
  function clear() { setDraft({ ...emptyAuditDraft }); setSearchDraft({ keyword: '', username: '', module: '', platform: '' }); setError(undefined); query({ ...emptyAuditFilters }, { ...emptyAuditSearch }, 1) }
  function moduleFilter(module: AuditLogSearch['module']) { setSearchDraft(old => ({ ...old, module: module ?? '' })); query(applied, { ...search, module }, 1) }
  async function download() {
    const controller = new AbortController(); exportRun.current = controller; setExporting(true); setError(undefined); setNotice('')
    const current = () => exportRun.current === controller && !controller.signal.aborted
    try {
      const blob = await exportAuditRecords(applied, search, controller.signal, current)
      if (!current()) return
      const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = 'operation-logs.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 0)
      setNotice('已导出当前筛选条件下的全部授权日志。')
    } catch (failure) { if (current() && !(failure instanceof DOMException && failure.name === 'AbortError')) setError(failure) }
    finally { if (current()) { exportRun.current = null; setExporting(false) } }
  }
  const metadata = selected && data ? data.details[selected.id] : undefined
  return <section className="replica-page" aria-label="授权单位操作审计" data-audit-records-as-of={data?.as_of}>
    <PageHeading eyebrow="系统管理 / 日志管理" title="日志管理" description="按授权单位检索已记录的操作与审计结果。">
      <PageHelp title="审计数据说明"><div className="form-stack"><p>仅展示授权单位的持久记录，不补造历史；可用详情查看当前查询返回的记录。</p><p>请求端是描述性标记，不证明调用者身份。原始请求、凭据及自由文本原因不公开。</p></div></PageHelp>
    </PageHeading>
    <div className="replica-directory-layout">
      <Card className="replica-module-tree"><CardContent><h2>功能模块</h2><nav aria-label="日志功能模块"><Button variant={search.module === null ? 'secondary' : 'ghost'} onClick={() => moduleFilter(null)}>全部模块</Button>{Object.entries(auditModules).map(([code, label]) => <Button key={code} variant={search.module === code ? 'secondary' : 'ghost'} onClick={() => moduleFilter(code as keyof typeof auditModules)}>{label}</Button>)}</nav></CardContent></Card>
      <div className="replica-directory-main">
        <Card className="list-card management-list-panel"><CardContent>
          <div className="management-list-bar">
            <h2>查询与导出</h2>
            <div className="list-toolbar management-list-actions">
              <Button variant="outline" onClick={() => query(applied, search, page)}><RefreshCw size={15}/>刷新审计记录</Button>
              {session.permissions.includes('exports:read') && <Button variant="outline" disabled={exporting || read.loading} onClick={() => void download()}><Download size={15}/>{exporting ? '正在导出' : '导出筛选结果'}</Button>}
              {read.loading && <Button variant="outline" onClick={read.cancel}>取消本次读取</Button>}
              {exporting && <Button variant="outline" onClick={() => { exportRun.current?.abort(); exportRun.current = null; setExporting(false) }}>取消导出</Button>}
            </div>
          </div>
          <form onSubmit={apply} className="form-stack"><div className="replica-filter-grid">
          <div className="field"><Label htmlFor="audit-keyword">操作关键字</Label><Input id="audit-keyword" maxLength={100} value={searchDraft.keyword} onChange={event => updateSearch('keyword', event.target.value)} placeholder="单位名称、操作代码、记录编号"/></div>
          <div className="field"><Label htmlFor="audit-username">用户名</Label><Input id="audit-username" maxLength={100} disabled={!canNames} value={searchDraft.username} onChange={event => updateSearch('username', event.target.value)} placeholder={canNames ? '按当前可读用户目录查询' : '需要相应用户目录读取权限'}/></div>
          <div className="field"><Label htmlFor="audit-action">审计动作</Label><select id="audit-action" className="native-select" value={draft.action} onChange={event => update('action', event.target.value)}><option value="">全部动作分类</option>{auditActionDefinitions.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}</select></div>
          <div className="field"><Label htmlFor="audit-outcome">审计结果</Label><select id="audit-outcome" className="native-select" value={draft.outcome} onChange={event => update('outcome', event.target.value)}><option value="">全部结果分类</option>{auditOutcomeDefinitions.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}</select></div>
          <div className="field"><Label htmlFor="audit-platform">请求端标记</Label><select id="audit-platform" className="native-select" value={searchDraft.platform} onChange={event => updateSearch('platform', event.target.value)}><option value="">全部请求端</option>{Object.entries(auditPlatforms).map(([code, label]) => <option key={code} value={code}>{label}</option>)}</select></div>
          <div className="field"><Label htmlFor="audit-from">发生开始（北京时间，含）</Label><Input id="audit-from" value={draft.occurred_from} onChange={event => update('occurred_from', event.target.value)} placeholder="YYYY-MM-DDTHH:mm:ss.ffffff" autoComplete="off"/></div>
          <div className="field"><Label htmlFor="audit-to">发生结束（北京时间，不含）</Label><Input id="audit-to" value={draft.occurred_to} onChange={event => update('occurred_to', event.target.value)} placeholder="YYYY-MM-DDTHH:mm:ss.ffffff" autoComplete="off"/></div>
        </div><details><summary>精确引用筛选</summary><div className="replica-filter-grid"><div className="field"><Label htmlFor="audit-actor">操作者UUID引用</Label><Input id="audit-actor" value={draft.actor_ref} onChange={event => update('actor_ref', event.target.value)} autoComplete="off"/></div><div className="field"><Label htmlFor="audit-target">目标UUID引用</Label><Input id="audit-target" value={draft.target_ref} onChange={event => update('target_ref', event.target.value)} autoComplete="off"/></div></div></details>
          <div className="version-actions"><Button type="submit"><Search size={15}/>应用审计筛选</Button><Button type="button" variant="outline" onClick={clear}><X size={15}/>清空审计条件</Button></div>
        </form></CardContent></Card>
        {error != null && <ErrorNotice error={error}/>}{notice && <p role="status" className="notice">{notice}</p>}
        <Card className="list-card management-list-panel"><CardContent>
          <div className="management-list-bar"><h2>操作记录{data && <span className="muted-note"> · {data.total} 条</span>}</h2>{data && <span className="muted-note">{data.scope.tenant_ids.length} 个授权单位 · {search.module ? auditModules[search.module] : '全部模块'}</span>}</div>
          {read.loading ? <Loading/> : read.error != null ? <ErrorNotice error={read.error} retry={() => query(applied, search, page)}/> : read.cancelled ? <Empty title="本次审计读取已取消，可显式刷新"/> : data ? <>
          {data.items.length ? <Table><TableHeader><TableRow><TableHead>发生时间（UTC）</TableHead><TableHead>单位</TableHead><TableHead>用户名</TableHead><TableHead>功能模块</TableHead><TableHead>操作</TableHead><TableHead>结果</TableHead><TableHead>请求端</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>{data.items.map(item => { const meta = data.details[item.id]; return <TableRow key={item.id} data-audit-record-id={item.id}><TableCell className="mono">{item.occurred_at}</TableCell><TableCell>{item.tenant_name}</TableCell><TableCell>{meta.actor_username ?? '不公开或未记录'}</TableCell><TableCell>{auditModules[meta.module]}</TableCell><TableCell><div>{auditActionLabels[item.action]}</div><small className="mono">{item.id}</small></TableCell><TableCell>{auditOutcomeLabels[item.outcome]}</TableCell><TableCell>{auditPlatforms[meta.platform]}</TableCell><TableCell><Button variant="ghost" size="sm" onClick={() => setSelected(item)}><FileSearch size={14}/>查看记录</Button></TableCell></TableRow> })}</TableBody></Table> : <Empty title="当前条件暂无审计记录"/>}
          <Pager page={data.page} size={data.size} total={data.total} onChange={next => query(applied, search, next)}/>
        </> : null}</CardContent></Card>
        
      </div>
    </div>
    <Sheet open={selected !== null} onOpenChange={open => { if (!open) setSelected(null) }}><SheetContent className="view-sheet sm:max-w-2xl"><SheetHeader><SheetTitle>操作记录详情</SheetTitle><SheetDescription>只读查看当前授权查询返回的记录，不回写业务状态。</SheetDescription></SheetHeader>{selected && metadata && <div className="review-body"><dl className="detail-list"><div><dt>记录编号</dt><dd className="mono">{selected.id}</dd></div><div><dt>单位</dt><dd>{selected.tenant_name}</dd></div><div><dt>单位编号</dt><dd className="mono">{selected.tenant_id}</dd></div><div><dt>发生时间（UTC）</dt><dd className="mono">{selected.occurred_at}</dd></div><div><dt>用户名</dt><dd>{metadata.actor_username ?? '不公开或未记录'}</dd></div><div><dt>功能模块</dt><dd>{auditModules[metadata.module]}</dd></div><div><dt>操作</dt><dd>{auditActionLabels[selected.action]} · {selected.action}</dd></div><div><dt>结果</dt><dd>{auditOutcomeLabels[selected.outcome]} · {selected.outcome}</dd></div><div><dt>请求端标记</dt><dd>{auditPlatforms[metadata.platform]}</dd></div><div><dt>操作者引用</dt><dd><Reference value={selected.actor_ref} state={selected.actor_reference_state}/></dd></div><div><dt>目标引用</dt><dd><Reference value={selected.target_ref} state={selected.target_reference_state}/></dd></div></dl></div>}</SheetContent></Sheet>
  </section>
}
