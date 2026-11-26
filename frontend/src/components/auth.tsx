import { useRef, useState, type FormEvent } from 'react'
import { ArrowRight, Flame, LockKeyhole, Radio, ShieldCheck, Signal, UserRound } from 'lucide-react'
import { motion } from 'motion/react'
import { api, errorMessage } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { Audience, Health } from '@/lib/types'
import { brand } from '@/lib/brand'
import { portalAudience, portalPath, setCustomerResumeTag } from '@/lib/portal-entry'
import { accountPasswordBounds, accountPasswordHint, validAccountPassword } from '@/lib/account-password'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Badge } from './ui/badge'
import { ErrorNotice } from './common'
import { LegacyCustomerLoginForm, rememberCustomerPassword } from './legacy-customer-login-form'

type AuthProps = { health?: Health; healthError?: unknown; selfChange?: boolean; onCancel?: () => void }

function Credentials({ audience, selfChange, onCancel }: Pick<AuthProps, 'selfChange' | 'onCancel'> & { audience: Audience }) {
  const user = useSession(s => s.audience === audience ? s.user : null)
  const notice = useSession(s => s.notice)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)
  const customer = audience === 'customer'
  const changeRequired = !!user && (user.must_change_password || selfChange)
  const submitLabel = changeRequired ? '更新密码并重新登录' : '登录'

  async function submit(event: FormEvent, remember = false) {
    event.preventDefault()
    if (submitting.current) return
    setError(undefined)
    if (changeRequired && newPassword !== confirmation) { setError(new Error('两次新密码不一致')); return }
    if (changeRequired && !validAccountPassword(newPassword)) { setError(new Error(accountPasswordHint())); return }
    const generation = useSession.getState().authGeneration
    const currentPortal = () => useSession.getState().authGeneration === generation && portalAudience(location.pathname) === audience
    if (!currentPortal()) return
    submitting.current = true
    setBusy(true)
    try {
      if (changeRequired) {
        await api.changePassword(password, newPassword)
        if (!currentPortal()) return
        useSession.getState().clear('密码已更新，旧会话已撤销。请使用新密码重新登录。')
        onCancel?.()
        setNewPassword(''); setConfirmation('')
      } else {
        const tab = customer ? globalThis.crypto?.randomUUID?.() : undefined
        const auth = await api.login(username.trim(), password, audience, tab)
        if (!currentPortal()) return
        if (tab) setCustomerResumeTag(auth.user.must_change_password ? null : tab)
        if (customer && remember && !auth.user.must_change_password) rememberCustomerPassword(username.trim(), password)
        useSession.getState().login(auth, audience)
      }
      setPassword('')
    } catch (error) {
      if (currentPortal()) setError(error)
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return <>
    {notice && <div role="status" className="notice">{notice}</div>}
    {error != null && <ErrorNotice error={error}/>}
    {customer && !changeRequired
      ? <LegacyCustomerLoginForm username={username} password={password} onUsername={setUsername} onPassword={setPassword} onSubmit={submit} busy={busy}/>
      : <form onSubmit={submit} className="form-stack">
      {!changeRequired && <div className="field"><Label htmlFor="username">账户</Label><div className="input-icon"><UserRound size={17}/><Input id="username" name="username" autoComplete="username" placeholder="请输入账户名称" required value={username} onChange={e => setUsername(e.target.value)}/></div></div>}
      <div className="field"><Label htmlFor="password">{changeRequired ? '当前密码' : '密码'}</Label><div className="input-icon"><LockKeyhole size={17}/><Input id="password" name="password" type="password" autoComplete="current-password" placeholder="请输入密码" required value={password} onChange={e => setPassword(e.target.value)}/></div></div>
      {changeRequired && <><div className="field"><Label htmlFor="new-password">新密码</Label><Input id="new-password" type="password" autoComplete="new-password" required minLength={accountPasswordBounds.min_length} maxLength={accountPasswordBounds.max_length} aria-describedby="new-password-policy" value={newPassword} onChange={e => setNewPassword(e.target.value)}/><p id="new-password-policy" className="muted-note">{accountPasswordHint()}</p></div><div className="field"><Label htmlFor="confirm-password">确认新密码</Label><Input id="confirm-password" type="password" autoComplete="new-password" placeholder="请再次输入新密码" required value={confirmation} onChange={e => setConfirmation(e.target.value)}/></div></>}
      <Button type="submit" size="lg" className="auth-submit" disabled={busy}>{busy ? '正在提交…' : submitLabel}{!customer && <ArrowRight/>}</Button>
    </form>}
    {selfChange && !user?.must_change_password && <Button variant="outline" className="mt-3 w-full" disabled={busy} onClick={onCancel}>取消并返回工作空间</Button>}
    {changeRequired && <Button variant="ghost" className="mt-3" disabled={busy} onClick={async () => {
      const state = useSession.getState()
      let message: string | undefined
      try { await api.logout() } catch (error) { message = `本地会话已清除，服务端退出未确认：${errorMessage(error)}` }
      if (useSession.getState().authGeneration === state.authGeneration) useSession.getState().clear(message)
    }}>退出当前账户</Button>}
  </>
}

export function AuthScreen({ health, healthError, selfChange = false, onCancel }: AuthProps) {
  const user = useSession(s => s.audience === 'management' ? s.user : null)
  const changeRequired = !!user && (user.must_change_password || selfChange)
  return <div className="auth-shell">
    <section className="auth-story" aria-label="平台介绍">
      <a className="brand auth-brand" href={portalPath('management', true)} aria-label={brand.fullName}><span className="brand-icon"><Flame/></span><span>{brand.name}<small>{brand.description}</small></span></a>
      <div className="story-content"><Badge className="story-badge" variant="outline"><span className="status-dot"/>让每一次异常，都被认真看见</Badge><h1>感知于细微<br/>守护于未然<span>。</span></h1><p>连接设备、洞察风险、协同处置。<br/>让消防安全管理清晰、有序、可追溯。</p>
        <div className="signal-illustration" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="orbit orbit-three"/><div className="sensor"><Radio size={40}/><span>FIRE SENSING</span><i/></div><div className="signal-chip chip-one"><Signal size={18}/>状态独立呈现</div><div className="signal-chip chip-two"><ShieldCheck size={18}/>服务端权限校验</div></div>
      </div><footer>SELF-HOSTED · 私有化部署</footer>
    </section>
    <section className="auth-main"><motion.div className="auth-form-wrap" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }}>
      <div className="auth-kicker"><ShieldCheck size={18}/> 安全访问</div><h2>{changeRequired ? user?.must_change_password ? '更新您的初始密码' : '修改自己的密码' : '登录'}</h2><p className="auth-description">{changeRequired ? '密码更新后请重新登录。' : '请输入账号和密码。'}</p>
      <Credentials audience="management" selfChange={selfChange} onCancel={onCancel}/>
      <div className="auth-security"><LockKeyhole size={14}/><span>登录凭证仅保存在内存，刷新页面即需重新登录</span></div>
      {healthError != null ? <ErrorNotice error={healthError}/> : <div className="auth-environment">{health ? `服务状态：${health.status} · ${health.storage_mode === 'h2-dev' ? 'H2 开发存储 / 非生产环境' : health.storage_mode === 'postgresql' ? 'PostgreSQL 存储' : health.storage_mode}` : '正在检查服务连接…'}</div>}
    </motion.div><footer className="auth-footer">{brand.fullName} · 消防安全，从连接开始</footer></section>
  </div>
}

