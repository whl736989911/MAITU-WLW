import { createContext, useContext, useState, type ReactNode } from 'react'
import { ConfigProvider, theme as antdTheme } from 'antd'
import { ChevronDown, ChevronLeft, ChevronRight, Maximize, Menu, Settings2, type LucideIcon } from 'lucide-react'
import logo from '@/assets/legacy-customer/logo.png'
import { customerAssetsApi, type CustomerUnit } from '@/lib/customer-assets'
import { useObjectRevision } from '@/lib/object-events'
import { portalPath } from '@/lib/portal-entry'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import type { PageName } from '@/lib/types'

export interface CustomerSection {
  id: string
  label: string
  shortcut: string
  icon: LucideIcon
  items: { page: PageName; title: string }[]
}

type Props = {
  sections: CustomerSection[]
  page: PageName
  title?: string
  systems: string[]
  system: string
  onSystem: (system: string) => void
  onPage: (page: PageName) => void
  onRevalidate: () => void
  onPasswordChange: () => void
  onLogout: () => void
  children: ReactNode
}

const theme = {
  algorithm: antdTheme.darkAlgorithm,
  token: { colorPrimary: '#1689ef', colorLink: '#75bcff', colorBgBase: '#0c234b', colorBgContainer: '#0f2958', colorBgElevated: '#153966', colorTextBase: '#f0f6ff', colorBorder: '#3c577b', colorSplit: '#2a4266', borderRadius: 4, fontSize: 14, controlHeight: 32 },
  components: { Table: { headerBg: '#163661', headerColor: '#dcecff', rowHoverBg: '#1c4675', borderColor: '#2a4266', cellPaddingBlock: 8, cellPaddingInline: 12 } },
}

type Units = { data?: CustomerUnit[]; error?: unknown; loading: boolean; refresh: () => void }
const UnitContext = createContext<Units | null>(null)
export function useLegacyUnits() { return useContext(UnitContext) }

