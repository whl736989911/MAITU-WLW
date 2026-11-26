import { useEffect, useMemo, useState } from 'react'
import { legacyApi, type LegacyCompanyCount, type LegacyDecoderPreview, type LegacyDecoderRevision, type LegacyDevicePreview, type LegacyDictionaryPreview, type LegacyExportFile, type LegacyExportKind, type LegacyImportStats, type LegacyIngestMessage, type LegacyIngestState, type LegacyMessageDetail, type LegacyQuarantine, type LegacyReconcileDifference, type LegacyUnit } from '@/lib/legacy-api'
import { errorMessage } from '@/lib/api'
import { saveDataTransferFile } from '@/lib/data-transfer'
import { useSession } from '@/lib/store'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Pager } from '@/components/common'

const states: LegacyIngestState[] = ['NORMALIZED', 'DUPLICATE', 'PENDING_DECODER', 'FAILED']
const stateLabels: Record<LegacyIngestState, string> = { NORMALIZED: '已标准化', DUPLICATE: '重复报文', PENDING_DECODER: '待解码', FAILED: '失败' }
const text = (value: unknown) => value == null || value === '' ? '—' : String(value)
// jsdom and older browsers may lack randomUUID; the server only requires a unique safe ASCII token.
function newRequestKey() {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
const pageSize = 20
type Tab = 'messages' | 'differences' | 'dictionary' | 'devices' | 'decoders' | 'quarantines'

export function LegacyIngestPage() {
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeInitialized = useSession(s => s.scopeInitialized)
  const [items, setItems] = useState<LegacyIngestMessage[]>([]), [messageTotal, setMessageTotal] = useState(0), [page, setPage] = useState(1), [platform, setPlatform] = useState(''), [state, setState] = useState<LegacyIngestState | ''>('')
  const [messagesLoaded, setMessagesLoaded] = useState(false), [messagesLoading, setMessagesLoading] = useState(false)
  const [activeTab, setActiveTab] = useState<Tab>('messages'), [detail, setDetail] = useState<LegacyMessageDetail | null>(null)
  const [differences, setDifferences] = useState<LegacyReconcileDifference[]>([]), [differenceTotal, setDifferenceTotal] = useState(0), [differencePage, setDifferencePage] = useState(1), [differencesLoaded, setDifferencesLoaded] = useState(false), [differencesLoading, setDifferencesLoading] = useState(false)
  const [loadingError, setLoadingError] = useState(''), [detailError, setDetailError] = useState('')
  const [runId, setRunId] = useState('')
  const [exportKind, setExportKind] = useState<LegacyExportKind | null>(null)

  useEffect(() => {
    if (!scopeInitialized) { setMessagesLoaded(false); setMessagesLoading(false); setItems([]); setMessageTotal(0); return }
    const controller = new AbortController(); setMessagesLoaded(false); setMessagesLoading(true); setItems([]); setMessageTotal(0)
    legacyApi.messages(page, pageSize, state, platform, controller.signal, scopeTenantIds, scopePlaceIds).then(result => {
      setItems(result.items ?? []); setMessageTotal(result.total); setLoadingError(''); setMessagesLoaded(true); setMessagesLoading(false)
    }).catch(error => { if (!controller.signal.aborted) { setLoadingError(errorMessage(error)); setMessagesLoaded(false); setMessagesLoading(false) } })
    return () => controller.abort()
  }, [scopeInitialized, scopeTenantIds, scopePlaceIds, page, state, platform])

  useEffect(() => {
    if (!scopeInitialized) { setDifferences([]); setDifferenceTotal(0); setDifferencesLoaded(false); setDifferencesLoading(false); return }
    const controller = new AbortController(); setDifferences([]); setDifferenceTotal(0); setDifferencesLoaded(false); setDifferencesLoading(true)
    legacyApi.differences(runId, differencePage, pageSize, controller.signal, scopeTenantIds, scopePlaceIds).then(result => {
      setDifferences(result.items ?? []); setDifferenceTotal(result.total); setLoadingError(''); setDifferencesLoaded(true); setDifferencesLoading(false)
    }).catch(error => { if (!controller.signal.aborted) { setLoadingError(errorMessage(error)); setDifferencesLoaded(false); setDifferencesLoading(false) } })
    return () => controller.abort()
  }, [scopeInitialized, scopeTenantIds, scopePlaceIds, runId, differencePage])

  const counts = useMemo(() => Object.fromEntries(states.map(value => [value, items.filter(item => item.state === value).length])) as Record<LegacyIngestState, number>, [items])
  const platforms = useMemo(() => Array.from(new Set(items.map(item => item.vendor_platform).filter(Boolean))), [items])
  const runs = useMemo(() => Array.from(new Set(differences.map(item => item.run_id))), [differences])
  const unknownDevice = detail?.summary.state === 'FAILED' && detail.summary.failure_code === 'UNKNOWN_DEVICE'
  const openDetail = async (item: LegacyIngestMessage) => {
    setDetailError(''); setDetail({ summary: { ...item }, payload_raw: null, events: item.events ?? [] })
    try { setDetail(await legacyApi.message(item.id, undefined, item.tenant_id)) } catch (error) { setDetailError(errorMessage(error)) }
  }
  const exportFilters = exportKind === 'MESSAGES' ? { state, platform } : { run_id: runId }

  return <section className="legacy-ingest-workspace space-y-6 p-6" aria-label="接入镜像">
    <header className="ingest-workspace-heading"><h1 className="text-2xl font-semibold">接入镜像</h1><p className="text-muted-foreground">旧平台报文接收、标准化与旧数据管理。</p></header>
    {activeTab === 'messages' && <section aria-label="当前页报文状态" className="ingest-workspace-counts">
      <h2 className="text-sm font-medium text-muted-foreground">当前页状态 · 最多 {pageSize} 条（非全量统计）</h2>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">{states.map(value => <Card key={value}><CardHeader><CardTitle className="text-sm font-medium">{stateLabels[value]}</CardTitle></CardHeader><CardContent className="text-2xl font-semibold">{messagesLoaded ? counts[value] : '—'}</CardContent></Card>)}</div>
    </section>}
    <div className="ingest-workspace-tabs flex flex-wrap gap-2">
      <Button variant={activeTab === 'messages' ? 'default' : 'outline'} onClick={() => setActiveTab('messages')}>报文</Button>
      <Button variant={activeTab === 'differences' ? 'default' : 'outline'} onClick={() => setActiveTab('differences')}>对账差异</Button>
      <Button variant={activeTab === 'dictionary' ? 'default' : 'outline'} onClick={() => setActiveTab('dictionary')}>字典导入</Button>
      <Button variant={activeTab === 'devices' ? 'default' : 'outline'} onClick={() => setActiveTab('devices')}>设备导入</Button>
      <Button variant={activeTab === 'decoders' ? 'default' : 'outline'} onClick={() => setActiveTab('decoders')}>解码器</Button>
      <Button variant={activeTab === 'quarantines' ? 'default' : 'outline'} onClick={() => setActiveTab('quarantines')}>归属异常</Button>
    </div>
    {loadingError && <p role="alert">读取失败：{loadingError}</p>}
    {activeTab === 'messages' && <Card><CardHeader><CardTitle>报文列表</CardTitle>
      <div className="flex flex-wrap items-end gap-3">
        <label>状态<select aria-label="按状态筛选" className="ml-2 rounded border p-2" value={state} onChange={event => { setPage(1); setState(event.target.value as LegacyIngestState | '') }}><option value="">全部状态</option>{states.map(value => <option key={value} value={value}>{stateLabels[value]}</option>)}</select></label>
        <label>平台<select aria-label="按平台筛选" className="ml-2 rounded border p-2" value={platform} onChange={event => { setPage(1); setPlatform(event.target.value) }}><option value="">全部平台</option>{platforms.map(value => <option key={value} value={value}>{value}</option>)}</select></label>
        <Button variant="outline" onClick={() => setExportKind('MESSAGES')}>导出报文</Button>
      </div></CardHeader><CardContent><div className="w-full overflow-x-auto">
        <Table><TableHeader><TableRow><TableHead>时间</TableHead><TableHead>入口</TableHead><TableHead>平台</TableHead><TableHead>设备</TableHead><TableHead>单位</TableHead><TableHead>状态</TableHead><TableHead>失败码</TableHead><TableHead>事件数</TableHead></TableRow></TableHeader>
          <TableBody>{items.map(item => <TableRow key={item.id} className="cursor-pointer" onClick={() => void openDetail(item)}><TableCell>{text(item.received_at)}</TableCell><TableCell>{text(item.ingress)}</TableCell><TableCell>{text(item.vendor_platform)}</TableCell><TableCell>{text(item.vendor_device_id)}</TableCell><TableCell>{text(item.tenant_id)}</TableCell><TableCell><Badge variant={item.state === 'FAILED' ? 'destructive' : 'secondary'}>{stateLabels[item.state]}</Badge>{item.state === 'FAILED' && item.failure_code === 'UNKNOWN_DEVICE' && <span className="ml-2 text-sm">未识别到设备，报文已记录。</span>}</TableCell><TableCell>{text(item.failure_code)}</TableCell><TableCell>{text(item.event_count ?? item.events?.length)}</TableCell></TableRow>)}</TableBody></Table>
        {messagesLoading && <p className="p-4 text-muted-foreground">正在读取接入报文…</p>}
        {messagesLoaded && items.length === 0 && <p className="p-4 text-muted-foreground">暂无接入报文</p>}
      </div></CardContent></Card>}
    {activeTab === 'messages' && <Pager page={page} size={pageSize} total={messageTotal} onChange={setPage}/>}
    {activeTab === 'differences' && <Card><CardHeader><CardTitle>对账差异</CardTitle>
      <div className="flex flex-wrap items-end gap-3">
        <label>对账批次<select aria-label="按对账批次筛选" className="ml-2 rounded border p-2" value={runId} onChange={event => { setDifferencePage(1); setRunId(event.target.value) }}><option value="">全部批次</option>{runs.map(value => <option key={value} value={value}>{value}</option>)}</select></label>
        <Button variant="outline" onClick={() => setExportKind('RECONCILE')}>导出对账差异</Button>
      </div></CardHeader><CardContent><div className="w-full overflow-x-auto">
        <Table><TableHeader><TableRow><TableHead>批次</TableHead><TableHead>类型</TableHead><TableHead>旧记录</TableHead><TableHead>新记录</TableHead><TableHead>差异说明</TableHead><TableHead>时间</TableHead></TableRow></TableHeader>
          <TableBody>{differences.map(item => <TableRow key={item.id}><TableCell>{item.run_id}</TableCell><TableCell>{item.kind}</TableCell><TableCell>{text(item.legacy_id)}</TableCell><TableCell>{text(item.new_id)}</TableCell><TableCell>{item.detail}</TableCell><TableCell>{text(item.created_at)}</TableCell></TableRow>)}</TableBody></Table>
        {differencesLoading && <p className="p-4 text-muted-foreground">正在读取对账差异…</p>}
        {differencesLoaded && differences.length === 0 && <p className="p-4 text-muted-foreground">暂无对账差异</p>}
      </div></CardContent></Card>}
    {activeTab === 'differences' && <Pager page={differencePage} size={pageSize} total={differenceTotal} onChange={setDifferencePage}/>}
    {activeTab === 'dictionary' && <DictionaryPanel/>}
    {activeTab === 'devices' && <DevicePanel/>}
    {activeTab === 'decoders' && <DecoderPanel/>}
    {activeTab === 'quarantines' && <QuarantinePanel/>}
    <Sheet open={detail !== null} onOpenChange={open => { if (!open) setDetail(null) }}><SheetContent className="overflow-y-auto sm:max-w-2xl"><SheetHeader><SheetTitle>报文明细</SheetTitle><SheetDescription>{detail?.summary.id ?? ''}</SheetDescription></SheetHeader>{detail && <div className="space-y-5 p-4">
      {detailError && <p role="alert">详情读取失败：{detailError}</p>}
      {unknownDevice && <p role="alert" className="rounded border border-destructive p-3">未识别到设备（UNKNOWN_DEVICE）：报文已记录，但未关联设备。</p>}
      <section><h2 className="font-semibold">认设备结果</h2><p>{detail.summary.device_id ? `已关联设备：${detail.summary.device_id}` : unknownDevice ? '未识别到设备' : '没有关联设备'}</p><p>状态：{stateLabels[detail.summary.state]}</p><p>失败码：{text(detail.summary.failure_code)}</p></section>
      <details><summary className="cursor-pointer font-semibold">原始报文</summary>{detail.payload_redacted ? <p className="mt-2 rounded bg-muted p-3 text-sm">需要独立的原文权限（messages:raw）后查看原始报文；此处不猜测或回退。</p> : <pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap rounded bg-muted p-3">{detail.payload_raw ?? '未提供原始报文'}</pre>}</details>
      <section><h2 className="mb-2 font-semibold">事件列表（{detail.events.length}）</h2>{detail.events.length ? <ul className="space-y-2">{detail.events.map((event, index) => <li key={event.id ?? index} className="rounded border p-3"><p>{event.kind} · {text(event.method)}</p><p>{text(event.observed_at)}</p><pre className="whitespace-pre-wrap">{typeof event.params_json === 'string' ? event.params_json : JSON.stringify(event.params_json ?? {}, null, 2)}</pre></li>)}</ul> : <p>暂无事件</p>}</section>
    </div>}</SheetContent></Sheet>
    {exportKind && <ExportSheet kind={exportKind} filters={exportFilters} tenantIds={scopeTenantIds} placeIds={scopePlaceIds} disabled={!scopeInitialized} onClose={() => setExportKind(null)}/>}
  </section>
}

function ExportSheet({ kind, filters, tenantIds, placeIds, disabled, onClose }: { kind: LegacyExportKind; filters: { state?: string; platform?: string; run_id?: string }; tenantIds: string[]; placeIds: string[]; disabled: boolean; onClose: () => void }) {
  const [units, setUnits] = useState<LegacyUnit[]>([]), [unitsError, setUnitsError] = useState('')
  const [target, setTarget] = useState<'TENANT' | 'ALL_AUTHORIZED'>('TENANT'), [tenantId, setTenantId] = useState('')
  const [reason, setReason] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState(''), [receipt, setReceipt] = useState<LegacyExportFile | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    legacyApi.units(controller.signal).then(result => { setUnits(result); setUnitsError('') }).catch(reason => { if (!controller.signal.aborted) setUnitsError(errorMessage(reason)) })
    return () => controller.abort()
  }, [])
  const invalid = disabled || busy || !reason.trim() || target === 'TENANT' && !tenantId
  async function submit() {
    if (invalid) return
    setBusy(true); setError(''); setReceipt(null)
    try {
      const file = await legacyApi.exportFile({ kind, target, tenant_id: target === 'TENANT' ? tenantId : null, ...filters, reason: reason.trim() }, undefined, tenantIds, placeIds)
      await saveDataTransferFile(file)
      setReceipt(file)
    } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  return <Sheet open onOpenChange={open => { if (!open) onClose() }}><SheetContent className="overflow-y-auto sm:max-w-xl"><SheetHeader><SheetTitle>{kind === 'MESSAGES' ? '导出报文' : '导出对账差异'}</SheetTitle><SheetDescription>按当前范围重新校验权限后生成 CSV；文件名不含业务字段。</SheetDescription></SheetHeader>
    <div className="space-y-4 p-4">
      <p className="text-sm text-muted-foreground">筛选：{kind === 'MESSAGES' ? `状态 ${filters.state || '全部'} · 平台 ${filters.platform || '全部'}` : `批次 ${filters.run_id || '全部'}`}</p>
      <div><Label htmlFor="legacy-export-target">导出目标</Label><select id="legacy-export-target" aria-label="导出目标" className="mt-1 w-full rounded border p-2" value={target} onChange={event => setTarget(event.target.value as 'TENANT' | 'ALL_AUTHORIZED')}><option value="TENANT">指定单位</option><option value="ALL_AUTHORIZED">全部已授权单位</option></select></div>
      {target === 'TENANT' && <div><Label htmlFor="legacy-export-unit">单位</Label><select id="legacy-export-unit" aria-label="导出单位" className="mt-1 w-full rounded border p-2" value={tenantId} onChange={event => setTenantId(event.target.value)}><option value="">请选择明确单位</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.name}（{unit.id}）</option>)}</select>{unitsError && <p role="alert" className="mt-1 text-sm">单位列表读取失败：{unitsError}</p>}</div>}
      <div><Label htmlFor="legacy-export-reason">导出原因</Label><Input id="legacy-export-reason" aria-label="导出原因" value={reason} onChange={event => setReason(event.target.value)} maxLength={1000}/></div>
      
      {error && <p role="alert">{error}</p>}
      {receipt && <p role="status">已导出 {receipt.row_count} 行 · 摘要 {receipt.content_sha256.slice(0, 12)}… · {receipt.filename}</p>}
      <div className="flex gap-2"><Button disabled={invalid} onClick={() => void submit()}>{busy ? '正在导出…' : '导出并下载'}</Button><Button variant="outline" onClick={onClose}>关闭</Button></div>
      <p className="text-sm text-muted-foreground">导出仍要求当前有效权限；生成后文件摘要用于浏览器校验。</p>
    </div>
  </SheetContent></Sheet>
}

