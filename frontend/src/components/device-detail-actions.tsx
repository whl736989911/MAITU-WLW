import { useState } from 'react'
import { Ban, RotateCcw, VolumeX } from 'lucide-react'
import { dateTime } from '@/lib/format'
import { deviceServicesApi, type ServiceCapabilities, type ServiceDefinition } from '@/lib/device-services'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { Button } from './ui/button'
import { Sheet, SheetContent } from './ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Empty, ErrorNotice, Loading, Pager } from './common'
import { CommandDetailView, CommandForm, ReconcileButton, stateLabel } from './device-services-panel'

type Target = { deviceId: string; tenantId: string; deviceName?: string | null }
type Draft = { service: ServiceDefinition; capability: ServiceCapabilities; values?: Record<string, string> }
const READ = 'parameters.read', WRITE = 'parameters.write'
const controls = [
  { label: '静音', hik: 'hik.v1.silence', generic: 'control.silence', icon: VolumeX },
  { label: '复位', hik: 'hik.v2.reset', generic: 'control.reset', icon: RotateCcw },
  { label: '禁用', hik: null, generic: 'control.disable', icon: Ban },
] as const
const parameterReferences: Record<string, [string, string]> = {
  heartbeatCycle: ['心跳周期', '60–2880 分钟，海康页面建议 1320 分钟'],
  alarmSilencePeriod: ['报警消音周期', '90 秒，或 5 / 8 / 12 / 15 分钟'],
  faultSilencePeriod: ['故障消音周期', '6 / 12 / 18 / 24 小时'],
  nightModeEnabled: ['夜间模式', '开 / 关'],
  lightSwitch: ['指示灯开关', '开 / 关'],
}
const sensitive = /password|secret|token|credential|verification/i
const text = (value: unknown) => typeof value === 'string' ? value : ''

function useCapability(target: Target) {
  const read = useSession(s => s.permissions.includes('device-services:read'))
  const engineering = useSession(s => s.permissions.includes('device-services:engineering'))
  const execute = useSession(s => s.permissions.includes('device-services:execute')) || engineering
  const query = useSession(s => s.permissions.includes('device-services:query')) || engineering
  const { deviceId, tenantId } = target
  const capability = useResource(`detail-capability:${tenantId}:${deviceId}`, signal => deviceServicesApi.capabilities(deviceId, tenantId, signal), read)
  const data = !capability.loading && capability.error == null && capability.data?.tenant_id === tenantId && capability.data.device_id === deviceId ? capability.data : undefined
  const allowed = (service: ServiceDefinition | undefined) => !!data && !!service?.dispatch_ready && execute && (service.risk_level !== 'ENGINEERING' || engineering)
  return { read, query, capability, data, allowed }
}

function ConfirmCommand({ target, draft, close, done }: { target: Target; draft: Draft | null; close: () => void; done: () => void }) {
  return <Sheet open={draft !== null} onOpenChange={open => { if (!open) close() }}><SheetContent className="review-sheet sm:max-w-xl">
    {draft && <CommandForm key={draft.service.service_key} {...target} service={draft.service} initialValues={draft.values}
      deviceRevision={draft.capability.device_revision} bindingId={draft.capability.binding?.id} onClose={close} onCreated={done}/>}
  </SheetContent></Sheet>
}