export function CustomerWorkspace({ sections, page, title, systems, system, onSystem, onPage, onRevalidate, onPasswordChange, onLogout, children }: Props) {
  const { user, permissions } = useSession()
  const revision = useObjectRevision('organization')
  const unit = useResource(`legacy-title:${user?.tenant_id}:${revision}`, signal => customerAssetsApi.scopeTree(signal), !!user?.tenant_id && permissions.includes('units:read'))
  const unitName = unit.data?.[0]?.name
  const platformName = import.meta.env.VITE_CUSTOMER_PLATFORM_TITLE || (unitName ? `${unitName}智慧消防平台` : '智慧消防平台')
  const [collapsed, setCollapsed] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [fullscreenError, setFullscreenError] = useState(false)
  const active = sections.find(section => section.items.some(item => item.page === page)) ?? sections.find(section => section.items.length > 0)
  const home = sections[0]?.items[0]?.page ?? active?.items[0]?.page
  const atHome = page === 'summary'

  function choose(target: PageName) {
    onPage(target)
    setMobileOpen(false)
    setAccountOpen(false)
  }

  async function toggleFullscreen() {
    setFullscreenError(false)
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await document.documentElement.requestFullscreen()
    } catch { setFullscreenError(true) }
  }

  function navItem({ id, label, items }: CustomerSection) {
    if (!items.length) return null
    return id === 'systems'
      ? <li className="legacy-system-menu" key={id}><button type="button" aria-current={id === active?.id ? 'true' : undefined}>{label}</button><ul>
        {systems.map(label => <li key={label}><button type="button" aria-current={page === 'customer-subsystem' && system === label ? 'page' : undefined}
          onClick={() => { onSystem(label); setMobileOpen(false) }}>{label}</button></li>)}
        {items.filter(item => item.page !== 'customer-subsystem').map(item => <li key={item.page}><button type="button" onClick={() => choose(item.page)}>{item.title}</button></li>)}
      </ul></li>
      : <li key={id}><button type="button" aria-current={id === active?.id ? 'true' : undefined} onClick={() => choose(items[0].page)}>{label}</button></li>
  }
  const submenu = (item: CustomerSection['items'][number]) => <button type="button" key={item.page} aria-current={item.page === page ? 'page' : undefined} onClick={() => choose(item.page)}><ChevronRight size={14}/>{item.title}</button>
  const systemPages = sections.find(section => section.id === 'systems')?.items.filter(item => item.page !== 'customer-subsystem') ?? []

  return <UnitContext.Provider value={unit}><ConfigProvider theme={theme}>
    <div className={`legacy-customer${atHome ? ' legacy-customer-home' : ''}${page === 'customer-subsystem' ? ' legacy-customer-subsystem' : ''}${collapsed ? ' legacy-customer-collapsed' : ''}${mobileOpen ? ' legacy-customer-menu-open' : ''}`}>
      <a className="legacy-skip" href="#main-content">跳到内容</a>
      <header className="legacy-header">
        <button className="legacy-mobile-toggle" type="button" aria-controls="legacy-side-menu" aria-expanded={mobileOpen} aria-label={mobileOpen ? '关闭栏目' : '打开栏目'} onClick={() => setMobileOpen(open => !open)}><Menu size={21}/></button>
        <a className="legacy-brand" title={platformName} href={portalPath('customer', false)} onClick={event => { event.preventDefault(); if (home) choose(home) }}><img src={logo} alt="" width={40} height={40}/><span>{platformName}</span></a>
        <nav className="legacy-top-nav" aria-label="主导航"><ul>{sections.slice(0, 3).map(navItem)}</ul><ul>{sections.slice(3).map(navItem)}</ul></nav>
        <div className="legacy-user-tools">
          {document.fullscreenEnabled && <button type="button" aria-label="全屏" title="全屏" onClick={() => void toggleFullscreen()}><Maximize size={17}/></button>}
          <div className="legacy-account" onKeyDown={event => { if (event.key === 'Escape') setAccountOpen(false) }}>
            <button className="legacy-account-trigger" type="button" title={user?.username} aria-label="账户菜单" aria-expanded={accountOpen} onClick={() => setAccountOpen(open => !open)}><span>{user?.username}</span><ChevronDown size={14}/></button>
            <div className="legacy-account-popup" hidden={!accountOpen}>
              <button type="button" onClick={() => { setAccountOpen(false); onPasswordChange() }}>修改密码</button>
              <button type="button" onClick={() => { setAccountOpen(false); onLogout() }}>退出登录</button>
            </div>
          </div>
          <button type="button" aria-label="重新核验当前会话" title="重新核验当前会话" onClick={onRevalidate}><Settings2 size={17}/></button>
        </div>
      </header>
      {fullscreenError && <p className="legacy-fullscreen-error" role="status">浏览器未能进入全屏。</p>}
      <div className="legacy-body">
        <aside className="legacy-sidebar" id="legacy-side-menu" aria-label="栏目">
          <nav aria-label="栏目菜单">
            {(systems.length > 0 || systemPages.length > 0) && <details className="legacy-mobile-systems"><summary>子系统</summary>
              {systems.map(label => <button key={label} type="button" aria-current={page === 'customer-subsystem' && system === label ? 'page' : undefined}
                onClick={() => { onSystem(label); setMobileOpen(false) }}>{label}</button>)}
              {systemPages.map(submenu)}</details>}
            {active?.id === 'systems' ? <div className="legacy-desktop-systems">{systemPages.map(submenu)}</div> : active?.items.map(submenu)}
          </nav>
        </aside>
        {!atHome && <button className="legacy-side-toggle" type="button" aria-controls="legacy-side-menu" aria-expanded={!collapsed} aria-label={collapsed ? '展开栏目' : '收起栏目'} onClick={() => setCollapsed(value => !value)}>{collapsed ? <ChevronRight size={14}/> : <ChevronLeft size={14}/>}</button>}
        <div className="legacy-page">
          {!atHome && <div className="legacy-breadcrumb" aria-label="当前位置">{active && <><span>{active.label}</span><ChevronRight size={13}/></>}<strong>{title ?? '工作区'}</strong></div>}
          {children}
        </div>
      </div>
      <nav className="legacy-quick-nav" aria-label="快捷导航">{['home', 'devices', 'alarms', 'mine'].map(id => sections.find(section => section.id === id)).flatMap(section => section ? [section] : []).map(({ id, shortcut, icon: Icon, items }) =>
        <button key={id} type="button" disabled={!items.length} aria-current={id === active?.id ? 'page' : undefined} onClick={() => { if (items.length) choose(items[0].page) }}><Icon size={18}/>{shortcut}</button>)}
      </nav>
    </div>
  </ConfigProvider></UnitContext.Provider>
}
