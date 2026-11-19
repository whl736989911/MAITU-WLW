import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import { BellRing, Coins, Building2, ChevronDown, ChevronLeft, ChevronRight, Flame, LayoutDashboard, LogOut, Menu, Radio, Settings2, ShieldCheck, ShieldX, Unplug, UserRound, Users, ContactRound, Landmark, KeyRound, Boxes, Cable, MessagesSquare, MessageSquareText, ListTodo, ListTree, SlidersHorizontal, RefreshCw } from 'lucide-react'
import { AnimatePresence, motion } from 'motion/react'
import { api, errorMessage } from '@/lib/api'
import { brand } from '@/lib/brand'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { allowedPage, type Health, type PageName } from '@/lib/types'
import { AuthScreen } from '@/components/auth'
import { Empty, ErrorNotice, Loading } from '@/components/common'
import { ScopeTreePanel } from '@/components/scope-tree-panel'
import { AuthorizedReadScope } from '@/components/authorized-read-scope'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { Input } from '@/components/ui/input'
import { RenderFailureBoundary } from '@/components/render-failure-boundary'
const CustomerAlarmMonitor = lazy(() => import('@/components/customer-alarm-monitor').then(m => ({ default: m.CustomerAlarmMonitor })))
const Dashboard = lazy(() => import('@/pages/dashboard').then(m => ({ default: m.Dashboard })))
const ManagementHome = lazy(() => import('@/pages/management-home').then(m => ({ default: m.ManagementHome })))
const CustomerHome = lazy(() => import('@/customer/home').then(m => ({ default: m.CustomerHome })))
const CustomerDevicesWorkspace = lazy(() => import('@/customer/workspace').then(m => ({ default: m.CustomerDevicesWorkspace })))
const CustomerAlarmsWorkspace = lazy(() => import('@/customer/workspace').then(m => ({ default: m.CustomerAlarmsWorkspace })))
const DevicesPage = lazy(() => import('@/pages/devices').then(m => ({ default: m.DevicesPage })))
const DutyWorkbenchPage = lazy(() => import('@/pages/duty-workbench').then(m => ({ default: m.DutyWorkbenchPage })))
const SettingsPage = lazy(() => import('@/pages/settings').then(m => ({ default: m.SettingsPage })))
const UnitsPage = lazy(() => import('@/pages/units').then(m => ({ default: m.UnitsPage })))
const PlacesPage = lazy(() => import('@/pages/places').then(m => ({ default: m.PlacesPage })))
const RelationsPage = lazy(() => import('@/pages/relations').then(m => ({ default: m.RelationsPage })))
const UsersPage = lazy(() => import('@/pages/users').then(m => ({ default: m.UsersPage })))
const RolesPage = lazy(() => import('@/pages/roles').then(m => ({ default: m.RolesPage })))
const ProductsPage = lazy(() => import('@/pages/products').then(m => ({ default: m.ProductsPage })))
const SourcesPage = lazy(() => import('@/pages/sources').then(m => ({ default: m.SourcesPage })))
const MessagesPage = lazy(() => import('@/pages/messages').then(m => ({ default: m.MessagesPage })))
const TasksPage = lazy(() => import('@/pages/tasks').then(m => ({ default: m.TasksPage })))
const ParametersPage = lazy(() => import('@/pages/parameters').then(m => ({ default: m.ParametersPage })))
const LegacyIngestPage = lazy(() => import('@/pages/legacy/ingest').then(m => ({ default: m.LegacyIngestPage })))
const NotificationContactsPage = lazy(() => import('@/pages/notification-contacts').then(m => ({ default: m.NotificationContactsPage })))
const NotificationProviderPage = lazy(() => import('@/pages/notification-provider').then(m => ({ default: m.NotificationProviderPage })))
const NotificationResourcesPage = lazy(() => import('@/pages/notification-resources').then(m => ({ default: m.NotificationResourcesPage })))
const NotificationRechargeSettingsPage = lazy(() => import('@/pages/notification-recharge-settings').then(m => ({ default: m.NotificationRechargeSettingsPage })))
const NotificationRechargePage = lazy(() => import('@/pages/notification-recharge').then(m => ({ default: m.NotificationRechargePage })))
const NotificationDeliveriesPage = lazy(() => import('@/pages/notification-deliveries').then(m => ({ default: m.NotificationDeliveriesPage })))
const WallPage = lazy(() => import('@/pages/wall').then(m => ({ default: m.WallPage })))
const DistributionPage = lazy(() => import('@/pages/distribution').then(m => ({ default: m.DistributionPage })))
const AccountPage = lazy(() => import('@/pages/account').then(m => ({ default: m.AccountPage })))
const BulkIoPage = lazy(() => import('@/pages/bulk-io').then(m => ({ default: m.BulkIoPage })))
const ProfilesPage = lazy(() => import('@/pages/profiles').then(m => ({ default: m.ProfilesPage })))
const SuppressionsPage = lazy(() => import('@/pages/suppressions').then(m => ({ default: m.SuppressionsPage })))
const AuditRecordsPage = lazy(() => import('@/pages/audit-records').then(m => ({ default: m.AuditRecordsPage })))
const DeviceGroupsPage = lazy(() => import('@/pages/device-groups').then(m => ({ default: m.DeviceGroupsPage })))
const DutyOverviewPage = lazy(() => import('@/pages/duty-overview').then(m => ({ default: m.DutyOverviewPage })))
const ParameterMonitoringPage = lazy(() => import('@/pages/parameter-monitoring').then(m => ({ default: m.ParameterMonitoringPage })))
const DeviceMonitoringPage = lazy(() => import('@/pages/device-monitoring').then(m => ({ default: m.DeviceMonitoringPage })))
const AlarmHistoryPage = lazy(() => import('@/pages/alarm-history').then(m => ({ default: m.AlarmHistoryPage })))
const FaultHistoryPage = lazy(() => import('@/pages/fault-history').then(m => ({ default: m.FaultHistoryPage })))
const OperationsOverviewPage = lazy(() => import('@/pages/operations-overview').then(m => ({ default: m.OperationsOverviewPage })))
const DeviceFaultsPage = lazy(() => import('@/pages/device-faults').then(m => ({ default: m.DeviceFaultsPage })))
const AlarmBaseConfigurationPage = lazy(() => import('@/pages/alarm-base-config').then(m => ({ default: m.AlarmBaseConfigurationPage })))
const ResourceLinkagePage = lazy(() => import('@/pages/resource-linkage').then(m => ({ default: m.ResourceLinkagePage })))
const HikvisionPage = lazy(() => import('@/pages/hikvision').then(m => ({ default: m.HikvisionPage })))
const UnitSettingsPage = lazy(() => import('@/pages/unit-settings').then(m => ({ default: m.UnitSettingsPage })))
const MyProfilePage = lazy(() => import('@/pages/my-profile').then(m => ({ default: m.MyProfilePage })))
const CustomerNotificationSettings = lazy(() => import('@/components/customer-notification-settings').then(m => ({ default: m.CustomerNotificationSettings })))
const MaintenanceWorkOrdersPage = lazy(() => import('@/pages/maintenance-work-orders').then(m => ({ default: m.MaintenanceWorkOrdersPage })))
const FireArchivesPage = lazy(() => import('@/pages/fire-archives').then(m => ({ default: m.FireArchivesPage })))
const PaymentSettingsPage = lazy(() => import('@/pages/payment-settings').then(m => ({ default: m.PaymentSettingsPage })))
const AlarmSessionsPage = lazy(() => import('@/pages/alarm-sessions').then(m => ({ default: m.AlarmSessionsPage })))
const DeviceServicesPage = lazy(() => import('@/pages/device-services').then(m => ({ default: m.DeviceServicesPage })))
const ApplicationsPage = lazy(() => import('@/pages/applications').then(m => ({ default: m.ApplicationsPage })))
const TopologyPage = lazy(() => import('@/pages/topology').then(m => ({ default: m.TopologyPage })))
const VideoControllersPage = lazy(() => import('@/pages/video-controllers').then(m => ({ default: m.VideoControllersPage })))
const PlatformOperationsPage = lazy(() => import('@/pages/platform-operations').then(m => ({ default: m.PlatformOperationsPage })))
const RuleFlowsPage = lazy(() => import('@/pages/rule-flows').then(m => ({ default: m.RuleFlowsPage })))
const RetentionPage = lazy(() => import('@/pages/retention').then(m => ({ default: m.RetentionPage })))
const navigation: { page: PageName; title: string; icon: typeof LayoutDashboard }[] = [
  { page: 'summary', title: '运行总览', icon: LayoutDashboard },
  { page: 'notification-provider', title: '通知通道', icon: MessageSquareText },
  { page: 'notification-resources', title: '通知资源', icon: Coins },
  { page: 'notification-recharge-settings', title: '客户充值设置', icon: Settings2 },
  { page: 'notification-recharge', title: '通知充值', icon: Coins },
  { page: 'devices', title: '设备资产', icon: Radio },
  { page: 'device-services', title: '设备服务', icon: SlidersHorizontal },
  { page: 'device-groups', title: '设备分组', icon: Boxes },
  { page: 'profiles', title: '设备画像', icon: Radio },
  { page: 'alarms', title: '当前报警事件', icon: BellRing },
  { page: 'bulk-io', title: '导入与导出', icon: SlidersHorizontal },
  { page: 'account', title: '消息', icon: MessagesSquare },
  { page: 'distribution', title: '设备分布', icon: SlidersHorizontal },
  { page: 'wall', title: '值守大屏', icon: SlidersHorizontal },
  { page: 'notification-deliveries', title: '通知发送记录', icon: BellRing },
  { page: 'notification-contacts', title: '通知联系人', icon: ContactRound },
  { page: 'suppressions', title: '平台屏蔽配置', icon: ShieldX },
  { page: 'users', title: '用户管理', icon: Users },
  { page: 'units', title: '单位管理', icon: Landmark },
  { page: 'places', title: '单位位置', icon: Building2 },
  { page: 'relations', title: '单位关系', icon: Users },
  { page: 'platform-users', title: '平台用户', icon: ContactRound },
  { page: 'roles', title: '角色管理', icon: KeyRound },
  { page: 'sources', title: '接入来源', icon: Cable },
  { page: 'hikvision', title: '海康云接入', icon: Cable },
  { page: 'legacy-ingest', title: '接入镜像', icon: Radio },
  { page: 'messages', title: '消息追踪', icon: MessagesSquare },
  { page: 'tasks', title: '任务处理', icon: ListTodo },
  { page: 'products', title: '产品模板', icon: Boxes },
  { page: 'parameters', title: '参数覆盖', icon: SlidersHorizontal },
  { page: 'audit-records', title: '日志管理', icon: ListTodo },
  { page: 'settings', title: '管理设置', icon: Settings2 },
  { page: 'duty-overview', title: '值守概览', icon: LayoutDashboard },
  { page: 'parameter-monitoring', title: '数据监测', icon: SlidersHorizontal },
  { page: 'device-monitoring', title: '设备监控', icon: Radio },
  { page: 'alarm-history', title: '报警历史', icon: BellRing },
  { page: 'fault-history', title: '故障历史', icon: ShieldX },
  { page: 'operations-overview', title: '运维概览', icon: LayoutDashboard },
  { page: 'device-faults', title: '设备故障', icon: Unplug },
  { page: 'resource-linkage', title: '资源联动配置', icon: Cable },
  { page: 'alarm-base-config', title: '报警基础配置', icon: Settings2 },
  { page: 'unit-settings', title: '本单位设置', icon: Settings2 },
  { page: 'my-profile', title: '我的', icon: UserRound },
  { page: 'maintenance-work-orders', title: '维保工单', icon: ListTodo },
  { page: 'fire-archives', title: '消防档案', icon: Boxes },
  { page: 'payment-settings', title: '支付与对账', icon: Coins },
  { page: 'alarm-sessions', title: '报警归并', icon: BellRing },
  { page: 'applications', title: '应用与数据输出', icon: Cable },
  { page: 'topology', title: '网关与测点', icon: ListTree },
  { page: 'video-controllers', title: '视频与控制器', icon: Boxes },
  { page: 'releases-migrations', title: '发布与迁移证据', icon: ListTodo },
  { page: 'rule-flows', title: '业务规则', icon: Cable },
  { page: 'data-governance', title: '数据保留', icon: Boxes },
]
const navGroups: { id: string; label: string; icon: typeof LayoutDashboard; items: PageName[]; core: PageName[] }[] = [
  { id: 'overview', label: '总览', icon: LayoutDashboard, core: ['summary'], items: ['summary', 'duty-overview', 'operations-overview', 'wall'] },
  { id: 'assets', label: '设备与接入', icon: Radio, core: ['devices'], items: ['devices', 'device-services', 'device-monitoring', 'parameter-monitoring', 'device-groups', 'distribution', 'topology', 'video-controllers', 'profiles', 'products', 'sources', 'hikvision', 'legacy-ingest', 'applications'] },
  { id: 'events', label: '事件与处置', icon: BellRing, core: ['alarms', 'device-faults'], items: ['alarms', 'device-faults', 'alarm-sessions', 'alarm-history', 'fault-history', 'maintenance-work-orders', 'fire-archives', 'notification-deliveries'] },
  { id: 'organization', label: '组织与权限', icon: Users, core: ['units', 'places', 'users'], items: ['units', 'places', 'users', 'relations', 'roles', 'platform-users', 'unit-settings'] },
  { id: 'notifications', label: '通知与费用', icon: BellRing, core: ['notification-resources', 'notification-contacts'], items: ['notification-resources', 'notification-contacts', 'notification-provider', 'notification-recharge-settings', 'payment-settings', 'suppressions', 'resource-linkage'] },
  { id: 'system', label: '系统设置', icon: Settings2, core: ['settings'], items: ['settings', 'parameters', 'bulk-io', 'audit-records', 'data-governance', 'releases-migrations', 'alarm-base-config', 'rule-flows', 'notification-recharge', 'account', 'messages', 'tasks', 'my-profile'] },
]
const managementPrimary: PageName[] = ['summary', 'alarms', 'devices', 'device-faults']
const managementTitles: Partial<Record<PageName, string>> = { summary: '处置工作台', alarms: '报警信息' }
const customerGroups: typeof navGroups = [
  { id: 'home', label: '首页', icon: LayoutDashboard, core: ['summary'], items: ['summary', 'duty-overview', 'operations-overview'] },
  { id: 'devices', label: '设备', icon: Radio, core: ['devices'], items: ['devices', 'device-services', 'device-monitoring', 'parameter-monitoring', 'device-groups', 'topology', 'video-controllers', 'legacy-ingest'] },
  { id: 'alarms', label: '报警', icon: BellRing, core: ['alarms'], items: ['alarms', 'alarm-sessions', 'alarm-history', 'fault-history', 'device-faults'] },
  { id: 'mine', label: '我的', icon: UserRound, core: ['my-profile'], items: ['my-profile', 'account', 'unit-settings', 'maintenance-work-orders', 'fire-archives', 'bulk-io', 'notification-recharge', 'users'] },
]
const customerTabs: { label: string; page: PageName; group: string; icon: typeof LayoutDashboard }[] = [
  { label: '首页', page: 'summary', group: 'home', icon: LayoutDashboard },
  { label: '设备', page: 'devices', group: 'devices', icon: Radio },
  { label: '报警', page: 'alarms', group: 'alarms', icon: BellRing },
  { label: '我的', page: 'my-profile', group: 'mine', icon: UserRound },
]
export default function App() {
  const user = useSession(s => s.user)
  const generation = useSession(s => s.generation)
  const health = useResource('public-health', signal => api.health(signal))
  const [changingPassword, setChangingPassword] = useState(false)
  useEffect(() => { if (!user) setChangingPassword(false) }, [user])
  if (!user || user.must_change_password || changingPassword) return <AuthScreen key={user?.id ?? 'anonymous'} health={health.data} healthError={health.error} selfChange={!!user && changingPassword} onCancel={() => setChangingPassword(false)}/>
  return <Workspace key={generation} health={health.data} healthError={health.error} onSelfPasswordChange={() => setChangingPassword(true)}/>
}
function Workspace({ health, healthError, onSelfPasswordChange }: { health?: Health; healthError?: unknown; onSelfPasswordChange: () => void }) {
  const { user, audience, permissions, page, navigate, generation } = useSession()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [scopeCollapsed, setScopeCollapsed] = useState(() => window.matchMedia('(max-width: 960px)').matches)
  useEffect(() => {
    const narrowScreen = window.matchMedia('(max-width: 960px)')
    const change = (event: MediaQueryListEvent) => setScopeCollapsed(event.matches)
    narrowScreen.addEventListener('change', change)
    return () => narrowScreen.removeEventListener('change', change)
  }, [])
  const management = audience === 'management'
  const groups = audience === 'customer' ? customerGroups : navGroups
  const groupFor = (target: PageName) => groups.find(group => group.items.includes(target))?.id ?? groups[0].id
  const [pageSearch, setPageSearch] = useState('')
  function secondaryGroup(target: PageName) {
    return groups.find(group => group.items.includes(target) && !(management ? managementPrimary : group.core).includes(target))?.id ?? null
  }
  const [openGroup, setOpenGroup] = useState(() => secondaryGroup(page))
  const [sessionCheck, setSessionCheck] = useState('')
  const revalidate = useCallback(async (manual = false) => {
    const before = useSession.getState(), token = before.token, authGeneration = before.authGeneration
    if (!token) return
    try {
      const current = await api.me()
      const now = useSession.getState()
      if (now.token !== token || now.authGeneration !== authGeneration) return
      const changed = now.revalidate(token, authGeneration, current.user, current.permissions)
      if (changed || manual) setSessionCheck(changed ? '服务端确认授权已变化；旧页面数据与草稿已清除，请重新核验。' : '当前会话与服务端授权已重新核验。')
      else setSessionCheck(previous => previous.startsWith('会话核验未完成') ? '' : previous)
    } catch (error) {
      const now = useSession.getState()
      if (now.token === token && now.authGeneration === authGeneration) setSessionCheck(`会话核验未完成：${errorMessage(error)}`)
    }
  }, [])
  const pageAllowed = useCallback((item: PageName) => allowedPage(item, audience, permissions) && !(item === 'roles' && user?.user_type !== 'PLATFORM'), [audience, permissions, user?.user_type])
  useEffect(() => {
    if (pageAllowed(page)) return
    const fallback = groups.flatMap(group => [...group.core, ...group.items]).find(pageAllowed)
    if (fallback) navigate(fallback)
  }, [pageAllowed, page, groups, navigate])
  useEffect(() => { if (management && window.scrollY > 0) window.scrollTo(0, 0) }, [management, page])
  const canPage = pageAllowed(page)
  const primaryItems = management ? managementPrimary.filter(pageAllowed) : []
  const homeTarget = pageAllowed('summary') ? 'summary' : groups.flatMap(group => [...group.core, ...group.items]).find(pageAllowed)
  const titleFor = (entry: (typeof navigation)[number]) => (management && managementTitles[entry.page]) || entry.title
  const searchTerm = pageSearch.trim().toLocaleLowerCase()
  const searchResults = searchTerm
    ? navigation.filter(item => pageAllowed(item.page) && groups.some(group => group.items.includes(item.page)) && [titleFor(item), item.title].some(title => title.toLocaleLowerCase().includes(searchTerm)))
    : []
  const activeEntry = navigation.find(item => item.page === page)
  const pageTitle = activeEntry && titleFor(activeEntry)
  function choosePage(target: PageName) {
    navigate(target)
    setOpenGroup(secondaryGroup(target))
    setPageSearch('')
    setMobileOpen(false)
  }
  const homeLink = (className: string, compact = false) => <a className={className} href="/" aria-label={`${brand.fullName}首页`} onClick={event => { event.preventDefault(); if (homeTarget) choosePage(homeTarget) }}><span className="brand-icon"><Flame size={compact ? 20 : 24}/></span>{compact ? <span><strong>{brand.name}</strong><small>{audience === 'management' ? '管理平台' : '用户平台'}</small></span> : <span>{brand.name}<small>{brand.description}</small></span>}</a>
  function pageButton(item: PageName, withIcon = false) {
    const definition = navigation.find(entry => entry.page === item)
    if (!definition) return null
    const Icon = definition.icon
    return <button key={item} type="button" className={`nav-item ${page === item ? 'active' : ''}`} onClick={() => choosePage(item)} aria-current={page === item ? 'page' : undefined}>
      {withIcon && <Icon size={18} aria-hidden="true"/>}<span>{titleFor(definition)}</span>{page === item && <span className="nav-active-dot"/>}
    </button>
  }
  const nav = <>{homeLink('brand')}<div className="sidebar-audience"><ShieldCheck size={13}/>{management ? '管理工作空间' : '客户工作空间'}</div>
    {primaryItems.length > 0 && <nav aria-label="一线操作"><div className="nav-section">一线操作</div>{primaryItems.map(item => pageButton(item, true))}</nav>}
    <div className="nav-page-search"><label>搜索页面<Input type="search" aria-label="搜索页面名称" value={pageSearch} onChange={event => setPageSearch(event.target.value)} placeholder="输入页面名称" autoComplete="off"/></label>{pageSearch && <button type="button" className="nav-search-clear" onClick={() => setPageSearch('')}>清除</button>}{pageSearch && (searchResults.length ? <div className="nav-search-results" aria-label="搜索结果">{searchResults.map(item => <button type="button" key={item.page} className={`nav-item ${page === item.page ? 'active' : ''}`} onClick={() => choosePage(item.page)}>{titleFor(item)}<small>{groups.find(group => group.items.includes(item.page))?.label}</small></button>)}</div> : <p className="nav-search-empty" role="status">没有匹配的可访问页面</p>)}</div>
    <nav aria-label="主导航">{groups.map(group => {
    const items = group.items.filter(item => pageAllowed(item) && (!management || !managementPrimary.includes(item)))
    if (!items.length) return null
    const coreItems = management ? [] : group.core.filter(pageAllowed)
    const secondaryItems = items.filter(item => !coreItems.includes(item))
    const GroupIcon = group.icon
    const isOpen = openGroup === group.id
    const isActive = items.includes(page)
    return <div className="nav-group-wrap" key={group.id}>
      {secondaryItems.length ? <button type="button" className={`nav-group ${isActive ? 'active' : ''} ${isOpen ? 'open' : ''}`} onClick={() => setOpenGroup(previous => previous === group.id ? null : group.id)} aria-expanded={isOpen}><GroupIcon size={18}/><span>{group.label}</span><ChevronDown size={14} className="nav-chevron"/></button> : <div className="nav-group"><GroupIcon size={18}/><span>{group.label}</span></div>}
      {coreItems.length > 0 && <div className="nav-core">{coreItems.map(item => pageButton(item))}</div>}
      {secondaryItems.length > 0 && isOpen && <div className="nav-sub">{secondaryItems.map(item => pageButton(item))}</div>}
    </div>
  })}</nav><div className="sidebar-bottom"><div className="sidebar-version"><span className="status-dot"/>自托管部署</div></div></>
  async function logout() {
    try { await api.logout(); useSession.getState().clear('您已安全退出。') }
    catch (error) { useSession.getState().clear(`本地凭证已清除，服务端退出未确认：${errorMessage(error)}`) }
  }
  return <div className={`app-shell${audience === 'customer' ? ' customer-shell' : scopeCollapsed ? ' scope-rail-collapsed' : ' scope-rail-expanded'}`}>
    <aside className="sidebar desktop-sidebar">{nav}</aside>
    {audience === 'management' && <>
      {!scopeCollapsed && <button type="button" className="scope-rail-backdrop" aria-label="关闭单位/位置筛选栏" onClick={() => setScopeCollapsed(true)}/>}
      <aside className="scope-rail" aria-label="单位/位置筛选">
        <Button type="button" variant="ghost" className="scope-rail-toggle" aria-label={scopeCollapsed ? '展开单位/位置筛选栏' : '收起单位/位置筛选栏'} aria-expanded={!scopeCollapsed} aria-controls="workspace-scope-tree" title={scopeCollapsed ? '展开单位/位置筛选栏' : '收起单位/位置筛选栏'} onClick={() => setScopeCollapsed(current => !current)}>
          {scopeCollapsed ? <ChevronRight size={16}/> : <><span>单位/位置筛选</span><ChevronLeft size={16}/></>}
        </Button>
        {scopeCollapsed && <span className="scope-rail-caption" aria-hidden="true">单位/位置</span>}
        <div id="workspace-scope-tree" className="scope-rail-body" hidden={scopeCollapsed}><ScopeTreePanel/></div>
      </aside>
    </>}
    <div className="main-shell"><header className="topbar">
      <div className="topbar-left">
        {homeLink('topbar-brand', true)}
        <Sheet open={mobileOpen} onOpenChange={open => { setMobileOpen(open); if (open) setScopeCollapsed(true) }}><SheetTrigger asChild><Button variant="ghost" size="icon" className="mobile-nav-trigger" aria-label="打开导航"><Menu/></Button></SheetTrigger><SheetContent side="left" className="mobile-sidebar"><SheetHeader className="sr-only"><SheetTitle>平台导航</SheetTitle><SheetDescription>选择工作空间页面</SheetDescription></SheetHeader>{nav}</SheetContent></Sheet>
        {audience === 'management' && <Button type="button" variant="ghost" size="icon" className="mobile-scope-trigger" aria-label={scopeCollapsed ? '打开单位/位置筛选' : '收起单位/位置筛选'} aria-expanded={!scopeCollapsed} aria-controls="workspace-scope-tree" onClick={() => setScopeCollapsed(current => !current)}><ListTree size={18}/></Button>}
        <div className="breadcrumb">{audience === 'management' ? '管理工作空间' : '用户工作空间'}<ChevronRight size={14}/><span>{pageTitle}</span></div>
      </div>
      <div className="topbar-right"><Button type="button" variant="ghost" size="icon-sm" onClick={() => void revalidate(true)} aria-label="重新核验当前会话" title="重新核验当前会话。服务端逐请求校验；离线已显示内容无法由浏览器撤回。"><RefreshCw size={16}/></Button><span className="header-divider"/><div className="user-avatar"><UserRound size={18}/></div><div className="user-info"><strong>{user?.name || user?.username}</strong><small>{audience === 'management' ? '平台账户' : '客户账户'}</small></div><Button variant="ghost" size="icon-sm" onClick={onSelfPasswordChange} aria-label="修改自己的密码" title="修改自己的密码"><KeyRound size={17}/></Button><Button variant="ghost" size="icon-sm" onClick={() => void logout()} aria-label="退出登录" title="退出登录"><LogOut size={17}/></Button></div>
    </header>
    {sessionCheck && <div className="session-revalidation"><span role="status">{sessionCheck}</span><Button type="button" variant="ghost" size="sm" onClick={() => setSessionCheck('')} aria-label="关闭会话核验提示">知道了</Button></div>}
    {audience === 'customer' && <Suspense fallback={null}><CustomerAlarmMonitor/></Suspense>}
    <main id="main-content" className="content">{healthError != null && <ErrorNotice error={healthError}/>}
      {!canPage ? <div className="access-denied"><ShieldX size={35}/><h1>此页面暂不可访问</h1><p>当前账户未获服务端权限。</p></div> : <AnimatePresence mode="wait"><motion.div key={generation} initial={{opacity:0}} animate={{opacity:1}} exit={{opacity:0}}><RenderFailureBoundary key={`${generation}:${page}`} onExit={page !== 'summary' && pageAllowed('summary') ? () => navigate('summary') : undefined}><AuthorizedReadScope><Suspense fallback={<Loading/>}>{(() => {
        switch (page) {
          case 'maintenance-work-orders': return <MaintenanceWorkOrdersPage/>
          case 'fire-archives': return <FireArchivesPage/>
          case 'payment-settings': return <PaymentSettingsPage/>
          case 'alarm-sessions': return <AlarmSessionsPage/>
          case 'device-services': return <DeviceServicesPage/>
          case 'applications': return <ApplicationsPage/>
          case 'topology': return <TopologyPage/>
          case 'video-controllers': return <VideoControllersPage/>
          case 'releases-migrations': return <PlatformOperationsPage/>
          case 'rule-flows': return <RuleFlowsPage/>
          case 'data-governance': return <RetentionPage/>
          case 'duty-overview': return <DutyOverviewPage/>
          case 'parameter-monitoring': return management ? <ParameterMonitoringPage/> : <CustomerDevicesWorkspace><ParameterMonitoringPage/></CustomerDevicesWorkspace>
          case 'device-monitoring': return management ? <DeviceMonitoringPage/> : <CustomerDevicesWorkspace><DeviceMonitoringPage/></CustomerDevicesWorkspace>
          case 'alarm-history': return management ? <AlarmHistoryPage/> : <CustomerAlarmsWorkspace><AlarmHistoryPage/></CustomerAlarmsWorkspace>
          case 'fault-history': return management ? <FaultHistoryPage/> : <CustomerAlarmsWorkspace><FaultHistoryPage/></CustomerAlarmsWorkspace>
          case 'operations-overview': return <OperationsOverviewPage/>
          case 'device-faults': return <DeviceFaultsPage/>
          case 'alarm-base-config': return <AlarmBaseConfigurationPage/>
          case 'resource-linkage': return <ResourceLinkagePage/>
          case 'summary': return management ? <ManagementHome onNavigate={choosePage} overview={<Dashboard/>}/> : <CustomerHome/>
          case 'devices': return management ? <DevicesPage/> : <CustomerDevicesWorkspace><DevicesPage/></CustomerDevicesWorkspace>
          case 'alarms': return management ? <DutyWorkbenchPage/> : <CustomerAlarmsWorkspace><DutyWorkbenchPage/></CustomerAlarmsWorkspace>
          case 'settings': return <SettingsPage/>
          case 'units': return <UnitsPage/>
          case 'places': return <PlacesPage/>
          case 'relations': return <RelationsPage/>
          case 'users': return <UsersPage platform={false}/>
          case 'platform-users': return <UsersPage platform/>
          case 'roles': return <RolesPage/>
          case 'products': return <ProductsPage/>
          case 'sources': return <SourcesPage health={health}/>
          case 'hikvision': return <HikvisionPage/>
          case 'unit-settings': return <UnitSettingsPage notifications={<CustomerNotificationSettings/>}/>
          case 'my-profile': return <MyProfilePage onPasswordChange={onSelfPasswordChange} onLogout={() => void logout()}/>
          case 'messages': return <MessagesPage health={health}/>
          case 'tasks': return <TasksPage/>
          case 'parameters': return <ParametersPage/>
          case 'suppressions': return <SuppressionsPage/>
          case 'profiles': return <ProfilesPage/>
          case 'bulk-io': return <BulkIoPage/>
          case 'account': return <AccountPage/>
          case 'distribution': return <DistributionPage/>
          case 'wall': return <WallPage/>
          case 'notification-contacts': return <NotificationContactsPage/>
          case 'notification-provider': return <NotificationProviderPage/>
          case 'notification-resources': return <NotificationResourcesPage/>
          case 'notification-recharge-settings': return <NotificationRechargeSettingsPage/>
          case 'notification-recharge': return <NotificationRechargePage/>
          case 'notification-deliveries': return <NotificationDeliveriesPage/>
          case 'audit-records': return <AuditRecordsPage/>
          case 'device-groups': return <DeviceGroupsPage/>
          case 'legacy-ingest': return <LegacyIngestPage/>
          default: return <Empty title="页面未启用"/>
        }
      })()}</Suspense></AuthorizedReadScope></RenderFailureBoundary></motion.div></AnimatePresence>}
    </main><footer className="workspace-footer"><span>{brand.fullName}</span><span><Unplug size={12}/>事件记录不替代现场核查与应急处置</span></footer></div>
    {audience === 'customer' && <nav className="customer-bottom-nav" aria-label="用户端快捷导航">{customerTabs.map(tab => { const Icon = tab.icon; const active = groupFor(page) === tab.group; return <button key={tab.group} type="button" aria-current={active ? 'page' : undefined} disabled={!pageAllowed(tab.page)} onClick={() => { navigate(tab.page); setMobileOpen(false) }}><Icon size={19}/><span>{tab.label}</span></button> })}</nav>}
  </div>
}
