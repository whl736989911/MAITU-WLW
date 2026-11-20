import { useEffect, useState, type FormEvent } from 'react'
import { MessageSquareText, RefreshCw, Save, Send, ShieldCheck } from 'lucide-react'
import { api, errorMessage } from '@/lib/api'
import { useResource } from '@/lib/use-resource'
import type { NotificationProvider, NotificationProviderTest } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice, Loading, PageHeading } from '@/components/common'
import { useSession } from '@/lib/store'
import { PageHelp } from '@/components/page-tools'
import { WechatConfigPanel } from '@/components/wechat-config'
import './notification-provider.css'

type Form = { smsEnabled: boolean; voiceEnabled: boolean; sign: string; smsCode: string; ttsCode: string; called: string; smsVariables: string; voiceVariables: string; smsVariablesConfirmed: boolean; voiceVariablesConfirmed: boolean; smsEndpoint: string; voiceEndpoint: string; accessKeyId: string; accessKeySecret: string; testModeEnabled: boolean; testPhone: string }
const empty: Form = { smsEnabled: false, voiceEnabled: false, sign: '', smsCode: '', ttsCode: '', called: '', smsVariables: '', voiceVariables: '', smsVariablesConfirmed: false, voiceVariablesConfirmed: false, smsEndpoint: 'https://dysmsapi.aliyuncs.com/', voiceEndpoint: 'https://dyvmsapi.aliyuncs.com/', accessKeyId: '', accessKeySecret: '', testModeEnabled: false, testPhone: '' }

function variableText(mapping: NotificationProvider['sms_template_variables'] | undefined) {
  return mapping == null ? '' : JSON.stringify(mapping, null, 2)
}

function checkedVariables(value: string, channel: string, enabled: boolean, reviewRequired: boolean, confirmed: boolean) {
  let mapping: unknown = null
  if (value.trim()) {
    try { mapping = JSON.parse(value) }
    catch { throw new Error(`${channel}模板变量映射不是有效的 JSON 对象`) }
    if (mapping !== null && (typeof mapping !== 'object' || Array.isArray(mapping))) {
      throw new Error(`${channel}模板变量映射不是有效的 JSON 对象`)
    }
  }
  if (mapping === null && enabled) throw new Error(`启用${channel}前必须填写模板变量映射；无变量请填写空对象`)
  if (mapping !== null && reviewRequired && !confirmed) throw new Error(`请先核对并确认${channel}模板 ID 与变量映射`)
  return mapping
}

