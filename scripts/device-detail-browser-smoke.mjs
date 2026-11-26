// P8a1 finite real-Chrome, controls-only acceptance. Credentials enter private stdin only.
// The single malformed-200 check is explicitly an interception injection, not server evidence.
import assert from 'node:assert/strict'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadChromium, browserExecutable, browserEnvironment } from './browser-fixture.mjs'

const ctx = JSON.parse(readFileSync(0, 'utf8'))
assert.match(ctx.url, /^http:\/\/127\.0\.0\.1:\d+\/$/)
assert(path.isAbsolute(ctx.artifact_directory) && existsSync(ctx.artifact_directory))
for (const key of ['tenant_id', 'other_tenant_id', 'device_id', 'other_device_id', 'empty_device_id']) assert.match(ctx[key], /^[A-Za-z0-9-]+$/)
assert.notEqual(ctx.tenant_id, ctx.other_tenant_id)
assert.notEqual(ctx.device_id, ctx.other_device_id)
assert.notEqual(ctx.device_id, ctx.empty_device_id)
assert.equal(ctx.linked_total, 10, 'Synthetic fixture must contain ten target-device alarms')
assert.equal(ctx.literal_count, 6, 'Synthetic fixture must contain six literal-name snapshots')
assert.equal(ctx.battery, '0.12345678901234567')
assert.notEqual(String(Number(ctx.battery)), ctx.battery, 'Fixture must expose a real JS decimal-display rounding hazard within the existing parser18-input-digit bound')
assert.match(ctx.boundary_from, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}$/)
assert.match(ctx.boundary_to, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}$/)
assert.match(ctx.boundary_alarm, /^[A-Za-z0-9-]+$/)
const principals = ['device_reader', 'both_reader', 'device_customer', 'both_customer']
for (const key of principals) {
  assert.deepEqual(Object.keys(ctx[key]).sort(), ['password', 'username'])
  assert(typeof ctx[key].username === 'string' && ctx[key].username.length > 0)
  assert(typeof ctx[key].password === 'string' && ctx[key].password.length > 0)
}
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const { chromium } = await loadChromium(root)
const executablePath = browserExecutable()
assert(executablePath, 'Select an installed Chromium browser')
const origin = new URL(ctx.url).origin
const requests = [], pageErrors = [], pages = [], checks = [], failedRequests = [], blockedRequests = []
const metricNames = ['alarm_total', 'unhandled', 'handled', 'unrecovered', 'recovered', 'true_fire']
const definitions = { scope: 'FILTERED_ALL_PAGES', time_field: 'ALARM_OCCURRED_AT', time_bounds: 'FROM_INCLUSIVE_TO_EXCLUSIVE', unhandled: 'RESPONSE_UNHANDLED', unrecovered: 'PHYSICAL_FIRE', true_fire: 'CURRENT_CONCLUSION_TRUE_FIRE' }
const literal = '%_!'
let browser, failure
const passwords = principals.map(key => ctx[key].password)
const redact = value => passwords.reduce((text, secret) => text.split(secret).join('[REDACTED]'), String(value))
  .replace(/\bBearer\s+[A-Za-z0-9._~+\/-]+/gi, 'Bearer [REDACTED]')
  .replace(/((?:authorization|set-cookie|cookie)\s*[:=]\s*)[^\r\n]+/gi, '$1[REDACTED]')
const detailPath = (customer, id) => `/api/${customer ? 'customer' : 'management'}/devices/${id}`
const listPath = customer => `/api/${customer ? 'customer' : 'management'}/devices`
const sheet = page => page.getByRole('dialog', { name: '设备只读详情', exact: true })
const scopeFor = (customer, tenant = ctx.tenant_id) => customer ? undefined : tenant

