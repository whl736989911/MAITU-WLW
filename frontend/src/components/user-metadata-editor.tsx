import './identity-directory.css'
import { useState, type FormEvent } from 'react'
import { identityDirectoryApi, type IdentityDepartment, type IdentityUserProfile } from '@/lib/identity-directory'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'

/**
 * Edits an existing customer account's organization/contact/validity metadata.
 * There is no duty-personnel concept here: validity, department, phone and email stay independent
 * from roles, permissions and device scope, and the existing valid_until is preserved unless this
 * form explicitly changes it.
 */
export function UserMetadataEditor({ value, departments, tenantId, onClose, onSaved }: {
  value: IdentityUserProfile
  departments: IdentityDepartment[]
  tenantId?: string
  onClose: () => void
  onSaved: () => void
}) {
  const profile = value
  const [phone, setPhone] = useState(profile.phone), [email, setEmail] = useState(profile.contact_email)
  const [department, setDepartment] = useState(profile.department_id ?? ''), [longTerm, setLongTerm] = useState(profile.long_term)
  const [validUntil, setValidUntil] = useState(profile.long_term || !profile.valid_until ? '' : profile.valid_until.slice(0, 10))
  const [error, setError] = useState<unknown>(), [busy, setBusy] = useState(false)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!tenantId) { setError(new Error('缺少目标用户所属单位')); return }
    if (!longTerm && !validUntil) { setError(new Error('非长期有效必须选择截止日期')); return }
    if (!window.confirm(`确认保存 ${profile.name} 的组织、联系方式与账号有效期？`)) return
    const body = { department_id: department || null, phone: phone.trim(), contact_email: email.trim(), long_term: longTerm, valid_until: longTerm ? null : `${validUntil}T23:59:59Z`, expected_revision: profile.revision }
    setBusy(true); setError(undefined)
    void identityDirectoryApi.updateUser(profile.id, body, crypto.randomUUID(), undefined, tenantId)
      .then(onSaved)
      .catch(setError)
      .finally(() => setBusy(false))
  }
  return <div className="modal-backdrop"><form className="identity-dialog form-stack" onSubmit={submit}>
    <h2>组织资料与账号有效期 · {profile.name}</h2>
    <p className="muted-note">账号有效期与启停状态独立于角色和设备范围；本表单未提交时不会改写现有有效期。</p>
    <div className="field"><Label>所属部门</Label><select className="select-control" value={department} onChange={event => setDepartment(event.target.value)}><option value="">未分配</option>{departments.filter(item => item.enabled).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></div>
    <div className="field"><Label>联系电话</Label><Input value={phone} maxLength={18} onChange={event => setPhone(event.target.value)}/></div>
    <div className="field"><Label>联系邮箱</Label><Input type="email" value={email} maxLength={254} onChange={event => setEmail(event.target.value)}/></div>
    <label className="toggle-field"><input type="checkbox" checked={longTerm} onChange={event => setLongTerm(event.target.checked)}/>长期有效</label>
    {!longTerm && <div className="field"><Label>有效截止日期</Label><Input type="date" value={validUntil} onChange={event => setValidUntil(event.target.value)} required/></div>}
    {error != null && <ErrorNotice error={error}/>}
    <div className="form-actions"><Button type="button" variant="outline" onClick={onClose} disabled={busy}>取消</Button><Button type="submit" disabled={busy}>{busy ? '保存中…' : '确认保存'}</Button></div>
  </form></div>
}
