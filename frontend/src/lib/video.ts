import { ApiError, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { Paginated } from '@/lib/types'

export interface VideoResource {
  id: string; tenant_id: string; name: string; resource_kind: 'CAMERA' | 'VIDEO_BOX' | 'NVR'
  protocol: 'HTTPS_MP4' | 'HTTPS_HLS' | 'HTTPS_JPEG' | 'RTSP'
  enabled: boolean; endpoint_configured: boolean; endpoint_host: string | null; credentials_configured: boolean
  gateway_configured: boolean; gateway_host: string | null
  archived_at: string | null; revision: number; visible_associations: number
  playback: string; snapshot: string; media_ready: boolean; note: string
  created_at: string; updated_at: string
}
export interface VideoAssociation {
  id: string; tenant_id: string; video_resource_id: string; device_id: string
  device_name: string | null; device_eui: string | null; place_name: string | null
  device_archived: 'ACTIVE' | 'ARCHIVED'; state: 'ACTIVE' | 'REVOKED'; revision: number
  created_at: string; revoked_at: string | null; revoke_reason: string | null
}
export interface VideoDetail { resource: VideoResource; associations: VideoAssociation[] }
export interface MediaSession {
  id: string; resource_id: string; device_id: string; purpose: 'PLAYBACK' | 'SNAPSHOT'
  media_kind: string; playback: string; snapshot: string; expires_at: string
  media_path: string; note: string
}
export interface CaptureJob {
  id: string; tenant_id: string; resource_id: string; device_id: string; protocol: string
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNSUPPORTED' | 'NOT_CONFIGURED'
  failure_code: string | null; failure_reason: string | null; capture_at: string | null
  image_sha256: string | null; image_media_type: string | null; image_bytes: number | null
  created_at: string; started_at: string | null; finished_at: string | null
}
export interface AlarmVideoIntent {
  resource: VideoResource
  intent: { id: string; resource_id: string; device_id: string; purpose: 'PREVIEW' | 'PLAYBACK'; state: string; created_at: string; expires_at: string } | null
  captures: CaptureJob[]
}
export interface MutationSpec { path: string; method: 'POST' | 'PUT'; body: Record<string, unknown>; display: string }

const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(200, 'VIDEO_RESPONSE_UNCONFIRMED', '视频响应结构未确认')
  return value as Record<string, unknown>
}

function errorFrom(status: number, body: Record<string, unknown>): ApiError {
  return new ApiError(status, typeof body.code === 'string' ? body.code : `HTTP_${status}`,
    typeof body.message === 'string' ? body.message : '媒体会话请求失败', typeof body.request_id === 'string' ? body.request_id : null)
}

/** Cookie-authenticated media session; the Bearer token is only used on this POST. */
export async function openMediaSession(tenantId: string, resourceId: string, deviceId: string, purpose: 'PLAYBACK' | 'SNAPSHOT', signal?: AbortSignal): Promise<MediaSession> {
  const session = useSession.getState()
  if (!session.token) throw new ApiError(401, 'SESSION_REQUIRED', '请先登录')
  const token = session.token, authGeneration = session.authGeneration
  const headers: Record<string, string> = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'application/json' }
  if (session.audience === 'management') headers['X-Tenant-Id'] = tenantId
  const response = await fetch(`/api/${session.audience}/video-resources/${encodeURIComponent(resourceId)}/media-sessions`, {
    method: 'POST', credentials: 'include', cache: 'no-store', signal, headers,
    body: JSON.stringify({ device_id: deviceId, purpose }),
  })
  const raw = await response.text()
  if (response.status === 401 && useSession.getState().token === token && useSession.getState().authGeneration === authGeneration) useSession.getState().clear('登录已失效，请重新登录。')
  let body: Record<string, unknown> = {}
  try { body = raw ? object(JSON.parse(raw)) : {} } catch { if (response.ok) throw new ApiError(response.status, 'INVALID_RESPONSE', '服务端返回了非 JSON 媒体会话响应') }
  if (!response.ok) throw errorFrom(response.status, body)
  return body as unknown as MediaSession
}

export async function revokeMediaSession(tenantId: string, resourceId: string, signal?: AbortSignal): Promise<void> {
  const session = useSession.getState()
  if (!session.token) return
  const token = session.token, authGeneration = session.authGeneration
  const headers: Record<string, string> = { Authorization: `Bearer ${token}`, Accept: 'application/json' }
  if (session.audience === 'management') headers['X-Tenant-Id'] = tenantId
  const response = await fetch(`/api/${session.audience}/video-resources/${encodeURIComponent(resourceId)}/media-sessions/revoke`, {
    method: 'POST', credentials: 'include', cache: 'no-store', signal, headers,
  })
  if (!response.ok && response.status !== 401) throw new ApiError(response.status, `HTTP_${response.status}`, '媒体会话撤销未确认')
  if (response.status === 401 && useSession.getState().token === token && useSession.getState().authGeneration === authGeneration) useSession.getState().clear('登录已失效，请重新登录。')
}

export function mediaUrl(resourceId: string, kind: 'play' | 'snapshot'): string {
  const audience = useSession.getState().audience
  return `/api/${audience}/video-resources/${encodeURIComponent(resourceId)}/media/${kind}`
}

