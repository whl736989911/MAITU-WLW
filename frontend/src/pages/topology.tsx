import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { request } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { useDomainWrite, type DomainWrite } from '@/lib/domain-write'
import {
  GATEWAY_PROTOCOLS, PROVENANCE_LABELS, topologyApi, type GatewayTree, type TopologyGateway, type TopologyLink,
} from '@/lib/topology'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { ConfigurationUnitTarget } from '@/components/configuration-unit-target'
import { DeviceSelector, type AssetReference } from '@/components/asset-picker'
import { PageHelp } from '@/components/page-tools'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { listDeviceChannels } from '@/lib/device-channels'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'

const PAGE = 'topology'

export function TopologyPage() {
  const s = useSession()
  if (!s.token || s.user?.must_change_password || !s.permissions.includes('topology:read') || String(s.page) !== PAGE)
    return <Empty title="当前无网关拓扑读取范围"/>
  const canReadDevices = s.audience === 'management' ? s.permissions.includes('device:read') : s.permissions.includes('device:read')
  if (s.audience === 'customer') {
    const tenantId = s.user?.tenant_id
    if (!tenantId) return <Empty title="当前账户未绑定单位"/>
    return <Content key={`${s.authGeneration}:${tenantId}:read`} tenantId={tenantId} canWrite={false}
      canReadDevices={canReadDevices} canReadPlaces={s.permissions.includes('units:read')}/>
  }
  return <ConfigurationUnitTarget>{tenantId => <Content key={`${s.authGeneration}:${tenantId}`} tenantId={tenantId}
    canWrite={s.permissions.includes('topology:write')} canReadDevices={canReadDevices}
    canReadPlaces={s.permissions.includes('units:read')}/>}</ConfigurationUnitTarget>
}

function Credentials({ write, disabled = false }: { write: DomainWrite; disabled?: boolean }) {
  return <>
    <div className="field"><Label htmlFor="topology-reason">变更原因</Label>
      <Input id="topology-reason" value={write.reason} maxLength={1000} disabled={disabled} onChange={event => write.setReason(event.target.value)}/></div>
    {write.error != null && <ErrorNotice error={write.error} retry={() => write.setError(undefined)}/>}
  </>
}

