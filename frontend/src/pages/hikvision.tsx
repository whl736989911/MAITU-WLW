import { lazy, Suspense, useState } from 'react'
import { Tabs } from 'antd'
import { HikvisionConfiguration } from '@/components/hikvision-configuration'
import { HikvisionPendingDevices } from '@/components/hikvision-pending-devices'
import { Loading, PageHeading } from '@/components/common'

const HikvisionCallback = lazy(() => import('@/components/hikvision-callback').then(module => ({ default: module.HikvisionCallback })))
const DeviceDetail = lazy(() => import('@/components/device-detail').then(module => ({ default: module.DeviceDetail })))

export function HikvisionPage() {
  const [device, setDevice] = useState<{ id: string; tenant_id?: string } | null>(null)
  const [activeTab, setActiveTab] = useState('configuration')
  return <>
    <PageHeading eyebrow="HIKVISION · 云端数据接入" title="海康云接入" description="维护海康连接配置，核对发现设备并建立归属关联。"/>
    <Tabs className="page-tabs" activeKey={activeTab} onChange={setActiveTab} destroyOnHidden={false} items={[
      { key: 'configuration', label: '连接配置', children: <HikvisionConfiguration/> },
      { key: 'discovery', label: '发现与归属', children: <HikvisionPendingDevices openDevice={setDevice}/> },
      { key: 'callback', label: '事件推送', children: activeTab === 'callback' ? <Suspense fallback={<Loading/>}><HikvisionCallback/></Suspense> : null },
    ]}/>
    {device && <Suspense fallback={<Loading/>}><DeviceDetail deviceId={device.id} tenantId={device.tenant_id} close={() => setDevice(null)}/></Suspense>}
  </>
}