export const videoApi = {
  alarmIntents(tenantId: string, alarmId: string, signal?: AbortSignal) {
    return request<AlarmVideoIntent[]>(`/${useSession.getState().audience}/video-alarm-intents?alarm_id=${encodeURIComponent(alarmId)}`, {
      tenantScoped: true, tenantId, signal, decode: raw => { const value = JSON.parse(raw); if (!Array.isArray(value)) throw new ApiError(200, 'VIDEO_RESPONSE_UNCONFIRMED', '视频意图响应结构未确认'); return value as AlarmVideoIntent[] },
    })
  },
  createCaptureJob(tenantId: string, resourceId: string, deviceId: string, signal?: AbortSignal) {
    return request<CaptureJob>(`/${useSession.getState().audience}/video-resources/${encodeURIComponent(resourceId)}/capture-jobs`, {
      tenantScoped: true, tenantId, method: 'POST', body: JSON.stringify({ device_id: deviceId }), signal,
      decode: raw => object(JSON.parse(raw)) as unknown as CaptureJob,
    })
  },
  captureJob(tenantId: string, resourceId: string, jobId: string, signal?: AbortSignal) {
    return request<CaptureJob>(`/${useSession.getState().audience}/video-resources/${encodeURIComponent(resourceId)}/capture-jobs/${encodeURIComponent(jobId)}`, {
      tenantScoped: true, tenantId, signal, decode: raw => object(JSON.parse(raw)) as unknown as CaptureJob,
    })
  },
  list(tenantId: string, page: number, options: { keyword?: string; include_archived?: boolean; size?: number } = {}, signal?: AbortSignal) {
    const params = new URLSearchParams({ page: String(page) })
    if (options.size !== undefined) params.set('size', String(options.size))
    if (options.keyword) params.set('keyword', options.keyword)
    if (options.include_archived) params.set('include_archived', 'true')
    return request<Paginated<VideoResource>>(`/${useSession.getState().audience}/video-resources?${params}`, {
      tenantScoped: true, tenantId, signal,
      decode: raw => { const value = object(JSON.parse(raw)); if (!Array.isArray(value.items)) throw new ApiError(200, 'VIDEO_RESPONSE_UNCONFIRMED', '视频分页响应结构未确认'); return value as unknown as Paginated<VideoResource> },
    })
  },
  detail(tenantId: string, id: string, signal?: AbortSignal) {
    return request<VideoDetail>(`/${useSession.getState().audience}/video-resources/${encodeURIComponent(id)}`, {
      tenantScoped: true, tenantId, signal, decode: raw => object(JSON.parse(raw)) as unknown as VideoDetail,
    })
  },
}

export async function fetchCaptureImage(resourceId: string, jobId: string, tenantId: string|undefined, signal?: AbortSignal): Promise<Blob> {
  const session = useSession.getState()
  if (!session.token) throw new ApiError(401, 'SESSION_REQUIRED', '请先登录')
  const headers: Record<string, string> = { Authorization: `Bearer ${session.token}` }
  if (session.audience === 'management' && tenantId) headers['X-Tenant-Id'] = tenantId
  const response = await fetch(`/api/${session.audience}/video-resources/${encodeURIComponent(resourceId)}/capture-jobs/${encodeURIComponent(jobId)}/image`, { headers, cache: 'no-store', signal })
  if (!response.ok) throw new ApiError(response.status, `HTTP_${response.status}`, '抓图读取未确认')
  return response.blob()
}

export const videoPaths = {
  create: (body: Record<string, unknown>): MutationSpec => ({ path: '/management/video-resources', method: 'POST', body, display: '登记视频资源' }),
  gateway: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/gateway`, method: 'PUT', body, display: '配置RTSP转封装网关' }),
  clearGateway: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/gateway/clear`, method: 'POST', body, display: '清除RTSP转封装网关' }),
  update: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/${useSession.getState().audience}/video-resources/${encodeURIComponent(id)}`, method: 'PUT', body, display: '修改视频资源' }),
  endpoint: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/endpoint`, method: 'PUT', body, display: '保存视频端点' }),
  clearEndpoint: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/endpoint/clear`, method: 'POST', body, display: '清除视频端点' }),
  credentials: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/credentials`, method: 'PUT', body, display: '保存视频端点凭据' }),
  clearCredentials: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/credentials/clear`, method: 'POST', body, display: '清除视频端点凭据' }),
  enabled: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/enabled`, method: 'POST', body, display: '启用/停用视频资源' }),
  archive: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/archive`, method: 'POST', body, display: '归档视频资源' }),
  associate: (id: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/associations`, method: 'POST', body, display: '关联设备' }),
  revokeAssociation: (id: string, associationId: string, body: Record<string, unknown>): MutationSpec => ({ path: `/management/video-resources/${encodeURIComponent(id)}/associations/${encodeURIComponent(associationId)}/revoke`, method: 'POST', body, display: '撤销视频关联' }),
}

export const VIDEO_KINDS = ['CAMERA', 'VIDEO_BOX', 'NVR'] as const
export const VIDEO_PROTOCOLS = ['HTTPS_MP4', 'HTTPS_HLS', 'HTTPS_JPEG', 'RTSP'] as const
export const PLAYBACK_LABELS: Record<string, string> = {
  NATIVE_MP4: '浏览器原生 MP4', HLS_JS: 'HLS（需 hls.js）', SNAPSHOT_ONLY: '仅抓图',
  RTSP_VIA_HLS_GATEWAY: 'RTSP 经HLS网关', RTSP_GATEWAY_NOT_CONFIGURED: 'RTSP 网关未配置',
  ENDPOINT_NOT_CONFIGURED: '未配置端点', DISABLED: '未启用', ARCHIVED: '已归档', ENCRYPTION_UNAVAILABLE: '密钥不可用',
  ALLOWLIST_NOT_CONFIGURED: '主机允许列表未配置', NOT_IMPLEMENTED: '未实现',
}
