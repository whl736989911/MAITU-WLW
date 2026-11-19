// P8c1 / Root task52. ONLY Root executes against its unique newly frozen package.
// Private bounded stdin; no environment/argv payload, traces, HAR, videos or fixture writes.
// Held route.fetch bytes are CONTROLLED DELIVERY, not a naturally occurring server fault.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, readSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadChromium, browserExecutable, browserEnvironment } from './browser-fixture.mjs'

const states = ['PENDING', 'WAIT_RETRY', 'SUCCEEDED_SIMULATION', 'FAILED', 'UNCERTAIN', 'CANCELLED', 'SUPPRESSED', 'REJECTED']
const metricKeys = ['notification_total', ...states.map(x => x.toLowerCase())]
const rowKeys = ['id', 'dispatch_id', 'alarm_id', 'device_id', 'device_name', 'address', 'channel', 'mode', 'level', 'status', 'attempt_count', 'available_at', 'created_at', 'finished_at', 'last_error_code', 'receiver_label', 'external_delivery']
const filters = ['keyword', 'status', 'channel', 'dispatch_id', 'created_from', 'created_to']
// Read from FE50's currently authored component; no future filename/private-store guesses.
const UI = Object.freeze({ title: '通知记录只读查询工作台', open: '打开通知记录只读查询工作台', back: '返回原通知列表', apply: '应用通知筛选', reset: '清空通知条件', refresh: '刷新通知工作台', next: '下一页', detail: '查看只读通知详情', detail_title: '通知记录只读详情', fields: { keyword: '通知记录关键字', status: '模拟通知状态', channel: '模拟通知渠道', dispatch_id: '通知调度批次ID', created_from: '创建开始（北京时间，含）', created_to: '创建结束（北京时间，不含）' }, metrics: { notification_total: '匹配通知记录', pending: '待模拟执行', wait_retry: '等待规则重试', succeeded_simulation: '模拟成功（未发送）', failed: '模拟明确失败（未发送）', uncertain: '结果不确定（UNCERTAIN）', cancelled: '已取消待发模拟', suppressed: '待发已抑制', rejected: '执行资格已拒绝' } })
const definitions = { scope: 'FILTERED_ALL_PAGES', time_field: 'RECORD_CREATED_AT', time_bounds: 'FROM_INCLUSIVE_TO_EXCLUSIVE', mode: 'SIMULATION', external_delivery: 'NOT_APPLICABLE_SIMULATION' }
const errorCodes = ['ACTOR_UNAVAILABLE', 'ACTOR_CHANGED', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_CHANGED', 'TENANT_CHANGED', 'UNIT_DISABLED', 'POLICY_CHANGED', 'RULE_CHANGED', 'TEMPLATE_CHANGED', 'CONTACT_CHANGED', 'SIMULATION_TARGET_REQUIRED', 'NOT_FOUND', 'ALARM_HANDLED', 'SIMULATED_RESPONSE_UNKNOWN', 'SIMULATED_RETRYABLE_REJECTION', 'SIMULATED_ATTEMPTS_EXHAUSTED', 'SIMULATED_REJECTED', 'CANCELLED', 'SIMULATION_RECONCILED']
const actorKeys = ['record_reader_platform', 'other_reader_platform', 'record_reader_customer', 'other_reader_customer']
const checks = [], requests = [], owners = [], screenshots = [], problems = [], knownSecrets = new Set(), controls = []
let input, origin, browser, phase = 'private-stdin', failed = false, browserCleanup = 'not-started', reportPath, progressPath, progressTimer, failureLocation
function progress(cleanup = false) { if (progressPath) writeFileSync(progressPath, JSON.stringify({ phase, cleanup, checks: checks.length, actors: owners.map(s => ({ name: s.name, closed: s.closed })), requests: requests.length, last_request: requests.length ? { context: requests.at(-1).context, kind: requests.at(-1).kind, status: requests.at(-1).status } : null }), { flag: 'w' }) }
const requireThat = (condition, code) => { if (!condition) throw new Error(code) }
const keys = (value, expected) => { requireThat(value && typeof value === 'object' && !Array.isArray(value), 'OBJECT_REQUIRED'); assert.deepEqual(Object.keys(value).sort(), [...expected].sort()) }
const int = (n, min = 0, max = Number.MAX_SAFE_INTEGER) => requireThat(Number.isSafeInteger(n) && n >= min && n <= max, 'INTEGER_INVALID')
const text = value => requireThat(typeof value === 'string' && value.length > 0, 'TEXT_REQUIRED')
const eq = (a, b) => assert.deepEqual(a, b)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function bounded(promise, code, ms = 10000) { let timer; try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(code)), ms) })]) } finally { clearTimeout(timer) } }
const frames = s => bounded(s.page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))), 'FRAME_SETTLE_TIMEOUT')
const wbPath = s => `/api/${s.principal.audience}/notification-workbench`
const oldPath = s => `/api/${s.principal.audience}/notifications`
const detailPath = (s, id) => `${wbPath(s)}/${encodeURIComponent(id)}`
const tenant = s => s.principal.audience === 'management' ? s.tenant : undefined
const button = (s, name) => s.page.getByRole('button', { name, exact: true })
// Semantic accessible roles/names only. UI names are supplied after FE owner freeze.
const panel = s => s.page.getByRole('region', { name: UI.title, exact: true, includeHidden: true }) // Read applied draft behind modal without clicking through it.
const field = (s, key) => panel(s).getByLabel(UI.fields[key], { exact: true })
const pbutton = (s, name) => panel(s).getByRole('button', { name, exact: true })
const dialog = s => s.page.getByRole('dialog', { name: UI.detail_title, exact: true })
const listCount = s => requests.filter(r => r.context === s.name && r.kind === 'workbench-list').length
const safePath = pathname => pathname.replace(/(\/notification-workbench|\/notifications)\/[^/]+(?=\/|$)/g, '$1/:id')
function privateInput() {
  const chunks = []; let count = 0
  for (;;) { const chunk = Buffer.alloc(8192), n = readSync(0, chunk, 0, chunk.length, null); if (!n) break; count += n; requireThat(count <= 131072, 'STDIN_TOO_LARGE'); chunks.push(chunk.subarray(0, n)) }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}
