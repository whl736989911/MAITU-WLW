import { useState } from 'react'
import { useResource } from '@/lib/use-resource'
import { vendorDevicesApi, type LegacyRealtimeProfile } from '@/lib/vendor-devices'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Empty, ErrorNotice, Loading, Pager } from '@/components/common'

const PROFILE_PAGE_SIZE = 20

/** Local decoder/model picker shared by single creation and batch creation. */
export function LegacyProfilePicker({ value, onChange, label = '设备型号（必选）', note, idPrefix = 'vendor-device-profile', disabled }: {
  value: LegacyRealtimeProfile | null
  onChange: (profile: LegacyRealtimeProfile | null) => void
  label?: string
  note?: string
  idPrefix?: string
  disabled?: boolean
}) {
  const [page, setPage] = useState(1), [draft, setDraft] = useState(''), [keyword, setKeyword] = useState('')
  const profiles = useResource(`vendor-device-legacy-profiles:${page}:${keyword}`,
    signal => vendorDevicesApi.legacyProfiles(page, PROFILE_PAGE_SIZE, keyword, signal))
  function search() { setPage(1); setKeyword(draft.trim()); onChange(null) }
  return <>
    <div className="field"><Label htmlFor={`${idPrefix}-search`}>搜索型号（可选）</Label><div className="identity-row-actions"><Input id={`${idPrefix}-search`} value={draft} disabled={disabled} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); search() } }} placeholder="型号太多时，可按型号或厂商搜索"/><Button type="button" variant="outline" disabled={disabled} onClick={search}>查询型号</Button></div></div>
    {profiles.loading ? <Loading/> : profiles.error != null ? <ErrorNotice error={profiles.error} retry={profiles.refresh}/> : profiles.data ? profiles.data.items.length ? <>
      <div className="field"><Label htmlFor={`${idPrefix}-select`}>{label}</Label><select id={`${idPrefix}-select`} className="native-select" required value={value?.id ?? ''} disabled={disabled} onChange={event => onChange(profiles.data!.items.find(item => item.id === event.target.value) ?? null)}><option value="">请选择设备型号</option>{profiles.data.items.map(item => <option key={item.id} value={item.id}>{item.name} · {item.manufacturer} / {item.model} · {item.legacy_product_id}{item.decoder_ready ? '' : ' · 解析未就绪'}</option>)}</select>{note && <p className="muted-note">{note}</p>}</div>
      {profiles.data.total > profiles.data.size && <Pager page={profiles.data.page} size={profiles.data.size} total={profiles.data.total} onChange={next => { setPage(next); onChange(null) }}/>}
    </> : <Empty title="没有可用的本地解析定义">维护协议或型号解析定义后再创建直连设备。</Empty> : null}
  </>
}
