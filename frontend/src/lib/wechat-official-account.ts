import { ApiError, request } from './api'
import { useSession } from './store'

/** Official Account API decoders. Every decoder rejects a shape the backend cannot prove. */
export interface WechatKeyMapping { deviceKey: string | null; addressKey: string | null; timeKey: string | null; eventKey: string | null }
export interface WechatDiagnostics {
  activeBindings: number; staleBindings: number; contactLinks: number; staleContactLinks: number; rulesWithWechat: number
  pendingOauthRequests: number; lastCallbackAt: string | null; lastCallbackEvent: string | null; lastCallbackStatus: string | null
  lastOauthCallbackAt: string | null; lastOauthCallbackStatus: string | null
}
export interface WechatConfigView {
  configured: boolean; enabled: boolean; appIdMasked: string | null; appSecretConfigured: boolean
  callbackTokenConfigured: boolean; aesKeyConfigured: boolean; oauthRedirectUri: string | null; callbackUrl: string | null
  fireTemplateId: string | null; faultTemplateId: string | null; fireKeys: WechatKeyMapping; faultKeys: WechatKeyMapping
  apiCredentialsReady: boolean; oauthReady: boolean; callbackReady: boolean; fireTemplateReady: boolean; faultTemplateReady: boolean
  secretStorageReady: boolean; tokenState: string; tokenExpiresAt: string | null; tokenCurrent: boolean
  revision: number; credentialsRevision: number; updatedAt: string | null; diagnostics: WechatDiagnostics
}
export interface WechatBinding {
  id: string; openidMasked: string; appIdMasked: string | null; status: string; followState: string
  revision: number; boundAt: string; lastSeenAt: string | null; stale: boolean
}
export interface WechatPending {
  id: string; status: 'PENDING' | 'CALLBACK_RECEIVED'; requestedAt: string; expiresAt: string
  callbackAt: string | null; openidMasked: string | null; revision: number
}
export interface WechatBindingStatus {
  configured: boolean; enabled: boolean; oauthReady: boolean; secretStorageReady: boolean; appIdMasked: string | null
  binding: WechatBinding | null; pending: WechatPending | null; staleBindingCount: number; canStart: boolean; message: string
}
export interface WechatStartResult {
  pendingId: string; authorizeUrl: string; qrPayload: string; expiresAt: string; appIdMasked: string | null; redirectUri: string
}
export interface WechatRecipient {
  bindingId: string; userName: string; openidMasked: string; boundAt: string; revision: number; followState: string; usable: boolean
}
export interface WechatBindingHistory {
  id: string; event: 'BOUND' | 'REPLACED' | 'UNBOUND'; maskedOpenid: string; actorName: string | null; detailCode: string | null; createdAt: string
}
export interface WechatChannel {
  configured: boolean; enabled: boolean; ready: boolean; fire_template_ready: boolean; fault_template_ready: boolean
  active_recipients: number; message: string
}

const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const bad = (message: string): never => { throw new ApiError(200, 'WECHAT_RESPONSE_UNCONFIRMED', message) }
const text = (value: unknown, name: string): string => { if (typeof value !== 'string' || !value) return bad(`${name} 未确认`); return value }
const nullableText = (value: unknown, name: string): string | null => { if (value !== null && typeof value !== 'string') return bad(`${name} 未确认`); return value }
const bool = (value: unknown, name: string): boolean => { if (typeof value !== 'boolean') return bad(`${name} 未确认`); return value }
const integer = (value: unknown, name: string, min = 0): number => {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min) return bad(`${name} 未确认`)
  return value
}
const timestamp = (value: unknown, name: string): string => { const result = text(value, name); if (Number.isNaN(Date.parse(result))) return bad(`${name} 未确认`); return result }
const nullableTimestamp = (value: unknown, name: string): string | null => value === null ? null : timestamp(value, name)