function queryMatches(url, expected) {
  const actual = [...url.searchParams.entries()].sort(([a], [b]) => a.localeCompare(b))
  const wanted = Object.entries(expected).map(([key, value]) => [key, String(value)]).sort(([a], [b]) => a.localeCompare(b))
  return JSON.stringify(actual) === JSON.stringify(wanted)
}
function next(page, pathname, params = {}, tenant, method = 'GET') {
  return page.waitForResponse(response => {
    const request = response.request(), url = new URL(response.url())
    return request.method() === method && url.origin === origin && url.pathname === pathname && queryMatches(url, params) && request.headers()['x-tenant-id'] === tenant
  })
}
async function body(pending, expected = 200) {
  const response = await pending
  assert.equal(response.status(), expected, 'Unexpected status on the exact requested route/scope/query')
  const raw = await response.text()
  return { response, raw, data: JSON.parse(raw) }
}
function calls(page, pathname) { return requests.filter(row => row.page === page && row.method === 'GET' && row.pathname === pathname) }
function linkedCalls(page) { return requests.filter(row => row.page === page && /^\/api\/(management|customer)\/devices\/[^/]+\/alarms$/.test(row.pathname)) }
async function bounded(promise, label, milliseconds = 10000) {
  let timer
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(label)), milliseconds) })]) }
  finally { clearTimeout(timer) }
}
const allowedIds = new Set([ctx.device_id, ctx.other_device_id, ctx.empty_device_id])
function allowedApi(pathname) {
  if (['/api/health', '/api/auth/login', '/api/auth/logout', '/api/auth/me'].includes(pathname)) return true
  const match = /^\/api\/(management|customer)\/devices(?:\/([^/]+)(\/alarms)?)?$/.exec(pathname)
  return !!match && (!match[2] || (allowedIds.has(match[2]) && (!match[3] || match[2] === ctx.device_id)))
}
async function create(customer, combined) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 }, timezoneId: 'UTC', locale: 'zh-CN', reducedMotion: 'reduce', serviceWorkers: 'block' })
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    const allowedMethod = request.method() === 'GET' || (request.method() === 'POST' && /^\/api\/auth\/(login|logout)$/.test(url.pathname))
    if (url.origin !== origin || !allowedMethod || (url.pathname.startsWith('/api/') && (!allowedApi(url.pathname) || (!combined && url.pathname.endsWith('/alarms'))))) {
      blockedRequests.push({ method: request.method(), origin: url.origin, pathname: url.pathname })
      await route.abort('blockedbyclient')
    } else await route.continue()
  })
  const page = await context.newPage()
  pages.push({ page, context, customer, combined })
  // Capture before navigation/login; never retain Authorization, request body or credentials.
  page.on('request', request => {
    const url = new URL(request.url())
    requests.push({ page, method: request.method(), origin: url.origin, pathname: url.pathname, query: url.search, tenant: request.headers()['x-tenant-id'] })
  })
  page.on('requestfailed', request => failedRequests.push({ request, failure: request.failure()?.errorText ?? '' }))
  page.on('pageerror', error => pageErrors.push(error.message))
  return page
}
async function explicitScope(page, tenant, expected = 200, expectsDeviceList = true) {
  await page.getByRole('button', { name: '指定单位ID', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '指定单位ID', exact: true })
  await dialog.getByLabel('单位ID（需已授权）', { exact: true }).fill(tenant)
  const pending = expectsDeviceList ? next(page, listPath(false), { page: 1, keyword: '' }, tenant) : null
  await dialog.getByRole('button', { name: '应用单位ID', exact: true }).click()
  const result = pending ? await body(pending, expected) : null
  await dialog.waitFor({ state: 'detached' })
  return result
}
async function login(page, principal, customer = false) {
  await page.goto(new URL(customer ? '/customer/login' : '/management/login', ctx.url).href)
  await page.getByLabel('账户', { exact: true }).fill(principal.username)
  await page.getByLabel('密码', { exact: true }).fill(principal.password)
  const authenticated = next(page, '/api/auth/login', {}, undefined, 'POST')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  assert.equal((await authenticated).status(), 200)
  // No first-login password change: Python supplies already-initialized principals.
  await page.getByRole('button', { name: '退出登录', exact: true }).waitFor()
  if (customer) {
    assert.equal(await page.getByRole('button', { name: '指定单位ID', exact: true }).count(), 0)
    assert.equal(await page.getByRole('combobox', { name: '选择租户', exact: true }).count(), 0)
    const pending = next(page, listPath(true), { page: 1, keyword: '' }, undefined)
    await page.getByRole('button', { name: '设备监测', exact: true }).click()
    await body(pending)
  } else {
    assert.equal(await page.getByRole('combobox', { name: '选择租户', exact: true }).count(), 0)
    await explicitScope(page, ctx.tenant_id, 200, false)
    const pending = next(page, listPath(false), { page: 1, keyword: '' }, ctx.tenant_id)
    await page.getByRole('button', { name: '设备监测', exact: true }).click()
    await body(pending)
  }
  await page.locator(`[data-device-id="${ctx.device_id}"]`).waitFor()
}
async function open(page, customer, id = ctx.device_id, tenant = ctx.tenant_id) {
  const pending = next(page, detailPath(customer, id), {}, scopeFor(customer, tenant))
  await page.locator(`[data-device-id="${id}"]`).getByRole('button', { name: '查看设备详情', exact: true }).click()
  const result = await body(pending)
  assert.deepEqual(Object.keys(result.data).sort(), ['as_of', 'consumer', 'device', 'timezone'])
  assert.equal(result.data.device.id, id)
  assert.equal(result.data.timezone, 'Asia/Shanghai')
  assert.equal(result.data.consumer, 'QUERY_CONNECTIVITY_ONLY')
  await sheet(page).locator(`[data-device-detail-id="${id}"]`).waitFor()
  assertSafe(result.data)
  return result
}
async function close(page) {
  const dialog = sheet(page)
  await dialog.waitFor()
  await page.keyboard.press('Escape')
  await dialog.waitFor({ state: 'detached' })
  assert.equal(await page.locator('[data-device-detail-id]').count(), 0)
}
async function precise(page, result) {
  assert.equal(result.data.device.name, ctx.device_name)
  assert(new RegExp(`"battery_percent"\\s*:\\s*${ctx.battery.replaceAll('.', '\\.')}0*(?=\\s*[,}])`).test(result.raw), 'Actual response lost the exact battery token')
  await sheet(page).getByRole('heading', { name: ctx.device_name, exact: true }).waitFor()
  await sheet(page).getByText(`电量 ${ctx.battery}%`, { exact: true }).waitFor()
  assert.equal(await sheet(page).locator('[data-device-detail-id]').getAttribute('data-device-detail-id'), ctx.device_id)
}
function assertSafe(value) {
  if (Array.isArray(value)) { value.forEach(assertSafe); return }
  if (!value || typeof value !== 'object') return
  for (const [key, item] of Object.entries(value)) {
    assert(!['access_token', 'password', 'actor_password', 'actor_id', 'actor_name', 'remark', 'reason', 'raw_payload', 'payload', 'schema', 'script', 'phone', 'phone_masked', 'ciphertext', 'lease_token', 'rendered_message'].includes(key), 'Device read exposed an unrelated/sensitive field')
    assertSafe(item)
  }
}
async function assertMetrics(page, result, total) {
  const data = result.data, dialog = sheet(page)
  assert.deepEqual(Object.keys(data).sort(), ['as_of', 'device_id', 'filters', 'items', 'metric_definitions', 'metrics', 'page', 'size', 'timezone', 'total'])
  assert.equal(data.device_id, ctx.device_id)
  assert.equal(data.total, total)
  assert.equal(data.metrics.alarm_total, total)
  assert.equal(data.timezone, 'Asia/Shanghai')
  assert.deepEqual(data.metric_definitions, definitions)
  assert.equal(data.metrics.unhandled + data.metrics.handled, total)
  assert.equal(data.metrics.unrecovered + data.metrics.recovered, total)
  assert(data.metrics.true_fire <= total)
  assert(data.items.length > 0, 'Required linked page must not pass as a vacuous empty result')
  assert.equal(new Set(data.items.map(row => row.id)).size, data.items.length)
  for (const key of metricNames) {
    assert(Number.isSafeInteger(data.metrics[key]) && data.metrics[key] >= 0)
    await dialog.locator(`[data-device-alarm-metric="${key}"]`).filter({ hasText: new RegExp(`^\\s*${data.metrics[key]}\\s*$`) }).waitFor()
  }
  for (const row of data.items) {
    assert.equal(row.device_id, ctx.device_id)
    const rendered = dialog.locator(`[data-device-alarm-id="${row.id}"]`)
    await rendered.waitFor()
    assert((await rendered.textContent()).includes(row.device_name), 'Alarm historical name was replaced by the current device name')
    assert((await rendered.textContent()).includes(row.address))
  }
  assert.equal(await dialog.locator('[data-device-alarm-id]').count(), data.items.length)
  assertSafe(data)
  assert.equal(await page.evaluate(() => window.__WB_BAD), undefined, 'Literal snapshot text executed as a script')
}
async function linked(page, customer, params = { page: 1 }) {
  const pending = next(page, detailPath(customer, ctx.device_id) + '/alarms', params, scopeFor(customer))
  await sheet(page).getByRole('button', { name: '读取关联报警', exact: true }).click()
  return body(pending)
}
function canonicalMicros(instant) {
  const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})$/.exec(instant)
  assert(m, 'Expected a precise explicit-offset fixture/server timestamp')
  const seconds = new Date(m[1] + m[3]).toISOString().slice(0, 19)
  return `${seconds}.${(m[2] ?? '').padEnd(6, '0')}Z`
}
async function combinedFlow(page, customer) {
  const start = linkedCalls(page).length
  await precise(page, await open(page, customer))
  assert.equal(linkedCalls(page).length, start, 'Opening current detail implicitly fetched alarms')
  assert.equal(await sheet(page).locator('[data-device-alarm-metric]').count(), 0, 'Unread alarms were rendered as a count')
  const initial = await linked(page, customer)
  assert.equal(initial.data.page, 1)
  assert.equal(initial.data.size, 5, 'Fixture-configured page size was not consumed')
  assert.equal(initial.data.items.length, 5)
  await assertMetrics(page, initial, ctx.linked_total)
  const dialog = sheet(page), keyword = dialog.getByLabel('关联报警关键字', { exact: true })
  const beforeDraft = linkedCalls(page).length
  await keyword.fill(literal)
  assert.equal(linkedCalls(page).length, beforeDraft, 'Typing a filter silently queried')
  let pending = next(page, detailPath(customer, ctx.device_id) + '/alarms', { page: 1, keyword: literal }, scopeFor(customer))
  await dialog.getByRole('button', { name: '查询关联报警', exact: true }).click()
  const filtered = await body(pending)
  assert.equal(filtered.data.filters.keyword, literal)
  assert.equal(filtered.data.items.length, 5)
  assert(filtered.data.items.every(row => row.device_name.includes(literal) && row.device_name !== ctx.device_name))
  await assertMetrics(page, filtered, ctx.literal_count)
  const appliedCalls = linkedCalls(page).length
  await keyword.fill('未应用的关联报警草稿')
  assert.equal(linkedCalls(page).length, appliedCalls)
  pending = next(page, detailPath(customer, ctx.device_id) + '/alarms', { page: 2, keyword: literal }, scopeFor(customer))
  await dialog.getByRole('button', { name: '下一页', exact: true }).click()
  const second = await body(pending)
  assert.equal(second.data.page, 2)
  assert.equal(second.data.items.length, 1)
  assert.deepEqual(second.data.metrics, filtered.data.metrics)
  assert.equal(second.data.filters.keyword, literal)
  assert(!filtered.data.items.some(row => row.id === second.data.items[0].id), 'Second page repeated a page-one alarm')
  await assertMetrics(page, second, ctx.literal_count)
  pending = next(page, detailPath(customer, ctx.device_id) + '/alarms', { page: 1 }, scopeFor(customer))
  await dialog.getByRole('button', { name: '清空关联报警条件', exact: true }).click()
  const cleared = await body(pending)
  assert.equal(cleared.data.filters.keyword, '')
  assert.equal(await keyword.inputValue(), '')
  await assertMetrics(page, cleared, ctx.linked_total)
  // The UTC browser must preserve the precise Beijing text, not native-date millisecond truncation.
  await dialog.getByLabel('关联报警开始时间（北京时间）', { exact: true }).fill(ctx.boundary_from)
  await dialog.getByLabel('关联报警结束时间（北京时间）', { exact: true }).fill(ctx.boundary_to)
  const explicitFrom = ctx.boundary_from + '+08:00', explicitTo = ctx.boundary_to + '+08:00'
  pending = next(page, detailPath(customer, ctx.device_id) + '/alarms', { page: 1, occurred_from: explicitFrom, occurred_to: explicitTo }, scopeFor(customer))
  await dialog.getByRole('button', { name: '查询关联报警', exact: true }).click()
  const preciseTime = await body(pending)
  assert.equal(canonicalMicros(preciseTime.data.filters.occurred_from), canonicalMicros(explicitFrom))
  assert.equal(canonicalMicros(preciseTime.data.filters.occurred_to), canonicalMicros(explicitTo))
  assert(preciseTime.data.filters.occurred_from.endsWith('Z') && preciseTime.data.filters.occurred_to.endsWith('Z'))
  await assertMetrics(page, preciseTime, 1)
  assert.equal(preciseTime.data.items[0].id, ctx.boundary_alarm)
  const actualSummary = dialog.locator('p').filter({ hasText: /^服务端实际应用条件：/ })
  await actualSummary.filter({ hasText: preciseTime.data.filters.occurred_from }).waitFor()
  await actualSummary.filter({ hasText: preciseTime.data.filters.occurred_to }).waitFor()
  assert.equal(await actualSummary.count(), 1, 'UTC filter display must be the unique applied-summary, not the report or alarm timestamp')
  pending = next(page, detailPath(customer, ctx.device_id) + '/alarms', { page: 1 }, scopeFor(customer))
  await dialog.getByRole('button', { name: '清空关联报警条件', exact: true }).click()
  await assertMetrics(page, await body(pending), ctx.linked_total)
  checks.push('actual UTC-context browser retains six-microsecond Beijing text and explicit+08 query; independent server UTC filters and one-microsecond half-open interval select exactly the known alarm, then clear restores all ten')
  assert.equal(await dialog.locator('form form').count(), 0)
  assert.equal(await dialog.getByRole('button', { name: /复核|业务轨迹|排队|下发|领取/ }).count(), 0)
  return cleared
}
async function refreshCurrentThenRead(page, customer) {
  const before = linkedCalls(page).length
  const pending = next(page, detailPath(customer, ctx.device_id), {}, scopeFor(customer))
  await sheet(page).getByRole('button', { name: '刷新设备详情', exact: true }).click()
  const current = await body(pending)
  await precise(page, current)
  assert.equal(linkedCalls(page).length, before, 'Refreshing current facts silently re-read alarms')
  assert.equal(await sheet(page).locator('[data-device-alarm-metric], [data-device-alarm-id]').count(), 0)
  assert.equal(await sheet(page).getByLabel('关联报警关键字', { exact: true }).count(), 0)
  await assertMetrics(page, await linked(page, customer), ctx.linked_total)
}
async function showActualLinks(page) {
  await sheet(page).locator('[data-device-alarm-metric="alarm_total"]').evaluate(element => element.scrollIntoView({ block: 'start' }))
  await sheet(page).locator('[data-device-alarm-id]').first().waitFor()
}
async function malformedInjection(page) {
  const pathname = detailPath(false, ctx.device_id) + '/alarms'
  let injected = 0, rejectInterception
  const interceptionFailed = new Promise((_, reject) => { rejectInterception = reject })
  // Install a handler immediately; the original promise still rejects the main check below.
  interceptionFailed.catch(() => {})
  const match = url => url.origin === origin && url.pathname === pathname
  const handler = async route => {
    try {
      if (route.request().headers()['x-tenant-id'] !== ctx.tenant_id) { await route.continue(); return }
      const response = await route.fetch()
      assert.equal(response.status(), 200)
      const original = await response.text()
      assert.equal(JSON.parse(original).total, ctx.linked_total)
      const expression = /("total"\s*:\s*)10(?=\s*[,}])/
      assert(expression.test(original), 'No exact total token to inject')
      const altered = original.replace(expression, (_, prefix) => prefix + '9.99999999999999999999999')
      assert.notEqual(altered, original)
      injected++
      await route.fulfill({ response, body: altered })
    } catch (error) { rejectInterception(error) }
  }
  await page.route(match, handler)
  try {
    const pending = next(page, pathname, { page: 1 }, ctx.tenant_id)
    await sheet(page).getByRole('button', { name: '刷新关联报警', exact: true }).click()
    assert.equal((await Promise.race([pending, interceptionFailed])).status(), 200)
    await sheet(page).getByText(/DEVICE_DETAIL_UNCONFIRMED/).waitFor()
    assert.equal(injected, 1)
    assert.equal(await sheet(page).locator('[data-device-alarm-metric]').count(), 0)
    assert.equal(await sheet(page).locator('[data-device-alarm-id]').count(), 0)
    assert.equal(await sheet(page).locator(`[data-device-detail-id="${ctx.device_id}"]`).count(), 1)
  } finally { await page.unroute(match, handler) }
  const pending = next(page, pathname, { page: 1 }, ctx.tenant_id)
  await sheet(page).getByRole('button', { name: '刷新关联报警', exact: true }).click()
  await assertMetrics(page, await body(pending), ctx.linked_total)
  checks.push('controlled malformed-200 interception injection of a near-integer total clears linked rows/metrics and a separate real refresh recovers; injected bytes are not claimed as server facts')
}
async function lateScope(page) {
  await close(page)
  const pathname = detailPath(false, ctx.device_id)
  let release, observed, rejectObserved, finished
  const delivery = new Promise(resolve => { release = resolve })
  const captured = new Promise((resolve, reject) => { observed = resolve; rejectObserved = reject })
  captured.catch(() => {}) // Keep early route errors handled; bounded(captured) still fails the check.
  const completed = new Promise(resolve => { finished = resolve })
  const problems = []
  const match = url => url.origin === origin && url.pathname === pathname
  const handler = async route => {
    if (route.request().headers()['x-tenant-id'] !== ctx.tenant_id) { await route.continue(); return }
    try {
      const response = await route.fetch()
      assert.equal(response.status(), 200)
      const actual = await response.json()
      assert.equal(actual.device.id, ctx.device_id)
      observed(actual)
      await delivery
      try { await route.fulfill({ response }) }
      catch (error) {
        const aborted = failedRequests.some(row => row.request === route.request() && /ERR_ABORTED|abort|cancel/i.test(row.failure))
        if (!aborted && !/net::ERR_ABORTED|request (?:was |has been )?(?:aborted|cancell?ed)|Invalid InterceptionId|interception.*(?:invalid|not found)/i.test(error.message)) problems.push(error)
      }
    } catch (error) { problems.push(error); rejectObserved(error) }
    finally { finished() }
  }
  await page.route(match, handler)
  try {
    await page.locator(`[data-device-id="${ctx.device_id}"]`).getByRole('button', { name: '查看设备详情', exact: true }).click()
    const actualA = await bounded(captured, 'Actual scoped detail A was not captured')
    assert.equal(actualA.device.name, ctx.device_name)
    await sheet(page).waitFor()
    await close(page)
    await explicitScope(page, ctx.other_tenant_id)
    const actualB = await open(page, false, ctx.other_device_id, ctx.other_tenant_id)
    assert.equal(actualB.data.device.id, ctx.other_device_id)
    release()
    await bounded(completed, 'Held response A was not released')
    assert.deepEqual(problems, [], 'Held-route failure was not an expected request abort')
    await page.unroute(match, handler)
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    assert.equal(await sheet(page).locator('[data-device-detail-id]').getAttribute('data-device-detail-id'), ctx.other_device_id)
    assert.equal(await sheet(page).locator(`[data-device-detail-id="${ctx.device_id}"]`).count(), 0)
    assert.equal(await sheet(page).locator('[data-device-alarm-metric]').count(), 0)
  } finally {
    release()
    await bounded(completed, 'Held route cleanup did not settle').catch(error => { if (!failure) problems.push(error) })
    await page.unroute(match, handler)
    assert.deepEqual(problems, [], 'Unrelated held-route error must not be swallowed')
  }
}
async function stored(page) {
  assert.deepEqual(await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) })), { local: [], session: [] })
  assert.equal((await page.context().cookies()).length, 0, 'Owned read-only context persisted a cookie')
}
async function screenshot(page, name) {
  assert.equal(await page.locator('input[type="password"], textarea').evaluateAll(inputs => inputs.some(input => input.value !== '')), false)
  await page.screenshot({ path: path.join(ctx.artifact_directory, name), fullPage: true, animations: 'disabled' })
}
function network() {
  assert.equal(blockedRequests.length, 0, 'The browser attempted an external, unrelated API or non-auth mutation (blocked before execution)')
  for (const row of requests) {
    assert.equal(row.origin, origin, 'An application request left the owned fixture origin')
    if (row.method !== 'GET') assert(row.method === 'POST' && /^\/api\/auth\/(login|logout)$/.test(row.pathname), 'A read-only browser emitted a non-auth mutation')
    if (!row.pathname.startsWith('/api/')) continue
    assert(allowedApi(row.pathname), 'An unrelated protected directory/history/notification route was fetched')
    const owner = pages.find(item => item.page === row.page)
    if (/^\/api\/customer\//.test(row.pathname)) assert.equal(row.tenant, undefined, 'Customer forged a tenant header')
    if (/^\/api\/(management|customer)\//.test(row.pathname)) assert(row.pathname.startsWith(`/api/${owner.customer ? 'customer' : 'management'}/devices`))
    if (!owner.combined) assert(!/\/alarms$/.test(row.pathname), 'Device-only principal queried linked alarms')
  }
}

try {
  browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true, env: browserEnvironment() })
  const reader = await create(false, false)
  await login(reader, ctx.device_reader)
  const first = await open(reader, false)
  await precise(reader, first)
  assert.equal(await sheet(reader).getByRole('button', { name: '读取关联报警', exact: true }).count(), 0)
  await sheet(reader).getByText(/缺独立alarm:read/).waitFor()
  assert.equal(await sheet(reader).locator('[data-device-alarm-metric]').count(), 0)
  assert.equal(linkedCalls(reader).length, 0)
  const count = calls(reader, detailPath(false, ctx.device_id)).length
  await close(reader)
  await precise(reader, await open(reader, false))
  assert.equal(calls(reader, detailPath(false, ctx.device_id)).length, count + 1, 'Reopen reused a stale list/detail instead of a new GET')
  await screenshot(reader, 'readonly-device-detail-p8a1.png')
  await close(reader)
  await explicitScope(reader, ctx.other_tenant_id, 403)
  await reader.getByRole('alert').first().waitFor()
  assert.equal(await reader.locator('[data-device-id], [data-device-detail-id], [data-device-alarm-metric]').count(), 0)
  assert.equal(await reader.getByLabel('密码', { exact: true }).count(), 0)
  await reader.getByRole('button', { name: '退出登录', exact: true }).waitFor()
  await explicitScope(reader, ctx.tenant_id)
  await precise(reader, await open(reader, false))
  await stored(reader)
  checks.push('minimum device-only platform logs in through actual controls, explicitly sets an unverified unit ID, reads precise current facts with no alarm count/directory, reopens with a new GET and rejects an ungranted unit403 without leaking old data or losing its valid session')

  const deviceCustomer = await create(true, false)
  await login(deviceCustomer, ctx.device_customer, true)
  await precise(deviceCustomer, await open(deviceCustomer, true))
  await close(deviceCustomer)
  const empty = await open(deviceCustomer, true, ctx.empty_device_id)
  assert.equal(empty.data.device.physical_state, 'UNKNOWN')
  assert.equal(empty.data.device.connectivity, 'UNACTIVATED')
  assert.equal(empty.data.device.battery_percent, null)
  assert.equal(empty.data.device.last_message_at, null)
  assert.equal(empty.data.device.is_simulated, false)
  await sheet(deviceCustomer).getByText('电量 未上报', { exact: true }).waitFor()
  await sheet(deviceCustomer).getByText('未激活', { exact: true }).waitFor()
  assert.equal(await sheet(deviceCustomer).getByText('电量 0%', { exact: true }).count(), 0)
  assert.equal(await sheet(deviceCustomer).getByText('正常', { exact: true }).count(), 0)
  assert.equal(linkedCalls(deviceCustomer).length, 0)
  await stored(deviceCustomer)
  checks.push('device-only customer has a fixed session unit and no scope chooser; a formal unreported device remains UNKNOWN/UNACTIVATED/null, never normal or zero battery, and no alarm API is requested')

  const manager = await create(false, true)
  await login(manager, ctx.both_reader)
  await combinedFlow(manager, false)
  await malformedInjection(manager)
  await refreshCurrentThenRead(manager, false)
  await showActualLinks(manager)
  await screenshot(manager, 'management-device-detail-p8a1.png')
  await lateScope(manager)
  await stored(manager)
  checks.push('combined platform explicitly reads only its exact device alarms: real full-page total10, current name separated from six literal old snapshots, draft does not GET, applied keyword persists across five-plus-one pages, clear restores ten and a real held scoped A response cannot replace opened device B')

  const customer = await create(true, true)
  await login(customer, ctx.both_customer, true)
  await combinedFlow(customer, true)
  await refreshCurrentThenRead(customer, true)
  await customer.setViewportSize({ width: 390, height: 844 })
  await customer.evaluate(() => document.fonts.ready)
  try { await customer.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth, undefined, { timeout: 2000 }) }
  catch {
    const geometry = await customer.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth, body: document.body.scrollWidth, dialog: [...document.querySelectorAll('[role="dialog"],.device-detail-body,.device-related-alarms,.device-detail-grid')].map(element => ({ class: element.className, left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right, client: element.clientWidth, scroll: element.scrollWidth })) }))
    throw new Error('Settled device detail has document-level horizontal overflow: ' + JSON.stringify(geometry))
  }
  assert.equal(await customer.evaluate(() => innerWidth), 390)
  const bounds = await sheet(customer).evaluate(element => ({ left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right }))
  assert(bounds.left >= -0.5 && bounds.right <= 390.5, 'Fixed Sheet extends beyond the actual mobile viewport')
  await showActualLinks(customer)
  await screenshot(customer, 'mobile-device-detail-p8a1.png')
  await stored(customer)
  checks.push('combined customer repeats real exact-device full-page filtering/pagination without review/history/queue; actual settled390 document pixels fit while the internal alarm table may scroll')
  for (const { page } of pages) await stored(page)
  network()
  assert.deepEqual(pageErrors, [])
} catch (error) {
  failure = error
  let screenshotState = 'unavailable'
  const last = pages.filter(item => !item.page.isClosed()).at(-1)?.page
  if (last) {
    try {
      const sensitive = await last.locator('input[type="password"], textarea').evaluateAll(inputs => inputs.some(input => input.value !== ''))
      if (sensitive) screenshotState = 'omitted-sensitive-draft'
      else { await last.screenshot({ path: path.join(ctx.artifact_directory, 'device-detail-browser-failure.png'), fullPage: true }); screenshotState = 'captured-nonsensitive' }
    } catch { screenshotState = 'unavailable' }
  }
  const safe = { status: 'failed', message: redact(error.message), stack: redact(error.stack), screenshot: screenshotState, page_errors: pageErrors.map(redact), real_vendor_calls: 0, real_notification_calls: 0, hardware_downlinks: 0 }
  writeFileSync(path.join(ctx.artifact_directory, 'device-detail-browser-failure.json'), JSON.stringify(safe, null, 2))
  console.error(JSON.stringify(safe))
} finally {
  if (browser) {
    try { await browser.close() }
    catch (error) { failure ??= error; console.error(JSON.stringify({ status: 'failed', message: redact(error.message), phase: 'owned-browser-cleanup' })) }
  }
}
if (failure) process.exitCode = 1
else {
  const report = { status: 'passed', checks, controlled_malformed_response_injections: 1, real_vendor_calls: 0, real_notification_calls: 0, hardware_downlinks: 0 }
  writeFileSync(path.join(ctx.artifact_directory, 'device-detail-browser-result.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
}
