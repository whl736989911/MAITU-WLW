import { useState, type ReactNode } from 'react'
import { Copy, Eye, EyeOff, KeyRound, ShieldCheck } from 'lucide-react'
import { useSession } from '@/lib/store'
import { allowedPage, type CredentialResult, type Health } from '@/lib/types'
import { Button } from './ui/button'
import { SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Empty } from './common'
export function IngestionGate({ page, children }: { page: 'sources' | 'messages' | 'tasks' | 'parameters'; children: ReactNode }) {
  const { user, audience, permissions, generation } = useSession()
  if (!user || user.must_change_password || !allowedPage(page, audience, permissions)) return <Empty title="当前账户无此管理页面权限"/>
  return <section key={generation}>{children}</section>
}
export function IngestionBoundary({ health }: { health?: Health }) {
  return <div className="ingestion-boundary"><ShieldCheck size={19}/><div><strong>接收事实、真实应用与人工预览是不同阶段</strong><p>平台接收时间与来源报告时间独立保留；新认证绑定捕获由真实服务端处理器异步应用，消息详情展示其服务端应用状态与错误码。历史未认证来源不自动处理。经授权操作者也可明确发起单条真实应用；人工预览仅记解析历史，不更新设备状态、在线时间、电量、事件或报警。未知、未绑定、未授权或未接通均不推断为正常或成功。</p><small>通用接收开关：{health?.generic_ingest_enabled === true ? '已启用' : health?.generic_ingest_enabled === false ? '已关闭' : '未确认'}</small></div></div>
}
export function SourceEndpoint({ id }: { id: string }) {
  const endpoint = `${window.location.origin}/api/ingest/${encodeURIComponent(id)}/messages`
  return <div className="source-endpoint"><p>平台自有通用格式 GENERIC_HTTP_JSON_V1 · 公网部署须另配HTTPS。管理Bearer不能代替接入凭据，接入凭据也不能访问管理API。</p><pre className="json-preview">{`POST ${endpoint}\nAuthorization: Bearer <接入令牌占位符>\nContent-Type: application/json\n\n{"source_message_id":"<稳定消息身份>","vendor_device_id":"<显式绑定的来源设备标识>","reported_at":null,"payload":"<原始UTF8报文字符串>"}`}</pre></div>
}
export type CredentialDrawerState = { sourceId: string; sourceName: string; token: string | null; recoveryRequired: boolean }
export function credentialDrawer(result: CredentialResult): CredentialDrawerState { return { sourceId: result.source.id, sourceName: result.source.name, token: result.one_time_token, recoveryRequired: result.credential_recovery_required || !result.one_time_token } }
export function OneTimeCredential({ credential }: { credential: CredentialDrawerState }) {
  const [visible, setVisible] = useState(false), [copyNotice, setCopyNotice] = useState('')
  async function copy() {
    if (!credential.token) return
    try { await navigator.clipboard.writeText(credential.token); setCopyNotice('已按您的明确操作复制到系统剪贴板，请立即交由安全渠道保存。') }
    catch { setCopyNotice('复制未完成。可显式显示后自行保存；不会自动下载或将凭据加入示例。') }
  }
  return <><SheetHeader className="review-header"><div className="eyebrow">ONE TIME · 仅此响应</div><SheetTitle>一次性接入凭据</SheetTitle><SheetDescription>独立内存抽屉。关闭、切换身份或登出即销毁；浏览范围筛选不会改变凭据所属来源。不会从列表重新找回，不会自动复制或下载。</SheetDescription></SheetHeader><div className="review-body form-stack"><h3><KeyRound size={18} className="inline mr-2"/>{credential.sourceName}</h3><div className="identity-readonly mono">来源 {credential.sourceId}</div>{credential.recoveryRequired ? <div role="alert" className="notice credential-warning">首次响应可能丢失，无法找回，请显式轮换。相同幂等键重试不会创建第二个来源，也不会静默轮换或再次返回明文。请关闭本抽屉，再由拥有凭据轮换权限的操作者明确轮换。</div> : <><div className="notice">仅首次成功响应提供明文。应用只在此抽屉内存持有；一旦关闭便不可重新打开。明确复制后，系统剪贴板独立于应用，可能继续保留，请安全管理。</div>{visible ? <pre className="credential-secret" aria-label="当前一次性令牌">{credential.token}</pre> : <div className="credential-hidden">凭据已隐藏，尚未显示</div>}<div className="version-actions"><Button variant="outline" onClick={() => setVisible(value => !value)}>{visible ? <EyeOff/> : <Eye/>}{visible ? '隐藏一次性令牌' : '显示一次性令牌'}</Button><Button onClick={() => void copy()}><Copy/>复制一次性令牌</Button></div></>}{copyNotice && <div role="status" className="notice">{copyNotice}</div>}<SourceEndpoint id={credential.sourceId}/></div></>
}
