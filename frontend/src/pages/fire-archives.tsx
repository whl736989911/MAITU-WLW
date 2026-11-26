import { useEffect, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { ApiError } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import {
  ARCHIVE_STATUSES, attachmentPayload, downloadAttachment, getArchive, getArchiveHistory,
  getArchives,
  type ArchiveDetail, type ArchiveHistory, type ArchivePage, type ArchiveSummary,
} from '@/lib/fire-archives'
import { useArchiveIntent } from '@/lib/archive-intent'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { DeviceSelector, PlaceSelector, TargetUnitSelect, type AssetReference } from '@/components/asset-picker'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { CustomerRecordPager, CustomerRecordsSearch, CustomerRecordTable } from '@/components/customer-records'
import { dateTime } from '@/lib/format'

const wrap = { minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' as const }
const PAGE = 'fire-archives'
type Query = { page: number; status: string; keyword: string }
type FormMode = 'create' | 'edit' | null

export function FireArchivesPage() {
  const s = useSession()
  const correct = s.audience === 'management' ? s.user?.user_type === 'PLATFORM' : s.user?.user_type === 'CUSTOMER'
  if (!s.token || !correct || s.user?.must_change_password || !s.permissions.includes('fire-archives:read') || String(s.page) !== PAGE)
    return <Empty title="当前无消防档案读取范围"/>
  const frame = `${s.authGeneration}`
  return <Content key={frame} permissions={[...s.permissions]} audience={s.audience} sessionTenant={s.user?.tenant_id ?? ''}
    scopeTenantIds={[...s.scopeTenantIds]} scopePlaceIds={[...s.scopePlaceIds]}/>
}

function Content({ permissions, audience, sessionTenant, scopeTenantIds, scopePlaceIds }: {
  permissions: string[]; audience: 'management' | 'customer'; sessionTenant: string
  scopeTenantIds: string[]; scopePlaceIds: string[]
}) {
  const intent = useArchiveIntent()
  const [query, setQuery] = useState<Query>({ page: 1, status: '', keyword: '' })
  const [keywordDraft, setKeywordDraft] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [mode, setMode] = useState<FormMode>(null)
  const [targetUnit, setTargetUnit] = useState('')
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState('')
  const [number, setNumber] = useState('')
  const [device, setDevice] = useState('')
  const [deviceRef, setDeviceRef] = useState<AssetReference>()
  const [place, setPlace] = useState('')
  const [placeRef, setPlaceRef] = useState<AssetReference>()
  const [issued, setIssued] = useState('')
  const [expires, setExpires] = useState('')
  const [description, setDescription] = useState('')
  const [reason, setReason] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [uploadReason, setUploadReason] = useState('')
  const [showHistory, setShowHistory] = useState(false)
  const [confirmArchive, setConfirmArchive] = useState(false)
  const [localError, setLocalError] = useState<unknown>()
  const [historyRevision, setHistoryRevision] = useState(0)
  useEffect(() => { setDevice(''); setDeviceRef(undefined); setPlace(''); setPlaceRef(undefined) }, [audience])

  const canWrite = permissions.includes('fire-archives:write')
  const transferFilters: Record<string, string | number | boolean | null> = {
    keyword: query.keyword,
    ...(query.status ? { status: query.status } : {}),
    ...(audience === 'management' ? { unit_ids: scopeTenantIds.join(','), place_ids: scopePlaceIds.join(',') } : {}),
  }
  const scopeKey = audience === 'management' ? `${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}` : sessionTenant
  const list = useResource<ArchivePage>(`archive-list:${scopeKey}:${query.page}:${query.status}:${query.keyword}`,
    signal => getArchives(query, signal), true)
  const detail = useResource<ArchiveDetail>(`archive-detail:${scopeKey}:${selected ?? ''}`,
    signal => getArchive(selected!, signal), !!selected)
  const history = useResource<ArchiveHistory[]>(`archive-history:${scopeKey}:${selected ?? ''}:${historyRevision}`,
    signal => getArchiveHistory(selected!, signal), !!selected && showHistory)

  const archive = detail.data?.archive
  const busy = intent.phase === 'PENDING'
  const completion = intent.completion
  const hasIntent = !!intent.intent && intent.phase !== 'EDITING'
  const frozenBanner = hasIntent && !completion && (intent.phase !== 'CONFIRMING' || (!mode && !confirmArchive))
  const freezeNavigation = !!intent.intent || !!completion
  const formLocked = busy || !!completion || (!!intent.intent && intent.phase !== 'CONFIRMING')
  const unit = mode === 'create' ? (audience === 'management' ? targetUnit : sessionTenant) : archive?.tenant_id

  function resetWrite() {
    setReason(''); setUploadReason(''); setFile(null)
    setLocalError(undefined); setConfirmArchive(false)
  }
  function refreshAll() {
    setMode(null); resetWrite(); setShowHistory(false); setLocalError(undefined)
    setTargetUnit(''); setDevice(''); setDeviceRef(undefined); setPlace(''); setPlaceRef(undefined)
    intent.dismissCompletion(); detail.refresh(); list.refresh(); setHistoryRevision(value => value + 1)
  }
  function choose(id: string | null) {
    if (freezeNavigation) return
    setSelected(id); setMode(null); resetWrite(); setShowHistory(false)
  }
  function fill(summary: ArchiveSummary) {
    setTitle(summary.title); setCategory(summary.category); setNumber(summary.archive_number ?? '')
    setDevice(summary.device_id ?? ''); setDeviceRef(summary.device_id ? { id: summary.device_id, name: summary.device_name, tenantId: summary.tenant_id } : undefined)
    setPlace(summary.place_id ?? ''); setPlaceRef(summary.place_id ? { id: summary.place_id, name: summary.place_name, tenantId: summary.tenant_id } : undefined)
    setIssued(summary.issued_on ?? ''); setExpires(summary.expires_on ?? ''); setDescription(summary.description ?? '')
  }
  function beginCreate() {
    if (!canWrite || freezeNavigation) return
    resetWrite(); setMode('create'); setTitle(''); setCategory(''); setNumber(''); setDevice(''); setDeviceRef(undefined)
    setPlace(''); setPlaceRef(undefined); setIssued(''); setExpires(''); setDescription('')
  }
  function beginEdit() {
    if (!archive || freezeNavigation) return
    resetWrite(); setMode('edit'); fill(archive)
  }
  function retry() {
    void intent.retry()
  }
  function submitMetadata(event: FormEvent) {
    event.preventDefault()
    if (formLocked || !mode) return
    if (!title.trim() || !category.trim() || !reason.trim()) { setLocalError(new ApiError(400, 'INPUT_REQUIRED', '标题、分类与原因均为必填。')); return }
    if (!unit) { setLocalError(new ApiError(400, 'TARGET_UNIT_REQUIRED', '请明确选择本次写入的已授权单位。')); return }
    if (deviceRef?.tenantId && deviceRef.tenantId !== unit) { setLocalError(new ApiError(400, 'ASSET_UNIT_MISMATCH', '所选设备不属于当前单位，请重新选择。')); return }
    if (placeRef?.tenantId && placeRef.tenantId !== unit) { setLocalError(new ApiError(400, 'PLACE_UNIT_MISMATCH', '所选位置不属于当前单位，请重新选择。')); return }
    const body: Record<string, unknown> = { title, category, archive_number: number.trim() || null, device_id: device.trim() || null, place_id: place.trim() || null,
      issued_on: issued || null, expires_on: expires || null, description: description.trim() || null, reason }
    const editing = mode === 'edit' ? archive : undefined
    const request = { path: editing ? `/fire-archives/${encodeURIComponent(editing.id)}` : '/fire-archives', method: (editing ? 'PUT' : 'POST') as 'PUT' | 'POST',
      tenant: audience === 'management' ? unit : undefined,
      body: editing ? { ...body, expected_revision: editing.revision } : body,
      display: `${editing ? '更新' : '新建'}档案 · ${title.trim()} · 单位 ${audience === 'management' ? unit : sessionTenant}` }
    setLocalError(undefined)
    void intent.perform(request)
  }
  function submitArchive() {
    if (!archive || formLocked) return
    if (!reason.trim()) { setLocalError(new ApiError(400, 'REASON_REQUIRED', '归档原因必填。')); return }
    const body: Record<string, unknown> = { expected_revision: archive.revision, reason }
    const request = { path: `/fire-archives/${encodeURIComponent(archive.id)}/archive`, method: 'POST' as const,
      tenant: audience === 'management' ? archive.tenant_id : undefined,
      body,
      display: `归档档案 · ${archive.title} · revision ${archive.revision}` }
    setLocalError(undefined)
    void intent.perform(request)
  }
  function submitAttachment(event: FormEvent) {
    event.preventDefault()
    if (!archive || !file || formLocked) return
    if (archive.status !== 'ACTIVE') { setLocalError(new ApiError(409, 'ARCHIVE_STATE', '已归档档案只读，不能新增附件。')); return }
    if (!uploadReason.trim()) { setLocalError(new ApiError(400, 'REASON_REQUIRED', '上传原因必填。')); return }
    const selectedFile = file, reasonText = uploadReason
    setLocalError(undefined)
    void attachmentPayload(selectedFile).then(payload => {
      const body: Record<string, unknown> = { ...payload, expected_revision: archive.revision, reason: reasonText }
      const request = { path: `/fire-archives/${encodeURIComponent(archive.id)}/attachments`, method: 'POST' as const,
        tenant: audience === 'management' ? archive.tenant_id : undefined,
        body,
        display: `上传附件 · ${payload.filename} · ${selectedFile.size} 字节 · 档案 ${archive.id}` }
      return intent.perform(request)
    }, failure => setLocalError(failure))
  }
  function download(item: ArchiveDetail, attachment: ArchiveDetail['attachments'][number]) {
    void downloadAttachment(item.archive.id, attachment, audience === 'management' ? item.archive.tenant_id : undefined,
      new AbortController().signal).catch(failure => setLocalError(failure))
  }

  return <section aria-label="消防档案" className={audience === 'customer' ? 'legacy-record-page' : 'device-workbench'} style={wrap}>
    {audience === 'management' && <PageHeading eyebrow="FIRE ARCHIVE · 单位消防资料" title="消防档案">
      <PageHelp title="消防档案边界">分类是单位自行填写的文本，不代表法规、合规或消防验收结论。更新只改本地元数据，不改变设备位置、火警或源状态。附件经过服务端加密后保存，下载需当前授权；导出只包含元数据，不含附件内容。</PageHelp>
    </PageHeading>}
    {completion && <section aria-label="档案提交回执" className="notice" style={wrap}>
      <h2>提交已确认（快照）</h2>
      <p>{completion.result.archive.id} · {completion.result.archive.status} · revision {completion.result.archive.revision} · 附件 {completion.result.attachments.length}</p>
      <p style={{ whiteSpace: 'pre-wrap' }}>{completion.result.archive.title}</p>
      <p>这是提交返回的快照，不代表当前事实。请显式刷新读取当前元数据、历史和附件清单。</p>
      <div className="version-actions"><Button variant="outline" onClick={refreshAll}>刷新读取当前事实</Button><Button variant="ghost" onClick={() => intent.dismissCompletion()}>关闭回执（不刷新）</Button></div>
    </section>}
    {frozenBanner && intent.intent && <section aria-label="冻结的档案意图" className="notice" style={wrap} role="alert">
      <h2>{intent.phase === 'PENDING' ? '正在提交，结果未知' : intent.phase === 'UNCERTAIN' ? '结果未确认（不是失败）' : intent.phase === 'CONFLICT' ? '提交冲突' : '上次提交未成功'}</h2>
      <p style={{ whiteSpace: 'pre-wrap' }}>{intent.intent.display}</p>
      <p>冻结的请求参数与幂等键仅保留在当前页面会话内存中；密码不保存。离开页面再返回仍会看到本回执；浏览器刷新会丢失。请先刷新核对当前事实，禁止另建新意图。</p>
      {intent.error != null && <ErrorNotice error={intent.error}/>}
      {intent.phase === 'UNCERTAIN' && <div className="form-stack">
        <div className="version-actions"><Button variant="outline"  onClick={retry}>沿原操作确认结果</Button><Button variant="outline" onClick={refreshAll}>刷新核对</Button></div></div>}
      {intent.phase === 'CONFIRMING' && <div className="form-stack">
        <div className="version-actions"><Button variant="outline"  onClick={retry}>沿原请求重试</Button><Button variant="outline" onClick={() => { void intent.edit(); setLocalError(undefined) }}>放弃旧请求并重新填写</Button><Button variant="outline" onClick={refreshAll}>刷新核对</Button></div></div>}
      {intent.phase === 'CONFLICT' && <div className="version-actions"><Button variant="outline" onClick={() => { refreshAll(); void intent.reload() }}>刷新并放弃旧意图</Button></div>}
      {intent.phase === 'PENDING' && <p className="muted-note">正在等待服务端结果；不要关闭浏览器或创建另一意图。</p>}
    </section>}
    {audience === 'management' && scopeTenantIds.length === 0 && scopePlaceIds.length === 0 && <p className="muted-note">当前未选择单位/位置范围，档案列表为空；请从左侧范围树选择后再查询。</p>}
    {localError != null && <ErrorNotice error={localError}/>}
    {mode && canWrite && !frozenBanner && !completion && <section aria-label="档案表单" style={{ ...wrap, border: '1px solid #c6d5bb', borderRadius: 12, padding: 16 }}>
      <h2>{mode === 'create' ? '新建档案' : '编辑档案（对象所属单位）'}</h2>
      <form className="form-stack" onSubmit={submitMetadata}>
        {mode === 'create' && <TargetUnitSelect audience={audience} permissions={permissions} value={targetUnit}
          onChange={value => { setTargetUnit(value); setDevice(''); setDeviceRef(undefined); setPlace(''); setPlaceRef(undefined) }} disabled={busy}/>}
        {mode === 'create' && <p className="muted-note">目标单位只影响本次写入；不会改变左侧单位/位置读取范围，也不会增加权限。</p>}
        {mode === 'edit' && <p className="muted-note">对象所属单位：{archive?.tenant_id ?? unit}</p>}
        <div className="field"><Label htmlFor="archive-title">标题</Label><Input id="archive-title" value={title} maxLength={200} onChange={event => setTitle(event.target.value)} required/></div>
        <div className="field"><Label htmlFor="archive-category">分类（单位自填文本）</Label><Input id="archive-category" value={category} maxLength={120} onChange={event => setCategory(event.target.value)} required/></div>
        <div className="field"><Label htmlFor="archive-number">档案编号（可空）</Label><Input id="archive-number" value={number} maxLength={120} onChange={event => setNumber(event.target.value)}/></div>
        <DeviceSelector audience={audience} tenantId={audience === 'management' ? unit || undefined : undefined} value={device} current={deviceRef}
          onChange={(id, reference) => { setDevice(id); setDeviceRef(id ? reference : undefined) }} canReadDevices={permissions.includes('device:read')}
          canReadPlaces={permissions.includes('units:read')} disabled={busy}/>
        <PlaceSelector audience={audience} tenantId={audience === 'management' ? unit || undefined : undefined} value={place} current={placeRef}
          onChange={(id, reference) => { setPlace(id); setPlaceRef(id ? reference : undefined) }}
          canReadPlaces={permissions.includes('units:read')} disabled={busy}/>
        <div className="field"><Label htmlFor="archive-issued">签发日期（可空）</Label><Input id="archive-issued" type="date" value={issued} onChange={event => setIssued(event.target.value)}/></div>
        <div className="field"><Label htmlFor="archive-expires">有效期至（可空）</Label><Input id="archive-expires" type="date" value={expires} onChange={event => setExpires(event.target.value)}/></div>
        <div className="field"><Label htmlFor="archive-description">说明（可空）</Label><textarea id="archive-description" className="native-select" rows={3} maxLength={4000} value={description} onChange={event => setDescription(event.target.value)}/></div>
        <div className="field"><Label htmlFor="archive-reason">变更原因</Label><Input id="archive-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
        
        {intent.error != null && intent.phase === 'CONFIRMING' && <ErrorNotice error={intent.error}/>}
        <div className="version-actions"><Button type="submit" disabled={formLocked || !title.trim() || !category.trim() || !reason.trim() || audience === 'management' && !unit}>{busy ? '正在提交…' : mode === 'create' ? '确认新建' : '确认保存'}</Button><Button variant="outline" onClick={() => { setMode(null); resetWrite(); void intent.edit() }} disabled={busy}>关闭</Button></div>
      </form>
    </section>}
    {audience === 'customer' ? <>
      <CustomerRecordsSearch field="档案名称 / 编号" keyword={keywordDraft} onKeyword={setKeywordDraft}
        onSearch={event => { event.preventDefault(); if (!freezeNavigation) { choose(null); setQuery(current => ({ ...current, page: 1, keyword: keywordDraft.trim() })) } }}
        onReset={() => { if (!freezeNavigation) { choose(null); setKeywordDraft(''); setQuery({ page: 1, status: '', keyword: '' }) } }}
        actions={<>
          {canWrite && <button type="button" className="legacy-device-button" disabled={mode === 'create' || freezeNavigation} onClick={beginCreate}>添加档案</button>}
          {!freezeNavigation && <DataTransferActions kind="FIRE_ARCHIVES" filters={transferFilters}/>}
        </>}>
        <select aria-label="档案状态" value={query.status} disabled={freezeNavigation} onChange={event => { choose(null); setQuery(current => ({ ...current, page: 1, status: event.target.value })) }}>
          <option value="">全部状态</option>{ARCHIVE_STATUSES.map(value => <option key={value} value={value}>{value === 'ACTIVE' ? '有效' : '已归档'}</option>)}
        </select>
      </CustomerRecordsSearch>
      <CustomerRecordTable name="消防档案" columns={['档案名称', '档案编号', '分类', '所属位置', '更新时间', '操作']}
        loading={list.loading} retry={list.refresh} error={list.error} rows={(list.data?.items ?? []).map(item => ({
          key: item.id, cells: [item.title, item.archive_number, item.category, item.place_name, dateTime(item.updated_at),
            <button type="button" className="legacy-device-link" disabled={freezeNavigation} onClick={() => choose(item.id)}>详情</button>],
        }))}/>
      {list.data && <CustomerRecordPager page={list.data.page} size={list.data.size} total={list.data.total} onPage={page => { if (!freezeNavigation) { choose(null); setQuery(current => ({ ...current, page })) } }}/>}
    </> : <section aria-label="档案列表" className="management-list-panel management-record-list">
      <div className="management-list-bar">
        <h2>档案列表</h2>
        <div className="list-toolbar management-list-actions">
          {!freezeNavigation && <DataTransferActions kind="FIRE_ARCHIVES" tenantId={targetUnit || undefined} filters={transferFilters}/>}
          <Button variant="outline" onClick={refreshAll}>刷新档案</Button>
          {canWrite && <Button onClick={beginCreate} disabled={mode === 'create' || freezeNavigation}>新建档案</Button>}
        </div>
      </div>
      <form className="filter-row management-search-filters" onSubmit={event => { event.preventDefault(); if (freezeNavigation) return; choose(null); setQuery(current => ({ ...current, page: 1, keyword: keywordDraft.trim() })) }}>
        <Input aria-label="档案关键字" value={keywordDraft} maxLength={100} onChange={event => setKeywordDraft(event.target.value)} placeholder="标题 / 分类 / 编号 / 说明 / 设备名"/>
        <select aria-label="档案状态" className="native-select" value={query.status} onChange={event => { if (freezeNavigation) return; choose(null); setQuery(current => ({ ...current, page: 1, status: event.target.value })) }}>
          <option value="">全部状态</option>{ARCHIVE_STATUSES.map(value => <option key={value} value={value}>{value}</option>)}
        </select>
        <Button type="submit" variant="outline" disabled={freezeNavigation}>查询档案</Button>
      </form>
      {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : list.data ? <>
        <p className="management-list-context">已应用状态：{query.status || '全部'} · 关键字：{query.keyword || '无'} · 本次每页 {list.data.size} 条（服务端实际值）</p>
        {list.data.items.length === 0 ? <Empty title="本筛选无消防档案"/> : <Table className="management-record-table" aria-label="消防档案记录">
          <TableHeader><TableRow><TableHead>档案 / 编号</TableHead><TableHead>分类 / 所属位置</TableHead><TableHead>状态</TableHead><TableHead>附件</TableHead><TableHead>更新时间 / 版本</TableHead><TableHead className="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>{list.data.items.map(item => <TableRow key={item.id} data-fire-archive-id={item.id}>
            <TableCell><strong>{item.title}</strong><div className="table-sub">{item.archive_number ?? '未填写编号'}</div></TableCell>
            <TableCell>{item.category}<div className="table-sub">{item.place_name ?? '未关联位置'} · 单位 {item.tenant_id}</div></TableCell>
            <TableCell>{item.status === 'ACTIVE' ? '有效' : '已归档'}</TableCell>
            <TableCell>{item.attachment_count}</TableCell>
            <TableCell>{dateTime(item.updated_at)}<div className="table-sub">版本 {item.revision}</div></TableCell>
            <TableCell className="text-right"><Button variant="outline" size="sm" onClick={() => choose(item.id)} disabled={freezeNavigation}>打开档案</Button></TableCell>
          </TableRow>)}</TableBody>
        </Table>}
        <Pager page={list.data.page} size={list.data.size} total={list.data.total} onChange={page => { if (freezeNavigation) return; choose(null); setQuery(current => ({ ...current, page })) }}/>
      </> : null}
    </section>}
    {selected && <section aria-label="档案详情" className={audience === 'management' ? 'management-record-detail-panel' : undefined} data-selected-fire-archive-id={selected} style={wrap}>
      <h2>档案详情</h2>
      {detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : detail.data && archive ? <>
        <dl className="detail-list" style={wrap}>
          <div><dt>档案ID</dt><dd>{archive.id}</dd></div>
          <div><dt>状态</dt><dd>{archive.status} · revision {archive.revision}</dd></div>
          <div><dt>单位</dt><dd>{archive.tenant_id}</dd></div>
          <div><dt>标题 / 分类 / 编号</dt><dd>{archive.title} / {archive.category} / {archive.archive_number ?? '未填写'}</dd></div>
          <div><dt>关联设备</dt><dd>{archive.device_name ?? '无'}（{archive.device_id ?? '无'}）</dd></div>
          <div><dt>关联位置</dt><dd>{archive.place_name ?? '无'}（{archive.place_id ?? '无'}）</dd></div>
          <div><dt>签发 / 有效期</dt><dd>{archive.issued_on ?? '未填写'} / {archive.expires_on ?? '未填写'}</dd></div>
          <div><dt>说明</dt><dd style={{ whiteSpace: 'pre-wrap' }}>{archive.description ?? '空'}</dd></div>
          <div><dt>归档</dt><dd>{archive.archived_at ?? '未归档'} · {archive.archived_by_name ?? archive.archived_by ?? '无'} · {archive.archived_reason ?? '无'}</dd></div>
        </dl>
        <div className="version-actions" style={{ flexWrap: 'wrap' }}>
          {canWrite && archive.status === 'ACTIVE' && !mode && <Button variant="outline" onClick={beginEdit} disabled={freezeNavigation}>编辑元数据</Button>}
          {canWrite && archive.status === 'ACTIVE' && !mode && <Button variant="outline" onClick={() => { resetWrite(); setConfirmArchive(value => !value) }} disabled={busy || freezeNavigation}>归档（软归档）</Button>}
          <Button variant="outline" onClick={() => setShowHistory(value => !value)}> {showHistory ? '收起历史' : '查看历史'}</Button>
          <Button variant="outline" onClick={refreshAll}>刷新档案</Button>
          <Button variant="outline" onClick={() => choose(null)} disabled={freezeNavigation}>关闭详情</Button>
        </div>
        {confirmArchive && canWrite && archive.status === 'ACTIVE' && !mode && !frozenBanner && !completion && <form className="form-stack" onSubmit={event => { event.preventDefault(); submitArchive() }} style={{ marginTop: 12 }}>
          <h3>确认软归档</h3>
          <p className="muted-note">归档后档案与附件只读；历史保留。此操作不删除数据。</p>
          <div className="field"><Label htmlFor="archive-confirm-reason">归档原因</Label><Input id="archive-confirm-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
          
          {intent.error != null && intent.phase === 'CONFIRMING' && <ErrorNotice error={intent.error}/>}
          <div className="version-actions"><Button type="submit" disabled={formLocked || !reason.trim()}>{busy ? '正在归档…' : '确认归档'}</Button><Button variant="outline" onClick={() => { setConfirmArchive(false); setReason(''); setLocalError(undefined); void intent.edit() }} disabled={busy}>取消</Button></div>
        </form>}
        {!confirmArchive && canWrite && archive.status === 'ACTIVE' && !mode && !frozenBanner && !completion && <form className="form-stack" onSubmit={submitAttachment} style={{ marginTop: 12 }}>
          <h3>上传加密附件</h3>
          <p className="muted-note">单个附件 1 字节至 1MiB；服务端使用现有加密存储，浏览器不保存密钥或明文副本。下载需当前授权。</p>
          <div className="field"><Label htmlFor="archive-file">选择文件</Label><Input id="archive-file" type="file" onChange={event => setFile(event.target.files?.[0] ?? null)}/></div>
          <div className="field"><Label htmlFor="archive-upload-reason">上传原因</Label><Input id="archive-upload-reason" value={uploadReason} maxLength={1000} onChange={event => setUploadReason(event.target.value)} required/></div>
          
          {intent.error != null && intent.phase === 'CONFIRMING' && <ErrorNotice error={intent.error}/>}
          <div className="version-actions"><Button type="submit" disabled={formLocked || !file || !uploadReason.trim()}>{busy ? '正在上传…' : '确认上传'}</Button></div>
        </form>}
        <section aria-label="档案附件" style={{ marginTop: 16 }}>
          <h3>附件（仅元数据与摘要）</h3>
          {detail.data.attachments.length === 0 ? <Empty title="暂无附件"/> : detail.data.attachments.map(item => <article key={item.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
            <p>{item.filename} · {item.mime_type} · {item.byte_size} 字节</p>
            <p style={wrap}>SHA-256 {item.content_sha256} · 上传 {item.uploader_name ?? item.uploaded_by} · {item.uploaded_at}</p>
            <Button variant="outline" onClick={() => download(detail.data!, item)}>下载附件</Button>
          </article>)}
        </section>
        {showHistory && <section aria-label="档案历史" style={{ marginTop: 16 }}>
          <h3>不可变历史</h3>
          {history.loading ? <Loading/> : history.error != null ? <ErrorNotice error={history.error} retry={history.refresh}/> : history.data ? (history.data.length === 0 ? <Empty title="暂无历史记录"/> : history.data.map(entry => <article key={entry.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
            <p>{entry.action} · revision {entry.revision_before ?? '无'} → {entry.revision_after} · {entry.actor_name ?? entry.actor_id} · {entry.created_at}</p>
            <p style={{ whiteSpace: 'pre-wrap' }}>{entry.reason}</p>
          </article>)) : null}
        </section>}
      </> : null}
    </section>}
  </section>
}
