import { DataTransferActions } from '@/components/data-transfer-actions'
import { useEffect, useMemo, useState, useSyncExternalStore, type FormEvent } from 'react'
import { Archive, ArchiveRestore, Boxes, Copy, FileJson, Pencil, Play, Plus, Rocket } from 'lucide-react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { allowedPage, type Capabilities, type CapabilityState, type ParserTestResult, type Product, type ProductVersion } from '@/lib/types'
import { sampleDocument, schemaDocument } from '@/lib/product-json'
import { capabilityEditorLabels, capabilityKeys, parseSchemaDraft, pendingCapabilities, productCapabilities, serializeSchemaDraft, validateSchemaDraft } from '@/lib/product-schema'
import { parseSampleDrafts, validateSampleDrafts } from '@/lib/product-samples'
import { SchemaEditor, SchemaSummary } from '@/components/product-measurement-editor'
import { SampleEditor } from '@/components/product-sample-editor'
import { dateTime } from '@/lib/format'
import { productState, productsByState } from '@/lib/product-metadata'
import { ProductLifecycleEditor, ProductMetadataEditor, type ProductLifecycleAction } from '@/components/product-metadata-editor'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { IdentitySearch, OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { CapabilityBadges, ParserResults } from '@/components/product-common'
import { ProductServiceDefinitions } from '@/components/product-service-definitions'
import { beginProductWrite, consumeProductWriteCompletion, getProductWriteSnapshot, releaseProductConflict, retryProductWrite, subscribeProductWrite, type ProductWriteCommand, type ProductWriteIntent } from '@/lib/product-write-intent'
import { PageHelp } from '@/components/page-tools'

type VersionMode = 'new' | 'clone' | 'edit' | 'test' | 'publish'
type ProductStateFilter = 'ACTIVE' | 'ARCHIVED' | 'ALL'
interface VersionEditorState { mode: VersionMode; version: ProductVersion | null }
export function ProductsPage() {
  const { user, audience, permissions, generation } = useSession()
  if (!user || user.must_change_password || !allowedPage('products', audience, permissions)) return <Empty title="当前账户无产品模板权限"/>
  return <ProductsContent key={generation}/>
}
function ProductsContent() {
  const canWrite = useSession(s => s.permissions.includes('products:write'))
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [stateFilter, setStateFilter] = useState<ProductStateFilter>('ACTIVE')
  const products = useResource(`products:${page}:${keyword}:${stateFilter}`, signal => productsByState(page, keyword, stateFilter, signal))
  const [selected, setSelected] = useState<Product | null>(null)
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<Product | null>(null)
  const [lifecycle, setLifecycle] = useState<{ product: Product; action: ProductLifecycleAction } | null>(null)
  function applyProduct(updated: Product) {
    setSelected(current => current && current.id === updated.id ? updated : current)
    setEditing(null); setLifecycle(null); products.refresh()
  }
  return <><PageHeading eyebrow="PRODUCTS · 产品模型" title="产品中心" description="维护产品型号、版本和声明能力；设备绑定具体的已发布版本。"><RefreshButton onClick={products.refresh}/>{canWrite && <Button onClick={() => setCreating(true)}><Plus/>创建产品</Button>}<DataTransferActions kind="PRODUCT_DRAFTS" label="产品草稿数据" onCompleted={receipt => { if ('plan_id' in receipt) products.refresh() }}/><PageHelp title="产品版本与能力证据"><p>本页管理全局产品模板。JSON Pointer 映射只解析合成测试样例，不执行脚本、不连接厂商，不更新设备或报警。</p><p>模板声明不等于硬件能力验证。测试通过或版本发布不代表真实厂商已接通；设备须明确绑定一个具体的已发布版本，新版本不会自动替换旧绑定。</p><p>厂商与型号是产品稳定身份，创建后不可修改；名称与描述可编辑并受 revision 冲突保护。归档会阻止发布新版本和新的设备绑定，但既有已发布版本、历史与存量解析继续可用；不会删除任何历史。</p></PageHelp></PageHeading><Card className="list-card"><CardContent><IdentitySearch placeholder="按产品名称、厂商或型号查询（字面匹配）" aria-label="搜索产品" onSearch={value => { setPage(1); setKeyword(value) }}><div className="field"><Label htmlFor="product-state-filter">产品状态</Label><select id="product-state-filter" className="native-select" value={stateFilter} onChange={event => { setStateFilter(event.target.value as ProductStateFilter); setPage(1) }}><option value="ACTIVE">启用中</option><option value="ARCHIVED">已归档</option><option value="ALL">全部</option></select></div></IdentitySearch>{products.loading ? <Loading/> : products.error != null ? <ErrorNotice error={products.error} retry={products.refresh}/> : products.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>产品</TableHead><TableHead>厂商 / 型号（稳定标识）</TableHead><TableHead>描述</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader><TableBody>{products.data.items.map(item => {
    const state = productState(item)
    return <TableRow key={item.id}><TableCell><Boxes size={16} className="inline mr-2"/>{item.name}{state === 'ARCHIVED' && <Badge variant="outline" className="status status-neutral">已归档</Badge>}<div className="table-sub mono">{item.id}</div></TableCell><TableCell>{item.manufacturer} / {item.model}</TableCell><TableCell className="whitespace-normal">{item.description || '未填写描述'}</TableCell><TableCell className="text-right">
      <Button variant="ghost" size="sm" onClick={() => setSelected(item)}>查看版本</Button>
      {canWrite && <Button variant="ghost" size="sm" onClick={() => setEditing(item)}><Pencil/>编辑</Button>}
      {canWrite && (state === 'ARCHIVED'
        ? <Button variant="ghost" size="sm" onClick={() => setLifecycle({ product: item, action: 'reactivate' })}><ArchiveRestore/>启用</Button>
        : <Button variant="ghost" size="sm" onClick={() => setLifecycle({ product: item, action: 'archive' })}><Archive/>归档</Button>)}
    </TableCell></TableRow>
  })}</TableBody></Table><Pager page={products.data.page} size={products.data.size} total={products.data.total} onChange={setPage}/></> : <Empty title={stateFilter === 'ARCHIVED' ? '暂无已归档产品' : '暂无产品模板'}>请创建真实产品记录；不会预置或冒充真实厂商协议。</Empty>}</CardContent></Card>{selected && <ProductVersions key={selected.id} product={selected} archived={productState(selected) === 'ARCHIVED'} onReactivate={() => setLifecycle({ product: selected, action: 'reactivate' })}/>}
  <Sheet open={creating} onOpenChange={setCreating}><SheetContent className="review-sheet sm:max-w-xl">{creating && <ProductCreate onClose={() => setCreating(false)} onSaved={() => { setCreating(false); products.refresh() }}/>}</SheetContent></Sheet>
  <Sheet open={editing !== null} onOpenChange={open => { if (!open) setEditing(null) }}><SheetContent className="review-sheet sm:max-w-xl">{editing && <ProductMetadataEditor product={editing} onClose={() => setEditing(null)} onSaved={applyProduct} onRefresh={() => { setEditing(null); products.refresh() }}/>}</SheetContent></Sheet>
  <Sheet open={lifecycle !== null} onOpenChange={open => { if (!open) setLifecycle(null) }}><SheetContent className="review-sheet sm:max-w-xl">{lifecycle && <ProductLifecycleEditor product={lifecycle.product} action={lifecycle.action} onClose={() => setLifecycle(null)} onSaved={applyProduct} onRefresh={() => { setLifecycle(null); products.refresh() }}/>}</SheetContent></Sheet>
  </>
}
function ProductCreate({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(''), [manufacturer, setManufacturer] = useState(''), [model, setModel] = useState(''), [description, setDescription] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (![name, manufacturer, model].every(item => item.trim())) { action.setError(new Error('请填写产品名称、厂商和型号')); return }
    void action.run((key, signal) => api.createProduct({ name: name.trim(), manufacturer: manufacturer.trim(), model: model.trim(), description: description.trim() }, key, signal), onSaved)
  }
  return <><SheetHeader className="review-header"><SheetTitle>创建产品</SheetTitle><SheetDescription>厂商与型号是稳定身份标识，创建后不可修改；名称与描述可在创建后编辑。归档不会删除已发布版本或历史。</SheetDescription></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}>{[{ id: 'product-name', label: '产品名称', value: name, change: setName }, { id: 'manufacturer', label: '厂商标识', value: manufacturer, change: setManufacturer }, { id: 'product-model', label: '型号标识', value: model, change: setModel }].map(field => <div className="field" key={field.id}><Label htmlFor={field.id}>{field.label}</Label><Input id={field.id} required value={field.value} onChange={e => { field.change(e.target.value); action.touch() }}/></div>)}<div className="field"><Label htmlFor="product-description">产品描述</Label><textarea id="product-description" rows={3} value={description} onChange={e => { setDescription(e.target.value); action.touch() }}/></div>{action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit">创建产品</Button></form></div></>
}
function ProductVersions({ product, archived, onReactivate }: { product: Product; archived: boolean; onReactivate: () => void }) {
  const permissions = useSession(s => s.permissions)
  const versions = useResource(`versions:${product.id}`, signal => api.versions(product.id, signal))
  const [selectedId, setSelectedId] = useState('')
  const [editor, setEditor] = useState<VersionEditorState | null>(null)
  const [lastTest, setLastTest] = useState<ParserTestResult | null>(null)
  const [notice, setNotice] = useState('')
  const writeSnapshot = useSyncExternalStore(subscribeProductWrite, getProductWriteSnapshot, getProductWriteSnapshot)
  const [refreshRequested, setRefreshRequested] = useState(false), [conflictRefreshed, setConflictRefreshed] = useState(false)
  const selected = versions.data?.items.find(item => item.id === selectedId)
  const close = () => setEditor(null)
  function saved(result?: ParserTestResult) { close(); setLastTest(result ?? null); setNotice(result ? `测试已保存，服务端当前 revision ${result.revision}；列表刷新后再操作，不能沿用旧版本号。` : '版本操作已完成；新版本不会自动影响设备，已有发布版本与原测试证据不可修改。'); versions.refresh() }
  useEffect(() => {
    if (writeSnapshot.completion?.productId === product.id) {
      const result = consumeProductWriteCompletion(product.id)
      saved(result && 'results' in result ? result : undefined)
    }
  }, [writeSnapshot.completion, product.id])
  useEffect(() => { if (refreshRequested && !versions.loading && versions.data) { setConflictRefreshed(true); setRefreshRequested(false) } }, [refreshRequested, versions.loading, versions.data, versions.loadedAt])
  const writeIntent = writeSnapshot.intent
  return <Card className="product-versions">
    <CardHeader className="section-header"><div><CardTitle>{product.name} · 版本</CardTitle><p>编号由服务端分配，不可自行填写；已发布版本不可变。厂商与型号是稳定身份标识，不可修改。</p></div>{permissions.includes('products:write') && <Button variant="outline" disabled={!!writeIntent || archived} onClick={() => setEditor({ mode: 'new', version: null })}><Plus/>创建空白草稿</Button>}</CardHeader>
    <CardContent>{archived && <div role="status" className="notice">产品已归档：不能发布版本、创建/复制新草稿或新增设备绑定。已有草稿仍可查看、编辑和运行合成样例测试；已发布版本、历史与存量解析继续可用。<Button type="button" variant="outline" size="sm" onClick={onReactivate}><ArchiveRestore/>启用产品</Button></div>}
      {notice && <div role="status" className="notice">{notice}</div>}
      {writeIntent && <ProductWriteRecovery intent={writeIntent} productId={product.id} releaseAllowed={conflictRefreshed && writeIntent.state === 'CONFLICT' && (() => { const command = writeIntent.command; if (command.mode === 'new' || command.mode === 'clone') return true; const item = versions.data?.items.find(version => version.id === command.version_id); return !!item && item.revision !== (command.mode === 'publish' ? command.expected_revision : command.body.expected_revision) })()} onRefresh={() => { setConflictRefreshed(false); setRefreshRequested(true); versions.refresh() }} onRelease={() => { if (releaseProductConflict(product.id)) { close(); setConflictRefreshed(false) } }}/>}
      {versions.loading ? <Loading/> : versions.error != null ? <ErrorNotice error={versions.error} retry={versions.refresh}/> : versions.data?.items.length ? <div className="version-layout">
        <div className="version-list">{versions.data.items.map(version => <button key={version.id} className={version.id === selectedId ? 'version-choice selected' : 'version-choice'} onClick={() => { setSelectedId(version.id); setLastTest(null) }}><strong>v{version.version_number}<Badge variant="outline">{version.status === 'PUBLISHED' ? '已发布 · 不可变' : '草稿'}</Badge></strong><span className="mono">{version.id} · revision {version.revision}</span><CapabilityBadges capabilities={version.capabilities}/><small>{version.parser_tested ? '当前模板有完整可发布样例证据' : '未有完整可发布证据'}</small></button>)}</div>
        <div className="version-detail">{selected ? <>
          <div className="identity-readonly">版本 v{selected.version_number} · revision {selected.revision}<br/>创建：{dateTime(selected.created_at)} · 发布：{dateTime(selected.published_at)}</div>
          <div className="version-actions">{permissions.includes('products:write') && <><Button variant="outline" disabled={archived} onClick={() => setEditor({ mode: 'clone', version: selected })}><Copy/>复制成新草稿</Button><Button variant="outline" disabled={selected.status !== 'DRAFT'} onClick={() => setEditor({ mode: 'edit', version: selected })}><FileJson/>编辑草稿</Button><Button variant="outline" disabled={selected.status !== 'DRAFT'} onClick={() => setEditor({ mode: 'test', version: selected })}><Play/>运行合成样例测试</Button></>}{permissions.includes('products:publish') && <Button disabled={archived || selected.status !== 'DRAFT' || !selected.parser_tested || selected.parser_test_summary?.passed !== true || selected.parser_test_summary.tested_revision !== selected.revision} onClick={() => setEditor({ mode: 'publish', version: selected })}><Rocket/>发布此版本</Button>}</div>
          <CapabilityBadges capabilities={selected.capabilities}/>
          <ProductServiceDefinitions version={selected} onChanged={() => versions.refresh()}/>
          <section aria-label="电量低阈值版本配置"><h3>电量低阈值 · 版本配置{selected.status === 'PUBLISHED' ? '（只读）' : ''}</h3>{selected.battery_low_threshold ? <p>{selected.battery_low_threshold.threshold_json}{selected.battery_low_threshold.comparator} · {selected.battery_low_threshold.parameter_key} · {selected.battery_low_threshold.metric_key} · {selected.battery_low_threshold.unit}</p> : <p>未配置（NOT_CONFIGURED）；无数值默认。</p>}</section>
          <h3 className="mt-5 mb-2">声明式 Schema</h3><SchemaSummary schemaText={selected.schema_json ?? JSON.stringify(selected.schema, null, 2)}/>
          {selected.parser_test_summary ? <><div className="muted-note mt-4">存档测试证据 · 测试 revision {selected.parser_test_summary.tested_revision} · 指纹 {selected.parser_test_summary.schema_fingerprint ?? '未提供'}；仅对应当时schema/能力。</div><ParserResults passed={selected.parser_test_summary.passed} results={selected.parser_test_summary.results}/></> : <div className="notice">当前草稿没有测试证据。保存编辑会清除旧证据，不能用旧通过结果发布。</div>}
        </> : <Empty title="请选择一个具体版本"/>}</div></div> : <Empty title="该产品暂无版本"/>}
      {lastTest && <><h3 className="mt-5">最近测试操作回执 · revision {lastTest.revision}</h3><ParserResults passed={lastTest.passed} results={lastTest.results}/></>}
    </CardContent>
    <Sheet open={!!editor} onOpenChange={open => { if (!open && !writeSnapshot.intent) close(); else if (!open) setNotice('产品版本操作尚未确认，不能关闭并丢弃；请返回操作所属产品恢复原请求。') }}><SheetContent className="review-sheet sm:max-w-xl">{editor && <VersionEditor key={`${editor.mode}:${editor.version?.id ?? 'new'}:${editor.version?.revision ?? ''}`} product={product} archived={archived} editor={editor} onRefresh={() => { close(); versions.refresh() }}/>}</SheetContent></Sheet>
  </Card>
}
function VersionEditor({ product, archived, editor, onRefresh }: { product: Product; archived: boolean; editor: VersionEditorState; onRefresh: () => void }) {
  const version = editor.version
  const [schema, setSchema] = useState(version?.schema_json ?? JSON.stringify(version?.schema ?? {}, null, 2))
  const [capabilities, setCapabilities] = useState<Capabilities>(version ? { ...pendingCapabilities(), ...productCapabilities(version.capabilities) } : pendingCapabilities())
  const [batteryThreshold, setBatteryThreshold] = useState(version?.battery_low_threshold?.threshold_json ?? '')
  const [batteryComparator, setBatteryComparator] = useState<'LT' | 'LTE' | ''>(version?.battery_low_threshold?.comparator ?? '')
  const [samples, setSamples] = useState('[]')
  const [syntheticConfirmed, setSyntheticConfirmed] = useState(false)
  const schemaDraft = useMemo(() => parseSchemaDraft(schema), [schema])
  const action = useMutationAction()
  const writeSnapshot = useSyncExternalStore(subscribeProductWrite, getProductWriteSnapshot, getProductWriteSnapshot)
  const unresolved = writeSnapshot.intent?.productId === product.id
  const titles: Record<VersionMode, string> = { new: '创建空白草稿', clone: '复制成新草稿', edit: '编辑草稿 JSON', test: '运行合成样例测试', publish: '发布此版本' }
  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (editor.mode === 'edit') {
        schemaDocument(schema)
        if (schemaDraft !== null) validateSchemaDraft(schemaDraft, capabilities)
        if (batteryThreshold !== '' && batteryComparator === '') throw new Error('请明确选择LT或LTE比较方式')
        if (batteryThreshold !== '' && capabilities.battery !== 'SUPPORTED') throw new Error('配置版本阈值前必须将电量能力声明为SUPPORTED')
      }
      if (editor.mode === 'test') {
        sampleDocument(samples)
        const drafts = parseSampleDrafts(samples)
        if (drafts === null) throw new Error('合成样例结构未确认')
        if (schemaDraft !== null) validateSampleDrafts(drafts, schemaDraft.measurements)
        if (!syntheticConfirmed) throw new Error('请确认仅提交合成、无真实凭据的样例')
      }
      if (editor.mode === 'publish' && (archived || !version?.parser_tested || version.parser_test_summary?.passed !== true || version.parser_test_summary.tested_revision !== version.revision || version.status !== 'DRAFT')) throw new Error(archived ? '产品已归档，不能发布版本；请先启用产品' : '当前版本没有完整有效发布证据，不能发布')
    } catch (error) { action.setError(error); return }
    if (editor.mode === 'publish' && !action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    let command: ProductWriteCommand
    if (editor.mode === 'new') command = { mode: 'new', product_id: product.id, base_version_id: null }
    else if (editor.mode === 'clone') command = { mode: 'clone', product_id: product.id, base_version_id: version!.id }
    else if (editor.mode === 'edit') command = { mode: 'edit', version_id: version!.id, body: { expected_revision: version!.revision, schema_json: schema, capabilities, battery_low_threshold: batteryThreshold === '' ? null : { parameter_key: 'battery_low_threshold_percent', metric_key: 'battery_percent', unit: '%', comparator: batteryComparator as 'LT' | 'LTE', threshold: Number(batteryThreshold), threshold_json: batteryThreshold } } }
    else if (editor.mode === 'test') command = { mode: 'test', version_id: version!.id, body: { expected_revision: version!.revision, samples_json: samples } }
    else command = { mode: 'publish', version_id: version!.id, expected_revision: version!.revision }
    if (!beginProductWrite(product.id, command)) action.setError(new Error('已有未解决的产品版本操作；请先恢复原请求，不可重新解释或覆盖。'))
    else onRefresh()
  }
  if (unresolved) return <><SheetHeader className="review-header"><SheetTitle>{titles[editor.mode]}</SheetTitle><SheetDescription>原操作仍在等待确认；页面离开不会丢弃请求内容或幂等键。</SheetDescription></SheetHeader><div className="review-body"><div role="status" className="notice">请在所属产品的待确认操作区使用原操作内容和幂等键恢复。</div></div></>
  return <>
    <SheetHeader className="review-header"><div className="eyebrow">PRODUCT VERSION · {product.name}</div><SheetTitle>{titles[editor.mode]}</SheetTitle><SheetDescription>只处理声明式JSON映射与明确合成输入，不执行脚本，不调用真实设备或外部厂商。</SheetDescription></SheetHeader>
    <div className="review-body"><form className="form-stack" onSubmit={submit}>
      {action.confirming && editor.mode === 'publish' ? <OperationConfirmation action={action} title={`二次确认 · ${titles[editor.mode]}`} submitLabel={`确认${titles[editor.mode]}`} refresh={onRefresh} warning="发布后不可逆且不可再编辑/测试；不会影响设备现有绑定，不代表厂商联调通过。"><p>产品：{product.name} · {product.id}</p><p>版本：{version ? `v${version.version_number} · ${version.id} · revision ${version.revision}` : '由服务端分配新序号'}</p><p>操作：{titles[editor.mode]}</p></OperationConfirmation> : <>
        {version && <div className="identity-readonly">{version.id} · v{version.version_number} · revision {version.revision} · {version.status}</div>}
        {archived && <div className="notice" role="status">产品已归档：草稿可编辑/测试，但不能发布；请先启用产品。</div>}
        {editor.mode === 'edit' && <>
          <SchemaEditor draft={schemaDraft} schemaText={schema} capabilities={capabilities} onChangeDraft={draft => { setSchema(serializeSchemaDraft(draft)); action.touch() }} onChangeText={text => { setSchema(text); action.touch() }}/>
          <fieldset className="field"><legend>能力声明（仅模板声明）</legend>{capabilityKeys.map(key => <label key={key} className="capability-select">{capabilityEditorLabels[key]}<select aria-label={capabilityEditorLabels[key]} className="native-select" value={capabilities[key] ?? 'PENDING'} onChange={e => { setCapabilities({ ...capabilities, [key]: e.target.value as CapabilityState }); action.touch() }}>{(['SUPPORTED', 'UNSUPPORTED', 'PENDING'] as const).map(value => <option key={value} value={value}>{value === 'SUPPORTED' ? '声明支持' : value === 'UNSUPPORTED' ? '声明不支持' : '待确认'}</option>)}</select></label>)}</fieldset>
          <div className="notice">保存立即使旧测试通过证据失效；需要显式重新测试全部样例。能力声明、事件映射与读数声明仅表示模板合同，不代表厂商硬件已验证。电量阈值仅用于查询观察，不会告警、通知、下发或修改设备事实。</div>
          <fieldset className="field"><legend>电量低阈值</legend>
            <div className="field"><Label htmlFor="battery-comparator">电量比较方式</Label><select id="battery-comparator" className="native-select" value={batteryComparator} onChange={event => { setBatteryComparator(event.target.value as 'LT' | 'LTE' | ''); action.touch() }}><option value="">请选择LT或LTE比较方式</option><option value="LT">LT · 小于</option><option value="LTE">LTE · 小于或等于</option></select></div>
            <div className="field"><Label htmlFor="battery-threshold">电量低阈值（%）</Label><Input id="battery-threshold" inputMode="decimal" value={batteryThreshold} onChange={event => { setBatteryThreshold(event.target.value); action.touch() }}/></div>
            <Button type="button" variant="outline" disabled={!batteryThreshold} onClick={() => { setBatteryThreshold(''); action.touch() }}>清除电量阈值</Button>
            <p className="muted-note">留空与LT/LTE选择无关，留空会显式清除阈值；配置要求版本电量能力SUPPORTED，十进制精度最多36位。</p>
          </fieldset>
        </>}
        {editor.mode === 'test' && <>
          <SampleEditor text={samples} schema={schemaDraft} capabilities={capabilities} onChange={value => { setSamples(value); action.touch() }}/>
          <label className="confirmation"><input type="checkbox" checked={syntheticConfirmed} onChange={e => { setSyntheticConfirmed(e.target.checked); action.touch() }}/>确认样例全部合成、无真实凭据或个人资料</label>
        </>}
        {editor.mode === 'publish' && <div className="notice">发布不可逆，版本与测试证据随后不可修改。发布只说明当前合成样例映射通过，不表示真实厂商协议或硬件联调完成；不会自动迁移任何设备。产品归档时不能发布。</div>}
        {(editor.mode === 'new' || editor.mode === 'clone') && <div className="notice">{editor.mode === 'new' ? '服务端生成默认可编辑草稿，能力PENDING，无通过证据。' : '复制当前schema、能力与版本电量阈值到新草稿，不复制原发布/测试通过状态。'}序号由服务端分配。</div>}
        {action.error != null && <ErrorNotice error={action.error}/>}<Button type="submit" disabled={action.busy}>{action.busy ? '正在提交…' : editor.mode === 'publish' ? '核对并继续' : editor.mode === 'new' ? '创建空白草稿' : editor.mode === 'clone' ? '复制成新草稿' : editor.mode === 'test' ? '保存测试结果' : '保存草稿'}</Button>
      </>}
    </form></div>
  </>
}
function ProductWriteRecovery({ intent, productId, releaseAllowed, onRefresh, onRelease }: { intent: ProductWriteIntent; productId: string; releaseAllowed: boolean; onRefresh: () => void; onRelease: () => void }) {
  const local = intent.productId === productId
  function retry(event: FormEvent) { event.preventDefault(); if (!retryProductWrite()) return }
  const command = intent.command
  const summary = command.mode === 'new' ? '创建新版本草稿' : command.mode === 'clone' ? `复制版本 ${command.base_version_id}` : command.mode === 'edit' ? `编辑版本 ${command.version_id} · revision ${command.body.expected_revision}` : command.mode === 'test' ? `测试版本 ${command.version_id} · revision ${command.body.expected_revision}` : `发布版本 ${command.version_id} · revision ${command.expected_revision}`
  return <section className="notice" role="status"><strong>{intent.state === 'PENDING' ? '产品版本操作等待服务端回执' : intent.state === 'CONFLICT' ? '产品版本操作发生revision/幂等冲突' : '产品版本操作结果未确认'}</strong><p>{local ? summary : `产品 ${intent.productId} 存在待确认操作`}。页面导航不会丢弃原操作内容或幂等键。</p>{local && intent.state === 'UNKNOWN' && <form onSubmit={retry}><Button type="submit">使用原操作内容和幂等键重试</Button></form>}{local && intent.state === 'CONFLICT' && <><Button type="button" variant="outline" onClick={onRefresh}>刷新产品版本后再处理冲突</Button>{releaseAllowed && <Button type="button" variant="outline" onClick={onRelease}>已刷新并确认冲突，放弃原操作后重新编辑</Button>}</>}{!local && <p>请返回该产品恢复原请求；其他产品写操作已锁定。</p>}</section>
}