function mapping(value: unknown): WechatKeyMapping {
  if (!object(value)) return bad('模板字段映射未确认')
  return { deviceKey: nullableText(value.device_key, '设备字段'), addressKey: nullableText(value.address_key, '地址字段'), timeKey: nullableText(value.time_key, '时间字段'), eventKey: nullableText(value.event_key, '事件字段') }
}
function diagnostics(value: unknown): WechatDiagnostics {
  if (!object(value)) return bad('公众号诊断未确认')
  return {
    activeBindings: integer(value.active_bindings, '有效绑定数'), staleBindings: integer(value.stale_bindings, '旧账号绑定数'),
    contactLinks: integer(value.contact_links, '联系人关联数'), staleContactLinks: integer(value.stale_contact_links, '失效关联数'),
    rulesWithWechat: integer(value.rules_with_wechat, '公众号规则数'), pendingOauthRequests: integer(value.pending_oauth_requests, '待确认授权数'),
    lastCallbackAt: nullableTimestamp(value.last_callback_at, '最近回调时间'), lastCallbackEvent: nullableText(value.last_callback_event, '最近回调事件'),
    lastCallbackStatus: nullableText(value.last_callback_status, '最近回调状态'), lastOauthCallbackAt: nullableTimestamp(value.last_oauth_callback_at, '最近授权回调时间'),
    lastOauthCallbackStatus: nullableText(value.last_oauth_callback_status, '最近授权回调状态'),
  }
}
export function wechatConfig(value: unknown): WechatConfigView {
  if (!object(value)) return bad('公众号配置响应未确认')
  return {
    configured: bool(value.configured, '已配置'), enabled: bool(value.enabled, '启用状态'), appIdMasked: nullableText(value.app_id_masked, 'AppID 遮罩'),
    appSecretConfigured: bool(value.app_secret_configured, 'AppSecret 状态'), callbackTokenConfigured: bool(value.callback_token_configured, 'Token 状态'),
    aesKeyConfigured: bool(value.aes_key_configured, 'AESKey 状态'), oauthRedirectUri: nullableText(value.oauth_redirect_uri, 'OAuth 回调地址'),
    callbackUrl: nullableText(value.callback_url, '消息回调地址'), fireTemplateId: nullableText(value.fire_template_id, '火警模板'),
    faultTemplateId: nullableText(value.fault_template_id, '故障模板'), fireKeys: mapping(value.fire_keys), faultKeys: mapping(value.fault_keys),
    apiCredentialsReady: bool(value.api_credentials_ready, '接口凭据'), oauthReady: bool(value.oauth_ready, 'OAuth 就绪'), callbackReady: bool(value.callback_ready, '回调就绪'),
    fireTemplateReady: bool(value.fire_template_ready, '火警模板就绪'), faultTemplateReady: bool(value.fault_template_ready, '故障模板就绪'),
    secretStorageReady: bool(value.secret_storage_ready, '密钥存储'), tokenState: text(value.token_state, 'Token 状态'),
    tokenExpiresAt: nullableTimestamp(value.token_expires_at, 'Token 到期'), tokenCurrent: bool(value.token_current, 'Token 账户匹配'),
    revision: integer(value.revision, '配置版本'), credentialsRevision: integer(value.credentials_revision, '凭据版本'),
    updatedAt: nullableTimestamp(value.updated_at, '更新时间'), diagnostics: diagnostics(value.diagnostics),
  }
}
function binding(value: unknown): WechatBinding | null {
  if (value === null) return null
  if (!object(value)) return bad('绑定状态未确认')
  return { id: text(value.id, '绑定ID'), openidMasked: text(value.openid_masked, 'OpenID 遮罩'), appIdMasked: nullableText(value.app_id_masked, 'AppID 遮罩'),
    status: text(value.status, '绑定状态'), followState: text(value.follow_state, '关注状态'), revision: integer(value.revision, '绑定版本'),
    boundAt: timestamp(value.bound_at, '绑定时间'), lastSeenAt: nullableTimestamp(value.last_seen_at, '最近事件'), stale: bool(value.stale, '旧账号标记') }
}
function pending(value: unknown): WechatPending | null {
  if (value === null) return null
  if (!object(value)) return bad('待确认授权未确认')
  if (value.status !== 'PENDING' && value.status !== 'CALLBACK_RECEIVED') return bad('待确认授权状态未确认')
  return { id: text(value.id, '待确认ID'), status: value.status, requestedAt: timestamp(value.requested_at, '发起时间'),
    expiresAt: timestamp(value.expires_at, '过期时间'), callbackAt: nullableTimestamp(value.callback_at, '授权回调时间'),
    openidMasked: nullableText(value.openid_masked, 'OpenID 遮罩'), revision: integer(value.revision, '授权版本') }
}
export function wechatBindingStatus(value: unknown): WechatBindingStatus {
  if (!object(value)) return bad('微信绑定状态未确认')
  return { configured: bool(value.configured, '已配置'), enabled: bool(value.enabled, '启用状态'), oauthReady: bool(value.oauth_ready, 'OAuth 就绪'),
    secretStorageReady: bool(value.secret_storage_ready, '密钥存储'), appIdMasked: nullableText(value.app_id_masked, 'AppID 遮罩'),
    binding: binding(value.binding), pending: pending(value.pending), staleBindingCount: integer(value.stale_binding_count, '旧绑定数'),
    canStart: bool(value.can_start, '可发起绑定'), message: text(value.message, '说明') }
}
export function wechatStart(value: unknown): WechatStartResult {
  if (!object(value)) return bad('授权发起响应未确认')
  const authorizeUrl = text(value.authorize_url, '授权链接')
  if (!authorizeUrl.startsWith('https://open.weixin.qq.com/connect/oauth2/authorize?')) return bad('授权链接不是官方 OAuth 地址')
  return { pendingId: text(value.pending_id, '待确认ID'), authorizeUrl, qrPayload: text(value.qr_payload, '二维码内容'),
    expiresAt: timestamp(value.expires_at, '过期时间'), appIdMasked: nullableText(value.app_id_masked, 'AppID 遮罩'), redirectUri: text(value.redirect_uri, '回调地址') }
}
export function wechatRecipient(value: unknown): WechatRecipient {
  if (!object(value)) return bad('公众号收件人未确认')
  return { bindingId: text(value.binding_id, '绑定ID'), userName: text(value.user_name, '用户名称'), openidMasked: text(value.openid_masked, 'OpenID 遮罩'),
    boundAt: timestamp(value.bound_at, '绑定时间'), revision: integer(value.revision, '绑定版本'), followState: text(value.follow_state, '关注状态'), usable: bool(value.usable, '可用状态') }
}
export function wechatHistory(value: unknown): WechatBindingHistory {
  if (!object(value) || (value.event !== 'BOUND' && value.event !== 'REPLACED' && value.event !== 'UNBOUND')) return bad('绑定历史未确认')
  return { id: text(value.id, '历史ID'), event: value.event, maskedOpenid: text(value.masked_openid, 'OpenID 遮罩'),
    actorName: nullableText(value.actor_name, '操作者'), detailCode: nullableText(value.detail_code, '原因码'), createdAt: timestamp(value.created_at, '时间') }
}
export function wechatChannel(value: unknown): WechatChannel {
  if (!object(value)) return bad('公众号通道能力未确认')
  return { configured: bool(value.configured, '已配置'), enabled: bool(value.enabled, '启用状态'), ready: bool(value.ready, '就绪'),
    fire_template_ready: bool(value.fire_template_ready, '火警模板'), fault_template_ready: bool(value.fault_template_ready, '故障模板'),
    active_recipients: integer(value.active_recipients, '可用收件人'), message: text(value.message, '说明') }
}
function page<T>(value: unknown, decode: (item: unknown) => T, expectedPage: number): { items: T[]; total: number; page: number; size: number } {
  if (!object(value) || !Array.isArray(value.items) || integer(value.page, '页码', 1) !== expectedPage) return bad('分页响应未确认')
  const items = value.items.map(decode)
  return { items, total: integer(value.total, '总数'), page: expectedPage, size: integer(value.size, '页大小', 1) }
}

