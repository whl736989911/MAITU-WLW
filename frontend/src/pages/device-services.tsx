import { useState, type FormEvent } from 'react'
import { Search } from 'lucide-react'
import { api } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import type { Device } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent } from '@/components/ui/card'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, RefreshButton } from '@/components/common'
import { DeviceServicesPanel } from '@/components/device-services-panel'

export function DeviceServicesPage() {
  const { user, permissions, generation } = useSession()
  if (!user || user.must_change_password || !permissions.includes('device-services:read') || !permissions.includes('device:read'))
    return <Empty title="当前账户无设备服务查看权限">需要设备读取与设备服务读取权限。查询、下发和工程操作分别受独立权限与型号能力约束。</Empty>
  return <ServiceWorkspacePage key={generation}/>
}

function ServiceWorkspacePage() {
  const { scopeTenantIds, scopePlaceIds, scopeInitialized } = useSession()
  const emptyScope = scopeInitialized && scopeTenantIds.length === 0 && scopePlaceIds.length === 0
  const [draft, setDraft] = useState(''), [keyword, setKeyword] = useState(''), [page, setPage] = useState(1)
  const [selected, setSelected] = useState<Device | null>(null)
  const devices = useResource(`service-devices:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}:${page}:${keyword}`, signal => api.devices(page, keyword, 20, signal, undefined, undefined, undefined, scopeTenantIds, scopePlaceIds), scopeInitialized && !emptyScope)
  function search(event: FormEvent) { event.preventDefault(); setSelected(null); setPage(1); setKeyword(draft.trim()) }
  return <>
    <PageHeading eyebrow="DEVICE SERVICES · 设备服务" title="服务能力、参数与命令回执">
      <RefreshButton onClick={devices.refresh}/>
    </PageHeading>
    <p className="muted-note">打开页面只读取缓存的元数据与已保存快照，不会唤醒设备。所有读取、参数下发与控制都必须由操作者显式提交为持久命令；厂商接受（code 0）不等于物理执行成功。</p>
    <Card className="list-card"><CardContent>
      <form onSubmit={search} className="search-form"><div className="input-icon"><Search size={16}/><Input aria-label="搜索设备" placeholder="按设备名称、ID或型号字面搜索" value={draft} onChange={event => setDraft(event.target.value)}/></div><Button type="submit" variant="outline">查询</Button></form>
      {emptyScope && <Empty title="当前范围未选择">先选择授权单位或位置。</Empty>}
      {devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : devices.data && (devices.data.items.length
        ? <div className="service-device-picker">{devices.data.items.map(device => <button type="button" key={device.id} className={selected?.id === device.id ? 'version-choice selected' : 'version-choice'} onClick={() => setSelected(device)}>
          <strong>{device.name ?? device.id}</strong>
          <span className="mono">{device.id} · {device.model ?? '型号未记录'}</span>
          <small>{device.place_name ?? '位置未记录'} · {device.integration_state ?? '接入状态未确认'}</small>
        </button>)}</div>
        : <Empty title="没有匹配设备">只展示当前授权范围内的真实设备；不使用旧模拟或默认候选。</Empty>)}
      {devices.data && <Pager page={devices.data.page} size={devices.data.size} total={devices.data.total} onChange={next => { setSelected(null); setPage(next) }}/>}
    </CardContent></Card>
    {selected && <Card className="list-card"><CardContent>
      <div className="section-header"><h3>{selected.name ?? selected.id}</h3><span className="muted-note mono">{selected.id} · revision {selected.revision ?? '未提供'}</span></div>
      {Number.isSafeInteger(selected.revision)
        ? <DeviceServicesPanel deviceId={selected.id} tenantId={selected.tenant_id}/>
        : <p className="notice">设备未提供有效 revision，不能创建命令；请刷新设备列表。</p>}
    </CardContent></Card>}
  </>
}
