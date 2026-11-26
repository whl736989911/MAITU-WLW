import { useState } from 'react'
import { useSession } from '@/lib/store'
import { Empty, PageHeading } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
import { ConfigurationUnitTarget } from '@/components/configuration-unit-target'
import { VideoResourcesPanel } from '@/components/video-resources-panel'
import { ControllerResourcesPanel } from '@/components/controller-resources-panel'
import { Button } from '@/components/ui/button'

const PAGE = 'video-controllers'

export function VideoControllersPage() {
  const s = useSession()
  const canVideo = s.permissions.includes('video:read')
  const canControllers = s.permissions.includes('controller:read')
  if (!s.token || s.user?.must_change_password || String(s.page) !== PAGE || !(canVideo || (s.audience === 'management' && canControllers)))
    return <Empty title="当前无视频或控制器读取权限"/>
  const canPlay = canVideo && s.permissions.includes('video:play')
  if (s.audience === 'customer') {
    const tenantId = s.user?.tenant_id
    if (!tenantId) return <Empty title="当前账户未绑定单位"/>
    return <Content key={`${s.authGeneration}:${tenantId}:read`} tenantId={tenantId}
      canVideo={canVideo} canControllers={false} canPlay={canPlay}
      videoWrite={false} controllerWrite={false}
      canReadDevices={s.permissions.includes('device:read')} canReadPlaces={s.permissions.includes('units:read')}/>
  }
  return <ConfigurationUnitTarget>{tenantId => <Content key={`${s.authGeneration}:${tenantId}`} tenantId={tenantId}
    canVideo={canVideo} canControllers={canControllers} canPlay={canPlay}
    videoWrite={s.permissions.includes('video:write')} controllerWrite={s.permissions.includes('controller:write')}
    canReadDevices={s.permissions.includes('device:read')} canReadPlaces={s.permissions.includes('units:read')}/>}</ConfigurationUnitTarget>
}

function Content({ tenantId, canVideo, canControllers, canPlay, videoWrite, controllerWrite, canReadDevices, canReadPlaces }: {
  tenantId: string; canVideo: boolean; canControllers: boolean; canPlay: boolean; videoWrite: boolean; controllerWrite: boolean
  canReadDevices: boolean; canReadPlaces: boolean
}) {
  const legacyVideoOnly = useSession(s => s.audience === 'customer') && canVideo && !canControllers
  const [tab, setTab] = useState<'VIDEO' | 'CONTROLLER'>(canVideo ? 'VIDEO' : 'CONTROLLER')
  return <>
    {!legacyVideoOnly && <PageHeading eyebrow="设备与产品 · 视频与联动控制器" title="视频资源与联动控制器"
      description="保留真实视频资源、关联和控制器自动/声光联动配置；播放和动作只按真实协议、授权与能力执行，不用空壳或假成功替代。">
      <PageHelp title="能力与边界">
        <div className="form-stack">
          <p>视频端点/签名地址/凭据只写；播放与抓图经同源受权代理，RTSP 在无转封装网关时明确未实现。</p>
          <p>控制器关联必须指向真实设备上已发布的服务能力；只有已验证的 NORMAL 类型化 COMMAND 才能启用自动联动，工程操作不能由规则发起。</p>
          <p>入队命令不代表设备已执行；物理结果以设备服务队列的厂商回执为准。</p>
        </div>
      </PageHelp>
    </PageHeading>}
    {(canVideo && canControllers) && <div className="list-toolbar" role="tablist" aria-label="视频与控制器标签">
      <Button type="button" role="tab" aria-selected={tab === 'VIDEO'} variant={tab === 'VIDEO' ? 'default' : 'outline'} onClick={() => setTab('VIDEO')}>视频资源</Button>
      <Button type="button" role="tab" aria-selected={tab === 'CONTROLLER'} variant={tab === 'CONTROLLER' ? 'default' : 'outline'} onClick={() => setTab('CONTROLLER')}>联动控制器</Button>
    </div>}
    {tab === 'VIDEO' && canVideo && <VideoResourcesPanel tenantId={tenantId} canWrite={videoWrite} canPlay={canPlay} canReadDevices={canReadDevices} canReadPlaces={canReadPlaces}/>}
    {tab === 'CONTROLLER' && canControllers && <ControllerResourcesPanel tenantId={tenantId} canWrite={controllerWrite} canReadDevices={canReadDevices} canReadPlaces={canReadPlaces}/>}
  </>
}
