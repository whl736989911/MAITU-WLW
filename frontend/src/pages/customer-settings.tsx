import { useState } from 'react'
import { CustomerDeviceCard } from '@/components/customer-device-ledger'
import { CustomerRecordPager, CustomerRecordsSearch, CustomerRecordTable } from '@/components/customer-records'
import { CustomerPlaceManager } from '@/components/customer-place-manager'
import { CustomerNotificationSettings } from '@/components/customer-notification-settings'
import { ControllerResourcesPanel } from '@/components/controller-resources-panel'
import { useLegacyUnits } from '@/components/customer-workspace'
import { WechatSelfBinding } from '@/components/wechat-binding'
import { Empty, ErrorNotice, Loading } from '@/components/common'
import { accessApi } from '@/lib/access-roles'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'

export function CustomerSettings({ view, changePassword }: { view: 'unit' | 'places' | 'contacts' | 'controllers' | 'roles' | 'wechat' | 'password'; changePassword: () => void }) {
  const { audience, user, permissions } = useSession()
  if (audience !== 'customer' || !user?.tenant_id || user.must_change_password) return <Empty title="此入口不可访问"/>
  return <section className="legacy-record-page">
    {view === 'unit' && <UnitInfo tenantId={user.tenant_id}/>}
    {view === 'places' && <CustomerPlaceManager/>}
    {view === 'contacts' && <CustomerNotificationSettings appearance="legacy"/>}
    {view === 'controllers' && (permissions.includes('controller:read') ? <ControllerResourcesPanel tenantId={user.tenant_id} canWrite={false}
      canReadDevices={permissions.includes('device:read')} canReadPlaces={permissions.includes('units:read')}/> : <Empty title="当前账号无控制器读取权限"/>)}
    {view === 'roles' && (permissions.includes('roles:read') ? <CustomerRoles/> : <Empty title="当前账号无角色读取权限"/>)}
    {view === 'wechat' && <div className="legacy-wechat-panels"><CustomerDeviceCard title="微信公众号二维码"><WechatSelfBinding/></CustomerDeviceCard>
      <CustomerDeviceCard title="如何绑定步骤说明"><ol><li>打开本人授权二维码，用微信扫码。</li><li>回到本页刷新并核对账号，再确认绑定。</li></ol></CustomerDeviceCard></div>}
    {view === 'password' && <CustomerDeviceCard title="修改密码"><p>修改当前登录账号的密码。</p><button type="button" className="legacy-device-button" onClick={changePassword}>修改密码</button></CustomerDeviceCard>}
  </section>
}

function UnitInfo({ tenantId }: { tenantId: string }) {
  const permissions = useSession(s => s.permissions), navigate = useSession(s => s.navigate)
  const unit = useLegacyUnits(), name = unit?.data?.find(item => item.id === tenantId)?.name
  const balances = useResource(`legacy-unit-resources:${tenantId}`, signal => api.customerRechargeAccounts(signal), permissions.includes('notification-recharge:read'))
  const account = balances.data?.find(item => item.owner_type === 'ORGANIZATION' && item.owner_id === tenantId)
  const fields = [
    ['单位名称', name],
    ...(import.meta.env.VITE_CUSTOMER_PLATFORM_TITLE ? [['平台标题', import.meta.env.VITE_CUSTOMER_PLATFORM_TITLE]] : []),
    ...(account ? [['语音资源', `${account.voice_remaining} 次`], ['短信资源', `${account.sms_remaining} 条`]] : []),
  ]
  return <CustomerDeviceCard title="单位信息" action={permissions.includes('unit-parameters:read') ?
    <button type="button" className="legacy-device-button" onClick={() => navigate('customer-unit-parameters')}>单位业务参数</button> : undefined}>
    {unit?.loading ? <Loading/> : unit?.error != null ? <ErrorNotice error={unit.error} retry={unit.refresh}/> : <dl className="legacy-device-fields legacy-unit-grid">
      {fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}
    </dl>}
    {balances.error != null && <ErrorNotice error={balances.error} retry={balances.refresh}/>}
    <p className="muted-note">仅展示当前账号可读的单位资料和资源余额。</p>
  </CustomerDeviceCard>
}

function CustomerRoles() {
  const [draft, setDraft] = useState(''), [keyword, setKeyword] = useState(''), [page, setPage] = useState(1), [size, setSize] = useState(10)
  const records = useResource(`legacy-customer-roles:${page}:${size}:${keyword}`, signal => accessApi.customerRoles(page, size, signal, keyword))
  return <>
    <CustomerRecordsSearch field="角色名称" keyword={draft} onKeyword={setDraft} onSearch={e => { e.preventDefault(); setPage(1); setKeyword(draft.trim()) }}
      onReset={() => { setDraft(''); setKeyword(''); setPage(1) }}/>
    <CustomerRecordTable name="角色管理" columns={['角色名称', '唯一编码', '角色状态']} rows={(records.data?.items ?? []).map(item => ({
      key: item.id, cells: [item.name, item.code, item.enabled ? '启用' : '停用'],
    }))} loading={records.loading} error={records.error} retry={records.refresh}/>
    {records.data && <CustomerRecordPager page={records.data.page} size={records.data.size} total={records.data.total} onPage={setPage} onSize={next => { setSize(next); setPage(1) }}/>}
  </>
}