function Content({ tenantId, canWrite, canReadDevices, canReadPlaces }: {
  tenantId: string; canWrite: boolean; canReadDevices: boolean; canReadPlaces: boolean
}) {
  const [keyword, setKeyword] = useState(''), [draft, setDraft] = useState(''), [page, setPage] = useState(1)
  const gateways = useResource(`topology-gateways:${tenantId}:${page}:${keyword}`, signal => topologyApi.gateways(tenantId, page, keyword, signal))
  const [selectedId, setSelectedId] = useState('')
  const selected = gateways.data?.items.find(item => item.id === selectedId) ?? null
  const tree = useResource(`topology-tree:${tenantId}:${selectedId}`, signal => topologyApi.tree(tenantId, selectedId, signal), !!selectedId)
  const [linkState, setLinkState] = useState('ACTIVE'), [linkKind, setLinkKind] = useState(''), [linkPage, setLinkPage] = useState(1), [linkKeyword, setLinkKeyword] = useState('')
  const links = useResource(`topology-links:${tenantId}:${linkPage}:${linkState}:${linkKind}:${linkKeyword}:${selectedId}`,
    signal => topologyApi.links(tenantId, linkPage, { gateway_id: selectedId || undefined, child_kind: linkKind || undefined, state: linkState, keyword: linkKeyword }, signal))
  const write = useDomainWrite()
  const [createOpen, setCreateOpen] = useState(false), [address, setAddress] = useState(''), [name, setName] = useState(''), [protocol, setProtocol] = useState('LEGACY_TURMASS_MQTT'), [gatewayDevice, setGatewayDevice] = useState<AssetReference | undefined>()
  const [editing, setEditing] = useState<TopologyGateway | null>(null)
  const [linkOpen, setLinkOpen] = useState(false), [childKind, setChildKind] = useState<'DEVICE' | 'MEASUREMENT'>('DEVICE'), [childDevice, setChildDevice] = useState<AssetReference | undefined>(), [channelId, setChannelId] = useState('')
  const [provenance, setProvenance] = useState<'MANUAL' | 'OBSERVED'>('MANUAL'), [eventId, setEventId] = useState(''), [observedAt, setObservedAt] = useState('')
  const [linkToRevoke, setLinkToRevoke] = useState<TopologyLink | null>(null)
  const [verifyLink, setVerifyLink] = useState<TopologyLink | null>(null), [verifyNote, setVerifyNote] = useState('')
  const [channelPage, setChannelPage] = useState(1)
  const channels = useResource(`topology-channels:${tenantId}:${childDevice?.id ?? ''}:${channelPage}`,
    signal => listDeviceChannels({ device_id: childDevice?.id, page: channelPage, size: 20 }, signal, tenantId),
    canWrite && canReadDevices && linkOpen && childKind === 'MEASUREMENT' && !!childDevice?.id)
  const [retiring, setRetiring] = useState<TopologyGateway | null>(null)
  const [revokeReason, setRevokeReason] = useState('')

  useEffect(() => { setChannelId(''); setChannelPage(1) }, [childKind, childDevice?.id])
  useEffect(() => { setLinkPage(1) }, [tenantId, selectedId, linkState, linkKind, linkKeyword])
  useEffect(() => {
    if (!gateways.data) return
    if (selectedId && !gateways.data.items.some(item => item.id === selectedId)) setSelectedId('')
  }, [gateways.data, selectedId])

  const reload = () => { gateways.refresh(); links.refresh(); if (selectedId) tree.refresh() }
  function search(event: FormEvent) { event.preventDefault(); setKeyword(draft.trim()); setPage(1) }

  function submitGateway(event: FormEvent) {
    event.preventDefault()
    const fields = { address: address.trim().toUpperCase(), name: name.trim(), protocol, device_id: gatewayDevice?.id ?? null, reason: write.reason.trim() }
    const spec = editing
      ? { identity: ['gateway.update', editing.id, fields], path: `/management/topology/gateways/${encodeURIComponent(editing.id)}`, method: 'PUT' as const }
      : { identity: ['gateway.create', fields], path: '/management/topology/gateways', method: 'POST' as const }
    const payload = editing ? { ...fields, expected_revision: editing.revision } : fields
    write.submit(spec.identity, key => request(spec.path, { tenantScoped: true, tenantId, method: spec.method, headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload) }),
      () => { setCreateOpen(false); setEditing(null); setAddress(''); setName(''); setGatewayDevice(undefined); reload() })
  }

  function submitLink(event: FormEvent) {
    event.preventDefault()
    if (!selected) return
    const body: Record<string, unknown> = { gateway_id: selected.id, child_kind: childKind, device_id: childDevice?.id ?? '', channel_id: childKind === 'MEASUREMENT' ? channelId.trim() || null : null, provenance, observation_source: provenance === 'OBSERVED' ? 'LEGACY_MQTT_EVENT' : null, observation_event_id: provenance === 'OBSERVED' ? eventId.trim() : null, observed_at: provenance === 'OBSERVED' ? observedAt.trim() : null, reason: write.reason.trim() }
    write.submit(['link.create', body], key => request('/management/topology/links', { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
      () => { setLinkOpen(false); setChildDevice(undefined); setChannelId(''); setEventId(''); setObservedAt(''); reload() })
  }

  const rows = gateways.data?.items ?? []
  const linkRows = links.data?.items ?? []
  const treeData: GatewayTree | undefined = tree.data

  return <>
    <PageHeading eyebrow="设备与产品 · 网关拓扑" title="网关与测点拓扑"
      description="只登记真实网关到设备/测点的承载关系；人工声明、旧MQTT事件观察和人工核验分开展示。未知来源不绘图。">
      <PageHelp title="拓扑事实说明">
        <div className="form-stack">
          <p>人工关系需要原因与二次确认；观察关系必须引用一条真实旧平台MQTT事件，事件中的网关地址与设备必须匹配。</p>
          <p>一个设备/测点最多一个有效网关；冲突会拒绝而不是自动改亲。导入只创建人工关系，不伪造观察证据。</p>
          <p>读取按当前设备范围过滤；范围外的子节点不会出现，也不会显示其数量。</p>
        </div>
      </PageHelp>
      <RefreshButton onClick={reload}/>
      {canWrite && <DataTransferActions kind="TOPOLOGY_LINKS" tenantId={tenantId} filters={{ unit_ids: tenantId, place_ids: '' }}/>}
    </PageHeading>

    <Card><CardContent>
      <div className="list-toolbar"><h2>网关台账</h2>{canWrite && <Button type="button" onClick={() => { setEditing(null); setAddress(''); setName(''); setProtocol('LEGACY_TURMASS_MQTT'); setGatewayDevice(undefined); setCreateOpen(true); write.reset() }}>登记网关</Button>}</div>
      <form className="search-form" onSubmit={search}>
        <Input aria-label="搜索网关" value={draft} maxLength={100} onChange={event => setDraft(event.target.value)} placeholder="网关地址 / 名称"/>
        <Button type="submit" variant="outline">查询</Button>
        {keyword && <Button type="button" variant="ghost" onClick={() => { setDraft(''); setKeyword(''); setPage(1) }}>清除搜索</Button>}
      </form>
      {gateways.loading ? <Loading/> : gateways.error != null ? <ErrorNotice error={gateways.error} retry={gateways.refresh}/> : rows.length === 0 ? <Empty title="没有可见网关">服务端没有返回网关记录；不推断或生成演示拓扑。</Empty> : <>
        <div className="table-wrap"><table><thead><tr><th>地址</th><th>名称</th><th>协议</th><th>网关设备</th><th>可见子节点</th><th>修订</th><th>操作</th></tr></thead>
          <tbody>{rows.map(gateway => <tr key={gateway.id}>
            <td className="mono">{gateway.address}</td><td>{gateway.name}</td><td>{gateway.protocol}</td>
            <td>{gateway.device_name ?? '未绑定设备资产'}{gateway.device_id && <div className="table-sub mono">{gateway.device_id}</div>}</td>
            <td>{gateway.visible_children}{gateways.data?.scope_limited && <div className="table-sub">仅当前范围</div>}</td><td>{gateway.revision}</td>
            <td><div className="row-actions"><Button type="button" size="sm" variant="outline" onClick={() => setSelectedId(gateway.id)}>关系</Button>
              {canWrite && <><Button type="button" size="sm" variant="outline" onClick={() => { setEditing(gateway); setAddress(gateway.address); setName(gateway.name); setProtocol(gateway.protocol); setGatewayDevice(gateway.device_id ? { id: gateway.device_id, name: gateway.device_name } : undefined); setCreateOpen(true); write.reset() }}>编辑</Button>
                <Button type="button" size="sm" variant="outline" aria-label={`停用网关 · ${gateway.address}`} disabled={write.saving || gateway.visible_children > 0} title={gateway.visible_children > 0 ? '请先撤销有效设备与测点关系' : '停用网关并保留关系历史'} onClick={() => { setCreateOpen(false); setLinkOpen(false); setVerifyLink(null); setLinkToRevoke(null); setRetiring(gateway); write.reset(); write.setReason('') }}>停用</Button></>}</div></td>
          </tr>)}</tbody></table></div>
        <Pager page={gateways.data?.page ?? 1} size={gateways.data?.size ?? 20} total={gateways.data?.total ?? 0} onChange={setPage}/>
        {gateways.data?.scope_limited && <p className="muted-note">当前读取范围较窄：只显示范围内子节点可被看到的网关，子节点计数也仅统计可见记录。</p>}
      </>}
    </CardContent></Card>

    {createOpen && <Card><CardContent><form onSubmit={submitGateway}>
      <h2>{editing ? '编辑网关' : '登记网关'}</h2>
      <div className="form-grid">
        <div className="field"><Label htmlFor="gateway-address">网关地址</Label><Input id="gateway-address" value={address} maxLength={64} onChange={event => setAddress(event.target.value)} placeholder="例如 70800045"/></div>
        <div className="field"><Label htmlFor="gateway-name">网关名称</Label><Input id="gateway-name" value={name} maxLength={200} onChange={event => setName(event.target.value)}/></div>
        <div className="field"><Label htmlFor="gateway-protocol">协议</Label><select id="gateway-protocol" className="native-select" value={protocol} onChange={event => setProtocol(event.target.value)}>{GATEWAY_PROTOCOLS.map(value => <option key={value} value={value}>{value}</option>)}</select></div>
      </div>
      <details><summary>{gatewayDevice?.id ? `已关联设备：${gatewayDevice.name ?? gatewayDevice.id}` : '关联设备资产（可选）'}</summary>
      <DeviceSelector audience="management" tenantId={tenantId} value={gatewayDevice?.id ?? ''} current={gatewayDevice}
        onChange={(_id, reference) => setGatewayDevice(reference)} canReadDevices={canReadDevices} canReadPlaces={canReadPlaces}/>
      <p className="muted-note">网关设备可留空（旧网关通常不是本地设备资产）。绑定的设备必须未归档、在当前设备范围且未作为其他网关的设备资产。</p>
      </details>
      <Credentials write={write} disabled={write.saving}/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>{write.saving ? '处理中…' : editing ? '保存修改' : '登记网关'}</Button>
        <Button type="button" variant="outline" onClick={() => { setCreateOpen(false); setEditing(null); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {selected && <Card><CardContent>
      <div className="list-toolbar"><h2>网关 {selected.address} 的设备与测点关系</h2><div className="row-actions">
        <Button type="button" variant="outline" onClick={() => tree.refresh()}>刷新关系</Button>
        {canWrite && <Button type="button" onClick={() => { setLinkOpen(true); setLinkToRevoke(null); write.reset() }}>登记关系</Button>}
      </div></div>
      {tree.loading ? <Loading/> : tree.error != null ? <ErrorNotice error={tree.error} retry={tree.refresh}/> : !treeData ? <Empty title="未读取到关系"/> : <>
        {treeData.note && <p className="muted-note">{treeData.note}</p>}
        {treeData.links.length === 0 ? <Empty title="未提供关系">该网关没有可见的设备或测点关系；不会根据名称/型号推断。</Empty> :
          <div className="table-wrap"><table><thead><tr><th>子节点</th><th>类型</th><th>位置/通道</th><th>来源</th><th>核验</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>{treeData.links.map(link => <tr key={link.id}>
              <td>{link.device_name ?? '未命名设备'}{link.device_eui && <div className="table-sub mono">{link.device_eui}</div>}{link.device_archived_at && <Badge variant="outline">设备已归档</Badge>}</td>
              <td>{link.child_kind === 'MEASUREMENT' ? `测点 · ${link.channel_name ?? link.channel_id}` : '设备'}</td>
              <td>{link.place_name ?? '未提供'}</td>
              <td>{PROVENANCE_LABELS[link.provenance]}{link.provenance === 'OBSERVED' && <div className="table-sub mono">{link.observation_event_id}</div>}</td>
              <td>{link.verified ? <Badge variant="outline">已核验</Badge> : <Badge variant="outline">未核验</Badge>}{link.verification_note && <div className="table-sub">{link.verification_note}</div>}</td>
              <td>{link.state === 'ACTIVE' ? '有效' : '已撤销'}{link.revoke_reason && <div className="table-sub">{link.revoke_reason}</div>}</td>
              <td>{canWrite && link.state === 'ACTIVE' && <div className="row-actions">
                {!link.verified && <Button type="button" size="sm" variant="outline" onClick={() => { setVerifyLink(link); setVerifyNote(''); write.reset() }}>核验</Button>}
                <Button type="button" size="sm" variant="outline" onClick={() => { setLinkToRevoke(link); setRevokeReason(''); write.reset() }}>撤销</Button>
              </div>}</td>
            </tr>)}</tbody></table></div>}
      </>}
    </CardContent></Card>}

    {linkOpen && selected && <Card><CardContent><form onSubmit={submitLink}>
      <h2>登记关系到 {selected.address}</h2>
      <div className="form-grid">
        <div className="field"><Label htmlFor="link-kind">关系类型</Label><select id="link-kind" className="native-select" value={childKind} onChange={event => setChildKind(event.target.value as 'DEVICE' | 'MEASUREMENT')}><option value="DEVICE">设备承载</option><option value="MEASUREMENT">测点通道</option></select></div>
        {childKind === 'MEASUREMENT' && <div className="field"><Label htmlFor="link-channel">测点通道</Label>
          <select id="link-channel" className="native-select" value={channelId} disabled={!childDevice || channels.loading || channels.error != null} onChange={event => setChannelId(event.target.value)}>
            <option value="">{!childDevice ? '请先选择设备' : channels.loading ? '读取通道…' : channels.data?.items.length ? '请选择本页通道' : '该设备未登记通道'}</option>
            {(channels.data?.items ?? []).map(channel => <option key={channel.id} value={channel.id}>{channel.name} · {channel.channel_key}{channel.enabled ? '' : '（已停用）'}</option>)}
          </select>
          {channels.error != null && <ErrorNotice error={channels.error} retry={channels.refresh}/>}
          {channels.data && <Pager page={channelPage} size={channels.data.size} total={channels.data.total} onChange={next => { setChannelId(''); setChannelPage(next) }}/>}
        </div>}
        <div className="field"><Label htmlFor="link-provenance">来源</Label><select id="link-provenance" className="native-select" value={provenance} onChange={event => setProvenance(event.target.value as 'MANUAL' | 'OBSERVED')}><option value="MANUAL">人工声明</option><option value="OBSERVED">旧MQTT事件观察</option></select></div>
      </div>
      <DeviceSelector audience="management" tenantId={tenantId} value={childDevice?.id ?? ''} current={childDevice}
        onChange={(_id, reference) => setChildDevice(reference)} canReadDevices={canReadDevices} canReadPlaces={canReadPlaces}/>
      {provenance === 'OBSERVED' && <div className="form-grid">
        <div className="field"><Label htmlFor="link-event">旧平台事件ID</Label><Input id="link-event" value={eventId} maxLength={64} onChange={event => setEventId(event.target.value)} placeholder="legacy_ingest_events.id"/></div>
        <div className="field"><Label htmlFor="link-observed">观察时间（ISO-8601带时区）</Label><Input id="link-observed" value={observedAt} maxLength={64} onChange={event => setObservedAt(event.target.value)} placeholder="2026-11-02T01:38:38+08:00"/></div>
      </div>}
      <p className="muted-note">服务端会核验事件属于该设备且事件参数中的网关地址与当前网关一致；不一致会拒绝，不会生成推测关系。</p>
      <Credentials write={write} disabled={write.saving}/>
      <div className="row-actions"><Button type="submit" disabled={write.saving || (childKind === 'MEASUREMENT' && !channelId)}>{write.saving ? '处理中…' : '登记关系'}</Button>
        <Button type="button" variant="outline" onClick={() => { setLinkOpen(false); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {verifyLink && <Card><CardContent><form onSubmit={event => { event.preventDefault(); write.submit(['link.verify', verifyLink.id, verifyLink.revision, verifyNote], key => request(`/management/topology/links/${encodeURIComponent(verifyLink.id)}/verify`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ expected_revision: verifyLink.revision, verification_note: verifyNote.trim(), reason: write.reason.trim() }) }), () => { setVerifyLink(null); reload() }) }}>
      <h2>核验拓扑关系</h2>
      <div className="field"><Label htmlFor="verify-note">核验说明</Label><Input id="verify-note" value={verifyNote} maxLength={1000} onChange={event => setVerifyNote(event.target.value)} placeholder="现场/证据核对结论"/></div>
      <Credentials write={write} disabled={write.saving}/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>确认核验</Button><Button type="button" variant="outline" onClick={() => { setVerifyLink(null); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    {linkToRevoke && <Card><CardContent><form onSubmit={event => { event.preventDefault(); write.submit(['link.revoke', linkToRevoke.id, linkToRevoke.revision, revokeReason], key => request(`/management/topology/links/${encodeURIComponent(linkToRevoke.id)}/revoke`, { tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ expected_revision: linkToRevoke.revision, revoke_reason: revokeReason.trim(), reason: write.reason.trim() }) }), () => { setLinkToRevoke(null); reload() }) }}>
      <h2>撤销拓扑关系</h2>
      <div className="field"><Label htmlFor="revoke-reason">撤销原因</Label><Input id="revoke-reason" value={revokeReason} maxLength={1000} onChange={event => setRevokeReason(event.target.value)}/></div>
      <Credentials write={write} disabled={write.saving}/>
      <div className="row-actions"><Button type="submit" disabled={write.saving}>确认撤销</Button><Button type="button" variant="outline" onClick={() => { setLinkToRevoke(null); write.reset() }}>取消</Button></div>
    </form></CardContent></Card>}

    <Sheet open={retiring !== null} onOpenChange={open => { if (!open && !write.saving) { setRetiring(null); write.reset() } }}><SheetContent className="review-sheet sm:max-w-2xl">
      <SheetHeader className="review-header"><SheetTitle>停用网关</SheetTitle></SheetHeader>
      {retiring && <form className="review-body form-stack" onSubmit={event => {
        event.preventDefault()
        write.submit(['gateway.retire', retiring.id, retiring.revision, write.reason.trim()], key => request(`/management/topology/gateways/${encodeURIComponent(retiring.id)}/retire`, {
          tenantScoped: true, tenantId, method: 'POST', headers: { 'Idempotency-Key': key },
          body: JSON.stringify({ expected_revision: retiring.revision, reason: write.reason.trim() }),
        }), () => { setRetiring(null); setSelectedId(''); reload() })
      }}>
        <p><strong>{retiring.name}</strong> · {retiring.address}</p>
        <p className="notice">必须先撤销全部有效设备与测点关系。服务端检查完整范围，不只当前页可见记录；停用后不再列于有效网关台账，历史关系保留。</p>
        <Credentials write={write} disabled={write.saving}/>
        <div className="row-actions"><Button type="submit" disabled={write.saving || !write.reason.trim()}>确认停用</Button><Button type="button" variant="outline" disabled={write.saving} onClick={() => { setRetiring(null); write.reset() }}>取消</Button></div>
      </form>}
    </SheetContent></Sheet>

    <Card><CardContent>
      <div className="list-toolbar"><h2>关系记录</h2><span className="muted-note">含已撤销历史；按当前设备范围过滤。</span></div>
      <div className="form-grid">
        <div className="field"><Label htmlFor="link-state">状态</Label><select id="link-state" className="native-select" value={linkState} onChange={event => setLinkState(event.target.value)}><option value="ACTIVE">有效</option><option value="REVOKED">已撤销</option><option value="ALL">全部</option></select></div>
        <div className="field"><Label htmlFor="link-kind-filter">类型</Label><select id="link-kind-filter" className="native-select" value={linkKind} onChange={event => setLinkKind(event.target.value)}><option value="">全部</option><option value="DEVICE">设备</option><option value="MEASUREMENT">测点</option></select></div>
        <div className="field"><Label htmlFor="link-keyword">搜索</Label><Input id="link-keyword" value={linkKeyword} maxLength={100} onChange={event => setLinkKeyword(event.target.value)} placeholder="设备/EUI/网关/通道"/></div>
      </div>
      {links.loading ? <Loading/> : links.error != null ? <ErrorNotice error={links.error} retry={links.refresh}/> : linkRows.length === 0 ? <Empty title="没有符合条件的关系"/> : <>
        <div className="table-wrap"><table><thead><tr><th>网关</th><th>子节点</th><th>来源</th><th>核验</th><th>状态</th><th>更新时间</th></tr></thead>
          <tbody>{linkRows.map(link => <tr key={link.id}>
            <td className="mono">{link.gateway_address}<div className="table-sub">{link.gateway_name}</div></td>
            <td>{link.device_name ?? link.device_id}<div className="table-sub">{link.child_kind === 'MEASUREMENT' ? `测点 ${link.channel_name ?? link.channel_id}` : link.device_eui ?? '设备'}</div></td>
            <td>{PROVENANCE_LABELS[link.provenance]}</td><td>{link.verified ? '已核验' : '未核验'}</td>
            <td>{link.state === 'ACTIVE' ? '有效' : '已撤销'}</td><td>{link.updated_at}</td>
          </tr>)}</tbody></table></div>
        <Pager page={links.data?.page ?? 1} size={links.data?.size ?? 20} total={links.data?.total ?? 0} onChange={setLinkPage}/>
      </>}
    </CardContent></Card>
  </>
}
