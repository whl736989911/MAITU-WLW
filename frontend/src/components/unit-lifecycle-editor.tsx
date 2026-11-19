import { useState, type FormEvent } from 'react'
import { Archive, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'
import { SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { unitDirectoryApi } from '@/lib/unit-directory'
import type { UnitDetails } from '@/lib/unit-directory'

export function UnitLifecycleEditor({ unit, mode, close, saved }: { unit: UnitDetails; mode: 'archive' | 'delete'; close: () => void; saved: () => void }) {
  const [reason,setReason]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState<unknown>()
  async function submit(event:FormEvent){event.preventDefault();if(!reason.trim())return;setBusy(true);setError(undefined);try{const body={expected_revision:unit.revision,reason:reason.trim()};if(mode==='archive')await unitDirectoryApi.archive(unit.id,body,crypto.randomUUID());else await unitDirectoryApi.deleteIfEmpty(unit.id,body,crypto.randomUUID());saved()}catch(failure){setError(failure)}finally{setBusy(false)}}
  return <><SheetHeader><SheetTitle>{mode==='archive'?'归档/停用单位':'删除无引用单位'} · {unit.name}</SheetTitle></SheetHeader><div className="review-body"><form className="form-stack" onSubmit={submit}><p className="notice">{mode==='archive'?'保留单位及业务历史，记录归档原因并停用访问；客户会话撤销。':'仅允许没有任何业务、授权或审计历史引用的单位删除。存在引用时服务端拒绝并要求归档/停用，不会级联删除。'}</p><div className="field"><Label htmlFor="unit-lifecycle-reason">{mode==='archive'?'归档原因':'删除原因'}</Label><Input id="unit-lifecycle-reason" required maxLength={1000} value={reason} onChange={event=>setReason(event.target.value)}/></div>{error!=null&&<ErrorNotice error={error}/>}<div className="identity-form-actions"><Button type="button" variant="outline" onClick={close}>取消</Button><Button type="submit" variant={mode==='delete'?'destructive':'default'} disabled={busy}>{mode==='archive'?<Archive/>:<Trash2/>}{busy?'处理中…':mode==='archive'?'确认归档':'确认删除'}</Button></div></form></div></>
}
