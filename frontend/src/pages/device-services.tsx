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
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'

export function DeviceServicesPage() {
  const { user, permissions, generation, scopeTenantIds, scopePlaceIds } = useSession()
  if (!user || user.must_change_password || !permissions.includes('device-services:read') || !permissions.includes('device:read'))
    return <Empty title="当前账户无设备服务查看权限">需要设备读取与设备服务读取权限。查询、下发和工程操作分别受独立权限与型号能力约束。</Empty>
  return <ServiceWorkspacePage key={JSON.stringify([generation, scopeTenantIds, scopePlaceIds])}/>
}

function ServiceWorkspacePage() {
  const { audience, scopeTenantIds, scopePlaceIds, scopeInitialized } = useSession()
  const emptyScope = scopeInitialized && scopeTenantIds.length === 0 && scopePlaceIds.length === 0
  const [draft, setDraft] = useState(''), [keyword, setKeyword] = useState(''), [page, setPage] = useState(1)
  const [selected, setSelected] = useState<Device | null>(null)
  const devices = useResource(`service-devices:${scopeTenantIds.join(',')}:${scopePlaceIds.join(',')}:${page}:${keyword}`, signal => api.devices(page, keyword, 20, signal, undefined, undefined, undefined, scopeTenantIds, scopePlaceIds), scopeInitialized && !emptyScope)
  function search(event: FormEvent) { event.preventDefault(); setSelected(null); setPage(1); setKeyword(draft.trim()) }
  const management = audience === 'management'
  const refresh = <RefreshButton onClick={devices.refresh}/>
  const searchForm = <form onSubmit={search} className="search-form"><div className="input-icon"><Search size={16}/><Input aria-label="搜索设备" placeholder="按名称、设备编号或型号查询" value={draft} onChange={event => setDraft(event.target.value)}/></div><Button type="submit" variant="outline">查询</Button></form>
  const guidance = '按设备查看接入能力、已保存参数和命令回执。同为海康设备，因型号、来源绑定及能力验证不同，可用操作也会不同。打开设备不会查询厂商或唤醒设备。'
  return <>
    <PageHeading eyebrow="DEVICE SERVICES · 设备服务" title="服务能力、参数与命令回执">{!management && refresh}</PageHeading>
    {!management && <p className="muted-note">{guidance}</p>}
    <Card className={management ? 'list-card management-list-panel' : 'list-card'}><CardContent>
      {management ? <>
        <div className="management-list-bar"><h2>选择服务设备</h2><div className="list-toolbar management-list-actions">{refresh}</div></div>
        <p className="management-list-context">{guidance}</p>
        <div className="list-toolbar">{searchForm}</div>
      </> : searchForm}
      {emptyScope && <Empty title="当前范围未选择">先选择授权单位或位置。</Empty>}
      {devices.loading ? <Loading/> : devices.error != null ? <ErrorNotice error={devices.error} retry={devices.refresh}/> : devices.data && (devices.data.items.length
        ? <div className="service-device-picker">{devices.data.items.map(device => <button type="button" key={device.id} className={selected?.id === device.id ? 'version-choice selected' : 'version-choice'} onClick={() => setSelected(device)}>
          <strong>{device.name ?? device.id}</strong>
          <span className="mono">{device.device_eui ?? device.id} · {device.model ?? '型号未记录'}</span>
          <small>{device.place_name ?? '位置未记录'} · 查看服务能力</small>
        </button>)}</div>
        : <Empty title="没有匹配设备">只展示当前授权范围内的真实设备；不使用旧模拟或默认候选。</Empty>)}
      {devices.data && <Pager page={devices.data.page} size={devices.data.size} total={devices.data.total} onChange={next => { setSelected(null); setPage(next) }}/>}
    </CardContent></Card>
    <Sheet open={selected !== null} onOpenChange={open => { if (!open) setSelected(null) }}>
      <SheetContent className="review-sheet sm:max-w-5xl">
        {selected && <>
          <SheetHeader className="review-header"><SheetTitle>{selected.name ?? selected.id} · 设备服务</SheetTitle>
            <SheetDescription>设备编号 {selected.device_eui ?? selected.id}。此处显示已保存参数和命令回执，不会自动发起厂商查询。</SheetDescription>
          </SheetHeader>
          <div className="review-body">
            <DeviceServicesPanel key={selected.id} deviceId={selected.id} tenantId={selected.tenant_id}/>
          </div>
        </>}
      </SheetContent>
    </Sheet>
  </>
}
