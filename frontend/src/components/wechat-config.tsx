import { useEffect, useState, type FormEvent } from 'react'
import { MessageCircle, RefreshCw, Save, ShieldCheck } from 'lucide-react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { wechatApi, type WechatConfigView, type WechatKeyMapping } from '@/lib/wechat-official-account'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice, Loading } from '@/components/common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'
import { dateTime } from '@/lib/format'

type Form = {
  enabled: boolean; appId: string; appSecret: string; clearAppSecret: boolean
  callbackToken: string; clearCallbackToken: boolean; aesKey: string; clearAesKey: boolean
  oauthRedirect: string; callbackUrl: string; fireTemplate: string; faultTemplate: string
  fireKeys: WechatKeyMapping; faultKeys: WechatKeyMapping
}
const emptyForm: Form = {
  enabled: false, appId: '', appSecret: '', clearAppSecret: false, callbackToken: '', clearCallbackToken: false,
  aesKey: '', clearAesKey: false, oauthRedirect: '', callbackUrl: '', fireTemplate: '', faultTemplate: '',
  fireKeys: { deviceKey: 'thing1', addressKey: 'thing3', timeKey: 'time2', eventKey: 'const4' },
  faultKeys: { deviceKey: 'thing1', addressKey: 'thing3', timeKey: 'time2', eventKey: 'const4' },
}
export function WechatConfigPanel() {
  const canWrite = useSession(s => s.permissions.includes('notification-config:write'))
  const config = useResource<WechatConfigView>('wechat-official-account-config', signal => wechatApi.config(signal))
  const [form, setForm] = useState<Form>(emptyForm)
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  useEffect(() => { if (config.data) hydrate(config.data) }, [config.data])
  function hydrate(value: WechatConfigView) {
    setForm({
      enabled: value.enabled, appId: '', appSecret: '', clearAppSecret: false,
      callbackToken: '', clearCallbackToken: false, aesKey: '', clearAesKey: false,
      oauthRedirect: value.oauthRedirectUri ?? '', callbackUrl: value.callbackUrl ?? '',
      fireTemplate: value.fireTemplateId ?? '', faultTemplate: value.faultTemplateId ?? '',
      fireKeys: { ...value.fireKeys }, faultKeys: { ...value.faultKeys },
    })
  }
  function field<K extends keyof Form>(key: K, value: Form[K]) { action.touch(); setForm(current => ({ ...current, [key]: value })) }
  function keyField(event: 'fire' | 'fault', key: keyof WechatKeyMapping, value: string) {
    action.touch()
    setForm(current => ({ ...current, [event === 'fire' ? 'fireKeys' : 'faultKeys']: { ...(event === 'fire' ? current.fireKeys : current.faultKeys), [key]: value } }))
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canWrite) { action.setError(new Error('当前账户没有通知配置写入权限')); return }
    if (!reason.trim()) { action.setError(new Error('请填写本次配置变更原因')); return }
    if (!action.confirming) { action.setConfirming(true); return }
    const body = {
      enabled: form.enabled, app_id: form.appId.trim() || null, app_secret: form.appSecret || null,
      clear_app_secret: form.clearAppSecret, callback_token: form.callbackToken || null,
      clear_callback_token: form.clearCallbackToken, encoding_aes_key: form.aesKey || null,
      clear_encoding_aes_key: form.clearAesKey, oauth_redirect_uri: form.oauthRedirect.trim() || null,
      callback_url: form.callbackUrl.trim() || null, fire_template_id: form.fireTemplate.trim() || null,
      fault_template_id: form.faultTemplate.trim() || null,
      fire_device_key: form.fireKeys.deviceKey || null, fire_address_key: form.fireKeys.addressKey || null,
      fire_time_key: form.fireKeys.timeKey || null, fire_event_key: form.fireKeys.eventKey || null,
      fault_device_key: form.faultKeys.deviceKey || null, fault_address_key: form.faultKeys.addressKey || null,
      fault_time_key: form.faultKeys.timeKey || null, fault_event_key: form.faultKeys.eventKey || null,
      reason: reason.trim(), expected_revision: config.data?.revision ?? 0,
    }
    void action.run((key, signal) => wechatApi.updateConfig(body, key, signal), () => { setReason(''); setForm(current => ({ ...current, appSecret: '', callbackToken: '', aesKey: '', clearAppSecret: false, clearCallbackToken: false, clearAesKey: false })); action.touch(); config.refresh() })
  }
  if (config.loading) return <Card><CardContent><Loading/></CardContent></Card>
  if (config.error != null) return <Card><CardContent><ErrorNotice error={config.error} retry={config.refresh}/></CardContent></Card>
  if (!config.data) return null
  const value = config.data
  return <Card><CardContent><div className="list-toolbar"><h2 className="section-heading" style={{ margin: 0 }}><MessageCircle size={18}/>微信公众号通道</h2><Button type="button" variant="outline" onClick={() => config.refresh()}><RefreshCw/>刷新配置与诊断</Button></div>
    <div className={value.enabled && value.oauthReady && (value.fireTemplateReady || value.faultTemplateReady) ? 'notice success-note' : 'notice'} role="status">
      配置 {value.configured ? '已保存' : '未保存'} · 通道 {value.enabled ? '启用' : '关闭'} · 接口凭据 {value.apiCredentialsReady ? '就绪' : '未就绪'} · OAuth {value.oauthReady ? '就绪' : '未就绪'} · 回调 {value.callbackReady ? '就绪' : '未就绪'} · 火警模板 {value.fireTemplateReady ? '就绪' : '未完成'} · 故障模板 {value.faultTemplateReady ? '就绪' : '未完成'} · 密钥存储 {value.secretStorageReady ? '可用' : '不可用'} · Token {value.tokenState === 'VALID' ? '已缓存' : value.tokenState === 'STALE' ? '旧账户缓存，不会复用' : '未获取'}{value.tokenExpiresAt ? `（至 ${dateTime(value.tokenExpiresAt)}）` : ''} · 版本 {value.revision} / 凭据 {value.credentialsRevision}
    </div>
    <p className="muted-note">保存配置不会调用微信接口、不会发送测试消息。只有真实报警命中规则且通道策略启用时才获取 access_token 并发送；打开页面不进行在线验证。AppSecret、服务器 Token、EncodingAESKey 只写不读，留空保持原值，清除需显式勾选。</p>
    {!canWrite && <div className="notice">当前账户只有读取权限，不能保存公众号配置。</div>}
    <form className="form-stack" onSubmit={submit}>
      <fieldset className="provider-fieldset form-stack" disabled={!canWrite || action.busy}>
        <h3>基本参数</h3>
        <label className="inline-check"><input type="checkbox" checked={form.enabled} onChange={event => field('enabled', event.target.checked)}/>启用公众号模板通知</label>
        <div className="provider-config-grid">
          <div className="field"><Label htmlFor="wechat-app-id">AppID</Label><Input id="wechat-app-id" value={form.appId} placeholder={value.appIdMasked ?? 'wx...'} maxLength={64} onChange={event => field('appId', event.target.value)}/></div>
          <div className="field"><Label htmlFor="wechat-app-secret">AppSecret（只写）</Label><Input id="wechat-app-secret" type="password" value={form.appSecret} placeholder={value.appSecretConfigured ? '已配置，留空保持不变' : '32 位 AppSecret'} autoComplete="new-password" onChange={event => field('appSecret', event.target.value)}/></div>
        </div>
        <label className="inline-check"><input type="checkbox" checked={form.clearAppSecret} onChange={event => field('clearAppSecret', event.target.checked)}/>显式清除已保存 AppSecret（会使接口不可用）</label>
        <div className="provider-config-grid">
          <div className="field"><Label htmlFor="wechat-callback-token">服务器配置 Token（只写）</Label><Input id="wechat-callback-token" type="password" value={form.callbackToken} placeholder={value.callbackTokenConfigured ? '已配置，留空保持不变' : '3–32 位明文 Token'} autoComplete="new-password" onChange={event => field('callbackToken', event.target.value)}/></div>
          <div className="field"><Label htmlFor="wechat-aes-key">EncodingAESKey（只写，可选）</Label><Input id="wechat-aes-key" type="password" value={form.aesKey} placeholder={value.aesKeyConfigured ? '已配置，留空保持不变' : '43 位 Base64'} autoComplete="new-password" onChange={event => field('aesKey', event.target.value)}/></div>
        </div>
        <div className="provider-config-grid"><label className="inline-check"><input type="checkbox" checked={form.clearCallbackToken} onChange={event => field('clearCallbackToken', event.target.checked)}/>显式清除服务器 Token</label><label className="inline-check"><input type="checkbox" checked={form.clearAesKey} onChange={event => field('clearAesKey', event.target.checked)}/>显式清除 EncodingAESKey</label></div>
      </fieldset>
      <fieldset className="provider-fieldset form-stack" disabled={!canWrite || action.busy}>
        <h3>固定 HTTPS 回调</h3>
        <div className="provider-config-grid">
          <div className="field"><Label htmlFor="wechat-oauth-redirect">OAuth 回调地址</Label><Input id="wechat-oauth-redirect" value={form.oauthRedirect} placeholder="https://your-host/api/wechat/oauth/callback" maxLength={500} onChange={event => field('oauthRedirect', event.target.value)}/></div>
          <div className="field"><Label htmlFor="wechat-callback-url">消息/事件回调地址</Label><Input id="wechat-callback-url" value={form.callbackUrl} placeholder="https://your-host/api/wechat/callback" maxLength={500} onChange={event => field('callbackUrl', event.target.value)}/></div>
        </div>
        <p className="muted-note">必须与微信后台“网页授权域名/服务器配置”一致；只允许固定路径，不接受任意 return URL。反向代理必须对这两个路径关闭访问日志并避免记录查询字符串。</p>
      </fieldset>
      <fieldset className="provider-fieldset form-stack" disabled={!canWrite || action.busy}>
        <h3>模板与字段映射</h3>
        <div className="provider-config-grid">
          <div className="field"><Label htmlFor="wechat-fire-template">火警模板 ID</Label><Input id="wechat-fire-template" value={form.fireTemplate} maxLength={128} onChange={event => field('fireTemplate', event.target.value)}/></div>
          <div className="field"><Label htmlFor="wechat-fault-template">故障模板 ID</Label><Input id="wechat-fault-template" value={form.faultTemplate} maxLength={128} onChange={event => field('faultTemplate', event.target.value)}/></div>
        </div>
        {(['fire', 'fault'] as const).map(event => <div className="form-stack" key={event}><h4>{event === 'fire' ? '火警模板字段键' : '故障模板字段键'}</h4><div className="provider-config-grid">
          {([['deviceKey', '设备名称键'], ['addressKey', '地址键（可选）'], ['timeKey', '时间键'], ['eventKey', '事件键（可选）']] as const).map(([key, label]) => <div className="field" key={key}><Label htmlFor={`wechat-${event}-${key}`}>{label}</Label><Input id={`wechat-${event}-${key}`} value={form[event === 'fire' ? 'fireKeys' : 'faultKeys'][key] ?? ''} maxLength={32} onChange={input => keyField(event, key, input.target.value)}/></div>)}
        </div></div>)}
        <p className="muted-note">字段键必须与微信模板中的占位键一致（如 thing1、time2）。thing/const 官方显示上限 20 字符；超出时平台会以官方长度截断并在发送快照中保存实际发送内容。厂商校验失败会记录为发送拒绝，不会伪造送达。</p>
      </fieldset>
      <div className="field"><Label htmlFor="wechat-config-reason">本次配置变更原因</Label><Input id="wechat-config-reason" required maxLength={1000} value={reason} disabled={action.confirming || action.busy} onChange={event => { action.touch(); setReason(event.target.value) }}/></div>
      {!action.confirming && <div className="identity-form-actions"><Button type="submit" disabled={!canWrite || action.busy}><Save/>核对配置变更</Button></div>}
      {action.confirming && <OperationConfirmation action={action} title="确认保存公众号配置" submitLabel={action.busy ? '保存中…' : '确认保存'} refresh={() => { config.refresh(); action.touch() }} warning="只保存配置，不获取 access_token、不发送模板消息、不改变任何报警或设备状态。">版本 {value.revision} → 启用 {form.enabled ? '是' : '否'} · AppID {form.appId || value.appIdMasked || '未配置'} · 原因：{reason}</OperationConfirmation>}
      {action.error != null && <ErrorNotice error={action.error}/>}
      {action.conflict && <div className="notice">配置已在其他请求中更新。请刷新后按新的 revision 重新确认。</div>}
    </form>
    <div className="notice"><ShieldCheck size={15}/>绑定诊断：有效绑定 {value.diagnostics.activeBindings} · 旧公众号绑定 {value.diagnostics.staleBindings} · 联系人关联 {value.diagnostics.contactLinks}（失效 {value.diagnostics.staleContactLinks}）· 使用公众号规则 {value.diagnostics.rulesWithWechat} · 待确认授权 {value.diagnostics.pendingOauthRequests} · 最近模板完成事件 {dateTime(value.diagnostics.lastCallbackAt)} {value.diagnostics.lastCallbackStatus ?? ''} · 最近 OAuth 回调 {dateTime(value.diagnostics.lastOauthCallbackAt)} {value.diagnostics.lastOauthCallbackStatus ?? ''}</div>
  </CardContent></Card>
}
