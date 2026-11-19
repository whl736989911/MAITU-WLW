// Finite real Linux browser sandbox probe, not an application or product test.
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { browserEnvironment, browserExecutable, loadChromium } from './browser-fixture.mjs'
const root = process.env.FIRE_VALIDATION_ROOT ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const { chromium } = await loadChromium(root)
let browser
try {
  browser = await chromium.launch({ executablePath: browserExecutable(), headless: true, chromiumSandbox: true, env: browserEnvironment(), timeout: 15000 })
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const page = await context.newPage()
  await page.goto('data:text/html,<title>Ubuntu sandbox probe</title>')
  if (await page.title() !== 'Ubuntu sandbox probe') throw new Error('REAL_LINUX_BROWSER_PROBE_FAILED')
  console.log(JSON.stringify({ status: 'real-linux-browser-sandbox-probe-passed', version: browser.version(), chromiumSandbox: true, application_test: false }))
  await context.close()
} finally { if (browser) await browser.close() }
