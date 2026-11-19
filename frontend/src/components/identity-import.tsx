import { useRef, useState } from 'react'
import { ApiError, api } from '@/lib/api'
import type { PermissionCatalog, UserCreate } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'

type Row = { key: string; data: UserCreate; state: 'READY'|'SENDING'|'UNKNOWN'|'CREATED'|'REJECTED'; message: string }
function csvRows(text: string): string[][] {
  const rows: string[][]=[]; let row:string[]=[],cell='',quoted=false
  for(let index=0;index<text.length;index++){
    const char=text[index]
    if(quoted){if(char==='"'&&text[index+1]==='"'){cell+='"';index++}else if(char==='"')quoted=false;else cell+=char;continue}
    if(char==='"'){if(cell.length)throw new Error('CSV 引号只能出现在单元格开头');quoted=true;continue}
    if(char===','){row.push(cell);cell='';continue}
    if(char==='\n'||char==='\r'){if(char==='\r'&&text[index+1]==='\n')index++;row.push(cell);if(row.some(value=>value.length))rows.push(row);row=[];cell='';continue}
    cell+=char
  }
  if(quoted)throw new Error('CSV 存在未闭合引号')
  row.push(cell);if(row.some(value=>value.length))rows.push(row)
  return rows
}
export function IdentityImport({platform,catalog,tenantId,disabled,onImported}:{platform:boolean;catalog:PermissionCatalog;tenantId?:string;disabled:boolean;onImported:()=>void}){
  const input=useRef<HTMLInputElement>(null),[rows,setRows]=useState<Row[]>([]),[error,setError]=useState<unknown>(),[busy,setBusy]=useState(false)
  async function load(file?:File){if(!file)return;setError(undefined);setRows([]);if(file.size>1_000_000){setError(new Error('导入文件不能超过 1 MB'));return}try{
    const parsed=csvRows(await file.text());if(!parsed.length)throw new Error('CSV 文件为空')
    const header=parsed[0].map(value=>value.trim());header[0]=header[0].replace(/^\uFEFF/,'');const required=['username','name','initial_password','permissions'];if(header.length!==required.length+1||required.some((key,index)=>header[index]!==key)||header[4]!=='tenant_ids')throw new Error('CSV 首行必须严格为 username,name,initial_password,permissions,tenant_ids')
    if(parsed.length-1>100)throw new Error('单次最多导入 100 个账号')
    const validPermissions=new Set(catalog.items.filter(item=>item.user_types.includes(platform?'PLATFORM':'CUSTOMER')&&item.delegable).map(item=>item.key));const names=new Set<string>();const preview:Row[]=[]
    for(const values of parsed.slice(1)){if(values.length!==header.length)throw new Error('CSV 每行必须包含 5 个字段');const [username,name,initial_password,rawPermissions,rawTenants]=values;const normalized=username.trim();if(names.has(normalized.toLowerCase()))throw new Error(`文件内用户名重复：${normalized}`);names.add(normalized.toLowerCase());const permissions=[...new Set(rawPermissions.split('|').map(value=>value.trim()).filter(Boolean))];const tenant_ids=[...new Set(rawTenants.split('|').map(value=>value.trim()).filter(Boolean))];const badPermission=permissions.find(permission=>!validPermissions.has(permission));const invalidPassword=initial_password.length<catalog.password_policy.min_length||initial_password.length>catalog.password_policy.max_length;const message=!normalized||!name.trim()?'用户名和姓名不能为空':badPermission?`权限不可委派或不属于此账号类型：${badPermission}`:invalidPassword?`初始密码须为 ${catalog.password_policy.min_length}–${catalog.password_policy.max_length} 个字符`:'';preview.push({key:crypto.randomUUID(),data:{username:normalized,name:name.trim(),initial_password,permissions,...(platform?{tenant_ids}:{})},state:message?'REJECTED':'READY',message})}
    setRows(preview);if(input.current)input.current.value=''
  }catch(reason){setError(reason);if(input.current)input.current.value=''}}
  async function submit(retryUnknown=false){if(disabled||busy)return;if(!platform&&!tenantId){setError(new Error('请先明确本次导入的目标单位'));return}const pending=rows.filter(row=>retryUnknown?row.state==='UNKNOWN':row.state==='READY');if(!pending.length){setError(new Error(retryUnknown?'没有待确认的未知结果':'没有有效的待导入行'));return}if(!retryUnknown&&!window.confirm(`即将逐条创建 ${pending.length} 个账号。每个账号首次登录必须修改密码；CSV 中的初始密码不会写入导出文件。继续？`))return
    setBusy(true);setError(undefined)
    for(const row of pending){setRows(current=>current.map(item=>item.key===row.key?{...item,state:'SENDING',message:''}:item));const body={...row.data};try{await api.createUser(platform,body,row.key,undefined,platform?undefined:tenantId);setRows(current=>current.map(item=>item.key===row.key?{...item,data:{...item.data,initial_password:''},state:'CREATED',message:'已创建；初始密码已清除'}:item))}catch(reason){if(reason instanceof ApiError&&(reason.status===0||reason.status>=500)){setRows(current=>current.map(item=>item.key===row.key?{...item,state:'UNKNOWN',message:'结果未确认。该行已冻结；仅可使用相同幂等键重试确认。'}:item));break}setRows(current=>current.map(item=>item.key===row.key?{...item,state:'REJECTED',message:reason instanceof Error?reason.message:'服务端拒绝'}:item))}}
    setBusy(false);onImported()
  }
  return <details className="field permission-field"><summary>安全导入账号（CSV）</summary><p className="muted-note">格式：username,name,initial_password,permissions,tenant_ids；permissions 与平台 tenant_ids 用竖线分隔。该流程使用现有账号创建 API、当前授权目录和每行唯一幂等键；不导入角色或隐式权限。初始密码不得经导出或消息发送。</p><div className="field"><Label htmlFor={`identity-import-${platform?'platform':'customer'}`}>选择 CSV（最多 100 行）</Label><Input ref={input} id={`identity-import-${platform?'platform':'customer'}`} type="file" accept=".csv,text/csv" disabled={disabled||busy} onChange={event=>void load(event.target.files?.[0])}/></div>{rows.length>0&&<><div className="notice">预览 {rows.length} 行。密码列仅保留在当前页面内存，关闭页面后不可恢复。失败行不会自动重试；未知结果保留原幂等键和原始业务内容。</div><div className="permission-grid">{rows.map((row,index)=><div key={row.key} className="notice"><strong>#{index+1} {row.data.name} · {row.data.username}</strong><div>{row.state==='READY'?'待导入':row.state==='SENDING'?'提交中':row.state==='UNKNOWN'?'结果未知':row.state==='CREATED'?'已创建':'拒绝'}</div>{row.message&&<div>{row.message}</div>}</div>)}</div><div className="form-actions"><Button type="button" disabled={disabled||busy||!rows.some(row=>row.state==='READY')} onClick={()=>void submit(false)}>导入有效账号</Button><Button type="button" variant="outline" disabled={disabled||busy||!rows.some(row=>row.state==='UNKNOWN')} onClick={()=>void submit(true)}>用原幂等键确认未知行</Button></div></>}{error!=null&&<ErrorNotice error={error}/>}</details>
}
