// Ubuntu-only fixture plumbing. No downloads, global service changes or browser fallback bundle.
import { accessSync, chmodSync, closeSync, constants, existsSync, fstatSync, ftruncateSync, lstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, readSync, realpathSync, rmSync, statSync, writeFileSync, writeSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const linuxKeys = ['PATH', 'HOME', 'USER', 'LOGNAME', 'LANG', 'LC_ALL', 'LC_CTYPE', 'TZ', 'TMPDIR', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'XDG_RUNTIME_DIR', 'DISPLAY', 'WAYLAND_DISPLAY', 'DBUS_SESSION_BUS_ADDRESS']
function requireLinux() { if (process.platform !== 'linux') throw new Error('UBUNTU_LINUX_REQUIRED'); if (process.getuid() === 0) throw new Error('NON_ROOT_UBUNTU_FIXTURE_USER_REQUIRED') }
function absolute(value) { return typeof value === 'string' && value.startsWith('/') && !value.includes('\0') }
function executable(value) { try { return absolute(value) && statSync(value).isFile() && (accessSync(value, constants.X_OK), true) } catch { return false } }

// A supplied executable is authoritative: an invalid choice never silently selects a different browser.
export function browserExecutable(env = process.env) {
  requireLinux()
  const allowedName = /^(?:chrome|google-chrome(?:-stable|-beta|-unstable)?|chromium(?:-browser)?)$/
  const valid = candidate => executable(candidate) && allowedName.test(path.basename(candidate))
  if (env.BROWSER_EXECUTABLE !== undefined) {
    if (!valid(env.BROWSER_EXECUTABLE)) throw new Error('BROWSER_EXECUTABLE_MUST_SELECT_INSTALLED_LINUX_CHROME_OR_CHROMIUM')
    return env.BROWSER_EXECUTABLE
  }
  const selected = ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/opt/google/chrome/chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser', '/snap/bin/chromium'].find(valid)
  if (!selected) throw new Error('INSTALLED_UBUNTU_CHROME_OR_CHROMIUM_REQUIRED')
  return selected
}

export async function loadChromium(root) {
  requireLinux()
  const module = path.join(root, '.tools/browser-tools/node_modules/playwright/index.mjs')
  if (!absolute(root) || !existsSync(module)) throw new Error('PROJECT_LOCAL_PLAYWRIGHT_MODULE_REQUIRED')
  const loaded = await import(pathToFileURL(module).href)
  if (typeof loaded.chromium?.launch !== 'function') throw new Error('PROJECT_LOCAL_PLAYWRIGHT_CHROMIUM_REQUIRED')
  return { chromium: loaded.chromium }
}

// Never pass fixture passwords, database credentials, or runtime-injection/debug options to Chromium.
export function browserEnvironment(source = process.env) {
  requireLinux()
  return Object.fromEntries(linuxKeys.filter(key => typeof source[key] === 'string' && source[key]).map(key => [key, source[key]]))
}

// Only the two Node-owned JVM scenarios allocate here. Python-owned runs supply their own TMPDIR.
const nodeShortTemps = new Map()
const sameIdentity = (a, b) => a.dev === b.dev && a.ino === b.ino && a.uid === b.uid && a.mode === b.mode
function ownedShortTemp(entry) {
  if (!/^\/tmp\/fire-node-[A-Za-z0-9]{6}$/.test(entry.directory)) return false
  const current = lstatSync(entry.directory, { bigint: true })
  return current.isDirectory() && !current.isSymbolicLink() && current.uid === BigInt(process.getuid()) && (current.mode & 0o7777n) === 0o700n && sameIdentity(current, entry.identity) && realpathSync(entry.directory) === entry.directory
}
function shortTempMarker(entry, state) {
  return { kind: 'owned-linux-short-tmp-node', schema: 'node-short-tmp-v1', owner_pid: process.pid, source_run: entry.run, directory: entry.directory, device: entry.identity.dev.toString(), inode: entry.identity.ino.toString(), uid: Number(entry.identity.uid), mode: 0o700, state }
}
function updateShortTempMarker(entry, state) {
  const fd = openSync(entry.marker, constants.O_WRONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
  try {
    const current = fstatSync(fd, { bigint: true })
    if (!current.isFile() || !sameIdentity(current, entry.markerIdentity) || current.uid !== BigInt(process.getuid()) || (current.mode & 0o7777n) !== 0o600n) throw new Error('OWNED_SHORT_TMP_MARKER_IDENTITY_CHANGED')
    const data = Buffer.from(JSON.stringify(shortTempMarker(entry, state), null, 2) + '\n')
    ftruncateSync(fd, 0)
    for (let offset = 0; offset < data.length;) { const written = writeSync(fd, data, offset, data.length - offset, offset); if (written <= 0) throw new Error('OWNED_SHORT_TMP_MARKER_WRITE_FAILED'); offset += written }
  } finally { closeSync(fd) }
}
function cleanupShortTemps() {
  for (const entry of nodeShortTemps.values()) {
    let state = 'cleanup-unconfirmed', failed = false
    try {
      if (ownedShortTemp(entry)) { rmSync(entry.directory, { recursive: true }); state = 'removed' }
      else { state = 'identity-mismatch-preserved'; failed = true }
    } catch (error) {
      // Only absence of the root itself counts as already gone; never turn a partial rm failure into success.
      try { lstatSync(entry.directory); failed = true } catch (check) { if (error?.code === 'ENOENT' && check?.code === 'ENOENT') state = 'already-absent'; else failed = true }
    }
    try { updateShortTempMarker(entry, state) } catch { failed = true }
    if (failed && !process.exitCode) process.exitCode = 1
  }
}
function shortTempForRun(run) {
  const cached = nodeShortTemps.get(run)
  if (cached) {
    if (!ownedShortTemp(cached)) throw new Error('OWNED_SHORT_TMP_IDENTITY_CHANGED')
    const marker = lstatSync(cached.marker, { bigint: true })
    if (!marker.isFile() || !sameIdentity(marker, cached.markerIdentity)) throw new Error('OWNED_SHORT_TMP_MARKER_IDENTITY_CHANGED')
    return cached.directory
  }
  const parent = lstatSync('/tmp')
  if (!parent.isDirectory() || parent.isSymbolicLink() || realpathSync('/tmp') !== '/tmp') throw new Error('REAL_LINUX_TMP_DIRECTORY_REQUIRED')
  const directory = mkdtempSync('/tmp/fire-node-')
  const identity = lstatSync(directory, { bigint: true })
  const entry = { run, directory, identity, marker: path.join(run, 'node-short-tmp.json') }
  try {
    chmodSync(directory, 0o700)
    entry.identity = lstatSync(directory, { bigint: true })
    if (!ownedShortTemp(entry)) throw new Error('OWNED_SHORT_TMP_REQUIRED')
    writeFileSync(entry.marker, JSON.stringify(shortTempMarker(entry, 'active'), null, 2) + '\n', { flag: constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, mode: 0o600 })
    entry.markerIdentity = lstatSync(entry.marker, { bigint: true })
    if (!entry.markerIdentity.isFile() || entry.markerIdentity.uid !== BigInt(process.getuid()) || (entry.markerIdentity.mode & 0o7777n) !== 0o600n) throw new Error('OWNED_SHORT_TMP_MARKER_REQUIRED')
  } catch (error) {
    if (ownedShortTemp(entry)) rmSync(directory, { recursive: true })
    throw error
  }
  nodeShortTemps.set(run, entry)
  if (nodeShortTemps.size === 1) process.once('exit', cleanupShortTemps)
  return directory
}
export function fixtureEnvironment(run, source = process.env) {
  requireLinux()
  if (!absolute(run) || !statSync(run).isDirectory()) throw new Error('OWNED_FIXTURE_DIRECTORY_REQUIRED')
  const canonicalRun = realpathSync(run), env = browserEnvironment(source)
  if (lstatSync(canonicalRun, { bigint: true }).uid !== BigInt(process.getuid())) throw new Error('OWNED_FIXTURE_DIRECTORY_REQUIRED')
  for (const [key, relative] of Object.entries({ HOME: 'home', XDG_CONFIG_HOME: 'xdg-config', XDG_CACHE_HOME: 'xdg-cache', XDG_RUNTIME_DIR: 'xdg-runtime' })) {
    const directory = path.join(canonicalRun, relative)
    mkdirSync(directory, { recursive: true, mode: 0o700 })
    const current = lstatSync(directory, { bigint: true })
    if (!current.isDirectory() || current.isSymbolicLink() || current.uid !== BigInt(process.getuid()) || realpathSync(directory) !== directory) throw new Error('OWNED_FIXTURE_PRIVATE_DIRECTORY_REQUIRED')
    chmodSync(directory, 0o700)
    env[key] = directory
  }
  env.TMPDIR = shortTempForRun(canonicalRun)
  return env
}

function elf(file) {
  const fd = openSync(file, 'r')
  try { const bytes = Buffer.alloc(4); return readSync(fd, bytes, 0, 4, 0) === 4 && bytes.equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46])) } finally { closeSync(fd) }
}
export function javaFixture(root, run, source = process.env) {
  requireLinux()
  let selected
  if (source.JAVA_HOME !== undefined) {
    if (!absolute(source.JAVA_HOME)) throw new Error('JAVA_HOME_MUST_BE_AN_ABSOLUTE_LINUX_JDK21_HOME')
    selected = path.join(source.JAVA_HOME, 'bin/java')
  } else {
    const tools = source.FIRE_TOOLS_DIR ?? path.join(root, '.tools/linux')
    if (!absolute(tools)) throw new Error('FIRE_TOOLS_DIR_MUST_BE_AN_ABSOLUTE_LINUX_DIRECTORY')
    const local = path.join(tools, 'jdk-21/bin/java')
    selected = existsSync(local) ? local : (source.PATH ?? '').split(':').filter(absolute).map(directory => path.join(directory, 'java')).find(executable)
  }
  if (!selected || !executable(selected)) throw new Error('LINUX_JDK21_REQUIRED')
  const java = realpathSync(selected), home = path.dirname(path.dirname(java))
  if (!elf(java) || !/^JAVA_VERSION="21(?:[.+-][^"]*)?"\r?$/m.test(readFileSync(path.join(home, 'release'), 'utf8'))) throw new Error('LINUX_ELF_JDK21_REQUIRED')
  const env = fixtureEnvironment(run, source)
  env.JAVA_HOME = home
  env.PATH = [path.dirname(java), env.PATH].filter(Boolean).join(':')
  return { java, env }
}
