import { useState, type FormEvent } from 'react'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { ApiError, errorMessage } from '@/lib/api'
import { beginWriteIntent, clearWriteIntent, markWriteIntentUnknown, useWriteIntent } from '@/lib/write-intent'
import {
  deviceServicesApi, emptyArguments, fieldValue,
  type CommandState, type Driver, type LegacySourceConfig, type ParameterRead, type ServiceDefinition, type SourceBinding,
} from '@/lib/device-services'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Card, CardContent, CardHeader, CardTitle } from './ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Empty, ErrorNotice, Loading, Pager, RefreshButton } from './common'
import { OperationConfirmation, useMutationAction } from './identity-common'
import { PageHelp } from './page-tools'

export const stateLabel: Record<CommandState, string> = {
  QUEUED: '排队中（未外发）', IN_FLIGHT: '发送中', ACCEPTED: '厂商已接受（效果未证实）', REJECTED: '厂商拒绝',
  UNKNOWN: '结果未知（禁止自动重发）', SUCCEEDED: '文档回执成功', FAILED: '失败/未下发', CANCELLED: '已取消（未外发）',
}
const modeLabel: Record<string, string> = { PRODUCT_MODEL: '产品模型（标准HTTPS网关）', VENDOR_PLATFORM: '厂商平台（按驱动配置）', NONE: '未接通' }
const categoryLabel: Record<string, string> = { QUERY: '读取', CONFIGURATION: '参数下发', CONTROL: '控制' }
const sensitiveKey = /password|secret|token|credential|verification/i

