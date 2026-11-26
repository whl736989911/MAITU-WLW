// P8a2 finite owned real-Chrome acceptance. Private stdin only; never persist context/token.
// Only the near-integer malformed-200 is an explicit test injection, NOT a server fact.
// Run against Root's newly built owned loopback package, not an older running application.
import assert from 'node:assert/strict'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadChromium, browserExecutable, browserEnvironment } from './browser-fixture.mjs'

const metricKeys = ['device_total', 'online', 'offline', 'unactivated', 'fire', 'normal', 'unknown', 'simulated', 'non_simulated']
const enums = { connectivity: ['ONLINE', 'OFFLINE', 'UNACTIVATED'], physical_state: ['FIRE', 'NORMAL', 'UNKNOWN'], integration_state: ['LEGACY_SIMULATION', 'PENDING_INTEGRATION'] }
const definitions = { scope: 'FILTERED_ALL_PAGES', connectivity: 'EFFECTIVE_OFFLINE_POLICY_AT_AS_OF', physical_state: 'CURRENT_DEVICE_PHYSICAL_STATE', simulation: 'DEVICE_IS_SIMULATED_MARKER_ONLY', binding: 'DEVICE_SAVED_METADATA_NOT_LATEST', consumer: 'QUERY_CONNECTIVITY_ONLY' }
const emptyFilters = { keyword: '', connectivity: null, physical_state: null, integration_state: null, is_simulated: null }
const labels = { keyword: '设备关键字', connectivity: '连接状态筛选', physical_state: '物理状态筛选', integration_state: '接入状态筛选', is_simulated: '模拟标记筛选' }
const checks = [], owners = [], requests = [], blocked = [], pageErrors = [], routeErrors = [], failedRequests = []
let ctx, origin, browser, failure, phase = 'private-stdin-validation', injections = 0
let secrets = []
const sanitize = value => secrets.reduce((text, secret) => secret ? text.split(secret).join('[REDACTED]') : text, String(value))
  .replace(/\bBearer\s+[^\s"'<>]+/gi, 'Bearer [REDACTED]')
  .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[TOKEN]')
  .replace(/((?:authorization|set-cookie|cookie)\s*[:=]\s*)[^\r\n]+/gi, '$1[REDACTED]')
  .replace(/https?:\/\/[^\s"'<>]+/gi, '[URL]')
const wbPath = customer => `/api/${customer ? 'customer' : 'management'}/device-workbench`
const listPath = customer => `/api/${customer ? 'customer' : 'management'}/devices`
const detailPath = (customer, id = ctx.device_id) => listPath(customer) + '/' + id
const wb = page => page.locator('section.device-workbench')
const rows = page => wb(page).locator('tr[data-device-id]')
const sheet = page => page.getByRole('dialog', { name: '设备只读详情', exact: true })
const owner = page => owners.find(item => item.page === page)
const scope = page => owner(page).customer ? undefined : owner(page).tenant
const field = (page, key) => wb(page).getByLabel(labels[key], { exact: true })
const count = (page, pathname) => requests.filter(item => item.page === page && item.method === 'GET' && item.pathname === pathname).length
const wbCount = page => count(page, wbPath(owner(page).customer))
const listCount = page => count(page, listPath(owner(page).customer))
const frames = page => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
function queryMatches(url, expected) {
  return JSON.stringify([...url.searchParams.entries()].sort(([a], [b]) => a.localeCompare(b))) === JSON.stringify(Object.entries(expected).map(([key, value]) => [key, String(value)]).sort(([a], [b]) => a.localeCompare(b)))
}
function next(page, pathname, params, tenant = scope(page), method = 'GET') {
  const pending = page.waitForResponse(response => {
    const url = new URL(response.url()), request = response.request()
    return url.origin === origin && url.pathname === pathname && request.method() === method && queryMatches(url, params) && request.headers()['x-tenant-id'] === tenant
  })
  // A failed preceding click must not leave an unhandled timeout printing raw URLs.
  pending.catch(() => {})
  return pending
}
async function body(pending, status = 200) {
  const response = await pending
  assert.equal(response.status(), status, 'Exact route/scope/query returned an unexpected status')
  const raw = await response.text()
  return { raw, data: JSON.parse(raw), response }
}
async function bounded(promise, label, milliseconds = 10000) {
  let timer
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(label)), milliseconds) })]) }
  finally { clearTimeout(timer) }
}
function integerToken(raw, key, expected) {
  const matches = [...raw.matchAll(new RegExp(`"${key}"\\s*:\\s*(-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)(?=\\s*[,}])`, 'g'))]
  assert.equal(matches.length, 1, 'Expected exactly one observed numeric metadata token')
  assert.match(matches[0][1], /^(?:0|[1-9]\d*)$/, 'Observed metadata must be a plain nonnegative integer literal')
  assert.equal(Number(matches[0][1]), expected)
  assert(Number.isSafeInteger(expected))
}
function canonicalMicros(value) {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})$/.exec(value)
  assert(match, 'Expected an explicit-offset server timestamp, never a browser-derived time')
  return new Date(match[1] + match[3]).toISOString().slice(0, 19) + '.' + (match[2] ?? '').padEnd(6, '0') + 'Z'
}
function safeFacts(value) {
  if (Array.isArray(value)) { value.forEach(safeFacts); return }
  if (!value || typeof value !== 'object') return
  for (const [key, item] of Object.entries(value)) {
    assert(!['access_token', 'password', 'actor_password', 'actor_id', 'actor_name', 'raw_payload', 'payload', 'schema', 'script', 'phone', 'phone_masked', 'ciphertext', 'lease_token', 'rendered_message'].includes(key), 'Read facts exposed an unrelated sensitive field')
    safeFacts(item)
  }
}
// Fail closed BEFORE dispatch. Queries are duplicate-free and the complete key/value set
// is constrained; no summary/tenant/product/alarm/history/notification/write endpoints.
function permitted(request, state) {
  const url = new URL(request.url()), method = request.method(), tenant = request.headers()['x-tenant-id']
  if (url.origin !== origin || url.username || url.password || url.hash) return false
  const entries = [...url.searchParams.entries()], keys = entries.map(([key]) => key)
  if (new Set(keys).size !== keys.length) return false
  if (!url.pathname.startsWith('/api/')) return method === 'GET' && tenant === undefined && entries.length === 0 && (url.pathname === '/' || url.pathname === '/favicon.ico' || /^\/assets\/[A-Za-z0-9._-]+$/.test(url.pathname))
  if (['/api/health', '/api/auth/me'].includes(url.pathname)) return method === 'GET' && entries.length === 0 && tenant === undefined
  if (url.pathname === '/api/auth/login') return method === 'POST' && entries.length === 0 && tenant === undefined
  if (method !== 'GET') return false
  const audience = state.customer ? 'customer' : 'management'
  if (!url.pathname.startsWith(`/api/${audience}/`)) return false
  if (state.customer ? tenant !== undefined : ![ctx.tenant_id, ctx.other_tenant_id].includes(tenant) || tenant !== state.tenant) return false
  const isList = url.pathname === listPath(state.customer), isWorkbench = url.pathname === wbPath(state.customer)
  if (isList && state.advanced) return false
  if (!isList && !isWorkbench) return entries.length === 0 && [ctx.device_id, ctx.other_device_id].some(id => url.pathname === detailPath(state.customer, id))
  const allowedKeys = isList ? ['page', 'size', 'keyword'] : ['page', 'size', 'keyword', 'connectivity', 'physical_state', 'integration_state', 'is_simulated']
  if (keys.some(key => !allowedKeys.includes(key)) || !keys.includes('page')) return false
  for (const [key, value] of entries) {
    if (['page', 'size'].includes(key) && (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value)))) return false
    if (key === 'keyword' && !['', ctx.device_keyword, ctx.literal_keyword].includes(value)) return false
    if (Object.hasOwn(enums, key) && !['', ...enums[key]].includes(value)) return false
    if (key === 'is_simulated' && !['true', 'false'].includes(value)) return false
  }
  return true
}
async function guard(route, state) {
  if (!permitted(route.request(), state)) { blocked.push({ kind: 'blocked-before-dispatch' }); await route.abort('blockedbyclient'); return false }
  return true
}
async function create(customer) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 }, timezoneId: 'UTC', locale: 'zh-CN', reducedMotion: 'reduce', serviceWorkers: 'block', acceptDownloads: false })
  const state = { context, customer, tenant: undefined, page: undefined, advanced: false }
  await context.route('**/*', async route => {
    try { if (await guard(route, state)) await route.continue() }
    catch { routeErrors.push('network-guard-failed'); await route.abort().catch(() => {}) }
  })
  const page = await context.newPage(); state.page = page; owners.push(state)
  page.setDefaultTimeout(10000); page.setDefaultNavigationTimeout(15000)
  page.on('request', request => {
    const url = new URL(request.url())
    // Do not retain URLs, Authorization, POST bodies, usernames or passwords.
    requests.push({ page, method: request.method(), pathname: url.pathname, params: [...url.searchParams.entries()], tenant: request.headers()['x-tenant-id'] })
  })
  page.on('requestfailed', request => failedRequests.push({ request, failure: request.failure()?.errorText ?? '' }))
  page.on('pageerror', error => pageErrors.push(sanitize(error.message)))
  page.on('dialog', async dialog => { pageErrors.push('unexpected-native-dialog'); await dialog.dismiss().catch(() => {}) })
  return page
}
async function explicitScope(page, tenant, { expectList = true, status = 200 } = {}) {
  await page.getByRole('button', { name: '指定单位ID', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '指定单位ID', exact: true })
  await dialog.getByText(/不代表已验证授权|不代表授权/).first().waitFor()
  await dialog.getByLabel('单位ID（需已授权）', { exact: true }).fill(tenant)
  const pending = expectList ? next(page, listPath(false), { page: 1, keyword: '' }, tenant) : null
  owner(page).tenant = tenant
  owner(page).advanced = false // Scope generation remounts the default basic page.
  await dialog.getByRole('button', { name: '应用单位ID', exact: true }).click()
  const result = pending ? await body(pending, status) : undefined
  await dialog.waitFor({ state: 'detached' })
  await page.getByLabel('当前单位ID（未核验）', { exact: true }).filter({ hasText: tenant }).waitFor()
  return result
}
async function login(page, principal) {
  const { customer } = owner(page)
  await page.goto(new URL(customer ? '/customer/login' : '/management/login', ctx.url).href)
  await page.getByLabel('账户', { exact: true }).fill(principal.username)
  await page.getByLabel('密码', { exact: true }).fill(principal.password)
  const authenticated = next(page, '/api/auth/login', {}, undefined, 'POST')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  assert.equal((await authenticated).status(), 200)
  await page.getByRole('button', { name: '退出登录', exact: true }).waitFor()
  assert.equal(await page.getByRole('combobox', { name: '选择租户', exact: true }).count(), 0, 'Minimum reader must not load a tenant directory')
  if (customer) assert.equal(await page.getByRole('button', { name: '指定单位ID', exact: true }).count(), 0)
  else await explicitScope(page, ctx.tenant_id, { expectList: false })
  const pending = next(page, listPath(customer), { page: 1, keyword: '' })
  await page.getByRole('button', { name: '设备监测', exact: true }).click()
  const initial = await body(pending)
  assert.equal(initial.data.total, ctx.total)
  await page.getByRole('button', { name: '打开设备筛选工作台', exact: true }).waitFor()
  assert.equal(wbCount(page), 0, 'Default basic page fetched the new workbench without explicit entry')
  assert.equal(await wb(page).count(), 0)
  assert.equal(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), 'UTC')
}
async function noAdvancedWrites(page) {
  assert.equal(await wb(page).getByRole('button', { name: /正式设备开户|元数据|迁移版本|绑定历史|编辑|删除|复核|下发|领取|读取关联报警/ }).count(), 0)
  assert.equal(await wb(page).locator('input[type="date"], input[type="datetime-local"]').count(), 0)
  assert.equal(await page.getByLabel('搜索设备', { exact: true }).count(), 0, 'Hidden old basic filter survived advanced entry')
  assert.equal(await page.locator('[data-device-alarm-metric], [data-device-alarm-id]').count(), 0)
}
async function enter(page, total = ctx.total) {
  const beforeOld = listCount(page), beforeNew = wbCount(page), pending = next(page, wbPath(owner(page).customer), { page: 1 })
  owner(page).advanced = true
  await page.getByRole('button', { name: '打开设备筛选工作台', exact: true }).click()
  const result = await body(pending)
  await page.getByRole('heading', { name: '设备筛选工作台', exact: true }).waitFor()
  await metrics(page, result, total)
  assert.equal(wbCount(page), beforeNew + 1)
  assert.equal(listCount(page), beforeOld, 'Advanced entry silently reloaded old devices')
  assert.equal(await page.locator('table').count(), 1, 'Basic and advanced tables must not coexist')
  assert.equal(await sheet(page).count(), 0)
  for (const key of Object.keys(labels)) assert.equal(await field(page, key).inputValue(), '')
  assert.equal(await wb(page).getByLabel('每页设备工作台条数', { exact: true }).inputValue(), '')
  await noAdvancedWrites(page)
  return result
}
async function metrics(page, result, total) {
  const data = result.data
  assert.deepEqual(Object.keys(data).sort(), ['as_of', 'filters', 'items', 'metric_definitions', 'metrics', 'page', 'size', 'timezone', 'total'])
  assert.equal(data.total, total); assert.equal(data.metrics.device_total, total)
  for (const key of ['total', 'page', 'size']) integerToken(result.raw, key, data[key])
  assert.equal(data.timezone, 'Asia/Shanghai'); assert(data.as_of.endsWith('Z')); canonicalMicros(data.as_of)
  assert.deepEqual(data.metric_definitions, definitions)
  assert.deepEqual(Object.keys(data.filters).sort(), Object.keys(emptyFilters).sort())
  assert.deepEqual(Object.keys(data.metrics).sort(), [...metricKeys].sort())
  assert.equal(data.metrics.online + data.metrics.offline + data.metrics.unactivated, total)
  assert.equal(data.metrics.fire + data.metrics.normal + data.metrics.unknown, total)
  assert.equal(data.metrics.simulated + data.metrics.non_simulated, total)
  assert.equal(data.items.length, Math.min(data.size, Math.max(0, total - (data.page - 1) * data.size)))
  assert.equal(new Set(data.items.map(item => item.id)).size, data.items.length)
  assert.deepEqual(data.items.map(item => item.id), data.items.map(item => item.id).sort(), 'Server rows must have stable ascending device IDs')
  await wb(page).filter({ has: page.locator(`[data-device-workbench-metric="device_total"]`) }).waitFor()
  await page.waitForFunction(asOf => document.querySelector('[data-device-workbench-as-of]')?.getAttribute('data-device-workbench-as-of') === asOf, data.as_of)
  for (const key of metricKeys) {
    integerToken(result.raw, key, data.metrics[key]); assert(data.metrics[key] >= 0 && data.metrics[key] <= total)
    await wb(page).locator(`[data-device-workbench-metric="${key}"]`).filter({ hasText: new RegExp(`^\\s*${data.metrics[key]}\\s*$`) }).waitFor()
  }
  assert.equal(await wb(page).locator('[data-device-workbench-metric]').count(), 9)
  const observed = Object.fromEntries(metricKeys.map(key => [key, 0]))
  for (const row of data.items) {
    assert(enums.connectivity.includes(row.connectivity)); assert(enums.physical_state.includes(row.physical_state)); assert(enums.integration_state.includes(row.integration_state))
    assert.equal(typeof row.is_simulated, 'boolean')
    assert.equal(canonicalMicros(row.offline_policy.evaluated_at), canonicalMicros(data.as_of))
    assert.equal(row.last_message_at === null, row.connectivity === 'UNACTIVATED')
    if (data.filters.keyword) assert([row.name, row.device_eui, row.address].some(value => typeof value === 'string' && value.toLowerCase().includes(data.filters.keyword.toLowerCase())))
    for (const key of ['connectivity', 'physical_state', 'integration_state', 'is_simulated']) if (data.filters[key] !== null) assert.equal(row[key], data.filters[key])
    const rendered = rows(page).filter({ has: page.locator(`button`) }).and(page.locator(`[data-device-id="${row.id}"]`))
    await rendered.waitFor(); assert((await rendered.textContent()).includes(row.name))
    observed.device_total++; observed[row.connectivity.toLowerCase()]++; observed[row.physical_state.toLowerCase()]++; observed[row.is_simulated ? 'simulated' : 'non_simulated']++
  }
  assert.equal(await rows(page).count(), data.items.length)
  for (const key of metricKeys) assert(data.items.length === total ? observed[key] === data.metrics[key] : observed[key] <= data.metrics[key])
  const summary = wb(page).locator('p[data-device-workbench-filters]')
  assert.equal(await summary.count(), 1)
  for (const value of Object.values(data.filters)) if (value !== null && value !== '') assert((await summary.textContent()).includes(String(value)), 'Applied summary did not display a real server filter')
  assert.equal(await page.evaluate(() => window.__DW_BAD), undefined, 'New WB literal text executed script')
  assert.equal(await page.evaluate(() => window.__WB_BAD), undefined, 'Foreign legacy literal text executed script')
  safeFacts(data)
}
async function apply(page, params) {
  const pending = next(page, wbPath(owner(page).customer), { page: 1, ...params })
  await wb(page).getByRole('button', { name: '应用设备筛选', exact: true }).click()
  return body(pending)
}
async function clear(page) {
  const pending = next(page, wbPath(owner(page).customer), { page: 1 })
  await wb(page).getByRole('button', { name: '清空设备条件', exact: true }).click()
  const result = await body(pending); await metrics(page, result, ctx.total)
  assert.deepEqual(result.data.filters, emptyFilters)
  for (const key of Object.keys(labels)) assert.equal(await field(page, key).inputValue(), '')
  return result
}
async function draftFalseLiteral(page) {
  phase = 'five-drafts-false-literal-pages'
  const initialCalls = wbCount(page)
  const selected = { connectivity: 'ONLINE', physical_state: 'FIRE', integration_state: 'PENDING_INTEGRATION', is_simulated: 'false' }
  await field(page, 'keyword').fill(ctx.literal_keyword)
  for (const [key, value] of Object.entries(selected)) {
    const values = await field(page, key).locator('option').evaluateAll(options => options.map(option => option.value))
    assert(values.includes(value), 'Required literal DOM option value is absent')
    await field(page, key).selectOption(value)
  }
  await frames(page); assert.equal(wbCount(page), initialCalls, 'Changing any of five drafts issued an implicit GET')
  await field(page, 'keyword').fill('')
  for (const key of Object.keys(enums)) await field(page, key).selectOption('')
  const nonSimulated = await apply(page, { is_simulated: 'false' })
  assert.equal(nonSimulated.data.filters.is_simulated, false)
  assert(nonSimulated.data.total > 0, 'False marker fixture must be non-vacuous')
  assert(nonSimulated.data.items.every(row => row.is_simulated === false))
  assert.equal(nonSimulated.data.metrics.simulated, 0); assert.equal(nonSimulated.data.metrics.non_simulated, nonSimulated.data.total)
  await metrics(page, nonSimulated, nonSimulated.data.total)
  for (const row of nonSimulated.data.items) await rows(page).and(page.locator(`[data-device-id="${row.id}"]`)).getByText(/^非模拟(?:标记)?$/, { exact: true }).waitFor()
  await clear(page)
  await field(page, 'keyword').fill(ctx.literal_keyword)
  const first = await apply(page, { keyword: ctx.literal_keyword })
  assert.equal(first.data.size, 5, 'Fixture must configure five rows; do not hardcode application defaults')
  assert.equal(first.data.items.length, 5); await metrics(page, first, ctx.literal_count)
  const prior = wbCount(page)
  await field(page, 'keyword').fill('未应用草稿，不应偷入分页')
  await field(page, 'is_simulated').selectOption('false')
  await frames(page); assert.equal(wbCount(page), prior)
  const pending = next(page, wbPath(owner(page).customer), { page: 2, keyword: ctx.literal_keyword })
  await wb(page).getByRole('button', { name: '下一页', exact: true }).click()
  const second = await body(pending)
  assert.equal(second.data.page, 2); assert.equal(second.data.items.length, 1); assert.equal(second.data.filters.keyword, ctx.literal_keyword)
  assert.deepEqual(second.data.metrics, first.data.metrics)
  assert.deepEqual([...first.data.items, ...second.data.items].map(item => item.id).sort(), [...ctx.literal_ids].sort())
  await metrics(page, second, ctx.literal_count)
  assert.equal(await field(page, 'keyword').inputValue(), '未应用草稿，不应偷入分页')
  assert.equal(await field(page, 'is_simulated').inputValue(), 'false')
  const refresh = next(page, wbPath(owner(page).customer), { page: 2, keyword: ctx.literal_keyword })
  await wb(page).getByRole('button', { name: '刷新设备工作台', exact: true }).click()
  await metrics(page, await body(refresh), ctx.literal_count)
  assert.equal(await field(page, 'keyword').inputValue(), '未应用草稿，不应偷入分页')
  await clear(page)
}
async function openPrecise(page) {
  await field(page, 'keyword').fill(ctx.device_keyword)
  const filtered = await apply(page, { keyword: ctx.device_keyword })
  assert.equal(filtered.data.total, 1); assert.equal(filtered.data.items[0].id, ctx.device_id)
  await metrics(page, filtered, 1)
  const before = count(page, detailPath(owner(page).customer)), pending = next(page, detailPath(owner(page).customer), {})
  await rows(page).and(page.locator(`[data-device-id="${ctx.device_id}"]`)).getByRole('button', { name: '查看设备详情', exact: true }).click()
  const detail = await body(pending)
  assert.equal(count(page, detailPath(owner(page).customer)), before + 1, 'Detail was not a new independent GET')
  assert.equal(detail.data.device.id, ctx.device_id)
  assert(new RegExp(`"battery_percent"\\s*:\\s*${ctx.precision.replaceAll('.', '\\.')}0*(?=\\s*[,}])`).test(detail.raw), 'Actual detail response lost the exact battery token')
  await sheet(page).getByText(`电量 ${ctx.precision}%`, { exact: true }).waitFor()
  assert.equal(await sheet(page).getByText(`电量 ${Number(ctx.precision)}%`, { exact: true }).count(), 0)
  assert.equal(await sheet(page).getByRole('button', { name: '读取关联报警', exact: true }).count(), 0)
  assert.equal(await sheet(page).locator('[data-device-detail-id]').getAttribute('data-device-detail-id'), ctx.device_id)
  safeFacts(detail.data)
  return filtered
}
async function closeDetail(page) { await sheet(page).waitFor(); await page.keyboard.press('Escape'); await sheet(page).waitFor({ state: 'detached' }) }
async function back(page) {
  // Radix Sheet is modal: close through its real Escape control before clicking the
  // background mode control. Never force-click through an overlay or mutate UI state.
  if (await sheet(page).count()) await closeDetail(page)
  const beforeNew = wbCount(page), pending = next(page, listPath(owner(page).customer), { page: 1, keyword: '' })
  owner(page).advanced = false
  await wb(page).getByRole('button', { name: '返回基础设备列表', exact: true }).click()
  const data = await body(pending)
  assert.equal(data.data.page, 1); assert.equal(data.data.total, owner(page).tenant === ctx.other_tenant_id ? ctx.other_total : ctx.total)
  await page.getByRole('button', { name: '打开设备筛选工作台', exact: true }).waitFor()
  assert.equal(await page.getByLabel('搜索设备', { exact: true }).inputValue(), '')
  assert.equal(await page.getByLabel('每页设备条数', { exact: true }).inputValue(), '')
  assert.equal(await wb(page).count(), 0); assert.equal(await sheet(page).count(), 0)
  assert.equal(await page.locator('[data-device-workbench-metric]').count(), 0)
  assert.equal(wbCount(page), beforeNew)
}
async function malformed(page) {
  phase = 'controlled-near-integer-malformed-200'
  const state = owner(page), pathname = wbPath(state.customer)
  let rejectRoute
  const routeFailed = new Promise((_, reject) => { rejectRoute = reject }); routeFailed.catch(() => {})
  const match = url => url.origin === origin && url.pathname === pathname
  const handler = async route => {
    try {
      if (!(await guard(route, state))) return
      assert(queryMatches(new URL(route.request().url()), { page: 1 }))
      const response = await route.fetch({ maxRedirects: 0 }); assert.equal(response.status(), 200)
      const original = await response.text(), data = JSON.parse(original)
      assert.equal(data.total, ctx.total); integerToken(original, 'total', ctx.total)
      const expression = new RegExp(`("total"\\s*:\\s*)${ctx.total}(?=\\s*[,}])`, 'g')
      assert.equal([...original.matchAll(expression)].length, 1)
      const near = `${ctx.total - 1}.999999999999999999999999`
      assert.equal(Number(near), ctx.total, 'Injection must expose the actual JS rounding blind spot')
      const altered = original.replace(expression, (_, prefix) => prefix + near)
      assert.notEqual(altered, original); assert.equal(JSON.parse(altered).total, ctx.total)
      injections++
      await route.fulfill({ response, body: altered })
    } catch (error) { rejectRoute(error); await route.abort().catch(() => {}) }
  }
  await page.route(match, handler)
  try {
    const pending = next(page, pathname, { page: 1 })
    await wb(page).getByRole('button', { name: '刷新设备工作台', exact: true }).click()
    assert.equal((await Promise.race([pending, routeFailed])).status(), 200)
    await wb(page).getByText(/DEVICE_WORKBENCH_UNCONFIRMED/).waitFor()
    assert.equal(await rows(page).count(), 0); assert.equal(await wb(page).locator('[data-device-workbench-metric]').count(), 0)
    assert.equal(await wb(page).getAttribute('data-device-workbench-as-of'), null, 'Malformed response left an old as_of')
    assert.equal(injections, 1)
  } finally { await page.unroute(match, handler) }
  const pending = next(page, pathname, { page: 1 })
  await wb(page).getByRole('button', { name: '刷新设备工作台', exact: true }).click()
  await metrics(page, await body(pending), ctx.total)
}
async function heldScope(page) {
  phase = 'real-fetched-held-scope-response'
  const state = owner(page), pathname = wbPath(false)
  let release, observed, rejectObserved, finished
  const delivery = new Promise(resolve => { release = resolve })
  const captured = new Promise((resolve, reject) => { observed = resolve; rejectObserved = reject }); captured.catch(() => {})
  const completed = new Promise(resolve => { finished = resolve })
  let started = false
  const problems = [], match = url => url.origin === origin && url.pathname === pathname
  const handler = async route => {
    if (route.request().headers()['x-tenant-id'] !== ctx.tenant_id) { if (await guard(route, state)) await route.continue(); return }
    started = true
    try {
      if (!(await guard(route, state))) throw new Error('Held request failed strict allowlist')
      const response = await route.fetch({ maxRedirects: 0 }); assert.equal(response.status(), 200)
      const actual = await response.json(); assert.equal(actual.total, ctx.total); assert.deepEqual(actual.filters, emptyFilters)
      observed(actual)
      await delivery
      try { await route.fulfill({ response }) }
      catch (error) {
        const aborted = failedRequests.some(item => item.request === route.request() && /ERR_ABORTED|abort|cancel/i.test(item.failure))
        if (!aborted && !/net::ERR_ABORTED|request (?:was |has been )?(?:aborted|cancell?ed)|Invalid InterceptionId|interception.*(?:invalid|not found)/i.test(error.message)) problems.push('unexpected-held-delivery-error')
      }
    } catch (error) { problems.push('held-response-fetch-or-validation-error'); rejectObserved(error) }
    finally { finished() }
  }
  await page.route(match, handler)
  try {
    await wb(page).getByRole('button', { name: '刷新设备工作台', exact: true }).click()
    const actualA = await bounded(captured, 'Real scoped response A was not captured')
    assert(actualA.items.length > 0)
    await back(page)
    await explicitScope(page, ctx.other_tenant_id)
    const other = await enter(page, ctx.other_total)
    assert.equal(other.data.items.length, 1); assert.equal(other.data.items[0].id, ctx.other_device_id)
    release(); await bounded(completed, 'Held A response did not settle')
    await page.unroute(match, handler); await frames(page)
    assert.deepEqual(problems, [])
    await metrics(page, other, ctx.other_total)
    assert.equal(await rows(page).and(page.locator(`[data-device-id="${ctx.device_id}"]`)).count(), 0)
    assert.equal(await field(page, 'keyword').inputValue(), '')
    assert.equal(await sheet(page).count(), 0)
  } finally {
    release()
    if (started) await bounded(completed, 'Held-route cleanup did not settle').catch(() => { problems.push('held-route-cleanup-failed') })
    await page.unroute(match, handler)
    assert.deepEqual(problems, [], 'Unrelated held-route errors cannot be swallowed')
  }
}
async function forbiddenScope(page) {
  phase = 'real-ungranted-scope-403'
  await explicitScope(page, ctx.other_tenant_id, { status: 403 })
  await page.getByRole('alert').first().waitFor()
  assert.equal(await page.locator('[data-device-id], [data-device-detail-id], [data-device-workbench-metric]').count(), 0)
  const pending = next(page, wbPath(false), { page: 1 })
  owner(page).advanced = true
  await page.getByRole('button', { name: '打开设备筛选工作台', exact: true }).click()
  await body(pending, 403); await wb(page).getByRole('alert').first().waitFor()
  assert.equal(await wb(page).locator('[data-device-id], [data-device-workbench-metric]').count(), 0)
  assert.equal(await page.getByLabel('密码', { exact: true }).count(), 0)
  await page.getByRole('button', { name: '退出登录', exact: true }).waitFor()
  await explicitScope(page, ctx.tenant_id)
  await enter(page)
}
async function stored(page) {
  assert.deepEqual(await page.evaluate(async () => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage), caches: await caches.keys(), indexed: typeof indexedDB.databases === 'function' ? (await indexedDB.databases()).length : 0 })), { local: [], session: [], caches: [], indexed: 0 })
  assert.equal((await page.context().cookies()).length, 0, 'Owned context persisted cookies')
}
async function cleanCapture(page, name) {
  assert.equal(await sheet(page).count(), 0)
  assert.equal(await page.locator('input[type="password"], textarea').count(), 0)
  const sensitive = await page.evaluate(values => {
    const text = document.body.innerText + [...document.querySelectorAll('input,textarea')].map(input => input.value).join('\n')
    return values.some(value => value && text.includes(value))
  }, secrets)
  assert.equal(sensitive, false, 'Credential-bearing page must not become a screenshot')
  await page.evaluate(() => window.scrollTo(0, 0)); await page.evaluate(() => document.fonts.ready); await frames(page)
  if (name === 'device-workbench-management-p8a2.png' || name === 'device-workbench-mobile-p8a2.png') {
    // Native viewport capture of the real nine-card overview, not a stitched drawer/background.
    await wb(page).locator('[data-device-workbench-metric="device_total"]').evaluate(element => element.closest('.device-workbench-metrics').scrollIntoView({ block: 'center' }))
    await frames(page)
    assert.equal(await wb(page).locator('[data-device-workbench-metric]').evaluateAll(elements => elements.length === 9 && elements.every(element => { const box = element.getBoundingClientRect(); return box.top >= 0 && box.bottom <= innerHeight })), true, 'Nine real metric values must fit the selected viewport screenshot')
  }
  await page.screenshot({ path: path.join(ctx.artifact_directory, name), fullPage: false, animations: 'disabled' })
}
async function mobile(page) {
  phase = 'actual-390-fonts-and-sheet-geometry'
  await page.setViewportSize({ width: 390, height: 844 })
  await bounded(page.evaluate(() => document.fonts.ready), 'Mobile fonts did not become ready', 10000)
  // Existing <=390 invariant and <=2s settling bound; no app CSS/threshold/timeout edits.
  await page.waitForFunction(() => document.documentElement.scrollWidth <= 390 && innerWidth === 390, undefined, { timeout: 2000 })
  await frames(page)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= 390), true)
  await openPrecise(page)
  await page.waitForFunction(() => {
    const element = document.querySelector('[role="dialog"]'), rect = element?.getBoundingClientRect()
    return document.documentElement.scrollWidth <= 390 && !!rect && rect.left >= 0 && rect.right <= 390 && rect.top >= 0 && rect.bottom <= innerHeight
  }, undefined, { timeout: 2000 })
  await closeDetail(page); await clear(page)
  await page.evaluate(() => window.scrollTo(0, 0)); await frames(page)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= 390), true)
  await cleanCapture(page, 'device-workbench-mobile-p8a2.png')
}
function network() {
  assert.equal(blocked.length, 0, 'Browser attempted an external/unlisted/query/header/write route; blocked before dispatch')
  assert.deepEqual(routeErrors, []); assert.deepEqual(pageErrors, [])
  for (const item of owners) {
    const own = requests.filter(row => row.page === item.page)
    assert(own.some(row => row.pathname === '/api/auth/login' && row.method === 'POST'))
    assert(!own.some(row => row.pathname.startsWith('/api/') && /alarm|notification|history|products|parameters|tenants|summary|messages|tasks/.test(row.pathname)))
    assert(!own.some(row => row.method !== 'GET' && !(row.method === 'POST' && row.pathname === '/api/auth/login')))
    for (const row of own) if (row.pathname.startsWith('/api/customer/')) assert.equal(row.tenant, undefined)
    if (item.advancedStart !== undefined) assert.equal(listCount(item.page), item.advancedStart, 'Advanced-only actions issued an implicit old devices GET')
  }
}