// Reject duplicate object keys and lossy numeric tokens. Only nonnegative integer numbers
// occur in this frozen wire shape. This is NOT imported application code.
function rawJson(raw) {
  requireThat(Buffer.byteLength(raw) <= 1048576, 'WIRE_TOO_LARGE'); let at = 0, nodes = 0
  const ws = () => { while (/[\x20\t\r\n]/.test(raw[at] ?? '') && at < raw.length) at++ }
  function str() { const start = at; eq(raw[at++], '"'); for (;;) { const c = raw[at++]; requireThat(c !== undefined, 'STRING_UNTERMINATED'); if (c === '"') return JSON.parse(raw.slice(start, at)); requireThat(c.charCodeAt(0) >= 32, 'STRING_CONTROL'); if (c === '\\') { const esc = raw[at++]; requireThat('"\\/bfnrtu'.includes(esc ?? '!'), 'STRING_ESCAPE'); if (esc === 'u') { requireThat(/^[a-fA-F0-9]{4}$/.test(raw.slice(at, at + 4)), 'STRING_UNICODE'); at += 4 } } } }
  function value(depth = 0) {
    requireThat(depth < 20 && ++nodes < 20000, 'WIRE_COMPLEXITY'); ws()
    if (raw[at] === '"') return str()
    if (raw[at] === '{') { at++; ws(); const out = Object.create(null); if (raw[at] === '}') { at++; return out } for (;;) { ws(); const k = str(); requireThat(!Object.hasOwn(out, k), 'DUPLICATE_KEY'); ws(); eq(raw[at++], ':'); out[k] = value(depth + 1); ws(); const c = raw[at++]; if (c === '}') return out; eq(c, ',') } }
    if (raw[at] === '[') { at++; ws(); const out = []; if (raw[at] === ']') { at++; return out } for (;;) { out.push(value(depth + 1)); ws(); const c = raw[at++]; if (c === ']') return out; eq(c, ',') } }
    for (const [literal, result] of [['null', null], ['true', true], ['false', false]]) if (raw.startsWith(literal, at)) { at += literal.length; return result }
    const token = /^(?:0|[1-9]\d*)(?=[\x20\t\r\n,}\]]|$)/.exec(raw.slice(at))?.[0]; requireThat(token !== undefined, 'NUMBER_TOKEN'); const n = Number(token); int(n); at += token.length; return n
  }
  const result = value(); ws(); eq(at, raw.length); return result
}
function micros(value) {
  text(value); const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/.exec(value)
  requireThat(m && !/[1-9]/.test((m[2] ?? '').slice(6)), 'EXPLICIT_OFFSET_MICROSECONDS_REQUIRED'); const milliseconds = Date.parse(m[1] + m[3]); requireThat(Number.isFinite(milliseconds), 'TIMESTAMP_INVALID')
  return BigInt(milliseconds) * 1000n + BigInt((m[2] ?? '').slice(0, 6).padEnd(6, '0'))
}
function utcQuery(value) {
  const us = micros(value), seconds = us >= 0n ? us / 1000000n : (us - 999999n) / 1000000n, fraction = us - seconds * 1000000n
  return new Date(Number(seconds * 1000n)).toISOString().slice(0, 19) + '.' + fraction.toString().padStart(6, '0') + '000Z'
}
function beijingDraft(value) {
  const canonical = utcQuery(value), whole = new Date(Date.parse(canonical.slice(0, 19) + 'Z') + 8 * 3600000).toISOString().slice(0, 19)
  return whole + '.' + canonical.slice(20, 26)
}
function wireQuery(query) { return Object.fromEntries(Object.entries(query).map(([k, v]) => [k, k.startsWith('created_') && v ? utcQuery(v) : v])) }
function record(r) {
  keys(r, rowKeys); for (const k of ['id', 'dispatch_id', 'alarm_id', 'device_id']) text(r[k])
  for (const k of ['device_name', 'address']) eq(typeof r[k], 'string')
  requireThat(['SMS', 'VOICE'].includes(r.channel) && states.includes(r.status), 'ENUM_INVALID'); eq(r.mode, 'SIMULATION'); eq(r.external_delivery, 'NOT_APPLICABLE_SIMULATION')
  int(r.level, 1, 5); int(r.attempt_count, 0, 20); eq(r.receiver_label, `第${r.level}级接收人`)
  for (const k of ['created_at', 'available_at']) micros(r[k]); if (r.finished_at !== null) micros(r.finished_at)
  requireThat(r.last_error_code === null || errorCodes.includes(r.last_error_code), 'ERROR_CODE_INVALID')
}
function workbench(raw, query, expected) {
  const d = rawJson(raw); keys(d, ['items', 'total', 'page', 'size', 'as_of', 'timezone', 'filters', 'metrics', 'metric_definitions']); keys(d.filters, filters); keys(d.metrics, metricKeys)
  int(d.total); int(d.page, 1, 1000000); int(d.size, 1, 100); eq(d.page, Number(query.page)); eq(d.timezone, 'Asia/Shanghai'); micros(d.as_of)
  eq({ ...d.metric_definitions }, definitions); eq(d.metrics.notification_total, d.total)
  let sum = 0; for (const k of metricKeys) { int(d.metrics[k]); requireThat(d.metrics[k] <= d.total, 'METRIC_LIMIT'); if (k !== 'notification_total') sum += d.metrics[k] } eq(sum, d.total)
  for (const k of filters) { const v = query[k] ?? (k === 'keyword' ? '' : null); if (k.startsWith('created_') && v !== null) eq(micros(d.filters[k]), micros(v)); else eq(d.filters[k], v) }
  requireThat(Array.isArray(d.items), 'ROWS_REQUIRED'); eq(d.items.length, Math.min(d.size, Math.max(0, d.total - (d.page - 1) * d.size)))
  const ids = new Set(), pageMetrics = Object.fromEntries(states.map(s => [s.toLowerCase(), 0])); let previous
  for (const r of d.items) {
    record(r); requireThat(!ids.has(r.id), 'DUPLICATE_ROW'); ids.add(r.id); pageMetrics[r.status.toLowerCase()]++
    if (previous) requireThat(micros(previous.created_at) > micros(r.created_at) || micros(previous.created_at) === micros(r.created_at) && previous.id < r.id, 'ROW_SORT')
    previous = r
    for (const k of ['status', 'channel', 'dispatch_id']) if (d.filters[k] !== null) eq(r[k], d.filters[k])
    if (d.filters.created_from !== null) requireThat(micros(r.created_at) >= micros(d.filters.created_from), 'FROM_INCLUSIVE')
    if (d.filters.created_to !== null) requireThat(micros(r.created_at) < micros(d.filters.created_to), 'TO_EXCLUSIVE')
    // Keyword IDs/metrics are fixture/backend expectations, never a JS SQL-casefold emulation.
  }
  for (const [k, n] of Object.entries(pageMetrics)) { requireThat(n <= d.metrics[k], 'PAGE_METRIC_LOWER_BOUND'); if (d.items.length === d.total) eq(n, d.metrics[k]) }
  if (d.filters.status !== null) for (const s of states) eq(d.metrics[s.toLowerCase()], s === d.filters.status ? d.total : 0)
  if (expected) { eq(d.total, expected.total); if (expected.metrics) eq({ ...d.metrics }, expected.metrics); if (expected.ids && d.items.length === d.total) eq([...ids].sort(), [...expected.ids].sort()) }
  return d
}
function querySame(u, q) { q = wireQuery(q); const entries = [...u.searchParams]; if (new Set(entries.map(x => x[0])).size !== entries.length) return false; const sort = a => a.sort(([a], [b]) => a.localeCompare(b)); return JSON.stringify(sort(entries)) === JSON.stringify(sort(Object.entries(q).map(([k, v]) => [k, String(v)]))) }
function permit(s, pathname, query = {}, method = 'GET', wantedTenant = tenant(s)) { if (!/^\/api\/(management|customer)\//.test(pathname)) wantedTenant = undefined; const p = { pathname, query, method, tenant: wantedTenant, used: false }; s.permits.push(p); return p }
function next(s, pathname, query = {}, method = 'GET', wantedTenant = tenant(s)) {
  if (!/^\/api\/(management|customer)\//.test(pathname)) wantedTenant = undefined
  permit(s, pathname, query, method, wantedTenant)
  const pending = s.page.waitForResponse(r => { const u = new URL(r.url()); return u.origin === origin && u.pathname === pathname && r.request().method() === method && querySame(u, query) && r.request().headers()['x-tenant-id'] === wantedTenant }); pending.catch(() => {}); return pending
}
async function response(pending, status = 200) { const r = await bounded(pending, 'EXPECTED_RESPONSE_TIMEOUT'); eq(r.status(), status); /* New GET deliberately need not consume 403 bodies; DevTools body retrieval can remain pending for an unused/aborted fetch. Parent native HTTP tests separately validate error envelopes. */ return { raw: status === 200 ? await bounded(r.text(), 'EXPECTED_SUCCESS_BODY_TIMEOUT') : '', response: r } }
function requestKind(s, pathname) { return pathname === wbPath(s) ? 'workbench-list' : pathname.startsWith(wbPath(s) + '/') ? 'workbench-detail' : pathname === oldPath(s) ? 'basic-list' : pathname.endsWith('notification-capabilities') ? 'basic-capability' : pathname.startsWith('/api/auth/') ? 'auth' : pathname === '/api/health' ? 'health' : 'asset' }
function policy(s, request) {
  const u = new URL(request.url()), method = request.method(), h = request.headers(), query = [...u.searchParams]
  requireThat(u.origin === origin && !u.username && !u.password && !u.hash, 'OUTSIDE_OWNED_ORIGIN')
  requireThat(!h.cookie, 'COOKIE_NOT_ALLOWED')
  if (u.pathname.startsWith('/api/')) {
    if (['/api/health', '/api/auth/login'].includes(u.pathname)) eq(h.authorization, undefined)
    else requireThat(typeof h.authorization === 'string' && h.authorization.startsWith('Bearer '), 'AUTHORIZATION_REQUIRED')
    if (method === 'GET') eq(request.postData(), null)
    if (u.pathname.startsWith('/api/customer/')) eq(h['x-tenant-id'], undefined)
  }
  const p = s.permits.find(p => !p.used && p.pathname === u.pathname && p.method === method && p.tenant === h['x-tenant-id'] && querySame(u, p.query))
  if (p) { p.used = true; return }
  if (!u.pathname.startsWith('/api/')) { requireThat(method === 'GET' && !query.length && !h['x-tenant-id'] && (u.pathname === '/' || u.pathname === '/favicon.ico' || /^\/assets\/[A-Za-z0-9._-]+$/.test(u.pathname)), 'ASSET_DENIED'); return }
  if (u.pathname === '/api/health') { requireThat(method === 'GET' && !query.length && !h['x-tenant-id'], 'HEALTH_DENIED'); return }
  // Existing basic hooks are deliberately allowed BEFORE explicit entry, never afterwards.
  if (s.mode === 'basic' && method === 'GET' && u.pathname === oldPath(s)) {
    requireThat(query.every(([k, v]) => ['page', 'size', 'keyword', 'status', 'channel', 'dispatch_id'].includes(k) && (k !== 'keyword' || v === '')), 'BASIC_QUERY_DENIED')
    eq(h['x-tenant-id'], tenant(s)); return
  }
  if (s.mode === 'basic' && method === 'GET' && s.principal.audience === 'management' && u.pathname === '/api/management/notification-capabilities' && !query.length && !h['x-tenant-id']) return
  throw new Error('PHASE_ALLOWLIST_DENIED')
}
async function create(name) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 }, timezoneId: 'UTC', locale: 'zh-CN', serviceWorkers: 'block', acceptDownloads: false, reducedMotion: 'reduce' })
  const s = { name, context, principal: input[name], actor: name, tenant: undefined, mode: 'login', permits: [], holds: [], closed: false }; owners.push(s)
  // No workbench WebSocket is authorized. Playwright routing stays disconnected from
  // any server unless connectToServer is called; never call it or send mock success.
  await context.routeWebSocket('**/*', async socket => { problems.push('BLOCKED_WEBSOCKET_BEFORE_SERVER_CONNECTION'); await socket.close({ code: 1008, reason: 'READ_ONLY_FIREWALL' }).catch(() => {}) })
  await context.route('**/*', async route => {
    let entry, held
    try {
      policy(s, route.request()); const u = new URL(route.request().url()), kind = requestKind(s, u.pathname)
      requireThat(requests.length < 1500, 'REQUEST_REPORT_BOUND')
      entry = { context: s.name, actor: s.actor, phase, mode: s.mode, method: route.request().method(), pathname: safePath(u.pathname), query_names: [...u.searchParams.keys()].sort(), kind, status: null, authorization_present: Object.hasOwn(route.request().headers(), 'authorization'), tenant_header_present: Object.hasOwn(route.request().headers(), 'x-tenant-id') }; requests.push(entry)
      // All fetches remain real and redirects are forbidden. Never retain auth header values.
      const fetched = await route.fetch({ maxRedirects: 0 }); entry.status = fetched.status()
      if (u.pathname === '/api/auth/login' && fetched.status() === 200) {
        const auth = JSON.parse(await fetched.text()); eq([...auth.permissions].sort(), ['notifications:read']); eq(auth.user.id, s.principal.user_id); eq(auth.user.user_type, s.principal.audience === 'management' ? 'PLATFORM' : 'CUSTOMER'); eq(auth.user.must_change_password, false)
        if (s.principal.audience === 'customer') eq(auth.user.tenant_id, s.principal.tenant_id)
        text(auth.access_token); knownSecrets.add(auth.access_token)
      }
      if (kind.startsWith('workbench-') && fetched.status() === 200) requireThat(/(?:^|,)\s*no-store(?:,|$)/i.test(fetched.headers()['cache-control'] ?? ''), 'NO_STORE_REQUIRED')
      if (s.hold && kind === s.hold.kind && !s.hold.started) {
        held = s.hold; held.started = true; const bytes = await fetched.body(); held.bytes = bytes; held.status = fetched.status(); held.raw = bytes.toString('utf8'); held.captured(); await held.gate
        // Same raw real bytes. Optional 401 is STATUS-ONLY synthetic delivery, never revokedAuth.
        if (held.synthetic401) { entry.delivered_status = 401; await route.fulfill({ response: fetched, status: 401, body: bytes }) }
        else await route.fulfill({ response: fetched, body: bytes })
        held.delivery_outcome = 'fulfill-completed-not-proof-of-app-body-consumption'
      } else await route.fulfill({ response: fetched })
    } catch (error) {
      const retired = held && held.released && /abort|cancel|Invalid InterceptionId|interception.*(?:invalid|not found)/i.test(String(error?.message))
      if (retired) held.delivery_outcome = 'retired-request-aborted-before-delivery'
      else problems.push(entry ? 'REAL_ROUTE_FETCH_OR_DELIVERY_FAILED' : 'BLOCKED_BEFORE_DISPATCH')
      await route.abort('blockedbyclient').catch(() => {})
    } finally { if (held) { held.bytes = null; held.raw = null; held.settled() } }
  })
  s.page = await context.newPage(); s.page.setDefaultTimeout(10000); s.page.setDefaultNavigationTimeout(15000)
  s.page.on('pageerror', () => problems.push('PAGE_ERROR'))
  s.page.on('dialog', async d => { problems.push('NATIVE_DIALOG'); await d.dismiss().catch(() => {}) })
  s.page.on('popup', async p => { problems.push('POPUP'); await p.close().catch(() => {}) })
  s.page.on('download', async d => { problems.push('DOWNLOAD'); await d.cancel().catch(() => {}) })
  s.page.on('websocket', () => problems.push('UNEXPECTED_WEBSOCKET'))
  return s
}
async function scope(s, id, status = 200, onBasic = true) {
  s.mode = 'basic'; await button(s, '指定单位ID').click(); const d = s.page.getByRole('dialog', { name: '指定单位ID', exact: true }); await d.getByLabel('单位ID（需已授权）', { exact: true }).fill(id)
  s.tenant = id; const pending = onBasic ? next(s, oldPath(s), { page: 1 }, 'GET', id) : null
  await d.getByRole('button', { name: '应用单位ID', exact: true }).click(); await d.waitFor({ state: 'detached' })
  // Basic query representation is intentionally not assumed: legacy helper omits empty keyword.
  if (pending) await response(pending, status)
}
async function login(s, name = s.actor, navigate = true) {
  s.actor = name; s.principal = input[name]; s.mode = 'login'; s.tenant = undefined
  if (navigate) await s.page.goto(input.url)
  await s.page.getByRole('heading', { name: '欢迎回来', exact: true }).waitFor()
  await button(s, s.principal.audience === 'customer' ? '客户端' : '管理端').click()
  await s.page.getByLabel('账户', { exact: true }).fill(s.principal.username); await s.page.getByLabel('密码', { exact: true }).fill(s.principal.password)
  const pending = next(s, '/api/auth/login', {}, 'POST', undefined)
  await button(s, s.principal.audience === 'customer' ? '登录客户端' : '登录管理端').click(); await response(pending)
  await button(s, '退出登录').waitFor(); eq(await s.page.getByRole('combobox', { name: '选择租户', exact: true }).count(), 0)
  if (s.principal.audience === 'management') await scope(s, s.principal.tenant_id, 200, false)
  else eq(await button(s, '指定单位ID').count(), 0)
  s.mode = 'basic'; const initial = next(s, oldPath(s), { page: 1 })
  await button(s, '通知中心（仅模拟）').click(); await response(initial)
  await button(s, UI.open).waitFor(); eq(listCount(s), s.defaultNewCount ?? 0); eq(await panel(s).count(), 0)
  s.defaultNewCount = listCount(s)
  eq(await s.page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), 'UTC')
  checks.push(`${name}:real-login-notifications-read-only-basic-zero-new-GET`)
}
async function forbiddenControls(s) {
  eq(await panel(s).getByRole('button', { name: /创建模拟通知|取消待发模拟|模拟核对|查看模拟尝试|查看通知历史|联系人|模板|规则|发送短信|拨打电话/ }).count(), 0)
  eq(await s.page.getByLabel('通知查询关键字', { exact: true }).count(), 0)
  eq(await s.page.getByRole('button', { name: '查询通知记录', exact: true }).count(), 0)
  eq(await s.page.locator('table').count(), 0) // Authored advanced UI uses safe article cards; basic table unmounts.
}
async function rendered(s, d) {
  await panel(s).getByRole('heading', { name: UI.title, exact: true }).waitFor()
  for (const k of metricKeys) {
    // Existing FE metric div has no group role: anchor its authored visible label,
    // then inspect only that label's immediate container (no guessed CSS/data hook).
    const card = panel(s).getByText(UI.metrics[k], { exact: true }).locator('..')
    await card.getByText(String(d.metrics[k]), { exact: true }).waitFor()
  }
  for (const r of d.items) { const row = panel(s).getByRole('article').filter({ has: s.page.getByText(r.id, { exact: true }) }); await row.waitFor(); requireThat((await row.innerText()).includes(r.device_name), 'SAFE_TEXT_NOT_RENDERED') }
  eq(await panel(s).getByRole('article').count(), d.items.length)
  await panel(s).getByText(d.as_of, { exact: false }).first().waitFor()
  await forbiddenControls(s); await frames(s)
}
async function one(s, query, action, expected) {
  const before = listCount(s), old = requests.filter(r => r.context === s.name && ['basic-list', 'basic-capability'].includes(r.kind)).length
  const pending = next(s, wbPath(s), query); await action(); const { raw } = await response(pending); const d = workbench(raw, query, expected); await rendered(s, d); await sleep(120); await frames(s)
  eq(listCount(s), before + 1); eq(requests.filter(r => r.context === s.name && ['basic-list', 'basic-capability'].includes(r.kind)).length, old)
  s.query = query; s.data = d; return d
}
function expectedFor(s) { return (s.principal.audience === 'management' ? s.tenant : s.principal.tenant_id) === input.tenant_id ? { total: input.total, metrics: input.expected_metrics } : { total: input.other_total, metrics: input.other_expected_metrics } }
async function enter(s, status = 200) {
  const before = listCount(s); s.mode = 'advanced'
  if (status !== 200) { const pending = next(s, wbPath(s), { page: 1 }); await button(s, UI.open).click(); await response(pending, status); await panel(s).getByRole('alert').first().waitFor(); eq(await panel(s).getByRole('article').count(), 0); await button(s, '退出登录').waitFor(); return }
  const d = await one(s, { page: 1 }, () => button(s, UI.open).click(), expectedFor(s)); eq(listCount(s), before + 1)
  for (const k of filters) eq(await field(s, k).inputValue(), '')
  return d
}
async function reset(s) { const d = await one(s, { page: 1 }, () => pbutton(s, UI.reset).click(), expectedFor(s)); for (const k of filters) eq(await field(s, k).inputValue(), ''); return d }
async function draft(s, values) {
  const before = listCount(s)
  for (const [k, v] of Object.entries(values)) {
    if (['status', 'channel'].includes(k)) await field(s, k).selectOption(v)
    else {
      await field(s, k).fill(k.startsWith('created_') && v ? beijingDraft(v) : v)
      if (k.startsWith('created_') && v) { const local = await field(s, k).inputValue(); eq(micros((local.length === 16 ? local + ':00' : local) + '+08:00'), micros(v)) }
    }
  }
  await sleep(120); await frames(s); eq(listCount(s), before)
}
async function apply(s, query, expected) { return one(s, { page: 1, ...query }, () => pbutton(s, UI.apply).click(), expected) }
async function back(s) { if (await dialog(s).count()) { await s.page.keyboard.press('Escape'); await dialog(s).waitFor({ state: 'detached' }) }; s.mode = 'basic'; const before = listCount(s), pending = next(s, oldPath(s), { page: 1 }); await pbutton(s, UI.back).click(); await response(pending); await button(s, UI.open).waitFor(); eq(await panel(s).count(), 0); eq(listCount(s), before) }
async function detail(s, id) {
  const before = listCount(s), pending = next(s, detailPath(s, id)), h = hold(s, 'workbench-detail')
  try {
    const row = panel(s).getByRole('article').filter({ has: s.page.getByText(id, { exact: true }) }); await row.getByRole('button', { name: UI.detail, exact: true }).click()
    await bounded(h.seen, 'REAL_DETAIL_NOT_HELD'); eq(h.status, 200)
    const real = rawJson(h.raw); keys(real, ['as_of', 'timezone', 'record']); eq(real.record.id, id); record(real.record)
    await dialog(s).waitFor(); await frames(s)
    // A loading Sheet may show its selected ID, but never borrow old row facts as detail.
    eq(await dialog(s).getByText(input.html_text, { exact: true }).count(), 0)
    eq(await dialog(s).getByText(real.record.created_at, { exact: true }).count(), 0)
    await h.release()
    const { raw } = await response(pending); const d = rawJson(raw); keys(d, ['as_of', 'timezone', 'record']); eq(d.record.id, id); eq(d.timezone, 'Asia/Shanghai'); micros(d.as_of); record(d.record)
    await dialog(s).getByText(id, { exact: true }).waitFor(); await dialog(s).getByText(d.as_of, { exact: false }).first().waitFor(); await frames(s); eq(listCount(s), before)
    eq(await dialog(s).getByRole('button', { name: /查看模拟尝试|查看通知历史|取消待发模拟|模拟核对|联系人|正文|报警详情|设备详情/ }).count(), 0)
    controls.push({ kind: 'held-real-detail-unchanged', transition: 'loading-Sheet-no-list-snapshot-fallback', fetched_status: 200, delivery_outcome: h.delivery_outcome, not_natural_fault: true })
    // No relation such as created<=finished/as_of or terminal=>finished is invented.
    return d
  } finally { await h.release() }
}
async function closeDetail(s) { await s.page.keyboard.press('Escape'); await dialog(s).waitFor({ state: 'detached' }) }
async function exercise(s) {
  phase = `${s.actor}-draft-literal-pagination-time-detail`
  const sample = s.data.items[0]; requireThat(sample, 'NONEMPTY_FIXTURE_REQUIRED')
  await draft(s, { keyword: input.literal_keyword, status: sample.status, channel: sample.channel, dispatch_id: sample.dispatch_id, created_from: input.precision.created_from, created_to: input.precision.created_to })
  await reset(s); await draft(s, { keyword: input.literal_keyword }); const first = await apply(s, { keyword: input.literal_keyword }, { total: input.literal_total })
  requireThat(first.total > first.size, 'LITERAL_FIXTURE_MUST_SPAN_PAGES'); const allIds = first.items.map(r => r.id), metrics = { ...first.metrics }
  await draft(s, { keyword: 'unapplied-local-draft' })
  for (let page = 2; allIds.length < first.total; page++) { requireThat(page <= 20, 'FINITE_PAGE_BOUND'); const d = await one(s, { page, keyword: input.literal_keyword }, () => pbutton(s, UI.next).click(), { total: input.literal_total }); eq({ ...d.metrics }, metrics); allIds.push(...d.items.map(r => r.id)); eq(await field(s, 'keyword').inputValue(), 'unapplied-local-draft') }
  eq(allIds.sort(), [...input.literal_ids].sort()); eq(new Set(allIds).size, input.literal_total)
  await one(s, s.query, () => pbutton(s, UI.refresh).click(), { total: input.literal_total, metrics }); eq(await field(s, 'keyword').inputValue(), 'unapplied-local-draft')
  await reset(s)
  for (const c of input.cases) {
    await draft(s, Object.fromEntries(filters.map(k => [k, c.query[k] ?? ''])))
    const d = await apply(s, c.query, c); if (c.ids && d.items.length < d.total) requireThat(d.items.every(r => c.ids.includes(r.id)), 'CASE_EXPECTED_ID_MEMBERSHIP')
  }
  await reset(s); await draft(s, { created_from: input.precision.created_from, created_to: input.precision.created_to })
  const precise = await apply(s, { created_from: input.precision.created_from, created_to: input.precision.created_to }, { total: input.precision.expected_total, ids: input.precision.expected_ids })
  eq(precise.items.map(r => r.id).sort(), [...input.precision.expected_ids].sort())
  await draft(s, { created_from: input.precision.created_to, created_to: input.precision.created_from }); const before = listCount(s); await pbutton(s, UI.apply).click(); await panel(s).getByRole('alert').first().waitFor(); await frames(s); eq(listCount(s), before)
  await reset(s); await draft(s, { keyword: input.record_keyword }); const matching = await apply(s, { keyword: input.record_keyword }, { total: 1, ids: [input.record_id] })
  eq(matching.items[0].created_at, input.precision.created_at)
  const snapshot = JSON.stringify(s.data); const draftBefore = await field(s, 'keyword').inputValue(); const current = await detail(s, input.record_id)
  eq(current.record.created_at, input.precision.created_at); requireThat((await dialog(s).innerText()).includes(input.precision.created_at), 'RAW_MICROSECOND_DETAIL_DISPLAY_REQUIRED')
  requireThat((await dialog(s).innerText()).includes(input.html_text), 'LITERAL_HTML_TEXT_REQUIRED')
  eq(await s.page.evaluate(() => ({ executed: Object.hasOwn(window, '__NWB_BAD'), rawScript: [...document.querySelectorAll('script')].some(e => e.textContent.includes('__NWB_BAD')) })), { executed: false, rawScript: false })
  eq(JSON.stringify(s.data), snapshot); eq(await field(s, 'keyword').inputValue(), draftBefore)
  await closeDetail(s); await reset(s)
  checks.push(`${s.actor}:six-drafts-zero-GET/literal-exact-fixture-IDs/all-pages-nine-metrics/applied-pagination-refresh/explicit-reset/backend-AND-empty-cases/raw-microsecond-half-open/independent-safe-detail`)
}
function hold(s, kind, synthetic401 = false) {
  requireThat(!s.hold, 'HOLD_ALREADY_ACTIVE'); let captured, release, settled
  const h = { kind, synthetic401, started: false, released: false, seen: new Promise(r => { captured = r }), gate: new Promise(r => { release = r }), done: new Promise(r => { settled = r }), captured, settled }
  h.captured = captured; h.settled = settled
  h.release = async () => { h.released = true; release(); if (h.started) await bounded(h.done, 'HELD_DELIVERY_NOT_SETTLED'); if (s.hold === h) s.hold = null }
  s.hold = h; s.holds.push(h); return h
}
async function logout(s) { const pending = next(s, '/api/auth/logout', {}, 'POST', undefined); s.mode = 'logout'; await button(s, '退出登录').click(); await response(pending, 204); await s.page.getByRole('heading', { name: '欢迎回来', exact: true }).waitFor(); s.defaultNewCount = listCount(s) }
async function heldScope(s) {
  phase = 'controlled-real-200-delivery-after-scope-switch'; const h = hold(s, 'workbench-list')
  try {
    permit(s, wbPath(s), s.query); await pbutton(s, UI.refresh).click(); await bounded(h.seen, 'REAL_SCOPE_BODY_NOT_HELD'); eq(h.status, 200); workbench(h.raw, s.query, { total: input.total })
    await back(s); await scope(s, input.other_tenant_id); const b = await enter(s); eq(b.total, input.other_total); const before = JSON.stringify(b)
    await h.release(); await frames(s); await rendered(s, b); eq(JSON.stringify(s.data), before); eq(await panel(s).getByText(input.record_id, { exact: true }).count(), 0)
    controls.push({ kind: 'held-real-response-unchanged', transition: 'scope-A-to-B', fetched_status: 200, delivery_outcome: h.delivery_outcome, not_natural_fault: true })
    await back(s); await scope(s, input.tenant_id); await enter(s)
  } finally { await h.release() }
}
async function heldActor(s, synthetic401) {
  phase = synthetic401 ? 'controlled-synthetic-stale401-after-real-actor-login' : 'controlled-real-body-after-real-actor-login'; const h = hold(s, 'workbench-list', synthetic401)
  try {
    permit(s, wbPath(s), s.query); await pbutton(s, UI.refresh).click(); await bounded(h.seen, 'REAL_ACTOR_BODY_NOT_HELD'); eq(h.status, 200); workbench(h.raw, s.query, { total: input.total })
    await logout(s); await login(s, 'other_reader_platform', false); const b = await enter(s); eq(b.total, input.other_total)
    await h.release(); await frames(s); await button(s, '退出登录').waitFor(); await rendered(s, b); eq(await panel(s).getByText(input.record_id, { exact: true }).count(), 0)
    controls.push({ kind: synthetic401 ? 'synthetic-status401-real-body-unchanged' : 'held-real-response-unchanged', transition: 'logout-A-login-B', fetched_status: 200, attempted_delivery_status: synthetic401 ? 401 : 200, delivery_outcome: h.delivery_outcome, not_natural_fault: true, genuine_revoked_auth: false })
    await logout(s); await login(s, 'record_reader_platform', false); await enter(s)
  } finally { await h.release() }
}
async function permission(s) {
  phase = 'actual-ungranted-unit403-clears-view-not-session'; await back(s); await scope(s, input.tenant_id, 403); await enter(s, 403)
  await button(s, '退出登录').waitFor(); eq(await s.page.getByLabel('密码', { exact: true }).count(), 0)
  // Current403 is a real backend denial; no permission mutation or browser admin.
  checks.push('other-platform:real-ungranted-A403-no-stale-records-session-retained')
}
async function noPersistence(s) {
  eq(await s.page.evaluate(async () => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage), caches: await caches.keys(), indexed: typeof indexedDB.databases === 'function' ? (await indexedDB.databases()).length : 0 })), { local: [], session: [], caches: [], indexed: 0 }); eq((await s.context.cookies()).length, 0)
}
async function scanDom(s) {
  eq(await s.page.locator('input[type="password"]').count(), 0)
  // Bring observed DOM bytes to Node, never inject all actors' passwords/tokens
  // or Node-only private phone/canary values into any renderer for scanning.
  const content = await s.page.evaluate(() => document.documentElement.outerHTML + '\n' + [...document.querySelectorAll('input,textarea')].map(e => e.value).join('\n'))
  for (const secret of knownSecrets) requireThat(!secret || !content.includes(secret), 'DOM_EXACT_SECRET')
  await noPersistence(s)
}
async function capture(s, name, sheet = false) {
  eq(await s.page.getByRole('button', { name: '退出登录', exact: true, includeHidden: true }).count(), 1); await scanDom(s); await bounded(s.page.evaluate(() => document.fonts.ready), 'FONT_READY_TIMEOUT'); await s.page.evaluate(() => window.scrollTo(0, 0)); await frames(s)
  eq(await dialog(s).count(), sheet ? 1 : 0)
  if (!sheet) {
    const heading = panel(s).getByRole('heading', { name: UI.title, exact: true }); await heading.scrollIntoViewIfNeeded(); await frames(s)
    // Six stacked mobile filters intentionally scroll vertically; never demand
    // heading+all filters+apply occupy one390x844 viewport or stitch a fake image.
    const visibleControls = s.page.viewportSize().width <= 390 ? [heading, field(s, 'keyword')] : [heading, field(s, 'keyword'), pbutton(s, UI.apply)]
    for (const control of visibleControls) requireThat(await control.evaluate(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight }), 'SCREENSHOT_TITLE_FILTER_CONTROL_NOT_IN_VIEWPORT')
  }
  else requireThat(await dialog(s).evaluate(e => { const r = e.getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight }), 'SHEET_VIEWPORT_GEOMETRY')
  requireThat(await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'BODY_HORIZONTAL_OVERFLOW')
  const target = path.join(input.artifact_directory, name); requireThat(!existsSync(target), 'SCREENSHOT_EXISTS'); await s.page.screenshot({ path: target, fullPage: false, animations: 'disabled' })
  const bytes = readFileSync(target); eq(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a'); for (const secret of knownSecrets) requireThat(!bytes.includes(Buffer.from(secret)), 'PNG_EXACT_SECRET_BYTES')
  screenshots.push({ path: target, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), width: s.page.viewportSize().width, height: s.page.viewportSize().height, original: true, capture: sheet ? 'customer-Sheet' : 'document', png_scan: 'exact-byte-only-no-OCR; DOM exact-secret scan before capture' })
}
function validateInput() {
  requireThat(/^http:\/\/127\.0\.0\.1:\d+\/$/.test(input.url), 'OWNED_LOOPBACK_ROOT_REQUIRED'); origin = new URL(input.url).origin
  requireThat(path.isAbsolute(input.artifact_directory) && existsSync(input.artifact_directory), 'ARTIFACT_DIRECTORY_REQUIRED'); reportPath = path.join(input.artifact_directory, 'notification-workbench-browser-result.json'); requireThat(!existsSync(reportPath), 'RESULT_EXISTS')
  for (const k of ['tenant_id', 'other_tenant_id', 'record_id', 'other_record_id', 'record_keyword']) text(input[k]); requireThat(input.tenant_id !== input.other_tenant_id && input.record_id !== input.other_record_id, 'DISTINCT_SCOPE_REQUIRED')
  eq(input.literal_keyword, '%_!'); int(input.total, 1); int(input.other_total, 1); int(input.literal_total, 2); eq(input.literal_ids.length, input.literal_total); eq(new Set(input.literal_ids).size, input.literal_total)
  keys(input.expected_metrics, metricKeys); keys(input.other_expected_metrics, metricKeys)
  for (const name of actorKeys) { const a = input[name]; keys(a, ['username', 'password', 'user_id', 'audience', 'tenant_id']); for (const k of ['username', 'password', 'user_id', 'tenant_id']) text(a[k]); eq(a.audience, name.endsWith('platform') ? 'management' : 'customer'); eq(a.tenant_id, name.startsWith('other_') ? input.other_tenant_id : input.tenant_id); knownSecrets.add(a.username); knownSecrets.add(a.password) }
  eq(new Set(actorKeys.map(k => input[k].user_id)).size, 4)
  keys(input.precision, ['created_from', 'created_to', 'expected_ids', 'expected_total', 'created_at', 'beijing_created_at']); requireThat(micros(input.precision.created_from) < micros(input.precision.created_to), 'PRECISION_RANGE'); requireThat(/\.\d{6}(?:Z|[+-]\d{2}:\d{2})$/.test(input.precision.created_at), 'SIX_RAW_MICROSECONDS_REQUIRED'); text(input.precision.beijing_created_at); eq(beijingDraft(input.precision.created_at), input.precision.beijing_created_at); int(input.precision.expected_total, 1); eq(input.precision.expected_ids.length, input.precision.expected_total)
  text(input.html_text); requireThat(input.html_text.includes('<') && input.html_text.includes('>'), 'REAL_HTML_TEXT_FIXTURE_REQUIRED')
  requireThat(Array.isArray(input.cases) && input.cases.length >= 2 && input.cases.length <= 20, 'BACKEND_CASES_REQUIRED'); requireThat(input.cases.some(c => c.total === 0), 'EMPTY_CASE_REQUIRED'); requireThat(input.cases.some(c => Object.keys(c.query).length >= 3 && c.total > 0), 'NONVACUOUS_AND_CASE_REQUIRED')
  for (const c of input.cases) { requireThat(Object.keys(c.query).every(k => filters.includes(k)), 'CASE_QUERY_KEYS'); int(c.total); if (c.metrics) keys(c.metrics, metricKeys); if (c.ids) eq(c.ids.length, c.total) }
  // Array element keys are numeric, so the generic sensitive-key walker cannot
  // discover this explicitly declared private canary list by a property regex.
  if (input.node_only?.restricted_values !== undefined) { requireThat(Array.isArray(input.node_only.restricted_values), 'PRIVATE_CANARY_ARRAY_REQUIRED'); for (const value of input.node_only.restricted_values) { text(value); knownSecrets.add(value) } }
  // Unknown optional private controls remain Node-only and are never forwarded wholesale.
  function collect(v, key = '') { if (typeof v === 'string' && /password|token|secret|canary|phone/i.test(key)) knownSecrets.add(v); else if (v && typeof v === 'object') for (const [k, x] of Object.entries(v)) collect(x, k) }; collect(input)
}
try {
  input = privateInput(); validateInput()
  progressPath = path.join(input.artifact_directory, 'notification-workbench-browser-progress.json'); requireThat(!existsSync(progressPath), 'PROGRESS_EXISTS'); progress(); progressTimer = setInterval(() => progress(), 2000); progressTimer.unref()
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const { chromium } = await loadChromium(root)
  const executablePath = browserExecutable(); requireThat(executablePath, 'REAL_INSTALLED_CHROME_REQUIRED')
  phase = 'owned-chromium-launch'; browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true, env: browserEnvironment() }); browserCleanup = 'pending'
  const platform = await create('record_reader_platform'); await login(platform); await enter(platform); await exercise(platform); await heldScope(platform); await heldActor(platform, false); await heldActor(platform, true)
  await capture(platform, 'management-notification-workbench-p8c1.png')
  const other = await create('other_reader_platform'); await login(other); await enter(other); eq(other.data.items.map(r => r.id).includes(input.record_id), false); await permission(other)
  const customer = await create('record_reader_customer'); await login(customer); await enter(customer)
  await customer.page.setViewportSize({ width: 390, height: 844 }); await customer.page.waitForFunction(() => innerWidth === 390 && document.documentElement.scrollWidth <= 390)
  await exercise(customer); await capture(customer, 'customer-notification-workbench-p8c1.png')
  await draft(customer, { keyword: input.record_keyword }); await apply(customer, { keyword: input.record_keyword }, { total: 1, ids: [input.record_id] }); await detail(customer, input.record_id); await capture(customer, 'customer-notification-workbench-sheet-390-p8c1.png', true); await closeDetail(customer)
  const otherCustomer = await create('other_reader_customer'); await login(otherCustomer); await enter(otherCustomer); eq(otherCustomer.data.total, input.other_total); requireThat(otherCustomer.data.items.some(r => r.id === input.other_record_id), 'OTHER_CUSTOMER_REAL_ROW'); eq(await panel(otherCustomer).getByText(input.record_id, { exact: true }).count(), 0)
  for (const s of owners) { await scanDom(s); for (const r of requests.filter(r => r.context === s.name && r.pathname.startsWith('/api/customer/'))) eq(r.tenant_header_present, false) }
  eq(problems, []); eq(owners.length, 4); eq(screenshots.length, 3)
  requireThat(requests.every(r => r.method === 'GET' || r.pathname === '/api/auth/login' && r.method === 'POST' || r.pathname === '/api/auth/logout' && r.method === 'POST'), 'BUSINESS_MUTATION_ATTEMPT')
  checks.push('four-leastprivilege-actual-actors/two-platform-two-fixed-customer/no-tenants-directory/no-advanced-capability-config-old-detail-history-attempt-otherentity-network/no-business-mutation/no-vendor-no-delivery-claim')
  checks.push('controlled-unchanged-real-200-bytes-after-scope-and-real-actor-switch; explicitly-synthetic-status401-after-real-login-not-genuine-revocation; no-forged-data')
  checks.push('three-original-safe-logged-in-PNGs/1440x1080-desktop/390x844-customer-Sheet/no-crop-stitch-normalization/no-horizontal-body-overflow')
} catch (error) { failed = true; problems.push(/^[A-Z][A-Z0-9_]{1,100}$/.test(error?.message ?? '') ? error.message : 'ASSERTION_OR_RUNTIME_FAILURE_AT_REPORTED_PHASE'); const location = String(error?.stack ?? '').match(/notification-workbench-browser-smoke\.mjs:(\d+):(\d+)/); if (location) failureLocation = { line: Number(location[1]), column: Number(location[2]) } }
finally {
  if (progressTimer) clearInterval(progressTimer); progress(true)
  for (const s of owners) {
    for (const h of s.holds) { try { await h.release() } catch { failed = true; problems.push('HELD_ROUTE_CLEANUP_UNCERTAIN') } }
    try { await bounded(s.context.close(), 'CONTEXT_CLOSE_TIMEOUT'); s.closed = s.page.isClosed() && !browser.contexts().includes(s.context); requireThat(s.closed, 'CONTEXT_NOT_CLOSED') } catch { failed = true; problems.push('CONTEXT_CLEANUP_UNCERTAIN') }
  }
  if (browser) { try { await bounded(browser.close(), 'BROWSER_CLOSE_TIMEOUT', 15000); browserCleanup = browser.isConnected() ? 'uncertain' : 'confirmed-disconnected-owned-browser'; requireThat(!browser.isConnected(), 'BROWSER_NOT_CLOSED') } catch { failed = true; browserCleanup = 'uncertain'; problems.push('BROWSER_CLEANUP_UNCERTAIN') } }
}
const report = { status: failed ? 'failed' : 'passed', phase, failure_location: failureLocation ?? null, checks, requests, actors: owners.map(s => ({ name: s.name, context_closed: s.closed })), browser_cleanup: browserCleanup, teardown_completed: browserCleanup === 'confirmed-disconnected-owned-browser' && owners.length === 4 && owners.every(s => s.closed), screenshots, controlled_delivery: controls, errors: [...new Set(problems)], known_secret_scan: 'DOM exact values; PNG exact bytes only (not OCR); report exact values', limitations: ['Not production/vendor delivery evidence', 'Held HTTP delays headers/body together; response.text-only split and same-token-generation/fake-clock cases belong to new FE unit tests', 'Synthetic stale401 is not naturally observed revokedAuth; no admin grant/revoke operation', 'Browser disconnect/context closure is observed; external OS process census belongs to Root unique-package cleanup'], result_path: reportPath ?? null }
try {
  const raw = JSON.stringify(report, null, 2); for (const secret of knownSecrets) requireThat(!raw.includes(secret), 'REPORT_EXACT_SECRET')
  requireThat(Buffer.byteLength(raw) <= 524288, 'REPORT_TOO_LARGE'); if (reportPath) writeFileSync(reportPath, raw, { flag: 'wx' }); console.log(raw)
} catch { failed = true; console.log(JSON.stringify({ status: 'failed', phase: 'safe-report-write', errors: ['REPORT_WRITE_OR_EXACT_SECRET_SCAN_FAILED'], browser_cleanup: browserCleanup })) }
if (failed) process.exit(1) // Report is sync-written; do not hang on an already-unconfirmed Playwright transport.
