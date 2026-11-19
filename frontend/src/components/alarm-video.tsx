import { useState } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { dateTime } from '@/lib/format'
import { videoApi, type AlarmVideoIntent } from '@/lib/video'
import type { Alarm } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Empty, ErrorNotice, Loading } from '@/components/common'
import { VideoMediaPlayer } from '@/components/video-media-player'
import { VideoCapturePanel } from '@/components/video-capture-panel'

export function AlarmVideo({ alarm }: { alarm: Alarm }) {
  const permissions = useSession(s => s.permissions)
  const [open, setOpen] = useState(false)
  if (!permissions.includes('video:read')) return null
  return <section className="mt-4"><Button type="button" variant="outline" aria-expanded={open} onClick={() => setOpen(value => !value)}>{open ? '收起关联视频' : '关联视频与抓图证据'}</Button>
    {open && <AlarmVideoResources alarm={alarm} canPlay={permissions.includes('video:play')}/>}</section>
}

function AlarmVideoResources({ alarm, canPlay }: { alarm: Alarm; canPlay: boolean }) {
  const list = useResource(`alarm-video:${alarm.tenant_id}:${alarm.id}`, signal => videoApi.alarmIntents(alarm.tenant_id, alarm.id, signal))
  const [selected, setSelected] = useState<string | null>(null)
  if (list.loading) return <Loading/>
  if (list.error != null) return <ErrorNotice error={list.error} retry={list.refresh}/>
  if (!list.data?.length) return <Empty title="该报警设备未关联可见视频">不会推断或生成视频资源。</Empty>
  const current = list.data.find(row => row.resource.id === selected)
  return <div className="form-stack">
    <div className="row-actions">{list.data.map(row => <Button key={row.resource.id} type="button" variant="outline" aria-pressed={selected === row.resource.id} onClick={() => setSelected(row.resource.id)}>{row.resource.name}</Button>)}<Button type="button" variant="outline" onClick={list.refresh}>刷新关联及证据</Button></div>
    {current && <AlarmVideoResource key={current.resource.id} row={current} alarm={alarm} canPlay={canPlay}/>}
  </div>
}

function AlarmVideoResource({ row, alarm, canPlay }: { row: AlarmVideoIntent; alarm: Alarm; canPlay: boolean }) {
  const resource = useResource(`alarm-video-detail:${alarm.tenant_id}:${row.resource.id}`, signal => videoApi.detail(alarm.tenant_id, row.resource.id, signal))
  const detail = resource.data
  const [captureId, setCaptureId] = useState<string | null>(null)
  const capture = row.captures.find(item => item.id === captureId)
  const associations = detail?.associations.filter(item => item.device_id === alarm.device_id) ?? []
  return <div className="form-stack"><h3>{row.resource.name}</h3>
    {row.intent && <p className="muted-note">已登记{row.intent.purpose === 'PLAYBACK' ? '回看' : '预览'}意图 · {dateTime(row.intent.created_at)} · 有效期至 {dateTime(row.intent.expires_at)}；意图不代表已经播放。</p>}
    {row.captures.length > 0 && <><h4>本报警的自动抓图记录</h4><div className="row-actions">{row.captures.map(item => <Button key={item.id} type="button" variant="outline" aria-pressed={captureId === item.id} onClick={() => setCaptureId(item.id)}>{dateTime(item.created_at)} · {item.state === 'SUCCEEDED' ? '已保存图像' : item.state === 'QUEUED' || item.state === 'RUNNING' ? '抓图未完成' : item.failure_reason ?? item.state}</Button>)}</div></>}
    {resource.loading ? <Loading/> : resource.error != null ? <ErrorNotice error={resource.error} retry={resource.refresh}/> : detail?.resource.id !== row.resource.id || detail.resource.tenant_id !== alarm.tenant_id ? <Empty title="视频资源身份未确认"/> : <>
      <VideoMediaPlayer resource={detail.resource} associations={associations} canPlay={canPlay}/>
      {canPlay && <><h4>本次授权抓图</h4><VideoCapturePanel tenantId={alarm.tenant_id} resourceId={detail.resource.id} associations={associations}/></>}
      {canPlay && capture && <><h4>选中报警抓图证据</h4><VideoCapturePanel tenantId={alarm.tenant_id} resourceId={detail.resource.id} associations={associations} initialJob={capture}/></>}
    </>}
  </div>
}