try {
  ctx = JSON.parse(readFileSync(0, 'utf8'))
  secrets = ['device_reader', 'scope_reader', 'device_customer'].flatMap(key => [ctx[key]?.username, ctx[key]?.password]).filter(value => typeof value === 'string' && value.length > 0)
  assert.match(ctx.url, /^http:\/\/127\.0\.0\.1:\d+\/$/)
  origin = new URL(ctx.url).origin
  assert(path.isAbsolute(ctx.artifact_directory) && existsSync(ctx.artifact_directory))
  for (const key of ['tenant_id', 'other_tenant_id', 'device_id', 'other_device_id']) assert.match(ctx[key], /^[A-Za-z0-9-]+$/)
  assert.notEqual(ctx.tenant_id, ctx.other_tenant_id); assert.notEqual(ctx.device_id, ctx.other_device_id)
  assert.equal(ctx.device_keyword, 'P5-MAIN'); assert.equal(ctx.literal_keyword, '%_!'); assert.equal(ctx.literal_count, 6)
  assert.equal(ctx.precision, '0.12345678901234567'); assert.notEqual(String(Number(ctx.precision)), ctx.precision)
  assert(Number.isSafeInteger(ctx.total) && ctx.total >= ctx.literal_count && ctx.total < Number.MAX_SAFE_INTEGER)
  assert.equal(ctx.other_total, 1); assert(Array.isArray(ctx.literal_ids)); assert.equal(ctx.literal_ids.length, 6); assert.equal(new Set(ctx.literal_ids).size, 6)
  for (const id of ctx.literal_ids) assert.match(id, /^[A-Za-z0-9-]+$/)
  for (const key of ['device_reader', 'scope_reader', 'device_customer']) {
    assert.deepEqual(Object.keys(ctx[key]).sort(), ['password', 'username'])
    assert(typeof ctx[key].username === 'string' && ctx[key].username.length > 0 && typeof ctx[key].password === 'string' && ctx[key].password.length > 0)
  }
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const { chromium } = await loadChromium(root)
  const executablePath = browserExecutable()
  assert(executablePath, 'An installed Ubuntu Chrome/Chromium executable is required (no bundled-browser fallback)')
  phase = 'owned-chrome-launch'
  browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true, env: browserEnvironment() })

  phase = 'device-reader-actual-login-basic-advanced'
  const reader = await create(false)
  await login(reader, ctx.device_reader); await enter(reader)
  const old = listCount(reader)
  await draftFalseLiteral(reader); await openPrecise(reader)
  assert.equal(listCount(reader), old)
  await closeDetail(reader); await back(reader)
  await enter(reader)
  await openPrecise(reader); await back(reader) // Actual modal close, then actual basic-mode control.
  await enter(reader); await malformed(reader)
  await forbiddenScope(reader)
  owner(reader).advancedStart = listCount(reader)
  await cleanCapture(reader, 'device-workbench-management-p8a2.png')
  await stored(reader)
  checks.push('actual minimal platform login and unverified explicit A; default only legacy devices; explicit advanced owns one table and only new GET; nine real metrics/three axes/as_of; five drafts do not GET; strict false/non-simulated; six literal IDs paginate 5+1 using applied filters; refresh preserves draft and clear restores supplied total')
  checks.push('known main row opens independent P8a1 detail with original 17-digit battery text, no alarm request; close/back reset basic page1/default-size/empty draft and destroy advanced/detail; reentry issues new GET; real ungranted B returns403 without stale data or session loss and A recovers')
  checks.push('one explicitly controlled near-integer malformed200 injection changes only the actual fetched total literal; JS rounds it back to N but UI rejects original fractional token and clears all nine cards/rows/as_of; separate unmodified real refresh recovers')

  phase = 'scope-reader-actual-login'
  const scoped = await create(false)
  await login(scoped, ctx.scope_reader); await enter(scoped); await heldScope(scoped); await stored(scoped)
  owner(scoped).advancedStart = listCount(scoped)
  checks.push('separate actual A+B device-only principal: route.fetch obtains real scoped A bytes then holds; exit advanced, explicitly switch to B/basic, open fresh B advanced and release old A; B one-device rows/metrics/as_of and empty filters survive; no fabricated context or stale scope restoration')

  phase = 'customer-actual-login'
  const customer = await create(true)
  await login(customer, ctx.device_customer); await enter(customer)
  owner(customer).advancedStart = listCount(customer)
  await draftFalseLiteral(customer); await mobile(customer); await stored(customer)
  checks.push('independent actual fixed-A customer has no scope chooser/tenant header; repeats strict false, literal5+1 and applied-vs-draft; actual UTC390 fonts-ready bounded<=2s geometry keeps document and opened Sheet inside viewport; internal device table may scroll; clean native viewport PNGs are unmodified1440x1080 and390x844')
  for (const { page } of owners) await stored(page)
  network(); assert.equal(owners.length, 3); assert.equal(injections, 1)
} catch (error) {
  failure = error
  let screenshot = 'omitted-not-proven-safe'
  const page = owners.filter(item => !item.page.isClosed()).at(-1)?.page
  if (page && ctx?.artifact_directory) {
    try { await cleanCapture(page, 'device-workbench-browser-failure.png'); screenshot = 'captured-safe-viewport' }
    catch { /* Never photograph login credentials, unsafe drafts or an unconfirmed sheet. */ }
  }
  const report = { status: 'failed', phase, message: sanitize(error?.message ?? 'Unclassified failure'), screenshot, controlled_malformed_response_injections: injections, blocked_before_dispatch: blocked.length, real_vendor_calls: 0, real_notification_calls: 0, hardware_downlinks: 0 }
  if (ctx?.artifact_directory && path.isAbsolute(ctx.artifact_directory) && existsSync(ctx.artifact_directory)) writeFileSync(path.join(ctx.artifact_directory, 'device-workbench-browser-failure.json'), JSON.stringify(report, null, 2))
  console.error(JSON.stringify(report))
} finally {
  if (browser) {
    try { await browser.close() }
    catch (error) { failure ??= error; console.error(JSON.stringify({ status: 'failed', phase: 'owned-browser-cleanup', message: sanitize(error.message) })) }
  }
}
if (failure) process.exitCode = 1
else {
  const report = { status: 'passed', checks, controlled_malformed_response_injections: injections, real_vendor_calls: 0, real_notification_calls: 0, hardware_downlinks: 0 }
  writeFileSync(path.join(ctx.artifact_directory, 'device-workbench-browser-result.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
}
