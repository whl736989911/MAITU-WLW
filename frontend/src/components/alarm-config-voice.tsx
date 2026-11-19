import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { alarmBaseApi } from '@/lib/alarm-configuration'
import { ApiError, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { Button } from '@/components/ui/button'
import { ErrorNotice, Loading } from '@/components/common'

type VoiceTarget = { tenantId: string; deviceId: string; sourceId: string; eventType: 'FIRE' | 'FAULT' }
const fieldLabels = { zone: '区域', position: '详细位置', eventSource: '报警源', time: '发生时间', eventType: '事件类型' }
type VoiceField = keyof typeof fieldLabels
const isVoiceField = (key: unknown): key is VoiceField => typeof key === 'string' && Object.hasOwn(fieldLabels, key)

export function canReadBrowserVoice(eventType: VoiceTarget['eventType'], session = useSession.getState()) {
  return Boolean(session.user && !session.user.must_change_password && session.audience === 'management'
    && session.permissions.includes('alarm-base:read') && session.permissions.includes(eventType === 'FIRE' ? 'alarm:read' : 'device:read'))
}

export async function readEventVoice(target: VoiceTarget, signal: AbortSignal) {
  const { tenantId, deviceId, sourceId, eventType } = target
  const session = useSession.getState()
  if (!canReadBrowserVoice(eventType, session) || !tenantId || !deviceId || !sourceId) {
    throw new ApiError(403, 'BROWSER_VOICE_UNCONFIRMED', '无权读取本事件的本地语音策略')
  }
  const path = `/management/alarm-base-configuration/${eventType}/scopes/DEVICE/${encodeURIComponent(deviceId)}?${new URLSearchParams({ tenant_id: tenantId })}`
  const response = await request<{ event_kind?: unknown; scope?: unknown; scope_id?: unknown; tenant_id?: unknown; effective?: { value?: Record<string, unknown>; source_scope?: unknown; source_id?: unknown; source_revision?: unknown } }>(path, { signal })
  const effective = response?.effective
  const value = effective?.value
  const fields = value?.voice_fields
  const enabled = value?.voice_enabled, continuous = value?.voice_continuous, silenceMinutes = value?.silence_minutes
  if (response?.event_kind !== eventType || response.scope !== 'DEVICE' || response.scope_id !== deviceId || response.tenant_id !== tenantId
    || !effective || !['PLATFORM', 'UNIT', 'PLACE', 'DEVICE'].some(scope => scope === effective.source_scope)
    || !(effective.source_id === null || typeof effective.source_id === 'string')
    || effective.source_scope === 'DEVICE' && effective.source_id !== deviceId
    || effective.source_scope === 'UNIT' && effective.source_id !== tenantId
    || typeof effective.source_revision !== 'number' || !Number.isSafeInteger(effective.source_revision) || effective.source_revision < 0
    || typeof enabled !== 'boolean' || typeof continuous !== 'boolean' || !Array.isArray(fields) || !fields.every(isVoiceField)
    || !Array.isArray(value?.voice_types) || !value.voice_types.every((entry: unknown) => typeof entry === 'string')
    || typeof silenceMinutes !== 'number' || !Number.isSafeInteger(silenceMinutes) || silenceMinutes < 0 || silenceMinutes > 1440) {
    throw new ApiError(200, 'BROWSER_VOICE_POLICY_INVALID', '设备本地语音有效策略未确认')
  }
  const policy = { enabled, continuous, silenceMinutes, provenance: `${effective.source_scope}${effective.source_id ? ` / ${effective.source_id}` : ''} · 版本 ${effective.source_revision}` }
  if (!enabled || !fields.length) return { ...policy, message: '' }

  signal.throwIfAborted()
  const current = useSession.getState()
  if (current.generation !== session.generation || current.scopeTenantIds !== session.scopeTenantIds || current.scopePlaceIds !== session.scopePlaceIds || !canReadBrowserVoice(eventType, current)) {
    throw new ApiError(403, 'BROWSER_VOICE_UNCONFIRMED', '本事件的读取权限或范围已改变')
  }
  const outcomes = await alarmBaseApi.outcomes(sourceId, signal, tenantId)
  if (!Array.isArray(outcomes)) throw new ApiError(200, 'BROWSER_VOICE_EVIDENCE_INVALID', '本地语音结果格式未确认')
  let outcome: (typeof outcomes)[number] | undefined
  for (let index = outcomes.length - 1; index >= 0; index--) {
    const item = outcomes[index]
    if (item?.tenant_id === tenantId && item.event_type === eventType && item.outcome_kind === 'VOICE_BROADCAST'
      && (eventType !== 'FIRE' || item.alarm_id === sourceId)) { outcome = item; break }
  }
  if (!outcome || outcome.outcome !== 'LOCAL_CONTENT_COMPOSED_NOT_SENT') return { ...policy, message: '' }

  let content: Record<string, unknown> | undefined
  try { content = JSON.parse(outcome.detail_json)?.content } catch { /* Malformed persisted evidence cannot be read aloud. */ }
  if (!content || typeof content !== 'object' || Array.isArray(content) || !Array.isArray(content.fields) || !content.fields.every(isVoiceField)) {
    throw new ApiError(200, 'BROWSER_VOICE_EVIDENCE_INVALID', '本地语音内容未确认')
  }
  if (typeof content.eventType !== 'string' || !value.voice_types.includes(content.eventType)) return { ...policy, message: '' }
  const recordedFields = new Set(content.fields)
  const message = fields.flatMap(field => {
    const text = content[field]
    return recordedFields.has(field) && typeof text === 'string' && text.trim() ? [`${fieldLabels[field]}：${text}`] : []
  }).join('。')
  return { ...policy, message }
}

const muteKey = ({ tenantId, eventType, deviceId }: VoiceTarget) => `alarm-audio-mute:${JSON.stringify([tenantId, eventType, deviceId])}`
export function browserVoiceMutedUntil(target: VoiceTarget) {
  try {
    const until = Number(localStorage.getItem(muteKey(target)))
    return Number.isFinite(until) ? until : 0
  } catch { return 0 }
}

export function browserSpeechAvailable() {
  return typeof window.speechSynthesis?.speak === 'function' && typeof SpeechSynthesisUtterance === 'function'
}

export function useBrowserSpeech() {
  const epoch = useRef(0)
  const active = useRef(new Set<SpeechSynthesisUtterance>())
  const stop = useCallback(() => {
    epoch.current++
    if (!active.current.size) return
    active.current.clear()
    window.speechSynthesis?.cancel?.()
  }, [])
  useLayoutEffect(() => stop, [stop])
  const play = useCallback((message: string, repeat: boolean, eligible: () => boolean, onError: () => void) => {
    const playback = epoch.current
    function next(): boolean {
      if (playback !== epoch.current || !eligible()) return false
      if (!browserSpeechAvailable()) { onError(); return false }
      const utterance = new SpeechSynthesisUtterance(message)
      utterance.lang = 'zh-CN'
      utterance.onend = () => { active.current.delete(utterance); if (repeat) next() }
      utterance.onerror = () => {
        active.current.delete(utterance)
        if (playback === epoch.current) { stop(); onError() }
      }
      active.current.add(utterance)
      try { window.speechSynthesis.speak(utterance); return true }
      catch { active.current.delete(utterance); onError(); return false }
    }
    return next()
  }, [stop])
  return { play, stop }
}

export function AlarmConfigVoice({ occurredAt, ...target }: VoiceTarget & { occurredAt: string }) {
  const session = useSession()
  if (!canReadBrowserVoice(target.eventType, session) || !target.tenantId || !target.deviceId || !target.sourceId) return null
  return <EventVoice key={JSON.stringify([session.generation, session.scopeTenantIds, session.scopePlaceIds, target.eventType, target.tenantId, target.deviceId, target.sourceId])} target={target} occurredAt={occurredAt}/>
}

function EventVoice({ target, occurredAt }: { target: VoiceTarget; occurredAt: string }) {
  const voice = useResource('event-browser-voice', signal => readEventVoice(target, signal))
  const { play, stop } = useBrowserSpeech()
  const [mutedUntil, setMutedUntil] = useState(() => browserVoiceMutedUntil(target))
  const [speechError, setSpeechError] = useState(false)
  const confirmed = !voice.loading && voice.error == null ? voice.data : undefined
  const muted = mutedUntil > Date.now()
  useLayoutEffect(() => {
    if (!muted) return
    const timer = window.setTimeout(() => setMutedUntil(0), Math.max(0, mutedUntil - Date.now()))
    return () => window.clearTimeout(timer)
  }, [muted, mutedUntil])
  useLayoutEffect(() => stop, [confirmed, stop])

  function speak(repeat: boolean) {
    stop(); setSpeechError(false)
    if (!confirmed?.message || muted) return
    play(confirmed.message, repeat && confirmed.continuous,
      () => canReadBrowserVoice(target.eventType) && browserVoiceMutedUntil(target) <= Date.now(),
      () => setSpeechError(true))
  }
  function mute(until: number) {
    stop(); setMutedUntil(until)
    try {
      if (until) localStorage.setItem(muteKey(target), String(until))
      else localStorage.removeItem(muteKey(target))
    } catch { /* In restricted storage the silence lasts only while this panel is open. */ }
  }
  return <section className="notice min-w-0 [overflow-wrap:anywhere]" aria-label="本地语音播报">
    <strong>{target.eventType === 'FAULT' ? '本地故障语音' : '本地报警语音'}</strong>
    {voice.loading ? <Loading/> : voice.error != null ? <ErrorNotice error={voice.error} retry={voice.refresh}/> : !confirmed ? <p role="alert">设备有效语音策略未确认，未播报。</p> : <>
      <p className="muted-note">设备当前有效策略：{confirmed.provenance}。仅本浏览器播放，不拨打电话或控制设备。</p>
      {!confirmed.enabled ? <p>设备有效策略已关闭本地语音。</p> : !confirmed.message ? <p>没有可按当前策略朗读的事件内容。</p> : <>
        <p>{confirmed.message}</p><p>来源发生时间：{occurredAt}</p>
        {muted && <p>本设备同类事件已在此浏览器静音，至 {new Date(mutedUntil).toLocaleString()}。</p>}
        {speechError && <p role="alert">浏览器未能播放语音，请检查语音合成支持或权限。</p>}
        <div className="flex flex-wrap gap-2">
          <Button type="button" disabled={muted} onClick={() => speak(false)}>点击朗读</Button>
          {confirmed.continuous && <Button type="button" variant="outline" disabled={muted} onClick={() => speak(true)}>持续重复播报</Button>}
          <Button type="button" variant="outline" onClick={stop}>停止播报</Button>
          {muted ? <Button type="button" variant="outline" onClick={() => mute(0)}>提前取消静音</Button> : confirmed.silenceMinutes > 0 && <Button type="button" variant="outline" onClick={() => mute(Date.now() + confirmed.silenceMinutes * 60_000)}>静音 {confirmed.silenceMinutes} 分钟</Button>}
        </div>
      </>}
    </>}
  </section>
}
