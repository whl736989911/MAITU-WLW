import { Tabs } from 'antd'
import { useSession } from '@/lib/store'
import { NotificationGate } from '@/components/notification-common'
import { NotificationChannelPolicyView } from '@/components/notification-channel-policy'
import { NotificationConfiguration } from '@/components/notification-settings'
import { NotificationRules } from '@/components/notification-rules'
import { DataTransferActions } from '@/components/data-transfer-actions'
import './notification-contacts.css'
export function NotificationContactsPage() {
  const session = useSession()
  const filters = { unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(',') }
  return <section className="notification-contacts-workspace" aria-label="通知联系人工作区">
    <NotificationGate page="notification-contacts">
      <Tabs className="page-tabs" destroyOnHidden={false} tabBarExtraContent={<div className="notification-contact-transfers"><DataTransferActions kind="CONTACTS" label="联系人" filters={filters}/><DataTransferActions kind="RULE_DRAFTS" label="规则草稿" filters={filters}/></div>} items={[
        { key: 'contacts', label: '通知联系人', children: <NotificationConfiguration/> },
        { key: 'rules', label: '报警与故障通知规则', children: <NotificationRules canRead={session.permissions.includes('notification-config:read')} canWrite={session.permissions.includes('notification-config:write')}/> },
        { key: 'channel-policy', label: '短信/语音通道策略', children: <NotificationChannelPolicyView canRead={session.permissions.includes('notification-config:read')} canWrite={session.permissions.includes('notification-config:write')}/> },
      ]}/>
    </NotificationGate>
  </section>
}
