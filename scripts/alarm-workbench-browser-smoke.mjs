// Owned real-Chrome workbench acceptance; credentials are private stdin only.
import assert from 'node:assert/strict'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadChromium, browserExecutable, browserEnvironment } from './browser-fixture.mjs'
const ctx = JSON.parse(readFileSync(0, 'utf8'))
assert.match(ctx.url, /^http:\/\/127\.0\.0\.1:\d+\/$/)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const { chromium } = await loadChromium(root)
const executablePath = browserExecutable()
assert(executablePath)
const errors = [], checks = []
let browser, failure
try {
  browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true, env: browserEnvironment() })
  const requests = []
  async function create() {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1080 }, timezoneId: 'UTC', locale: 'zh-CN', reducedMotion: 'reduce' })
    const page = await context.newPage()
    page.on('pageerror', error => errors.push(error.message))
    page.on('request', request => requests.push({ page, url: request.url(), method: request.method(), tenant: request.headers()['x-tenant-id'] }))
    return page
  }
  const endpoint = response => response.request().method() === 'GET' && new URL(response.url()).pathname.endsWith('/alarm-workbench')
  function next(page, predicate = () => true) { return page.waitForResponse(response => endpoint(response) && predicate(new URL(response.url()), response.request())) }
  async function body(pending) { const response = await pending; assert.equal(response.status(), 200); return response.json() }
  async function login(page, customer = false) {
    await page.goto(new URL(customer ? '/customer/login' : '/management/login', ctx.url).href)
    await page.getByLabel('账户', { exact: true }).fill(customer ? ctx.customer_username : ctx.username)
    await page.getByLabel('密码', { exact: true }).fill(customer ? ctx.customer_password : ctx.password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    if (!customer) await page.getByRole('combobox', { name: '选择租户' }).selectOption(ctx.tenant_id)
    await page.getByRole('button', { name: customer ? '警情监控' : '报警中心', exact: true }).click()
    await page.getByRole('heading', { name: '报警筛选与口径统计', exact: true }).waitFor()
    await page.getByRole('heading', { name: '实际应用条件（服务端已确认）', exact: true }).waitFor()
  }
  function keyword(page) { return page.getByLabel('关键字（报警ID/名称/地址快照）', { exact: true }) }
  const metric = (page, name) => page.locator(`[data-workbench-metric="${name}"]`)
  async function expectMetric(page, name, value) { await metric(page, name).filter({ hasText: new RegExp(`^\\s*${value}\\s*$`) }).waitFor() }
  const workbenchRequests = page => requests.filter(request => request.page === page && request.method === 'GET' && new URL(request.url).pathname.endsWith('/alarm-workbench'))
  async function clear(page) {
    const response = next(page, url => !url.searchParams.get('keyword') && !url.searchParams.get('occurred_from') && (url.searchParams.get('page') ?? '1') === '1')
    await page.getByRole('button', { name: '清空筛选', exact: true }).click()
    return body(response)
  }
  async function apply(page, predicate = () => true) {
    const response = next(page, predicate)
    await page.getByRole('button', { name: '应用筛选', exact: true }).click()
    return body(response)
  }
  async function close(page) {
    const dialog = page.getByRole('dialog')
    if (await dialog.count()) { await dialog.getByRole('button', { name: '关闭', exact: true }).click(); await dialog.waitFor({ state: 'detached' }) }
  }
  async function emptyStorage(page) {
    assert.deepEqual(await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) })), { local: [], session: [] })
  }

  const page = await create()
  await login(page)
  const initial = workbenchRequests(page).at(-1)
  assert(initial && !new URL(initial.url).searchParams.has('size'), 'Initial page must consume the configured server page size')
  assert.equal(await page.getByLabel('物理状态筛选', { exact: true }).inputValue(), '')
  assert.equal(await page.getByLabel('人工处置筛选', { exact: true }).inputValue(), '')
  assert.equal(await page.getByLabel('复核结论筛选', { exact: true }).inputValue(), '')
  assert.equal(await page.getByLabel('开始时间（北京时间）', { exact: true }).inputValue(), '')
  assert.equal(await page.getByLabel('结束时间（北京时间）', { exact: true }).inputValue(), '')
  const beforeDraft = workbenchRequests(page).length
  await keyword(page).fill(ctx.literal_keyword)
  await page.getByText(/草稿修改未应用/).waitFor()
  assert.equal(workbenchRequests(page).length, beforeDraft, 'Draft edit issued a query')
  const literal = await apply(page, url => url.searchParams.get('keyword') === ctx.literal_keyword)
  assert.equal(literal.total, ctx.literal_count)
  assert.equal(literal.size, 5)
  assert.equal(literal.items.length, 5)
  await expectMetric(page, 'alarm_total', ctx.literal_count)
  assert.equal(await page.evaluate(() => window.__WB_BAD), undefined)
  const beforeUnapplied = workbenchRequests(page).length
  await keyword(page).fill('尚未应用不应带入翻页')
  await page.getByText(/草稿修改未应用/).waitFor()
  assert.equal(workbenchRequests(page).length, beforeUnapplied)
  const pageTwo = next(page, url => url.searchParams.get('page') === '2')
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  const second = await body(pageTwo)
  assert.equal(second.filters.keyword, ctx.literal_keyword)
  assert.equal(second.total, ctx.literal_count)
  assert.equal(second.items.length, 1)
  assert.deepEqual(second.metrics, literal.metrics)
  await expectMetric(page, 'alarm_total', ctx.literal_count)
  await clear(page)
  assert.equal(await keyword(page).inputValue(), '')
  checks.push('server-configured five-row pages, initial all without hidden dates, draft does not query and page2 uses applied literal keyword; all-page metrics remain six rather than one visible row')

  // The browser runs in UTC; datetime-local must be interpreted as explicit Beijing input.
  assert.equal(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), 'UTC')
  await page.getByLabel('开始时间（北京时间）', { exact: true }).fill(ctx.range_from_local)
  await page.getByLabel('结束时间（北京时间）', { exact: true }).fill(ctx.range_to_local)
  const ranged = await apply(page, url => Boolean(url.searchParams.get('occurred_from')))
  const rangedRequest = new URL(workbenchRequests(page).at(-1).url)
  assert(rangedRequest.searchParams.get('occurred_from').endsWith('+08:00'))
  assert(rangedRequest.searchParams.get('occurred_to').endsWith('+08:00'))
  assert.equal(Date.parse(ranged.filters.occurred_from), Date.parse(ctx.range_from_local + '+08:00'))
  assert.equal(Date.parse(ranged.filters.occurred_to), Date.parse(ctx.range_to_local + '+08:00'))
  assert.equal(ranged.total, 9, 'Explicit range must include the nine public synthetic alarm observations, not future PG precision fixtures')
  await clear(page)
  await page.getByLabel('物理状态筛选', { exact: true }).selectOption('NORMAL')
  await page.getByLabel('人工处置筛选', { exact: true }).selectOption('UNHANDLED')
  await page.getByLabel('复核结论筛选', { exact: true }).selectOption('UNREVIEWED')
  const separated = await apply(page, url => url.searchParams.get('physical_state') === 'NORMAL')
  assert.equal(separated.filters.response_state, 'UNHANDLED')
  assert.equal(separated.filters.conclusion, 'UNREVIEWED')
  assert.equal(separated.metrics.alarm_total, 1)
  assert.equal(separated.metrics.unhandled, 1)
  assert.equal(separated.metrics.unrecovered, 0)
  await expectMetric(page, 'unhandled', 1)
  await expectMetric(page, 'unrecovered', 0)
  await clear(page)

  await keyword(page).fill(ctx.alarm_id)
  const targeted = await apply(page, url => url.searchParams.get('keyword') === ctx.alarm_id)
  assert.equal(targeted.total, 1)
  assert.equal(targeted.items[0].physical_state, 'FIRE')
  await expectMetric(page, 'unhandled', 1)
  await expectMetric(page, 'unrecovered', 1)
  await page.locator(`[data-alarm-id="${ctx.alarm_id}"]`).getByRole('button', { name: '查看', exact: true }).click()
  const review = page.getByRole('dialog', { name: '核查报警，留存处置记录', exact: true })
  await review.getByLabel('复核结论', { exact: true }).selectOption('FALSE_ALARM')
  await review.getByLabel('现场情况 / 处置备注', { exact: true }).fill('不领取直接复核，人工已处理仍然物理火警，不发命令')
  const refreshed = next(page, url => url.searchParams.get('keyword') === ctx.alarm_id)
  await review.getByRole('button', { name: '提交复核结论', exact: true }).click()
  const after = await body(refreshed)
  assert.equal(after.filters.keyword, ctx.alarm_id)
  assert.equal(after.metrics.alarm_total, 1)
  assert.equal(after.metrics.unhandled, 0)
  assert.equal(after.metrics.handled, 1)
  assert.equal(after.metrics.unrecovered, 1)
  assert.equal(after.metrics.true_fire, 0)
  await expectMetric(page, 'unhandled', 0)
  await expectMetric(page, 'unrecovered', 1)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: path.join(ctx.artifact_directory, 'management-alarm-workbench.png'), fullPage: true, animations: 'disabled' })
  await page.locator(`[data-alarm-id="${ctx.alarm_id}"]`).getByRole('button', { name: '查看详情', exact: true }).click()
  await page.getByRole('dialog', { name: '报警只读详情', exact: true }).waitFor()
  await close(page)
  checks.push('UTC browser sends explicit UTC+08 time bounds and receives nine matched facts; existing direct review re-reads unchanged ID filter and truthful HANDLED/FIRE counts, no claim or implied recovery')

  await keyword(page).fill('跨单位未应用草稿')
  const scope = next(page, (_url, request) => request.headers()['x-tenant-id'] === ctx.other_tenant_id)
  await page.getByRole('combobox', { name: '选择租户' }).selectOption(ctx.other_tenant_id)
  const scoped = await body(scope)
  assert.equal(scoped.filters.keyword, '')
  assert.equal(scoped.total, 1)
  assert.equal(await keyword(page).inputValue(), '')
  assert(!(await page.locator('body').textContent()).includes(ctx.alarm_id))
  assert(!(await page.locator('body').textContent()).includes('跨单位未应用草稿'))
  await emptyStorage(page)

  const customer = await create()
  await login(customer, true)
  await keyword(customer).fill(ctx.literal_keyword)
  const safe = await apply(customer, url => url.searchParams.get('keyword') === ctx.literal_keyword)
  assert.equal(safe.total, ctx.literal_count)
  assert(safe.items.every(row => !Object.hasOwn(row, 'actor_id') && !Object.hasOwn(row, 'actor_name') && !Object.hasOwn(row, 'remark')))
  await expectMetric(customer, 'alarm_total', ctx.literal_count)
  await customer.screenshot({ path: path.join(ctx.artifact_directory, 'customer-alarm-workbench.png'), fullPage: true, animations: 'disabled' })
  await customer.setViewportSize({ width: 390, height: 844 })
  // setViewportSize can return before the existing responsive shell finishes its CSS/Motion resize.
  // Await the required final geometry, not a sleep or relaxed width assertion; keep failure evidence.
  try { await customer.waitForFunction(() => document.documentElement.scrollWidth <= window.innerWidth, null, { timeout: 5000 }) }
  catch (error) {
    const layout = await customer.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth, overflow: [...document.querySelectorAll('body *')].map(element => { const box = element.getBoundingClientRect(); return { tag: element.tagName, class: element.className, left: box.left, right: box.right, width: box.width } }).filter(box => box.right > innerWidth + 1 || box.left < -1).slice(0, 30) }))
    writeFileSync(path.join(ctx.artifact_directory, 'mobile-layout-failure.json'), JSON.stringify(layout, null, 2))
    await customer.screenshot({ path: path.join(ctx.artifact_directory, 'mobile-alarm-workbench-failure.png'), fullPage: true, animations: 'disabled' })
    throw error
  }
  assert(await customer.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mobile workbench overflowed horizontally')
  await customer.screenshot({ path: path.join(ctx.artifact_directory, 'mobile-alarm-workbench.png'), fullPage: true, animations: 'disabled' })
  const firstRow = customer.locator('[data-alarm-id]').first()
  const oldReview = firstRow.getByRole('button', { name: '查看', exact: true })
  // Preserve the pre-existing exact 查看 entry: for no-review users it opens readonly details,
  // rather than requiring a new disabled-table-button behavior which was never the contract.
  await oldReview.click()
  const legacyReadonly = customer.getByRole('dialog', { name: '核查报警，留存处置记录', exact: true })
  await legacyReadonly.waitFor()
  assert.equal(await legacyReadonly.getByRole('button', { name: '提交复核结论', exact: true }).count(), 0)
  assert.equal(await legacyReadonly.getByLabel('复核结论', { exact: true }).count(), 0)
  assert(!requests.some(request => request.page === customer && request.method === 'POST' && new URL(request.url).pathname.endsWith('/review')))
  await close(customer)
  await firstRow.getByRole('button', { name: '查看详情', exact: true }).click()
  const detail = customer.getByRole('dialog', { name: '报警只读详情', exact: true })
  await detail.waitFor()
  assert.equal(await detail.getByRole('button', { name: '查看业务轨迹', exact: true }).count(), 0)
  assert.equal(await detail.getByRole('button', { name: '开始人工复核', exact: true }).count(), 0)
  await close(customer)
  const customerRequests = requests.filter(request => request.page === customer)
  assert(!customerRequests.some(request => /\/api\/customer\/(?:devices|summary|products)(?:\?|\/|$)|\/history(?:\?|$)/.test(new URL(request.url).pathname + new URL(request.url).search)), 'Minimal reader fetched an unrelated protected directory/history')
  assert.equal(await customer.evaluate(() => window.__WB_BAD), undefined)
  await emptyStorage(customer)
  assert(!requests.some(request => /\/(?:claim|batch-review)(?:\?|$)/.test(request.url)))
  assert.deepEqual(errors, [])
  checks.push('tenant switch destroys draft/applied old data, minimal alarm-read customer has actual filtered metrics without directories/history, preserved readonly detail, 390px no horizontal overflow, escaped literal text, no persistent credentials or page errors')
  const report = { status: 'passed', checks, real_vendor_calls: 0, notification_calls: 0, hardware_downlinks: 0 }
  writeFileSync(path.join(ctx.artifact_directory, 'browser-result.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
} catch (error) {
  failure = String(error?.stack ?? error)
  for (const secret of [ctx.password, ctx.customer_password]) if (secret) failure = failure.replaceAll(secret, '[REDACTED]')
} finally { if (browser) await browser.close() }
if (failure) { console.error(failure); process.exitCode = 1 }
