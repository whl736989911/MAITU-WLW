import { useState, type FormEvent } from 'react'
import { Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ErrorNotice } from '@/components/common'
import { useMutationAction } from '@/components/identity-common'
import { identityLifecycleApi } from '@/lib/identity-lifecycle'
import { useSession } from '@/lib/store'
import type { ManagedUser } from '@/lib/types'

export function UserDeleteAction({ user, platform, onSaved }: { user: ManagedUser; platform: boolean; onSaved: () => void }) {
  const session=useSession(),action=useMutationAction()
  const [open,setOpen]=useState(false),[reason,setReason]=useState('')
  const permission=platform?'platform-users:write':'users:write'
  if(session.audience!=='management'||!session.permissions.includes(permission)||user.is_protected||user.id===session.user?.id)return null
  function close(){setOpen(false);setReason('');action.touch()}
  function submit(event:FormEvent){event.preventDefault();if(!reason.trim()){action.setError(new Error('请说明删除原因'));return}void action.run((key,signal)=>identityLifecycleApi.deleteUser(platform,user.id,{expected_revision:user.revision,reason:reason.trim()},key,user.tenant_id??undefined,signal),()=>{close();onSaved()})}
  return <><Button type="button" variant="ghost" size="sm" onClick={()=>setOpen(true)}><Trash2/>删除无历史账号</Button>{open&&<div className="review-overlay" role="dialog" aria-modal="true" aria-label="删除无历史账号"><section className="review-dialog"><h2>删除无历史账号</h2><form className="form-stack" onSubmit={submit}><div className="notice"><strong>目标账号</strong><p>{user.username} · {user.name} · revision {user.revision}</p></div><p className="notice">只有无任何审计/业务历史引用的账号才允许永久删除，删除不可撤销；服务端会再次核对历史引用、保护状态和账号版本。存在历史时请改为停用以保留历史并撤销会话。禁止自删及删除受保护账号。</p><label className="field">删除原因<Input required maxLength={1000} value={reason} onChange={event=>{setReason(event.target.value);action.touch()}}/></label>{action.error!=null&&<ErrorNotice error={action.error}/>}<div className="identity-form-actions"><Button type="button" variant="outline" onClick={close}>取消</Button><Button type="submit" variant="destructive" disabled={action.busy}>{action.busy?'处理中…':'确认删除账号'}</Button></div></form></section></div>}</>
}
