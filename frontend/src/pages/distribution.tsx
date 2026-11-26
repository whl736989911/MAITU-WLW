import { useState, type FormEvent } from 'react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading } from '@/components/common'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { PageHelp } from '@/components/page-tools'

const x = (lng: number) => ((lng + 180) / 360) * 100
const y = (lat: number) => ((90 - lat) / 180) * 100

export function DistributionPage() { return <DistributionGate><DistributionContent/></DistributionGate> }

function DistributionGate({ children }: { children: React.ReactNode }) {
  const { user, audience, permissions } = useSession()
  if (!user || user.must_change_password || audience !== 'management' || !permissions.includes('device:read')) return <Empty title="当前账户无设备分布权限"/>
  return <>{children}</>
}

function DistributionContent() {
  const session = useSession(), permissions = session.permissions
  const canWrite = permissions.includes('device:write')
  const tenantIds = session.scopeTenantIds, placeIds = session.scopePlaceIds, initialized = session.scopeInitialized
  const empty = initialized && tenantIds.length === 0 && placeIds.length === 0
  const scopeKey = `${tenantIds.join(',')}:${placeIds.join(',')}:${initialized}`
  const distribution = useResource(`device-distribution:${scopeKey}`, signal => api.deviceDistribution(signal, tenantIds, placeIds), initialized && !empty)
  const devices = useResource(`distribution-devices:${scopeKey}`, signal => api.devices(1, '', 100, signal, undefined, undefined, undefined, tenantIds, placeIds), canWrite && initialized && !empty)
  const [deviceId, setDeviceId] = useState(''), [lat, setLat] = useState(''), [lng, setLng] = useState('')
  const [notice, setNotice] = useState(''), [error, setError] = useState<unknown>(null)
  const [coordsOpen, setCoordsOpen] = useState(false)
  const selected = devices.data?.items.find(device => device.id === deviceId)

  async function save(event: FormEvent) {
    event.preventDefault(); setError(undefined); setNotice('')
    if (!selected || !selected.tenant_id) { setError(new Error('请选择具有明确归属单位的设备')); return }
    if (!lat.trim() || !lng.trim()) { setError(new Error('请填写纬度和经度')); return }
    const latitude = Number(lat), longitude = Number(lng)
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) { setError(new Error('纬度需在 -90..90、经度需在 -180..180')); return }
    try {
      await api.setDeviceLocation(selected.id, { latitude, longitude, expected_revision: selected.revision ?? 0 }, undefined, selected.tenant_id)
      setNotice(`已更新 ${selected.name} 的坐标`); distribution.refresh(); devices.refresh()
    } catch (e) { setError(e) }
  }
  async function clear() {
    if (!selected || !selected.tenant_id) { setError(new Error('请选择具有明确归属单位的设备')); return }
    setError(undefined); setNotice('')
    try {
      await api.clearDeviceLocation(selected.id, { expected_revision: selected.revision ?? 0 }, undefined, selected.tenant_id)
      setNotice(`已清除 ${selected.name} 的坐标`); distribution.refresh(); devices.refresh()
    } catch (e) { setError(e) }
  }
  const data = distribution.data
  return <><PageHeading eyebrow="MAP · 无底图分布" title="设备分布"><PageHelp title="分布视图说明">仅展示人工维护的经纬度，不推断坐标、不接入地图服务、不引入建筑楼层体系。客户界面仍只显示文字地址。</PageHelp></PageHeading>
    <Card className="list-card management-list-panel"><CardContent>
      <div className="management-list-bar"><h2>坐标与设备</h2>
        {canWrite && <div className="list-toolbar management-list-actions"><Button variant="outline" onClick={() => setCoordsOpen(true)}>维护坐标</Button></div>}
      </div>
      {empty ? <Empty title="当前范围未选择">请在左侧单位 / 位置树选择至少一个范围；清空后不会读取全部设备。</Empty> : distribution.loading ? <Loading/> : distribution.error != null ? <ErrorNotice error={distribution.error} retry={distribution.refresh}/> : data && <>
        <p className="management-list-context">已维护 {data.with_coordinates} 台 · 未维护 {data.without_coordinates} 台</p>
        {data.points.length > 0 && <svg viewBox="0 0 100 100" role="img" aria-label="设备经纬度散布（无底图）" className="distribution-plot">
          <line x1="0" y1="50" x2="100" y2="50" stroke="var(--border)" strokeWidth="0.3"/>
          <line x1="50" y1="0" x2="50" y2="100" stroke="var(--border)" strokeWidth="0.3"/>
          {data.points.map(point => <circle key={point.id} cx={x(Number(point.longitude))} cy={y(Number(point.latitude))} r="1.6" fill="var(--accent)"><title>{`${point.name} · ${point.latitude},${point.longitude}`}</title></circle>)}
        </svg>}
        <p className="muted-note">{data.semantics}</p>
        {data.points.length ? <Table><TableHeader><TableRow><TableHead>设备</TableHead><TableHead>地址</TableHead><TableHead>纬度</TableHead><TableHead>经度</TableHead><TableHead>物理状态</TableHead></TableRow></TableHeader><TableBody>{data.points.map(point => <TableRow key={point.id}><TableCell><strong>{point.name}</strong><div className="table-sub mono">{point.id}</div></TableCell><TableCell>{point.address}</TableCell><TableCell className="mono">{point.latitude}</TableCell><TableCell className="mono">{point.longitude}</TableCell><TableCell>{point.physical_state}</TableCell></TableRow>)}</TableBody></Table> : <Empty title="尚无人工维护坐标的设备"/>}
      </>}
    </CardContent></Card>
    {canWrite && <Sheet open={coordsOpen} onOpenChange={setCoordsOpen}><SheetContent className="review-sheet sm:max-w-xl"><SheetHeader className="review-header"><SheetTitle>维护设备坐标</SheetTitle></SheetHeader><div className="review-body">{coordsOpen && <Card><CardContent>
      <form className="form-stack" onSubmit={save}>
        <div className="field"><Label htmlFor="loc-device">设备</Label><select id="loc-device" className="native-select" value={deviceId} onChange={e => setDeviceId(e.target.value)}><option value="">请选择设备</option>{devices.data?.items.map(device => <option key={device.id} value={device.id}>{device.name} · {device.device_eui} · {device.tenant_id}</option>)}</select></div>
        <div className="field"><Label htmlFor="loc-lat">纬度（-90..90）</Label><Input id="loc-lat" value={lat} onChange={e => setLat(e.target.value)}/></div>
        <div className="field"><Label htmlFor="loc-lng">经度（-180..180）</Label><Input id="loc-lng" value={lng} onChange={e => setLng(e.target.value)}/></div>
        {error != null && <ErrorNotice error={error}/>}
        {notice && <p role="status" className="notice">{notice}</p>}
        <div className="identity-form-actions"><Button type="submit" disabled={!selected?.tenant_id}>保存坐标</Button><Button type="button" variant="outline" disabled={!selected?.tenant_id} onClick={() => void clear()}>清除坐标</Button></div>
      </form>
      {selected && <p className="muted-note">当前 revision {selected.revision} · 更新于 {dateTime(selected.last_message_at)}</p>}
    </CardContent></Card>}</div></SheetContent></Sheet>}
  </>
}
