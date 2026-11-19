import { useState, type FormEvent } from 'react'
import { ArrowRight, Flame, LockKeyhole, Radio, ShieldCheck, Signal, UserRound } from 'lucide-react'
import { motion } from 'motion/react'
import { api, errorMessage } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { Audience, Health } from '@/lib/types'
import { brand } from '@/lib/brand'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Badge } from './ui/badge'
import { ErrorNotice } from './common'

export function AuthScreen({ health, healthError, selfChange = false, onCancel }: { health?: Health; healthError?: unknown; selfChange?: boolean; onCancel?: () => void }) {
  const user = useSession(s => s.user)
  const notice = useSession(s => s.notice)
  const [audience, setAudience] = useState<Audience>(() => useSession.getState().audience)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const changeRequired = !!user && (user.must_change_password || selfChange)
  async function submit(event: FormEvent) {
    event.preventDefault(); setError(undefined)
    if (changeRequired && newPassword !== confirmation) { setError(new Error('两次新密码不一致')); return }
    setBusy(true)
    try {
      if (changeRequired) {
        const token = useSession.getState().token
        await api.changePassword(password, newPassword)
        if (useSession.getState().token !== token) return
        onCancel?.()
        useSession.getState().clear('密码已更新，旧会话已撤销。请使用新密码重新登录。')
        setPassword(''); setNewPassword(''); setConfirmation('')
      } else {
        const auth = await api.login(username.trim(), password, audience)
        useSession.getState().login(auth, audience)
        setPassword('')
      }
    } catch (error) { setError(error) }
    finally { setBusy(false) }
  }
  return <div className="auth-shell">
    <section className="auth-story" aria-label="平台介绍">
      <a className="brand auth-brand" href="/" aria-label={`${brand.fullName}首页`}><span className="brand-icon"><Flame/></span><span>{brand.name}<small>{brand.description}</small></span></a>
      <div className="story-content"><Badge className="story-badge" variant="outline"><span className="status-dot"/>让每一次异常，都被认真看见</Badge><h1>感知于细微<br/>守护于未然<span>。</span></h1><p>连接设备、洞察风险、协同处置。<br/>让消防安全管理清晰、有序、可追溯。</p>
        <div className="signal-illustration" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="orbit orbit-three"/><div className="sensor"><Radio size={40}/><span>FIRE SENSING</span><i/></div><div className="signal-chip chip-one"><Signal size={18}/>状态独立呈现</div><div className="signal-chip chip-two"><ShieldCheck size={18}/>服务端权限校验</div></div>
      </div><footer>SELF-HOSTED · 私有化部署</footer>
    </section>
    <section className="auth-main"><motion.div className="auth-form-wrap" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }}>
      <div className="auth-kicker"><ShieldCheck size={18}/> 安全访问工作空间</div><h2>{changeRequired ? user?.must_change_password ? '更新您的初始密码' : '修改自己的密码' : '欢迎回来'}</h2><p className="auth-description">{changeRequired ? '为保护账户安全，请先完成密码更新。更新后需要重新登录。' : '请选择访问端，登录已授权的工作空间。'}</p>
      {!changeRequired && <div className="audience-switch" role="group" aria-label="选择访问端"><Button type="button" variant={audience === 'management' ? 'default' : 'ghost'} onClick={() => setAudience('management')}>管理端</Button><Button type="button" variant={audience === 'customer' ? 'default' : 'ghost'} onClick={() => setAudience('customer')}>客户端</Button></div>}
      {notice && <div role="status" className="notice">{notice}</div>}
      {error != null && <ErrorNotice error={error}/>}
      <form onSubmit={submit} className="form-stack">
        {!changeRequired && <div className="field"><Label htmlFor="username">账户</Label><div className="input-icon"><UserRound size={17}/><Input id="username" name="username" autoComplete="username" placeholder="请输入账户名称" required value={username} onChange={e => setUsername(e.target.value)}/></div></div>}
        <div className="field"><Label htmlFor="password">{changeRequired ? '当前密码' : '密码'}</Label><div className="input-icon"><LockKeyhole size={17}/><Input id="password" name="password" type="password" autoComplete="current-password" placeholder="请输入密码" required value={password} onChange={e => setPassword(e.target.value)}/></div></div>
        {changeRequired && <><div className="field"><Label htmlFor="new-password">新密码</Label><Input id="new-password" type="password" autoComplete="new-password" placeholder="请设置符合服务端策略的新密码" required value={newPassword} onChange={e => setNewPassword(e.target.value)}/></div><div className="field"><Label htmlFor="confirm-password">确认新密码</Label><Input id="confirm-password" type="password" autoComplete="new-password" required value={confirmation} onChange={e => setConfirmation(e.target.value)}/></div></>}
        <Button type="submit" size="lg" className="auth-submit" disabled={busy}>{busy ? '正在提交…' : changeRequired ? '更新密码并重新登录' : `登录${audience === 'management' ? '管理端' : '客户端'}`}<ArrowRight/></Button>
      </form>
      {selfChange && !user?.must_change_password && <Button variant="outline" className="mt-3 w-full" disabled={busy} onClick={onCancel}>取消并返回工作空间</Button>}
      {changeRequired && <Button variant="ghost" className="mt-3" onClick={async () => { try { await api.logout(); useSession.getState().clear() } catch (error) { useSession.getState().clear(`本地会话已清除，服务端退出未确认：${errorMessage(error)}`) } }}>退出当前账户</Button>}
      <div className="auth-security"><LockKeyhole size={14}/><span>登录凭证仅保存在内存，刷新页面即需重新登录</span></div>
      {healthError != null ? <ErrorNotice error={healthError}/> : <div className="auth-environment">{health ? `服务状态：${health.status} · ${health.storage_mode === 'h2-dev' ? 'H2 开发存储 / 非生产环境' : health.storage_mode === 'postgresql' ? 'PostgreSQL 存储' : health.storage_mode}` : '正在检查服务连接…'}</div>}
    </motion.div><footer className="auth-footer">{brand.fullName} · 消防安全，从连接开始</footer></section>
  </div>
}
