// Finite UI onboarding/reset/self-password regression against the actual packaged JAR.
import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, openSync, closeSync, writeFileSync } from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadChromium, browserExecutable, browserEnvironment, javaFixture } from './browser-fixture.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const { chromium } = await loadChromium(root)
const browserExe = browserExecutable()
assert(browserExe, 'Set BROWSER_EXECUTABLE to an installed Ubuntu Chrome/Chromium browser')
const jar = path.join(root, 'backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar')
assert(existsSync(jar))
const reservation = net.createServer()
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const url = `http://127.0.0.1:${port}/`
const run = path.join(root, '.runtime', 'identity-browser-' + randomUUID())
mkdirSync(run, { recursive: true })
const secret = () => randomBytes(24).toString('base64url')
const initial = secret(), adminPassword = secret(), clientInitial = secret(), clientChanged = secret(), resetPassword = secret()
const log = openSync(path.join(run, 'server.log'), 'w')
const { java, env } = javaFixture(root, run)
Object.assign(env, { APP_PROFILE: 'test', APP_STORAGE: 'h2-dev', APP_PORT: String(port), APP_BIND_ADDRESS: '127.0.0.1', APP_ROLES: 'all', APP_BOOTSTRAP_USERNAME: 'browser-root', APP_BOOTSTRAP_PASSWORD: initial, APP_DB_URL: 'jdbc:h2:mem:identity-browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH', APP_DB_USERNAME: 'sa', APP_DB_PASSWORD: '' })
const server = spawn(java, ['-Djava.io.tmpdir=' + env.TMPDIR, '-jar', jar], { cwd: run, env, stdio: ['ignore', log, log] })
let browser
const checks = [], errors = []
async function login(page, username, password, customer = false) {
  await page.getByRole('heading', { name: '欢迎回来', exact: true }).waitFor()
  await page.getByRole('button', { name: customer ? '客户端' : '管理端', exact: true }).click()
  await page.getByLabel('账户', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: customer ? '登录客户端' : '登录管理端', exact: true }).click()
}
async function changeInitial(page, current, updated) {
  await page.getByRole('heading', { name: '更新您的初始密码' }).waitFor()
  await page.getByLabel('当前密码', { exact: true }).fill(current)
  await page.getByLabel('新密码', { exact: true }).fill(updated)
  await page.getByLabel('确认新密码', { exact: true }).fill(updated)
  await page.getByRole('button', { name: '更新密码并重新登录', exact: true }).click()
  await page.getByRole('heading', { name: '欢迎回来', exact: true }).waitFor()
}
function responseFor(page, ending, method) { return page.waitForResponse(r => r.url().endsWith(ending) && r.request().method() === method) }
try {
  const deadline = Date.now() + 90000
  while (true) {
    if (server.exitCode !== null) throw new Error('Owned JVM exited before readiness')
    try { const r = await fetch(url + 'api/health', { signal: AbortSignal.timeout(1500) }); if (r.ok) break } catch {}
    if (Date.now() > deadline) throw new Error('Readiness timeout')
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  browser = await chromium.launch({ executablePath: browserExe, headless: true, chromiumSandbox: true, env: browserEnvironment(env) })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 }, reducedMotion: 'reduce' })
  page.on('pageerror', e => errors.push(e.message))
  await page.goto(url)
  await login(page, 'browser-root', initial)
  await changeInitial(page, initial, adminPassword)
  await login(page, 'browser-root', adminPassword)
  await page.getByRole('button', { name: '单位管理', exact: true }).click()
  await page.getByRole('button', { name: '新增单位', exact: true }).click()
  await page.getByLabel('单位名称', { exact: true }).fill('浏览器正式开户单位')
  const unitResponse = responseFor(page, '/api/management/units', 'POST')
  await page.getByRole('button', { name: '保存单位', exact: true }).click()
  const created = await unitResponse
  assert.equal(created.status(), 200)
  const unit = await created.json()
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await page.locator('#workspace-scope-tree').getByText(unit.name, { exact: true }).waitFor()
  checks.push('formal unit creation saves in one submit under the current session; new unit appears in the read scope tree')

  await page.locator('.sidebar').getByRole('button', { name: '用户管理', exact: true }).click()
  await page.getByRole('button', { name: '创建单位用户', exact: true }).click()
  await page.getByLabel('用户名', { exact: true }).fill('browser-client')
  await page.getByLabel('姓名', { exact: true }).fill('浏览器正式客户')
  for (const permission of ['summary:read', 'device:read', 'alarm:read', 'users:read']) await page.locator('label').filter({ hasText: permission }).getByRole('checkbox').check()
  await page.getByLabel('初始密码', { exact: true }).fill(clientInitial)
  await page.getByLabel('确认初始密码', { exact: true }).fill(clientInitial)
  await page.getByRole('button', { name: '核对并继续', exact: true }).click()
  const userResponse = responseFor(page, '/api/management/users', 'POST')
  await page.getByRole('button', { name: '确认创建账号', exact: true }).click()
  const newUser = await userResponse
  assert.equal(newUser.status(), 200)
  const user = await newUser.json()
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await page.getByText('浏览器正式客户', { exact: true }).waitFor()
  await page.waitForFunction(() => { const x = document.querySelector('#main-content > div'); return x && getComputedStyle(x).opacity === '1' })
  await page.screenshot({ path: path.join(run, 'unit-users.png'), fullPage: true, animations: 'disabled' })
  assert.equal(await page.locator('input[type=password]').count(), 0)
  checks.push('real unit user creation via catalog checkboxes and session-authorized confirmation')

  const client = await browser.newPage({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' })
  client.on('pageerror', e => errors.push(e.message))
  await client.goto(url)
  await login(client, 'browser-client', clientInitial, true)
  await changeInitial(client, clientInitial, clientChanged)
  await login(client, 'browser-client', clientChanged, true)
  const mine = client.locator('.nav-group-wrap').filter({ has: client.locator('.nav-group', { hasText: '我的' }) })
  await mine.getByRole('button', { name: '更多相关功能', exact: true }).click()
  await mine.getByRole('button', { name: '用户管理', exact: true }).click()
  await client.getByText('browser-client', { exact: true }).waitFor()
  assert.equal(await client.getByRole('button', { name: '单位管理', exact: true }).count(), 0)
  assert.equal(await client.getByRole('button', { name: '平台用户', exact: true }).count(), 0)
  assert.equal(await client.getByRole('button', { name: '创建单位用户', exact: true }).count(), 0)
  checks.push('new customer logs in after forced change; read-only scoped management does not reveal platform actions')

  const row = page.getByRole('row').filter({ hasText: 'browser-client' })
  await row.getByRole('button', { name: '重置密码', exact: true }).click()
  await page.getByLabel('新密码', { exact: true }).fill(resetPassword)
  await page.getByLabel('确认新密码', { exact: true }).fill(resetPassword)
  await page.getByRole('button', { name: '核对并继续', exact: true }).click()
  const resetResponse = responseFor(page, `/api/management/users/${user.id}/reset-password`, 'POST')
  await page.getByRole('button', { name: '确认重置并撤销会话', exact: true }).click()
  // The client's first-password change incremented revision after the admin list was read.
  const staleReset = await resetResponse
  assert.equal(staleReset.status(), 409)
  assert.equal((await staleReset.json()).code, 'REVISION_CONFLICT')
  assert.equal(await page.getByRole('button', { name: '确认重置并撤销会话', exact: true }).isDisabled(), true)
  const refreshed = page.waitForResponse(r => r.url().includes('/api/management/users?') && r.request().method() === 'GET')
  await page.getByRole('button', { name: '刷新后重新打开', exact: true }).click()
  const currentRows = await (await refreshed).json()
  const currentUser = currentRows.items.find(item => item.id === user.id)
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await page.waitForFunction(({ username, revision }) => {
    const row = [...document.querySelectorAll('tbody tr')].find(item => item.textContent.includes(username))
    return row && row.querySelectorAll('td')[3]?.textContent.trim() === String(revision)
  }, { username: 'browser-client', revision: currentUser.revision })
  await row.getByRole('button', { name: '重置密码', exact: true }).click()
  await page.getByLabel('新密码', { exact: true }).fill(resetPassword)
  await page.getByLabel('确认新密码', { exact: true }).fill(resetPassword)
  await page.getByRole('button', { name: '核对并继续', exact: true }).click()
  const retryReset = responseFor(page, `/api/management/users/${user.id}/reset-password`, 'POST')
  await page.getByRole('button', { name: '确认重置并撤销会话', exact: true }).click()
  assert.equal((await retryReset).status(), 200)
  checks.push('stale account revision returns 409; UI blocks overwrite and requires refresh/reopen')
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await client.getByRole('button', { name: '刷新数据', exact: true }).click()
  await client.getByRole('heading', { name: '欢迎回来', exact: true }).waitFor()
  await login(client, 'browser-client', resetPassword, true)
  await client.getByRole('heading', { name: '更新您的初始密码' }).waitFor()
  checks.push('admin credential reset revokes an already-open client session and forces another password change')

  await page.getByRole('button', { name: '组织与权限', exact: true }).click()
  await page.locator('.sidebar').getByRole('button', { name: '平台用户', exact: true }).click()
  await page.getByText('受保护账号 · 只读', { exact: true }).waitFor()
  await page.waitForFunction(() => { const x = document.querySelector('#main-content > div'); return x && getComputedStyle(x).opacity === '1' })
  await page.screenshot({ path: path.join(run, 'platform-users.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '修改自己的密码', exact: true }).click()
  await page.getByRole('heading', { name: '修改自己的密码', exact: true }).waitFor()
  await page.getByLabel('当前密码', { exact: true }).fill(adminPassword)
  await page.getByRole('button', { name: '取消并返回工作空间', exact: true }).click()
  assert.equal(await page.locator('input[type=password]').count(), 0)
  await page.getByRole('button', { name: '修改自己的密码', exact: true }).click()
  await page.getByLabel('当前密码', { exact: true }).fill(adminPassword)
  const ownUpdated = secret()
  await page.getByLabel('新密码', { exact: true }).fill(ownUpdated)
  await page.getByLabel('确认新密码', { exact: true }).fill(ownUpdated)
  await page.getByRole('button', { name: '更新密码并重新登录', exact: true }).click()
  await page.getByRole('heading', { name: '欢迎回来', exact: true }).waitFor()
  checks.push('protected root is management-readonly but may self-change password; cancel destroys secret inputs')
  const storage = await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }))
  assert.deepEqual(storage, { local: [], session: [] })
  assert.deepEqual(errors, [])
  const result = { status: 'passed', checks, real_vendor_calls: 0, artifact_directory: path.relative(root, run) }
  writeFileSync(path.join(run, 'result.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result, null, 2))
} finally {
  if (browser) await browser.close()
  if (server.exitCode === null) {
    server.kill('SIGTERM')
    await Promise.race([new Promise(resolve => server.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 10000))])
    if (server.exitCode === null) server.kill('SIGKILL')
  }
  closeSync(log)
}