export function DeviceServicesPanel({ deviceId, tenantId, compact = false }: { deviceId: string; tenantId?: string; compact?: boolean }) {
  const permissions = useSession(s => s.permissions)
  const audience = useSession(s => s.audience)
  const canRead = permissions.includes('device-services:read')
  const canExecute = permissions.includes('device-services:execute') || permissions.includes('device-services:engineering')
  const canQuery = permissions.includes('device-services:query') || permissions.includes('device-services:engineering')
  const canEngineering = permissions.includes('device-services:engineering')
  const canBind = permissions.includes('device:write')
  const capabilities = useResource(`service-capabilities:${tenantId ?? 'none'}:${deviceId}`, signal => deviceServicesApi.capabilities(deviceId, tenantId, signal), canRead)
  const parameters = useResource(`service-parameters:${tenantId ?? 'none'}:${deviceId}`, signal => deviceServicesApi.parameters(deviceId, tenantId, signal), canRead)
  const [page, setPage] = useState(1)
  const commands = useResource(`service-commands:${tenantId ?? 'none'}:${deviceId}:${page}`, signal => deviceServicesApi.commands(deviceId, page, tenantId, signal), canRead)
  const bindings = useResource(`source-bindings:${tenantId ?? 'none'}:${deviceId}`, signal => deviceServicesApi.sourceBindings(deviceId, tenantId, signal), canRead)
  const canDrivers = permissions.includes('integrations:read')
  const drivers = useResource('integration-drivers', signal => deviceServicesApi.drivers(signal), canRead && canDrivers)
  const legacySources = useResource('legacy-source-configs', signal => deviceServicesApi.legacySources(signal), canRead && canDrivers)
  const [selected, setSelected] = useState<ServiceDefinition | null>(null)
  const [openCommand, setOpenCommand] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  if (!canRead) return <Empty title="当前账户无设备服务权限">设备服务能力、参数快照与命令历史需要独立的 device-services:read 权限。</Empty>
  const refreshAll = () => { capabilities.refresh(); parameters.refresh(); commands.refresh(); bindings.refresh(); drivers.refresh(); legacySources.refresh() }
  const productDriver = drivers.data?.find(driver => driver.mode === 'PRODUCT_MODEL')
  const activeBinding = capabilities.data?.binding ?? null
  const vendorDrivers = drivers.data?.filter(driver => driver.mode === 'VENDOR_PLATFORM') ?? []
  return <div className="service-workspace" data-device-service-device={deviceId}>
    <div className="section-header"><h3>设备服务与参数</h3><RefreshButton onClick={refreshAll}/></div>
    {notice && <div role="status" className="notice">{notice}</div>}
    {capabilities.loading ? <Loading/> : capabilities.error != null ? <ErrorNotice error={capabilities.error} retry={capabilities.refresh}/> : capabilities.data && <>
      <section className="offline-policy" aria-label="接入驱动状态">
        <strong>{modeLabel[capabilities.data.mode] ?? capabilities.data.mode}</strong>
        <span>驱动：{capabilities.data.driver_key ?? '无'} · 配置状态 {capabilities.data.driver_configuration_code} · revision {capabilities.data.device_revision}</span>
        {capabilities.data.binding && <span className="mono">来源 {capabilities.data.binding.external_id} · 代次 {capabilities.data.binding.generation}{audience === 'management' ? ` · 账号指纹 ${capabilities.data.binding.account_fingerprint ?? '未知'}` : ''} · 待处理原始记录 {capabilities.data.binding.pending_raw_records}</span>}
        {capabilities.data.notes.map(note => <span key={note} className="muted-note">{note}</span>)}
        <span>已登记 {capabilities.data.services.length} 项服务 · 当前可下发 {capabilities.data.services.filter(service => service.dispatch_ready).length} 项（仍须有相应权限）</span>
      </section>
      <ParameterSnapshotView read={parameters.data} loading={parameters.loading} error={parameters.error} refresh={parameters.refresh}/>
      {capabilities.data.services.length === 0
        ? <Empty title="该设备暂无可用服务记录">{capabilities.data.mode === 'NONE'
          ? '当前未接通服务驱动。收到上报或显示在线，不代表支持远程查询或控制。'
          : '服务驱动尚未返回本型号的操作定义。已有参数快照和历史命令仍可在此查看；不会用现场数据或厂商受理冒充控制能力。'}</Empty>
        : <section aria-label="服务操作列表">{capabilities.data.services.map(service => <article key={service.service_key} className="service-card">
          <div className="service-head"><strong>{service.display_name}</strong>
            <span className="mono">{service.service_key}</span>
            <span>{categoryLabel[service.category] ?? service.category}</span>
            <span className={service.risk_level === 'ENGINEERING' ? 'badge-danger' : ''}>{service.risk_level === 'ENGINEERING' ? '高风险工程操作' : '常规操作'}</span>
            <span>{service.support_state === 'VERIFIED' ? '能力证据已登记' : '能力未验证'}</span>
          </div>
          {service.dispatch_blocked_code && <p className="notice">不可下发：{service.dispatch_blocked_code} · {service.dispatch_blocked_reason ?? '原因未确认'}</p>}
          {service.input_schema.fields.length > 0 && <p className="muted-note">参数：{service.input_schema.fields.map(field => `${field.label}${field.required ? '(必填)' : ''}${field.sensitive ? '(敏感)' : ''}`).join('、')}</p>}
          <div className="notification-actions">
            <Button type="button" variant="outline" size="sm" disabled={!canExecute || !service.dispatch_ready} onClick={() => { setSelected(service); setNotice('') }}>发起指令</Button>
            <PageHelp title="厂商接受不等于物理执行"><p>code 0 / ACCEPTED 仅表示厂商API接受；只有文档化关联回执或回读才显示成功。控制类操作的官方回执缺失时保持未知。</p></PageHelp>
          </div>
        </article>)}</section>}
    </>}
    <section aria-label="命令历史">
      <div className="section-header"><h3>命令与回执历史</h3></div>
      {commands.loading ? <Loading/> : commands.error != null ? <ErrorNotice error={commands.error} retry={commands.refresh}/> : commands.data ? (commands.data.items.length ? <>
        <Table><TableHeader><TableRow><TableHead>服务</TableHead><TableHead>状态</TableHead><TableHead>风险</TableHead><TableHead>厂商码/引用</TableHead><TableHead>创建</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>
          {commands.data.items.map(command => <TableRow key={command.id}>
            <TableCell><strong>{command.service_key}</strong><div className="mono muted-note">{command.id}</div></TableCell>
            <TableCell>{stateLabel[command.state]}<div className="muted-note">{command.next_action === 'RECONCILE' ? '需显式核对' : ''}</div></TableCell>
            <TableCell>{command.risk_level}</TableCell>
            <TableCell className="mono">{command.vendor_code ?? '—'}{command.accepted_reference ? ` · ${command.accepted_reference}` : ''}</TableCell>
            <TableCell>{command.created_at}</TableCell>
            <TableCell><div className="identity-row-actions">
              <Button type="button" variant="ghost" size="sm" onClick={() => setOpenCommand(command.id)}>查看</Button>
              {(command.state === 'ACCEPTED' || command.state === 'UNKNOWN') && canQuery && <ReconcileButton commandId={command.id} tenantId={tenantId} onDone={refreshAll}/>}
              {command.state === 'QUEUED' && canExecute && <CancelButton commandId={command.id} tenantId={tenantId} onDone={refreshAll}/>}
            </div></TableCell>
          </TableRow>)}
        </TableBody></Table>
        <Pager page={commands.data.page} size={commands.data.size} total={commands.data.total} onChange={setPage}/>
      </> : <Empty title="尚无命令记录">命令只在操作者显式提交后创建；打开页面不会唤醒设备或下发任何指令。</Empty>) : null}
    </section>
    <section aria-label="来源绑定历史">
      <div className="section-header"><h3>来源关联与解绑</h3></div>
      {bindings.loading ? <Loading/> : bindings.error != null ? <ErrorNotice error={bindings.error} retry={bindings.refresh}/> : bindings.data && (bindings.data.length ? <ol className="binding-history">{bindings.data.map(item => <li key={item.id}>
        <strong>{item.state === 'ACTIVE' ? '生效中' : '已解绑'} · {item.driver_key}</strong>
        <div className="mono">{item.external_id}{item.serial ? ` · ${item.serial}` : ''} · 代次 {item.generation}</div>
        <p>绑定 {item.bound_at}{item.released_at ? ` · 解绑 ${item.released_at}` : ''}{item.release_reason ? ` · 原因 ${item.release_reason}` : ''}</p>
        <p className="muted-note">待处理原始记录 {item.pending_raw_records}；解绑不删除历史原始记录</p>
      </li>)}</ol> : <Empty title="无来源关联">产品模型设备不需要来源绑定；历史设备如曾有云关联会显示在这里。</Empty>)}
      {canBind && activeBinding && <div className="notification-actions">
        <UnbindButton deviceId={deviceId} tenantId={tenantId} revision={capabilities.data?.device_revision ?? 0} binding={activeBinding} onDone={() => { refreshAll(); setNotice('平台来源关联已解除；厂商台账与终端均未删除，本地归档需另行执行。') }}/>
        {canEngineering && <CloudDeleteButton deviceId={deviceId} tenantId={tenantId} revision={capabilities.data?.device_revision ?? 0} binding={activeBinding} onDone={() => { refreshAll(); setNotice('厂商台账删除已入队；仅代表台账记录操作，不代表终端注销、NS注销或本地归档，需以核对结果为准。') }}/>}
      </div>}
      {canBind && activeBinding && <p className="muted-note">本地退役/归档是独立操作，只在解除来源关联后由设备归档流程执行；不会自动删除厂商台账记录。</p>}
    </section>
    <details className="device-filter-drawer"><summary>接入驱动配置与能力证据（工程）</summary>
      <div className="review-body">
        {!productDriver && !vendorDrivers.length && <p className="muted-note">未读取到驱动列表。</p>}
        {productDriver && <StandardDriverConfig driver={productDriver} canWrite={permissions.includes('integrations:write')} onSaved={() => { drivers.refresh(); refreshAll() }}/>}
        {canDrivers && (legacySources.data ?? []).map(source => <LegacySourceConfigCard key={source.driver_key} source={source} canWrite={permissions.includes('integrations:write')} onSaved={() => { legacySources.refresh(); drivers.refresh(); refreshAll() }}/>)}
        {canEngineering && vendorDrivers.filter(driver => !driver.configurable_origin_account).map(driver => <CapabilityEvidence driver={driver} key={driver.key} onSaved={refreshAll}/>)}
      </div>
    </details>
    <Sheet open={!!selected} onOpenChange={open => { if (!open) setSelected(null) }}><SheetContent className="review-sheet sm:max-w-xl">
      {selected && <CommandForm service={selected} deviceId={deviceId} tenantId={tenantId} deviceRevision={capabilities.data?.device_revision ?? 0} bindingId={capabilities.data?.binding?.id} onClose={() => setSelected(null)} onCreated={() => { setSelected(null); refreshAll(); setNotice('指令已持久化入队；外部发送结果以命令状态与回执核对为准。') }}/>}
    </SheetContent></Sheet>
    <Sheet open={!!openCommand} onOpenChange={open => { if (!open) setOpenCommand(null) }}><SheetContent className="review-sheet sm:max-w-2xl">
      {openCommand && <CommandDetailView commandId={openCommand} tenantId={tenantId} onClose={() => setOpenCommand(null)}/>}
    </SheetContent></Sheet>
  </div>
}

