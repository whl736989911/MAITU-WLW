import { useEffect, useState, type FormEvent } from 'react'
import { request } from '@/lib/api'
import { useDomainWrite, type DomainWrite } from '@/lib/domain-write'
import { useResource } from '@/lib/use-resource'
import {
  PLAYBACK_LABELS, VIDEO_KINDS, VIDEO_PROTOCOLS, videoApi, type VideoResource,
} from '@/lib/video'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { DeviceSelector, type AssetReference } from '@/components/asset-picker'
import { VideoMediaPlayer } from '@/components/video-media-player'
import { VideoCapturePanel } from '@/components/video-capture-panel'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice, Loading, Pager, RefreshButton } from '@/components/common'
import { useSession } from '@/lib/store'
import { CustomerDeviceBack, CustomerDeviceCard, CustomerDeviceSearch, CustomerDeviceTable, CustomerDeviceTools } from '@/components/customer-device-ledger'
import { CustomerSystemTabs } from '@/components/customer-records'

function WriteFields({ write, label }: { write: DomainWrite; label: string }) {
  return <>
    <div className="field"><Label htmlFor={`${label}-reason`}>变更原因</Label><Input id={`${label}-reason`} value={write.reason} maxLength={1000} onChange={event => write.setReason(event.target.value)}/></div>
    {write.error != null && <ErrorNotice error={write.error} retry={() => write.setError(undefined)}/>}
  </>
}