const selfPath = () => `/${useSession.getState().audience}/me/wechat-binding`
export const wechatApi = {
  config: (signal?: AbortSignal) => request<WechatConfigView>('/management/wechat-official-account', { signal, decode: raw => wechatConfig(JSON.parse(raw)) }),
  updateConfig: (body: Record<string, unknown>, key: string, signal?: AbortSignal) =>
    request<WechatConfigView>('/management/wechat-official-account', { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: raw => wechatConfig(JSON.parse(raw)) }),
  status: (signal?: AbortSignal) => request<WechatBindingStatus>(selfPath(), { signal, decode: raw => wechatBindingStatus(JSON.parse(raw)) }),
  start: (signal?: AbortSignal) => request<WechatStartResult>(`${selfPath()}/requests`, { method: 'POST', signal, decode: raw => wechatStart(JSON.parse(raw)) }),
  confirm: (id: string, body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal) =>
    request<WechatBinding>(`${selfPath()}/pending/${encodeURIComponent(id)}/confirm`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: raw => binding(JSON.parse(raw)) ?? bad('绑定确认响应未确认') }),
  reject: (id: string, signal?: AbortSignal) => request<void>(`${selfPath()}/pending/${encodeURIComponent(id)}/reject`, { method: 'POST', signal }),
  unbind: (body: { expected_revision: number; reason: string }, key: string, signal?: AbortSignal) =>
    request<{ bindingId: string; status: string; revision: number }>(`${selfPath()}/unbind`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal, decode: raw => {
      const value: unknown = JSON.parse(raw)
      if (!object(value)) return bad('解除绑定响应未确认')
      return { bindingId: text(value.binding_id, '绑定ID'), status: text(value.status, '状态'), revision: integer(value.revision, '版本') }
    } }),
  history: (pageNumber: number, signal?: AbortSignal) => request<{ items: WechatBindingHistory[]; total: number; page: number; size: number }>(
    `${selfPath()}/history?page=${pageNumber}`, { signal, decode: raw => page(JSON.parse(raw), wechatHistory, pageNumber) }),
  recipients: (signal?: AbortSignal, tenantId?: string) => {
    const audience = useSession.getState().audience
    if (audience === 'customer') return request<WechatRecipient[]>('/customer/notification-settings/wechat-recipients', { tenantScoped: true, signal, decode: raw => recipients(JSON.parse(raw)) })
    return request<WechatRecipient[]>('/management/notification-wechat/recipients', { tenantScoped: true, tenantId, signal, decode: raw => recipients(JSON.parse(raw)) })
  },
  channel: (signal?: AbortSignal, tenantId?: string) => {
    const audience = useSession.getState().audience
    if (audience === 'customer') return request<WechatChannel>('/customer/notification-settings/wechat-channel', { tenantScoped: true, signal, decode: raw => wechatChannel(JSON.parse(raw)) })
    return request<WechatChannel>('/management/notification-wechat/channel', { tenantScoped: true, tenantId, signal, decode: raw => wechatChannel(JSON.parse(raw)) })
  },
}
function recipients(value: unknown): WechatRecipient[] {
  if (!Array.isArray(value) || value.length > 500) return bad('公众号收件人列表未确认')
  return value.map(wechatRecipient)
}
