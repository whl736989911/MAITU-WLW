import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Coins, Trash2, Users } from 'lucide-react'
import { api, errorMessage } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { placePath } from '@/lib/place-tree'
import { publishObjectChange } from '@/lib/object-events'
import type { NotificationRechargeGrant, NotificationRechargeMode } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { TargetUnitSelect } from '@/components/asset-picker'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

export function NotificationRechargeSettingsPage() {
  const session = useSession(), { user, audience, permissions } = session
  if (!user || audience !== 'management' || !permissions.includes('notification-resources:read')) return <Empty title="当前账户无客户充值设置权限"/>
  return <SettingsContent key={session.generation}/>
}
function SettingsContent() {
  const permissions = useSession(s => s.permissions)
  const canReadUsers = useSession(s => s.permissions.includes('users:read'))
  const [targetUnit, setTargetUnit] = useState('')
  const canWrite = permissions.includes('notification-resources:write') && !!targetUnit
  const scopeTenantIds = useSession(s => s.scopeTenantIds), scopePlaceIds = useSession(s => s.scopePlaceIds), scopeKey = useSession(s => `${s.scopeTenantIds.join(',')}|${s.scopePlaceIds.join(',')}|${s.scopeInitialized}`)
  const [policyOverviewPage, setPolicyOverviewPage] = useState(1), [grantOverviewPage, setGrantOverviewPage] = useState(1)
  const [userPage, setUserPage] = useState(1), [userKeyword, setUserKeyword] = useState(''), [userDraft, setUserDraft] = useState('')
  const policyOverview = useResource(`recharge-policy-overview:${scopeKey}:${policyOverviewPage}`, signal => api.notificationRechargePolicies(policyOverviewPage, scopeTenantIds, scopePlaceIds, signal))
  const grantOverview = useResource(`recharge-grant-overview:${scopeKey}:${grantOverviewPage}`, signal => api.notificationRechargeGrantOverview(grantOverviewPage, scopeTenantIds, scopePlaceIds, signal))
  const policy = useResource(`recharge-policy:${targetUnit || 'none'}`, signal => api.notificationRechargePolicy(signal, targetUnit || undefined), !!targetUnit)
  const grants = useResource(`recharge-grants:${targetUnit || 'none'}`, signal => api.notificationRechargeGrants(signal, targetUnit || undefined), !!targetUnit)
  const users = useResource(`recharge-users:${targetUnit || 'none'}:${userPage}:${userKeyword}`, signal => api.users(false, userPage, userKeyword, signal, targetUnit ? [targetUnit] : [], [], true), !!targetUnit && canReadUsers)
  const places = useResource(`recharge-places:${targetUnit || 'none'}`, signal => api.places(signal, targetUnit ? [targetUnit] : [], []), !!targetUnit)
  const [mode, setMode] = useState<NotificationRechargeMode>('ORGANIZATION'), [revision, setRevision] = useState(0)
  const [userId, setUserId] = useState(''), [ownerType, setOwnerType] = useState<'ORGANIZATION'|'PLACE'>('ORGANIZATION'), [ownerId, setOwnerId] = useState('')
  const [notice, setNotice] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  useEffect(() => { if (policy.data) { setMode(policy.data.mode); setRevision(policy.data.revision) } }, [policy.data])
  const customerUsers = useMemo(() => (users.data?.items ?? []).filter(item => item.user_type === 'CUSTOMER' && item.tenant_id === targetUnit), [users.data, targetUnit])
  const placeChoices = useMemo(() => {
    const current = (places.data ?? []).filter(place => place.tenant_id === targetUnit)
    return current.map(place => ({ id: place.id, label: placePath(place, current) })).sort((a, b) => a.label.localeCompare(b.label))
  }, [places.data, targetUnit])
  const selectedCustomer = customerUsers.find(item => item.id === userId)
  const selectedPlace = placeChoices.find(place => place.id === ownerId)
  function accountLabel(grant: Pick<NotificationRechargeGrant, 'owner_type' | 'owner_id' | 'owner_name'>) { return grant.owner_type === 'PLACE' ? placeChoices.find(place => place.id === grant.owner_id)?.label ?? grant.owner_name : grant.owner_name }
  function searchUsers() { if (busy) return; setUserKeyword(userDraft.trim()); setUserPage(1); setUserId('') }
  function userPageChanged(page: number) { if (busy) return; setUserPage(page); setUserId('') }
  async function savePolicy(event: FormEvent) {
    event.preventDefault(); if (!canWrite || !targetUnit || busy) return; setError(''); setNotice('')
    if (!window.confirm(`保存充值模式：${mode === 'ORGANIZATION' ? '默认充值到单位（该单位所有客户用户可选）' : '按用户授权（仅被明确授权的账户可选）'}。仅改变客户可选充值账户，不改变已有订单或退款。继续？`)) return
    setBusy(true)
    try { const saved = await api.saveNotificationRechargePolicy({ mode, expected_revision: revision }, undefined, targetUnit); setRevision(saved.revision); setNotice(`客户充值模式已保存：${saved.mode === 'ORGANIZATION' ? '默认充值到单位' : '按用户授权'}`); policy.refresh(); policyOverview.refresh(); publishObjectChange('notifications') }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  async function addGrant(event: FormEvent) {
    event.preventDefault(); if (!canWrite || !targetUnit || busy) return; setError(''); setNotice('')
    if (!canReadUsers || !selectedCustomer || users.loading || users.error != null) { setError('请明确选择当前单位的客户用户'); return }
    if (ownerType === 'PLACE' && (!selectedPlace || places.loading || places.error != null)) { setError('请明确选择当前单位的充值位置'); return }
    if (!window.confirm(`为 ${selectedCustomer.name} 新增充值授权：${ownerType === 'ORGANIZATION' ? '单位账户' : `位置账户 ${selectedPlace!.label}`}。仅修改该用户可选的充值账户，不充值、不产生订单。继续？`)) return
    setBusy(true)
    try { await api.addNotificationRechargeGrant({ user_id: selectedCustomer.id, owner_type: ownerType, owner_id: ownerType === 'ORGANIZATION' ? targetUnit : selectedPlace!.id }, undefined, targetUnit); setNotice('授权记录已添加；仅在“按用户授权”模式下影响该用户可选的充值账户。'); grants.refresh(); grantOverview.refresh(); publishObjectChange('notifications') }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  async function revoke(grant: NotificationRechargeGrant) {
    if (!canWrite || !targetUnit || busy) return; setError(''); setNotice('')
    if (!window.confirm(`撤销 ${grant.user_name} 的${accountLabel(grant)}充值授权？不取消已有订单或退款。继续？`)) return
    setBusy(true)
    try { await api.revokeNotificationRechargeGrant(grant.user_id, grant.owner_type, grant.owner_id, undefined, targetUnit); setNotice('充值授权记录已撤销；按用户授权模式的可选账户将更新，不取消已有订单或退款。'); grants.refresh(); grantOverview.refresh(); publishObjectChange('notifications') }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <><PageHeading eyebrow="NOTIFICATION · 客户充值" title="客户充值设置" description="配置客户能否充值到单位账户，或只允许指定用户充值到指定位置。">
      <TargetUnitSelect audience="management" permissions={permissions} value={targetUnit} onChange={unit => { setTargetUnit(unit); setUserId(''); setOwnerType('ORGANIZATION'); setOwnerId('') }} disabled={busy}/>
    </PageHeading>
    <details className="secondary-controls"><summary>跨单位充值配置与授权概览</summary>
    <Card><CardContent><h2 className="section-heading">所有授权单位充值配置概览</h2>{policyOverview.loading ? <Loading/> : policyOverview.error != null ? <ErrorNotice error={policyOverview.error} retry={policyOverview.refresh}/> : policyOverview.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>充值模式</TableHead><TableHead>版本</TableHead></TableRow></TableHeader><TableBody>{policyOverview.data.items.map(item => <TableRow key={item.tenant_id}><TableCell>{item.tenant_name}<div className="table-sub mono">{item.tenant_id}</div></TableCell><TableCell>{item.mode === 'ORGANIZATION' ? '充值到单位' : '按用户授权'}</TableCell><TableCell>{item.revision}</TableCell></TableRow>)}</TableBody></Table><Pager page={policyOverview.data.page} size={policyOverview.data.size} total={policyOverview.data.total} onChange={setPolicyOverviewPage}/></> : <Empty title="当前树选择范围内没有充值配置"/>}<h3 className="section-heading">授权账户</h3>{grantOverview.loading ? <Loading/> : grantOverview.error != null ? <ErrorNotice error={grantOverview.error} retry={grantOverview.refresh}/> : grantOverview.data?.items.length ? <><Table><TableHeader><TableRow><TableHead>单位</TableHead><TableHead>客户用户</TableHead><TableHead>账户</TableHead><TableHead>类型</TableHead><TableHead>授权时间</TableHead></TableRow></TableHeader><TableBody>{grantOverview.data.items.map((grant, index) => <TableRow key={`${grant.tenant_id}:${grant.user_id}:${grant.owner_type}:${grant.owner_id}:${index}`}><TableCell>{grant.tenant_name}<div className="table-sub mono">{grant.tenant_id}</div></TableCell><TableCell>{grant.user_name}<div className="table-sub mono">{grant.username}</div></TableCell><TableCell>{grant.owner_name}<div className="table-sub mono">{grant.owner_id}</div></TableCell><TableCell>{grant.owner_type === 'ORGANIZATION' ? '单位' : '位置'}</TableCell><TableCell>{grant.created_at}</TableCell></TableRow>)}</TableBody></Table><Pager page={grantOverview.data.page} size={grantOverview.data.size} total={grantOverview.data.total} onChange={setGrantOverviewPage}/></> : <Empty title="当前树选择范围内没有充值授权"/>}<p className="muted-note">选择一个单位后才显示配置表单；新增、撤销及模式修改始终只作用于当前明确单位。</p></CardContent></Card>
    </details>
    <div className="notice"><Coins size={15}/>默认模式为“充值到单位”：该单位所有客户用户都可以选择单位账户充值。切换为“按用户授权”后，用户只能看到被明确授权的账户。</div>
    {targetUnit && <p className="muted-note">当前配置单位：{targetUnit}；本页只控制客户可充值账户，不改变设备可见范围或通知收件人。按用户授权仅在 GRANTS 模式生效。</p>}
    {policy.loading ? <Loading/> : policy.error != null ? <ErrorNotice error={policy.error} retry={policy.refresh}/> : policy.data && <Card><CardContent><h2 className="section-heading">充值模式</h2>
      <form className="provider-grid" onSubmit={savePolicy}>
        <div className="form-stack"><div className="field"><Label htmlFor="recharge-mode">模式</Label><select id="recharge-mode" className="native-select" value={mode} onChange={e => setMode(e.target.value as NotificationRechargeMode)} disabled={!canWrite || busy}><option value="ORGANIZATION">默认充值到单位（所有客户用户）</option><option value="GRANTS">按用户授权（单位或位置）</option></select></div><div className="table-sub">版本 {revision} · 撤销账户授权不取消已有充值订单或退款。</div></div>
        <div className="form-stack"><Button disabled={busy || !canWrite} type="submit">保存充值模式</Button></div>
      </form></CardContent></Card>}
    <Card><CardContent><h2 className="section-heading"><Users size={18}/>客户充值授权</h2>
      <form className="provider-grid" onSubmit={addGrant}>
        <div className="form-stack"><section className="form-stack" aria-label="选择客户用户">
          <div className="field"><Label htmlFor="recharge-user-search">搜索本单位客户</Label><Input id="recharge-user-search" value={userDraft} onChange={event => setUserDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); searchUsers() } }} disabled={!canWrite || !canReadUsers || busy} placeholder="按客户姓名或账号查询"/></div>
          <Button type="button" variant="outline" disabled={!canWrite || !canReadUsers || busy} onClick={searchUsers}>查询客户</Button>
          <div className="field"><Label htmlFor="recharge-grant-user">客户用户</Label><select id="recharge-grant-user" className="native-select" value={userId} onChange={event => setUserId(event.target.value)} disabled={!canWrite || !canReadUsers || busy || users.loading || users.error != null}><option value="">请明确选择本单位客户</option>{customerUsers.map(item => <option key={item.id} value={item.id}>{item.name}（{item.username}）{item.enabled === false ? ' · 已停用' : ''}</option>)}</select></div>
          {!canReadUsers ? <p className="muted-note">选择客户需要客户用户读取权限，请联系管理员。</p> : users.loading ? <Loading/> : users.error != null ? <ErrorNotice error={users.error} retry={users.refresh}/> : users.data && <><small className="muted-note">客户第 {users.data.page} 页；搜索或翻页会清除原选择，避免授权给旧客户。</small>{users.data.total > users.data.size && <Pager page={users.data.page} size={users.data.size} total={users.data.total} onChange={userPageChanged}/>}</>}
          {selectedCustomer && Array.isArray(selectedCustomer.permissions) && (!selectedCustomer.permissions.includes('notification-recharge:read') || !selectedCustomer.permissions.includes('notification-recharge:write')) && <p className="notice">{!selectedCustomer.permissions.includes('notification-recharge:read') ? '该客户尚无通知充值页面读取权限' : '该客户当前只能查看通知充值，不能新建充值订单'}；本页只分配账户，页面和下单权限需在用户管理中配置。</p>}
        </section>
        <div className="field"><Label htmlFor="recharge-grant-owner-type">充值账户类型</Label><select id="recharge-grant-owner-type" className="native-select" value={ownerType} onChange={event => { setOwnerType(event.target.value as 'ORGANIZATION' | 'PLACE'); setOwnerId('') }} disabled={!canWrite || busy}><option value="ORGANIZATION">单位账户</option><option value="PLACE">位置账户</option></select></div>
        {ownerType === 'PLACE' && <div className="field"><Label htmlFor="recharge-grant-place">位置</Label><select id="recharge-grant-place" className="native-select" value={ownerId} onChange={event => setOwnerId(event.target.value)} disabled={!canWrite || busy || places.loading || places.error != null}><option value="">请明确选择本单位位置</option>{placeChoices.map(place => <option key={place.id} value={place.id}>{place.label} · {place.id}</option>)}</select>{places.error != null && <ErrorNotice error={places.error} retry={places.refresh}/>}</div>}
        </div>
        <div className="form-stack"><Button disabled={busy || !canWrite || !canReadUsers} type="submit">新增授权</Button></div>
      </form>
      {grants.loading ? <Loading/> : grants.error != null ? <ErrorNotice error={grants.error} retry={grants.refresh}/> : grants.data?.length ? <section className="settings-grid" aria-label="本单位充值授权">
        {grants.data.map(grant => <article className="recharge-grant-card" key={`${grant.user_id}:${grant.owner_type}:${grant.owner_id}`} aria-label={`${grant.user_name}的${accountLabel(grant)}充值授权`}>
          <h3>{grant.user_name}</h3><p className="table-sub mono">{grant.username}</p><p>{grant.owner_type === 'ORGANIZATION' ? '单位账户' : '位置账户'} · {accountLabel(grant)}</p>
          <div className="recharge-grant-actions"><details><summary>授权信息</summary><p className="muted-note">{grant.created_at}</p><p className="mono">{grant.user_id} · {grant.owner_id}</p></details><Button type="button" variant="outline" size="sm" disabled={busy || !canWrite} onClick={() => void revoke(grant)} aria-label={`撤销${grant.user_name}的${accountLabel(grant)}充值授权`}><Trash2/>撤销</Button></div>
        </article>)}
      </section> : <Empty title="暂无授权"/>}
    </CardContent></Card>
    {notice && <div role="status" className="notice success-note">{notice}</div>}{error && <div role="alert" className="notice danger-note">{error}</div>}
  </>
}
