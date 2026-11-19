import { useEffect, useId, useRef, useState } from 'react'
import { useSession } from '@/lib/store'
import { dateTime } from '@/lib/format'
import { fetchCaptureImage, videoApi, type CaptureJob, type VideoAssociation } from '@/lib/video'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'

type Props = { tenantId: string; resourceId: string; associations: VideoAssociation[]; initialJob?: CaptureJob }
const labels: Record<CaptureJob['state'], string> = { QUEUED: '等待抓图', RUNNING: '正在抓图', SUCCEEDED: '已保存抓图证据', FAILED: '抓图失败', UNSUPPORTED: '当前协议不支持', NOT_CONFIGURED: '抓图服务未配置' }

export function VideoCapturePanel(props: Props) {
  const generation = useSession(s => s.authGeneration)
  const associationKey = props.associations.map(item => `${item.id}:${item.revision}:${item.state}:${item.device_archived}`).join(',')
  return <ScopedCapturePanel key={`${generation}:${props.tenantId}:${props.resourceId}:${associationKey}:${props.initialJob?.id ?? ''}`} {...props}/>
}

function ScopedCapturePanel({ tenantId, resourceId, associations, initialJob }: Props) {
  const selectId = useId()
  const active = associations.filter(item => item.state === 'ACTIVE' && item.device_archived === 'ACTIVE')
  const [deviceId, setDeviceId] = useState(initialJob?.device_id ?? active[0]?.device_id ?? '')
  const [job, setJob] = useState<CaptureJob | null>(initialJob ?? null)
  const [image, setImage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const controller = useRef<AbortController | null>(null)
  const timer = useRef<number | undefined>(undefined)
  const imageUrl = useRef<string | null>(null)

  function cancel() {
    controller.current?.abort()
    window.clearTimeout(timer.current); timer.current = undefined
    if (imageUrl.current) { URL.revokeObjectURL(imageUrl.current); imageUrl.current = null }
  }
  useEffect(() => cancel, [])

  async function observe(jobId: string, signal: AbortSignal) {
    try {
      const current = await videoApi.captureJob(tenantId, resourceId, jobId, signal)
      if (signal.aborted) return
      if (current.id !== jobId || current.resource_id !== resourceId || current.tenant_id !== tenantId || current.device_id !== deviceId)
        throw new Error('抓图任务身份未确认，拒绝显示结果')
      setJob(current)
      if (current.state === 'QUEUED' || current.state === 'RUNNING') {
        timer.current = window.setTimeout(() => { void observe(jobId, signal) }, 1500)
      } else if (current.state === 'SUCCEEDED') {
        if (!current.image_sha256 || !current.image_bytes || !current.capture_at) throw new Error('抓图成功记录缺少图像证据')
        const blob = await fetchCaptureImage(resourceId, jobId, tenantId, signal)
        if (signal.aborted) return
        if (blob.type !== 'image/jpeg' || blob.size !== current.image_bytes) throw new Error('抓图内容与记录不一致')
        if (imageUrl.current) URL.revokeObjectURL(imageUrl.current)
        imageUrl.current = URL.createObjectURL(blob)
        setImage(imageUrl.current)
      }
    } catch (failure) { if (!signal.aborted) setError(failure) }
  }

  async function start() {
    if (!deviceId || busy) return
    cancel()
    setImage(null); setJob(null); setError(undefined); setBusy(true)
    const pending = new AbortController()
    controller.current = pending
    try {
      const created = await videoApi.createCaptureJob(tenantId, resourceId, deviceId, pending.signal)
      if (pending.signal.aborted) return
      if (created.resource_id !== resourceId || created.tenant_id !== tenantId || created.device_id !== deviceId || !created.id)
        throw new Error('抓图任务身份未确认')
      setJob(created)
      await observe(created.id, pending.signal)
    } catch (failure) { if (!pending.signal.aborted) setError(failure) }
    finally { if (!pending.signal.aborted) setBusy(false) }
  }

  function refresh() {
    if (!job) return
    controller.current?.abort()
    window.clearTimeout(timer.current)
    const pending = new AbortController()
    controller.current = pending
    setError(undefined)
    void observe(job.id, pending.signal)
  }

  if (active.length === 0) return <p className="muted-note">没有有效的设备关联，不能创建抓图任务。</p>
  const running = busy || job?.state === 'QUEUED' || job?.state === 'RUNNING'
  return <div className="form-stack">
    <div className="field"><Label htmlFor={selectId}>抓图授权设备</Label><select id={selectId} className="native-select" value={deviceId} onChange={event => {
      cancel(); setDeviceId(event.target.value); setJob(null); setImage(null); setBusy(false); setError(undefined)
    }}>{active.map(item => <option key={item.device_id} value={item.device_id}>{item.device_name ?? item.device_id}</option>)}</select></div>
    <div className="row-actions"><Button type="button" variant="outline" disabled={!deviceId || running} onClick={() => void start()}>创建抓图任务</Button>{job && <Button type="button" variant="outline" onClick={refresh}>刷新此任务</Button>}</div>
    {error != null && <ErrorNotice error={error} retry={job ? refresh : () => setError(undefined)}/>}
    {job && <p className="muted-note" role="status">{labels[job.state] ?? '任务状态未确认'} · {job.id}{job.failure_code ? ` · ${job.failure_code} ${job.failure_reason ?? ''}` : ''}{job.capture_at ? ` · 平台抓图时间 ${dateTime(job.capture_at)}` : ''}</p>}
    {image && <img src={image} alt="该设备授权抓图任务结果" style={{ maxWidth: '100%' }}/>}
  </div>
}
