import { useMemo, useState } from 'react'
import { ArrowRight, MapPin, Radio, Users } from 'lucide-react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { allowedPage, type Device, type ManagedUser, type Paginated, type PlaceNode } from '@/lib/types'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { PageHelp } from '@/components/page-tools'

interface RelationsOverview { places: PlaceNode[]; devices: Paginated<Device>; users: Paginated<ManagedUser> }
export function RelationsPage() {
  const { audience, permissions, user } = useSession()
  if (!user || !allowedPage('relations', audience, permissions)) return <Empty title="当前账户无单位关系权限"/>
  return <RelationsContent/>
}

function RelationsContent() {
  const session = useSession()
  const permissions = session.permissions
  const scopeTenantIds = session.scopeTenantIds, scopePlaceIds = session.scopePlaceIds
  const [devicePage, setDevicePage] = useState(1), [userPage, setUserPage] = useState(1)
  const overview = useResource(`relations:${session.generation}:${JSON.stringify(scopeTenantIds)}:${JSON.stringify(scopePlaceIds)}:${devicePage}:${userPage}`, signal => api.relations(signal, scopeTenantIds, scopePlaceIds, devicePage, userPage))
  const places = overview.data?.places ?? []
  const devices = overview.data?.devices, users = overview.data?.users
  const placeIds = useMemo(() => new Set(places.map(place => place.id)), [places])
  const top = useMemo(() => places.filter(place => !place.parent_id || !placeIds.has(place.parent_id)), [places, placeIds])
  const children = useMemo(() => {
    const counts = new Map<string, number>()
    for (const place of places) if (place.parent_id) counts.set(place.parent_id, (counts.get(place.parent_id) ?? 0) + 1)
    return counts
  }, [places])
  const refresh = () => overview.refresh()
  return <>
    <PageHeading eyebrow="RELATIONS · 单位关系" title="授权单位关系总览"><PageHelp title="关系视图说明">只读汇总当前账户可见的位置层级、设备与用户；这里不替代各自的资产与授权管理页面。</PageHelp>
      <RefreshButton onClick={refresh}/>
    </PageHeading>
    {overview.loading ? <Loading/> : overview.error != null ? <ErrorNotice error={overview.error} retry={refresh}/> : <>
      <div className="relation-summary">
        <div><small>授权单位</small><strong>{new Set(places.map(p => p.tenant_id)).size}</strong></div><ArrowRight size={16}/>
        <div><small>一级位置</small><strong>{top.length}</strong></div><ArrowRight size={16}/>
        <div><small>位置总数</small><strong>{places.length}</strong></div><ArrowRight size={16}/>
        <div><small>设备</small><strong>{devices?.total ?? '无权限'}</strong></div><ArrowRight size={16}/>
        <div><small>单位用户</small><strong>{permissions.includes('users:read') ? users?.total ?? '—' : '无权限'}</strong></div>
      </div>
      <Card><CardContent><h2 className="section-heading"><MapPin size={18}/>一级位置与子位置</h2>{top.length ? <Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>一级位置</TableHead><TableHead>直属设备</TableHead><TableHead>子位置</TableHead><TableHead>旧平台位置 ID</TableHead></TableRow></TableHeader><TableBody>{top.map(place => <TableRow key={place.id}><TableCell>{place.tenant_name}</TableCell><TableCell><strong>{place.name}</strong></TableCell><TableCell><Badge variant={place.device_count ? 'secondary' : 'outline'}>{place.device_count}</Badge></TableCell><TableCell>{children.get(place.id) ?? 0}</TableCell><TableCell className="mono">{place.legacy_place_id ?? '—'}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="当前筛选范围暂无位置数据"/>}</CardContent></Card>
      <div className="relation-grid">
        <Card><CardContent><h2 className="section-heading"><Radio size={18}/>设备</h2>{!permissions.includes('device:read') ? <Empty title="无权查看设备"/> : devices?.items.length ? <><Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>设备</TableHead><TableHead>状态</TableHead><TableHead>安装位置</TableHead></TableRow></TableHeader><TableBody>{devices.items.map(device => <TableRow key={device.id}><TableCell>{device.tenant_id}</TableCell><TableCell><strong>{device.name ?? '未上报'}</strong><div className="table-sub mono">{device.device_eui ?? '—'}</div></TableCell><TableCell>{device.physical_state === 'FIRE' ? '火警' : device.connectivity === 'ONLINE' ? '在线' : device.connectivity === 'OFFLINE' ? '离线' : '未激活'}</TableCell><TableCell>{device.address ?? '未上报'}</TableCell></TableRow>)}</TableBody></Table><Pager page={devices.page} size={devices.size} total={devices.total} onChange={setDevicePage}/></> : <Empty title="当前筛选范围暂无设备"/>}</CardContent></Card>
        <Card><CardContent><h2 className="section-heading"><Users size={18}/>单位用户</h2>{!permissions.includes('users:read') ? <Empty title="无权查看单位用户"/> : users?.items.length ? <><Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>用户</TableHead><TableHead>角色</TableHead><TableHead>状态</TableHead></TableRow></TableHeader><TableBody>{users.items.map(user => <TableRow key={user.id}><TableCell>{user.tenant_name ?? user.tenant_id}</TableCell><TableCell>{user.name}</TableCell><TableCell>{user.permissions.length ? `${user.permissions.length} 项权限` : '—'}</TableCell><TableCell>{user.enabled ? '启用' : '停用'}</TableCell></TableRow>)}</TableBody></Table><Pager page={users.page} size={users.size} total={users.total} onChange={setUserPage}/></> : <Empty title="当前筛选范围暂无用户"/>}</CardContent></Card>
      </div>
    </>}
  </>
}