export function DeviceControlPanel(target: Target) {
  const { read, data, capability, allowed } = useCapability(target)
  const [draft, setDraft] = useState<Draft | null>(null), [notice, setNotice] = useState('')
  const hik = data?.driver_key === 'HIKVISION_FIRECLOUD'
  return <>
    <div className="device-control-actions">
      {controls.map(({ label, hik: hikKey, generic, icon: Icon }) => {
        const service = data?.services.find(row => row.service_key === (hik ? hikKey : generic) && row.category === 'CONTROL')
        const ready = allowed(service)
        const reason = !read ? '无设备服务权限' : hik && hikKey === null ? '海康开放接口未确认设备禁用操作，不能用屏蔽或远程停机替代'
          : service?.dispatch_blocked_reason ?? (service ? '无对应执行权限或能力尚未确认' : '未配置此动作的真实驱动能力')
        return <div key={label}><Button type="button" size="sm" variant="outline" disabled={!ready} title={!ready ? reason : undefined} onClick={() => {
          if (data && service && ready) { setNotice(''); setDraft({ capability: data, service }) }
        }}><Icon size={15}/>{label}</Button></div>
      })}
    </div>
    {capability.error != null && <ErrorNotice error={capability.error} retry={capability.refresh}/>}
    {notice ? <p role="status" className="muted-note">{notice}</p> : <p className="muted-note">{hik ? '仅开放已核验的型号能力；海康未提供已确认的禁用接口。' : '需当前设备支持及账号授权。'}厂商接受不代表终端执行。</p>}
    <ConfirmCommand target={target} draft={draft} close={() => setDraft(null)} done={() => { setDraft(null); capability.refresh(); setNotice('控制指令已入队。结果可在设备服务的命令历史核对。') }}/>
  </>
}

