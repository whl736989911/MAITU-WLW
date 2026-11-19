import { useEffect, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import { api, ApiError } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import {
  APPLICATION_PAGE_SIZE, DELIVERY_STATUSES, OUTPUT_AUTH_SCHEMES, OUTPUT_MODES, OUTPUT_PROTOCOLS, REFERENCE_KINDS,
  SCOPE_MODES,
  getApplication, getApplicationCapabilities, getApplicationDeliveries, getApplicationDelivery,
  getApplicationEvents, getApplications, getReferenceOptions,
  type ApplicationDetail, type ApplicationPage, type ApplicationEvent, type Authorization, type DeliveryDetail,
  type DeliveryPage, type Output, type OutputAuthScheme, type OutputMode, type OutputProtocol, type ReferenceOptions,
  type ReferenceKind, type ScopeMode,
} from '@/lib/applications'
import { useApplicationIntent } from '@/lib/application-intent'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { TargetUnitSelect } from '@/components/asset-picker'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'

const wrap = { minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' as const }
const PAGE = 'applications'
type FormMode = 'create' | 'edit' | 'grant' | 'output' | 'archive' | null
type OutputDraft = {
  id: string | null; name: string; mode: OutputMode; protocol: OutputProtocol; url: string
  scheme: OutputAuthScheme | ''; headerName: string; credential: string
  mqttTopic: string; mqttUsername: string; mqttPassword: string
  timeout: string; enabled: boolean; revision: number
}

function emptyOutput(): OutputDraft {
  return {
    id: null, name: '', mode: 'NORMALIZED', protocol: 'HTTPS', url: '', scheme: 'NONE', headerName: '',
    credential: '', mqttTopic: '', mqttUsername: '', mqttPassword: '', timeout: '5000', enabled: false, revision: 0,
  }
}

/** Open applications and data output: real configuration, real deliveries, no placeholder transport. */
export function ApplicationsPage() {
  const s = useSession()
  const correct = s.audience === 'management' && s.user?.user_type === 'PLATFORM'
  if (!s.token || !correct || s.user?.must_change_password || !s.permissions.includes('applications:read')
    || String(s.page) !== PAGE) return <Empty title="当前无应用与数据输出读取范围"/>
  const frame = `${s.authGeneration}`
  return <Content key={frame} permissions={[...s.permissions]}/>
}

function Content({ permissions }: { permissions: string[] }) {
  const session = useSession()
  const scopeKey = `${session.scopeTenantIds.join(',')}:${session.scopePlaceIds.join(',')}`
  const intent = useApplicationIntent()
  const canWrite = permissions.includes('applications:write')
  const canReadDevices = permissions.includes('device:read')

  const [query, setQuery] = useState({ page: 1, status: '', keyword: '' })
  const [keywordDraft, setKeywordDraft] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [mode, setMode] = useState<FormMode>(null)
  const [targetUnit, setTargetUnit] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [reason, setReason] = useState('')

  const [productPage, setProductPage] = useState(1)
  const [productKeyword, setProductKeyword] = useState('')
  const [productDraft, setProductDraft] = useState('')
  const [productId, setProductId] = useState('')
  const [versionId, setVersionId] = useState('')
  const [referenceKind, setReferenceKind] = useState<ReferenceKind>('FORMAL_VERSION')
  const [refPlatform, setRefPlatform] = useState('')
  const [legacyProductId, setLegacyProductId] = useState('')
  const [vendorModelId, setVendorModelId] = useState('')
  const [driverKey, setDriverKey] = useState('')
  const [refPage, setRefPage] = useState(1)
  const [refKeyword, setRefKeyword] = useState('')
  const [refDraft, setRefDraft] = useState('')
  const [scopeMode, setScopeMode] = useState<ScopeMode>('DEVICE_SUBSET')
  const [devicePage, setDevicePage] = useState(1)
  const [deviceKeyword, setDeviceKeyword] = useState('')
  const [deviceDraft, setDeviceDraft] = useState('')
  const [pickedDevices, setPickedDevices] = useState<Map<string, string>>(new Map())

  const [output, setOutput] = useState<OutputDraft>(emptyOutput)

  const [deliveryQuery, setDeliveryQuery] = useState<{ page: number; status: string; outputId: string }>(
    { page: 1, status: '', outputId: '' })
  const [deliveryId, setDeliveryId] = useState<string | null>(null)
  const [showEvents, setShowEvents] = useState(false)
  const [eventPage, setEventPage] = useState(1)
  const [localError, setLocalError] = useState<unknown>()

  const capabilities = useResource('application-capabilities', signal => getApplicationCapabilities(signal))
  const list = useResource<ApplicationPage>(`application-list:${scopeKey}:${query.page}:${query.status}:${query.keyword}`,
    signal => getApplications(query, signal))
  const detail = useResource<ApplicationDetail>(`application-detail:${scopeKey}:${selectedId ?? ''}`,
    signal => getApplication(selectedId!, signal), !!selectedId)
  const deliveries = useResource<DeliveryPage>(
    `application-deliveries:${scopeKey}:${selectedId ?? ''}:${deliveryQuery.page}:${deliveryQuery.status}:${deliveryQuery.outputId}`,
    signal => getApplicationDeliveries(selectedId!, deliveryQuery, signal), !!selectedId)
  const delivery = useResource<DeliveryDetail>(`application-delivery:${scopeKey}:${selectedId ?? ''}:${deliveryId ?? ''}`,
    signal => getApplicationDelivery(selectedId!, deliveryId!, signal), !!selectedId && !!deliveryId)
  const events = useResource(`application-events:${scopeKey}:${selectedId ?? ''}:${eventPage}`,
    signal => getApplicationEvents(selectedId!, eventPage, signal), !!selectedId && showEvents)
  const products = useResource(`application-products:${productPage}:${productKeyword}`,
    signal => api.products(productPage, productKeyword, signal),
    mode === 'grant' && referenceKind === 'FORMAL_VERSION')
  const versions = useResource(`application-versions:${productId}`,
    signal => api.versions(productId, signal),
    mode === 'grant' && referenceKind === 'FORMAL_VERSION' && !!productId)
  const references = useResource<ReferenceOptions>(
    `application-reference-options:${referenceKind}:${refPlatform}:${refPage}:${refKeyword}:${targetUnit}`,
    signal => getReferenceOptions({ kind: referenceKind, platform: refPlatform, page: refPage, keyword: refKeyword },
      targetUnit, signal),
    mode === 'grant' && referenceKind !== 'FORMAL_VERSION' && !!targetUnit)
  const devices = useResource(`application-devices:${targetUnit}:${devicePage}:${deviceKeyword}`,
    signal => api.devices(devicePage, deviceKeyword, APPLICATION_PAGE_SIZE, signal, undefined, undefined, targetUnit || undefined),
    mode === 'grant' && scopeMode === 'DEVICE_SUBSET' && canReadDevices && !!targetUnit)

  const app = detail.data?.application
  const busy = intent.phase === 'PENDING'
  const completion = intent.completion
  const frozen = !!intent.intent && intent.phase !== 'EDITING'
  const rawPermitted = !!capabilities.data?.raw_forward_permitted

  useEffect(() => {
    if (!completion) return
    detail.refresh()
    list.refresh()
    deliveries.refresh()
  }, [completion?.serial])

  // A vendor-model authorization freezes a real adapter digest, so a driver must be chosen before submitting.
  useEffect(() => {
    if (mode !== 'grant' || referenceKind !== 'VENDOR_MODEL' || driverKey) return
    const drivers = references.data?.drivers ?? []
    if (drivers.length === 1) setDriverKey(drivers[0].key)
  }, [mode, referenceKind, driverKey, references.data?.drivers])

  // Import/export and every write target the opened application's own unit, never a stale selector value.
  useEffect(() => {
    if (app?.tenant_id) setTargetUnit(app.tenant_id)
  }, [app?.tenant_id])

  function resetWrite() {
    setReason(''); setLocalError(undefined)
  }
  function refreshAll() {
    setMode(null); resetWrite(); setShowEvents(false); setDeliveryId(null)
    intent.dismissCompletion(); detail.refresh(); list.refresh(); deliveries.refresh()
  }
  function choose(id: string | null) {
    if (frozen) return
    setSelectedId(id); setMode(null); resetWrite(); setShowEvents(false); setDeliveryId(null)
  }
  function beginCreate() {
    if (!canWrite || frozen) return
    resetWrite(); setMode('create'); setName(''); setDescription(''); setTargetUnit('')
  }
  function beginEdit() {
    if (!app || frozen) return
    resetWrite(); setMode('edit'); setName(app.name); setDescription(app.description)
  }
  function beginGrant() {
    if (!app || frozen) return
    resetWrite(); setMode('grant'); setProductId(''); setVersionId(''); setScopeMode('DEVICE_SUBSET')
    setProductKeyword(''); setProductDraft(''); setProductPage(1); setPickedDevices(new Map())
    setReferenceKind('FORMAL_VERSION'); setRefPlatform(''); setLegacyProductId(''); setVendorModelId('')
    setDriverKey(''); setRefKeyword(''); setRefDraft(''); setRefPage(1)
    setDeviceKeyword(''); setDeviceDraft(''); setDevicePage(1); setTargetUnit(app.tenant_id)
  }
  function beginOutput(existing?: Output) {
    if (!app || frozen) return
    resetWrite()
    setOutput(existing
      ? { id: existing.id, name: existing.name, mode: existing.mode, protocol: existing.protocol, url: '',
          scheme: existing.auth_scheme, headerName: existing.auth_header_name ?? '', credential: '',
          mqttTopic: '', mqttUsername: '', mqttPassword: '', timeout: String(existing.timeout_ms),
          enabled: existing.enabled, revision: existing.revision }
      : emptyOutput())
    setMode('output')
  }
  function send(request: Parameters<typeof intent.perform>[0]) {
    setLocalError(undefined)
    void intent.perform(request)
  }
  function submitApp(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || (mode !== 'create' && mode !== 'edit')) return
    if (!name.trim() || !reason.trim()) { setLocalError(new ApiError(400, 'INPUT_REQUIRED', '名称与原因均为必填。')); return }
    if (mode === 'create' && !targetUnit) { setLocalError(new ApiError(400, 'TARGET_UNIT_REQUIRED', '请明确选择本次写入的已授权单位。')); return }
    const body: Record<string, unknown> = { name: name.trim(), description: description.trim(), reason: reason.trim() }
    if (mode === 'edit' && app) body.expected_revision = app.revision
    send({
      path: mode === 'edit' && app ? `/applications/${encodeURIComponent(app.id)}` : '/applications',
      method: mode === 'edit' ? 'PUT' : 'POST',
      tenant: mode === 'edit' && app ? app.tenant_id : targetUnit,
      body, display: `${mode === 'edit' ? '更新' : '新建'}应用 · ${name.trim()} · 单位 ${mode === 'edit' && app ? app.tenant_id : targetUnit}`,
    })
  }
  function submitArchive(event: FormEvent) {
    event.preventDefault()
    if (!app || !reason.trim()) { setLocalError(new ApiError(400, 'REASON_REQUIRED', '归档原因必填。')); return }
    send({
      path: `/applications/${encodeURIComponent(app.id)}/archive`, method: 'POST', tenant: app.tenant_id,
      body: { expected_revision: app.revision, reason: reason.trim() },
      display: `归档应用 · ${app.name} · 将停用全部输出并撤销授权 · revision ${app.revision}`,
    })
  }
  function submitGrant(event: FormEvent) {
    event.preventDefault()
    const identityReady = referenceKind === 'FORMAL_VERSION' ? !!productId && !!versionId
      : referenceKind === 'LEGACY_PRODUCT' ? !!refPlatform && !!legacyProductId
      : !!refPlatform && !!vendorModelId
    if (!app || !identityReady || !reason.trim()) {
      setLocalError(new ApiError(400, 'INPUT_REQUIRED', '授权身份与原因均为必填。')); return
    }
    if (scopeMode === 'DEVICE_SUBSET' && pickedDevices.size === 0) {
      setLocalError(new ApiError(400, 'DEVICE_REQUIRED', '部分设备授权必须明确选择设备。')); return
    }
    const identity: Record<string, unknown> = referenceKind === 'FORMAL_VERSION'
      ? { product_id: productId, product_version_id: versionId }
      : referenceKind === 'LEGACY_PRODUCT'
        ? { source_platform: refPlatform, legacy_product_id: legacyProductId }
        : { source_platform: refPlatform, vendor_model_id: vendorModelId, driver_key: driverKey || undefined }
    const displayIdentity = referenceKind === 'FORMAL_VERSION' ? `版本 ${versionId}`
      : referenceKind === 'LEGACY_PRODUCT' ? `旧产品 ${refPlatform}/${legacyProductId}`
      : `型号 ${refPlatform}/${vendorModelId}`
    send({
      path: `/applications/${encodeURIComponent(app.id)}/authorizations`, method: 'POST', tenant: app.tenant_id,
      body: { ...identity, reference_kind: referenceKind, scope_mode: scopeMode,
        device_ids: scopeMode === 'DEVICE_SUBSET' ? [...pickedDevices.keys()] : [], reason: reason.trim() },
      display: `授权 · ${app.name} · ${displayIdentity} · ${scopeMode === 'ALL_TENANT' ? '全单位' : `${pickedDevices.size}台设备`}`,
    })
  }
  function submitOutput(event: FormEvent) {
    event.preventDefault()
    if (!app || !output.name.trim() || !reason.trim()) {
      setLocalError(new ApiError(400, 'INPUT_REQUIRED', '输出名称与原因均为必填。')); return
    }
    if (output.mode === 'SINGLE_DEVICE_RAW' && !rawPermitted) {
      setLocalError(new ApiError(403, 'RAW_PERMISSION_REQUIRED', '原始报文转发需要应用原始转发权限与消息原文权限。')); return
    }
    const mqtt = output.protocol === 'MQTT_TLS_3_1_1'
    const existing = detail.data?.outputs.find(item => item.id === output.id)
    if (mqtt) {
      const topic = output.mqttTopic.trim()
      const username = output.mqttUsername.trim()
      const password = output.mqttPassword
      const targetMissing = !output.url.trim() && !existing?.endpoint_origin
      const credentialsMissing = !(topic || existing?.mqtt_topic_masked)
        || !(username || existing?.mqtt_username_masked) || !(password || existing?.mqtt_password_masked)
      if (output.enabled && (targetMissing || credentialsMissing)) {
        setLocalError(new ApiError(400, 'MQTT_INPUT_REQUIRED',
          '启用MQTT输出前必须提供 mqtts:// 地址、固定主题及用户名/密码；不支持匿名连接。')); return
      }
    } else if (output.enabled && !output.url.trim() && !existing?.endpoint_origin) {
      setLocalError(new ApiError(400, 'ENDPOINT_REQUIRED', '启用HTTPS输出前必须提供地址。')); return
    }
    const body: Record<string, unknown> = {
      name: output.name.trim(), timeout_ms: Number(output.timeout), enabled: output.enabled, reason: reason.trim(),
    }
    if (output.url.trim()) body.endpoint_url = output.url.trim()
    if (!output.id) body.protocol = output.protocol
    if (mqtt) {
      body.auth_scheme = 'NONE'
      if (output.mqttTopic.trim()) body.mqtt_topic = output.mqttTopic.trim()
      if (output.mqttUsername.trim()) body.mqtt_username = output.mqttUsername.trim()
      if (output.mqttPassword) body.mqtt_password = output.mqttPassword
    } else {
      body.auth_scheme = output.scheme || undefined
      body.auth_header_name = output.headerName.trim() || null
      if (output.credential) body.credential = output.credential
    }
    if (output.id) {
      body.expected_revision = output.revision
    } else {
      body.mode = output.mode
    }
    send({
      path: output.id ? `/applications/${encodeURIComponent(app.id)}/outputs/${encodeURIComponent(output.id)}`
        : `/applications/${encodeURIComponent(app.id)}/outputs`,
      method: output.id ? 'PUT' : 'POST', tenant: app.tenant_id, body,
      display: `${output.id ? '更新' : '新建'}输出 · ${output.name.trim()} · ${output.protocol} · ${output.mode} · ${output.enabled ? '启用' : '停用'}`,
    })
  }
  function submitRevoke(authorization: Authorization) {
    if (!app || frozen) return
    if (!reason.trim()) {
      setLocalError(new ApiError(400, 'INPUT_REQUIRED', '撤销授权需要原因。')); return
    }
    send({
      path: `/applications/${encodeURIComponent(app.id)}/authorizations/${encodeURIComponent(authorization.id)}/revoke`,
      method: 'POST', tenant: app.tenant_id,
      body: { expected_revision: authorization.revision, reason: reason.trim() },
      display: `撤销授权 · ${authorization.reference_key}`,
    })
  }

  return <section aria-label="应用与数据输出" className="device-workbench" style={wrap}>
    <PageHeading eyebrow="OPEN APPLICATION · 单位应用与数据输出" title="应用与数据输出">
      <PageHelp title="应用输出边界">应用、产品版本授权和输出配置都属当前单位。厂家来源账号与本页的应用分开建模，本页不保存任何来源凭据。启用输出需要部署级允许清单与加密密钥；原始报文模式还需要独立的原文权限，且只转发已证明属于单一设备的原始报文。HTTP成功或MQTT PUBACK仅代表远端传输层接受请求，不代表远端已业务处理；结果未确认时不会自动重发。MQTT只支持TLS（mqtts://，固定8883）、固定主题与用户名/密码，不支持匿名或明文连接。</PageHelp>
      {!frozen && <DataTransferActions kind="APPLICATION_OUTPUTS" tenantId={targetUnit || undefined}/>}
      <Button variant="outline" onClick={refreshAll}>刷新应用</Button>
      {canWrite && <Button onClick={beginCreate} disabled={mode === 'create' || frozen}>新建应用</Button>}
    </PageHeading>

    {capabilities.data && (!capabilities.data.encryption_ready || !capabilities.data.allowlist_configured) && <section className="notice" style={wrap} role="status">
      <h2>输出外呼未就绪（真实配置缺失）</h2>
      <p>加密密钥 {capabilities.data.encryption_ready ? '已配置' : '未配置'} · 主机允许清单 {capabilities.data.allowlist_configured ? '已配置' : '未配置'}。</p>
      <p>缺少任一部署配置时，服务端拒绝保存输出地址与凭据，也拒绝启用输出；页面不会提供替代地址。</p>
    </section>}

    {completion && <section aria-label="应用提交回执" className="notice" style={wrap}>
      <h2>提交已确认（快照）</h2>
      <p>{completion.result.application.id} · {completion.result.application.status} · revision {completion.result.application.revision}</p>
      <p>授权 {completion.result.authorizations.length} 条 · 输出 {completion.result.outputs.length} 条 · 这是提交返回的快照，请刷新读取当前事实。</p>
      <div className="version-actions"><Button variant="outline" onClick={refreshAll}>刷新读取当前事实</Button><Button variant="ghost" onClick={() => intent.dismissCompletion()}>关闭回执（不刷新）</Button></div>
    </section>}

    {frozen && intent.intent && <section aria-label="冻结的应用意图" className="notice" style={wrap} role="alert">
      <h2>{intent.phase === 'PENDING' ? '正在提交，结果未知' : intent.phase === 'UNCERTAIN' ? '结果未确认（不是失败）' : intent.phase === 'CONFLICT' ? '提交冲突' : '上次提交未成功'}</h2>
      <p style={{ whiteSpace: 'pre-wrap' }}>{intent.intent.display}</p>
      <p>冻结的请求与幂等键只保留在当前页面内存储内存中；密码不保存。请先刷新核对当前事实，禁止另建新意图。</p>
      {intent.error != null && <ErrorNotice error={intent.error}/>}
      {(intent.phase === 'UNCERTAIN' || intent.phase === 'CONFIRMING') && <div className="form-stack">
        
        <div className="version-actions"><Button variant="outline"  onClick={() => { void intent.retry() }}>沿原操作确认结果</Button><Button variant="outline" onClick={refreshAll}>刷新核对</Button></div>
      </div>}
      {intent.phase === 'CONFLICT' && <div className="version-actions"><Button variant="outline" onClick={() => { refreshAll(); intent.reload() }}>刷新并放弃旧意图</Button></div>}
      {intent.phase === 'PENDING' && <p className="muted-note">正在等待服务端结果；不要关闭浏览器或创建另一意图。</p>}
    </section>}

    {localError != null && <ErrorNotice error={localError}/>}

    {(mode === 'create' || mode === 'edit') && !frozen && <section aria-label="应用表单" style={{ ...wrap, border: '1px solid #c6d5bb', borderRadius: 12, padding: 16 }}>
      <h2>{mode === 'create' ? '新建应用' : '编辑应用'}</h2>
      <form className="form-stack" onSubmit={submitApp}>
        {mode === 'create' && <TargetUnitSelect audience="management" permissions={permissions}
          value={targetUnit} onChange={setTargetUnit} disabled={busy}/>}
        {mode === 'create' && <p className="muted-note">目标单位只影响本次写入，不改变左侧读取范围。应用与厂家来源账号分开建模。</p>}
        <div className="field"><Label htmlFor="application-name">应用名称</Label><Input id="application-name" value={name} maxLength={200} onChange={event => setName(event.target.value)} required/></div>
        <div className="field"><Label htmlFor="application-description">说明</Label><textarea id="application-description" className="native-select" rows={3} maxLength={2000} value={description} onChange={event => setDescription(event.target.value)}/></div>
        <div className="field"><Label htmlFor="application-reason">变更原因</Label><Input id="application-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
        
        <div className="version-actions"><Button type="submit" disabled={busy || !name.trim() || !reason.trim()}>{busy ? '正在提交…' : mode === 'create' ? '新建应用' : '保存应用'}</Button><Button variant="outline" onClick={() => { setMode(null); resetWrite(); intent.edit() }} disabled={busy}>关闭</Button></div>
      </form>
    </section>}

    <section aria-label="应用列表" style={wrap}>
      <h2>应用列表</h2>
      <form className="search-form" onSubmit={event => { event.preventDefault(); if (frozen) return; setSelectedId(null); setQuery(current => ({ ...current, page: 1, keyword: keywordDraft.trim() })) }} style={{ ...wrap, flexWrap: 'wrap', marginBottom: 12 }}>
        <Input aria-label="应用关键字" value={keywordDraft} maxLength={100} onChange={event => setKeywordDraft(event.target.value)} placeholder="应用名称 / 说明"/>
        <select aria-label="应用状态" className="native-select" value={query.status} onChange={event => { if (frozen) return; setSelectedId(null); setQuery(current => ({ ...current, page: 1, status: event.target.value })) }}>
          <option value="">全部状态</option><option value="ACTIVE">ACTIVE</option><option value="ARCHIVED">ARCHIVED</option>
        </select>
        <Button type="submit" variant="outline" disabled={frozen}>查询应用</Button>
      </form>
      {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : list.data ? <>
        <p>状态：{query.status || '全部'} · 关键字：{query.keyword || '无'} · 每页 {list.data.size} 条（服务端实际值）</p>
        {list.data.items.length === 0 ? <Empty title="本范围无应用配置"/> : <div style={{ display: 'grid', gap: 12 }}>
          {list.data.items.map(item => <article key={item.id} data-application-id={item.id} style={{ ...wrap, border: '1px solid #e3e8db', borderRadius: 12, padding: 16 }}>
            <h3>{item.name} · {item.status}</h3>
            <p>单位 {item.tenant_name}（{item.tenant_id}） · 授权 {item.authorization_count} · 输出 {item.output_count}（启用 {item.enabled_output_count}）</p>
            <p>待执行 {item.pending_deliveries} · 未确认 {item.unknown_deliveries} · 阻断 {item.blocked_deliveries} · revision {item.revision}</p>
            <Button variant="outline" onClick={() => choose(item.id)} disabled={frozen}>打开应用</Button>
          </article>)}
        </div>}
        <Pager page={list.data.page} size={list.data.size} total={list.data.total} onChange={page => { if (frozen) return; setSelectedId(null); setQuery(current => ({ ...current, page })) }}/>
      </> : null}
    </section>

    {selectedId && <section aria-label="应用详情" data-selected-application-id={selectedId} style={wrap}>
      <h2>应用详情</h2>
      {detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : detail.data && app ? <>
        <dl className="detail-list" style={wrap}>
          <div><dt>应用</dt><dd>{app.name} · {app.status} · revision {app.revision}</dd></div>
          <div><dt>单位</dt><dd>{app.tenant_name}（{app.tenant_id}）</dd></div>
          <div><dt>说明</dt><dd style={{ whiteSpace: 'pre-wrap' }}>{app.description || '空'}</dd></div>
          <div><dt>归档</dt><dd>{app.archived_at ?? '未归档'} · {app.archived_by_name ?? '无'} · {app.archived_reason ?? '无'}</dd></div>
          <div><dt>投递汇总</dt><dd>待执行 {detail.data.deliveries.pending} · 执行中 {detail.data.deliveries.in_flight} · 已接受 {detail.data.deliveries.accepted} · 拒绝 {detail.data.deliveries.rejected} · 未确认 {detail.data.deliveries.unknown} · 阻断 {detail.data.deliveries.blocked}</dd></div>
          <div><dt>最近捕获</dt><dd>{detail.data.deliveries.last_captured_at ?? '无'}</dd></div>
        </dl>
        <div className="version-actions" style={{ flexWrap: 'wrap' }}>
          {canWrite && app.status === 'ACTIVE' && !mode && <Button variant="outline" onClick={beginEdit} disabled={frozen}>编辑应用</Button>}
          {canWrite && app.status === 'ACTIVE' && !mode && <Button variant="outline" onClick={beginGrant} disabled={frozen}>新增产品版本授权</Button>}
          {canWrite && app.status === 'ACTIVE' && !mode && <Button variant="outline" onClick={() => beginOutput()} disabled={frozen}>新增输出配置</Button>}
          {canWrite && app.status === 'ACTIVE' && !mode && <Button variant="outline" onClick={() => { resetWrite(); setMode('archive') }} disabled={frozen}>归档应用</Button>}
          <Button variant="outline" onClick={() => setShowEvents(value => !value)}>{showEvents ? '收起历史' : '查看变更历史'}</Button>
          <Button variant="outline" onClick={refreshAll}>刷新应用</Button>
          <Button variant="outline" onClick={() => choose(null)} disabled={frozen}>关闭详情</Button>
        </div>

        {mode === 'archive' && !frozen && <form className="form-stack" onSubmit={submitArchive} style={{ marginTop: 12 }}>
          <h3>确认软归档</h3>
          <p className="muted-note">归档会停用该应用的全部输出并撤销全部授权；历史与投递记录保留，不删除数据，也不通知远端。</p>
          <div className="field"><Label htmlFor="application-archive-reason">归档原因</Label><Input id="application-archive-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
          
          <div className="version-actions"><Button type="submit" disabled={busy || !reason.trim()}>确认归档</Button><Button variant="outline" onClick={() => { setMode(null); resetWrite() }} disabled={busy}>取消</Button></div>
        </form>}

        {mode === 'grant' && !frozen && <section aria-label="授权表单" style={{ marginTop: 12 }}>
          <h2>新增身份授权</h2>
          <form className="form-stack" onSubmit={submitGrant}>
            <p className="muted-note">授权指向一个权威身份：已发布产品版本、旧平台解码器注册表中的旧产品，或来源发现元数据中的厂商型号。解码器/适配器摘要由系统按真实制品冻结，不需要手填；事实未携带相同摘要时投递会在执行前被前置条件阻断。全单位授权要求操作者当前拥有该单位全量设备范围；部分设备授权记录当时设备快照。</p>
            <div className="field"><Label htmlFor="application-reference-kind">授权身份类型</Label>
              <select id="application-reference-kind" className="native-select" value={referenceKind}
                onChange={event => {
                  const next = event.target.value as ReferenceKind
                  setReferenceKind(next); setProductId(''); setVersionId('')
                  setRefPlatform(next === 'VENDOR_MODEL' ? 'hikvision_firecloud' : '')
                  setLegacyProductId(''); setVendorModelId(''); setDriverKey('')
                  setRefKeyword(''); setRefDraft(''); setRefPage(1)
                }}>
                {REFERENCE_KINDS.map(item => <option key={item} value={item}>{
                  item === 'FORMAL_VERSION' ? '正式产品版本（已发布）'
                    : item === 'LEGACY_PRODUCT' ? '旧产品（解码器注册表）' : '厂商型号（发现元数据）'}</option>)}
              </select>
            </div>
            {referenceKind === 'FORMAL_VERSION' && <>
              <div className="search-form" style={{ flexWrap: 'wrap' }}>
                <Input aria-label="产品关键字" value={productDraft} maxLength={100} onChange={event => setProductDraft(event.target.value)} placeholder="产品名称 / 型号 / 制造商"/>
                <Button type="button" variant="outline" onClick={() => { setProductPage(1); setProductKeyword(productDraft.trim()) }}>查询产品</Button>
              </div>
              {products.loading ? <Loading/> : products.error != null ? <ErrorNotice error={products.error} retry={products.refresh}/> : products.data ? <>
                <div style={{ display: 'grid', gap: 6 }}>
                  {products.data.items.map(item => <label key={item.id} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <input type="radio" name="product" checked={productId === item.id} onChange={() => { setProductId(item.id); setVersionId('') }}/>
                    <span>{item.name} · {item.manufacturer} {item.model}</span>
                  </label>)}
                </div>
                <Pager page={products.data.page} size={products.data.size} total={products.data.total} onChange={setProductPage}/>
              </> : null}
              {productId && <div className="field"><Label htmlFor="application-version">已发布版本</Label>
                {versions.loading ? <Loading/> : versions.error != null ? <ErrorNotice error={versions.error} retry={versions.refresh}/> :
                  <select id="application-version" className="native-select" value={versionId} onChange={event => setVersionId(event.target.value)} required>
                    <option value="">选择已发布版本</option>
                    {(versions.data?.items ?? []).filter(item => item.status === 'PUBLISHED').map(item =>
                      <option key={item.id} value={item.id}>v{item.version_number}</option>)}
                  </select>}
              </div>}
            </>}
            {referenceKind !== 'FORMAL_VERSION' && <>
              {referenceKind === 'LEGACY_PRODUCT' && <div className="field"><Label htmlFor="application-legacy-platform">来源平台（本单位已观测）</Label>
                <select id="application-legacy-platform" className="native-select" value={refPlatform}
                  onChange={event => { setRefPlatform(event.target.value); setLegacyProductId('') }}>
                  <option value="">选择已观测来源平台</option>
                  {(references.data?.platforms ?? []).map(item => <option key={item} value={item}>{item}</option>)}
                </select>
                {(references.data?.platforms ?? []).length === 0 && <p className="muted-note">该单位尚无旧平台观测报文；缺少这一前置条件时不能建立旧产品授权。</p>}
              </div>}
              {referenceKind === 'VENDOR_MODEL' && <>
                <p className="muted-note">厂商型号来自发现元数据（本地已绑定设备）。来源平台固定为 {refPlatform || 'hikvision_firecloud'}。</p>
                <div className="field"><Label htmlFor="application-driver">来源适配器（必选）</Label>
                  <select id="application-driver" className="native-select" value={driverKey} onChange={event => setDriverKey(event.target.value)} required>
                    <option value="">选择来源适配器</option>
                    {(references.data?.drivers ?? []).map(item => <option key={item.key} value={item.key}>{item.name}（{item.key}）</option>)}
                  </select>
                </div>
              </>}
              <div className="search-form" style={{ flexWrap: 'wrap' }}>
                <Input aria-label="授权身份关键字" value={refDraft} maxLength={100} onChange={event => setRefDraft(event.target.value)}
                  placeholder={referenceKind === 'LEGACY_PRODUCT' ? '旧产品ID' : '厂商型号ID'}/>
                <Button type="button" variant="outline" onClick={() => { setRefPage(1); setRefKeyword(refDraft.trim()) }}>查询身份</Button>
              </div>
              {references.loading ? <Loading/> : references.error != null ? <ErrorNotice error={references.error} retry={references.refresh}/> : references.data ? <>
                <div style={{ display: 'grid', gap: 6 }}>
                  {references.data.items.map(item => <label key={item.id} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <input type="radio" name="reference-option"
                      checked={(referenceKind === 'LEGACY_PRODUCT' ? legacyProductId : vendorModelId) === item.id}
                      onChange={() => (referenceKind === 'LEGACY_PRODUCT' ? setLegacyProductId(item.id) : setVendorModelId(item.id))}/>
                    <span>{item.label} · {item.detail}{item.sha256 ? ` · sha ${item.sha256.slice(0, 16)}` : ''}</span>
                  </label>)}
                </div>
                {references.data.items.length === 0 && <p className="muted-note">该项来源没有可用于授权的权威身份；这是前置条件缺失，不是空成功。</p>}
                <Pager page={references.data.page} size={references.data.size} total={references.data.total} onChange={setRefPage}/>
              </> : null}
            </>}
            <div className="field"><Label htmlFor="application-scope">授权范围</Label>
              <select id="application-scope" className="native-select" value={scopeMode} onChange={event => setScopeMode(event.target.value as ScopeMode)}>
                {SCOPE_MODES.map(item => <option key={item} value={item}>{item === 'ALL_TENANT' ? '全单位（需全量设备范围）' : '部分设备（快照）'}</option>)}
              </select>
            </div>
            {scopeMode === 'DEVICE_SUBSET' && <section aria-label="设备快照选择" style={wrap}>
              {!canReadDevices ? <p className="muted-note">缺少 device:read，无法浏览设备；请改用具备设备读取权限的账户，本页不提供手工输入设备ID。</p> : <>
                <div className="search-form" style={{ flexWrap: 'wrap' }}>
                  <Input aria-label="设备关键字" value={deviceDraft} maxLength={100} onChange={event => setDeviceDraft(event.target.value)} placeholder="设备名称 / EUI"/>
                  <Button type="button" variant="outline" onClick={() => { setDevicePage(1); setDeviceKeyword(deviceDraft.trim()) }}>查询设备</Button>
                </div>
                {devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : devices.data ? <>
                  <div style={{ display: 'grid', gap: 6 }}>
                    {devices.data.items.map(item => <label key={item.id} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <input type="checkbox" checked={pickedDevices.has(item.id)} onChange={event => {
                        setPickedDevices(current => {
                          const next = new Map(current)
                          if (event.target.checked) next.set(item.id, `${item.name ?? item.id}${item.device_eui ? ` · ${item.device_eui}` : ''}`)
                          else next.delete(item.id)
                          return next
                        })
                      }}/>
                      <span>{item.name ?? item.id} · {item.device_eui ?? '无EUI'} · {item.version_number ? `v${item.version_number}` : '未绑定版本'}</span>
                    </label>)}
                  </div>
                  <Pager page={devices.data.page} size={devices.data.size} total={devices.data.total} onChange={setDevicePage}/>
                </> : null}
              </>}
              <p>已选 {pickedDevices.size} 台：{[...pickedDevices.values()].join('；') || '无'}</p>
            </section>}
            <div className="field"><Label htmlFor="application-grant-reason">授权原因</Label><Input id="application-grant-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
            
            <div className="version-actions"><Button type="submit" disabled={busy || !reason.trim()
              || (referenceKind === 'FORMAL_VERSION' ? !productId || !versionId
                : referenceKind === 'LEGACY_PRODUCT' ? !refPlatform || !legacyProductId
                : !refPlatform || !vendorModelId || !driverKey)}>{busy ? '正在授权…' : '确认授权'}</Button><Button variant="outline" onClick={() => { setMode(null); resetWrite() }} disabled={busy}>取消</Button></div>
          </form>
        </section>}

        {mode === 'output' && !frozen && <section aria-label="输出配置表单" style={{ marginTop: 12 }}>
          <h2>{output.id ? '编辑输出配置' : '新增输出配置'}</h2>
          <form className="form-stack" onSubmit={submitOutput}>
            <p className="muted-note">HTTPS只接受部署允许清单内公网主机的443端口；MQTT只接受 mqtts:// 与固定8883 TLS端口，使用一个短连接、clean session、QoS1 PUBLISH，匹配的PUBACK仅代表broker已接受报文。两种协议都不跟随跳转、不自动重连、不自动重发；完整地址、主题与凭据加密保存，页面只显示可读源、掩码与摘要。</p>
            <div className="field"><Label htmlFor="application-output-name">输出名称</Label><Input id="application-output-name" value={output.name} maxLength={200} onChange={event => setOutput(current => ({ ...current, name: event.target.value }))} required/></div>
            <div className="field"><Label htmlFor="application-output-mode">输出模式</Label>
              <select id="application-output-mode" className="native-select" value={output.mode} disabled={!!output.id}
                onChange={event => setOutput(current => ({ ...current, mode: event.target.value as OutputMode }))}>
                {OUTPUT_MODES.map(item => <option key={item} value={item} disabled={item === 'SINGLE_DEVICE_RAW' && !rawPermitted}>
                  {item === 'NORMALIZED' ? '归一化（仅标准投影）' : `原始单设备（需独立原文权限${rawPermitted ? '' : '，当前未授予'}）`}
                </option>)}
              </select>
            </div>
            <div className="field"><Label htmlFor="application-output-protocol">传输协议</Label>
              <select id="application-output-protocol" className="native-select" value={output.protocol} disabled={!!output.id}
                onChange={event => setOutput(current => ({
                  ...current, protocol: event.target.value as OutputProtocol, scheme: 'NONE', headerName: '',
                  credential: '', mqttTopic: '', mqttUsername: '', mqttPassword: '',
                }))}>
                {OUTPUT_PROTOCOLS.map(item => <option key={item} value={item}>{
                  item === 'HTTPS' ? 'HTTPS（HTTP POST）' : 'MQTT_TLS_3_1_1（MQTT 3.1.1 over TLS，QoS1）'}</option>)}
              </select>
              {output.id && <p className="muted-note">协议创建后不可修改；如需切换请新建输出配置。</p>}
            </div>
            {output.mode === 'SINGLE_DEVICE_RAW' && <p className="muted-note">原始模式只发送生产者声明为单一设备、且原始字节可用的报文；混合回调或单位归属不明的报文在捕获阶段就不会进入原始输出。</p>}
            {output.protocol === 'MQTT_TLS_3_1_1' ? <>
              <div className="field"><Label htmlFor="application-output-mqtt-url">MQTT地址（mqtts://主机，端口固定8883；留空则保留现有）</Label><Input id="application-output-mqtt-url" value={output.url} maxLength={1000} onChange={event => setOutput(current => ({ ...current, url: event.target.value }))} placeholder="mqtts://broker.example"/></div>
              <div className="field"><Label htmlFor="application-output-mqtt-topic">固定主题（留空则保留现有）</Label><Input id="application-output-mqtt-topic" value={output.mqttTopic} maxLength={255} onChange={event => setOutput(current => ({ ...current, mqttTopic: event.target.value }))} placeholder={detail.data?.outputs.find(item => item.id === output.id)?.mqtt_topic_masked ?? '例如 fire/alarm'}/></div>
              <div className="field"><Label htmlFor="application-output-mqtt-username">MQTT用户名（只写；留空则保留现有）</Label><Input id="application-output-mqtt-username" autoComplete="off" value={output.mqttUsername} maxLength={256} onChange={event => setOutput(current => ({ ...current, mqttUsername: event.target.value }))} placeholder={detail.data?.outputs.find(item => item.id === output.id)?.mqtt_username_masked ?? '必填，不支持匿名'}/></div>
              <div className="field"><Label htmlFor="application-output-mqtt-password">MQTT密码（只写；留空则保留现有）</Label><Input id="application-output-mqtt-password" type="password" autoComplete="off" value={output.mqttPassword} maxLength={2048} onChange={event => setOutput(current => ({ ...current, mqttPassword: event.target.value }))} placeholder={detail.data?.outputs.find(item => item.id === output.id)?.mqtt_password_masked ?? '必填'}/></div>
              <div className="field"><Label htmlFor="application-output-mqtt-timeout">整体超时（毫秒，含连接/握手/两次回包）</Label><Input id="application-output-mqtt-timeout" inputMode="numeric" value={output.timeout} maxLength={5} onChange={event => setOutput(current => ({ ...current, timeout: event.target.value }))}/></div>
            </> : <details><summary>高级：地址与认证</summary>
              <div className="field"><Label htmlFor="application-output-url">输出地址（留空则保留现有）</Label><Input id="application-output-url" value={output.url} maxLength={1000} onChange={event => setOutput(current => ({ ...current, url: event.target.value }))} placeholder="https://…"/></div>
              <div className="field"><Label htmlFor="application-output-scheme">认证方式</Label>
                <select id="application-output-scheme" className="native-select" value={output.scheme}
                  onChange={event => setOutput(current => {
                    const scheme = event.target.value as OutputAuthScheme | ''
                    return { ...current, scheme, credential: scheme === 'NONE' ? '' : current.credential }
                  })}>
                  <option value="">保留现有</option>
                  {OUTPUT_AUTH_SCHEMES.map(item => <option key={item} value={item}>{item}</option>)}
                </select>
              </div>
              {output.scheme === 'HEADER' && <div className="field"><Label htmlFor="application-output-header">认证请求头名称</Label><Input id="application-output-header" value={output.headerName} maxLength={64} onChange={event => setOutput(current => ({ ...current, headerName: event.target.value }))}/></div>}
              {output.scheme !== 'NONE' && <div className="field"><Label htmlFor="application-output-credential">凭据（只写；留空则保留现有）</Label><Input id="application-output-credential" type="password" autoComplete="off" value={output.credential} maxLength={2048} onChange={event => setOutput(current => ({ ...current, credential: event.target.value }))}/></div>}
              <div className="field"><Label htmlFor="application-output-timeout">超时（毫秒）</Label><Input id="application-output-timeout" inputMode="numeric" value={output.timeout} maxLength={5} onChange={event => setOutput(current => ({ ...current, timeout: event.target.value }))}/></div>
            </details>}
            <div className="field"><label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input type="checkbox" checked={output.enabled} onChange={event => setOutput(current => ({ ...current, enabled: event.target.checked }))}/>
              <span>启用输出（默认关闭；启用前必须有完整地址与凭据）</span>
            </label></div>
            <div className="field"><Label htmlFor="application-output-reason">变更原因</Label><Input id="application-output-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} required/></div>
            
            <div className="version-actions"><Button type="submit" disabled={busy || !output.name.trim() || !reason.trim()}>{busy ? '正在保存…' : '保存输出配置'}</Button><Button variant="outline" onClick={() => { setMode(null); resetWrite() }} disabled={busy}>取消</Button></div>
          </form>
        </section>}

        <section aria-label="授权列表" style={{ marginTop: 16 }}>
          <h3>产品版本授权</h3>
          {detail.data.authorizations.length === 0 ? <Empty title="暂无授权；没有授权就不会产生任何投递"/> :
            detail.data.authorizations.map(item => <article key={item.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
              <p><strong>{item.reference_kind}</strong> · {
                item.reference_kind === 'FORMAL_VERSION'
                  ? `${item.product_name ?? '产品'} · ${item.manufacturer ?? ''} ${item.model ?? ''} · v${item.version_number ?? '?'}`
                  : item.reference_kind === 'LEGACY_PRODUCT'
                    ? `旧产品 ${item.legacy_product_id} · 平台 ${item.source_platform} · 解码器修订 ${item.legacy_decoder_revision ?? '未记录'} · 摘要 ${item.legacy_decoder_sha256 ? item.legacy_decoder_sha256.slice(0, 16) : '未记录（不满足修订门禁）'}`
                    : `厂商型号 ${item.vendor_model_id} · 平台 ${item.source_platform} · 适配器 ${item.driver_key ?? '未指定'} · 摘要 ${item.adapter_digest ? item.adapter_digest.slice(0, 16) : '未记录（不满足修订门禁）'}`
              } · {item.scope_mode} · {item.status}</p>
              <p>{item.reference_key}</p>
              <p>{item.scope_mode === 'DEVICE_SUBSET' ? `设备快照 ${item.device_count} 台` : item.reference_kind === 'FORMAL_VERSION' ? '全单位（按当前同版本设备计算）' : '全单位（按当前来源身份核对）'} · revision {item.revision}</p>
              {item.revoked_at && <p>撤销 {item.revoked_at} · {item.revoked_reason ?? '无原因'}</p>}
              {canWrite && app.status === 'ACTIVE' && item.status === 'ACTIVE' && <div className="version-actions">
                <Input aria-label="撤销授权原因" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} placeholder="撤销原因"/>
                
                <Button variant="outline" disabled={busy || frozen} onClick={() => submitRevoke(item)}>撤销授权</Button>
              </div>}
            </article>)}
        </section>

        <section aria-label="输出配置列表" style={{ marginTop: 16 }}>
          <h3>输出配置</h3>
          {detail.data.outputs.length === 0 ? <Empty title="暂无输出配置；应用不会向外发送任何数据"/> :
            detail.data.outputs.map(item => <article key={item.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
              <p>{item.name} · {item.protocol} · {item.mode} · {item.enabled ? '已启用' : '已停用'} · revision {item.revision}</p>
              <p>地址 {item.endpoint_masked ?? '未配置（草稿，无法启用）'} · 源 {item.endpoint_origin ?? '无'}</p>
              {item.protocol === 'MQTT_TLS_3_1_1'
                ? <p>MQTT认证 {item.mqtt_auth_scheme} · 主题 {item.mqtt_topic_masked ?? '未配置'} · 用户名 {item.mqtt_username_masked ?? '未配置'} · 密码 {item.mqtt_password_masked ?? '未配置'} · 超时 {item.timeout_ms}ms</p>
                : <p>认证 {item.auth_scheme}{item.auth_header_name ? ` · ${item.auth_header_name}` : ''} · 凭据 {item.credential_masked ?? '未配置'} · 超时 {item.timeout_ms}ms</p>}
              <p>地址摘要 {item.endpoint_digest ?? '无'} · {item.protocol === 'MQTT_TLS_3_1_1' ? `主题摘要 ${item.mqtt_topic_digest ?? '无'}` : `凭据摘要 ${item.credential_digest ?? '无'}`}</p>
              {canWrite && app.status === 'ACTIVE' && !mode && <div className="version-actions"><Button variant="outline" onClick={() => beginOutput(item)} disabled={frozen}>编辑/启停</Button></div>}
            </article>)}
        </section>

        <section aria-label="投递结果" style={{ marginTop: 16 }}>
          <h3>投递结果</h3>
          <p className="muted-note">ACCEPTED 只代表远端传输层接受（HTTP 2xx 或 MQTT PUBACK），不代表远端业务处理；UNKNOWN 不会自动重发，需要人工核对远端。BLOCKED 表示执行前被应用/授权/设备/配置边界阻断。</p>
          <form className="search-form" onSubmit={event => event.preventDefault()} style={{ flexWrap: 'wrap' }}>
            <select aria-label="投递状态" className="native-select" value={deliveryQuery.status}
              onChange={event => { setDeliveryId(null); setDeliveryQuery(current => ({ ...current, page: 1, status: event.target.value })) }}>
              <option value="">全部状态</option>{DELIVERY_STATUSES.map(item => <option key={item} value={item}>{item}</option>)}
            </select>
            <select aria-label="输出筛选" className="native-select" value={deliveryQuery.outputId}
              onChange={event => { setDeliveryId(null); setDeliveryQuery(current => ({ ...current, page: 1, outputId: event.target.value })) }}>
              <option value="">全部输出</option>{detail.data.outputs.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
            <Button type="button" variant="outline" onClick={() => deliveries.refresh()}>刷新结果</Button>
          </form>
          {deliveries.loading ? <Loading/> : deliveries.error != null ? <ErrorNotice error={deliveries.error} retry={deliveries.refresh}/> : deliveries.data ? <>
            {deliveries.data.items.length === 0 ? <Empty title="本筛选无投递记录"/> : <div style={{ display: 'grid', gap: 8 }}>
              {deliveries.data.items.map(item => <article key={item.id} style={{ ...wrap, border: '1px solid #e3e8db', borderRadius: 8, padding: 12 }}>
                <p>{item.status} · {item.failure_meaning} · 输出 {item.output_name ?? item.output_id} · {item.protocol} · {item.mode}</p>
                <p>设备 {item.device_name ?? item.device_id} · 来源 {item.source_platform} · 事件 {item.source_event_type ?? '未提供'} · 捕获 {item.captured_at}</p>
                <p>授权身份 {item.authorization_reference_kind} · {item.authorization_reference_key} · 事实身份 {item.source_product_kind ?? (item.product_version_id ? 'FORMAL_VERSION' : '未提供')}{item.source_product_id ? ` ${item.source_product_id}` : item.product_version_id ? ` ${item.product_version_id}` : ''}</p>
                <p>{item.protocol === 'MQTT_TLS_3_1_1' ? 'MQTT PUBACK' : 'HTTP'} {item.http_status ?? '无状态码'} · 尝试 {item.attempt_count} · 失败码 {item.failure_code ?? '无'} · 载荷 {item.payload_bytes} 字节 · SHA-256 {item.payload_sha256}</p>
                <Button variant="outline" onClick={() => setDeliveryId(item.id)}>查看本次投递</Button>
              </article>)}
            </div>}
            <Pager page={deliveries.data.page} size={deliveries.data.size} total={deliveries.data.total}
              onChange={page => { setDeliveryId(null); setDeliveryQuery(current => ({ ...current, page })) }}/>
          </> : null}
        </section>

        {deliveryId && <section aria-label="投递详情" style={{ marginTop: 16 }}>
          <h3>投递详情</h3>
          {delivery.loading ? <Loading/> : delivery.error != null ? <ErrorNotice error={delivery.error} retry={delivery.refresh}/> : delivery.data ? <>
            <dl className="detail-list" style={wrap}>
              <div><dt>状态</dt><dd>{delivery.data.delivery.status} · {delivery.data.delivery.failure_meaning} · {delivery.data.delivery.protocol} · {delivery.data.delivery.protocol === 'MQTT_TLS_3_1_1' ? `MQTT状态码 ${delivery.data.delivery.http_status ?? '不适用'}` : `HTTP ${delivery.data.delivery.http_status ?? '无'}`}</dd></div>
              <div><dt>成功语义</dt><dd>{delivery.data.success_semantics}</dd></div>
              <div><dt>载荷</dt><dd>{delivery.data.delivery.content_type} · {delivery.data.delivery.payload_bytes} 字节 · SHA-256 {delivery.data.delivery.payload_sha256}</dd></div>
              <div><dt>载荷留存</dt><dd>{delivery.data.payload_retention}</dd></div>
              <div><dt>授权身份</dt><dd>{delivery.data.delivery.authorization_reference_kind} · {delivery.data.delivery.authorization_reference_key}</dd></div>
              <div><dt>厂家载荷</dt><dd>原始单设备证明 {delivery.data.delivery.single_device_proof ? '已声明' : '未声明'}</dd></div>
              <div><dt>远端响应</dt><dd>不读取、不保存响应正文或凭据回显；只记录 HTTP 状态与结果分类。</dd></div>
            </dl>
            <h4>不可变尝试记录</h4>
            {delivery.data.attempts.length === 0 ? <Empty title="尚无尝试记录"/> : delivery.data.attempts.map(item => <article key={item.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
              <p>#{item.attempt_no} · {item.status} · {item.failure_meaning} · {item.protocol} · {item.protocol === 'MQTT_TLS_3_1_1' ? 'PUBACK/连接状态' : 'HTTP'} {item.http_status ?? '无'} · 耗时 {item.duration_ms}ms</p>
              <p>目标 {item.endpoint_masked ?? '无'} · 源 {item.endpoint_origin ?? '无'} · 摘要 {item.endpoint_digest ?? '无'}</p>
              <p>{item.started_at} → {item.finished_at} · 未读取响应正文</p>
            </article>)}
            <Button variant="outline" onClick={() => setDeliveryId(null)}>关闭详情</Button>
          </> : null}
        </section>}

        {showEvents && <section aria-label="应用变更历史" style={{ marginTop: 16 }}>
          <h3>不可变变更历史</h3>
          {events.loading ? <Loading/> : events.error != null ? <ErrorNotice error={events.error} retry={events.refresh}/> : events.data ? (events.data.items.length === 0
            ? <Empty title="暂无变更记录"/>
            : events.data.items.map((item: ApplicationEvent) => <article key={item.id} style={{ ...wrap, borderTop: '1px solid #e3e8db', paddingTop: 8 }}>
              <p>{item.subject_type} · {item.action} · revision {item.revision_before ?? '无'} → {item.revision_after} · {item.actor_name ?? item.actor_id} · {item.created_at}</p>
              <p style={{ whiteSpace: 'pre-wrap' }}>{item.reason}</p>
            </article>)) : null}
          {events.data && events.data.total > events.data.size && <Pager page={events.data.page} size={events.data.size} total={events.data.total} onChange={setEventPage}/>}
        </section>}
      </> : null}
    </section>}
  </section>
}
