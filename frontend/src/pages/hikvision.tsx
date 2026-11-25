import { lazy, Suspense, useState } from 'react'
import { Tabs } from 'antd'
import { HikvisionConfiguration } from '@/components/hikvision-configuration'
import { Loading, PageHeading } from '@/components/common'
import { Button } from '@/components/ui/button'

const HikvisionCallback = lazy(() => import('@/components/hikvision-callback').then(module => ({ default: module.HikvisionCallback })))
const HikvisionAssociationSheet = lazy(() => import('@/components/hikvision-pending-devices').then(module => ({ default: module.HikvisionAssociationSheet })))

export function HikvisionPage({ openAssetAssociations }: { openAssetAssociations?: () => void }) {
  const [associationOpen, setAssociationOpen] = useState(false)
  const [activeTab, setActiveTab] = useState('configuration')
  return <>
    <PageHeading eyebrow="HIKVISION · 云端数据接入" title="海康云接入" description="维护海康接口设置，查看事件推送与接收诊断。">
      <Button type="button" variant="outline" onClick={openAssetAssociations ?? (() => setAssociationOpen(true))}>查看待关联设备</Button>
    </PageHeading>
    <Tabs className="page-tabs" activeKey={activeTab} onChange={setActiveTab} destroyOnHidden={false} items={[
      { key: 'configuration', label: '接口设置', children: <HikvisionConfiguration/> },
      { key: 'callback', label: '事件推送', children: activeTab === 'callback' ? <Suspense fallback={<Loading/>}><HikvisionCallback/></Suspense> : null },
    ]}/>
    {associationOpen && <Suspense fallback={<Loading/>}><HikvisionAssociationSheet onClose={() => setAssociationOpen(false)}/></Suspense>}
  </>
}