export function DeviceRemoteParametersPanel(target: Target & { model?: string | null }) {
  const { deviceId, tenantId } = target
  const { read, query, data, capability, allowed } = useCapability(target)
  const [draft, setDraft] = useState<Draft | null>(null), [openId, setOpenId] = useState<string | null>(null), [page, setPage] = useState(1)
  const [notice, setNotice] = useState('')
  const parameters = useResource(`detail-params:${tenantId}:${deviceId}`, signal => deviceServicesApi.parameters(deviceId, tenantId, signal), read)
  const commands = useResource(`detail-knob-writes:${tenantId}:${deviceId}:${page}`, signal => deviceServicesApi.commands(deviceId, page, tenantId, signal, WRITE), read)
  const reads = useResource(`detail-knob-reads:${tenantId}:${deviceId}`, signal => deviceServicesApi.commands(deviceId, 1, tenantId, signal, READ), read)
  const latest = useResource(`detail-knob-write:${tenantId}:${deviceId}`, signal => deviceServicesApi.commands(deviceId, 1, tenantId, signal, WRITE), read && page !== 1)
  const current = !parameters.loading && parameters.error == null && parameters.data?.device_id === deviceId ? parameters.data.snapshot : null
  const latestWrite = (!commands.loading && page === 1 ? commands.data : !latest.loading && latest.error == null ? latest.data : undefined)?.items[0]
  const latestRead = !reads.loading && reads.error == null ? reads.data?.items[0] : undefined
  const submitted = useResource(`detail-knob-last:${tenantId}:${latestWrite?.id ?? ''}`, signal => deviceServicesApi.command(latestWrite!.id, tenantId, signal), !!latestWrite)
  const lastArgs = !submitted.loading && submitted.error == null && submitted.data?.command.device_id === deviceId && submitted.data.command.tenant_id === tenantId ? submitted.data.arguments : undefined
  const reading = data?.services.find(row => row.service_key === READ && row.category === 'QUERY')
  const writing = data?.services.find(row => row.service_key === WRITE && row.category === 'CONFIGURATION')
  const rows = (Array.isArray(current?.values) ? current.values as Record<string, unknown>[] : []).filter(v => v && text(v.paramType) && !sensitive.test(text(v.paramType)) && !sensitive.test(text(v.paramName)))
  const refs = data?.driver_key === 'HIKVISION_FIRECLOUD' && target.model === '800235' ? parameterReferences : {}
  const extra = Object.entries(refs).filter(([key]) => !rows.some(row => row.paramType === key)).map(([paramType, [paramName]]) => ({ paramType, paramName }))
  function refresh() { capability.refresh(); parameters.refresh(); commands.refresh(); reads.refresh(); latest.refresh(); submitted.refresh() }
  function start(service: ServiceDefinition | undefined, values?: Record<string, string>) { if (data && service && allowed(service)) { setNotice(''); setDraft({ capability: data, service, values }) } }
  if (!read) return <Empty title="无设备服务读取权限"/>
  return <section aria-label="本设备远程参数配置">
    <div className="device-detail-toolbar">
      <Button type="button" size="sm" variant="outline" disabled={!allowed(reading)} onClick={() => start(reading)}>获取参数</Button>
      <Button type="button" size="sm" variant="outline" disabled={!allowed(writing)} onClick={() => start(writing)}>参数配置下发</Button>
      <Button type="button" size="sm" variant="ghost" onClick={refresh}>刷新参数与结果</Button>
    </div>
    {capability.error != null && <ErrorNotice error={capability.error} retry={capability.refresh}/>}
    {parameters.error != null && <ErrorNotice error={parameters.error} retry={parameters.refresh}/>}
    {notice && <p role="status" className="notice">{notice}</p>}
    {writing && !writing.dispatch_ready && <p className="muted-note">下发未开放：{writing.dispatch_blocked_reason ?? writing.dispatch_blocked_code}</p>}
    <p className="muted-note">当前值来自设备快照；参考值不会自动填入下发。未下发、厂商接受和终端结果分别记录。</p>
    {latestRead && <p>最近获取：{dateTime(latestRead.created_at)} · {stateLabel[latestRead.state]}</p>}
    {parameters.loading ? <Loading/> : <Table><TableHeader><TableRow><TableHead>参数</TableHead><TableHead>设备当前值<br/><small>上报：{current ? dateTime(current.observed_at) : '未获取'}</small></TableHead><TableHead>最近下发值<br/><small>{latestWrite ? dateTime(latestWrite.created_at) : '无记录'}</small></TableHead><TableHead>参考值</TableHead></TableRow></TableHeader><TableBody>
      {[...rows, ...extra].map((row: Record<string, unknown>, index) => {
        const key = text(row.paramType), reference = refs[key], channelId = text(row.channelId) || text(row.channelID)
        const currentValue = typeof row.value === 'string' || typeof row.value === 'number' || typeof row.value === 'boolean' ? String(row.value) : '未获取'
        const last = lastArgs?.paramType === key && (!channelId || channelId === lastArgs.channelId) ? text(lastArgs.value) : ''
        return <TableRow key={`${key}:${channelId}:${index}`}><TableCell>{text(row.paramName) || reference?.[0] || key}{channelId && <div className="table-sub">通道 {channelId}</div>}</TableCell><TableCell>{currentValue}</TableCell><TableCell>{last || '—'} {channelId && allowed(writing) && <Button type="button" size="sm" variant="link" onClick={() => start(writing, { channelId, paramType: key })}>配置</Button>}</TableCell><TableCell>{reference?.[1] ?? '—'}</TableCell></TableRow>
      })}
    </TableBody></Table>}
    <h3>参数下发记录</h3>
    {commands.loading ? <Loading/> : commands.error != null ? <ErrorNotice error={commands.error} retry={commands.refresh}/> : commands.data && <>
      {commands.data.items.length ? <Table><TableHeader><TableRow><TableHead>提交时间</TableHead><TableHead>下发状态</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>
        {commands.data.items.map(row => <TableRow key={row.id}><TableCell>{dateTime(row.created_at)}</TableCell><TableCell>{stateLabel[row.state]}</TableCell><TableCell><Button type="button" size="sm" variant="link" onClick={() => setOpenId(row.id)}>参数、操作人与回执</Button>{query && (row.state === 'ACCEPTED' || row.state === 'UNKNOWN') && <ReconcileButton commandId={row.id} tenantId={tenantId} onDone={refresh}/>}</TableCell></TableRow>)}
      </TableBody></Table> : <Empty title="尚无参数下发记录"/>}
      <Pager page={commands.data.page} size={commands.data.size} total={commands.data.total} onChange={setPage}/>
    </>}
    <ConfirmCommand target={target} draft={draft} close={() => setDraft(null)} done={() => { setDraft(null); setPage(1); refresh(); setNotice('参数命令已入队，结果以命令记录为准。') }}/>
    <Sheet open={openId !== null} onOpenChange={open => { if (!open) setOpenId(null) }}><SheetContent className="review-sheet sm:max-w-2xl">{openId && <CommandDetailView commandId={openId} tenantId={tenantId} onClose={() => setOpenId(null)}/>}</SheetContent></Sheet>
  </section>
}