function ParameterSnapshotView({ read, loading, error, refresh }: { read: ParameterRead | undefined; loading: boolean; error: unknown; refresh: () => void }) {
  if (loading) return <Loading/>
  if (error != null) return <ErrorNotice error={error} retry={refresh}/>
  if (!read) return null
  const snapshot = read.snapshot
  const values = snapshot && Array.isArray(snapshot.values) ? snapshot.values as Array<Record<string, unknown>> : []
  return <section aria-label="参数快照"><div className="section-header"><h3>参数快照（缓存读取结果）</h3></div>
    <p className="muted-note">{read.note}</p>
    {!snapshot ? <p className="notice">尚无参数快照。只有显式读取指令完成后才会保存，页面打开不会唤醒设备。</p>
      : <><p className="muted-note">观察时间 {snapshot.observed_at} · 驱动 {snapshot.driver_key} · 来源代次 {snapshot.source_generation ?? '无'}</p>
        {values.length ? <Table><TableHeader><TableRow><TableHead>参数</TableHead><TableHead>值</TableHead></TableRow></TableHeader><TableBody>
          {values.map((item, index) => {
            const type = typeof item.paramType === 'string' ? item.paramType : `#${index + 1}`
            const label = typeof item.paramName === 'string' ? item.paramName : type
            const hidden = sensitiveKey.test(type) || sensitiveKey.test(label)
            return <TableRow key={`${type}:${index}`}><TableCell>{label}<div className="mono muted-note">{type}</div></TableCell><TableCell className={hidden ? 'mono' : ''}>{hidden ? '******' : String(item.value ?? '未提供')}</TableCell></TableRow>
          })}
        </TableBody></Table> : <pre className="json-preview">{JSON.stringify(snapshot.values, null, 2)}</pre>}</>}
  </section>
}
interface CommandPayload { service_key: string; arguments: Record<string, unknown>; expected_device_revision: number; capability_revision?: number; source_binding_id?: string; reason: string; risk_confirmed: boolean }

