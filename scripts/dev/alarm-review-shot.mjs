// 报警详情页处置交互：打开详情 → 选结论/填备注 → 提交 → 截图。仅连 127.0.0.1。
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import path from 'node:path'
import { browserExecutable, browserEnvironment, loadChromium } from '../browser-fixture.mjs'

const BASE = process.env.LEGACY_BASE_URL, OUT = process.env.LEGACY_SHOT_DIR
const USER = process.env.LEGACY_USER, PASS = process.env.LEGACY_PASS
const TENANT_ID = process.env.LEGACY_TENANT_ID, TENANT = process.env.LEGACY_TENANT_NAME
const sha = b => createHash('sha256').update(b).digest('hex').toUpperCase()
if (!BASE || !OUT || !USER || !PASS) { console.error('missing env'); process.exit(2) }
mkdirSync(OUT, { recursive: true })
const checks = [], screenshots = []
let browser, failure = null
const shot = async (page, name) => {
  await page.evaluate(() => document.fonts.ready)
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))))
  const file = path.join(OUT, name + '.png')
  if (existsSync(file)) throw new Error('exists: ' + file)
  await page.screenshot({ path: file, fullPage: false, animations: 'disabled', timeout: 15000 })
  const bytes = readFileSync(file)
  screenshots.push({ name, bytes: bytes.length, sha256: sha(bytes) })
}
try {
  const { chromium } = await loadChromium(path.resolve(process.env.LEGACY_ROOT ?? '.'))
  browser = await chromium.launch({ executablePath: browserExecutable(), headless: true, chromiumSandbox: true, env: browserEnvironment(), timeout: 20000 })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } })
  const page = await context.newPage()
  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.getByRole('button', { name: '管理端', exact: true }).click()
  await page.getByLabel('账户', { exact: true }).fill(USER)
  await page.getByLabel('密码', { exact: true }).fill(PASS)
  await page.getByRole('button', { name: /登录管理端/ }).click()
  await page.getByRole('main').waitFor({ state: 'visible', timeout: 30000 })

  const selector = page.getByLabel('选择租户', { exact: true })
  if (await selector.count()) { await selector.waitFor({ state: 'visible', timeout: 20000 }); await selector.selectOption({ label: TENANT }) }
  else {
    await page.getByRole('button', { name: '指定单位ID', exact: true }).click()
    const d = page.getByRole('dialog', { name: '指定单位ID', exact: true })
    await d.getByLabel('单位ID（需已授权）', { exact: true }).fill(TENANT_ID)
    await d.getByRole('button', { name: '应用单位ID', exact: true }).click()
  }

  const list = page.waitForResponse(r => r.url().includes('/api/management/alarms'), { timeout: 25000 }).catch(() => null)
  await page.getByRole('button', { name: '报警中心', exact: true }).click()
  const listed = await list
  checks.push({ code: 'ALARMS_REQUEST', value: listed ? listed.status() : 'timeout' })
  await page.locator('[data-alarm-id]').first().waitFor({ state: 'visible', timeout: 20000 })
  await shot(page, 'review-1-list')

  await page.locator('[data-alarm-id]').first().getByRole('button', { name: '查看详情', exact: true }).click()
  await page.waitForTimeout(1200)
  await shot(page, 'review-2-detail')
  await page.getByRole('button', { name: '开始人工复核', exact: true }).click()
  const conclusion = page.getByLabel('复核结论', { exact: true })
  await conclusion.waitFor({ state: 'visible', timeout: 20000 })
  checks.push({ code: 'DETAIL_HAS_REVIEW_FORM', value: true })

  await conclusion.selectOption({ label: '误报' })
  await page.getByLabel('现场情况 / 处置备注', { exact: true }).fill('界面闭环演练：现场核查为误报，无烟无火')
  await shot(page, 'review-3-filled')

  const submit = page.waitForResponse(r => r.url().includes('/review') && r.request().method() === 'POST', { timeout: 25000 }).catch(() => null)
  await page.getByRole('button', { name: '提交复核结论', exact: true }).click()
  const done = await submit
  checks.push({ code: 'REVIEW_REQUEST', value: done ? done.status() : 'timeout' })
  await page.waitForTimeout(1200)
  const body = await page.locator('#main-content').innerText()
  checks.push({ code: 'ROW_NOW_HANDLED', value: /已处理|误报/.test(body) })
  await shot(page, 'review-4-after')
} catch (error) {
  failure = String(error?.message ?? error).slice(0, 300)
} finally {
  if (browser) await browser.close().catch(() => {})
}
const report = { status: failure ? 'failed' : 'passed', checks, screenshots, failure }
writeFileSync(path.join(OUT, 'review-shots.json'), JSON.stringify(report, null, 2))
console.log(JSON.stringify(report, null, 2))
if (failure) process.exitCode = 1
