import type { ReactNode } from 'react'
import { useSession } from '@/lib/store'
import { allowedPage, type PageName } from '@/lib/types'
import { Empty } from './common'

export function NotificationGate({ page, children }: { page: PageName; children: ReactNode }) {
  const { user, audience, permissions, generation } = useSession()
  if (!user || user.must_change_password || !allowedPage(page, audience, permissions)) return <Empty title="当前账户无此通知页面权限"/>
  return <section key={generation}>{children}</section>
}