export function CommandForm({ service, deviceId, tenantId, deviceRevision, bindingId, initialValues, deviceName, onClose, onCreated }: { service: ServiceDefinition; deviceId: string; tenantId?: string; deviceRevision: number; bindingId?: string; initialValues?: Record<string, string | boolean>; deviceName?: string | null; onClose: () => void; onCreated: () => void }) {
  const [values, setValues] = useState<Record<string, string | boolean>>(() => ({ ...emptyArguments(service.input_schema.fields), ...initialValues }))
  const [reason, setReason] = useState('')
  const [riskConfirmed, setRiskConfirmed] = useState(false)
  const action = useMutationAction()
  const scope = `command:${deviceId}:${service.service_key}`
  const intent = useWriteIntent<CommandPayload>(scope)
  const [sending, setSending] = useState(false)
  async function send(payload: CommandPayload, key: string, done: () => void) {
    if (sending) return
    setSending(true); action.setError(undefined)
    try {
      await deviceServicesApi.createCommand(deviceId, payload, key, tenantId)
      clearWriteIntent(scope, key); done()
    } catch (error) {
      if (error instanceof ApiError && error.status !== 0 && error.status < 500) clearWriteIntent(scope, key)
      else markWriteIntentUnknown(scope, key)
      action.setError(error)
    } finally { setSending(false) }
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (intent) return
    if (!reason.trim()) { action.setError(new Error('请填写操作原因')); return }
    if (service.risk_level === 'ENGINEERING' && !riskConfirmed) { action.setError(new Error('高风险操作必须显式确认风险')); return }
    const args: Record<string, unknown> = {}
    for (const field of service.input_schema.fields) {
      const raw = values[field.key]
      const parsed = fieldValue(field, typeof raw === 'string' ? raw : '', raw === true)
      if (parsed === undefined) { if (field.required) { action.setError(new Error(`请填写${field.label}`)); return } continue }
      if ((field.type === 'integer' || field.type === 'number') && typeof parsed !== 'number') { action.setError(new Error(`${field.label}必须是数值`)); return }
      args[field.key] = parsed
    }
    if (!action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    try {
      const created = beginWriteIntent<CommandPayload>(scope, {
        service_key: service.service_key,
        arguments: args,
        expected_device_revision: deviceRevision,
        capability_revision: service.revision || undefined,
        source_binding_id: bindingId,
        reason: reason.trim(),
        risk_confirmed: riskConfirmed,
      }, `${service.service_key} · ${service.display_name}`)
      void send(created.payload, created.key, () => { onCreated() })
    } catch (error) { action.setError(error) }
  }
  if (intent) return <>
    <SheetHeader className="review-header"><div className="eyebrow">SERVICE COMMAND · 未确认意图</div><SheetTitle>{service.display_name}</SheetTitle>
      <SheetDescription>原命令可能已送达服务端，但结果未确认。重试使用原幂等键与原载荷，不会自动创建第二条物理命令。</SheetDescription></SheetHeader>
    <div className="review-body">
      <div role="status" className="notice">未确认意图：{intent.label} · 幂等键 {intent.key.slice(0, 8)}… · 状态 {intent.state === 'UNKNOWN' ? '结果未知' : '请求中/未确认'}。导航或关闭不会丢失该意图。</div>
      <p className="muted-note">如服务端已创建命令，重试会返回原命令记录；如未创建，只会创建这一条。不会自动重发。</p>
      {action.error != null && <ErrorNotice error={action.error}/>}
      <div className="identity-form-actions"><Button type="button" disabled={sending} onClick={() => void send(intent.payload, intent.key, () => { onCreated() })}>{sending ? '正在重试…' : '使用原幂等键重试原命令'}</Button><Button type="button" variant="outline" onClick={onClose}>返回</Button></div>
    </div>
  </>
  return <>
    <SheetHeader className="review-header"><div className="eyebrow">SERVICE COMMAND · 显式下发</div><SheetTitle>{service.display_name}</SheetTitle>
      <SheetDescription>命令先持久化为QUEUED，再由受控工作进程外发。厂商接受、执行中、成功、失败与未知严格区分；崩溃或未知结果不会自动重发。</SheetDescription></SheetHeader>
    <div className="review-body"><form className="form-stack" onSubmit={submit}>
      {!action.confirming ? <>
        <div className="identity-readonly mono">{service.service_key} · {service.risk_level} · revision {service.revision} · 设备revision {deviceRevision}</div>
        {deviceName && <p>目标设备：{deviceName}</p>}
        {service.input_schema.fields.length === 0 ? <p className="muted-note">该操作没有输入参数。</p> : service.input_schema.fields.map(field => <div className="field" key={field.key}>
          <Label htmlFor={`svc-${field.key}`}>{field.label}{field.required ? '（必填）' : ''}{field.sensitive ? '（敏感，回显将掩码）' : ''}</Label>
          {field.type === 'boolean'
            ? <label className="confirmation"><input type="checkbox" checked={values[field.key] === true} onChange={event => { action.touch(); setValues(current => ({ ...current, [field.key]: event.target.checked })) }}/>{field.label}</label>
            : <Input id={`svc-${field.key}`} type={field.sensitive ? 'password' : undefined} autoComplete={field.sensitive ? 'off' : undefined} inputMode={field.type === 'string' ? undefined : 'numeric'} value={typeof values[field.key] === 'string' ? values[field.key] as string : ''} onChange={event => { action.touch(); setValues(current => ({ ...current, [field.key]: event.target.value })) }}/>}
          {(field.minimum !== null || field.maximum !== null || field.maxLength !== null || field.enum.length > 0) && <p className="muted-note">
            {field.enum.length > 0 ? `允许值：${field.enum.join(' / ')}` : `范围：${field.minimum ?? '—'} ~ ${field.maximum ?? '—'}${field.maxLength ? ` · 最大长度 ${field.maxLength}` : ''}`}
          </p>}
        </div>)}
        <div className="field"><Label htmlFor="svc-reason">操作原因</Label><textarea id="svc-reason" required rows={3} value={reason} onChange={event => { action.touch(); setReason(event.target.value) }}/></div>
        {service.risk_level === 'ENGINEERING' && <label className="confirmation"><input type="checkbox" checked={riskConfirmed} onChange={event => { action.touch(); setRiskConfirmed(event.target.checked) }}/>我确认已核对设备、参数与能力证据，理解该操作可能影响真实设备</label>}
        <div className="notice">这不是通知屏蔽或本地状态修改。v3参数写入结果为 0失败 / 1成功 / 2执行中 / 3未下发；控制类操作若无官方关联回执将保持未知。</div>
        {action.error != null && <><ErrorNotice error={action.error}/><p className="muted-note">{errorMessage(action.error)}</p></>}
        <Button type="submit">核对并继续</Button>
      </> : <OperationConfirmation action={action} title="二次确认 · 下发设备指令" submitLabel="确认入队" refresh={onClose} warning="命令入队后不能修改参数；仅未外发时可撤销。未知结果必须显式核对，禁止重复提交。">
        <p>服务：{service.display_name} · {service.service_key}</p><p>设备：{deviceName ?? deviceId} · revision {deviceRevision}</p><p>参数：{JSON.stringify(Object.fromEntries(service.input_schema.fields.filter(field => values[field.key] !== '' && values[field.key] !== undefined).map(field => [field.label, field.sensitive ? '******' : values[field.key]])))}</p><p>原因：{reason}</p>
      </OperationConfirmation>}
    </form></div>
  </>
}

export function CommandDetailView({ commandId, tenantId, onClose }: { commandId: string; tenantId?: string; onClose: () => void }) {
  const detail = useResource(`service-command:${tenantId ?? 'none'}:${commandId}`, signal => deviceServicesApi.command(commandId, tenantId, signal))
  return <>
    <SheetHeader className="review-header"><SheetTitle>命令详情</SheetTitle><SheetDescription>快照与参数不可变；未知结果保留为未知，不替代为成功。</SheetDescription></SheetHeader>
    <div className="review-body">
      {detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : detail.data && <>
        <div className="identity-readonly mono">{detail.data.command.id}<br/>hash {detail.data.payload_sha256}</div>
        <dl className="detail-list">
          <div><dt>状态</dt><dd>{stateLabel[detail.data.command.state]} · revision {detail.data.command.status_version}</dd></div>
          <div><dt>模式/驱动</dt><dd>{modeLabel[detail.data.command.driver_mode] ?? detail.data.command.driver_mode} · {detail.data.command.driver_key}</dd></div>
          <div><dt>厂商结果</dt><dd className="mono">{detail.data.command.vendor_code ?? '未提供'}{detail.data.command.accepted_reference ? ` · 引用 ${detail.data.command.accepted_reference}` : ''}</dd></div>
          <div><dt>原因</dt><dd>{detail.data.reason}</dd></div>
          <div><dt>操作人</dt><dd>{detail.data.actor_id ?? '未提供'}</dd></div>
        </dl>
        <h3>参数（敏感字段掩码）</h3><pre className="json-preview">{JSON.stringify(detail.data.arguments, null, 2)}</pre>
        <h3>不可变绑定快照</h3><pre className="json-preview">{JSON.stringify(detail.data.snapshot, null, 2)}</pre>
        <h3>结果证据</h3>{detail.data.result ? <pre className="json-preview">{JSON.stringify(detail.data.result, null, 2)}</pre> : <p className="muted-note">尚无结果证据；未知不等于成功。</p>}
        <h3>尝试记录</h3><ol className="binding-history">{detail.data.attempts.map(attempt => <li key={attempt.id}>
          <strong>#{attempt.attempt_no} · {attempt.phase} · {attempt.outcome}</strong>
          <div className="mono">{attempt.vendor_code ?? '无厂商码'} · {attempt.started_at} → {attempt.finished_at ?? '未结束'}</div>
          {attempt.detail != null && <pre className="json-preview">{JSON.stringify(attempt.detail, null, 2)}</pre>}
        </li>)}</ol>
        <Button type="button" variant="outline" onClick={onClose}>关闭</Button>
      </>}
    </div>
  </>
}

function CancelButton({ commandId, tenantId, onDone }: { commandId: string; tenantId?: string; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('请填写撤销原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => deviceServicesApi.cancel(commandId, { reason: reason.trim() }, key, tenantId, signal), () => { onDone(); setReason('') })
  }
  return <form className="inline-action" onSubmit={submit}>
    {!action.confirming ? <Button type="submit" variant="ghost" size="sm">撤销（未外发）</Button>
      : <><Input aria-label="撤销原因" placeholder="撤销原因" value={reason} onChange={event => { action.touch(); setReason(event.target.value) }}/><Button type="submit" size="sm" disabled={action.busy}>确认撤销</Button><Button type="button" size="sm" variant="ghost" onClick={() => action.setConfirming(false)}>取消</Button></>}
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}

export function ReconcileButton({ commandId, tenantId, onDone }: { commandId: string; tenantId?: string; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('请填写核对原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => deviceServicesApi.reconcile(commandId, { reason: reason.trim() }, key, tenantId, signal), () => { onDone(); setReason('') })
  }
  return <form className="inline-action" onSubmit={submit}>
    {!action.confirming ? <Button type="submit" variant="ghost" size="sm">核对厂商结果</Button>
      : <><Input aria-label="核对原因" placeholder="核对原因" value={reason} onChange={event => { action.touch(); setReason(event.target.value) }}/><Button type="submit" size="sm" disabled={action.busy}>确认核对</Button><Button type="button" size="sm" variant="ghost" onClick={() => action.setConfirming(false)}>取消</Button></>}
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}

function UnbindButton({ deviceId, tenantId, revision, binding, onDone }: { deviceId: string; tenantId?: string; revision: number; binding: SourceBinding; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim()) { action.setError(new Error('请填写解绑原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => deviceServicesApi.unbind(deviceId, { expected_device_revision: revision, binding_id: binding.id, reason: reason.trim() }, key, tenantId, signal), onDone)
  }
  return <form className="inline-action" onSubmit={submit}>
    {!action.confirming ? <Button type="submit" variant="ghost" size="sm">解除平台来源关联（不删除厂商台账）</Button>
      : <><Input aria-label="解绑原因" placeholder="解绑原因" value={reason} onChange={event => { action.touch(); setReason(event.target.value) }}/><Button type="submit" size="sm" disabled={action.busy}>确认解除关联</Button><Button type="button" size="sm" variant="ghost" onClick={() => action.setConfirming(false)}>取消</Button></>}
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}

function CloudDeleteButton({ deviceId, tenantId, revision, binding, onDone }: { deviceId: string; tenantId?: string; revision: number; binding: SourceBinding; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim() || !confirmed) { action.setError(new Error('请填写原因并确认风险')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    void action.run((key, signal) => deviceServicesApi.cloudDelete(deviceId, { expected_device_revision: revision, external_device_id: binding.external_id, reason: reason.trim(), risk_confirmed: true }, key, tenantId, signal), onDone)
  }
  return <form className="inline-action" onSubmit={submit}>
    {!action.confirming ? <Button type="submit" variant="ghost" size="sm">删除厂商台账记录（不注销终端）</Button>
      : <><Input aria-label="厂商台账删除原因" placeholder="删除原因" value={reason} onChange={event => { action.touch(); setReason(event.target.value) }}/><label className="confirmation"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)}/>我确认只删除厂商台账记录，不代表终端注销、NS注销或本地归档</label><Button type="submit" size="sm" disabled={action.busy}>确认入队</Button><Button type="button" size="sm" variant="ghost" onClick={() => action.setConfirming(false)}>取消</Button></>}
    {action.error != null && <span className="muted-note">{errorMessage(action.error)}</span>}
  </form>
}

interface DriverConfigPayload { endpoint: string; token: string; enabled: boolean; expected_revision: number }

function StandardDriverConfig({ driver, canWrite, onSaved }: { driver: Driver; canWrite: boolean; onSaved: () => void }) {
  const [endpoint, setEndpoint] = useState(driver.endpoint ?? '')
  const [token, setToken] = useState('')
  const [enabled, setEnabled] = useState(driver.enabled)
  const action = useMutationAction()
  const scope = `driver-config:${driver.key}`
  const intent = useWriteIntent<DriverConfigPayload>(scope)
  const [sending, setSending] = useState(false)
  async function send(payload: DriverConfigPayload, key: string, done: () => void) {
    if (sending) return
    setSending(true); action.setError(undefined)
    try {
      await deviceServicesApi.saveDriverConfig(driver.key, payload, key)
      clearWriteIntent(scope, key); done()
    } catch (error) {
      if (error instanceof ApiError && error.status !== 0 && error.status < 500) clearWriteIntent(scope, key)
      else markWriteIntentUnknown(scope, key)
      action.setError(error)
    } finally { setSending(false) }
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || intent) return
    if (!endpoint.trim()) { action.setError(new Error('请填写已审批的HTTPS网关地址')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    try {
      const created = beginWriteIntent<DriverConfigPayload>(scope, { endpoint: endpoint.trim(), token, enabled, expected_revision: driver.revision }, `网关配置 ${driver.key}`)
      void send(created.payload, created.key, () => { setToken(''); onSaved() })
    } catch (error) { action.setError(error) }
  }
  if (intent) return <Card className="service-card"><CardHeader><CardTitle>标准HTTPS产品网关 · 未确认配置</CardTitle></CardHeader><CardContent>
    <div role="status" className="notice">网关配置请求可能已送达，但结果未确认。重试使用原幂等键与原内容，不会静默切换账号或重复保存。</div>
        {action.error != null && <ErrorNotice error={action.error}/>}
    <div className="identity-form-actions"><Button type="button" disabled={sending} onClick={() => void send(intent.payload, intent.key, onSaved)}>使用原幂等键重试</Button></div>
  </CardContent></Card>
  return <Card className="service-card"><CardHeader><CardTitle>标准HTTPS产品网关 · {driver.display_name}</CardTitle></CardHeader><CardContent>
    <p className="muted-note">配置状态 {driver.configuration_code} · revision {driver.revision} · 令牌 {driver.token_masked ?? '未配置'} · 账号指纹 {driver.account_fingerprint ?? '未配置'}</p>
    <p className="muted-note">仅允许部署白名单内的443端口HTTPS主机；不跟随重定向、不自动重试，也不接受请求级任意URL/凭据。</p>
    <form className="form-stack" onSubmit={submit}>
      <div className="field"><Label htmlFor="driver-endpoint">网关HTTPS地址</Label><Input id="driver-endpoint" value={endpoint} onChange={event => { action.touch(); setEndpoint(event.target.value) }} disabled={!canWrite}/></div>
      <div className="field"><Label htmlFor="driver-token">访问令牌（留空保留）</Label><Input id="driver-token" type="password" autoComplete="off" value={token} onChange={event => { action.touch(); setToken(event.target.value) }} disabled={!canWrite}/></div>
      <label className="confirmation"><input type="checkbox" checked={enabled} onChange={event => { action.touch(); setEnabled(event.target.checked) }} disabled={!canWrite}/>启用真实外发</label>
      {!action.confirming ? <Button type="submit" variant="outline" disabled={!canWrite}>保存网关配置</Button>
        : <OperationConfirmation action={action} title="二次确认 · 网关配置" submitLabel="确认保存" refresh={onSaved} warning="保存后新命令使用新配置；已入队的旧命令沿用其不可变账号代次，不会静默切换。"><p>endpoint：{endpoint}</p><p>启用：{enabled ? '是' : '否'} · expected revision {driver.revision}</p></OperationConfirmation>}
      {action.error != null && <ErrorNotice error={action.error}/>}
    </form>
  </CardContent></Card>
}

interface LegacySourcePayload { origin: string; username: string; password: string; allow_insecure_http: boolean; enabled: boolean; expected_revision: number }

function LegacySourceConfigCard({ source, canWrite, onSaved }: { source: LegacySourceConfig; canWrite: boolean; onSaved: () => void }) {
  const [origin, setOrigin] = useState(source.origin ?? '')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [allowInsecureHttp, setAllowInsecureHttp] = useState(source.allow_insecure_http)
  const [enabled, setEnabled] = useState(source.enabled)
  const httpOrigin = /^http:\/\//i.test(origin.trim())
  const action = useMutationAction()
  const scope = `legacy-source:${source.driver_key}`
  const intent = useWriteIntent<LegacySourcePayload>(scope)
  const [sending, setSending] = useState(false)
  async function send(payload: LegacySourcePayload, key: string, done: () => void) {
    if (sending) return
    setSending(true); action.setError(undefined)
    try {
      await deviceServicesApi.saveLegacySource(source.driver_key, payload, key)
      clearWriteIntent(scope, key); done()
    } catch (error) {
      if (error instanceof ApiError && error.status !== 0 && error.status < 500) clearWriteIntent(scope, key)
      else markWriteIntentUnknown(scope, key)
      action.setError(error)
    } finally { setSending(false) }
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || intent) return
    if (!origin.trim()) { action.setError(new Error('请填写部署审批的旧平台来源地址')); return }
    if (!username.trim()) { action.setError(new Error('请填写旧平台管理账号')); return }
    if (!source.credentials_stored && !password) { action.setError(new Error('首次保存必须填写旧平台管理密码')); return }
    if (httpOrigin && !allowInsecureHttp) { action.setError(new Error('明文HTTP来源必须在表单中显式确认风险；HTTPS来源不能携带该确认')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    try {
      const created = beginWriteIntent<LegacySourcePayload>(scope, {
        origin: origin.trim(), username: username.trim(), password, allow_insecure_http: httpOrigin && allowInsecureHttp,
        enabled, expected_revision: source.revision,
      }, `旧平台来源 ${source.driver_key}`)
      void send(created.payload, created.key, () => { setPassword(''); onSaved() })
    } catch (error) { action.setError(error) }
  }
  if (intent) return <Card className="service-card"><CardHeader><CardTitle>{source.display_name} · 未确认配置</CardTitle></CardHeader><CardContent>
    <div role="status" className="notice">来源配置请求可能已送达；重试使用原幂等键与原内容，不会静默切换账号或协议。</div>
        {action.error != null && <ErrorNotice error={action.error}/>}
    <div className="identity-form-actions"><Button type="button" disabled={sending} onClick={() => void send(intent.payload, intent.key, onSaved)}>使用原幂等键重试</Button></div>
  </CardContent></Card>
  return <Card className="service-card"><CardHeader><CardTitle>{source.display_name} · 旧平台来源与账号</CardTitle></CardHeader><CardContent>
    <p className="muted-note">配置状态 {source.configured ? 'READY' : '未就绪'} · revision {source.revision} · 地址 {source.origin ?? '未配置'} · 账号 {source.username_masked ?? '未配置'} · 账号指纹 {source.account_fingerprint ?? '未配置'}</p>
    <p className="muted-note">账号与密码只写不读；密码以应用主密钥加密保存。地址必须在部署白名单 APP_LEGACY_SOURCE_APPROVED_ORIGINS 内；明文HTTP必须显式确认风险，系统不会把HTTPS降级为HTTP。启用只影响台账读写/下发，不代表NS开通或终端注销。</p>
    <form className="form-stack" onSubmit={submit}>
      <div className="field"><Label htmlFor={`legacy-origin-${source.driver_key}`}>旧平台来源（协议+主机+端口）</Label><Input id={`legacy-origin-${source.driver_key}`} value={origin} onChange={event => { const value = event.target.value; action.touch(); setOrigin(value); if (/^https:\/\//i.test(value.trim())) setAllowInsecureHttp(false) }} disabled={!canWrite} placeholder="http://host:port 或 https://host"/><p className="muted-note">地址必须与部署白名单 APP_LEGACY_SOURCE_APPROVED_ORIGINS 完全一致；未配置白名单时服务端拒绝保存。</p></div>
      <div className="field"><Label htmlFor={`legacy-username-${source.driver_key}`}>管理账号（每次保存需重新填写）</Label><Input id={`legacy-username-${source.driver_key}`} autoComplete="off" value={username} onChange={event => { action.touch(); setUsername(event.target.value) }} disabled={!canWrite}/></div>
      <div className="field"><Label htmlFor={`legacy-password-${source.driver_key}`}>管理密码（留空保留原密码，加密保存）</Label><Input id={`legacy-password-${source.driver_key}`} type="password" autoComplete="off" value={password} onChange={event => { action.touch(); setPassword(event.target.value) }} disabled={!canWrite}/></div>
      <label className="confirmation"><input type="checkbox" checked={httpOrigin && allowInsecureHttp} onChange={event => { action.touch(); setAllowInsecureHttp(event.target.checked) }} disabled={!canWrite || !httpOrigin}/>{httpOrigin ? '我确认该旧平台来源使用明文HTTP，并理解凭据/报文暴露风险' : 'HTTPS来源不需要明文HTTP风险确认；系统不会把HTTPS降级为HTTP'}</label>
      <label className="confirmation"><input type="checkbox" checked={enabled} onChange={event => { action.touch(); setEnabled(event.target.checked) }} disabled={!canWrite}/>启用真实外发</label>
      {!action.confirming ? <Button type="submit" variant="outline" disabled={!canWrite}>保存旧平台来源配置</Button>
        : <OperationConfirmation action={action} title="二次确认 · 旧平台来源配置" submitLabel="确认保存" refresh={onSaved} warning="保存只更新平台侧接入配置；不会立即下发设备命令。旧命令与已建登记沿用其不可变账号指纹，不会静默切换。">
          <p>来源：{origin}</p><p>账号：{username} · 启用：{enabled ? '是' : '否'} · 明文HTTP确认：{httpOrigin && allowInsecureHttp ? '是' : '否'}</p><p>expected revision {source.revision}</p>
        </OperationConfirmation>}
      {action.error != null && <ErrorNotice error={action.error}/>}
    </form>
  </CardContent></Card>
}

interface EvidencePayload { model: string; service_key: string; evidence: string }

function CapabilityEvidence({ driver, onSaved }: { driver: Driver; onSaved: () => void }) {
  const [model, setModel] = useState('800235')
  const [serviceKey, setServiceKey] = useState(driver.services[0]?.service_key ?? '')
  const [evidence, setEvidence] = useState('')
  const action = useMutationAction()
  const scope = `capability-evidence:${driver.key}`
  const intent = useWriteIntent<EvidencePayload>(scope)
  const [sending, setSending] = useState(false)
  async function send(payload: EvidencePayload, key: string, done: () => void) {
    if (sending) return
    setSending(true); action.setError(undefined)
    try {
      await deviceServicesApi.addCapabilityEvidence(driver.key, payload, key)
      clearWriteIntent(scope, key); done()
    } catch (error) {
      if (error instanceof ApiError && error.status !== 0 && error.status < 500) clearWriteIntent(scope, key)
      else markWriteIntentUnknown(scope, key)
      action.setError(error)
    } finally { setSending(false) }
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (intent) return
    if (!model.trim() || !serviceKey || !evidence.trim()) { action.setError(new Error('请填写型号、操作与证据依据')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    try {
      const created = beginWriteIntent<EvidencePayload>(scope, { model: model.trim(), service_key: serviceKey, evidence: evidence.trim() }, `能力证据 ${driver.key}/${model}/${serviceKey}`)
      void send(created.payload, created.key, () => { setEvidence(''); onSaved() })
    } catch (error) { action.setError(error) }
  }
  if (intent) return <Card className="service-card"><CardHeader><CardTitle>能力证据 · 未确认登记</CardTitle></CardHeader><CardContent>
    <div role="status" className="notice">证据登记请求可能已送达，但结果未确认。重试使用原幂等键与原内容。</div>
        {action.error != null && <ErrorNotice error={action.error}/>}
    <div className="identity-form-actions"><Button type="button" disabled={sending} onClick={() => void send(intent.payload, intent.key, onSaved)}>使用原幂等键重试</Button></div>
  </CardContent></Card>
  return <Card className="service-card"><CardHeader><CardTitle>能力证据 · {driver.display_name}</CardTitle></CardHeader><CardContent>
    <p className="muted-note">官方未发布该型号能力矩阵。登记证据只说明操作者已验证，不改变“厂商接受≠物理执行”的语义；没有证据时对应操作保持UNVERIFIED且不可下发。</p>
    <form className="form-stack" onSubmit={submit}>
      <div className="field"><Label htmlFor={`evidence-model-${driver.key}`}>型号</Label><Input id={`evidence-model-${driver.key}`} value={model} onChange={event => { action.touch(); setModel(event.target.value) }}/></div>
      <div className="field"><Label htmlFor={`evidence-service-${driver.key}`}>服务操作</Label><select id={`evidence-service-${driver.key}`} className="native-select" value={serviceKey} onChange={event => { action.touch(); setServiceKey(event.target.value) }}>{driver.services.map(service => <option key={service.service_key} value={service.service_key}>{service.display_name} · {service.service_key}</option>)}</select></div>
      <div className="field"><Label htmlFor={`evidence-text-${driver.key}`}>证据依据</Label><textarea id={`evidence-text-${driver.key}`} rows={3} value={evidence} onChange={event => { action.touch(); setEvidence(event.target.value) }}/></div>
      {!action.confirming ? <Button type="submit" variant="outline">登记能力证据</Button>
        : <OperationConfirmation action={action} title="二次确认 · 能力证据" submitLabel="确认登记" refresh={onSaved} warning="登记后该型号对应操作可被下发；请确保证据真实可追溯。"><p>{driver.key} · {model} · {serviceKey}</p><p>{evidence}</p></OperationConfirmation>}
      {action.error != null && <ErrorNotice error={action.error}/>}
    </form>
  </CardContent></Card>
}
