import { useState, type FormEvent } from 'react'
import { Archive, ArchiveRestore } from 'lucide-react'
import type { Product } from '@/lib/types'
import { archiveProduct, reactivateProduct, updateProductMetadata } from '@/lib/product-metadata'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { ErrorNotice } from './common'
import { OperationConfirmation, useMutationAction } from './identity-common'

export type ProductLifecycleAction = 'archive' | 'reactivate'

export function ProductMetadataEditor({ product, onClose, onSaved, onRefresh }: { product: Product; onClose: () => void; onSaved: (product: Product) => void; onRefresh: () => void }) {
  const [name, setName] = useState(product.name)
  const [description, setDescription] = useState(product.description)
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim()) { action.setError(new Error('请填写产品名称')); return }
    if (!reason.trim() || reason.trim().length > 1000) { action.setError(new Error('请填写1000字符以内的变更原因')); return }
    let result: Product | null = null
    void action.run(async (key, signal) => { result = await updateProductMetadata(product.id, { name: name.trim(), description: description.trim(), expected_revision: product.revision, reason: reason.trim() }, key, signal) }, () => { if (result) { onSaved(result); onRefresh() } })
  }
  return <>
    <form className="form-stack" onSubmit={submit}>
      
        <div className="identity-readonly">稳定身份（不可修改）：{product.manufacturer} / {product.model} · 当前 revision {product.revision}</div>
        <div className="field"><Label htmlFor="product-edit-name">产品名称</Label><Input id="product-edit-name" required value={name} onChange={event => { setName(event.target.value); action.touch() }}/></div>
        <div className="field"><Label htmlFor="product-edit-description">产品描述</Label><textarea id="product-edit-description" rows={3} value={description} onChange={event => { setDescription(event.target.value); action.touch() }}/></div>
        <div className="field"><Label htmlFor="product-edit-reason">变更原因</Label><Input id="product-edit-reason" required maxLength={1000} value={reason} onChange={event => { setReason(event.target.value); action.touch() }}/><p className="muted-note">原因写入审计。厂商与型号不可修改；已发布版本、测试证据和历史不因名称变更而改写。</p></div>
        {action.error != null && <ErrorNotice error={action.error}/>}
        <div className="identity-form-actions"><Button type="submit" disabled={action.busy}>{action.busy ? '正在保存…' : '保存产品元数据'}</Button><Button type="button" variant="outline" onClick={onClose}>取消</Button></div>
    </form>
  </>
}
export function ProductLifecycleEditor({ product, action: lifecycle, onClose, onSaved, onRefresh }: { product: Product; action: ProductLifecycleAction; onClose: () => void; onSaved: (product: Product) => void; onRefresh: () => void }) {
  const [reason, setReason] = useState('')
  const action = useMutationAction()
  const archiving = lifecycle === 'archive'
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim() || reason.trim().length > 1000) { action.setError(new Error('请填写1000字符以内的操作原因')); return }
    if (!action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    let result: Product | null = null
    void action.run(async (key, signal) => {
      const body = { expected_revision: product.revision, reason: reason.trim() }
      result = archiving ? await archiveProduct(product.id, body, key, signal) : await reactivateProduct(product.id, body, key, signal)
    }, () => { if (result) onSaved(result) })
  }
  return <form className="form-stack" onSubmit={submit}>
    {!action.confirming ? <>
      <div className="identity-readonly">{product.name} · {product.manufacturer} / {product.model} · revision {product.revision}</div>
      <div className="notice">{archiving ? '归档后不能发布新版本、创建/复制草稿或新增设备绑定，但既有已发布版本、历史、存量解析和迁移继续可用；不会删除任何数据。' : '启用后恢复发布新版本与新增设备绑定的能力；不会自动创建版本或改动任何设备。'}</div>
      <div className="field"><Label htmlFor="product-lifecycle-reason">操作原因</Label><Input id="product-lifecycle-reason" required maxLength={1000} value={reason} onChange={event => { setReason(event.target.value); action.touch() }}/></div>
      {action.error != null && <ErrorNotice error={action.error}/>}
      <div className="identity-form-actions"><Button type="submit">{archiving ? <><Archive/>核对并归档</> : <><ArchiveRestore/>核对并启用</>}</Button><Button type="button" variant="outline" onClick={onClose}>取消</Button></div>
    </> : <OperationConfirmation action={action} title={archiving ? '二次确认 · 归档产品' : '二次确认 · 启用产品'} submitLabel={archiving ? '确认归档产品' : '确认启用产品'} refresh={onRefresh} warning={archiving ? '归档不删除版本、证据、设备绑定或历史；只阻止新的发布与新增绑定。' : '启用不会自动发布版本或改变设备绑定。'}><p>{product.name} · {product.id}</p><p>当前 revision {product.revision}</p><p>操作：{archiving ? '归档' : '启用'}</p></OperationConfirmation>}
  </form>
}