export function CustomerLoginScreen({ healthError, selfChange = false, onCancel }: AuthProps) {
  const user = useSession(s => s.audience === 'customer' ? s.user : null)
  const changeRequired = !!user && (user.must_change_password || selfChange)
  const filingNumber = import.meta.env.VITE_CUSTOMER_FILING_NUMBER
  return <main className={changeRequired ? 'legacy-login legacy-password' : 'legacy-login'}>
    <div className="legacy-login-box">
      <div className="legacy-login-art" aria-hidden="true"/>
      <section className="legacy-login-panel" aria-labelledby="legacy-login-title">
        <header className="legacy-login-title">
          <h1 id="legacy-login-title">{changeRequired ? user.must_change_password ? '更新您的初始密码' : '修改自己的密码' : '智慧消防平台'}</h1>
          <p>{changeRequired ? '密码更新后请重新登录' : '欢迎登录'}</p>
        </header>
        <Credentials audience="customer" selfChange={selfChange} onCancel={onCancel}/>
        {healthError != null && <ErrorNotice error={healthError}/>}
      </section>
    </div>
    <footer className="legacy-login-footer">
      <span>{import.meta.env.VITE_CUSTOMER_OPERATOR_NAME || '智慧消防平台'}</span>
      {filingNumber && <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">{filingNumber}</a>}
    </footer>
  </main>
}
