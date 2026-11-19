import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { Activity, BellRing, ChevronRight, ClipboardList, History, Radio, SlidersHorizontal } from 'lucide-react'
import { useSession } from '@/lib/store'
import { allowedPage, type PageName } from '@/lib/types'
import './customer.css'

type Link = { page: PageName; label: string; icon: LucideIcon }
const deviceLinks: Link[] = [
  { page: 'devices', label: '设备台账', icon: Radio },
  { page: 'device-monitoring', label: '状态监测', icon: Activity },
  { page: 'parameter-monitoring', label: '参数监测', icon: SlidersHorizontal },
]
const alarmLinks: Link[] = [
  { page: 'alarms', label: '报警工作台', icon: BellRing },
  { page: 'alarm-history', label: '报警历史', icon: History },
  { page: 'fault-history', label: '故障历史', icon: ClipboardList },
]

export function CustomerDevicesWorkspace({ children }: { children: ReactNode }) {
  return <WorkspaceLinks label="设备入口" links={deviceLinks}>{children}</WorkspaceLinks>
}

export function CustomerAlarmsWorkspace({ children }: { children: ReactNode }) {
  return <WorkspaceLinks label="报警入口" links={alarmLinks}>{children}</WorkspaceLinks>
}

function WorkspaceLinks({ label, links, children }: { label: string; links: Link[]; children: ReactNode }) {
  const { audience, permissions, page, navigate } = useSession()
  const visible = links.filter(link => allowedPage(link.page, audience, permissions))
  return <div className="customer-hub customer-workspace">
    {visible.length > 1 && <nav className="customer-route-links" aria-label={label}>
      {visible.map(({ page: target, label: title, icon: Icon }) => <button key={target} type="button" aria-current={page === target ? 'page' : undefined} onClick={() => navigate(target)}>
        <Icon size={18} aria-hidden="true"/><span>{title}</span>{page !== target && <ChevronRight size={15} aria-hidden="true"/>}
      </button>)}
    </nav>}
    <div className="customer-workspace-body">{children}</div>
  </div>
}
