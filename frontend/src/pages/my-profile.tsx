import { ChevronRight, Coins, Inbox, KeyRound, LogOut, Settings2, SlidersHorizontal, UserRound, type LucideIcon } from 'lucide-react'
import { useSession } from '@/lib/store'
import { allowedPage, type PageName } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { WechatSelfBinding } from '@/components/wechat-binding'
import { CustomerUnitSummary } from '@/customer/unit-summary'
import '../customer/customer.css'

export function MyProfilePage({ onPasswordChange, onLogout }: { onPasswordChange: () => void; onLogout: () => void }) {
  const { user, audience, permissions, navigate } = useSession()
  if (audience !== 'customer' || !user) return null
  const actions: { label: string; hint: string; icon: LucideIcon; page: PageName }[] = []
  if (allowedPage('account', audience, permissions)) actions.push({ label: '消息与资料', hint: '站内消息、个人资料与登录会话', icon: Inbox, page: 'account' })
  if (allowedPage('unit-settings', audience, permissions)) actions.push({ label: '本单位设置', hint: '位置、业务参数与通知', icon: Settings2, page: 'unit-settings' })
  if (allowedPage('notification-recharge', audience, permissions)) actions.push({ label: '通知充值与余额', hint: '短信/语音次数与充值记录', icon: Coins, page: 'notification-recharge' })
  if (allowedPage('bulk-io', audience, permissions)) actions.push({ label: '导入导出与任务', hint: '批量导入与导出任务', icon: SlidersHorizontal, page: 'bulk-io' })
  return <div className="customer-hub">
    <header className="customer-page-head customer-person">
      <div className="customer-person-icon"><UserRound size={26} aria-hidden="true"/></div>
      <div><span className="customer-kicker">我的</span><h1>{user.name || user.username}</h1><p>客户账号 · {user.username}</p></div>
    </header>
    <div className="customer-profile-layout">
      <section className="customer-surface" aria-labelledby="customer-my-actions">
        <div className="customer-section-head"><h2 id="customer-my-actions">常用操作</h2></div>
        <ul className="customer-records">
          {actions.map(({ page, label, hint, icon: Icon }) => <li key={page}><button type="button" className="customer-record-button" onClick={() => navigate(page)}>
            <span className="customer-record-icon"><Icon size={18} aria-hidden="true"/></span>
            <span className="customer-record-label"><strong>{label}</strong><small>{hint}</small></span><ChevronRight size={17} aria-hidden="true"/>
          </button></li>)}
          <li><button type="button" className="customer-record-button" onClick={onPasswordChange}>
            <span className="customer-record-icon"><KeyRound size={18} aria-hidden="true"/></span>
            <span className="customer-record-label"><strong>修改本人密码</strong><small>变更当前账户登录密码</small></span><ChevronRight size={17} aria-hidden="true"/>
          </button></li>
        </ul>
      </section>
      <div className="customer-account-stack">
        <CustomerUnitSummary/>
        {permissions.includes('inbox:read') && <WechatSelfBinding/>}
      </div>
    </div>
    <div className="customer-controls"><Button type="button" variant="outline" onClick={onLogout}><LogOut/>退出登录</Button></div>
  </div>
}