function DictionaryPanel() {
  const canWrite = useSession(s => s.permissions.includes('legacy-ingest:write'))
  const [preview, setPreview] = useState<LegacyDictionaryPreview | null>(null), [stats, setStats] = useState<LegacyImportStats | null>(null)
  const [loading, setLoading] = useState(false), [error, setError] = useState(''), [reason, setReason] = useState(''), [busy, setBusy] = useState(false)
  async function load() {
    setLoading(true); setError('')
    try { setPreview(await legacyApi.previewDictionaries()); setStats(null) } catch (failure) { setError(errorMessage(failure)) } finally { setLoading(false) }
  }
  async function confirm() {
    if (!preview || !reason.trim() || busy) return
    setBusy(true); setError('')
    try {
      setStats(await legacyApi.confirmDictionaries({ expected_sha256: preview.source_sha256, reason: reason.trim() }, newRequestKey()))
    } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  return <Card><CardHeader><CardTitle>旧平台字典导入</CardTitle><p className="text-sm text-muted-foreground">先读取只读预览，再依据来源摘要和影响范围二次确认。不会覆盖本地已编辑的名称/属性，不会恢复已归档位置。</p></CardHeader><CardContent className="space-y-4">
    <div className="flex flex-wrap gap-2"><Button variant="outline" disabled={loading} onClick={() => void load()}>{loading ? '正在读取预览…' : '读取只读预览'}</Button></div>
    {error && <p role="alert">{error}</p>}
    {preview && <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <PreviewItem label="来源单位" value={preview.source_companies}/>
      <PreviewItem label="来源场所" value={preview.source_places}/>
      <PreviewItem label="来源产品" value={preview.source_products}/>
      <PreviewItem label="来源属性" value={preview.source_attributes}/>
      <PreviewItem label="来源解码器文件" value={preview.source_decoder_files}/>
      <PreviewItem label="已存在版本" value={preview.versions_existing}/>
      <PreviewItem label="场所缺单位" value={preview.places_tenant_missing} warn/>
      <PreviewItem label="场所归属冲突" value={preview.places_identity_conflict} warn/>
      <PreviewItem label="已归档场所" value={preview.places_archived} warn/>
      <PreviewItem label="损坏产品行" value={preview.damaged_product_rows} warn/>
      <PreviewItem label="重复型号行" value={preview.duplicate_model_rows} warn/>
      <PreviewItem label="层级来源行" value={preview.hierarchy_source_rows}/>
    </div>}
    {preview && <p className="text-sm text-muted-foreground">来源摘要 {preview.source_sha256.slice(0, 16)}…；层级来源{preview.hierarchy_configured ? '已配置' : '未配置'}。确认后会把 {preview.grant_legacy_units} 个 LEGACY 单位授权给当前操作账号（现有导入行为）。</p>}
    {preview && <div className="grid gap-3 sm:grid-cols-2">
      <div><Label htmlFor="legacy-dict-reason">导入原因</Label><Input id="legacy-dict-reason" aria-label="字典导入原因" value={reason} onChange={event => setReason(event.target.value)} maxLength={1000}/></div>
      
    </div>}
    {preview && <Button disabled={!canWrite || !reason.trim() || busy} onClick={() => void confirm()}>{busy ? '正在导入…' : '确认导入字典'}</Button>}
    {!canWrite && <p role="alert" className="text-sm">当前账户只有 legacy-ingest:read，预览可用；确认导入需要 legacy-ingest:write。</p>}
    {stats && <div role="status" className="rounded border p-3 text-sm">本次结果：单位 {stats.tenants} · 场所 {stats.places} · 产品 {stats.products} · 版本 {stats.versions} · 属性 {stats.attributes} · 解码器 {stats.decoders} · 差异 {stats.differences}；层级应用 {stats.hierarchy_applied} / 跳过 {stats.hierarchy_skipped}。</div>}
    <p className="text-sm text-muted-foreground">预览为只读来源统计与冲突计数；产品导入仅创建 DRAFT 版本，不发布、不绑定设备。</p>
  </CardContent></Card>
}

function DevicePanel() {
  const canWrite = useSession(s => s.permissions.includes('legacy-ingest:write'))
  const [companies, setCompanies] = useState<string[]>([]), [preview, setPreview] = useState<LegacyDevicePreview | null>(null), [stats, setStats] = useState<{ devices: number; aliases: number; differences: number } | null>(null)
  const [loading, setLoading] = useState(false), [error, setError] = useState(''), [reason, setReason] = useState(''), [busy, setBusy] = useState(false)
  async function load() {
    setLoading(true); setError('')
    try { setPreview(await legacyApi.previewDevices(companies)); setStats(null) } catch (failure) { setError(errorMessage(failure)) } finally { setLoading(false) }
  }
  async function confirm() {
    if (!preview || companies.length === 0 || !reason.trim() || busy) return
    setBusy(true); setError('')
    try {
      setStats(await legacyApi.confirmDevices({ expected_sha256: preview.source_sha256, company_ids: companies, reason: reason.trim() }, newRequestKey()))
    } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  const toggle = (id: string) => setCompanies(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id])
  return <Card><CardHeader><CardTitle>旧平台设备导入</CardTitle><p className="text-sm text-muted-foreground">必须明确选择来源单位后确认。已归档或已转移的设备不会被恢复或改写归属；多个候选产品不会自动绑定。</p></CardHeader><CardContent className="space-y-4">
    <div className="flex flex-wrap items-end gap-2"><Button variant="outline" disabled={loading} onClick={() => void load()}>{loading ? '正在读取预览…' : companies.length ? `预览选定 ${companies.length} 个单位` : '只读预览全部来源单位'}</Button><span className="text-sm text-muted-foreground">已选 {companies.length} 个单位</span></div>
    {error && <p role="alert">{error}</p>}
    {preview && <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <PreviewItem label="台账总行" value={preview.total_rows}/>
      <PreviewItem label="本次选定行" value={preview.selected_rows}/>
      <PreviewItem label="可新增设备" value={preview.importable_new}/>
      <PreviewItem label="已存在" value={preview.existing}/>
      <PreviewItem label="已归档" value={preview.archived} warn/>
      <PreviewItem label="已转移" value={preview.moved} warn/>
      <PreviewItem label="场所缺失" value={preview.place_missing} warn/>
      <PreviewItem label="型号无匹配" value={preview.model_unmatched} warn/>
      <PreviewItem label="型号多候选" value={preview.model_ambiguous} warn/>
      <PreviewItem label="唯一型号候选" value={preview.model_single}/>
      <PreviewItem label="标识冲突" value={preview.identity_invalid} warn/>
      <PreviewItem label="EUI 冲突" value={preview.eui_conflict} warn/>
      <PreviewItem label="别名冲突" value={preview.alias_conflict} warn/>
    </div>}
    {preview && <div><p className="mb-2 text-sm font-medium">来源单位（点击选择）</p><div className="grid max-h-56 gap-2 overflow-y-auto sm:grid-cols-2 lg:grid-cols-3">{preview.companies.map((company: LegacyCompanyCount) => <label key={company.company_id} className="flex items-center gap-2 rounded border p-2 text-sm"><input type="checkbox" checked={companies.includes(company.company_id)} onChange={() => toggle(company.company_id)}/>{company.company_id} · {company.rows} 行</label>)}</div></div>}
    {preview && <p className="text-sm text-muted-foreground">来源摘要 {preview.source_sha256.slice(0, 16)}…；确认会为当前账号授予全部 LEGACY 单位范围（现有导入行为）。</p>}
    {preview && <div className="grid gap-3 sm:grid-cols-2">
      <div><Label htmlFor="legacy-device-reason">导入原因</Label><Input id="legacy-device-reason" aria-label="设备导入原因" value={reason} onChange={event => setReason(event.target.value)} maxLength={1000}/></div>
      
    </div>}
    {preview && <Button disabled={!canWrite || companies.length === 0 || !reason.trim() || busy} onClick={() => void confirm()}>{busy ? '正在导入…' : `确认导入 ${companies.length} 个单位`}</Button>}
    {!canWrite && <p role="alert" className="text-sm">当前账户只有 legacy-ingest:read，预览可用；确认导入需要 legacy-ingest:write。</p>}
    {stats && <div role="status" className="rounded border p-3 text-sm">本次结果：设备 {stats.devices} · 别名 {stats.aliases} · 差异 {stats.differences}。</div>}
  </CardContent></Card>
}

function DecoderPanel() {
  const canWrite = useSession(s => s.permissions.includes('legacy-ingest:write'))
  const [productId, setProductId] = useState(''), [revisions, setRevisions] = useState<LegacyDecoderRevision[]>([]), [selected, setSelected] = useState<number | null>(null)
  const [payloadHex, setPayloadHex] = useState(''), [attrs, setAttrs] = useState(''), [source, setSource] = useState('')
  const [reason, setReason] = useState(''), [preview, setPreview] = useState<LegacyDecoderPreview | null>(null)
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [message, setMessage] = useState('')
  async function loadRevisions() {
    if (!productId.trim()) return setError('请先填写旧产品标识')
    setBusy(true); setError(''); setMessage('')
    try { const rows = await legacyApi.decoderRevisions(productId.trim()); setRevisions(rows); setSelected(rows.find(row => row.active)?.revision ?? null) } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  function attrValue(): Record<string, unknown> | undefined {
    if (!attrs.trim()) return undefined
    const value: unknown = JSON.parse(attrs)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('属性必须是 JSON 对象')
    return value as Record<string, unknown>
  }
  async function runStored() {
    if (!productId.trim() || selected == null || !payloadHex.trim() || busy) return
    setBusy(true); setError(''); setMessage('')
    try { setPreview(await legacyApi.previewDecoderRevision(productId.trim(), selected, { payload_hex: payloadHex.trim(), attrs: attrValue() })) } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  async function runSource() {
    if (!source.trim() || !payloadHex.trim() || busy) return
    setBusy(true); setError(''); setMessage('')
    try { setPreview(await legacyApi.previewDecoderSource({ source, payload_hex: payloadHex.trim(), attrs: attrValue() })) } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  async function importSource() {
    if (!productId.trim() || !source.trim() || !reason.trim() || busy) return
    setBusy(true); setError(''); setMessage('')
    try {
      const revision = await legacyApi.importDecoder({ legacy_product_id: productId.trim(), source, reason: reason.trim() }, newRequestKey())
      setMessage(`已导入修订 ${revision.revision}（${revision.source_sha256.slice(0, 12)}…），并作为最新修订用于后续解码。`)
      setRevisions(await legacyApi.decoderRevisions(productId.trim()))
      setSelected(revision.revision)
    } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  return <Card><CardHeader><CardTitle>解码器修订与预览</CardTitle><p className="text-sm text-muted-foreground">预览在无宿主绑定的 QuickJS/WASM 中运行：WASM 内存上限32MiB、脚本中断预算200ms、外层进程截止1s、输出上限256KiB。分配和回收不保证200ms内完成；预览不写库、不产生设备事实或通知。</p></CardHeader><CardContent className="space-y-4">
    <div className="flex flex-wrap items-end gap-2"><div className="grow"><Label htmlFor="legacy-decoder-product">旧产品标识</Label><Input id="legacy-decoder-product" aria-label="旧产品标识" value={productId} onChange={event => setProductId(event.target.value)} maxLength={64}/></div><Button variant="outline" disabled={busy} onClick={() => void loadRevisions()}>读取修订</Button></div>
    {revisions.length > 0 && <Table><TableHeader><TableRow><TableHead>修订</TableHead><TableHead>来源摘要</TableHead><TableHead>字节</TableHead><TableHead>时间</TableHead><TableHead>状态</TableHead></TableRow></TableHeader><TableBody>{revisions.map(row => <TableRow key={row.revision} className="cursor-pointer" onClick={() => setSelected(row.revision)}><TableCell>{row.revision}</TableCell><TableCell>{row.source_sha256.slice(0, 16)}…</TableCell><TableCell>{row.bytes}</TableCell><TableCell>{row.created_at}</TableCell><TableCell>{row.active ? <Badge>最新生效</Badge> : selected === row.revision ? <Badge variant="secondary">已选择</Badge> : text('')}</TableCell></TableRow>)}</TableBody></Table>}
    {revisions.length === 0 && <p className="text-sm text-muted-foreground">尚未读取该产品的修订。</p>}
    <div className="grid gap-3 sm:grid-cols-2"><div><Label htmlFor="legacy-decoder-hex">报文（十六进制，偶数长度）</Label><Input id="legacy-decoder-hex" aria-label="报文十六进制" value={payloadHex} onChange={event => setPayloadHex(event.target.value)}/></div><div><Label htmlFor="legacy-decoder-attrs">属性 JSON（可选）</Label><Input id="legacy-decoder-attrs" aria-label="属性JSON" value={attrs} onChange={event => setAttrs(event.target.value)}/></div></div>
    <div className="flex flex-wrap gap-2"><Button variant="outline" disabled={!canWrite || busy || selected == null || !payloadHex.trim()} onClick={() => void runStored()}>预览已选修订</Button></div>
    <div><Label htmlFor="legacy-decoder-source">粘贴源码（导入或一次性预览）</Label><textarea id="legacy-decoder-source" aria-label="解码器源码" className="mt-1 min-h-40 w-full rounded border p-2 font-mono text-sm" value={source} onChange={event => setSource(event.target.value)}/></div>
    <div className="flex flex-wrap gap-2"><Button variant="outline" disabled={!canWrite || busy || !source.trim() || !payloadHex.trim()} onClick={() => void runSource()}>用该源码预览</Button></div>
    <div className="grid gap-3 sm:grid-cols-2"><div><Label htmlFor="legacy-decoder-reason">导入原因</Label><Input id="legacy-decoder-reason" aria-label="解码器导入原因" value={reason} onChange={event => setReason(event.target.value)} maxLength={1000}/></div></div>
    <Button disabled={!canWrite || busy || !productId.trim() || !source.trim() || !reason.trim()} onClick={() => void importSource()}>{busy ? '正在执行…' : '导入为新修订'}</Button>
    {!canWrite && <p role="alert" className="text-sm">当前账户只有 legacy-ingest:read；预览和导入需要 legacy-ingest:write。</p>}
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    {preview && <div className="space-y-2 rounded border p-3"><p>{preview.ok ? `沙箱返回 ${preview.events} 个事件` : `沙箱未通过：${preview.code}`}{preview.message ? ` · ${preview.message}` : ''}</p><p className="text-sm text-muted-foreground">源码摘要 {preview.source_sha256.slice(0, 16)}…</p>{preview.events_json && <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded bg-muted p-3 text-sm">{preview.events_json}</pre>}{!preview.ok && <p role="alert" className="text-sm">预览失败不会被当作成功，也不会写入任何修订。</p>}</div>}
  </CardContent></Card>
}

function QuarantinePanel() {
  const permissions = useSession(s => s.permissions), canRead = permissions.includes('ownership-exceptions:read')
  const [items, setItems] = useState<LegacyQuarantine[]>([]), [total, setTotal] = useState(0), [page, setPage] = useState(1), [error, setError] = useState(''), [loading, setLoading] = useState(false)
  useEffect(() => {
    if (!canRead) { setItems([]); setTotal(0); return }
    const controller = new AbortController(); setLoading(true); setError('')
    legacyApi.quarantines(page, pageSize, controller.signal).then(result => { setItems(result.items ?? []); setTotal(result.total); setLoading(false) }).catch(failure => { if (!controller.signal.aborted) { setError(errorMessage(failure)); setLoading(false) } })
    return () => controller.abort()
  }, [canRead, page])
  if (!canRead) return <Card><CardHeader><CardTitle>归属异常</CardTitle></CardHeader><CardContent><p className="text-sm text-muted-foreground">需要独立的 <code>ownership-exceptions:read</code> 权限才能查看待定归属报文；本页不提供重放或归属猜测。</p></CardContent></Card>
  return <Card><CardHeader><CardTitle>归属异常（待人工处理）</CardTitle><p className="text-sm text-muted-foreground">这些报文因来源时间或归属证据不足被隔离，保持 FAILED 且未关联设备；不展示原始报文，不自动重放、归一化或发送通知。处理需要来源时间戳与明确设备绑定。</p></CardHeader><CardContent className="space-y-3">
    {error && <p role="alert">{error}</p>}
    <div className="w-full overflow-x-auto"><Table><TableHeader><TableRow><TableHead>接收时间</TableHead><TableHead>平台</TableHead><TableHead>厂商设备</TableHead><TableHead>候选项</TableHead><TableHead>原因</TableHead><TableHead>观测时间</TableHead></TableRow></TableHeader><TableBody>{items.map(item => <TableRow key={item.id}><TableCell>{text(item.received_at)}</TableCell><TableCell>{text(item.vendor_platform)}</TableCell><TableCell>{text(item.vendor_device_id)}</TableCell><TableCell>{text(item.candidate_device_id)}</TableCell><TableCell>{item.reason}</TableCell><TableCell>{text(item.observed_at)}</TableCell></TableRow>)}</TableBody></Table>
      {loading && <p className="p-4 text-muted-foreground">正在读取归属异常…</p>}
      {!loading && items.length === 0 && <p className="p-4 text-muted-foreground">暂无待处理归属异常</p>}
    </div>
    <Pager page={page} size={pageSize} total={total} onChange={setPage}/>
  </CardContent></Card>
}

function PreviewItem({ label, value, warn = false }: { label: string; value: number; warn?: boolean }) {
  return <div className="rounded border p-3"><p className="text-sm text-muted-foreground">{label}</p><p className={`text-xl font-semibold ${warn && value > 0 ? 'text-destructive' : ''}`}>{value}</p></div>
}
