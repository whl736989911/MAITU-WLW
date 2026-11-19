import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { alarmBaseApi, type AlarmConfigurationOutcome } from '@/lib/alarm-configuration'
import { useResource } from '@/lib/use-resource'
import { Button } from '@/components/ui/button'
import { ErrorNotice, Loading } from '@/components/common'

export function AlarmConfigVoice({tenantId,sourceId,eventType,occurredAt}:{tenantId:string;sourceId:string;eventType:'FIRE'|'FAULT';occurredAt:string}){
  const config=useResource(`alarm-voice-config:${tenantId}`,signal=>alarmBaseApi.get(signal,tenantId),true)
  const outcomes=useResource(`alarm-voice-outcomes:${tenantId}:${sourceId}`,signal=>alarmBaseApi.outcomes(sourceId,signal,tenantId),true)
  const key=`alarm-audio-muted-until:${tenantId}`,isFault=eventType==='FAULT'
  const [mutedUntil,setMutedUntil]=useState(0),[continuous,setContinuous]=useState(false),[unsupported,setUnsupported]=useState(false)
  const playing=useRef(0)
  function stop(){playing.current++;window.speechSynthesis?.cancel()}
  const voice=config.data?.value.voiceBroadcast
  const enabled=isFault?voice?.faultEnabled:voice?.alarmEnabled,fields=isFault?voice?.faultFields:voice?.alarmFields
  const silenceMinutes=isFault?config.data?.value.silenceIntervals.faultMinutes??0:config.data?.value.silenceIntervals.alarmMinutes??0
  const muted=mutedUntil>Date.now()
  const outcome=useMemo(()=>Array.isArray(outcomes.data)?outcomes.data.slice().reverse().find(item=>item.event_type===eventType&&item.outcome_kind==='VOICE_BROADCAST'&&item.outcome==='LOCAL_CONTENT_COMPOSED_NOT_SENT'):undefined,[outcomes.data,eventType])
  const message=useMemo(()=>compose(outcome,fields),[outcome,fields])
  useEffect(()=>{const stored=Number(localStorage.getItem(key)||0);setMutedUntil(Number.isFinite(stored)?stored:0);return stop},[key,sourceId])
  useEffect(()=>{if(!mutedUntil)return;const timer=window.setInterval(()=>{if(Date.now()>=mutedUntil){setMutedUntil(0);localStorage.removeItem(key)}},1000);return()=>window.clearInterval(timer)},[mutedUntil,key])
  useLayoutEffect(()=>{setContinuous(Boolean(config.data?.value.voiceBroadcast.continuous));stop()},[sourceId,config.data?.revision])
  function speak(repeat=continuous){
    stop()
    if(!message||muted||!('speechSynthesis'in window)){setUnsupported(!('speechSynthesis'in window));return}
    setUnsupported(false)
    const playback=playing.current
    function play(){
      if(playing.current!==playback)return
      const utterance=new SpeechSynthesisUtterance(message)
      utterance.lang='zh-CN'
      utterance.onend=()=>{if(repeat&&playing.current===playback)play()}
      utterance.onerror=()=>{if(playing.current===playback)stop()}
      window.speechSynthesis.speak(utterance)
    }
    play()
  }
  function mute(){const until=Date.now()+silenceMinutes*60_000;stop();setContinuous(false);setMutedUntil(until);localStorage.setItem(key,String(until))}
  return <section className="notice" aria-label="本地语音播报"><strong>{isFault?'本地故障语音':'本地报警语音'}</strong>{config.loading||outcomes.loading?<Loading/>:config.error!=null?<ErrorNotice error={config.error} retry={config.refresh}/>:outcomes.error!=null?<ErrorNotice error={outcomes.error} retry={outcomes.refresh}/>:!Array.isArray(outcomes.data)?<p role="alert">语音播报结果格式未确认；不推断为空或已播报。</p>:!enabled||!message?<p>该事件没有启用的本地语音播报内容。</p>:<><p>{message}</p><p>发生时间：{occurredAt}</p>{muted?<p>本浏览器静音至 {new Date(mutedUntil).toLocaleString()}。</p>:null}{unsupported?<p role="alert">当前浏览器没有语音合成能力；未发出语音。</p>:null}<div className="list-toolbar"><Button type="button" disabled={muted||!message} onClick={()=>speak(false)}>点击朗读</Button><label className="notification-checkbox"><input type="checkbox" checked={continuous} disabled={muted} onChange={event=>{setContinuous(event.target.checked);if(event.target.checked)speak(true);else stop()}}/>持续重复播报</label><Button type="button" variant="outline" disabled={silenceMinutes===0} onClick={mute}>静音 {silenceMinutes} 分钟</Button>{muted&&<Button type="button" variant="outline" onClick={()=>{stop();localStorage.removeItem(key);setMutedUntil(0)}}>提前取消静音</Button>}</div></>}</section>
}
function compose(outcome:AlarmConfigurationOutcome|undefined,fields?:string[]){if(!outcome)return '';let value:unknown;try{value=JSON.parse(outcome.detail_json)}catch{return ''}if(!value||typeof value!=='object')return '';const detail=value as Record<string,unknown>,content=detail.content&&typeof detail.content==='object'?detail.content as Record<string,unknown>:detail;const selected=(fields??(Array.isArray(content.fields)?content.fields.filter((x):x is string=>typeof x==='string'):[]));const labels:Record<string,string>={zone:'区域',position:'详细位置',eventSource:'报警源',time:'发生时间',eventType:'事件类型'};return selected.map(key=>{const label=labels[key],value=content[key];return label&&typeof value==='string'&&value?`${label}：${value}`:''}).filter(Boolean).join('。')}
