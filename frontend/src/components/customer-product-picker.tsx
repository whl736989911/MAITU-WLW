import { useState } from 'react'
import { Search } from 'lucide-react'
import { customerAssetsApi, type CustomerPublishedOption } from '@/lib/customer-assets'
import { useResource } from '@/lib/use-resource'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import { Empty, ErrorNotice, Loading, Pager } from './common'

/**
 * Customer-safe published-version selector. It exposes only published product name/model/version, never
 * product schemas, parser code, drafts or evidence blobs; the exact published version must be chosen.
 */
export function CustomerPublishedVersionPicker({ value, onChange }: { value: string; onChange: (option: CustomerPublishedOption | null) => void }) {
  const [page, setPage] = useState(1), [draft, setDraft] = useState(''), [keyword, setKeyword] = useState('')
  const options = useResource(`customer-published-versions:${page}:${keyword}`, signal => customerAssetsApi.publishedVersions(page, keyword, 20, signal))
  const items = options.data?.items ?? []
  const selected = items.find(item => item.id === value) ?? null
  function search() { setPage(1); setKeyword(draft.trim()) }
  return <div className="form-stack">
    <div className="field"><Label htmlFor="customer-product-search">已发布产品版本（必选）</Label>
      <div className="search-form"><div className="input-icon"><Search size={16}/><Input id="customer-product-search" value={draft} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); search() } }} placeholder="按产品名称、型号或厂商搜索"/></div><Button type="button" variant="outline" onClick={search}>查询</Button>{keyword && <Button type="button" variant="ghost" onClick={() => { setDraft(''); setKeyword(''); setPage(1) }}>清除</Button>}</div>
      <p className="muted-note">只能选择平台已发布的具体版本；不显示草稿、原始schema、解析代码或默认最新版本。</p>
    </div>
    {options.loading ? <Loading/> : options.error != null ? <ErrorNotice error={options.error} retry={options.refresh}/> : options.data && <>
      {options.data.items.length ? <div className="field"><select id="customer-product-version" className="native-select" value={value} onChange={event => onChange(items.find(item => item.id === event.target.value) ?? null)}><option value="">请显式选择已发布版本</option>{options.data.items.map(item => <option key={item.id} value={item.id}>{item.product_name} · {item.manufacturer} / {item.model} · v{item.version_number}</option>)}</select></div> : <Empty title="没有匹配的已发布产品版本"/>}
      <Pager page={options.data.page} size={options.data.size} total={options.data.total} onChange={next => { setPage(next); onChange(null) }}/>
    </>}
    {selected && <div className="identity-readonly mono">已选：{selected.product_name} · {selected.model} · v{selected.version_number} · {selected.id}</div>}
  </div>
}
