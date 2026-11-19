import { useEffect, useId, useRef, useState } from 'react'
import { mediaUrl, openMediaSession, revokeMediaSession, type MediaSession, type VideoAssociation, type VideoResource } from '@/lib/video'
import { useSession } from '@/lib/store'
import { ErrorNotice } from '@/components/common'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'

type MediaState = 'IDLE' | 'REQUESTED' | 'DECODING' | 'PLAYING' | 'SNAPSHOT_LOADED' | 'FAILED' | 'UNSUPPORTED'

/**
 * Media is only requested after an explicit, device-scoped session. We never claim playback from a session
 * response alone: the label changes only on browser decode/play events, otherwise it reports fetch-only.
 */
export function VideoMediaPlayer({ resource, associations, canPlay }: { resource: VideoResource; associations: VideoAssociation[]; canPlay: boolean }) {
  const generation = useSession(s => s.authGeneration)
  const associationKey = associations.map(item => `${item.id}:${item.revision}:${item.state}:${item.device_archived}`).join(',')
  return <ScopedVideoMediaPlayer key={`${generation}:${resource.id}:${resource.revision}:${associationKey}`} resource={resource} associations={associations} canPlay={canPlay}/>
}

function ScopedVideoMediaPlayer({ resource, associations, canPlay }: { resource: VideoResource; associations: VideoAssociation[]; canPlay: boolean }) {
  const selectId = useId()
  const pending = useRef<AbortController | null>(null)
  const active = associations.filter(item => item.state === 'ACTIVE' && item.device_archived === 'ACTIVE')
  const [deviceId, setDeviceId] = useState(active[0]?.device_id ?? '')
  const [session, setSession] = useState<MediaSession | null>(null)
  const [state, setState] = useState<MediaState>('IDLE')
  const [error, setError] = useState<unknown>()
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const hlsRef = useRef<{ destroy: () => void } | null>(null)

  useEffect(() => {
    if (!session || session.media_kind !== 'HLS') return
    const video = videoRef.current
    if (!video) return
    const src = mediaUrl(resource.id, 'play')
    let disposed = false
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src
      return () => { video.removeAttribute('src'); video.load() }
    }
    void import('hls.js').then(module => {
      if (disposed) return
      const Hls = module.default
      if (!Hls.isSupported()) { setState('UNSUPPORTED'); return }
      const instance = new Hls({ enableWorker: true, lowLatencyMode: false })
      hlsRef.current = instance
      instance.loadSource(src)
      instance.attachMedia(video)
      instance.on(Hls.Events.ERROR, (_event: unknown, data: { fatal?: boolean }) => {
        if (data?.fatal) setState('FAILED')
      })
    }).catch(() => setState('UNSUPPORTED'))
    return () => {
      disposed = true
      hlsRef.current?.destroy()
      hlsRef.current = null
    }
  }, [session, resource.id])

  useEffect(() => () => {
    pending.current?.abort()
    hlsRef.current?.destroy()
    hlsRef.current = null
  }, [])

  if (!canPlay) return <p className="muted-note">当前账户没有视频播放权限；不会请求或展示媒体。</p>
  if (!resource.media_ready) return <p className="muted-note">{resource.note}</p>
  if (active.length === 0) return <p className="muted-note">没有可用的设备关联；先关联真实设备后才能申请媒体会话。</p>

  async function start(purpose: 'PLAYBACK' | 'SNAPSHOT') {
    setError(undefined)
    setState('REQUESTED')
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    try {
      const issued = await openMediaSession(resource.tenant_id, resource.id, deviceId, purpose, controller.signal)
      if (!controller.signal.aborted) setSession(issued)
    } catch (failure) {
      if (controller.signal.aborted) return
      setState('FAILED')
      setError(failure)
    }
  }

  async function stop() {
    pending.current?.abort()
    hlsRef.current?.destroy()
    hlsRef.current = null
    if (videoRef.current) { videoRef.current.pause(); videoRef.current.removeAttribute('src'); videoRef.current.load() }
    setSession(null)
    setState('IDLE')
    try { await revokeMediaSession(resource.tenant_id, resource.id) } catch (failure) { setError(failure) }
  }

  const label = state === 'REQUESTED' ? '已申请媒体会话；代理取回结果以浏览器加载/播放事件为准'
    : state === 'DECODING' ? '浏览器已加载媒体数据，尚未确认正在播放'
    : state === 'PLAYING' ? '浏览器已开始播放（仅代表本机浏览器解码）'
    : state === 'SNAPSHOT_LOADED' ? '抓图已由浏览器解码显示'
    : state === 'FAILED' ? '代理或浏览器未完成媒体加载'
    : state === 'UNSUPPORTED' ? '当前浏览器不支持该HLS播放方式'
    : '尚未申请媒体会话'

  return <div className="form-stack">
    <div className="field"><Label htmlFor={selectId}>媒体授权设备</Label>
      <select id={selectId} className="native-select" value={deviceId} onChange={event => { pending.current?.abort(); setDeviceId(event.target.value); setSession(null); setState('IDLE') }}>
        {active.map(item => <option key={item.device_id} value={item.device_id}>{item.device_name ?? item.device_id}{item.place_name ? ` · ${item.place_name}` : ''}</option>)}
      </select></div>
    <div className="row-actions">
      {['NATIVE_MP4', 'HLS_JS', 'RTSP_VIA_HLS_GATEWAY'].includes(resource.playback) && <Button type="button" variant="outline" disabled={!deviceId || state === 'REQUESTED'} onClick={() => void start('PLAYBACK')}>授权播放</Button>}
      {resource.snapshot === 'JPEG' && <Button type="button" variant="outline" disabled={!deviceId || state === 'REQUESTED'} onClick={() => void start('SNAPSHOT')}>授权抓图</Button>}
      {session && <Button type="button" variant="outline" onClick={() => void stop()}>结束媒体会话</Button>}
    </div>
    {error != null && <ErrorNotice error={error} retry={() => setError(undefined)}/>}
    <p className="muted-note" role="status">{label}</p>
    {session && session.media_kind === 'HLS' && <video ref={videoRef} controls playsInline style={{ width: '100%', maxWidth: 720 }}
      onLoadedData={() => setState(previous => previous === 'PLAYING' ? previous : 'DECODING')}
      onPlaying={() => setState('PLAYING')}
      onError={() => setState('FAILED')}/>}
    {session && session.media_kind === 'MP4' && <video ref={videoRef} controls playsInline src={mediaUrl(resource.id, 'play')} style={{ width: '100%', maxWidth: 720 }}
      onLoadedData={() => setState(previous => previous === 'PLAYING' ? previous : 'DECODING')}
      onPlaying={() => setState('PLAYING')}
      onError={() => setState('FAILED')}/>}
    {session && session.media_kind === 'JPEG' && <img src={mediaUrl(resource.id, 'snapshot')} alt="受权视频抓图" style={{ maxWidth: '100%' }}
      onLoad={() => setState('SNAPSHOT_LOADED')} onError={() => setState('FAILED')}/>}
    <p className="muted-note">媒体请求经同源受权代理；端点、凭据与签名地址不返回浏览器。会话过期或权限/设备范围变化后请求会被拒绝。</p>
  </div>
}