export function VideoResourcesPanel({ tenantId, canWrite, canPlay, canReadDevices, canReadPlaces }: {
  tenantId: string; canWrite: boolean; canPlay: boolean; canReadDevices: boolean; canReadPlaces: boolean
}) {
  const legacy = useSession(s => s.audience === 'customer')
  const [size, setSize] = useState(10)
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [draft, setDraft] = useState(''), [includeArchived, setIncludeArchived] = useState(false)
  const list = useResource(`video-resources:${tenantId}:${page}:${keyword}:${includeArchived}:${legacy ? size : ''}`,
    signal => videoApi.list(tenantId, page, { keyword, include_archived: includeArchived, size: legacy ? size : undefined }, signal))
  const [selectedId, setSelectedId] = useState('')
  const detail = useResource(`video-detail:${tenantId}:${selectedId}`, signal => videoApi.detail(tenantId, selectedId, signal), !!selectedId)
  const write = useDomainWrite()
  const [createOpen, setCreateOpen] = useState(false), [name, setName] = useState(''), [kind, setKind] = useState<'CAMERA' | 'VIDEO_BOX' | 'NVR'>('CAMERA'), [protocol, setProtocol] = useState<'HTTPS_MP4' | 'HTTPS_HLS' | 'HTTPS_JPEG' | 'RTSP'>('HTTPS_MP4')
  const [endpointOpen, setEndpointOpen] = useState(false), [endpointUrl, setEndpointUrl] = useState(''), [endpointUser, setEndpointUser] = useState(''), [endpointPassword, setEndpointPassword] = useState('')
  const [credentialsOpen, setCredentialsOpen] = useState(false), [credentialUser, setCredentialUser] = useState(''), [credentialPassword, setCredentialPassword] = useState('')
  const [associateOpen, setAssociateOpen] = useState(false), [associateDevice, setAssociateDevice] = useState<AssetReference | undefined>()
  const [revokeAssociation, setRevokeAssociation] = useState<string | null>(null), [revokeReason, setRevokeReason] = useState('')
  const [archive, setArchive] = useState<VideoResource | null>(null)
  const [gatewayOpen, setGatewayOpen] = useState(false), [gatewayUrl, setGatewayUrl] = useState('')
  const selected = detail.data ?? null

  useEffect(() => { setPage(1) }, [tenantId, keyword, includeArchived])
  useEffect(() => {
    if (!list.data) return
    if (selectedId && !list.data.items.some(item => item.id === selectedId)) setSelectedId('')
  }, [list.data, selectedId])

  const reload = () => { list.refresh(); if (selectedId) detail.refresh() }
  function search(event: FormEvent) { event.preventDefault(); setKeyword(draft.trim()); setPage(1) }
  const rows = list.data?.items ?? []

  function submitCreate(event: FormEvent) {
    event.preventDefault()
    const body = { name: name.trim(), resource_kind: kind, protocol, reason: write.reason.trim() }
    write.submit(['video.create', body], key => request('/management/video-resources', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setCreateOpen(false); setName(''); reload() })
  }
  function submitEndpoint(event: FormEvent) {
    event.preventDefault()
    if (!selected) return
    const body = { expected_revision: selected.resource.revision, endpoint_url: endpointUrl, username: endpointUser || null, password: endpointUser ? endpointPassword : null, reason: write.reason.trim() }
    write.submit(['video.endpoint', selected.resource.id, body], key => request(`/management/video-resources/${encodeURIComponent(selected.resource.id)}/endpoint`, { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setEndpointOpen(false); setEndpointUrl(''); setEndpointUser(''); setEndpointPassword(''); reload() })
  }
  function submitCredentials(event: FormEvent) {
    event.preventDefault()
    if (!selected) return
    const body = { expected_revision: selected.resource.revision, username: credentialUser, password: credentialPassword, reason: write.reason.trim() }
    write.submit(['video.credentials', selected.resource.id, body], key => request(`/management/video-resources/${encodeURIComponent(selected.resource.id)}/credentials`, { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setCredentialsOpen(false); setCredentialUser(''); setCredentialPassword(''); reload() })
  }
  function submitAssociation(event: FormEvent) {
    event.preventDefault()
    if (!selected || !associateDevice) return
    const body = { device_id: associateDevice.id, reason: write.reason.trim() }
    write.submit(['video.associate', selected.resource.id, body], key => request(`/management/video-resources/${encodeURIComponent(selected.resource.id)}/associations`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setAssociateOpen(false); setAssociateDevice(undefined); reload() })
  }
  function mutate(kindName: 'enabled' | 'archive' | 'endpoint-clear' | 'credentials-clear', body: Record<string, unknown>) {
    if (!selected) return
    const suffix = kindName === 'endpoint-clear' ? '/endpoint/clear' : kindName === 'credentials-clear' ? '/credentials/clear' : `/${kindName}`
    write.submit(['video', kindName, selected.resource.id, body], key => request(`/management/video-resources/${encodeURIComponent(selected.resource.id)}${suffix}`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setArchive(null); reload() })
  }
  function submitRevoke(event: FormEvent) {
    event.preventDefault()
    if (!selected || !revokeAssociation) return
    const association = selected.associations.find(item => item.id === revokeAssociation)
    if (!association) return
    const body = { expected_revision: association.revision, revoke_reason: revokeReason.trim(), reason: write.reason.trim() }
    write.submit(['video.association.revoke', association.id, body], key => request(`/management/video-resources/${encodeURIComponent(selected.resource.id)}/associations/${encodeURIComponent(association.id)}/revoke`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setRevokeAssociation(null); setRevokeReason(''); reload() })
  }

  function submitGateway(event: FormEvent) {
    event.preventDefault()
    if (!selected) return
    const body = { expected_revision: selected.resource.revision, gateway_url: gatewayUrl, reason: write.reason.trim() }
    write.submit(['video.gateway', selected.resource.id, body], key => request(`/management/video-resources/${encodeURIComponent(selected.resource.id)}/gateway`, { tenantScoped: true, tenantId, method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setGatewayOpen(false); setGatewayUrl(''); reload() })
  }

  return <>
    {legacy ? <section className="legacy-device-ledger" aria-label="视频设备信息">
      {selectedId ? <div className="legacy-device-detail">
        <CustomerDeviceBack onClick={() => setSelectedId('')}/>
        {detail.loading ? <Loading/> : detail.error != null ? <ErrorNotice error={detail.error} retry={detail.refresh}/> : selected &&
          <CustomerDeviceCard title="视频设备信息"><dl className="legacy-device-fields">
            <div><dt>设备名称</dt><dd>{selected.resource.name}</dd></div>
            <div><dt>设备类型</dt><dd>{selected.resource.resource_kind}</dd></div>
            <div><dt>接入协议</dt><dd>{selected.resource.protocol}</dd></div>
          </dl></CustomerDeviceCard>}
      </div> : <>
        <CustomerSystemTabs/>
        <CustomerDeviceSearch fields={[['keyword', '设备名称 / 端点主机']]} draft={draft} onDraft={setDraft}
          onSearch={(_field, value) => { setKeyword(value); setPage(1) }} onReset={() => { setDraft(''); setKeyword(''); setPage(1) }}>
          <CustomerDeviceTools kind="视频设备"/>
        </CustomerDeviceSearch>
        <CustomerDeviceTable page={page} size={size} list={{ ...list, data: list.data && { ...list.data,
          items: rows.map(row => ({ id: row.id, name: row.name, status: row.enabled ? '已启用' : '未启用' })) } }}
          open={row => setSelectedId(row.id)} setPage={setPage} setSize={value => { setPage(1); setSize(value) }}/>
      </>}
    </section> : <>
    <div className="list-toolbar"><h2>视频资源</h2><div className="row-actions">
      <RefreshButton onClick={reload}/>
      {canWrite && <DataTransferActions kind="VIDEO_RESOURCES" tenantId={tenantId} filters={{ unit_ids: tenantId, place_ids: '' }}/>}
    </div></div>
    <form className="search-form" onSubmit={search}>
      <Input aria-label="搜索视频资源" value={draft} maxLength={100} onChange={event => setDraft(event.target.value)} placeholder="名称 / 端点主机"/>
      <Button type="submit" variant="outline">查询</Button>
      {canWrite && <label className="notification-checkbox"><input type="checkbox" checked={includeArchived} onChange={event => setIncludeArchived(event.target.checked)}/>含已归档</label>}
      {canWrite && <Button type="button" onClick={() => { setCreateOpen(true); write.reset() }}>登记视频资源</Button>}
    </form>
    {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : rows.length === 0 ? <Empty title="没有视频资源">未登记任何视频资源；不会生成演示摄像头或预览。</Empty> : <>
      <div className="table-wrap"><table><thead><tr><th>名称</th><th>类型/协议</th><th>播放能力</th><th>端点</th><th>凭据</th><th>关联</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{rows.map(resource => <tr key={resource.id}>
          <td>{resource.name}<div className="table-sub mono">{resource.id}</div></td>
          <td>{resource.resource_kind} · {resource.protocol}</td>
          <td>{PLAYBACK_LABELS[resource.playback] ?? resource.playback}<div className="table-sub">{resource.note}</div></td>
          <td>{resource.endpoint_configured ? resource.endpoint_host : '未配置'}</td>
          <td>{resource.credentials_configured ? '已配置（只写）' : '未配置'}</td>
          <td>{resource.visible_associations}</td>
          <td>{resource.archived_at ? <Badge variant="outline">已归档</Badge> : resource.enabled ? '已启用' : '未启用'}</td>
          <td><Button type="button" size="sm" variant="outline" onClick={() => { setSelectedId(resource.id); write.reset() }}>详情</Button></td>
        </tr>)}</tbody></table></div>
      <Pager page={list.data?.page ?? 1} size={list.data?.size ?? 20} total={list.data?.total ?? 0} onChange={setPage}/>
    </>}
    </>}

    {createOpen && <Card><CardContent><form onSubmit={submitCreate}>
      <h2>登记视频资源</h2>
      <div className="form-grid">
        <div className="field"><Label htmlFor="video-name">名称</Label><Input id="video-name" value={name} maxLength={200} onChange={event => setName(event.target.value)}/></div>
        <div className="field"><Label htmlFor="video-kind">类型</Label><select id="video-kind" className="native-select" value={kind} onChange={event => setKind(event.target.value as typeof kind)}>{VIDEO_KINDS.map(value => <option key={value} value={value}>{value}</option>)}</select></div>
        <div className="field"><Label htmlFor="video-protocol">协议</Label><select id="video-protocol" className="native-select" value={protocol} onChange={event => setProtocol(event.target.value as typeof protocol)}>{VIDEO_PROTOCOLS.map(value => <option key={value} value={value}>{value}</option>)}</select></div>
      </div>
      <p className="muted-note">登记后为停用且不含端点；端点、签名地址与凭据必须单独写入，不会出现在导入或导出中。RTSP 没有转封装网关时播放保持未实现。</p>
      <WriteFields write={write} label="video-create"/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>登记</Button><Button type="button" variant="outline" onClick={() => { setCreateOpen(false); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {selected && <Card><CardContent>
      {!legacy && <div className="list-toolbar"><h2>视频资源详情 · {selected.resource.name}</h2><Button type="button" variant="outline" onClick={() => setSelectedId('')}>关闭</Button></div>}
      <div className="detail-grid">
        <div><strong>协议</strong><p>{selected.resource.protocol}</p></div>
        <div><strong>端点主机</strong><p>{selected.resource.endpoint_host ?? '未配置'}</p></div>
        <div><strong>端点配置</strong><p>{selected.resource.endpoint_configured ? '已保存（值只写）' : '未配置'}</p></div>
        <div><strong>凭据</strong><p>{selected.resource.credentials_configured ? '已保存（值只写）' : '未配置'}</p></div>
        <div><strong>RTSP网关</strong><p>{selected.resource.gateway_configured ? `${selected.resource.gateway_host}（值只写）` : '未配置'}</p></div>
        <div><strong>抓图能力</strong><p>{selected.resource.snapshot}</p></div>
        <div><strong>修订</strong><p>{selected.resource.revision}</p></div>
      </div>
      {!selected.resource.archived_at && canWrite && <div className="row-actions">
        <Button type="button" variant="outline" onClick={() => { setEndpointOpen(true); write.reset() }}>配置端点</Button>
        {selected.resource.protocol === 'RTSP' && <Button type="button" variant="outline" onClick={() => { setGatewayOpen(true); write.reset() }}>配置RTSP网关</Button>}
        <Button type="button" variant="outline" onClick={() => { setCredentialsOpen(true); write.reset() }}>配置凭据</Button>
        <Button type="button" variant="outline" onClick={() => { setAssociateOpen(true); write.reset() }}>关联设备</Button>
        <Button type="button" variant="outline" onClick={() => { setArchive(selected.resource); write.reset() }}>归档</Button>
      </div>}
      {!selected.resource.archived_at && canWrite && (selected.resource.endpoint_configured || selected.resource.enabled) && <>
        <WriteFields write={write} label="video-actions"/>
        <div className="row-actions">
        {selected.resource.endpoint_configured && <Button type="button" variant="outline" onClick={() => mutate('endpoint-clear', { expected_revision: selected.resource.revision, reason: write.reason.trim() })}>清除端点</Button>}
        {selected.resource.credentials_configured && <Button type="button" variant="outline" onClick={() => mutate('credentials-clear', { expected_revision: selected.resource.revision, reason: write.reason.trim() })}>清除凭据</Button>}
        <Button type="button" variant="outline" onClick={() => mutate('enabled', { expected_revision: selected.resource.revision, enabled: !selected.resource.enabled, reason: write.reason.trim() })}>{selected.resource.enabled ? '停用' : '启用'}</Button>
        </div>
      </>}
      <h3>媒体</h3>
      <VideoMediaPlayer resource={selected.resource} associations={selected.associations} canPlay={canPlay}/>
      {canPlay && <><h3>抓图任务</h3><VideoCapturePanel tenantId={tenantId} resourceId={selected.resource.id} associations={selected.associations}/></>}
      <h3>设备关联</h3>
      {selected.associations.length === 0 ? <Empty title="没有设备关联">未关联真实设备时不会向客户暴露该视频。</Empty> :
        <div className="table-wrap"><table><thead><tr><th>设备</th><th>位置</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>{selected.associations.map(association => <tr key={association.id}>
            <td>{association.device_name ?? association.device_id}<div className="table-sub mono">{association.device_eui ?? association.device_id}</div></td>
            <td>{association.place_name ?? '未提供'}</td><td>{association.state === 'ACTIVE' ? '有效' : '已撤销'}</td>
            <td>{canWrite && association.state === 'ACTIVE' && <Button type="button" size="sm" variant="outline" onClick={() => { setRevokeAssociation(association.id); setRevokeReason(''); write.reset() }}>撤销关联</Button>}</td>
          </tr>)}</tbody></table></div>}
    </CardContent></Card>}

    {selected && endpointOpen && <Card><CardContent><form onSubmit={submitEndpoint}>
      <h2>配置视频端点（只写）</h2>
      <div className="field"><Label htmlFor="endpoint-url">HTTPS/RTSP 端点地址</Label><Input id="endpoint-url" value={endpointUrl} maxLength={4096} onChange={event => setEndpointUrl(event.target.value)} placeholder="https://allowlisted.example/live/index.m3u8"/></div>
      <div className="form-grid">
        <div className="field"><Label htmlFor="endpoint-user">Basic 用户名（可空）</Label><Input id="endpoint-user" value={endpointUser} maxLength={128} onChange={event => setEndpointUser(event.target.value)}/></div>
        <div className="field"><Label htmlFor="endpoint-password">Basic 密码（可空）</Label><Input id="endpoint-password" type="password" value={endpointPassword} maxLength={256} onChange={event => setEndpointPassword(event.target.value)}/></div>
      </div>
      <p className="muted-note">保存后服务端只回显主机名；地址/签名参数与凭据加密存储。HTTPS 主机必须命中 APP_VIDEO_ALLOWED_HOSTS；变更端点会清除旧凭据。</p>
      <WriteFields write={write} label="video-endpoint"/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>保存端点</Button><Button type="button" variant="outline" onClick={() => { setEndpointOpen(false); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {selected && credentialsOpen && <Card><CardContent><form onSubmit={submitCredentials}>
      <h2>配置端点凭据（只写）</h2>
      <div className="form-grid">
        <div className="field"><Label htmlFor="credential-user">用户名</Label><Input id="credential-user" value={credentialUser} maxLength={128} onChange={event => setCredentialUser(event.target.value)}/></div>
        <div className="field"><Label htmlFor="credential-password">密码</Label><Input id="credential-password" type="password" value={credentialPassword} maxLength={256} onChange={event => setCredentialPassword(event.target.value)}/></div>
      </div>
      <WriteFields write={write} label="video-credentials"/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>保存凭据</Button><Button type="button" variant="outline" onClick={() => { setCredentialsOpen(false); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {selected && associateOpen && <Card><CardContent><form onSubmit={submitAssociation}>
      <h2>关联真实设备</h2>
      <DeviceSelector audience="management" tenantId={tenantId} value={associateDevice?.id ?? ''} current={associateDevice}
        onChange={(_id, reference) => setAssociateDevice(reference)} canReadDevices={canReadDevices} canReadPlaces={canReadPlaces}/>
      <WriteFields write={write} label="video-associate"/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>建立关联</Button><Button type="button" variant="outline" onClick={() => { setAssociateOpen(false); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {selected && revokeAssociation && <Card><CardContent><form onSubmit={submitRevoke}>
      <h2>撤销视频设备关联</h2>
      <div className="field"><Label htmlFor="association-reason">撤销原因</Label><Input id="association-reason" value={revokeReason} maxLength={1000} onChange={event => setRevokeReason(event.target.value)}/></div>
      <WriteFields write={write} label="video-revoke"/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>确认撤销</Button><Button type="button" variant="outline" onClick={() => { setRevokeAssociation(null); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {selected && gatewayOpen && <Card><CardContent><form onSubmit={submitGateway}>
      <h2>配置RTSP转封装网关（只写）</h2>
      <div className="field"><Label htmlFor="gateway-url">HTTPS HLS 网关地址</Label><Input id="gateway-url" value={gatewayUrl} maxLength={4096} onChange={event => setGatewayUrl(event.target.value)} placeholder="https://allowlisted.example/rtsp/camera/index.m3u8"/></div>
      <p className="muted-note">网关必须返回HLS；主机必须命中 APP_VIDEO_ALLOWED_HOSTS。地址加密保存，仅回显主机名。RTSP播放复用同源HLS代理。</p>
      <WriteFields write={write} label="video-gateway"/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>保存网关</Button><Button type="button" variant="outline" onClick={() => { setGatewayOpen(false); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {selected && archive && <Card><CardContent>
      <h2>归档视频资源</h2>
      <p>归档会停用资源并撤销所有媒体会话；历史关联保留。请填写原因与密码后确认。</p>
      <WriteFields write={write} label="video-archive"/>
      <div className="row-actions"><Button type="button" disabled={write.saving} onClick={() => {
        const body = { expected_revision: archive.revision, reason: write.reason.trim() }
        write.submit(['video.archive', archive.id, body], key => request(`/management/video-resources/${encodeURIComponent(archive.id)}/archive`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }), () => { setArchive(null); reload() })
      }}>确认归档</Button><Button type="button" variant="outline" onClick={() => { setArchive(null); write.reset() }}>取消</Button></div>
    </CardContent></Card>}
  </>
}
