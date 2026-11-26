import { Card, Descriptions, Space } from 'antd'
import { useSession } from '@/lib/store'
import { allowedPage } from '@/lib/types'
import { PageHeading } from '@/components/common'
import { Button } from '@/components/ui/button'
import { WechatSelfBinding } from '@/components/wechat-binding'

export function MyProfilePage({ onPasswordChange, onLogout }: { onPasswordChange: () => void; onLogout: () => void }) {
  const { user, audience, permissions, navigate } = useSession()
  if (!user) return null
  return <section className="unit-settings-grid"><PageHeading eyebrow="MY · 个人工作空间" title="我的" description="账号信息来自当前有效会话；单位设置和数据操作只按明确授权提供。"/>
    <Card title="当前账号"><Descriptions column={1} items={[
      { key: 'name', label: '姓名', children: user.name },
      { key: 'username', label: '账号', children: user.username },
      { key: 'unit', label: '所属单位标识', children: user.tenant_id ?? '平台账号' },
    ]}/></Card>
    <Card title="常用操作"><Space wrap>
      {allowedPage('account', audience, permissions) && <Button variant="outline" onClick={() => navigate('account')}>我的资料与消息</Button>}
      {allowedPage('unit-settings', audience, permissions) && <Button variant="outline" onClick={() => navigate('unit-settings')}>本单位设置</Button>}
      {allowedPage('notification-recharge', audience, permissions) && <Button variant="outline" onClick={() => navigate('notification-recharge')}>通知充值与余额</Button>}
      {allowedPage('bulk-io', audience, permissions) && <Button variant="outline" onClick={() => navigate('bulk-io')}>导入导出与任务</Button>}
      <Button variant="outline" onClick={onPasswordChange}>修改本人密码</Button>
      <Button variant="destructive" onClick={onLogout}>安全退出</Button>
    </Space></Card>
    {permissions.includes('inbox:read') && <WechatSelfBinding/>}
  </section>
}
