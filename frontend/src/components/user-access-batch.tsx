import { useState, type FormEvent } from 'react'
import { ApiError } from '@/lib/api'
import { ListChecks } from 'lucide-react'
import { accessApi } from '@/lib/access-roles'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ErrorNotice, Loading, Pager } from '@/components/common'
import { OperationConfirmation, useMutationAction } from '@/components/identity-common'
import type { ManagedUser } from '@/lib/types'

type Mode='ROLES'|'SCOPE'
type Outcome={user_id:string;name:string;status:'UPDATED'|'UNCHANGED'|'REJECTED'|'UNKNOWN';message:string}
export function UserAccessBatch({users,platform,onCompleted}:{users:ManagedUser[];platform:boolean;onCompleted:()=>void}){
  const session=useSession(),action=useMutationAction()
  const [selected,setSelected]=useState<Set<string>>(new Set()),[mode,setMode]=useState<Mode>('ROLES'),[roleAction,setRoleAction]=useState<'ADD'|'REMOVE'|'REPLACE'>('REPLACE'),[roleIds,setRoleIds]=useState<string[]>([]),[rolePage,setRolePage]=useState(1),[scopeTenant,setScopeTenant]=useState(''),[scopeMode,setScopeMode]=useState<'ALL_TENANT'|'DEVICE_SET'|'PLACE_SUBTREE'>('DEVICE_SET'),[deviceIds,setDeviceIds]=useState(''),[placeIds,setPlaceIds]=useState(''),[reason,setReason]=useState(''),[report,setReport]=useState<Outcome[]>([])
  const assignRoles=platform?session.permissions.includes('platform-users:assign-roles'):session.permissions.includes('users:assign-roles')
  const assignScope=platform?session.permissions.includes('platform-users:assign-scope'):session.permissions.includes('users:assign-scope')
  const roles=useResource(`batch-assignable-roles:${platform?'PLATFORM':'CUSTOMER'}:${session.audience}:${rolePage}`,signal=>session.audience==='customer'?accessApi.customerRoles(rolePage,100,signal):accessApi.roles(platform?'PLATFORM':'CUSTOMER',rolePage,100,signal),mode==='ROLES'&&assignRoles)
  const targets=users.filter(user=>selected.has(user.id))
  const sameTenant=targets.length>0&&targets.every(user=>user.tenant_id===targets[0]?.tenant_id)
  const tenantEligible=platform?targets.length>0&&targets.every(user=>user.tenant_ids.includes(scopeTenant)):sameTenant&&!!targets[0]?.tenant_id
  function toggle(id:string,checked:boolean){setSelected(old=>{const next=new Set(old);if(checked)next.add(id);else next.delete(id);return next});action.touch()}
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!targets.length || targets.length > 100 || !reason.trim()) { action.setError(new Error('选择1–100个账号并填写变更理由')); return }
    if (mode === 'ROLES' && (!assignRoles || (roleAction !== 'REPLACE' && !roleIds.length) || roles.loading || roles.error)) { action.setError(new Error('角色批量调整缺少可用权限或已加载角色')); return }
    if (mode === 'SCOPE' && (!assignScope || !tenantEligible)) { action.setError(new Error('设备范围批量调整要求目标单位可授权；DEVICE_SET允许明确空集（无设备访问权）')); return }
    const ids = scopeMode === 'DEVICE_SET' ? [...new Set(deviceIds.split(/[\s,，;；]+/).filter(Boolean))] : []
    const places = scopeMode === 'PLACE_SUBTREE' ? [...new Set(placeIds.split(/[\s,，;；]+/).filter(Boolean))] : []
    if (mode === 'SCOPE' && scopeMode === 'PLACE_SUBTREE' && !places.length) { action.setError(new Error('动态位置范围必须至少指定一个位置根')); return }
    if (!action.confirming) { setReport([]); action.setConfirming(true); return }
    void action.run(async (key, signal) => {
      const outcome: Outcome[] = []
      for (const target of targets) {
        if (signal.aborted) break
        if (target.is_protected || target.id === session.user?.id) {
          outcome.push({ user_id: target.id, name: target.name, status: 'REJECTED', message: '当前账号或受保护账号禁止管理' }); setReport([...outcome]); continue
        }
        const tenant = mode === 'SCOPE' ? platform ? scopeTenant : target.tenant_id ?? '' : target.tenant_id ?? undefined
        const perKey = `${key}-${target.id}`
        try {
          if (mode === 'ROLES') {
            const current = await accessApi.userAccess(platform, target.id, signal, tenant)
            const existing = current.roles.map(role => role.id), chosen = new Set(roleIds)
            const next = roleAction === 'REPLACE' ? [...chosen] : roleAction === 'ADD' ? [...new Set([...existing, ...chosen])] : existing.filter(id => !chosen.has(id))
            if (next.length === existing.length && next.every(id => existing.includes(id))) {
              outcome.push({ user_id: target.id, name: target.name, status: 'UNCHANGED', message: '目标角色集合已一致' }); setReport([...outcome]); continue
            }
            await accessApi.assignRoles(platform, target.id, { role_ids: next, expected_revision: current.revision, reason: reason.trim() }, perKey, signal, tenant)
          } else {
            if (!tenant) throw new Error('目标单位缺失')
            if (platform && !target.tenant_ids.includes(tenant)) throw new Error('该平台用户未获此单位授权')
            const current = await accessApi.userScope(platform, target.id, signal, tenant)
            if (current.mode === scopeMode && current.device_ids.length === ids.length && current.device_ids.every(id => ids.includes(id))
              && current.place_ids.length === places.length && current.place_ids.every(id => places.includes(id))) {
              outcome.push({ user_id: target.id, name: target.name, status: 'UNCHANGED', message: '目标设备范围已一致' }); setReport([...outcome]); continue
            }
            await accessApi.updateUserScope(platform, target.id, { mode: scopeMode, device_ids: ids, place_ids: places, expected_revision: current.revision, reason: reason.trim() }, perKey, signal, tenant)
          }
          outcome.push({ user_id: target.id, name: target.name, status: 'UPDATED', message: '服务端已按该账号当前revision接受变更' }); setReport([...outcome])
        } catch (error) {
          const unknown = !(error instanceof ApiError && error.status >= 400 && error.status < 500)
          outcome.push({ user_id: target.id, name: target.name, status: unknown ? 'UNKNOWN' : 'REJECTED', message: error instanceof Error ? error.message : '请求结果未知' }); setReport([...outcome])
        }
      }
      return outcome
    }, () => { action.touch(); onCompleted() })
  }
  const availableTenants=platform?[...new Set(targets.flatMap(user=>user.tenant_ids))]:[...new Set(targets.map(user=>user.tenant_id).filter((id):id is string=>!!id))]
  return <section className="list-card">
    <div className="list-toolbar"><strong><ListChecks size={17}/>用户角色 / 设备范围批量调整</strong><span>{selected.size} 已选 / 本页 {users.length}；最多100个账号/次</span></div>
    <div className="permission-grid">{users.map(user=><label key={user.id}><input type="checkbox" disabled={user.is_protected||user.id===session.user?.id||action.busy||action.confirming} checked={selected.has(user.id)} onChange={event=>toggle(user.id,event.target.checked)}/><span>{user.name}<small>{user.username} · {user.tenant_name??user.tenant_id??'平台账号'}</small></span></label>)}</div>
    <form className="form-stack" onSubmit={submit}>
      <fieldset disabled={action.busy||action.confirming} className="form-stack">
        <div className="filter-row"><label className="field">调整对象<select value={mode} onChange={event=>{setMode(event.target.value as Mode);action.touch()}}><option value="ROLES">角色</option><option value="SCOPE">设备范围</option></select></label>{mode==='ROLES'&&<><label className="field">角色操作<select value={roleAction} onChange={event=>{setRoleAction(event.target.value as typeof roleAction);action.touch()}}><option value="ADD">增授所选角色</option><option value="REMOVE">移除所选角色</option><option value="REPLACE">替换为所选角色（可清空）</option></select></label><fieldset className="field permission-field"><legend>角色（第 {roles.data?.page ?? rolePage} 页；{roles.data?.total ?? 0} 个）</legend>{roles.loading?<Loading/>:roles.error!=null?<ErrorNotice error={roles.error} retry={roles.refresh}/>:roles.data?.items.map(role=><label key={role.id}><input type="checkbox" checked={roleIds.includes(role.id)} disabled={!role.enabled||role.deleted} onChange={event=>{setRoleIds(event.target.checked?[...roleIds,role.id]:roleIds.filter(id=>id!==role.id));action.touch()}}/><span>{role.name}<small>{role.code}</small></span></label>)}{roles.data&&roles.data.total>roles.data.size&&<Pager page={roles.data.page} size={roles.data.size} total={roles.data.total} onChange={setRolePage}/>}</fieldset></>}</div>
        {mode==='SCOPE'&&<><label className="field">目标单位<select required value={scopeTenant} onChange={event=>{setScopeTenant(event.target.value);action.touch()}}><option value="">请选择</option>{availableTenants.map(id=><option key={id} value={id}>{id}</option>)}</select></label><label><input type="radio" checked={scopeMode==='ALL_TENANT'} onChange={()=>{setScopeMode('ALL_TENANT');action.touch()}}/>ALL_TENANT · 全单位设备</label><label><input type="radio" checked={scopeMode==='DEVICE_SET'} onChange={()=>{setScopeMode('DEVICE_SET');action.touch()}}/>DEVICE_SET · 明确设备集合</label><label><input type="radio" checked={scopeMode==='PLACE_SUBTREE'} onChange={()=>{setScopeMode('PLACE_SUBTREE');action.touch()}}/>PLACE_SUBTREE · 动态位置子树</label>{scopeMode==='DEVICE_SET'&&<label className="field">设备ID（逐行/逗号；留空表示明确无设备访问权）<textarea className="text-area" rows={4} value={deviceIds} onChange={event=>{setDeviceIds(event.target.value);action.touch()}}/></label>}{scopeMode==='PLACE_SUBTREE'&&<label className="field">位置根 ID（逐行/逗号；包含全部后代及未来设备）<textarea className="text-area" rows={4} value={placeIds} onChange={event=>{setPlaceIds(event.target.value);action.touch()}}/></label>}</>}
        <label className="field">变更理由<Input required maxLength={1000} value={reason} onChange={event=>{setReason(event.target.value);action.touch()}}/></label>
      </fieldset>
      {action.error!=null&&<ErrorNotice error={action.error}/>}
      {!action.confirming&&<Button type="submit" disabled={action.busy||!selected.size||mode==='ROLES'&&!assignRoles||mode==='SCOPE'&&!assignScope}>核对批量操作</Button>}
    </form>
    {action.confirming&&<form className="form-stack" onSubmit={submit}><OperationConfirmation action={action} title="逐账号重新读取并提交CAS" submitLabel="执行批量调整" refresh={()=>{action.touch()}} warning="每个账号分别读取当前授权、校验权限及revision并调用真实服务端命令；成功/拒绝/未知逐项显示。已成功项不会回滚。"><p>模式：{mode==='ROLES'?`${roleAction} · ${roleIds.length}角色`:`${scopeMode} · 单位 ${scopeTenant}`}</p><p>目标：{targets.map(user=>user.name).join('、')}</p><p>理由：{reason}</p></OperationConfirmation></form>}
    {report.length>0&&<div className="notice" role="status"><strong>逐项服务端结果</strong>{report.map(row=><p key={row.user_id}>{row.name} · {row.status} · {row.message}</p>)}</div>}
  </section>
}