export function NotificationProviderPage() {
  const canWrite = useSession(s => s.permissions.includes('notification-config:write'))
  const provider = useResource('notification-provider', signal => api.notificationProvider(signal))
  const status = useResource('notification-provider-status', signal => api.notificationProviderStatus(signal))
  const [form, setForm] = useState<Form>(empty), [baseline, setBaseline] = useState<NotificationProvider | null>(null), [notice, setNotice] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [testChannel, setTestChannel] = useState('SMS'), [testPhone, setTestPhone] = useState(''), [testResult, setTestResult] = useState<NotificationProviderTest | null>(null)
  useEffect(() => { if (provider.data) hydrate(provider.data) }, [provider.data])
  const smsIdChanged = form.smsCode.trim() !== (baseline?.sms_template_code ?? '')
  const voiceIdChanged = form.ttsCode.trim() !== (baseline?.tts_code ?? '')
  const smsReviewRequired = smsIdChanged || baseline?.sms_template_variables == null
  const voiceReviewRequired = voiceIdChanged || baseline?.voice_template_variables == null
  function hydrate(value: NotificationProvider) {
    setForm({ smsEnabled: value.sms_enabled, voiceEnabled: value.voice_enabled, sign: value.sms_sign_name ?? '', smsCode: value.sms_template_code ?? '', ttsCode: value.tts_code ?? '', called: value.tts_called_show_number ?? '',
      smsVariables: variableText(value.sms_template_variables), voiceVariables: variableText(value.voice_template_variables),
      smsVariablesConfirmed: false, voiceVariablesConfirmed: false,
      smsEndpoint: value.sms_endpoint, voiceEndpoint: value.voice_endpoint, accessKeyId: '', accessKeySecret: '', testModeEnabled: value.test_mode_enabled, testPhone: '' })
    setBaseline(value)
  }
  function field<K extends keyof Form>(key: K, value: Form[K]) {
    setForm(previous => {
      const next = { ...previous, [key]: value }
      if (key === 'smsCode' || key === 'smsVariables') next.smsVariablesConfirmed = false
      if (key === 'ttsCode' || key === 'voiceVariables') next.voiceVariablesConfirmed = false
      return next
    })
    setNotice(''); setError('')
  }
  async function save(event: FormEvent) {
    event.preventDefault(); if (!canWrite) { setError('当前账户没有通知通道写入权限'); return }; setError(''); setNotice('')
    if (!baseline) return
    try {
      const smsVariables = checkedVariables(form.smsVariables, '短信', form.smsEnabled, smsReviewRequired, form.smsVariablesConfirmed)
      const voiceVariables = checkedVariables(form.voiceVariables, '语音', form.voiceEnabled, voiceReviewRequired, form.voiceVariablesConfirmed)
      const body = { sms_enabled: form.smsEnabled, voice_enabled: form.voiceEnabled, sms_sign_name: form.sign || null, sms_template_code: form.smsCode || null, tts_code: form.ttsCode || null, tts_called_show_number: form.called || null,
        sms_template_variables: smsIdChanged || form.smsVariables !== variableText(baseline.sms_template_variables) ? smsVariables : undefined,
        voice_template_variables: voiceIdChanged || form.voiceVariables !== variableText(baseline.voice_template_variables) ? voiceVariables : undefined,
        sms_template_variables_confirmed: smsVariables !== null && smsReviewRequired && form.smsVariablesConfirmed ? true : undefined,
        voice_template_variables_confirmed: voiceVariables !== null && voiceReviewRequired && form.voiceVariablesConfirmed ? true : undefined,
        sms_endpoint: form.smsEndpoint, voice_endpoint: form.voiceEndpoint, access_key_id: form.accessKeyId || null, access_key_secret: form.accessKeySecret || null, test_mode_enabled: form.testModeEnabled, test_phone: form.testPhone || null, expected_revision: baseline.revision }
      if (!window.confirm(`将保存影响全平台后续通知的通道配置。${baseline.test_mode_enabled && !form.testModeEnabled ? '关闭测试接收覆盖后，将恢复使用各单位/位置启用规则中的真实联系人；请先核对联系人与规则。' : '联系人范围和发送资格仍由服务端逐次核验。'}继续保存？`)) return
      setBusy(true)
      const saved = await api.updateNotificationProvider(body, crypto.randomUUID())
      hydrate(saved); setNotice('通知通道配置已保存。密钥只写不读。'); provider.refresh(); status.refresh()
    } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  async function test() {
    if (!canWrite) { setError('当前账户没有通知通道写入权限'); return }
    setTestResult(null); setError('')
    if (!window.confirm(`将向 ${testPhone || '已配置的测试号码'} 真实发送一条${testChannel === 'SMS' ? '短信' : '语音'}测试通知，可能产生费用；厂商受理不等于送达或接听。继续？`)) return
    setBusy(true)
    try { setTestResult(await api.testNotificationProvider({ channel: testChannel, phone: testPhone })) }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <div className="notification-provider-workspace">
    <PageHeading eyebrow="NOTIFICATION · 通道配置" title="短信与语音通道" description="配置真实通知通道、短信模板和语音模板。">
      <Button type="button" variant="outline" onClick={() => { provider.refresh(); status.refresh() }}><RefreshCw/>刷新</Button>
      <PageHelp title="通知通道与凭据"><p>短信与语音共用阿里云 AccessKey，分别配置模板及启用状态。页面中的全部配置通过同一个保存动作提交。</p><p>AccessKey ID 仅显示遮罩，Secret 只写不读；凭据输入留空表示保持现有值。保存需当前登录会话与服务端版本校验。</p><p>测试发送会真实调用短信或语音接口，可能计费。厂商受理不表示短信送达或语音接听。</p></PageHelp>
    </PageHeading>
    {status.error != null && <ErrorNotice error={status.error} retry={status.refresh}/>}
    {status.data && <div className="notice provider-live-status" role="status"><ShieldCheck size={15}/>真实发送：{status.data.real_enabled ? '已启用' : '已关闭'} · 短信通道：{status.data.sms_ready ? '就绪' : '未就绪'} · 语音通道：{status.data.voice_ready ? '就绪' : '未就绪'} · 密钥存储：{status.data.secret_storage_ready ? '可用' : '不可用'}{status.data.test_mode_enabled && <strong> · 正在使用测试接收号码 {status.data.test_phone_masked ?? '号码遮罩未提供'}</strong>}</div>}
    {!canWrite && <div role="status" className="notice">当前账户只有读取权限，不能保存配置或发送测试通知。</div>}
    {provider.loading ? <Loading/> : provider.error != null ? <ErrorNotice error={provider.error} retry={provider.refresh}/> : provider.data ? <>
      {((provider.data.sms_enabled && provider.data.sms_template_variables == null) || (provider.data.voice_enabled && provider.data.voice_template_variables == null)) &&
        <div className="notice danger-note" role="status">已启用的通道中有模板变量未配置。相应通道不会外发；核对模板、填写映射并保存后才能恢复。</div>}
      <form id="notification-provider-form" className="form-stack" onSubmit={save}>
        <fieldset className="provider-fieldset form-stack" disabled={!canWrite || busy}>
          <p id="template-variable-help" className="muted-note">键是阿里云模板中的变量名，值选用 device_name（设备名）、address（位置）、alarmTime（时间）、event（事件名）。无变量写 {'{}'}。更换模板 ID 后，即使沿用原映射也需重新核对。若要在通道停用时仅保存新 ID，请清空对应映射；映射未核对不能启用。</p>
          <div className="provider-config-grid">
            <Card><CardContent><h2 className="section-heading"><MessageSquareText size={18}/>短信通道</h2><div className="form-stack">
              <label className="inline-check"><input type="checkbox" checked={form.smsEnabled} onChange={e => field('smsEnabled', e.target.checked)}/>启用短信通道</label>
              <div className="field"><Label htmlFor="provider-sign">短信签名</Label><Input id="provider-sign" value={form.sign} onChange={e => field('sign', e.target.value)} maxLength={200}/></div>
              <div className="field"><Label htmlFor="provider-sms-code">短信模板 ID</Label><Input id="provider-sms-code" value={form.smsCode} onChange={e => field('smsCode', e.target.value)} maxLength={100}/></div>
              <div className="field"><Label htmlFor="provider-sms-variables">短信模板变量映射</Label><textarea id="provider-sms-variables" aria-describedby="template-variable-help" rows={3} maxLength={2048} placeholder={'{"deviceName":"device_name"}'} value={form.smsVariables} onChange={e => field('smsVariables', e.target.value)}/></div>
              {smsReviewRequired && !['', 'null'].includes(form.smsVariables.trim()) && <label className="inline-check"><input type="checkbox" checked={form.smsVariablesConfirmed} onChange={e => field('smsVariablesConfirmed', e.target.checked)}/>已核对短信模板 ID 与变量映射</label>}
              <details className="secondary-controls"><summary>短信接入地址</summary><div className="field"><Label htmlFor="provider-sms-endpoint">短信接入地址</Label><Input id="provider-sms-endpoint" value={form.smsEndpoint} onChange={e => field('smsEndpoint', e.target.value)}/></div></details>
            </div></CardContent></Card>
            <Card><CardContent><h2 className="section-heading"><MessageSquareText size={18}/>语音通道</h2><div className="form-stack">
              <label className="inline-check"><input type="checkbox" checked={form.voiceEnabled} onChange={e => field('voiceEnabled', e.target.checked)}/>启用语音通道</label>
              <div className="field"><Label htmlFor="provider-tts-code">语音 TTS 模板 ID</Label><Input id="provider-tts-code" value={form.ttsCode} onChange={e => field('ttsCode', e.target.value)} maxLength={100}/></div>
              <div className="field"><Label htmlFor="provider-voice-variables">语音模板变量映射</Label><textarea id="provider-voice-variables" aria-describedby="template-variable-help" rows={3} maxLength={2048} placeholder={'{"addr":"address"}'} value={form.voiceVariables} onChange={e => field('voiceVariables', e.target.value)}/></div>
              {voiceReviewRequired && !['', 'null'].includes(form.voiceVariables.trim()) && <label className="inline-check"><input type="checkbox" checked={form.voiceVariablesConfirmed} onChange={e => field('voiceVariablesConfirmed', e.target.checked)}/>已核对语音模板 ID 与变量映射</label>}
              <div className="field"><Label htmlFor="provider-called">语音主叫号码</Label><Input id="provider-called" value={form.called} onChange={e => field('called', e.target.value)} maxLength={50} placeholder="可选，填写服务商分配的号码"/></div>
              <details className="secondary-controls"><summary>语音接入地址</summary><div className="field"><Label htmlFor="provider-voice-endpoint">语音接入地址</Label><Input id="provider-voice-endpoint" value={form.voiceEndpoint} onChange={e => field('voiceEndpoint', e.target.value)}/></div></details>
            </div></CardContent></Card>
          </div>
          <details className="secondary-controls"><summary>共用 AccessKey 凭据 · {provider.data.secret_configured ? '已配置' : '未配置'}</summary><div className="secondary-controls-body provider-config-grid">
            <div className="field"><Label htmlFor="provider-access-key">AccessKey ID</Label><Input id="provider-access-key" value={form.accessKeyId} placeholder={provider.data.access_key_id_masked ?? '未配置，留空保持不变'} onChange={e => field('accessKeyId', e.target.value)} autoComplete="off"/></div>
            <div className="field"><Label htmlFor="provider-secret">AccessKey Secret</Label><Input id="provider-secret" type="password" value={form.accessKeySecret} placeholder={provider.data.secret_configured ? '已配置，留空保持不变' : '只写不读'} onChange={e => field('accessKeySecret', e.target.value)} autoComplete="new-password"/></div>
          </div></details>
          <details className="secondary-controls"><summary>真实报警测试接收</summary><div className="secondary-controls-body form-stack">
            <p>仅对真实 FIRE/FAULT 中已有启用规则且到达级别的短信/语音计划替换目的号码；不增加收件人、渠道或发送次数。公众号计划不会改发至测试号码。</p>
            <label className="inline-check"><input type="checkbox" checked={form.testModeEnabled} onChange={e => field('testModeEnabled', e.target.checked)}/>启用测试接收号码</label>
            <div className="field"><Label htmlFor="provider-test-phone">测试接收号码</Label><Input id="provider-test-phone" value={form.testPhone} onChange={e => field('testPhone', e.target.value)} placeholder={provider.data.test_phone_masked ? `已配置 ${provider.data.test_phone_masked}，留空保留` : '输入测试接收号码'} autoComplete="off"/></div>
          </div></details>
          {form.testModeEnabled && <div className="notice danger-note" role="status">测试接收号码仅替换真实 FIRE/FAULT 已匹配规则中 SMS/VOICE 的实际目的号码；不会新增收件人、通道或通知级别，也不会改变升级次数。公众号规则不转发到测试号码。</div>}
          <div className="provider-save-bar">
            <Button disabled={!canWrite || busy} type="submit"><Save/>{busy ? '保存中…' : '保存全部通道配置'}</Button>
            <span className="muted-note">版本 {baseline?.revision ?? provider.data.revision} · {provider.data.updated_at ?? '尚未保存'}</span>
          </div>
        </fieldset>
      </form>
      <details className="secondary-controls"><summary>通道发送测试（真实调用，可能计费）</summary><div className="secondary-controls-body form-stack">
        <div className="provider-config-grid">
          <div className="field"><Label htmlFor="test-channel">测试通道</Label><select id="test-channel" className="native-select" value={testChannel} onChange={e => setTestChannel(e.target.value)} disabled={!canWrite || busy}><option value="SMS">短信</option><option value="VOICE">语音</option></select></div>
          <div className="field"><Label htmlFor="test-phone">测试号码</Label><Input id="test-phone" value={testPhone} onChange={e => setTestPhone(e.target.value)} placeholder="手机号或带区号座机" disabled={!canWrite || busy}/></div>
        </div>
        <p>此操作会向所填号码发送真实通知，可能计费；厂商受理不等于送达或接听。</p>
        <Button type="button" variant="outline" disabled={!canWrite || busy} onClick={test}><Send/>发送真实测试通知</Button>
      </div></details>
      {testResult && <div role="status" className={testResult.success ? 'notice success-note' : 'notice danger-note'}><strong>{testResult.outcome === 'UNKNOWN' ? '测试受理结果未知，请勿重复发送' : testResult.success ? '测试请求已获厂商受理（未确认送达）' : '测试请求已被拒绝'}</strong><div>{testResult.channel} · {testResult.code || '未提供厂商码'} · {testResult.provider_id ?? '无厂商回执 ID'}</div><div>{testResult.message}</div></div>}
    </> : null}
    <WechatConfigPanel/>
    {error && <div role="alert" className="notice danger-note">{error}</div>}{notice && <div role="status" className="notice success-note">{notice}</div>}
  </div>
}
