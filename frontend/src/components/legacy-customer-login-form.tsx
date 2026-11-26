import { useEffect, useRef, useState, type FormEvent } from 'react'

const codeCharacters = '123456789abcdefghjkmnpqrstvwxyz'
const random = (minimum: number, maximum: number) => Math.floor(Math.random() * (maximum - minimum)) + minimum

function makeCode() {
  // Match the old form interaction; authentication and rate limits still belong to the API.
  return Array.from(crypto.getRandomValues(new Uint8Array(4)), byte => codeCharacters[byte % codeCharacters.length]).join('')
}

function CodePicture({ code }: { code: string }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    if (typeof CanvasRenderingContext2D === 'undefined') return
    const picture = canvas.current
    const context = picture?.getContext('2d')
    if (!picture || !context) return
    const { width, height } = picture
    context.fillStyle = '#fff'
    context.fillRect(0, 0, width, height)
    context.textBaseline = 'bottom'
    context.font = '35px SimHei, sans-serif'
    for (let index = 0; index < code.length; index++) {
      context.save()
      context.fillStyle = `rgb(${random(50, 160)},${random(50, 160)},${random(50, 160)})`
      context.translate((index + 1) * ((width - 5) / (code.length + 1)), 34)
      context.rotate(random(-20, 20) * Math.PI / 180)
      context.fillText(code[index], 0, 0)
      context.restore()
    }
    context.fillStyle = '#000'
    for (let index = 0; index < 100; index++) {
      context.beginPath()
      context.arc(random(0, width), random(0, height), .5, 0, 2 * Math.PI)
      context.fill()
    }
  }, [code])
  return <canvas ref={canvas} width={92} height={36} aria-hidden="true"/>
}

const PasswordCredentialClass = (globalThis as typeof globalThis & {
  PasswordCredential?: new (data: { id: string; password: string }) => Credential
}).PasswordCredential

export function rememberCustomerPassword(username: string, password: string) {
  if (!PasswordCredentialClass || !navigator.credentials) return
  void navigator.credentials.store(new PasswordCredentialClass({ id: username, password })).catch(() => {
    console.warn('浏览器未保存登录密码。')
  })
}

type LoginProps = {
  username: string
  password: string
  onUsername: (value: string) => void
  onPassword: (value: string) => void
  onSubmit: (event: FormEvent<HTMLFormElement>, remember: boolean) => Promise<void>
  busy: boolean
}

export function LegacyCustomerLoginForm({ username, password, onUsername, onPassword, onSubmit, busy }: LoginProps) {
  const [code, setCode] = useState(makeCode)
  const [answer, setAnswer] = useState('')
  const [codeError, setCodeError] = useState('')
  const [remember, setRemember] = useState(false)
  const canRemember = Boolean(PasswordCredentialClass && navigator.credentials)

  function refreshCode() {
    setAnswer('')
    setCodeError('')
    setCode(makeCode())
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    if (answer.trim().toLowerCase() !== code) {
      setCodeError(answer.trim() ? '验证码错误' : '验证码不能为空')
      return
    }
    await onSubmit(event, remember && canRemember)
    refreshCode()
  }

  return <form className="legacy-entry-form" onSubmit={submit}>
    <div className="legacy-entry-field">
      <label className="sr-only" htmlFor="username">账户</label>
      <input id="username" name="username" placeholder="请输入用户名或邮箱" autoComplete="username" autoCapitalize="none" required value={username} onChange={event => onUsername(event.target.value)}/>
      <span className="legacy-entry-icon" aria-hidden="true">{'\ue6cb'}</span>
    </div>
    <div className="legacy-entry-field">
      <label className="sr-only" htmlFor="password">密码</label>
      <input id="password" name="password" type="password" placeholder="请输入密码" autoComplete="current-password" required value={password} onChange={event => onPassword(event.target.value)}/>
      <span className="legacy-entry-icon" aria-hidden="true">{'\ue6e2'}</span>
    </div>
    <div className="legacy-entry-field legacy-entry-code">
      <label className="sr-only" htmlFor="legacy-code">验证码</label>
      <input id="legacy-code" name="code" placeholder="请输入验证码" maxLength={4} autoComplete="off" autoCapitalize="none" aria-invalid={!!codeError} aria-describedby={codeError ? 'legacy-code-error' : undefined} value={answer} onChange={event => { setAnswer(event.target.value); setCodeError('') }}/>
      <span className="legacy-entry-icon" aria-hidden="true">{'\ue69e'}</span>
      <button type="button" aria-label="点击刷新验证码" title="点击刷新验证码" onClick={refreshCode}><CodePicture code={code}/></button>
      {codeError && <span id="legacy-code-error" className="legacy-entry-error" role="alert">{codeError}</span>}
    </div>
    <div className="legacy-entry-options">
      <label title={canRemember ? '由浏览器密码管理器保存' : '此浏览器不支持由网页发起保存，请使用浏览器密码管理器'}>
        <input type="checkbox" checked={remember && canRemember} disabled={!canRemember} onChange={event => setRemember(event.target.checked)}/>记住密码
      </label>
    </div>
    <button className="legacy-entry-submit" type="submit" disabled={busy}>{busy ? '正在提交…' : '登录'}</button>
  </form>
}
